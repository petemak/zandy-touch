(ns zandy.shared.pages-test
  (:require [clojure.test :refer [deftest is testing]]
            [zandy.shared.pages :as pages]
            [zandy.shared.routes :as routes]))

(deftest query-normalisation
  (is (= {} (pages/normalise-query {:q "a" :junk "1" :sort "newest" :page "1"})))
  (is (= {:q "serum" :availability "available" :sort "priceAsc" :page 2}
         (pages/normalise-query {:q " serum " :availability "available" :sort "priceAsc" :page "2" :utm "x"})))
  (is (= 80 (count (:q (pages/normalise-query {:q (apply str (repeat 100 "a"))})))))
  (is (= {} (pages/normalise-query {:sort "bogus" :availability "x" :page "-3"}))))

(deftest requests-per-route
  (is (= #{:site :categories :new :own :featured} (set (keys (pages/requests (routes/match-url "/"))))))
  (is (= "/api/products/glow" (get-in (pages/requests (routes/match-url "/products/glow")) [:product :path])))
  (is (= "skincare" (get-in (pages/requests (routes/match-url "/categories/skincare")) [:products :query :categorySlug])))
  (is (= "/api/policies/termsOfSale" (get-in (pages/requests (routes/match-url "/legal/terms-of-sale")) [:policy :path])))
  (is (= {} (pages/requests (routes/match-url "/cart"))))
  (is (= {} (pages/requests (routes/match-url "/admin/products")))))

(def ok {:status 200 :body {}})

(deftest assembling
  (let [route (routes/match-url "/products/x")]
    (is (= :ok (get-in (pages/assemble route {:site {:status 200 :body {:phone "1"}} :product {:status 200 :body {:slug "x"}}}) [:page :status])))
    (is (= :not-found (get-in (pages/assemble route {:site ok :product {:status 404}}) [:page :status])))
    (is (= :unavailable (get-in (pages/assemble route {:site ok :product {:status 503}}) [:page :status])))
    (is (= :unavailable (get-in (pages/assemble route {:site ok :product {:status nil}}) [:page :status]))))
  (testing "unknown category is a 404"
    (let [route (routes/match-url "/categories/nope")
          r (pages/assemble route {:site ok :categories {:status 200 :body {:items [{:slug "skincare"}]}} :products ok})]
      (is (= :not-found (get-in r [:page :status])))
      (is (= 404 (pages/status->http :not-found))))))
