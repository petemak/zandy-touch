#!/usr/bin/env bash
# Shows WHICH secrets are filled in and how long each is, never the value. Runs ON THE SERVER with sudo.
#   sudo /srv/zandy/bin/secret-lengths.sh production|staging
# A value that is still a placeholder (CHANGE_ME...) or missing is reported as NOT SET.
set -euo pipefail
dir=${ZANDY_ENV_DIR:-/etc/zandy}
case "${1:-}" in
  production) list="production.env:OWNER_INITIAL_PASSWORD production.env:SMTP_PASSWORD backup.env:RCLONE_CONFIG_R2_ENDPOINT backup.env:RCLONE_CONFIG_R2_ACCESS_KEY_ID backup.env:RCLONE_CONFIG_R2_SECRET_ACCESS_KEY production.env:SESSION_HMAC_SECRET transactor.env:DATOMIC_STORAGE_PASSWORD" ;;
  staging)    list="staging.env:OWNER_INITIAL_PASSWORD staging.env:SMTP_PASSWORD backup.env:RCLONE_CONFIG_R2_ENDPOINT backup.env:RCLONE_CONFIG_R2_ACCESS_KEY_ID backup.env:RCLONE_CONFIG_R2_SECRET_ACCESS_KEY staging.env:SESSION_HMAC_SECRET" ;;
  *) echo "usage: secret-lengths.sh production|staging" >&2; exit 2 ;;
esac
for item in $list; do
  f=${item%%:*}; n=${item##*:}
  v=$(sed -n "s/^$n=//p" "$dir/$f" | tail -n 1)
  if [ -z "$v" ] || [[ "$v" == CHANGE_ME* ]]; then printf '%-14s %-36s NOT SET\n' "$f" "$n"
  else printf '%-14s %-36s set, %d characters\n' "$f" "$n" "${#v}"; fi
done
