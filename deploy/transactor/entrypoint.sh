#!/usr/bin/env bash
# Entry point of the Datomic image.
#   entrypoint.sh transactor [-Xmx.. -Xms.. ...]   render the properties, then run the transactor
#   entrypoint.sh datomic <command> ...            run bin/datomic (backup-db, restore-db, list-backups)
#   entrypoint.sh <anything else>                  exec it
set -euo pipefail

TEMPLATE=/etc/datomic/transactor.properties.template
RENDERED_DIR=${RENDERED_DIR:-/run/datomic}   # a tmpfs in compose.yaml, so the passwords never touch disk

mode=${1:-transactor}
[ $# -gt 0 ] && shift

case "$mode" in
  transactor)
    for v in DATOMIC_STORAGE_ADMIN_PASSWORD DATOMIC_STORAGE_PASSWORD DATOMIC_ALT_HOST; do
      if [ -z "${!v:-}" ]; then
        echo "FATAL: $v is not set (see deploy/env.transactor.example)" >&2
        exit 64
      fi
    done
    if [ "${#DATOMIC_STORAGE_ADMIN_PASSWORD}" -lt 16 ] || [ "${#DATOMIC_STORAGE_PASSWORD}" -lt 16 ]; then
      echo "FATAL: the Datomic storage passwords must be at least 16 characters" >&2
      exit 64
    fi
    : "${DATOMIC_MEMORY_INDEX_THRESHOLD:=32m}" "${DATOMIC_MEMORY_INDEX_MAX:=256m}" "${DATOMIC_OBJECT_CACHE_MAX:=128m}"
    export DATOMIC_MEMORY_INDEX_THRESHOLD DATOMIC_MEMORY_INDEX_MAX DATOMIC_OBJECT_CACHE_MAX
    umask 077
    mkdir -p "$RENDERED_DIR"
    props="$RENDERED_DIR/transactor.properties"
    # Only these names are substituted; anything else that looks like ${X} stays as written.
    envsubst '${DATOMIC_ALT_HOST} ${DATOMIC_STORAGE_ADMIN_PASSWORD} ${DATOMIC_STORAGE_PASSWORD} ${DATOMIC_MEMORY_INDEX_THRESHOLD} ${DATOMIC_MEMORY_INDEX_MAX} ${DATOMIC_OBJECT_CACHE_MAX}' \
      < "$TEMPLATE" > "$props"
    # bin/transactor treats -Xmx/-Xms specially; any other JVM option replaces Datomic's default
    # options, so the defaults are repeated here together with the console log configuration.
    unset JAVA_OPTS
    exec /opt/datomic/bin/transactor "$@" \
      --enable-native-access=ALL-UNNAMED -XX:+UseG1GC -XX:MaxGCPauseMillis=50 \
      -Dlogback.configurationFile=/etc/datomic/logback.xml \
      "$props"
    ;;
  datomic)
    exec /opt/datomic/bin/datomic "$@"
    ;;
  *)
    exec "$mode" "$@"
    ;;
esac
