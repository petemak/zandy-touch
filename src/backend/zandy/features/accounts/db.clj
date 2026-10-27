(ns zandy.features.accounts.db
  "Accounts persistence: users, sessions, reset tokens."
  (:require [datomic.api :as d]
            [zandy.platform.datomic :as datomic]
            [zandy.platform.util :as util]))

(def user-pattern
  "Datomic pull pattern for a user; the shape user-map reads."
  [:db/id :user/id :user/name :user/email :user/role :user/password-hash :user/active?
   :user/must-change-password? :user/notify-on-new-order? :user/created-at :user/last-sign-in-at])

(defn- user-map [m]
  (when (:user/id m)
    {:eid (:db/id m) :id (:user/id m) :name (:user/name m) :email (:user/email m) :role (:user/role m)
     :password-hash (:user/password-hash m) :active? (boolean (:user/active? m))
     :must-change-password? (boolean (:user/must-change-password? m))
     :notify-on-new-order? (boolean (:user/notify-on-new-order? m))
     :created-at (:user/created-at m) :last-sign-in-at (:user/last-sign-in-at m)}))

(defn user-by-email
  "The user map for this email (compared lower-case) in database value `db`, or nil."
  [db email]
  (user-map (d/pull db user-pattern [:user/email (.toLowerCase ^String email)])))

(defn user-by-id
  "The user map for uuid `id`, or nil."
  [db id]
  (user-map (d/pull db user-pattern [:user/id id])))

(defn user-by-eid
  "The user map for entity id `eid`, or nil. Maps look like {:eid :id :name :email :role :password-hash :active? :must-change-password? :notify-on-new-order? :created-at :last-sign-in-at}."
  [db eid]
  (user-map (d/pull db user-pattern eid)))

