# Runbook: Zandy Touch deployment

Who does what
- **Account holder** (the person paying with the Swiss card; owns the Porkbun, Cloudflare and OVHcloud accounts): steps 1 to 6 and the secret entries. The deployment engineer never creates an account, never sees a payment detail and never receives a secret.
- **Deployment engineer** (Claude, working from the machine of user `pjm`): everything in "After SSH access works".
- **Shop owner** (Gmail account `sandratonanyonjo@gmail.com`): step 6 only.

Rules for secrets
- A secret goes **only** into a file under `/etc/zandy/` on the server, typed at a hidden prompt with `set-secret.sh` in the account holder's own SSH session. Never into chat, email, a ticket, a screenshot or the repository.
- Secrets: Gmail app password, R2 access key id and secret, the owner's initial dashboard password. The session secret and the Datomic passwords are generated on the server by `server-setup.sh` and nobody reads them.
- Not secrets (may be sent): the server IP address, SSH **public** keys, the R2 account id and bucket name, hostnames.

Cost summary (deploy/COSTS.md): staging phase about 5.58 USD per month (VPS-1 plus domain); at go-live 10.24 to 13.04 depending on the production server, or 11.29 with a single VPS-2. Two OVH servers where one is a VPS-2 would be 15.95, over the ceiling: do not keep both. Cards: OVHcloud, Porkbun and Cloudflare (R2 needs a payment method on file) all take the Swiss credit card; no other payment method is needed.

## Account steps, in order

### 1. Domain at Porkbun
1. Log in to the existing Porkbun account. Search `zandytouch.com`, add to cart, **uncheck any extras** (privacy and DNS hosting are included free; skip email and SSL add-ons), pay with the Swiss card. About 11.08 USD per year; check the cart total before paying.
2. Leave the nameservers as they are for now (step 2 changes them).
3. Send the engineer: "domain bought". Nothing secret.

### 2. Cloudflare zone and nameservers
Follow deploy/CLOUDFLARE.md section 1: add `zandytouch.com` to the existing Cloudflare account (Free plan), copy the two Cloudflare nameservers into Porkbun, wait until Cloudflare shows the zone as **Active**. Add no DNS records yet except as told in "Staging hostname" below. DNSSEC can wait.

### 3. R2 bucket and token
Follow deploy/CLOUDFLARE.md section 7: bucket `zandy-touch-backups`, API token **Object Read & Write**, limited to that bucket only. Keep the **Access Key ID** and **Secret Access Key** on the screen or in your password manager until step 9; they are shown once. Also note the **account id** (it is in the S3 endpoint `https://<account id>.r2.cloudflarestorage.com`; not secret). If Cloudflare asks for a payment method to enable R2, add the Swiss card.

### 4. SSH key on this machine (user pjm)
The deployment is run from the machine of user `pjm`, so the server must accept that machine's key.
1. On this machine, if no key exists: `ssh-keygen -t ed25519 -C "zandy-touch-deploy" -f ~/.ssh/id_ed25519` (a passphrase is recommended).
2. Show the **public** key: `cat ~/.ssh/id_ed25519.pub`. This is safe to share. It starts with `ssh-ed25519`.
3. If the key has a passphrase, load it before asking the engineer to deploy: `ssh-add ~/.ssh/id_ed25519` and enter the passphrase once. Deploys cannot prompt for it. (`ssh-add -l` lists loaded keys.)
4. The account holder needs to type secrets on the server (step 9). If the account holder is not the person at this machine, they also generate their own key (same commands on their computer) and send the public key; the engineer adds it to the server's `~/.ssh/authorized_keys` after first login.

### 5. OVHcloud server (staging first)
**Situation (26 October 2026):** VPS-2 (8 GB) is out of stock ("out of stock in region GRA"). The owner may retry VPS-2 in another European datacenter; if it is available, order it exactly as in option A. Otherwise order option B, which serves **staging only**. Production gets its own server at go-live (section "Production server at go-live").

