(ns zandy.features.photos.imports
  "Imperative shell of the picture uploads and the bulk product import: reads uploads, calls the catalog
  functions, builds the per-file and per-row results."
  (:require [zandy.features.catalog.api :as catalog]
            [zandy.features.photos.csv :as csv]
            [zandy.features.photos.logic :as logic]
            [zandy.platform.errors :as errors]))

(defn uploaded
  "The uploaded parts of a multipart field as a vector of {:filename :tempfile ...}."
  [req field]
  (let [f (get (:multipart req) field)]
    (cond (map? f) [f] (sequential? f) (vec f) :else [])))

;; ---- pictures ------------------------------------------------------------------------------------

(defn- file-result [filename r {:keys [product-id sku position]}]
  (if (= :attached (:status r))
    {:filename filename :status "attached" :product-id (:product-id r) :sku sku :position (:position r)
     :image (:image r) :error nil}
    {:filename filename :status "rejected" :product-id product-id :sku sku :position position
     :image nil :error (:error r)}))

(defn attach-to-product!
  "Appends the uploaded files to one product, in the order sent. Returns the per-file results."
  [ctx user product-id sku files]
  (mapv (fn [{:keys [filename tempfile]}]
          (file-result filename
                       (catalog/attach-file! ctx {:user-eid (:eid user) :filename filename} product-id tempfile)
                       {:product-id product-id :sku sku}))
        files))

(defn attach-by-sku!
  "Matches every file to a product by the SKU at the start of its name and attaches it."
  [ctx user files]
  (let [sku->id (catalog/skus ctx)
        skus (keys sku->id)]
    (mapv (fn [{:keys [filename tempfile]}]
            (let [m (logic/match-file filename skus)]
              (if-let [error (:error m)]
                {:filename filename :status "rejected" :product-id nil :sku nil :position nil :image nil :error error}
                (let [{:keys [sku position]} m
                      product-id (get sku->id sku)
                      r (catalog/attach-file! ctx {:user-eid (:eid user) :filename filename :position position}
                                              product-id tempfile)]
                  (file-result filename r {:product-id product-id :sku sku :position nil})))))
          files)))

;; ---- product import --------------------------------------------------------------------------------

(defn read-csv-upload
  "Reads the single uploaded CSV file and returns the parsed table. Throws the contract's whole-file errors."
  [files]
  (when (empty? files)
    (errors/validation-failed [(errors/field-error "file" "required" "Choose the CSV file to import.")]))
  (when (> (count files) 1)
    (errors/validation-failed [(errors/field-error "file" "invalid" "Send exactly one CSV file.")]))
  (let [^java.io.File f (:tempfile (first files))]
    (when (> (.length f) csv/max-bytes)
      (errors/fail! :fileTooLarge "The file is larger than 1 MB."))
    (let [text (csv/decode-text (java.nio.file.Files/readAllBytes (.toPath f)))]
      (when (nil? text)
        (errors/validation-failed [(errors/field-error "file" "notUtf8" "The file must be a UTF-8 text (CSV) file. In Excel choose \"CSV UTF-8\".")]))
      (let [table (csv/parse-table text)]
        (when-let [problems (:file-errors table)]
          (errors/validation-failed "The file could not be imported." problems))
        table))))

(def ^:private create-keys
  {:name :name :brand :brand :own-brand :is-own-brand :category :category-slug :price :price
   :short-description :short-description :description :description :size :size
   :how-to-use :how-to-use :ingredients :ingredients :featured :featured :sku :sku})

(defn- request-fields [fields]
  (reduce-kv (fn [m k v] (if-let [rk (create-keys k)] (assoc m rk v) m)) {} fields))

(defn- failure [ex]
  (when-let [{:keys [code details message]} (errors/error-data ex)]
    (if (seq details)
      (mapv #(select-keys % [:field :code :message]) details)
      [{:field "row" :code (name code) :message message}])))

(defn- apply-row!
  "Writes one evaluated row. Returns the row with :status and :product-id (or the errors that stopped it)."
  [ctx user {:keys [action fields sku] :as row}]
  (let [user* {:user-eid (:eid user)}]
    (try
      (case action
        :create
        (let [p (catalog/create-product! ctx user*
                                         (cond-> (assoc (request-fields fields) :status "draft")
                                           (:opening-stock fields) (assoc :stock-quantity (:opening-stock fields))))]
          (assoc row :status "created" :product-id (:id p)))
        :update
        (loop [attempt 0]
          (let [p (catalog/product-by-sku ctx sku)
                changes (dissoc (request-fields fields) :sku)]
            (if (empty? changes)
              (assoc row :status "updated" :product-id (:id p))
              (let [r (try (catalog/update-product! ctx user* (:id p) (assoc changes :version (:version p)))
                           (catch Throwable ex
                             (if (and (< attempt 3) (= :versionConflict (:code (errors/error-data ex)))) ::retry (throw ex))))]
                (if (= ::retry r)
                  (recur (inc attempt))
                  (assoc row :status "updated" :product-id (:id r))))))))
      (catch Throwable ex
        (if-let [errs (failure ex)]
          (assoc row :status "rejected" :product-id nil :warnings [] :errors errs)
          (throw ex))))))

(defn run-import!
  "Validates the rows and, unless dry-run?, applies the valid ones one by one. Returns the report."
  [ctx user {:keys [rows]} dry-run?]
  (let [known (set (keys (catalog/skus ctx)))
        evaluated (csv/evaluate rows (catalog/categories ctx) known)
        done (mapv (fn [{:keys [action sku] :as row}]
                     (cond
                       (= :rejected action) (assoc row :status "rejected" :product-id nil)
                       dry-run? (assoc row :status (if (= :create action) "created" "updated")
                                       :product-id (when (= :update action) (:id (catalog/product-by-sku ctx sku))))
                       :else (apply-row! ctx user row)))
                   evaluated)]
    (csv/report dry-run? done)))

(defn template-response []
  {:status 200
   :headers {"Content-Type" "text/csv; charset=utf-8"
             "Content-Disposition" "attachment; filename=\"zandy-touch-products.csv\""
             "Cache-Control" "no-store"}
   :body (csv/template-csv)})
