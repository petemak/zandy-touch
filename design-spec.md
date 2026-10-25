# Design Spec: Zandy Touch

Inputs: business-context.md (wins over the brief), engineering-standards.md (including "Deployment topology"), brand-brief.md (black `#0B0B0B`, gold `#C9A24B`, ivory `#F6F0E4`; Playfair Display headings, Inter body; calm, elegant voice). Companion files: scope-checklist.md, api-contract.md, user-journeys.md.

Constraints carried through: guest checkout only; cash or mobile money on delivery; no online payment; delivery fee negotiated per order, recorded by staff, never computed by the site; product prices set by the owner and required before publishing; counted stock that cannot oversell; no staff-entered orders; mobile first; WCAG 2.1 AA; one deployable frontend build; light pages and no third-party widgets for the hosting budget.

## 0. Site-wide notes

- Business schema.org type: **Store** (a physical shop in Kampala plus online ordering). Product pages additionally carry Product and Offer markup. `Offer.availability` is `InStock` or `OutOfStock` from the product's `availability`.
- Categories (six, final): Skincare, Perfumes, Body-shaping, Body care, Weight loss, Health & Wellness. Dietary supplements and the weight-gain porridge are in Health & Wellness. There is no Bedroom category anywhere in navigation, icons, copy or seed data.
- Topology and rendering: the frontend server renders public pages on the server (SSR) from the same Replicant views the browser uses, so title, meta, canonical and JSON-LD are in the first HTML. It reads data only through the backend HTTP API (api-contract.md section 12), never Datomic. The browser hydrates, reusing the embedded data. Cart, checkout, order-placed, track-order, unsubscribe and all admin pages are a `noindex` document shell rendered in the browser. The frontend owns `robots.txt` and `sitemap.xml` (built from `GET /api/sitemap-entries`), which are blocked on staging.
- Caching of rendered pages (frontend server and CDN), kept close to the API lifetimes so stock staleness stays bounded:
  - Home, shop, category, product: `public, s-maxage=60, stale-while-revalidate=30`, browser `max-age=0` (at most about 90 seconds old; owner decision). Never cache an error or 503 response.
  - About, contact, delivery and payment, legal pages: `public, s-maxage=600, stale-while-revalidate=600`.
  - Document shells (cart, checkout, order-placed, track-order, admin): `no-cache` with an ETag. Fingerprinted JS and CSS: one year, immutable.
  - Stock is shown on cached pages, so the product page re-checks its product from the API on load (a revalidating request) and updates "Add to cart" and the quantity limit if the stock changed. The cart and checkout always validate against the live server. A cached page is a hint; the order endpoint is the authority.
- Prices always show as "UGX 85,000". Everywhere a cart or order total appears, delivery reads "agreed with you before dispatch". The site never shows a delivery fee estimate. Once staff have recorded a fee on an order, tracking shows the fee and the total.
- Stock display: customers see only "Available" or "Sold out", never a count, plus an "up to N per order" limit where stock is below 10. A sold-out product stays visible (badge, disabled button, WhatsApp link) and sorts after available products.
- Tone: calm and warm, no exclamation marks, no medical or guarantee claims (Q2). Product copy comes from the owner as plain text.
- Placeholder policies: while a policy document's `reviewStatus` is `placeholder`, its page, and the order-terms panel in checkout, show a visible notice "Draft text, not yet reviewed". It disappears when the document is approved. This keeps unreviewed text from going live unnoticed.
- Accessibility: gold on black passes for text, gold on ivory does not (use black text on ivory, gold only for large elements or borders). Visible focus ring, 44px touch targets, labels on every field, an error summary on long forms, alt text on every product image, no information by colour alone, reduced-motion respected, `aria-live` for cart and stock changes.
- Route rule (frontend page routes, Reitit): no static route beside a `{param}` route at the same position. Hence `/categories/{slug}`, `/products/{slug}`, `/admin/new-product` with `/admin/products/{id}` (not `/admin/products/new`). All `/legal/*` routes are static siblings with no parameter.

## 1. Page list

Public pages:

| # | Page | Path |
|---|---|---|
| P1 | Home | `/` |
| P2 | Shop (all products, search results) | `/shop` |
| P3 | Category | `/categories/{slug}` |
| P4 | Product detail | `/products/{slug}` |
| P5 | Cart | `/cart` |
| P6 | Checkout (includes the order terms review and acceptance step) | `/checkout` |
| P7 | Order placed (confirmation) | `/order-placed` |
| P8 | Track your order | `/track-order` |
| P9 | About | `/about` |
| P10 | Contact | `/contact` |
| P11 | Delivery and payment | `/delivery-and-payment` |
| P12 | Terms of sale | `/legal/terms-of-sale` |
| P13 | Privacy policy | `/legal/privacy` |
| P14 | Returns and refunds | `/legal/returns-and-refunds` |
| P15 | Unsubscribe | `/unsubscribe?token=` |
| P16 | Not found / error | any unknown path (HTTP 404), and the 503 page |
| P17 | Order terms (the document accepted at checkout) | `/legal/order-terms` |

