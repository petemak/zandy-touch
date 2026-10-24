# Scope Checklist: Zandy Touch

Inputs: business-context.md (updated by the owner, wins over the brief), engineering-standards.md (updated, including "Deployment topology"), brand-brief.md. Companion files: design-spec.md, api-contract.md, user-journeys.md.

Conflict check: no conflict with a non-negotiable was found, so nothing needed stopping. The non-negotiables (server-side validation and authorization, no hardcoded secrets, versioned migrations, idempotent mutations, no micro-frontends) are built into api-contract.md. Two points are flagged, not blocking:
- The new topology runs a frontend server, a backend server and Datomic inside 15 USD/month. That is a goal, not a non-negotiable, but it is tight (see "Hosting-budget constraints").
- The contract assumes one public origin (zandytouch.com) with the edge sending `/api/*` and `/media/*` to the backend and everything else to the frontend. The architect confirms this or asks for a contract change (CORS and cookie rules would differ).

## What kind of site this is

A small-business online shop for beauty and wellness goods, with a physical shop in Kampala. Guests browse a catalogue, fill a cart and place an order with name, phone and delivery address. They pay cash or mobile money on delivery. The delivery fee is negotiated with the customer after the order is placed, and staff record the agreed fee on the order before dispatch. Products have a counted stock quantity. Customers place every order themselves in the webshop; staff never enter orders. The owner (root) and support staff run the shop through a back-office. Most visitors use phones. Read traffic dominates, so the public frontend is server-rendered and cache-friendly, and the write-heavy backend is small.

## Capability categories

| Category | Status | Reason |
|---|---|---|
| Catalog / inventory | APPLIES | Six categories: Skincare, Perfumes, Body-shaping, Body care, Weight loss, Health & Wellness. Dietary supplements and the weight-gain porridge are in Health & Wellness. There is no Bedroom category. Owner and support staff add, update and remove products and bulk-upload photos. Stock is counted: each product has a `stockQuantity`. Placing an order reduces it atomically and cannot oversell. Zero stock shows as "Sold out". Staff adjust stock (restock, stocktake, walk-in shop sale, damage) through logged stock movements. Cancelling an order before dispatch restores stock. A product needs a price (plus an image and a category) before it can be published. Removing a product archives it, because past orders reference it. Seed data provides sample products in every category (list in design-spec.md section 9). |
| Payments / checkout | APPLIES, reduced form | Checkout exists: guest checkout, idempotent order placement, payment preference. There is no online payment, no gateway and no card data. Payment is cash or mobile money on delivery. The order records the preferred method and, on delivery, the method actually used. Delivery fee is never computed by the site: the order starts with no fee, staff record the agreed fee before the order is confirmed or dispatched, and the customer sees the total once it is set. |
| Accounts / auth | APPLIES for staff only; DOESN'T APPLY for customers | Customers are guests: no registration, login, wishlist or order history. Staff sign in with two roles, owner (root) and support. The owner adds and deactivates support accounts. Guests can look up an order's status with order number plus phone, which is not an account. |
| Admin / back-office | APPLIES | Owner and support can view orders, process orders and approve delivery, record the delivery fee, manage products (add, update, archive), adjust stock, and bulk-upload photos. Only the owner manages support accounts. Also: password change, owner password recovery by email, a view of contact messages and newsletter subscribers. Staff cannot create orders (decided by the owner). |
| Search / discovery | APPLIES, simple | Category browsing, text search over name, brand and description, sort, and an availability filter. At hundreds of products no search service is needed. |
| Notifications | APPLIES, minimal | Email only. The owner email in business-context.md is the sender address for all site emails and receives order alerts. (1) New-order alert to the owner and to staff who opted in. (2) Order receipt to the customer, only if they gave an email (optional). (3) Contact-form message to the owner. (4) Password reset to the owner. The on-screen confirmation page, the tracking page and WhatsApp click-to-chat are the main customer channels. The newsletter signup only collects consented contacts; the owner sends campaigns manually from an export. No SMS and no WhatsApp Business API. |
| Legal / compliance | APPLIES | Four policy texts: terms of sale, privacy policy, returns and refunds, order terms. They are drafted as placeholders and a human reviews them before launch. The backend serves them as versioned documents, each with a `reviewStatus` of `placeholder` or `approved`, and the site shows a visible "draft text, not yet reviewed" notice while a document is a placeholder, so an unreviewed text cannot go live unnoticed. Data-protection consent points (Uganda Data Protection and Privacy Act 2019) are in the checkout and newsletter forms. Product copy must not make unsupported medical claims (Q2). Design note, not legal advice. |
| Customer agreements | APPLIES, lightweight | Every order is a binding purchase with terms that matter: price excludes a delivery fee that is agreed later, payment is on delivery, the customer must be reachable, cancellation before dispatch, returns rules. So checkout has an explicit "Review and accept the order terms" step before Place order. Acceptance is recorded server-side as part of the same transaction as the order: customer name and phone, server timestamp, agreement type, version and content hash, linked to the order. An order submitted without the current version is rejected. The checkout step and the order-terms page read the same versioned document. It is a first-class step in user-journeys.md. |

