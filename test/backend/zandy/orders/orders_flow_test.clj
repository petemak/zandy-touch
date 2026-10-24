(ns zandy.orders.orders-flow-test
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [zandy.features.catalog.db :as catalog-db]
            [zandy.test-helpers :as t]))

(defn- stock [ctx product] (:stock-quantity (catalog-db/product-by-id (t/db ctx) (:id product))))

(deftest place-order-happy-path
  (let [ctx (t/make-ctx) app (t/app ctx)
        _ (t/create-user! ctx {:name "Sandra" :email "owner@example.com" :role :owner :notify? true})
        p (t/seed-product! ctx {:stock 10})
        key (str (random-uuid))
        r (t/place-order! app [[p 2]] key {})]
    (is (= 201 (:status r)))
    (let [order (get-in r [:body :order])]
      (is (re-matches #"ZT-[A-HJ-NP-Z2-9]{6}" (:order-number order)))
      (is (= "placed" (:status order)))
      (is (= "+256771234567" (get-in order [:customer :phone])))
      (is (= 170000 (:items-subtotal order)))
      (is (nil? (:delivery-fee order)))
      (is (nil? (:total order)))
      (is (= "orderTerms" (get-in r [:body :agreement-acceptance :type]))))
    (is (= 8 (stock ctx p)))
    (testing "stock movement of type order was written"
      (is (= #{[:order -2 8]}
             (d/q '[:find ?type ?delta ?after :where [?m :stock-movement/type ?type] [?m :stock-movement/delta ?delta]
                    [?m :stock-movement/quantity-after ?after] [?m :stock-movement/order]] (t/db ctx)))
          "one movement"))
    (testing "acceptance recorded with hash"
      (is (= 1 (count (d/q '[:find ?a :where [?a :acceptance/content-hash ?h]] (t/db ctx))))))
    (testing "replay returns the original order and takes no more stock"
      (let [r2 (t/place-order! app [[p 2]] key {})]
        (is (= 200 (:status r2)))
        (is (= "true" (get-in r2 [:headers "Idempotent-Replayed"])))
        (is (= (get-in r [:body :order :order-number]) (get-in r2 [:body :order :order-number])))
        (is (= 8 (stock ctx p)))))
    (testing "same key with a different body is rejected"
      (let [r3 (t/place-order! app [[p 3]] key {})]
        (is (= 409 (:status r3)))
        (is (= "idempotencyKeyReused" (t/error-code r3)))))
    (testing "owner alert (no staff opted in) and customer receipt are queued"
      (is (= 2 (count (d/q '[:find ?e :where [?e :outbox/status :pending]] (t/db ctx))))))))

(deftest place-order-validation-and-errors
  (let [ctx (t/make-ctx) app (t/app ctx)
        p (t/seed-product! ctx {:stock 3})]
    (testing "missing idempotency key"
      (let [r (t/call app :post "/api/orders" :body (t/order-body [[p 1]]))]
        (is (= "missingIdempotencyKey" (t/error-code r)))))
    (testing "idempotency key must be a uuid"
      (let [r (t/call app :post "/api/orders" :body (t/order-body [[p 1]]) :headers {"Idempotency-Key" "abc"})]
        (is (= "malformedRequest" (t/error-code r)))))
    (testing "unknown key rejected"
      (let [r (t/call app :post "/api/orders" :body (assoc (t/order-body [[p 1]]) :extra 1)
                      :headers {"Idempotency-Key" (str (random-uuid))})]
        (is (= 422 (:status r)))
        (is (= "unknownField" (get-in r [:body :error :details 0 :code])))))
    (testing "bad phone gives a field error"
      (let [r (t/place-order! app [[p 1]] (str (random-uuid)) {:customer {:name "Amina" :phone "123" :preferred-contact "phone"}})]
        (is (= 422 (:status r)))
        (is (= "customer.phone" (get-in r [:body :error :details 0 :field])))
        (is (= "invalidPhone" (get-in r [:body :error :details 0 :code])))))
    (testing "city outside the list"
      (let [r (t/place-order! app [[p 1]] (str (random-uuid)) {:delivery {:country-code "UG" :city "Gulu" :area "Center" :address-line "Main st"}})]
        (is (= 422 (:status r)))
        (is (= "delivery.city" (get-in r [:body :error :details 0 :field])))))
    (testing "agreement version"
      (let [r (t/place-order! app [[p 1]] (str (random-uuid)) {:agreement {:type "orderTerms" :version "1999"}})]
        (is (= "agreementVersionOutdated" (t/error-code r)))
        (is (= "2026-10-01" (get-in r [:body :error :details 0 :message])))))
    (testing "cartOutdated reports every failing line and takes nothing"
      (let [q (t/seed-product! ctx {:name "Other" :stock 0 :sku "OTH-1"})
            r (t/place-order! app [[p 5] [q 1]] (str (random-uuid)) {})
            codes (set (map :code (get-in r [:body :error :details])))]
        (is (= 409 (:status r)))
        (is (= "cartOutdated" (t/error-code r)))
        (is (= #{"insufficientStock" "soldOut"} codes))
        (is (= 3 (:available-quantity (first (filter #(= "insufficientStock" (:code %)) (get-in r [:body :error :details]))))))
        (is (= 3 (stock ctx p)))))
    (testing "price changed"
      (let [r (t/place-order! app [[(assoc p :price 1) 1]] (str (random-uuid)) {})]
        (is (= "priceChanged" (get-in r [:body :error :details 0 :code])))
        (is (= 85000 (get-in r [:body :error :details 0 :current-price])))))
    (testing "failed requests do not burn the key"
      (let [k (str (random-uuid))
            bad (t/place-order! app [[p 9]] k {})
            good (t/place-order! app [[p 1]] k {})]
        (is (= 409 (:status bad)))
        (is (= 201 (:status good)))))
    (testing "honeypot stores nothing"
      (let [before (stock ctx p)
            r (t/place-order! app [[p 1]] (str (random-uuid)) {:website "http://spam"})]
        (is (= 201 (:status r)))
        (is (= before (stock ctx p)))
        (is (nil? (d/q '[:find ?e . :where [?e :order/number "NOPE"]] (t/db ctx))))
        (let [tr (t/call app :post "/api/order-trackings" :body {:order-number (get-in r [:body :order :order-number]) :phone "0771234567"})]
          (is (= "orderNotFound" (t/error-code tr))))))))

(deftest concurrent-duplicate-requests-create-one-order
  (let [ctx (t/make-ctx) app (t/app ctx)
        p (t/seed-product! ctx {:stock 5})
        k (str (random-uuid))
        results (doall (pmap (fn [_] (t/place-order! app [[p 1]] k {})) (range 6)))]
    (is (= #{200 201} (set (map :status results))))
    (is (= 1 (count (filter #(= 201 (:status %)) results))))
    (is (= 4 (stock ctx p)))
    (is (= 1 (count (d/q '[:find ?o :where [?o :order/id]] (t/db ctx)))))))

(deftest tracking
  (let [ctx (t/make-ctx) app (t/app ctx)
        p (t/seed-product! ctx {:stock 5})
        r (t/place-order! app [[p 1]])
        number (get-in r [:body :order :order-number])]
    (let [ok (t/call app :post "/api/order-trackings" :body {:order-number number :phone "+256 771 234 567"})]
      (is (= 200 (:status ok)))
      (is (= "placed" (:status (:body ok))))
      (is (nil? (get-in ok [:body :delivery])))
      (is (= "Kampala" (get-in ok [:body :city]))))
    (is (= "orderNotFound" (t/error-code (t/call app :post "/api/order-trackings" :body {:order-number number :phone "0700000000"}))))
    (is (= "orderNotFound" (t/error-code (t/call app :post "/api/order-trackings" :body {:order-number "ZT-AAAAAA" :phone "0771234567"}))))))

(deftest staff-order-processing
  (let [ctx (t/make-ctx) app (t/app ctx)
        owner (t/setup-owner! ctx app)
        p (t/seed-product! ctx {:stock 10})
        r (t/place-order! app [[p 2]])
        id (get-in r [:body :order :id])
        path (str "/api/admin/orders/" id)
        change (fn [body] (t/staff app owner :post (str path "/status-changes") :body body))
        detail #(:body (t/staff app owner :get path))]
    (testing "list shows the order with counts"
      (let [l (:body (t/staff app owner :get "/api/admin/orders"))]
        (is (= 1 (:total-items l)))
        (is (= 1 (get-in l [:counts :placed])))))
    (testing "confirm and dispatch need a delivery fee"
      (is (= "deliveryFeeRequired" (t/error-code (change {:version 1 :status "confirmed"})))))
    (testing "record the fee"
      (let [v (t/staff app owner :patch path :body {:version 1 :delivery-fee 10000 :staff-note "Evening"})]
        (is (= 200 (:status v)))
        (is (= 2 (get-in v [:body :order :version])))
        (is (= 180000 (get-in v [:body :order :total])))
        (is (= "versionConflict" (t/error-code (t/staff app owner :patch path :body {:version 1 :delivery-fee 5000}))))))
    (testing "invalid transition"
      (is (= "invalidStatusTransition" (t/error-code (change {:version 2 :status "delivered" :payment-method-used "cash"})))))
    (testing "confirm"
      (let [c (change {:version 2 :status "confirmed" :note "Fee agreed by phone"})]
        (is (= 201 (:status c)))
        (is (= "confirmed" (get-in c [:body :order :status])))
        (is (false? (get-in c [:body :stock-restored])))))
    (testing "repeating the same status is a no-op"
      (let [c (change {:version 3 :status "confirmed"})]
        (is (= 200 (:status c)))
        (is (= 3 (get-in c [:body :order :version])))))
    (testing "version conflict"
      (is (= "versionConflict" (t/error-code (change {:version 1 :status "approvedForDelivery"})))))
    (testing "cancel requires a reason, restores stock before dispatch, once"
      (is (= 422 (:status (change {:version 3 :status "cancelled"}))))
      (let [c (change {:version 3 :status "cancelled" :cancel-reason "Customer did not agree the fee"})]
        (is (= 201 (:status c)))
        (is (true? (get-in c [:body :stock-restored])))
        (is (= 10 (stock ctx p)))
        (let [again (change {:version 4 :status "cancelled" :cancel-reason "again"})]
          (is (= 200 (:status again)))
          (is (= 10 (stock ctx p))))
        (is (= "invalidStatusTransition" (t/error-code (change {:version 4 :status "confirmed"}))))))
    (testing "history lists status changes with the staff name"
      (let [h (:history (detail))]
        (is (= ["placed" "confirmed" "cancelled"] (map :status h)))
        (is (= "Sandra" (get-in h [1 :by :name])))
        (is (nil? (get-in h [0 :by])))))))

(deftest cancel-after-dispatch-keeps-stock
  (let [ctx (t/make-ctx) app (t/app ctx)
        owner (t/setup-owner! ctx app)
        p (t/seed-product! ctx {:stock 4})
        id (get-in (t/place-order! app [[p 3]]) [:body :order :id])
        path (str "/api/admin/orders/" id)
        change (fn [body] (t/staff app owner :post (str path "/status-changes") :body body))]
    (t/staff app owner :patch path :body {:version 1 :delivery-fee 0})
    (is (= 201 (:status (change {:version 2 :status "confirmed"}))))
    (is (= 201 (:status (change {:version 3 :status "approvedForDelivery"}))))
    (let [c (change {:version 4 :status "cancelled" :cancel-reason "Customer refused on arrival"})]
      (is (= 201 (:status c)))
      (is (false? (get-in c [:body :stock-restored])))
      (is (= 1 (stock ctx p))))
    (testing "delivered needs a payment method"
      (let [id2 (get-in (t/place-order! app [[p 1]]) [:body :order :id])
            path2 (str "/api/admin/orders/" id2)]
        (t/staff app owner :patch path2 :body {:version 1 :delivery-fee 1000})
        (t/staff app owner :post (str path2 "/status-changes") :body {:version 2 :status "confirmed"})
        (t/staff app owner :post (str path2 "/status-changes") :body {:version 3 :status "approvedForDelivery"})
        (is (= 422 (:status (t/staff app owner :post (str path2 "/status-changes") :body {:version 4 :status "delivered"}))))
        (let [ok (t/staff app owner :post (str path2 "/status-changes") :body {:version 4 :status "delivered" :payment-method-used "cash"})]
          (is (= "delivered" (get-in ok [:body :order :status])))
          (is (= "cash" (get-in ok [:body :order :payment-method-used]))))))))
