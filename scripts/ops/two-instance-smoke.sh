#!/usr/bin/env bash
# Fitness check R5 / acceptance D-8: two backend and two ssr instances work together. Run ON THE SERVER
# against STAGING's transactor, using a throw-away database (zandy-smoke-<time>) that is deleted at the end:
#   /srv/zandy/bin/two-instance-smoke.sh
# It starts, from the staging release image, two backends (A, B) and two ssr (A, B) as extra containers on
# the compose network, plus an SMTP sink (a stand-in for Gmail so the real SMTP mailer runs but no mail leaves).
# Checks:
#   1. a session created on backend A is accepted by backend B at once
#   2. an order placed on A with Idempotency-Key K and replayed on B gives Idempotent-Replayed: true, and
#      the database holds exactly one order
#   3. five failed logins on A make the sixth, on B, answer 429
#   4. every outbox message is sent exactly once although both backends run the sender
#   5. both ssr instances return identical HTML for the same URLs
# Memory: adds about 1.6 GB for a few minutes (2 backends at -Xmx384m, 2 ssr at -Xmx192m, sink at -Xmx64m).
set -euo pipefail
ZANDY_HOME=${ZANDY_HOME:-/srv/zandy}
OPS=$(cd "$(dirname "$0")" && pwd)
. "$OPS/lib.sh"
need docker curl bb openssl

cd "$ZANDY_HOME/compose"
rel=$(sed -n 's/^STAGING_RELEASE=//p' .env)
[ -n "$rel" ] && [ "$rel" != none ] || die "no staging release is deployed"
img=zandy/backend:$rel; ssrimg=zandy/ssr:$rel
net=zandy_zandy
pw=$(sed -n 's/^DATOMIC_STORAGE_PASSWORD=//p' "${ZANDY_ENV_DIR:-/etc/zandy}/transactor.env")
db=zandy-smoke-$(date +%s)
uri="datomic:dev://transactor:4334/$db?password=$pw"
owner_email=owner-smoke@example.invalid
owner_pw="Sm0ke-$(openssl rand -hex 10)-Zz"
secret=$(openssl rand -hex 24)
vol=zandy-smoke-media-$$
names=(smoke-sink smoke-be-a smoke-be-b smoke-ssr-a smoke-ssr-b)

# On a 4 GB host the live staging backend and ssr are stopped for the test (the test needs about 1.6 GB of its
# own) and started again afterwards. Set SMOKE_KEEP_STAGING=yes to skip that on a larger host.
mem_mb=$(awk '/MemTotal/ {print int($2/1024)}' /proc/meminfo)
paused=0
if [ "$mem_mb" -lt 6000 ] && [ "${SMOKE_KEEP_STAGING:-}" != yes ]; then
  log "small host ($mem_mb MB): pausing staging backend and ssr during the test"
  docker compose --profile staging stop backend-staging ssr-staging >/dev/null
  paused=1
fi

cleanup() {
  if [ "$paused" = 1 ]; then ( cd "$ZANDY_HOME/compose" && docker compose --profile staging up -d --no-deps backend-staging ssr-staging >/dev/null 2>&1 ) || true; fi
  docker rm -f "${names[@]}" >/dev/null 2>&1 || true
  docker run --rm --network "$net" -e COUNT_URI="$uri" -e COUNT_DELETE=1 \
    -v "$OPS/count_db.clj:/tmp/count_db.clj:ro" "$img" clj /tmp/count_db.clj >/dev/null 2>&1 || true
  docker volume rm "$vol" >/dev/null 2>&1 || true
  rm -f "$FETCH_BODY" "${jar:-/dev/null}"
}
trap cleanup EXIT
jar=$(mktemp)

