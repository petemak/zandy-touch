# Architecture review: Zandy Touch (MODE 2)

Reviewed at commit 1a3b47d (working tree equals the commit apart from `tutorial/prompts.org`). Inputs: architecture.md, api-contract.md, engineering-standards.md, business-context.md ("Owner decisions"), scope-checklist.md, design-spec.md (A7 to A13), admin-help.md, and the code under `src/backend`, `src/shared`, `src/frontend`, `ssr/`, `resources/`, `scripts/`, `test/`, `dev/`.

Method and limit. I read the code; I did not run it. Shell access was not available in this session, so no test, script or server was executed and every "conforms" below is from reading the file named. The mechanical checks of architecture.md section 7 still have to be run by qa; section 12 below lists the ones I could not judge from the source. The one external lookup I made: the Replicant source (`replicant.dom/render` clears the container on its first render: `(set! (.-innerHTML el) "")`, "Replaces any pre-existing content not created by this function").

Edits I made: the two wording fixes in architecture.md section 7 (N6(b), B10(a)) and the api-contract.md amendments listed in section 13. No code was edited. architecture.md was not otherwise changed; where the design text is out of date, the amendment is stated here for the owner (section 11).

## 1. Summary

| | Conforms | Deviates | Not assessable without running servers |
|---|---|---|---|
| Components (section 2) | 39 | 15 | 0 |
| Boundaries and fitness rules (section 3) | 28 | 1 | 5 (B14, B16, B18, R2, R5) |

Every deviation is either an accepted design amendment (section 4) or a small code change that does not block qa (section 10). Nothing departs from a non-negotiable rule. Nothing blocks qa (section 12), but three of qa's checks (N6(b), B10(a), and the `--check` step of 4.2) were mis-worded or unreachable in the design and are now corrected or restated.

## 2. Components: one line each

Backend platform (`src/backend/zandy/platform/`)
- config: conforms. `config.clj` Aero + Malli, `prod-problems` adds prod-only rules, fails with a list of problems and never values.
- log: deviates. `log.clj` is a 38-line JSON logger, not mulog. Design should change (DA1). Advisory: `exception-data` logs `.getMessage`, which can carry values (section 10, H2).
- datomic: conforms. `datomic.clj` `fresh-db` (`d/sync`), `transact!` translates db-function failures to API errors.
- migrations: conforms. `migrations.clj` ledger, checksum, gap and missing-file checks, `verify-current!`; writes `:policy/*` for the policy documents as architecture 3.2 already says ("migrations only"). The reported "extra edge" is only a labelling issue in `scripts/check/backend_deps.clj` (see 4.3).
- errors: conforms. `errors.clj` is the only constructor (B7 pattern finds no `:status 4xx/5xx` elsewhere).
- json: deviates. `json.clj` is jsonista only, no muuntaja (DA1). Keys and `Z` instants are right.
- http: deviates. `http.clj` uses Pedestal's own router (D6 conforms) but JSON, validation and multipart are hand-written wrappers (DA1), and it requires `ring.middleware.multipart-params` from ring-core, which is only a transitive dependency (H4).
- auth-interceptors: conforms. `auth_interceptors.clj` enforces `:access`, `mustChangePassword`, CSRF; startup assertion for a missing `:access` is in `http.clj` `assert-route-table!`.
- rate-limit: conforms. `rate_limit.clj` reads first, writes through the `ratelimit_hit` db function.
- idempotency: deviates. `idempotency.clj` and `http.clj` `wrap-idempotency` store the response and claim in a separate transaction (DA2).
- media: conforms. `media.clj` `MediaStore` protocol, libvips CLI with `[Q=80,strip]`, magic-byte detection, limits.
- mailer: conforms. `mailer.clj` protocol, SMTP and log implementations.
- system: conforms. `system.clj` is the only namespace that knows every component. Start-up diagnostics are weak (section 9, H3).
- util: deviates (new component). `util.clj` shared pure helpers (hashing, paging, client IP). Not in the design list (DA3).
- validation: deviates (new component). `validation.clj` Malli details, phone and email rules. Not in the design list (DA3).

Backend slices (`src/backend/zandy/features/`)
- site: conforms. `site/routes.clj` config-only plus `/api/healthz`.
- catalog: deviates. `catalog/api.clj` uses the whole `platform.media` pipeline, not only the URL helper, because it owns the photo entity (DA3); staff names come through the `ctx` function `:user-summaries` (DA3).
- photos: conforms. `photos/routes.clj`, `imports.clj`, `csv.clj` (pure core), `logic.clj` (pure); all writes go through `catalog.api`; no datoms owned.
- policies: conforms. `policies/api.clj`, `db.clj` read only.
- orders: deviates. `orders/routes.clj` requires `site.api` (city list), `platform.util`, `platform.validation` (DA3); it rebuilds the idempotency scope string by hand (lines 102 and 133), duplicating `http.clj` (H1).
- accounts: deviates. `accounts/routes.clj` requires `platform.rate-limit` for the per-email login lockout; `bootstrap.clj` requires `platform.config` (DA3).
- engagement: conforms. `engagement/routes.clj`, `api.clj` retention at 183 days.
- notifications: conforms. `notifications/api.clj` claim-based worker, daily cap.
- seed: conforms. `seed/core.clj` uses `catalog.api/create-product!` and `attach-file!`, idempotent by SKU.