**Option B, the likely order: OVH VPS-1 2027** (2 vCore, 4 GB RAM, 40 GB NVMe, daily backup included, 3.81 EUR per month excluding tax):
1. Order it at https://www.ovhcloud.com/en/vps/ in the account holder's OVHcloud account.
2. Billing **monthly**, no commitment. **No paid options** (no extra backup, no extra snapshot, no managed services, no extra IPs). Swiss VAT (about 8.1%) is expected; check the total at checkout (about 4.66 USD).
3. Datacenter: **any European datacenter** that has stock (Gravelines, Frankfurt, Strasbourg, Warsaw, London). Distance matters little because Cloudflare sits in front.
4. Operating system image: **Ubuntu 24.04 LTS**.
5. SSH key: **add the public key from step 4** in OVH's key field. No password login.
6. Send the engineer the server's **public IPv4 address** and the login user name (normally `ubuntu`). Do not send a password.

**Option A, if a VPS-2 (4 vCPU, 8 GB, 75 GB NVMe) can be ordered after all:** same choices (monthly, European datacenter, Ubuntu 24.04, key added, no options). The same server then runs staging and production, and `server-setup.sh` detects the 8 GB and sets both roles.

### 6. Gmail app password (shop owner)
The owner of `sandratonanyonjo@gmail.com`: Google Account, Security, turn on **2-Step Verification**; then Security, App passwords, create one named "Zandy Touch server". Google shows 16 letters once. Keep it ready for step 9 (type it only into the hidden prompt on the server). If Google blocks the first sign-in from the new server, approve it from the notification Google sends.

### Staging hostname (before the domain is ready)
Until the zone is Active, staging uses `staging.<IP with dashes>.sslip.io`, for example `staging.203-0-113-10.sslip.io` for 203.0.113.10. A real certificate is issued automatically and no DNS record is needed. Once the zone is Active, add the `staging` A record (CLOUDFLARE.md section 2) and switch `STAGING_HOST`, `SITE_BASE_URL` to `staging.zandytouch.com` (below).

## After SSH access works (deployment engineer)
Pre-conditions: server IP, user name, key loaded. QA sign-off for the release has been confirmed (deploy.sh refuses otherwise).

### A. Prepare the server (once)
```
export DEPLOY_HOST=<IP> DEPLOY_USER=ubuntu
ssh $DEPLOY_USER@$DEPLOY_HOST true                       # accepts the host key
scp scripts/ops/{server-setup.sh,set-secret.sh,check-env.sh,lib.sh,backup.sh,restore-test.sh,count_db.clj,two-instance-smoke.sh,smtp_sink.clj,json.clj} \
    deploy/env.*.example $DEPLOY_USER@$DEPLOY_HOST:
ssh $DEPLOY_USER@$DEPLOY_HOST 'sudo bash server-setup.sh'
```
`server-setup.sh` installs Docker and rclone, opens ports 22/80/443 only, creates `/srv/zandy` and `/etc/zandy`, and writes the env files from the templates with machine-made secrets only. It lists what is still `CHANGE_ME`.

Host roles are detected from RAM: under 6 GB the host is **staging only** (roles `staging`, a 2 GB swap file is created, `COMPOSE_FILE=compose.yaml:compose.small.yaml`, no production env files, no production Caddy site); 8 GB gets `staging,production`. Override with `sudo ZANDY_ROLES=staging bash server-setup.sh`. `deploy.sh production` against a staging-only host is refused.

### B. Fill the non-secret values (engineer)
Over SSH, edit `/etc/zandy/staging.env`, `/etc/zandy/ssr.staging.env` and `/srv/zandy/compose/.env` (plus `production.env` and `ssr.production.env` only on a host with the production role):
- `.env`: `STAGING_HOST=staging.<IP-dashes>.sslip.io` (and `PROD_HOST=prod.<IP-dashes>.sslip.io` on a host with the production role), `ACME_EMAIL=<account holder's address, for certificate notices>`.
- `staging.env`/`ssr.staging.env`: `SITE_BASE_URL=https://staging.<IP-dashes>.sslip.io`, `OWNER_EMAIL`, `SMTP_USER` (the Gmail address).
- `backup.env`: `RCLONE_CONFIG_R2_ENDPOINT` (account id), `BACKUP_BUCKET=zandy-touch-backups`.

