#!/usr/bin/env bash
# Build and push the image for the release tag (GHCR_USER / GHCR_TOKEN: Semaphore secret "deploy").
set -euo pipefail
missing=()
[ -n "${GHCR_USER:-}" ] || missing+=(GHCR_USER)
[ -n "${GHCR_TOKEN:-}" ] || missing+=(GHCR_TOKEN)
[ ${#missing[@]} -eq 0 ] || { echo "缺少环境变量：${missing[*]}"; exit 1; }
[ "${SEMAPHORE_GIT_REF_TYPE:-}" = tag ] || { echo "只在 tag 构建里发布镜像"; exit 1; }
version=${SEMAPHORE_GIT_TAG_NAME#v}
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "发布标签必须是 vMAJOR.MINOR.PATCH：$SEMAPHORE_GIT_TAG_NAME"; exit 1; }
sha=sha-${SEMAPHORE_GIT_SHA:0:7}
repo=ghcr.io/yumengjh/smart-closet-admin-server
created=$(date -u +%Y-%m-%dT%H:%M:%SZ)
printf '%s' "$GHCR_TOKEN" | docker login ghcr.io -u "$GHCR_USER" --password-stdin
trap 'docker logout ghcr.io || true' EXIT
docker build \
  --build-arg APP_VERSION="$version" \
  --label org.opencontainers.image.source=https://github.com/yumengjh/smart-closet-admin-server \
  --label org.opencontainers.image.revision="$SEMAPHORE_GIT_SHA" \
  --label org.opencontainers.image.version="$version" \
  --label org.opencontainers.image.created="$created" \
  -t "$repo:$version" -t "$repo:$sha" -t "$repo:latest" .
docker push "$repo:$version"
docker push "$repo:$sha"
docker push "$repo:latest"
echo "已推送 ${repo}:${version}、${repo}:${sha}、${repo}:latest"
