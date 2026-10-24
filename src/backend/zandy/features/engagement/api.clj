(ns zandy.features.engagement.api
  (:require [zandy.features.engagement.db :as db]
            [zandy.platform.log :as log]
            [zandy.platform.util :as util]))

(def retention-ms (* 183 24 60 60 1000))

(defn run-retention!
  "Removes contact messages older than six months."
  [ctx]
  (let [n (db/run-retention! (:conn ctx) (util/plus-ms (util/now ctx) (- retention-ms)))]
    (when (pos? n) (log/info :contact-retention {:messages n}))
    n))
