#!/usr/bin/env bash
# Full frontend build:
#   npm ci && bb scripts/build/help.clj && npx shadow-cljs release app && bb scripts/build/assets.clj
# help.clj regenerates src/frontend/zandy/frontend/admin/help_data.cljs from admin-help.md.
# Order matters: help data first (the bundle includes it), then the bundle, then
# the asset step, which fingerprints files and needs the bundle's manifest.
set -euo pipefail
cd "$(dirname "$0")/../.."
[ "${SKIP_NPM:-}" = "1" ] || npm ci
bb scripts/build/help.clj
rm -rf target/static/js
npx shadow-cljs release app
bb scripts/build/assets.clj
