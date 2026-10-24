(ns zandy.features.orders.api
  "Order operations other parts of the system run: the retention job."
  (:require [zandy.features.orders.db :as db]
            [zandy.platform.log :as log]
            [zandy.platform.util :as util]))

(def retention-ms (* 183 24 60 60 1000))

(defn run-retention!
  "Removes personal data of orders closed more than six months ago (small batch, safe to repeat)."
  [ctx]
  (let [cutoff (util/plus-ms (util/now ctx) (- retention-ms))
        n (db/run-retention! (:conn ctx) cutoff 25)]
    (when (pos? n) (log/info :orders-retention {:orders n}))
    n))
