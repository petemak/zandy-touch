(ns zandy.shared.ui.product
  "Product components: ProductCard, ProductGrid, CategoryTile, Gallery,
  QuantityStepper, buy box, details accordion, filter bar, Load more and the
  listing with its empty state (design-spec.md section 3, used by P1 to P4).
  Pure hiccup functions, shared code (.cljc). Actions are data handled by
  frontend.state."
  (:require [zandy.shared.format :as fmt]
            [zandy.shared.routes :as routes]
            [zandy.shared.ui.core :as ui]))

(defn badges
  "Badge group for a product summary. Argument: product map with :isNew,
  :isOwnBrand and :availability (\"available\" or \"soldOut\"). Sold out hides
  New. Returns hiccup, or nil when there are no badges."
  [{:keys [isNew isOwnBrand availability]}]
  (let [bs (cond-> []
             (= "soldOut" availability) (conj (ui/badge :sold-out))
             (and isNew (not= "soldOut" availability)) (conj (ui/badge :new))
             isOwnBrand (conj (ui/badge :own-brand)))]
    (when (seq bs)
      (into [:div.badges] bs))))

(defn product-card
  "ProductCard. Argument: product summary {:slug :name :brand :price
  :availability :image :isNew :isOwnBrand}. The whole card is one <a> (client
  navigation through [:nav/link]); sold-out products get the is-sold-out class
  and a visually hidden \"(sold out)\". Returns hiccup."
  [{:keys [slug name brand price availability image] :as p}]
  (let [sold-out? (= "soldOut" availability)]
    [:article {:class (ui/classes "product-card" (when sold-out? "is-sold-out"))}
     (ui/link (routes/path-for :product {:slug slug}) {:class "product-card__link"}
              [:div.product-card__media
               (ui/picture image {:fallback-label name})
               (badges p)]
              [:div.product-card__body
               [:h3.product-card__name name]
               [:p.product-card__brand brand]
               [:p.product-card__price (ui/price price)
                (when sold-out? (ui/visually-hidden " (sold out)"))]])]))

(defn product-grid
  "ProductGrid: a list of ProductCards. `items` is a seq of product summaries
  from GET /api/products. Returns hiccup."
  [items]
  [:ul.product-grid
   (for [p items]
     ^{:key (:id p)} [:li (product-card p)])])

(defn product-row
  "Same cards as product-grid, in a list that scrolls sideways on phones and is
  a grid on desktop (home sections). Returns hiccup."
  [items]
  [:ul.product-grid.product-grid--row
   (for [p items]
     ^{:key (:id p)} [:li (product-card p)])])

(defn category-tile
  "CategoryTile for the home page. Argument: category map {:slug :name
  :productCount} from GET /api/categories. Links to the category page. Returns
  hiccup."
  [{:keys [slug name productCount]}]
  [:li.category-tile
   (ui/link (routes/path-for :category {:slug slug}) {:class "category-tile__link"}
            (ui/icon (keyword (str "category-" slug)) :lg)
            [:h3.category-tile__name name]
            [:p.category-tile__count (str productCount (if (= 1 productCount) " product" " products"))])])

(defn gallery
  "Gallery. Arguments: product map {:images [...] :name ...} and `selected`
  (index from (:gallery :ui), clamped to the available images). The main image
  is eager-loaded (first screen); thumbnails dispatch [:gallery/select i].
  With no images a placeholder is shown. Returns hiccup."
  [{:keys [images name] :as p} selected]
  (let [selected (min (or selected 0) (max 0 (dec (count images))))
        main (nth images selected nil)]
    [:div.gallery
     [:div.gallery__main
      (ui/picture main {:fallback-label name :eager? true :sizes "(min-width: 48em) 50vw, 100vw"})
      (badges p)]
     (when (> (count images) 1)
       [:ul.gallery__thumbs {:aria-label "Product photos"}
        (map-indexed
         (fn [i img]
           ^{:key (:id img)}
           [:li
            [:button.gallery__thumb {:type "button"
                                     :aria-label (str "Show photo " (inc i) " of " (count images))
                                     :aria-current (when (= i selected) "true")
                                     :on {:click [[:gallery/select i]]}}
             (ui/picture img {:sizes "20vw"})]])
         images)])]))

