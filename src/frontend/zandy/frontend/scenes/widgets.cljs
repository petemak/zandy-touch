(ns zandy.frontend.scenes.widgets
  "Portfolio scenes for the staff-area components UploadDropzone,
  ImportResultList and ProductImageManager, one per meaningful state."
  (:require [portfolio.replicant :refer-macros [defscene]]
            [zandy.frontend.admin.widgets :as w]
            [zandy.frontend.scenes.fixtures :as fx]))

(defn- card [& children] (into [:div.admin-card {:style {:max-width "48rem"}}] children))

;; UploadDropzone ---------------------------------------------------------------
(def ^:private dz {:id "dz" :label "Choose the product pictures" :hint "JPEG, PNG or WebP, up to 5 MB each." :accept "image/jpeg,image/png,image/webp"
                   :multiple? true :on-files [:admin/pictures-picked]})
(defscene dropzone-empty :title "UploadDropzone / empty" (card (w/upload-dropzone dz)))
(defscene dropzone-busy :title "UploadDropzone / uploading (disabled)" (card (w/upload-dropzone (assoc dz :busy? true))))
(defscene progress-uploading :title "UploadDropzone / progress bar" (card (w/progress-bar {:id "pg" :label "Uploading batch 3 of 5 (40 of 87 pictures sent)" :value 2 :max 5})))

;; ImportResultList -------------------------------------------------------------
(def ^:private rows
  [{:line 2 :sku "ZT-SKIN-001" :status "created" :warnings [] :errors []}
   {:line 3 :sku "ZT-PERF-002" :status "updated" :warnings [{:field "openingStock" :code "ignoredForExisting"}] :errors []}
   {:line 4 :sku "ZT-BODY-003" :status "rejected" :warnings []
    :errors [{:field "price" :code "invalid" :message "Price must be a whole number of shillings."}
             {:field "name" :code "required" :message "A new product needs a name."}]}
   {:line 5 :sku "" :status "rejected" :warnings [] :errors [{:field "sku" :code "required"}]}
   {:line 6 :sku "ZT-SKIN-001" :status "rejected" :warnings [] :errors [{:field "sku" :code "duplicateSku"}]}])
(def ^:private summary {:rows 5 :created 1 :updated 1 :rejected 3})
(defscene results-empty :title "ImportResultList / empty" (card (w/import-result-list {:heading "Check result" :items []})))
(defscene results-csv-dry :title "ImportResultList / CSV check with errors and warnings"
  (card (w/import-result-list {:heading "Check result: nothing has been changed yet" :summary (w/summary-text summary true) :items (w/csv-row-items rows true)})))
(defscene results-csv-done :title "ImportResultList / CSV import done"
  (card (w/import-result-list {:heading "Import result" :summary (w/summary-text summary false) :items (w/csv-row-items rows false)})))
(defscene results-csv-all-ok :title "ImportResultList / CSV, every row applied"
  (card (w/import-result-list {:heading "Import result" :summary (w/summary-text {:rows 2 :created 2 :updated 0 :rejected 0} false) :items (w/csv-row-items (subvec rows 0 1) false)})))
(defscene results-pictures :title "ImportResultList / pictures, attached and rejected"
  (card (w/import-result-list {:heading "Results per picture"
                               :items (w/picture-items [{:filename "ZT-SKIN-001-1.jpg" :status "attached" :sku "ZT-SKIN-001" :position 1}
                                                        {:filename "holiday.jpg" :status "rejected" :error {:code "unknownSku" :message "No product has a SKU at the start of this file name."}}
                                                        {:filename "scan.tiff" :status "rejected" :error {:code "unsupportedMediaType"}}])})))

;; ProductImageManager ---------------------------------------------------------------
(defn- manager [m] (card (w/product-image-manager m)))
(def ^:private three [(fx/img 1) (fx/img 2) (fx/img 3)])
(defscene manager-empty :title "ProductImageManager / empty" (manager {:images [] :saved-images []}))
(defscene manager-empty-error :title "ProductImageManager / empty, publish blocked" (manager {:images [] :saved-images [] :error "A product needs at least one picture before it can be published."}))
(defscene manager-loaded :title "ProductImageManager / three pictures" (manager {:images three :saved-images three}))
(defscene manager-uploading :title "ProductImageManager / uploading" (manager {:images (vec (take 2 three)) :saved-images (vec (take 2 three)) :upload {:status :uploading}}))
(defscene manager-upload-errors :title "ProductImageManager / upload with errors"
  (manager {:images three :saved-images three
            :upload {:items [{:filename "front.jpg" :status "attached" :sku nil :position 3}
                             {:filename "huge.png" :status "rejected" :error {:code "fileTooLarge" :message "This file is larger than 5 MB."}}
                             {:filename "notes.pdf" :status "rejected" :error {:code "unsupportedMediaType"}}
                             {:filename "seventh.jpg" :status "rejected" :error {:code "tooManyImages" :message "A product can have at most 6 pictures."}}]}}))
(defscene manager-changed :title "ProductImageManager / unsaved reorder, alt text and removal"
  (manager {:images [(assoc (second three) :alt "Gold jar, open") (first three)] :saved-images three}))
(defscene manager-saving :title "ProductImageManager / saving" (manager {:images three :saved-images (rest three) :save {:status :saving}}))
(defscene manager-saved :title "ProductImageManager / saved" (manager {:images three :saved-images three :save {:status :done}}))
(defscene manager-save-error :title "ProductImageManager / save failed" (manager {:images three :saved-images (rest three) :save {:error "We could not save the pictures. Please try again."}}))
(defscene manager-full :title "ProductImageManager / six pictures, no more room"
  (let [six (mapv fx/img (range 1 7))] (manager {:images six :saved-images six})))
