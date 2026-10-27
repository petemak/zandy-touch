#!/usr/bin/env bash
# Remote-access helpers for deploy.sh and rollback.sh. Source after lib.sh.
# Environment:
#   DEPLOY_HOST   server address (required)
#   DEPLOY_USER   login on the server (default ubuntu, OVH's default user on Ubuntu images)
#   SSH_KEY       optional private key path; default is whatever ssh/ssh-agent offers (key of this machine)
ZANDY_HOME=/srv/zandy
: "${DEPLOY_HOST:?set DEPLOY_HOST to the server address}"
DEPLOY_USER=${DEPLOY_USER:-ubuntu}
SSH_OPTS=(-o StrictHostKeyChecking=accept-new -o BatchMode=yes -o ServerAliveInterval=30)
[ -n "${SSH_KEY:-}" ] && SSH_OPTS+=(-i "$SSH_KEY")
TARGET="$DEPLOY_USER@$DEPLOY_HOST"

rsh()  { ssh "${SSH_OPTS[@]}" "$TARGET" "$@"; }
# rcompose <args>: docker compose in the release directory on the server.
rcompose() { rsh "cd $ZANDY_HOME/compose && docker compose $*"; }
rsync_to() { rsync -a -e "ssh ${SSH_OPTS[*]}" "$@"; }
# env_get <KEY>: value from the server's compose .env
env_get() { rsh "sed -n 's/^$1=//p' $ZANDY_HOME/compose/.env | head -n1"; }
# env_set <KEY> <VALUE>
env_set() { rsh "sed -i 's#^$1=.*#$1=$2#' $ZANDY_HOME/compose/.env && grep -q '^$1=' $ZANDY_HOME/compose/.env"; }

service_names() {   # service_names <staging|production> -> "backend ssr" service names
  case "$1" in
    staging) echo "backend-staging ssr-staging" ;;
    production) echo "backend ssr" ;;
    *) die "environment must be staging or production" ;;
  esac
}
