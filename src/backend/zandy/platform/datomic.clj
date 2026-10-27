(ns zandy.platform.datomic
  "Owns the Datomic peer connection and the thin I/O helpers every slice uses."
  (:require [datomic.api :as d]
            [zandy.platform.errors :as errors]))

(defn connect!
  "Creates the database at `uri` if it does not exist yet (in-memory and dev storage) and returns a connection.
  Side effect: may create the database. Throws if the transactor is unreachable."
  [uri]
  (d/create-database uri)
  (d/connect uri))

(defn release!
  "Releases the peer connection `conn` (nil-safe). Side effect: closes the connection."
  [conn] (when conn (d/release conn)))

(defn db
  "The current database value of `conn` as this peer sees it (may lag other instances by milliseconds; use fresh-db to catch up)."
  [conn] (d/db conn))

(defn fresh-db
  "Waits until this peer has seen everything the transactor has committed (d/sync), so a lookup
  that found nothing can be retried against an up-to-date view when several backend instances run."
  [conn]
  ;; Several backend instances are separate Datomic peers; a peer sees other peers' commits shortly after, not instantly.
  ;; d/sync blocks until this peer has caught up, giving read-your-writes across instances without sticky sessions.
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
  "Transacts `tx-data` on `conn` and blocks for the result map (:db-before :db-after :tx-data :tempids).
  Failures raised inside transaction functions are rethrown as API errors (notFound, versionConflict,
  cartOutdated, ...); any other exception propagates unchanged."
  [conn tx-data]
  (try
    @(d/transact conn tx-data)
    (catch Throwable ex
      ;; Transaction functions throw ex-info with :zandy/failure; the peer wraps it (ExecutionException), so we unwrap by walking the cause chain.
      (let [translated (translate-failure ex)]
        (throw translated)))))

(defn pull
  "Datomic pull of `pattern` for entity id or lookup ref `eid` against database value `db`. Returns a map."
  [db pattern eid] (d/pull db pattern eid))

(defn lookup-eid
  "Entity id for the unique attribute `attr` equal to `value` in `db`, or nil when there is none."
  [db attr value] (d/entid db [attr value]))

(defn excise!
  "Transacts excision requests and waits until the transactor has finished them (excision is
  asynchronous)."
  [conn tx-data]
  (let [result (transact! conn tx-data)]
    ;; Excision (real deletion of history for retention/privacy) is asynchronous on the transactor; wait so callers know the data is gone.
    @(d/sync-excise conn (d/basis-t (:db-after result)))
    result))
