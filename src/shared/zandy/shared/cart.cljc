(ns zandy.shared.cart
  "Pure cart functions on the shape {:items [{:product-id :slug :name :price
  :image :quantity}]}. The cart reserves nothing; the server decides stock and
  price at order placement. Shared code (.cljc) but only the browser keeps a
  cart (frontend.cart-store persists it, frontend.state updates it); depends
  on nothing else in the project."
  (:require [clojure.string :as str]))

(def max-lines
  "Largest number of different products one cart may hold (20). Adding a 21st
  product is ignored."
  20)

(def empty-cart
  "The cart with no items."
  {:items []})

(defn count-items
  "Total quantity over all lines of `cart` (shown on the header cart icon).
  Returns a number; 0 for an empty cart."
  [cart]
  (reduce + 0 (map :quantity (:items cart))))

;; Quantities are always at least 1 and at most the product limit (default
;; 10), the same limits the server enforces.
(defn- clamp [q max-q]
  (-> q (max 1) (min (or max-q 10))))

(defn add-item
  "Add a product (a public product map from the API) with a quantity. Merges
  with an existing line. `max-q` caps the line (product maxQuantity)."
  [cart product quantity max-q]
  (let [id (:id product)
        items (:items cart)
        idx (first (keep-indexed (fn [i it] (when (= id (:product-id it)) i)) items))]
    (if idx
      (update-in cart [:items idx :quantity] #(clamp (+ % quantity) max-q))
      (if (>= (count items) max-lines)
        cart
        (update cart :items conj
                {:product-id id
                 :slug (:slug product)
                 :name (:name product)
                 :price (:price product)
                 :image (:image product)
                 :quantity (clamp quantity max-q)})))))

(defn set-quantity
  "Returns `cart` with the line for `product-id` set to `quantity`, clamped to
  1..`max-q` (10 when `max-q` is nil). Other lines and unknown ids are left
  unchanged."
  [cart product-id quantity max-q]
  (update cart :items
          (fn [items]
            (mapv (fn [it] (if (= product-id (:product-id it))
                             (assoc it :quantity (clamp quantity max-q))
                             it))
                  items))))

(defn remove-item
  "Returns `cart` without the line for `product-id`. Unknown ids are ignored."
  [cart product-id]
  (update cart :items (fn [items] (vec (remove #(= product-id (:product-id %)) items)))))

(defn clear
  "Returns the empty cart, whatever `_cart` was. Used after an order is placed."
  [_cart] empty-cart)

(defn validation-request
  "Body for POST /api/cart-validations."
  [cart]
  {:items (mapv (fn [it] {:productId (:product-id it) :quantity (:quantity it)})
                (:items cart))})

(defn order-items
  "items for POST /api/orders: the price the customer saw is the validated
  unit price when known, else the stored price."
  [cart validation]
  (let [by-id (into {} (map (juxt :productId identity)) (:items validation))]
    (mapv (fn [it]
            {:productId (:product-id it)
             :quantity (:quantity it)
             :expectedUnitPrice (or (:unitPrice (get by-id (:product-id it))) (:price it))})
          (:items cart))))

;; Why the stored :price is kept: it is the last price the customer saw. The
;; cart page compares it with the validated :unitPrice to show the "Price
;; changed" notice (ui.order/price-changed-notice); acknowledge-prices moves
;; it forward when the customer goes on to checkout.
(defn apply-validation
  "Refresh stored name/slug/image from a validation response. The stored
  :price is NOT overwritten: it is the price the customer last saw, so the cart
  can say \"Price changed\" while it differs from the validated :unitPrice.
  `acknowledge-prices` moves it forward."
  [cart validation]
  (let [by-id (into {} (map (juxt :productId identity)) (:items validation))]
    (update cart :items
            (fn [items]
              (mapv (fn [it]
                      (if-let [v (get by-id (:product-id it))]
                        (assoc it :name (:name v) :slug (:slug v) :image (:image v))
                        it))
                    items)))))

(defn acknowledge-prices
  "The customer has seen the validated prices (they proceed to checkout):
  the stored price becomes the validated unit price, so the notice clears."
  [cart validation]
  (let [by-id (into {} (map (juxt :productId identity)) (:items validation))]
    (update cart :items
            (fn [items]
              (mapv (fn [it]
                      (if-let [p (:unitPrice (get by-id (:product-id it)))]
                        (assoc it :price p)
                        it))
                    items)))))

(defn line-problems
  "Map product-id -> validation line for lines with a problem."
  [validation]
  (into {} (comp (filter :problem) (map (juxt :productId identity))) (:items validation)))

(defn blocked?
  "Checkout is blocked while a validation reports problems."
  [validation]
  (boolean (or (nil? validation) (:hasProblems validation))))

(defn cart-signature
  "Changes whenever the cart contents change (used to renew the order key)."
  [cart]
  (str/join "|" (map (fn [it] (str (:product-id it) "x" (:quantity it))) (:items cart))))

(defn ->storage
  "What is persisted in localStorage: only ids, quantity and display hints."
  [cart]
  (select-keys cart [:items]))
