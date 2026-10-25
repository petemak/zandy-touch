(ns zandy.contract-conformance-test
  "Fitness check B8: camelCase keys, ISO 8601 UTC instants and the one error shape, checked over
  responses from a scenario that exercises most endpoints."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [jsonista.core :as j]
            [zandy.test-helpers :as t]))

(def ^:private iso-z #"^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ$")

(defn- violations
  "Walks parsed JSON (string keys) and returns a list of problems."
  [x path]
  (cond
    (map? x) (mapcat (fn [[k v]]
                       (concat (when-not (re-matches #"^[a-z][A-Za-z0-9]*$" k) [(str path "/" k " is not camelCase")])
                               (when (and (or (str/ends-with? k "At") (= k "at") (= k "effectiveFrom")) (some? v)
                                          (not (and (string? v) (re-matches iso-z v))))
                                 [(str path "/" k " is not an ISO 8601 UTC instant: " (pr-str v))])
                               (violations v (str path "/" k))))
                     x)
    (sequential? x) (mapcat (fn [i v] (violations v (str path "[" i "]"))) (range) x)
    :else nil))

(defn- error-shape-problems [body]
  (let [e (get body "error")]
    (cond-> []
      (not= #{"error"} (set (keys body))) (conj "error body has other keys")
      (not (string? (get e "code"))) (conj "no code")
      (not (string? (get e "message"))) (conj "no message")
      (not (every? #{"code" "message" "details"} (keys e))) (conj "unexpected error keys"))))

(deftest responses-follow-the-conventions
  (let [recorded (atom [])
        original t/call]
    (with-redefs [t/call (fn [& args]
                           (let [r (apply original args)]
                             (swap! recorded conj (select-keys r [:status :raw]))
                             r))]
      (let [ctx (t/make-ctx) app (t/app ctx)
            owner (t/setup-owner! ctx app)
            p (t/seed-product! ctx {:name "Glow Serum" :stock 5 :sku "GS-1"})
            order (t/place-order! app [[p 1]])
            id (get-in order [:body :order :id])
            number (get-in order [:body :order :order-number])]
        (doseq [path ["/api/site-info" "/api/delivery-areas" "/api/categories" "/api/policies/orderTerms" "/api/policies/privacy"
                      "/api/policies/nope" "/api/sitemap-entries" "/api/products" "/api/products/glow-serum" "/api/products/missing"
                      "/api/healthz" "/api/nothing" "/api/products?sort=zzz"]]
          (t/call app :get path))
        (t/call app :post "/api/cart-validations" :body {:items [{:product-id (str (:id p)) :quantity 2}]})
        (t/call app :post "/api/order-trackings" :body {:order-number number :phone "0771234567"})
        (t/call app :post "/api/order-trackings" :body {:order-number number :phone "0700000001"})
        (t/place-order! app [[p 99]])
        (t/call app :post "/api/orders" :body {} :headers {"Idempotency-Key" (str (random-uuid))})
        (t/call app :post "/api/subscriptions" :body {:email "a@example.com" :consent true :source "footer"}
                :headers {"Idempotency-Key" (str (random-uuid))})
        (t/call app :post "/api/contact-messages" :body {:name "Amina" :phone "0771234567" :message "Hello there, please call me."}
                :headers {"Idempotency-Key" (str (random-uuid))})
        (t/call app :post "/api/auth/password-resets" :body {:email "owner@example.com"})
        (t/call app :post "/api/auth/sessions" :body {:email "owner@example.com" :password "nope-nope-nope"})
        (t/staff app owner :get "/api/auth/sessions/current" :idem false)
        (t/staff app owner :patch (str "/api/admin/orders/" id) :body {:version 1 :delivery-fee 5000 :staff-note "x"})
        (t/staff app owner :post (str "/api/admin/orders/" id "/status-changes") :body {:version 2 :status "confirmed" :note "ok"})
        (t/staff app owner :get (str "/api/admin/orders/" id) :idem false)
        (t/staff app owner :get "/api/admin/orders" :idem false)
        (t/staff app owner :get "/api/admin/products" :idem false)
        (t/staff app owner :get (str "/api/admin/products/" (:id p)) :idem false)
        (t/staff app owner :post (str "/api/admin/products/" (:id p) "/stock-movements") :body {:delta 3 :reason "restock"})
        (t/staff app owner :get (str "/api/admin/products/" (:id p) "/stock-movements") :idem false)
        (t/upload-to-product! app owner (:id p) [["a.jpg" (t/jpeg-bytes)] ["bad.txt" (.getBytes "text")]])
        (t/upload-images! app owner [["nope.jpg" (t/jpeg-bytes)]])
        (t/import-csv! app owner "sku,name,brand,category,price\nCF-1,Conformance,B,skincare,5000\nCF-2,,,,abc\n")
        (t/staff app owner :get "/api/admin/product-import-template" :idem false)
        (t/staff app owner :post "/api/admin/product-publications" :body {:product-ids [(str (:id p)) (str (random-uuid))]})
        (t/staff app owner :get "/api/admin/contact-messages" :idem false)
        (t/staff app owner :get "/api/admin/subscribers" :idem false)
        (t/staff app owner :get "/api/admin/staff" :idem false)
        (t/staff app owner :post "/api/admin/staff" :body {:name "Grace Atim" :email "grace@example.com"})
        (t/staff app owner :post "/api/admin/products" :body {:name "Draft" :brand "B" :category-slug "skincare" :status "published"})))
    (let [bodies (filter #(and (seq (:raw %)) (str/starts-with? (str (:raw %)) "{")) @recorded)]
      (is (> (count bodies) 30))
      (doseq [{:keys [status raw]} bodies
              :let [parsed (j/read-value raw)]]
        (is (empty? (violations parsed "")) (str raw))
        (when (>= status 400)
          (is (empty? (error-shape-problems parsed)) (str raw)))))))
