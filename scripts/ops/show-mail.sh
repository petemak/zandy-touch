#!/usr/bin/env bash
# Runs ON THE SERVER. Shows the mail the staging mail sink captured (nothing is ever sent outside).
#   /srv/zandy/bin/show-mail.sh              the last 200 lines
#   /srv/zandy/bin/show-mail.sh --all        everything still in the log
#   /srv/zandy/bin/show-mail.sh --links      only the lines holding links (password-reset links, tracking links)
#   /srv/zandy/bin/show-mail.sh --follow     wait for new mail
set -euo pipefail
cd /srv/zandy/compose
case "${1:-}" in
  --all)    docker compose --profile staging logs --no-log-prefix --no-color mailsink ;;
  --links)  docker compose --profile staging logs --no-log-prefix --no-color mailsink | grep -E '^(Subject:|Envelope-To:)|https?://' ;;
  --follow) docker compose --profile staging logs --no-log-prefix --no-color -f --tail 20 mailsink ;;
  *)        docker compose --profile staging logs --no-log-prefix --no-color --tail 200 mailsink ;;
esac