Frontend server (`ssr/src/zandy/ssr/`)
- config: conforms. `config.clj` closed Malli map over Aero.
- log: conforms.
- api-client: conforms. `api_client.clj` JDK `HttpClient`, only network user (B10(c)), forwards `X-Request-Id`, timeout.
- pages: conforms. Uses `shared.pages` for the pure mapping as 2.3 says.
- render: conforms. `render.clj` CSP `connect-src 'self'`, ETag, per-route `Cache-Control`.
- seo-routes: conforms. `seo_routes.clj` robots and sitemap; the XML namespace lives in `ssr/resources/sitemap-head.xml`.
- static: conforms. `static.clj` immutable for js, css, tokens, fonts; one day for logo and icons (PE3); path-escape guard.
- launch-gate: conforms. `launch_gate.clj` retries 60 s, throws if any policy is not `approved`.
- http: conforms. `http.clj` one dispatch handler, Reitit as data matcher, 503 page with `no-store`.
- system: conforms. `system.clj`; `-main` returns after start-up and relies on Jetty's non-daemon threads (fragility noted in section 9).

Shared (`src/shared/zandy/shared/`)
- routes: conforms. Reitit data, `:access` per admin route, no static sibling beside a `{param}`.
- format: conforms. Fixed +3 Kampala arithmetic, shared by JVM and browser (section 7).
- cart, seo, json: conforms.
- ui.* and pages.*: deviates. `shared.pages` (the dispatcher over `pages.*`) is a component the dependency list in 2.4 does not show (DA5).
- document: conforms. `document.cljc` renders `#app`, `initial-state` script, and the assets.

Browser (`src/frontend/zandy/frontend/`)
- main: conforms. Renders over `#app`; Replicant clears the container on first render, which is the documented fallback in 2.4 (section 4.5).
- state, effects, api, router, cart-store: deviates. Extra edges `api -> shared.routes`, `effects -> shared.json, shared.seo` (DA5). `js/fetch` appears only in `api.cljs` (B10(b)).
- admin.core and admin.state: conforms. `admin.state` extends the `frontend.state` handlers (allowed in the script), pure handlers, no network.
- admin.widgets: deviates (new, DA5). Shared admin pieces used by `core` and the Portfolio scenes.
- admin.help and help_data: deviates (edges not in the list, DA5). Pure role filter plus generated data; narrative already in 2.4.
- scenes: conforms. Not required from `main.cljs`; Portfolio build only.

