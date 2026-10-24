(ns zandy.features.photos.routes
  "Photo library: bulk upload, listing, assignment, deletion, and serving the media files
  (contract sections 8.2 and 13)."
  (:require [clojure.java.io :as io]
            [zandy.features.catalog.api :as catalog]
            [zandy.features.photos.schema :as schema]
            [zandy.platform.errors :as errors]
            [zandy.platform.media :as media]
            [zandy.platform.util :as util]
            [zandy.platform.validation :as v]))

(defn- uploaded-files [req]
  (let [f (get (:multipart req) "files")]
    (cond (map? f) [f] (sequential? f) (vec f) :else [])))

(defn- stored-item [ctx filename photo-id]
  {:filename filename :status "stored"
   :photo {:id photo-id :urls (media/urls photo-id) :assigned-product-ids []}
   :suggested-product-id (catalog/find-product-id-by-sku-prefix ctx filename)
   :error nil})

(defn upload [{:keys [ctx user] :as req}]
  (let [files (uploaded-files req)]
    (when (empty? files)
      (errors/fail! :malformedRequest "Send the photos as multipart/form-data in the field \"files\"."))
    (when (> (count files) 20)
      (errors/validation-failed [(errors/field-error "files" "tooManyFiles" "Upload at most 20 photos at a time.")]))
    (let [items
          (mapv (fn [{:keys [filename tempfile]}]
                  (try
                    (let [photo-id (random-uuid)
                          result (media/process-upload! (:media-store ctx) (:image-processor ctx) photo-id tempfile)]
                      (if-let [ok (:ok result)]
                        (do (catalog/create-photo! ctx {:id photo-id :original-filename filename
                                                        :content-type (:content-type ok) :width (:width ok)
                                                        :height (:height ok) :uploaded-by-eid (:eid user)})
                            (stored-item ctx filename photo-id))
                        {:filename filename :status "rejected" :photo nil :suggested-product-id nil :error (:error result)}))
                    (finally (when tempfile (io/delete-file tempfile true)))))
                files)]
      {:status 201 :body {:items items}})))

(defn list-photos [{:keys [ctx parameters]}]
  (let [q (:query parameters)
        page (catalog/list-photos ctx {:unassigned? (:unassigned q) :page (:page q) :page-size (or (:page-size q) 40)})]
    {:status 200
     :body (update page :items (fn [items] (mapv (fn [p] {:id (:id p) :filename (:filename p) :urls (media/urls (:id p))
                                                          :assigned-product-ids (:assigned-product-ids p)
                                                          :uploaded-at (:uploaded-at p)})
                                                 items)))}))

(defn assign [{:keys [ctx parameters user]}]
  (let [entries (mapv (fn [{:keys [photo-id product-id]}] {:photo-id (parse-uuid photo-id) :product-id (parse-uuid product-id)})
                      (get-in parameters [:body :assignments]))
        results (catalog/assign-photos! ctx {:user-eid (:eid user)} entries)]
    {:status 200 :body {:results (mapv #(util/compact %) results)}}))

(defn delete-photo [{:keys [ctx parameters]}]
  (let [id (v/parse-uuid-or-404 (get-in parameters [:path :id]))]
    (catalog/delete-photo! ctx id)
    (media/delete-photo! (:media-store ctx) id)
    {:status 204 :body nil}))

(def ^:private media-file-re #"^([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})-(thumb|medium|large)\.webp$")

(defn serve-media [{:keys [ctx parameters]}]
  (let [[_ id variant] (re-matches media-file-re (str (get-in parameters [:path :file])))
        f (when id (media/open (:media-store ctx) id (keyword variant)))]
    (if f
      {:status 200
       :headers {"Content-Type" "image/webp" "Cache-Control" "public, max-age=31536000, immutable"}
       :body f}
      (errors/fail! :notFound "Not found."))))

(def routes
  [{:method :post :path "/api/admin/photos" :name :photos/upload :access :staff :idempotent? true :multipart? true
    :handler upload}
   {:method :get :path "/api/admin/photos" :name :photos/list :access :staff
    :parameters {:query schema/PhotosQuery} :handler list-photos}
   {:method :post :path "/api/admin/photo-assignments" :name :photos/assign :access :staff
    :parameters {:body schema/Assignments} :handler assign}
   {:method :delete :path "/api/admin/photos/:id" :name :photos/delete :access :staff :handler delete-photo}
   {:method :get :path "/media/:file" :name :photos/media :access :public :handler serve-media}])