(defn all-users
  "Every user (owner first, then support staff by creation time) as user maps."
  [db]
  (->> (d/q '[:find [?e ...] :where [?e :user/id]] db)
       (map #(user-by-eid db %))
       (sort-by (juxt (comp {:owner 0 :support 1} :role) :created-at))
       vec))

(defn owner
  "The owner's user map, or nil when no owner exists."
  [db]
  (first (filter #(= :owner (:role %)) (all-users db))))

(defn notified-emails
  "Addresses to alert for a new order: the owner plus every active staff member who opted in."
  [db]
  (->> (all-users db)
       (filter (fn [u] (and (:active? u) (or (= :owner (:role u)) (:notify-on-new-order? u)))))
       (map :email) distinct vec))

(defn user-tx
  "Entity map for a new user from {:id :name :email :role :password-hash :must-change-password? :notify-on-new-order? :created-at}. The email is lower-cased, the user starts active, nil values are dropped. Pure."
  [{:keys [id name email role password-hash must-change-password? notify-on-new-order? created-at]}]
  (util/compact
   {:user/id id :user/name name :user/email (.toLowerCase ^String email) :user/role role
    :user/password-hash password-hash :user/active? true
    :user/must-change-password? (boolean must-change-password?)
    :user/notify-on-new-order? (boolean notify-on-new-order?)
    :user/created-at created-at}))

;; sessions

(defn session-by-hash
  "The session map {:eid :id-hash :created-at :last-seen-at :expires-at :revoked? :user-eid} whose id hash (SHA-256 of the cookie value) is given, or nil."
  [db id-hash]
  (when-let [m (d/pull db [:db/id :session/id-hash :session/created-at :session/last-seen-at
                           :session/expires-at :session/revoked? {:session/user [:db/id]}]
                       [:session/id-hash id-hash])]
    (when (:session/id-hash m)
      {:eid (:db/id m) :id-hash (:session/id-hash m) :created-at (:session/created-at m)
       :last-seen-at (:session/last-seen-at m) :expires-at (:session/expires-at m)
       :revoked? (boolean (:session/revoked? m)) :user-eid (get-in m [:session/user :db/id])})))

;; Sessions live in Datomic so any instance can serve any request. A peer may not have seen a session another instance just created, hence the d/sync retry on a miss.
(defn session-for-token
  "Looks the session up by id hash; when nothing is found, syncs with the transactor once and looks again so a session created by another instance a moment ago is found."
  [conn id-hash]
  (or (session-by-hash (d/db conn) id-hash)
      (session-by-hash (datomic/fresh-db conn) id-hash)))

(defn create-session!
  "Stores a new session. Arguments: conn, SHA-256 hex of the cookie token, user entity id, created-at and expires-at instants. Side effect: one transaction. last-seen-at starts equal to created-at."
  [conn id-hash user-eid created-at expires-at]
  (datomic/transact! conn [{:session/id-hash id-hash :session/user user-eid :session/created-at created-at
                            :session/last-seen-at created-at :session/expires-at expires-at}]))

;; last-seen-at is :db/noHistory in the schema, so this frequent write does not grow history.
(defn touch-session!
  "Records that the session was seen at `now` and sets its new `expires-at`. Side effect: one transaction; last-seen-at is :db/noHistory so repeated updates leave no history."
  [conn eid now expires-at]
  (datomic/transact! conn [[:db/add eid :session/last-seen-at now] [:db/add eid :session/expires-at expires-at]]))

(defn revoke-session!
  "Marks the session with entity id `eid` revoked (sign-out). Side effect: one transaction."
  [conn eid]
  (datomic/transact! conn [[:db/add eid :session/revoked? true]]))

(defn revoke-user-sessions!
  "Revokes every still-valid session of the user (entity id `user-eid`) except the one named by `except-eid` (nil revokes all). Used after password changes and deactivation. Side effect: one transaction when there is something to revoke."
  [conn user-eid except-eid]
  (let [eids (d/q '[:find [?s ...] :in $ ?u :where [?s :session/user ?u] (not [?s :session/revoked? true])]
                  (d/db conn) user-eid)
        eids (remove #{except-eid} eids)]
    (when (seq eids)
      (datomic/transact! conn (mapv (fn [s] [:db/add s :session/revoked? true]) eids)))))

(defn record-sign-in!
  "Stores the user's last sign-in time. Side effect: one transaction."
  [conn user-eid now]
  (datomic/transact! conn [[:db/add user-eid :user/last-sign-in-at now]]))

;; password changes

(defn set-password!
  "Stores a new password hash and clears must-change-password. Arguments: conn, user entity id, Argon2id hash. Side effect: one transaction."
  [conn user-eid password-hash]
  (datomic/transact! conn [[:db/add user-eid :user/password-hash password-hash]
                           [:db/add user-eid :user/must-change-password? false]]))

(defn set-temporary-password!
  "Stores a temporary password hash and requires the user to change it at next sign-in. Side effect: one transaction."
  [conn user-eid password-hash]
  (datomic/transact! conn [[:db/add user-eid :user/password-hash password-hash]
                           [:db/add user-eid :user/must-change-password? true]]))

;; reset tokens

(defn create-reset-token!
  "Stores a password-reset token record (only the SHA-256 of the token). Arguments: conn, token hash, user entity id, expires-at instant. Side effect: one transaction."
  [conn token-hash user-eid expires-at]
  (datomic/transact! conn [{:reset-token/token-hash token-hash :reset-token/user user-eid
                            :reset-token/expires-at expires-at}]))

;; Same d/sync retry on a miss as sessions: the reset link may be opened on a different instance than the one that created the token.
(defn reset-token-by-hash
  "The reset token record {:eid :expires-at :used-at :user-eid} for this SHA-256 token hash, or nil. Retries once after d/sync."
  [conn token-hash]
  (let [pull (fn [db] (d/pull db [:db/id :reset-token/expires-at :reset-token/used-at {:reset-token/user [:db/id]}]
                              [:reset-token/token-hash token-hash]))
        m (let [m (pull (d/db conn))] (if (:db/id m) m (pull (datomic/fresh-db conn))))]
    (when (:reset-token/expires-at m)
      {:eid (:db/id m) :expires-at (:reset-token/expires-at m) :used-at (:reset-token/used-at m)
       :user-eid (get-in m [:reset-token/user :db/id])})))

(defn use-reset-token!
  "Marks the reset token with entity id `eid` as used at `now`, so the link works once. Side effect: one transaction."
  [conn eid now]
  (datomic/transact! conn [[:db/add eid :reset-token/used-at now]]))

;; staff management

(defn create-user!
  "Creates a user from a map as in user-tx. Side effect: one transaction; throws if the email already exists (unique)."
  [conn user]
  (datomic/transact! conn [(user-tx user)]))

(defn update-user!
  "Sets attributes on a user. `changes` is a map from attribute keyword to new value, e.g. {:user/name \"A\"}. Side effect: one transaction."
  [conn eid changes]
  (datomic/transact! conn (mapv (fn [[k v]] [:db/add eid k v]) changes)))

;; connection-based lookups for handlers

(defn find-user-by-email
  "user-by-email on the current database of `conn`."
  [conn email] (user-by-email (d/db conn) email))
(defn find-user-by-id
  "user-by-id on the current database of `conn`."
  [conn id] (user-by-id (d/db conn) id))
(defn list-users
  "all-users on the current database of `conn`."
  [conn] (all-users (d/db conn)))
