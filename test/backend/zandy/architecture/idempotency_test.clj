(ns zandy.architecture.idempotency-test
  "Fitness check N5: every endpoint in api-contract.md section 1.6 requires an Idempotency-Key, replays
  on the same key and body, and rejects the same key with a different body."
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [zandy.test-helpers :as t]))

(defn- count-of [ctx attr] (count (d/q '[:find ?e :in $ ?a :where [?e ?a]] (t/db ctx) attr)))

(deftest every-idempotent-endpoint-behaves
  (let [ctx (t/make-ctx) app (t/app ctx)
        owner (t/setup-owner! ctx app)
        product (t/seed-product! ctx {:stock 5})
        staff-id (get-in (t/staff app owner :post "/api/admin/staff" :body {:name "Grace Atim" :email "grace@example.com"}) [:body :staff :id])
        endpoints
        [{:name "POST /api/orders" :attr :order/id :public? true :method :post :path "/api/orders"
          :body (t/order-body [[product 1]]) :other (t/order-body [[product 2]])}
         {:name "POST /api/admin/staff" :attr :user/id :method :post :path "/api/admin/staff"
          :body {:name "Hope Okello" :email "hope@example.com"} :other {:name "Ivy Auma" :email "ivy@example.com"} :delta 1}
         {:name "POST /api/admin/staff/{id}/password-resets" :method :post :path (str "/api/admin/staff/" staff-id "/password-resets")
          :body nil :other nil :no-different-body? true}
         {:name "POST /api/admin/products" :attr :product/id :method :post :path "/api/admin/products"
          :body {:name "Draft One" :brand "B" :category-slug "skincare" :status "draft"}
          :other {:name "Draft Two" :brand "B" :category-slug "skincare" :status "draft"}}
         {:name "POST /api/admin/products/{id}/stock-movements" :attr :stock-movement/id :method :post
          :path (str "/api/admin/products/" (:id product) "/stock-movements")
          :body {:delta 1 :reason "restock"} :other {:delta 2 :reason "restock"}}
         {:name "POST /api/subscriptions" :attr :subscriber/id :public? true :method :post :path "/api/subscriptions"
          :body {:email "a@example.com" :consent true :source "footer"} :other {:email "b@example.com" :consent true :source "footer"}}
         {:name "POST /api/contact-messages" :attr :contact-message/id :public? true :method :post :path "/api/contact-messages"
          :body {:name "Amina" :phone "0771234567" :message "Hello, do you deliver to Jinja?"}
          :other {:name "Amina" :phone "0771234567" :message "A different message to the shop."}}]]
    (doseq [{:keys [name attr method path body other public? no-different-body?]} endpoints]
      (testing name
        (let [send (fn [k b] (if public?
                               (t/call app method path :body b :headers (cond-> {} k (assoc "Idempotency-Key" k)))
                               (t/staff app owner method path :body b :idem (or k false))))
              before (when attr (count-of ctx attr))
              k (str (random-uuid))]
          (is (= "missingIdempotencyKey" (t/error-code (send nil body))) "missing key")
          (is (= "malformedRequest" (t/error-code (send "not-a-uuid" body))) "key must be a UUID")
          (let [a (send k body)
                b (send k body)]
            (is (< (:status a) 300) (str name " first call " (:status a)))
            (is (< (:status b) 300) (str name " replay " (:status b)))
            (is (= "true" (get-in b [:headers "Idempotent-Replayed"])))
            (is (nil? (get-in a [:headers "Idempotent-Replayed"])))
            (when attr (is (= 1 (- (count-of ctx attr) before)) "one record only")))
          (when-not no-different-body?
            (let [c (send k other)]
              (is (= 409 (:status c)))
              (is (= "idempotencyKeyReused" (t/error-code c))))))))
    (testing "temporary passwords are not replayed"
      (let [k (str (random-uuid))
            path (str "/api/admin/staff/" staff-id "/password-resets")
            a (t/staff app owner :post path :idem k)
            b (t/staff app owner :post path :idem k)]
        (is (string? (get-in a [:body :temporary-password])))
        (is (nil? (get-in b [:body :temporary-password])))))
    (testing "POST /api/admin/photos"
      (let [files [["a.jpg" (t/jpeg-bytes)]]
            before (count-of ctx :photo/id)
            k (str (random-uuid))
            missing (let [{:keys [content-type body]} (t/multipart files)]
                      (t/call app :post "/api/admin/photos" :raw-body body
                              :headers {"Content-Type" content-type "Cookie" (:cookie owner) "X-CSRF-Token" (:csrf owner)}))
            a (t/upload! app owner files :idem k)
            b (t/upload! app owner files :idem k)
            c (t/upload! app owner [["different.jpg" (t/jpeg-bytes 80 60)]] :idem k)]
        (is (= "missingIdempotencyKey" (t/error-code missing)))
        (is (= 201 (:status a)))
        (is (= "true" (get-in b [:headers "Idempotent-Replayed"])))
        (is (= (get-in a [:body :items 0 :photo :id]) (get-in b [:body :items 0 :photo :id])))
        (is (= 1 (- (count-of ctx :photo/id) before)))
        (is (= "idempotencyKeyReused" (t/error-code c)))))
    (testing "natural-key idempotency: status changes and deletes"
      (is (= 204 (:status (t/staff app owner :delete (str "/api/admin/products/" (:id product)) :idem false))))
      (is (= 204 (:status (t/staff app owner :delete (str "/api/admin/products/" (:id product)) :idem false)))))))

(deftest concurrent-identical-requests-take-effect-once
  (let [ctx (t/make-ctx) app (t/app ctx)
        owner (t/setup-owner! ctx app)
        k (str (random-uuid))
        body {:name "Racer" :brand "B" :category-slug "skincare" :status "draft"}
        results (doall (pmap (fn [_] (t/staff app owner :post "/api/admin/products" :body body :idem k)) (range 6)))]
    (is (every? #(= 201 (:status %)) results))
    (is (= 1 (count-of ctx :product/id)))
    (is (= 1 (count (distinct (map #(get-in % [:body :id]) results)))))))
