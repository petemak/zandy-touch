(ns zandy.platform.idempotency
  "Idempotency keys stored in Datomic (contract 1.6). A key is claimed with a transaction function,
  so of several concurrent identical requests exactly one runs the business logic; the others wait
  for its stored response and replay it."
  (:require [clojure.edn :as edn]
            [clojure.instant]
            [datomic.api :as d]
            [zandy.platform.datomic :as datomic])
  (:import (java.util Date UUID)))

(def stale-claim-ms
  "Milliseconds after which an unfinished claim is treated as abandoned (crashed holder) and may be taken over."
  60000)
(def retention-ms
  "How long idempotency records are kept: 24 hours in milliseconds. cleanup! deletes older ones."
  (* 24 60 60 1000))

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
  "Tries to take ownership of the idempotency key `k` (the scoped key string) for a request whose body fingerprint is `body-hash`; `now` is a java.util.Date.
  Returns one of {:status :owner :token t} (run the handler, then complete! or release! with t),
  {:status :replay :response r} (the stored response map {:status :body}),
  {:status :conflict} (the key was used with a different body) or {:status :busy} (the original did not finish within the wait).
  Side effect: one :idempotency/claim transaction (writes only when the key is new or its claim is stale); may sleep while waiting."
  [conn k body-hash now]
  (let [token (str (random-uuid))
        stale-before (Date. (- (.getTime ^Date now) stale-claim-ms))]
    (loop [attempt 0]
      ;; The claim is a transaction function so concurrent requests are serialised on the transactor:
      ;; exactly one sees no record (or a stale one) and becomes the owner; for the others the claim writes nothing.
      (let [result (datomic/transact! conn [[:idempotency/claim k body-hash token now stale-before]])
            rec (entity-map (:db-after result) k)]
        (cond
          (= token (:token rec)) {:status :owner :token token}
          (not= body-hash (:body-hash rec)) {:status :conflict}
          (= :complete (:state rec)) {:status :replay :response (replay-of rec)}
          (>= attempt 50) {:status :busy}
          ;; Another request owns the key: poll up to ~5 s (50 x 100 ms) for its stored response, using fresh-db so a response committed on another instance becomes visible.
          :else (do (Thread/sleep 100)
                    (let [fresh (entity-map (datomic/fresh-db conn) k)]
                      (cond
                        (nil? fresh) (recur (inc attempt))
                        (= :complete (:state fresh)) {:status :replay :response (replay-of fresh)}
                        :else (recur (inc attempt))))))))))

;; The stored response is kept for 24 h only. :idempotency/* is :db/noHistory in the schema, so deleted records leave nothing behind in history.
(defn complete!
  "Stores `response` (a {:status :body} map, printed as EDN) under key `k` and marks it complete, but only when claim token `token` still owns the key; otherwise does nothing.
  Side effect: one Datomic transaction."
  [conn k token response]
  (let [rec (entity-map (d/db conn) k)]
    (when (= token (:token rec))
      (datomic/transact! conn [[:db/add (:eid rec) :idempotency/response (pr-str response)]
                               [:db/add (:eid rec) :idempotency/state :complete]]))))

(defn release!
  "Deletes the unfinished claim of `token` on key `k` so a retry can run. No-op when the record is complete or owned by someone else.
  Side effect: one Datomic transaction (retractEntity)."
  [conn k token]
  (let [rec (entity-map (d/db conn) k)]
    (when (and rec (= token (:token rec)) (not= :complete (:state rec)))
      (datomic/transact! conn [[:db/retractEntity (:eid rec)]]))))

(defn cleanup!
  "Deletes idempotency records created more than retention-ms before `now` (a java.util.Date). Returns the number removed.
  Side effect: one Datomic transaction. Safe to run concurrently on every instance (retracting twice is harmless). Called hourly by the jobs component."
  [conn now]
  (let [cutoff (Date. (- (.getTime ^Date now) retention-ms))
        eids (d/q '[:find [?e ...] :in $ ?cutoff
                    :where [?e :idempotency/created-at ?t] [(< ?t ?cutoff)]]
                  (d/db conn) cutoff)]
    (when (seq eids)
      (datomic/transact! conn (mapv (fn [e] [:db/retractEntity e]) eids)))
    (count eids)))
