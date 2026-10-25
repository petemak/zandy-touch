(ns zandy.frontend.admin.widgets
  "Staff-area components for pictures and bulk import: UploadDropzone,
  ImportResultList and ProductImageManager (design-spec.md section 3). Pure
  functions from data to hiccup; every handler is an action vector. File
  objects never appear here, only their names and sizes."
  (:require [clojure.string :as str]
            [zandy.shared.format :as fmt]
            [zandy.shared.routes :as routes]
            [zandy.shared.ui.core :as ui]))

;; ---------------------------------------------------------------------------
;; Wording for codes the API returns (api-contract.md 1.4, 8.2)

(def field-labels
  {"sku" "SKU" "name" "Name" "brand" "Brand" "ownBrand" "Own brand" "category" "Category" "price" "Price"
   "shortDescription" "Short description" "description" "Description" "size" "Size" "howToUse" "How to use"
   "ingredients" "Ingredients" "featured" "Featured" "openingStock" "Opening stock"})

(def code-text
  {"ignoredForExisting" "Ignored because this product already exists. Change stock with Adjust stock."
   "duplicateSku" "This SKU is already used by an earlier row in the file."
   "required" "This value is required."
   "invalid" "This value is not valid."
   "unknownCategory" "No category has this name. Use one of the six categories."
   "extraCell" "This row has more cells than the header line."
   "notFound" "was not found"
   "unknownSku" "No product has a SKU at the start of this file name."
   "tooManyImages" "This product already has 6 pictures."
   "fileTooLarge" "This file is larger than 5 MB."
   "unsupportedMediaType" "Only JPEG, PNG or WebP pictures can be used."
   "dimensionsTooLarge" "This picture is more than 6000 pixels on a side."
   "unreadableImage" "This file could not be read as a picture."
   "uploadFailed" "The upload did not reach the server. Try again."
   "priceRequired" "needs a price"
   "imageRequired" "needs a picture"})

(defn- message-text
  "Server message when there is one, otherwise our wording for the code."
  [field {:keys [code message]}]
  (let [text (or (not-empty message) (get code-text code) code)
        label (get field-labels field)]
    (if label (str label ": " text) text)))

;; ---------------------------------------------------------------------------
;; Result items: one shape for CSV rows and picture files

(defn csv-row-items
  "Rows of a product-imports report -> result items."
  [rows dry-run?]
  (vec (for [{:keys [line sku status warnings errors]} rows]
         {:id (str "row-" line)
          :title (if (str/blank? sku) "No SKU" sku)
          :detail (str "Row " line)
          :tone (case status "created" :success "updated" :info :error)
          :status-label (case status
                          "created" (if dry-run? "Would be created" "Created")
                          "updated" (if dry-run? "Would be updated" "Updated")
                          "Rejected")
          :messages (vec (concat (for [e errors] {:kind :error :text (message-text (:field e) e)})
                                 (for [w warnings] {:kind :warning :text (message-text (:field w) w)})))})))

(defn picture-items
  "Per-file upload results -> result items."
  [results]
  (vec (for [[i {:keys [filename status sku position error]}] (map-indexed vector results)]
         (if (= "attached" status)
           {:id (str "file-" i) :title filename
            :detail (str "SKU " sku ", picture " position)
            :tone :success :status-label "Attached" :messages []}
           {:id (str "file-" i) :title filename :tone :error :status-label "Not attached"
            :messages [{:kind :error :text (message-text nil error)}]}))))

(defn summary-text
  "\"3 rows: 1 created, 1 updated, 1 rejected\" for an import report summary."
  [{:keys [rows created updated rejected]} dry-run?]
  (str rows (if (= 1 rows) " row" " rows") ": "
       created (if dry-run? " to create, " " created, ")
       updated (if dry-run? " to update, " " updated, ")
       rejected " rejected"))

