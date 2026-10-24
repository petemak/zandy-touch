(ns zandy.ssr.launch-gate-test
  (:require [clojure.test :refer [deftest is]]
            [integrant.core :as ig]
            [zandy.ssr.api-client :as api-client]
            [zandy.ssr.config :as config]
            [zandy.ssr.launch-gate :as gate]
            [zandy.ssr.stub-api :as stub]
            [zandy.ssr.system :as system]))

(defn- with-stub [approved? f]
  (let [server (stub/start 0)
        port (.getPort (.getAddress server))]
    (reset! stub/approved? approved?)
    (try (f port)
         (finally (reset! stub/approved? false) (.stop server 0)))))

(defn- client [port]
  (api-client/create (config/load-config {:api-base-url (str "http://127.0.0.1:" port) :api-timeout-ms 2000})))

(deftest startup-fails-while-a-policy-is-a-placeholder
  (with-stub false
    (fn [port]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not approved"
                            (gate/check! (client port) {:retry-ms 1000 :interval-ms 100})))
      (is (thrown? Exception
                   (system/start {:api-base-url (str "http://127.0.0.1:" port) :site-indexable true :port 0 :api-timeout-ms 2000}))
          "the system refuses to start with SITE_INDEXABLE=true"))))

(deftest startup-succeeds-when-all-approved
  (with-stub true
    (fn [port]
      (is (nil? (gate/check! (client port) {:retry-ms 1000 :interval-ms 100}))))))

(deftest staging-is-not-gated
  (with-stub false
    (fn [port]
      (let [sys (system/start {:api-base-url (str "http://127.0.0.1:" port) :site-indexable false :port 18931 :api-timeout-ms 2000})]
        (is (some? sys))
        (ig/halt! sys)))))

(deftest unreachable-backend-is-retried-then-fails
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unreachable"
                        (gate/check! (client 1) {:retry-ms 300 :interval-ms 100}))))
