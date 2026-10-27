(ns zandy.platform.rate-limit
  "Fixed-window rate limits kept in Datomic so every instance shares them. A check reads first and
  rejects without writing once the limit is reached."
  (:require [datomic.api :as d]
            [zandy.platform.datomic :as datomic]
            [zandy.platform.errors :as errors]))

(defn- retry-after-seconds [^java.util.Date window-end ^java.util.Date now]
  (max 1 (long (Math/ceil (/ (- (.getTime window-end) (.getTime now)) 1000.0)))))

(defn blocked-for
  "Seconds (at least 1) until key `k` may be used again, or nil when it is under its limit.
  `db` is a database value, limit-cfg a {:limit n ...} map, `now` a java.util.Date. Pure read."
  [db k {:keys [limit]} now]
  (when-let [e (d/entity db [:ratelimit/key k])]
    (let [window-end (:ratelimit/window-end e)]
      (when (and window-end
                 (< (.getTime ^java.util.Date now) (.getTime ^java.util.Date window-end))
                 (>= (or (:ratelimit/count e) 0) limit))
        (retry-after-seconds window-end now)))))

(defn- limited! [seconds]
  (errors/fail! :rateLimited "Too many requests. Please try again later." nil
                {"Retry-After" (str seconds)}))

(defn check!
  "Throws rateLimited (429, Retry-After header) when key `k` has used its limit in the current window; limit-cfg is {:limit n :window-ms ms}, `now` a java.util.Date. Writes nothing."
  [conn k limit-cfg now]
  (when-let [seconds (blocked-for (d/db conn) k limit-cfg now)]
    (limited! seconds)))

;; Counters are rewritten on every hit, so :ratelimit/count and :ratelimit/window-end are :db/noHistory in the schema: Datomic would otherwise keep every past count forever.
(defn hit!
  "Counts one use of key `k` in a fixed window of window-ms starting at the first hit. Returns true when counted, false when the limit was already reached.
  Side effect: one :ratelimit/hit transaction (which writes nothing when over the limit)."
  [conn k {:keys [limit window-ms]} now]
  ;; hit is a transaction function: the count only increases while under the limit and a new window starts when the old one ended.
  ;; Whether it counted is read back from the transaction result (a :ratelimit/count datom), so concurrent requests cannot exceed the limit.
  (let [result (datomic/transact! conn [[:ratelimit/hit k limit window-ms now]])
        aid (d/entid (:db-after result) :ratelimit/count)]
    (boolean (some (fn [^datomic.Datom dm] (= aid (.a dm))) (:tx-data result)))))

(defn consume!
  "check! then hit!: throws rateLimited (429) when over the limit, otherwise counts one use. Side effect: one Datomic transaction when under the limit."
  [conn k limit-cfg now]
  ;; Read first (check!) so requests over the limit are rejected without any write; a flood then causes at most `limit` writes per key per window.
  (check! conn k limit-cfg now)
  (when-not (hit! conn k limit-cfg now)
    (limited! 1)))

(defn reset-key!
  "Deletes the counter for key `k` (e.g. after a successful login), if there is one.
  Side effect: one Datomic transaction."
  [conn k]
  (when-let [e (d/entid (d/db conn) [:ratelimit/key k])]
    (datomic/transact! conn [[:db/retractEntity e]])))
