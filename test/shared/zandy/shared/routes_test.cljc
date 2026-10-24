(ns zandy.shared.routes-test
  (:require [clojure.test :refer [deftest is testing]]
            [zandy.shared.routes :as routes]))

(deftest matches-pages
  (is (= :home (:name (routes/match-url "/"))))
  (is (= {:slug "glow-serum"} (:params (routes/match-url "/products/glow-serum"))))
  (is (= :category (:name (routes/match-url "/categories/skincare"))))
  (is (= :admin-orders (:name (routes/match-url "/admin"))))
  (is (= :admin-new-product (:name (routes/match-url "/admin/new-product"))))
  (is (= "orderTerms" (get-in (routes/match-url "/legal/order-terms") [:data :policy])))
  (is (nil? (routes/match-url "/nope")))
  (testing "trailing slash"
    (is (= :shop (:name (routes/match-url "/shop/"))))))

(deftest query-strings
  (is (= {:q "a b" :page "2"} (:query (routes/match-url "/shop?q=a%20b&page=2"))))
  (is (= "page=2&q=a%20b" (routes/encode-query {:q "a b" :page 2})))
  (is (= "" (routes/encode-query {:q nil :page ""}))))

(deftest builds-paths
  (is (= "/products/x" (routes/path-for :product {:slug "x"})))
  (is (= "/shop?q=serum" (routes/path-for :shop nil {:q "serum"}))))

(deftest every-route-has-a-name-and-no-param-beside-static
  (let [names (map (comp :name second) routes/routes)]
    (is (= (count names) (count (set names))))))