(defn- result-item [{:keys [title detail tone status-label messages]}]
  [:li {:class (ui/classes "result" (str "result--" (name tone)))}
   [:div.result__head
    (ui/pill tone status-label)
    [:strong.result__title title]
    (when detail [:span.result__detail detail])]
   (when (seq messages)
     [:ul.result__messages
      (for [[i {:keys [kind text]}] (map-indexed vector messages)]
        ^{:key i}
        [:li {:class (ui/classes "result__message" (str "result__message--" (name kind)))}
         (ui/icon (if (= :error kind) :warning :info) :sm)
         [:span text]])])])

(defn import-result-list
  "ImportResultList: one line per CSV row or picture file with the outcome and
  the reason per field. `items` come from csv-row-items or picture-items.
  Announced politely because it appears after an action."
  [{:keys [heading summary items]}]
  [:div.import-results {:aria-live "polite"}
   (when heading [:h3.import-results__title heading])
   (when summary [:p.import-results__summary summary])
   (if (empty? items)
     [:p.field__hint "Nothing to show."]
     [:ul.result-list
      (for [it items] ^{:key (:id it)} (result-item it))])])

;; ---------------------------------------------------------------------------
;; UploadDropzone

(defn upload-dropzone
  "File field that also accepts dropped files. `on-files` is an action prefix,
  for example [:admin/pictures-picked]; the picked files are appended to it
  as [:event/files]. While `busy?` the field is disabled and drops are ignored."
  [{:keys [id label hint accept multiple? busy? on-files]}]
  (let [picked (conj on-files [:event/files])]
    [:div {:class (ui/classes "dropzone" (when busy? "dropzone--busy"))
           :on {:dragover [[:dom/prevent-default]]
                :drop (cond-> [[:dom/prevent-default]] (not busy?) (conj picked))}}
     (ui/icon :upload :lg)
     [:label.field__label {:for id} label]
     (when hint [:p.field__hint {:id (ui/field-hint-id id)} hint])
     [:input.dropzone__input (cond-> {:id id :type "file" :disabled (boolean busy?)
                                      :accept accept
                                      :on {:change [picked]}}
                               multiple? (assoc :multiple true)
                               hint (assoc :aria-describedby (ui/field-hint-id id)))]
     [:p.dropzone__drop-hint {:aria-hidden "true"} "You can also drop files here."]]))

(defn progress-bar
  "Native <progress> with a text label (never colour or width alone)."
  [{:keys [id label value max]}]
  [:div.progress
   [:label.progress__label {:for id} label]
   [:progress {:id id :value value :max max} (str value " of " max)]])

;; ---------------------------------------------------------------------------
;; ProductImageManager

(def max-images 6)

(defn- same-picture [im] [(:id im) (or (not-empty (:alt im)) "")])

(defn images-dirty?
  "True when the pictures on the form differ from the saved ones (order, alt
  text or removal)."
  [images saved-images]
  (not= (mapv same-picture images) (mapv same-picture saved-images)))

