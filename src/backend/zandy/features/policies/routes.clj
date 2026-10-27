(ns zandy.features.policies.routes
  "Versioned policy documents (contract section 2)."
  (:require [zandy.features.policies.api :as api]
            [zandy.platform.errors :as errors]))

(defn get-policy
  "Handler for GET /api/policies/:type. Returns 200 with the current policy (review-status as a string) or throws notFound (404) for an unknown type or a type with no current version."
  [{:keys [ctx parameters]}]
  (let [type (get-in parameters [:path :type])
        policy (and (some #{type} api/policy-types) (api/current-policy ctx type))]
    (if policy
      {:status 200 :body (update policy :review-status name)}
      (errors/fail! :notFound "Policy not found."))))

(def routes
  "Route data for the policies slice (public, cached 10 minutes)."
  [{:method :get :path "/api/policies/:type" :name :policies/get :access :public
    :cache "public, max-age=600, stale-while-revalidate=600" :handler get-policy}])
