#!/usr/bin/env bash
# Fitness check R2 (architecture.md section 7.3), run on staging: Cache-Control matches the D2 table and
# api-contract.md section 1.5.
#   scripts/ops/check-cache-headers.sh <base-url>
# Options (environment):
#   STOP_BACKEND_CMD / START_BACKEND_CMD   commands that stop and start the backend (for example
#       "ssh host 'cd /srv/zandy/compose && docker compose stop backend-staging'"). When both are set the
#       script also checks that a stopped backend gives an ssr 503 page with no-store, and always starts
#       the backend again. Never point them at production.
#   CACHE_CATALOGUE, CACHE_INFO, CACHE_NOT_FOUND   expected values when PAGE_CACHE_* is overridden.
# Also asserts: no cacheable response sets a cookie or varies on Cookie.
set -euo pipefail
. "$(dirname "$0")/lib.sh"
need curl bb awk grep
base=${1:?usage: check-cache-headers.sh <base-url>}
base=${base%/}

CATALOGUE=${CACHE_CATALOGUE:-public, max-age=0, s-maxage=60, stale-while-revalidate=30, stale-if-error=86400}
INFO=${CACHE_INFO:-public, max-age=0, s-maxage=600, stale-while-revalidate=600, stale-if-error=86400}
NOT_FOUND=${CACHE_NOT_FOUND:-public, max-age=0, s-maxage=60}
IMMUTABLE='public, max-age=31536000, immutable'
ASSETS='public, max-age=86400, stale-while-revalidate=604800'

if [ -n "${START_BACKEND_CMD:-}" ]; then
  # Whatever happens below, leave the backend running.
  trap 'bash -c "$START_BACKEND_CMD" >/dev/null 2>&1 || true; rm -f "$FETCH_BODY"' EXIT
fi

# expect <label> <url> <status-regex> <cache-control> [cacheable: yes|no] [extra curl args...]
expect() {
  local label=$1 url=$2 status_re=$3 want=$4 cacheable=${5:-no}
  shift 5 || shift $#
  fetch "$url" "$@"
  local got; got=$(header cache-control)
  if ! [[ "$FETCH_STATUS" =~ $status_re ]]; then
    fail "$label: HTTP $FETCH_STATUS, expected $status_re"
    return
  fi
  if [ "$(normalize "$got")" != "$(normalize "$want")" ]; then
    fail "$label: Cache-Control '$got', expected '$want'"
    return
  fi
  if [ "$cacheable" = yes ]; then
    local cookie vary
    cookie=$(header set-cookie); vary=$(header vary)
    if [ -n "$cookie" ]; then fail "$label: cacheable response sets a cookie"; return; fi
    if printf '%s' "$vary" | grep -qi 'cookie'; then fail "$label: cacheable response has Vary: $vary"; return; fi
  fi
  pass "$label"
}

echo "R2 cache headers against $base"

# Discover a product, a category and the hashed assets from the live site.
fetch "$base/api/products"
slug=$(json_get items 0 slug)
thumb=$(json_get items 0 image urls thumb)
fetch "$base/api/categories"
cat_slug=$(json_get items 0 slug)
[ -n "$slug" ] && [ -n "$cat_slug" ] || fail "no product or category found: seed the database first"
fetch "$base/"
home=$(cat "$FETCH_BODY")
find_asset() { printf '%s' "$home" | grep -o -E "$1" | head -n 1 || true; }
js=$(find_asset '/static/js/main\.[0-9A-Za-z]+\.js')
css=$(find_asset '/static/css/app\.[0-9a-f]+\.css')
tokens=$(find_asset '/static/design-tokens\.[0-9a-f]+\.css')
font=$(find_asset '/static/assets/fonts/[A-Za-z0-9._-]+\.woff2')
logo=$(find_asset '/static/assets/logo/[A-Za-z0-9._-]+')
manifest=$(find_asset '/static/assets/site\.webmanifest')

echo "-- ssr pages (D2)"
expect "/"                      "$base/"                        '^200$' "$CATALOGUE" yes
expect "/shop"                  "$base/shop"                    '^200$' "$CATALOGUE" yes
[ -n "$cat_slug" ] && expect "/categories/$cat_slug" "$base/categories/$cat_slug" '^200$' "$CATALOGUE" yes
[ -n "$slug" ] && expect "/products/$slug" "$base/products/$slug" '^200$' "$CATALOGUE" yes
expect "/about"                 "$base/about"                   '^200$' "$INFO" yes
expect "/contact"               "$base/contact"                 '^200$' "$INFO" yes
expect "/delivery-and-payment"  "$base/delivery-and-payment"    '^200$' "$INFO" yes
expect "/legal/privacy"         "$base/legal/privacy"           '^200$' "$INFO" yes
expect "/legal/order-terms"     "$base/legal/order-terms"       '^200$' "$INFO" yes
for shell in /cart /checkout /order-placed /track-order /unsubscribe /admin /admin/login; do
  expect "$shell (shell)" "$base$shell" '^200$' 'no-cache' no
  fetch "$base$shell"
  if [ -n "$(header etag)" ]; then pass "$shell has an ETag"
  elif [ -n "$(header cf-ray)" ]; then
    # Behind Cloudflare an uncached no-cache page loses its ETag (the origin sends it: run the check at the origin to see).
    # Cloudflare is identified by its cf-ray header; direct-to-origin runs stay strict.
    pass "$shell: no ETag, accepted behind Cloudflare (uncached no-cache page)"
  else fail "$shell has no ETag"; fi
