# Architecture: Zandy Touch

Inputs read in full: engineering-standards.md (including "Deployment topology" and the Reitit fallback), business-context.md, brand-brief.md, scope-checklist.md, design-spec.md, user-journeys.md, api-contract.md, visual-guide.md (section 11 and the seed image section), and the agent definitions in .claude/agents/. design-tokens.css (font paths only) and the assets/ layout were skimmed. Research date: 24 October 2026. This file replaces the earlier single-service design.

Summary:
1. Two stateless web processes and one datastore. `ssr` (frontend server, JVM Clojure, Pedestal, `replicant.string`) renders public pages from views shared with the browser bundle and reads data only through the backend HTTP API. `backend` (Pedestal, Datomic peer) owns all data and business rules and serves no HTML. One Datomic transactor with dev storage.
2. One public origin. An edge proxy routes `/api/*` and `/media/*` to the backend and everything else to `ssr`. A CDN in front caches by the Cache-Control headers each server sends. The proxy, CDN, provider and domain are deployment choices (section 8 and deployment-handover.md).
3. Every piece of shared state (sessions, rate limits, idempotency keys, stock, the email outbox) lives in Datomic, so any number of `backend` and `ssr` instances can run. Launch runs one of each; the memory budget per process is in D3 and what deployment must provide is in section 8.
4. Two builders with disjoint trees: backend-clojure (`src/backend`, root `deps.edn`) and frontend-cljs (`ssr/`, `src/frontend`, `src/shared`). Their only interface is api-contract.md.
5. Reitit with Pedestal is a time-boxed spike; the documented fallback is Pedestal 0.8's own router. Pedestal is never downgraded.

---

## 1. Context

### What the system must do
From the categories marked "applies" in scope-checklist.md:
- Catalog and inventory: six seeded categories, products with up to 6 photos, counted stock that cannot oversell, logged stock movements, archive instead of delete, bulk photo upload with SKU matching, seed sample products with CC0 images.
- Checkout (reduced form): guest order, cash or mobile money on delivery, no gateway, delivery fee recorded by staff later, explicit server-recorded acceptance of the versioned order terms, all in one atomic transaction.
- Accounts for staff only: owner and support, owner manages support accounts, password change, owner password reset by email.
- Back-office: orders (confirm, approve delivery, deliver, cancel with stock restore), products, stock, photos, contact messages, subscribers export.
- Search: category browse, substring text search, sort, availability filter.
- Notifications: email only, from and to the owner's Gmail address (business-context.md), optional customer receipt.
- Legal: four versioned policy documents with `reviewStatus`, and a launch gate that blocks indexing while any is a placeholder.

From engineering-standards.md: separate frontend and backend servers, SSR from shared Replicant views, both servers able to run several instances, shared state in the backend database, Datomic dev storage plus off-site backups accepted, 15 USD per month total; non-negotiable rules (server-side validation and authorization, no hardcoded secrets, versioned migrations, idempotent mutations, no micro-frontends); WCAG 2.1 AA; Integrant, Aero, Malli, Pedestal, Reitit (fallback allowed), Datomic.

### Constraints that shape everything else
| Constraint | Source | Consequence |
|---|---|---|
| One human owner plus two builder agents (backend-clojure, frontend-cljs) | standards, task | Two source trees, one contract, no shared editing. |
| Scale: hundreds of customers growing to thousands; a few hundred products; tens of orders a day. Mostly read-only browsing | business-context, scope-checklist | Reads are absorbed by the CDN and the SSR cache headers; writes are few. One transactor is plenty. |
| Hosting at most 15 USD per month in total, including both servers, the transactor and storage | business-context, standards | Small JVM heaps and no extra services (D1, D3). Everything must be able to run on one small server at launch. Provider, plan and domain are chosen at the deployment stage (section 8). |
| Several instances of each server must be possible | standards, owner decision 2 | No state in process memory; db-backed sessions, rate limits, idempotency, outbox claiming; migrations run as a separate step; media store must not be instance-local once the backend spans hosts (PE5). |
| Mobile first, expensive data, SEO | brand-brief, visual-guide, design-spec | Server-rendered public HTML with SEO tags in the first response, one small client bundle, WebP variants. |
| Cards uncommon in Uganda; no online payment | scope-checklist | No payment gateway, no card data. |
| A short backend restart is acceptable at launch | scope-checklist | Single instances at launch; rolling restarts become zero-downtime once a second instance runs. |

---

## 2. Components

Notation: `A -> B` means A depends on B. Dependencies point one way. No cycle exists in the lists below.

### 2.1 Runtime topology (what runs)
```
Browser
  -> CDN (honours origin Cache-Control)            [deployment choice]
  -> edge proxy (TLS, path routing, compression, load balancing) [deployment choice]
        /api/*, /media/*   -> backend  x N   (JVM, Pedestal, Datomic peer)
        everything else    -> ssr      x N   (JVM, Pedestal, replicant.string)
  ssr     -> internal proxy address -> backend x N   (HTTP API only)
  backend -> Datomic transactor (dev storage)
  backend -> MEDIA_DIR (photos) ; backend -> smtp.gmail.com (outbound only)
  ssr     -> STATIC_DIR (bundle, css, fonts, icons)
```
Processes at launch: the edge proxy, `backend`, `ssr` and the transactor, plus a staging set if the owner chooses to run staging alongside (section 8). The CDN, Gmail SMTP and the off-site backup destination are external services.

Why `ssr` reaches the backend through an internal proxy address (`API_BASE_URL`): the proxy already load-balances and health-checks the backend instances, so adding a backend instance changes only proxy configuration, not `ssr`. `ssr` never goes through the CDN for its own API calls, so what it renders is fresh backend data.

### 2.2 Backend components (owner: backend-clojure)
Source root `src/backend/zandy/`, project file the root `deps.edn`. The backend serves JSON and media only; it never renders HTML and does not use `src/shared`.

Platform (Integrant keys `zandy.platform.*`):

| Component | Single responsibility |
|---|---|
| `platform.config` | Read `resources/config.edn` with Aero (`#profile`, `#env`), validate with Malli, fail fast at boot. |
| `platform.log` | Structured JSON log events; the one place that decides which fields are safe to log. |
| `platform.datomic` | Own the peer connection. Provide `conn`, `db`, and `fresh-db` (`d/sync`, see section 5, multi-instance reads). |
| `platform.migrations` | Apply versioned schema, data and db-function migrations (3.3). Run by `clojure -M:migrate` before instances start; at boot the system only verifies the ledger is current (a dev/test profile may apply them automatically). |
| `platform.errors` | The only place that builds error responses. |
| `platform.json` | Muuntaja/jsonista with camelCase to kebab-case keys and the ISO 8601 UTC `Z` encoder. |
| `platform.http` | Build the Pedestal connector and the router from the route table. Common interceptors: request id (reuse inbound `X-Request-Id`), access log (route template only), security headers, error handling, Malli validation, rate limiting, idempotency, cache headers (from route data). No business logic. See D6 for the Reitit/Pedestal decision. |
| `platform.auth-interceptors` | Enforce `:access` (`:public`, `:staff`, `:owner`), `mustChangePassword` and CSRF from route data. |
| `platform.rate-limit` | Datomic-backed counters (section 5). Owns `:ratelimit/*`. |
| `platform.idempotency` | Check and record `Idempotency-Key` in Datomic. Owns `:idempotency/*`; exposes tx-data builders so features record the key in the same transaction as their own writes. |
| `platform.media` | `MediaStore` protocol (`local` directory implementation at launch, `s3` later) plus the image pipeline (libvips CLI). Builds the `/media/{id}-{variant}.webp` URLs and serves them with immutable headers. |
| `platform.mailer` | `Mailer` protocol; SMTP implementation (Angus Mail) configured for Gmail; a log-only implementation for dev and tests. |
| `platform.system` | Integrant system map and `-main`. The only namespace that knows every component. |

Feature slices (`zandy.features.<slice>`), each with `api.clj` (the only namespace other slices may require), `logic.clj` (pure), `db.clj` (queries and tx-data builders), `schema.clj` (Malli), `routes.clj` (route data and thin handlers). Extra files inside a slice are allowed.

| Slice | Responsibility | Contract |
|---|---|---|
| `site` | Business info and delivery areas from config. | 2 |
| `catalog` | Categories, products, product images, the photo entity, stock quantity and stock movements (public and staff product endpoints, stock adjustment and history, photo assignment, search/sort, sitemap entries). Owns `:category/*`, `:product/*`, `:product-image/*`, `:photo/*`, `:stock-movement/*`. | 2, 3, 8.1, 8.3, photo-assignments |
| `photos` | Photo library HTTP: bulk upload, list, delete. Uses `platform.media` and `catalog.api`. Owns no datoms. | 8.2 |
| `policies` | Read the four versioned policy documents (data delivered by migration), return the current one. Owns no writes (migrations write `:policy/*`). | 2 |
| `orders` | Cart validation, order placement (atomic), tracking, staff order list, detail, fee edit, status changes with stock restore. Owns `:order/*`, `:order-line/*`, `:order-event/*`, `:acceptance/*`. Owns the db functions `place-order` and `change-order-status` (the declared exception in B4: they also write `:product/stock-quantity`, `:stock-movement/*`, `:outbox/*`, `:idempotency/*` in the same transaction). | 3 cart-validations, 4, 7 |
| `accounts` | Staff users, sessions, login/logout, password change, owner password reset, staff management. Owns `:user/*`, `:session/*`, `:reset-token/*`. | 6, 10 |
| `engagement` | Subscriptions, unsubscribes, contact messages, subscriber CSV. Owns `:subscriber/*`, `:contact-message/*`. | 5, 9 |
| `notifications` | Email outbox: tx-data builders, the claiming worker, templates. Owns `:outbox/*`. | 9a |

