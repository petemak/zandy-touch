#!/usr/bin/env bash
# One-time preparation of a fresh Ubuntu 24.04 or 26.04 VPS. Run ON THE SERVER as the sudo user:
#   sudo bash server-setup.sh
# (copy it up with: scp scripts/ops/{server-setup.sh,set-secret.sh,check-env.sh,lib.sh} deploy/env.*.example <user>@<ip>:)
# Hosts: 8 GB = staging and production; 4 GB = ONE role (staging, or production with ZANDY_ALLOW_SMALL_PRODUCTION=yes and
# compose.small-prod.yaml). ZANDY_HARDEN_SSH=yes also writes the key-only sshd drop-in: use it only after you have
# confirmed key login in a second session.
# Does: Docker Engine and rclone from their official sources, firewall (22, 80, 443), unattended security
# updates, directory layout, env files from the templates with MACHINE-MADE secrets only
# (SESSION_HMAC_SECRET, Datomic passwords). It never asks for or prints the human secrets
# (Gmail app password, owner password, R2 keys): those go in with set-secret.sh.
set -euo pipefail
[ "$(id -u)" = 0 ] || { echo "run with sudo" >&2; exit 1; }
deploy_user=${SUDO_USER:?run through sudo from the login user}
# Roles of this host. A 4 GB host (OVH VPS-1) is staging only; an 8 GB host runs both environments.
#   sudo ZANDY_ROLES="staging" bash server-setup.sh          explicit
# Default: staging only when the host has under 6 GB of RAM, else "staging production".
mem_mb=$(awk '/MemTotal/ {print int($2/1024)}' /proc/meminfo)
if [ -z "${ZANDY_ROLES:-}" ]; then if [ "$mem_mb" -lt 6000 ]; then ZANDY_ROLES="staging"; else ZANDY_ROLES="staging production"; fi; fi
ZANDY_ROLES=${ZANDY_ROLES//,/ }
for r in $ZANDY_ROLES; do case "$r" in staging|production) ;; *) echo "bad role $r" >&2; exit 1 ;; esac; done
case " $ZANDY_ROLES " in *" production "*) has_prod=1 ;; *) has_prod=0 ;; esac
if [ "$mem_mb" -lt 6000 ] && [ "$has_prod" = 1 ] && [ "${ZANDY_ALLOW_SMALL_PRODUCTION:-}" != yes ]; then
  echo "refusing: production on a host with only $mem_mb MB RAM. Decide it from the staging measurements first (RUNBOOK, 'Production server at go-live'), then rerun with ZANDY_ALLOW_SMALL_PRODUCTION=yes and a production sizing file." >&2; exit 1
fi
echo "host: ${mem_mb} MB RAM, roles: $ZANDY_ROLES"
here=$(cd "$(dirname "$0")" && pwd)
export DEBIAN_FRONTEND=noninteractive

echo "== packages"
apt-get update -y
apt-get install -y ca-certificates curl gnupg ufw rsync unattended-upgrades openssl unzip ipset
if ! command -v docker >/dev/null; then
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" > /etc/apt/sources.list.d/docker.list
  apt-get update -y
  apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