(defn quantity-stepper
  "QuantityStepper. Argument map: {:id :label (product name, for accessible
  names) :value :max-q :on-dec :on-inc (action vectors) :on-set (action
  vector; the typed value is appended)}. The minus button is disabled at 1 and
  the plus button at `max-q`. Returns hiccup."
  [{:keys [id label value max-q on-dec on-inc on-set]}]
  [:div.stepper {:role "group" :aria-label label}
   [:button.stepper__button {:type "button" :aria-label (str "Decrease quantity of " label)
                             :disabled (<= value 1)
                             :on {:click [on-dec]}}
    (ui/icon :minus)]
   [:input.stepper__value {:id id :type "number" :inputmode "numeric" :min 1 :max max-q
                           :value value :aria-label (str "Quantity of " label)
                           :on {:change [(conj on-set [:event/target.value])]}}]
   [:button.stepper__button {:type "button" :aria-label (str "Increase quantity of " label)
                             :disabled (>= value max-q)
                             :on {:click [on-inc]}}
    (ui/icon :plus)]])

(defn buy-box
  "Buy box on P4. Arguments: product map (detail response: :name :brand :price
  :availability :maxQuantity ...) and options {:qty (chosen quantity)
  :stock-note (message after a stock re-check) :added? (show the \"Added\"
  notice) :site}. States: available, sold out (disabled button and a WhatsApp
  question link), limited stock hint when maxQuantity is under 10, and
  just-added. Add to cart dispatches [:cart/add product-subset qty]. Returns
  hiccup."
  [{:keys [name brand price availability maxQuantity] :as p} {:keys [qty stock-note added? site]}]
  (let [sold-out? (or (= "soldOut" availability) (<= (or maxQuantity 0) 0))
        qty (min (max 1 (or qty 1)) (max 1 (or maxQuantity 1)))]
    [:div.buy-box
     [:p.buy-box__brand brand]
     [:p.buy-box__price (ui/price price)]
     [:p {:class (ui/classes "buy-box__availability" (if sold-out? "is-sold-out" "is-available"))}
      (ui/icon (if sold-out? :close :check) :sm)
      [:span (if sold-out? "Sold out" "Available")]]
     (when stock-note (ui/notice {:kind :warning :live? true} stock-note))
     (if sold-out?
       [:div.buy-box__sold-out
        (ui/button {:disabled? true :full? true} "Sold out")
        (ui/whatsapp-button site (str "Hello, I would like to ask about " name ".") "Ask about this product")]
       [:div.buy-box__form
        (when (< maxQuantity 10)
          [:p.buy-box__limit (str "Up to " maxQuantity " per order")])
        (quantity-stepper {:id "pdp-qty" :label name :value qty :max-q maxQuantity
                           :on-dec [:product/set-qty (dec qty)]
                           :on-inc [:product/set-qty (inc qty)]
                           :on-set [:product/set-qty-input]})
        (ui/button {:full? true
                    :on-click [[:cart/add (select-keys p [:id :slug :name :price :image :maxQuantity]) qty]]}
                   "Add to cart")])
     (when added?
       (ui/notice {:kind :success :live? true :id "added-notice"}
                  [:p "Added to your cart."]
                  [:p.notice__actions
                   (ui/link "/cart" {:class "text-link"} "View cart")
                   (ui/link "/shop" {:class "text-link"} "Keep shopping")]))
     [:ul.reassurance
      [:li (ui/icon :delivery) [:span "Delivery fee agreed with you before dispatch"]]
      [:li (ui/icon :pay-on-delivery) [:span "Pay on delivery: cash or mobile money"]]
      (when (:whatsapp site)
        [:li (ui/icon :whatsapp)
         (ui/external-link (ui/whatsapp-href (:whatsapp site) (str "Hello, a question about " name))
                           {:rel "noopener"} "Questions? Chat on WhatsApp")])]]))

(defn details-accordion
  "Accordion with the Description, Size, How to use and Ingredients sections
  that the product has; the first one is open. Argument: product map
  {:description :size :howToUse :ingredients}. Returns hiccup, or nil when it
  has none of them."
  [{:keys [description size howToUse ingredients]}]
  (let [sections (cond-> []
                   (seq description) (conj ["Description" (into [:div] (for [para (fmt/paragraphs description)] [:p para]))])
                   size (conj ["Size" [:p size]])
                   howToUse (conj ["How to use" [:p howToUse]])
                   ingredients (conj ["Ingredients" [:p ingredients]]))]
    (when (seq sections)
      [:div.accordion
       (map-indexed
        (fn [i [title body]]
          ^{:key title}
          [:details.accordion__item {:open (zero? i)}
           [:summary.accordion__summary [:span title] (ui/icon :chevron-down)]
           [:div.accordion__body body]])
        sections)])))

