(ns zandy.features.policies.routes
  "Versioned policy documents (contract section 2)."
  (:require [zandy.features.policies.api :as api]
            [zandy.platform.errors :as errors]))

(defn get-policy [{:keys [ctx parameters]}]
  (let [type (get-in parameters [:path :type])
        policy (and (some #{type} api/policy-types) (api/current-policy ctx type))]
    (if policy
      {:status 200 :body (update policy :review-status name)}
      (errors/fail! :notFound "Policy not found."))))

(def routes
  [{:method :get :path "/api/policies/:type" :name :policies/get :access :public
    :cache "public, max-age=600, stale-while-revalidate=600" :handler get-policy}])
