(ns zandy.frontend.cart-store
  "Browser storage for the cart (architecture.md section 2.4,
  `frontend.cart-store`). Runs in the browser only. The cart is kept in
  localStorage under \"zt-cart\" so it survives visits; the last placed order is
  kept in sessionStorage under \"zt-last-order\" only so the order-placed page
  (P7) can show it after the redirect. Only ids, quantities and display hints
  are stored (shared.cart/->storage); prices are re-checked by the server at
  checkout. Storage errors (private mode, quota) are swallowed, so the shop
  works without storage."
  (:require [zandy.shared.cart :as cart]))

(def ^:private cart-key "zt-cart")
(def ^:private order-key "zt-last-order")

(defn- read-json
  "Reads `k` from the given Storage and parses it into a keyword-keyed map;
  returns nil if missing or invalid."
  [^js storage k]
  (try (some-> (.getItem storage k) js/JSON.parse (js->clj :keywordize-keys true))
       (catch :default _ nil)))

(defn- write-json!
  "Writes `v` as JSON to `k` in the Storage; ignores storage errors. Side
  effect: browser storage write."
  [^js storage k v]
  (try (.setItem storage k (js/JSON.stringify (clj->js v)))
       (catch :default _ nil)))

(defn load-cart
  "The stored cart, or the empty cart when nothing valid is stored. Returns
  {:items [...]}."
  []
  (let [c (read-json js/localStorage cart-key)]
    (if (vector? (:items c)) c cart/empty-cart)))

(defn save-cart!
  "Stores `c` (only its :items) in localStorage. Side effect: storage write."
  [c] (write-json! js/localStorage cart-key (cart/->storage c)))

(defn load-last-order
  "The last placed order response (POST /api/orders body) from sessionStorage,
  or nil."
  [] (read-json js/sessionStorage order-key))
(defn save-last-order!
  "Stores the order response `o` in sessionStorage. Side effect: storage write."
  [o] (write-json! js/sessionStorage order-key o))
