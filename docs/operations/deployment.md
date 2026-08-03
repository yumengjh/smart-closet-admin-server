# Deployment guide

## Production checklist

- Run Java 21 and the `postgres` profile. PostgreSQL must be reachable using `DB_URL`,
  `DB_USERNAME`, and `DB_PASSWORD`; use a least-privilege application role and supported
  TLS settings for the environment.
- Supply the initial administrator variables only when bootstrap is required. Rotate or
  remove the bootstrap secret after successful creation according to the secret
  manager's policy.
- Configure both `APP_JWT_PRIVATE_KEY_PEM` and `APP_JWT_PUBLIC_KEY_PEM` through the
  deployment secret manager, or mount secret files into the container and configure both
  `APP_JWT_PRIVATE_KEY_PATH` and `APP_JWT_PUBLIC_KEY_PATH`. `compose.yaml` forwards all
  four variables but does not mount host key paths automatically; PEM variables are the
  ready-to-use Compose option, while file paths require an explicit read-only secret volume.
  The `postgres` profile refuses ephemeral JWT keys. Never bake private keys, database
  passwords, tokens, or a populated `.env` into the image.
- Configure exact CORS origins through `APP_SECURITY_CORS_ALLOWED_ORIGINS` when creating
  a database, or through the authorized runtime-config API for an existing database.
  The `postgres` profile refuses to start with wildcard credentialed CORS.
- Keep `APP_AUTH_REFRESH_COOKIE_SECURE=true`. This is the `postgres` profile and Compose
  default; the production safety check refuses an explicit insecure override.
- Keep forwarded-header handling disabled unless trusted proxy CIDRs are explicitly
  configured through `APP_SECURITY_TRUSTED_PROXIES`. The immediate sender must be
  trusted before `X-Forwarded-For` is considered; test the complete load-balancer/proxy chain.
- Set `APP_RESPONSE_POWERED_BY` and `APP_RESPONSE_SERVER` when the deployment should
  use response identities other than the defaults `ASP.NET` and
  `Microsoft-IIS/10.0`. The application adds `Powered-By` and `Server` to every Servlet
  response; values are trimmed, limited to 128 characters, and rejected when they
  contain control characters. Treat them as presentation metadata, not as a security
  control or an authoritative declaration of the implementation stack.
- Decide whether Swagger UI should be exposed. OpenAPI/Swagger exposure is configurable
  in production; protect or disable it according to policy.
- Ensure the runtime identity is non-root and can write only required paths. SQLite
  storage is `/app/data` in the image, but PostgreSQL deployments should not depend on
  container filesystem persistence. The application still needs persistent avatar/file
  storage: mount `/app/data` as `compose.yaml` does, or set `APP_STORAGE_LOCAL_ROOT` to a
  durable volume. A container's writable layer is not an acceptable storage target.
- Run the self-hosted DiceBear API and set `APP_AVATAR_GENERATOR_BASE_URL`; the default
  is `http://localhost:3100` outside containers and `http://host.docker.internal:3100`
  in this Compose setup. The application never falls back to DiceBear's public service.
  Generator failure degrades to cached or built-in avatars and can be retried from the
  runtime management API.
- Verify `com.github.usefulness:webp-imageio` on every production CPU/OS combination.
  WebP decoding uses bundled JNI/libwebp binaries; CI coverage on one architecture does
  not prove another architecture is supported.

The canonical variable names are documented in `.env.example`; deployment manifests must
not invent aliases. Runtime CORS policy remains database-backed. The environment value only
seeds a missing setting on a new database; change an existing database through the authorized
runtime-config API.

## Health probes

Use the public actuator groups, not sensitive actuator endpoints:

```text
Liveness:  GET /actuator/health/liveness
Readiness: GET /actuator/health/readiness
```

Liveness answers whether the process should be restarted. Readiness gates traffic and
should fail while required dependencies or startup work are unavailable. Set initial
delays/timeouts for the platform and migration duration; avoid restart loops during a
legitimate Liquibase rollout. `/api/v1/system/info` exposes non-sensitive build version,
Git commit, and build time where available (`null`, not fabricated values, otherwise).
`/actuator/info`, `/actuator/metrics`, and every other non-health Actuator endpoint require
the `system:config:read` authority in addition to a valid Access Token.

## PostgreSQL backup and recovery

Before schema rollout, take a provider snapshot or a consistent `pg_dump` using an
appropriately privileged backup identity. Encrypt backups, restrict access, define
retention, and monitor success. A backup is not proven until a restore test succeeds in
an isolated database with application smoke tests.

