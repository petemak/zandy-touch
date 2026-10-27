#!/usr/bin/env bash
# Deploy a release to staging or production.
#
#   DEPLOY_HOST=203.0.113.10 scripts/ops/deploy.sh staging
#   DEPLOY_HOST=203.0.113.10 scripts/ops/deploy.sh production <release>   (a release already on staging)
#
# Options:
#   --seed             staging only, first deploy: load the sample catalogue (docker compose run ... seed)
#   --build-mode remote  build the images on the server (only if this machine has no Docker; the build
#                      needs about 2 GB and can squeeze the running containers, so stage it carefully)
#   --skip-migrate     do not run migrations (never for a release with a new migration)
#   SKIP_PUBLIC_CHECKS=yes (environment) skip the final routing and retention checks against the public URL, for a first
#                      production deploy before DNS points at the server
#   --stage-only       first deploy while human secrets are still missing: build, upload images, upload config, start
#                      the transactor and the proxy (certificate), then stop. Touches no database, no instance.
#
# Gates (the script stops if they are not met):
#   QA_SIGNED_OFF=yes          you have read qa-report.md and QA has signed this release off
#   OWNER_APPROVED_STAGING=yes production only: the owner said, in their own words, that staging looks right
#   a clean git working tree (the release name is the commit)
#
# A host runs the roles in HOST_ROLES of its /srv/zandy/compose/.env (a 4 GB VPS-1 is one role only: staging, or production
# with compose.small-prod.yaml). A release already built on this machine is shipped as it is, never rebuilt.
#
# Order (architecture.md 3.3 and D10): build -> ship images and static files -> add static files
# (earlier files stay 14 days) -> transactor and proxy up -> migrate -> bootstrap owner (idempotent) ->
# restart backend, wait healthy -> restart ssr, wait healthy -> checks.
set -euo pipefail
. "$(dirname "$0")/lib.sh"
. "$(dirname "$0")/remote.sh"
need ssh rsync git curl bb

envname=${1:-}; shift || true
case "$envname" in staging|production) ;; *) die "usage: deploy.sh staging|production [release] [--seed] [--build-mode remote] [--skip-migrate]" ;; esac
release=""; seed=0; build_mode=local; migrate=1; stage_only=0
while [ $# -gt 0 ]; do
  case "$1" in
    --seed) seed=1 ;;
    --skip-migrate) migrate=0 ;;
    --stage-only) stage_only=1 ;;
    --build-mode) build_mode=${2:?}; shift ;;
    -*) die "unknown option $1" ;;
    *) release=$1 ;;
  esac
  shift
done

# ---- gates ---------------------------------------------------------------------------------------
[ "${QA_SIGNED_OFF:-}" = yes ] || die "refusing to deploy unverified work: read qa-report.md, then rerun with QA_SIGNED_OFF=yes"
if [ "$envname" = production ]; then
  [ "${OWNER_APPROVED_STAGING:-}" = yes ] || die "production needs the owner's approval of staging: rerun with OWNER_APPROVED_STAGING=yes after they said so"
  [ -n "$release" ] || die "production deploys a named release that is already running on staging: deploy.sh production <release>"
fi
dirty_release=0
if [ -z "$release" ]; then
  if [ -z "$(git -C "$REPO_ROOT" status --porcelain)" ]; then
    release=$(git -C "$REPO_ROOT" rev-parse --short=12 HEAD)
  elif [ "$envname" = staging ] && [ "${ALLOW_DIRTY:-}" = yes ]; then
    # Staging only: the release name is the commit plus a hash of the uncommitted state, so it is still unique
    # and honest. Production never accepts this: it deploys a named release already on staging, and the
    # owner is asked to commit before go-live.
    head=$(git -C "$REPO_ROOT" rev-parse --short=12 HEAD)
    h=$( { git -C "$REPO_ROOT" diff HEAD; git -C "$REPO_ROOT" ls-files --others --exclude-standard -z | xargs -0 -r sha256sum; } | sha256sum | cut -c1-8)
    release="$head-wip$h"; dirty_release=1
    log "WARNING: working tree is not clean; staging release $release (commit $head plus uncommitted changes)"
  else
    die "working tree is not clean; commit first (the release is the commit id), or for STAGING only rerun with ALLOW_DIRTY=yes"
  fi
