#!/usr/bin/env bash
# Runs on the backend host over SSH, from the bundle directory uploaded by scripts/ci/deploy.sh.
#
# Environment: IMAGE_TAG, ADMIN_DEPLOY_DIR (default /opt/smart-closet-admin), ADMIN_HOST_PORT (default 8081),
# ADMIN_ALLOWED_ORIGINS (seeded into .env on the first deployment), GHCR_USER (the token arrives on stdin).
#
# First deployment: creates $ADMIN_DEPLOY_DIR/.env (random database password and bootstrap administrator password,
# mode 600) and an RSA key pair under keys/. Later deployments keep both, only compose.yaml, IMAGE_TAG and the
# published port are refreshed. Nothing secret is printed.
set -euo pipefail
BUNDLE=$(cd "$(dirname "$0")" && pwd)
DIR=${ADMIN_DEPLOY_DIR:-/opt/smart-closet-admin}
PORT=${ADMIN_HOST_PORT:-8081}
IMAGE_TAG=${IMAGE_TAG:-latest}
PROJECT=smart-closet-admin
log() { printf '[deploy] %s\n' "$*" >&2; }
die() { printf '[deploy] 错误：%s\n' "$*" >&2; exit 1; }

case "$DIR" in /*/*) ;; *) die "ADMIN_DEPLOY_DIR 太短，拒绝使用：$DIR" ;; esac
[[ "$PORT" =~ ^[0-9]+$ ]] && [ "$PORT" -ge 1024 ] && [ "$PORT" -le 65535 ] || die "ADMIN_HOST_PORT 不合法：$PORT"
[[ "$IMAGE_TAG" =~ ^[A-Za-z0-9_.-]+$ ]] || die "镜像标签不合法：$IMAGE_TAG"

# docker and file operations directly, or through password-less sudo.
SUDO=(); PRE=()
if ! docker info >/dev/null 2>&1; then
  if sudo -n docker info >/dev/null 2>&1; then PRE=(sudo -n); else die "当前用户无法使用 docker，也不能免密 sudo"; fi
fi
[ "$(id -u)" = 0 ] || SUDO=(sudo -n)
dk() { "${PRE[@]}" docker "$@"; }
for tool in curl openssl; do command -v "$tool" >/dev/null || die "服务器缺少 $tool"; done
dk compose version >/dev/null 2>&1 || die "没有 docker compose v2 插件"

# The port must be free, or already ours.
ours=$(dk ps --filter "label=com.docker.compose.project=$PROJECT" --filter "publish=$PORT" -q)
if [ -z "$ours" ] && "${SUDO[@]}" ss -ltn "( sport = :$PORT )" 2>/dev/null | grep -q LISTEN; then
  die "宿主机端口 $PORT 已被占用，请在 Semaphore Secret 里设置 ADMIN_HOST_PORT 换一个端口"
fi

"${SUDO[@]}" mkdir -p "$DIR/keys"
"${SUDO[@]}" chmod 755 "$DIR"
"${SUDO[@]}" cp "$BUNDLE/compose.prod.yaml" "$DIR/compose.yaml"

ENV="$DIR/.env"
if ! "${SUDO[@]}" test -f "$ENV"; then
  log "首次部署：生成 $ENV 与 JWT 密钥"
  tmp=$(mktemp); umask 077
  {
    echo "# Created by scripts/deploy/remote-deploy.sh. Edit to change settings; later deployments keep this file."
    echo "POSTGRES_PASSWORD=$(openssl rand -hex 24)"
    echo "APP_BOOTSTRAP_ADMIN_USERNAME=admin"
    echo "APP_BOOTSTRAP_ADMIN_PASSWORD=$(openssl rand -base64 18 | tr -d '/+=' | head -c 20)"
    echo "APP_SECURITY_CORS_ALLOWED_ORIGINS=${ADMIN_ALLOWED_ORIGINS:-}"
    echo "# Fish Closet management integration (off until the closet side has the same key id and secret):"
    echo "APP_CLOSET_ENABLED=false"
    echo "APP_CLOSET_BASE_URL=http://host.docker.internal:8080"
    echo "APP_CLOSET_KEY_ID=primary"
    echo "APP_CLOSET_SECRET="
  } > "$tmp"
  "${SUDO[@]}" install -m 600 -o "$(id -u)" -g "$(id -g)" "$tmp" "$ENV"; rm -f "$tmp"
fi
if ! "${SUDO[@]}" test -f "$DIR/keys/jwt-private.pem"; then
  kt=$(mktemp -d); openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$kt/jwt-private.pem" 2>/dev/null
  openssl pkey -in "$kt/jwt-private.pem" -pubout -out "$kt/jwt-public.pem"
  # The container runs as uid 10001 and only reads the keys.
  "${SUDO[@]}" install -m 400 -o 10001 -g 10001 "$kt/jwt-private.pem" "$DIR/keys/jwt-private.pem"
  "${SUDO[@]}" install -m 444 -o 10001 -g 10001 "$kt/jwt-public.pem" "$DIR/keys/jwt-public.pem"
  rm -rf "$kt"
fi

cd "$DIR"
if [ -n "${GHCR_USER:-}" ]; then
  IFS= read -r token || true
  [ -n "${token:-}" ] && printf '%s' "$token" | dk login ghcr.io -u "$GHCR_USER" --password-stdin >/dev/null
  trap 'dk logout ghcr.io >/dev/null 2>&1 || true' EXIT
fi
export IMAGE_TAG ADMIN_HOST_PORT=$PORT
"${PRE[@]}" env IMAGE_TAG="$IMAGE_TAG" ADMIN_HOST_PORT="$PORT" docker compose -p "$PROJECT" pull
"${PRE[@]}" env IMAGE_TAG="$IMAGE_TAG" ADMIN_HOST_PORT="$PORT" docker compose -p "$PROJECT" up -d --remove-orphans

log "等待 http://127.0.0.1:$PORT 就绪（最长 180 秒）"
for _ in $(seq 1 60); do
  if curl -fsS -m 5 "http://127.0.0.1:$PORT/actuator/health/readiness" 2>/dev/null | grep -q '"UP"'; then
    ver=$(curl -fsS -m 5 "http://127.0.0.1:$PORT/api/v1/system/info" 2>/dev/null | head -c 300 || true)
    log "管理后端已就绪：镜像标签 $IMAGE_TAG，宿主机端口 $PORT"
    log "system/info：${ver:-（无）}"
    log "初始管理员账号与密码在服务器 $ENV（APP_BOOTSTRAP_ADMIN_*），登录后请改密"
    exit 0
  fi
  sleep 3
done
log "容器状态："; "${PRE[@]}" docker compose -p "$PROJECT" ps >&2 || true
log "app 最近日志："; "${PRE[@]}" docker compose -p "$PROJECT" logs --tail 60 app >&2 || true
die "180 秒内没有就绪"
