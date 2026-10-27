#!/usr/bin/env bash
# Nightly backup, run ON THE SERVER by deploy/systemd/zandy-backup.timer (or by hand).
#   /srv/zandy/bin/backup.sh [--local-only] [--staging]
# --staging backs up the STAGING database and media instead, under the R2 prefix staging-trial/. It exists to
# prove the mechanism (backup, upload, restore) on a staging-only host, whose data is sample data only. On a
# host without the production role, running without --staging does nothing and exits 0, and the timers are
# not enabled; they are enabled on the server that runs production.
# 1. Datomic backup-db of the production database into /srv/zandy/datomic/backup/<db> (incremental).
# 2. A dated archive of that directory goes to R2: <bucket>/datomic/<db>/daily/<db>-YYYY-MM-DD.tar.gz;
#    on Sundays it is also copied (inside R2) to .../weekly/<db>-YYYY-Www.tar.gz.
#    Retention: the newest 7 daily and 4 weekly archives.
# 3. MEDIA_DIR is mirrored to <bucket>/media/current; files that change or disappear are moved to
#    <bucket>/media/changed/YYYY-MM-DD/ and kept 28 days (covers 7 daily and 4 weekly restore points).
# Credentials: /etc/zandy/backup.env (rclone RCLONE_CONFIG_R2_* variables) and /etc/zandy/transactor.env.
# Staging is not backed up (it holds sample data).
set -euo pipefail
local_only=0; staging=0
for a in "$@"; do case "$a" in --local-only) local_only=1 ;; --staging) staging=1 ;; *) echo "unknown option $a" >&2; exit 2 ;; esac; done

ZANDY_HOME=${ZANDY_HOME:-/srv/zandy}
ENV_DIR=${ZANDY_ENV_DIR:-/etc/zandy}
set -a; . "$ENV_DIR/backup.env"; set +a
roles=$(sed -n 's/^HOST_ROLES=//p' "$ZANDY_HOME/compose/.env" 2>/dev/null || true)
if [ "$staging" = 0 ]; then
  case ",${roles:-production}," in *",production,"*) ;; *) echo "backup: this host has no production role ($roles); nothing to back up. Use --staging to test the mechanism."; exit 0 ;; esac
fi
if [ "$staging" = 1 ]; then DB=zandy-staging; MEDIA=$ZANDY_HOME/media/staging; RP=staging-trial/; else DB=${BACKUP_DB:-zandy}; MEDIA=$ZANDY_HOME/media/production; RP=; fi
DATE=$(date -u +%F); WEEK=$(date -u +%G-W%V); DOW=$(date -u +%u)
BUCKET=${BACKUP_BUCKET:?BACKUP_BUCKET missing in backup.env}
R=r2:$BUCKET/${RP%/}
R=${R%/}
STAGE=$ZANDY_HOME/backups/stage
mkdir -p "$STAGE"
log() { printf '%s backup: %s\n' "$(date -u +%FT%TZ)" "$*"; }

pw=$(sed -n 's/^DATOMIC_STORAGE_PASSWORD=//p' "$ENV_DIR/transactor.env")
[ -n "$pw" ] || { log "DATOMIC_STORAGE_PASSWORD not found"; exit 1; }

log "datomic backup-db $DB"
cd "$ZANDY_HOME/compose"
BACKUP_URI="datomic:dev://transactor:4334/$DB?password=$pw" \
  docker compose --profile tools run --rm -T -e BACKUP_URI datomic-tools sh -c \
  "exec /opt/datomic/bin/datomic -Xms64m -Xmx384m backup-db \"\$BACKUP_URI\" file:/var/lib/datomic/backup/$DB"

if [ "$local_only" = 1 ]; then log "local-only: done"; exit 0; fi

archive="$STAGE/$DB-$DATE.tar.gz"
tar -C "$ZANDY_HOME/datomic/backup" -czf "$archive" "$DB"
size=$(stat -c %s "$archive")
log "archive $archive $size bytes"
[ "$size" -gt 1024 ] || { log "archive suspiciously small"; exit 1; }
[ "$size" -lt 2000000000 ] || log "WARNING: archive over 2 GB; R2 free tier is 10 GB. Revisit the retention plan."

rclone copyto "$archive" "$R/datomic/$DB/daily/$DB-$DATE.tar.gz"
if [ "$DOW" = 7 ]; then rclone copyto "$R/datomic/$DB/daily/$DB-$DATE.tar.gz" "$R/datomic/$DB/weekly/$DB-$WEEK.tar.gz"; fi
rm -f "$archive"

prune() {   # prune <path under bucket> <how many newest to keep>
  rclone lsf --files-only "$R/$1" | sort | head -n "-$2" | while read -r f; do
    [ -n "$f" ] && { log "prune $1/$f"; rclone deletefile "$R/$1/$f"; }
  done
}
prune "datomic/$DB/daily" 7
prune "datomic/$DB/weekly" 4

# ---- media ------------------------------------------------------------------------------------
local_files=$(find "$MEDIA" -type f | wc -l)
remote_files=$(rclone lsf -R --files-only "$R/media/current" 2>/dev/null | wc -l || echo 0)
if [ "$local_files" -eq 0 ] && [ "$remote_files" -gt 0 ]; then
  log "REFUSING to sync: $MEDIA is empty but the backup holds $remote_files files (volume not mounted?)"; exit 1
fi
log "media sync ($local_files files)"
rclone sync "$MEDIA" "$R/media/current" --backup-dir "$R/media/changed/$DATE" --transfers 4 --checksum
cutoff=$(date -u -d '28 days ago' +%F)
rclone lsf --dirs-only "$R/media/changed" 2>/dev/null | tr -d '/' | while read -r d; do
  if [ -n "$d" ] && [[ "$d" < "$cutoff" ]]; then log "prune media/changed/$d"; rclone purge "$R/media/changed/$d"; fi
done

date -u +%FT%TZ > "$ZANDY_HOME/state/last-backup${RP:+-staging}"
log "done"
