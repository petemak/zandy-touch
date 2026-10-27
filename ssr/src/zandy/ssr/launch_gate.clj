(ns zandy.ssr.launch-gate
  "Launch gate (architecture.md section 2.3, `ssr.launch-gate`). JVM only, runs
  once at boot. When SITE_INDEXABLE=true the server must not go live while any
  of the four policy documents is still a placeholder; the check reads
  api-contract.md section 12's policy endpoints and refuses to start
  otherwise."
  (:require [zandy.ssr.api-client :as api]
            [zandy.ssr.log :as log]))

(def policy-types
  "The four policy document types that must have reviewStatus \"approved\" before
  launch."
  ["orderTerms" "termsOfSale" "privacy" "returns"])

(defn- check-once
  "One pass of the gate: fetches the four policies in parallel. Returns {:state
  :ok}, {:state :unreachable :which [types]} when a call did not return 2xx,
  or {:state :not-approved :which [types]} when a policy's reviewStatus is not
  \"approved\". Side effect: backend requests."
  [api-client]
  (let [results (api/fetch-all api-client
                               (into {} (map (fn [t] [t {:path (str "/api/policies/" t)}])) policy-types)
                               "launch-gate")
        unreachable (seq (remove #(<= 200 (or (:status (val %)) 0) 299) results))]
    (if unreachable
      {:state :unreachable :which (mapv key unreachable)}
      (let [bad (vec (for [[t r] results :when (not= "approved" (get-in r [:body :reviewStatus]))] t))]
        (if (seq bad) {:state :not-approved :which bad} {:state :ok})))))

;; Why a launch gate: the legal texts must be approved by the owner before
;; real customers can order. Failing at boot is deliberate (the process exits
;; non-zero, see system/-main) so a mistaken production deploy cannot go
;; public with placeholder text. It only runs when SITE_INDEXABLE is true, so
;; staging and local development start without it.
(defn check!
  "Runs the launch gate. Arguments: `api-client` and an optional map {:retry-ms
  (default 60000) :interval-ms (default 2000)}. Returns nil (after logging
  :launch-gate-passed) when every policy is approved. Throws ex-info \"Launch
  gate: policy documents are not approved\" with {:policies [...]} immediately
  when any is not approved, and \"Launch gate: backend unreachable\" when the
  API is still unreachable after `retry-ms`. While the API is unreachable it
  sleeps and retries, so a backend that starts a little later does not stop
  the frontend server. Side effects: backend requests, log lines, sleeping."
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
