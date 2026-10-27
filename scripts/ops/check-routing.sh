#!/usr/bin/env bash
# Acceptance check D-1 (architecture.md section 8): path routing.
#   scripts/ops/check-routing.sh <base-url>        e.g. https://staging.zandytouch.com
# /api/site-info and a /media/...-thumb.webp must come from the backend; /, /shop, the hashed design
# tokens CSS, /robots.txt and /healthz from ssr; /api/healthz must return 200. Exit 0 = all pass.
set -euo pipefail
. "$(dirname "$0")/lib.sh"
need curl bb awk
base=${1:?usage: check-routing.sh <base-url>}
base=${base%/}

expect() {   # expect <path> <upstream> <status-regex>
  local path=$1 upstream=$2 status_re=${3:-^200$}
  fetch "$base$path"
  local got; got=$(header x-upstream)
  if [[ "$FETCH_STATUS" =~ $status_re ]] && [ "$got" = "$upstream" ]; then
    pass "$path -> X-Upstream: $got (HTTP $FETCH_STATUS)"
  else
    fail "$path: HTTP $FETCH_STATUS, X-Upstream '${got:-none}', expected $upstream"
  fi
}

echo "D-1 path routing against $base"

expect /api/site-info backend
expect /api/healthz backend

# A real photo: take the first product's thumbnail URL from the API (staging is seeded).
fetch "$base/api/products"
thumb=$(json_get items 0 image urls thumb)
if [ -n "$thumb" ]; then
  expect "$thumb" backend
else
  fail "no product with a picture in /api/products: seed staging first (docker compose run --rm backend-staging seed)"
fi
expect /media/does-not-exist-thumb.webp backend '^404$'
expect /api/no-such-endpoint backend '^404$'

expect / ssr
expect /shop ssr
expect /robots.txt ssr
expect /healthz ssr

# The hashed design tokens file named in the home page.
fetch "$base/"
tokens=$(grep -o '/static/design-tokens\.[0-9a-f]*\.css' "$FETCH_BODY" | head -n 1 || true)
if [ -n "$tokens" ]; then expect "$tokens" ssr; else fail "home page does not link /static/design-tokens.<hash>.css"; fi

# A visitor cannot choose the upstream label.
fetch "$base/api/site-info" -H 'X-Upstream: ssr'
if [ "$(header x-upstream)" = "backend" ]; then pass "inbound X-Upstream is ignored"; else fail "inbound X-Upstream changed the answer"; fi

summary "D-1 routing"
