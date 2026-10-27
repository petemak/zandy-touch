(ns zandy.ssr.pages
  "Page data for the frontend server (architecture.md section 2.3,
  `ssr.pages`). JVM only. For a matched route it makes the API calls listed by
  zandy.shared.pages/requests (in parallel, through ssr.api-client) and builds
  the page state with zandy.shared.pages/assemble. It also decides when a
  listing URL must redirect to its clean form."
  (:require [malli.core :as m]
            [zandy.shared.pages :as pages]
            [zandy.shared.routes :as routes]
            [zandy.ssr.api-client :as api]))

(def ListingQuery
  "Malli schema of a normalised listing query for /shop and category pages:
  optional :q (2 to 80 characters), :availability (\"available\" or \"soldOut\"),
  :sort (not \"newest\", which is the default) and :page (2 to 1000). Closed
  map, so unknown parameters never validate."
  [:map {:closed true}
   [:q {:optional true} [:string {:min 2 :max 80}]]
   [:availability {:optional true} [:enum "available" "soldOut"]]
   [:sort {:optional true} [:enum "priceAsc" "priceDesc" "nameAsc"]]
   [:page {:optional true} [:int {:min 2 :max 1000}]]])

(defn normalised-query
  "Known listing parameters in a valid form, everything else dropped. Argument:
  `query` (map of keyword to string from routes/parse-query). Returns the map
  from zandy.shared.pages/normalise-query, or {} if that result still fails
  the ListingQuery schema (boundary check). Pure."
  [query]
  (let [q (pages/normalise-query query)]
    (if (m/validate ListingQuery q)
      q
      {})))

;; Why: every distinct query string is a distinct CDN cache key. Redirecting
;; ?utm_source=x&sort=bogus to the clean URL keeps one cache entry per real
;; page and stops crawlers from indexing junk variants. The redirect itself is
;; cached briefly by the caller (ssr.http).
(defn canonical-redirect
  "Path to redirect to when a listing request carried unknown or invalid
  parameters. Arguments: `route` (matched route {:name :path :query ...}) and
  `raw-query-string` (the query string as received, or nil). Returns a path
  with a clean query when the route is :shop or :category and the clean query
  differs from the raw one; nil otherwise. Pure."
  [{:keys [name path query]} raw-query-string]
  (when (#{:shop :category} name)
    (let [clean (routes/encode-query (normalised-query query))
          raw (or raw-query-string "")]
      (when-not (= clean raw)
        (routes/with-query path (normalised-query query))))))

(defn load-state
  "Builds the full page state for a matched route. Arguments: `api` (from
  api-client/create), `route` (from routes/match-url, nil for an unknown path)
  and `request-id`. Returns {:state page-state :failed {request-key
  http-status-or-nil}}; :failed is present only when the page is :unavailable
  and says which calls failed (for the log). Unknown paths give a :not-found
  state. Shell routes (cart, checkout, admin ...) give a :loading state
  without any API call. Side effect: backend GET requests for server-rendered
  routes."
  [api route request-id]
  (let [base (pages/initial-state)]
    (cond
      (nil? route)
      {:state (assoc base :route {:name :not-found :path nil} :page {:status :not-found})}

      (:shell? (:data route))
      {:state (assoc base :route route :page {:status :loading})}

      :else
      (let [route (update route :query normalised-query)
            reqs (pages/requests route)
            results (api/fetch-all api reqs request-id)
            {:keys [site page]} (pages/assemble route results)]
        {:state (cond-> (assoc base :route route :page page)
                  site (assoc :site site))
         :failed (when (= :unavailable (:status page))
                   (into {} (keep (fn [[k r]] (when-not (pages/ok? r) [k (:status r)]))) results))}))))

(defn site-only
  "Best-effort GET /api/site-info so that error pages can still show the header
  and footer. Returns the site-info body, or nil when the call fails."
  [api request-id]
  (let [r (api/fetch api pages/site-request request-id)]
    (when (pages/ok? r) (:body r))))