### C. Secrets (account holder, own SSH session, hidden prompts)
```
sudo /srv/zandy/bin/set-secret.sh staging.env SMTP_PASSWORD               # Gmail app password (step 6)
sudo /srv/zandy/bin/set-secret.sh staging.env OWNER_INITIAL_PASSWORD      # a password of your choice, 12+ characters; tell the owner in person
sudo /srv/zandy/bin/set-secret.sh backup.env RCLONE_CONFIG_R2_ACCESS_KEY_ID
sudo /srv/zandy/bin/set-secret.sh backup.env RCLONE_CONFIG_R2_SECRET_ACCESS_KEY
```
The production server needs `SMTP_PASSWORD` and `OWNER_INITIAL_PASSWORD` in its own `production.env` the same way (Phase 2).
Where each secret lives: Gmail app password in `staging.env`/`production.env`; initial owner password the same (delete the line after the owner has signed in once: `set-secret.sh production.env --unset OWNER_INITIAL_PASSWORD`); R2 keys in `backup.env`; Datomic passwords in `transactor.env` and inside `DATOMIC_URI`; session secret in `production.env`/`staging.env`. All files are `root:zandyops`, mode 640.

### D. First staging deploy
```
QA_SIGNED_OFF=yes scripts/ops/deploy.sh staging --seed
```
Order: build images locally (needs Docker on this machine; otherwise `--build-mode remote` with `ALLOW_REMOTE_BUILD=yes`) -> upload -> add static files -> transactor and Caddy up -> `migrate` -> `bootstrap-owner` -> sample catalogue -> backend restart and wait healthy -> ssr restart and wait healthy -> `check-routing.sh` and `check-static-retention.sh`.
Then, on staging:
```
scripts/ops/check-cache-headers.sh https://staging.<...>
bb scripts/ops/sum_xmx.clj --layout small        # 4 GB staging-only host (without the flag: the 8 GB layout)
bb scripts/ops/check_costs.clj
ssh ... '/srv/zandy/bin/check-env.sh staging'                          # D-7
ssh ... '/srv/zandy/bin/two-instance-smoke.sh'                          # R5 / D-8
ssh ... '/srv/zandy/bin/backup.sh --staging && /srv/zandy/bin/restore-test.sh --staging'   # D-3 (staging-only host; with production: without --staging)
scripts/ops/check-edge-cache.sh https://staging.zandytouch.com          # D-5, once Cloudflare is in front
```
Install the timers only on the server that runs production (deploy/systemd/README.md); a staging-only host has no real data and its backup is a one-off test (`backup.sh --staging`, R2 prefix `staging-trial/`). Then do the measurements in the next section and record them in deploy/DECISIONS.md.
The engineer reports the staging URL and every result, then stops. The owner looks at staging. Nothing goes further without the owner's explicit words that design and function look right.

