(ns zandy.features.accounts.api
  "What other slices and the HTTP platform may use from accounts."
  (:require [datomic.api :as d]
            [zandy.features.accounts.db :as db]
            [zandy.features.accounts.logic :as logic]
            [zandy.platform.util :as util]))

(defn authenticate
  "Resolves a raw session cookie value `token` to {:user user-map :session session-map}, or nil when the session is unknown, expired, revoked or its user is deactivated. ctx needs :conn and :clock.
  Side effect: refreshes the sliding expiry and last-seen-at (one transaction) at most every five minutes. Looks the session up with a d/sync retry so a session created on another instance is found."
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

(defn csrf-token
  "The CSRF token for a session: HMAC-SHA256, keyed by the configured session secret, of the session id hash. Stateless, so any instance can verify it. Arguments: ctx, session map (needs :id-hash)."
  [ctx session]
  (util/hmac-hex (get-in ctx [:config :auth :session-hmac-secret]) (str "csrf:" (:id-hash session))))

(defn csrf-valid?
  "True when `header` (the X-CSRF-Token value, possibly nil) equals the session's csrf-token, compared in constant time."
  [ctx session header]
  (and (string? header) (util/constant-time-equals? header (csrf-token ctx session))))

(defn order-alert-recipients
  "Email addresses to alert when an order is placed: the owner plus active staff who opted in. Read only."
  [ctx]
  (db/notified-emails (d/db (:conn ctx))))

(defn user-summaries
  "Map of user entity id to {:id :name} for the given entity ids (nil ids ignored; unknown ids absent). Used for \"updated by\" and order history. Read only."
  [ctx eids]
  (let [db (d/db (:conn ctx))]
    (into {} (keep (fn [e] (when-let [u (db/user-by-eid db e)] [e {:id (:id u) :name (:name u)}]))) (distinct (remove nil? eids)))))

(defn user-eid
  "Datomic entity id of the user with uuid `user-id`, or nil."
  [ctx user-id]
  (d/entid (d/db (:conn ctx)) [:user/id user-id]))