backend_env=(-e ZANDY_PROFILE=prod -e PORT=8080 -e "DATOMIC_URI=$uri" -e "SESSION_HMAC_SECRET=$secret"
  -e "OWNER_EMAIL=$owner_email" -e "OWNER_INITIAL_PASSWORD=$owner_pw" -e SMTP_HOST=smoke-sink -e SMTP_PORT=2525
  -e SMTP_USER=smoke -e SMTP_PASSWORD=smoke -e MAIL_DAILY_CAP=400 -e SITE_BASE_URL=https://smoke.example.invalid
  -e MEDIA_DIR=/data/media -e "JAVA_OPTS=-Xmx384m -Xss512k -XX:+UseSerialGC -XX:MaxMetaspaceSize=192m")
run_oneshot() { docker run --rm --network "$net" "${backend_env[@]}" -v "$vol:/data/media" "$img" "$@"; }
ip_of() { docker inspect -f "{{(index .NetworkSettings.Networks \"$net\").IPAddress}}" "$1"; }
wait_http() { local url=$1; for _ in $(seq 1 90); do curl -fsS -o /dev/null "$url" 2>/dev/null && return 0; sleep 2; done; return 1; }

echo "R5 two-instance smoke test (release $rel, database $db)"
docker volume create "$vol" >/dev/null
docker run -d --name smoke-sink --network "$net" -e "JAVA_OPTS=-Xmx64m -XX:+UseSerialGC" -v "$OPS/smtp_sink.clj:/tmp/smtp_sink.clj:ro" "$img" clj /tmp/smtp_sink.clj 2525 >/dev/null
run_oneshot migrate >/dev/null && pass "migrations applied to $db"
run_oneshot bootstrap-owner >/dev/null && pass "owner created"
run_oneshot seed >/dev/null && pass "sample catalogue loaded"

for x in a b; do
  docker run -d --name "smoke-be-$x" --network "$net" "${backend_env[@]}" -v "$vol:/data/media" "$img" serve >/dev/null
done
ipa=$(ip_of smoke-be-a); ipb=$(ip_of smoke-be-b)
wait_http "http://$ipa:8080/api/healthz" && wait_http "http://$ipb:8080/api/healthz" && pass "both backends healthy" || { fail "a backend did not become healthy"; docker logs smoke-be-a 2>&1 | tail -5; summary "R5"; exit 1; }
for x in a b; do
  docker run -d --name "smoke-ssr-$x" --network "$net" -e PORT=8081 -e "API_BASE_URL=http://smoke-be-$x:8080" \
    -e SITE_BASE_URL=https://smoke.example.invalid -e SITE_INDEXABLE=false -e API_TIMEOUT_MS=3000 -e STATIC_DIR=/srv/static \
    -e "JAVA_OPTS=-Xmx192m -Xss512k -XX:+UseSerialGC" -v "$ZANDY_HOME/static/staging:/srv/static:ro" "$ssrimg" serve >/dev/null
done
sa=$(ip_of smoke-ssr-a); sb=$(ip_of smoke-ssr-b)
wait_http "http://$sa:8081/healthz" && wait_http "http://$sb:8081/healthz" && pass "both ssr instances healthy" || fail "an ssr instance did not become healthy"

A=http://$ipa:8080; B=http://$ipb:8080

echo "-- 1. session created on A is valid on B"
fetch "$A/api/auth/sessions" -c "$jar" -X POST -H 'Content-Type: application/json' -d "{\"email\":\"$owner_email\",\"password\":\"$owner_pw\"}"
[ "$FETCH_STATUS" = 201 ] && pass "login on A: 201" || fail "login on A: HTTP $FETCH_STATUS"
# The session cookie is Secure and the instances are addressed over plain http by container address, so curl's cookie jar
# neither keeps it nor would send it to B (a different host). Take it from Set-Cookie and send it by hand, as a visitor's
# browser does behind the proxy (one host name, two instances).
cookie=$(header set-cookie | cut -d';' -f1)
fetch "$B/api/auth/sessions/current" -H "Cookie: $cookie"
[ "$FETCH_STATUS" = 200 ] && pass "session read on B straight away: 200" || fail "session read on B: HTTP $FETCH_STATUS"