fi
command -v rclone >/dev/null || { curl -fsSL https://rclone.org/install.sh | bash; }
# IPv4 only, no userland proxy: the visitor's address must reach Caddy unchanged.
cat > /etc/docker/daemon.json <<'JSON'
{ "log-driver": "json-file", "log-opts": { "max-size": "10m", "max-file": "5" }, "userland-proxy": false, "live-restore": true }
JSON
systemctl enable --now docker && systemctl restart docker

if [ "$mem_mb" -lt 6000 ] && ! swapon --show | grep -q .; then
  echo "== 2 GB swap file (small host: last safety net before the kernel kills a process)"
  fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile >/dev/null && swapon /swapfile
  grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
  echo 'vm.swappiness=10' > /etc/sysctl.d/99-zandy.conf && sysctl -q -p /etc/sysctl.d/99-zandy.conf
fi

if [ "${ZANDY_HARDEN_SSH:-}" = yes ]; then
  echo "== ssh: key login only"
  printf '%s\n' "# Zandy: key login only (written by server-setup.sh). First match wins, so this file sorts before cloud-init." \
    "PasswordAuthentication no" "KbdInteractiveAuthentication no" "PermitRootLogin no" > /etc/ssh/sshd_config.d/00-zandy-hardening.conf
  sshd -t && systemctl reload ssh
fi

echo "== firewall (22, 80, 443 only; the transactor and the internal listeners are never published)"
ufw default deny incoming; ufw default allow outgoing
ufw allow 22/tcp; ufw allow 80/tcp; ufw allow 443/tcp; ufw allow 443/udp
ufw --force enable
dpkg-reconfigure -f noninteractive unattended-upgrades

echo "== users and directories"
# Numeric owners (the container user 10001 has no account on the host). Ubuntu 26.04 ships uutils coreutils,
# whose 'install -o 10001' refuses ids that do not exist, so: create the directory, then chown by number.
mkd() {   # mkd <owner> <group> <mode> <dir>...
  local o=$1 g=$2 m=$3; shift 3
  install -d -m "$m" "$@"; chown "$o:$g" "$@"
}
getent group zandyops >/dev/null || groupadd zandyops
usermod -aG docker,zandyops "$deploy_user"
mkd root zandyops 750 /etc/zandy
for d in compose compose/caddy compose/caddy/sites bin releases state; do mkd "$deploy_user" "$deploy_user" 755 /srv/zandy/$d; done
mkd "$deploy_user" "$deploy_user" 755 /srv/zandy/static
for r in $ZANDY_ROLES; do mkd "$deploy_user" "$deploy_user" 755 /srv/zandy/static/$r; done
mkd 10001 10001 755 /srv/zandy/datomic
mkd 10001 10001 750 /srv/zandy/datomic/data
mkd 10001 zandyops 2775 /srv/zandy/datomic/backup   # tools container writes, ops user reads
mkd 10001 10001 755 /srv/zandy/media
for r in $ZANDY_ROLES; do mkd 10001 10001 755 /srv/zandy/media/$r; done
mkd root root 755 /srv/zandy/caddy /srv/zandy/caddy/data /srv/zandy/caddy/config
mkd "$deploy_user" zandyops 750 /srv/zandy/backups
for f in set-secret.sh secret-lengths.sh cloudflare-firewall.sh show-mail.sh check-env.sh lib.sh backup.sh restore-test.sh two-instance-smoke.sh burst-test.sh count_db.clj smtp_sink.clj json.clj; do
  [ -f "$here/$f" ] && install -m 755 "$here/$f" /srv/zandy/bin/$f
done
chown -R "$deploy_user":"$deploy_user" /srv/zandy/bin

echo "== babashka (used by json.clj in the check scripts)"
if ! command -v bb >/dev/null; then
  curl -fsSL -o /tmp/bb.tar.gz https://github.com/babashka/babashka/releases/download/v1.13.226/babashka-1.13.226-linux-amd64-static.tar.gz
  echo "bf89e909c4025edf2d669921c81107435b7244840d8f4306f58439c328a342f9  /tmp/bb.tar.gz" | sha256sum -c -
  tar -xzf /tmp/bb.tar.gz -C /usr/local/bin bb && rm /tmp/bb.tar.gz
fi

echo "== environment files (only machine-made secrets are filled)"
rand() { openssl rand -base64 64 | tr -dc 'A-Za-z0-9' | head -c "$1"; }
make_env() {   # make_env <template> <target>
  [ -f "$2" ] && { echo "keeping existing $2"; return; }
  install -m 640 -o root -g zandyops "$1" "$2"
}
pairs="env.transactor.example:transactor.env env.backup.example:backup.env"
for r in $ZANDY_ROLES; do pairs="$pairs env.$r.example:$r.env env.ssr.$r.example:ssr.$r.env"; done
for pair in $pairs; do
  src=$here/${pair%%:*}; [ -f "$src" ] || src=$here/../../deploy/${pair%%:*}
  make_env "$src" "/etc/zandy/${pair##*:}"
done
if grep -q "^DATOMIC_STORAGE_PASSWORD=CHANGE_ME_RANDOM" /etc/zandy/transactor.env; then
  sed -i "s#^DATOMIC_STORAGE_ADMIN_PASSWORD=.*#DATOMIC_STORAGE_ADMIN_PASSWORD=$(rand 40)#; s#^DATOMIC_STORAGE_PASSWORD=.*#DATOMIC_STORAGE_PASSWORD=$(rand 40)#" /etc/zandy/transactor.env
fi
peerpw=$(sed -n 's/^DATOMIC_STORAGE_PASSWORD=//p' /etc/zandy/transactor.env)
for f in $ZANDY_ROLES; do
  sed -i "s#CHANGE_ME_DATOMIC_STORAGE_PASSWORD#$peerpw#; s#^SESSION_HMAC_SECRET=CHANGE_ME_RANDOM_48_CHARS#SESSION_HMAC_SECRET=$(rand 48)#" /etc/zandy/$f.env
done
if [ ! -f /srv/zandy/compose/.env ]; then
  src=$here/env.host.example; [ -f "$src" ] || src=$here/../../deploy/env.host.example
  install -m 640 -o "$deploy_user" -g "$deploy_user" "$src" /srv/zandy/compose/.env
  sed -i "s#^HOST_ROLES=.*#HOST_ROLES=$(echo $ZANDY_ROLES | tr ' ' ',')#" /srv/zandy/compose/.env
  case " $ZANDY_ROLES " in *" production "*) ;; *) sed -i 's#^PROD_HOST=.*#PROD_HOST=unused.invalid#' /srv/zandy/compose/.env ;; esac
  case " $ZANDY_ROLES " in *" staging "*) ;; *) sed -i 's#^STAGING_HOST=.*#STAGING_HOST=unused.invalid#' /srv/zandy/compose/.env ;; esac
  if [ "$has_prod" = 0 ]; then sed -i 's#^COMPOSE_FILE=.*#COMPOSE_FILE=compose.yaml:compose.small.yaml#' /srv/zandy/compose/.env; fi
  # 4 GB production-only host: the small production layout (backend and ssr at the D3 production heaps).
  if [ "$has_prod" = 1 ] && [ "$mem_mb" -lt 6000 ] && [ "$ZANDY_ROLES" = production ]; then sed -i 's#^COMPOSE_FILE=.*#COMPOSE_FILE=compose.yaml:compose.small-prod.yaml#' /srv/zandy/compose/.env; fi
fi

echo "== done. Log out and in again (group membership), then fill the rest. Still to write by hand:"
for f in /etc/zandy/*.env /srv/zandy/compose/.env; do grep -v "^[[:space:]]*#" "$f" | grep -q CHANGE_ME && echo "   $f: $(grep -v "^[[:space:]]*#" "$f" | grep CHANGE_ME | cut -d= -f1 | tr "\n" " ")"; done || true
echo "   Human secrets with:  sudo /srv/zandy/bin/set-secret.sh production.env SMTP_PASSWORD  (hidden prompt)"
