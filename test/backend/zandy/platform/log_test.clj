(ns zandy.platform.log-test
  "Fitness check B12: structured logging with request ids and no personal data."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [jsonista.core :as j]
            [zandy.platform.log :as log]
            [zandy.test-helpers :as t]))

(deftest an-unexpected-error-logs-one-json-line-with-context
  (let [ctx (assoc (t/make-ctx) :conn "not-a-connection")
        app (t/app ctx)
        lines (atom [])]
    (binding [log/*sink* #(swap! lines conj %)]
      (let [r (t/call app :get "/api/categories" :headers {"X-Request-Id" "req-12345678"})]
        (is (= 500 (:status r)))
        (is (= "internalError" (t/error-code r)))
        (is (= "req-12345678" (get-in r [:headers "X-Request-Id"])))
        (is (not (str/includes? (:raw r) "ClassCast")) "no internals in the response")))
    (let [parsed (map j/read-value @lines)
          errors (filter #(= "unhandled-exception" (get % "event")) parsed)]
      (is (= 1 (count errors)))
      (is (= "req-12345678" (get-in (vec errors) [0 "requestId"])))
      (is (= "/api/categories" (get-in (vec errors) [0 "route"])))
      (is (every? #(get % "ts") parsed)))))

(deftest logs-contain-no-passwords-phones-or-emails
  (let [ctx (t/make-ctx) app (t/app ctx)
        lines (atom [])]
    (binding [log/*sink* #(swap! lines conj %)]
      (t/create-user! ctx {:name "Sandra" :email "owner@example.com" :role :owner})
      (t/login! app "owner@example.com" t/owner-password)
      (t/call app :post "/api/auth/sessions" :body {:email "owner@example.com" :password "wrong-password-1"})
      (let [p (t/seed-product! ctx {})]
        (t/place-order! app [[p 1]])))
    (let [all (str/join "\n" @lines)]
      (is (seq @lines))
      (doseq [secret [t/owner-password "wrong-password-1" "0771234567" "+256771234567" "amina@example.com" "owner@example.com" "Amina"]]
        (is (not (str/includes? all secret)) (str "log contains " secret))))))

(deftest sensitive-fields-are-redacted
  (let [lines (atom [])]
    (binding [log/*sink* #(swap! lines conj %)]
      (log/info :test {:phone "0771234567" :nested {:password "x"} :ok 1}))
    (let [m (j/read-value (first @lines))]
      (is (= "[redacted]" (get m "phone")))
      (is (= "[redacted]" (get-in m ["nested" "password"])))
      (is (= 1 (get m "ok"))))))
