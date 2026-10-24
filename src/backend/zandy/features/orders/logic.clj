(ns zandy.features.orders.logic
  "Pure order rules and views: numbers, totals, cart validation lines, tracking and admin views."
  (:require [clojure.string :as str]))

(def number-alphabet "ABCDEFGHJKLMNPQRSTUVWXYZ23456789")

(defn order-number
  "ZT-XXXXXX from an index function (n -> 0..n-1)."
  [rand-index]
  (str "ZT-" (apply str (repeatedly 6 #(nth number-alphabet (rand-index (count number-alphabet)))))))

(def delivery-fee-note "Agreed with you before dispatch")

(defn line-total [{:keys [unit-price quantity]}] (* unit-price quantity))

(defn subtotal [items] (reduce + 0 (map line-total items)))

(defn total [items delivery-fee]
  (when delivery-fee (+ (subtotal items) delivery-fee)))

(defn items-view [lines]
  (->> lines (sort-by :position)
       (mapv (fn [l] {:product-id (:product-id l) :name (:name l) :unit-price (:unit-price l)
                      :quantity (:quantity l) :line-total (line-total l)}))))

(defn order-view
  "The order as returned to the customer on placement."
  [{:keys [id number status placed-at customer-name customer-phone customer-email preferred-contact
           delivery-country delivery-city delivery-area delivery-address-line delivery-directions
           payment-method note lines delivery-fee]}]
  (let [items (items-view lines)]
    {:id id :order-number number :status status :placed-at placed-at
     :customer {:name customer-name :phone customer-phone :email customer-email :preferred-contact preferred-contact}
     :delivery {:country-code delivery-country :city delivery-city :area delivery-area
                :address-line delivery-address-line :directions delivery-directions}
     :payment-method payment-method :note note
     :items items :items-subtotal (subtotal items) :currency "UGX"
     :delivery-fee delivery-fee :delivery-fee-note delivery-fee-note
     :total (total items delivery-fee)}))

(defn admin-order-view [{:keys [version staff-note payment-method-used cancel-reason stock-restored?] :as order}]
  (assoc (order-view order)
         :version version :staff-note staff-note :payment-method-used payment-method-used
         :cancel-reason cancel-reason :stock-restored (boolean stock-restored?)))

(defn acceptance-view [{:keys [policy-type policy-version accepted-at]}]
  {:type policy-type :version policy-version :accepted-at accepted-at})

(defn admin-acceptance-view [{:keys [policy-type policy-version content-hash accepted-at customer-name customer-phone]}]
  {:type policy-type :version policy-version :content-hash content-hash :accepted-at accepted-at
   :customer-name customer-name :customer-phone customer-phone})

(def ^:private status-rank {:placed 0 :confirmed 1 :approvedForDelivery 2 :delivered 3 :cancelled 4})

(defn history-view [events users]
  (->> events
       (sort-by (juxt :at (comp status-rank :status)))
       (mapv (fn [{:keys [status at by-eid note]}]
               {:status status :at at :by (get users by-eid) :note note}))))

(defn tracking-view
  "What a customer may see: no street address, email, staff names or internal notes."
  [{:keys [number status placed-at lines delivery-fee payment-method delivery-city events]}]
  (let [items (items-view lines)]
    {:order-number number :status status :placed-at placed-at
     :items (mapv #(dissoc % :product-id) items)
     :items-subtotal (subtotal items) :delivery-fee delivery-fee :total (total items delivery-fee)
     :currency "UGX" :payment-method payment-method :city delivery-city
     :timeline (->> events (sort-by (juxt :at (comp status-rank :status)))
                    (mapv (fn [{:keys [status at]}] {:status status :at at})))}))

(defn list-item [{:keys [id number status placed-at customer-name customer-phone delivery-city lines
                         delivery-fee payment-method updated-at]}]
  (let [items (items-view lines)]
    {:id id :order-number number :status status :placed-at placed-at :customer-name customer-name
     :customer-phone customer-phone :city delivery-city :item-count (reduce + 0 (map :quantity items))
     :items-subtotal (subtotal items) :delivery-fee delivery-fee :total (total items delivery-fee)
     :currency "UGX" :payment-method payment-method :updated-at updated-at}))

(defn filter-orders [orders {:keys [status q phone]}]
  (let [q (some-> q str/lower-case)]
    (cond->> orders
      status (filter #(= (keyword status) (:status %)))
      q (filter (fn [o] (or (some-> (:number o) str/lower-case (str/includes? q))
                            (some-> (:customer-name o) str/lower-case (str/includes? q))
                            (and phone (some-> (:customer-phone o) (str/includes? phone)))
                            (some-> (:customer-phone o) (str/includes? q))))))))

(defn status-counts [orders]
  (merge {:placed 0 :confirmed 0 :approvedForDelivery 0 :delivered 0 :cancelled 0}
         (frequencies (map :status orders))))

;; ---- cart validation -----------------------------------------------------------------------------

(defn cart-line
  "One validated cart line. `info` is the catalogue's view of the product, or nil when unknown."
  [{:keys [product-id quantity]} info max-qty]
  (let [stock (:stock-quantity info)
        problem (cond
                  (or (nil? info) (not (:published? info))) :unavailable
                  (zero? stock) :soldOut
                  (< stock quantity) :insufficientStock)
        purchasable? (nil? problem)]
    {:product-id product-id :slug (:slug info) :name (:name info) :unit-price (:price info)
     :quantity quantity :line-total (when (and purchasable? (:price info)) (* (:price info) quantity))
     :availability (if (and info (:published? info) (pos? stock)) :available :soldOut)
     :max-quantity (if (and info (:published? info)) (max 0 (min max-qty stock)) 0)
     :purchasable purchasable? :image (:image info) :problem problem}))

(defn cart-response [lines]
  {:items lines
   :items-subtotal (reduce + 0 (keep #(when (:purchasable %) (:line-total %)) lines))
   :currency "UGX" :delivery-fee nil :delivery-fee-note delivery-fee-note
   :has-problems (boolean (some :problem lines))})

(defn problem-message [problem]
  (case problem
    "priceChanged" "The price of this product has changed."
    "soldOut" "This product is sold out."
    "unavailable" "This product is no longer available."
    "insufficientStock" "There is not enough stock for this quantity."
    "Please check this item."))
