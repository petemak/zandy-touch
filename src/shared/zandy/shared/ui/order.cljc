(ns zandy.shared.ui.order
  "Cart lines, order summary, status timeline."
  (:require [zandy.shared.format :as fmt]
            [zandy.shared.routes :as routes]
            [zandy.shared.ui.core :as ui]
            [zandy.shared.ui.product :as product]))

(defn line-notice
  "Inline text for a validation problem on a cart line."
  [{:keys [problem maxQuantity]} _item]
  (case problem
    "soldOut" (ui/notice {:kind :error :live? true} "Sold out, remove to continue")
    "unavailable" (ui/notice {:kind :error :live? true} "No longer available, remove to continue")
    "insufficientStock" (ui/notice {:kind :warning :live? true}
                                   (str "Only " maxQuantity " available"))
    nil))

(defn price-changed-notice [line item]
  (when (and (:unitPrice line) (:price item) (not= (:unitPrice line) (:price item)))
    (ui/notice {:kind :warning :live? true} (str "Price changed. Now " (fmt/ugx (:unitPrice line))))))

(defn cart-line
  "item is the stored cart line, vline the validation line (or nil)."
  [{:keys [product-id slug name price image quantity] :as item} vline]
  (let [problem (:problem vline)
        unit (or (:unitPrice vline) price)
        max-q (or (:maxQuantity vline) 10)]
    [:li {:class (ui/classes "cart-line" (when problem "has-problem"))}
     [:div.cart-line__media
      (ui/link (routes/path-for :product {:slug slug}) {:tabindex "-1" :aria-hidden "true"}
               (ui/picture image {:fallback-label name :sizes "20vw"}))]
     [:div.cart-line__body
      [:h2.cart-line__name (ui/link (routes/path-for :product {:slug slug}) {} name)]
      [:p.cart-line__unit (ui/price unit)]
      (line-notice vline item)
      (price-changed-notice vline item)
      (when (and (= "insufficientStock" problem) (:maxQuantity vline))
        (ui/button {:variant :secondary
                    :on-click [[:cart/set-qty product-id (:maxQuantity vline)]]}
                   (str "Change to " (:maxQuantity vline))))
      [:div.cart-line__controls
       (when-not (#{"soldOut" "unavailable"} problem)
         (product/quantity-stepper {:id (str "qty-" product-id) :label name :value quantity :max-q max-q
                                    :on-dec [:cart/set-qty product-id (dec quantity)]
                                    :on-inc [:cart/set-qty product-id (inc quantity)]
                                    :on-set [:cart/set-qty-input product-id]}))
       (ui/button {:variant :secondary :on-click [[:cart/remove product-id]]
                   :attrs {:aria-label (str "Remove " name " from cart")}}
                  (ui/icon :trash :sm) "Remove")]]
     [:p.cart-line__total (when-not (#{"soldOut" "unavailable"} problem)
                            (ui/price (* unit quantity)))]]))

(defn order-summary
  "Lines are {:name :quantity :unitPrice :lineTotal}. Delivery reads 'agreed'
  until a fee is recorded."
  [{:keys [lines subtotal deliveryFee total heading]}]
  [:section.order-summary {:aria-labelledby "order-summary-title"}
   [:h2#order-summary-title.order-summary__title (or heading "Order summary")]
   (when (seq lines)
     [:ul.order-summary__lines
      (for [[i l] (map-indexed vector lines)]
        ^{:key i}
        [:li [:span (str (:name l) " × " (:quantity l))]
         (ui/price (or (:lineTotal l) (* (:unitPrice l) (:quantity l))))])])
   [:dl.order-summary__totals
    [:div [:dt "Items"] [:dd (ui/price subtotal)]]
    [:div [:dt "Delivery"]
     [:dd (if (some? deliveryFee)
            (if (zero? deliveryFee) "Free" (ui/price deliveryFee))
            "Agreed with you before dispatch")]]
    (when (some? total)
      [:div.order-summary__total [:dt "Total"] [:dd (ui/price total)]])]])

(def timeline-steps ["placed" "confirmed" "approvedForDelivery" "delivered"])

(defn status-timeline
  "timeline: [{:status :at}]. A cancelled order replaces the remaining steps."
  [timeline status]
  (let [reached (into {} (map (juxt :status :at)) timeline)
        cancelled? (= "cancelled" status)
        steps (if cancelled?
                (concat (filter #(contains? reached %) (butlast timeline-steps)) ["cancelled"])
                timeline-steps)]
    [:ol.timeline
     (for [s steps]
       ^{:key s}
       [:li {:class (ui/classes "timeline__step" (when (contains? reached s) "is-reached")
                                (when (= s status) "is-current"))
             :aria-current (when (= s status) "step")}
        (ui/icon (if (contains? reached s) :check :history) :sm)
        [:span.timeline__label (fmt/order-status-label s)]
        [:span.timeline__time (if-let [at (get reached s)]
                                (fmt/iso->date-time at)
                                "Not yet")]])]))
