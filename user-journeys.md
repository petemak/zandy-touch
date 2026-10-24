# User Journeys: Zandy Touch

Note on references: the live reference sites were not fetched (the brand brief says it could not inspect them). The structure follows the flows the brief names: Jumia (pay-on-delivery clarity), Etsy (plain cart), Glovo (delivery messaging), Walmart (low-friction ordering). Verify against the live sites if needed.

Page paths refer to design-spec.md and endpoints to api-contract.md. Rules that shape every journey: customers are guests; all orders are placed by customers in the webshop (staff never enter orders); stock is counted and checked only when the order is placed; the delivery fee is agreed with the customer and recorded by staff, never computed by the site.

---

## Journey 1: Guest customer browses, orders, accepts terms, tracks the order

Actor: a guest on a phone, arriving from an Instagram or WhatsApp link to a product.

1. Entry. The customer taps a shared link to /products/{slug} (or lands on /). The page is server-rendered, so it appears fast and may be up to about a minute old from a cache. They see a large photo, name, UGX price, "Available", "Delivery fee agreed with you before dispatch", "Pay cash or mobile money on delivery", and Add to cart. The header shows a cart icon with a count. In the background the page re-checks the product, so if it has just sold out the button changes to the Sold out state. (GET /api/products/{slug})
2. Browse. They tap a category in the nav ("Health & Wellness" for the weight-gain porridge or supplements, "Skincare", and so on) and land on /categories/{slug}, a grid of product cards with sold-out items last and badged. They can sort, switch on "In stock only", or search from the header, which goes to /shop?q=... (GET /api/products)
3. Choose. They open a product, read the description and tap Add to cart, after choosing a quantity (the stepper stops at the most they can order; below 10 it says "Up to N per order"). A confirmation appears in place with "View cart" and "Keep shopping". A sold-out product has a disabled "Sold out" button and a WhatsApp "Ask about this product" link instead.
4. Review the cart. They tap the cart icon and land on /cart: lines with image, name, unit price, quantity stepper, remove, and a subtotal. Below it: "Delivery fee: agreed with you before dispatch. You pay on delivery." and "Items are not held until you place your order." The page refreshes prices and stock from the server. Changed prices, sold-out items, and quantities above stock are marked on the affected line ("Only 2 available", with a "Change to 2" button) and Checkout is blocked until they fix them. (POST /api/cart-validations)
5. Checkout details. On /checkout, one short form with no login prompt: name and phone (required); email (optional, "for an order receipt only"); preferred contact (call or WhatsApp); city (list plus "Other city, we will confirm"), area, address line, directions; payment on delivery: cash or mobile money; optional note. The order summary shows delivery as "To be agreed". The form validates inline; the first error is announced and focused. (GET /api/delivery-areas)
6. Review and accept the order terms. A distinct section "Review and accept" near the bottom shows the current order terms: a short bullet summary, a scrollable full text, and a link to the full page. There is an unchecked box: "I have read and accept the order terms (version 2026-10-01)". Place order is disabled until it is checked. The server validates the version again. (GET /api/policies/orderTerms, loaded with the page)
7. Place order. They tap Place order; the button shows progress and disables. One request goes out with the Idempotency-Key created when the page loaded. (POST /api/orders) The server, in one atomic step, checks price and stock, reduces stock, creates the order and records the acceptance (name, phone, time, version, hash).
   - Network failure: they retry safely. The same key returns the same order, with no duplicate and no second stock reduction.
   - Price changed, item sold out, or someone bought the last unit just before them (409 cartOutdated): nothing is ordered and no stock is taken. They return to /cart with every affected line explained and their details kept.
   - Terms version changed (409): the page reloads the new terms and asks for acceptance again, keeping everything they typed.
