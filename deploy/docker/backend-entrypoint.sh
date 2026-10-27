#!/usr/bin/env bash
# Entry point of the backend image.
#   serve (default)        start the server (profile from ZANDY_PROFILE, prod in the image)
#   migrate                apply pending database migrations, then exit   (clojure -M:migrate)
#   bootstrap-owner        create the owner account if none exists, exit  (clojure -M:bootstrap-owner)
#   seed                   load the sample catalogue, exit                (clojure -M:seed)
#   send-test-mail         send one test message to OWNER_EMAIL, exit     (clojure -M:send-test-mail)
#   check                  run the start-up checks only, exit
#   clj <file.clj>         run a script with the application classpath
set -euo pipefail

log() { printf '{"level":"%s","event":"entrypoint","message":"%s"}\n' "$1" "$2"; }
die() { log error "$1"; exit "${2:-70}"; }

# ---- Start-up checks (architecture-review.md section 9: the app itself does not check libvips) ----
# A missing vipsthumbnail would otherwise show up only as a failed first photo upload.
check_runtime() {
  local tool
  for tool in vipsthumbnail vipsheader vips; do
    command -v "$tool" >/dev/null 2>&1 || die "$tool is not installed: image variants cannot be produced" 70
  done
  # The WebP writer must exist too: make one tiny WebP and read its header back.
  local tmp probe
  tmp=$(mktemp -d)
  probe="$tmp/probe.webp"
  if ! vips black "$probe" 8 8 >/dev/null 2>&1 || ! vipsheader "$probe" >/dev/null 2>&1; then
    rm -rf "$tmp"
    die "libvips cannot write WebP files (libwebp support missing)" 70
  fi
  rm -rf "$tmp"
  # MEDIA_DIR must be writable by this user, or uploads fail later.
  : "${MEDIA_DIR:?MEDIA_DIR is not set}"
  [ -d "$MEDIA_DIR" ] || die "MEDIA_DIR $MEDIA_DIR does not exist (bind mount missing?)" 71
  local t="$MEDIA_DIR/.write-test.$$"
  if ! ( : > "$t" ) 2>/dev/null; then
    die "MEDIA_DIR $MEDIA_DIR is not writable by uid $(id -u); chown it to 10001" 71
  fi
  rm -f "$t"
  [ "${ZANDY_PROFILE:-prod}" = "prod" ] || die "ZANDY_PROFILE must be prod in the image, got ${ZANDY_PROFILE}" 72
}

CP=$(cat /app/classpath)
cd /app

# Runs <namespace>/-main as a one-shot task and exits explicitly. The Datomic peer keeps non-daemon
# threads alive, so a plain `-m <ns>` of a one-shot -main would never end (lead: see deploy report).
run_task() {
  local ns=$1
  # shellcheck disable=SC2086  # JAVA_OPTS is a list of options on purpose
  exec java ${JAVA_OPTS:-} -cp "$CP" clojure.main -e \
    "(require '$ns) (try (@(ns-resolve '$ns '-main)) (System/exit 0) (catch Throwable t (.printStackTrace t) (System/exit 1)))"
}

cmd=${1:-serve}
[ $# -gt 0 ] && shift

case "$cmd" in
  serve)
    check_runtime
    # shellcheck disable=SC2086
    exec java ${JAVA_OPTS:-} -cp "$CP" clojure.main -m zandy.platform.system
    ;;
  check)
    check_runtime
    log info "runtime checks passed"
    ;;
  migrate)         run_task zandy.platform.migrations ;;
  bootstrap-owner) run_task zandy.features.accounts.bootstrap ;;
  seed)            check_runtime; run_task zandy.seed.core ;;
  send-test-mail)  run_task zandy.platform.mailer ;;
  clj)
    # shellcheck disable=SC2086
    exec java ${JAVA_OPTS:-} -cp "$CP" clojure.main "$@"
    ;;
  *)
    exec "$cmd" "$@"
    ;;
esac
