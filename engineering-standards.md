## Goals
- Simplest architecture that satisfies everything below — one
  backend service, one frontend server, one database, until
  something here demands more
- Maintainable by a single developer
- Accessible to WCAG 2.1 AA

## Deployment topology
- Frontend and backend run as separate servers so they can be
  cached and scaled independently. Most traffic is read-only
  browsing, which the frontend serves and a CDN caches; writes to
  Datomic (orders, catalogue edits) need far less scaling.
- The frontend server renders public pages on the server (SSR)
  from the same Replicant views the browser uses, so SEO tags are
  in the first HTML response. It gets data only through the
  backend's HTTP API, never directly from Datomic.
- Both servers must be stateless enough to run more than one
  instance; anything shared (sessions, rate limits, idempotency
  keys) lives in the backend's database, not in process memory.
- Datomic with its built-in dev storage plus off-site backups is
  accepted for launch; a different storage can come later.
- Total hosting stays within 15 USD per month.

## Architectural boundaries
- Business logic never lives only in the frontend — the server
  validates and enforces it; client-side logic is convenience
  only, never the source of truth
- Route/handler code stays thin; business logic lives in its
  own module, testable without going through HTTP
- Organize backend code by feature, not by technical layer —
  src/features/catalog/, src/features/checkout/, each owning
  its own logic end to end, rather than one controllers/
  folder and one services/ folder spanning the whole app
  (vertical slices). This also lowers collision risk if you
  ever split work across more than one backend teammate.
- Frontend never calls third-party APIs or holds secrets
  directly — always proxied through the backend
- Auth/authorization enforced server-side on every protected
  endpoint, never only by hiding a button in the UI
- One deployable frontend build. Do not split the UI into
  independently built and deployed fragments (micro-frontends)
  — that pattern earns its cost at a scale of multiple
  independent teams shipping on separate schedules, which is
  not this crew's situation regardless of how the site grows

## Architectural Guidelines
When building application on the Clojure technology stack follow these recommendations:
- Pure Core: Keep business logic in pure, immutable functions that accept data 
  maps and return transformed data maps without side effects.
- Imperative Shell: Push I/O (database calls, network requests, file systems) 
  to the outer boundaries of the application.
- Data-First: Use plain Clojure maps and vectors instead of custom classes or 
  deep object hierarchies for domain models.
- Component Systems: Manage long-lived state (database pools, HTTP servers, workers) 
  using explicit lifecycle libraries rather than global singletons or dependency injection frameworks.
- Configuration Files: Store environment-specific configuration externally using 
  structured EDN files or environment variables
- Schema Validation: Validate configuration maps and incoming/outgoing data payloads 
  at the boundary using Malli.

## Libraries, tools and frameworks
When building Clojure and ClojureScript applications, use the following libraries:
- `Replicant` (https://github.com/cjohansen/replicant) - rendering library for Clojure/ClojureScript
- `Pedestal` (https://github.com/pedestal/pedestal) - backend for basic services, APIs and applications
- `Reitit` (https://github.com/metosin/reitit) - for HTTP routing.
  If Reitit does not work with the current Pedestal version, use
  Pedestal's own router on the backend instead of downgrading Pedestal.
- `Portfolio` (https://github.com/cjohansen/portfolio) - the Storybook-like visual tool for the UI testing
- `Integrant` (https://github.com/weavejester/integrant) - for component management
- `Aero` (https://github.com/juxt/aero) - for application for configuration
- `Datomic`(https://docs.datomic.com/datomic-overview.html) - as database management system
- `Malli` (https://github.com/metosin/malli) - for schema validation
- `Cognitect test-runner` (https://github.com/cognitect-labs/test-runner) -  for discovering and running tests
- `babashka` (https://babashka.org) - great tool to write scripts in Clojure

## Technology restrictions
- Frontend: ClojureScript, Replicant, Portfolio, shadow-cljs
- Backend: Clojure, Integrant, Aero, Pedestal, Reitit, Malli,
  Datomic
- Not allowed: Reagent, re-frame, Rum, Compojure, ring-jetty,
  Mount, environ, Plumatic Schema, SQL databases for application data
- The libraries named above are required: use each one for its
  stated purpose and do not replace it with an alternative.
  Other libraries may be added without approval, provided they
  are actively maintained, do not duplicate the purpose of a
  required library, and are not in the "Not allowed" list. Name
  each added library and give one line on why in your summary.
- One error response shape across every endpoint:
  { error: { code, message } }
- camelCase keys, ISO 8601 dates, consistently across all JSON
- Mutating endpoints (checkout, booking) must be idempotent —
  a retried request can't double-charge or double-book
- All external input validated at the API boundary, before it
  reaches business logic or the database
- No secrets or API keys hardcoded anywhere, including seed
  data — environment variables only
- Schema changes go through versioned migrations, never ad hoc
  changes run by whichever agent happens to need a column
- Structured error logging with real context, not scattered
  print statements

## Non-negotiable vs guideline
- Non-negotiable: server-side validation and authorization, no
  hardcoded secrets, versioned migrations, idempotent mutations,
  no micro-frontends
- Guideline, may deviate with a stated reason: vertical-slice
  folder structure, the specific error shape, the date format
