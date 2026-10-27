#!/usr/bin/env bash
# Entry point of the ssr image. `serve` (default) starts the frontend server.
set -euo pipefail

log() { printf '{"level":"%s","service":"ssr","event":"entrypoint","message":"%s"}\n' "$1" "$2"; }
die() { log error "$1"; exit "${2:-70}"; }

: "${STATIC_DIR:?STATIC_DIR is not set}"
: "${API_BASE_URL:?API_BASE_URL is not set (the internal proxy address)}"
: "${SITE_BASE_URL:?SITE_BASE_URL is not set}"

# The server renders every page with the hashed file names from asset-manifest.edn. Without the
# bind-mounted static directory it would start and then serve pages that point at missing files.
[ -d "$STATIC_DIR" ] || die "STATIC_DIR $STATIC_DIR is missing (bind mount of /srv/zandy/static/<env>?)" 71
[ -s "$STATIC_DIR/asset-manifest.edn" ] || die "$STATIC_DIR/asset-manifest.edn is missing: run scripts/ops/deploy.sh to copy the static files" 71

case "${SITE_INDEXABLE:-false}" in
  true|false) ;;
  *) die "SITE_INDEXABLE must be true or false" 72 ;;
esac

CP=$(cat /app/classpath)
cd /app
cmd=${1:-serve}
[ $# -gt 0 ] && shift
case "$cmd" in
  serve)
    # shellcheck disable=SC2086  # JAVA_OPTS is a list of options on purpose
    exec java ${JAVA_OPTS:-} -cp "$CP" clojure.main -m zandy.ssr.system
    ;;
  *)
    exec "$cmd" "$@"
    ;;
esac
