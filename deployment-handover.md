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

## Part A answers (deployment-engineer, 25 October 2026). Waiting for the owner.

Research and prices: deploy/COSTS.md. Recommendation marked (R). All options in the recommended combination are under 15 USD per month, about 10.5 USD in total.

1. Payment method. ANSWERED (owner, 26 Oct 2026): a Swiss credit card, paid from Switzerland, not from Uganda. The options below no longer apply.
   Original question: Both servers need a card or PayPal; neither takes mobile money directly. Which can you use?
   a. (R) An MTN MoMo virtual Mastercard (*165*70#, 3% fee, 30-day validity) or an Airtel Money virtual card (5% fee, one-year validity).
   b. PayPal linked to a Ugandan bank card.
   c. A Ugandan bank Visa or Mastercard that is enabled for online payments.
   d. None of these. Then the only affordable route is a EUR bank transfer to Contabo, which is slow and has bank fees; tell me and I will re-plan.
2. Server. (R) OVHcloud VPS-2, 8 GB, monthly, no commitment: about 9.60 USD plus any VAT shown at checkout. Or Contabo Cloud VPS 4, 8 GB, 1-month term: about 7.46 USD. Neither sleeps. A 4 GB plan is too small for production plus staging.
3. Domain. The cheapest honest .com is Cloudflare Registrar at cost: 10.46 USD until 31 October, about 11.17 USD from 1 November (0.93 USD per month). (R) Register it on or before 31 October. A first-year promo at Spaceship (2.90 USD, code COMPROS) is unconfirmed and costs about 10 USD a year from year 2. Do you want Cloudflare (R) or to try the Spaceship promo?
4. CDN and DNS. (R) Cloudflare Free, on a Cloudflare account you create and own. One risk: its Free plan may cache pages for up to 2 hours, not 90 seconds. I will measure that on staging; if so, we cache only images, scripts and styles at the CDN, or use bunny.net at about 1 to 3 USD per month. OK?
5. Off-site backups. (R) Google Drive through rclone: free, no card, uses 15 GB of your Google storage. Or Cloudflare R2 or Backblaze B2 (10 GB free; R2 needs a card or PayPal to switch on). Which? If Google Drive: which Google account holds the backups?
6. Staging. (R) On the same server with its own database, at staging.zandytouch.com once the domain exists (sslip.io address until then), blocked from Google. It uses about 1.2 GB of the 8 GB. Or only start it for reviews.
7. Gmail app password. Please turn on 2-Step Verification on sandratonanyonjo@gmail.com, create an app password, and keep it ready. I will ask you to paste it into the server environment as SMTP_PASSWORD, never into chat or the repository.
8. Accounts. (R) You, the owner, create and hold every account (server, Cloudflare, backup, Google). I never create accounts or enter payment details. Please confirm, and give me a login-free way to work: an SSH public key you add to the server.
