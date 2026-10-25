(ns zandy.shared.pages.shop
  "Cart, checkout, order placed and tracking (P5 to P8). These are rendered in
  the browser; the server sends only the document shell."
  (:require [zandy.shared.format :as fmt]
            [zandy.shared.ui.core :as ui]
            [zandy.shared.ui.order :as order]
            [zandy.shared.ui.policy :as policy]))

;; ---------------------------------------------------------------------------
;; P5 Cart

(defn cart-page [{:keys [cart cart-check ui]}]
  (let [items (:items cart)
        result (:result cart-check)
        vlines (into {} (map (juxt :productId identity)) (:items result))
        problems? (:hasProblems result)
        checking? (= :loading (:status cart-check))
        subtotal (if result
                   (:itemsSubtotal result)
                   (reduce + 0 (map #(* (:price %) (:quantity %)) items)))]
    [:div.container.cart-page
     [:h1.page-title "Your cart"]
     (if (empty? items)
       (ui/empty-state {:title "Your cart is empty" :text "Browse the shop and add something you like."}
                       (ui/link-button "/shop" {} "Keep shopping"))
       [:div.cart-layout
        [:div
         (when-let [m (:cart-notice ui)]
           (ui/notice {:kind :warning :live? true} m))
         (when (= :error (:status cart-check))
           (ui/notice {:kind :warning :live? true}
                      "We could not refresh prices and availability. "
                      (ui/button {:variant :secondary :on-click [[:cart/validate]]} "Try again")))
         [:ul.cart-lines
          (for [it items]
            ^{:key (:product-id it)} (order/cart-line it (get vlines (:product-id it))))]]
        [:aside.cart-summary {:aria-label "Cart summary"}
         (order/order-summary {:subtotal subtotal :heading "Summary"})
         [:p "You pay on delivery: cash or mobile money."]
         [:p.cart-summary__note "Items are not held until you place your order."]
         (when problems?
           (ui/notice {:kind :warning :live? true} "Fix the marked items to continue."))
         (if (or problems? checking? (= :error (:status cart-check)) (nil? result))
           (ui/button {:disabled? true :full? true}
                      (cond checking? "Checking your cart"
                            problems? "Fix the marked items first"
                            :else "Checkout"))
           (ui/link-button "/checkout"
                          {:full? true
                           ;; proceeding to checkout means the customer has seen the prices
                           :attrs {:on {:click [[:cart/acknowledge-prices] [:nav/link "/checkout"]]}}}
                          "Checkout"))
         (ui/link-button "/shop" {:variant :secondary :full? true} "Keep shopping")]])]))

;; ---------------------------------------------------------------------------
;; P6 Checkout

(def checkout-fields
  "Field id -> display key for the error summary."
  [[:name "checkout-name"] [:phone "checkout-phone"] [:email "checkout-email"]
   [:preferred-contact "checkout-contact"] [:city "checkout-city"] [:area "checkout-area"]
   [:address-line "checkout-address"] [:directions "checkout-directions"]
   [:payment-method "checkout-payment"] [:note "checkout-note"] [:accepted "accept-terms"]])

(defn- summary-errors [errors]
  (vec (for [[k id] checkout-fields
             :let [m (get errors k)]
             :when m]
         {:id id :message m})))

(defn checkout-page [{:keys [cart cart-check forms refs]}]
  (let [form (:checkout forms)
        {:keys [values errors status]} form
        result (:result cart-check)
        areas (:delivery-areas refs)
        cities (get-in areas [:countries 0 :cities])
        policy (get-in refs [:policies "orderTerms"])
        policy-status (get-in refs [:policy-status "orderTerms"])
        submitting? (= :submitting status)
        items (:items cart)]
    [:div.container.checkout-page
     [:h1.page-title "Checkout"]
     (cond
       (empty? items)
       (ui/empty-state {:title "Your cart is empty" :text "Add something to your cart before checking out."}
                       (ui/link-button "/shop" {} "Keep shopping"))

       :else
       [:form.checkout-form {:novalidate true
                             :on {:submit [[:dom/prevent-default] [:checkout/submit]]}}
        (ui/error-summary (summary-errors errors))
        (when (:form errors) (ui/notice {:kind :error :live? true} (:form errors)))
        [:div.checkout-layout
         [:div.checkout-sections
          [:fieldset.checkout-section
           [:legend [:span.checkout-section__num "1"] "Your details"]
           (ui/text-field {:id "checkout-name" :label "Full name" :autocomplete "name" :required? true
                           :value (:name values) :error (:name errors)
                           :on-input [:form/input :checkout :name [:event/target.value]]})
           (ui/text-field {:id "checkout-phone" :label "Phone number" :type "tel" :autocomplete "tel" :inputmode "tel" :required? true
                           :hint "For example 0771 234 567"
                           :value (:phone values) :error (:phone errors)
                           :on-input [:form/input :checkout :phone [:event/target.value]]})
           (ui/text-field {:id "checkout-email" :label "Email" :type "email" :autocomplete "email" :optional? true
                           :hint "Only used to send you an order receipt."
                           :value (:email values) :error (:email errors)
                           :on-input [:form/input :checkout :email [:event/target.value]]})
           (ui/radio-group {:name "checkout-contact" :legend "How should we contact you?"
                            :value (or (:preferred-contact values) "phone")
                            :error (:preferred-contact errors)
                            :on-change [:form/set :checkout :preferred-contact]
                            :options [["phone" "Call me"] ["whatsapp" "WhatsApp me"]]})]
          [:fieldset.checkout-section
           [:legend [:span.checkout-section__num "2"] "Delivery address"]
           [:p.field__hint "Delivery is available to major cities in Uganda. We will agree the delivery fee with you."]
           (ui/text-field {:id "checkout-country" :label "Country" :value "Uganda" :disabled? true
                           :on-input [:form/noop]})
           (if (seq cities)
             (ui/select-input {:id "checkout-city" :label "City" :value (:city values) :error (:city errors)
                               :placeholder "Choose your city"
                               :on-change [:form/input :checkout :city [:event/target.value]]
                               :options (map (fn [c] [c c]) cities)})
             (ui/spinner-text "Loading delivery cities"))
           (ui/text-field {:id "checkout-area" :label "Area or neighbourhood" :autocomplete "address-level3" :required? true
                           :value (:area values) :error (:area errors)
                           :on-input [:form/input :checkout :area [:event/target.value]]})
           (ui/text-field {:id "checkout-address" :label "Street address" :autocomplete "street-address" :required? true
                           :value (:address-line values) :error (:address-line errors)
                           :on-input [:form/input :checkout :address-line [:event/target.value]]})
           (ui/text-field {:id "checkout-directions" :label "Directions or landmark" :optional? true
                           :value (:directions values) :error (:directions errors)
                           :on-input [:form/input :checkout :directions [:event/target.value]]})]
          [:fieldset.checkout-section
           [:legend [:span.checkout-section__num "3"] "Payment on delivery"]
           (ui/radio-group {:name "checkout-payment" :legend "How will you pay?"
                            :value (:payment-method values) :error (:payment-method errors)
                            :on-change [:form/set :checkout :payment-method]
                            :options [["cash" "Cash"] ["mobileMoney" "Mobile money"]]})
           [:p.field__hint "You pay when your order arrives. Nothing is charged online."]]
          [:fieldset.checkout-section
           [:legend [:span.checkout-section__num "4"] "Order note"]
           (ui/textarea-input {:id "checkout-note" :label "Note for the shop" :optional? true :maxlength 500
                               :value (:note values) :error (:note errors)
                               :on-input [:form/input :checkout :note [:event/target.value]]})]
          [:div.checkout-section
           (policy/agreement-panel {:policy policy
                                    :status (or policy-status :loading)
                                    :accepted? (:accepted? values)
                                    :error (:accepted errors)})]]
         [:aside.checkout-aside
          (order/order-summary {:lines (map (fn [it] {:name (:name it) :quantity (:quantity it) :unitPrice (:price it)}) items)
                                :subtotal (or (:itemsSubtotal result)
                                              (reduce + 0 (map #(* (:price %) (:quantity %)) items)))})
          [:p "Delivery fee: to be agreed with you."]
          (ui/link "/cart" {:class "text-link"} "Back to cart")
          (ui/button {:type "submit" :full? true
                      :disabled? (or submitting? (not (:accepted? values)) (not= :ready policy-status))}
                     (if submitting? "Placing your order" "Place order"))
          (when-not (:accepted? values)
            [:p.field__hint "Accept the order terms to place your order."])
          [:p.checkout-aside__note "We will contact you to confirm your order."]
          (when (:retry? form)
            (ui/notice {:kind :warning :live? true}
                       "We could not reach the shop. Your order has not been lost. Press Place order to try again; the same order will not be placed twice."))]]])]))

;; ---------------------------------------------------------------------------
;; P7 Order placed

(defn order-placed-page [{:keys [last-order forms site]}]
  (let [{:keys [order]} last-order]
    (if-not order
      [:div.container.narrow
       [:h1.page-title "Order received"]
       (ui/empty-state {:title "Look up your order"
                        :text "If you have just placed an order, use your order number and phone number to check it."}
                       (ui/link-button "/track-order" {} "Track your order"))]
      [:div.container.narrow.order-placed
       [:div.confirmation
        (ui/icon :check :lg)
        [:h1.page-title (str "Thank you, " (fmt/first-name (get-in order [:customer :name])) ".")]
        [:p.confirmation__lead "Your order has been received."]
        [:p.confirmation__number
         [:span "Order number"]
         [:strong#order-number (:orderNumber order)]
         (ui/button {:variant :secondary :on-click [[:copy-text (:orderNumber order)]]
                     :attrs {:aria-label "Copy order number"}}
                    (ui/icon :copy :sm) "Copy")]]
       [:section.what-next {:aria-labelledby "next-title"}
        [:h2#next-title "What happens next"]
        [:ol
         [:li (str "We contact you on " (fmt/phone-display (get-in order [:customer :phone])) " to agree the delivery fee.")]
         [:li "We send out your order."]
         [:li "You pay on delivery, in cash or mobile money."]]
        [:p "If you do not agree the fee, you can cancel before dispatch."]]
       (order/order-summary {:lines (:items order) :subtotal (:itemsSubtotal order) :deliveryFee (:deliveryFee order)
                             :total (:total order) :heading "Your order"})
       [:div.actions
        (ui/whatsapp-button site (str "Hello, my order number is " (:orderNumber order) ".") "Message us on WhatsApp")
        (ui/link-button "/track-order" {:variant :secondary} "Track your order")
        (ui/link-button "/shop" {:variant :secondary} "Continue shopping")]
       [:section.section
        [:div.newsletter-box
         [:h2 "Get new arrivals"]
         (let [form (:newsletter forms)]
           (if (= :done (:status form))
             (ui/notice {:kind :success :live? true} "Thank you. We will tell you when new products arrive.")
             [:form {:novalidate true :on {:submit [[:dom/prevent-default] [:newsletter/submit "orderConfirmation"]]}}
              (ui/text-field {:id "confirm-email" :label "Email address" :type "email" :autocomplete "email"
                              :value (get-in form [:values :email]) :error (get-in form [:errors :email])
                              :on-input [:form/input :newsletter :email [:event/target.value]]})
              (ui/button {:type "submit" :disabled? (= :submitting (:status form))} "Subscribe")]))]]])))

;; ---------------------------------------------------------------------------
;; P8 Track

(defn track-page [{:keys [forms site]}]
  (let [form (:track forms)
        {:keys [values errors status result]} form]
    [:div.container.narrow
     [:h1.page-title "Track your order"]
     [:form.track-form {:novalidate true :on {:submit [[:dom/prevent-default] [:track/submit]]}}
      (when (:form errors) (ui/notice {:kind :error :live? true} (:form errors)))
      (ui/text-field {:id "track-number" :label "Order number" :placeholder "ZT-XXXXXX" :autocomplete "off"
                      :value (:order-number values) :error (:order-number errors)
                      :on-input [:form/input :track :order-number [:event/target.value]]})
      (ui/text-field {:id "track-phone" :label "Phone number" :type "tel" :autocomplete "tel" :inputmode "tel"
                      :value (:phone values) :error (:phone errors)
                      :on-input [:form/input :track :phone [:event/target.value]]})
      (ui/button {:type "submit" :disabled? (= :submitting status)}
                 (if (= :submitting status) "Looking up" "Track order"))]
     (when result
       [:section.track-result {:aria-labelledby "track-title" :aria-live "polite"}
        [:h2#track-title (str "Order " (:orderNumber result))]
        [:p (str "Status: " (fmt/order-status-label (:status result)))]
        (order/status-timeline (:timeline result) (:status result))
        (order/order-summary {:lines (:items result) :subtotal (:itemsSubtotal result)
                              :deliveryFee (:deliveryFee result) :total (:total result) :heading "Items"})
        [:p (str "Payment: " (fmt/payment-label (:paymentMethod result)) " on delivery. City: " (:city result) ".")]
        (when-let [wa (ui/whatsapp-button site (str "Hello, about order " (:orderNumber result)) "Need help? WhatsApp us")]
          wa)])]))
