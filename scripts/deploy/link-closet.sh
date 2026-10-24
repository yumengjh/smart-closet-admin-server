#!/usr/bin/env bash
# Runs on the backend host over SSH (uploaded by scripts/ci/link-closet.sh). Connects this admin service to the Fish Closet
# deployment on the same host, safely repeatable:
#
#   1. finds the running Fish Closet "server" container (compose project, env file, network, image tag);
#   2. turns its management API on (CLOSET_MANAGEMENT_ENABLED/KEY_ID/SECRET/EXPORT_SALT in its env file, backed up first;
#      an existing secret is kept) and recreates only the "server" container, with the image tag it already runs;
#   3. attaches the admin app to the closet's compose network (compose.override.yaml) and points APP_CLOSET_* in
#      $ADMIN_DEPLOY_DIR/.env at http://server:8080 with the same key id and secret, then recreates the admin app;
#   4. checks that the admin container reaches the management API and that it answers 401 to an unsigned request.
#
# The management API is never published: it stays on the closet's loopback port and on the shared compose network.
# Secrets are never printed. Environment: ADMIN_DEPLOY_DIR (default /opt/smart-closet-admin), ADMIN_HOST_PORT (default 8081).
set -euo pipefail
DIR=${ADMIN_DEPLOY_DIR:-/opt/smart-closet-admin}
PORT=${ADMIN_HOST_PORT:-8081}
ADMIN_PROJECT=smart-closet-admin
log() { printf '[link] %s\n' "$*" >&2; }
die() { printf '[link] 错误：%s\n' "$*" >&2; exit 1; }

PRE=(); SUDO=()
if ! docker info >/dev/null 2>&1; then
  if sudo -n docker info >/dev/null 2>&1; then PRE=(sudo -n); else die "当前用户无法使用 docker，也不能免密 sudo"; fi
fi
[ "$(id -u)" = 0 ] || SUDO=(sudo -n)
dk() { "${PRE[@]}" docker "$@"; }
command -v openssl >/dev/null || die "服务器缺少 openssl"
"${SUDO[@]}" test -f "$DIR/.env" && "${SUDO[@]}" test -f "$DIR/compose.yaml" || die "$DIR 下没有管理后端的部署（先完成一次后端发布）"

# 1. The Fish Closet server container: the one that mounts /models.
cid=""
for id in $(dk ps -q --filter label=com.docker.compose.service=server); do
  if [ -n "$(dk inspect -f '{{range .Mounts}}{{if eq .Destination "/models"}}{{.Source}}{{end}}{{end}}' "$id")" ]; then cid=$id; break; fi