### What to measure on staging (4 GB host), and why
Goal: the memory plan in `deploy/compose.small.yaml` (transactor 512m, limits 2756 MB in total) was derived from a local test; confirm it on the real server, and learn whether production would fit in 4 GB.
1. Baseline: `free -m`, `docker stats --no-stream`, and `ps -o pid,rss,cmd -C java` after the stack has been idle for ten minutes.
2. **Picture-upload burst** (the heaviest thing the backend does: libvips plus multipart in memory): as staff on staging, bulk-import products and upload 20 pictures of about 5 MB in one go, then again 3 times in a row. While it runs, sample every 2 seconds into a file: `while sleep 2; do docker stats --no-stream --format '{{.Name}} {{.MemUsage}}' ; done > upload-burst.txt`. Record the **peak** per container and the host's lowest `available` from `free -m`.
3. **Order burst:** 100 orders in about 30 seconds. The orders limit (10 per hour per client address, in `resources/config.edn`, not adjustable by env) would stop this through the proxy, so run it on the server straight at the staging backend container (`docker inspect -f '{{(index .NetworkSettings.Networks "zandy_zandy").IPAddress}}' zandy-backend-staging-1`, port 8080) with a different `X-Client-Ip` header per request (the backend trusts that header; on this path no proxy overwrites it). Use `POST /api/orders` with a distinct `Idempotency-Key` each, a seeded product with stock above 100, and the body shape in api-contract.md. Sample as in step 2. Staging orders send mail to the owner address through Gmail: keep `MAIL_DAILY_CAP` low (staging.env has 50) or use `SMTP_HOST` pointing at a closed port during the burst; the outbox just retries.
4. Check the transactor after both: `docker stats` peak against its 1152m limit, and `docker inspect -f '{{.State.OOMKilled}}'` for every container (must be false).
5. Decision rule for "production fits on a VPS-1": peak total of transactor + backend + ssr + caddy during the bursts stays under **2.6 GB** (leaving more than 1.2 GB free on 3.8 GB), no OOM kill, no swap use beyond a few hundred MB, and uploads do not push the backend over about 900 MB. Production uses the D3 heaps (backend 768m, ssr 256m) plus a transactor of 512m to 1024m, so add roughly 250 MB to the staging backend peak. If it does not fit, production goes on a VPS-2 or Contabo.
6. Run the same two bursts again after go-live traffic starts if the choice was a VPS-1.

## Production server at go-live
Go-live is blocked anyway until the owner has approved the four policy texts (launch gate). By then one of these three will be chosen; the staging measurements above decide.
| Option | Monthly (USD, incl. VAT) | When |
|---|---|---|
| 1. OVH VPS-2 8 GB (8.49 EUR ex tax, about 10.37) | production 10.37; with the staging VPS-1 kept the total is 15.95, over the 15 USD ceiling, so cancel the staging VPS-1 at go-live and run staging on the VPS-2 (the original single-host layout) | VPS-2 is back in stock. Safest technically. |
| 2. A second OVH VPS-1 4 GB (about 4.66) | total 10.24 with staging kept | The measurements show production fits (rule above). The 4 GB layout for production needs its own sizing file (production heaps) written from those numbers before `ZANDY_ALLOW_SMALL_PRODUCTION=yes` is used. Smallest cost, least headroom. |
| 3. Contabo Cloud VPS 4, 8 GB, 1-month term (about 7.46 USD; tax not re-checked) | total about 13.04 with the staging VPS-1 | VPS-2 stays out of stock and a VPS-1 is too small. Same Ubuntu/Docker procedure; needs the card. |
Procedure for any of them: order with monthly billing, Ubuntu 24.04 and the SSH key; run `server-setup.sh` (roles `production`, or `staging,production` on one 8 GB host); fill its env files and secrets (section C); `QA_SIGNED_OFF=yes OWNER_APPROVED_STAGING=yes DEPLOY_HOST=<new IP> scripts/ops/deploy.sh production <release>`. A new host builds the images from the same commit that staging ran (the script says so); migrations run before the backend starts. DNS: the apex `A` record points to the production server, the `staging` `A` record stays on the staging server (CLOUDFLARE.md section 2). Enable the backup and restore-test timers on the production server only, then run `backup.sh` and `restore-test.sh` once by hand.

