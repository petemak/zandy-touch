(ns zandy.frontend.scenes.components
  "Portfolio scenes for the shared UI components (Button, Badge, StatusPill,
  Notice, FormField, ProductCard, Gallery, QuantityStepper, CartLine,
  OrderSummary, StatusTimeline, AgreementPanel, SiteHeader, SiteFooter ...),
  one scene per meaningful state. Browser only; not reachable from
  frontend.main."
  (:require [portfolio.replicant :refer-macros [defscene]]
            [zandy.frontend.scenes.fixtures :as fx]
            [zandy.shared.ui.core :as ui]
            [zandy.shared.ui.layout :as layout]
            [zandy.shared.ui.order :as order]
            [zandy.shared.ui.policy :as policy]
            [zandy.shared.ui.product :as product]))

(defn- wrap [& children] (into [:div {:style {:padding "1rem"}}] children))

;; Buttons --------------------------------------------------------------
(defscene button-primary :title "Button / primary" (wrap (ui/button {} "Add to cart")))
(defscene button-secondary :title "Button / secondary" (wrap (ui/button {:variant :secondary} "Keep shopping")))
(defscene button-disabled :title "Button / disabled" (wrap (ui/button {:disabled? true} "Sold out")))
(defscene button-on-dark :title "Button / on dark" [:div.hero {:style {:padding "1rem"}} (ui/button {:variant :on-dark} "Shop now")])
(defscene button-full :title "Button / full width" (wrap (ui/button {:full? true} "Place order")))

;; Badges, pills ----------------------------------------------------------
(defscene badges :title "Badges" (wrap [:div.badges (ui/badge :new) (ui/badge :own-brand) (ui/badge :sold-out) (ui/badge :featured) (ui/badge :low-stock)]))
(defscene pills :title "Status pills"
  (wrap [:div.row-actions (for [s ["placed" "confirmed" "approvedForDelivery" "delivered" "cancelled"]] ^{:key s} (ui/order-status-pill s))
         (ui/pill :muted "Archived") (ui/pill :success "Published") (ui/pill :info "Draft")]))

;; Notices ---------------------------------------------------------------
(defscene notice-info :title "Notice / info" (wrap (ui/notice {:kind :info} "Items are not held until you place your order.")))
(defscene notice-success :title "Notice / success" (wrap (ui/notice {:kind :success} "Added to your cart.")))
(defscene notice-warning :title "Notice / warning" (wrap (ui/notice {:kind :warning} "Price changed. Now UGX 90,000")))
(defscene notice-error :title "Notice / error" (wrap (ui/notice {:kind :error} "Sold out, remove to continue")))
(defscene notice-draft :title "Notice / draft text" (wrap (ui/draft-notice)))

;; Images ----------------------------------------------------------------
(defscene image-photo :title "Image / photo" (wrap [:div {:style {:max-width "16rem"}} (ui/picture (fx/img 1) {})]))
(defscene image-placeholder :title "Image / placeholder (no photo)" (wrap [:div {:style {:max-width "16rem"}} (ui/placeholder-image "Daily Moisturising Cream 50 ml")]))
(defscene image-svg-placeholder :title "Image / brand placeholder (hero, about)" (wrap [:div {:style {:max-width "16rem"}} (ui/svg-placeholder "Zandy Touch products")]))

;; Form fields -----------------------------------------------------------
(defscene field-default :title "Field / default" (wrap (ui/text-field {:id "f1" :label "Full name" :value "" :on-input [:form/noop]})))
(defscene field-hint :title "Field / hint, optional" (wrap (ui/text-field {:id "f2" :label "Email" :optional? true :hint "Only used to send you an order receipt." :value "" :on-input [:form/noop]})))
(defscene field-error :title "Field / error" (wrap (ui/text-field {:id "f3" :label "Phone number" :value "123" :error "Enter a valid Uganda phone number." :on-input [:form/noop]})))
(defscene field-select :title "Field / select" (wrap (ui/select-input {:id "f4" :label "City" :value "" :placeholder "Choose your city" :options [["Kampala" "Kampala"] ["Jinja" "Jinja"]] :on-change [:form/noop]})))
(defscene field-select-error :title "Field / select with error" (wrap (ui/select-input {:id "f5" :label "City" :value "" :error "Choose your city." :options [["Kampala" "Kampala"]] :on-change [:form/noop]})))
(defscene field-checkbox :title "Checkbox / unchecked" (wrap (ui/checkbox {:id "c1" :label "I have read and accept the order terms (version 2026-10-01)" :checked? false :on-change [:form/noop]})))
(defscene field-checkbox-error :title "Checkbox / error" (wrap (ui/checkbox {:id "c2" :label "I accept" :checked? false :error "Please accept the order terms to place your order." :on-change [:form/noop]})))
(defscene field-radio :title "Radio group" (wrap (ui/radio-group {:name "pay" :legend "How will you pay?" :value "cash" :options [["cash" "Cash"] ["mobileMoney" "Mobile money"]] :on-change [:form/noop]})))
(defscene error-summary :title "Error summary" (wrap (ui/error-summary [{:id "f3" :message "Enter a valid Uganda phone number."} {:id "f1" :message "Enter your full name."}])))

