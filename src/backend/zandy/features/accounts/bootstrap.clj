(ns zandy.features.accounts.bootstrap
  "clojure -M:bootstrap-owner : creates the owner account from OWNER_EMAIL and OWNER_INITIAL_PASSWORD.
  The owner must change the password at first sign-in. Never part of migrations or seed data."
  (:require [datomic.api :as d]
            [zandy.features.accounts.db :as db]
            [zandy.features.accounts.logic :as logic]
            [zandy.features.accounts.passwords :as passwords]
            [zandy.platform.config :as config]
            [zandy.platform.datomic :as datomic]
            [zandy.platform.log :as log])
  (:import (java.util Date)))

(defn bootstrap-owner!
  "Creates the owner when no owner exists yet. Returns :created, :exists or :skipped."
  [conn {:keys [owner-email owner-initial-password]}]
  (cond
    (db/owner (d/db conn)) :exists
    (or (nil? owner-email) (nil? owner-initial-password)) :skipped
    :else
    (do (when-let [problem (logic/password-problem owner-initial-password)]
          (throw (ex-info (str "OWNER_INITIAL_PASSWORD rejected: " problem) {})))
        (db/create-user! conn {:id (random-uuid) :name "Owner" :email owner-email :role :owner
                               :password-hash (passwords/hash-password owner-initial-password)
                               :must-change-password? true :notify-on-new-order? true
                               :created-at (Date.)})
        :created)))

(defn -main [& _]
  (let [cfg (config/load-config (config/profile-from-env))
        conn (datomic/connect! (get-in cfg [:datomic :uri]))
        result (bootstrap-owner! conn (:auth cfg))]
    (log/info :bootstrap-owner {:result result})
    (datomic/release! conn)
    (shutdown-agents)))
