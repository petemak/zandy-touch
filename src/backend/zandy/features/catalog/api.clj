(ns zandy.features.catalog.api
  "Catalogue operations used by other slices (orders, photos, the seed) and by this slice's routes.
  A picture always belongs to one product: it is stored by attach-file! and deleted when it is removed from
  its product."
  (:require [clojure.string :as str]
            [datomic.api :as d]
            [zandy.features.catalog.db :as db]
            [zandy.features.catalog.logic :as logic]
            [zandy.platform.errors :as errors]
            [zandy.platform.media :as media]
            [zandy.platform.util :as util]))

(defn view-opts [ctx]
  {:max-qty (get-in ctx [:config :catalog :max-quantity-per-line])
   :low (get-in ctx [:config :catalog :low-stock-threshold])
   :now (util/now ctx)})

(defn- unique-violation? [ex]
  (loop [e ex]
    (when e
      (or (some-> (ex-message e) (str/includes? "unique")) (recur (ex-cause e))))))

(defn- cas-failed? [ex]
  (loop [e ex]
    (when e
      (or (some-> (ex-message e) (str/includes? "cas-failed")) (some-> (ex-message e) (str/includes? ":db.error/cas-failed"))
          (recur (ex-cause e))))))

(defn- write-fields
  "Converts validated request fields to internal keys, resolving the category and normalising the sku."
  [dbv body]
  (let [problems (atom [])
        out (cond-> {}
              (contains? body :name) (assoc :name (:name body))
              (contains? body :brand) (assoc :brand (:brand body))
              (contains? body :is-own-brand) (assoc :own-brand? (:is-own-brand body))
              (contains? body :featured) (assoc :featured? (:featured body))
              (contains? body :price) (assoc :price (:price body))
              (contains? body :short-description) (assoc :short-description (:short-description body))
              (contains? body :description) (assoc :description (:description body))
              (contains? body :size) (assoc :size (:size body))
              (contains? body :how-to-use) (assoc :how-to-use (:how-to-use body))
              (contains? body :ingredients) (assoc :ingredients (:ingredients body))
              (contains? body :status) (assoc :status (keyword (:status body)))
              (contains? body :sku) (assoc :sku (logic/normalize-sku (:sku body))))
        out (if-let [slug (:category-slug body)]
              (if-let [ceid (db/category-eid dbv slug)]
                (assoc out :category-eid ceid)
                (do (swap! problems conj {:field "categorySlug" :code "unknownCategory" :message "This category does not exist."}) out))
              out)]
    [out @problems]))

(defn- check-sku! [dbv sku own-id]
  (when sku
    (when-let [other (db/product-by-sku dbv sku)]
      (when (not= own-id (:id other))
        [{:field "sku" :code "skuAlreadyInUse" :message "This SKU is already used by another product."}]))))

(defn- delete-media! [ctx photo-ids]
  (doseq [id photo-ids]
    (media/delete-photo! (:media-store ctx) id)))

