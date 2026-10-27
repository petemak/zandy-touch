(ns zandy.features.orders.db
  "Order persistence: reads and the retention excision."
  (:require [datomic.api :as d]
            [zandy.platform.datomic :as datomic])
  (:import (java.util Date)))

(def order-pattern
  "Datomic pull pattern for an order with its lines, events and acceptance record; the shape order-map reads."
  [:db/id :order/id :order/number :order/status :order/placed-at :order/customer-name :order/customer-phone
   :order/customer-email :order/preferred-contact :order/delivery-country :order/delivery-city :order/delivery-area
   :order/delivery-address-line :order/delivery-directions :order/payment-method :order/payment-method-used
   :order/note :order/staff-note :order/delivery-fee :order/delivery-fee-set-at :order/cancel-reason
   :order/stock-restored? :order/version :order/updated-at :order/idempotency-key
   {:order/lines [:order-line/product-id :order-line/product-name :order-line/unit-price :order-line/quantity
                  :order-line/position]}
   {:order/events [:order-event/id :order-event/status :order-event/at :order-event/note {:order-event/by [:db/id]}]}
   {:order/acceptance [:acceptance/policy-type :acceptance/policy-version :acceptance/content-hash
                       :acceptance/accepted-at :acceptance/customer-name :acceptance/customer-phone]}])

(defn order-map
  "Converts a pulled order entity to the order map {:eid :id :number :status :placed-at :customer-name :customer-phone :customer-email :preferred-contact :delivery-country :delivery-city :delivery-area :delivery-address-line :delivery-directions :payment-method :payment-method-used :note :staff-note :delivery-fee :cancel-reason :stock-restored? :version :updated-at :idempotency-key :lines [...] :events [{:id :status :at :note :by-eid}] :acceptance {...}}. Nil when the entity has no order id. Money is UGX, times are java.util.Date."
  [m]
  (when (:order/id m)
    {:eid (:db/id m) :id (:order/id m) :number (:order/number m) :status (:order/status m)
     :placed-at (:order/placed-at m) :customer-name (:order/customer-name m) :customer-phone (:order/customer-phone m)
     :customer-email (:order/customer-email m) :preferred-contact (:order/preferred-contact m)
     :delivery-country (:order/delivery-country m) :delivery-city (:order/delivery-city m)
     :delivery-area (:order/delivery-area m) :delivery-address-line (:order/delivery-address-line m)
     :delivery-directions (:order/delivery-directions m) :payment-method (:order/payment-method m)
     :payment-method-used (:order/payment-method-used m) :note (:order/note m) :staff-note (:order/staff-note m)
     :delivery-fee (:order/delivery-fee m) :cancel-reason (:order/cancel-reason m)
     :stock-restored? (boolean (:order/stock-restored? m)) :version (:order/version m)
     :updated-at (:order/updated-at m) :idempotency-key (:order/idempotency-key m)
     :lines (mapv (fn [l] {:product-id (:order-line/product-id l) :name (:order-line/product-name l)
                           :unit-price (:order-line/unit-price l) :quantity (:order-line/quantity l)
                           :position (:order-line/position l)})
                  (:order/lines m))
     :events (mapv (fn [e] {:id (:order-event/id e) :status (:order-event/status e) :at (:order-event/at e)
                            :note (:order-event/note e) :by-eid (get-in e [:order-event/by :db/id])})
                   (:order/events m))
     :acceptance (when-let [a (:order/acceptance m)]
                   {:policy-type (:acceptance/policy-type a) :policy-version (:acceptance/policy-version a)
                    :content-hash (:acceptance/content-hash a) :accepted-at (:acceptance/accepted-at a)
                    :customer-name (:acceptance/customer-name a) :customer-phone (:acceptance/customer-phone a)})}))

(defn order-by-id
  "The order map with uuid `id` in database value `db`, or nil."
  [db id] (order-map (d/pull db order-pattern [:order/id id])))
(defn order-by-number
  "The order map with this order number (\"ZT-XXXXXX\") in `db`, or nil."
  [db number] (order-map (d/pull db order-pattern [:order/number number])))
(defn order-by-idempotency-key
  "The order map that was placed under this scoped idempotency key (\"POST /api/orders <key>\"), or nil."
  [db k] (order-map (d/pull db order-pattern [:order/idempotency-key k])))

