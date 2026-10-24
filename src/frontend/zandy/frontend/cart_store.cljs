(ns zandy.frontend.cart-store
  "The cart lives in localStorage; the last placed order lives in
  sessionStorage (kept only for the confirmation page)."
  (:require [zandy.shared.cart :as cart]))

(def ^:private cart-key "zt-cart")
(def ^:private order-key "zt-last-order")

(defn- read-json [^js storage k]
  (try (some-> (.getItem storage k) js/JSON.parse (js->clj :keywordize-keys true))
       (catch :default _ nil)))

(defn- write-json! [^js storage k v]
  (try (.setItem storage k (js/JSON.stringify (clj->js v)))
       (catch :default _ nil)))

(defn load-cart []
  (let [c (read-json js/localStorage cart-key)]
    (if (vector? (:items c)) c cart/empty-cart)))

(defn save-cart! [c] (write-json! js/localStorage cart-key (cart/->storage c)))

(defn load-last-order [] (read-json js/sessionStorage order-key))
(defn save-last-order! [o] (write-json! js/sessionStorage order-key o))
