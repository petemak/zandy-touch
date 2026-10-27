(ns zandy.shared.cart-test
  "Tests of shared.cart: add, merge, clamp, validation request and price
  acknowledgement. Runs on the JVM and in the Node test build."
  (:require [clojure.test :refer [deftest is testing]]
            [zandy.shared.cart :as cart]
            [zandy.shared.ui.order :as order]))

(def serum {:id "p1" :slug "serum" :name "Serum" :price 85000 :image nil})

(deftest add-merge-and-clamp
  (let [c (cart/add-item cart/empty-cart serum 2 10)]
    (is (= 2 (cart/count-items c)))
    (is (= 5 (cart/count-items (cart/add-item c serum 3 10))))
    (testing "never above the line maximum"
      (is (= 10 (cart/count-items (cart/add-item c serum 50 10)))))
    (testing "at most 20 lines"
      (let [full (reduce (fn [c i] (cart/add-item c (assoc serum :id (str "x" i)) 1 10)) cart/empty-cart (range 25))]
        (is (= 20 (count (:items full))))))))

(deftest set-and-remove
  (let [c (cart/add-item cart/empty-cart serum 2 10)]
    (is (= 1 (get-in (cart/set-quantity c "p1" 0 10) [:items 0 :quantity])))
    (is (= 4 (get-in (cart/set-quantity c "p1" 4 10) [:items 0 :quantity])))
    (is (empty? (:items (cart/remove-item c "p1"))))))

(deftest requests-and-validation
  (let [c (cart/add-item cart/empty-cart serum 2 10)
        v {:items [{:productId "p1" :unitPrice 90000 :name "Serum" :slug "serum" :image nil :problem nil}] :hasProblems false}]
    (is (= {:items [{:productId "p1" :quantity 2}]} (cart/validation-request c)))
    (testing "validation keeps the price the customer saw"
      (is (= 85000 (get-in (cart/apply-validation c v) [:items 0 :price]))))
    (testing "the price sent with the order is the validated price"
      (is (= [{:productId "p1" :quantity 2 :expectedUnitPrice 90000}] (cart/order-items c v))))
    (testing "blocked until validated without problems"
      (is (cart/blocked? nil))
      (is (cart/blocked? {:hasProblems true}))
      (is (not (cart/blocked? v))))
    (testing "signature changes with contents"
      (is (not= (cart/cart-signature c) (cart/cart-signature (cart/set-quantity c "p1" 3 10)))))))

(deftest price-changed-notice-lifecycle
  (let [c (cart/add-item cart/empty-cart serum 2 10)
        v {:items [{:productId "p1" :unitPrice 90000 :name "Serum" :slug "serum" :image nil :problem nil}] :hasProblems false}
        item #(first (:items %))
        notice #(order/price-changed-notice (first (:items v)) (item %))
        c2 (cart/apply-validation c v)]
    (testing "a changed price shows the notice with the new price"
      (is (= 85000 (:price (item c2))))
      (is (re-find #"Price changed\. Now UGX 90,000" (pr-str (notice c2)))))
    (testing "validating again does not clear it"
      (is (some? (notice (cart/apply-validation c2 v)))))
    (testing "proceeding to checkout acknowledges it"
      (let [c3 (cart/acknowledge-prices c2 v)]
        (is (= 90000 (:price (item c3))))
        (is (nil? (notice c3)))))
    (testing "an unchanged price shows no notice"
      (is (nil? (order/price-changed-notice {:unitPrice 85000} (item c)))))))