8. Confirmation. They land on /order-placed: order number (ZT-7K3P9Q), items and subtotal, "We will call or WhatsApp you on 07xx... to agree the delivery fee and arrange delivery", "If you do not agree the fee, you can cancel before dispatch", three next steps, a WhatsApp button prefilled with the order number, and a Track link. The cart is cleared. The shop gets an email alert at the owner address (and from there staff with alerts on); the customer gets a receipt only if they gave an email.
9. Wait. The shop contacts them, agrees the fee and dispatches (Journey 2). If the customer does not agree the fee, they tell the shop and the order is cancelled (Journey 2, step 9).
10. Track. Later they open /track-order, enter order number and phone, and see Received, Confirmed (with the agreed fee and total), On its way, Delivered, or Cancelled, with times. The total including the fee appears once staff have recorded it. (POST /api/order-trackings)
11. End. The courier delivers, the customer pays cash or mobile money, staff mark it delivered. On /order-placed there is a small "Get new arrivals" signup, the route into the newsletter. (POST /api/subscriptions)

Failure and side paths to design:
- Empty cart on /checkout: redirect to /cart with a message.
- Unserved city: choose "Other city".
- Wrong order number or phone on tracking: one generic message.
- A customer who phones or WhatsApps to order: staff cannot enter the order for them. They answer questions and send the product link; the customer places the order in the webshop (P11 says so).
- API down: pages show the 503 page with the phone number; no order is lost because nothing is sent until the API accepts it.

---

## Journey 2: Staff member processes an order from placement to delivery or cancellation

Actor: a support employee (the owner does the same).

1. Alert. A "New order ZT-7K3P9Q" email arrives from the shop's address with a link to /admin/orders/{id}.
2. Sign in. If not signed in, they reach /admin/login, enter email and password and land on /admin. (POST /api/auth/sessions)
3. Orders list. /admin shows tabs with counts: New (placed), Confirmed, Out for delivery, Delivered, Cancelled. New orders first. Search by order number, name or phone. The Fee column shows "Not set" on open orders. (GET /api/admin/orders)
4. Open the order. They see customer name, tap-to-call and WhatsApp links, address, items with price snapshots (read-only), subtotal, payment preference, note, the accepted-terms record (version and time) and the history. Stock for the items was already reduced when the customer placed the order.
5. Agree the fee. They call or WhatsApp the customer and agree the delivery fee. If the customer wants different quantities there is no editing: they cancel the order and the customer reorders (Q7).
6. Record the fee and confirm. They enter the agreed fee in UGX (0 means free) and an optional internal note, Save, then press Confirm order. Confirm and Approve delivery are disabled with "Record the delivery fee first" until the fee is saved. A dialog repeats the total (subtotal plus fee). The customer's tracking page now shows the fee and total. (PATCH /api/admin/orders/{id}, then POST /api/admin/orders/{id}/status-changes)
7. Approve delivery (dispatch). When ready they press Approve delivery. This works only with a recorded fee. The status becomes Out for delivery and tracking shows "On its way". (POST status-changes)
8. Delivered. After handover they press Mark delivered and choose how the customer paid (cash or mobile money). The order closes. (POST status-changes)
9. Alternative: cancel. At any open stage they press Cancel order and give a reason (for example "customer did not agree the fee"). The dialog says what happens to stock:
   - Before dispatch (New or Confirmed): "Stock for these items will be returned." The status becomes Cancelled, stock is restored in the same step, and the confirmation says so. The product can show Available again on the storefront within about a minute.
   - After dispatch (Out for delivery): the order can still be cancelled (for example the customer refuses on arrival), but stock is not restored automatically. The dialog says "Adjust stock if the goods come back", and staff do that from the product (Journey 3, step 8).
   The reason is stored in the history and is not shown to the customer. (POST status-changes)
10. Conflicts. If a colleague changed the order meanwhile, the server returns 409 and the page reloads the latest order with a message ("This order was updated by Grace a moment ago").
11. End. They return to the list. Delivered and cancelled orders stay searchable.

---

## Journey 3: Staff adds a product, sets stock, publishes, and keeps stock correct

Actor: support employee or owner.

