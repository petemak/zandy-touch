# QA report: Zandy Touch

Commit 1a3b47d plus the architect review edits (api-contract.md hash 589983eb…). Run on 25–26 October 2026 against a live dev backend (in-memory Datomic, seeded), the frontend server and the dev proxy, on ports 28080–28090. All QA servers were stopped afterwards. No code was edited. Saved by the lead from the QA agent's report; the content is QA's.

**Owner sign-off for staging: given in chat on 26 October 2026 ("QA has signed off"), after the re-check below.** Earlier lead's note, kept for history: not yet signed off for staging. QA found every contract endpoint, scope category, journey, non-negotiable rule and local fitness check passing. The lead recommends fixing open items 1, 2 and 4 below before staging; items 3, 5, 6 and 7 are owner decisions or low priority. The owner decides on sign-off.

## Open items, in order of importance

1. **Category strip: FAIL on two of three accessibility conditions** (architect condition, DA6). Owner: ux-designer or visual-designer, `styles/30-pages.css` (`.site-nav__list`), or a frontend-cljs focus handler.
   - Keyboard reachability: pass (all 7 links reachable with Tab).
   - Focus does not scroll the focused link fully into view: at 390 px "Body-shaping" and "Health & Wellness" stay clipped while focused; at 320 px "Body-shaping" is clipped.
   - No visible cue that more items exist (scrollbar hidden, no fade or arrow; strip is 803 px wide against 320–390 px visible).
   - No sideways page scroll at 320 px, 390 px or 200% zoom: pass.
   - Architect's fallback: wrap the list onto two lines.
2. **A price change in the cart is never explained** (Journey 1 steps 4 and 7). Owner: frontend-cljs.
   - After a product's price changes, the cart line silently shows the new price with no "Price changed" notice, also after a 409 price-change rejection.
   - Cause: `apply-validation` in `src/shared/zandy/shared/cart.cljc` overwrites the stored `:price`; `price-changed-notice` in `src/shared/zandy/shared/ui/order.cljc` compares stored `:price` with `:unitPrice`, so it can never fire.
3. **Empty cart at checkout** (Journey 1 side path). `/checkout` with an empty cart stays on `/checkout` with "Your cart is empty…" instead of redirecting to `/cart`. Owner: frontend-cljs, or ux-designer to change the journey.
4. **Help text vs screens (check 8): three mismatches.** Owner: ux-designer (`admin-help.md`), or frontend-cljs if the screens should change.
   - `products`, Removing a product, step 2: the product page's **Remove from shop** box says only "This archives the product. It leaves the shop, search and sitemap."; only the list's box mentions bringing it back with Restore.
   - `bulk-import`, Step 3 item 6: the heading "These stay drafts:" only appears when the server rejects something; normally not-ready drafts just stay in the table with their "Still needs" entries.
   - `stock`: "The count already matches." appears after pressing **Confirm**, not while typing.
5. **B25 (proposed check): partial.** One h1, no duplicate ids, scroll position kept. When the JS arrives late, focus drops to BODY and a half-typed footer email is erased; this is the accepted cost in DA7.
6. **Dev ergonomics.** The dev log mailer prints only recipient count and subject, so the reset link is not visible and the "Forgot password" help steps 4–8 cannot be walked in dev. Single use and 60-minute expiry are covered by `accounts_test.clj`. Owner: backend-clojure, `src/backend/zandy/platform/mailer.clj`.
7. **Low priority.**
   - The adjust-stock box keeps "Stock cannot go below zero." after switching to "Set to counted number" (frontend-cljs).
   - Replicant dev warning "Avoid space separated :class strings": `src/shared/zandy/shared/pages/catalog.cljc` line 28 passes `"site-photo--natural"` through `ui/site-photo` (frontend-cljs).
   - The admin conflict message is generic; Journey 2 step 10 names the colleague. `admin-help.md` matches the screen.
   - `PATCH /api/admin/staff/{id}` has no UI caller (no rename or alert toggle after creation). Not required by the journeys.
   - Rate limits count every request, including rejected ones and idempotent replays (orders: 10 per hour per IP).

