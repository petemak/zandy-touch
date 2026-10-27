(ns zandy.features.policies.api
  "Policies slice, api role: the only namespace other slices may require. Gives access to the current version of each policy document."
  (:require [zandy.features.policies.db :as db]
            [datomic.api :as d]))

(def policy-types
  "The four policy document types served by the API (contract 2), in display order."
  ["orderTerms" "termsOfSale" "privacy" "returns"])

(defn current-policy
  "The current policy of `type` (one of policy-types) as a map {:type :version :title :review-status :summary :body-blocks :content-hash :effective-from}, or nil.
  Arguments: ctx (needs :conn), type string. Reads the current database value."
  [ctx type]
  (db/current-policy (d/db (:conn ctx)) type))
