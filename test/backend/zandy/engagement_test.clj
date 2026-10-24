(ns zandy.engagement-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [zandy.test-helpers :as t]))

(deftest subscriptions
  (let [ctx (t/make-ctx) app (t/app ctx)
        owner (t/setup-owner! ctx app)
        sub (fn [body & [k]] (t/call app :post "/api/subscriptions" :body body :headers {"Idempotency-Key" (or k (str (random-uuid)))}))]
    (testing "needs consent and a contact"
      (is (= 422 (:status (sub {:email "a@example.com" :consent false :source "footer"}))))
      (is (= 422 (:status (sub {:consent true :source "footer"}))))
      (is (= "missingIdempotencyKey" (t/error-code (t/call app :post "/api/subscriptions" :body {:email "a@example.com" :consent true :source "footer"})))))
    (testing "first signup is 201, a repeat is 200 with the same body"
      (let [a (sub {:email "Amina@Example.com" :consent true :source "footer"})
            b (sub {:email "amina@example.com" :consent true :source "home"})]
        (is (= 201 (:status a)))
        (is (= 200 (:status b)))
        (is (= (:body a) (:body b) {:subscribed true}))))
    (testing "honeypot looks like success and stores nothing"
      (is (= 201 (:status (sub {:email "bot@example.com" :consent true :source "footer" :website "http://x"}))))
      (let [l (t/staff app owner :get "/api/admin/subscribers" :idem false)]
        (is (= 1 (get-in l [:body :total-items])))))
    (testing "listing, csv and unsubscribe"
      (sub {:phone "0771234567" :consent true :source "footer"})
      (sub {:email "=cmd@example.com" :consent true :source "footer"})
      (let [l (t/staff app owner :get "/api/admin/subscribers" :idem false)
            items (get-in l [:body :items])
            url (:unsubscribe-url (first (filter #(= "amina@example.com" (:email %)) items)))
            token (second (re-find #"token=(.+)$" url))]
        (is (= 3 (count items)))
        (is (str/starts-with? url "http://localhost:8081/unsubscribe?token="))
        (let [csv (t/staff app owner :get "/api/admin/subscribers?format=csv" :idem false)]
          (is (str/starts-with? (get-in csv [:headers "Content-Type"]) "text/csv"))
          (is (str/includes? (:body csv) "'=cmd@example.com"))
          (is (str/includes? (:body csv) "'+256771234567")))
        (is (= 200 (:status (t/call app :post "/api/unsubscribes" :body {:token token}))))
        (is (= 200 (:status (t/call app :post "/api/unsubscribes" :body {:token "garbage.token"}))))
        (is (= 422 (:status (t/call app :post "/api/unsubscribes" :body {}))))
        (let [csv (t/staff app owner :get "/api/admin/subscribers?format=csv" :idem false)]
          (is (not (str/includes? (:body csv) "amina@example.com"))))
        (is (false? (:active (first (filter #(= "amina@example.com" (:email %))
                                            (get-in (t/staff app owner :get "/api/admin/subscribers" :idem false) [:body :items]))))))))))

(deftest contact-messages
  (let [ctx (t/make-ctx) app (t/app ctx)
        owner (t/setup-owner! ctx app)
        post (fn [body] (t/call app :post "/api/contact-messages" :body body :headers {"Idempotency-Key" (str (random-uuid))}))]
    (is (= 422 (:status (post {:name "Amina" :message "Short" :phone "0771234567"}))))
    (is (= 422 (:status (post {:name "Amina" :message "Hello, do you deliver to Jinja?"}))))
    (is (= 201 (:status (post {:name "Amina" :phone "0771234567" :message "Hello, do you deliver to Jinja?"}))))
    (is (= 201 (:status (post {:name "Bot" :email "b@example.com" :message "Buy cheap watches now" :website "x"}))))
    (let [l (t/staff app owner :get "/api/admin/contact-messages" :idem false)]
      (is (= 1 (get-in l [:body :total-items])))
      (is (= "+256771234567" (get-in l [:body :items 0 :phone]))))
    (testing "an email is queued for the owner, sent by the worker"
      (is (zero? (count (t/sent-mail ctx))))
      (is (= 1 ((requiring-resolve 'zandy.features.notifications.api/process-once!) ctx "test-worker")))
      (is (= 1 (count (t/sent-mail ctx))))
      (is (= "owner@example.invalid" (:to (first (t/sent-mail ctx)))))
      (is (zero? ((requiring-resolve 'zandy.features.notifications.api/process-once!) ctx "test-worker"))))))