Admin pages (staff only, not linked from the public site, `noindex`):

| # | Page | Path | Who |
|---|---|---|---|
| A1 | Sign in | `/admin/login` | public form |
| A2 | Forgot password (owner) | `/admin/forgot-password` | public form |
| A3 | Reset password | `/admin/reset-password?token=` | public form |
| A4 | Orders list (admin home) | `/admin` | staff |
| A5 | Order detail and processing | `/admin/orders/{id}` | staff |
| A6 | Products list (with stock) | `/admin/products` | staff |
| A7 | New product | `/admin/new-product` | staff |
| A8 | Edit product (with stock panel) | `/admin/products/{id}` | staff |
| A9 | Bulk product import | `/admin/product-import` | staff |
| A10 | Messages and subscribers | `/admin/inbox` | staff |
| A11 | My account (change password) | `/admin/account` | staff |
| A12 | Staff accounts | `/admin/staff` | owner only |
| A13 | Help (topic list and one page per topic) | `/admin/help`, `/admin/help/{topic}` | staff; topics filtered by role |

There is deliberately no "create order" page in the admin. Customers place all orders in the webshop.

## 2. Public pages: sections

### P1 Home `/`
SEO: intent "beauty and wellness products Kampala"; title "Zandy Touch | Skincare, Perfumes and Wellness in Uganda".
1. **Header** (SiteHeader).
2. **Hero.** Who Zandy Touch is. Headline "Beauty with Confidence", one calm sentence, "Shop now" (to /shop), secondary link "Order on WhatsApp" (hidden until Q1 is answered). Full-width black panel, gold rule, headline left on desktop, stacked on mobile, one image. No auto-rotating carousel.
3. **Category tiles.** The six categories from `GET /api/categories`; 2 columns on mobile, 3 on desktop; image or icon, name, product count.
4. **New arrivals.** Up to 8 (`sort=newest`); horizontal scroll on mobile, 4 columns on desktop; "View all".
5. **Zandy Touch own brand.** Up to 4 (`ownBrand=true`) plus a short paragraph on natural, organic and authentic products (wording per Q2). Hidden if empty.
6. **Featured products.** Up to 8. Hidden if empty.
7. **How ordering works.** Three steps: Choose, We confirm delivery with you, Pay on delivery (cash or mobile money). Three columns with icons.
8. **Newsletter signup** (NewsletterForm): "Be first to know about new arrivals".
9. **Footer.**

### P2 Shop `/shop`
SEO: intent "buy beauty products online Uganda"; title "Shop All Products | Zandy Touch". Filtered or searched views are `noindex`, canonical `/shop`.
1. **Heading and result count.** "All products" or `Results for "…"`.
2. **Filter and sort bar.** Category chips (six), availability toggle ("In stock only"), sort select (Newest, Price low to high, Price high to low, Name). Horizontal scroll chips on mobile, collapsible filter sheet.
3. **Product grid** (ProductCard): 2 columns mobile, 3 tablet, 4 desktop; "Load more" (works as plain links without JavaScript). Sold-out cards show the badge and sort last.
4. **Empty state.** "Nothing matched. Try another word or browse a category." with category links and a WhatsApp link to ask for a product.
5. Dynamic: `GET /api/products`.

### P3 Category `/categories/{slug}`
SEO: intent e.g. "skincare products Kampala", "perfumes Uganda", "weight gain porridge Uganda" (Health & Wellness); title "{Category} in Uganda | Zandy Touch".
1. **Breadcrumb.**
2. **Category header.** H1, one or two intro sentences from the API. Slim black band, gold rule.
3. **Filter and sort bar, grid, empty state** as P2, pre-filtered. Unknown slug: HTTP 404 page (P16).

