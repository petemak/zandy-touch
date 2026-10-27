#!/usr/bin/env bash
# Acceptance check D-6: the previous release's bundle still answers 200 after a deploy.
#   scripts/ops/check-static-retention.sh <base-url> <previous-asset-manifest.edn>
# deploy.sh saves the manifest that was live before the deploy under deploy/.state/ and calls this.
# Every file named in it (hashed JS, CSS and tokens) is requested under /static/.
set -euo pipefail
. "$(dirname "$0")/lib.sh"
need curl bb
base=${1:?usage: check-static-retention.sh <base-url> <previous-asset-manifest.edn>}
base=${base%/}
prev=${2:?previous manifest file required}
[ -s "$prev" ] || { echo "D-6: no previous manifest ($prev): first deploy, nothing to retain"; exit 0; }

echo "D-6 previous bundle survives, against $base"
files=$(bb -e '(doseq [v (vals (clojure.edn/read-string (slurp (first *command-line-args*))))] (println v))' "$prev")
[ -n "$files" ] || die "previous manifest lists no files"
while IFS= read -r f; do
  fetch "$base/static/$f"
  if [ "$FETCH_STATUS" = 200 ]; then pass "/static/$f"; else fail "/static/$f: HTTP $FETCH_STATUS"; fi
done <<< "$files"
summary "D-6 static retention"
