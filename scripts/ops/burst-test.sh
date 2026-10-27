#!/usr/bin/env bash
# Memory measurement on the staging host (RUNBOOK "What to measure on staging"). Run ON THE SERVER:
#   /srv/zandy/bin/burst-test.sh
# Replaces backend-staging for a few minutes by a twin container with the SAME image, heap, limits and read-only
# layout but a throw-away database (zandy-burst-<time>), so the owner's staging data is not touched and no
# staging login is needed. Mail goes nowhere (SMTP host does not resolve; the outbox just retries).
# transactor, caddy, ssr and the mail sink keep running, so the host picture is the real one.
#   1. idle baseline (60 s after start)
#   2. picture-upload burst: 20 pictures of about 5 MB in one request, 4 requests in a row
#   3. order burst: 100 orders in about 30 s, a different X-Client-Ip each, straight at the backend container
# Samples docker stats and `free` every 2 s and prints the PEAK per container, the lowest host `available`, swap used
# and OOMKilled per container. Staging's own backend is started again at the end, pass or fail.
set -euo pipefail
ZANDY_HOME=${ZANDY_HOME:-/srv/zandy}
OPS=$(cd "$(dirname "$0")" && pwd)
. "$OPS/lib.sh"
need docker curl bb openssl awk

cd "$ZANDY_HOME/compose"
rel=$(sed -n 's/^STAGING_RELEASE=//p' .env)
[ -n "$rel" ] && [ "$rel" != none ] || die "no staging release is deployed"
img=zandy/backend:$rel
net=zandy_zandy
pw=$(sed -n 's/^DATOMIC_STORAGE_PASSWORD=//p' "${ZANDY_ENV_DIR:-/etc/zandy}/transactor.env")
db=zandy-burst-$(date +%s)
uri="datomic:dev://transactor:4334/$db?password=$pw"
owner_email=owner-burst@example.invalid
owner_pw="Bu${RANDOM}rst-$(openssl rand -hex 8)-Zz"
newpw="Bu${RANDOM}rst-$(openssl rand -hex 8)-Yy"
secret=$(openssl rand -hex 24)
vol=zandy-burst-media-$$
pics=$(mktemp -d); chmod 777 "$pics"
jar=$(mktemp)
samples=${BURST_SAMPLES:-/tmp/burst-samples.txt}
: > "$samples"
sampler_pid=""
stats_names="zandy-transactor-1 zandy-burst-be zandy-ssr-staging-1 zandy-caddy-1 zandy-mailsink-1"

cleanup() {
  [ -n "$sampler_pid" ] && kill "$sampler_pid" 2>/dev/null || true
  docker inspect -f 'zandy-burst-be OOMKilled={{.State.OOMKilled}}' zandy-burst-be 2>/dev/null > /tmp/burst-oom.txt || true
  docker rm -f zandy-burst-be >/dev/null 2>&1 || true
  docker run --rm --network "$net" -e COUNT_URI="$uri" -e COUNT_DELETE=1 \
    -v "$OPS/count_db.clj:/tmp/count_db.clj:ro" "$img" clj /tmp/count_db.clj >/dev/null 2>&1 || true
  docker volume rm "$vol" >/dev/null 2>&1 || true
  docker run --rm --entrypoint sh -v "$pics:/pics" "$img" -c "rm -rf /pics/* /pics/.[!.]*" >/dev/null 2>&1 || true
  rm -rf "$pics" "$jar" "${FETCH_BODY:-/dev/null}"
  ( cd "$ZANDY_HOME/compose" && docker compose --profile staging up -d --no-deps backend-staging >/dev/null 2>&1 ) || true
}
trap cleanup EXIT

