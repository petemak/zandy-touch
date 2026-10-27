#!/usr/bin/env bash
# Acceptance check D-10 (architecture.md section 8): the origin answers only through Cloudflare.
#   scripts/ops/check-firewall.sh <public-https-url> <origin-ip> [ssh-target]
# Run from a machine that is NOT inside Cloudflare's ranges (the operator's computer).
#   1. a direct connection to the origin on 443 and on 80 (curl --resolve to the origin address) times out or is refused;
#   2. the same URL through Cloudflare (normal DNS) answers 200 with a cf-ray header;
#   3. SSH to the origin still works (when ssh-target is given, for example ubuntu@IP; set SSH_KEY to the private key path).
set -euo pipefail
. "$(dirname "$0")/lib.sh"
need curl
url=${1:?usage: check-firewall.sh <https-url> <origin-ip> [ssh-target]}; ip=${2:?origin ip}; sshto=${3:-}
host=${url#https://}; host=${host%%/*}
echo "D-10 origin firewall: $host, origin $ip"

for port in 443 80; do
  scheme=$([ "$port" = 443 ] && echo https || echo http)
  code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 8 --resolve "$host:$port:$ip" -k "$scheme://$host/" 2>/dev/null || true)
  if [ "$code" = 000 ] || [ -z "$code" ]; then pass "direct to origin on $port: no answer (timeout or refused)"; else fail "direct to origin on $port answered HTTP $code"; fi
done
fetch "$url/"
if [ "$FETCH_STATUS" = 200 ] && [ -n "$(header cf-ray)" ]; then pass "through Cloudflare: HTTP 200, cf-ray $(header cf-ray)"; else fail "through Cloudflare: HTTP $FETCH_STATUS, cf-ray '$(header cf-ray)'"; fi
if [ -n "$sshto" ]; then
  if ssh ${SSH_KEY:+-i "$SSH_KEY" -o IdentitiesOnly=yes} -o BatchMode=yes -o ConnectTimeout=8 "$sshto" true 2>/dev/null; then pass "ssh to $sshto works"; else fail "ssh to $sshto failed"; fi
fi
summary "D-10"