(defn removed-count
  "How many saved pictures are no longer on the form (deleted on save)."
  [images saved-images]
  (let [keep-ids (set (map :id images))]
    (count (remove #(keep-ids (:id %)) saved-images))))

(defn product-image-manager
  "ProductImageManager: the product's own pictures. Upload straight onto the
  product, reorder, write alt text, remove. Order, alt text and removal are
  sent with the product (PATCH `images`); \"Save pictures\" sends only those.
  upload: {:status :uploading|nil :items [per-file results]}
  save: {:status :saving|:done|nil :error msg}"
  [{:keys [images saved-images upload save error]}]
  (let [n (count images)
        full? (>= n max-images)
        uploading? (= :uploading (:status upload))
        dirty? (images-dirty? images saved-images)
        gone (removed-count images saved-images)]
    [:div.picture-manager
     [:h2 "Pictures"]
     [:p.field__hint "Up to 6 pictures. The first one is the main picture shown in the shop. Describe each picture in a few words for people who cannot see it."]
     (when error [:p.field__error (ui/icon :warning :sm) [:span error]])
     (if (empty? images)
       (ui/empty-state {:title "No pictures yet" :text "A product needs at least one picture before it can be published."})
       [:ol.picture-list
        (for [[i im] (map-indexed vector images)]
          ^{:key (:id im)}
          [:li.picture
           (if-let [u (get-in im [:urls :thumb])]
             [:img.picture__thumb {:src u :alt "" :width 96 :height 120}]
             (ui/svg-placeholder "Picture"))
           [:div.picture__body
            [:p.picture__position (if (zero? i) "Main picture" (str "Picture " (inc i)))]
            (ui/text-field {:id (str "pim-alt-" (:id im)) :label "Alt text" :optional? true :maxlength 140
                            :value (:alt im) :on-input [:admin/product-image-alt i [:event/target.value]]})
            [:div.row-actions
             (ui/button {:variant :secondary :disabled? (zero? i) :on-click [[:admin/product-image-move i -1]]
                         :attrs {:aria-label (str "Move picture " (inc i) " earlier")}} "Earlier")
             (ui/button {:variant :secondary :disabled? (= i (dec n)) :on-click [[:admin/product-image-move i 1]]
                         :attrs {:aria-label (str "Move picture " (inc i) " later")}} "Later")
             (ui/button {:variant :secondary :on-click [[:admin/product-image-remove i]]
                         :attrs {:aria-label (str "Remove picture " (inc i))}} "Remove")]]])])
     (when (pos? gone)
       (ui/notice {:kind :warning}
                  (str gone (if (= 1 gone) " picture is" " pictures are") " removed and will be deleted when you save.")))
     (if full?
       (ui/notice {:kind :info} "This product has 6 pictures, the most it can have. Remove one to add another.")
       (upload-dropzone {:id "pim-files" :label (if (empty? images) "Add pictures" "Add more pictures")
                         :hint (str "JPEG, PNG or WebP, up to 5 MB each. You can add " (- max-images n) " more.")
                         :accept "image/jpeg,image/png,image/webp" :multiple? true :busy? uploading?
                         :on-files [:admin/product-images-picked]}))
     (when uploading? (ui/spinner-text "Uploading pictures"))
     (when (seq (:items upload))
       (import-result-list {:heading "Last upload" :items (picture-items (:items upload))}))
     (when (= :done (:status save)) (ui/notice {:kind :success :live? true} "Pictures saved."))
     (when (:error save) (ui/notice {:kind :error :live? true} (:error save)))
     [:div.row-actions
      (ui/button {:disabled? (or (not dirty?) (= :saving (:status save))) :on-click [[:admin/product-images-save]]}
                 (if (= :saving (:status save)) "Saving pictures" "Save pictures"))
      (when-not dirty? [:span.field__hint "No unsaved picture changes."])]]))

;; ---------------------------------------------------------------------------
;; StockMovementList (api-contract.md 8.3)

(defn orders-search-path
  "The orders list searched for one order number (the API's `q`)."
  [order-number]
  (routes/with-query "/admin" {:q order-number}))

(defn stock-movement-list
  "Stock history, newest first, in plain words. Codes the list does not know are
  shown as they are. An order number links to the orders list searched by it."
  [movements]
  (if (empty? movements)
    [:p "No movements yet."]
    [:div.table-wrap
     [:table.data-table.movement-list
      [:thead [:tr [:th "When"] [:th "What happened"] [:th.num "Change"] [:th.num "Stock after"] [:th "Reason"] [:th "Who"]]]
      [:tbody
       (for [m movements]
         ^{:key (:id m)}
         [:tr
          [:td {:data-label "When"} (fmt/iso->date-time (:at m))]
          [:td {:data-label "What happened"}
           (fmt/movement-type-label (:type m))
           (when (:orderNumber m)
             [:span " " (ui/link (orders-search-path (:orderNumber m)) {:class "text-link"} (:orderNumber m))])]
          [:td.num {:data-label "Change"}
           [:span {:class (cond (pos? (:delta m)) "stock-in" (neg? (:delta m)) "stock-out")} (fmt/signed-delta (:delta m))]]
          [:td.num {:data-label "Stock after"} (:quantityAfter m)]
          [:td {:data-label "Reason"} (or (fmt/stock-reason-label (:reason m)) "")]
          [:td {:data-label "Who"} (or (get-in m [:by :name]) (if (= "order" (:type m)) "Customer order" "System"))]])]]]))
