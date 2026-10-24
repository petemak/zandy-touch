(ns zandy.shared.pages.info
  "About, contact, delivery, legal, unsubscribe and error pages."
  (:require [zandy.shared.format :as fmt]
            [zandy.shared.ui.core :as ui]
            [zandy.shared.ui.policy :as policy]))

(defn about [{:keys [site]}]
  [:div.container
   [:h1.page-title "About Zandy Touch"]
   [:div.two-col
    [:div.prose
     [:h2 "Our story"]
     [:p "Zandy Touch is a beauty and wellness business based in Kampala, Uganda. We offer skincare, perfumes, body-shaping products, body care, weight loss and health and wellness products."]
     [:p "We believe beauty comes with confidence. We choose our products with care, offer friendly service and make ordering simple, so you can shop knowing what to expect."]
     [:h2 "What we value"]
     [:ul
      [:li "Confidence: products that help you feel your best."]
      [:li "Trust: honest information and fair prices."]
      [:li "Professionalism: reliable ordering and delivery."]
      [:li "Friendly service: we listen and suggest what suits you."]]
     [:h2 "Our own brand"]
     [:p "Alongside established brands, we develop products under the Zandy Touch name, with a focus on natural, organic and authentic ingredients."]]
    [:div (ui/site-photo "about-zandy.jpg" "Smiling young woman outdoors" 512 640 false)]]
   [:section.section {:aria-labelledby "visit-title"}
    [:h2.section__title {:id "visit-title"} "Visit the shop"]
    (if-let [addr (:shopAddress site)]
      [:p addr]
      [:p "Contact us for the shop address."])
    (ui/link-button "/contact" {:variant :secondary} "Contact us")]])

(defn contact-form [form]
  (let [{:keys [values errors status]} form]
    (if (= :done status)
      (ui/notice {:kind :success :live? true}
                 [:p "Thank you. Your message has been received and we will get back to you."])
      [:form.contact-form {:novalidate true :on {:submit [[:dom/prevent-default] [:contact/submit]]}}
       (ui/error-summary (vec (for [[k id] [[:name "contact-name"] [:phone "contact-phone"] [:email "contact-email"] [:message "contact-message"]]
                                    :let [m (get errors k)] :when m]
                                {:id id :message m})))
       (when (:form errors) (ui/notice {:kind :error :live? true} (:form errors)))
       (ui/text-field {:id "contact-name" :label "Your name" :autocomplete "name" :required? true
                       :value (:name values) :error (:name errors)
                       :on-input [:form/input :contact :name [:event/target.value]]})
       (ui/text-field {:id "contact-phone" :label "Phone number" :type "tel" :autocomplete "tel" :inputmode "tel" :optional? true
                       :hint "Give a phone number or an email address."
                       :value (:phone values) :error (:phone errors)
                       :on-input [:form/input :contact :phone [:event/target.value]]})
       (ui/text-field {:id "contact-email" :label "Email" :type "email" :autocomplete "email" :optional? true
                       :value (:email values) :error (:email errors)
                       :on-input [:form/input :contact :email [:event/target.value]]})
       (ui/textarea-input {:id "contact-message" :label "Message" :maxlength 1000
                           :hint "10 to 1000 characters."
                           :value (:message values) :error (:message errors)
                           :on-input [:form/input :contact :message [:event/target.value]]})
       (ui/button {:type "submit" :disabled? (= :submitting status)}
                  (if (= :submitting status) "Sending" "Send message"))])))