1. Sign in and open /admin/products: list with search, category and status filters, a stock column, and tabs "Out of stock (n)" and "Low stock (n)". The first launch shows the seeded sample products (SKU starts with SEED-).
2. Photos first (optional). On /admin/photos they drag in up to 20 photos (5 MB each). Each shows progress. The server stores them and tries to match each file name to an existing product SKU. They confirm the matches or pick products from a dropdown and press Attach. Photos for products not yet created stay in the library. (POST /api/admin/photos, POST /api/admin/photo-assignments)
3. Create the product. They press New product (/admin/new-product) and fill name, brand, own-brand toggle, category (one of the six; the porridge and supplements go in Health & Wellness), SKU, price in UGX, descriptions, optional size, how to use and ingredients, images from the library in display order, and Opening stock (for example 30). (POST /api/admin/products)
4. Price comes before publishing. If they try to Publish without a price, the form shows "A product needs a price before it can be published" (the server enforces this too, with the same rule for a missing image). They can Save as Draft without a price and come back. A draft is invisible to customers.
5. Publish and verify. With price, image, category and stock set, they Publish and press "View on shop", which opens /products/{slug}. It shows with its price, photos and "Available". It also appears on its category page, in New arrivals and in search. Because the storefront is cached briefly, a change may take up to about a minute and a half to appear for a visitor; the staff screens are always live.
6. Update. Later they edit price or description on /admin/products/{id}. The change shows on the storefront within about a minute and a half. Open carts pick up the new price at their next validation, and checkout rejects stale prices with an explanation. (PATCH /api/admin/products/{id})
7. Stock goes down by itself. Each customer order reduces the product's stock; at 0 the product shows Sold out on the storefront with a disabled button and a WhatsApp link. In the admin the product moves to "Out of stock". The staff screen never lets the product form change stock, so saving a description cannot overwrite a count that an order just changed.
8. Stock goes up or is corrected by staff. From the product page or the list, they press Adjust stock and choose:
   - Add stock (a delivery from the supplier, "restock"),
   - Remove stock (a walk-in sale in the physical shop, damaged or lost goods, or goods returned to them to be put back),
   - Set to counted number (stocktake; the screen sends the number it showed, so if an order changed the stock meanwhile it asks them to look again instead of overwriting it).
   They pick a reason, add an optional note and confirm. The stock cannot go below 0. The entry appears in the movement history with who and when, together with the system entries from customer orders and cancellations. (POST /api/admin/products/{id}/stock-movements, GET the same path)
9. Remove. They Remove a product: it is archived, disappears from the storefront, search and sitemap, and old orders and stock history stay intact. Restore is publishing it again. (DELETE /api/admin/products/{id})
10. End. The list shows updated status, stock and "last updated by". Before launch the owner archives or replaces the seeded sample products.

---

## Journey 4: Owner adds a support employee, who signs in for the first time

Actor: owner, then the new employee.

1. The owner signs in and opens /admin/staff (owner only; support staff get no menu entry and the server answers 403).
2. They press Add support employee, enter name and email and choose "notify me of new orders" or not. (POST /api/admin/staff)
3. The server creates the account with a one-time temporary password shown once. The owner passes it to the employee in person or by WhatsApp. Nothing is emailed.
4. The employee goes to /admin/login and signs in with it. The session is flagged `mustChangePassword`, the app sends them straight to /admin/account, and every other admin endpoint answers 403 `passwordChangeRequired` until they set their own password. (PUT /api/admin/account/password)
5. They land on /admin and can work on orders, products, stock and photos. The Staff menu is absent. With "notify" on, they receive the new-order alert email.
6. Later the owner deactivates an employee who left, from /admin/staff. Their sessions end immediately and their name stays on past history. (DELETE /api/admin/staff/{id})
7. Forgotten passwords: support staff ask the owner for a reset (POST /api/admin/staff/{id}/password-resets issues a new temporary password). The owner uses "Forgot password" on /admin/login, which emails a reset link to the owner address. (POST /api/auth/password-resets, then PUT /api/auth/password-resets/{token})
