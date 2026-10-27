#!/usr/bin/env bash
# Acceptance check D-3: a backup restores, and the counts match the live data.
#   Add --staging on a staging-only host: tests the staging database, media and R2 prefix staging-trial/.
#   /srv/zandy/bin/restore-test.sh            fresh local backup -> restore into scratch database
#                                              "zandy-restore-test" -> orders, products, photos, users and
#                                              media file counts must be EQUAL to live.
#   /srv/zandy/bin/restore-test.sh --remote   also: download the newest daily archive and the media mirror
#                                              from R2 and restore those; counts must not exceed live and
#                                              must not be zero (the backup is a day old, so equality is not
#                                              expected if orders arrived since).
# The scratch database and scratch directories are removed at the end, pass or fail.
set -euo pipefail
remote=0; staging=0
for a in "$@"; do case "$a" in --remote) remote=1 ;; --staging) staging=1 ;; *) echo "unknown option $a" >&2; exit 2 ;; esac; done
ZANDY_HOME=${ZANDY_HOME:-/srv/zandy}
ENV_DIR=${ZANDY_ENV_DIR:-/etc/zandy}
OPS=$(cd "$(dirname "$0")" && pwd)
set -a; . "$ENV_DIR/backup.env"; set +a
DB=${BACKUP_DB:-zandy}; SCRATCH=zandy-restore-test
MEDIA_ENV=production; RP=; SVC_FLAG=
if [ "$staging" = 1 ]; then DB=zandy-staging; MEDIA_ENV=staging; RP=staging-trial/; SVC_FLAG=--staging; fi
RB=r2:$BACKUP_BUCKET/${RP%/}; RB=${RB%/}
pw=$(sed -n 's/^DATOMIC_STORAGE_PASSWORD=//p' "$ENV_DIR/transactor.env")
cd "$ZANDY_HOME/compose"
rel=$(sed -n 's/^PROD_RELEASE=//p' .env)
if [ "$staging" = 0 ] && [ -n "$rel" ] && [ "$rel" != none ]; then svc=backend; prof=production; else svc=backend-staging; prof=staging; fi
LIVE="datomic:dev://transactor:4334/$DB?password=$pw"
# Datomic refuses to restore a backup into a storage that already holds that database (":restore/collision", even under
# another name). So the restore goes into a SECOND, throw-away transactor with empty storage, which is also what a real
# recovery on a new server looks like. It lives only for this test (container zandy-restore-tx, data on a tmpfs).
RTX=zandy-restore-tx
TEST="datomic:dev://$RTX:4334/$SCRATCH?password=$pw"
BK=$ZANDY_HOME/datomic/backup
work=$ZANDY_HOME/backups/restore-test
fails=0
cleanup() {
  docker rm -f "$RTX" >/dev/null 2>&1 || true
  rm -rf "$BK/$SCRATCH" "$work" 2>/dev/null || sudo rm -rf "$BK/$SCRATCH" "$work" 2>/dev/null || true
}
trap cleanup EXIT

count() {   # count: prints the counts of $COUNT_URI
  docker compose --profile "$prof" run --rm -T --no-deps \
    -e COUNT_URI -e COUNT_DELETE -v "$OPS/count_db.clj:/tmp/count_db.clj:ro" "$svc" clj /tmp/count_db.clj 2>/dev/null | tail -n 1
}
tools() {   # tools <script> : runs sh -c in the datomic-tools container with TEST/LIVE in env
  LIVE_URI=$LIVE TEST_URI=$TEST docker compose --profile tools run --rm -T -e LIVE_URI -e TEST_URI datomic-tools sh -c "$1"
}
field() { printf '%s\n' "$1" | tr ' ' '\n' | sed -n "s/^$2=//p"; }
compare() {   # compare <label> <live-counts> <restored-counts> <equal|atmost>
  for k in orders products photos users; do
    l=$(field "$2" $k); r=$(field "$3" $k)
    if [ "$4" = equal ] && [ "$l" = "$r" ]; then echo "  PASS  $1 $k: live $l restored $r"
    elif [ "$4" = atmost ] && [ "$r" -le "$l" ] && { [ "$r" -gt 0 ] || [ "$l" -eq 0 ]; }; then echo "  PASS  $1 $k: live $l restored $r"
    else echo "  FAIL  $1 $k: live $l restored $r"; fails=$((fails + 1)); fi
  done
}