(defn contact [{:keys [site forms]}]
  (let [{:keys [phone whatsapp email shopAddress openingHours]} site]
    [:div.container
     [:h1.page-title "Contact us"]
     [:div.two-col
      [:section {:aria-labelledby "reach-title"}
       [:h2#reach-title "Reach us"]
       [:ul.contact-list
        (when phone [:li (ui/icon :phone) (ui/external-link (str "tel:" phone) {} (fmt/phone-display phone))])
        (when whatsapp [:li (ui/icon :whatsapp) (ui/external-link (ui/whatsapp-href whatsapp nil) {:rel "noopener"} "Chat on WhatsApp")])
        (when email [:li (ui/icon :email) (ui/external-link (str "mailto:" email) {} email)])
        (when shopAddress [:li (ui/icon :location) [:span shopAddress]])
        (when (seq openingHours)
          [:li (ui/icon :history)
           [:ul.hours (for [h openingHours] ^{:key (:days h)} [:li (fmt/hours-label h)])]])]]
      [:section {:aria-labelledby "message-title"}
       [:h2#message-title "Send us a message"]
       (contact-form (:contact forms))]]]))

(defn delivery [{:keys [page site]}]
  (let [areas (:data page)
        cities (get-in areas [:countries 0 :cities])]
    [:div.container
     [:h1.page-title "Delivery and payment"]
     [:div.prose
      [:h2 "How delivery works"]
      [:p "We deliver to major cities in Uganda. The delivery fee and timing are agreed with you for each order, and the fee is recorded before anything is sent. We never add a delivery fee without your agreement, and you can cancel before dispatch if you do not agree."]
      [:p "Delivery to neighbouring countries is coming later."]
      [:h2 "How to pay"]
      [:p "You pay when your order arrives, in cash or with mobile money. Nothing is paid online."]
      [:h2 "Cities we deliver to"]
      (if (seq cities)
        [:ul.city-list (for [c cities] ^{:key c} [:li c])]
        [:p "Contact us to ask about delivery to your area."])
      (when-not (:allowOtherCity areas)
        [:p "Do not see your city? Ask us, and we will tell you whether we can deliver to you."
         (when (:whatsapp site)
           [:span " " (ui/external-link (ui/whatsapp-href (:whatsapp site) "Hello, can you deliver to my city?")
                                         {:class "text-link" :rel "noopener"} "Ask on WhatsApp")])])
      [:h2 "Ordering by phone or WhatsApp"]
      [:p "For an order, please use the webshop. We are happy to send you a link to any product."]
      (ui/link-button "/shop" {} "Shop now")]]))

(defn legal [{:keys [page]}]
  [:div.container.narrow
   (policy/policy-document (:data page))])

(defn unsubscribe [{:keys [route forms]}]
  (let [form (:unsubscribe forms)
        token (get-in route [:query :token])]
    [:div.container.narrow
     [:h1.page-title "Unsubscribe"]
     (cond
       (= :done (:status form))
       (ui/notice {:kind :success :live? true} "You have been unsubscribed. We will not email you any more news.")

       (nil? token)
       (ui/notice {:kind :warning} "This link is missing its token. Please use the unsubscribe link from our email.")

       :else
       [:div
        [:p "Press the button to stop receiving news from Zandy Touch."]
        (when (:form (:errors form)) (ui/notice {:kind :error :live? true} (:form (:errors form))))
        (ui/button {:disabled? (= :submitting (:status form)) :on-click [[:unsubscribe/submit]]} "Unsubscribe me")])]))

(defn not-found [{:keys [site]}]
  [:div.container.narrow
   [:h1.page-title "We could not find that page"]
   [:p "The page may have moved, or the product is no longer available."]
   [:form.search-box {:action "/shop" :method "get" :role "search"
                      :on {:submit [[:dom/prevent-default] [:search/submit [:event/form-data]]]}}
    [:label.field__label {:for "nf-q"} "Search the shop"]
    [:div.search-box__row
     [:input.input {:id "nf-q" :name "q" :type "search" :minlength 2 :maxlength 80}]
     (ui/button {:type "submit"} "Search")]]
   [:ul.chips
    (for [[slug label] ui/nav-categories]
      ^{:key slug} [:li [:a.chip {:href (str "/categories/" slug)} label]])]
   (when (:whatsapp site)
     [:p (ui/external-link (ui/whatsapp-href (:whatsapp site) "Hello, I am looking for a product.")
                           {:class "text-link" :rel "noopener"} "Ask us on WhatsApp")])])

(defn unavailable [{:keys [site]}]
  (let [phone (or (:phone site) "+256706105779")]
    [:div.container.narrow
     [:h1.page-title "We are having trouble loading the shop"]
     [:p "Please try again in a minute."]
     [:p "You can also call us on " (ui/external-link (str "tel:" phone) {} (fmt/phone-display phone)) "."]]))
