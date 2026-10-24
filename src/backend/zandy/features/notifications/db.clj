(ns zandy.features.notifications.db
  "Outbox persistence: enqueue tx-data, claiming, and marking messages sent or failed."
  (:require [datomic.api :as d]
            [zandy.platform.datomic :as datomic])
  (:import (java.util Date)))

(def stale-claim-ms (* 5 60 1000))
(def max-attempts 3)

(defn enqueue-tx
  "tx-data that queues the messages. Use it in the same transaction as the event that causes them."
  [messages now]
  (mapv #(assoc % :outbox/status :pending :outbox/created-at now :outbox/attempts 0) messages))

(defn sent-since [db ^Date since]
  (or (d/q '[:find (count ?e) . :in $ ?since
             :where [?e :outbox/sent-at ?t] [(>= ?t ?since)]]
           db since)
      0))

(defn claimable
  "Pending messages, plus claims older than five minutes (their sender probably died)."
  [db ^Date now limit]
  (let [stale-before (Date. (- (.getTime now) stale-claim-ms))
        pending (d/q '[:find [?e ...] :where [?e :outbox/status :pending]] db)
        stale (d/q '[:find [?e ...] :in $ ?before
                     :where [?e :outbox/status :claimed] [?e :outbox/claimed-at ?t] [(< ?t ?before)]]
                   db stale-before)]
    (->> (concat pending stale) (take limit) vec)))

(defn try-claim!
  "Claims the message with :db/cas. Returns true for the one instance that wins."
  [conn eid claimer now]
  (let [e (d/entity (d/db conn) eid)
        status (:outbox/status e)
        claimed-at (:outbox/claimed-at e)
        cas (case status
              :pending [:db/cas eid :outbox/status :pending :claimed]
              :claimed (when (and claimed-at (< (.getTime ^Date claimed-at) (- (.getTime ^Date now) stale-claim-ms)))
                         [:db/cas eid :outbox/claimed-at claimed-at now])
              nil)]
    (when cas
      (try (datomic/transact! conn [cas
                                    [:db/add eid :outbox/claimed-by claimer]
                                    [:db/add eid :outbox/claimed-at now]])
           true
           (catch Throwable _ false)))))

(defn message [db eid]
  (let [e (d/entity db eid)]
    {:eid eid :to (:outbox/to e) :subject (:outbox/subject e) :text (:outbox/body-text e)
     :attempts (or (:outbox/attempts e) 0) :kind (:outbox/kind e)}))

(defn mark-sent! [conn eid now]
  (datomic/transact! conn [[:db/add eid :outbox/status :sent] [:db/add eid :outbox/sent-at now]]))

(defn mark-failed! [conn eid attempts error-class]
  (datomic/transact! conn
                     [[:db/add eid :outbox/attempts attempts]
                      [:db/add eid :outbox/last-error error-class]
                      [:db/add eid :outbox/status (if (>= attempts max-attempts) :failed :pending)]]))

(defn purge-older-than!
  "Removes sent or failed messages older than the cutoff (they contain customer details)."
  [conn ^Date cutoff]
  (let [eids (d/q '[:find [?e ...] :in $ ?cutoff
                    :where [?e :outbox/created-at ?t] [(< ?t ?cutoff)]
                    (not [?e :outbox/status :pending]) (not [?e :outbox/status :claimed])]
                  (d/db conn) cutoff)]
    (when (seq eids)
      (datomic/excise! conn (mapv (fn [e] {:db/excise e}) eids)))
    (count eids)))
