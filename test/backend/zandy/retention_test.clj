(ns zandy.retention-test
  "Customer personal data is excised (not just retracted) six months after an order closes."
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [zandy.features.engagement.api :as engagement]
            [zandy.features.orders.api :as orders]
            [zandy.test-helpers :as t]))

(declare retention-check*)

(defn- ever-had? [ctx attr]
  (boolean (seq (d/q '[:find ?v :in $ ?a :where [?e ?a ?v]] (d/history (t/db ctx)) attr))))

(deftest personal-data-is-excised-six-months-after-closing
  (if-not (t/dev-storage?)
    (println "SKIPPED zandy.retention-test: excision needs a transactor with dev storage. Set ZANDY_TEST_DATOMIC_URI_PREFIX=datomic:dev://localhost:4334/ (in-memory databases ignore :db/excise)")
    (retention-check*)))

(defn- retention-check* []
  (let [ctx (t/make-ctx) app (t/app ctx)
        owner (t/setup-owner! ctx app)
        p (t/seed-product! ctx {:stock 5})
        open-order (t/place-order! app [[p 1]])
        closed (t/place-order! app [[p 1]])
        id (get-in closed [:body :order :id])
        path (str "/api/admin/orders/" id)]
    (t/staff app owner :patch path :body {:version 1 :delivery-fee 0})
    (t/staff app owner :post (str path "/status-changes") :body {:version 2 :status "cancelled" :cancel-reason "Customer changed mind"})
    (t/call app :post "/api/contact-messages" :body {:name "Amina" :phone "0771234567" :message "Hello, do you deliver to Jinja?"}
            :headers {"Idempotency-Key" (str (random-uuid))})
    (is (ever-had? ctx :order/customer-name))
    (testing "nothing happens before six months"
      (t/advance-clock! ctx (* 100 24 60 60 1000))
      (is (= 0 (orders/run-retention! ctx)))
      (is (= 0 (engagement/run-retention! ctx))))
    (t/advance-clock! ctx (* 90 24 60 60 1000))
    (testing "after six months the closed order and the old message are excised, history included"
      (is (= 1 (orders/run-retention! ctx)))
      (is (= 1 (engagement/run-retention! ctx)))
      (let [hist (d/history (t/db ctx))
            names (d/q '[:find ?n :where [?o :order/customer-name ?n]] hist)]
        (is (= #{["Amina Nakato"]} names) "only the still-open order keeps its customer name, in history too")
        (is (= 1 (count (d/q '[:find ?o :where [?o :order/customer-name]] hist))))
        (is (empty? (d/q '[:find ?v :where [?o :order/delivery-address-line ?v] [?o :order/status :cancelled]] hist)))
        (is (empty? (d/q '[:find ?v :where [_ :contact-message/phone ?v]] hist)))))
    (testing "order number, lines and totals stay for the books"
      (let [owner (t/login! app "owner@example.com" t/owner-password)
            o (:body (t/staff app owner :get path :idem false))]
        (is (= 85000 (get-in o [:order :items-subtotal])))
        (is (= "cancelled" (get-in o [:order :status])))
        (is (some? (get-in o [:order :order-number])))))
    (testing "the job is safe to repeat"
      (is (= 0 (orders/run-retention! ctx))))
    (is (some? open-order))))
