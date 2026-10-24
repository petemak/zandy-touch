(ns zandy.smoke-test
  (:require [clojure.test :refer [deftest is testing]]
            [zandy.test-helpers :as t]))

(deftest public-endpoints
  (let [ctx (t/make-ctx) app (t/app ctx)]
    (testing "site info"
      (let [r (t/call app :get "/api/site-info")]
        (is (= 200 (:status r)))
        (is (= "Zandy Touch" (get-in r [:body :business-name])))
        (is (= "public, max-age=300, stale-while-revalidate=600" (get-in r [:headers "Cache-Control"])))))
    (testing "categories"
      (let [r (t/call app :get "/api/categories")]
        (is (= 6 (count (get-in r [:body :items]))))))
    (testing "policy"
      (let [r (t/call app :get "/api/policies/orderTerms")]
        (is (= "placeholder" (get-in r [:body :review-status])))))
    (testing "404 and 405"
      (is (= "notFound" (t/error-code (t/call app :get "/api/nothing"))))
      (is (= 405 (:status (t/call app :delete "/api/site-info")))))
    (testing "healthz"
      (is (= 200 (:status (t/call app :get "/api/healthz")))))))
