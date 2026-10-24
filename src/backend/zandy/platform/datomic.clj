(ns zandy.platform.datomic
  "Owns the Datomic peer connection and the thin I/O helpers every slice uses."
  (:require [datomic.api :as d]
            [zandy.platform.errors :as errors]))

(defn connect!
  "Creates the database when it does not exist (in-memory and dev storage) and connects."
  [uri]
  (d/create-database uri)
  (d/connect uri))

(defn release! [conn] (when conn (d/release conn)))

(defn db [conn] (d/db conn))

(defn fresh-db
  "Waits until this peer has seen everything the transactor has committed (d/sync), so a lookup
  that found nothing can be retried against an up-to-date view when several backend instances run."
  [conn]
  @(d/sync conn))

(defn- failure-data [ex]
  (loop [e ex]
    (when e
      (or (when (:zandy/failure (ex-data e)) (ex-data e)) (recur (ex-cause e))))))

(defn translate-failure
  "Rethrows a failure raised by a transaction function as the matching API error. Other
  exceptions are returned unchanged."
  [ex]
  (if-let [{:zandy/keys [failure] :keys [message details]} (failure-data ex)]
    (errors/fail! failure (or message "The request could not be completed.") details)
    ex))

(defn transact!
  "Transacts tx-data synchronously. Failures raised inside transaction functions become API errors."
  [conn tx-data]
  (try
    @(d/transact conn tx-data)
    (catch Throwable ex
      (let [translated (translate-failure ex)]
        (throw translated)))))

(defn pull [db pattern eid] (d/pull db pattern eid))

(defn lookup-eid [db attr value] (d/entid db [attr value]))

(defn excise!
  "Transacts excision requests and waits until the transactor has finished them (excision is
  asynchronous)."
  [conn tx-data]
  (let [result (transact! conn tx-data)]
    @(d/sync-excise conn (d/basis-t (:db-after result)))
    result))
