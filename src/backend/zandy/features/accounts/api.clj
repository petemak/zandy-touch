(ns zandy.features.accounts.api
  "What other slices and the HTTP platform may use from accounts."
  (:require [datomic.api :as d]
            [zandy.features.accounts.db :as db]
            [zandy.features.accounts.logic :as logic]
            [zandy.platform.util :as util]))

(defn authenticate
  "Resolves a raw session cookie value to {:user :session}, or nil when missing, expired, revoked
  or the user is deactivated. Refreshes the sliding expiry at most every five minutes."
  [ctx token]
  (let [conn (:conn ctx)
        now (util/now ctx)
        sess (db/session-for-token conn (util/sha256-hex token))]
    (when (and sess (logic/session-active? sess now))
      (let [user (db/user-by-eid (d/db conn) (:user-eid sess))]
        (when (and user (:active? user))
          (when (logic/touch-due? (:last-seen-at sess) now)
            (db/touch-session! conn (:eid sess) now (logic/session-expiry (:created-at sess) now)))
          {:user user :session sess})))))

(defn csrf-token [ctx session]
  (util/hmac-hex (get-in ctx [:config :auth :session-hmac-secret]) (str "csrf:" (:id-hash session))))

(defn csrf-valid? [ctx session header]
  (and (string? header) (util/constant-time-equals? header (csrf-token ctx session))))

(defn order-alert-recipients
  "Email addresses to alert when an order is placed."
  [ctx]
  (db/notified-emails (d/db (:conn ctx))))

(defn user-summaries
  "Map of entity id to {:id :name} for the given user entity ids (for history and 'updated by')."
  [ctx eids]
  (let [db (d/db (:conn ctx))]
    (into {} (keep (fn [e] (when-let [u (db/user-by-eid db e)] [e {:id (:id u) :name (:name u)}]))) (distinct (remove nil? eids)))))

(defn user-eid [ctx user-id]
  (d/entid (d/db (:conn ctx)) [:user/id user-id]))
