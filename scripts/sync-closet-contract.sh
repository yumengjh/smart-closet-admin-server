#!/usr/bin/env bash
# 把鱼搭管理接口契约（契约源）同步为本仓库快照 docs/integrations/closet/management-api.yaml。
#
#   scripts/sync-closet-contract.sh [鱼搭仓库路径]          写入快照
#   scripts/sync-closet-contract.sh --check [鱼搭仓库路径]  只比对，不一致时退出码 1
#
# 鱼搭仓库路径默认 ../smart-closet-server。快照 = 4 行来源头 + 契约源原文；
# ClosetContractSnapshotTest 校验头里的 sha256 与正文一致，并在能读到契约源时逐字节比对。
set -euo pipefail

check=false
if [[ "${1:-}" == "--check" ]]; then check=true; shift; fi
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
closet="${1:-$root/../smart-closet-server}"
source_file="$closet/docs/management-api.yaml"
snapshot="$root/docs/integrations/closet/management-api.yaml"

[[ -f "$source_file" ]] || { echo "找不到契约源：$source_file" >&2; exit 2; }

if command -v sha256sum >/dev/null 2>&1; then sha() { sha256sum "$1" | cut -d' ' -f1; }
else sha() { shasum -a 256 "$1" | cut -d' ' -f1; }; fi

if $check; then
  if [[ -f "$snapshot" ]] && cmp -s <(tail -n +5 "$snapshot") "$source_file"; then
    echo "快照与契约源一致"; exit 0
  fi
  echo "快照与契约源不一致，请运行 scripts/sync-closet-contract.sh 更新" >&2; exit 1
fi

if git -C "$closet" diff --quiet HEAD -- docs/management-api.yaml 2>/dev/null; then
  commit="$(git -C "$closet" log -1 --format=%H -- docs/management-api.yaml)"
else
  commit="uncommitted"
  echo "警告：契约源有未提交改动，来源提交记为 uncommitted" >&2
fi

{
  echo "# 鱼搭管理接口契约快照，请勿手改。契约源：yumengjh/smart-closet-server docs/management-api.yaml"
  echo "# source-commit: $commit"
  echo "# source-sha256: $(sha "$source_file")"
  echo "# 更新方式：scripts/sync-closet-contract.sh [鱼搭仓库路径]"
  cat "$source_file"
} > "$snapshot"
echo "已同步快照（${commit}）"
