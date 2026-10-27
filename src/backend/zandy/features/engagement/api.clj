(ns zandy.features.engagement.api
  "Engagement slice, api role: the only namespace other slices (and platform.system) may require. Exposes the contact-message retention job."
  (:require [zandy.features.engagement.db :as db]
            [zandy.platform.log :as log]
            [zandy.platform.util :as util]))

(def retention-ms
  "How long contact messages are kept: 183 days (about six months) in milliseconds."
  (* 183 24 60 60 1000))

(defn run-retention!
  "Deletes (excises) contact messages older than retention-ms. Argument: ctx (needs :conn and :clock).
  Returns the number removed in this run (at most 100). Side effects: Datomic excision, one info log line when something was removed."
  [ctx]
  (let [n (db/run-retention! (:conn ctx) (util/plus-ms (util/now ctx) (- retention-ms)))]
    (when (pos? n) (log/info :contact-retention {:messages n}))
    n))
