;; Fixed-window counter. Counts one hit unless the limit is already reached in the current window.
;; The caller learns whether a hit was counted from the transaction result (a :ratelimit/count datom).
{:params [db k limit window-ms now]}
(let [e (datomic.api/entity db [:ratelimit/key k])
      now-ms (.getTime now)
      active? (and e
                   (:ratelimit/window-end e)
                   (< now-ms (.getTime (:ratelimit/window-end e))))
      window-end (java.util.Date. (+ now-ms window-ms))]
  (cond
    (not active?)
    (if e
      [[:db/add (:db/id e) :ratelimit/count 1]
       [:db/add (:db/id e) :ratelimit/window-end window-end]]
      [{:ratelimit/key k :ratelimit/count 1 :ratelimit/window-end window-end}])

    (< (:ratelimit/count e) limit)
    [[:db/add (:db/id e) :ratelimit/count (inc (:ratelimit/count e))]]

    :else []))
