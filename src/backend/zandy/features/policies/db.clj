(ns zandy.features.policies.db
  "Policies slice, db role: queries for the versioned policy documents that migrations write."
  (:require [clojure.edn :as edn]
            [datomic.api :as d]))

(defn current-policy
  "The current version (:policy/current? true) of the policy of `type` in database value `db`, or nil.
  Returns {:type :version :title :review-status (keyword) :summary :body-blocks :content-hash :effective-from (instant)};
  summary and blocks are stored as EDN strings and are parsed back here. Read only."
  [db type]
  (when-let [m (d/q '[:find (pull ?e [:policy/type :policy/version :policy/title :policy/review-status
                                      :policy/summary :policy/blocks :policy/content-hash :policy/effective-from]) .
                      :in $ ?type :where [?e :policy/type ?type] [?e :policy/current? true]]
                    db type)]
    {:type (:policy/type m) :version (:policy/version m) :title (:policy/title m)
     :review-status (:policy/review-status m)
     :summary (edn/read-string (:policy/summary m))
     :body-blocks (edn/read-string (:policy/blocks m))
     :content-hash (:policy/content-hash m)
     :effective-from (:policy/effective-from m)}))