echo "-- 2. order on A, replay on B"
fetch "$A/api/products"; pid=$(json_get items 0 id); price=$(json_get items 0 price); slug=$(json_get items 0 slug)
fetch "$A/api/policies/orderTerms"; ver=$(json_get version)
key=$(cat /proc/sys/kernel/random/uuid)
body=$(cat <<JSON
{"customer":{"name":"Smoke Test","phone":"0771234567","email":"smoke-customer@example.invalid","preferredContact":"phone"},
 "delivery":{"countryCode":"UG","city":"Kampala","area":"Ntinda","addressLine":"Plot 1, Smoke Rd"},
 "paymentMethod":"cash","items":[{"productId":"$pid","quantity":1,"expectedUnitPrice":$price}],
 "agreement":{"type":"orderTerms","version":"$ver"},"website":""}
JSON
)
fetch "$A/api/orders" -X POST -H 'Content-Type: application/json' -H "Idempotency-Key: $key" -d "$body"
id_a=$(json_get order id)
[ "$FETCH_STATUS" = 201 ] && pass "order on A: 201" || fail "order on A: HTTP $FETCH_STATUS"
fetch "$B/api/orders" -X POST -H 'Content-Type: application/json' -H "Idempotency-Key: $key" -d "$body"
id_b=$(json_get order id)
if [ "$FETCH_STATUS" = 200 ] && [ "$(header idempotent-replayed)" = true ] && [ -n "$id_a" ] && [ "$id_a" = "$id_b" ]; then pass "replay on B: 200, Idempotent-Replayed: true, same order"; else fail "replay on B: HTTP $FETCH_STATUS, replayed '$(header idempotent-replayed)', ids '$id_a' '$id_b'"; fi
counts=$(docker run --rm --network "$net" -e COUNT_URI="$uri" -v "$OPS/count_db.clj:/tmp/count_db.clj:ro" "$img" clj /tmp/count_db.clj 2>/dev/null | tail -n 1)
case "$counts" in orders=1\ *) pass "database holds exactly one order ($counts)" ;; *) fail "expected orders=1, got: $counts" ;; esac

echo "-- 5. ssr instances render identical HTML"
for path in / /shop "/products/$slug" /about; do
  fetch "http://$sa:8081$path"; ha=$(sha256sum < "$FETCH_BODY" | cut -d' ' -f1)
  fetch "http://$sb:8081$path"; hb=$(sha256sum < "$FETCH_BODY" | cut -d' ' -f1)
  [ "$ha" = "$hb" ] && pass "identical HTML for $path" || fail "different HTML for $path"
done

echo "-- 4. outbox: each message sent once (waiting up to 60 s, then 40 s more for duplicates)"
for _ in $(seq 1 30); do n=$(docker logs smoke-sink 2>&1 | grep -c '^MSG' || true); [ "$n" -ge 1 ] && break; sleep 2; done
sleep 40
msgs=$(docker logs smoke-sink 2>&1 | grep '^MSG' || true)
total=$(printf '%s\n' "$msgs" | grep -c '^MSG' || true)
uniq_ids=$(printf '%s\n' "$msgs" | sed -n 's/.*:message-id "\([^"]*\)".*/\1/p' | sort -u | wc -l)
if [ "$total" -ge 1 ] && [ "$total" -eq "$uniq_ids" ]; then pass "$total message(s) sent, no duplicates"; else fail "messages sent: $total, distinct Message-IDs: $uniq_ids"; fi

echo "-- 3. rate limit shared between instances"
for _ in 1 2 3 4 5; do fetch "$A/api/auth/sessions" -X POST -H 'Content-Type: application/json' -d '{"email":"ratelimit@example.invalid","password":"wrong-password-1"}'; done
fetch "$B/api/auth/sessions" -X POST -H 'Content-Type: application/json' -d '{"email":"ratelimit@example.invalid","password":"wrong-password-1"}'
[ "$FETCH_STATUS" = 429 ] && pass "sixth failed login, on B, answered 429" || fail "sixth failed login on B: HTTP $FETCH_STATUS (expected 429)"

summary "R5 two-instance smoke"
