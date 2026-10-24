(ns zandy.architecture.contract-inventory-test
  "Fitness check B9: the route table and the headings of api-contract.md list the same endpoints."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is]]
            [zandy.architecture.contract :as contract]
            [zandy.platform.http :as http]))

(deftest route-inventory-equals-the-contract
  (let [from-contract (set (map (juxt :method :norm) (contract/endpoints)))
        from-routes (set (map (juxt :method #(contract/route-norm (:path %))) (http/route-table)))]
    (is (= #{} (set/difference from-contract from-routes)) "in the contract but not implemented")
    (is (= #{} (set/difference from-routes from-contract)) "implemented but not in the contract")))
