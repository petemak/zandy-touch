(ns zandy.shared.ui.core
  "Basic building blocks: links, buttons, icons, badges, notices, images and
  form fields. Pure functions returning hiccup. Event handlers are data: vectors
  of actions dispatched to the single state atom (see zandy.frontend.state)."
  (:require [clojure.string :as str]
            [zandy.shared.format :as fmt]))

(def nav-categories
  "The six categories are fixed by migration (api-contract.md section 2)."
  [["skincare" "Skincare"]
   ["perfumes" "Perfumes"]
   ["body-shaping" "Body-shaping"]
   ["body-care" "Body care"]
   ["weight-loss" "Weight loss"]
   ["health-and-wellness" "Health & Wellness"]])

(defn classes [& cs]
  (vec (remove str/blank? (map #(when % (if (keyword? %) (name %) (str %))) cs))))

(defn icon
  "Icon from assets/icons rendered as a CSS mask so it takes the text colour.
  `size` is nil, :sm or :lg."
  ([icon-name] (icon icon-name nil))
  ([icon-name size]
   [:span {:class (classes "icon" (str "icon-" (name icon-name)) (when size (str "icon--" (name size))))
           :aria-hidden "true"}]))

(defn link
  "Internal link. Handled by the client router when JavaScript is available,
  a normal link otherwise."
  [path attrs & children]
  (into [:a (merge {:href path :on {:click [[:nav/link path]]}} attrs)] children))

(defn external-link
  [href attrs & children]
  (into [:a (merge {:href href} attrs)] children))

(defn button
  "variant: :primary (default), :secondary, :on-dark (primary on black),
  :secondary-on-dark, :danger-text. `disabled-reason` is shown as text."
  [{:keys [variant disabled? type on-click class attrs full?]} & children]
  [:button (merge {:type (or type "button")
                   :class (classes "button" (str "button--" (name (or variant :primary)))
                                   (when full? "button--full") class)
                   :disabled (boolean disabled?)
                   :on (when on-click {:click on-click})}
                  attrs)
   (into [:span.button__label] children)])

(defn link-button
  "A link that looks like a button."
  [path {:keys [variant class full?]} & children]
  (apply link path {:class (classes "button" (str "button--" (name (or variant :primary)))
                                    (when full? "button--full") class)}
         children))

(defn badge [kind]
  (case kind
    :new [:span.badge.badge--new "New"]
    :own-brand [:span.badge.badge--own-brand "Zandy Touch"]
    :sold-out [:span.badge.badge--sold-out "Sold out"]
    :featured [:span.badge.badge--featured "Featured"]
    :low-stock [:span.badge.badge--low-stock "Low stock"]))

(defn pill
  "Admin status pill. The word always carries the meaning."
  [tone text]
  [:span {:class (classes "pill" (str "pill--" (name tone)))} text])

(defn order-status-pill [status]
  (pill (case status
          "placed" :info
          "confirmed" :warning
          "approvedForDelivery" :warning
          "delivered" :success
          "cancelled" :error
          :info)
        (fmt/staff-status-label status)))

(defn notice
  "Inline notice. kind: :info :success :warning :error. Announced politely when
  `live?`."
  [{:keys [kind live? id]} & children]
  [:div (cond-> {:class (classes "notice" (str "notice--" (name (or kind :info))))
                 :role (if (= kind :error) "alert" "status")}
          id (assoc :id id)
          live? (assoc :aria-live "polite"))
   (icon (case kind :success :check :warning :warning :error :warning :info))
   (into [:div.notice__body] children)])

(defn draft-notice
  "Shown on policy documents while reviewStatus is placeholder."
  []
  [:div.draft-notice {:role "note"}
   (icon :warning)
   [:span "Draft text, not yet reviewed"]])

(defn price [n]
  [:span.price (fmt/ugx n)])

(defn price-label [n]
  (price n))

(defn- media-url [image variant]
  (get-in image [:urls variant]))

(defn placeholder-image
  "Flat labelled placeholder. Used when a product has no photo."
  [label]
  [:div.placeholder-image {:role "img" :aria-label (str (or label "Product") ": photo coming soon")}
   [:span.placeholder-image__name (or label "")]
   [:span.placeholder-image__text "Photo coming soon"]])

(defn picture
  "Responsive product photo from an API image {:id :alt :urls}. Falls back to a
  labelled placeholder."
  [image {:keys [fallback-label sizes eager?]}]
  (if-let [thumb (media-url image :thumb)]
    [:img.photo (cond-> {:src (or (media-url image :medium) thumb)
                         :srcset (str thumb " 480w, " (media-url image :medium) " 800w, " (media-url image :large) " 1200w")
                         :sizes (or sizes "(min-width: 48em) 25vw, 50vw")
                         :alt (or (:alt image) "")
                         :width 800
                         :height 1000
                         :decoding "async"}
                  (not eager?) (assoc :loading "lazy"))]
    (placeholder-image fallback-label)))

(defn site-photo
  "Temporary site photo from assets/photos (sources in assets/photos/SOURCES.md).
  `eager?` is for the above-the-fold hero."
  ([file alt width height eager?] (site-photo file alt width height eager? nil))
  ([file alt width height eager? extra-class]
  [:img (cond-> {:class (cond-> "site-photo" extra-class (str " " extra-class)) :src (str "/static/assets/photos/" file) :alt alt
                 :width width :height height :decoding "async"}
          (not eager?) (assoc :loading "lazy"))]))

(defn svg-placeholder
  "Neutral brand-palette SVG, obviously a placeholder. Colours come from CSS
  classes that use design tokens."
  [label]
  [:svg {:class "svg-placeholder" :viewBox "0 0 400 500" :role "img"
         :aria-label (str label " (placeholder image)")
         :preserveAspectRatio "xMidYMid slice"}
   [:rect {:class "svg-placeholder__bg" :width "400" :height "500"}]
   [:circle {:class "svg-placeholder__shape" :cx "200" :cy "190" :r "90"}]
   [:path {:class "svg-placeholder__shape" :d "M80 500 C80 360 320 360 320 500 Z"}]
   [:text {:class "svg-placeholder__text" :x "200" :y "460" :text-anchor "middle"} "Placeholder image"]])

(defn empty-state
  [{:keys [title text]} & children]
  (into [:div.empty-state
         [:h2.empty-state__title title]
         (when text [:p text])]
        children))

;; ---------------------------------------------------------------------------
;; Forms

(defn field-error-id [id] (str id "-error"))
(defn field-hint-id [id] (str id "-hint"))

(defn- described-by [id hint error]
  (let [ids (cond-> [] hint (conj (field-hint-id id)) error (conj (field-error-id id)))]
    (when (seq ids) (str/join " " ids))))

(defn field-wrap [{:keys [id label hint error optional?]} control]
  [:div {:class (classes "field" (when error "field--error"))}
   [:label.field__label {:for id}
    label
    (when optional? [:span.field__optional " (optional)"])]
   (when hint [:p.field__hint {:id (field-hint-id id)} hint])
   control
   (when error
     [:p.field__error {:id (field-error-id id)}
      (icon :warning :sm) [:span error]])])

(defn text-field
  "Text-like input. `on-input` is an action vector, e.g.
  [:form/input :checkout :name [:event/target.value]]."
  [{:keys [id name label type value error hint on-input autocomplete inputmode required? maxlength minlength placeholder disabled? optional?]}]
  (field-wrap {:id id :label label :hint hint :error error :optional? optional?}
              [:input.input (cond-> {:id id
                                     :name (or name id)
                                     :type (or type "text")
                                     :value (or value "")
                                     :aria-invalid (when error "true")
                                     :aria-describedby (described-by id hint error)
                                     :on {:input [on-input]}}
                              autocomplete (assoc :autocomplete autocomplete)
                              inputmode (assoc :inputmode inputmode)
                              required? (assoc :required true)
                              maxlength (assoc :maxlength maxlength)
                              minlength (assoc :minlength minlength)
                              placeholder (assoc :placeholder placeholder)
                              disabled? (assoc :disabled true))]))

(defn textarea-input
  [{:keys [id label value error hint on-input rows maxlength optional?]}]
  (field-wrap {:id id :label label :hint hint :error error :optional? optional?}
              [:textarea.input.input--textarea
               (cond-> {:id id :name id :rows (or rows 4)
                        :value (or value "")
                        :aria-invalid (when error "true")
                        :aria-describedby (described-by id hint error)
                        :on {:input [on-input]}}
                 maxlength (assoc :maxlength maxlength))]))

(defn select-input
  "options: seq of [value label]."
  [{:keys [id label value error hint on-change options placeholder optional? disabled?]}]
  (field-wrap {:id id :label label :hint hint :error error :optional? optional?}
              [:select.input.input--select
               (cond-> {:id id :name id :value (or value "")
                        :aria-invalid (when error "true")
                        :aria-describedby (described-by id hint error)
                        :on {:change [on-change]}}
                 disabled? (assoc :disabled true))
               (when placeholder [:option {:value ""} placeholder])
               (for [[v l] options]
                 [:option {:value v :selected (= v value)} l])]))

(defn checkbox
  [{:keys [id label checked? on-change error]}]
  [:div {:class (classes "check" (when error "check--error"))}
   [:label.check__row {:for id}
    [:input.check__input {:id id :name id :type "checkbox" :checked (boolean checked?)
                          :aria-invalid (when error "true")
                          :aria-describedby (when error (field-error-id id))
                          :on {:change [on-change]}}]
    [:span.check__label label]]
   (when error [:p.field__error {:id (field-error-id id)} (icon :warning :sm) [:span error]])])

(defn radio-group
  "options: seq of [value label]."
  [{:keys [name legend value on-change options error hint]}]
  [:fieldset {:class (classes "radio-group" (when error "field--error"))}
   [:legend.field__label legend]
   (when hint [:p.field__hint hint])
   (for [[v l] options]
     (let [id (str name "-" v)]
       ^{:key v}
       [:label.check__row {:for id}
        [:input.check__input {:id id :type "radio" :name name :value v :checked (= v value)
                              :on {:change [(conj on-change v)]}}]
        [:span.check__label l]]))
   (when error [:p.field__error (icon :warning :sm) [:span error]])])

(defn error-summary
  "Summary of field errors at the top of a long form. `errors` is a seq of
  {:id :message}. Links jump to the field."
  [errors]
  (when (seq errors)
    [:div.error-summary {:id "error-summary" :role "alert" :tabindex "-1"}
     [:h2.error-summary__title "Please check the highlighted fields"]
     [:ul
      (for [{:keys [id message]} errors]
        ^{:key id}
        [:li [:a {:href (str "#" id) :on {:click [[:dom/focus-id id]]}} message]])]]))

(defn spinner-text [text]
  [:p.loading {:role "status" :aria-live "polite"} text])

(defn breadcrumb
  "crumbs: seq of [label path-or-nil]. The last one has no path (current page)."
  [crumbs]
  [:nav.breadcrumb {:aria-label "Breadcrumb"}
   [:ol
    (for [[label path] crumbs]
      ^{:key label}
      [:li (if path
             (link path {} label)
             [:span {:aria-current "page"} label])])]])

(defn visually-hidden [& children]
  (into [:span.visually-hidden] children))

(defn whatsapp-href
  "wa.me link with an optional prefilled text."
  [number text]
  (str "https://wa.me/" (fmt/digits number)
       (when text (str "?text=" (-> text
                                    (str/replace "%" "%25")
                                    (str/replace " " "%20")
                                    (str/replace "\n" "%0A")
                                    (str/replace "#" "%23")
                                    (str/replace "&" "%26"))))))

(defn whatsapp-button
  "Hidden when the number is unknown."
  [site text label]
  (when-let [n (:whatsapp site)]
    [:a {:class (classes "button" "button--secondary")
         :href (whatsapp-href n text)
         :rel "noopener"}
     (icon :whatsapp) [:span.button__label label]]))