fi
read -r backend_svc ssr_svc <<< "$(service_names "$envname")"
proj="$ZANDY_HOME/compose"
# One-shot tasks run beside the live containers, so they get a small heap (matters on a 4 GB host).
TASK_OPTS="-e JAVA_OPTS='-Xmx384m -Xss512k -XX:+UseSerialGC -XX:MaxMetaspaceSize=192m --enable-native-access=ALL-UNNAMED'"
echo "== deploy $release to $envname on $TARGET"

# ---- server preflight ----------------------------------------------------------------------------
rsh "test -d $proj && command -v docker >/dev/null" || die "server is not set up: run scripts/ops/server-setup.sh first (RUNBOOK step 'Prepare the server')"
(cd "$OPS_DIR" && rsync_to check-env.sh lib.sh set-secret.sh secret-lengths.sh cloudflare-firewall.sh show-mail.sh backup.sh restore-test.sh two-instance-smoke.sh burst-test.sh count_db.clj smtp_sink.clj json.clj "$TARGET:$ZANDY_HOME/bin/")
[ "$stage_only" = 1 ] || rsh "$ZANDY_HOME/bin/check-env.sh $envname" || die "server environment files are incomplete (see above)"

roles=$(env_get HOST_ROLES)
case ",$roles," in *",$envname,"*) ;; *) die "this host has roles '$roles' and does not run $envname (production gets its own server at go-live, see RUNBOOK)" ;; esac
if [ "$envname" = production ]; then
  staged=$(env_get STAGING_RELEASE)
  case ",$roles," in *",staging,"*) [ "$staged" = "$release" ] || die "release $release is not the one on staging ($staged): deploy and approve it on staging first" ;;
    *) echo "NOTE: staging is on another host; confirm yourself that $release is the release the owner approved there" ;; esac
fi

# ---- build (or reuse) ----------------------------------------------------------------------------
have_images=$(rsh "docker image inspect zandy/backend:$release zandy/ssr:$release >/dev/null 2>&1 && echo yes || echo no")
have_static=$(rsh "test -s $ZANDY_HOME/releases/$release/static/asset-manifest.edn && echo yes || echo no")
tx_tag=$(sed -n 's/^TRANSACTOR_TAG=//p' "$REPO_ROOT/deploy/env.host.example")
have_tx=$(rsh "docker image inspect zandy/transactor:$tx_tag >/dev/null 2>&1 && echo yes || echo no")

# A release that was already built on this machine (for staging) is shipped as it is, without rebuilding: production
# then runs the very same image bytes the owner approved on staging. Needs the images in the local Docker and
# deploy/.state/static-<release>/asset-manifest.edn (written by the build).
local_ok=no
if [ "$build_mode" = local ] && command -v docker >/dev/null 2>&1 \
   && docker image inspect "zandy/backend:$release" "zandy/ssr:$release" >/dev/null 2>&1 \
   && [ -s "$REPO_ROOT/deploy/.state/static-$release/asset-manifest.edn" ]; then local_ok=yes; fi

if [ "$have_images" = yes ] && [ "$have_static" = yes ]; then
  echo "== release $release is already on the server: skipping build and upload"
elif [ "$local_ok" = yes ]; then
  echo "== release $release was built on this machine: shipping those images and static files (no rebuild)"
  imgs=("zandy/backend:$release" "zandy/ssr:$release")
  if [ "$have_tx" = no ]; then
    docker image inspect "zandy/transactor:$tx_tag" >/dev/null 2>&1 || docker build -f "$REPO_ROOT/deploy/transactor/Dockerfile" -t "zandy/transactor:$tx_tag" "$REPO_ROOT"
    imgs+=("zandy/transactor:$tx_tag")
  fi
  docker save "${imgs[@]}" | gzip | rsh "gunzip | docker load"
  rsh "mkdir -p $ZANDY_HOME/releases/$release"
  rsync_to --delete "$REPO_ROOT/deploy/.state/static-$release/" "$TARGET:$ZANDY_HOME/releases/$release/static/"
