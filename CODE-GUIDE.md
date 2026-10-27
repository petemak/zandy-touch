# Code guide: Zandy Touch

A reviewer's guide for someone who did not write this code. It is based on the code as it is today, not only on `architecture.md`. Where the two differ, the text says so (section 8 collects them). Paths are relative to the project root. Budget: two to three hours; the reading order in section 3 is the shortest useful route.

Companion documents: `architecture.md` (design and fitness checks), `architecture-review.md` (rulings DA1 to DA10 and the earlier review), `api-contract.md` (the only interface between backend and frontend), `engineering-standards.md` ("Code documentation" section: every namespace and public var has a docstring; `:missing-docstring` is a clj-kondo warning).

---

## 1. The big picture

Three runtime parts, one database, one public origin.

```
Browser (ClojureScript app, src/frontend + src/shared, built by shadow-cljs)
   |  HTTPS
CDN (Cloudflare, honours Cache-Control)  ->  Caddy (deploy/Caddyfile)
   |                                            |-- /api/* and /media/*  -> backend  (JVM, Pedestal, Datomic peer)
   |                                            `-- everything else      -> ssr      (JVM, Pedestal, replicant.string)
ssr -- HTTP, GET only --> backend (through API_BASE_URL)          backend --> Datomic transactor (dev storage)
```

| Part | Where | What it does |
|---|---|---|
| Backend | `src/backend/zandy/`, root `deps.edn` | JSON API and `/media/*` files. Owns all data and business rules. Never renders HTML. Datomic peer, Integrant system, Pedestal router. |
| Frontend server ("ssr") | `ssr/` (own `ssr/deps.edn`) | Renders the public pages (home, shop, category, product, about, contact, delivery, four legal pages) to full HTML with SEO tags, serves `/static/*`, `robots.txt`, `sitemap.xml`, `/healthz`. Reads data only through the backend API. No database, no request state. |
| Browser app | `src/frontend/` (+ `src/shared/`) | Takes over after the HTML loads: client navigation, cart (localStorage), checkout, order tracking, and the whole staff area (`/admin/*`, client-rendered only). Calls relative `/api/...` URLs on the same origin. |
| Shared views | `src/shared/zandy/shared/*.cljc` | One set of pure Replicant views, page-data mapping, routes, cart and formatting code that runs on the JVM (inside ssr) and in the browser. This is why server HTML and browser render agree. |
| Database | Datomic (peer in each backend instance) | Everything: catalogue, orders, staff, sessions, rate-limit counters, idempotency keys, the email outbox, policies, the migration ledger. |

Request flow in one sentence: a public page request goes CDN, Caddy, ssr (`zandy.ssr.http`), backend GET calls (`zandy.ssr.pages`), HTML (`zandy.shared.document/render-page`); after that the browser app (`zandy.frontend.main/init`) re-renders the same view and sends every later action as a `/api` call, which passes the route middleware stack in `zandy.platform.http/compose` and reaches a slice handler.

Design detail: `architecture.md` sections 1 to 5 and decisions D1 to D12.

---

## 2. Map from architecture to code

File roles inside a backend slice (`src/backend/zandy/features/<slice>/`):

| File | Role | Rule |
|---|---|---|
| `routes.clj` | Route data (`def routes`) and thin handlers: read the request, call logic/db/api, build the response. | No `datomic.api` here (check B6). |
| `schema.clj` | Malli schemas for bodies and queries (closed maps). | Used by `platform.http/wrap-validate` via route data. |
| `logic.clj` | Pure functions: rules, views, templates. | No I/O (check B5). |
| `db.clj` | Queries and tx-data builders; the only place a slice talks to Datomic. | Owns the slice's attribute namespaces (check B4). |
| `api.clj` | The only namespace other slices may require. | Check B2. |

Not every slice has every file; the table below lists what exists.

### 2.1 Backend platform (`src/backend/zandy/platform/`)

| Component | File | Responsibility | Start reading at |
|---|---|---|---|
| system | `system.clj` | Integrant map (config, conn, ctx, jobs, server) and `-main`. The only namespace that knows every component. Also owns the background jobs. | `system-map`, `build-ctx`, `::jobs` init-key |
| config | `config.clj` + `resources/config.edn` | Aero read, Malli `Config` schema, prod-only extra rules, profile from env. | `load-config`, `validate!`, `profile-from-env` |
| datomic | `datomic.clj` | Connection, `fresh-db` (`d/sync`), `transact!` (translates db-function failures into API errors), `excise!`. | `transact!`, `translate-failure`, `fresh-db` |
| migrations | `migrations.clj` + `resources/migrations/` | Versioned schema/data/db-function migrations with a checksum ledger. | `load-migrations`, `pending`, `migrate!`, `verify-current!` |
| http | `http.clj` | Route table, per-route middleware composition, Pedestal connector, edge interceptor (request id, access log, 404/405). | `compose`, `route-table`, `wrap-idempotency`, `connector` |
| auth | `auth_interceptors.clj` | Enforces `:access`, `mustChangePassword`, CSRF from route data. | `wrap-auth` |
| errors | `errors.clj` | The only builder of error responses; status table. | `fail!`, `error-response`, `statuses` |
| validation | `validation.clj` | Malli decode/explain to contract `details`, phone/email/uuid schemas. | `check`, `normalize`, `normalize-phone` |
| json | `json.clj` | camelCase on the wire, kebab-case inside, UTC `Z` instants. | `encode`, `decode`, `format-instant` |
| log | `log.clj` | JSON log lines; the field redaction list. | `log!`, `sensitive`, `exception-data` |
| rate-limit | `rate_limit.clj` | Fixed-window counters in Datomic. | `consume!`, `check!`, `hit!` |
| idempotency | `idempotency.clj` | Claim/complete/release of `Idempotency-Key` records. | `claim!`, `complete!`, `cleanup!` |
| media | `media.clj` | `MediaStore` protocol (local dir), upload checks, libvips CLI variants. | `process-upload!`, `detect-type`, `local-store` |
| mailer | `mailer.clj` | `Mailer` protocol; SMTP (Angus Mail) and log-only implementations. | `make-mailer`, `send!` |
| util | `util.clj` | Clock (`now`), hashing, tokens, `fingerprint`, `client-ip`, `paginate`, `compact`. | `now`, `fingerprint`, `client-ip` |

`ctx` is the map every handler receives as `(:ctx req)`: `:config :conn :clock :mailer :media-store :image-processor :user-summaries`. Tests replace `:clock`, `:mailer`, `:media-store` (`system/build-ctx` overrides).

### 2.2 Backend feature slices

| Slice | Namespaces (under `features/<slice>/`) | Responsibility | Entry points |
|---|---|---|---|
| site | `routes api logic db` | Business info and delivery areas from config; `GET /api/healthz`. | `routes/site-info`, `routes/healthz`, `api/city-allowed?` |
| policies | `routes api db` | Current version of the four policy documents (data comes from migrations). | `api/current-policy`, `routes/get-policy` |
| catalog | `routes api logic db schema` | Categories, products, pictures, stock, search/sort, publishing rule, bulk publish, sitemap entries. Owns `:category/* :product/* :product-image/* :photo/* :stock-movement/*`. | `api/create-product!`, `api/update-product!`, `api/attach-file!`, `api/adjust-stock!`, `api/publish-product!`, `api/cart-info`, `logic/publish-problems` |
| photos | `routes imports csv logic schema` | Picture upload (per product, bulk by SKU file name), CSV product import, `/media/:file`. Owns no attributes; writes through `catalog.api`. | `routes/import-products`, `imports/run-import!`, `imports/attach-by-sku!`, `logic/match-file`, `csv/evaluate` |
| orders | `routes api logic db schema` | Cart validation, order placement, tracking, staff list/detail/fee/status, retention. Owns `:order/* :order-line/* :order-event/* :acceptance/*`. | `routes/place-order`, `db/place-order!`, `db/change-status!`, `routes/status-change`, `api/run-retention!` |
| accounts | `routes api logic db schema passwords bootstrap` | Staff users, sessions, login/logout, password change/reset, staff management, owner bootstrap. Owns `:user/* :session/* :reset-token/*`. | `routes/login`, `api/authenticate`, `api/csrf-token`, `bootstrap/bootstrap-owner!` |
| engagement | `routes api logic db schema` | Subscriptions, unsubscribe (HMAC token), contact messages, subscriber CSV, retention. Owns `:subscriber/* :contact-message/*`. | `routes/subscribe`, `routes/contact-message`, `api/run-retention!` |
| notifications | `api logic db` (no routes, no schema) | Email outbox: message builders, enqueue tx-data, claiming worker, direct send. Owns `:outbox/*`. | `api/process-once!`, `db/try-claim!`, `api/send-now!`, `db/enqueue-tx` |
| seed | `src/backend/zandy/seed/core.clj` | Sample catalogue (22 published, 1 draft; SKUs `SEED-...`) through the same catalog functions. Data in `resources/seed/`. | `seed!`, `-main` |

Route files per slice are concatenated by `platform.http/route-table`; every route map has `:method :path :name :access` plus optional `:parameters {:body :query}`, `:idempotent?`, `:rate-limit`, `:cache`, `:multipart?`, `:keep-nils?`, `:optional-session?`, `:allow-must-change?`.

### 2.3 Database definition (`resources/migrations/`)

| File | Content |
|---|---|
| `index.edn` | The ordered list of migration files (add at the end, never reorder). |
| `0001-ledger.edn` | `:migration/*` ledger attributes. |
| `0002` to `0006` | Schema: catalog (0002), accounts (0003), orders with lines, events, acceptance and the outbox (0004), subscribers, contact messages and policies (0005), rate limits and idempotency keys (0006, `noHistory`). |
| `0007-transaction-functions.edn` | Installs the five db functions from `dbfns/`. |
| `0008-categories.edn`, `0009-policies.edn` | Data: the six categories; the four placeholder policy texts of 2026-10-01 (loaded from `resources/policies/<type>/<version>.edn`). |
| `0010-pictures-belong-to-products.edn` | Makes `:product-image/photo` a component reference (no photo library). |
| `0011-approved-policies.edn` | The owner-approved 2026-10-26 policy versions become current; the 2026-10-01 drafts are kept so earlier acceptances still point at what was accepted. |
| `dbfns/ratelimit_hit.clj` | `:ratelimit/hit` fixed-window counter. |
| `dbfns/idempotency_claim.clj` | `:idempotency/claim` take-or-find a key; takes over stale claims (60 s). |
| `dbfns/place_order.clj` | `:orders/place-order`: checks every line, writes order, lines, stock decrements, movements, acceptance, history and outbox together, or throws `cartOutdated`. |
| `dbfns/change_order_status.clj` | `:orders/change-status`: transitions, version check, fee rule, one-time stock restore. |
| `dbfns/adjust_stock.clj` | `:catalog/adjust-stock`: never below zero, optional expected quantity. |

Db functions run on the transactor and may only use `clojure.core` and `datomic.api` (fully qualified, no `ns`). The first form of each file is `{:params [...]}`, the rest is the body (`migrations/dbfn-tx`).

### 2.4 Frontend server (`ssr/src/zandy/ssr/`)

| Namespace | Responsibility | Start reading at |
|---|---|---|
| `system` | Integrant: `:ssr/config`, `:ssr/api`, `:ssr/launch-gate`, `:ssr/server`; `-main` exits 1 on any startup failure. | `system-map`, `-main` |
| `config` + `ssr/resources/ssr-config.edn` | Aero + Malli config; `PAGE_CACHE_*` defaults. | `load-config` |
| `http` | One catch-all Pedestal interceptor: `/healthz`, `/robots.txt`, `/sitemap.xml`, `/favicon.svg`, `/static/*`, pages. GET/HEAD only. | `dispatch`, `page`, `handler` |
| `pages` | Per-route API calls and state assembly; the query clean-up and 302 for listing URLs. | `load-state`, `canonical-redirect`, `normalised-query` |
| `api_client` | The only HTTP client (JDK `HttpClient`); parallel GETs; never throws. | `fetch-all`, `fetch` |
| `render` | State to HTTP response: status, `Cache-Control` per page type, weak ETag/304, CSP, 503 `Retry-After`. | `page-response`, `cache-control` |
| `static` | `/static/*` with cache headers; reads `asset-manifest.edn` on every page request. | `serve`, `assets`, `cache-control-for` |
| `seo_routes` | `robots.txt`, `sitemap.xml` (depends on `SITE_INDEXABLE`). | `robots`, `sitemap` |
| `launch_gate` | At boot with `SITE_INDEXABLE=true`, fails unless all four policies are `approved`. | `check!` |
| `log` | JSON log lines, same shape as backend. | `event` |
| `ssr/dev/` (not shipped) | `stub_api` (stateful fake backend), `dev_proxy` (single-origin dev proxy). | `-main` of each |
| `ssr/test/` | `http_test`, `launch_gate_test`, `log_test`. | |

### 2.5 Shared views (`src/shared/zandy/shared/`, `.cljc`)

| Namespace | Responsibility | Start reading at |
|---|---|---|
| `routes` | Reitit page route table (`:ssr?` / `:shell?`, `:cache`, `:policy`, `:admin?`, `:access`), query parse/encode, `match-url`, `path-for`. Reitit is a data matcher only. | `routes`, `match-url`, `encode-query` |
| `pages` | Which API calls each route needs (`requests`), how responses become page state (`assemble`), `normalise-query`, `public-view` (P1 to P17), `admin-route?`. | `requests`, `assemble`, `content`, `public-view` |
| `pages/catalog`, `pages/info`, `pages/shop` | Views: home/shop/category/product (P1 to P4), about/contact/delivery/legal/unsubscribe/not-found/unavailable (P9 to P17), cart/checkout/order-placed/track (P5 to P8). | `catalog/home`, `shop/checkout-page` |
| `ui/core`, `ui/layout`, `ui/product`, `ui/order`, `ui/policy` | Small components (buttons, fields, header/footer, cards, order summary, policy blocks). | `layout/page-frame` |
| `document` | `render-page`: full HTML via `replicant.string`, SEO head, `<script id="initial-state">`. | `document-hiccup` |
| `seo` | Title, description, canonical, Open Graph, JSON-LD for a state. | `head` |
| `cart` | Pure cart functions (add, set quantity, apply validation, order items, storage form). | `add-item`, `apply-validation`, `order-items` |
| `format` | UGX, phone, Kampala time, status labels. | `ugx`, `kampala-parts`, `iso->date-time` |
| `json` | `write-str` that escapes `<`, `>`, `&` for the embedded state. | `write-str` |

Rules: `src/shared` may not require `zandy.frontend`, `zandy.ssr`, `datomic`, `io.pedestal`, and may not use `js/` (checked by `frontend_deps.clj graph`).

### 2.6 Browser app and admin (`src/frontend/zandy/frontend/`, `.cljs`)

| Namespace | Responsibility | Start reading at |
|---|---|---|
| `main` | Entry (`init`): reads embedded state, renders into `#app`, turns DOM events into action vectors (`enrich` fills `[:event/...]` placeholders), `transition!`. | `init`, `dispatch-event`, `transition!` |
| `state` | The single state atom `!state`; `handle` multimethod: pure `(state action) -> {:state :fx}`. Navigation, cart, forms, checkout, tracking, newsletter. | `handle`, `start-route`, `route-enter`, `:checkout/submit` |
| `effects` | The imperative shell: runs effect vectors (`:fx/request`, `:fx/page-load`, `:fx/upload-files`, `:fx/uuid`, storage, focus, head). Adds the CSRF token to staff mutations. | `run-effect!`, `with-credentials` |
| `api` | The only `js/fetch` user. Relative URLs, headers (CSRF, Idempotency-Key, no-cache). | `request!` |
| `router` | `history` push/replace/popstate, current URL. | `push!`, `listen!` |
| `cart_store` | localStorage for the cart (`zt-cart`) and the last order (`zt-last-order`). | `load-cart`, `save-cart!` |
| `admin/state` | `:admin/*` and `:help/print` handlers on the same multimethod: session check, login, orders, products, stock, pictures, CSV import, publish, staff. | `:admin/enter`, `:admin/status-change`, `:admin/csv-check`, `:admin/publish-complete` |
| `admin/core` | Views A1 to A13. | `view` |
| `admin/widgets` | Import report, drop zone, picture manager, stock list. | `product-image-manager`, `import-result-list` |
| `admin/help`, `admin/help_data` | Staff help: role filter, topic views; `help_data.cljs` is GENERATED. | `visible-topics`, `topic-view`, `how-do-i` |
| `scenes/*` | Portfolio component gallery (`shadow-cljs.edn` build `:portfolio`); not reachable from `main`. | |

### 2.7 Scripts, builds and tests

| Path | Purpose |
|---|---|
| `scripts/build/build.sh` | Full frontend build (see section 6). |
| `scripts/build/assets.clj` | Copies `design-tokens.css`, `assets/`, concatenated `styles/*.css` into `target/static/`, content-hashes CSS, writes `asset-manifest.edn`. |
| `scripts/build/help.clj` | `admin-help.md` to `help_data.cljs` (walkthrough f). `--check` for staleness. |
| `scripts/check/backend_deps.clj` | Fitness checks `slices`, `graph`, `attrs` (B2, B3, B4). |
| `scripts/check/frontend_deps.clj` | `graph`, `api-calls`, `versions` (B3, B9b, B19), `all`. |
| `scripts/check/seed_manifest.clj` | Seed images vs `manifest.edn` and `SOURCES.md` (B23). |
| `scripts/ops/` | Deployment scripts (deploy, backup, smoke tests); not application code. |
| `dev/user.clj` | REPL helpers `go`, `halt`, `ctx`. Not on any alias classpath (see section 6). |
| `test/backend/zandy/` | Backend tests (in-process, `datomic:mem`): `orders/orders_flow_test`, `orders/place_order_concurrency_test`, `architecture/{routes,idempotency,contract_inventory}_test`, `contract_conformance_test`, `accounts_test`, `catalog_test`, `import_test`, `pictures_test`, `notifications_test`, `engagement_test`, `retention_test`, `seed_test`, `smoke_test`, `platform/{config,http,log,media,migrations}_test`. `test_helpers.clj` builds a context with a controllable clock. |
| `test/shared/`, `test/frontend/` | `.cljc` tests (run on the JVM via `ssr` and in node), `help_build_test.clj` (JVM), `state_test.cljs`, `help_test.cljs` (node). |

---

## 3. Suggested reading order (about 10 steps)

| Step | Read | Why / what to look for | Time |
|---|---|---|---|
| 1 | `deps.edn`, `resources/config.edn`, `platform/config.clj` | The aliases you will run; every environment value; how prod fails fast. | 10 min |
| 2 | `platform/system.clj` | Start-up order, what runs in the background (outbox every 15 s, hourly clean-ups and retention), the dev-only owner bootstrap and seed. | 10 min |
| 3 | `platform/http.clj` (`compose`, `wrap-*`, `edge-interceptor`) and `platform/auth_interceptors.clj` | The middleware order is the security model: response, errors, auth, rate limit, validate, idempotency, handler. | 25 min |
| 4 | `platform/errors.clj`, `validation.clj`, `datomic.clj` (`transact!`), `idempotency.clj`, `rate_limit.clj` | The error shape, how db-function failures become API errors, how a key is claimed. | 20 min |
| 5 | `resources/migrations/dbfns/place_order.clj`, `change_order_status.clj`, then `platform/migrations.clj` | The business-critical atomic code and how it is installed and checksummed. | 25 min |
| 6 | One full slice: `features/orders/` (`routes`, `db`, `logic`, `schema`, `api`) | The slice pattern at its richest. Then skim `catalog/api.clj` for the product write path. | 30 min |
| 7 | `features/accounts/` (`routes`, `api`, `db`, `logic`) and `notifications/` | Sessions, CSRF, Argon2id, outbox claiming. | 20 min |
| 8 | `features/photos/` (`routes`, `imports`, `csv`, `logic`) and `platform/media.clj` | The import pipeline and upload checks. | 15 min |
| 9 | `ssr/src/zandy/ssr/http.clj`, `pages.clj`, `render.clj`, `api_client.clj`; `shared/routes.cljc`, `shared/pages.cljc`, `shared/document.cljc` | How the public HTML is made and cached. | 25 min |
| 10 | `frontend/main.cljs`, `state.cljs` (navigation and checkout parts), `effects.cljs`, `admin/state.cljs` (login, orders, import), `scripts/build/help.clj` | The action/effect loop; then the checks in `scripts/check/` and the oddity list in section 7. | 30 min |

Run `clojure -M:test` once before step 6; the tests double as executable examples (`orders_flow_test`, `place_order_concurrency_test`, `idempotency_test`).

---

## 4. Walkthroughs

Notation: `ns/fn` with file paths relative to the project root. `src/backend/zandy` is abbreviated `B/`, `src/shared/zandy/shared` as `S/`, `src/frontend/zandy/frontend` as `F/`, `ssr/src/zandy/ssr` as `SSR/`.

### 4a. A public page request (for example `/products/radiance-face-serum`)

1. CDN: a hit within `s-maxage` is answered there (lifetimes below). On a miss it goes to Caddy (`deploy/Caddyfile`), which sends `/api/*`, `/media/*` to the backend and everything else to ssr, setting `X-Client-Ip`, `X-Request-Id` and `X-Upstream`.
2. `SSR/http/handler` generates or reuses the request id, calls `dispatch`. GET/HEAD only; named paths first, otherwise `SSR/http/page`.
3. `page`: `S/routes/match-url` matches the path with Reitit (`:product`, `:ssr? true`, `:cache :catalogue`). For `:shop` and `:category`, `SSR/pages/canonical-redirect` returns a 302 to the clean query when unknown or invalid parameters are present (one CDN cache key per real page).
4. `SSR/pages/load-state`: shell routes (cart, checkout, admin ...) get `:page {:status :loading}` with no API call. Otherwise `S/pages/requests` lists the calls (`/api/site-info`, `/api/products/{slug}`), `SSR/api_client/fetch-all` starts all of them in parallel (3 s timeout, forwards `X-Request-Id`), and `S/pages/assemble` turns the responses into `{:site :page}`: 404 on the primary resource gives `:not-found`, any other failure gives `:unavailable`.
5. Backend side of each call: `platform.http` edge interceptor, `compose`, `features/catalog/routes/get-product` (public, `:cache` header, ETag). Only published products are served.
6. `SSR/render/page-response` calls `S/document/render-page`: `S/seo/head` (title, canonical, Open Graph, JSON-LD), `S/pages/public-view` into `#app`, the state JSON in `<script id="initial-state">` (escaped by `S/json/write-str`), CSS and bundle links from `SSR/static/assets` (reads `asset-manifest.edn`). Status from `S/pages/status->http` (200, 404, 503), `Cache-Control` from `render/cache-control`, ETag and 304, CSP.
7. Browser: bundle runs `F/main/init`: `effects/init!`, `read-embedded`, `boot-state`, then `render!` renders the same views over `#app` (Replicant clears the container on first render, so the server markup is replaced by identical markup; DA7). `state/route-enter` runs: head update, a no-cache re-fetch of the product (`:product/rechecked`) because the cached HTML may be about 90 s old.
8. Later navigation: `[:nav/link url]` (from `ui/link`) to `state/start-route`, then `[:fx/page-load url reqs]` in `effects/run-effect!`, `api/request!`, `[:page/loaded url results]`, which reuses `S/pages/assemble`.

Failure path: any API failure gives `:unavailable`, a 503 page with `Retry-After: 30` and `Cache-Control: no-store`; an exception in `handler` renders the same page (or plain text).

### 4b. A customer places an order

1. Cart (browser only, localStorage): `[:cart/add product qty]` in `F/state` uses `S/cart/add-item`; `effects` `:fx/save-cart` writes `zt-cart`. Opening `/cart` or `/checkout` runs `route-enter`, which dispatches `[:cart/validate]`.
2. `[:cart/validate]` posts to `POST /api/cart-validations` (signature-tagged so a stale answer is ignored). Backend: `features/orders/routes/cart-validation` checks quantities/duplicates, `catalog/cart-info` (read), `orders/logic/cart-line` (pure: `unavailable`, `soldOut`, `insufficientStock`), `cart-response`. Read only. `:cart/validated` stores the result with `S/cart/apply-validation` (prices are deliberately not overwritten so "price changed" can show; `:cart/acknowledge-prices` replaces them when the customer goes to checkout).
3. `[:checkout/submit]`: client checks (`checkout-errors`), then a UUID idempotency key from the `:fx/uuid` effect (handlers are pure and cannot call `random-uuid`) stored as `:order-key`, then `POST /api/orders` with body from `order-body` (`expectedUnitPrice` per line, order-terms version, empty honeypot `website`).
4. Backend middleware (`B/platform/http/compose`): `wrap-auth` (public), `wrap-rate-limit` (`:orders` limit per IP), `wrap-validate` (Malli `orders.schema/PlaceOrder`, fingerprint of the parsed body), `wrap-idempotency`: requires a UUID `Idempotency-Key`, scoped as `METHOD uri key`, `idempotency/claim!` runs the `:idempotency/claim` db function. A second identical request replays the stored response with `Idempotent-Replayed: true`; a different body is 409 `idempotencyKeyReused`.
5. `orders/routes/place-order`: honeypot (filled `website` returns a believable fake order, stores nothing); quantity/duplicate/city checks (`site/city-allowed?`); order-terms version must equal `policies/current-policy` else 409 `agreementVersionOutdated`; `catalog/cart-info`; builds the outbox messages (`outbox-messages`: owner/staff alerts via `accounts/order-alert-recipients`, optional customer receipt); builds the spec (`place-spec`).
6. `orders/db/place-order!` transacts `[:orders/place-order spec]`. On the transactor (`resources/migrations/dbfns/place_order.clj`): re-reads each product, fails with `{:zandy/failure :cartOutdated :details [...]}` for any unpublished, sold out, short or repriced line; otherwise returns one transaction with the order (lines with price snapshots, `:placed` event, acceptance with policy version and hash, `:order/idempotency-key`), `:product/stock-quantity` decrements, `:order` stock movements and the outbox entities as `:pending`. `platform.datomic/transact!` unwraps the failure into `errors/fail! :cartOutdated` (409 with per-line details). Two buyers of the last unit are serialised by the transactor, so stock cannot go negative (`place_order_concurrency_test`).
7. Duplicate handling inside the handler: a unique clash on `:order/idempotency-key` becomes `:replay` (re-read by key, 200); a clash on `:order/number` retries with a new random number (up to 5). Both are detected by exception message text (oddity B2). First success returns 201; `:replay-status 200` makes the stored replay a 200.
8. Browser `:checkout/result`: 201/200 clears the cart, saves the order (`zt-last-order`), navigates to `/order-placed`; 409 `cartOutdated` goes back to `/cart` with a notice and a new key; 409 `agreementVersionOutdated` reloads the terms; 422 shows field errors via `checkout-field-map`; 429 and network errors keep or rotate the key as appropriate.
9. Email: the outbox rows are already in the database. The `::jobs` scheduler in `platform/system.clj` runs `notifications/api/process-once!` every 15 s on every instance: `db/claimable`, `db/try-claim!` (`:db/cas`, one winner), `mailer/send!`, `db/mark-sent!`, or `db/mark-failed!` (back to pending, `failed` after 3 attempts); daily cap `MAIL_DAILY_CAP`. A failed email never fails the order.
10. Tracking: `/track-order` (shell page) posts `POST /api/order-trackings` (`[:track/submit]`). `orders/routes/track-order` reads `db/order-by-number-fresh` and compares the normalised phone; a wrong number and a wrong phone give the same 404 `orderNotFound`. `orders/logic/tracking-view` omits address, email, product ids and staff names.

### 4c. Staff process an order

1. Sign in (see 4e), then `/admin` loads `GET /api/admin/orders` (`admin/state/route-requests`, `orders/routes/admin-list`: in-memory filter and page over `db/all-orders`, status counts over all orders).
2. Open an order: `GET /api/admin/orders/:id` (`admin-get`: order, acceptance with name/phone, history with user summaries from `ctx :user-summaries`).
3. Delivery fee: `[:admin/order-save]` sends `PATCH /api/admin/orders/:id` with `version`, `deliveryFee` and/or `staffNote`. `orders/routes/admin-patch` checks the order is open, the version matches, then `db/patch-order!` uses `[:db/cas ... :order/version v (inc v)]`. Stale version: 409 `versionConflict` (UI shows the conflict banner and reloads).
4. Status change: `[:admin/status-change target]` sends `POST /api/admin/orders/:id/status-changes` with `version`, `status` and, per target, `paymentMethodUsed` (delivered) or `cancelReason` (cancelled). `orders/routes/status-change`: same status is a 200 no-op; missing fields are 422; then `db/change-status!` runs `[:orders/change-status spec]`.
5. `dbfns/change_order_status.clj` decides on the transactor: allowed transitions `placed -> confirmed | cancelled`, `confirmed -> approvedForDelivery | cancelled`, `approvedForDelivery -> delivered | cancelled`; version check; `confirmed` and `approvedForDelivery` need a delivery fee (else 409 `deliveryFeeRequired`); `:db/cas` on version; adds an `order-event`; when cancelling from `placed` or `confirmed` and `stock-restored?` is not set, adds each line's quantity back to `:product/stock-quantity`, writes an `:orderCancelled` stock movement per line and sets `stock-restored?`. Cancelling from `approvedForDelivery` does not restore stock (goods have left; staff adjust by hand), which matches `api-contract.md` section 7 and the UI notice in `:admin/status-changed`.
6. Response 201 when the version moved (200 otherwise) with `stockRestored`. Manual stock changes use `POST /api/admin/products/:id/stock-movements` (`catalog/api/adjust-stock!`, `:catalog/adjust-stock` db function, idempotent).

### 4d. Bulk import: CSV, pictures, publish (admin page A9, `/admin/product-import`)

1. Download the template: `GET /api/admin/product-import-template` (`photos/imports/template-response`, `csv/template-csv`).
2. CSV dry run: `[:admin/csv-picked]` (local size checks), `[:admin/csv-check :dry]` gets a UUID key per file and mode, `:fx/upload-files` posts `POST /api/admin/product-imports?dryRun=true` (field `file`). The platform (`http/wrap-validate`, `:multipart? true`) parses multipart, fingerprints query, fields and file contents (`multipart-fingerprint`) and deletes the temp files afterwards.
3. `photos/routes/import-products` to `imports/read-csv-upload` (one file, at most 1 MB, `csv/decode-text` strict UTF-8 with BOM handling, `csv/parse-table` with comma/semicolon detection, header checks, at most 500 rows), then `imports/run-import!` to `csv/evaluate` (pure: each row validated; SKU present means update, absent means create and then name, brand, category are required; duplicates in the file rejected; `openingStock` ignored with a warning for existing products). With `dryRun` the rows are only reported; `csv/report` builds `{:dryRun :summary :rows}`.
4. Real import: `[:admin/csv-check :real]` is only allowed after a dry run that would change something (`admin/state/can-import?`), with a different key. `imports/apply-row!` per row: `catalog/create-product!` (always `draft`; opening stock becomes the first `initial` movement) or `catalog/update-product!` (retries a version conflict up to 3 times). One rejected row never stops the others.
5. Pictures by SKU: `[:admin/pictures-picked]` (local checks), `[:admin/pictures-upload]` sends batches of 20 (`batch-size`), each with its own key, to `POST /api/admin/product-images`. `photos/routes/upload-by-sku` to `imports/attach-by-sku!`: `photos/logic/match-file` matches the SKU at the start of the file name (case-insensitive, optional `-1` to `-6` slot suffix, longest SKU wins), then `catalog/api/attach-file!`: `media/process-upload!` (magic-byte type check, 5 MB and 6000 px limits, 480/800/1200 WebP via the libvips CLI), then one transaction creating the photo and `product-image` together (`catalog/db/attach-picture-tx`, `:db/cas` on the product version, up to 5 retries), deletes a replaced picture's files after commit. Per-file problems are reported per item, never as a request error.
6. Review and publish: step 3 lists drafts (`drafts-request`, up to 5 pages of 100). `[:admin/publish-complete]` sends the ids of drafts that have price, picture and category to `POST /api/admin/product-publications`. `catalog/routes/publish-many` calls `catalog/api/publish-product!` per id (`update-product!` with `status "published"` under `catalog/logic/publish-problems`; retries version conflicts; failures reported per product).

### 4e. Staff sign-in, sessions, CSRF and role checks

1. `/admin/*` pages are shells (`:shell? true`, `:access` in `S/routes`). On entry `[:admin/enter]` asks `GET /api/auth/sessions/current` (the cookie is HttpOnly, so the browser cannot tell). 401 sends the user to `/admin/login`.
2. Login: `[:admin/login]` posts `POST /api/auth/sessions`. `accounts/routes/login`: `rate-limit/check!` for `login-ip:<ip>` and `login-email:<email>` (read-only), user lookup, `passwords/check-password?` (Argon2id) or `passwords/check-dummy!` for unknown/inactive accounts (same timing), failures call `rate-limit/hit!` on both keys. Success: `util/random-token`, only its SHA-256 is stored (`db/create-session!`), `Set-Cookie: zt_session=...; HttpOnly; Secure; SameSite=Lax; Path=/api; Max-Age=7 days`, body `{user, csrfToken}`.
3. Every later staff request: `platform/auth_interceptors/wrap-auth` reads the cookie, `accounts/api/authenticate` hashes it and looks the session up (with a `d/sync` retry for sessions created on another instance), checks `logic/session-active?` and that the user is active, and refreshes `last-seen-at` and the 12 h sliding expiry at most every 5 minutes (7 days absolute: `logic/session-expiry`). Check order and resulting errors: 401 `unauthenticated`; 403 `forbidden` for an owner-only route; 403 `passwordChangeRequired` unless the route sets `:allow-must-change?`; 403 `csrfInvalid` for POST/PUT/PATCH/DELETE without a valid `X-CSRF-Token`.
4. CSRF token = `accounts/api/csrf-token` = HMAC-SHA256 of the session id hash with `SESSION_HMAC_SECRET`: nothing stored, any instance can verify. The browser keeps it in state `[:admin :session :csrf]` and `effects/with-credentials` adds it to every non-GET request to `/api/admin...` and to every DELETE.
5. Roles: `:owner` or `:support` on the user; the route's `:access` (`:staff` or `:owner`) is the authorization. `S/routes` and `admin/state/enter-with-session` only hide pages for convenience. `architecture/routes_test` walks the whole route table (every route has `:access`; staff routes 401 without a cookie; owner routes 403 for support; CSRF enforced).
6. Password change/reset: `PUT /api/admin/account/password` revokes the user's other sessions; owner reset by email (`request-reset` emails a one-hour link directly with `notifications/send-now!`, never via the outbox; `complete-reset` revokes all sessions); owners reset staff by issuing a temporary password (`create-staff`, `reset-staff-password`; shown once, the idempotent replay stores `nil`). Sign-out (`DELETE /api/auth/sessions/current`) revokes the session and clears the cookie.
7. First owner: `clojure -M:bootstrap-owner` (`accounts/bootstrap/bootstrap-owner!`) from `OWNER_EMAIL`/`OWNER_INITIAL_PASSWORD`, must change password. In non-prod profiles `system/::conn` calls it on boot.

### 4f. The staff help

1. Source: `admin-help.md` (written by the UX designer; never edited by builders). A small fixed format: `# Title`, `## id | Title | roles: ... | pages: A4, A5 | group: ...`, then a plain-text summary line, then paragraphs, `### ` headings, numbered steps, bullets, `> ` notes, `**bold**`, `[text](help:id)`, `[text](page:A4)`.
2. Build: `scripts/build/help.clj` (`bb scripts/build/help.clj`): `split-sections`, `parse-header`, `parse-blocks`, `parse-inline`, `resolve-links` into `parse`; `render` writes the data namespace. It fails with `admin-help.md:<line>:` messages on unknown syntax, duplicate ids, a link to an unknown topic or page, a support-visible topic linking to an owner-only topic or page A12, or an admin page A4 to A12 without a topic.
3. Output: `src/frontend/zandy/frontend/admin/help_data.cljs` (`title`, `groups`, `pages`, `topics`; header "GENERATED ... Do not edit"). Committed. `bb scripts/build/help.clj --check` exits 1 when it is stale; `test/frontend/zandy/frontend/help_build_test.clj` tests `parse`.
4. Runtime: `admin/help` filters by role. `visible-topics`, `grouped`, `can-see?`, `topic-view` (shows a "this topic is for the shop owner" notice for a topic the role may not read, never its text), `how-do-i` (the link beside each admin page via `admin/core`, using `topic-for-route` and the `pages:` field). `admin/core/view` routes `:admin-help` and `:admin-help-topic` to `list-view` and `topic-view`. The role comes from `admin/state/role`.
5. This filter is a convenience only: the whole help text ships in the public JavaScript bundle (DA10).

---

## 5. Cross-cutting concerns, with code pointers

| Concern | How it works | Where |
|---|---|---|
| Configuration | Aero `#profile`/`#env`; backend profile from `ZANDY_PROFILE` or `-Dzandy.profile`, default `prod` (fail fast). Malli `Config` plus prod-only rules (secret length 32, SMTP, no stub processor, no in-memory Datomic). Non-prod gets throwaway secret and owner email. SSR: same pattern, `ssr-config.edn`, env `API_BASE_URL SITE_BASE_URL SITE_INDEXABLE STATIC_DIR PAGE_CACHE_* API_TIMEOUT_MS PORT`. | `B/platform/config.clj`, `resources/config.edn`, `SSR/config.clj`, `ssr/resources/ssr-config.edn` |
| Validation | Malli schemas per slice, closed maps; `wrap-validate` decodes query (strip unknown keys, coerce), parses JSON, `normalize` (trim, empty string to nil, drops nils unless `:keep-nils?`), validates, answers 422 with `details` `{field code message}` in camelCase. CSV rows use the same machinery. SSR validates its config and the listing query. Browser checks are conveniences. | `B/platform/validation.clj`, `http/wrap-validate`, `features/*/schema.clj`, `photos/csv.clj` (`RowSchema`), `SSR/pages.clj` (`ListingQuery`) |
| Error shape | `{"error": {"code", "message", "details"?}}`, one constructor, status table in one map. Exceptions: `fail!` throws, `wrap-errors` converts; unknown exceptions are logged and answered as `internalError`. Db-function failures: `{:zandy/failure kw}` in `ex-data`, converted in `datomic/transact!`. | `B/platform/errors.clj`, `http/wrap-errors`, `datomic/translate-failure` |
| Logging | One JSON line per event; redaction by key (`sensitive`: passwords, tokens, phones, emails, names, addresses, free text, bodies) at any depth; exceptions reduced to class names and one-line messages; request log uses the route template, never the raw path or query. SSR has the same shape. | `B/platform/log.clj`, `http/edge-interceptor`, `SSR/log.clj`, `SSR/http/handler` |
| Idempotency | Generic: `Idempotency-Key` header (UUID) on routes with `:idempotent? true`; record in Datomic (`:idempotency/*`, `noHistory`), 24 h retention (`idempotency/cleanup!`), one-time secrets not replayed (`:replay-body`). Orders add a second guard: the key is stored on the order (`:order/idempotency-key`, unique) inside the same transaction. | `B/platform/idempotency.clj`, `http/wrap-idempotency`, `dbfns/idempotency_claim.clj`, `orders/routes/place-order` |
| Rate limits | Fixed windows kept in Datomic by a db function; check reads first, so a flood causes at most `limit` writes. Named limits in `config.edn :limits` (`login orders order-tracking subscriptions contact reset-request unsubscribe`). Client identity from the header named by `CLIENT_IP_HEADER`, set by Caddy. | `B/platform/rate_limit.clj`, `http/wrap-rate-limit`, `util/client-ip` |
| Multi-instance state | No request state in memory (backend or ssr). Sessions, limits, idempotency keys, stock, outbox in Datomic. Peers lag by milliseconds: lookups that miss retry once after `d/sync` (`fresh-db`): sessions, reset tokens, orders by id/number/key, idempotency polling. Background jobs run on every instance and rely on `:db/cas` and idempotent clean-up. | `datomic/fresh-db`, `accounts/db/session-for-token`, `orders/db/*-fresh`, `notifications/db/try-claim!`, `system/::jobs` |
| Migrations and ledger | Files listed in `index.edn`; checksum covers the file plus its db-function and policy files; the runner refuses edited-after-applied files, ledger entries without files, and numbering gaps. Prod runs `clojure -M:migrate` as a separate step; instances only `verify-current!`. Dev and test migrate on boot. | `B/platform/migrations.clj`, `resources/migrations/`, `system/::conn`, `migrations_test` |
| Retention and excision | Hourly job: closed orders older than 183 days lose name, phone, email, area, address, directions and the acceptance's name/phone (Datomic `:db/excise`, 25 per run); contact messages older than 183 days are excised (100 per run); sent/failed outbox messages older than that are excised; idempotency records older than 24 h are deleted. | `orders/api/run-retention!`, `orders/db/excise-order-personal-data!`, `engagement/api/run-retention!`, `notifications/api/purge-old!`, `datomic/excise!`, `retention_test` |
| Time zones | The API speaks UTC only (`...Z`, whole seconds; `platform.json` encoders). Kampala time (UTC+3, no DST) is applied only for display with fixed-offset arithmetic so JVM and browser output are identical. Emails contain no times. | `B/platform/json.clj`, `S/format.cljc` (`kampala-parts`, `iso->date-time`) |
| Caching headers | Backend: `:cache` in route data applies to successful GETs, plus ETag (hash of body) and 304; all errors and non-GET responses are `no-store`; media files are immutable for a year. SSR pages: `Cache-Control` by page type from `PAGE_CACHE_*` (catalogue about 90 s, info 10 min, shells `no-cache` + ETag, 404 60 s, 503 `no-store`); `/static`: fingerprinted js/css/fonts immutable, logos and icons 1 day; `robots.txt`/`sitemap.xml` `s-maxage=3600`. The session cookie has `Path=/api` so page requests stay cacheable. | `http/wrap-response`, `catalog/routes` (cache constants), `photos/routes/serve-media`, `SSR/render.clj`, `SSR/static.clj`, `SSR/seo_routes.clj`, `deploy/CLOUDFLARE.md` |
| Security headers | Backend: `X-Content-Type-Options: nosniff` (edge interceptor). SSR: CSP `default-src 'self'; ... connect-src 'self'`, `Referrer-Policy`, `X-Robots-Tag: noindex` when not indexable. HSTS at Caddy. | `http/edge-interceptor`, `SSR/render/security-headers` |

---

## 6. How to run, test and check locally

All commands exist in `deps.edn`, `ssr/deps.edn`, `shadow-cljs.edn`, `package.json` or `scripts/`. Tools assumed: `clojure`, `bb` (babashka), `node`/`npx`, optionally `clj-kondo` and libvips.

Backend (root):
```
clojure -M:run                       # dev profile: in-memory Datomic, log mailer, stub image processor, seeded, port 8080
OWNER_INITIAL_PASSWORD='correct-horse-1' clojure -M:run   # also creates the owner (owner@example.invalid by default); without it no owner exists
clojure -M:test                      # all backend tests
clojure -M:test -n zandy.orders.orders-flow-test          # one namespace
ZANDY_TEST_DATOMIC_URI_PREFIX=datomic:dev://host:4334/ clojure -M:test   # run tests against a real transactor
clojure -M:migrate                   # apply pending migrations (prod/staging step)
clojure -M:bootstrap-owner           # create the owner from OWNER_EMAIL / OWNER_INITIAL_PASSWORD
clojure -M:seed                      # sample catalogue
clojure -M:send-test-mail            # one SMTP test message to OWNER_EMAIL
```
`dev/user.clj` (`go`, `halt`, `ctx`) is not on the classpath of any alias; add `dev` to the classpath yourself (for example in your editor's REPL alias) if you want it.

Frontend server (`ssr/`):
```
cd ssr && clojure -M:run             # port 8081; API_BASE_URL defaults to http://127.0.0.1:8080; STATIC_DIR defaults to ../target/static (build assets first)
cd ssr && clojure -M:test            # ssr tests + ../test/shared + ../test/frontend (.clj/.cljc only)
cd ssr && clojure -M:stub            # stateful fake backend on 8080 (ssr/dev/zandy/ssr/stub_api.clj)
cd ssr && clojure -M:proxy           # single-origin dev proxy on 8090 (/api,/media -> 8080; rest -> 8081)
```
Typical full local stack: `clojure -M:run`; `scripts/build/build.sh`; `cd ssr && clojure -M:run`; `cd ssr && clojure -M:proxy`; open `http://localhost:8090`.

Browser bundle and ClojureScript tests (root):
```
npx shadow-cljs watch app            # dev build into target/static/js
npx shadow-cljs release app          # advanced-optimised, hashed file name
npx shadow-cljs watch portfolio      # component gallery on http://localhost:8085 (run `bb scripts/build/assets.clj` once first)
npx shadow-cljs compile test && node target/test/node-tests.js   # :node-test build, namespaces ending in -test (no wrapper script exists)
```

Build:
```
scripts/build/build.sh               # npm ci (skip with SKIP_NPM=1), help.clj, remove target/static/js, shadow release, assets.clj
bb scripts/build/help.clj            # regenerate help_data.cljs from admin-help.md
bb scripts/build/help.clj --check    # exit 1 when help_data.cljs is stale
bb scripts/build/assets.clj          # fingerprint CSS, copy assets, write asset-manifest.edn
```

Fitness checks (exit 0 = pass):
```
bb scripts/check/backend_deps.clj slices    # slices only require each other's .api; no layer folders
bb scripts/check/backend_deps.clj graph     # namespace edges match the allowed table; acyclic
bb scripts/check/backend_deps.clj attrs     # attribute namespaces used only by their owner
bb scripts/check/frontend_deps.clj all      # graph + api-calls + versions (or one of them)
bb scripts/check/seed_manifest.clj          # seed images vs manifest
```

Lint (per `architecture.md` section 7, B1 and B26):
```
clj-kondo --lint src/backend test/backend dev
cd ssr && clj-kondo --lint src ../src/shared ../src/frontend
```

Useful architecture-level tests: `zandy.architecture.routes-test` (authorization/validation of every route), `zandy.architecture.idempotency-test`, `zandy.architecture.contract-inventory-test` (routes equal the contract headings), `zandy.contract-conformance-test` (camelCase, UTC, error shape), `zandy.orders.place-order-concurrency-test` (no oversell), `zandy.retention-test`, `zandy.platform.migrations-test`.

---

## 7. Review checklist

### 7.1 Where a reviewer's time pays off most

| # | Area | Look at | Question |
|---|---|---|---|
| 1 | Atomic order code | `resources/migrations/dbfns/place_order.clj`, `change_order_status.clj` | Can stock go negative or be restored twice? Is every failure path a thrown `:zandy/failure`? |
| 2 | Middleware order | `platform/http.clj` `compose`; `auth_interceptors.clj` | Is every route authorised before validation and before the handler runs? Does an exception in the handler release the idempotency claim? |
| 3 | Session and CSRF | `accounts/routes.clj` `login`; `accounts/api.clj`; `effects.cljs` `with-credentials` | Same error for unknown user and wrong password; CSRF on every mutating staff route; cookie flags. |
| 4 | Data written outside its owner | `scripts/check/backend_deps.clj attrs` and the two db functions | The only multi-entity writers are the order db functions (architecture B4 exception). |
| 5 | Privacy | `platform/log.clj` `sensitive`; `orders/logic/tracking-view`; retention functions | No phone/email/address in logs or tracking; excision, not retraction. |
| 6 | Money and stock | `orders/logic.clj`, `catalog/logic.clj` (`availability`, `max-quantity`), `shared/cart.cljc` | Totals are derived, never stored; client clamp vs server limit (F3). |
| 7 | Launch gate and indexing | `ssr/launch_gate.clj`, `SSR/seo_routes.clj`, `render/security-headers` | Staging cannot be indexed; production cannot start with placeholder policies. |
| 8 | Import | `photos/csv.clj`, `photos/imports.clj` | Row independence, dry run equals real run, draft-only creation. |

### 7.2 Oddities reported during the documentation pass (as found, not fixed)

Ratings: **bug** (wrong behaviour), **risk** (can go wrong in some conditions), **cleanup** (harmless, tidy up), **doc-only** (code is fine, the description needs updating).

#### Backend

| # | Where | Finding | Rating | Suggested action |
|---|---|---|---|---|
| B1 | `platform/rate_limit.clj` `reset-key!`; `accounts/api.clj` `user-eid`; `notifications/logic.clj` `daily-cap-reached?`; `orders/logic.clj` `problem-message`; route key `:no-input?` (set on `accounts/routes.clj` `reset-staff-password`, read only by `test/backend/zandy/architecture/routes_test.clj`) | Dead code: no caller in `src/` (checked by search). `:no-input?` has no effect in `platform/http.clj`; it is only an assertion marker for the N1 test. `platform/media.clj` `vips-available?` is likewise used only by a test. | cleanup | Delete, or put to use (see B6 for `reset-key!`); keep `:no-input?` but document it in `routes_test`. |
| B2 | `catalog/api.clj` `unique-violation?`, `cas-failed?`; `orders/routes.clj` `unique-conflict-on`, `admin-patch` | Errors are recognised by exception message text ("unique", "cas-failed", "cas"). In `catalog/api.clj` every "unique" error in `create-product!`/`update-product!` is reported as `skuAlreadyInUse`, so a slug race (the `:product/slug` attribute is unique too) would be misreported. In `admin-patch` any message containing the three letters "cas" counts as a version conflict. | risk | Inspect `ex-data` (`:db/error`) and the attribute named in it; or catch only the specific anomaly. Add a test for the slug race. |
| B3 | `engagement/routes.clj` `subscribe` | `catch Throwable` around `db/subscribe!` answers 200 `{subscribed true}` for any write failure (the comment admits it), including a database outage. | bug (low impact) | Catch only the unique-clash case; log and rethrow others. |
| B4 | `photos/imports.clj` `attach-by-sku!` | For rejected files the item has `:position nil` (and `:sku nil` for unmatched files) even when the file name carried a slot suffix. | cleanup | Return the requested slot if the contract wants it (`api-contract.md` 8.2), else document. |
| B5 | `platform/util.clj` `paginate` | Defaults page size to 24; `orders/routes.clj`, `catalog/routes.clj`, `engagement/routes.clj` pass 25 or 50 for staff lists, public lists use 24 via the schema/query. Two sources for the default. | cleanup | Make `paginate` require a size, or document the defaults per endpoint in the contract. |
| B6 | `accounts/routes.clj` `login` | A successful sign-in does not reset the `login-ip` / `login-email` counters (`reset-key!` exists but is unused). Failures stay counted for the whole 15-minute window, so 5 scattered failures can lock out a user who signed in between. | risk (low) | Decide: call `reset-key!` for the email key on success, or document the behaviour. |
| B7 | `notifications/api.clj` `process-once!` | If `db/mark-sent!` throws after `mailer/send!` succeeded, the `catch` counts a failed attempt, sets the message back to pending and a later pass may send it again (a duplicate email, at most 3 times). Documented as at-least-once. | risk | Separate the two steps: if only `mark-sent!` fails, log and retry the mark without incrementing attempts. |
| B8 | `notifications/db.clj` `try-claim!` | In the stale-claim branch the CAS already sets `claimed-at`; the trailing `[:db/add eid :outbox/claimed-at now]` repeats it (needed only for the pending branch). | cleanup | Move the add into the pending branch. |
| B9 | `accounts/bootstrap.clj` `bootstrap-owner!` | Uses `(Date.)` for `created-at` instead of the context clock; untestable with the test clock. | cleanup | Pass a `now` argument (callers: `system/::conn`, `-main`). |
| B10 | `seed/core.clj` `-main` | Builds a minimal `ctx` by hand (no `:mailer`, no `:user-summaries`) instead of `system/build-ctx`. `seed.core` cannot require `platform.system` (that namespace requires `seed.core`), which is the reason. Works today because the catalog functions the seed uses need only `:config :conn :clock :media-store :image-processor`. | risk (low) | Move `build-ctx` to a lower namespace, or comment the constraint at the call site. |
| B11 | `scripts/check/backend_deps.clj` `allowed` | Allows two edges beyond `architecture.md` 2.2: `accounts -> platform.rate-limit` and `orders -> features.site` (plus `util`, `validation`, `seed`). Accepted as amendment DA3 in `architecture-review.md`, but 2.2 has not been rewritten. | doc-only | Merge DA3 into `architecture.md` 2.2. |
| B12 | `scripts/check/backend_deps.clj` `check-attrs` | A text scan (`re-seq` over whole files): it also matches attribute-like keywords in docstrings and comments, so documentation may not name another slice's attributes (the docstring says so). | cleanup | Strip comments and strings before scanning, or keep the rule and mention it in the standards. |

#### Frontend

| # | Where | Finding | Rating | Suggested action |
|---|---|---|---|---|
| F1 | `shared/ui/core.cljc` `price-label` | An alias of `price` (identical body). | cleanup | Remove one name. |
| F2 | `frontend/state.cljs` `:nav/go`, `:nav/link` | Duplicate handlers (identical bodies). The only difference is in `main.cljs` `run-action!`, which special-cases `:nav/link` to let modified clicks (ctrl, meta, middle button, `target=_blank`) through. | cleanup | Keep both names but implement one in terms of the other, or document the split at the handler. |
| F3 | `frontend/state.cljs` `:cart/set-qty` | Clamps to 1..10 with the literal `10`, not the server's `maxQuantity` (stock and `max-quantity-per-line`). If the per-line limit changes in `config.edn`, the client and server disagree until the next validation. | risk | Use the `maxQuantity` from the last cart validation, falling back to a config value from site-info. |
| F4 | `frontend/state.cljs` `route-enter` | For `/checkout` with an empty cart it queues `:cart/validate` (a no-op for an empty cart), the delivery-areas request and `:agreement/load`, then also the redirect to `/cart`. The requests run needlessly (the code comment says so). | cleanup | Return early (only the redirect) when the cart is empty. |
| F5 | `frontend/admin/widgets.cljs` `max-images` | Duplicates `admin/state.cljs` `max-product-images` (6) and the backend's `catalog/logic.clj` `max-images`. Three copies of the same limit. | cleanup | Reference `admin.state/max-product-images` from the widget; keep the server copy authoritative. |
| F6 | `shared/routes.cljc` `url-encode` | The JVM encoder (`URLEncoder`) and browser (`goog.string/urlEncode`) may encode `~ ! ' ( ) *` differently. A listing URL containing such a character can differ between a server-built and a browser-built form; `ssr/pages/canonical-redirect` then redirects once to the server form. | risk (low) | Normalise both platforms to the same set (percent-encode those characters on the JVM), and add a cross-platform test in `test/shared/zandy/shared/routes_test.cljc`. |
| F7 | `ssr/api_client.clj` `fetch-all` | `(deref fut (+ timeout 500) nil)` returns nil on a stuck future; `parse` then throws `NullPointerException`, caught and reported as `{:status nil :error "NullPointerException"}` instead of a timeout. The page still degrades to the 503 page, but the log names the wrong cause. | cleanup | Use a sentinel timeout value and map it to `:error "TimeoutException"`. |
| F8 | `ssr/dev/zandy/ssr/stub_api.clj` `add-photo!` | Unused binding: the file-name argument `fname` is never used in the body. Dev-only. | cleanup | Remove the parameter or store it. |
| F9 | `frontend/state.cljs` `!state`; `ssr/dev/.../stub_api.clj` `db` | Two `defonce` vars documented with `^{:doc "..."}` metadata (a `defonce` takes no docstring argument), so the docstring is metadata, which clj-kondo and `doc` handle but readers may not expect. | doc-only | Keep; note it in a comment, or use `def` with `defonce`-like guards. |
| F10 | `scripts/check/frontend_deps.clj` `check-api-calls` | Scans every line, so `"/api/..."` text inside a docstring or comment is also checked against the contract headings. | cleanup | Strip docstrings and comments first, or accept and mention in the script header. |

#### Earlier known items

| # | Item | Where | Rating | Suggested action |
|---|---|---|---|---|
| E1 | The one-shot `-main` processes (`migrations`, `bootstrap`, `seed`, `mailer`) call `shutdown-agents` but may not exit against a real transactor (Datomic peer threads). Worked around in the deploy entrypoint, which wraps each in `System/exit`. Running `clojure -M:migrate` by hand against a real transactor may hang. | `deploy/docker/backend-entrypoint.sh` `run_task`; the four `-main` functions | risk (operations) | Add `(System/exit 0)` at the end of each `-main` (and 1 on failure) so the behaviour does not depend on the entrypoint. |
| E2 | No in-app libvips start-up check; done in the container entrypoint (`check_runtime`). `media/vips-available?` exists but is not called at boot. | `deploy/docker/backend-entrypoint.sh`; `platform/media.clj` | risk (low) | Optionally call `vips-available?` in `::conn` or a new component and log loudly when the prod profile lacks libvips. |
| E3 | The dev mailer hides reset links: `LogMailer` logs recipient count and subject only. Staging now uses a mail sink (`deploy/mailsink`, `scripts/ops/smtp_sink.clj`, `scripts/ops/show-mail.sh`). | `platform/mailer.clj`; `deploy/RUNBOOK.md` | doc-only | Local developers: read `mailer/sent-messages` from the REPL (`(:mailer (user/ctx))`) or run the sink. |
| E4 | Stored-response idempotency has a narrow crash window for non-order writes: `idempotency/complete!` is a separate transaction from the business write (DA2). A crash between the two leaves a claim that goes stale after 60 s, and a retry then repeats the write. Orders are protected because the key is also stored on the order inside the same transaction. | `platform/idempotency.clj`, `platform/http.clj` `wrap-idempotency`; `architecture-review.md` DA2 | risk (low) | Accept (documented), or write the record in the same tx for the few creates that matter (staff creation, product creation). |

---

## 8. Where the code and `architecture.md` disagree

Already ruled on (approved in `architecture-review.md` DA1 to DA10, but `architecture.md` itself has not been rewritten):

1. Libraries: no `muuntaja` and no `mulog` (DA1). JSON is `jsonista` (`platform/json.clj`), logging is the hand-written `platform/log.clj` with logback underneath. `platform.http` composes plain ring-style wrappers per route inside one Pedestal interceptor per route (`route-interceptor`), not separate Pedestal interceptors as D6 item 2 words it.
2. Extra backend edges (DA3): `util`, `validation`, `accounts -> rate-limit`, `orders -> site.api`, `seed`, `platform.log -> json`. See B11.
3. Extra frontend edges (DA5): `shared.pages`, `shared.json`, `admin.state/widgets/help/help-data`, `frontend.api -> shared.routes`, `frontend.effects -> shared.json, shared.seo`.
4. Browser start-up (DA7): the browser re-renders the same views and replaces the server markup; there is no true hydration.
5. Help text in the public bundle (DA10).

Found while writing this guide and not seen in the DA list:

6. Migration file format: `architecture.md` 3.3 describes `{:id :description :tx-data}` or `{:fn ...}`. The code supports `:tx-data`, `:dbfns [{:ident :file}]` and `:policy-files [...]` (`platform/migrations.clj`); there is no `:fn`.
7. Media serving: `architecture.md` 2.2 says `platform.media` serves `/media/{id}-{variant}.webp`. The route is in `features/photos/routes.clj` (`serve-media`); `platform/media.clj` only stores, opens and processes files.
8. Background jobs: `platform.system` has a `::jobs` component (outbox worker every 15 s; hourly idempotency clean-up, order and contact retention, outbox purge) that 2.2 does not list.
9. Asset manifest: 4.2 says ssr "reads `asset-manifest.edn` at boot". `SSR/static.clj` `current-manifest` re-reads it on every page request (deliberately, so a release is picked up without a restart); `:ssr/server` also reads it once for the context.
10. Build script: 4.2 says `build.sh` ends with `help.clj --check`. `scripts/build/build.sh` regenerates the help data first and has no `--check` step; staleness is covered by `help_build_test` and by running the check by hand.
11. Test namespace names: section 7 cites `zandy.contract-inventory-test`; the namespace is `zandy.architecture.contract-inventory-test` (file under `test/backend/zandy/architecture/`).
12. `dev/user.clj` exists (and 4.1 lists `dev/**`) but no alias puts `dev` on the classpath.
13. Honeypot field `website` (orders, subscriptions, contact) and the fake-order answer are in `api-contract.md` but not described in `architecture.md`.
14. Seed `-main` and `bootstrap` build their own ctx or use `(Date.)` (B9, B10), so "the only namespace that knows every component" holds, but not "every handler context comes from `system/build-ctx`".
