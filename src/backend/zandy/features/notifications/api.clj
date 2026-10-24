(ns zandy.features.notifications.api
  "What other slices may use: message builders, enqueueing, direct sending and the worker."
  (:require [datomic.api :as d]
            [zandy.features.notifications.db :as db]
            [zandy.features.notifications.logic :as logic]
            [zandy.platform.log :as log]
            [zandy.platform.mailer :as mailer]
            [zandy.platform.util :as util]))

(def outbox-entity logic/outbox-entity)
(def new-order-alert logic/new-order-alert)
(def customer-receipt logic/customer-receipt)
(def contact-message-mail logic/contact-message-mail)
(def password-reset-mail logic/password-reset-mail)
(def enqueue-tx db/enqueue-tx)

(defn send-now!
  "Sends one message directly (used for password resets, which are never stored). Failures are
  logged and never thrown."
  [ctx {:keys [to subject text]}]
  (try
    (mailer/send! (:mailer ctx) {:to to :subject subject :text text})
    true
    (catch Throwable ex
      (log/error :mail-send-failed (merge {:kind :direct} (log/exception-data ex)))
      false)))

(defn process-once!
  "One pass of the outbox worker: claims pending messages and sends them, respecting the daily cap.
  Returns the number of messages sent."
  [ctx claimer]
  (let [conn (:conn ctx)
        now (util/now ctx)
        cap (get-in ctx [:config :mail :daily-cap])
        sent-24h (db/sent-since (d/db conn) (util/plus-ms now (- (* 24 60 60 1000))))
        room (max 0 (- cap sent-24h))]
    (if (zero? room)
      (do (log/warn :mail-daily-cap-reached {:cap cap}) 0)
      (let [eids (db/claimable (d/db conn) now (min room 20))]
        (reduce
         (fn [n eid]
           (if (db/try-claim! conn eid claimer now)
             (let [m (db/message (d/db conn) eid)]
               (try
                 (mailer/send! (:mailer ctx) (select-keys m [:to :subject :text]))
                 (db/mark-sent! conn eid (util/now ctx))
                 (inc n)
                 (catch Throwable ex
                   (log/error :mail-send-failed (merge {:kind (:kind m) :outbox-id eid} (log/exception-data ex)))
                   (db/mark-failed! conn eid (inc (:attempts m)) (.getName (class ex)))
                   n)))
             n))
         0 eids)))))

(defn purge-old! [ctx cutoff] (db/purge-older-than! (:conn ctx) cutoff))