### P4 Product detail `/products/{slug}`
SEO: intent = product name plus "Kampala" or "Uganda"; title "{Product name} | {Category} | Zandy Touch".
1. **Breadcrumb.**
2. **Gallery.** Large main image, up to 6 thumbnails; stacked and swipeable on mobile, thumbnails left on desktop. Alt text from the API. Badges (New, Zandy Touch, Sold out) at the corner.
3. **Buy box.** Name, brand, price in UGX, availability ("Available" or "Sold out"), quantity stepper (1 to `maxQuantity`; if `maxQuantity` is below 10 a hint "Up to N per order"), "Add to cart". Three reassurance lines with icons: "Delivery fee agreed with you before dispatch", "Pay on delivery: cash or mobile money", "Questions? Chat on WhatsApp". When sold out: disabled "Sold out" button, WhatsApp "Ask about this product" link, no stepper. Sticky Add to cart bar on mobile.
4. **Details.** Description, size, how to use, ingredients as an accordion; only fields present.
5. **Related products.** Up to 4 ProductCards.
6. Dynamic: `GET /api/products/{slug}`, re-checked on load. Add to cart is a client action. If stock dropped while the page was open, the stepper clamps and an inline notice (`aria-live`) explains.

### P5 Cart `/cart`
`noindex`. Title "Your Cart | Zandy Touch".
1. **Cart lines** (CartLine): image, name, unit price, quantity stepper, remove, line total. Problem lines show an inline text notice: price changed ("Now UGX …"), sold out ("Sold out, remove to continue"), or not enough stock ("Only N available" with a "Change to N" button).
2. **Summary.** Subtotal, "Delivery: agreed with you before dispatch", "You pay on delivery: cash or mobile money". "Checkout" (disabled while a problem exists), "Keep shopping".
3. **Empty state.**
4. Dynamic: cart in browser storage; `POST /api/cart-validations` on load and on every change. The cart does not reserve stock; a short line says "Items are not held until you place your order".

### P6 Checkout `/checkout`
`noindex`. Title "Checkout | Zandy Touch". One page, numbered sections, no account prompt.
1. **Your details.** Name, phone, email (optional, "only for an order receipt"), preferred contact (Call or WhatsApp).
2. **Delivery address.** Country (Uganda), city (select from `GET /api/delivery-areas`; no free-text city while `allowOtherCity` is false), area, address line, directions or landmark. Helper: "Delivery is available to major cities in Uganda. We will agree the delivery fee with you."
3. **Payment on delivery.** Radio: Cash or Mobile money. "You pay when your order arrives. Nothing is charged online."
4. **Order note** (optional).
5. **Order summary.** Lines, subtotal, "Delivery fee: to be agreed with you", no total including delivery. Link back to the cart.
6. **Review and accept the order terms (agreement step).** A binding, explicit acceptance at the moment the order is finalized, not a footer link. Content: `GET /api/policies/orderTerms`, shown as a short bullet summary plus a scrollable full text and a link to P17. It must cover: prices exclude delivery; the fee is agreed before dispatch and the customer may cancel before dispatch if they do not agree; payment on delivery; being reachable at the number given; the returns rules; how personal data is used (link to P13). Control: an unchecked checkbox "I have read and accept the order terms (version {version})", never pre-checked. "Place order" stays disabled until it is checked. On submit the version goes to `POST /api/orders` and the server records the acceptance (customer, timestamp, version, content hash) linked to the order. On 409 `agreementVersionOutdated` the section reloads the new text and asks again, keeping everything typed. A placeholder notice shows if the document is still a placeholder.
7. **Place order.** One primary button, disabled with progress while sending. Under it: "We will contact you to confirm your order."
8. States: inline field errors plus an error summary. 409 `cartOutdated` returns the customer to P5 with every affected line explained (price changed, sold out, or only N left), including the case where someone else bought the last unit a moment earlier; the form data is kept. Network failure shows "Try again", which reuses the same idempotency key, so a retry cannot create a second order or take stock twice.
9. Dynamic: `GET /api/delivery-areas`, `GET /api/policies/orderTerms`, `POST /api/cart-validations`, `POST /api/orders`.

### P7 Order placed `/order-placed`
`noindex`. Title "Order Received | Zandy Touch".
1. **Confirmation panel.** Check icon, "Thank you, {first name}. Your order {orderNumber} has been received." Order number large and copyable.
2. **What happens next.** We contact you on {phone} to agree the delivery fee; we send it out; you pay on delivery. Line: "If you do not agree the fee, you can cancel before dispatch."
3. **Order recap.** Items, subtotal, "Delivery fee: to be agreed".
4. **Actions.** "Message us on WhatsApp" (prefilled with the order number), "Track your order", "Continue shopping".
5. **Newsletter prompt** (small).
6. Data comes from the `POST /api/orders` response kept in session storage; the cart is cleared on success. With no data (tab reloaded after closing) show "Look up your order" linking to P8.

### P8 Track your order `/track-order`
`noindex`. Title "Track Your Order | Zandy Touch".
1. **Lookup form.** Order number and phone. A generic error for no match.
2. **Result.** StatusTimeline (Received, Confirmed, On its way, Delivered; Cancelled replaces the rest), times, items, subtotal, delivery fee and total once recorded (otherwise "To be agreed"), payment method, "Need help? WhatsApp us".
3. Dynamic: `POST /api/order-trackings`.