echo "D-3 restore test"
docker rm -f "$RTX" >/dev/null 2>&1 || true     # a transactor left from an interrupted run
txtag=$(sed -n 's/^TRANSACTOR_TAG=//p' .env)
docker run -d --name "$RTX" --network zandy_zandy --init --memory 700m --memory-swap 700m --cap-drop ALL \
  --security-opt no-new-privileges:true --env-file "$ENV_DIR/transactor.env" -e "DATOMIC_ALT_HOST=$RTX" \
  -e DATOMIC_MEMORY_INDEX_THRESHOLD=16m -e DATOMIC_MEMORY_INDEX_MAX=128m -e DATOMIC_OBJECT_CACHE_MAX=64m \
  --tmpfs /run/datomic:mode=0700,uid=10001,gid=10001 --tmpfs /var/lib/datomic/log:uid=10001,gid=10001 \
  --tmpfs /var/lib/datomic/data:size=512m,uid=10001,gid=10001 --tmpfs /tmp:uid=10001,gid=10001 \
  "zandy/transactor:$txtag" transactor -Xmx384m -Xms384m -XX:ActiveProcessorCount=8 >/dev/null
for _ in $(seq 1 60); do docker exec "$RTX" curl -fsS http://127.0.0.1:9999/health >/dev/null 2>&1 && break; sleep 2; done
docker exec "$RTX" curl -fsS http://127.0.0.1:9999/health >/dev/null 2>&1 || { echo "  FAIL  scratch transactor did not start"; exit 1; }
echo "  scratch transactor $RTX up (empty storage)"

if [ "$remote" = 0 ]; then
  "$OPS/backup.sh" --local-only $SVC_FLAG
  rm -rf "$BK/$SCRATCH"; cp -a "$BK/$DB" "$BK/$SCRATCH"
  live=$(COUNT_URI=$LIVE count)
else
  mkdir -p "$work"
  latest=$(rclone lsf --files-only "$RB/datomic/$DB/daily" | sort | tail -n 1)
  [ -n "$latest" ] || { echo "  FAIL  no daily archive in R2"; exit 1; }
  echo "  using $latest"
  rclone copyto "$RB/datomic/$DB/daily/$latest" "$work/a.tar.gz"
  tar -C "$work" -xzf "$work/a.tar.gz"
  rm -rf "$BK/$SCRATCH"; mv "$work/$DB" "$BK/$SCRATCH"
  live=$(COUNT_URI=$LIVE count)
fi

tools "exec /opt/datomic/bin/datomic -Xms64m -Xmx384m restore-db file:/var/lib/datomic/backup/$SCRATCH \"\$TEST_URI\""
restored=$(COUNT_URI=$TEST count)
echo "  live     $live"; echo "  restored $restored"
mode=equal; [ "$remote" = 1 ] && mode=atmost
compare database "$live" "$restored" $mode

# media
media_live=$(find "$ZANDY_HOME/media/$MEDIA_ENV" -type f | wc -l)
if [ "$remote" = 1 ]; then
  mkdir -p "$work/media"
  rclone copy "$RB/media/current" "$work/media"
  media_back=$(find "$work/media" -type f | wc -l)
  if [ "$media_back" -le "$media_live" ] && { [ "$media_back" -gt 0 ] || [ "$media_live" -eq 0 ]; }; then echo "  PASS  media files: live $media_live backup $media_back"; else echo "  FAIL  media files: live $media_live backup $media_back"; fails=$((fails + 1)); fi
else
  media_back=$(rclone lsf -R --files-only "$RB/media/current" | wc -l)
  [ "$media_back" -eq "$media_live" ] && echo "  PASS  media files: live $media_live backup $media_back" || { echo "  FAIL  media files: live $media_live backup $media_back (run backup.sh first)"; fails=$((fails + 1)); }
fi

[ "$fails" -eq 0 ] && echo "D-3 PASS" || { echo "D-3 FAIL ($fails)"; exit 1; }
