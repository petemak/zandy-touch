# Deployment handover: Zandy Touch

Read by deployment-engineer after qa has signed off. Written at the architecture stage (24 October 2026).

What is binding is in architecture.md section 8, "What deployment must provide". This file holds only:
- **Part A:** the questions the owner answers at the deployment stage.
- **Part B:** the architect's preliminary research.

Part B is **not a decision and not verified**. Prices and availability go stale within months. deployment-engineer researches the options again, presents them, and lets the owner choose.

---

## Part A. Choices for the owner at the deployment stage

deployment-engineer researches the options for each item, then asks the owner to choose. Don't decide these alone.

1. **Server provider and plan.** It must fit the memory plan in architecture.md section 8 and the 15 USD/month ceiling in business-context.md, including VAT and any commitment term.
2. **Domain.** The owner finds about 12 USD a year too expensive. Compare at-cost registrars, renewal price against first-year promotion, and transfer options. Also offer a free interim hostname for staging.
3. **CDN and DNS.** Choose a CDN that honours origin `Cache-Control` (including `s-maxage`, `stale-while-revalidate`, `stale-if-error`) and can bypass the cache for admin, auth and session-cookie requests. Say who creates and holds the account.
4. **Off-site backup destination** for the Datomic backups and `MEDIA_DIR`.
5. **Payment method.** Cards are uncommon in Uganda. For each paid item, say whether it needs a card or PayPal, and offer alternatives where one exists.
6. **Staging placement.** Staging can run on the production server, or be brought up only for review.
7. **Gmail app password.** The owner creates it after turning on 2-Step Verification on the owner Gmail account. It goes only into the server environment as `SMTP_PASSWORD`.

## Part B. Preliminary research from the architecture stage (unverified, non-binding)

Checked around 20 to 24 October 2026, mostly from vendor pages and third-party summaries. Use these as leads only.

**Server**
- OVHcloud: VPS-1, 4 GB, from about 4.54 USD. VPS-2, 8 GB, from about 8.50 USD. The commitment and VAT terms were unclear. [OVHcloud VPS](https://www.ovhcloud.com/en/vps/)
- Contabo: 8 GB at about 5.50 EUR on a 24-month promotional term. [Contabo VPS](https://contabo.com/en/vps/)
- Hetzner: cheap tiers reported unavailable since September 2026. The 4 GB Regular Performance plan was about 19.99 EUR. [note](https://www.vincentschmalbach.com/hetzner-cheap-cloud-unavailable-price-increases/)
- The architect estimated that production plus staging needs about 5.3 GB of RAM, so a 4 GB plan has no headroom. Measure this rather than trust it.

**Domain (.com)**
- Cloudflare Registrar at cost: about 10.46 USD a year, rising to about 11.17 after the 1 November 2026 wholesale increase.
- Others: Dynadot 10.88, Porkbun 11.08, Namecheap 11.48 then 18.68 on renewal.
- Spaceship first-year promotions are reported near 4.99. Verify against the new wholesale price.
- No .com is free. [costgoat](https://costgoat.com/compare/domains/com), [domaindetails](https://domaindetails.com/registrars/cheapest)
- Free interim: a DuckDNS subdomain with Let's Encrypt, for staging only (no CDN).

**CDN**
- Cloudflare's free plan documents honouring `s-maxage`, `stale-while-revalidate`, `stale-if-error` and `no-store`. HTML and JSON need a Cache Rule to be cached.
- The minimum edge TTL for 30 to 60 second lifetimes on the free plan was not confirmed. [Cloudflare Cache-Control](https://developers.cloudflare.com/cache/concepts/cache-control/)

**Backup storage**
- Cloudflare R2: 10 GB free, no egress fee, needs a payment method.
- Backblaze B2: first 10 GB free.
- Google Drive via `rclone` needs no card.

**Edge proxy**
- Caddy would cover path routing, TLS, compression, load balancing and health checks in one small process. Nginx or Traefik are alternatives.

**Not verified:** Datomic's minimum transactor heap, OVH commitment and VAT terms, and Contabo disk performance.
