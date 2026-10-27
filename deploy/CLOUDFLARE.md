# Cloudflare setup: Zandy Touch

Account: the payer's existing Cloudflare account, Free plan (decisions 3 and 4). The domain is bought at Porkbun; Cloudflare only hosts the DNS zone, the proxy/CDN and R2.
Written 26 October 2026. Dashboard labels move; where a label below is paraphrased it is marked "(label may differ)". The behaviour described comes from Cloudflare's documentation: https://developers.cloudflare.com/cache/concepts/cache-control/ , https://developers.cloudflare.com/cache/how-to/cache-rules/order/ , https://developers.cloudflare.com/cache/how-to/edge-browser-cache-ttl/ .

## 1. Zone and nameservers (account holder)
1. Cloudflare dashboard, Add a domain, `zandytouch.com`, Free plan. Cloudflare shows two nameservers (for example `xxx.ns.cloudflare.com`).
2. At Porkbun: domain `zandytouch.com`, Details, Authoritative Nameservers, replace Porkbun's with the two Cloudflare names. Wait for Cloudflare to show the zone as Active (minutes to a few hours).
3. DNSSEC is optional and can wait: in Cloudflare, DNS, Settings, Enable DNSSEC, then copy the DS record into Porkbun's DNSSEC section. Do it only after the zone is Active.

## 2. DNS records (zone `zandytouch.com`)
Server IPv4 address = `<IP>` (from the OVH panel). While staging and production are on different servers (VPS-1 for staging, another server for production), `staging` points to the staging server's IP and `@` to the production server's IP. No AAAA records: compose publishes IPv4 only so the visitor address reaches Caddy unchanged.

| Type | Name | Content | Proxy status | Notes |
|---|---|---|---|---|
| A | `@` (zandytouch.com) | `<IP>` | Proxied (orange cloud) | Production. Add at go-live, not before (RUNBOOK Phase 2). |
| A | `staging` | `<IP>` | Proxied | Staging. Add once the zone is Active; until then staging uses `staging.<ip-with-dashes>.sslip.io` with no Cloudflare. |
| CNAME | `www` | `zandytouch.com` | Proxied | Optional. Pair with the redirect rule in section 6. |

