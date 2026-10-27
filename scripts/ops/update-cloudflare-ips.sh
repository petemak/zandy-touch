#!/usr/bin/env bash
# Regenerates deploy/caddy/cloudflare-ips.conf from Cloudflare's published ranges.
set -euo pipefail
. "$(dirname "$0")/lib.sh"
need curl
out="$REPO_ROOT/deploy/caddy/cloudflare-ips.conf"
v4=$(curl -fsS https://www.cloudflare.com/ips-v4); v6=$(curl -fsS https://www.cloudflare.com/ips-v6)
[ -n "$v4" ] && [ -n "$v6" ] || die "empty answer from cloudflare.com"
{
  echo "# Cloudflare edge ranges, used by Caddy's trusted_proxies (deploy/Caddyfile global options)."
  echo "# Source: https://www.cloudflare.com/ips-v4 and https://www.cloudflare.com/ips-v6 , fetched $(date -u +%Y-%m-%d)."
  echo "# Regenerate with scripts/ops/update-cloudflare-ips.sh; then run deploy.sh to ship it."
  printf 'trusted_proxies static'
  for r in $v4 $v6; do printf ' %s' "$r"; done
  echo
} > "$out"
echo "wrote $out"
