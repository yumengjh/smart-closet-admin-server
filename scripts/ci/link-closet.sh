#!/usr/bin/env bash
# Link this admin service to the Fish Closet on the same host (started by hand: promotion "Link Fish Closet" ->
# .semaphore/link-closet.yml). Server side: scripts/deploy/link-closet.sh. Secret "deploy", same variables as deploy.sh.
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/ci/ssh-setup.sh
DEST=/tmp/smart-closet-admin-link-${SEMAPHORE_WORKFLOW_ID:-$$}
cleanup() { [ -x ~/remote ] && ~/remote "rm -rf '$DEST'" || true; rm -f ~/.ssh/deploy_key; }
trap cleanup EXIT
~/remote 'echo "已连接 $(hostname)"'
~/remote "rm -rf '$DEST' && mkdir -p '$DEST' && chmod 700 '$DEST'"
~/upload scripts/deploy/link-closet.sh "$DEPLOY_SSH_USER@$DEPLOY_SSH_HOST:$DEST/"
~/remote "ADMIN_DEPLOY_DIR=$(printf %q "${ADMIN_DEPLOY_DIR:-/opt/smart-closet-admin}") ADMIN_HOST_PORT=$(printf %q "${ADMIN_HOST_PORT:-8081}") bash '$DEST/link-closet.sh'"
