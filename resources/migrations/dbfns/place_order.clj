;; Places an order atomically on the transactor: checks every line (published, price, stock), then
;; writes the order, price snapshots, stock decrements, stock movements, the acceptance record, the
;; history entry and the outbox messages together, or writes nothing and throws cartOutdated.
{:params [db spec]}
(let [compact (fn [m] (into {} (remove (fn [e] (nil? (val e)))) m))
      {:keys [now order-id order-number idem-key customer delivery payment-method note
              lines acceptance outbox event-id movement-ids]} spec
      checked (map-indexed
               (fn [i {:keys [product-id quantity expected-price]}]
                 (let [p (datomic.api/entity db [:product/id product-id])
                       stock (or (when p (:product/stock-quantity p)) 0)
                       price (when p (:product/price p))
                       problem (cond
                                 (or (nil? p) (not= :published (:product/status p)))
                                 {:code "unavailable" :message "This product is no longer available."}
                                 (zero? stock)
                                 {:code "soldOut" :message "This product is sold out."}
                                 (< stock quantity)
                                 {:code "insufficientStock" :message (str "Only " stock " available.")
                                  :available-quantity stock}
                                 (not= price expected-price)
                                 {:code "priceChanged" :message "The price of this product has changed."
                                  :current-price price})]
                   {:i i :p p :stock stock :quantity quantity :price price
                    :product-id product-id
                    :problem (when problem (assoc problem :product-id product-id))}))
               lines)
      problems (vec (keep :problem checked))]
  (when (seq problems)
    (throw (ex-info "cartOutdated"
                    {:zandy/failure :cartOutdated
                     :message "Some items in your order have changed. Nothing was ordered."
                     :details problems})))
  (vec
   (concat
    [(compact
      {:db/id "order"
       :order/id order-id
       :order/number order-number
       :order/status :placed
       :order/placed-at now
       :order/customer-name (:name customer)
       :order/customer-phone (:phone customer)
       :order/customer-email (:email customer)
       :order/preferred-contact (:preferred-contact customer)
       :order/delivery-country (:country-code delivery)
       :order/delivery-city (:city delivery)
       :order/delivery-area (:area delivery)
       :order/delivery-address-line (:address-line delivery)
       :order/delivery-directions (:directions delivery)
       :order/payment-method payment-method
       :order/note note
       :order/version 1
       :order/updated-at now
       :order/idempotency-key idem-key
       :order/lines (mapv (fn [{:keys [i p quantity price product-id]}]
                            {:order-line/product-id product-id
                             :order-line/product-name (:product/name p)
                             :order-line/unit-price price
                             :order-line/quantity quantity
                             :order-line/position i})
                          checked)
       :order/events [(compact {:order-event/id event-id :order-event/status :placed :order-event/at now})]
       :order/acceptance (compact
                          {:acceptance/id (:id acceptance)
                           :acceptance/policy-type (:policy-type acceptance)
                           :acceptance/policy-version (:policy-version acceptance)
                           :acceptance/content-hash (:content-hash acceptance)
                           :acceptance/accepted-at now
                           :acceptance/customer-name (:name customer)
                           :acceptance/customer-phone (:phone customer)})})]
    (mapcat (fn [{:keys [i stock quantity product-id]}]
              [[:db/add [:product/id product-id] :product/stock-quantity (- stock quantity)]
               {:stock-movement/id (nth movement-ids i)
                :stock-movement/type :order
                :stock-movement/delta (- quantity)
                :stock-movement/quantity-after (- stock quantity)
                :stock-movement/at now
                :stock-movement/product [:product/id product-id]
                :stock-movement/order "order"
                :stock-movement/order-number order-number}])
            checked)
    (map (fn [m] (assoc m :outbox/status :pending :outbox/created-at now :outbox/attempts 0)) outbox))))
