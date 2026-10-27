# Hosting costs: Zandy Touch

STATUS: OWNER CHOICES MADE (26 October 2026); FINAL TOTAL BELOW. Nothing is bought or booked yet. Re-check every price on the day of purchase.
Prices checked on 25 October 2026 (the day this was drafted) from the URLs given. Re-check on the day of purchase.
Ceiling: 15 USD per month in total, including VAT, the domain (per year divided by 12) and storage (business-context.md, engineering-standards.md).
Exchange rate used for EUR prices: 1 EUR = 1.13 USD (October 2026 average, approximate; the real charge depends on the payment method's rate).

## What has to fit (architecture.md D3 and section 8)
- Production alone: transactor ~1.3 GB, backend ~1.1 GB, ssr ~0.5 GB, OS and proxy ~0.5 GB = ~3.4 GB.
- With staging on the same server: ~5.3 GB. A 4 GB plan does not fit either way with headroom; an 8 GB plan fits both with ~2.7 GB spare.
- JVM 21 or later, a persistent disk, several processes, libvips CLI: any plain Linux VPS gives these. Managed app platforms (Fly.io, Render) price per process and do not.

## Servers (all stay on, nothing sleeps; all are plain VPSs)
| Option | Plan | Price per month | Billing term / commitment | VAT | Payment methods | Fits section 8? | Source |
|---|---|---|---|---|---|---|---|
| OVHcloud | VPS-2: 4 vCPU, 8 GB, 75 GB NVMe | 8.49 EUR (about 9.60 USD) monthly. The page headline "from 8.50 USD" is a longer-term price. 12 months upfront: 86.52 EUR (7.21 EUR/month) | Monthly billing, no commitment. 12 months upfront is optional | Prices exclude VAT; the VAT for a Uganda customer is not stated and must be read at checkout | Visa, Mastercard, Maestro, PayPal; prepaid account (points); some regional pages also list bank transfer (at least 3 days). No mobile money. CARD OR PAYPAL NEEDED | Yes. Includes a rolling 24-hour backup of the VPS (not off-site, so not a substitute for D-3) | https://www.ovhcloud.com/en/vps/ , https://old.hostingcompass.com/en/providers/ovhcloud/vps , https://www.ovhcloud.com/en/terms-and-conditions/payment/ |
| Contabo | Cloud VPS 4: 4 vCPU, 8 GB, 100 GB SSD | 1-month term 6.60 EUR (about 7.46 USD). 12-month 5.61 EUR. 24-month 5.28 to 5.50 EUR | 1-month term is prepaid with no cancellation notice. 12 and 24 months are commitments | Contabo shows 5.50 EUR "including applicable taxes" for 24 months; tax for Uganda not stated, read at checkout | Credit card and PayPal in USD; in EUR also bank transfer, Skrill and direct debit. No mobile money, no crypto. CARD OR PAYPAL NEEDED (EUR bank transfer is the one non-card route) | Yes. Disk speed under load is unverified (shared SSD); the transactor writes to local disk. Auto Backup is a paid add-on | https://contabo.com/en/vps/ , https://toolradar.com/tools/contabo/pricing , https://contabo.com/blog/kb/103000269311-which-currencies-are-accepted-by-contabo/ |
| Hetzner | CX23 (4 GB) / CX33 (8 GB) 5.99 / 8.99 EUR; regular CPX 19.99 EUR for 4 GB | not orderable | none | n/a | card | NO. Cost-optimised plans marked unavailable since 7 September 2026; the available regular plans are over budget | https://www.vincentschmalbach.com/hetzner-cheap-cloud-unavailable-price-increases/ |
| Lineserve (Kampala, pays in UGX) | 8 GB Linux VPS | UGX 179,000 (about 49 USD) plus 18% VAT | Month-to-month; annual = 10 months for 12 | 18% extra | MTN MoMo, Airtel Money, bank, card (UGX) | Fits technically. OVER BUDGET (about 58 USD with VAT). The 4 GB plan (UGX 89,400) is too small | https://www.lineserve.co.ug/vps |
| AFRICLOUD | VM6 8 GB | 180 USD list, 90 USD with a 50% promo code | monthly | not stated | MTN MoMo, Airtel Money, card, PayPal, crypto | OVER BUDGET | https://africloud.com/ug |
| Fly.io | shared-cpu-1x 2 GB machine 10.70 USD, volume 0.15 USD/GB | Two such machines (4 GB) already cost ~21 USD, before the transactor | pay as you go | n/a | CARD ONLY after the trial | NO (over budget, per-process pricing) | https://fly.io/docs/about/pricing/ |
| Oracle Cloud Always Free (Ampere A1, 4 cores, 24 GB) | free | 0 | none | n/a | CARD NEEDED (1 USD hold). Capacity errors are widely reported | Maybe, but unreliable to obtain and a card is required. Not recommended for a shop | https://docs.oracle.com/en-us/iaas/Content/FreeTier/resourceref.htm |

Lead from the architect not confirmed: "OVH VPS-1 4 GB from 4.54 USD" is real but too small (see memory above).

## Domain: zandytouch.com
No .com is free. The cheapest honest floor today is the registry price plus the ICANN fee, which a registrar can sell at cost.
Verisign's wholesale price rises from 10.26 to 10.97 USD on 1 November 2026, so the at-cost floor goes from 10.46 to 11.17 USD per year. Source: https://tldes.com/pages/com-price-increase-2026 , https://costgoat.com/compare/domains/com (updated 20 October 2026).
| Registrar | Year 1 | Renewal | Payment | Notes | Source |
|---|---|---|---|---|---|
| Cloudflare Registrar | 10.46 USD until 31 Oct 2026, then about 11.17 | same (at cost, no markup) | Card (Visa, Mastercard, AmEx, Discover, UnionPay) or PayPal | Domain must use Cloudflare DNS, which is free and is what we want. Buying 31 Oct or earlier locks the lower price for this purchase | https://costgoat.com/compare/domains/com , https://developers.cloudflare.com/billing/get-started/create-billing-profile/ |
| Porkbun | 11.08 USD (all fees included, same for renewal and transfer) | 11.08 | Card, PayPal, crypto | Flat prices | https://porkbun.com/products/domains |
| Spaceship | 2.90 USD first year with promo code COMPROS (seen May to Oct 2026 on a comparison site, NOT confirmed on Spaceship's own site, which blocked my fetch) | 10.18 USD (will likely rise with the 1 Nov wholesale change) | Card; PayPal not confirmed | Saves about 8 USD in year 1 only. Check the code still works at checkout | https://domainoffer.net/blog/namecheap-vs-godaddy-vs-spaceship-vs-porkbun |
| Namecheap | 11.48 USD | 18.68 USD | card, PayPal | Renewal is the highest of the group | https://costgoat.com/compare/domains/com |
| .co.ug / .ug | n/a | 60 to 160 USD per year | various | Not an option on price | https://anycast.io/domains/co.ug |
Domain cost in the monthly total: 11.17 / 12 = 0.93 USD per month (worst case, after the rise). Cheapest year 1 route: Spaceship promo, 2.90 / 12 = 0.24 USD per month, then about 0.85 per month.

## CDN and DNS
| Option | Price | Honours origin Cache-Control | Payment | Source |
|---|---|---|---|---|
| Cloudflare Free (DNS + proxy CDN) | 0 | Yes: s-maxage, stale-while-revalidate, stale-if-error, no-store, private are honoured; origin cache control is on by default for Free. HTML and JSON need a Cache Rule ("eligible for cache"). Bypass rules for /api/admin, /api/auth, /admin and the zt_session cookie are possible in Free rules. RISK: the documented minimum Edge Cache TTL on Free is 2 hours, and a community answer says a 60 s s-maxage may then be cached up to 2 hours. NOT CONFIRMED either way; check D-5 measures it. The owner decision (pages at most about 90 s old) would be broken if it holds. Fallbacks below | No card for the Free plan itself. The domain purchase needs card or PayPal | https://developers.cloudflare.com/cache/concepts/cache-control/ , https://developers.cloudflare.com/cache/how-to/edge-browser-cache-ttl/ |
| bunny.net CDN | 0.01 USD/GB (Europe, North America), 0.06 USD/GB (Middle East and Africa); 1 USD monthly minimum; 14-day trial without a card | Yes, per their docs claims; not verified by me | Card, PayPal, crypto | https://bunny.net/pricing/cdn/ |
| No CDN at launch | 0 | n/a | none | An 8 GB server handles hundreds of customers; the cost is slower pages from Europe to Uganda and no stale-if-error cover |
Fallbacks if Cloudflare Free keeps pages for 2 hours: (a) cache only /static, /media and immutable files at Cloudflare and let HTML pass through (no loss of freshness, more origin load); (b) bunny.net for HTML at about 1 to 3 USD per month; (c) Cloudflare Pro, 25 USD, over budget.

## Off-site backup destination (Datomic backup-db plus MEDIA_DIR; 7 daily and 4 weekly sets)
Current data: media 241 MB (development folder), Datomic backups are small and incremental. 10 GB free fits launch.
| Option | Price | Payment | Notes | Source |
|---|---|---|---|---|
| Google Drive via rclone | 0 (15 GB free shared with the Gmail account) | None | No card. Uses the owner's Google account (same account as the shop email). The Google OAuth grant expires after 7 days unless the OAuth app is published (rclone docs); one-time set-up on the server | https://rclone.org/drive/ |
| Cloudflare R2 | 10 GB-month free, then 0.015 USD/GB-month; no egress fee | Card or PayPal required to enable R2, even for the free tier | S3 API; same vendor as DNS and domain | https://developers.cloudflare.com/r2/pricing/ |
| Backblaze B2 | first 10 GB free, then 6.95 USD per TB-month; egress free up to 3x stored | Whether a card is needed to sign up is not stated on the page; billing is by card or invoice | S3 API; separate vendor from Cloudflare and from the server | https://www.backblaze.com/cloud-storage/pricing |

## Free staging hostname
| Option | Price | Notes |
|---|---|---|
| staging.zandytouch.com (subdomain of the real domain, on Cloudflare) | 0 once the domain exists | Lets us test the CDN rules (D-5) on a real zone. Marked noindex and robots Disallow |
| <server-ip-with-dashes>.sslip.io | 0, no account | Real Let's Encrypt certificate through Caddy. Works the moment the server exists, no CDN. https://sslip.io |
| DuckDNS | 0 | Needs a Google, GitHub or Twitter login. https://www.duckdns.org/faqs.jsp |

## Mobile-money route for the card problem (leads for the owner, not verified by an actual purchase)
- MTN MoMo virtual Mastercard: dial *165*70#; 3% fee per transaction, UGX 1,000 issuing fee, valid 30 days. https://nymynet.com/?p=11128
- Airtel Money Global Pay virtual card: 5% per transaction, no issuing fee, valid one year. https://africanwirelesscomms.com/airtel-money-launches-virtual-payment-card-in-uganda-for-seamless-online-transactions/
- PayPal works in Uganda and can be linked to a Ugandan Visa or Mastercard debit card. https://www.dignited.com/16173/receive-money-and-get-paid-via-paypal-uganda/
- Budget about 5% extra for card fees: well under 1 USD per month at these prices.
Whether a given virtual card is accepted by OVH, Contabo or Cloudflare cannot be known until the owner tries the card at checkout. No purchase has been attempted.

## Monthly totals against the 15 USD ceiling
Server + domain at 11.17/12 + off-site backup + CDN + staging (same server, free hostname). Fees on a mobile-money card (3 to 5%) shown separately. "Worst VAT" assumes 20% VAT on the server.
| # | Combination | Server | Domain | Backup | CDN/DNS | Total per month | Worst VAT | With 5% card fee |
|---|---|---|---|---|---|---|---|---|
| A | OVH VPS-2 monthly + Cloudflare Registrar + Cloudflare Free + Google Drive/rclone, staging on same server | 9.60 | 0.93 | 0 | 0 | 10.53 | 12.45 | 11.06 |
| B | Contabo Cloud VPS 4, 1-month term + Cloudflare Registrar + Cloudflare Free + Google Drive/rclone, staging on same server | 7.46 | 0.93 | 0 | 0 | 8.39 | 9.88 | 8.81 |
| C | A or B with bunny.net for HTML (fallback) | as A or B | 0.93 | 0 | 1 to 3 | 11.5 to 13.5 (A), 9.4 to 11.4 (B) | up to 15.5 for A | |
| D | Contabo 24-month term (5.50 EUR = 6.22 USD) + Spaceship promo year 1 | 6.22 | 0.24 | 0 | 0 | 6.46 in year 1 | | Needs about 150 USD upfront and a 24-month commitment; not recommended |
| E | Lineserve 8 GB (pay with MoMo) | 57.7 with VAT | 0.93 | 0 | 0 | ~59 | | OVER the ceiling |
Combinations A and B stay under 15 USD in the worst case; C stays under 15 USD for B and for A when bunny costs under about 2 USD. D-4 will be checked again against the real invoice.


## FINAL monthly cost (decided combination, 26 October 2026)
Owner decisions: OVHcloud VPS-2 8 GB monthly; domain zandytouch.com at **Porkbun** (owner's existing account); Cloudflare Free for DNS and CDN (owner's existing account, nameservers at Porkbun pointed to Cloudflare); Cloudflare R2 for off-site backups; staging on the same server. Payer: Swiss credit card, paid from Switzerland.
The Cloudflare Registrar prices above (10.46 and 11.17 USD, and the 31 October deadline) no longer apply.

| Item | Price | Per month (USD) | Billing, VAT, payment | Source and date checked |
|---|---|---|---|---|
| OVHcloud VPS-2, 4 vCPU, 8 GB, 75 GB NVMe, Europe | 8.49 EUR/month excl. VAT, 1 EUR = 1.13 USD | 9.59 | Monthly, no commitment. Swiss VAT 8.1% likely: +0.78 = 10.37. Swiss credit card (Visa, Mastercard) | https://www.ovhcloud.com/en/vps/ , checked 25 Oct 2026 (re-check at checkout) |
| Domain zandytouch.com, Porkbun | 11.08 USD/year all fees included, same on renewal | 0.92 | Yearly. Card or PayPal | https://porkbun.com/products/domains , checked 25 Oct 2026 |
| Cloudflare Free (DNS, proxy CDN, SSL) | 0 | 0 | No payment for the Free plan | https://developers.cloudflare.com/cache/concepts/cache-control/ |
| Cloudflare R2 backups (first 10 GB free, no egress fee) | 0 at launch size | 0 | Cloudflare account needs a payment method on file; 0.015 USD/GB-month above 10 GB | https://developers.cloudflare.com/r2/pricing/ , 25 Oct 2026 |
| Staging | same server | 0 | | |
| Gmail SMTP (shop owner's account) | 0 | 0 | | |
| sslip.io hostname before DNS | 0 | 0 | | |

TOTAL_MONTHLY_USD: 11.29   (the planned single-host VPS-2 layout; superseded for now by the staging-only phase below)

- With Swiss VAT 8.1% on the server: 10.37 + 0.92 = **11.29 USD per month** (about 11.30). Without VAT: 10.51.
- Worst case if VAT were 20%: 11.51 + 0.92 = 12.43. Still under the 15 USD ceiling, with at least 2.7 USD to spare.
- Card currency conversion (a Swiss franc card paying EUR and USD) usually adds 1 to 3%: under 0.35 USD per month; not included above.
- Bunny.net fallback for HTML if the Cloudflare Free edge TTL (check D-5) is too long: +1 to 3 USD, total up to about 14.3 USD, still under the ceiling.
- Check D-4 (`bb scripts/ops/check_costs.clj`) reads the TOTAL_MONTHLY_USD line above and fails above 15.


## UPDATE 26 October 2026: OVH VPS-2 out of stock, staging-only server
An order for VPS-2 failed with "Sorry we are out of stock in region GRA". Server for now: **OVH VPS-1 4 GB, staging only (VPS-2 out of stock, 26 Oct 2026); production server to be decided at go-live.** VPS-1 2027: 2 vCore, 4 GB RAM, 40 GB NVMe, daily backup included, **3.81 EUR per month excluding tax** (figure from the owner's order page on 26 October; not re-checked by deployment-engineer; check VAT and total at checkout). 3.81 EUR x 1.13 = 4.31 USD; with 8.1% Swiss VAT 4.66 USD.

| Scenario (monthly, USD, Swiss VAT 8.1% on OVH, domain 11.08 / 12 = 0.92) | Servers | Total |
|---|---|---|
| A. Staging phase (now): VPS-1 for staging only | 4.66 | 5.58 |
| B. Go-live on a new VPS-2 (10.37) and the staging VPS-1 is kept | 4.66 + 10.37 | 15.95 (OVER the ceiling by 0.95) |
| B2. As B, but the staging VPS-1 is cancelled at go-live and staging moves onto the VPS-2 (the original single-host layout; monthly billing, no commitment) | 10.37 | 11.29 |
| C. Production on a second VPS-1, staging VPS-1 kept (only if staging shows production fits in 4 GB) | 4.66 + 4.66 | 10.24 |
| D. Production at Contabo Cloud VPS 4, 1-month term (7.46 USD from the table above; Contabo tax not re-checked), staging VPS-1 kept | 4.66 + 7.46 | 13.04 (13.64 if 8.1% VAT applies to Contabo) |

TOTAL_STAGING_PHASE_USD: 5.58
TOTAL_GO_LIVE_B2_USD: 11.29
TOTAL_GO_LIVE_C_USD: 10.24
OVER_CEILING_B_USD: 15.95

Reading: the ceiling holds in A, B2, C and D. B is the only combination over 15 USD: keep two OVH servers only if one of them is VPS-1. Check D-4 reads every `TOTAL_...USD` line and fails if any is above 15; OVER_CEILING lines are printed as warnings.