### P9 About `/about`
SEO: intent "about Zandy Touch Kampala beauty shop"; title "About Zandy Touch | Beauty with Confidence".
1. **Story and values** (confidence, trust, professionalism, friendly service). Two columns with an image.
2. **Our own brand** (natural, organic, authentic; per Q2).
3. **Visit the shop.** Address from `GET /api/site-info` (hidden until Q10), link to P10.

### P10 Contact `/contact`
SEO: intent "Zandy Touch Kampala contact phone WhatsApp"; title "Contact Zandy Touch | Kampala, Uganda".
1. **Contact options.** Phone `+256 706 105 779` (tap to call), WhatsApp (Q1), public email if approved (Q11), address and hours if known (Q10).
2. **Message form** (ContactForm): name, phone or email, message. Success replaces the form.
3. Dynamic: `POST /api/contact-messages`, `GET /api/site-info`.

### P11 Delivery and payment `/delivery-and-payment`
SEO: intent "delivery in Uganda pay on delivery mobile money"; title "Delivery and Payment | Zandy Touch".
1. **How delivery works.** Major cities in Uganda; the fee is agreed with you for each order and recorded before dispatch; usual timing (Q3); neighbouring countries "coming later".
2. **How to pay.** Cash or mobile money on delivery; nothing is paid online.
3. **Cities we deliver to** from `GET /api/delivery-areas`.
4. **Ordering by phone or WhatsApp.** "For an order, please use the webshop; we are happy to send you a link to any product." (Staff cannot enter orders for customers.)

### P12 to P14 and P17 Legal pages
All four render a policy document from `GET /api/policies/{type}` (title, version, effective date, body blocks, and the placeholder notice when applicable). Single column of text with headings. The text is a placeholder reviewed by a human before launch.
- P12 Terms of sale `/legal/terms-of-sale` (`termsOfSale`). SEO: intent "Zandy Touch terms of sale"; title "Terms of Sale | Zandy Touch".
- P13 Privacy policy `/legal/privacy` (`privacy`). SEO: intent "Zandy Touch privacy policy"; title "Privacy Policy | Zandy Touch". Covers what is collected (name, phone, address, optional email, newsletter contact), why, how long, who sees it, how to ask for removal; aligned with Uganda's Data Protection and Privacy Act 2019.
- P14 Returns and refunds `/legal/returns-and-refunds` (`returns`). SEO: intent "Zandy Touch returns policy"; title "Returns and Refunds | Zandy Touch".
- P17 Order terms `/legal/order-terms` (`orderTerms`). The same document shown in the checkout acceptance step. SEO: intent "Zandy Touch order terms"; title "Order Terms | Zandy Touch".

### P15 Unsubscribe `/unsubscribe?token=`
`noindex`. Button "Unsubscribe me", success message. Dynamic: `POST /api/unsubscribes`.

### P16 Not found / error
`noindex`. HTTP 404 for unknown paths, slugs and archived or draft products. A calm message, search box, category links and a WhatsApp link. A variant for HTTP 503 when the API is unreachable: "We are having trouble loading the shop. Please try again in a minute", with phone contact. Never cached.

## 3. Component inventory (reused)

Public:
- **SiteHeader / Nav**: logo (SVG recreated from the brand mark: Z monogram, wordmark, slogan; no phone number or category icons from the badge), category links (six, collapsing to a menu on mobile), search icon opening a field, cart icon with count. Black with a gold hairline.
- **AnnouncementBar** (optional): "Pay on delivery: cash or mobile money".
- **SearchField**, **Breadcrumb**.
- **ProductCard**: image, badges (New, Sold out, Zandy Touch), name, brand, price. Whole card links to the product. No quick-add in v1.
- **ProductGrid** with "Load more". **CategoryTile**. **Gallery**.
- **PriceLabel** (UGX formatting). **QuantityStepper** (max from `maxQuantity`, with limit hint). **StockNotice** (inline `aria-live` message for stock changes in cart and product page).
- **Badge**, **Button** (primary gold on black, secondary outline, disabled), **Link**.
- **CartLine**, **OrderSummary** (shared by cart, checkout, confirmation, tracking).
- **FormField**, **ErrorSummary**, **Select**, **RadioGroup**, **Checkbox**.
- **AgreementPanel**: scrollable terms text, version label, acceptance checkbox, error state (checkout).
- **PolicyDocument**: renders title, version, effective date, body blocks and the PlaceholderNotice. **PlaceholderNotice**.
- **StatusTimeline** (tracking, admin history).
- **NewsletterForm**, **ContactForm**.
- **WhatsAppButton** (prefilled link) and **CallLink**.
- **Toast / inline notice** (`aria-live`), **Accordion**, **EmptyState**.
- **SiteFooter**: logo, short blurb, links (Shop, About, Contact, Delivery and payment, Terms of sale, Order terms, Privacy, Returns), contact details, newsletter, social links (TikTok, Facebook, and Instagram once configured; Q12), copyright.