Mail records are not needed (mail goes through the shop owner's Gmail).

## 3. SSL/TLS
- SSL/TLS, Overview: encryption mode **Full (strict)**. Caddy holds a real Let's Encrypt certificate for each hostname, so strict works.
- Edge Certificates: Always Use HTTPS **on**; Minimum TLS Version 1.2; TLS 1.3 on. Leave **HSTS off at Cloudflare**: the origin already sends `Strict-Transport-Security` (Caddyfile), one source only.
- If Caddy cannot get a certificate while the record is proxied (Let's Encrypt validation failing in the Caddy log), set that one record to DNS only (grey cloud) until the certificate is issued, then turn the proxy back on. Last resort: an Origin CA certificate (SSL/TLS, Origin Server, 15 years) copied to the server and referenced with `tls <cert> <key>` in the Caddyfile.

## 4. Features that must be OFF (they rewrite the HTML or JavaScript the server rendered)
Speed, Optimization: Rocket Loader **off**, Auto Minify (if shown) **off for HTML, CSS and JS**, Mirage off. Email Address Obfuscation **off** (in the current dashboard: Security, Settings, category "Client side abuse"; formerly Scrape Shield; the dashboard search box also finds it) (it edits the page and breaks client-side start-up, which re-renders the same markup). Always Online off (it would serve a stale copy that hides the origin's own `stale-if-error`). Leave Brotli on. Leave Polish off.

## 5. Cache Rules (Caching, Cache Rules): architecture D2 and section 8
The application states its caching through `Cache-Control` on every response (`no-store` on private data and errors). The CDN only honours it.
The rule order matters: Cloudflare documents that when several rules match, **the last matching rule wins** for each setting. So the cache rule excludes the private paths itself, and the bypass rule is last as a second safety net.

**Rule 1: "Cache public pages and API, honour origin"**
- When incoming requests match, Custom filter expression (edit expression):
  ```
  (http.host in {"zandytouch.com" "staging.zandytouch.com"})
  and not starts_with(http.request.uri.path, "/api/admin")
  and not starts_with(http.request.uri.path, "/api/auth")
  and not starts_with(http.request.uri.path, "/admin")
  and not http.cookie contains "zt_session"
  ```
- Cache eligibility: **Eligible for cache** (this is what lets HTML and JSON be cached at all).
- Edge TTL: **Use cache-control header if present, bypass cache if not** (label may differ). Never "Ignore cache-control header and use this TTL": that is a hard-coded edge TTL, which architecture section 8 forbids. Because every origin response carries an explicit `Cache-Control`, the origin stays in charge.
- Browser TTL: **Respect origin TTL / Respect existing headers** (label may differ). Cloudflare must not insert or override `Cache-Control`.
- Cache key: leave the default. `ssr` drops unknown query parameters, so they cannot multiply cache entries.
- Serve stale while revalidating / stale-if-error: leave Cloudflare's defaults; they follow the origin directives.

**Rule 2 (last): "Bypass private paths"**
- Expression:
  ```
  starts_with(http.request.uri.path, "/api/admin")
  or starts_with(http.request.uri.path, "/api/auth")
  or starts_with(http.request.uri.path, "/admin")
  or http.cookie contains "zt_session"
  ```
- Cache eligibility: **Bypass cache**.

Why `zt_session`: the cookie has `Path=/api`, so browsers send it only on `/api/...` requests. A signed-in staff member's API calls bypass the cache, public page requests never carry it and stay cacheable.

The documented Cloudflare behaviour (cache-control page above): `s-maxage`, `stale-while-revalidate`, `stale-if-error`, `no-store` and `private` are honoured, and origin cache control is on by default on Free.

## 6. Optional redirect for www
Rules, Redirect Rules: if `http.host eq "www.zandytouch.com"` then Dynamic redirect to `concat("https://zandytouch.com", http.request.uri.path)` status 301, preserve query string. Free plans include this.

## 7. R2 (backups, decision 4)
1. R2, Create bucket `zandy-touch-backups` (location automatic, default storage class). Do not enable public access.
2. R2, Manage API tokens, Create API token: permission **Object Read & Write**, **Specify bucket** `zandy-touch-backups` only, no TTL. Copy Access Key ID and Secret Access Key once (they are shown once). Also note the account id / S3 endpoint `https://<account id>.r2.cloudflarestorage.com` on the same page.
3. These go to the server only, with `set-secret.sh` (RUNBOOK step 8), never into chat or the repository.
4. R2 needs a payment method on the Cloudflare account even for the free allowance (10 GB). Backups at launch are well below that. Pricing beyond: 0.015 USD per GB-month, no egress fee (https://developers.cloudflare.com/r2/pricing/).

## 8. How to measure the edge-TTL risk (check D-5) on staging
The risk: Cloudflare documents a minimum Edge Cache TTL of **2 hours on the Free plan**. If that minimum overrides a 60 second `s-maxage`, catalogue pages could be up to 2 hours old instead of about 90 seconds (owner decision, Q6). Nobody has confirmed which way it goes, so measure it.

1. Staging hostname proxied through Cloudflare, Rules 1 and 2 in place, SSL Full (strict).
2. Run (takes about 4 minutes):
   ```
   scripts/ops/check-edge-cache.sh https://staging.zandytouch.com
   ```
   It checks that: admin, auth and `zt_session` requests are never cache hits; the second fetch of `/shop` and `/api/products` is a hit; and, polling every 10 s for 240 s, the largest `Age` of a hit stays at or below 100 s (the origin allows 60+30 s for pages and 30+60 s for the API).
3. Reading the result:
   - **PASS**: Cloudflare Free honours the short lifetimes. Nothing to change.
   - **FAIL with "MEASURED minimum edge TTL is at least N s"**: the edge keeps objects longer than the origin allows. The measured number is the evidence for the owner. Fall back, in this order (decision 3):
     a. Cache only static files and media at Cloudflare. Change Rule 1 so it matches `starts_with(http.request.uri.path, "/static/") or starts_with(http.request.uri.path, "/media/")` only; HTML and API JSON then pass through to the server (always fresh, no `stale-if-error` cover from the CDN, more load on the VPS, which is sized for it).
     b. bunny.net for HTML at about 1 to 3 USD per month (COSTS.md); a card is needed there.
     c. Cloudflare Pro, 25 USD per month: over budget, listed only for completeness.
4. Record the measured number and the decision in `deploy/DECISIONS.md` and the result in the staging report.
