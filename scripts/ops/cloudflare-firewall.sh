#!/usr/bin/env bash
# Origin firewall (architecture.md section 8, requirement 5 and check D-10): ports 80 and 443 accept only
# Cloudflare's published IPv4 ranges. SSH (22) is never touched. Runs ON THE SERVER as root.
#   sudo /srv/zandy/bin/cloudflare-firewall.sh apply | remove | status
# Installed as the systemd unit zandy-cloudflare-fw.service (deploy/systemd/), which applies it at boot BEFORE Docker starts
# the containers, and re-applies it on `systemctl reload` (after update-cloudflare-ips.sh and a deploy have shipped new ranges).
#
# Why DOCKER-USER and not ufw: Docker publishes 80/443 with its own NAT and FORWARD rules, which ufw's INPUT rules never see.
# DOCKER-USER is the chain Docker evaluates first for forwarded (published-port) traffic and never flushes. We keep our rules in
# our own chain ZANDY-CF, jumped to from the top of DOCKER-USER, so they can be rebuilt without touching Docker's.
#
# Ranges come from the same file Caddy trusts (/srv/zandy/compose/caddy/cloudflare-ips.conf, the "trusted_proxies static" line),
# so the firewall and the real-visitor-address logic can never disagree. IPv6 ranges are ignored: compose publishes IPv4 only.
# The set is swapped atomically, so a refresh never opens a window.
#
# Never apply this while a DNS record is grey cloud (DNS only): Let's Encrypt then connects straight to the origin and
# certificate issuance and renewal fail. Apply it only when every hostname Caddy serves is Proxied (orange).
# Traffic from the server itself (curl to 127.0.0.1) is local output and never passes this chain, so run on-server checks freely.
set -euo pipefail
[ "$(id -u)" = 0 ] || { echo "run as root (sudo)" >&2; exit 1; }
CONF=${ZANDY_CF_CONF:-/srv/zandy/compose/caddy/cloudflare-ips.conf}
SET=zandy-cf4
CHAIN=ZANDY-CF

ranges() {
  [ -r "$CONF" ] || { echo "cannot read $CONF" >&2; exit 1; }
  grep -v '^[[:space:]]*#' "$CONF" | tr ' ' '\n' | grep -E '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+/[0-9]+$' | sort -u
}

apply() {
  local n tmp="$SET-new"
  n=$(ranges | wc -l)
  [ "$n" -ge 10 ] || { echo "only $n IPv4 ranges found in $CONF; refusing (Cloudflare publishes about 15)" >&2; exit 1; }
  ipset create -exist "$SET" hash:net
  ipset destroy "$tmp" 2>/dev/null || true
  ipset create "$tmp" hash:net
  ranges | while read -r r; do ipset add "$tmp" "$r"; done
  ipset swap "$tmp" "$SET"
  ipset destroy "$tmp"

  iptables -N DOCKER-USER 2>/dev/null || true       # Docker adopts an existing DOCKER-USER chain; created here for the boot order
  if iptables -N "$CHAIN" 2>/dev/null; then :; else iptables -F "$CHAIN"; fi
  # Packets arriving from a Docker bridge (container to container) are not visitors: leave them alone.
  iptables -A "$CHAIN" -i docker0 -j RETURN
  iptables -A "$CHAIN" -i br-+ -j RETURN
  iptables -A "$CHAIN" -p tcp -m multiport --dports 80,443 -m set ! --match-set "$SET" src -j DROP
  # HTTP/3: Cloudflare does not use it towards the origin, so UDP 443 is dropped for everyone.
  iptables -A "$CHAIN" -p udp --dport 443 -j DROP
  iptables -A "$CHAIN" -j RETURN
  iptables -C DOCKER-USER -j "$CHAIN" 2>/dev/null || iptables -I DOCKER-USER 1 -j "$CHAIN"
  echo "cloudflare-firewall: applied ($n IPv4 ranges in set $SET)"
}

remove() {
  while iptables -D DOCKER-USER -j "$CHAIN" 2>/dev/null; do :; done
  iptables -F "$CHAIN" 2>/dev/null || true
  iptables -X "$CHAIN" 2>/dev/null || true
  ipset destroy "$SET" 2>/dev/null || true
  echo "cloudflare-firewall: removed (80/443 open to everyone again)"
}

status() {
  echo "--- ipset $SET: $(ipset list "$SET" 2>/dev/null | sed -n 's/^Number of entries: //p') entries"
  echo "--- DOCKER-USER"; iptables -S DOCKER-USER 2>/dev/null || echo "(no chain)"
  echo "--- $CHAIN"; iptables -S "$CHAIN" 2>/dev/null || echo "(not applied)"
  echo "--- hits"; iptables -L "$CHAIN" -n -v 2>/dev/null | sed -n '3,$p' || true
}

case "${1:-}" in
  apply|start|reload) apply ;;
  remove|stop) remove ;;
  status) status ;;
  *) echo "usage: cloudflare-firewall.sh apply|remove|status" >&2; exit 2 ;;
esac
