#!/usr/bin/env bash
# Acceptance check D-7 and deploy preflight. Runs ON THE SERVER; reads /etc/zandy/*.env.
#   check-env.sh staging|production
# Never prints a value. Fails when: a CHANGE_ME placeholder remains, SESSION_HMAC_SECRET is shorter than 32,
# the environment names the wrong database, SITE_BASE_URL differs between backend and ssr, staging is
# indexable, API_BASE_URL points at the wrong proxy listener.
set -euo pipefail
envname=${1:?usage: check-env.sh staging|production}
dir=${ZANDY_ENV_DIR:-/etc/zandy}
case "$envname" in
  staging)    be=$dir/staging.env;    ssr=$dir/ssr.staging.env;    db=zandy-staging; listener=8089 ;;
  production) be=$dir/production.env; ssr=$dir/ssr.production.env; db=zandy;         listener=8088 ;;
  *) echo "environment must be staging or production" >&2; exit 2 ;;
esac
bad=0
say_bad() { echo "  FAIL  $*"; bad=1; }
say_ok()  { echo "  PASS  $*"; }
get() { sed -n "s/^$2=//p" "$1" | tail -n 1; }

for f in "$be" "$ssr" "$dir/transactor.env"; do
  [ -r "$f" ] || { say_bad "cannot read $f"; continue; }
  if grep -v '^[[:space:]]*#' "$f" | grep -q 'CHANGE_ME'; then
    say_bad "$f still has placeholders: $(grep -v '^[[:space:]]*#' "$f" | grep 'CHANGE_ME' | cut -d= -f1 | tr '\n' ' ')"
  else say_ok "$f has no placeholders"; fi
done
[ "$bad" = 0 ] || exit 1

uri=$(get "$be" DATOMIC_URI)
case "$uri" in
  datomic:dev://transactor:4334/"$db"\?password=*) say_ok "DATOMIC_URI names database $db" ;;
  *) say_bad "DATOMIC_URI must be datomic:dev://transactor:4334/$db?password=..." ;;
esac
pw=${uri#*password=}
[ "$pw" = "$(get "$dir/transactor.env" DATOMIC_STORAGE_PASSWORD)" ] && say_ok "DATOMIC_URI password matches transactor.env" || say_bad "DATOMIC_URI password differs from DATOMIC_STORAGE_PASSWORD"
[ "$(get "$be" SESSION_HMAC_SECRET | wc -c)" -ge 33 ] && say_ok "SESSION_HMAC_SECRET is 32+ characters" || say_bad "SESSION_HMAC_SECRET shorter than 32 characters"
for k in OWNER_EMAIL SMTP_USER SMTP_PASSWORD SITE_BASE_URL; do [ -n "$(get "$be" $k)" ] && say_ok "$k set" || say_bad "$k missing in $be"; done
[ "$(get "$be" SITE_BASE_URL)" = "$(get "$ssr" SITE_BASE_URL)" ] && say_ok "SITE_BASE_URL equal in backend and ssr files" || say_bad "SITE_BASE_URL differs between $be and $ssr"
[ "$(get "$ssr" API_BASE_URL)" = "http://caddy:$listener" ] && say_ok "API_BASE_URL is http://caddy:$listener" || say_bad "API_BASE_URL must be http://caddy:$listener"
ind=$(get "$ssr" SITE_INDEXABLE)
if [ "$envname" = staging ]; then
  [ "$ind" = false ] && say_ok "SITE_INDEXABLE=false" || say_bad "staging must have SITE_INDEXABLE=false"
  case "$(get "$be" SITE_BASE_URL)" in https://*) say_ok "SITE_BASE_URL is https" ;; *) say_bad "SITE_BASE_URL must start with https://" ;; esac
else
  case "$ind" in true|false) say_ok "SITE_INDEXABLE=$ind" ;; *) say_bad "SITE_INDEXABLE must be true or false" ;; esac
  [ "$(get "$be" SITE_BASE_URL)" = "https://zandytouch.com" ] || echo "  NOTE  production SITE_BASE_URL is $(get "$be" SITE_BASE_URL) (expected https://zandytouch.com at go-live)"
fi
[ "$bad" = 0 ] && echo "env check $envname: PASS" || { echo "env check $envname: FAIL"; exit 1; }
