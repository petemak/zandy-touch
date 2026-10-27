#!/usr/bin/env bash
# The Datomic peer in deps.edn and the transactor pinned in deploy/transactor/Dockerfile and
# deploy/env.host.example must be the same version.
set -euo pipefail
. "$(dirname "$0")/lib.sh"
peer=$(sed -n 's/.*com.datomic\/peer[^"]*"\([^"]*\)".*/\1/p' "$REPO_ROOT/deps.edn")
tx=$(sed -n 's/^ARG DATOMIC_VERSION=//p' "$REPO_ROOT/deploy/transactor/Dockerfile")
tag=$(sed -n 's/^TRANSACTOR_TAG=//p' "$REPO_ROOT/deploy/env.host.example")
[ -n "$peer" ] || die "could not read the peer version from deps.edn"
if [ "$peer" = "$tx" ] && [ "$peer" = "$tag" ]; then echo "datomic versions agree: $peer"; else die "datomic mismatch: peer $peer, transactor image $tx, TRANSACTOR_TAG $tag"; fi