done
[ -n "$cid" ] || die "没有找到运行中的鱼搭 server 容器（挂载了 /models 的 compose 服务 server）"
label() { dk inspect -f "{{ index .Config.Labels \"$1\" }}" "$cid"; }
PROJECT=$(label com.docker.compose.project); WORKDIR=$(label com.docker.compose.project.working_dir)
CONFIG=$(label com.docker.compose.project.config_files); ENV_FILE=$(label com.docker.compose.project.environment_file)
[ -n "$ENV_FILE" ] || ENV_FILE="$WORKDIR/.env"
"${SUDO[@]}" test -f "$ENV_FILE" || die "找不到鱼搭的环境文件：$ENV_FILE"
NETWORK=$(dk inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{println $k}}{{end}}' "$cid" | head -1)
[ -n "$NETWORK" ] || die "读不到鱼搭容器的网络"
image=$(dk inspect -f '{{.Config.Image}}' "$cid")
case "$image" in ghcr.io/yumengjh/smart-closet-server:*) IMAGE_TAG=${image##*:} ;; *) die "鱼搭 server 容器的镜像不是预期的 ghcr.io/yumengjh/smart-closet-server:<tag>：$image" ;; esac
log "鱼搭：项目 $PROJECT，目录 $WORKDIR，环境文件 $ENV_FILE，网络 $NETWORK，镜像标签 $IMAGE_TAG"

get() { "${SUDO[@]}" sed -n "s/^$2=//p" "$1" | tail -1 | sed -e "s/^'//" -e "s/'\$//"; }
upsert() { # file key value
  local f=$1 k=$2 v=$3
  if "${SUDO[@]}" grep -q "^$k=" "$f"; then
    "${SUDO[@]}" sed -i "s|^$k=.*|$k=$v|" "$f"
  else
    [ -z "$("${SUDO[@]}" tail -c1 "$f")" ] || echo | "${SUDO[@]}" tee -a "$f" >/dev/null
    printf '%s=%s\n' "$k" "$v" | "${SUDO[@]}" tee -a "$f" >/dev/null
  fi
}

KEY_ID=$(get "$ENV_FILE" CLOSET_MANAGEMENT_KEY_ID); KEY_ID=${KEY_ID:-primary}
SECRET=$(get "$ENV_FILE" CLOSET_MANAGEMENT_SECRET)
if [ -z "$SECRET" ]; then SECRET=$(get "$DIR/.env" APP_CLOSET_SECRET); fi
if [ -z "$SECRET" ]; then SECRET=$(openssl rand -base64 48); log "已生成新的共享密钥"; else log "沿用已有的共享密钥"; fi
[ "${#SECRET}" -ge 32 ] || die "共享密钥不足 32 字节"
SALT=$(get "$ENV_FILE" CLOSET_MANAGEMENT_EXPORT_SALT); SALT=${SALT:-$(openssl rand -hex 32)}

# 2. Fish Closet side.
backup="$ENV_FILE.bak-$(date +%Y%m%d%H%M%S)"
"${SUDO[@]}" cp -p "$ENV_FILE" "$backup"; log "已备份鱼搭环境文件：$backup"
upsert "$ENV_FILE" CLOSET_MANAGEMENT_ENABLED true
upsert "$ENV_FILE" CLOSET_MANAGEMENT_KEY_ID "$KEY_ID"
upsert "$ENV_FILE" CLOSET_MANAGEMENT_SECRET "$SECRET"
upsert "$ENV_FILE" CLOSET_MANAGEMENT_EXPORT_SALT "$SALT"

closet_compose() {
  local args=(-p "$PROJECT" --project-directory "$WORKDIR") f
  IFS=',' read -ra files <<< "$CONFIG"; for f in "${files[@]}"; do args+=(-f "$f"); done
  args+=(--env-file "$ENV_FILE")
  "${PRE[@]}" env IMAGE_TAG="$IMAGE_TAG" docker compose "${args[@]}" "$@"
}
log "重建鱼搭 server 容器（只重建 server，沿用镜像标签 $IMAGE_TAG，短暂中断）"
closet_compose up -d --no-build --no-deps server
deadline=$((SECONDS + 300)); status=""
while [ $SECONDS -lt $deadline ]; do
  id=$(closet_compose ps -q server)
  status=$(dk inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$id" 2>/dev/null || echo missing)
  [ "$status" = healthy ] && break
  case "$status" in unhealthy|exited|dead|missing) break ;; esac
  sleep 5
done
if [ "$status" != healthy ]; then
  log "鱼搭 server 状态：$status，恢复环境文件并重建"
  "${SUDO[@]}" cp -p "$backup" "$ENV_FILE"
  closet_compose logs --tail=40 server >&2 || true
  closet_compose up -d --no-build --no-deps server || true
  die "鱼搭没有在 300 秒内恢复健康，已恢复到开启前的环境文件"
fi
log "鱼搭 server 已恢复健康，管理接口已开启"

# 3. Admin side.
tmp=$(mktemp)
cat > "$tmp" <<EOF
# Written by scripts/deploy/link-closet.sh: the admin app joins the Fish Closet compose network.
services:
  app:
    networks:
      - default
      - closet
networks:
  closet:
    external: true
    name: $NETWORK
EOF
"${SUDO[@]}" install -m 644 "$tmp" "$DIR/compose.override.yaml"; rm -f "$tmp"
ADMIN_ENV="$DIR/.env"
upsert "$ADMIN_ENV" APP_CLOSET_ENABLED true
upsert "$ADMIN_ENV" APP_CLOSET_BASE_URL http://server:8080
upsert "$ADMIN_ENV" APP_CLOSET_KEY_ID "$KEY_ID"
upsert "$ADMIN_ENV" APP_CLOSET_SECRET "$SECRET"

admin_compose() { ( cd "$DIR" && "${PRE[@]}" env IMAGE_TAG="${ADMIN_TAG:-}" ADMIN_HOST_PORT="$PORT" docker compose -p "$ADMIN_PROJECT" "$@" ); }
# Keep the admin image tag it is running now.
acid=$(dk ps -q --filter "label=com.docker.compose.project=$ADMIN_PROJECT" --filter label=com.docker.compose.service=app | head -1)
[ -n "$acid" ] || die "管理后端容器没有在运行"
aimage=$(dk inspect -f '{{.Config.Image}}' "$acid"); ADMIN_TAG=${aimage##*:}
log "重建管理后端 app（镜像标签 $ADMIN_TAG）"
admin_compose up -d --no-build --no-deps app

for _ in $(seq 1 60); do
  curl -fsS -m 5 "http://127.0.0.1:$PORT/actuator/health/readiness" 2>/dev/null | grep -q '"UP"' && ready=1 && break
  sleep 3
done
[ "${ready:-0}" = 1 ] || { admin_compose logs --tail=60 app >&2 || true; die "管理后端 180 秒内没有就绪（环境文件 $ADMIN_ENV；鱼搭侧已开启，可把 APP_CLOSET_ENABLED 改回 false 再 up -d）"; }

# 4. Reachability from the admin container, unsigned request must be refused with 401 (not 404: the API is on).
acid=$(dk ps -q --filter "label=com.docker.compose.project=$ADMIN_PROJECT" --filter label=com.docker.compose.service=app | head -1)
reply=$(dk exec "$acid" bash -c 'exec 3<>/dev/tcp/server/8080 && printf "GET /api/manage/v1/users HTTP/1.0\r\nHost: server\r\n\r\n" >&3 && head -1 <&3' 2>&1 | tr -d '\r' || true)
log "管理后端容器访问 http://server:8080/api/manage/v1/users（不签名）：${reply:-无响应}"
case "$reply" in *" 401"*) log "联动就绪：管理接口已开启且管理后端可以访问到它" ;; *) die "未得到预期的 401：$reply" ;; esac
