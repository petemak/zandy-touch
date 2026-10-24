(ns zandy.platform.idempotency
  "Idempotency keys stored in Datomic (contract 1.6). A key is claimed with a transaction function,
  so of several concurrent identical requests exactly one runs the business logic; the others wait
  for its stored response and replay it."
  (:require [clojure.edn :as edn]
            [clojure.instant]
            [datomic.api :as d]
            [zandy.platform.datomic :as datomic])
  (:import (java.util Date UUID)))

(def stale-claim-ms 60000)
(def retention-ms (* 24 60 60 1000))

(defn- entity-map [db k]
  (when-let [e (d/entity db [:idempotency/scoped-key k])]
    {:state (:idempotency/state e)
     :body-hash (:idempotency/body-hash e)
     :token (:idempotency/claim-token e)
     :response (:idempotency/response e)
     :eid (:db/id e)}))

(defn- replay-of [{:keys [response]}]
  (edn/read-string {:readers {'inst clojure.instant/read-instant-date 'uuid #(UUID/fromString %)}} response))

(defn claim!
  "Returns {:status :owner :token t}, {:status :replay :response r}, {:status :conflict} (same key,
  different body) or {:status :busy} (another request still working on it after the wait)."
  [conn k body-hash now]
  (let [token (str (random-uuid))
        stale-before (Date. (- (.getTime ^Date now) stale-claim-ms))]
    (loop [attempt 0]
      (let [result (datomic/transact! conn [[:idempotency/claim k body-hash token now stale-before]])
            rec (entity-map (:db-after result) k)]
        (cond
          (= token (:token rec)) {:status :owner :token token}
          (not= body-hash (:body-hash rec)) {:status :conflict}
          (= :complete (:state rec)) {:status :replay :response (replay-of rec)}
          (>= attempt 50) {:status :busy}
          :else (do (Thread/sleep 100)
                    (let [fresh (entity-map (datomic/fresh-db conn) k)]
                      (cond
                        (nil? fresh) (recur (inc attempt))
                        (= :complete (:state fresh)) {:status :replay :response (replay-of fresh)}
                        :else (recur (inc attempt))))))))))

(defn complete!
  "Stores the response under the key (only if the caller still owns the claim)."
  [conn k token response]
  (let [rec (entity-map (d/db conn) k)]
    (when (= token (:token rec))
      (datomic/transact! conn [[:db/add (:eid rec) :idempotency/response (pr-str response)]
                               [:db/add (:eid rec) :idempotency/state :complete]]))))

(defn release!
  "Drops an unfinished claim so a corrected retry can run."
  [conn k token]
  (let [rec (entity-map (d/db conn) k)]
    (when (and rec (= token (:token rec)) (not= :complete (:state rec)))
      (datomic/transact! conn [[:db/retractEntity (:eid rec)]]))))

(defn cleanup!
  "Removes keys older than 24 hours. Safe to run on every instance."
  [conn now]
  (let [cutoff (Date. (- (.getTime ^Date now) retention-ms))
        eids (d/q '[:find [?e ...] :in $ ?cutoff
                    :where [?e :idempotency/created-at ?t] [(< ?t ?cutoff)]]
                  (d/db conn) cutoff)]
    (when (seq eids)
      (datomic/transact! conn (mapv (fn [e] [:db/retractEntity e]) eids)))
    (count eids)))
