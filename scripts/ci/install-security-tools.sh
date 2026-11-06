#!/usr/bin/env bash
# Linux x64 CI runners; scanner versions and release checksums are shared.
set -euo pipefail
python3 -m pip install --user --break-system-packages 'semgrep==1.183.0'
tools_dir="$HOME/.local/bin"
mkdir -p "$tools_dir"
repo_dir=$PWD
download_dir=$(mktemp -d)
trap 'rm -rf "$download_dir"' EXIT
cd "$download_dir"
curl --retry 3 -fsSLO https://github.com/gitleaks/gitleaks/releases/download/v8.30.1/gitleaks_8.30.1_linux_x64.tar.gz
curl --retry 3 -fsSLO https://github.com/gitleaks/gitleaks/releases/download/v8.30.1/gitleaks_8.30.1_checksums.txt
sha256sum --ignore-missing -c gitleaks_8.30.1_checksums.txt
tar -xzf gitleaks_8.30.1_linux_x64.tar.gz -C "$tools_dir" gitleaks
if [ -f "$repo_dir/pom.xml" ]; then
  curl --retry 3 -fsSLO https://github.com/google/osv-scanner/releases/download/v2.7.0/osv-scanner_linux_amd64
  curl --retry 3 -fsSLO https://github.com/google/osv-scanner/releases/download/v2.7.0/osv-scanner_SHA256SUMS
  sha256sum --ignore-missing -c osv-scanner_SHA256SUMS
  install -m 755 osv-scanner_linux_amd64 "$tools_dir/osv-scanner"
fi