;; Misc -------------------------------------------------------------------
(defscene breadcrumb :title "Breadcrumb" (wrap (ui/breadcrumb [["Home" "/"] ["Shop" "/shop"] ["Skincare" "/categories/skincare"] ["Serum" nil]])))
(defscene empty-state :title "Empty state" (wrap (ui/empty-state {:title "Your cart is empty" :text "Browse the shop and add something you like."} (ui/link-button "/shop" {} "Keep shopping"))))
(defscene loading :title "Loading text" (wrap (ui/spinner-text "Loading the order terms")))

;; Product components ------------------------------------------------------
(defscene card-available :title "Product card / available, new, own brand" (wrap [:div {:style {:max-width "16rem"}} (product/product-card fx/serum)]))
(defscene card-sold-out :title "Product card / sold out" (wrap [:div {:style {:max-width "16rem"}} (product/product-card fx/sold-out)]))
(defscene card-no-photo :title "Product card / no photo" (wrap [:div {:style {:max-width "16rem"}} (product/product-card (assoc fx/cleanser :image nil))]))
(defscene grid :title "Product grid" (wrap (product/product-grid fx/products)))
(defscene category-tile :title "Category tile" (wrap [:ul.category-grid (map product/category-tile fx/categories)]))
(defscene gallery-many :title "Gallery / several photos" (wrap [:div {:style {:max-width "28rem"}} (product/gallery fx/product-detail 0)]))
(defscene gallery-one :title "Gallery / one photo" (wrap [:div {:style {:max-width "28rem"}} (product/gallery (assoc fx/product-detail :images [(fx/img 1)]) 0)]))
(defscene gallery-none :title "Gallery / no photo" (wrap [:div {:style {:max-width "28rem"}} (product/gallery (assoc fx/product-detail :images []) 0)]))
(defscene stepper-min :title "Quantity stepper / minimum" (wrap (product/quantity-stepper {:id "q1" :label "Serum" :value 1 :max-q 10 :on-dec [:form/noop] :on-inc [:form/noop] :on-set [:form/noop]})))
(defscene stepper-max :title "Quantity stepper / at the limit" (wrap (product/quantity-stepper {:id "q2" :label "Serum" :value 3 :max-q 3 :on-dec [:form/noop] :on-inc [:form/noop] :on-set [:form/noop]})))
(defscene buy-available :title "Buy box / available" (wrap [:div {:style {:max-width "24rem"}} (product/buy-box fx/product-detail {:qty 1 :site fx/site})]))
(defscene buy-limited :title "Buy box / limited stock" (wrap [:div {:style {:max-width "24rem"}} (product/buy-box (assoc fx/product-detail :maxQuantity 3) {:qty 2 :site fx/site})]))
(defscene buy-sold-out :title "Buy box / sold out" (wrap [:div {:style {:max-width "24rem"}} (product/buy-box (assoc fx/product-detail :availability "soldOut" :maxQuantity 0) {:qty 1 :site fx/site})]))
(defscene buy-stock-changed :title "Buy box / stock changed notice" (wrap [:div {:style {:max-width "24rem"}} (product/buy-box (assoc fx/product-detail :maxQuantity 2) {:qty 2 :stock-note "Stock changed. Only 2 can be ordered now." :site fx/site})]))
(defscene buy-added :title "Buy box / added to cart" (wrap [:div {:style {:max-width "24rem"}} (product/buy-box fx/product-detail {:qty 1 :added? true :site fx/site})]))
(defscene buy-no-whatsapp :title "Buy box / no WhatsApp number" (wrap [:div {:style {:max-width "24rem"}} (product/buy-box fx/product-detail {:qty 1 :site fx/site-minimal})]))
(defscene accordion :title "Details accordion" (wrap (product/details-accordion fx/product-detail)))
(defscene filter-bar :title "Filter bar" (wrap (product/filter-bar {:base-path "/shop" :query {:availability "available" :sort "priceAsc"} :current-slug nil})))
(defscene load-more :title "Load more" (wrap (product/load-more {:base-path "/shop" :query {} :page 1 :pageSize 24 :totalItems 60})))
(defscene load-more-busy :title "Load more / loading" (wrap (product/load-more {:base-path "/shop" :query {} :page 1 :pageSize 24 :totalItems 60 :loading? true})))
(defscene listing-empty :title "Listing / nothing matched" (wrap (product/product-listing {:items [] :page 1 :pageSize 24 :totalItems 0} {:site fx/site})))

