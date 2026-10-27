(ns zandy.shared.ui.layout
  "Site frame shared by every public page: SiteHeader / Nav, AnnouncementBar,
  SiteFooter (with NewsletterForm and social links), logos and the page frame
  with the skip link. Pure hiccup functions, shared code (.cljc): rendered on
  the JVM for the first response and in the browser afterwards. The admin area
  has its own shell in frontend.admin.core."
  (:require [clojure.string :as str]
            [zandy.shared.cart :as cart]
            [zandy.shared.format :as fmt]
            [zandy.shared.routes :as routes]
            [zandy.shared.ui.core :as ui]))

(def nav-categories
  "The six fixed categories as [slug label] pairs (alias of
  ui.core/nav-categories), shown in the header navigation strip."
  ui/nav-categories)

(def static-prefix
  "URL prefix under which the frontend server serves the files of assets/ (see
  ssr.static)."
  "/static/assets/")

(defn logo
  "Logo as an image from assets/logo. `variant` is a file name stem."
  [stem alt cls]
  [:img {:class cls :src (str static-prefix "logo/" stem ".svg") :alt alt}])

;; The logo files are transparent WebP made for dark backgrounds, which is why
;; they only appear in the header, footer and admin bar.
(def brand-logos
  "The owner's real logo, cut from business/images/logo/zandy-logo.jpeg.
  Transparent WebP for dark surfaces only (header, footer, admin bar)."
  {:horizontal {:file "zandy-touch-horizontal.webp" :width 655 :height 128}
   :lockup     {:file "zandy-touch-lockup.webp" :width 488 :height 260}})

(defn brand-logo
  "The owner's logo on a dark surface. `kind` is :horizontal or :lockup."
  [kind alt cls]
  (let [{:keys [file width height]} (brand-logos kind)]
    [:img {:class cls :src (str static-prefix "logo/" file) :alt alt
           :width width :height height}]))

(defn site-header
  "SiteHeader / Nav. Argument: page state; uses :cart (the count on the cart
  link), :route (marks the current nav link with aria-current) and
  (:search-open? :ui). Renders the logo link, the search disclosure (a GET
  form to /shop, also handled client-side by [:search/submit]), the cart link
  and the category nav strip. Returns hiccup."
  [{:keys [cart route] ui-state :ui}]
  (let [n (cart/count-items cart)
        current (get-in route [:params :slug])]
    [:header.site-header
     [:div.site-header__bar.container
      (ui/link "/" {:class "site-header__logo" :aria-label "Zandy Touch, home"}
               (brand-logo :horizontal "" "site-header__logo-img"))
      [:div.site-header__actions
       ;; Why a <details>/<summary>: the search field opens and closes without
       ;; JavaScript; the toggle event keeps :search-open? in state so a
       ;; re-render does not close it.
       [:details.header-search {:open (boolean (:search-open? ui-state))
                                :on {:toggle [[:ui/search-toggled [:event/target.open]]]}}
        [:summary.icon-button {:aria-label "Search products"}
         (ui/icon :search)]
        [:form.header-search__form {:action "/shop" :method "get" :role "search"
                                    :on {:submit [[:dom/prevent-default] [:search/submit [:event/form-data]]]}}
         [:label.visually-hidden {:for "header-q"} "Search products"]
         [:input.input {:id "header-q" :name "q" :type "search" :minlength 2 :maxlength 80
                        :placeholder "Search products" :autocomplete "off"}]
         [:button.button.button--on-dark {:type "submit"} [:span.button__label "Search"]]]]
       (ui/link "/cart" {:class ["icon-button" "cart-link"]
                         :aria-label (if (pos? n) (str "Cart, " n (if (= 1 n) " item" " items")) "Cart, empty")}
                (ui/icon :cart)
                (when (pos? n) [:span.cart-link__count n]))]]
     [:nav.site-nav {:aria-label "Product categories"}
      [:ul.site-nav__list.container
       [:li (ui/link "/shop" {:class "site-nav__link"
                              :aria-current (when (= :shop (:name route)) "page")} "All products")]
       (for [[slug label] nav-categories]
         ^{:key slug}
         [:li (ui/link (routes/path-for :category {:slug slug})
                       {:class "site-nav__link"
                        :aria-current (when (and (= :category (:name route)) (= slug current)) "page")}
                       label)])]]]))

(defn newsletter-form
  "Footer and confirmation-page signup. `state` is the :newsletter form."
  [{:keys [id source form dark?]}]
  (let [{:keys [values errors status]} form]
    (if (= :done status)
      (ui/notice {:kind :success :live? true} "Thank you. We will tell you when new products arrive.")
      [:form {:class (ui/classes "newsletter" (when dark? "newsletter--dark"))
              :novalidate true
              :on {:submit [[:dom/prevent-default] [:newsletter/submit source]]}}
       [:h2.newsletter__title "Be first to know about new arrivals"]
       (ui/text-field {:id id :label "Email address" :type "email" :autocomplete "email"
                       :value (:email values) :error (:email errors)
                       :on-input [:form/input :newsletter :email [:event/target.value]]})
       (when (:form errors) (ui/notice {:kind :error} (:form errors)))
       [:p.newsletter__consent "By subscribing you agree that Zandy Touch may email you about new products. You can unsubscribe at any time."]
       (ui/button {:type "submit" :variant (if dark? :on-dark :primary) :disabled? (= :submitting status)}
                  (if (= :submitting status) "Subscribing" "Subscribe"))])))