### E. Go live (Phase 2, only after that approval)
1. Account holder: in Cloudflare add `A @ -> <production server IP>` proxied (and `www` if wanted), and set `staging` to proxied if not yet. Wait for DNS.
2. Engineer: set `PROD_HOST=zandytouch.com` in the production server's `/srv/zandy/compose/.env` and `STAGING_HOST=staging.zandytouch.com` in the staging server's; `SITE_BASE_URL=https://zandytouch.com` in `production.env` and `ssr.production.env`; `https://staging.zandytouch.com` for staging. Account holder enters the two production secrets (section C). `check-env.sh production` must pass.
3. `QA_SIGNED_OFF=yes OWNER_APPROVED_STAGING=yes scripts/ops/deploy.sh production <release-on-staging>`. The same images and static files that were approved are promoted; migrations run before the backend starts.
4. **Make production indexable:** only when the owner has approved all four policy texts, set `SITE_INDEXABLE=true` in `ssr.production.env` and restart `ssr` (`docker compose --profile production up -d --no-deps ssr`). ssr refuses to start while a policy is still a placeholder (launch gate); that refusal is correct. Verify `https://zandytouch.com/robots.txt` allows crawling and names `sitemap.xml`, and that staging's still says `Disallow: /`.
5. Re-run the acceptance checks against `https://zandytouch.com`. Seed data must be removed by the owner's launch procedure before real sales (architecture 3.4).
6. Account holder or owner, manual: submit `https://zandytouch.com/sitemap.xml` in Google Search Console (verify the domain with the TXT record Search Console shows, added in Cloudflare DNS, not proxied); optional DNSSEC (CLOUDFLARE.md section 1); the shop owner signs in and changes the initial password; delete `OWNER_INITIAL_PASSWORD` from the env file.

## Operating notes
- Logs: `docker compose logs -f backend` (JSON lines). Caddy access logs have no query strings.
- A restart of the only backend takes 20 to 30 seconds; the proxy retries for 8 seconds and the CDN can serve cached pages (`stale-if-error`) if the CDN rules allow.
- Backups: timer `zandy-backup.timer` 02:30 UTC; check `systemctl list-timers 'zandy-*'` and `/srv/zandy/state/last-backup`. Monthly restore test from R2: `zandy-restore-test.timer`.
- Cloudflare IP ranges (for the real visitor address): `scripts/ops/update-cloudflare-ips.sh`, then `deploy.sh` (ships it), when Cloudflare announces a change.
- Updating Docker images' base: rebuild with `deploy.sh` (base tags float within Ubuntu noble / Temurin 21).

## Rollback
- Application: `DEPLOY_HOST=<IP> scripts/ops/rollback.sh <staging|production>` switches back to the previous release. Images and static files of the previous release stay on the server. Migrations are additive, so the previous backend runs against the new schema for one release. Running it again rolls forward.
- Static files are never removed for 14 days, so a CDN-cached page that names the old bundle still works.
- Data: the database is not rolled back by `rollback.sh`. To restore data from a backup use `restore-test.sh` as the model: stop the backend (`docker compose --profile production stop backend`), restore into a new database name with `datomic restore-db`, point `DATOMIC_URI` to it, start the backend. Do this only with the account holder and shop owner agreeing, since orders after the backup are lost.
- Proxy configuration mistake: the previous Caddyfile is the one in git; fix, `deploy.sh` re-uploads and reloads. `docker compose exec caddy caddy reload --config /etc/caddy/Caddyfile`.
- Total loss of the server: order a new VPS-2, repeat steps A to D with the same env files re-created, restore Datomic from the newest R2 archive and media from `media/current`, update the DNS A records.

## Staging mail sink and the switch to Gmail
Until the Gmail app password exists, staging mail goes to the `mailsink` container and nothing leaves the server. Read what the shop would have sent (order alerts, customer confirmations, password-reset links):
```
ssh -i ~/.ssh/ovicloud_ed25519 ubuntu@141.95.0.8 /srv/zandy/bin/show-mail.sh            # last 200 lines
ssh ... /srv/zandy/bin/show-mail.sh --links                                              # subjects, recipients and links only
ssh ... /srv/zandy/bin/show-mail.sh --follow                                             # wait for new mail (Ctrl-C to stop)
```
Switching staging mail to Gmail (account holder, own terminal, then restart):
```
ssh -t -i ~/.ssh/ovicloud_ed25519 ubuntu@141.95.0.8
sudo sed -i 's#^SMTP_HOST=.*#SMTP_HOST=smtp.gmail.com#; s#^SMTP_PORT=.*#SMTP_PORT=587#; s#^SMTP_USER=.*#SMTP_USER=sandratonanyonjo@gmail.com#' /etc/zandy/staging.env
sudo /srv/zandy/bin/set-secret.sh staging.env SMTP_PASSWORD        # the 16-letter app password, hidden prompt
cd /srv/zandy/compose && docker compose --profile staging up -d --no-deps --force-recreate backend-staging
docker compose --profile staging stop mailsink                      # optional
```
Note: `depends_on: mailsink` in compose.small.yaml starts the sink with the backend; stopping it afterwards is harmless because the backend no longer uses it.

