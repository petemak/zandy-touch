(ns zandy.platform.rate-limit
  "Fixed-window rate limits kept in Datomic so every instance shares them. A check reads first and
  rejects without writing once the limit is reached."
  (:require [datomic.api :as d]
            [zandy.platform.datomic :as datomic]
            [zandy.platform.errors :as errors]))

(defn- retry-after-seconds [^java.util.Date window-end ^java.util.Date now]
  (max 1 (long (Math/ceil (/ (- (.getTime window-end) (.getTime now)) 1000.0)))))

(defn blocked-for
  "Seconds until the key may be used again, or nil when it is under its limit."
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
  "Throws rateLimited when the key has used up its limit. Writes nothing."
  [conn k limit-cfg now]
  (when-let [seconds (blocked-for (d/db conn) k limit-cfg now)]
    (limited! seconds)))

(defn hit!
  "Counts one use of the key. Returns true when the hit was counted."
  [conn k {:keys [limit window-ms]} now]
  (let [result (datomic/transact! conn [[:ratelimit/hit k limit window-ms now]])
        aid (d/entid (:db-after result) :ratelimit/count)]
    (boolean (some (fn [^datomic.Datom dm] (= aid (.a dm))) (:tx-data result)))))

(defn consume!
  "Check, then count one use. Throws rateLimited when over the limit."
  [conn k limit-cfg now]
  (check! conn k limit-cfg now)
  (when-not (hit! conn k limit-cfg now)
    (limited! 1)))

(defn reset-key! [conn k]
  (when-let [e (d/entid (d/db conn) [:ratelimit/key k])]
    (datomic/transact! conn [[:db/retractEntity e]])))