(defn create-product!
  "Creates a product. `fields` uses the request keys (kebab-case, status as a string). A new product has no
  pictures yet: they are added with attach-file! once it exists. Returns the product map."
  [ctx {:keys [user-eid]} fields]
  (let [conn (:conn ctx)
        dbv (d/db conn)
        now (util/now ctx)
        [w problems] (write-fields dbv fields)
        sku-problems (check-sku! dbv (:sku w) nil)
        image-problems (:problems (logic/reconcile-images [] (:images fields)))
        merged {:status (:status w) :price (:price w) :images [] :category (:category-eid w)}
        all (vec (concat problems sku-problems image-problems (logic/publish-problems merged)))]
    (when (seq all) (errors/validation-failed all))
    (let [taken (set (d/q '[:find [?s ...] :where [_ :product/slug ?s]] dbv))
          slug (logic/unique-slug (logic/slugify (:name w)) taken)
          id (random-uuid)
          tx (db/create-product-tx (assoc w :id id :slug slug :category-eid (:category-eid w)
                                          :stock-quantity (:stock-quantity fields)
                                          :user-eid user-eid :now now :movement-id (random-uuid)))]
      (try (db/transact! conn tx)
           (catch Throwable ex
             (if (unique-violation? ex)
               (errors/validation-failed [{:field "sku" :code "skuAlreadyInUse" :message "This SKU is already used by another product."}])
               (throw ex))))
      (db/product-by-id (d/db conn) id))))

(defn update-product!
  "Partial update guarded by the version. When `images` is present it is the new picture list: pictures can
  be reordered, re-labelled or left out, and a picture left out is deleted. Returns the updated product map."
  [ctx {:keys [user-eid]} product-id body]
  (let [conn (:conn ctx)
        dbv (d/db conn)
        existing (or (db/product-by-id dbv product-id) (errors/fail! :notFound "Product not found."))
        _ (when (not= (:version body) (:version existing))
            (errors/fail! :versionConflict "This product was changed by someone else. Please reload it."))
        [w problems] (write-fields dbv (dissoc body :version :images))
        sku-problems (check-sku! dbv (:sku w) product-id)
        recon (when (contains? body :images) (logic/reconcile-images (:images existing) (:images body)))
        merged {:status (or (:status w) (:status existing))
                :price (if (contains? w :price) (:price w) (:price existing))
                :images (if recon (:kept recon) (:images existing))
                :category (or (:category-eid w) (:category existing))}
        all (vec (concat problems sku-problems (:problems recon) (logic/publish-problems merged)))]
    (when (seq all) (errors/validation-failed all))
    (let [tx (vec (concat (db/update-product-tx existing w {:user-eid user-eid :now (util/now ctx)})
                          (when recon (db/reorder-images-tx existing (:kept recon) (:removed recon)))))]
      (try (db/transact! conn tx)
           (catch Throwable ex
             (cond
               (cas-failed? ex) (errors/fail! :versionConflict "This product was changed by someone else. Please reload it.")
               (unique-violation? ex) (errors/validation-failed [{:field "sku" :code "skuAlreadyInUse" :message "This SKU is already used by another product."}])
               :else (throw ex))))
      (when recon (delete-media! ctx (keep :photo-id (:removed recon))))
      (db/product-by-id (d/db conn) product-id))))

(defn archive-product! [ctx {:keys [user-eid]} product-id]
  (let [conn (:conn ctx)
        existing (or (db/product-by-id (d/db conn) product-id) (errors/fail! :notFound "Product not found."))]
    (when-not (= :archived (:status existing))
      (try (db/transact! conn (db/archive-tx existing {:user-eid user-eid :now (util/now ctx)}))
           (catch Throwable ex
             (if (cas-failed? ex)
               (errors/fail! :versionConflict "This product was changed by someone else. Please try again.")
               (throw ex)))))
    nil))

(defn adjust-stock!
  "Atomic stock adjustment through the :catalog/adjust-stock transaction function."
  [ctx {:keys [user-id]} product-id {:keys [delta reason note expected-quantity]}]
  (let [movement-id (random-uuid)
        spec (util/compact {:product-id product-id :delta delta :reason (keyword reason) :note note
                            :expected-quantity expected-quantity :by user-id
                            :now (util/now ctx) :movement-id movement-id})]
    (db/transact! (:conn ctx) [[:catalog/adjust-stock spec]])
    movement-id))

;; ---- reads used elsewhere ----------------------------------------------------------------------

(defn sku-exists? [ctx sku]
  (some? (db/product-by-sku (d/db (:conn ctx)) sku)))

(defn published-sitemap [ctx]
  (let [dbv (d/db (:conn ctx))
        products (db/published-products dbv)]
    {:products (mapv (fn [p] {:slug (:slug p) :updated-at (:updated-at p)}) (sort-by :slug products))
     :categories (mapv #(select-keys % [:slug]) (db/categories dbv))
     :generated-at (util/now ctx)}))

(defn cart-info
  "For each product id, what the cart and checkout need: name, price, stock, publication and image."
  [ctx ids]
  (let [opts (view-opts ctx)]
    (into {}
          (map (fn [[id p]]
                 [id {:id id :slug (:slug p) :name (:name p) :price (:price p) :stock-quantity (:stock-quantity p)
                      :published? (= :published (:status p))
                      :availability (logic/availability (:stock-quantity p))
                      :max-quantity (logic/max-quantity (:stock-quantity p) (:max-qty opts))
                      :image (logic/first-image p)}]))
          (db/products-by-ids (d/db (:conn ctx)) ids))))

;; ---- product pictures ----------------------------------------------------------------------------

(defn get-product
  "The product map by id, or nil."
  [ctx id]
  (db/product-by-id (d/db (:conn ctx)) id))

(defn product-by-sku [ctx sku]
  (db/product-by-sku (d/db (:conn ctx)) (logic/normalize-sku sku)))

(defn skus
  "Every SKU in use, upper-case, as a map sku -> product id (archived products included)."
  [ctx]
  (into {} (keep (fn [p] (when (:sku p) [(:sku p) (:id p)]))) (db/all-products (d/db (:conn ctx)))))

(defn categories [ctx]
  (db/categories (d/db (:conn ctx))))

(defn attach-file!
  "Processes an uploaded image file and stores it on the product: appended, or in the 1-based `position`
  slot (replacing the picture there, which is deleted). The photo record is created in the same transaction
  that links it to the product, so a picture never exists without a product. `opts`: :user-eid :filename
  :position :seed?. Returns {:status :attached :position n :product-id id :image {:id :alt :urls}} or
  {:status :rejected :error {:code :message}}; per-file problems never throw. Throws notFound for an unknown product."
  [ctx {:keys [user-eid filename position seed?]} product-id ^java.io.File file]
  (let [conn (:conn ctx)
        rejected (fn [code message] {:status :rejected :error {:code code :message message}})
        too-many (fn [] (rejected "tooManyImages" "This product already has 6 pictures."))
        product (or (db/product-by-id (d/db conn) product-id) (errors/fail! :notFound "Product not found."))]
    (if (:error (logic/plan-slot (:images product) position))
      (too-many)
      (let [photo-id (random-uuid)
            result (media/process-upload! (:media-store ctx) (:image-processor ctx) photo-id file)]
        (if-let [ok (:ok result)]
          (loop [attempt 0]
            (let [product (db/product-by-id (d/db conn) product-id)
                  plan (logic/plan-slot (:images product) position)]
              (if (:error plan)
                (do (media/delete-photo! (:media-store ctx) photo-id) (too-many))
                (let [tx (db/attach-picture-tx product (assoc ok :photo-id photo-id :filename filename :index (:index plan)
                                                              :replaces (:replaces plan) :user-eid user-eid
                                                              :now (util/now ctx) :seed? seed?))
                      outcome (try (db/transact! conn tx) :ok
                                   (catch Throwable ex (if (cas-failed? ex) :conflict (throw ex))))]
                  (cond
                    (= :ok outcome)
                    (do (when-let [old (:replaces plan)] (delete-media! ctx [(:photo-id old)]))
                        {:status :attached :product-id product-id :position (inc (:index plan))
                         :image {:id photo-id :alt nil :urls (util/media-urls photo-id)}})
                    (< attempt 5) (recur (inc attempt))
                    :else (do (media/delete-photo! (:media-store ctx) photo-id)
                              (errors/fail! :versionConflict "This product was changed by someone else. Please try again.")))))))
          {:status :rejected :error (:error result)})))))

;; ---- bulk publication ------------------------------------------------------------------------------

(defn- error-details [ex]
  (when-let [{:keys [code details message]} (errors/error-data ex)]
    (if (seq details) details [{:field nil :code (name code) :message message}])))

(defn publish-product!
  "Publishes one product under the publishing rule. Returns {:product-id id :status :published} or
  {:product-id id :status :rejected :errors [{:field :code :message}]}. An already published product is
  left alone."
  [ctx {:keys [user-eid]} product-id]
  (loop [attempt 0]
    (let [p (db/product-by-id (d/db (:conn ctx)) product-id)]
      (cond
        (nil? p) {:product-id product-id :status :rejected
                  :errors [{:field "productId" :code "notFound" :message "This product does not exist."}]}
        (= :published (:status p)) {:product-id product-id :status :published}
        :else
        (let [r (try (update-product! ctx {:user-eid user-eid} product-id {:version (:version p) :status "published"})
                     :done
                     (catch Throwable ex
                       (if (and (< attempt 3) (= :versionConflict (:code (errors/error-data ex))))
                         :retry
                         (if-let [details (error-details ex)] details (throw ex)))))]
          (cond
            (= :done r) {:product-id product-id :status :published}
            (= :retry r) (recur (inc attempt))
            :else {:product-id product-id :status :rejected :errors r}))))))

(defn admin-product-view
  "The staff detail view of a product (shape of GET /api/admin/products/{id})."
  [ctx p]
  (let [by ((:user-summaries ctx) [(:updated-by-eid p)])]
    (logic/admin-detail p (get by (:updated-by-eid p)) (view-opts ctx))))
