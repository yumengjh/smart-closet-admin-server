#!/usr/bin/env bash
# Sourced by scripts/ci/deploy.sh: writes ~/.ssh files and the ~/remote and ~/upload helpers from the Semaphore secret
# "deploy" (DEPLOY_SSH_HOST, DEPLOY_SSH_USER, DEPLOY_SSH_KEY or DEPLOY_SSH_PASSWORD; optional DEPLOY_SSH_PORT,
# DEPLOY_SSH_KNOWN_HOSTS). Multi-line values may be stored as they are or base64-encoded on one line.
# Same logic as smart-closet-server's scripts/ci/deploy.sh.

missing=()
[ -n "${DEPLOY_SSH_HOST:-}" ] || missing+=(DEPLOY_SSH_HOST)
[ -n "${DEPLOY_SSH_USER:-}" ] || missing+=(DEPLOY_SSH_USER)
[ -n "${DEPLOY_SSH_KEY:-}${DEPLOY_SSH_PASSWORD:-}" ] || missing+=("DEPLOY_SSH_KEY 或 DEPLOY_SSH_PASSWORD")
[ ${#missing[@]} -eq 0 ] || { echo "缺少环境变量：${missing[*]}"; exit 1; }

mkdir -p ~/.ssh && chmod 700 ~/.ssh
port=${DEPLOY_SSH_PORT:-22}
text() { case "$1" in *BEGIN*|*ssh-*|*ecdsa-*) printf '%s\n' "$1" ;; *) printf '%s' "$1" | base64 -d ;; esac; }
# A private key whose line breaks were lost on pasting: rebuild header, body and footer.
pem() {
  local v h f b; v=$(text "$1" | tr -d '\r'); h=$(printf '%s' "$v" | grep -o -- '-----BEGIN [A-Z ]*-----' | head -1)
  [ -n "$h" ] || { echo "私钥格式不对（没有 -----BEGIN ... -----）" >&2; return 1; }
  f=${h/BEGIN/END}; b=${v#*"$h"}; b=${b%%"$f"*}
  printf '%s\n' "$h"; printf '%s' "$b" | tr -d ' \t\n' | fold -w 64; printf '\n%s\n' "$f"
}
if [ -n "${DEPLOY_SSH_KNOWN_HOSTS:-}" ]; then
  # One "host type key" entry per line, even when the pasted lines (and ssh-keyscan's # comments) were joined.
  text "$DEPLOY_SSH_KNOWN_HOSTS" | tr -s ' \t\r\n' ' ' | awk '{for (i = 2; i < NF; i++) if ($i ~ /^(ssh-|ecdsa-|sk-)/ && $(i-1) != "#") print $(i-1), $i, $(i+1)}' > ~/.ssh/known_hosts
  echo "DEPLOY_SSH_KNOWN_HOSTS：$(wc -l < ~/.ssh/known_hosts | tr -d ' ') 条，类型 $(awk '{print $2}' ~/.ssh/known_hosts | sort -u | tr '\n' ' ')"
  name=$DEPLOY_SSH_HOST; [ "$port" = 22 ] || name="[$DEPLOY_SSH_HOST]:$port"
  # The host keys do not depend on the port: entries scanned without -p also serve [host]:port.
  if [ "$port" != 22 ] && ! ssh-keygen -F "$name" -f ~/.ssh/known_hosts >/dev/null && ssh-keygen -F "$DEPLOY_SSH_HOST" -f ~/.ssh/known_hosts >/dev/null; then
    ssh-keygen -F "$DEPLOY_SSH_HOST" -f ~/.ssh/known_hosts | grep -v '^#' | awk -v n="$name" '{$1 = n; print}' >> ~/.ssh/known_hosts
    echo "DEPLOY_SSH_KNOWN_HOSTS 的记录不带端口，已按端口 $port 补上"
  fi
  ssh-keygen -F "$name" -f ~/.ssh/known_hosts >/dev/null || { echo "DEPLOY_SSH_KNOWN_HOSTS 里没有与 DEPLOY_SSH_HOST（端口 $port）对应的记录：请用 ssh-keyscan -p $port <与 DEPLOY_SSH_HOST 完全相同的地址> 重新生成"; exit 1; }
else ssh-keyscan -p "$port" -T 15 "$DEPLOY_SSH_HOST" > ~/.ssh/known_hosts 2>/dev/null; echo "警告：未设置 DEPLOY_SSH_KNOWN_HOSTS，本次信任扫描到的主机指纹"; fi
opts=(-p "$port" -o BatchMode=no -o ServerAliveInterval=30 -o ConnectTimeout=20 -o StrictHostKeyChecking=yes)
if [ -n "${DEPLOY_SSH_KEY:-}" ]; then
  pem "$DEPLOY_SSH_KEY" > ~/.ssh/deploy_key && chmod 600 ~/.ssh/deploy_key
  ssh-keygen -y -f ~/.ssh/deploy_key >/dev/null || { echo "DEPLOY_SSH_KEY 无法读取（格式不对或带了密码）"; exit 1; }
  opts+=(-i ~/.ssh/deploy_key -o IdentitiesOnly=yes); pre=""
else
  sudo apt-get update -qq >/dev/null && sudo apt-get install -y -qq sshpass >/dev/null
  pre="sshpass -e"
  opts+=(-o PubkeyAuthentication=no)
fi
# sshpass -e reads SSHPASS.
printf '#!/usr/bin/env bash\nexport SSHPASS="${DEPLOY_SSH_PASSWORD:-}"\nexec %s ssh %s "%s@%s" "$@"\n' "$pre" "${opts[*]}" "$DEPLOY_SSH_USER" "$DEPLOY_SSH_HOST" > ~/remote && chmod +x ~/remote
printf '#!/usr/bin/env bash\nexport SSHPASS="${DEPLOY_SSH_PASSWORD:-}"\nexec %s scp %s "$@"\n' "$pre" "${opts[*]/-p/-P}" > ~/upload && chmod +x ~/upload
