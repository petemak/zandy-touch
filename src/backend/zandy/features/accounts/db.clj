(ns zandy.features.accounts.db
  "Accounts persistence: users, sessions, reset tokens."
  (:require [datomic.api :as d]
            [zandy.platform.datomic :as datomic]
            [zandy.platform.util :as util]))

(def user-pattern
  [:db/id :user/id :user/name :user/email :user/role :user/password-hash :user/active?
   :user/must-change-password? :user/notify-on-new-order? :user/created-at :user/last-sign-in-at])

(defn- user-map [m]
  (when (:user/id m)
    {:eid (:db/id m) :id (:user/id m) :name (:user/name m) :email (:user/email m) :role (:user/role m)
     :password-hash (:user/password-hash m) :active? (boolean (:user/active? m))
     :must-change-password? (boolean (:user/must-change-password? m))
     :notify-on-new-order? (boolean (:user/notify-on-new-order? m))
     :created-at (:user/created-at m) :last-sign-in-at (:user/last-sign-in-at m)}))

(defn user-by-email [db email]
  (user-map (d/pull db user-pattern [:user/email (.toLowerCase ^String email)])))

(defn user-by-id [db id]
  (user-map (d/pull db user-pattern [:user/id id])))

(defn user-by-eid [db eid]
  (user-map (d/pull db user-pattern eid)))

(defn all-users [db]
  (->> (d/q '[:find [?e ...] :where [?e :user/id]] db)
       (map #(user-by-eid db %))
       (sort-by (juxt (comp {:owner 0 :support 1} :role) :created-at))
       vec))

(defn owner [db]
  (first (filter #(= :owner (:role %)) (all-users db))))

(defn notified-emails
  "Addresses to alert for a new order: the owner plus every active staff member who opted in."
  [db]
  (->> (all-users db)
       (filter (fn [u] (and (:active? u) (or (= :owner (:role u)) (:notify-on-new-order? u)))))
       (map :email) distinct vec))

(defn user-tx [{:keys [id name email role password-hash must-change-password? notify-on-new-order? created-at]}]
  (util/compact
   {:user/id id :user/name name :user/email (.toLowerCase ^String email) :user/role role
    :user/password-hash password-hash :user/active? true
    :user/must-change-password? (boolean must-change-password?)
    :user/notify-on-new-order? (boolean notify-on-new-order?)
    :user/created-at created-at}))

;; sessions

(defn session-by-hash [db id-hash]
  (when-let [m (d/pull db [:db/id :session/id-hash :session/created-at :session/last-seen-at
                           :session/expires-at :session/revoked? {:session/user [:db/id]}]
                       [:session/id-hash id-hash])]
    (when (:session/id-hash m)
      {:eid (:db/id m) :id-hash (:session/id-hash m) :created-at (:session/created-at m)
       :last-seen-at (:session/last-seen-at m) :expires-at (:session/expires-at m)
       :revoked? (boolean (:session/revoked? m)) :user-eid (get-in m [:session/user :db/id])})))

(defn session-for-token
  "Looks the session up; when nothing is found, syncs with the transactor once and looks again so a
  session created by another instance a moment ago is found."
  [conn id-hash]
  (or (session-by-hash (d/db conn) id-hash)
      (session-by-hash (datomic/fresh-db conn) id-hash)))

(defn create-session! [conn id-hash user-eid created-at expires-at]
  (datomic/transact! conn [{:session/id-hash id-hash :session/user user-eid :session/created-at created-at
                            :session/last-seen-at created-at :session/expires-at expires-at}]))

(defn touch-session! [conn eid now expires-at]
  (datomic/transact! conn [[:db/add eid :session/last-seen-at now] [:db/add eid :session/expires-at expires-at]]))

(defn revoke-session! [conn eid]
  (datomic/transact! conn [[:db/add eid :session/revoked? true]]))

(defn revoke-user-sessions!
  "Ends every session of the user, except the one named by except-eid."
  [conn user-eid except-eid]
  (let [eids (d/q '[:find [?s ...] :in $ ?u :where [?s :session/user ?u] (not [?s :session/revoked? true])]
                  (d/db conn) user-eid)
        eids (remove #{except-eid} eids)]
    (when (seq eids)
      (datomic/transact! conn (mapv (fn [s] [:db/add s :session/revoked? true]) eids)))))

(defn record-sign-in! [conn user-eid now]
  (datomic/transact! conn [[:db/add user-eid :user/last-sign-in-at now]]))

;; password changes

(defn set-password! [conn user-eid password-hash]
  (datomic/transact! conn [[:db/add user-eid :user/password-hash password-hash]
                           [:db/add user-eid :user/must-change-password? false]]))

(defn set-temporary-password! [conn user-eid password-hash]
  (datomic/transact! conn [[:db/add user-eid :user/password-hash password-hash]
                           [:db/add user-eid :user/must-change-password? true]]))

;; reset tokens

(defn create-reset-token! [conn token-hash user-eid expires-at]
  (datomic/transact! conn [{:reset-token/token-hash token-hash :reset-token/user user-eid
                            :reset-token/expires-at expires-at}]))

(defn reset-token-by-hash [conn token-hash]
  (let [pull (fn [db] (d/pull db [:db/id :reset-token/expires-at :reset-token/used-at {:reset-token/user [:db/id]}]
                              [:reset-token/token-hash token-hash]))
        m (let [m (pull (d/db conn))] (if (:db/id m) m (pull (datomic/fresh-db conn))))]
    (when (:reset-token/expires-at m)
      {:eid (:db/id m) :expires-at (:reset-token/expires-at m) :used-at (:reset-token/used-at m)
       :user-eid (get-in m [:reset-token/user :db/id])})))

(defn use-reset-token! [conn eid now]
  (datomic/transact! conn [[:db/add eid :reset-token/used-at now]]))

;; staff management

(defn create-user! [conn user]
  (datomic/transact! conn [(user-tx user)]))

(defn update-user! [conn eid changes]
  (datomic/transact! conn (mapv (fn [[k v]] [:db/add eid k v]) changes)))

;; connection-based lookups for handlers

(defn find-user-by-email [conn email] (user-by-email (d/db conn) email))
(defn find-user-by-id [conn id] (user-by-id (d/db conn) id))
(defn list-users [conn] (all-users (d/db conn)))