## Not building (deliberate)

- Customer accounts, wishlist, saved addresses, reviews.
- Staff-entered orders for phone or WhatsApp customers (decided by the owner). Staff send those customers a product link instead.
- Editing order lines after placement. Staff cancel the order (which restores stock) and the customer reorders (Q7).
- Online payment or mobile-money API integration.
- A delivery fee calculator or zone pricing.
- Discounts, "Sale" pricing (Q9).
- Stock reservation in the cart. Stock is only checked, and reduced, when the order is placed. First order wins the last unit.
- Low-stock email alerts to staff, back-in-stock notifications to customers, "only N left" messages to customers (exact counts stay private).
- Server-side cart. The cart lives in the browser and is validated by the server at checkout.
- Multi-currency and international delivery (planned later; addresses carry a country code).
- An admin editor for policy texts. Policies are versioned documents delivered by migration in v1.

## Launch gate (must be true before go-live)

1. All four policy texts reviewed by a human and set to `approved` (proposal for the architect: production start-up refuses to run with `SITE_INDEXABLE=true` while any policy is `placeholder`, turning the review into an enforced step).
2. Seed sample products and their images archived or replaced with the owner's real catalogue and photos (seed products have the SKU prefix `SEED-`).
3. Owner account created from environment configuration; no password in seed data.
4. Sender mailbox credentials supplied as environment variables (Q14).
5. Delivery cities, shop address, hours and WhatsApp number confirmed (Q1, Q3, Q10).

## Hosting-budget constraints that shape the design

- Budget: at most 15 USD/month in total. The topology now needs a frontend server process, a backend server process and Datomic. Launch runs one instance of each; the design allows more later. The backend and devops roles must confirm that this fits the budget before committing (Datomic is the heaviest piece, and two JVM processes add to it). The single-origin path routing at the edge can be a free CDN tier (for example Cloudflare free) plus a small reverse proxy on the server.
- Shared state (sessions, rate limits, idempotency keys, stock) lives in the backend's database, so any instance can serve any request and the stock check stays atomic across instances.
- Public GET endpoints are cacheable (see api-contract.md section 1.5) so a CDN and the frontend server absorb most traffic. Stock-bearing responses use a short lifetime (30 seconds) and are never trusted at order time.
- Images: stored on the backend's disk or volume, with thumb, medium and large WebP variants. Limits: 5 MB per file, 20 files per bulk request, 6 images per product. Immutable one-year caching.
- Email: a free or low-tier SMTP account only for order alerts, receipts, contact messages and password resets.
- Public pages are server-rendered HTML with a small client bundle, for phone speed, SEO and low data cost.

## Open questions for the owner

See design-spec.md section 10 (Q1 to Q15). Questions that business-context.md now answers (porridge category, Bedroom, product price negotiation, counted stock, staff-entered orders) are closed.
