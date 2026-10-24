(ns zandy.orders.place-order-concurrency-test
  "Fitness check B24: stock cannot oversell."
  (:require [clojure.test :refer [deftest is]]
            [datomic.api :as d]
            [zandy.features.catalog.db :as catalog-db]
            [zandy.test-helpers :as t]))

(deftest twenty-concurrent-orders-for-five-units
  (let [ctx (t/make-ctx) app (t/app ctx)
        p (t/seed-product! ctx {:stock 5})
        results (doall (pmap (fn [_] (t/place-order! app [[p 1]] (str (random-uuid)) {})) (range 20)))]
    (is (= {201 5, 409 15} (frequencies (map :status results))))
    (is (= #{"cartOutdated"} (set (map t/error-code (filter #(= 409 (:status %)) results)))))
    (is (= 0 (:stock-quantity (catalog-db/product-by-id (t/db ctx) (:id p)))))
    (is (= 5 (count (d/q '[:find ?o :where [?o :order/id]] (t/db ctx)))))
    (is (= 0 (d/q '[:find (sum ?d) . :with ?m :where [?m :stock-movement/delta ?d]] (t/db ctx)))
        "initial +5 and five orders of -1 sum to the final stock of zero")))

(deftest two-orders-for-the-same-last-unit-in-different-lines
  (let [ctx (t/make-ctx) app (t/app ctx)
        a (t/seed-product! ctx {:name "A" :stock 1})
        b (t/seed-product! ctx {:name "B" :stock 1})
        results (doall (pmap (fn [_] (t/place-order! app [[a 1] [b 1]] (str (random-uuid)) {})) (range 4)))]
    (is (= {201 1, 409 3} (frequencies (map :status results))))
    (is (= [0 0] (map #(:stock-quantity (catalog-db/product-by-id (t/db ctx) (:id %))) [a b])))))
