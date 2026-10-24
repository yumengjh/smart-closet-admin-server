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

## Reverse proxy

The container publishes `ADMIN_HOST_PORT` on the host. The static site (a different host) proxies `/api/` to
`http://<backend host>:<port>/api/`; restrict that port to the web host in the firewall. Keep
`APP_AUTH_REFRESH_COOKIE_SECURE=true`, so the site must be served over HTTPS.

## Fish Closet integration

`APP_CLOSET_ENABLED` is `false` after the first deployment. To manage Fish Closet, set the same key id and a secret of at
least 32 bytes on both sides (`APP_CLOSET_KEY_ID`, `APP_CLOSET_SECRET` here; `CLOSET_MANAGEMENT_ENABLED`,
`CLOSET_MANAGEMENT_KEY_ID`, `CLOSET_MANAGEMENT_SECRET` in smart-closet-server) and set `APP_CLOSET_ENABLED=true`, then
`docker compose -p smart-closet-admin up -d` in `$ADMIN_DEPLOY_DIR`. See [closet-integration](../api/closet-integration.md).