## Mechanical suites

| Command | Result |
|---|---|
| `clojure -M:test` | pass: 69 tests, 920 assertions (retention test skipped: needs a transactor) |
| `cd ssr && clojure -M:test` | pass: 53 tests, 353 assertions |
| `npx shadow-cljs compile test && node target/test/node-tests.js` | pass: 71 tests, 515 assertions |
| `bb scripts/check/backend_deps.clj slices`, `graph`, `attrs` | pass |
| `bb scripts/check/frontend_deps.clj all` | pass |
| `bb scripts/check/seed_manifest.clj` | pass: 24 images, 23 products |
| `bb scripts/build/help.clj --check` | pass |
| `clj-kondo --lint src/backend test/backend dev` | 0 errors, 0 warnings |
| `cd ssr && clj-kondo --lint src ../src/shared ../src/frontend test ../test/frontend` | 0 errors, 0 warnings |
| `SKIP_NPM=1 scripts/build/build.sh` | pass; asset tree clean |

## 1. Contract endpoints

Checked live per endpoint: paths, methods, request and response key sets, status codes, error shape, caching headers, Idempotency-Key rules, 401/403/CSRF.

All PASS: site-info, delivery-areas (six cities, `allowOtherCity` false), categories, policies/{type}, sitemap-entries, products, products/{slug}, cart-validations, orders (replay, key reuse 409, missing key 400, stale agreement 409, city 422, cartOutdated 409, honeypot, 5 concurrent same-key requests gave one order), order-trackings, subscriptions, unsubscribes, contact-messages, auth sessions (cookie HttpOnly, SameSite=Lax, Path=/api; lockout 429), password-resets (bad token 404; success path covered by unit test only), account/password, admin orders (list, detail, PATCH, status-changes incl. fee and payment rules, stock restore exactly once before dispatch, none after), admin products (list, detail, create, PATCH incl. images and archived save, DELETE), products/{id}/images, product-images (SKU matching, slots, rejects), product-imports (dry run, key separation, replay, every whole-file code, 413), product-import-template, product-publications, stock-movements (all six reasons, no negative stock, expectedQuantity conflict, concurrent same-key applied once), contact-messages, subscribers (JSON and CSV with formula guard, unsubscribed excluded), staff endpoints (support 403 everywhere, owner row protected, reset ends sessions), healthz, media.

Cross-cutting PASS: 401 without cookie on every staff route; 403 `csrfInvalid` without token on every staff mutation; `passwordChangeRequired` gate; errors `no-store`; `X-Request-Id` echoed; camelCase keys.

Frontend calls: every contract endpoint is called with matching path and method, except `PATCH /api/admin/staff/{id}` (no UI caller).

## 2. Scope coverage

All "applies" categories PASS end to end: catalog and counted stock (last-unit race: 1 success, 1 soldOut; 20 orders on stock 5: exactly 5), checkout (reduced form), staff accounts and auth, admin / back-office (reset-by-email success not walkable in dev, item 6), staff help (with item 4 mismatches), search, notifications (no mail for honeypot orders), legal (draft notices; launch gate refuses `SITE_INDEXABLE=true` while policies are placeholders), customer agreements (acceptance record with name, phone, time, version, hash).

## 3. Click test (Playwright, 390 and 1280 px)

PASS on every public page and all 13 admin pages. Product cards (whole-card link) open the right product from image, name, price and badges. No overlay problems.

## 4. Journeys

- Journey 1: PASS steps 1–11; deviations in items 2 and 3.
- Journey 2: PASS (email link step not testable in dev; conflict message generic, item 7).
- Journey 3: PASS, including archive, Restore and restore-rejected.
- Journey 4: PASS.

## 5. Standards and fitness checks

Non-negotiables PASS: server-side validation and authorization, no hardcoded secrets, versioned migrations (only the migration index file grows), idempotent mutations, no micro-frontends.