else
  [ "$dirty_release" = 1 ] || [ "$(git -C "$REPO_ROOT" rev-parse --short=12 HEAD)" = "$release" ] || die "to build, check out commit $release first"
  "$OPS_DIR/check-versions.sh"
  if [ "$build_mode" = local ]; then
    need docker
    cd "$REPO_ROOT"
    export DOCKER_BUILDKIT=1
    docker build -f Dockerfile.backend -t "zandy/backend:$release" .
    docker build -f Dockerfile.ssr -t "zandy/ssr:$release" .
    rm -rf "$REPO_ROOT/deploy/.state/static-$release"; mkdir -p "$REPO_ROOT/deploy/.state"
    docker build -f Dockerfile.ssr --target static --output "type=local,dest=deploy/.state/static-$release" .
    imgs=("zandy/backend:$release" "zandy/ssr:$release")
    if [ "$have_tx" = no ]; then
      docker build -f deploy/transactor/Dockerfile -t "zandy/transactor:$tx_tag" .
      imgs+=("zandy/transactor:$tx_tag")
    fi
    echo "== uploading images"
    docker save "${imgs[@]}" | gzip | rsh "gunzip | docker load"
    rsh "mkdir -p $ZANDY_HOME/releases/$release"
    rsync_to --delete "$REPO_ROOT/deploy/.state/static-$release/" "$TARGET:$ZANDY_HOME/releases/$release/static/"
  else
    log "WARNING: building on the production server. The ClojureScript release build needs about 1.5 GB of RAM."
    [ "${ALLOW_REMOTE_BUILD:-}" = yes ] || die "remote build is risky; set ALLOW_REMOTE_BUILD=yes to accept"
    rsh "rm -rf $ZANDY_HOME/build/$release && mkdir -p $ZANDY_HOME/build/$release"
    git -C "$REPO_ROOT" archive "$release" | rsh "tar -x -C $ZANDY_HOME/build/$release"
    rsh "cd $ZANDY_HOME/build/$release && export DOCKER_BUILDKIT=1 &&
      docker build -f Dockerfile.backend -t zandy/backend:$release . &&
      docker build -f Dockerfile.ssr -t zandy/ssr:$release . &&
      mkdir -p $ZANDY_HOME/releases/$release &&
      docker build -f Dockerfile.ssr --target static --output type=local,dest=$ZANDY_HOME/releases/$release/static . &&
      { docker image inspect zandy/transactor:$tx_tag >/dev/null 2>&1 || docker build -f deploy/transactor/Dockerfile -t zandy/transactor:$tx_tag .; } &&
      cd .. && rm -rf build/$release"
  fi
fi