Backend dependency list (allowed edges only; `scripts/check/backend_deps.clj` enforces exactly this):
```
platform.config            -> (none)
platform.errors, json      -> (none)
platform.log               -> config
platform.datomic           -> config, log
platform.migrations        -> datomic
platform.media             -> config, log
platform.mailer            -> config, log
platform.rate-limit        -> datomic, errors
platform.idempotency       -> datomic, errors
platform.auth-interceptors -> errors, features.accounts.api
platform.http              -> config, log, errors, json, rate-limit, idempotency,
                              auth-interceptors, routes of every feature slice
platform.system            -> every component
features.site              -> (none)
features.policies          -> (none)
features.notifications     -> platform.mailer
features.catalog           -> platform.media (url helper only)
features.photos            -> catalog.api, platform.media
features.accounts          -> notifications.api
features.orders            -> catalog.api, policies.api, notifications.api, accounts.api
features.engagement        -> notifications.api
```
Every feature may also use `platform.errors`, `platform.log`, `platform.datomic`, `platform.idempotency`. Cycle check: `site`, `policies`, `notifications`, `catalog` depend on no feature; `photos`, `accounts`, `engagement` depend only on those; `orders` depends on four others and nothing depends on `orders`. `platform.auth-interceptors` -> `accounts.api`, and `accounts` depends on no HTTP component. No cycle. (The earlier photos <-> catalog cycle stays removed: `catalog` owns the photo entity.)

Rules inside a slice: Pure Core (`logic.clj` requires no I/O namespaces), Imperative Shell (handlers fetch, decide, transact), other slices only through `<slice>.api`, only the owning slice writes an entity's attributes, except the db functions named above.

### 2.3 Frontend server components (owner: frontend-cljs)
Project `ssr/` with its own `ssr/deps.edn`; sources `ssr/src/zandy/ssr/`; it also puts `../src/shared` on its classpath. It never touches Datomic, and it keeps no request state.

| Component | Single responsibility |
|---|---|
| `ssr.config` | Aero + Malli config (`API_BASE_URL`, `SITE_BASE_URL`, `SITE_INDEXABLE`, `STATIC_DIR`, `PAGE_CACHE_*`, `API_TIMEOUT_MS`, `PORT`). |
| `ssr.log` | Structured JSON logs (same shape as the backend). |
| `ssr.api-client` | The only namespace that makes HTTP calls. `java.net.http.HttpClient` (JDK, no library), 3 s timeout, forwards or creates `X-Request-Id`, sends `Accept: application/json`, returns data or an error value. Parses JSON into keyword-keyed maps with the same camelCase keys the browser sees. |
| `ssr.pages` | For each page route, the API calls of api-contract.md section 12 (independent calls in parallel) and the assembly of page data. Pure mapping lives in `shared.pages`. |
| `ssr.render` | Calls `shared.document/render-page` and sets status and `Cache-Control` (from `PAGE_CACHE_*`, defaults from design-spec.md section 0), `ETag`, security headers including CSP. |
| `ssr.seo-routes` | `robots.txt` and `sitemap.xml` (from `GET /api/sitemap-entries`), environment-aware via `SITE_INDEXABLE` and `SITE_BASE_URL`. |
| `ssr.static` | Serve `STATIC_DIR` under `/static/` with the headers in section 5. |
| `ssr.launch-gate` | At boot with `SITE_INDEXABLE=true`, fetch the four policies and exit non-zero if any `reviewStatus` is not `approved` (retries the API for up to 60 s). |
| `ssr.http` | Pedestal connector: one catch-all handler that matches the path with the shared Reitit route table (`reitit.core/match-by-path`), plus `/static/*`, `/robots.txt`, `/sitemap.xml`, `/healthz`. Interceptors: request id, access log, error handling (503 page on API failure, `no-store`). Using Reitit only as a data matcher avoids any Reitit-Pedestal integration risk on this server. |
| `ssr.system` | Integrant system and `-main`. |

Dependency list:
```
ssr.config, ssr.log        -> (none)
ssr.api-client             -> ssr.config, ssr.log
ssr.pages                  -> ssr.api-client, shared.routes
ssr.render                 -> ssr.config, shared.document
ssr.seo-routes             -> ssr.api-client, ssr.config
ssr.static                 -> ssr.config
ssr.launch-gate            -> ssr.api-client, ssr.config
ssr.http                   -> ssr.pages, ssr.render, ssr.seo-routes, ssr.static, shared.routes
ssr.system                 -> every ssr component
```

### 2.4 Shared and browser components (owner: frontend-cljs)
Roots `src/shared/zandy/shared/` (`.cljc`, runs on the JVM inside `ssr` and in the browser) and `src/frontend/zandy/frontend/` (`.cljs`, browser only). The browser bundle is built once by shadow-cljs (`:app`) and served by `ssr` at `/static/js/`.

| Component | Responsibility |
|---|---|
| `shared.routes` | Page route table (Reitit data): name, path, `:ssr?`, `:access`. |
| `shared.format`, `shared.cart`, `shared.seo` | Pure formatting, cart functions, head tags. |
| `shared.ui.*`, `shared.pages.*` | Replicant components and the public page views (P1 to P17), pure functions of page data. |
| `shared.document` | `render-page`: full HTML document via `replicant.string`, including head tags, `<script id="initial-state" type="application/json">` (with `<` escaped) and the script/CSS links. Called only by `ssr.render`. |
| `frontend.main`, `frontend.state`, `frontend.effects`, `frontend.api`, `frontend.router`, `frontend.cart-store` | Browser entry, single state atom with data-only actions, effects shell, the only `js/fetch` user (relative URLs under `/api`, no base URL), client navigation, localStorage cart. |
| `frontend.admin.*` | Admin area A1 to A12, client-rendered only. |
| `frontend.scenes.*` | Portfolio scenes; not reachable from `frontend.main`. |