(defn all-orders
  "Every order as an order map. Staff lists filter and page this in memory (tens of orders a day)."
  [db]
  (->> (d/q '[:find [?e ...] :where [?e :order/id]] db)
       (d/pull-many db order-pattern)
       (keep order-map)
       vec))

(defn order-by-id-fresh
  "Looks up an order by uuid; when nothing is found, syncs once (d/sync) and tries again (multi-instance read-your-writes). Returns the order map or nil."
  [conn id]
  ;; Read-your-writes across instances: this peer may not have seen a just-committed order yet, so if nothing is found sync once and look again.
  (or (order-by-id (d/db conn) id) (order-by-id (datomic/fresh-db conn) id)))

(defn order-by-number-fresh
  "Like order-by-id-fresh, by order number."
  [conn number]
  (or (order-by-number (d/db conn) number) (order-by-number (datomic/fresh-db conn) number)))

(defn order-by-idempotency-key-fresh
  "Like order-by-id-fresh, by scoped idempotency key."
  [conn k]
  (or (order-by-idempotency-key (d/db conn) k) (order-by-idempotency-key (datomic/fresh-db conn) k)))

;; ---- writes ------------------------------------------------------------------------------------------

;; The transaction function runs on the transactor, so it sees one consistent database: stock, price and status are checked and the order, stock decrements, stock movements, acceptance, history and outbox messages are written together, or nothing is (409 cartOutdated).
;; Two customers buying the last unit are serialised there, so stock cannot be oversold and no partial order exists, whichever backend instance handled the request.
(defn place-order!
  "Places an order by running the :orders/place-order transaction function on the transactor; either everything is written or nothing is.
  `spec` keys: :now :order-id :order-number :idem-key :customer :delivery :payment-method :note :lines [{:product-id :quantity :expected-price}] :acceptance {:id :policy-type :policy-version :content-hash} :event-id :movement-ids :outbox (outbox entity maps).
  Throws cartOutdated (409) with per-line details when any product is unavailable, sold out, short of stock or changed price. A duplicate :idem-key or :order-number fails with a unique-attribute error that the caller interprets."
  [conn spec]
  (datomic/transact! conn [[:orders/place-order spec]]))

;; Same idea for status changes: the transition rules, the version check and the stock restore on cancellation happen inside one transaction function, so a cancel restores stock exactly once.
(defn change-status!
  "Changes an order's status by running the :orders/change-status transaction function. `spec` keys: :order-id :version :status (keyword) :note :by (user uuid) :payment-method-used :cancel-reason :now :event-id :movement-ids.
  Throws notFound, versionConflict, invalidStatusTransition or deliveryFeeRequired (409). Requesting the current status writes nothing. Cancelling an unshipped order restores stock in the same transaction."
  [conn spec]
  (datomic/transact! conn [[:orders/change-status spec]]))

(defn patch-order!
  "Sets the delivery fee and/or staff note of `order` (an order map), guarded by its version with :db/cas. `opts`: :delivery-fee (UGX, nil leaves it), :staff-note, :user-eid, :now. Bumps the version. Throws when the version is stale (the caller maps that to versionConflict)."
  [conn order {:keys [delivery-fee staff-note user-eid now]}]
  (let [oid (:eid order)]
    (datomic/transact! conn
                       (vec (concat
                             [[:db/cas oid :order/version (:version order) (inc (:version order))]
                              [:db/add oid :order/updated-at now]]
                             (when delivery-fee
                               (concat [[:db/add oid :order/delivery-fee delivery-fee]
                                        [:db/add oid :order/delivery-fee-set-at now]]
                                       (when user-eid [[:db/add oid :order/delivery-fee-set-by user-eid]])))
                             (when staff-note [[:db/add oid :order/staff-note staff-note]]))))))

;; ---- retention -----------------------------------------------------------------------------------------

(def personal-order-attrs
  "The order attributes that hold customer personal data and are excised after retention."
  [:order/customer-name :order/customer-phone :order/customer-email :order/delivery-area
   :order/delivery-address-line :order/delivery-directions])

(defn expired-order-eids
  "Entity ids of closed orders (delivered or cancelled) last updated before `cutoff` (java.util.Date) that still hold personal data (a customer name). Read only."
  [db ^Date cutoff]
  (d/q '[:find [?o ...] :in $ ?cutoff
         :where [?o :order/status ?s] [(contains? #{:delivered :cancelled} ?s)]
         [?o :order/updated-at ?t] [(< ?t ?cutoff)]
         [?o :order/customer-name]]
       db cutoff))

(defn excise-order-personal-data!
  "Excises (permanently removes, history included) the personal attributes of an order and the customer name/phone of its acceptance record. Retraction would not be enough because Datomic keeps history.
  Side effect: up to two excision requests, each waiting for the transactor to finish."
  [conn order-eid]
  (let [db (d/db conn)
        acceptance (:db/id (:order/acceptance (d/pull db [{:order/acceptance [:db/id]}] order-eid)))]
    ;; Excision (not retraction) is required: retracted values stay in Datomic history. Only the personal attributes are removed; the order itself, its lines and totals remain for the owner's records.
    (datomic/excise! conn [{:db/excise order-eid :db.excise/attrs personal-order-attrs}])
    (when acceptance
      (datomic/excise! conn [{:db/excise acceptance :db.excise/attrs [:acceptance/customer-name :acceptance/customer-phone]}]))))

(defn run-retention!
  "Excises personal data of orders closed before `cutoff`, at most `batch` orders per call. Returns how many were processed. Safe to repeat."
  [conn ^Date cutoff batch]
  (let [eids (take batch (expired-order-eids (d/db conn) cutoff))]
    (doseq [e eids] (excise-order-personal-data! conn e))
    (count eids)))


(defn current-db
  "The current database value of `conn` (no d/sync)."
  [conn] (d/db conn))