## Ubuntu 26.04 notes
Docker CE installs from download.docker.com/linux/ubuntu `resolute`; Compose is v5 (plugin). The OS ships uutils coreutils: numeric owners for ids without an account must be applied with `chown`, not `install -o`. SSH hardening lives in /etc/ssh/sshd_config.d/00-zandy-hardening.conf (it sorts before the cloud-init file that enables passwords; first match wins).

## Recommendation (not applied): restrict the origin to Cloudflare before production
Why: the origin answers anyone who connects to 141.95.0.8 directly (a scanner at 54.186.188.46 did, 26 October 2026). Nothing private leaks (the staff API needs a session, Caddy ignores a spoofed CF-Connecting-IP), but a direct hit skips Cloudflare's cache, rate limiting and bot filtering, and the limit of 10 orders per hour per address then applies to the scanner's own address. Closing 80/443 to everything except Cloudflare's published ranges (https://www.cloudflare.com/ips-v4 and /ips-v6, the same list as deploy/caddy/cloudflare-ips.conf) removes that.
1. **ufw alone is not enough.** Docker publishes 80/443 through its own iptables chains, which ufw's INPUT rules do not see. The rules must go into the `DOCKER-USER` chain (evaluated before Docker's own), with an ipset: `ipset create cf4 hash:net`, add every range, then `iptables -I DOCKER-USER -p tcp -m multiport --dports 80,443 -m set ! --match-set cf4 src -j DROP` and the same for UDP 443 (HTTP/3, which Cloudflare does not use towards the origin, so it can simply be dropped). Make it persistent with a small systemd unit that runs before docker.service restarts containers, and rebuild the set from `scripts/ops/update-cloudflare-ips.sh` output whenever Cloudflare announces a change. Keep SSH (22) as it is.
2. **Let's Encrypt renewals.** Caddy renews with HTTP-01 / TLS-ALPN-01. For a **proxied** (orange cloud) name the validation request reaches Let's Encrypt's view of the name, which is Cloudflare, so it arrives at the origin from a Cloudflare address and passes the filter. Cloudflare forwards `/.well-known/acme-challenge/*` and exempts it from "Always Use HTTPS". So renewals keep working.
3. **What it breaks.** Any name that is not proxied cannot renew: a grey-cloud record, and the **sslip.io alternative host** (`STAGING_ALT_HOST`), because Let's Encrypt then connects straight to the origin. Remove `STAGING_ALT_HOST` from /srv/zandy/compose/.env (then redeploy or reload Caddy) before applying the filter, and never apply it while a record is grey. It also blocks the engineer's `curl --resolve` direct checks and the uptime monitors that do not go through Cloudflare; run those from the server itself.
4. **Test before relying on it:** from a machine outside Cloudflare, `curl -m 5 --resolve staging.zandytouch.com:443:141.95.0.8 https://staging.zandytouch.com/` must time out, while `https://staging.zandytouch.com/` still answers. Then force a renewal test with `docker compose exec caddy caddy reload` and watch the log for certificate errors (a new certificate is only requested within 30 days of expiry, so also run it with a staging name whose certificate you delete once).
5. **Stronger alternative (no IP lists to maintain):** Cloudflare Authenticated Origin Pulls (mutual TLS: Caddy only accepts connections that present Cloudflare's client certificate). It needs a Caddy `client_auth` block and the Cloudflare setting; the scanner would still connect but get no answer. Can be added to the IP filter later.