Scripts and build
- scripts/build/help.clj: conforms. Parser, `--check`, fails loudly with line numbers.
- scripts/build/assets.clj: conforms (not exercised here).
- scripts/build/build.sh: deviates. Runs `help.clj` but never `--check` (design 4.2 says it does). See section 6.
- scripts/check/*.clj: conforms. Allowed-edge tables encode the deviations of section 4.

Resources
- config.edn: conforms. Every secret is `#env`; prod requires owner email, HMAC secret (32+ characters), SMTP user and password, smtp mailer, vips processor, non-memory Datomic.
- migrations 0001 to 0010: conforms to N4. `0010-pictures-belong-to-products.edn` is a new additive file that alters `:product-image/photo` to `isComponent`.
- seed (images, manifest, products.edn): conforms in form (`seed_manifest.clj` must pass); edited by the lead (section 8).
- policies: conforms. Four documents in data form, all `placeholder`, so the launch gate holds.

Data model
- `product-image` to `photo` as a component: deviates (DA4). Design 3.2 had a plain reference.

## 3. Boundaries and fitness rules: one line each

Judged from source and tests; qa runs the commands.
- N1 conforms. `routes_test.clj` walks the route table; `wrap-validate` in `http.clj` runs Malli before any handler.
- N2 conforms. `:access` per route, `routes_test.clj` checks 401, 403 for support on owner routes, CSRF; owner routes under `/api/admin/staff` are `:access :owner` in `accounts/routes.clj`.
- N3 conforms. No literal secret found; `config.edn` and `ssr-config.edn` read the environment.
- N4 conforms. All schema under `resources/migrations`; 0010 is a new file.
- N5 conforms. `idempotency_test.clj` covers missing key, replay, reuse, concurrency, temporary password, multipart and import. The design text on how it works changes (DA2).
- N6 conforms after the wording fix: `:app` has one module (`:main`); `:portfolio` has its own `:modules`.
- B1 conforms. Banned libraries absent from `deps.edn`, `ssr/deps.edn`, `shadow-cljs.edn`.
- B2 conforms. No layer folders; slices require each other only through `.api`.
- B3 deviates (design text): the allowed-edge lists in `backend_deps.clj` and `frontend_deps.clj` carry the accepted edges of section 4, so architecture 2.2 and 2.4 must be updated (DA3, DA5).
- B3b conforms. No `datomic` text in `ssr`, `src/shared`, `src/frontend`; no `replicant`, `text/html` in `src/backend`.
- B4 conforms. Attribute ownership holds; `place_order.clj` does not write `:idempotency/*` (it uses `:order/idempotency-key`), which is stricter than the design's exception list.
- B5 conforms. No I/O requires in any `logic.clj`; `photos/csv.clj` is pure as well.
- B6 conforms. No `datomic.api` in any `routes.clj`.
- B7 conforms. Only `errors.clj` builds 4xx and 5xx responses.
- B8 conforms. `json.clj` encoder; `contract_conformance_test.clj` exists.
- B9 conforms. Headings in api-contract.md are unchanged by my edits; `contract_inventory_test.clj` compares them with the route table.
- B9b conforms. Frontend `/api` literals all match headings.
- B10 conforms after the wording fix (a). (b) `js/fetch` only in `api.cljs`. (c) `java.net.http` only in `api_client.clj`.
- B11 conforms. No `js/` in `src/shared`; no hex colours in `styles`, `src/frontend`, `src/shared`.
- B12 conforms. No `println`, `prn`, `tap>` in backend or `ssr/src`; no `console.log`. Advisory H2.
- B13 conforms. No `localhost`, `127.0.0.1` or `zandytouch.com` in `src` or `ssr/src`.
- B14 not assessable (needs both servers running).
- B15 conforms in code (`seo_routes.clj`, `launch_gate.clj`, tests present); run by qa.
- B16 not assessable (needs the build output and a running `ssr`). `target/static/js/main.<hash>.js` exists.
- B17 conforms in code and test (`media_test.clj`); needs `vips` on the machine.
- B18 not assessable (bundle size). The bundle now carries the admin area and all help text; measure.
- B19 conforms. replicant 2026.07.1 and reitit-core 0.9.1 agree. Pedestal is 0.8.2 in `deps.edn` and 0.8.1 in `ssr/deps.edn` (not checked by B19; align them).
- B23 conforms in form (script and `seed_test.clj` exist); qa runs both.
- B24 conforms. `place_order_concurrency_test.clj` exists.
- R2 not assessable (deployment).
- R3 conforms. Pedestal 0.8.x; no `reitit` in `deps.edn` or `src/backend`.
- R4 conforms. No top-level atoms in `src/backend` or `ssr/src`.
- R5 not assessable (deployment).
- R9 conforms. `config_test.clj`, `mailer.clj` `-main`.

## 4. Rulings on the deviations the builders reported

### 4.1 Backend

1. No muuntaja, no mulog. Ruling: accept as a design amendment (DA1). `platform.json` (jsonista with the key and instant rules), `platform.http` wrappers and `platform.log` (JSON lines with a key allowlist) are small, tested (`log_test.clj`) and remove two dependencies, which suits a one-developer project. Evidence: `json.clj` lines 22-31, `log.clj`. Conditions: name the libraries actually used in D11 (jsonista, `org.clojure/data.csv`, ring-core for multipart, slf4j-nop in `ssr`); declare `ring/ring-core` in `deps.edn` (code change H4, backend-clojure).
2. Stored-response idempotency instead of rebuilding from a result id. Ruling: accept as a design amendment (DA2), with a recorded limitation. The stored response gives the contract's literal guarantee ("the original response is returned unchanged") with no per-feature rebuild code; secrets are dropped through `:replay-body` (`accounts/routes.clj` lines 130, 163); all `:idempotency/*` attributes are `noHistory` and cleaned after 24 hours, so the stored order body (customer name and phone) does not outlive the 6-month rule. The limitation: the claim (`idempotency_claim` db function) and the business write are separate transactions, as the response is stored after the business write. A process that dies between the two and is retried after the 60-second stale window can repeat the write. Orders are safe (unique `:order/idempotency-key` in `place_order.clj`). Stock adjustments, product creation, staff creation and uploads are not. Hardening (non-blocking, backend-clojure, H5): derive the new entity ids (movement id, product id) from the scoped key so a repeat collides on a unique id.
3. Extra module edges. Ruling: accept all as design amendments (DA3); the dependency graph stays acyclic and one-way:
   - accounts to `platform.rate-limit`: the per-email login lockout needs counters inside the handler; `platform.config` is used only by `bootstrap.clj` `-main`; `platform.datomic` is already shared.
   - orders to `site.api`: the delivery-city list is site configuration; `site` depends on nothing.
   - orders (and others) to `platform.util`, `platform.validation`: stateless helpers. Add both as platform components that any component may use.
   - catalog to `platform.media` beyond the URL helper: catalog owns the photo entity and runs the pipeline in `attach-file!`.
   - migrations writing `:policy/*`: not a deviation of the design. Architecture 3.2 already makes migrations the only writer. The ownership table in `scripts/check/backend_deps.clj` (`owners`, "policy" -> `:policies`) is what is out of step; change the script table to name `platform.migrations` and drop the special case (backend-clojure, optional).
   - `:user-summaries` function in `ctx` (`system.clj` `build-ctx`, used by `catalog/routes.clj` and `orders/routes.clj`): accept. It lets `catalog` and `orders` show staff names without a source edge to `accounts`. Cost: the dependency is invisible to the graph script and tests must build the context with it. Amendment: document it in 2.2 as "staff names reach `catalog` and `orders` through `ctx :user-summaries`, built in `platform.system` from `accounts.api`". A visible edge `catalog -> accounts.api` would be equally acceptable; there is no cycle either way.
4. `:product-image/photo` as a component (migration 0010). Ruling: accept as a design amendment (DA4). It enforces the owner decision "a picture always belongs to a product" in the schema: retracting the product-image retracts the photo record, so no unassigned photo can exist. Evidence: `0010-pictures-belong-to-products.edn`, `catalog/db.clj` `reorder-images-tx` and `attach-picture-tx`, `pictures_test.clj` "every stored photo record belongs to exactly one product". The migration is additive and a new file (N4). No production data exists, so there are no orphaned photo records to clean up; staging databases created before this release should be recreated or checked once.

### 4.2 Frontend

5. No true Replicant takeover. Ruling: no deviation; the design already allows it (architecture 2.4: "hydrate by replacing `#app` with identical markup if true takeover is unavailable"). Replicant clears the container on the first render, so the server markup is replaced by identical markup from the embedded state; SEO, first paint and no-JavaScript browsing are unaffected because they come from the server HTML. Amendment (DA7): replace the sentence with the verified fact. Cost to accept: focus and a partially typed field are lost at the moment JavaScript starts. Recommended qa check (add as B25, owner to approve): in a browser, after load there is exactly one `h1`, no duplicate `id`, and the scroll position did not change.
6. Extra edges (`shared.pages`, `frontend.admin.widgets`, `frontend.admin.help` and `help_data`). Ruling: accept all (DA5). `shared.pages` is the pure page-data layer 2.3 already names; `admin.widgets` splits the admin view; `help` and `help_data` are described in 2.4 without edges. The same table in `frontend_deps.clj` also allows `frontend.api -> shared.routes` (query encoding), `frontend.effects -> shared.json, shared.seo` (head updates), `frontend.admin.state -> frontend.state, frontend.admin.help`, `shared.document -> shared.json, shared.pages`. No cycle, `src/shared` stays free of `js/`, `ssr`, `frontend`, Datomic and Pedestal.
7. Scrollable category strip on phones instead of a collapsing menu. Ruling: accept, but the design change belongs to ux-designer and the owner: design-spec.md line 174 says "collapsing to a menu on mobile". `styles/30-pages.css` line 54 makes `.site-nav__list` a horizontal scroller with the scrollbar hidden. It needs no JavaScript, so it works in the server HTML. Conditions for qa: every link is reachable by keyboard and scrolls into view on focus; there is a visible cue that more items exist (a hidden scrollbar gives none); at 320 px width and 200% zoom the page itself does not scroll sideways (WCAG 1.4.10). If any of these fails, wrapping the list onto two lines is the low-risk alternative. The menu and close icons in design-spec section 6 are then unused.

### 4.3 Summary of design amendments for the owner

See section 11 for the text of DA1 to DA10.

## 5. Bulk import, pictures, bulk publish against api-contract.md 8.2

Code read: `photos/routes.clj`, `photos/imports.clj`, `photos/csv.clj`, `photos/logic.clj`, `catalog/api.clj` (`attach-file!`, `publish-product!`), `catalog/logic.clj` (`plan-slot`, `admin-image-view`), `catalog/db.clj` (`attach-picture-tx`); tests `import_test.clj`, `pictures_test.clj`, `idempotency_test.clj`.

| Contract statement | Code | Test | Result |
|---|---|---|---|
| CSV, UTF-8 with BOM, comma or semicolon, 500 rows, 1 MB | `csv.clj` `decode-text`, `detect-separator`, `max-rows`; `imports.clj` size check | `csv-dialects`, `whole-file-errors` | conforms |
| Columns, unknown rejected, case-insensitive headings | `csv.clj` `column-keys`, `parse-table` | `whole-file-errors` | conforms |
| New SKU creates a draft, `initial` movement | `imports.clj` `apply-row!` | `import-creates-updates-and-rejects-rows` | conforms |
| Existing SKU (archived too): non-empty cells only, status and stock unchanged | `apply-row!` `:update` branch | `published-and-archived-keep-their-status` | conforms |
| Invalid row rejected whole, others apply; duplicate SKU | `csv.clj` `evaluate` | same | conforms |
| Dry run changes nothing; `productId` null for would-create | `run-import!` | `dry-run-changes-nothing` | conforms |
| Template download | `template-response` | `template-round-trips-through-the-import` | conforms |
| Per-product upload, 1 to 6 files, response `items` + `product` | `routes.clj` `upload-to-product` | `upload-onto-a-product` | conforms |
| Bulk by SKU, 20 per request, longest SKU, suffix rule, slot replace | `logic.clj` `match-file`; `catalog/logic.clj` `plan-slot` | `match-file-names-to-skus`, `bulk-upload-by-sku` | conforms |
| Per-file codes, rejected files not kept | `media.clj` `process-upload!` | `upload-onto-a-product` | conforms |
| Version bumped by a picture | `attach-picture-tx` `:db/cas` on version | indirectly (the PATCH after an upload uses the new version) | conforms; no direct assertion (test gap T1) |
| Staff views return the stored `alt`, null when unset | `admin-image-view` | `images-list-on-patch-...` | conforms |
| Bulk publish: published unchanged, archived restored, `notFound`, per-reason errors | `catalog/api.clj` `publish-product!`, `routes.clj` `publish-many` | `imported-drafts-are-published-in-bulk` (archived restore is not tested; T2) | conforms |
| Idempotency required on the three upload/import endpoints | `routes.clj` `:idempotent? true` | `idempotency_test.clj` | conforms |

Photo library: completely gone. No `/api/admin/photos` or `/api/admin/photo-assignments` route in any `routes.clj` (the route table is asserted free of `/photos` and `photo-assignments` in `pictures_test.clj` `there-is-no-photo-library`); no `/admin/photos` page (`shared/routes.cljc` has none; `routes_test.cljc` and `state_test.cljs` assert `match-url "/admin/photos"` is nil); no admin menu item for it (`help_test.cljs` pins the menu to Orders, Products, Import, Inbox, Staff, My account, Help); no unassigned-photo query or state anywhere under `src`, `ssr`, `resources`, `scripts`. The remaining `:photo/*` entity is the component of `:product-image/photo`; its only writers are `attach-file!` and the seed through the same function. The word "photos" in `shared/ui/core.cljc` refers to the static site photos under `/static/assets/photos/`, which is unrelated. Verdict: conforms.

Frontend side of the import (read for completeness): `admin/state.cljs` uses one key per file and mode (lines 625-641), so the dry run and the real import never share a key; pictures go in batches of 20 with a fresh key per batch (line 696); drafts are loaded with `status=draft&pageSize=100` for up to five pages (lines 16-22, 46-50) and filtered by the ids returned by the real import (lines 663-671, 732-741); "Publish all complete drafts" sends at most 500 ids (line 754). This matches the contract text I wrote.

Test gaps (non-blocking, backend-clojure): T1 assert the version bump after an upload; T2 bulk publish of an archived id (restored, and rejected-and-still-archived); T3 `duplicateColumn` (no test), `invalidCsv` (the test checks only the status 422, not the code), `invalid` for two files, `tooManyFiles` code name.

## 6. Staff help

- admin-help.md is ux-designer's file: yes (architecture 4.1, `.claude/agents/ux-designer.md` line 106, `frontend-cljs.md` line 90 "never edit").
- `scripts/build/help.clj` (frontend-cljs) converts it to `src/frontend/zandy/frontend/admin/help_data.cljs`: yes. It rejects unknown syntax, unknown ids, links from a support topic to an owner-only topic or page, and any admin page A4 to A12 without a topic, with a line number.
- `scripts/build/build.sh` runs it: yes, line 8, before `shadow-cljs release app`. It does not run `--check`, which 4.2 says it does. Running `--check` after regenerating can never fail. Stale committed data is caught by the JVM test `help_build_test.clj` `data-namespace-is-up-to-date` (it runs in `cd ssr && clojure -M:test`, whose `:test` alias adds `../scripts/build` and `../test/frontend`) and by the ClojureScript tests `help_test.cljs`. Ruling: restate 4.2 (DA8): "build.sh regenerates the data; staleness of the committed file is detected by `bb scripts/build/help.clj --check` run before the build and by `help-build-test`". Optional code change (frontend-cljs, H6): run `--check` first in `build.sh`, or add `git diff --exit-code` on the data file in CI.
- Help routes are staff-only: `shared/routes.cljc` lines 38-40 give `/admin/help` and `/admin/help/:topic` `:access :staff`, and `admin/core.cljs` `view` shows the shell only with a session; the SSR serves a no-data document shell. This is a UI gate.
- Role filtering is a UI convenience only: correct, and acceptable. `admin/help.cljs` `can-see?` hides owner-only topics and `topic-view` never renders their text. But `help_data.cljs` is compiled into `main.<hash>.js`, which anyone can download, so the owner-only topic text (staff accounts, password handover) is publicly readable. The text holds procedures and button names, no credential, token or address; the real protection for those actions is server-side: every `/api/admin/staff*` route is `:access :owner` (`accounts/routes.clj`), and `routes_test.clj` asserts 403 for a support session on each of them. Ruling: acceptable; tell the owner (Q for section 12) that the help text is not private. If it ever must be, serve it from an authenticated endpoint and take it out of the bundle (a contract change, so not now).
- Also check B18: the help data and admin area add to the single bundle; measure the gzip size.

## 7. Dates

Conforms. `shared/format.cljc` (lines 41-105) converts ISO 8601 to Kampala wall-clock time by pure arithmetic with a fixed +3 offset (Uganda has no daylight saving), so the JVM render and the browser give identical text. Uses found: admin orders list and detail, history, agreement record, products list, product page ("Last updated"), stock history (`admin/widgets.cljs` 232), inbox, subscribers, staff last sign-in (`admin/core.cljs` 209, 266, 301, 330, 417, 546, 738, 750, 778), public tracking timeline (`ui/order.cljc` 93), policy "effective" date (`ui/policy.cljc` 22). No `js/Date`, `toLocale*`, `Intl` or `java.time` formatting anywhere in `src/shared`, `src/frontend`, `ssr/src` (only `Duration` and `Instant` for timeouts and log stamps). Tests: `format_test.cljc` covers midnight crossing, month and year ends, leap day, offsets and date-only text. The API stays UTC: `json.clj` writes `yyyy-MM-dd'T'HH:mm:ss'Z'` in UTC for every `Date`. Emails (`notifications/logic.clj`) print no times. Sitemap `lastmod` and JSON-LD stay machine UTC, which is correct. Contract 1.2 now says so.

## 8. Edits outside each owner's folders

| Edit | Owner per 4.1 | Ruling |
|---|---|---|
| `assets/photos/` (hero, About photo, SOURCES.md), `assets/logo/zandy-touch-*.webp`, `assets/share/` added by the lead | visual-designer | Accept as owner-recorded one-off content; ownership of `assets/**` does not move. Conditions: (1) every file has a provenance line: `assets/photos/SOURCES.md` covers photos; add lines for `assets/share/og-default.jpg` and `assets/logo/zandy-touch-*.webp` (origin, licence); (2) B16 `git status --porcelain assets design-tokens.css` must stay clean after the build (the build copies, never writes, `assets/`); (3) the next visual-designer run must be told these files exist and must not delete or regenerate them. Owner decision: `about-zandy.jpg` is a CC0 stock photo of an unidentified woman on the About page; SOURCES.md says to replace it before launch, so record it as a launch item. Minor: `assets.clj` copies `assets/photos/SOURCES.md` to the public `/static/` tree; skip `*.md` (frontend-cljs, optional). |
| `resources/seed/images/*` and `resources/seed/products.edn` changed by the lead | backend-clojure | Accept; ownership stays with backend-clojure. The lead replaced placeholders by CC0 or public-domain photos with a manifest. Conditions: `bb scripts/check/seed_manifest.clj` and `zandy.seed-test` must pass; the licence claims are only as good as the manifest (the script cannot verify them), so qa or the owner spot-checks three source URLs; backend-clojure re-reads both files before its next change. |
| small edits to `admin-help.md` by the lead | ux-designer | Accept, record. They track real screens (Kampala time, restore flow, stock history). The parser and its tests guard the format. Going forward: wording that follows the code may be fixed by the lead; any change of meaning goes through ux-designer, and ux-designer must start from the current file. |
| `scripts/build/help.clj` parser fix by the lead | frontend-cljs | Accept, record. The paragraph-may-start-with-bold fix is pinned by `help_build_test.clj` `a-paragraph-may-start-with-bold`. |

Recommendation: the owner records these four as approved exceptions (DA9); do not move ownership.

## 9. Backend exits after "server-started" without ZANDY_PROFILE=dev

Finding. I could not reproduce it (no shell), and the code has no path that ends the JVM after that line. What the source shows:

- `system.clj` lines 89-92: `-main` starts the system, adds a shutdown hook and blocks on `@(promise)`. A blocking main exists, so "non-blocking main" is not the cause for the backend. (It is true of `ssr/system.clj` `-main`, which returns and keeps alive only because Jetty's threads are non-daemon.)
- Config problems cannot occur after the log line. `config.clj` `validate!` and `prod-problems` (owner email, `SESSION_HMAC_SECRET` of 32+ characters, SMTP user and password, smtp mailer, vips processor, non-memory `DATOMIC_URI`) throw while the `::config` key starts, so the process dies before `::server`. The same holds for `migrations/verify-current!` ("Database is not migrated. Run `clojure -M:migrate` first."). `::server` is the last key, so `:server-started` is printed only after everything else succeeded.
- The `vips` requirement is not checked at start-up at all. `media.clj` `vips-available?` is used only by `media_test.clj`. With `:processor :vips` (the prod default in `config.edn` line 38) a missing `vipsthumbnail` shows up at the first upload as a per-file `unreadableImage` plus an `image-processing-failed` log line. It does not stop the process.
- No `System/exit` exists in `src/backend`. The scheduled jobs catch `Throwable` (`system.clj` lines 53-56).
- The profile comes from `ZANDY_PROFILE`, then the `zandy.profile` property, else `prod` (`config.clj` lines 79-82). `deps.edn` line 19 bakes `-Dzandy.profile=dev` into the `:run` alias, so `clojure -M:run` is always the dev profile, with or without `ZANDY_PROFILE`; a prod run needs another entry point (`clojure -M -m zandy.platform.system` or the image command). Tell deployment-engineer: `Dockerfile.backend` must not use `:run`.
- The commands in `tutorial/prompts.org` lines 52-60 and 62-70 put the assignments on separate lines without `export` or a trailing `\`. In bash that sets shell variables, not environment variables, so `PORT`, `OWNER_EMAIL` and `OWNER_INITIAL_PASSWORD` do not reach the JVM: the backend listens on 8080, no owner account is created (`bootstrap.clj` line 18 returns `:skipped` when the password is nil), and the `ssr` block's `API_BASE_URL` points at 8082. Fix the snippet.

So the likely explanations of a silent end after the log line are external: a signal (the shutdown hook stops the system without logging anything), a kernel OOM kill (the prod JVM has no `-Xmx`; a real Datomic peer uses half the default heap for its object cache), or running a process in the background of a shell that is closed (SIGHUP). Exit code tells which: 129, 130 or 143 for signals, 137 for an OOM kill, 1 for an exception.

Bug or unclear message? An unclear start-up, not a logic bug. Changes (backend-clojure, none blocks qa; H3):
1. `-main`: wrap start in `try`, log `:startup-failed` with the problem list (never values) and `System/exit 1`, as `ssr` already does.
2. The shutdown hook logs `:shutdown-requested` before stopping, so a signal is visible.
3. In the prod profile, fail fast when `:processor` is `:vips` and `vips-available?` is false.
4. Log a warning `:no-owner-account` at start when no owner exists, because a prod server without an owner starts fine and nobody can sign in.
5. `ssr/system.clj`: block in `-main` (`@(promise)`) so the process does not depend on Jetty's thread settings (frontend-cljs).
6. Document the prod command and the heap flags in the deployment handover.

## 10. Other findings

Code changes recommended, none blocking qa:
- H1 `orders/routes.clj` lines 102 and 133 rebuild the idempotency scope string that `http.clj` line 173 builds. If the formats ever differ, the replay lookup silently stops working. Expose one `scoped-key` function in `platform.idempotency` (backend-clojure).
- H2 `log.clj` `exception-data` logs the exception message, which for Datomic unique conflicts and some db-function failures can include values such as an email. Log the class and ex-data keys, not the message, or scrub the message (backend-clojure; B12 intent).
- H3 start-up diagnostics, section 9.
- H4 declare `ring/ring-core` (used directly in `http.clj`) and name `org.clojure/data.csv` in D11 (backend-clojure, architect for D11).
- H5 hardening for idempotency crash window, 4.1 item 2.
- H6 `build.sh` check, section 6; align Pedestal 0.8.1 and 0.8.2.
- `tutorial/.#prompts.org` is an Emacs lock file in `git status`; add `.#*` to `.gitignore`.

Frontend notes (accepted, no change): "Check the file" replays the stored report when pressed twice on the same file (same key), which can show a dry-run result that is older than the data; acceptable.

## 11. Design amendments for the owner to approve

- DA1 Libraries. Replace muuntaja and mulog by hand-written JSON interceptors over jsonista and a small JSON logger. D11 lists jsonista, `data.csv`, ring-core (multipart), logback (backend), slf4j-nop (`ssr`). 2.2 `platform.json` and `platform.http` rows and D6 item 2 are reworded.
- DA2 Idempotency. "The server claims the key with a transaction function, runs the handler, then stores the response (EDN, `noHistory`) under the key for 24 hours; replays return the stored response with `Idempotent-Replayed: true`, one-time secrets excluded. `place-order` additionally carries a unique `:order/idempotency-key`. The claim and the business write are separate transactions; a request that dies in between and is retried after 60 seconds can repeat a non-order write (hardening H5)." The `idempotency` entity in 3.2 lists `state`, `claim-token`, `response`, `body-hash`, `created-at`; the B4 exception for `place-order` no longer names `:idempotency/*`.
- DA3 Backend edges. Add to 2.2: platform components `util` and `validation`; `accounts -> platform.rate-limit`; `orders -> site.api`; `catalog -> platform.media` (pipeline, not only URLs); `ctx :user-summaries`; `seed -> catalog.api, platform.*`; `platform.log -> json` (not config). `policy` attributes are written only by `platform.migrations`.
- DA4 Data model. `product-image/photo` is a component reference; a photo exists only as part of a product's image.
- DA5 Frontend edges. Add to 2.4: `shared.pages`, `shared.json`, `frontend.admin.state`, `.widgets`, `.help`, `.help-data`; `frontend.api -> shared.routes`; `frontend.effects -> shared.json, shared.seo`; `frontend.admin.state -> frontend.state`.
- DA6 Category navigation on phones is a horizontally scrollable strip (design-spec.md line 174, ux-designer to amend) with the conditions of 4.2 item 7.
- DA7 Browser start-up renders the same views over `#app`, replacing the server markup (verified in Replicant); proposed check B25 in 4.2 item 5.
- DA8 4.2 build text: `build.sh` regenerates the help data; staleness is detected by `help.clj --check` before the build and by `help-build-test`.
- DA9 Ownership record: the four lead edits of section 8 are approved one-off exceptions; owners unchanged.
- DA10 Help text is public in the bundle; role filtering is a UI convenience (section 6).

## 11a. Owner decisions (26 October 2026)
- DA1 to DA10: approved. This includes DA10: the staff help text, owner-only topics included, is readable in the public JavaScript bundle; role filtering is a UI convenience and owner-only actions stay enforced server-side.
- Phone navigation: keep the horizontally scrolling category strip (DA6). It must still meet the accessibility conditions in 4.2 item 7; qa-report.md found two unmet (a focused link is not scrolled fully into view; no visible cue that more items exist), so those two are fixed on the strip rather than switching to a wrapped list.
- About photo: keep the CC0 stock photo for now; the owner replaces it with an own photo before launch.

## 12. Blocking qa, and what qa must still run

Nothing blocks qa. The fixes qa needed are done: N6(b) and B10(a) now pass as worded (B10(a) also excludes `scenes/`, as B13 does; the Portfolio fixtures hold sample TikTok, Facebook and Instagram URLs and would otherwise fail the check), and api-contract.md now matches the code, so contract-based tests and the frontend `api-calls` check read an accurate file.

qa must run what I could not: the whole of `clojure -M:test`, `cd ssr && clojure -M:test` (includes the help build test), `shadow-cljs` `:test`, the three `bb scripts/check` scripts, `clj-kondo`, B14, B16, B18 (bundle size with the help data), the keyboard and 320 px check of 4.2 item 7, and the proposed B25. Owner questions: (1) approve DA1 to DA10; (2) accept that the staff help text is publicly readable in the JavaScript bundle; (3) decide about the stock About photo; (4) confirm the phone navigation as a scrolling strip.

## 13. Changes made to api-contract.md (all in place)

- CC1 (1.2): dates stay UTC in the API; the staff area and public pages show Kampala time, UTC+3, as display only.
- CC2 (1.2): the route-design sentence now names Pedestal's own router (the earlier change C4 in architecture.md section 11 had not been applied to the contract).
- CC3 (1.4): added the `405 methodNotAllowed` row (the code returns it with `Allow`); 404 also covers an unknown path; the 413 row is now the import CSV over 1 MB, with pictures reported per file; the 415 row says it is not returned today; the 503 row also covers a still-running duplicate request; the list of detail-only codes now includes `required`, `invalid`, `unknownField`, `unknownCategory`, `unknownImage`, `duplicateImage`, `tooManyFiles`, `weakPassword`, `duplicate` and the import codes; the per-file error list points to 8.2.
- CC4 (1.6): idempotency text matches the code: the response is stored, the scope excludes the query, the fingerprint covers the body or every multipart part (file names and content hashes) and the query string, concurrent duplicates wait up to about five seconds then get 503 with `Retry-After`, failed requests release the key, stale claims expire after 60 seconds.
- CC5 (8.1): `sku` is stored in upper case and matched case-insensitively; `GET /api/admin/products` `pageSize` maximum 100 and the stored (nullable) `alt` for admin views; picture changes bump the version.
- CC6 (8.2), all verified against the code before writing:
  - Slots 1 to 6 with gaps; positioned file replaces its slot; `position` is the slot used (null if rejected); PATCH `images` renumbers.
  - Stored `alt`, null when unset, in admin views; attaching a picture bumps the product `version`.
  - Per-file codes including `unknownSku` (also for a wrong suffix) and `tooManyImages`; the file-name rule (nothing or `-1` to `-6`, case-insensitive, longest SKU wins, directory part ignored, archived SKUs included) with an example.
  - Upload with no files: 422 `required` on `files`; too many: `tooManyFiles`.
  - Import: `line` is the spreadsheet row (heading is row 1, blank rows skipped but counted, quoted line breaks are one row); dry-run `productId` null for would-create; per-row codes `required`, `invalid`, `unknownCategory`, `duplicateSku`, `extraCell`, and failure codes when applying; warnings carry only `field` and `code`, `ignoredForExisting`; whole-file codes `required`, `invalid` (more than one file), `notUtf8`, `invalidCsv`, `missingColumn`, `unknownColumn`, `duplicateColumn`, `tooManyRows`, and 413 `fileTooLarge`; the dry run and the real import need different `Idempotency-Key` values (same key gives 409 `idempotencyKeyReused` because the query is in the fingerprint); how the admin page finds the imported drafts.
  - Bulk publication: archived ids are restored (stay archived and rejected when the rule fails), `notFound` for an unknown id with `field: "productId"`, error entries carry `message`, results in request order.
- CC7 (11): the photo library endpoints are recorded as removed.

## 14. Amendments merged into architecture.md (27 October 2026)

The approved amendments DA1 to DA10 (section 11, approved in 11a), the nine code-versus-design points of CODE-GUIDE.md section 8 (items 6 to 14), the changes since the design and the deployment boundary of deploy/DECISIONS.md are merged into architecture.md; its "Revision history" and section 13 give the details. This review is not rewritten to match: the sections above remain the record of the review as made at commit 1a3b47d. Where the text above says "design text is out of date" (sections 1, 2, 3, 4.1 to 4.2, 6), architecture.md now carries the corrected text. Open items that remain are listed in architecture.md section 9 (O1 to O4) and the recommended code changes H1 to H6 in its section 13.
