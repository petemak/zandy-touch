(ns zandy.features.policies.api
  (:require [zandy.features.policies.db :as db]
            [datomic.api :as d]))

(def policy-types ["orderTerms" "termsOfSale" "privacy" "returns"])

(defn current-policy [ctx type]
  (db/current-policy (d/db (:conn ctx)) type))
