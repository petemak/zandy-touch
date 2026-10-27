(ns zandy.features.notifications.db
  "Outbox persistence: enqueue tx-data, claiming, and marking messages sent or failed."
  (:require [datomic.api :as d]
            [zandy.platform.datomic :as datomic])
  (:import (java.util Date)))

(def stale-claim-ms
  "A claimed message whose sender made no progress for this long (5 minutes, milliseconds) may be claimed again."
  (* 5 60 1000))
(def max-attempts
  "Number of failed sends after which a message is marked :failed and no longer retried."
  3)

(defn enqueue-tx
  "tx-data that queues the messages (maps from logic/outbox-entity) as :pending with created-at `now` and zero attempts. Use it in the same transaction as the event that causes them so a message exists exactly when the event does."
  [messages now]
  (mapv #(assoc % :outbox/status :pending :outbox/created-at now :outbox/attempts 0) messages))

(defn sent-since
  "How many outbox messages were sent at or after `since` (java.util.Date); 0 when none. Used for the daily cap."
  [db ^Date since]
  (or (d/q '[:find (count ?e) . :in $ ?since
             :where [?e :outbox/sent-at ?t] [(>= ?t ?since)]]
           db since)
      0))

(defn claimable
  "Entity ids of messages that may be claimed at `now`: pending ones, plus claims older than five minutes (their sender probably died); at most `limit`. Read only."
  [db ^Date now limit]
  (let [stale-before (Date. (- (.getTime now) stale-claim-ms))
        pending (d/q '[:find [?e ...] :where [?e :outbox/status :pending]] db)
        stale (d/q '[:find [?e ...] :in $ ?before
                     :where [?e :outbox/status :claimed] [?e :outbox/claimed-at ?t] [(< ?t ?before)]]
                   db stale-before)]
    (->> (concat pending stale) (take limit) vec)))

;; Claiming is a compare-and-swap, so two instances cannot both win. A pending message is claimed by CAS on its status; an abandoned claim is taken over by CAS on its old claimed-at. The loser's transaction throws and returns false.
(defn try-claim!
  "Claims the outbox message `eid` for `claimer` (a string naming the instance) at `now` using :db/cas. Returns true for the one instance that wins, false (or nil) otherwise.
  A :pending message is claimed by swapping its status; a :claimed one only if its claim is older than stale-claim-ms. Side effect: one transaction."
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

(defn message
  "The outbox message with entity id `eid` as {:eid :to :subject :text :attempts :kind}."
  [db eid]
  (let [e (d/entity db eid)]
    {:eid eid :to (:outbox/to e) :subject (:outbox/subject e) :text (:outbox/body-text e)
     :attempts (or (:outbox/attempts e) 0) :kind (:outbox/kind e)}))

(defn mark-sent!
  "Marks the message sent at `now`. Side effect: one transaction."
  [conn eid now]
  (datomic/transact! conn [[:db/add eid :outbox/status :sent] [:db/add eid :outbox/sent-at now]]))

;; Only the exception class name is stored, never its message (which could contain addresses). :outbox/attempts and :outbox/claimed-* change often and are :db/noHistory in the schema.
(defn mark-failed!
  "Records a failed send: stores the attempt count and the exception class name (never the message text), and sets the status to :failed once `attempts` reaches max-attempts, else back to :pending. Side effect: one transaction."
  [conn eid attempts error-class]
  (datomic/transact! conn
                     [[:db/add eid :outbox/attempts attempts]
                      [:db/add eid :outbox/last-error error-class]
                      [:db/add eid :outbox/status (if (>= attempts max-attempts) :failed :pending)]]))

;; Excision, not retraction: sent mail bodies contain customer names and phone numbers, which must not survive in history.
(defn purge-older-than!
  "Excises sent or failed messages created before `cutoff` (java.util.Date); they contain customer details. Pending and claimed messages are kept. Returns the count. Side effect: one excision wait."
  [conn ^Date cutoff]
  (let [eids (d/q '[:find [?e ...] :in $ ?cutoff
                    :where [?e :outbox/created-at ?t] [(< ?t ?cutoff)]
                    (not [?e :outbox/status :pending]) (not [?e :outbox/status :claimed])]
                  (d/db conn) cutoff)]
    (when (seq eids)
      (datomic/excise! conn (mapv (fn [e] {:db/excise e}) eids)))
    (count eids)))
