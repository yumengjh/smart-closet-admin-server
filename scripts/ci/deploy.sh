#!/usr/bin/env bash
# Deploy over SSH (used by .semaphore/release.yml and .semaphore/deploy.yml). Server side: scripts/deploy/remote-deploy.sh.
#
# Semaphore secret "deploy" (shared with smart-closet-server; same host): GHCR_USER, GHCR_TOKEN, DEPLOY_SSH_HOST,
# DEPLOY_SSH_USER, DEPLOY_SSH_KEY or DEPLOY_SSH_PASSWORD, optional DEPLOY_SSH_PORT, DEPLOY_SSH_KNOWN_HOSTS.
# This service has its own settings, which never reuse the closet's DEPLOY_DIR:
#   ADMIN_DEPLOY_DIR   directory on the server (default /opt/smart-closet-admin)
#   ADMIN_HOST_PORT    host port published for the reverse proxy (default 8081)
#   ADMIN_ALLOWED_ORIGINS  CORS origin seeded into a new database (default https://yuda-admin.yumg.cn)
set -euo pipefail
cd "$(dirname "$0")/../.."

# What to deploy: the promotion parameter DEPLOY_IMAGE_TAG, else the version of the release tag being built.
tag="${DEPLOY_IMAGE_TAG:-}"
if [ -z "$tag" ]; then
  [ "${SEMAPHORE_GIT_REF_TYPE:-}" = tag ] || { echo "既没有 DEPLOY_IMAGE_TAG 参数，也不是 tag 构建"; exit 1; }
  tag="${SEMAPHORE_GIT_TAG_NAME#v}"
fi
[[ "$tag" =~ ^[A-Za-z0-9_.-]+$ ]] || { echo "镜像标签只能包含字母、数字、. _ -"; exit 1; }
echo "部署镜像标签：$tag"
[ -n "${GHCR_USER:-}" ] && [ -n "${GHCR_TOKEN:-}" ] || { echo "缺少环境变量：GHCR_USER / GHCR_TOKEN"; exit 1; }

source scripts/ci/ssh-setup.sh
DEST=/tmp/smart-closet-admin-deploy-${SEMAPHORE_WORKFLOW_ID:-$$}
cleanup() { [ -x ~/remote ] && ~/remote "rm -rf '$DEST'" || true; rm -f ~/.ssh/deploy_key; }
trap cleanup EXIT
~/remote 'echo "已连接 $(hostname)"'

~/remote "rm -rf '$DEST' && mkdir -p '$DEST' && chmod 700 '$DEST'"
~/upload scripts/deploy/remote-deploy.sh scripts/deploy/compose.prod.yaml "$DEPLOY_SSH_USER@$DEPLOY_SSH_HOST:$DEST/"

printf '%s' "$GHCR_TOKEN" | ~/remote "IMAGE_TAG=$(printf %q "$tag") GHCR_USER=$(printf %q "$GHCR_USER") ADMIN_DEPLOY_DIR=$(printf %q "${ADMIN_DEPLOY_DIR:-/opt/smart-closet-admin}") ADMIN_HOST_PORT=$(printf %q "${ADMIN_HOST_PORT:-8081}") ADMIN_ALLOWED_ORIGINS=$(printf %q "${ADMIN_ALLOWED_ORIGINS:-https://yuda-admin.yumg.cn}") bash '$DEST/remote-deploy.sh'"