;; Header, footer, newsletter ------------------------------------------------
(defscene header-empty :title "Header / empty cart" (layout/site-header {:cart {:items []} :route {:name :home} :ui {}}))
(defscene header-items :title "Header / cart with items" (layout/site-header {:cart {:items fx/cart-items} :route {:name :shop} :ui {}}))
(defscene header-search-open :title "Header / search open" (layout/site-header {:cart {:items []} :route {:name :home} :ui {:search-open? true}}))
(defscene footer-full :title "Footer / full contact details, TikTok and Facebook (Instagram not configured)" (layout/site-footer {:site fx/site :forms {}}))
(defscene footer-minimal :title "Footer / unconfirmed details hidden" (layout/site-footer {:site fx/site-minimal :forms {}}))
(defscene footer-no-social :title "Footer / no social profile configured (list hidden)" (layout/site-footer {:site fx/site-no-social :forms {}}))
(defscene footer-social :title "Footer / all three social links set" (layout/site-footer {:site fx/site-social :forms {}}))
(defscene newsletter-idle :title "Newsletter / idle" (wrap (layout/newsletter-form {:id "n1" :source "footer" :form {}})))
(defscene newsletter-error :title "Newsletter / error" (wrap (layout/newsletter-form {:id "n2" :source "footer" :form {:values {:email "x"} :errors {:email "Enter a valid email address."}}})))
(defscene newsletter-busy :title "Newsletter / submitting" (wrap (layout/newsletter-form {:id "n3" :source "footer" :form {:values {:email "a@b.co"} :status :submitting}})))
(defscene newsletter-done :title "Newsletter / done" (wrap (layout/newsletter-form {:id "n4" :source "footer" :form {:status :done}})))

;; Cart, order, policy ---------------------------------------------------------
(def ^:private item1 (first fx/cart-items))
(defscene line-ok :title "Cart line / ok" (wrap [:ul.cart-lines (order/cart-line item1 (first (:items fx/validation-ok)))]))
(defscene line-price :title "Cart line / price changed" (wrap [:ul.cart-lines (order/cart-line item1 (assoc (first (:items fx/validation-ok)) :unitPrice 90000))]))
(defscene line-stock :title "Cart line / not enough stock" (wrap [:ul.cart-lines (order/cart-line item1 (first (:items fx/validation-problems)))]))
(defscene line-sold-out :title "Cart line / sold out" (wrap [:ul.cart-lines (order/cart-line (second fx/cart-items) (second (:items fx/validation-problems)))]))
(defscene summary-open :title "Order summary / fee to be agreed" (wrap (order/order-summary {:lines (:items fx/order) :subtotal 170000})))
(defscene summary-fee :title "Order summary / fee recorded" (wrap (order/order-summary {:lines (:items fx/order) :subtotal 170000 :deliveryFee 10000 :total 180000})))
(defscene timeline-confirmed :title "Status timeline / confirmed" (wrap (order/status-timeline (:timeline fx/tracking) "confirmed")))
(defscene timeline-delivered :title "Status timeline / delivered" (wrap (order/status-timeline [{:status "placed" :at "2026-10-24T09:30:00Z"} {:status "confirmed" :at "2026-10-24T10:00:00Z"} {:status "approvedForDelivery" :at "2026-10-24T11:00:00Z"} {:status "delivered" :at "2026-10-24T14:00:00Z"}] "delivered")))
(defscene timeline-cancelled :title "Status timeline / cancelled" (wrap (order/status-timeline [{:status "placed" :at "2026-10-24T09:30:00Z"} {:status "cancelled" :at "2026-10-24T10:00:00Z"}] "cancelled")))
(defscene agreement-loading :title "Agreement panel / loading" (wrap (policy/agreement-panel {:status :loading})))
(defscene agreement-error :title "Agreement panel / failed to load" (wrap (policy/agreement-panel {:status :error})))
(defscene agreement-draft :title "Agreement panel / draft text" (wrap (policy/agreement-panel {:status :ready :policy fx/policy :accepted? false})))
(defscene agreement-approved :title "Agreement panel / approved, accepted" (wrap (policy/agreement-panel {:status :ready :policy fx/policy-approved :accepted? true})))
(defscene agreement-not-accepted :title "Agreement panel / not accepted error" (wrap (policy/agreement-panel {:status :ready :policy fx/policy :accepted? false :error "Please accept the order terms to place your order."})))
(defscene policy-draft :title "Policy document / draft" (wrap (policy/policy-document fx/policy)))
(defscene policy-approved :title "Policy document / approved" (wrap (policy/policy-document fx/policy-approved)))