(def ^:private social-networks
  "Footer social networks in display order as [site-info key, label, icon
  name]. Networks whose URL is not configured are hidden (api-contract.md
  section 2)."
  [[:tiktok "TikTok" :tiktok]
   [:facebook "Facebook" :facebook]
   [:instagram "Instagram" :instagram]])

(defn social-links
  "Footer \"Follow us\" block. `social` is the site-info social map {:tiktok
  :facebook :instagram}; networks with a blank URL are hidden. Returns hiccup,
  or nil when none is configured. Links open in a new tab and say so in their
  accessible name."
  [social]
  (let [shown (filter (fn [[k]] (not (str/blank? (get social k)))) social-networks)]
    (when (seq shown)
      [:div.site-footer__follow
       [:h2.site-footer__title "Follow us"]
       [:ul.site-footer__social
        (for [[k label icon] shown]
          ^{:key k}
          [:li (ui/external-link (get social k) {:rel "noopener" :target "_blank" :aria-label (str "Zandy Touch on " label " (opens in a new tab)")}
                                 (ui/icon icon) [:span.site-footer__social-label label])])]])))

(defn site-footer
  "SiteFooter. Argument: page state; uses :site (phone, WhatsApp, email,
  shopAddress, openingHours, social) and (:newsletter :forms). Renders the
  logo lockup, link columns, contact details, social links and the newsletter
  form. Returns hiccup."
  [{:keys [site forms]}]
  (let [{:keys [phone whatsapp email shopAddress openingHours social]} site]
    [:footer.site-footer
     [:div.site-footer__inner.container
      [:div.site-footer__brand
       (brand-logo :lockup "Zandy Touch, Beauty with Confidence" "site-footer__logo")
       [:p "Skincare, perfumes and wellness products from our shop in Kampala. Order online and pay on delivery."]]
      [:nav.site-footer__col {:aria-label "Shop links"}
       [:h2.site-footer__title "Shop"]
       [:ul
        [:li (ui/link "/shop" {} "All products")]
        [:li (ui/link "/about" {} "About us")]
        [:li (ui/link "/contact" {} "Contact")]
        [:li (ui/link "/delivery-and-payment" {} "Delivery and payment")]
        [:li (ui/link "/track-order" {} "Track your order")]]]
      [:nav.site-footer__col {:aria-label "Legal"}
       [:h2.site-footer__title "Legal"]
       [:ul
        [:li (ui/link "/legal/terms-of-sale" {} "Terms of sale")]
        [:li (ui/link "/legal/order-terms" {} "Order terms")]
        [:li (ui/link "/legal/privacy" {} "Privacy policy")]
        [:li (ui/link "/legal/returns-and-refunds" {} "Returns and refunds")]]]
      [:div.site-footer__col
       [:h2.site-footer__title "Visit or call"]
       [:ul.site-footer__contact
        (when phone [:li (ui/icon :phone :sm) (ui/external-link (str "tel:" phone) {} (fmt/phone-display phone))])
        (when whatsapp [:li (ui/icon :whatsapp :sm) (ui/external-link (ui/whatsapp-href whatsapp nil) {:rel "noopener"} "WhatsApp")])
        (when email [:li (ui/icon :email :sm) (ui/external-link (str "mailto:" email) {} email)])
        (when shopAddress [:li (ui/icon :location :sm) [:span shopAddress]])
        (for [h openingHours] ^{:key (:days h)} [:li (ui/icon :history :sm) [:span (fmt/hours-label h)]])]
       (social-links social)]
      [:div.site-footer__newsletter
       (newsletter-form {:id "footer-email" :source "footer" :form (:newsletter forms) :dark? true})]]
     [:p.site-footer__copy.container "© Zandy Touch, Kampala, Uganda"]]))

(defn announcement-bar
  "AnnouncementBar: \"Pay on delivery: cash or mobile money\". Returns hiccup."
  []
  [:p.announcement-bar "Pay on delivery: cash or mobile money"])

(defn page-frame
  "Page frame. Arguments: page state and `content` (hiccup for <main>). Renders
  the skip link (focuses #main), announcement bar, header, main, footer and a
  polite live region that reads (:announcement :ui) to screen reader users.
  Returns hiccup."
  [state content]
  [:div.page
   [:a.skip-link {:href "#main" :on {:click [[:dom/focus-id "main"]]}} "Skip to content"]
   (announcement-bar)
   (site-header state)
   [:main#main {:tabindex "-1"} content]
   (site-footer state)
   [:div.visually-hidden {:role "status" :aria-live "polite" :aria-atomic "true"}
    (get-in state [:ui :announcement])]])
