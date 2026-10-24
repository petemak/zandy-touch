(ns zandy.ssr.launch-gate
  "With SITE_INDEXABLE=true the server refuses to start while any policy
  document is still a placeholder (api-contract.md section 12)."
  (:require [zandy.ssr.api-client :as api]
            [zandy.ssr.log :as log]))

(def policy-types ["orderTerms" "termsOfSale" "privacy" "returns"])

(defn- check-once [api-client]
  (let [results (api/fetch-all api-client
                               (into {} (map (fn [t] [t {:path (str "/api/policies/" t)}])) policy-types)
                               "launch-gate")
        unreachable (seq (remove #(<= 200 (or (:status (val %)) 0) 299) results))]
    (if unreachable
      {:state :unreachable :which (mapv key unreachable)}
      (let [bad (vec (for [[t r] results :when (not= "approved" (get-in r [:body :reviewStatus]))] t))]
        (if (seq bad) {:state :not-approved :which bad} {:state :ok})))))

(defn check!
  "Returns nil when every policy is approved. Throws otherwise. The API is
  retried for up to `retry-ms` (default 60 s) while it is unreachable."
  ([api-client] (check! api-client {}))
  ([api-client {:keys [retry-ms interval-ms] :or {retry-ms 60000 interval-ms 2000}}]
   (let [deadline (+ (System/currentTimeMillis) retry-ms)]
     (loop []
       (let [{:keys [state which]} (check-once api-client)]
         (case state
           :ok (log/info :launch-gate-passed {})
           :not-approved (do (log/error :launch-gate-failed {:policies which})
                             (throw (ex-info "Launch gate: policy documents are not approved" {:policies which})))
           :unreachable (if (< (System/currentTimeMillis) deadline)
                          (do (Thread/sleep ^long interval-ms) (recur))
                          (do (log/error :launch-gate-unreachable {:policies which})
                              (throw (ex-info "Launch gate: backend unreachable" {:policies which}))))))))))
