;; Adjusts a product's stock by a signed amount, atomically. Never takes stock below zero. When
;; expected-quantity is given the adjustment applies only if the current stock equals it.
{:params [db spec]}
(let [{:keys [product-id delta reason note expected-quantity by now movement-id]} spec
      p (datomic.api/entity db [:product/id product-id])
      fail (fn [kind message details]
             (throw (ex-info (name kind) {:zandy/failure kind :message message :details details})))]
  (when (nil? p) (fail :notFound "Product not found." nil))
  (let [stock (or (:product/stock-quantity p) 0)
        after (+ stock delta)]
    (when (and expected-quantity (not= expected-quantity stock))
      (fail :versionConflict "The stock changed since you loaded it. Please check the count again." nil))
    (when (neg? after)
      (fail :validationFailed "Please check the highlighted fields."
            [{:field "delta" :code "stockCannotBeNegative" :message "Stock cannot go below zero."}]))
    [[:db/add (:db/id p) :product/stock-quantity after]
     (into {:stock-movement/id movement-id
            :stock-movement/type :adjustment
            :stock-movement/reason reason
            :stock-movement/delta delta
            :stock-movement/quantity-after after
            :stock-movement/at now
            :stock-movement/product (:db/id p)}
           (remove (fn [e] (nil? (val e))))
           {:stock-movement/note note
            :stock-movement/by (when by [:user/id by])})]))