JAVA="-Xmx512m -Xss512k -XX:+UseSerialGC -XX:MaxMetaspaceSize=192m -XX:+ExitOnOutOfMemoryError -Ddatomic.objectCacheMax=128m --enable-native-access=ALL-UNNAMED"
env_args=(-e ZANDY_PROFILE=prod -e PORT=8080 -e "DATOMIC_URI=$uri" -e "SESSION_HMAC_SECRET=$secret"
  -e "OWNER_EMAIL=$owner_email" -e "OWNER_INITIAL_PASSWORD=$owner_pw" -e SMTP_HOST=burst-no-such-host -e SMTP_PORT=2525
  -e SMTP_USER=x -e SMTP_PASSWORD=x -e MAIL_DAILY_CAP=500 -e SITE_BASE_URL=https://burst.example.invalid -e MEDIA_DIR=/data/media)
task() { docker run --rm --network "$net" "${env_args[@]}" -e "JAVA_OPTS=-Xmx384m -Xss512k -XX:+UseSerialGC -XX:MaxMetaspaceSize=192m --enable-native-access=ALL-UNNAMED" -v "$vol:/data/media" "$img" "$@"; }

echo "burst test (release $rel, database $db)"
docker compose --profile staging stop backend-staging >/dev/null
docker volume create "$vol" >/dev/null
docker run --rm -v "$vol:/data/media" "$img" sh -c 'chown 10001:10001 /data/media' >/dev/null 2>&1 || docker run --rm --user 0 --entrypoint chown -v "$vol:/data/media" "$img" 10001:10001 /data/media
task migrate >/dev/null; task bootstrap-owner >/dev/null; task seed >/dev/null && pass "throw-away database ready"

# sampler: docker stats + host memory every ~2 s; a line per container: epoch name usedMiB
( while true; do
    t=$(date +%s)
    docker stats --no-stream --format '{{.Name}} {{.MemUsage}}' 2>/dev/null | awk -v t="$t" '{u=$2; v=u+0; if (u ~ /GiB/) v=v*1024; else if (u ~ /KiB/) v=v/1024; printf "C %s %s %.1f\n", t, $1, v}' >> "$samples"
    free -m | awk -v t="$t" '/Mem:/ {printf "A %s available %s\n", t, $7} /Swap:/ {printf "S %s swapused %s\n", t, $3}' >> "$samples"
  done ) &
sampler_pid=$!

docker run -d --name zandy-burst-be --network "$net" --init --memory 900m --memory-swap 900m --read-only \
  --tmpfs /tmp:size=256m,uid=10001,gid=10001 --cap-drop ALL --security-opt no-new-privileges:true \
  "${env_args[@]}" -e "JAVA_OPTS=$JAVA" -v "$vol:/data/media" "$img" serve >/dev/null
ip=$(docker inspect -f "{{(index .NetworkSettings.Networks \"$net\").IPAddress}}" zandy-burst-be)
for _ in $(seq 1 90); do curl -fsS -o /dev/null "http://$ip:8080/api/healthz" 2>/dev/null && break; sleep 2; done
curl -fsS -o /dev/null "http://$ip:8080/api/healthz" && pass "twin backend healthy" || { fail "twin backend not healthy"; docker logs zandy-burst-be 2>&1 | tail -5; summary burst; exit 1; }
B=http://$ip:8080
mark() { date +%s; }
t_idle=$(mark); sleep 60; echo "-- phase idle: $t_idle .. $(mark)"; t_idle_end=$(mark)

# ---- staff session ------------------------------------------------------------------------------
fetch "$B/api/auth/sessions" -c "$jar" -X POST -H 'Content-Type: application/json' -d "{\"email\":\"$owner_email\",\"password\":\"$owner_pw\"}"
[ "$FETCH_STATUS" = 201 ] && pass "staff login" || fail "login HTTP $FETCH_STATUS"
# The session cookie is Secure: curl neither stores nor sends it over plain http to the container, so take it from
# the Set-Cookie header and send it by hand.
getcookie() { header set-cookie | cut -d';' -f1; }
cookie=$(getcookie)
fetch "$B/api/auth/sessions/current" -H "Cookie: $cookie"; csrf=$(json_get csrfToken)
fetch "$B/api/admin/account/password" -H "Cookie: $cookie" -c "$jar" -X PUT -H 'Content-Type: application/json' -H "X-CSRF-Token: $csrf" -d "{\"currentPassword\":\"$owner_pw\",\"newPassword\":\"$newpw\"}"
[ "$FETCH_STATUS" = 200 ] && pass "initial password changed" || fail "password change HTTP $FETCH_STATUS $(head -c 200 "$FETCH_BODY")"
fetch "$B/api/auth/sessions" -c "$jar" -X POST -H 'Content-Type: application/json' -d "{\"email\":\"$owner_email\",\"password\":\"$newpw\"}"
[ "$FETCH_STATUS" = 201 ] && pass "second login" || fail "second login HTTP $FETCH_STATUS $(head -c 200 "$FETCH_BODY")"
cookie=$(getcookie)
fetch "$B/api/auth/sessions/current" -H "Cookie: $cookie"; csrf=$(json_get csrfToken)
[ -n "$csrf" ] && pass "csrf token obtained" || { fail "no csrf token"; summary burst; exit 1; }

# ---- pictures: 20 files of about 5 MB named <SKU>-1.jpg -----------------------------------------
fetch "$B/api/admin/products?pageSize=100" -H "Cookie: $cookie"
skus=$(for i in $(seq 0 19); do json_get items $i sku; done)
n=0; for s in $skus; do n=$((n+1)); [ -n "$s" ] && echo "$s" >> "$pics/skus"; done
docker run --rm --entrypoint sh -v "$pics:/pics" "$img" -c '
  umask 022; i=0; while read -r s; do i=$((i+1)); vips gaussnoise /pics/tmp.v 2800 2800 --sigma 40 --mean 128 && vips copy /pics/tmp.v "/pics/$s-1.jpg[Q=90]"; done < /pics/skus; rm -f /pics/tmp.v' 2>&1 | tail -2
files=$(ls "$pics"/*.jpg | wc -l); size=$(du -cb "$pics"/*.jpg | tail -1 | cut -f1)
echo "pictures: $files files, $((size/1024/1024)) MiB in total, $((size/files/1024)) KiB each"
[ "$files" -ge 20 ] && pass "20 test pictures made" || fail "only $files pictures made"

echo "-- phase upload"; t_up=$(mark)
for round in 1 2 3 4; do
  args=(); for f in "$pics"/*.jpg; do args+=(-F "files=@$f"); done
  s=$(date +%s)
  fetch "$B/api/admin/product-images" -H "Cookie: $cookie" -H "Expect:" -X POST -H "X-CSRF-Token: $csrf" -H "Idempotency-Key: $(cat /proc/sys/kernel/random/uuid)" "${args[@]}"
  ok=$(grep -o '"status":"[a-zA-Z]*"' "$FETCH_BODY" | sort | uniq -c | tr '\n' ' ')
  [ "$FETCH_STATUS" = 200 ] && pass "upload round $round: HTTP 200 in $(( $(date +%s) - s )) s ($ok)" || fail "upload round $round: HTTP $FETCH_STATUS $(head -c 200 "$FETCH_BODY")"
done
t_up_end=$(mark)

# ---- orders ---------------------------------------------------------------------------------------
echo "-- phase orders"
fetch "$B/api/products"; pid=$(json_get items 0 id); price=$(json_get items 0 price)
fetch "$B/api/policies/orderTerms"; ver=$(json_get version)
fetch "$B/api/admin/products/$pid/stock-movements" -H "Cookie: $cookie" -X POST -H 'Content-Type: application/json' -H "X-CSRF-Token: $csrf" -H "Idempotency-Key: $(cat /proc/sys/kernel/random/uuid)" -d '{"delta":500,"reason":"restock"}'
[ "$FETCH_STATUS" = 201 ] && pass "stock raised for the burst" || fail "stock movement HTTP $FETCH_STATUS"
body=$(printf '{"customer":{"name":"Burst Test","phone":"0771234567","email":"burst@example.invalid","preferredContact":"phone"},"delivery":{"countryCode":"UG","city":"Kampala","area":"Ntinda","addressLine":"Plot 1, Burst Rd"},"paymentMethod":"cash","items":[{"productId":"%s","quantity":1,"expectedUnitPrice":%s}],"agreement":{"type":"orderTerms","version":"%s"},"website":""}' "$pid" "$price" "$ver")
echo "$body" > "$pics/order.json"
t_or=$(mark)
res=$(seq 1 100 | xargs -P 2 -I{} sh -c 'curl -s -o /dev/null -w "%{http_code}\n" -X POST "$0/api/orders" -H "Content-Type: application/json" -H "Idempotency-Key: $(cat /proc/sys/kernel/random/uuid)" -H "X-Client-Ip: 10.77.$(( $1 / 250 )).$(( $1 % 250 + 1 ))" --data @"$2"; sleep 0.5' "$B" {} "$pics/order.json" | sort | uniq -c | tr '\n' ' ')
t_or_end=$(mark)
echo "order statuses: $res (took $((t_or_end - t_or)) s)"
case "$res" in *"100 201"*) pass "100 orders placed (HTTP 201)" ;; *) fail "orders: $res" ;; esac
sleep 20; t_end=$(mark)

kill "$sampler_pid" 2>/dev/null || true; sampler_pid=""

# ---- results ----------------------------------------------------------------------------------------
peak() {   # peak <name> <from> <to>
  awk -v n="$1" -v a="$2" -v b="$3" '$1=="C" && $3==n && $2>=a && $2<=b && $4>m {m=$4} END {printf "%d", m}' "$samples"
}
echo
echo "MiB (docker stats, container memory incl. page cache of its files)   idle(60s)  upload-burst  order-burst  whole run"
for c in $stats_names; do
  printf '  %-22s %6s %12s %12s %10s\n' "$c" "$(peak $c $t_idle $t_idle_end)" "$(peak $c $t_up $t_up_end)" "$(peak $c $t_or $t_end)" "$(peak $c 0 9999999999)"
done
awk '$1=="C" {t[$2]+=$4} END {m=0; for (k in t) if (t[k]>m) m=t[k]; printf "  peak SUM of the five containers at one sample: %d MiB\n", m}' "$samples"
echo "  lowest host available: $(awk '$1=="A" {if (min==""||$4<min) min=$4} END {print min}' "$samples") MiB (total 3814)"
echo "  highest swap used:     $(awk '$1=="S" {if ($4>m) m=$4} END {print m+0}' "$samples") MiB"
docker inspect -f '  {{.Name}} OOMKilled={{.State.OOMKilled}} restarts={{.RestartCount}}' zandy-transactor-1 zandy-ssr-staging-1 zandy-caddy-1 zandy-mailsink-1 2>/dev/null
docker inspect -f '  twin backend OOMKilled={{.State.OOMKilled}} restarts={{.RestartCount}}' zandy-burst-be
summary "burst test"
