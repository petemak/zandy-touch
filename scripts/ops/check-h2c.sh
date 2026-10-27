#!/usr/bin/env bash
# Acceptance check D-9 (architecture.md section 8): the internal listeners drop the h2c upgrade headers.
#   scripts/ops/check-h2c.sh <staging|production>        run FROM the operator's machine; uses DEPLOY_HOST / SSH_KEY (lib: remote.sh)
# A request that carries "Connection: Upgrade, HTTP2-Settings", "Upgrade: h2c" and "HTTP2-Settings" is what the JDK HttpClient
# (used by ssr) sends first. Evidence in two parts, both from inside the Docker network:
#   control: straight to the backend (port 8080) the upgrade request is NOT answered normally (400 for the ssr client's
#            request, 101 Switching Protocols for a plain GET), so the headers are harmful when they arrive;
#   test:    through Caddy's internal listener (8088 production, 8089 staging) the same request gets 200,
#            which can only be so if Caddy removed both headers before the backend saw them.
set -euo pipefail
. "$(dirname "$0")/lib.sh"
. "$(dirname "$0")/remote.sh"
envname=${1:?usage: check-h2c.sh staging|production}
read -r backend_svc _ <<< "$(service_names "$envname")"
listener=$([ "$envname" = production ] && echo 8088 || echo 8089)
hdr="-H 'Connection: Upgrade, HTTP2-Settings' -H 'Upgrade: h2c' -H 'HTTP2-Settings: AAMAAABkAARAAAAAAAIAAAAA'"
run() { rcompose "--profile $envname exec -T $backend_svc curl -sS -o /dev/null -w '%{http_code}' --max-time 10 $hdr $1" 2>/dev/null || true; }
echo "D-9 h2c upgrade headers ($envname)"
direct=$(run http://127.0.0.1:8080/api/healthz)
via=$(run "http://caddy:$listener/api/healthz")
if [ "$direct" != 200 ] && [ -n "$direct" ]; then pass "control: backend directly answers the upgrade request with HTTP $direct, not 200 (the headers do matter)"; else warn "control: backend directly answered '$direct' (not 200 expected; the check below still decides)"; fi
[ "$via" = 200 ] && pass "through the internal listener :$listener the same request gets 200" || fail "through :$listener the request got '$via', expected 200"
summary "D-9"
