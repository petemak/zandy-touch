#!/usr/bin/env bash
# Runs ON THE SERVER, in the account holder's own SSH session. Writes one secret into an env file without
# showing it: the value is typed at a hidden prompt, never passed as an argument (so it stays out of shell
# history, process lists and chat).
#   sudo /srv/zandy/bin/set-secret.sh <file> <NAME>          e.g.  production.env SMTP_PASSWORD
#   sudo /srv/zandy/bin/set-secret.sh <file> --unset <NAME>
# <file> is a name under /etc/zandy: production.env staging.env transactor.env backup.env ...
set -euo pipefail
[ "$(id -u)" = 0 ] || { echo "run with sudo" >&2; exit 1; }
dir=/etc/zandy
file=${1:?usage: set-secret.sh <file> <NAME> | <file> --unset <NAME>}
case "$file" in */*|*..*) echo "give a file name under $dir, not a path" >&2; exit 1 ;; esac
path=$dir/$file
[ -f "$path" ] || { echo "$path does not exist" >&2; exit 1; }
if [ "${2:-}" = "--unset" ]; then
  name=${3:?name}; sed -i "/^$name=/d" "$path"; echo "removed $name from $path"; exit 0
fi
name=${2:?name}
[[ "$name" =~ ^[A-Z][A-Z0-9_]*$ ]] || { echo "bad variable name" >&2; exit 1; }
read -rsp "Value for $name (hidden): " v1; echo
read -rsp "Again: " v2; echo
[ "$v1" = "$v2" ] || { echo "values differ; nothing written" >&2; exit 1; }
[ -n "$v1" ] || { echo "empty value; nothing written" >&2; exit 1; }
case "$v1" in *$'\n'*|*'#'*) echo "value must be one line without '#'" >&2; exit 1 ;; esac
tmp=$(mktemp "$dir/.tmp.XXXXXX"); chmod 600 "$tmp"
# Keep every other line; replace or append this one.
awk -v n="$name" -v v="$v1" 'BEGIN{done=0} index($0, n"=")==1 {print n"="v; done=1; next} {print} END{if(!done) print n"="v}' "$path" > "$tmp"
chown --reference="$path" "$tmp"; chmod --reference="$path" "$tmp"; mv "$tmp" "$path"
echo "$name written to $path"
