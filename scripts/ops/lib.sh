#!/usr/bin/env bash
# Shared helpers for scripts/ops/*.sh. Source it:   . "$(dirname "$0")/lib.sh"
# Needs: bash 4+, curl, awk, bb (babashka, for JSON).

OPS_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "$OPS_DIR/../.." && pwd)

log()  { printf '%s\n' "$*" >&2; }
die()  { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
need() { local c; for c in "$@"; do command -v "$c" >/dev/null 2>&1 || die "$c is required but not installed"; done; }

# ---- pass / fail bookkeeping for check scripts ----------------------------------------------------
PASSES=0
FAILS=0
pass() { PASSES=$((PASSES + 1)); printf '  PASS  %s\n' "$*"; }
fail() { FAILS=$((FAILS + 1)); printf '  FAIL  %s\n' "$*"; }
warn() { printf '  WARN  %s\n' "$*"; }
summary() {
  printf '%s: %d passed, %d failed\n' "${1:-checks}" "$PASSES" "$FAILS"
  [ "$FAILS" -eq 0 ]
}

# ---- HTTP -----------------------------------------------------------------------------------------
# CURL_OPTS may add options (for example --resolve host:443:IP, or --insecure on a throw-away server).
FETCH_BODY=${FETCH_BODY:-$(mktemp)}
trap 'rm -f "$FETCH_BODY"' EXIT

# fetch <url> [extra curl args]   sets FETCH_STATUS, FETCH_HEADERS (CR removed) and FETCH_BODY.
# Never follows redirects. A transport error gives FETCH_STATUS=000.
fetch() {
  local url=$1; shift
  local raw
  # shellcheck disable=SC2086  # CURL_OPTS is a list of options on purpose
  if ! raw=$(curl -sS --max-time "${FETCH_TIMEOUT:-30}" ${CURL_OPTS:-} -o "$FETCH_BODY" -D - "$@" "$url" 2>/dev/null); then
    FETCH_STATUS=000
    FETCH_HEADERS=""
    : > "$FETCH_BODY"
    return 0
  fi
  raw=$(printf '%s\n' "$raw" | tr -d '\r')
  FETCH_STATUS=$(printf '%s\n' "$raw" | awk 'NR == 1 { print $2; exit }')
  FETCH_HEADERS=$(printf '%s\n' "$raw" | tail -n +2)
}

# header <name>   value of the last response header with that name (case-insensitive), may be empty.
header() {
  printf '%s\n' "$FETCH_HEADERS" | awk -v k="$(printf '%s' "$1" | tr 'A-Z' 'a-z')" '
    { i = index($0, ":"); if (i > 0 && tolower(substr($0, 1, i - 1)) == k) { v = substr($0, i + 1); sub(/^[ \t]+/, "", v); last = v } }
    END { print last }'
}

# json_get <key|index>...   read JSON from FETCH_BODY and print the value at that path (empty if absent).
json_get() { bb "$OPS_DIR/json.clj" "$@" < "$FETCH_BODY"; }

normalize() { printf '%s' "$1" | tr -s ' ' | sed -e 's/^ //' -e 's/ $//'; }
