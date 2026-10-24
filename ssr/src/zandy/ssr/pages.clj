(ns zandy.ssr.pages
  "For each page route: the API calls of api-contract.md section 12, and the
  assembly of the page state. The mapping itself is pure and lives in
  zandy.shared.pages."
  (:require [malli.core :as m]
            [zandy.shared.pages :as pages]
            [zandy.shared.routes :as routes]
            [zandy.ssr.api-client :as api]))

(def ListingQuery
  "The only query parameters /shop and /categories/* accept."
  [:map {:closed true}
   [:q {:optional true} [:string {:min 2 :max 80}]]
   [:availability {:optional true} [:enum "available" "soldOut"]]
   [:sort {:optional true} [:enum "priceAsc" "priceDesc" "nameAsc"]]
   [:page {:optional true} [:int {:min 2 :max 1000}]]])

(defn normalised-query
  "Known parameters in a valid form, everything else dropped. Asserts the
  result against the Malli schema (boundary check)."
  [query]
  (let [q (pages/normalise-query query)]
    (if (m/validate ListingQuery q)
      q
      {})))

(defn canonical-redirect
  "For listing routes, the path to redirect to when the request carried
  unknown or invalid parameters (so they cannot pollute CDN cache keys)."
  [{:keys [name path query]} raw-query-string]
  (when (#{:shop :category} name)
    (let [clean (routes/encode-query (normalised-query query))
          raw (or raw-query-string "")]
      (when-not (= clean raw)
        (routes/with-query path (normalised-query query))))))

(defn load-state
  "Build the full page state for a matched route (or nil for an unknown
  path). Returns {:state map :status int}."
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
  "Best-effort site-info for error pages."
  [api request-id]
  (let [r (api/fetch api pages/site-request request-id)]
    (when (pages/ok? r) (:body r))))
