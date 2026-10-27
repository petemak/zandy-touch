(ns zandy.features.photos.routes
  "Product pictures and the bulk product import (contract 8.2), and the media files (section 13).
  There is no photo library: every picture is uploaded onto a product."
  (:require [zandy.features.catalog.api :as catalog]
            [zandy.features.photos.imports :as imports]
            [zandy.features.photos.schema :as schema]
            [zandy.platform.errors :as errors]
            [zandy.platform.media :as media]
            [zandy.platform.validation :as v]))

(def ^:private max-product-files 6)
(def ^:private max-bulk-files 20)

(defn- require-files!
  "The uploaded files of the `files` field, 1 to `limit` of them, else a 422 in the contract's shape."
  [req limit]
  (let [files (imports/uploaded req "files")]
    (when (empty? files)
      (errors/validation-failed [(errors/field-error "files" "required" "Send the pictures as multipart/form-data in the field \"files\".")]))
    (when (> (count files) limit)
      (errors/validation-failed [(errors/field-error "files" "tooManyFiles" (str "Send at most " limit " pictures at a time."))]))
    files))

(defn upload-to-product
  "Handler for POST /api/admin/products/:id/images (staff, idempotent, multipart field \"files\", 1 to 6 pictures). Appends the pictures to the product in the order sent.
  Returns 200 {:items [per-file result] :product staff-detail}; a bad picture is reported in its item (status \"rejected\" with error code) and does not fail the request.
  Throws notFound (404) or validationFailed (files required, tooManyFiles). Side effects: media files and Datomic writes per picture; temp files are removed by the platform afterwards."
  [{:keys [ctx parameters user] :as req}]
  (let [id (v/parse-uuid-or-404 (get-in parameters [:path :id]))
        product (or (catalog/get-product ctx id) (errors/fail! :notFound "Product not found."))
        files (require-files! req max-product-files)
        items (imports/attach-to-product! ctx user id (:sku product) files)]
    {:status 200
     :body {:items items
            :product (catalog/admin-product-view ctx (catalog/get-product ctx id))}}))

(defn upload-by-sku
  "Handler for POST /api/admin/product-images (staff, idempotent, multipart field \"files\", 1 to 20 pictures). Each file is matched to a product by the SKU at the start of its file name (see logic/match-file).
  Returns 200 {:items [per-file result]}; unmatched or failed files are reported per item, never as a request error. Throws validationFailed for a missing or oversized file list."
  [{:keys [ctx user] :as req}]
  (let [files (require-files! req max-bulk-files)]
    {:status 200 :body {:items (imports/attach-by-sku! ctx user files)}}))

(defn import-products
  "Handler for POST /api/admin/product-imports (staff, idempotent, multipart field \"file\", one CSV). Query dryRun=true validates and reports without writing.
  Returns 200 with the report {:dryRun :summary {:rows :created :updated :rejected} :rows [...]}. Throws validationFailed for whole-file problems (missing or several files, not UTF-8, bad headings, over 500 rows) and fileTooLarge (413)."
  [{:keys [ctx parameters user] :as req}]
  (let [table (imports/read-csv-upload (imports/uploaded req "file"))]
    {:status 200 :body (imports/run-import! ctx user table (boolean (get-in parameters [:query :dry-run])))}))

(defn import-template
  "Handler for GET /api/admin/product-import-template (staff). Returns 200 with the CSV template as a download."
  [_req]
  (imports/template-response))

;; Only this exact shape is served: no path separators or dots, so a request cannot reach other files. Files are immutable (a new upload gets a new id), hence the long cache time.
(def ^:private media-file-re #"^([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})-(thumb|medium|large)\.webp$")

(defn serve-media
  "Handler for GET /media/:file (public). Serves a stored WebP variant (\"<photo-uuid>-<thumb|medium|large>.webp\") with immutable one-year cache headers, or notFound (404). The strict file-name pattern keeps requests inside the media directory."
  [{:keys [ctx parameters]}]
  (let [[_ id variant] (re-matches media-file-re (str (get-in parameters [:path :file])))
        f (when id (media/open (:media-store ctx) id (keyword variant)))]
    (if f
      {:status 200
       :headers {"Content-Type" "image/webp" "Cache-Control" "public, max-age=31536000, immutable"}
       :body f}
      (errors/fail! :notFound "Not found."))))

(def routes
  "Route data for the photos slice: four staff upload/import endpoints (multipart, idempotent except the template download) and the public media file route."
  [{:method :post :path "/api/admin/products/:id/images" :name :photos/upload-to-product :access :staff
    :idempotent? true :multipart? true :handler upload-to-product}
   {:method :post :path "/api/admin/product-images" :name :photos/upload-by-sku :access :staff
    :idempotent? true :multipart? true :handler upload-by-sku}
   {:method :post :path "/api/admin/product-imports" :name :photos/import :access :staff
    :idempotent? true :multipart? true :parameters {:query schema/ImportQuery} :handler import-products}
   {:method :get :path "/api/admin/product-import-template" :name :photos/import-template :access :staff
    :handler import-template}
   {:method :get :path "/media/:file" :name :photos/media :access :public :handler serve-media}])
