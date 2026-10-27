(ns zandy.features.engagement.db
  "Subscriber and contact message persistence."
  (:require [datomic.api :as d]
            [zandy.platform.datomic :as datomic]
            [zandy.platform.util :as util])
  (:import (java.util Date)))

(defn- subscriber-map [m]
  (when (:subscriber/id m)
    {:eid (:db/id m) :id (:subscriber/id m) :email (:subscriber/email m) :phone (:subscriber/phone m)
     :subscribed-at (:subscriber/subscribed-at m) :source (:subscriber/source m) :active? (boolean (:subscriber/active? m))}))

(def ^:private subscriber-pattern
  [:db/id :subscriber/id :subscriber/email :subscriber/phone :subscriber/subscribed-at :subscriber/source :subscriber/active?])

(defn subscriber-by-email
  "Subscriber map {:eid :id :email :phone :subscribed-at :source :active?} with this (lower-case) email in database value `db`, or nil."
  [db email] (subscriber-map (d/pull db subscriber-pattern [:subscriber/email email])))
(defn subscriber-by-phone
  "Subscriber map (see subscriber-by-email) with this E.164 phone in `db`, or nil."
  [db phone] (subscriber-map (d/pull db subscriber-pattern [:subscriber/phone phone])))
(defn subscriber-by-id
  "Subscriber map (see subscriber-by-email) with this :subscriber/id uuid in `db`, or nil."
  [db id] (subscriber-map (d/pull db subscriber-pattern [:subscriber/id id])))

(defn all-subscribers
  "Every subscriber as a map (see subscriber-by-email), newest subscription first."
  [db]
  (->> (d/q '[:find [?e ...] :where [?e :subscriber/id]] db)
       (d/pull-many db subscriber-pattern)
       (keep subscriber-map)
       (sort-by :subscribed-at #(compare %2 %1))
       vec))

(defn subscribe!
  "Creates an active subscriber. `m` keys: :email, :phone (either may be nil), :source (keyword), :consent-text-version (string), :now (instant).
  Side effect: one Datomic transaction. Throws on a unique-attribute clash (email or phone already stored)."
  [conn {:keys [email phone source consent-text-version now]}]
  (datomic/transact! conn [(util/compact {:subscriber/id (random-uuid) :subscriber/email email :subscriber/phone phone
                                          :subscriber/source source :subscriber/consent-text-version consent-text-version
                                          :subscriber/subscribed-at now :subscriber/active? true})]))

(defn reactivate!
  "Marks the subscriber with entity id `eid` active again. Side effect: one transaction."
  [conn eid] (datomic/transact! conn [[:db/add eid :subscriber/active? true]]))
(defn deactivate!
  "Marks the subscriber with entity id `eid` inactive (unsubscribed). The record is kept. Side effect: one transaction."
  [conn eid] (datomic/transact! conn [[:db/add eid :subscriber/active? false]]))

(defn save-contact-message!
  "Stores a contact message and its outbox entries in one transaction (so the owner alert exists exactly when the message does).
  `m` keys: :id (uuid) :name :phone :email :message :now; `outbox-tx` is tx-data from notifications/enqueue-tx."
  [conn {:keys [id name phone email message now]} outbox-tx]
  (datomic/transact! conn
                     (into [(util/compact {:contact-message/id id :contact-message/name name :contact-message/phone phone
                                           :contact-message/email email :contact-message/message message
                                           :contact-message/received-at now})]
                           outbox-tx)))

(defn contact-messages
  "Every contact message as {:id :name :phone :email :message :received-at}, newest first."
  [db]
  (->> (d/q '[:find [(pull ?e [:contact-message/id :contact-message/name :contact-message/phone :contact-message/email
                               :contact-message/message :contact-message/received-at]) ...]
              :where [?e :contact-message/id]] db)
       (map (fn [m] {:id (:contact-message/id m) :name (:contact-message/name m) :phone (:contact-message/phone m)
                     :email (:contact-message/email m) :message (:contact-message/message m)
                     :received-at (:contact-message/received-at m)}))
       (sort-by :received-at #(compare %2 %1))
       vec))

(defn run-retention!
  "Excises up to 100 contact messages received before `cutoff` (a java.util.Date). Returns how many were selected for removal (at most 100).
  Side effect: one excision per message, each waiting for the transactor. Excision is used because the messages hold personal data that
  must really disappear, not just be retracted (retracted values stay in history)."
  [conn ^Date cutoff]
  (let [eids (d/q '[:find [?e ...] :in $ ?cutoff :where [?e :contact-message/received-at ?t] [(< ?t ?cutoff)]]
                  (d/db conn) cutoff)]
    (doseq [e (take 100 eids)] (datomic/excise! conn [{:db/excise e}]))
    (min 100 (count eids))))

(defn current-db
  "The current database value of `conn` (no d/sync)."
  [conn] (d/db conn))
