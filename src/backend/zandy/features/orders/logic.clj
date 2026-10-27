(ns zandy.features.orders.logic
  "Pure order rules and views: numbers, totals, cart validation lines, tracking and admin views."
  (:require [clojure.string :as str]))

(def number-alphabet
  "Characters used in order numbers: upper-case letters and digits without the look-alikes I, O, 0 and 1."
  "ABCDEFGHJKLMNPQRSTUVWXYZ23456789")

(defn order-number
  "A customer-facing order number \"ZT-\" plus six characters from number-alphabet. `rand-index` is a function from n to a random integer in 0..n-1 (pass a SecureRandom-backed one; tests pass a fixed one). Uniqueness is enforced by the database, not here."
  [rand-index]
  (str "ZT-" (apply str (repeatedly 6 #(nth number-alphabet (rand-index (count number-alphabet)))))))

(def delivery-fee-note
  "Note shown with every order whose delivery fee is not yet set or is agreed by phone."
  "Agreed with you before dispatch")

(defn line-total
  "unit-price times quantity (UGX) of one line map {:unit-price :quantity}."
  [{:keys [unit-price quantity]}] (* unit-price quantity))

(defn subtotal
  "Sum of line totals (UGX) of a sequence of line maps; 0 for none."
  [items] (reduce + 0 (map line-total items)))

(defn total
  "Items subtotal plus the delivery fee (UGX), or nil while the delivery fee is not set."
  [items delivery-fee]
  (when delivery-fee (+ (subtotal items) delivery-fee)))

(defn items-view
  "Order lines (maps with :position :product-id :name :unit-price :quantity) as wire items in position order, each with its :line-total."
  [lines]
  (->> lines (sort-by :position)
       (mapv (fn [l] {:product-id (:product-id l) :name (:name l) :unit-price (:unit-price l)
                      :quantity (:quantity l) :line-total (line-total l)}))))

(defn order-view
  "The order as returned to the customer on placement. Takes the order map (see db/order-map).
  Returns {:id :order-number :status :placed-at :customer {...} :delivery {...} :payment-method :note :items :items-subtotal :currency \"UGX\" :delivery-fee :delivery-fee-note :total}.
  Money is whole UGX; :total is nil until the delivery fee is set. Pure."
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

(defn admin-order-view
  "Staff view of an order: order-view plus :version :staff-note :payment-method-used :cancel-reason :stock-restored."
  [{:keys [version staff-note payment-method-used cancel-reason stock-restored?] :as order}]
  (assoc (order-view order)
         :version version :staff-note staff-note :payment-method-used payment-method-used
         :cancel-reason cancel-reason :stock-restored (boolean stock-restored?)))

(defn acceptance-view
  "Customer-facing acceptance record {:type :version :accepted-at} from an acceptance map."
  [{:keys [policy-type policy-version accepted-at]}]
  {:type policy-type :version policy-version :accepted-at accepted-at})

(defn admin-acceptance-view
  "Staff view of the acceptance record: type, version, content hash, accepted-at and the customer name and phone captured at acceptance. Nil name/phone after retention excision."
  [{:keys [policy-type policy-version content-hash accepted-at customer-name customer-phone]}]
  {:type policy-type :version policy-version :content-hash content-hash :accepted-at accepted-at
   :customer-name customer-name :customer-phone customer-phone})

(def ^:private status-rank {:placed 0 :confirmed 1 :approvedForDelivery 2 :delivered 3 :cancelled 4})

(defn history-view
  "Order status history for staff: `events` ({:status :at :by-eid :note}) sorted by time then status order, each with :by looked up in `users` (map of entity id to user summary)."
  [events users]
  (->> events
       (sort-by (juxt :at (comp status-rank :status)))
       (mapv (fn [{:keys [status at by-eid note]}]
               {:status status :at at :by (get users by-eid) :note note}))))

(defn tracking-view
  "What a customer may see when tracking an order: no street address, email, staff names or internal notes. Returns number, status, items without product ids, money totals, payment method, city and a status timeline."
  [{:keys [number status placed-at lines delivery-fee payment-method delivery-city events]}]
  (let [items (items-view lines)]
    {:order-number number :status status :placed-at placed-at
     :items (mapv #(dissoc % :product-id) items)
     :items-subtotal (subtotal items) :delivery-fee delivery-fee :total (total items delivery-fee)
     :currency "UGX" :payment-method payment-method :city delivery-city
     :timeline (->> events (sort-by (juxt :at (comp status-rank :status)))
                    (mapv (fn [{:keys [status at]}] {:status status :at at})))}))

(defn list-item
  "Staff order list row from an order map: ids, number, status, customer name and phone, city, item count, subtotal, delivery fee, total, payment method, updated-at."
  [{:keys [id number status placed-at customer-name customer-phone delivery-city lines
                         delivery-fee payment-method updated-at]}]
  (let [items (items-view lines)]
    {:id id :order-number number :status status :placed-at placed-at :customer-name customer-name
     :customer-phone customer-phone :city delivery-city :item-count (reduce + 0 (map :quantity items))
     :items-subtotal (subtotal items) :delivery-fee delivery-fee :total (total items delivery-fee)
     :currency "UGX" :payment-method payment-method :updated-at updated-at}))

(defn filter-orders
  "Filters order maps by :status (string) and by free text :q matching order number, customer name or phone; :phone is the E.164-normalised form of q, so \"0772...\" finds \"+256772...\"."
  [orders {:keys [status q phone]}]
  (let [q (some-> q str/lower-case)]
    (cond->> orders
      status (filter #(= (keyword status) (:status %)))
      q (filter (fn [o] (or (some-> (:number o) str/lower-case (str/includes? q))
                            (some-> (:customer-name o) str/lower-case (str/includes? q))
                            (and phone (some-> (:customer-phone o) (str/includes? phone)))
                            (some-> (:customer-phone o) (str/includes? q))))))))

(defn status-counts
  "Map of every order status keyword to its number of orders (zeros included), for the admin list tabs."
  [orders]
  (merge {:placed 0 :confirmed 0 :approvedForDelivery 0 :delivered 0 :cancelled 0}
         (frequencies (map :status orders))))

;; ---- cart validation -----------------------------------------------------------------------------

(defn cart-line
  "One validated cart line. Arguments: {:product-id :quantity} requested; `info`, the catalogue's cart-info entry for the product (nil when unknown); `max-qty`, the per-line maximum.
  Returns {:product-id :slug :name :unit-price :quantity :line-total :availability :max-quantity :purchasable :image :problem}; :problem is nil or :unavailable, :soldOut or :insufficientStock. Pure."
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

(defn cart-response
  "Contract cart-validation body from validated `lines`: {:items :items-subtotal (purchasable lines only) :currency \"UGX\" :delivery-fee nil :delivery-fee-note :has-problems}."
  [lines]
  {:items lines
   :items-subtotal (reduce + 0 (keep #(when (:purchasable %) (:line-total %)) lines))
   :currency "UGX" :delivery-fee nil :delivery-fee-note delivery-fee-note
   :has-problems (boolean (some :problem lines))})

(defn problem-message
  "Customer-readable message for a cart problem code string (priceChanged, soldOut, unavailable, insufficientStock); a generic message for others."
  [problem]
  (case problem
    "priceChanged" "The price of this product has changed."
    "soldOut" "This product is sold out."
    "unavailable" "This product is no longer available."
    "insufficientStock" "There is not enough stock for this quantity."
    "Please check this item."))
