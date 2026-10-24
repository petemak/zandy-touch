(ns zandy.ssr.log-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [jsonista.core :as json]
            [zandy.ssr.api-client :as api-client]
            [zandy.ssr.config :as config]
            [zandy.ssr.http :as http]))

(deftest failure-logs-one-json-line-with-request-id-and-no-personal-data
  (let [cfg (config/load-config {:api-base-url "http://127.0.0.1:1" :api-timeout-ms 500})
        ctx {:config cfg :api (api-client/create cfg) :manifest {}}
        out (java.io.StringWriter.)
        resp (binding [*out* out]
               ;; a phone number in the query must never reach the log
               (http/handler ctx {:request-method :get :uri "/shop" :query-string "q=0771234567"
                                  :headers {"x-request-id" "req-123"}}))
        lines (remove str/blank? (str/split-lines (str out)))
        events (map #(json/read-value % json/keyword-keys-object-mapper) lines)
        request-line (first (filter #(= "request" (:event %)) events))]
    (is (= 503 (:status resp)))
    (is (every? #(map? %) events) "every line is one JSON object")
    (is (= "req-123" (:request-id request-line)))
    (is (= "shop" (:route request-line)))
    (is (= 503 (:status request-line)))
    (is (not (str/includes? (str out) "0771234567")))
    (is (not (str/includes? (str out) "q=")))
    (is (= "req-123" (get-in resp [:headers "X-Request-Id"])))))
