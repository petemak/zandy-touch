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

(defn subscriber-by-email [db email] (subscriber-map (d/pull db subscriber-pattern [:subscriber/email email])))
(defn subscriber-by-phone [db phone] (subscriber-map (d/pull db subscriber-pattern [:subscriber/phone phone])))
(defn subscriber-by-id [db id] (subscriber-map (d/pull db subscriber-pattern [:subscriber/id id])))

(defn all-subscribers [db]
  (->> (d/q '[:find [?e ...] :where [?e :subscriber/id]] db)
       (d/pull-many db subscriber-pattern)
       (keep subscriber-map)
       (sort-by :subscribed-at #(compare %2 %1))
       vec))

(defn subscribe! [conn {:keys [email phone source consent-text-version now]}]
  (datomic/transact! conn [(util/compact {:subscriber/id (random-uuid) :subscriber/email email :subscriber/phone phone
                                          :subscriber/source source :subscriber/consent-text-version consent-text-version
                                          :subscriber/subscribed-at now :subscriber/active? true})]))

(defn reactivate! [conn eid] (datomic/transact! conn [[:db/add eid :subscriber/active? true]]))
(defn deactivate! [conn eid] (datomic/transact! conn [[:db/add eid :subscriber/active? false]]))

(defn save-contact-message! [conn {:keys [id name phone email message now]} outbox-tx]
  (datomic/transact! conn
                     (into [(util/compact {:contact-message/id id :contact-message/name name :contact-message/phone phone
                                           :contact-message/email email :contact-message/message message
                                           :contact-message/received-at now})]
                           outbox-tx)))

(defn contact-messages [db]
  (->> (d/q '[:find [(pull ?e [:contact-message/id :contact-message/name :contact-message/phone :contact-message/email
                               :contact-message/message :contact-message/received-at]) ...]
              :where [?e :contact-message/id]] db)
       (map (fn [m] {:id (:contact-message/id m) :name (:contact-message/name m) :phone (:contact-message/phone m)
                     :email (:contact-message/email m) :message (:contact-message/message m)
                     :received-at (:contact-message/received-at m)}))
       (sort-by :received-at #(compare %2 %1))
       vec))

(defn run-retention!
  "Excises contact messages received before the cutoff."
  [conn ^Date cutoff]
  (let [eids (d/q '[:find [?e ...] :in $ ?cutoff :where [?e :contact-message/received-at ?t] [(< ?t ?cutoff)]]
                  (d/db conn) cutoff)]
    (doseq [e (take 100 eids)] (datomic/excise! conn [{:db/excise e}]))
    (min 100 (count eids))))

(defn current-db [conn] (d/db conn))
