(ns zandy.shared.ui.core
  "Basic building blocks shared by every page and by the admin area: links,
  buttons, icons, badges, pills, notices, images and form fields
  (design-spec.md section 3: Button, Link, Badge, FormField, Select,
  RadioGroup, Checkbox, ErrorSummary, EmptyState and so on). Pure functions
  returning hiccup, shared code (.cljc) used on the JVM and in the browser.
  Event handlers are data: vectors of actions handled by zandy.frontend.state."
  (:require [clojure.string :as str]
            [zandy.shared.format :as fmt]))

(def nav-categories
  "The six fixed categories as [slug label] pairs, in menu order (see
  api-contract.md section 2). Used by the header nav, the filter chips and the
  not-found page."
  [["skincare" "Skincare"]
   ["perfumes" "Perfumes"]
   ["body-shaping" "Body-shaping"]
   ["body-care" "Body care"]
   ["weight-loss" "Weight loss"]
   ["health-and-wellness" "Health & Wellness"]])

(defn classes
  "Builds a class vector from strings, keywords and nils: keywords become their
  name, blank and nil entries are dropped. Returns a vector of strings
  (Replicant accepts it as :class)."
  [& cs]
  (vec (remove str/blank? (map #(when % (if (keyword? %) (name %) (str %))) cs))))

(defn icon
  "Icon from assets/icons rendered as a CSS mask, so it takes the text colour.
  Arguments: `icon-name` (keyword that matches the icon CSS class) and
  optional `size` (nil, :sm or :lg). Hidden from assistive technology. Returns
  hiccup."
  ([icon-name] (icon icon-name nil))
  ([icon-name size]
   [:span {:class (classes "icon" (str "icon-" (name icon-name)) (when size (str "icon--" (name size))))
           :aria-hidden "true"}]))

(defn link
  "Internal link. Arguments: `path`, `attrs` (merged over the defaults, so a
  caller can replace :on) and children. The click dispatches [:nav/link path],
  which the browser handles with client-side navigation; without JavaScript
  (or with a modified click) it is an ordinary link. Returns hiccup."
  [path attrs & children]
  (into [:a (merge {:href path :on {:click [[:nav/link path]]}} attrs)] children))

(defn external-link
  "Plain <a href> for off-site or non-routed targets (tel:, mailto:, wa.me).
  Arguments: `href`, `attrs` (merged into the element) and children. No click
  action, so the browser follows it normally. Returns hiccup."
  [href attrs & children]
  (into [:a (merge {:href href} attrs)] children))

;; Why :disabled is always a boolean: Replicant sets the attribute from the
;; value, so nil and false must both remove it.
(defn button
  "Button component. Argument map: {:variant :primary (default), :secondary,
  :on-dark, :secondary-on-dark or :danger-text; :disabled? :type (default
  \"button\") :on-click (vector of actions) :class :attrs (merged into the
  element) :full?}, then children. Returns hiccup."
  [{:keys [variant disabled? type on-click class attrs full?]} & children]
  [:button (merge {:type (or type "button")
                   :class (classes "button" (str "button--" (name (or variant :primary)))
                                   (when full? "button--full") class)
                   :disabled (boolean disabled?)
                   :on (when on-click {:click on-click})}
                  attrs)
   (into [:span.button__label] children)])

(defn link-button
  "A link that looks like a button. Arguments: `path`, map {:variant :class
  :full? :attrs} and children. Returns hiccup."
  [path {:keys [variant class full? attrs]} & children]
  (apply link path (merge {:class (classes "button" (str "button--" (name (or variant :primary)))
                                           (when full? "button--full") class)}
                          attrs)
         children))

(defn badge
  "Badge component. `kind` is :new, :own-brand, :sold-out, :featured or
  :low-stock. Returns hiccup; an unknown kind throws (no matching case)."
  [kind]
  (case kind
    :new [:span.badge.badge--new "New"]
    :own-brand [:span.badge.badge--own-brand "Zandy Touch"]
    :sold-out [:span.badge.badge--sold-out "Sold out"]
    :featured [:span.badge.badge--featured "Featured"]
    :low-stock [:span.badge.badge--low-stock "Low stock"]))

(defn pill
  "Admin StatusPill. `tone` is :info, :warning, :success or :error; `text` is
  the visible word, which always carries the meaning. Returns hiccup."
  [tone text]
  [:span {:class (classes "pill" (str "pill--" (name tone)))} text])

(defn order-status-pill
  "Admin StatusPill for an order status code (\"placed\", \"confirmed\",
  \"approvedForDelivery\", \"delivered\", \"cancelled\"); the colour follows the
  status and the word is the staff label from fmt/staff-status-label. Returns
  hiccup."
  [status]
  (pill (case status
          "placed" :info
          "confirmed" :warning
          "approvedForDelivery" :warning
          "delivered" :success
          "cancelled" :error
          :info)
        (fmt/staff-status-label status)))

(defn notice
  "Inline notice (Toast / inline notice). Argument map: {:kind :info (default),
  :success, :warning or :error; :live? announce politely; :id}, then children.
  Errors use role alert, others role status. Returns hiccup."
  [{:keys [kind live? id]} & children]
  [:div (cond-> {:class (classes "notice" (str "notice--" (name (or kind :info))))
                 :role (if (= kind :error) "alert" "status")}
          id (assoc :id id)
          live? (assoc :aria-live "polite"))
   (icon (case kind :success :check :warning :warning :error :warning :info))
   (into [:div.notice__body] children)])