Admin:
- **AdminShell**: top bar with name and role, Sign out, navigation (Orders, Products, Import, Inbox, Staff for owner only, My account, Help). Every admin page (A4 to A12) also shows a small "How do I…?" link near its title that opens the matching A13 topic.
- **DataTable** (table on desktop, stacked cards on mobile), **StatusPill**, **FilterTabs with counts**, **SearchBar**.
- **ConfirmDialog**, **ProductForm**, **ProductImageManager** (the product's own pictures: upload, reorder, alt text, remove), **UploadDropzone**, **ImportResultList** (one line per CSV row and per photo: created, updated or rejected with the reason).
- **StockPanel** (current count, status badge, "Adjust stock" button, recent movements), **StockAdjustDialog** (modes: Add stock, Remove stock, Set to counted number; reason select; note), **StockMovementList** (type, change, quantity after, reason, order number link, who, when).
- **DeliveryFeeField** (UGX input with the running total beside it).
- **TemporaryPasswordNotice**, **ConflictBanner**.

## 4. Dynamic content summary

| Content | Page(s) | Endpoint(s) |
|---|---|---|
| Product list, search, filters | P1, P2, P3 | `GET /api/products`, `GET /api/categories` |
| Product detail | P4 | `GET /api/products/{slug}` |
| Cart validation (price, stock) | P5, P6 | `POST /api/cart-validations` |
| Cities | P6, P11 | `GET /api/delivery-areas` |
| Policy documents / order terms | P6, P12, P13, P14, P17 | `GET /api/policies/{type}` |
| Order placement, stock take, acceptance record | P6, P7 | `POST /api/orders` |
| Order tracking | P8 | `POST /api/order-trackings` |
| Newsletter | footer, P1, P7 | `POST /api/subscriptions`, `POST /api/unsubscribes` |
| Contact form | P10 | `POST /api/contact-messages` |
| Business details | header, footer, P10 | `GET /api/site-info` |
| Sitemap | `sitemap.xml` | `GET /api/sitemap-entries` |
| Staff sign in, reset, password | A1 to A3, A11 | `/api/auth/*`, `PUT /api/admin/account/password` |
| Orders, delivery fee, status | A4, A5 | `/api/admin/orders*` |
| Products, stock, product pictures, bulk import | A6 to A9 | `/api/admin/products*`, `/api/admin/products/{id}/stock-movements`, `/api/admin/products/{id}/images`, `/api/admin/product-imports`, `/api/admin/product-images`, `/api/admin/product-publications`, `/api/admin/product-import-template` |
| Messages, subscribers | A10 | `/api/admin/contact-messages`, `/api/admin/subscribers` |
| Staff accounts | A12 | `/api/admin/staff*` |

The cart itself is client-side only (browser storage).

## 5. Admin pages: sections

All admin pages need a signed-in session (the server enforces it; the UI also redirects to A1). Role checks are made on the server. A page a role cannot use is not shown in the menu.

### A1 Sign in
Email, password, "Forgot password?" (A2), error "Email or password is incorrect". Centred card on black.

### A2 and A3 Forgot and reset password
A2: email field; same confirmation whatever is entered. A3: new password with a strength hint, then to A1.

### A4 Orders list `/admin`
1. Status tabs with counts (New, Confirmed, Out for delivery, Delivered, Cancelled).
2. Search (order number, name, phone).
3. Table: order number, time placed, customer, city, items, subtotal, fee ("Not set" highlighted for open orders), payment preference, status pill. New orders emphasised. Row opens A5.
4. Pagination.

### A5 Order detail `/admin/orders/{id}`
1. **Header:** order number, status pill, placed time.
2. **Customer and delivery:** name, tap-to-call, WhatsApp link, email, address, directions, note.
3. **Items:** price snapshots, quantities, line totals, subtotal. Read-only (lines cannot be edited).
4. **Delivery fee and totals:** DeliveryFeeField where staff record the fee agreed with the customer, total beside it, internal staff note, Save. Label: "Agreed delivery fee (UGX). Required before you can confirm or dispatch."
5. **Actions:** by status: Confirm order, Approve delivery (dispatch), Mark delivered (asks for cash or mobile money), Cancel order (asks for a reason). Unavailable actions are disabled with the reason ("Record the delivery fee first"). ConfirmDialog repeats the total. The cancel dialog states the stock effect: before dispatch, "Stock for these items will be returned"; after dispatch, "Stock will not be returned automatically. Adjust stock if the goods come back." After cancelling, a notice reports `stockRestored`.
6. **Agreement record:** "Order terms version {v} accepted by {name}, {phone}, on {time}" with the content hash.
7. **History timeline:** status changes, who and when, fee entries.
8. `versionConflict` handled with ConflictBanner.
There is no "edit items" and no "create order" action.

### A6 Products list `/admin/products`
Search, filters (category, status), stock tabs ("Out of stock (n)", "Low stock (n)"), table: thumbnail, name, SKU, category, price (unpriced drafts flagged "No price"), stock count with status badge (Sold out, Low stock), status, updated by and when. Row actions: Edit, Adjust stock (opens StockAdjustDialog), Remove (confirmation says it archives). "New product" button. Toggle shows archived products with Restore.

### A7 and A8 New and edit product
ProductForm sections:
- Basics: name, brand, own-brand toggle, category (the six), SKU.
- Price: price in UGX. Required to publish; a draft may be saved without it. Hint: "A product needs a price before it can be published."
- Stock: on A7 an "Opening stock" number field (default 0). On A8 the StockPanel replaces it: the current count is read-only and changes only through Adjust stock, so saving the form can never overwrite stock changed by an order. Hint: "Orders reduce this number. At 0 the product shows as sold out."
- Descriptions: short, full, size, how to use, ingredients.
- Images: the ProductImageManager. Upload pictures straight onto this product (up to 6), reorder them, set alt text, remove one. Pictures belong to the product; there is no shared library to pick from. On A7 the images section appears after the first Save (the product needs to exist first).
- Publishing: draft or published, featured toggle. Publish is blocked with inline messages when price, image or category is missing.
Save, Save and view on shop. A8 also has Remove and a "last updated by" line.

### A9 Bulk product import `/admin/product-import`
Purpose: let the owner fill or update the catalogue in one go with product details and their pictures. Pictures are not managed separately; they always end up on a product.
1. Step 1, the spreadsheet. A short explanation, a "Download the template" link (CSV with the column headings and two example rows), and a file field for the filled CSV (saved as "CSV UTF-8" from Excel or Google Sheets). Columns: `sku` (required, the key), `name`, `brand`, `ownBrand` (yes/no), `category` (one of the six, by name), `price` (UGX, optional for a draft), `shortDescription`, `description`, `size`, `howToUse`, `ingredients`, `featured` (yes/no), `openingStock` (new products only). On upload the server checks every row and creates new products as drafts or updates existing ones (matched by SKU). ImportResultList shows each row: created, updated or rejected, with the reason per field ("price must be a whole number of shillings").
2. Step 2, the pictures. An UploadDropzone for the product photos, any number at once (the page sends them to the server 20 at a time, with progress). Each file name must start with the SKU, with an optional `-1` to `-6` suffix for the order (`ZT-SKIN-001-1.jpg`, `ZT-SKIN-001-2.jpg`). Each picture is attached to that product; a file whose SKU matches no product is rejected and not kept. Results per file in the ImportResultList.
3. Step 3, review and publish. A list of the imported drafts with what each still lacks (price, picture, category). "Publish all complete drafts" publishes every draft that has a price, a picture and a category; the others stay drafts with a link to fix them on A8.
Updates to existing products keep their current status (a published product stays published). An import never changes the stock of an existing product.

### A10 Inbox and subscribers `/admin/inbox`
Two tabs. Messages: newest first. Subscribers: list with source and date, "Export CSV".

### A11 My account `/admin/account`
Name and role; change password. With a temporary password this is the only page available.

### A12 Staff accounts `/admin/staff` (owner only)
List (name, email, role, active, last sign-in). Add support employee (name, email, notify on new orders), then TemporaryPasswordNotice. Row actions: Reset password, Deactivate (ConfirmDialog). The owner's row has no deactivate.

### A13 Help `/admin/help` and `/admin/help/{topic}`
Purpose: let the owner and support staff do every admin task without outside help. The content comes from admin-help.md (written by ux-designer, see that file for its format); the frontend build turns it into pages, so no text lives in the backend or database.
1. Topic list: the topics for the signed-in role, grouped (Getting started, Orders, Products and stock, Bulk import, Messages, Staff and account), each with a one-line summary.
2. Topic page: title, who it is for, short intro, numbered steps with the exact page and button names, "Good to know" notes, and links to related topics and to the admin page it describes.
3. Role filter: support staff never see owner-only topics (listing or direct link; a direct link to an owner-only topic shows "This topic is for the shop owner" and a link back to Help).
4. "How do I…?" link on A4 to A12 opens the topic mapped to that page in admin-help.md.
No screenshots in v1 (added later from QA screenshots). Printable: the topic page prints cleanly without the admin chrome.

## 6. Icons and badges needed

Icons (one consistent line-style set, usable in gold or ivory): cart, search, menu, close, account/staff (admin only), phone/call, WhatsApp, email, location pin/shop, delivery (truck or scooter), pay on delivery (cash), mobile money (phone with coin), trust (shield, used for "we confirm every order", not for card payment), returns, check/success, warning, info, chevrons (up, down, left, right), plus and minus, trash/remove, edit, upload, image, copy, filter, sort, external link, stock/box (admin stock panel and inventory), history/clock (stock and order history), social icons (Instagram, Facebook, TikTok, only those the owner confirms, Q12), category icons for the six categories (skincare, perfumes, body-shaping, body care, weight loss, health and wellness), and a star (featured, sparingly). No bed/Bedroom icon.

Badges: **New**, **Sold out**, **Zandy Touch** (own brand), **Featured** (admin only), **Low stock** (admin only), **Draft notice** for placeholder policies ("Draft text, not yet reviewed"). Admin status pills: Draft, Published, Archived, and order statuses Placed, Confirmed, Out for delivery, Delivered, Cancelled. Not needed: Sale (Q9), wishlist, customer account, compare.

## 7. SEO summary (for frontend)

Business type: **Store**. Per-page intent and title are given with each page above. Product pages: Product and Offer JSON-LD with price in UGX and availability from stock. `noindex`: P5, P6, P7, P8, P15, P16, all admin pages, and filtered or paginated variants of P2 and P3. Canonical URLs are absolute, built from the configured base URL.

## 8. Edge cases design must cover (stock and orders)

- Last unit contention: two customers place orders for the last unit; one succeeds, the other gets a clear cart message "Sold out" and keeps what they typed.
- Quantity above remaining stock: cart line says "Only N available" with a one-click fix.
- Retry after a dropped connection: same idempotency key, one order, one stock decrement.
- Cancelled before dispatch: stock returns, the product can show Available again within about a minute (cache lifetime).
- Staff stocktake while orders arrive: "Set to counted number" sends an expected value and shows a conflict message if stock changed meanwhile.
- Product published with zero stock: shows Sold out immediately.
- Product archived with an open order: the order is unaffected; cancelling it still restores stock to the archived product.

## 9. Seed data needed (backend responsibility)

The backend seeds the database by versioned migration or seed script so the shop is browsable before the owner's real catalogue exists. No secrets in seed data; the owner account is created from environment variables, not seeded.

**Categories (6):** Skincare `skincare`, Perfumes `perfumes`, Body-shaping `body-shaping`, Body care `body-care`, Weight loss `weight-loss`, Health & Wellness `health-and-wellness`. Each has a one or two sentence description and a sort order 1 to 6.

**Delivery cities (owner decision):** Kampala, Entebbe, Jinja, Mbale, Mbarara, Fort Portal.

**Policies (4, placeholder, `reviewStatus: placeholder`):** `orderTerms`, `termsOfSale`, `privacy`, `returns`, each version `2026-10-01` with a summary where needed and a clearly marked draft body.

**Sample products.** Names are generic (no real third-party brand names). Own brand = Zandy Touch; others use brand "Sample Brand". All SKUs start with `SEED-`. Copy is neutral: describe what the product is and how it is used, with no medical, weight-loss or "guaranteed" claims (Q2). Prices in UGX. Stock includes at least one sold-out and one low-stock product so the states can be seen.

| SKU | Product | Category | Own brand | Price | Stock |
|---|---|---|---|---|---|
| SEED-SK-01 | Radiance Vitamin C Serum 30 ml | skincare | yes | 85,000 | 24 |
| SEED-SK-02 | Gentle Foaming Face Cleanser 150 ml | skincare | no | 38,000 | 40 |
| SEED-SK-03 | Daily Moisturising Cream 50 ml | skincare | yes | 55,000 | 3 (low) |
| SEED-SK-04 | Mineral Sunscreen SPF 50, 60 ml | skincare | no | 62,000 | 0 (sold out) |
| SEED-PF-01 | Zandy Touch Signature Eau de Parfum 50 ml | perfumes | yes | 150,000 | 12 |
| SEED-PF-02 | Oud Evening Eau de Parfum 100 ml | perfumes | no | 220,000 | 6 |
| SEED-PF-03 | Fresh Citrus Body Mist 100 ml | perfumes | no | 45,000 | 30 |
| SEED-BS-01 | Waist Shaping Belt | body-shaping | no | 65,000 | 15 |
| SEED-BS-02 | Firming Body Contour Cream 200 ml | body-shaping | yes | 70,000 | 18 |
| SEED-BS-03 | Seamless Shapewear Bodysuit | body-shaping | no | 95,000 | 8 |
| SEED-BC-01 | Shea Butter Body Lotion 400 ml | body-care | yes | 42,000 | 35 |
| SEED-BC-02 | Coffee Body Scrub 250 g | body-care | yes | 35,000 | 20 |
| SEED-BC-03 | Nourishing Body Oil 100 ml | body-care | no | 48,000 | 14 |
| SEED-BC-04 | Moisturising Hand Cream 75 ml | body-care | no | 18,000 | 50 |
| SEED-WL-01 | Herbal Slimming Tea, 30 bags | weight-loss | no | 30,000 | 25 |
| SEED-WL-02 | Fibre Drink Mix, 14 sachets | weight-loss | no | 45,000 | 12 |
| SEED-WL-03 | Kitchen Scale and Body Tape Set | weight-loss | no | 28,000 | 22 |
| SEED-HW-01 | Zandy Touch Weight-Gain Porridge 500 g | health-and-wellness | yes | 40,000 | 30 |
| SEED-HW-02 | Vitamin D3 Dietary Supplement, 60 capsules | health-and-wellness | no | 52,000 | 22 |
| SEED-HW-03 | Daily Multivitamin, 90 tablets | health-and-wellness | no | 68,000 | 16 |
| SEED-HW-04 | Omega-3 Fish Oil Dietary Supplement, 60 softgels | health-and-wellness | no | 75,000 | 9 |
| SEED-HW-05 | Moringa Leaf Powder 200 g | health-and-wellness | yes | 25,000 | 28 |
| SEED-DRAFT-01 | Unpriced sample draft (not published) | health-and-wellness | no | none | 0 |

Rules for the seed:
- 22 published products (every category has at least three) plus one draft with no price and no image, so the publishing rule can be tested in the admin.
- Each published product has at least one image; SEED-SK-01 and SEED-PF-01 have two, to exercise the gallery.
- Images are public domain or CC0 only (for example from Wikimedia Commons or Openverse), matched to the product's category, with alt text. The business/images/products files are mood references only and are not used as product images.
- Every seed image is recorded in a manifest (for example `seed/images/SOURCES.md`) with: file name, product SKU, source URL, author, licence, date retrieved.
- Published seed products are `isNew` at first. Opening stock is written as `initial` stock movements.
- Seed products must be archived or replaced by the owner before launch (launch gate in scope-checklist.md); the `SEED-` prefix makes them easy to filter in the admin by SKU search.

## 10. Open questions for the owner

All answered by the owner on 25 October 2026; the answers are in business-context.md, "Owner decisions". In short:
- Q1 WhatsApp: same number as the phone. Show the WhatsApp buttons.
- Q2 Claims: none. Copy makes no brightening, weight or "natural and organic" claims.
- Q3 Delivery cities: Kampala, Entebbe, Jinja, Mbale, Mbarara, Fort Portal. Delivery time and fee are agreed when the order is confirmed. Other cities: not offered in the checkout; the delivery page invites a WhatsApp enquiry.
- Q4 Policies: placeholders until the owner reviews and approves each text; the launch gate stays.
- Q5 Stock: one shared pool with the physical shop; walk-in sales are recorded as stock adjustments (reason "shopSale"). Staff enter opening counts.
- Q6 Cancel after dispatch: stock unchanged; staff adjust by hand. As designed.
- Q7 Changing an order: cancel and reorder. As designed.
- Q8 Customer email: optional, receipt only. As designed.
- Q9 Sale prices: not in v1.
- Q10 Shop: Ring roads, Rubaga Rd, opposite Red Cross, Kampala. Monday to Saturday 8:00 AM – 10:00 PM, Sunday 10:00 AM – 9:30 PM.
- Q11 Public email: the owner email may be shown publicly.
- Q12 Social: TikTok (https://www.tiktok.com/@zandytouch) and Facebook (https://www.facebook.com/nanyonjo.sandra.1/) are linked in the footer. Instagram is an empty slot, hidden until an address is configured.
- Q13 Brands: Zandy Luxe is Zandy Touch, not a separate brand. Third-party brands are sold alongside own-brand products.
- Q14 Gmail sending: accepted.
- Q15 Thresholds: low stock at 5 or fewer; at most 10 per product line.
- Data retention (new): customer contact details on orders and contact messages are removed 6 months after the order is closed or the message received.