# ---- config files --------------------------------------------------------------------------------
echo "== uploading compose and proxy configuration"
rsync_to "$REPO_ROOT/deploy/compose.yaml" "$REPO_ROOT/deploy/compose.small.yaml" "$REPO_ROOT/deploy/compose.small-prod.yaml" "$REPO_ROOT/deploy/Caddyfile" "$TARGET:$proj/"
rsync_to "$REPO_ROOT/deploy/caddy/cloudflare-ips.conf" "$TARGET:$proj/caddy/"
rsh "mkdir -p $proj/mailsink" && rsync_to "$REPO_ROOT/deploy/mailsink/sink.py" "$TARGET:$proj/mailsink/"
# Only the Caddy site files of this host's roles (a staging-only host gets no production site).
rsh "mkdir -p $proj/caddy/sites"
for r in ${roles//,/ }; do rsync_to "$REPO_ROOT/deploy/caddy/sites/$r.caddy" "$TARGET:$proj/caddy/sites/"; done
rsh "cd $proj/caddy/sites && for f in *.caddy; do case \"\$f\" in $(for r in ${roles//,/ }; do printf '%s.caddy|' "$r"; done)__none__) ;; *) rm -f \"\$f\" ;; esac; done"
acme=$(env_get ACME_EMAIL)
if [ -n "$acme" ]; then rsh "printf 'email %s\n' '$acme' > $proj/caddy/acme-email.conf"; else rsh "rm -f $proj/caddy/acme-email.conf"; fi
rsh "test -s $proj/.env" || die "$proj/.env is missing (server-setup.sh creates it from deploy/env.host.example)"
rsh "grep -v '^#' $proj/.env | grep -q CHANGE_ME" && die "$proj/.env still contains CHANGE_ME placeholders"

if [ "$stage_only" = 1 ]; then
  echo "== stage-only: transactor and proxy"
  rcompose "up -d --wait transactor caddy"
  echo "== staged release $release on $TARGET (images, config, transactor, proxy). Run deploy.sh again without --stage-only once the secrets are in."
  exit 0
fi

# ---- static files: additive, earlier files stay 14 days -----------------------------------------
sdir="$ZANDY_HOME/static/$envname"
mkdir -p "$REPO_ROOT/deploy/.state"
prev_manifest="$REPO_ROOT/deploy/.state/$envname-previous-asset-manifest.edn"
rsh "cat $sdir/asset-manifest.edn 2>/dev/null || true" > "$prev_manifest"
echo "== adding static files to $sdir (nothing is deleted; files unused for 14 days are pruned)"
rsh "set -e; mkdir -p $sdir
  rsync -a $ZANDY_HOME/releases/$release/static/ $sdir/
  cd $ZANDY_HOME/releases/$release/static && find . -type f | while read -r f; do touch -c \"$sdir/\$f\"; done
  cd $sdir && find . -type f -mtime +14 ! -name asset-manifest.edn -delete && find . -type d -empty -delete
  chmod -R a+rX $sdir"

# ---- release bookkeeping -------------------------------------------------------------------------
key=$([ "$envname" = production ] && echo PROD || echo STAGING)
old=$(env_get "${key}_RELEASE")
if [ "$old" != "$release" ]; then env_set "PREVIOUS_${key}_RELEASE" "$old"; fi
env_set "${key}_RELEASE" "$release"

# ---- start shared services, migrate, bootstrap ---------------------------------------------------
echo "== transactor and proxy"
rcompose "up -d --wait transactor caddy"
# Staging mail sink (compose.small.yaml only): started explicitly because backends are started with --no-deps.
if rcompose "--profile $envname config --services" | grep -qx mailsink; then rcompose "--profile $envname up -d --no-deps mailsink"; fi
rcompose "exec -T caddy caddy reload --config /etc/caddy/Caddyfile" || log "caddy reload failed (first start?); continuing"

if [ "$migrate" = 1 ]; then
  echo "== migrations on $envname (before any instance starts)"
  rcompose "--profile $envname run --rm --no-deps $TASK_OPTS $backend_svc migrate"
fi
echo "== owner account (does nothing when one exists or OWNER_INITIAL_PASSWORD is absent)"
rcompose "--profile $envname run --rm --no-deps $TASK_OPTS $backend_svc bootstrap-owner"
if [ "$seed" = 1 ] && [ "$envname" = staging ]; then
  echo "== seeding the sample catalogue on staging"
  rcompose "--profile $envname run --rm --no-deps $TASK_OPTS $backend_svc seed"
fi

# ---- restart in order: backend, then ssr ---------------------------------------------------------
echo "== backend ($backend_svc)"
rcompose "--profile $envname up -d --wait --no-deps $backend_svc"
echo "== ssr ($ssr_svc)"
rcompose "--profile $envname up -d --wait --no-deps $ssr_svc"
rsh "echo $release > $ZANDY_HOME/state/$envname-release"

# ---- retire old releases (keep the five newest, never the running or previous ones) -------------
rsh "cd $ZANDY_HOME/releases && ls -1t | tail -n +6 | while read -r r; do
  case \"\$r\" in $(env_get PROD_RELEASE)|$(env_get STAGING_RELEASE)|$(env_get PREVIOUS_PROD_RELEASE)|$(env_get PREVIOUS_STAGING_RELEASE)) ;; *) rm -rf \"\$r\"; docker rmi zandy/backend:\$r zandy/ssr:\$r >/dev/null 2>&1 || true ;; esac
done"

# ---- checks --------------------------------------------------------------------------------------
host=$(rsh "sed -n 's/^$([ "$envname" = production ] && echo PROD || echo STAGING)_HOST=//p' $proj/.env")
url="https://$host"
if [ "${SKIP_PUBLIC_CHECKS:-}" = yes ]; then
  echo "== deployed $release to $envname; public checks skipped (SKIP_PUBLIC_CHECKS=yes: DNS or the certificate are not ready yet). Run check-routing.sh $url when they are."
  exit 0
fi
echo "== checks against $url"
sleep 5
"$OPS_DIR/check-routing.sh" "$url" || die "routing check failed"
"$OPS_DIR/check-static-retention.sh" "$url" "$prev_manifest" || die "previous bundle is gone"
echo "== deployed $release to $envname at $url"
echo "   still to run: check-cache-headers.sh, check-edge-cache.sh (once Cloudflare is in front), two-instance-smoke.sh on staging"