(defn draft-notice
  "Marker shown on a policy document while its reviewStatus is \"placeholder\"
  (draft text not yet reviewed). Returns hiccup."
  []
  [:div.draft-notice {:role "note"}
   (icon :warning)
   [:span "Draft text, not yet reviewed"]])

(defn price
  "PriceLabel: `n` (whole UGX, or nil) formatted as \"UGX 85,000\" in a span.
  Returns hiccup."
  [n]
  [:span.price (fmt/ugx n)])

(defn price-label
  "Same as `price`; kept as a second name for call sites that read better with
  it."
  [n]
  (price n))

;; Image variants come from the API as {:urls {:thumb :medium :large}}
;; (api-contract.md); srcset below uses all three widths.
(defn- media-url [image variant]
  (get-in image [:urls variant]))

(defn placeholder-image
  "Flat labelled placeholder used when a product has no photo. `label` is the
  product name. Returns hiccup with role img and an accessible label."
  [label]
  [:div.placeholder-image {:role "img" :aria-label (str (or label "Product") ": photo coming soon")}
   [:span.placeholder-image__name (or label "")]
   [:span.placeholder-image__text "Photo coming soon"]])

(defn picture
  "Responsive product photo. Arguments: `image` (API image {:id :alt :urls
  {:thumb :medium :large}} or nil) and options {:fallback-label :sizes
  :eager?}. Only the first screen of images should be :eager?; the rest load
  lazily. Without a thumb URL it returns placeholder-image. Returns hiccup."
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
  "Temporary site photo from assets/photos. Arguments: `file`, `alt`, `width`,
  `height`, `eager?` (above the fold only) and an optional extra class.
  Returns hiccup."
  ([file alt width height eager?] (site-photo file alt width height eager? nil))
  ([file alt width height eager? extra-class]
  [:img (cond-> {:class (classes "site-photo" extra-class) :src (str "/static/assets/photos/" file) :alt alt
                 :width width :height height :decoding "async"}
          (not eager?) (assoc :loading "lazy"))]))

(defn svg-placeholder
  "Neutral brand-palette SVG placeholder for a missing image; `label` goes into
  the accessible name. Returns hiccup."
  [label]
  [:svg {:class "svg-placeholder" :viewBox "0 0 400 500" :role "img"
         :aria-label (str label " (placeholder image)")
         :preserveAspectRatio "xMidYMid slice"}
   [:rect {:class "svg-placeholder__bg" :width "400" :height "500"}]
   [:circle {:class "svg-placeholder__shape" :cx "200" :cy "190" :r "90"}]
   [:path {:class "svg-placeholder__shape" :d "M80 500 C80 360 320 360 320 500 Z"}]
   [:text {:class "svg-placeholder__text" :x "200" :y "460" :text-anchor "middle"} "Placeholder image"]])

(defn empty-state
  "EmptyState component. Arguments: map {:title :text} (text optional) and
  children such as a call-to-action link. Returns hiccup with an h2."
  [{:keys [title text]} & children]
  (into [:div.empty-state
         [:h2.empty-state__title title]
         (when text [:p text])]
        children))

;; ---------------------------------------------------------------------------
;; Forms

(defn field-error-id
  "DOM id of the error message paragraph for the field `id` (used by
  aria-describedby)."
  [id] (str id "-error"))
