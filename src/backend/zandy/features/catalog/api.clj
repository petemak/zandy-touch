(ns zandy.features.catalog.api
  "Catalogue operations used by other slices (orders, photos, the seed) and by this slice's routes."
  (:require [clojure.string :as str]
            [datomic.api :as d]
            [zandy.features.catalog.db :as db]
            [zandy.features.catalog.logic :as logic]
            [zandy.platform.errors :as errors]
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

(defn- resolve-images
  "Checks image entries against the photo library. Returns [images problems]."
  [dbv images]
  (reduce (fn [[acc probs] [i {:keys [id alt]}]]
            (let [pid (parse-uuid id)
                  eid (when pid (db/photo-eid dbv pid))]
              (if eid
                [(conj acc {:photo-eid eid :alt alt}) probs]
                [acc (conj probs {:field (str "images[" i "].id") :code "unknownPhoto" :message "This photo does not exist."})])))
          [[] []]
          (map-indexed vector images)))

(defn- write-fields
  "Converts validated request fields to internal keys, resolving category, photos and sku."
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
              out)
        out (if (contains? body :images)
              (let [[imgs probs] (resolve-images dbv (:images body))]
                (swap! problems into probs)
                (assoc out :images imgs))
              out)]
    [out @problems]))

(defn- check-sku! [dbv sku own-id]
  (when sku
    (when-let [other (db/product-by-sku dbv sku)]
      (when (not= own-id (:id other))
        [{:field "sku" :code "skuAlreadyInUse" :message "This SKU is already used by another product."}]))))

(defn create-product!
  "Creates a product. `fields` uses the request keys (kebab-case, status as a string). Returns the product map."
  [ctx {:keys [user-eid]} fields]
  (let [conn (:conn ctx)
        dbv (d/db conn)
        now (util/now ctx)
        [w problems] (write-fields dbv fields)
        sku-problems (check-sku! dbv (:sku w) nil)
        merged {:status (:status w) :price (:price w) :images (:images w) :category (:category-eid w)}
        all (vec (concat problems sku-problems (logic/publish-problems merged)))]
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
  "Partial update guarded by the version. Returns the updated product map."
  [ctx {:keys [user-eid]} product-id body]
  (let [conn (:conn ctx)
        dbv (d/db conn)
        existing (or (db/product-by-id dbv product-id) (errors/fail! :notFound "Product not found."))
        _ (when (not= (:version body) (:version existing))
            (errors/fail! :versionConflict "This product was changed by someone else. Please reload it."))
        [w problems] (write-fields dbv (dissoc body :version))
        sku-problems (check-sku! dbv (:sku w) product-id)
        merged {:status (or (:status w) (:status existing))
                :price (if (contains? w :price) (:price w) (:price existing))
                :images (if (contains? w :images) (:images w) (:images existing))
                :category (or (:category-eid w) (:category existing))}
        all (vec (concat problems sku-problems (logic/publish-problems merged)))]
    (when (seq all) (errors/validation-failed all))
    (let [tx (vec (concat (db/update-product-tx existing w {:user-eid user-eid :now (util/now ctx)})
                          (when (contains? w :images) (db/replace-images-tx existing (:images w)))))]
      (try (db/transact! conn tx)
           (catch Throwable ex
             (cond
               (cas-failed? ex) (errors/fail! :versionConflict "This product was changed by someone else. Please reload it.")
               (unique-violation? ex) (errors/validation-failed [{:field "sku" :code "skuAlreadyInUse" :message "This SKU is already used by another product."}])
               :else (throw ex))))
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

;; ---- photos --------------------------------------------------------------------------------------

(defn create-photo! [ctx photo]
  (db/create-photo! (:conn ctx) (assoc photo :now (util/now ctx)))
  (:id photo))

(defn find-product-id-by-sku-prefix
  "Suggests a product for a file name that starts with an existing SKU (case-insensitive). The
  longest matching SKU wins."
  [ctx filename]
  (let [stem (str/upper-case (or filename ""))
        products (db/all-products (d/db (:conn ctx)))]
    (->> products
         (filter #(and (:sku %) (str/starts-with? stem (:sku %))))
         (sort-by #(- (count (:sku %))))
         first
         :id)))

(defn list-photos [ctx {:keys [unassigned? page page-size]}]
  (let [all (db/photos (d/db (:conn ctx)))
        all (if unassigned? (filter #(empty? (:assigned-product-ids %)) all) all)]
    (logic/paginate all page page-size)))

(defn photo-assigned-ids [ctx photo-id]
  (let [dbv (d/db (:conn ctx))]
    (when-let [p (db/photo-by-id dbv photo-id)]
      (get (db/assigned-product-ids dbv) (:eid p) []))))

(defn assign-photos!
  "Appends photos to products (max 6 per product). Returns one result per entry."
  [ctx {:keys [user-eid]} assignments]
  (let [conn (:conn ctx)]
    (mapv
     (fn [{:keys [photo-id product-id]}]
       (let [dbv (d/db conn)
             photo (db/photo-by-id dbv photo-id)
             product (db/product-by-id dbv product-id)
             base {:photo-id photo-id :product-id product-id}]
         (cond
           (nil? photo) (assoc base :status :rejected :error {:code "notFound" :message "This photo does not exist."})
           (nil? product) (assoc base :status :rejected :error {:code "notFound" :message "This product does not exist."})
           (some #(= (:photo-id %) photo-id) (:images product)) (assoc base :status :assigned)
           (>= (count (:images product)) logic/max-images)
           (assoc base :status :rejected :error {:code "tooManyImages" :message "A product can have at most 6 images."})
           :else
           (do (db/transact! conn (vec (remove nil?
                                    [{:db/id (:eid product)
                                      :product/images [{:product-image/id (random-uuid)
                                                        :product-image/position (count (:images product))
                                                        :product-image/photo (:eid photo)}]}
                                     [:db/add (:eid product) :product/updated-at (util/now ctx)]
                                     [:db/cas (:eid product) :product/version (:version product) (inc (:version product))]
                                     (when user-eid [:db/add (:eid product) :product/updated-by user-eid])])))
               (assoc base :status :assigned)))))
     assignments)))

(defn delete-photo!
  "Deletes the photo record and detaches it from products; a published product left without images
  becomes a draft. Idempotent."
  [ctx photo-id]
  (let [conn (:conn ctx)
        dbv (d/db conn)]
    (when-let [photo (db/photo-by-id dbv photo-id)]
      (let [products (db/products-using-photo dbv (:eid photo))
            now (util/now ctx)
            tx (vec
                (concat
                 (mapcat (fn [p]
                           (let [mine (filter #(= (:photo-id %) photo-id) (:images p))
                                 remaining (- (count (:images p)) (count mine))]
                             (concat (mapcat (fn [i] [[:db/retract (:eid p) :product/images (:eid i)] [:db/retractEntity (:eid i)]]) mine)
                                     [[:db/add (:eid p) :product/updated-at now]
                                      [:db/add (:eid p) :product/version (inc (:version p))]]
                                     (when (and (zero? remaining) (= :published (:status p)))
                                       [[:db/add (:eid p) :product/status :draft]]))))
                         products)
                 [[:db/retractEntity (:eid photo)]]))]
        (db/transact! conn tx)))
    nil))
