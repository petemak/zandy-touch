(ns zandy.notifications-test
  "The outbox: queued with the order, sent once by whichever worker claims it, capped per day."
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [zandy.features.notifications.api :as notifications]
            [zandy.platform.mailer :as mailer]
            [zandy.test-helpers :as t]))

(defn- status-counts [ctx]
  (frequencies (map first (d/q '[:find ?s ?e :where [?e :outbox/status ?s]] (t/db ctx)))))

(deftest order-emails-go-to-owner-opted-in-staff-and-customer
  (let [ctx (t/make-ctx) app (t/app ctx)
        _ (t/create-user! ctx {:name "Sandra" :email "owner@example.com" :role :owner})
        _ (t/create-user! ctx {:name "Grace" :email "grace@example.com" :role :support :notify? true})
        _ (t/create-user! ctx {:name "Hope" :email "hope@example.com" :role :support})
        p (t/seed-product! ctx {:stock 5})]
    (t/place-order! app [[p 2]])
    (t/place-order! app [[p 1]] (str (random-uuid)) {:customer {:name "No Email" :phone "0772000000" :preferred-contact "phone"}})
    (is (= {:pending 5} (status-counts ctx)) "2 alerts + 1 receipt, then 2 alerts and no receipt")
    (is (= 5 (notifications/process-once! ctx "w1")))
    (let [mail (t/sent-mail ctx)
          alerts (filter #(re-find #"New order" (:subject %)) mail)]
      (is (= {"owner@example.com" 2 "grace@example.com" 2 "amina@example.com" 1} (frequencies (map :to mail)))
          "owner and opted-in staff get both alerts, the customer with an email gets one receipt")
      (is (= 4 (count alerts)))
      (is (every? #(re-find #"/admin/orders/" (:text %)) alerts))
      (is (not-any? #(re-find #"Plot 12|amina@example" (:text %)) alerts) "no street address or email in alerts")
      (is (re-find #"delivery fee to be agreed|to be agreed" (:text (first (remove #(re-find #"New order" (:subject %)) mail))))))
    (is (= {:sent 5} (status-counts ctx)))
    (is (= 0 (notifications/process-once! ctx "w1")) "nothing is sent twice")))

(deftest only-one-worker-sends-each-message
  (let [ctx (t/make-ctx) app (t/app ctx)
        _ (t/create-user! ctx {:name "Sandra" :email "owner@example.com" :role :owner})
        p (t/seed-product! ctx {:stock 5})]
    (dotimes [_ 3] (t/place-order! app [[p 1]]))
    (let [sent (doall (pmap #(notifications/process-once! ctx %) ["a" "b" "c" "d"]))]
      (is (= 6 (reduce + sent)))
      (is (= 6 (count (t/sent-mail ctx)))))))

(deftest daily-cap-and-retries
  (let [ctx (t/make-ctx) app (t/app ctx)
        _ (t/create-user! ctx {:name "Sandra" :email "owner@example.com" :role :owner})
        ctx (assoc-in ctx [:config :mail :daily-cap] 2)
        p (t/seed-product! ctx {:stock 9})]
    (dotimes [_ 4] (t/place-order! app [[p 1]]))
    (testing "the cap stops sending and the rest stays queued"
      (is (= 2 (notifications/process-once! ctx "w")))
      (is (= 0 (notifications/process-once! ctx "w")))
      (is (= {:sent 2 :pending 6} (status-counts ctx))))
    (testing "the window passes and the rest goes out"
      (t/advance-clock! ctx (* 25 60 60 1000))
      (is (= 2 (notifications/process-once! ctx "w"))))))

(deftest failed-sends-are-retried-then-given-up-without-failing-requests
  (let [failing (reify mailer/Mailer (send! [_ _] (throw (ex-info "smtp down" {}))))
        ctx (t/make-ctx {:mailer failing})
        app (t/app ctx)
        _ (t/create-user! ctx {:name "Sandra" :email "owner@example.com" :role :owner})
        p (t/seed-product! ctx {:stock 5})]
    (is (= 201 (:status (t/place-order! app [[p 1]]))) "the order succeeds even though mail cannot be sent")
    (dotimes [_ 3] (is (= 0 (notifications/process-once! ctx "w"))))
    (is (= {:failed 2} (status-counts ctx)) "owner alert and customer receipt, three attempts each")))