(defn field-hint-id
  "DOM id of the hint paragraph for the field `id` (used by aria-describedby)."
  [id] (str id "-hint"))

(defn- described-by
  "Value for aria-describedby: the ids of the hint and error paragraphs that
  exist, space separated, or nil when there are none."
  [id hint error]
  (let [ids (cond-> [] hint (conj (field-hint-id id)) error (conj (field-error-id id)))]
    (when (seq ids) (str/join " " ids))))

(defn field-wrap
  "FormField wrapper: label (with \"(optional)\" marker), hint, the given
  `control` hiccup and the error message with icon. Argument map: {:id :label
  :hint :error :optional?}. The error state adds the field--error class.
  Returns hiccup."
  [{:keys [id label hint error optional?]} control]
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
  "Text-like input (FormField). Argument map: {:id :name :label :type :value
  :error :hint :on-input (action vector with an [:event/target.value]
  placeholder) :autocomplete :inputmode :required? :maxlength :minlength
  :placeholder :disabled? :optional?}. Returns hiccup."
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
  "Multi-line field. Argument map: {:id :label :value :error :hint :on-input
  (action vector) :rows (default 4) :maxlength :optional?}. Input events
  dispatch `on-input`, whose [:event/target.value] placeholder main/enrich
  fills in. Returns hiccup."
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
  "Select (FormField). Argument map: {:id :label :value :error :hint :on-change
  (action vector) :options (seq of [value label]) :placeholder (adds an empty
  first option) :optional? :disabled?}. Returns hiccup."
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
  "Checkbox component. Argument map: {:id :label :checked? :on-change (action
  vector) :error}; the new checked state is expected as the final argument via
  an [:event/target.checked] placeholder in `on-change`. Returns hiccup."
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
  "RadioGroup in a fieldset. Argument map: {:name :legend :value :on-change
  (action vector; the chosen value is appended) :options (seq of [value
  label]) :error :hint}. Returns hiccup."
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
  "ErrorSummary at the top of a long form. `errors` is a seq of {:id :message};
  nil or empty returns nil. Each link focuses its field by dispatching
  [:dom/focus-id id]. The summary has tabindex -1 so the state handlers can
  focus it after a failed submit. Returns hiccup."
  [errors]
  (when (seq errors)
    [:div.error-summary {:id "error-summary" :role "alert" :tabindex "-1"}
     [:h2.error-summary__title "Please check the highlighted fields"]
     [:ul
      (for [{:keys [id message]} errors]
        ^{:key id}
        [:li [:a {:href (str "#" id) :on {:click [[:dom/focus-id id]]}} message]])]]))

(defn spinner-text
  "Polite status line (role status, aria-live) with the loading `text`. Returns
  hiccup."
  [text]
  [:p.loading {:role "status" :aria-live "polite"} text])

(defn breadcrumb
  "Breadcrumb. `crumbs` is a seq of [label path-or-nil]; the last entry has no
  path and is marked as the current page. Returns hiccup."
  [crumbs]
  [:nav.breadcrumb {:aria-label "Breadcrumb"}
   [:ol
    (for [[label path] crumbs]
      ^{:key label}
      [:li (if path
             (link path {} label)
             [:span {:aria-current "page"} label])])]])

(defn visually-hidden
  "Wraps children in a span that is hidden visually but still read by screen
  readers. Returns hiccup."
  [& children]
  (into [:span.visually-hidden] children))

(defn whatsapp-href
  "wa.me link for `number` (any format; digits are kept) with optional
  prefilled `text`. Returns a URL string."
  [number text]
  (str "https://wa.me/" (fmt/digits number)
       (when text (str "?text=" (-> text
                                    (str/replace "%" "%25")
                                    (str/replace " " "%20")
                                    (str/replace "\n" "%0A")
                                    (str/replace "#" "%23")
                                    (str/replace "&" "%26"))))))

(defn whatsapp-button
  "WhatsAppButton. Arguments: `site` (site-info; uses :whatsapp), prefilled
  `text` and button `label`. Returns nil when the shop has no WhatsApp number,
  otherwise hiccup."
  [site text label]
  (when-let [n (:whatsapp site)]
    [:a {:class (classes "button" "button--secondary")
         :href (whatsapp-href n text)
         :rel "noopener"}
     (icon :whatsapp) [:span.button__label label]]))
