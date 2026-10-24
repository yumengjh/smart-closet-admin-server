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

The container publishes `ADMIN_HOST_PORT` on the host. The admin site (`https://yuda-admin.yumg.cn`, another host) calls
this service directly at `https://yuda-admin-server.yumg.cn`: proxy that domain to `http://127.0.0.1:<port>` (or the
host's address) and restrict the port to the proxy in the firewall. Credentialed CORS must allow the site origin:
`APP_SECURITY_CORS_ALLOWED_ORIGINS=https://yuda-admin.yumg.cn` seeds a new database (`ADMIN_ALLOWED_ORIGINS` on the first
deployment); on an existing database change it through the runtime-config API. The refresh cookie is `SameSite=Strict` and
`Secure`, which works because both names are under `yumg.cn` and served over HTTPS.

## Fish Closet integration

`APP_CLOSET_ENABLED` is `false` after the first deployment. To manage Fish Closet, set the same key id and a secret of at
least 32 bytes on both sides (`APP_CLOSET_KEY_ID`, `APP_CLOSET_SECRET` here; `CLOSET_MANAGEMENT_ENABLED`,
`CLOSET_MANAGEMENT_KEY_ID`, `CLOSET_MANAGEMENT_SECRET` in smart-closet-server) and set `APP_CLOSET_ENABLED=true`, then
`docker compose -p smart-closet-admin up -d` in `$ADMIN_DEPLOY_DIR`. See [closet-integration](../api/closet-integration.md).