architecture.md section 7: N1–N6 PASS; B1–B16 PASS (B14 all 9 pages; B15 staging robots and launch gate; B16 assets); B17 SKIPPED (no libvips); B18 PASS (148,446 bytes gzipped of 250,000); B19, B23, B24 PASS; R3, R4, R9 PASS. R2, R5 and all section 8 deployment checks SKIPPED: they belong to deployment-engineer.

## 6. SEO basics

PASS on all 12 public pages: unique title, description, one h1, canonical on `SITE_BASE_URL`, JSON-LD Store with `sameAs` (Product with Offer on product pages). Shell pages are `noindex`. Staging robots.txt is `Disallow: /`, every response has `X-Robots-Tag: noindex`, sitemap empty. Footer "Follow us" lists TikTok and Facebook; both are in `sameAs`; Instagram appears when configured.

## 7. Visual consistency (mechanical)

Token compliance PASS (no hard-coded colours or sizes outside `design-tokens.css`). No missing icons; favicon and manifest linked on every page. Screenshots: `qa-screenshots/<page>-390.png` and `-1280.png` for 22 public pages (taken on the first seeded run, so seed names may differ slightly from a later run).

## 8. Staff help per role

- Owner sees 8 topics, support 7 (no staff-accounts), matching `admin-help.md`. Direct link to an owner topic as support shows the notice only.
- Every "How do I…?" link opens its mapped topic for both roles.
- Topic walks: PASS getting-started, orders, password (to the email link), messages, staff-accounts. FAIL products, bulk-import, stock (item 4).
- Screenshots per topic: `qa-screenshots/help/`.

## Other cross-checks

- Kampala time: every displayed date converted, tested across the 21:00 UTC boundary (shows the next day, 00:01 Kampala time). No raw ISO or UTC text on any page.
- Rate limits return 429 with `Retry-After`. For the walkthrough the backend ran with `CLIENT_IP_HEADER=x-qa-ip` so per-IP limits did not lock the shared proxy address.
- Unsubscribe with a real token works end to end.

## Re-check of open items 1–4 (26 October 2026)

After fixes by frontend-cljs (strip, cart notice, empty-cart redirect, shared remove-dialog text, two small items) and ux-designer (help text), QA re-checked the open items in a real browser on ports 28080–28090.

- PASS 1. Phone category strip: every link reachable with Tab and fully visible when focused at 160, 195, 320 and 390 px (200% zoom included); a gold-tinted fade shows on the side with hidden links and disappears at each end; the fade never blocks clicks (text contrast over it about 8.9:1); no sideways page scroll; every link navigates correctly. Screenshots: `qa-screenshots/recheck/strip-{320,390}-{start,middle,end}.png`.
- PASS 2. Price change in the cart: "Price changed. Now UGX …" shows after an admin price change, survives a reload, clears at Checkout; a second change before Place order gives 409, returns to /cart with the top message and the notice; no notice when nothing changed.
- PASS 3. Empty cart at checkout: direct load, in-app navigation and browser Forward all end on /cart.
- 4. Help text vs screens: PASS `products` (remove dialog identical on list and product page), PASS `bulk-import` ("These stay drafts:" no longer claimed for the normal case). FAIL `stock`: the Out of stock and Low stock tabs listed drafts too, while their counts covered published products only ("Out of stock (1)" opened 2 rows). Cause: `filter-admin` in `src/backend/zandy/features/catalog/logic.clj`.
  - **Fixed afterwards by the lead (backend):** without an explicit `status`, the `stock` filter now lists published products only, matching `counts` and the help; api-contract.md GET /api/admin/products says so; new test `zandy.catalog-test/stock-tabs-match-their-counts`. Backend suite after the fix: 70 tests, 925 assertions, 0 failures; clj-kondo and the backend dependency checks pass. Not yet re-walked in a browser by QA.
  - Wording nit, not a failure: an archived product's page shows "Status" with "Archived" beside it; the help quotes "Status: Archived".
- PASS 5. The adjust-stock dialog clears "Stock cannot go below zero." on switching mode; no "Avoid space separated :class strings" warning.

Suites at re-check: frontend server 54 tests, browser 76 tests (0 warnings), frontend dependency checks, help `--check`, clj-kondo, backend 69 tests, full build: all pass.

