(ns zandy.platform.http-test
  "Fitness check R3: Pedestal 0.8's own router serves the route table; no Reitit on the backend."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [zandy.test-helpers :as t]))

(deftest deps-use-pedestal-0-8-without-reitit
  (let [deps (slurp "deps.edn")]
    (is (re-find #"io.pedestal/pedestal.service\s+\{:mvn/version \"0\.[89]" deps))
    (is (not (re-find #"(?i)reitit" deps)))))

(deftest routing-headers-and-errors
  (let [ctx (t/make-ctx) app (t/app ctx)]
    (testing "request ids are generated or reused, and echoed"
      (let [a (t/call app :get "/api/site-info")
            b (t/call app :get "/api/site-info" :headers {"X-Request-Id" "abc-12345678"})
            c (t/call app :get "/api/site-info" :headers {"X-Request-Id" "bad id!"})]
        (is (re-matches #"[0-9a-f-]{36}" (get-in a [:headers "X-Request-Id"])))
        (is (= "abc-12345678" (get-in b [:headers "X-Request-Id"])))
        (is (not= "bad id!" (get-in c [:headers "X-Request-Id"])))))
    (testing "nosniff on every response, JSON content type"
      (is (= "nosniff" (get-in (t/call app :get "/api/site-info") [:headers "X-Content-Type-Options"])))
      (is (= "nosniff" (get-in (t/call app :get "/nope") [:headers "X-Content-Type-Options"]))))
    (testing "unknown path and wrong method use the one error shape"
      (let [nf (t/call app :get "/api/nothing")
            na (t/call app :post "/api/site-info" :body {})]
        (is (= [404 "notFound"] [(:status nf) (t/error-code nf)]))
        (is (= 405 (:status na)))
        (is (= "GET" (get-in na [:headers "Allow"])))))
    (testing "path parameters"
      (is (= 200 (:status (t/call app :get "/api/policies/privacy"))))
      (is (= "notFound" (t/error-code (t/call app :get "/api/policies/other")))))
    (testing "no cookies on public GET responses and the cache headers match the contract"
      (doseq [[path cache] [["/api/site-info" "public, max-age=300, stale-while-revalidate=600"]
                            ["/api/delivery-areas" "public, max-age=3600, stale-while-revalidate=3600"]
                            ["/api/categories" "public, max-age=60, stale-while-revalidate=300"]
                            ["/api/policies/orderTerms" "public, max-age=600, stale-while-revalidate=600"]
                            ["/api/products" "public, max-age=30, stale-while-revalidate=60"]
                            ["/api/sitemap-entries" "public, max-age=3600"]
                            ["/api/healthz" "no-store"]]]
        (let [r (t/call app :get path)]
          (is (= cache (get-in r [:headers "Cache-Control"])) path)
          (is (nil? (get-in r [:headers "Set-Cookie"])) path)
          (is (not (str/includes? (str (get-in r [:headers "Vary"])) "Cookie")) path))))
    (testing "private and mutating responses are no-store"
      (is (= "no-store" (get-in (t/call app :post "/api/cart-validations" :body {:items [{:product-id (str (random-uuid)) :quantity 1}]})
                                [:headers "Cache-Control"])))
      (is (= "no-store" (get-in (t/call app :get "/api/admin/orders") [:headers "Cache-Control"]))))
    (testing "site info and delivery areas follow the owner decisions"
      (let [s (:body (t/call app :get "/api/site-info"))
            d (:body (t/call app :get "/api/delivery-areas"))]
        (is (= "+256706105779" (:whatsapp s)))
        (is (= "sandratonanyonjo@gmail.com" (:email s)))
        (is (= {:instagram nil :tiktok nil} (:social s)))
        (is (= {:days "Sunday" :opens "10:00" :closes "21:30"} (second (:opening-hours s))))
        (is (false? (:allow-other-city d)))
        (is (= "Fort Portal" (last (get-in d [:countries 0 :cities]))))))))

(deftest rate-limits-return-429-with-retry-after
  (let [ctx (-> (t/make-ctx) (update-in [:config :limits :contact] assoc :limit 2 :window-ms 60000))
        app (t/app ctx)
        post #(t/call app :post "/api/contact-messages" :body {:name "Amina" :phone "0771234567" :message "Hello, do you deliver?"}
                      :headers {"Idempotency-Key" (str (random-uuid)) "X-Client-Ip" "10.0.0.9"})]
    (is (= [201 201] [(:status (post)) (:status (post))]))
    (let [r (post)]
      (is (= 429 (:status r)))
      (is (= "rateLimited" (t/error-code r)))
      (is (<= 1 (parse-long (get-in r [:headers "Retry-After"])) 60)))
    (testing "another client address is unaffected"
      (is (= 201 (:status (t/call app :post "/api/contact-messages" :body {:name "Amina" :phone "0771234567" :message "Hello, do you deliver?"}
                                  :headers {"Idempotency-Key" (str (random-uuid)) "X-Client-Ip" "10.0.0.10"})))))
    (testing "the window passes"
      (t/advance-clock! ctx 61000)
      (is (= 201 (:status (post)))))))
