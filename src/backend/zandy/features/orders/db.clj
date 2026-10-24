(ns zandy.features.orders.db
  "Order persistence: reads and the retention excision."
  (:require [datomic.api :as d]
            [zandy.platform.datomic :as datomic])
  (:import (java.util Date)))

(def order-pattern
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

(defn order-map [m]
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

(defn order-by-id [db id] (order-map (d/pull db order-pattern [:order/id id])))
(defn order-by-number [db number] (order-map (d/pull db order-pattern [:order/number number])))
(defn order-by-idempotency-key [db k] (order-map (d/pull db order-pattern [:order/idempotency-key k])))

(defn all-orders [db]
  (->> (d/q '[:find [?e ...] :where [?e :order/id]] db)
       (d/pull-many db order-pattern)
       (keep order-map)
       vec))

(defn order-by-id-fresh
  "Looks up by id; when nothing is found, syncs once and tries again (multi-instance read-your-writes)."
  [conn id]
  (or (order-by-id (d/db conn) id) (order-by-id (datomic/fresh-db conn) id)))

(defn order-by-number-fresh [conn number]
  (or (order-by-number (d/db conn) number) (order-by-number (datomic/fresh-db conn) number)))

(defn order-by-idempotency-key-fresh [conn k]
  (or (order-by-idempotency-key (d/db conn) k) (order-by-idempotency-key (datomic/fresh-db conn) k)))

;; ---- writes ------------------------------------------------------------------------------------------

(defn place-order! [conn spec]
  (datomic/transact! conn [[:orders/place-order spec]]))

(defn change-status! [conn spec]
  (datomic/transact! conn [[:orders/change-status spec]]))

(defn patch-order! [conn order {:keys [delivery-fee staff-note user-eid now]}]
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
  [:order/customer-name :order/customer-phone :order/customer-email :order/delivery-area
   :order/delivery-address-line :order/delivery-directions])

(defn expired-order-eids
  "Closed orders (delivered or cancelled) last updated before the cutoff that still hold personal data."
  [db ^Date cutoff]
  (d/q '[:find [?o ...] :in $ ?cutoff
         :where [?o :order/status ?s] [(contains? #{:delivered :cancelled} ?s)]
         [?o :order/updated-at ?t] [(< ?t ?cutoff)]
         [?o :order/customer-name]]
       db cutoff))

(defn excise-order-personal-data!
  "Excises (permanently removes, history included) the personal attributes of an order and its
  acceptance record. Retraction would not be enough because Datomic keeps history."
  [conn order-eid]
  (let [db (d/db conn)
        acceptance (:db/id (:order/acceptance (d/pull db [{:order/acceptance [:db/id]}] order-eid)))]
    (datomic/excise! conn [{:db/excise order-eid :db.excise/attrs personal-order-attrs}])
    (when acceptance
      (datomic/excise! conn [{:db/excise acceptance :db.excise/attrs [:acceptance/customer-name :acceptance/customer-phone]}]))))

(defn run-retention!
  "Excises personal data of orders closed before the cutoff, in small batches. Returns the count."
  [conn ^Date cutoff batch]
  (let [eids (take batch (expired-order-eids (d/db conn) cutoff))]
    (doseq [e eids] (excise-order-personal-data! conn e))
    (count eids)))


(defn current-db [conn] (d/db conn))
