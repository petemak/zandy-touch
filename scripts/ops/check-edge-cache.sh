#!/usr/bin/env bash
# Acceptance check D-5: the CDN honours the origin's lifetimes. Run against the Cloudflare-fronted hostname.
#   scripts/ops/check-edge-cache.sh https://staging.zandytouch.com
# 1. Second fetch of /shop and /api/products must be a cache hit.
# 2. It then polls every POLL seconds (default 10) for up to WINDOW seconds (default 240) and records the
#    largest Age seen on a HIT. The origin lifetime is 60 s + 30 s (pages) and 30 s + 60 s (API), so a
#    HIT older than LIMIT (default 100 s) means the edge keeps objects longer than the origin allows:
#    that is the Cloudflare Free minimum edge TTL risk. The script then prints the MEASURED minimum TTL
#    and exits 1 (decision 3 fallbacks apply, see deploy/CLOUDFLARE.md).
# 3. Admin, auth and cookie requests must never be a HIT.
set -euo pipefail
. "$(dirname "$0")/lib.sh"
need curl awk
base=${1:?usage: check-edge-cache.sh <https-base-url>}
base=${base%/}
POLL=${POLL:-10}; WINDOW=${WINDOW:-240}; LIMIT=${LIMIT:-100}

status_of() { header cf-cache-status | tr 'a-z' 'A-Z'; }

echo "D-5 edge cache against $base (polling up to ${WINDOW}s; allow about 4 minutes)"
fetch "$base/shop"
[ -n "$(header cf-ray)" ] || die "no CF-Ray header: $base is not served through Cloudflare (DNS record not proxied?)"

# --- bypass rules first (cheap) -----------------------------------------------------------------
for spec in "/api/admin/orders|" "/api/auth/sessions/current|" "/admin|" "/admin/login|" "/shop|Cookie: zt_session=probe" "/api/products|Cookie: zt_session=probe"; do
  path=${spec%%|*}; cookie=${spec#*|}
  args=(); [ -n "$cookie" ] && args=(-H "$cookie")
  fetch "$base$path" "${args[@]}"; fetch "$base$path" "${args[@]}"
  st=$(status_of)
  case "$st" in
    HIT|STALE|UPDATING|REVALIDATED) fail "$path ${cookie:+(with zt_session cookie) }served from cache: cf-cache-status $st" ;;
    *) pass "$path ${cookie:+(with zt_session cookie) }bypasses the cache (cf-cache-status ${st:-none})" ;;
  esac
done

# --- hit and lifetime ------------------------------------------------------------------------------
declare -A maxage=([/shop]=0 [/api/products]=0)
for p in /shop /api/products; do
  fetch "$base$p"; fetch "$base$p"
  st=$(status_of)
  if [ "$st" = HIT ]; then pass "$p second fetch is a HIT (Age $(header age))"; else fail "$p second fetch is '$st', expected HIT (is there a Cache Rule 'eligible for cache'?)"; fi
done

end=$((SECONDS + WINDOW))
while [ $SECONDS -lt $end ]; do
  sleep "$POLL"
  for p in /shop /api/products; do
    fetch "$base$p"
    st=$(status_of); age=$(header age); age=${age:-0}
    if [ "$st" = HIT ] && [ "$age" -gt "${maxage[$p]}" ]; then maxage[$p]=$age; fi
  done
done

for p in /shop /api/products; do
  m=${maxage[$p]}
  if [ "$m" -le "$LIMIT" ]; then
    pass "$p largest Age on a HIT: ${m}s (limit ${LIMIT}s)"
  else
    fail "$p was served from cache at Age ${m}s: the edge keeps it longer than the origin's lifetime. MEASURED minimum edge TTL is at least ${m}s"
  fi
done
summary "D-5 edge cache"