(def sort-options
  "The sort choices offered on listings as [api-value label] pairs; they match
  the `sort` values accepted by pages/normalise-query."
  [["newest" "Newest"] ["priceAsc" "Price: low to high"] ["priceDesc" "Price: high to low"] ["nameAsc" "Name"]])

(defn filter-bar
  "Category chips (links), in-stock toggle and sort select. Argument map:
  {:base-path (/shop or the category path) :query (the UI query {:q
  :availability :sort}) :current-slug (selected category, nil for All)}. The
  chips and the GET form work without JavaScript; with it, submit dispatches
  [:filters/submit base-path form-data] and the controls submit the form on
  change. Returns hiccup."
  [{:keys [base-path query current-slug]}]
  [:div.filter-bar
   [:ul.chips {:aria-label "Categories"}
    [:li (ui/link "/shop" {:class (ui/classes "chip" (when (nil? current-slug) "is-selected"))
                           :aria-current (when (nil? current-slug) "page")} "All")]
    (for [[slug label] ui/nav-categories]
      ^{:key slug}
      [:li (ui/link (routes/path-for :category {:slug slug})
                    {:class (ui/classes "chip" (when (= slug current-slug) "is-selected"))
                     :aria-current (when (= slug current-slug) "page")}
                    label)])]
   [:form.filter-form {:action base-path :method "get"
                       :on {:submit [[:dom/prevent-default] [:filters/submit base-path [:event/form-data]]]}}
    (when (:q query) [:input {:type "hidden" :name "q" :value (:q query)}])
    [:label.check__row.filter-form__stock {:for "filter-stock"}
     [:input.check__input {:id "filter-stock" :type "checkbox" :name "availability" :value "available"
                           :checked (= "available" (:availability query))
                           :on {:change [[:filters/submit-form]]}}]
     [:span.check__label "In stock only"]]
    [:div.filter-form__sort
     [:label.field__label {:for "filter-sort"} "Sort by"]
     [:select.input.input--select {:id "filter-sort" :name "sort" :value (or (:sort query) "newest")
                                   :on {:change [[:filters/submit-form]]}}
      (for [[v l] sort-options]
        [:option {:value v :selected (= v (or (:sort query) "newest"))} l])]]
    [:noscript [:button.button.button--secondary {:type "submit"} [:span.button__label "Apply"]]]
    [:button.button.button--secondary.filter-form__apply {:type "submit"} [:span.button__label "Apply"]]]])

(defn load-more
  "Load more link. Argument map: {:base-path :query :page :pageSize :totalItems
  :loading?}. Returns nil when everything is shown; otherwise a link to the
  next page (\"?page=n+1\") that the click handler turns into
  [:products/load-more] (in-place load). Without JavaScript it is ordinary
  pagination."
  [{:keys [base-path query page pageSize totalItems loading?]}]
  (let [shown (* page pageSize)]
    (when (< shown totalItems)
      [:div.load-more
       (ui/link (routes/with-query base-path (assoc query :page (inc page)))
                {:class ["button" "button--secondary"]
                 :on {:click [[:dom/prevent-default] [:products/load-more]]}
                 :rel "next"}
                [:span.button__label (if loading? "Loading" "Load more")])])))

(defn product-listing
  "Product grid plus Load more, or the \"Nothing matched\" empty state with
  category chips and a WhatsApp link. Arguments: `data` (GET /api/products
  response {:items :page :pageSize :totalItems}) and `opts` ({:base-path
  :query :site :loading?}). Returns hiccup."
  [{:keys [items] :as data} opts]
  (if (seq items)
    [:div
     (product-grid items)
     (load-more (merge opts (select-keys data [:page :pageSize :totalItems])))]
    (ui/empty-state {:title "Nothing matched"
                     :text "Try another word or browse a category."}
                    (into [:ul.chips]
                          (for [[slug label] [["skincare" "Skincare"] ["perfumes" "Perfumes"] ["body-care" "Body care"]]]
                            [:li (ui/link (routes/path-for :category {:slug slug}) {:class "chip"} label)]))
                    (when-let [wa (:whatsapp (:site opts))]
                      [:p (ui/external-link (ui/whatsapp-href wa "Hello, I am looking for a product.") {:class "text-link" :rel "noopener"}
                                            "Ask us on WhatsApp for a product")]))))
