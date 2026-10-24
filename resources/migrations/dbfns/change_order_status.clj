;; Changes an order's status atomically: transition rules, version check, delivery fee rule and, when
;; cancelling before dispatch, the stock restore with its movements. Requesting the status the order
;; already has changes nothing.
{:params [db spec]}
(let [{:keys [order-id version status note by payment-method-used cancel-reason now event-id movement-ids]} spec
      o (datomic.api/entity db [:order/id order-id])
      fail (fn [kind message] (throw (ex-info (name kind) {:zandy/failure kind :message message})))]
  (when (nil? o) (fail :notFound "Order not found."))
  (let [cur (:order/status o)
        oid (:db/id o)
        allowed {:placed #{:confirmed :cancelled}
                 :confirmed #{:approvedForDelivery :cancelled}
                 :approvedForDelivery #{:delivered :cancelled}}]
    (cond
      (= cur status) []

      (not= version (:order/version o))
      (fail :versionConflict "This order was changed by someone else. Please reload it.")

      (not (contains? (get allowed cur #{}) status))
      (fail :invalidStatusTransition "This status change is not allowed for the order.")

      (and (contains? #{:confirmed :approvedForDelivery} status) (nil? (:order/delivery-fee o)))
      (fail :deliveryFeeRequired "Record the delivery fee first.")

      :else
      (let [restore? (and (= status :cancelled)
                          (contains? #{:placed :confirmed} cur)
                          (not (:order/stock-restored? o)))
            lines (sort-by :order-line/position (:order/lines o))
            restores (when restore?
                       (map-indexed
                        (fn [i l]
                          (let [pid (:order-line/product-id l)
                                p (datomic.api/entity db [:product/id pid])
                                stock (or (:product/stock-quantity p) 0)
                                q (:order-line/quantity l)]
                            [[:db/add [:product/id pid] :product/stock-quantity (+ stock q)]
                             {:stock-movement/id (or (nth movement-ids i nil) (java.util.UUID/randomUUID))
                              :stock-movement/type :orderCancelled
                              :stock-movement/delta q
                              :stock-movement/quantity-after (+ stock q)
                              :stock-movement/at now
                              :stock-movement/product [:product/id pid]
                              :stock-movement/order oid
                              :stock-movement/order-number (:order/number o)}]))
                        lines))
            event (into {:db/id "event" :order-event/id event-id :order-event/status status :order-event/at now}
                        (remove (fn [e] (nil? (val e))))
                        {:order-event/note note
                         :order-event/by (when by [:user/id by])})]
        (vec
         (concat
          [[:db/cas oid :order/version version (inc version)]
           [:db/add oid :order/status status]
           [:db/add oid :order/updated-at now]
           event
           [:db/add oid :order/events "event"]]
          (when payment-method-used [[:db/add oid :order/payment-method-used payment-method-used]])
          (when cancel-reason [[:db/add oid :order/cancel-reason cancel-reason]])
          (when restore? [[:db/add oid :order/stock-restored? true]])
          (apply concat restores)))))))