done
expect "404 page"               "$base/no-such-page-zzz"        '^404$' "$NOT_FOUND" no
expect "/robots.txt"            "$base/robots.txt"              '^200$' 'public, s-maxage=3600' yes
expect "/sitemap.xml"           "$base/sitemap.xml"             '^200$' 'public, s-maxage=3600' yes
expect "/healthz"               "$base/healthz"                 '^200$' 'no-store' no

echo "-- static files (D2)"
[ -n "$js" ]     && expect "$js"     "$base$js"     '^200$' "$IMMUTABLE" yes || fail "home page links no /static/js/main.<hash>.js"
[ -n "$css" ]    && expect "$css"    "$base$css"    '^200$' "$IMMUTABLE" yes || fail "home page links no /static/css/app.<hash>.css"
[ -n "$tokens" ] && expect "$tokens" "$base$tokens" '^200$' "$IMMUTABLE" yes || fail "home page links no design-tokens.<hash>.css"
[ -n "$font" ]   && expect "$font"   "$base$font"   '^200$' "$IMMUTABLE" yes || warn "no font file linked in the home page"
[ -n "$logo" ]   && expect "$logo"   "$base$logo"   '^200$' "$ASSETS" yes    || warn "no logo linked in the home page"
[ -n "$manifest" ] && expect "$manifest" "$base$manifest" '^200$' "$ASSETS" yes || warn "no site.webmanifest linked"
expect "/favicon.svg"           "$base/favicon.svg"             '^200$' "$ASSETS" yes

echo "-- public API (api-contract.md 1.5)"
expect "GET /api/site-info"       "$base/api/site-info"       '^200$' 'public, max-age=300, stale-while-revalidate=600' yes
expect "GET /api/delivery-areas"  "$base/api/delivery-areas"  '^200$' 'public, max-age=3600, stale-while-revalidate=3600' yes
expect "GET /api/categories"      "$base/api/categories"      '^200$' 'public, max-age=60, stale-while-revalidate=300' yes
expect "GET /api/policies/orderTerms" "$base/api/policies/orderTerms" '^200$' 'public, max-age=600, stale-while-revalidate=600' yes
expect "GET /api/products"        "$base/api/products"        '^200$' 'public, max-age=30, stale-while-revalidate=60' yes
[ -n "$slug" ] && expect "GET /api/products/$slug" "$base/api/products/$slug" '^200$' 'public, max-age=30, stale-while-revalidate=60' yes
expect "GET /api/sitemap-entries" "$base/api/sitemap-entries" '^200$' 'public, max-age=3600' yes
[ -n "$thumb" ] && expect "GET $thumb" "$base$thumb" '^200$' "$IMMUTABLE" yes
expect "GET /api/healthz"         "$base/api/healthz"         '^200$' 'no-store' no

echo "-- private and error responses are no-store"
expect "GET /api/admin/orders without a session"     "$base/api/admin/orders"            '^401$' 'no-store' no
expect "GET /api/auth/sessions/current without session" "$base/api/auth/sessions/current" '^401$' 'no-store' no
expect "GET /api/products/<unknown> (404)"            "$base/api/products/no-such-product-zzz" '^404$' 'no-store' no
expect "POST /api/cart-validations (empty body)"      "$base/api/cart-validations" '^4[0-9][0-9]$' 'no-store' no -X POST -H 'Content-Type: application/json' -d '{}'
expect "unknown /media file (404)"                    "$base/media/does-not-exist-thumb.webp" '^404$' 'no-store' no

if [ -n "${STOP_BACKEND_CMD:-}" ] && [ -n "${START_BACKEND_CMD:-}" ]; then
  echo "-- backend stopped: ssr answers 503 with no-store"
  bash -c "$STOP_BACKEND_CMD" >/dev/null 2>&1 || die "STOP_BACKEND_CMD failed"
  sleep "${STOP_SETTLE_SECONDS:-12}"   # lets the proxy's health check notice
  expect "/shop with the backend stopped" "$base/shop" '^503$' 'no-store' no
  expect "/healthz with the backend stopped" "$base/healthz" '^503$' 'no-store' no
  bash -c "$START_BACKEND_CMD" >/dev/null 2>&1 || die "START_BACKEND_CMD failed"
  log "backend started again; waiting for /api/healthz"
  for _ in $(seq 1 60); do
    fetch "$base/api/healthz"; [ "$FETCH_STATUS" = 200 ] && break; sleep 2
  done
  [ "$FETCH_STATUS" = 200 ] && pass "backend healthy again" || fail "backend did not come back"
else
  warn "stopped-backend check skipped (set STOP_BACKEND_CMD and START_BACKEND_CMD to run it)"
fi

summary "R2 cache headers"