Keep database recovery point/time objectives explicit. Include roles/privileges and
required extensions in recovery runbooks, while storing backup credentials outside the
repository. Do not use `docker compose down --volumes` against data you need to retain.

Avatar metadata and files form one recoverable data set. Back up the database and
`APP_STORAGE_LOCAL_ROOT` within a documented consistency window, retain both with the
same recovery point, and restore both before opening traffic. After restore, inspect
`GET /api/v1/system/storage/report`; missing files and untracked/orphan objects must be
explained or reconciled before declaring recovery complete. The built-in fallback keeps
profiles readable during an outage, but it is not a replacement for uploaded-avatar backups.

## Liquibase rollout

Liquibase is the only schema-authoring mechanism; Hibernate runs with
`ddl-auto: validate`. Review generated SQL against the target PostgreSQL version and
take a backup before risky changes. Prefer backward-compatible expand/migrate/contract
changes:

1. Add nullable/new structures that old and new application versions tolerate.
2. Deploy code that reads/writes the new representation and migrate data in bounded,
   observable batches.
3. Remove old structures only after rollback windows and old replicas are gone.

Run one controlled migration actor or rely on Liquibase locking with deployment
timeouts that prevent multiple rollouts from racing. Never edit an already-applied
changeset; append a corrective changeset. If startup validation fails, stop traffic,
inspect Liquibase state and logs, and choose forward repair or database restore based on
the reviewed runbook—do not bypass validation.

## GHCR publishing

Pushing an exact `vMAJOR.MINOR.PATCH` tag starts both the JAR and image release
workflows. They strip the leading `v` and pass the same version into Maven/Docker, so
`/api/v1/system/info`, generated build metadata, the JAR name, and image tags agree.
Invalid `v*` tags are rejected before a release build.

The image workflow builds once with BuildKit, scans that exact archive with Trivy, then
publishes semantic, `latest`, and commit-SHA tags to GitHub Container Registry.
Ordinary branch pushes and pull requests never receive package publishing credentials.
Workflow permissions are limited to repository read, package write, and the
security-event scope needed by scanning.

Do not build the application image on developer machines as routine validation. Run
Maven verification and static Compose parsing locally, then create and push a release
tag only after review. Observe the remote result with:

```bash
gh run list --workflow image.yml --limit 5
gh run list --workflow release-jar.yml --limit 5
gh run watch <run-id> --compact --exit-status
gh run view <run-id> --log-failed
```

Local Maven builds keep the default `0.0.1-SNAPSHOT`; override it with
`-Drevision=1.2.3` when verifying release metadata without publishing.

For consumers, pin a release by immutable digest rather than a mutable branch tag, then
promote the same digest across environments. Confirm the repository/package visibility
and grant the runtime only pull access. Critical unfixed vulnerabilities fail
publishing unless an explicitly reviewed, time-bounded allowlist documents the reason.

## Generic continuous delivery extension

Publishing an image is the built-in handoff; deploying it is intentionally outside
scope. Add a separate least-privilege environment workflow or external deployment
system that:

1. selects an approved GHCR digest;
2. obtains environment secrets through the platform secret manager;
3. runs a pre-deployment database backup and reviewed Liquibase strategy;
4. deploys through SSH, a webhook, Kubernetes, or a platform-specific API;
5. waits for readiness and runs authentication plus public-announcement smoke checks;
6. rolls back application traffic on failure while respecting database compatibility;
7. records approver, digest, migration version, and outcome.

Use protected environments and short-lived workload identity where available. Do not
put server credentials in pull-request workflows or repository variables visible to
untrusted code.

## Environment-dependent verification

Repository contract checks:

```bash
./mvnw clean verify
git grep -nE 'BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY|password:[[:space:]]+[^$<{]' -- ':!docs/**' ':!.env.example' || true
```

Runtime checks require their dependencies:

```bash
docker compose config
docker compose up --build --wait
curl --fail http://localhost:8080/actuator/health/readiness
curl --fail http://localhost:8080/v3/api-docs >/dev/null
test "$(curl --silent --output /dev/null --write-out '%header{Powered-By}' \
  http://localhost:8080/actuator/health)" = "${APP_RESPONSE_POWERED_BY:-ASP.NET}"
test "$(curl --silent --output /dev/null --write-out '%header{Server}' \
  http://localhost:8080/actuator/health)" = "${APP_RESPONSE_SERVER:-Microsoft-IIS/10.0}"
```

Docker/Compose/Testcontainers results are environment-dependent. Report skipped checks
as such; do not describe them as passed based only on static configuration.
