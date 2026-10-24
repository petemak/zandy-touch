(ns zandy.shared.pages.catalog
  "Home, shop, category and product pages (P1 to P4)."
  (:require [zandy.shared.routes :as routes]
            [zandy.shared.ui.core :as ui]
            [zandy.shared.ui.product :as product]))

(defn- section [id title more-path & content]
  [:section.section {:aria-labelledby id}
   [:div.section__head
    [:h2.section__title {:id id} title]
    (when more-path (ui/link more-path {:class "text-link"} "View all"))]
   (into [:div] content)])

(defn home [{:keys [site page]}]
  (let [{:keys [categories new own featured]} (:data page)]
    [:div
     [:section.hero {:aria-labelledby "hero-title"}
      [:div.hero__inner.container
       [:div.hero__text
        [:p.hero__eyebrow "Zandy Touch, Kampala"]
        [:h1#hero-title.hero__title "Beauty with Confidence"]
        [:p.hero__lead "Skincare, perfumes and wellness products, chosen with care. Order online and pay on delivery."]
        [:div.hero__actions
         (ui/link-button "/shop" {:variant :on-dark} "Shop now")
         (when (:whatsapp site)
           [:a.button.button--secondary-on-dark {:href (ui/whatsapp-href (:whatsapp site) "Hello, I would like to order.") :rel "noopener"}
            (ui/icon :whatsapp) [:span.button__label "Order on WhatsApp"]])]]
       [:div.hero__media.hero__media--wide (ui/site-photo "hero-zandy-range.webp" "The Zandy Touch range: body lotion, body oil, body scrub and face cream in gold packaging" 901 614 true "site-photo--natural")]]]
     [:div.container
      (section "cats-title" "Shop by category" nil
               [:ul.category-grid (map product/category-tile categories)])
      (when (seq new)
        (section "new-title" "New arrivals" "/shop" (product/product-row (:items new))))
      (when (seq (:items own))
        (section "own-title" "Zandy Touch own brand" "/shop?q=Zandy"
                 [:p.section__lead "Our own range focuses on natural, organic and authentic products, made for everyday confidence."]
                 (product/product-row (:items own))))
      (when (seq (:items featured))
        (section "featured-title" "Featured products" nil (product/product-row (:items featured))))
      [:section.section {:aria-labelledby "how-title"}
       [:h2.section__title {:id "how-title"} "How ordering works"]
       [:ol.steps
        [:li.step (ui/icon :search :lg) [:h3 "Choose"] [:p "Browse the shop and add what you like to your cart."]]
        [:li.step (ui/icon :phone :lg) [:h3 "We confirm delivery with you"] [:p "We contact you to agree the delivery fee before anything is sent."]]
        [:li.step (ui/icon :pay-on-delivery :lg) [:h3 "Pay on delivery"] [:p "Pay with cash or mobile money when your order arrives."]]]]]]))

(defn shop [{:keys [route page site]}]
  (let [data (get-in page [:data :products])
        query (select-keys (:query route) [:q :availability :sort])
        q (:q query)]
    [:div.container
     (ui/breadcrumb [["Home" "/"] ["Shop" nil]])
     [:h1.page-title (if q (str "Results for “" q "”") "All products")]
     [:p.result-count {:aria-live "polite"} (str (:totalItems data) (if (= 1 (:totalItems data)) " product" " products"))]
     (product/filter-bar {:base-path "/shop" :query query :current-slug nil})
     (product/product-listing data {:base-path "/shop" :query query :site site :loading? (get-in page [:loading-more?])})]))

(defn category [{:keys [route page site]}]
  (let [slug (get-in route [:params :slug])
        cat (some #(when (= slug (:slug %)) %) (get-in page [:data :categories]))
        data (get-in page [:data :products])
        base-path (routes/path-for :category {:slug slug})
        query (select-keys (:query route) [:q :availability :sort])]
    [:div
     [:div.category-header
      [:div.container
       (ui/breadcrumb [["Home" "/"] ["Shop" "/shop"] [(:name cat) nil]])
       [:h1.page-title (:name cat)]
       (when (:description cat) [:p.category-header__intro (:description cat)])]]
     [:div.container
      (product/filter-bar {:base-path base-path :query query :current-slug slug})
      (product/product-listing data {:base-path base-path :query query :site site :loading? (get-in page [:loading-more?])})]]))

(defn product-page [{:keys [page site] ui-state :ui}]
  (let [p (:data page)
        {:keys [category related]} p]
    [:div.container
     (ui/breadcrumb [["Home" "/"] ["Shop" "/shop"]
                     [(:name category) (routes/path-for :category {:slug (:slug category)})]
                     [(:name p) nil]])
     [:div.product-layout
      (product/gallery p (get-in ui-state [:gallery :index]))
      [:div.product-layout__info
       [:h1.product-title (:name p)]
       (product/buy-box p {:qty (get-in ui-state [:product :qty])
                           :stock-note (get-in ui-state [:product :stock-note])
                           :added? (get-in ui-state [:product :added?])
                           :site site})]]
     (product/details-accordion p)
     (when (seq related)
       [:section.section {:aria-labelledby "related-title"}
        [:h2.section__title {:id "related-title"} "You may also like"]
        (product/product-row related)])]))
