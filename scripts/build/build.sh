#!/usr/bin/env bash
# Full frontend build: npm ci && npx shadow-cljs release app && bb scripts/build/assets.clj
set -euo pipefail
cd "$(dirname "$0")/../.."
[ "${SKIP_NPM:-}" = "1" ] || npm ci
rm -rf target/static/js
npx shadow-cljs release app
bb scripts/build/assets.clj
