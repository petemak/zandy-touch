(ns zandy.features.notifications.api
  "What other slices may use: message builders, enqueueing, direct sending and the worker."
  (:require [datomic.api :as d]
            [zandy.features.notifications.db :as db]
            [zandy.features.notifications.logic :as logic]
            [zandy.platform.log :as log]
            [zandy.platform.mailer :as mailer]
            [zandy.platform.util :as util]))

(def outbox-entity
  "Alias of logic/outbox-entity: (id kind to subject text) to an outbox entity map."
  logic/outbox-entity)
(def new-order-alert
  "Alias of logic/new-order-alert: builds the new-order alert {:subject :text}."
  logic/new-order-alert)
(def customer-receipt
  "Alias of logic/customer-receipt: builds the customer receipt {:subject :text}."
  logic/customer-receipt)
(def contact-message-mail
  "Alias of logic/contact-message-mail: builds the contact-message alert {:subject :text}."
  logic/contact-message-mail)
(def password-reset-mail
  "Alias of logic/password-reset-mail: builds the reset email {:subject :text}."
  logic/password-reset-mail)
(def enqueue-tx
  "Alias of db/enqueue-tx: (messages now) to tx-data queuing the messages as pending. Add it to the transaction of the event that causes them."
  db/enqueue-tx)

(defn send-now!
  "Sends one message directly, bypassing the outbox (used for password resets, which are never stored). Message map {:to :subject :text}.
  Returns true when sent, false when sending failed; failures are logged (exception class and message, no addresses) and never thrown. Side effect: one email."
  [ctx {:keys [to subject text]}]
  (try
    (mailer/send! (:mailer ctx) {:to to :subject subject :text text})
    true
    (catch Throwable ex
      (log/error :mail-send-failed (merge {:kind :direct} (log/exception-data ex)))
      false)))

(defn process-once!
  "One pass of the outbox worker. Arguments: ctx (:conn :clock :mailer :config); `claimer`, a string naming this instance.
  Sends up to 20 claimable messages per pass (pending, or claimed over five minutes ago) and never more than config mail :daily-cap in any 24 hours. Returns the number sent.
  Side effects: :db/cas claim, then send, then a mark-sent or mark-failed transaction per message; log lines."
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
           ;; Several instances run this worker. Only the one whose :db/cas claim succeeds sends the message. Delivery is at-least-once: a crash after sending but before mark-sent! lets a later pass resend.
           (if (db/try-claim! conn eid claimer now)
             (let [m (db/message (d/db conn) eid)]
               (try
                 (mailer/send! (:mailer ctx) (select-keys m [:to :subject :text]))
                 (db/mark-sent! conn eid (util/now ctx))
                 (inc n)
                 (catch Throwable ex
                   (log/error :mail-send-failed (merge {:kind (:kind m) :outbox-id eid} (log/exception-data ex)))
                   ;; A failed send goes back to pending until max-attempts is reached, then it is marked failed and no longer retried. Failures are logged by class only, never with addresses.
                   (db/mark-failed! conn eid (inc (:attempts m)) (.getName (class ex)))
                   n)))
             n))
         0 eids)))))

(defn purge-old!
  "Excises sent or failed outbox messages created before `cutoff` (they hold customer details). Returns how many. Side effect: Datomic excision."
  [ctx cutoff] (db/purge-older-than! (:conn ctx) cutoff))