Dependency list:
```
shared.format, shared.cart -> (none)
shared.routes              -> reitit-core
shared.seo                 -> shared.format
shared.ui.*                -> shared.format
shared.pages.*             -> shared.ui.*, shared.format
shared.document            -> shared.routes, shared.pages.*, shared.seo, replicant.string
frontend.api               -> (none beyond js/fetch)
frontend.cart-store        -> shared.cart
frontend.router            -> shared.routes
frontend.state             -> shared.cart, shared.format
frontend.effects           -> frontend.api, frontend.router, frontend.cart-store
frontend.admin.*           -> shared.ui.*, shared.format
frontend.main              -> shared.routes, shared.pages.*, frontend.admin.*, frontend.state, frontend.effects
frontend.scenes.*          -> shared.ui.*, shared.pages.*, frontend.admin.*
```
`src/shared` must not require `zandy.frontend.*`, `zandy.ssr.*`, `zandy.backend.*`, `datomic`, `io.pedestal`, and must not use `js/`. The browser does hydrate by replacing `#app` with identical markup if true takeover is unavailable (the spike from D2 stands: frontend-cljs confirms how Replicant's DOM renderer takes over server markup; the design does not depend on it). No cycle.

### 2.5 How a public page is produced
1. A request reaches the CDN. A hit within `s-maxage` (or the stale window) is answered there.
2. On a miss, the edge proxy sends it to an `ssr` instance. `ssr.http` matches the path with the shared route table.
3. `ssr.pages` calls the backend API (via `API_BASE_URL`, the internal proxy address) for the data listed in api-contract.md section 12, in parallel, with a 3 s timeout.
4. `shared.document/render-page` returns the HTML with title, meta description, canonical, Open Graph, JSON-LD, the body, and the embedded initial state.
5. The browser paints the HTML; browsing, GET search forms and pagination links work without JavaScript. `frontend.main` then renders the same views over `#app` from the embedded state. Later navigation, cart, checkout, tracking and admin call `/api/*` directly (same origin, so the `zt_session` cookie works and no CORS exists).
6. If the API is unreachable or returns 5xx, `ssr` returns HTTP 503 with the calm error page, `Retry-After` and `Cache-Control: no-store`.

---

## 3. Data model

### 3.1 Datastore
Datomic (named in the standards): Datomic Pro peer library in each `backend` instance, one transactor with embedded `dev` storage (accepted for launch by the owner), `datomic:mem://` for tests and local development. Reasons and the multi-instance interaction are in D3 and section 5. `ssr` has no datastore.

Conventions: every entity has `:x/id` (uuid, `db.unique/identity`, from `d/squuid`); kebab-case namespaced attributes, camelCase only on the wire (`platform.json`); money is `:db.type/long` UGX; instants are `:db.type/instant`; enumerations are `:db/ident` keywords; optimistic concurrency by `:db/cas` on `:product/version` and `:order/version` (409 `versionConflict`). High-churn operational attributes (`:ratelimit/*`, `:idempotency/*`, `:session/last-seen-at`, `:outbox/attempts`, `:outbox/claimed-*`) are declared `:db/noHistory true`, because Datomic otherwise keeps every past value forever.

### 3.2 Entities, relationships and write owners
| Entity | Key attributes | Relationships | Writes owned by |
|---|---|---|---|
| `category` | id, slug, name, description, sort-order | has many products | `catalog` (seeded by migration) |
| `product` | id, slug, sku (unique, optional), name, brand, own-brand?, price (nullable while draft), short-description, description, size, how-to-use, ingredients, featured?, status (draft, published, archived), first-published-at, stock-quantity (long, never negative), version, created-at, updated-at | category (ref), updated-by (ref user), images | `catalog`; `stock-quantity` also written by the `orders` db functions (B4 exception) |
| `product-image` | id, position, alt | product, photo (refs) | `catalog` |
| `photo` | id, original-filename, content-type, width, height, uploaded-at, seed? | uploaded-by (ref user) | `catalog` (called by `photos`) |
| `stock-movement` | id, type (initial, adjustment, order, orderCancelled), reason, delta, quantity-after, note, at | product (ref), by (ref user, nil for system), order (ref, optional) | `catalog` (adjustments, initial); `orders` db functions (order, orderCancelled) |
| `order` | id, number (unique `ZT-XXXXXX`), status, placed-at, customer-name, customer-phone (E.164), customer-email, preferred-contact, delivery-country/city/area/address-line/directions, payment-method, payment-method-used, note, staff-note, delivery-fee (nullable), delivery-fee-set-by/at, cancel-reason, stock-restored?, version, updated-at, idempotency-key | order-lines (components), order-events, one acceptance | `orders` |
| `order-line` | product-id (uuid), product-name, unit-price, quantity | order | `orders` |
| `order-event` | id, status, at, note | order, by (ref user, nil for customer) | `orders` |
| `acceptance` | id, policy-type, policy-version, content-hash, accepted-at, customer-name, customer-phone | order | `orders` |
| `policy` | type, version, title, review-status (placeholder, approved), summary, blocks (EDN text), content-hash, effective-from, current? | none | migrations only (`policies` reads) |
| `user` | id, name, email (unique), role, password-hash, active?, must-change-password?, notify-on-new-order?, created-at, last-sign-in-at | referenced by events, movements, products | `accounts` |
| `session` | id-hash (SHA-256 of cookie value), created-at, last-seen-at, expires-at, revoked? | user | `accounts` |
| `reset-token` | token-hash, expires-at, used-at | user | `accounts` |
| `subscriber` | id, email, phone, consent-text-version, source, subscribed-at, active? | none | `engagement` |
| `contact-message` | id, name, phone, email, message, received-at | none | `engagement` |
| `outbox` | id, kind, to, subject, body-text, status (pending, claimed, sent, failed), attempts, claimed-by, claimed-at, created-at, sent-at, last-error | none | `notifications`; created inside the `orders` db functions (B4 exception) |
| `ratelimit` | key (unique, e.g. `login-ip:1.2.3.4`), count, window-end, locked-until | none | `platform.rate-limit` |
| `idempotency` | scoped-key (unique, "METHOD path key"), body-hash, result-kind, result-id, created-at | none | `platform.idempotency` |
| `migration` | id (unique), checksum, applied-at | none | `platform.migrations` |

Notes:
- Order totals are derived, never stored. `availability` and `maxQuantity` are derived from `stock-quantity` and `maxQuantityPerLine`. The low-stock threshold (5) and per-line maximum (10) come from config (design-spec Q15).
- Policies are data in Datomic, loaded by migrations from `resources/policies/<type>/<version>.edn` (blocks as data, so no markdown parser exists anywhere). Approving a text after human review is a new migration that sets `review-status` to `approved`; a changed text is a new version with a new hash.
- Search is in-memory substring filtering of the published set in `catalog.logic` (a few hundred products; Datomic fulltext would give whole-word semantics that do not match the contract).
- Data protection: Datomic keeps history, so erasure requests use excision, documented in a runbook by backend-clojure (open question Q9).

### 3.3 Versioned migrations with Datomic
- Files `resources/migrations/NNNN-description.edn`, each `{:id :description :tx-data [...]}` or `{:id ... :fn ...}` for data changes. Db functions (transaction functions) are installed by migrations from `resources/migrations/dbfns/*.clj` and use only `clojure.core`, because the transactor runs them.
- `platform.migrations` keeps a ledger of `:migration/*` entities; each migration and its ledger entry go in one transaction. It refuses to run if an applied file's checksum changed, a ledger id has no file, or numbers have a gap.
- Production and staging apply migrations with `clojure -M:migrate` as an explicit step before any instance starts (deployment-engineer's deploy script). Instances check at boot that the ledger is current and refuse to start otherwise. This avoids two instances racing to migrate.
- Schema is defined nowhere else. The same runner builds `datomic:mem` in every test.

### 3.4 Where files live
- Photos: `MEDIA_DIR` on the server disk through the `MediaStore` protocol. `originals/` private, `public/{photoId}-{thumb|medium|large}.webp` public. Several backend instances on the same host share the directory. A backend instance on another host needs the `s3` implementation (PE5).
- Seed images and manifest: `resources/seed/images/` (files `seed-<sku>-<n>.jpg`) with `resources/seed/images/manifest.edn` (machine-readable: file, sku, alt, source URL, author, licence, date retrieved) and `resources/seed/images/SOURCES.md` (human-readable view of the same facts). Owner: backend-clojure, who sources the images (CC0 or public domain only, per visual-guide.md "Seed images"). `clojure -M:seed` (alias in the root `deps.edn`, code in `src/backend/zandy/seed/`) is idempotent by SKU, writes the 22 published products and 1 draft of design-spec.md section 9 through the same `catalog` and `photos` functions the API uses, stores the images through `MediaStore` marked `seed? true`, and writes `initial` stock movements. It is run on staging and, before launch, on production (so the shop is browsable), and the launch gate removes it. Seed data contains no secrets.
- Static files (bundle, CSS, fonts, icons, logo): `STATIC_DIR` on the `ssr` host (4.2). Policy texts: `resources/policies/`.
- Everything else lives in Datomic.

---

## 4. Ownership

Two builders, one tree each. Anything not listed is read-only for builders. Every path has exactly one owner.

### 4.1 Map
| Path | Owner | Notes |
|---|---|---|
| `deps.edn` (root) | backend-clojure | Backend only. `:paths ["src/backend" "resources"]`. Aliases `:run`, `:test` (adds `test/backend`), `:migrate`, `:bootstrap-owner`, `:seed`, `:send-test-mail`. |
| `src/backend/**`, `test/backend/**`, `dev/**` | backend-clojure | Section 2.2. |
| `resources/**` | backend-clojure | `config.edn`, `migrations/**` (including `dbfns/`), `policies/**`, `seed/**` (including images and manifest). The policy texts are supplied by the owner (open question Q8); backend places them. |
| `.clj-kondo/config.edn` | backend-clojure | Root lint config for backend; its banned-namespace list may only grow. |
| `scripts/check/backend_deps.clj`, `scripts/check/seed_manifest.clj` | backend-clojure | Babashka fitness scripts (section 7). qa runs them and does not author them. |
| `ssr/**` (`deps.edn`, `src/`, `test/`, `resources/`, `.clj-kondo/`) | frontend-cljs | The frontend server (2.3). Aliases `:run`, `:test` (adds `ssr/test`, `../test/shared`). |
| `src/frontend/**`, `src/shared/**`, `test/frontend/**`, `test/shared/**`, `styles/**` | frontend-cljs | Browser app, shared views and their tests, page and component CSS using only `var(--...)` from design-tokens.css. |
| `shadow-cljs.edn`, `package.json`, `package-lock.json` | frontend-cljs | Builds `:app`, `:portfolio`, `:test`. `:dependencies` lists ClojureScript libraries, `:deps false`. Dev dependencies only in npm. |
| `scripts/build/**`, `scripts/check/frontend_deps.clj` | frontend-cljs | `assets.clj` (4.2); the frontend fitness script. |
| `Dockerfile.backend`, `Dockerfile.ssr`, `deploy/**`, `scripts/ops/**`, CI config | deployment-engineer | Whatever its chosen hosting needs (proxy config, service definitions, transactor properties, environment templates with variable names only, CDN rules, backup and restore, smoke scripts, `deploy/COSTS.md`). Never edits application source. |
| `design-tokens.css`, `assets/**`, `scripts/design/**`, `visual-guide.md`, `brand-brief.md` | visual-designer | Read-only to builders. |
| `design-spec.md`, `user-journeys.md`, `scope-checklist.md` | ux-designer | Read-only to builders. |
| `api-contract.md` | architect amends (ux-designer drafted) | Builders request changes by message. |
| `engineering-standards.md`, `business-context.md`, `architecture.md`, `architecture-review.md` | the human owner / architect | Read-only to builders. |
| `target/`, `ssr/target/`, `media/`, `.cpcache/`, `.shadow-cljs/`, `node_modules/` | generated, git-ignored | Never committed. |

`deps.edn`, `ssr/deps.edn` and `shadow-cljs.edn` are separate files so the two builders never edit the same one. `qa` runs the checks and writes only its own reports. Cross-builder changes go through api-contract.md and a message to the architect.

### 4.2 Static assets and design-tokens.css (visual-guide.md section 11)
- Public URLs: `/static/design-tokens.css`, `/static/assets/fonts/...`, `/static/assets/{logo,icons,badges}/...`, `/static/assets/favicon.svg` and the PNG icons and `site.webmanifest`, `/static/css/...`, `/static/js/main.<hash>.js`. The `@font-face` URLs are relative (`assets/fonts/x.woff2`) and resolve correctly because the tokens CSS and `assets/` stay side by side under `/static/`. No file in design-tokens.css needs editing.
- `bb scripts/build/assets.clj` (frontend-cljs) copies `design-tokens.css`, `assets/` and `styles/**` into `target/static/` (CSS files get a content hash in the file name; an `asset-manifest.edn` maps logical names to hashed names and `ssr` reads it at boot), and never modifies sources. shadow-cljs writes hashed bundles to `target/static/js`. Build: `npm ci && npx shadow-cljs release app && bb scripts/build/assets.clj`.
- Release layout: `Dockerfile.ssr` image contains the `ssr` classpath; the static files are copied by the deploy script into the host directory `STATIC_DIR`. The deploy script adds new files and keeps files from earlier releases for at least 14 days, so a CDN-cached HTML page that still names last release's bundle never gets a 404 (check R5).
- `/favicon.svg` may also be served at the root by `ssr` for crawlers.

---

## 5. Cross-cutting decisions

| Topic | Decision | Reason |
|---|---|---|
| One origin and routing | Edge proxy: `/api/*` and `/media/*` to the backend, all other paths (including `/static/*`, `/healthz`, `robots.txt`, `sitemap.xml`) to `ssr`. The proxy adds an `X-Upstream: backend\|ssr` response header for checking. A CDN sits in front; routing stays in the proxy. | Same-origin means no CORS, the session cookie works, CSP `connect-src 'self'` holds, and the frontend needs no build-time API URL. See D2. |
| Browser to API | Relative `/api/...` URLs only. `ssr` reads `API_BASE_URL` at runtime (the internal proxy address). | Removes the build-time `API_BASE_URL`; one build for all environments. |
| Authentication | Staff email + password, Argon2id (Password4j). Server-side session in Datomic: 256-bit random cookie `zt_session` (HttpOnly, Secure, SameSite=Lax, `Path=/api`, no Domain), only its SHA-256 stored; 12 h sliding, 7 d absolute; `last-seen-at` written at most every 5 minutes. | Revocable sessions are required by the contract. `Path=/api` keeps the cookie off public page requests so they stay cacheable. |
| Authorization | Each route's data declares `:access` (`:public`, `:staff`, `:owner`); `platform.auth-interceptors` enforces it; a route without it fails a startup assertion. `mustChangePassword` and CSRF in the same interceptor. | Non-negotiable server-side authorization, testable by enumerating the route table. |
| CSRF | Token = HMAC(`SESSION_HMAC_SECRET`, session id), returned by login and `GET /api/auth/sessions/current`, sent as `X-CSRF-Token`. | Nothing to store; works on any instance. |
| Owner bootstrap | `clojure -M:bootstrap-owner` creates the owner from `OWNER_EMAIL` and `OWNER_INITIAL_PASSWORD`, `mustChangePassword` true. Never in seed data or migrations. | No secrets in the repo (launch gate 3). |
| Error shape | `{ "error": { "code", "message", "details"? } }`, one constructor in `platform.errors`. `ssr` HTML error pages are separate and never contain API internals. | One shape everywhere (D8 for `details`). |
| Validation | Malli in each slice's `schema.clj`, closed maps, applied before handlers. `ssr` validates its config and normalises `/shop` and `/categories/*` query parameters with Malli (unknown parameters dropped so they cannot pollute CDN cache keys). | Non-negotiable boundary validation. |
| Configuration | Aero `#profile` + `#env`, validated at boot. Backend env: `PORT`, `DATOMIC_URI`, `SESSION_HMAC_SECRET`, `OWNER_EMAIL`, `OWNER_INITIAL_PASSWORD` (bootstrap only), `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASSWORD`, `MAIL_DAILY_CAP`, `MEDIA_DIR`, `SITE_BASE_URL`, `CLIENT_IP_HEADER`, and the optional public details (`SITE_PHONE`, `WHATSAPP_NUMBER`, `PUBLIC_EMAIL`, `SHOP_ADDRESS`, `OPENING_HOURS`). SSR env: `PORT`, `API_BASE_URL`, `SITE_BASE_URL`, `SITE_INDEXABLE`, `STATIC_DIR`, `PAGE_CACHE_*`, `API_TIMEOUT_MS`. | Aero is required; values outside the repo satisfy no-secrets. Staging and production differ only by values. |
| Logging | Structured JSON lines to stdout in both servers; request id, method, route template (never the raw path), status, duration; errors with context; field allowlist keeps passwords, tokens, phones and emails out. The proxy must not log query strings for `/admin/reset-password`. | Required structured logging, privacy-safe. |
| Request id | The edge proxy or `ssr` creates `X-Request-Id`; `ssr` forwards it to the backend; both log it; every response echoes it. | One id from edge to datastore. |
| Idempotency | `Idempotency-Key` stored in Datomic (`:idempotency/*`, unique scoped key) in the same transaction as the business write; replays rebuilt from the stored result id; 24 h retention; one-time secrets never replayed. `place-order` is a single db function, so duplicates, stock and acceptance are decided atomically on the transactor and any instance can handle the retry. | Non-negotiable; works across instances with no lock or cache. |
| Orders and stock | `place-order` and `change-order-status` are transaction functions: check price, status and stock, write order, lines, stock decrements, stock movements, acceptance, history and outbox together, or write nothing (409 `cartOutdated`). | Atomic across instances, no oversell, no partial order. |
| Sessions, rate limits, outbox claims | All in Datomic (3.2). Rate limits: fixed windows via a transaction function; the check reads first and rejects without writing once the limit is reached, so a flood causes at most `limit` writes per key per window. Outbox: the worker claims a message with `:db/cas` (pending to claimed with `claimed-by`, `claimed-at`); a claim older than 5 minutes may be re-claimed. At-least-once sending. Daily clean-up of expired keys is safe to run on every instance. | Owner decision 2. In-memory limits would multiply by instance count and reset on restart. |
| Multi-instance reads | Each instance is a Datomic peer. A peer sees other peers' writes within milliseconds but not instantly. Lookups by session id, reset token, idempotency key and order number that find nothing call `d/sync` once and retry. | Read-your-writes across instances without sticky sessions. |
| Migrations | Separate pre-deploy step (3.3). | No boot-time race between instances. |
| Payments and bookings | No online payment. Orders record the customer's preference and, on delivery, the method used. No bookings. | business-context. |
| File storage | Photos on `MEDIA_DIR` via `MediaStore`; variants 480/800/1200 px WebP quality about 80, EXIF orientation applied and metadata stripped, by the libvips CLI (at most 2 concurrent jobs); type by magic bytes; limits from the contract. Served by the backend at `/media/...` with `public, max-age=31536000, immutable`. | Meets the contract and visual-guide with no extra service (D5). |
| Email | Gmail SMTP with an app password from env vars, behind the `Mailer` protocol (D7). From address and alert recipient are both `OWNER_EMAIL`. Sent from the outbox after commit by whichever instance claims the message. Password-reset mails are sent inline and never stored. Failures are logged and never fail a request. | Owner decision 5; contract section 9a. |
| Rate-limit client identity | The edge proxy sets `X-Client-Ip` from the verified client address (the CDN's client-IP header when the connection comes from the CDN's published ranges, else the TCP peer) and strips any inbound value; the backend reads only the header named in `CLIENT_IP_HEADER`. | Clients cannot spoof their address. |
| Security headers | `ssr` HTML: CSP `default-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`. Backend JSON: `nosniff`. HSTS at the edge proxy. | Enforces "frontend never calls third parties". |
| Caching | Table in D2 (headers); CDN requirements in section 8. Every response states its own `Cache-Control`; errors are `no-store`. Cookies are never set on cacheable responses. | Contract section 1.5 and design-spec section 0 are the source. |
| SEO plumbing | `ssr` generates `robots.txt` and `sitemap.xml`. With `SITE_INDEXABLE=false`: `Disallow: /`, `X-Robots-Tag: noindex`, empty sitemap. Admin, cart, checkout, order-placed, track-order, unsubscribe carry `noindex`. | Meets the staging rule without static files. |
| Launch gate | `ssr` refuses to start with `SITE_INDEXABLE=true` while any policy is `placeholder` (`ssr.launch-gate`). | Scope-checklist proposal accepted: an unreviewed text cannot go live unnoticed. |
| Health | Backend `GET /api/healthz` checks the Datomic connection; `ssr` `GET /healthz` checks the process and one cheap backend call. The edge proxy uses them for upstream health checks. | Rolling restarts without dropping requests. |

---

## 6. Decisions

### D1. Two services and one datastore, no extra infrastructure
Chosen: `ssr`, `backend`, one transactor. No queue, cache server, search engine or session store. Requirement for the second server: standards "Deployment topology" (independent scaling and caching). Rejected: Redis for sessions and limits (an extra process and cost; Datomic holds them with `noHistory`); a message queue for email and images (the outbox plus in-request image work is enough at tens of orders a day); Elasticsearch (a few hundred products).

### D2. Frontend server runtime, origin routing and CDN caching
**Frontend server runtime: a small JVM Clojure service** (Pedestal 0.8 + `replicant.string` + Reitit data matching), owned by frontend-cljs.
- Why: it renders the same `.cljc` views the browser uses, it is the one runtime the project already needs (the backend is a JVM), tooling is one `clojure` command, the dependencies are all on the required list, and the JDK's own HTTP client covers the API calls. Cost: about 0.4 to 0.5 GB of RAM per instance.
- Rejected: Node SSR from the ClojureScript build (second runtime on the server; a Node process is cheaper in RAM, so this is the fallback if memory ever binds; the views are shared code either way); a static prerender at build time (product pages change daily and prices and stock must be current); a headless-browser prerender service (extra service and cost); client-only rendering with tags set after load (fails the SEO rule in the first HTML); Datomic access from the frontend server (forbidden by the standards, and it would make the contract meaningless).
- Standards check: Pedestal is listed for "basic services"; Datomic is not used here. Treated as consistent; confirm in Q10.

**Origin routing: one public origin, routed by an edge proxy.**
- Rule: `/api/*` and `/media/*` to the backend; everything else to `ssr`. api-contract.md assumed one origin; confirmed.
- Rejected: a separate API origin (`api.zandytouch.com`) needs CORS, cross-site cookie rules and a build-time base URL; no proxy at all (two public ports and two certificates).
- The proxy is also the load balancer for both server pools: a second instance is one more upstream with a health check. Which proxy is a deployment choice.

**CDN caching, using api-contract.md section 1.5 and design-spec.md section 0.**
The application states its caching through headers; the CDN only honours them. What the CDN must do is in section 8: honour origin `Cache-Control` (including `s-maxage`, `stale-while-revalidate`, `stale-if-error`, `no-store`), bypass the cache for `/api/admin`, `/api/auth`, `/admin` and any request carrying a `zt_session` cookie, and use no hardcoded edge TTL. Because every origin response carries an explicit `Cache-Control` (and `no-store` on private or error responses), the origin stays in charge.

| Response | Sent by | Cache-Control |
|---|---|---|
| Home, shop, category, product pages | `ssr` | `public, max-age=0, s-maxage=60, stale-while-revalidate=120, stale-if-error=86400` (see conflict below) |
| About, contact, delivery, legal | `ssr` | `public, max-age=0, s-maxage=600, stale-while-revalidate=600, stale-if-error=86400` |
| Document shells (cart, checkout, order-placed, track-order, unsubscribe, admin) | `ssr` | `no-cache` with ETag |
| 404 page | `ssr` | `public, max-age=0, s-maxage=60` (a 404 for an unknown product may become valid) |
| 503 error page, any 5xx | `ssr` | `no-store` |
| `/static/js/*`, `/static/css/*`, `/static/design-tokens.*.css`, `/static/assets/fonts/*` | `ssr` | `public, max-age=31536000, immutable` |
| `/static/assets/{logo,icons,badges}/*`, favicons, `site.webmanifest` | `ssr` | `public, max-age=86400, stale-while-revalidate=604800` (PE3) |
| `robots.txt`, `sitemap.xml` | `ssr` | `public, s-maxage=3600` |
| Public API GETs and `/media/*` | backend | exactly the table in api-contract.md section 1.5 |
| `/api/healthz`, `/healthz`, everything private | both | `no-store` |
Added value: `stale-if-error` lets the CDN keep serving catalogue pages for a day while the backend or `ssr` is down or restarting (a proposed addition to design-spec.md section 0).

Conflict, flagged and not resolved: design-spec.md section 0 gives HTML `s-maxage=60, stale-while-revalidate=120`, which allows a page up to about 180 seconds old, while user-journeys.md and api-contract.md section 1.5 promise "about 90 seconds". The lifetimes are configuration (`PAGE_CACHE_*`), so the default follows design-spec.md until the owner or ux-designer decides; my recommendation is `s-maxage=60, stale-while-revalidate=30` (Q6). Also: `ssr` fetches from the backend internally, not through the CDN, so what is cached is current as of the render.

### D3. Runtime footprint and Datomic layout
The application is designed so that everything can run on one small server at launch and within the 15 USD ceiling. Which server, provider and plan is a deployment decision (section 8).

**Memory budget per process** (targets the code must fit; deployment-engineer measures and may adjust):
| Process | Heap (`-Xmx`) | Expected RSS | Notes |
|---|---|---|---|
| Datomic transactor | 1024m | ~1.3 GB | `memory-index-max` 256m, `object-cache-max` 128m; minimum heap not verified, measure |
| `backend` | 768m | ~1.1 GB | `-Ddatomic.objectCacheMax=256m`; libvips jobs add up to ~0.3 GB |
| `ssr` | 256m | ~0.5 GB | |
| `backend` staging (if co-located) | 512m | ~0.8 GB | database `zandy-staging` on the same transactor |
| `ssr` staging (if co-located) | 192m | ~0.4 GB | |
Production alone is about 2.9 GB RSS plus about 0.5 GB for the OS and edge proxy; with a co-located staging set, about 5.3 GB in total. Suggested flags: `-XX:+UseSerialGC` for the two small services, `-Xss512k`, `-XX:MaxMetaspaceSize=192m`.

**Scaling order** (what the design allows, in order of cost):
1. CDN cache hit rate (lifetimes and rules). The biggest lever because most traffic is read-only.
2. A second `ssr` instance behind the proxy.
3. `ssr` on its own server. Static files are deployed to every `ssr` host.
4. A second `backend` instance. On the same host no change is needed (shared `MEDIA_DIR`). On another host the transactor must listen on a reachable address (`host`/`alt-host`) behind a firewall allowing only backend hosts on ports 4334 and 4335, and `MediaStore` must switch to the `s3` implementation (PE5).
5. Beyond that: Datomic on a different storage and a standby transactor.

**Datomic layout:** one transactor with dev storage (accepted for launch by the owner), databases `zandy` and, if staging is co-located, `zandy-staging`; each backend instance is a peer. Writes all go through the one transactor, which is also what makes `place-order` atomic across instances; adding backend instances scales reads only, and each peer needs about 1 GB of RAM. Dev storage on one machine is a single point of failure and Datomic documents it as not recommended for production. Required mitigation (section 8): nightly `bin/datomic backup-db`, an off-site copy of the backup and of `MEDIA_DIR`, and a tested restore. Retention: 7 daily and 4 weekly sets.
What I checked: Datomic Pro is free of licence fees and Apache 2.0 since April 2023; remote peers on dev storage need `storage-access=remote` with two passwords and connect with `datomic:dev://host:4334/db?password=...`; many peers may connect; one transactor is active, and a standby needs non-dev storage.

Rejected: Datomic Local (Client API only, tests would not match production, directory-copy backups); Datomic Pro on PostgreSQL storage (a second database process and memory, and against the spirit of "no SQL for application data").

### D4. Multi-instance state
Sessions, rate limits, idempotency keys, stock, outbox claims and policies are in Datomic (3.2, section 5). Nothing request-related is held in memory in either server. `ssr` is stateless; the browser cart is in localStorage. Interaction with Datomic: every instance is a peer with its own cache and no peer talks to another; all writes serialise on the one transactor (an advantage for stock atomicity, a limit for write scaling, which is not needed); peers can lag by milliseconds (hence `d/sync` on misses); `noHistory` stops operational counters from bloating history; rate-limit checks read before they write, so a flood does not become a write flood on the transactor. Restarts: with two backend instances, the proxy's health checks give rolling zero-downtime deploys; with one, a 20 to 30 s gap is covered by `stale-if-error` for GET pages.
Rejected: in-memory rate limits (multiply by instance count, reset on restart); signed stateless cookies (cannot revoke); sticky sessions at the proxy (would hide state bugs, and the owner asked for stateless).

### D5. Image resizing with the libvips CLI
Unchanged from the earlier design: `vipsthumbnail` and `vipsheader` from `platform.media` (libvips is actively maintained; it streams, so memory stays low; the exact WebP strip option is tested in B17). Rejected: JVM libraries that are archived, native-only for x86 or unproven (`webp-imageio`, `webp4j`), and Thumbnailator (no WebP writer). Installed as the OS package in `Dockerfile.backend`.

### D6. Reitit with Pedestal: spike and fallback (backend)
What I checked: Pedestal 0.8.x is current (0.8.1 stable, 0.8.2 betas). Its release notes say 0.8 introduced connectors replacing the service map, a default Sawtooth router that prefers literal paths over parameters and reports conflicts, and that "in theory you can swap out routing in 0.8, in practice 0.8 binds routing a little tighter than before" (routing now also serves WebSocket upgrades and asset requests). Pedestal's docs allow a custom router. The reitit-pedestal documentation (0.9.1) still shows examples with Pedestal 0.5.5 and `replace-last-interceptor`, and I found no 2026 compatibility statement.
Decision for the backend spike (backend-clojure's first task, time-boxed to one session):
1. Each slice's `routes.clj` defines routes as plain data (method, path, name, `:access`, Malli `:parameters`, `:idempotent?`, cache policy, handler). `platform.http/route-table` returns this normalised table. All fitness checks (N1, N2, B9, R2) read that table, so they pass under either router.
2. Spike: serve that table through Reitit (via the Pedestal 0.8 custom `:router` option) with Pedestal 0.8's interceptors, error handling and a request-id interceptor. Pass means: path and query matching, 404 and 405 handled by `platform.errors`, and the `:access` data visible to the interceptor.
3. **Fallback if the spike fails: Pedestal 0.8's own router (Sawtooth, table syntax) fed from the same table, with a small Malli validation interceptor in `platform.http`. Do not downgrade Pedestal.** The contract's route rule (no static sibling beside a `{param}` route) stays as written: Sawtooth would tolerate such siblings, but the rule costs nothing and keeps the router swappable.
4. backend-clojure records the outcome and the reason in a comment at the top of `platform.http` and tells the architect, who updates this decision.
Frontend: `shared.routes` stays Reitit data (matching only, no Pedestal integration), so it is not affected by the spike.
Rejected: downgrading Pedestal (forbidden by the standards); swapping to ring-based Reitit (not allowed).

### D7. Email through Gmail SMTP
Design: `Mailer` protocol; SMTP implementation with Angus Mail to `smtp.gmail.com`, port 465 (SSL) or 587 (STARTTLS), username = `OWNER_EMAIL`, password = a Google **app password** (`SMTP_PASSWORD`), `From` = `OWNER_EMAIL`. Alerts go to the same address.
What I checked: Google's app passwords need 2-Step Verification on the account; a personal Gmail account may send about 500 recipients in a rolling 24 hours (a message to two staff counts as two), and exceeding it suspends sending for up to 24 hours; SMTP allows up to 100 recipients per message (Nylas and serversmtp summaries of Google's limits).
Limits to accept:
- Cap: the worker stops at `MAIL_DAILY_CAP` (default 400) and leaves messages pending, so Google's block is never triggered.
- Deliverability: Gmail signs mail it sends with its own domain keys, so mail from `@gmail.com` is authenticated, but it shows the personal address, customer receipts may land in spam, and Google may block the first login from a new server IP or end the app password if the Google password changes (the owner then has to approve the sign-in or create a new app password).
- An alert sent to the sender's own mailbox still arrives in the inbox; the owner can add a Gmail filter on the subject prefix `[Zandy Touch]`.
- When zandytouch.com exists, a transactional provider with SPF/DKIM on the domain can replace Gmail by changing `SMTP_*` and `OWNER_EMAIL` only (the protocol hides it).
Rejected: sending directly from the server (poor deliverability); a vendor HTTP API (lock-in); a broker.

### D8. `error.details` extension
Accepted, additive, limited to `validationFailed`, `cartOutdated`, `agreementVersionOutdated`. The shape is a guideline, so this is a stated deviation (PE2).

### D9. Layout
Standards suggest `src/features/<slice>/`. Backend slices are `src/backend/zandy/features/<slice>/`; the frontend server is a separate project `ssr/`; browser code is `src/frontend`; shared views are `src/shared`. Reason: two builders, three runtime targets and disjoint trees (PE1).

### D10. Release and rollback
Each server is built into its own image. Backend deploy: run `clojure -M:migrate` against the target database, then restart instances one at a time. `ssr` deploy: add the new static files to `STATIC_DIR`, then restart instances one at a time. Migrations are additive, so the previous backend image still works against the new schema for one release, which gives a rollback path.

### D11. Extra libraries (policy: named, one line each; no approval list)
Required libraries are used for their stated purpose: Replicant (views and string rendering), Pedestal (both servers), Reitit (backend routing unless D6 falls back; shared route table and client navigation), Integrant, Aero, Malli, Datomic Pro peer, Portfolio, shadow-cljs, Cognitect test-runner, babashka. Additional:
| Library | Why |
|---|---|
| `metosin/muuntaja` + `jsonista` | JSON encoding and decoding (the `ssr` API client also uses `jsonista`). |
| `com.password4j/password4j` | Argon2id password hashing on the JVM. |
| `org.eclipse.angus/angus-mail` | SMTP sending (Jakarta Mail implementation). |
| `com.brunobonacci/mulog` | Structured events as data with JSON output (backend-clojure confirms the JSON publisher or writes a short one with jsonista). |
| `ch.qos.logback/logback-classic` | SLF4J backend so Pedestal and Datomic logs are quiet and structured. |
| `java.net.http.HttpClient` (JDK) | The `ssr` API client; no library. |
| libvips CLI (OS package) | Image variants, installed in the backend image. |
frontend-cljs expects to need no further libraries and names any it adds.

### D12. Seed data and images
Seed lives in the backend (owner decision 7): `resources/seed/**`, loaded by `clojure -M:seed` (3.4), images and manifest under `resources/seed/images/`. Rejected: a database migration for seed products (they are sample data, to be archived, not schema); pulling images at run time from any photo service (the backend definition and visual-guide.md forbid it); hotlinking.

---

## 7. Fitness checks

Conventions: run from the project root. Tools assumed on the machine: `rg` (ripgrep), `curl`, `clojure`, `bb`, `clj-kondo`, `docker` (for images). qa confirms with `command -v` first. "No output" means a pass. Backend tests: `clojure -M:test` at the root (one namespace: `clojure -M:test -n <ns>`). `ssr` tests: `cd ssr && clojure -M:test`. Local run for HTTP checks: backend `clojure -M:run` (dev profile, in-memory Datomic, seeded) on 8080, then `cd ssr && API_BASE_URL=http://127.0.0.1:8080 clojure -M:run` on 8081. Items that need something not yet written are marked **(to be built by owner)** with a path; qa runs them and does not author them.

### 7.1 Non-negotiable rules
| # | Rule | Check | Pass |
|---|---|---|---|
| N1 | Server-side validation at the boundary | `clojure -M:test -n zandy.architecture.routes-test` **(backend-clojure, `test/backend/zandy/architecture/routes_test.clj`)**: walks `platform.http/route-table`; fails if any `/api` route with body, query or path parameters lacks Malli `:parameters` (or an explicit `:no-input? true`); posts an invalid body to every mutating route and asserts 422 `validationFailed` or 400, never 500. | 0 failures |
| N2 | Server-side authorization | Same namespace: every route has `:access`; the `:public` set equals the allowlist derived from api-contract.md sections 2 to 6; every `:staff`/`:owner` route returns 401 without a cookie; an `:owner` route returns 403 for a support session; a staff mutation without `X-CSRF-Token` returns 403 `csrfInvalid`. | 0 failures |
| N3 | No hardcoded secrets | (a) `rg -n -i '(secret\|password\|api[-_]?key\|token\|smtp[-_]?pass\|uri)' resources/config.edn ssr/resources \| rg -v '#env'` (b) `rg -n --pcre2 -i '(secret\|password\|apikey\|api-key\|token)\W{1,4}"[A-Za-z0-9+/_=.-]{12,}"' src ssr resources dev scripts deploy Dockerfile.backend Dockerfile.ssr` (c) `git ls-files \| rg '(^\|/)\.env\|\.pem$\|\.key$'` | All three: no output |
| N4 | Versioned migrations | (a) `rg -n ':db/(ident\|valueType\|cardinality\|unique)' src ssr/src dev test scripts` no output (schema only in `resources/migrations`). (b) `git log --diff-filter=M --name-only --format= -- resources/migrations` no output after the first release. (c) `clojure -M:test -n zandy.platform.migrations-test` **(backend-clojure)**: applies all migrations to `datomic:mem`, ledger lists every file, re-run is a no-op, a changed checksum aborts, a numbering gap aborts, and booting with a stale ledger refuses to start. | (a), (b) no output; (c) 0 failures |
| N5 | Idempotent mutations | `clojure -M:test -n zandy.architecture.idempotency-test` **(backend-clojure)**: for every endpoint in api-contract.md 1.6: missing key gives 400 `missingIdempotencyKey`; same key and body twice creates one record and replays with `Idempotent-Replayed: true`; same key and a different body gives 409 `idempotencyKeyReused`; two concurrent identical requests create one record. For `POST /api/orders`: one order, one acceptance, one set of stock movements, one outbox entry per recipient. | 0 failures |
| N6 | No micro-frontends | (a) `rg -n '^\s+:[a-z-]+\s+\{:target' shadow-cljs.edn` lists only `:app`, `:portfolio`, `:test` (the frontend server is JVM code, not a second shadow build). (b) `:app` has one module (`rg -c ':modules' shadow-cljs.edn` at most 1). (c) after `npx shadow-cljs release app`, `ls target/static/js \| rg -c '^main\.'` is 1. (d) `rg -n -i 'module-federation\|single-spa\|single_spa\|importmap' package.json shadow-cljs.edn src ssr/src` no output. | as stated |

### 7.2 Boundaries and design rules
| # | Rule | Check | Pass |
|---|---|---|---|
| B1 | Banned libraries | `clj-kondo --lint src/backend test/backend dev` (root config) and `cd ssr && clj-kondo --lint src ../src/shared ../src/frontend test`; `rg -n -i '(reagent\|re-frame\|"react"\|rum/rum\|compojure\|ring-jetty\|ring/ring-jetty\|mount/mount\|environ\|plumatic\|prismatic\|next\.jdbc\|java\.jdbc\|honeysql\|postgres\|mysql\|sqlite)' deps.edn ssr/deps.edn shadow-cljs.edn package.json` | kondo exits 0; rg no output |
| B2 | Slice boundaries, no layer folders | `bb scripts/check/backend_deps.clj slices` **(backend-clojure)**: fails if slice A requires `zandy.features.B.<anything but api>`; `find src ssr/src -type d \( -name controllers -o -name services -o -name models \)` | exit 0; find prints nothing |
| B3 | Allowed edges and no cycles | `bb scripts/check/backend_deps.clj graph` **(backend-clojure)** compares backend namespace edges to the list in 2.2 and checks acyclic. `bb scripts/check/frontend_deps.clj graph` **(frontend-cljs, `scripts/check/frontend_deps.clj`)** does the same for `ssr`, `src/shared`, `src/frontend` against 2.3 and 2.4, and fails if `src/shared` requires `zandy.frontend`, `zandy.ssr`, `zandy.backend`, `datomic`, `io.pedestal`. | exit 0 both |
| B3b | Backend serves no HTML and ssr never touches the database | `rg -n -i 'zandy\.(shared\|ssr\|frontend)\|replicant\|text/html' src/backend deps.edn` no output. `rg -n -i 'datomic' ssr src/shared src/frontend --glob '!**/*.md'` no output. | no output |
| B4 | Write ownership of entities | `bb scripts/check/backend_deps.clj attrs` **(backend-clojure)**: for each attribute namespace in 3.2, the keyword may appear in `src/backend` only under the owning slice or platform component. The two db-function files `resources/migrations/dbfns/place_order.clj` and `change_order_status.clj` are the only allowlisted multi-entity writers. | exit 0 |
| B5 | Pure core | `rg -n '(datomic\.api\|clojure\.java\.(io\|shell)\|zandy\.platform\.(http\|mailer\|media\|datomic)\|java\.net\|slurp\|spit)' src/backend --glob '**/logic.clj'` | no output |
| B6 | Thin handlers | `rg -n 'datomic\.api' src/backend --glob '**/routes.clj'` | no output |
| B7 | Single error constructor | `rg -n '(:status\s+[45][0-9]{2}\|response\s+[45][0-9]{2})' src/backend --glob '!**/platform/errors.clj'` | no output |
| B8 | camelCase keys, ISO 8601 `Z` instants, error shape | `clojure -M:test -n zandy.contract-conformance-test` **(backend-clojure)**: runs every contract endpoint on in-memory Datomic and checks keys `^[a-z][A-Za-z0-9]*$`, timestamps under `*At`, `at`, `effectiveFrom` match `^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ$`, and every error body matches `{error:{code, message, details?}}`. | 0 failures |
| B9 | Route inventory equals the contract | `clojure -M:test -n zandy.contract-inventory-test` **(backend-clojure)**: compares `### METHOD /path` headings in api-contract.md with `route-table` (method, path with `{param}` normalised). | equal both ways |
| B9b | Frontend calls only contract endpoints | `bb scripts/check/frontend_deps.clj api-calls` **(frontend-cljs)**: extracts every `/api/...` path literal from `src/frontend/**/api.cljs`, `ssr/src/**/api_client.clj` and `ssr/src/**/pages.clj`, and checks each matches a contract heading. | exit 0 |
| B10 | Frontend never calls third parties or holds secrets | (a) `rg -n 'https?://' src/frontend src/shared ssr/src \| rg -v 'schema\.org\|www\.w3\.org\|wa\.me'` no output. (b) `rg -l '(js/fetch\|XMLHttpRequest\|js/WebSocket)' src/frontend src/shared` prints only `src/frontend/zandy/frontend/api.cljs`. (c) `rg -l '(java\.net\.http\|clj-http\|hato)' ssr/src` prints only `ssr/src/zandy/ssr/api_client.clj`. (d) `rg -n -i '(api[-_]?key\|secret\|bearer )' src/frontend src/shared ssr/src` no output. (e) with `ssr` running: `curl -sI http://127.0.0.1:8081/ \| rg -i 'content-security-policy'` contains `connect-src 'self'`. | as stated |
| B11 | `src/shared` is JVM-safe and uses tokens | `rg -n 'js/' src/shared` no output; `cd ssr && clojure -M:test` runs `test/shared`; `rg -n '#[0-9a-fA-F]{3,8}\b' styles src/frontend src/shared` no output. | as stated |
| B12 | Structured logging, no stray printing | `rg -n '\((println\|prn\|pr\|print\|printf\|tap>)\s' src/backend ssr/src`; `rg -n 'js/console\.(log\|debug)' src/frontend src/shared`; `clojure -M:test -n zandy.platform.log-test` **(backend-clojure)** and `cd ssr && clojure -M:test -n zandy.ssr.log-test` **(frontend-cljs)**: force a 500, assert one JSON line with request id and context, and that login and order logs contain no password or phone. | no output; 0 failures |
| B13 | No environment-specific literals | `rg -n 'localhost\|127\.0\.0\.1\|zandytouch\.com' src ssr/src --glob '!**/scenes/**'` | no output |
| B14 | SEO head in the raw SSR HTML | With backend and `ssr` running: for `/`, `/shop`, `/categories/skincare`, `/products/<seed-slug>`, `/about`, `/contact`, `/delivery-and-payment`, `/legal/terms-of-sale`, `/legal/order-terms`: `curl -s http://127.0.0.1:8081<path>` and `rg -c` each of `'<title>'`, `'name="description"'`, `'rel="canonical"'`, `'property="og:title"'`, `'application/ld\+json'`, `'<h1[ >]'`. | every count is 1 |
| B15 | robots, staging and launch gate | With `SITE_INDEXABLE=false`: `curl -s .../robots.txt` equals `User-agent: *` / `Disallow: /`, responses carry `X-Robots-Tag: noindex`, sitemap empty. With `true` (policies approved): robots references the sitemap, `sitemap.xml` lists product URLs on `SITE_BASE_URL`. `cd ssr && clojure -M:test -n zandy.ssr.launch-gate-test` **(frontend-cljs)**: with a stub API returning a `placeholder` policy and `SITE_INDEXABLE=true`, startup fails; with all approved, startup succeeds. | as stated; 0 failures |
| B16 | Static assets and fonts resolve | After the build: `curl -s -o /dev/null -w '%{http_code} %{content_type}\n' http://127.0.0.1:8081/static/assets/fonts/inter-latin-400.woff2` is `200 font/woff2`; the hashed tokens CSS, `/static/assets/logo/logo-horizontal.svg` and `/static/js/main.<hash>.js` (name from `asset-manifest.edn`) return 200. After `bb scripts/build/assets.clj` **(frontend-cljs)**: `git status --porcelain assets design-tokens.css` is empty. | 200s; clean status |
| B17 | Image pipeline | `vipsthumbnail --version` and `vipsheader --version` succeed; `clojure -M:test -n zandy.platform.media-test` **(backend-clojure)**: a JPEG with GPS EXIF yields three WebP files at 480/800/1200 px (or the original width if smaller) with no `exif-` fields; a 6001 px file, a 5.1 MB file, a text file renamed `.jpg` and a 21-file request are rejected with the contract's codes. | 0 failures |
| B18 | Client bundle budget (proposed target) | `gzip -c target/static/js/main.*.js \| wc -c` | at most 250000 |
| B19 | Shared dependency versions agree | `bb scripts/check/frontend_deps.clj versions` **(frontend-cljs)**: versions of `no.cjohansen/replicant` and `metosin/reitit-core` equal in `ssr/deps.edn` and `shadow-cljs.edn`. | exit 0 |
| B23 | Seed manifest complete | `bb scripts/check/seed_manifest.clj` **(backend-clojure)**: every file in `resources/seed/images/` has a `manifest.edn` entry with file, sku, alt, source URL, author, licence in {CC0, Public Domain}, date retrieved; every published seed SKU has at least one image (two for `SEED-SK-01`, `SEED-PF-01`); `SOURCES.md` lists the same files; no file is missing from disk. `clojure -M:test -n zandy.seed-test` **(backend-clojure)**: after the seed on `datomic:mem`, 22 published and 1 draft product, every SKU starts `SEED-`, each category has at least 3, one product has stock 0, one has stock at or below 5, opening stock is written as `initial` movements, seed photos are marked `seed?`. | exit 0; 0 failures |
| B24 | Stock cannot oversell | `clojure -M:test -n zandy.orders.place-order-concurrency-test` **(backend-clojure)**: 20 concurrent orders for a product with stock 5 produce exactly 5 orders, stock 0, 15 `cartOutdated`, and stock movements that sum to the stock. | 0 failures |

### 7.3 Topology, multi-instance and edge
| # | Rule | Check | Pass |
|---|---|---|---|
| R2 | Cache headers match section 6 D2 and the contract | `scripts/ops/check-cache-headers.sh <base-url>` **(deployment-engineer, run on staging)**: requests each row of the D2 table and the api-contract.md 1.5 table, compares `Cache-Control`, and asserts that cacheable responses have no `Set-Cookie` and no `Vary: Cookie`, that private and error responses are `no-store`, and that a stopped backend yields an `ssr` 503 with `no-store`. | exit 0 |
| R3 | Pedestal not downgraded; router recorded | `rg -n 'io.pedestal/[^ ]+ \{:mvn/version "0\.[0-7]\.' deps.edn ssr/deps.edn` no output; `clojure -M:test -n zandy.platform.http-test` **(backend-clojure)** passes with the router chosen in D6. | no output; 0 failures |
| R4 | No shared in-process state | `rg -n '^\(def(once)?\s+\S+\s+\(atom' src/backend ssr/src` no output; `rg -n '\(atom\b\|ConcurrentHashMap' src/backend/zandy/platform/rate_limit.clj src/backend/zandy/platform/idempotency.clj src/backend/zandy/features/accounts src/backend/zandy/features/notifications` no output. | no output |
| R5 | Two instances of each server work together | `scripts/ops/two-instance-smoke.sh` **(deployment-engineer, run on staging)** starts two `backend` instances (8080, 8090) on one transactor and two `ssr` instances (8081, 8082) behind a local test proxy, then: logs in on A and reads `/api/auth/sessions/current` on B immediately; places an order on A with key K and replays on B (`Idempotent-Replayed: true`, one order); exhausts a login rate limit on A and sees 429 on B; checks exactly one send per outbox message with the log mailer; confirms both `ssr` instances return identical HTML for the same URL. | exit 0 |
| R9 | Email configuration | `clojure -M:test -n zandy.platform.config-test` **(backend-clojure)**: the prod profile fails without `OWNER_EMAIL`, `SMTP_USER`, `SMTP_PASSWORD`; `From` equals `OWNER_EMAIL`; the daily cap is applied. `clojure -M:send-test-mail` (alias in root `deps.edn`, **backend-clojure**) sends one message to `OWNER_EMAIL`; qa runs it only when the owner has supplied the app password. | 0 failures; message received |

---

## 8. What deployment must provide

This is the boundary between architecture and deployment. These are requirements on the deployment, not choices. deployment-engineer researches how to meet them, presents the options and lets the owner choose (provider, plan, domain, CDN, backup destination, payment method); see deployment-handover.md.

Requirements:
1. **Processes:** `backend` x N, `ssr` x N, one Datomic transactor (dev storage), an edge proxy. JVM 21 or later. libvips CLI available to the backend.
2. **Memory:** the per-process budget in D3. Total hosting at most 15 USD per month including VAT, domain (per year divided by 12) and storage.
3. **One public origin** with path routing as in section 5; an internal address for `ssr` to reach the backend (`API_BASE_URL`); `X-Upstream` and `X-Client-Ip` headers set by the proxy as in section 5; HSTS.
4. **CDN** that honours origin `Cache-Control` (including `s-maxage`, `stale-while-revalidate`, `stale-if-error`), bypasses the cache for `/api/admin`, `/api/auth`, `/admin` and requests with a `zt_session` cookie, and adds no hardcoded edge TTL.
5. **Persistent disk** for `MEDIA_DIR` and Datomic dev storage.
6. **Backups:** nightly Datomic `backup-db` and `MEDIA_DIR`, copied off the server, with a tested restore; 7 daily and 4 weekly sets.
7. **Environments:** staging and production differ only in environment values (section 5, Configuration). Staging has `SITE_INDEXABLE=false` and its own database. Migrations run (`clojure -M:migrate`) before instances start (3.3).
8. **Static files:** deployed to `STATIC_DIR` on every `ssr` host, keeping earlier releases' files for at least 14 days (4.2).
9. **Secrets** only in the server environment: `SESSION_HMAC_SECRET`, `OWNER_INITIAL_PASSWORD`, `SMTP_PASSWORD` (Gmail app password), Datomic passwords.

Acceptance checks that deployment-engineer writes and runs (moved here from section 7 because they test the deployment, not the code):
| # | Check | Pass |
|---|---|---|
| D-1 | Path routing: `scripts/ops/check-routing.sh <base-url>`: `/api/site-info` and `/media/<known>-thumb.webp` return `X-Upstream: backend`; `/`, `/shop`, `/static/design-tokens.<hash>.css`, `/robots.txt`, `/healthz` return `X-Upstream: ssr`; `/api/healthz` returns 200. | exit 0 |
| D-2 | Memory plan: `bb scripts/ops/sum_xmx.clj` sums every `-Xmx` in `deploy/**` and the Dockerfiles and compares with D3. | within D3 plus any measured, recorded adjustment |
| D-3 | Backups restore: `scripts/ops/backup.sh` and `scripts/ops/restore-test.sh` restore into a scratch database name and compare order, product and media counts with live. | counts equal |
| D-4 | Cost ceiling: `deploy/COSTS.md` lists every recurring item with price, term, date checked and source URL, plus the domain per year divided by 12. | total at most 15 USD |
| D-5 | CDN honours lifetimes: `scripts/ops/check-edge-cache.sh <https-base-url>`: second fetch of `/shop` and `/api/products` is a cache hit with `Age` below the stated lifetime; admin, auth and cookie requests bypass. | exit 0, or the measured minimum TTL reported |
| D-6 | Previous bundle survives a deploy: `scripts/ops/check-static-retention.sh`. | 200 |
| D-7 | Environment values: staging `SITE_INDEXABLE=false` and `DATOMIC_URI` naming `zandy-staging`; production names `zandy`; `SITE_BASE_URL` set per environment. | as stated |
| D-8 | Two instances: check R5 and R2 from section 7, run on staging. | exit 0 |

## 9. Open questions for the owner (application)

Deployment choices (provider, plan, domain, CDN account, backup destination, payment method, staging placement) are asked at the deployment stage; see deployment-handover.md.

Q3. Gmail as the sender (owner decision; D7). Accept the 500-recipient-a-day limit, the Gmail address shown to customers, possible spam-folder placement of receipts, and the rule that a Google password change ends the app password? May the Gmail address also be shown publicly (design-spec Q11)?

Q5. Photos on the server disk only (PE5). Several backend instances share it on one machine; a second backend host needs object storage. Accept this limit for launch? Keep original uploads (about 2.5 GB for 500 photos at 5 MB), or delete them after the variants exist?

Q6. Page staleness. design-spec.md section 0 allows catalogue pages to be up to about 180 seconds old; user-journeys.md and api-contract.md say about 90 seconds. I recommend `s-maxage=60, stale-while-revalidate=30`. Which one? Also approve `stale-if-error` (a day) so a backend restart or outage does not blank the shop.

Q7. Logo and icon cache lifetime (PE3). visual-guide.md suggests one-year immutable caching for logo, icons and badges, but their file names are not versioned and you will supply a vector logo to replace the recreation, so I set one day. Agree?

Q8. Policy texts. The four texts must be supplied and reviewed by a person; the shop's frontend server refuses to start with indexing on while any is a placeholder. Who writes and reviews them?

Q9. Data retention and erasure. Datomic keeps history; a deletion request needs excision. Do you want a retention rule (for example removing order contact details after a period)?

Q10. Frontend server on the JVM. The standards list Pedestal for "basic services" and ClojureScript/shadow-cljs for the frontend; I read "frontend server" as a small Clojure service that uses no Datomic. Confirm that reading.

Existing design-spec.md questions Q1 to Q15 remain open and are not resolved here. Q14 (Gmail) is answered in design by D7.

---

## 10. Research notes and sources

- Pedestal and Reitit: [Pedestal 0.8.0 release](https://clojureverse.org/t/pedestal-0-8-0-released/14736), [Pedestal 0.8 routing guide](https://pedestal.io/pedestal/0.8/reference/routing-quick-reference.html), [reitit-pedestal doc 0.9.1](https://cljdoc.org/d/metosin/reitit/0.9.1/doc/http/pedestal), [pedestal.service versions](https://cljdoc.org/versions/io.pedestal/pedestal.service).
- Replicant: `replicant.string/render` renders on the JVM; current coordinate `no.cjohansen/replicant {:mvn/version "2026.07.1"}`; the README does not describe hydration. [Replicant on GitHub](https://github.com/cjohansen/replicant).
- Datomic: [peer-to-transactor connection and multiple peers](https://docs.datomic.com/operation/deployment.html), [dev storage and remote peers](https://docs.datomic.com/operation/storage.html).
- Gmail SMTP: [Nylas Gmail SMTP settings](https://developer.nylas.com/docs/cookbook/email/gmail-smtp-settings/), [serversmtp Gmail limits 2026](https://serversmtp.com/limits-of-gmail-smtp-server/).
- Hosting, domain, CDN and backup-storage research moved to deployment-handover.md (non-binding).
- Not verified: Datomic's minimum transactor heap, whether Replicant's DOM renderer takes over server markup, the mulog JSON publisher option, and whether libvips' WebP save strips metadata with the exact option I assume. Each has a measurement or a test above.

---

## 11. Changes made to api-contract.md

All made in place.
- C1 (1.1): the single-origin routing is now stated as decided, with the routing rule (`/api/*` and `/media/*` to the backend; every other path, including `/static/*`, `/healthz`, `robots.txt`, `sitemap.xml`, to the frontend server); removed the sentence offering separate origins; stated that the browser calls relative `/api/...` URLs, that `API_BASE_URL` is a runtime value read only by the frontend server, and that no build-time API URL exists.
- C2 (1.1): client IP for rate limiting comes from the header named by `CLIENT_IP_HEADER`, set by the edge after verification (clients cannot supply it); replaces "the edge's `X-Forwarded-For`".
- C3 (1.4, 1.5, 11, 12): the backend health endpoint is now `GET /api/healthz`. The old `GET /healthz` collided with the frontend server's own `/healthz` (section 12) once routing is by path.
- C4 (1.2): the route-design note is no longer described as Reitit-only (D6 may fall back to Pedestal's router); the rule itself is unchanged.
- C5 (1.5): added "error responses (4xx, 5xx) are `no-store`" and that conditional requests (`If-None-Match`) get 304; added a note that a CDN does not honour a request-level `Cache-Control: no-cache`, so the product page's re-check can be up to about 90 seconds old and the real stock check remains cart validation and order placement; added that the frontend server fetches from the backend internally, so rendered pages are as fresh as the page lifetime.
- C6 (1.7): `zt_session` is set with `Path=/api` and no `Domain`, so public page requests carry no cookie and stay cacheable.
- C7 (9, subscribers): `unsubscribeUrl` is built from the configured `SITE_BASE_URL`, not a fixed `https://zandytouch.com`, so staging and production differ only by configuration.
- C8 (9a): mail is sent by exactly one instance per message (outbox claim); added the Gmail-specific daily cap behaviour (`MAIL_DAILY_CAP`, remaining messages stay pending) and that password-reset mail is also sent from the owner address.
- C9 (12): static paths are `/static/...` (visual-guide.md section 11); added `stale-if-error` to the SSR page cache note; stated that the frontend server calls the backend's internal URL and not the CDN; added the launch gate (the frontend server refuses to start indexable while any policy is a placeholder); `/healthz` described as the frontend server's own.

Checked and left unchanged: naming (camelCase, resource nouns, `/api` prefix), error shape and codes, idempotency table and scoping (works across instances because keys are in the database), pagination, money, id types, photo upload, policy documents, order and stock flows. They are consistent with this design.

## 12. Proposed exceptions (for owner approval)
Nothing departs from a non-negotiable rule.
- PE1. Source layout: `src/backend/zandy/features/<slice>/`, a separate `ssr/` project, `src/frontend`, `src/shared` instead of `src/features/<slice>/` (guideline; reason in D9).
- PE2. `error.details` on three error codes (guideline; D8).
- PE3. Logo, icons and badges cached for one day instead of the one-year immutable lifetime suggested in visual-guide.md section 11, because the names are not versioned and the logo is due to be replaced.
- PE4. Interpretation, to confirm: the frontend server is JVM Clojure with Pedestal, within the "basic services" use of Pedestal and without Datomic (Q10).
- PE5. Partial exception to owner decision 2: backend instances can share photos only on the same host (`MediaStore` `local`). Running a backend on a second host requires the `s3` implementation and an object-storage account (Q5). Reason: an object store needs a payment card and extra code that no stated requirement needs at launch.
- Not exceptions but vendor guidance: Datomic dev storage in production (accepted by the owner); a single transactor remains a single point of failure.
