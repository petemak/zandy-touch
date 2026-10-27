#!/usr/bin/env bash
# Roll an environment back to the previous release (images and static files are still on the server).
#   DEPLOY_HOST=... scripts/ops/rollback.sh staging|production
# Migrations are additive (architecture D10), so the previous backend works against the newer schema for
# one release. A rollback never reverts data.
set -euo pipefail
. "$(dirname "$0")/lib.sh"
. "$(dirname "$0")/remote.sh"
envname=${1:?usage: rollback.sh staging|production}
read -r backend_svc ssr_svc <<< "$(service_names "$envname")"
key=$([ "$envname" = production ] && echo PROD || echo STAGING)
cur=$(env_get "${key}_RELEASE"); prev=$(env_get "PREVIOUS_${key}_RELEASE")
[ -n "$prev" ] && [ "$prev" != none ] || die "no previous release is recorded for $envname"
rsh "docker image inspect zandy/backend:$prev zandy/ssr:$prev >/dev/null" || die "images of $prev are gone from the server"
echo "== rolling $envname back from $cur to $prev"
# Put the previous release's asset manifest back so ssr names the files of that release.
rsh "test -s $ZANDY_HOME/releases/$prev/static/asset-manifest.edn && cp $ZANDY_HOME/releases/$prev/static/asset-manifest.edn $ZANDY_HOME/static/$envname/asset-manifest.edn"
env_set "${key}_RELEASE" "$prev"
env_set "PREVIOUS_${key}_RELEASE" "$cur"
rcompose "--profile $envname up -d --wait --no-deps $backend_svc"
rcompose "--profile $envname up -d --wait --no-deps $ssr_svc"
rsh "echo $prev > $ZANDY_HOME/state-$envname-release"
echo "== $envname now runs $prev (run rollback.sh again to go forward to $cur)"
