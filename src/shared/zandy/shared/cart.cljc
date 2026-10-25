(ns zandy.shared.cart
  "Pure cart functions. A cart is {:items [{:product-id :slug :name :price
  :image :quantity}]}. The cart reserves nothing; the server decides at order
  placement."
  (:require [clojure.string :as str]))

(def max-lines 20)

(def empty-cart {:items []})

(defn count-items [cart]
  (reduce + 0 (map :quantity (:items cart))))

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

(defn set-quantity [cart product-id quantity max-q]
  (update cart :items
          (fn [items]
            (mapv (fn [it] (if (= product-id (:product-id it))
                             (assoc it :quantity (clamp quantity max-q))
                             it))
                  items))))

(defn remove-item [cart product-id]
  (update cart :items (fn [items] (vec (remove #(= product-id (:product-id %)) items)))))

(defn clear [_cart] empty-cart)

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
