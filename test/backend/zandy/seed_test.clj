(ns zandy.seed-test
  "Fitness check B23 (part 2): the seed on an in-memory database."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [zandy.features.catalog.db :as catalog-db]
            [zandy.seed.core :as seed]
            [zandy.test-helpers :as t]))

(deftest seed-creates-the-sample-catalogue
  (let [ctx (t/make-ctx) app (t/app ctx)]
    (is (= 23 (seed/seed! ctx)))
    (testing "idempotent by SKU"
      (is (= 0 (seed/seed! ctx))))
    (let [products (catalog-db/all-products (t/db ctx))
          published (filter #(= :published (:status %)) products)]
      (is (= 22 (count published)))
      (is (= 1 (count (filter #(= :draft (:status %)) products))))
      (is (every? #(str/starts-with? (:sku %) "SEED-") products))
      (is (every? #(>= (count (:images %)) 1) published))
      (is (= 2 (count (:images (first (filter #(= "SEED-SK-01" (:sku %)) products))))))
      (is (= 2 (count (:images (first (filter #(= "SEED-PF-01" (:sku %)) products))))))
      (testing "every category has at least three products"
        (let [by-cat (frequencies (map #(get-in % [:category :slug]) published))]
          (is (= 6 (count by-cat)))
          (is (every? #(>= % 3) (vals by-cat)))))
      (testing "one sold out and one low-stock product"
        (is (some #(zero? (:stock-quantity %)) published))
        (is (some #(<= 1 (:stock-quantity %) 5) published)))
      (testing "the draft has no price and no image"
        (let [draft (first (filter #(= :draft (:status %)) products))]
          (is (nil? (:price draft)))
          (is (empty? (:images draft))))))
    (testing "opening stock is written as initial movements"
      (is (= #{:initial} (set (map first (d/q '[:find ?type :where [?m :stock-movement/type ?type]] (t/db ctx)))))))
    (testing "seed photos are marked seed?"
      (is (= (count (d/q '[:find ?p :where [?p :photo/id]] (t/db ctx)))
             (count (d/q '[:find ?p :where [?p :photo/seed? true]] (t/db ctx))))))
    (testing "the storefront serves them"
      (let [r (t/call app :get "/api/products?pageSize=100")]
        (is (= 22 (get-in r [:body :total-items])))
        (is (= 200 (:status (t/call app :get (get-in r [:body :items 0 :image :urls :medium])))))))))
