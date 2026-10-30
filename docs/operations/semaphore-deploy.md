# Semaphore deployment (backend host)

Releases of this service are built and deployed by Semaphore (organization `yumg`, project `smart-closet-admin-server`).
GitHub Actions workflows stay in the repository but the image/deploy path below is the one in use.

```text
push a branch / PR      -> .semaphore/semaphore.yml   ./mvnw verify
push a tag vX.Y.Z       -> checks, then release.yml   image to GHCR -> SSH deploy (scripts/ci/deploy.sh)
Promote "Deploy published image" -> deploy.yml        deploy an already published tag (parameter DEPLOY_IMAGE_TAG)
```

## Secrets

Uses the organization secret `deploy` that smart-closet-server already has (same host): `GHCR_USER`, `GHCR_TOKEN`,
`DEPLOY_SSH_HOST`, `DEPLOY_SSH_USER`, `DEPLOY_SSH_PASSWORD` or `DEPLOY_SSH_KEY`, optional `DEPLOY_SSH_PORT`,
`DEPLOY_SSH_KNOWN_HOSTS`. Optional, add to the same secret only when the defaults do not fit:

| Variable | Default | Meaning |
| --- | --- | --- |
| `ADMIN_DEPLOY_DIR` | `/opt/smart-closet-admin` | Directory on the host (never the closet's `DEPLOY_DIR`) |
| `ADMIN_HOST_PORT` | `8081` | Host port published for the reverse proxy; deployment stops if another process holds it |
| `ADMIN_ALLOWED_ORIGINS` | `https://yuda-admin.yumg.cn` | CORS origin seeded into `.env` on the first deployment |

## What the server keeps

`$ADMIN_DEPLOY_DIR` holds `compose.yaml` (refreshed on every deployment), `.env` and `keys/` (created once, never
overwritten). The first deployment generates a random PostgreSQL password, an RSA JWT key pair and a bootstrap
administrator (`admin` with a random password) in `.env` (mode 600); nothing secret appears in CI logs. Read the
initial password on the host, sign in, and change it:

```bash
sudo grep APP_BOOTSTRAP_ADMIN /opt/smart-closet-admin/.env
```

PostgreSQL data and avatar files live in the compose volumes `postgres-data` and `app-data`.

## Reverse proxy and CORS

The container publishes `ADMIN_HOST_PORT` on the host's loopback only (`127.0.0.1`), so the plain-HTTP port is never reachable from outside and every request passes the reverse proxy. The admin site (`https://yuda-admin.yumg.cn`, another host) calls
this service directly at `https://yuda-admin-server.yumg.cn`: proxy that domain to `http://127.0.0.1:<port>`. Credentialed CORS must allow the site origin:
`APP_SECURITY_CORS_ALLOWED_ORIGINS=https://yuda-admin.yumg.cn` seeds a new database (`ADMIN_ALLOWED_ORIGINS` on the first
deployment); on an existing database change it through the runtime-config API. The refresh cookie is `SameSite=Strict` and
`Secure`, which works because both names are under `yumg.cn` and served over HTTPS.

## Fish Closet integration

Run the promotion **Link Fish Closet** (`.semaphore/link-closet.yml`, `scripts/deploy/link-closet.sh`) on a passed `main`
or tag pipeline. It is safe to repeat and, on the host:

1. finds the running Fish Closet `server` container and its compose env file (backed up as `.bak-<time>`);
2. sets `CLOSET_MANAGEMENT_ENABLED=true`, `CLOSET_MANAGEMENT_KEY_ID`, `CLOSET_MANAGEMENT_SECRET` (an existing secret is kept,
   otherwise `openssl rand -base64 48`) and `CLOSET_MANAGEMENT_EXPORT_SALT`, then recreates only the closet `server`
   container with the image tag it already runs (a short interruption; the env file is restored if it does not turn healthy);
3. writes `compose.override.yaml` so the admin app joins the closet's compose network, sets `APP_CLOSET_ENABLED=true`,
   `APP_CLOSET_BASE_URL=http://server:8080` and the same key id and secret in `.env`, and recreates the admin app;
4. checks from inside the admin container that `/api/manage/v1/users` answers `401` to an unsigned request.

The management API stays on the closet's loopback port and the shared compose network. If the public reverse proxy of the
closet forwards `/api/` as a whole, also block `/api/manage/` there (see `docs/MANAGEMENT_API.md` of smart-closet-server):
the HMAC signature proves possession of the key, it does not replace network isolation.
Full route, signature and error-mapping details: [the Fish Closet integration guide](../api/closet-integration.md).
