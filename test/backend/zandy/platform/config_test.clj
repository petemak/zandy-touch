(ns zandy.platform.config-test
  "Fitness check R9: configuration is validated at boot; the prod profile needs the mail settings."
  (:require [clojure.test :refer [deftest is testing]]
            [zandy.platform.config :as config]
            [zandy.platform.mailer :as mailer]))

(def valid-prod
  "A configuration map that passes the prod-profile rules, used as the base for the invalid variants."
  (-> (config/load-config :test)
      (assoc :profile :prod)
      (assoc-in [:datomic :uri] "datomic:dev://db.internal:4334/zandy?password=x")
      (assoc-in [:mail :impl] :smtp)
      (assoc-in [:mail :user] "owner@example.com")
      (assoc-in [:mail :password] "app-password-xyz")
      (assoc-in [:media :processor] :vips)
      (assoc-in [:auth :owner-email] "owner@example.com")
      (assoc-in [:auth :session-hmac-secret] (apply str (repeat 40 "s")))))

(defn- problems [cfg]
  (try (config/validate! cfg) nil
       (catch clojure.lang.ExceptionInfo e (:problems (ex-data e)))))

(deftest prod-profile-requires-the-mail-and-owner-settings
  (is (nil? (problems valid-prod)))
  (doseq [[path message] [[[:auth :owner-email] "OWNER_EMAIL"]
                          [[:mail :user] "SMTP_USER"]
                          [[:mail :password] "SMTP_PASSWORD"]
                          [[:auth :session-hmac-secret] "SESSION_HMAC_SECRET"]]]
    (testing message
      (is (some #(.contains ^String % message) (problems (assoc-in valid-prod path nil)))))))

(deftest prod-profile-rejects-unsafe-settings
  (is (some #(.contains ^String % "stub") (problems (assoc-in valid-prod [:media :processor] :stub))))
  (is (some #(.contains ^String % "in-memory") (problems (assoc-in valid-prod [:datomic :uri] "datomic:mem://x"))))
  (is (some? (problems (assoc-in valid-prod [:mail :impl] :log)))))

(deftest loading-prod-without-environment-fails-fast
  (when-not (System/getenv "OWNER_EMAIL")
    (is (thrown? clojure.lang.ExceptionInfo (config/load-config :prod)))))

(deftest defaults-and-mail-sender
  (let [cfg (config/load-config :test)]
    (is (= 400 (get-in cfg [:mail :daily-cap])))
    (is (= 10 (get-in cfg [:catalog :max-quantity-per-line])))
    (is (= 5 (get-in cfg [:catalog :low-stock-threshold])))
    (is (= false (get-in cfg [:site :allow-other-city?])))
    (is (= ["Kampala" "Entebbe" "Jinja" "Mbale" "Mbarara" "Fort Portal"] (get-in cfg [:site :countries 0 :cities]))))
  (let [m (mailer/make-mailer (:mail valid-prod) "owner@example.com")]
    (is (= "owner@example.com" (:from m)))))
