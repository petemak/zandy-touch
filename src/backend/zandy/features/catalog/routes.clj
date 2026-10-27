(ns zandy.features.catalog.routes
  "Catalogue endpoints: categories, public products, sitemap entries, staff products and stock
  (contract sections 2, 3, 8.1, 8.3 and the bulk publication of 8.2)."
  (:require [zandy.features.catalog.api :as api]
            [zandy.features.catalog.db :as db]
            [zandy.features.catalog.logic :as logic]
            [zandy.features.catalog.schema :as schema]
            [zandy.platform.errors :as errors]
            [zandy.platform.validation :as v]))

(def ^:private cache-products "public, max-age=30, stale-while-revalidate=60")
(def ^:private cache-categories "public, max-age=60, stale-while-revalidate=300")
(def ^:private cache-sitemap "public, max-age=3600")

(defn- dbv [ctx] (db/current-db (:conn ctx)))
(defn- users [ctx eids] ((:user-summaries ctx) eids))

;; ---- public ------------------------------------------------------------------------------------

(defn list-categories
  "Handler for GET /api/categories (public, cached). Returns 200 {:items [{:id :slug :name :description :sortOrder :productCount}]}; productCount counts published products only."
  [{:keys [ctx]}]
  (let [d (dbv ctx)
        counts (frequencies (map #(get-in % [:category :slug]) (db/published-products d)))]
    {:status 200
     :body {:items (mapv (fn [c] (assoc (select-keys c [:id :slug :name :description :sort-order])
                                        :product-count (get counts (:slug c) 0)))
                         (db/categories d))}}))

(defn list-products
  "Handler for GET /api/products (public, cached). Query: categorySlug, q, availability, ownBrand, featured, sort, page, pageSize. Returns 200 with a page of public product summaries (sold-out products last)."
  [{:keys [ctx parameters]}]
  (let [q (:query parameters)
        opts (api/view-opts ctx)
        products (-> (db/published-products (dbv ctx))
                     (logic/filter-public q)
                     (logic/sort-public (:sort q)))
        page (logic/paginate products (:page q) (:page-size q))]
    {:status 200 :body (update page :items (fn [items] (mapv #(logic/summary-view % opts) items)))}))

(defn get-product
  "Handler for GET /api/products/:slug (public, cached). Returns 200 with the product detail and up to 4 related products, or notFound (404) when the slug is unknown or the product is not published."
  [{:keys [ctx parameters]}]
  (let [slug (get-in parameters [:path :slug])
        opts (api/view-opts ctx)
        d (dbv ctx)
        p (db/product-by-slug d slug)]
    (if (and p (= :published (:status p)))
      (let [related (mapv #(logic/summary-view % opts)
                          (logic/related-products (db/published-products d) p 4))]
        {:status 200 :body (logic/detail-view p related opts)})
      (errors/fail! :notFound "Product not found."))))

(defn sitemap-entries
  "Handler for GET /api/sitemap-entries (public, cached 1 h): published product slugs with updated-at, and category slugs, for the frontend server's sitemap.xml."
  [{:keys [ctx]}]
  {:status 200 :body (api/published-sitemap ctx)})

;; ---- staff products ----------------------------------------------------------------------------

(defn- admin-view [ctx p f]
  (let [opts (api/view-opts ctx)
        by ((:user-summaries ctx) [(:updated-by-eid p)])]
    (f p (get by (:updated-by-eid p)) opts)))

(defn admin-list
  "Handler for GET /api/admin/products (staff). Query: status, categorySlug, q, stock, sort, page, pageSize (default 25). Returns 200 with a page of staff summaries plus :counts {:out :low}. Archived products are hidden unless status=archived."
  [{:keys [ctx parameters]}]
  (let [q (:query parameters)
        opts (api/view-opts ctx)
        all (db/all-products (dbv ctx))
        filtered (-> all (logic/filter-admin q (:low opts)) (logic/sort-admin (:sort q)))
        page (logic/paginate filtered (:page q) (or (:page-size q) 25))
        by (users ctx (map :updated-by-eid (:items page)))]
    {:status 200
     :body (-> page
               (update :items (fn [items] (mapv #(logic/admin-summary % (get by (:updated-by-eid %)) opts) items)))
               (assoc :counts (logic/admin-counts all (:low opts))))}))

(defn admin-get
  "Handler for GET /api/admin/products/:id (staff). Returns 200 with the staff detail, notFound (404) for a bad or unknown id."
  [{:keys [ctx parameters]}]
  (let [id (v/parse-uuid-or-404 (get-in parameters [:path :id]))
        p (or (db/product-by-id (dbv ctx) id) (errors/fail! :notFound "Product not found."))]
    {:status 200 :body (admin-view ctx p logic/admin-detail)}))

(defn admin-create
  "Handler for POST /api/admin/products (staff, idempotent). Body: CreateProduct. Returns 201 with the staff detail; errors as api/create-product!."
  [{:keys [ctx parameters user]}]
  (let [p (api/create-product! ctx {:user-eid (:eid user)} (:body parameters))]
    {:status 201 :body (admin-view ctx p logic/admin-detail)}))

(defn admin-patch
  "Handler for PATCH /api/admin/products/:id (staff). Body: PatchProduct (nulls kept: :keep-nils? true so null clears a field). Returns 200 with the staff detail; errors as api/update-product!."
  [{:keys [ctx parameters user]}]
  (let [id (v/parse-uuid-or-404 (get-in parameters [:path :id]))
        p (api/update-product! ctx {:user-eid (:eid user)} id (:body parameters))]
    {:status 200 :body (admin-view ctx p logic/admin-detail)}))

(defn admin-delete
  "Handler for DELETE /api/admin/products/:id (staff). Archives the product and returns 204 with no body."
  [{:keys [ctx parameters user]}]
  (let [id (v/parse-uuid-or-404 (get-in parameters [:path :id]))]
    (api/archive-product! ctx {:user-eid (:eid user)} id)
    {:status 204 :body nil}))

;; ---- stock -----------------------------------------------------------------------------------------

(defn- movement-view [m by]
  {:id (:id m) :type (:type m) :reason (:reason m) :delta (:delta m) :quantity-after (:quantity-after m)
   :note (:note m) :order-number (:order-number m) :at (:at m) :by by})

(defn stock-adjust
  "Handler for POST /api/admin/products/:id/stock-movements (staff, idempotent). Body: StockMovement. Returns 201 {:movement :stockQuantity :availability} after the atomic adjustment; errors as api/adjust-stock!."
  [{:keys [ctx parameters user]}]
  (let [id (v/parse-uuid-or-404 (get-in parameters [:path :id]))
        movement-id (api/adjust-stock! ctx {:user-id (:id user)} id (:body parameters))
        d (dbv ctx)
        m (db/movement-by-id d movement-id)
        p (db/product-by-id d id)
        by (users ctx [(:by-eid m)])]
    {:status 201
     :body {:movement (movement-view m (get by (:by-eid m)))
            :stock-quantity (:stock-quantity p)
            :availability (logic/availability (:stock-quantity p))}}))

(defn stock-history
  "Handler for GET /api/admin/products/:id/stock-movements (staff). Query: page, pageSize (default 25). Returns 200 with a page of movements, newest first, each with the acting user summary; notFound (404) for an unknown product."
  [{:keys [ctx parameters]}]
  (let [id (v/parse-uuid-or-404 (get-in parameters [:path :id]))
        d (dbv ctx)
        p (or (db/product-by-id d id) (errors/fail! :notFound "Product not found."))
        q (:query parameters)
        page (logic/paginate (db/movements d (:eid p)) (:page q) (or (:page-size q) 25))
        by (users ctx (map :by-eid (:items page)))]
    {:status 200 :body (update page :items (fn [items] (mapv #(movement-view % (get by (:by-eid %))) items)))}))

;; ---- bulk publication ----------------------------------------------------------------------------

(defn publish-many
  "Handler for POST /api/admin/product-publications (staff). Body: {:productIds [...]}. Returns 200 {:results [{:productId :status \"published\"|\"rejected\" :errors?}]}; one product's failure does not stop the others."
  [{:keys [ctx parameters user]}]
  (let [results (mapv (fn [id]
                        (let [r (api/publish-product! ctx {:user-eid (:eid user)} (parse-uuid id))]
                          (cond-> {:product-id id :status (name (:status r))}
                            (seq (:errors r)) (assoc :errors (:errors r)))))
                      (get-in parameters [:body :product-ids]))]
    {:status 200 :body {:results results}}))

(def routes
  "Route data for the catalog slice. Public reads carry :cache headers; writes are staff-only, creates and stock movements are :idempotent?."
  [{:method :get :path "/api/categories" :name :catalog/categories :access :public :cache cache-categories
    :handler list-categories}
   {:method :get :path "/api/products" :name :catalog/products :access :public :cache cache-products
    :parameters {:query schema/PublicProductsQuery} :handler list-products}
   {:method :get :path "/api/products/:slug" :name :catalog/product :access :public :cache cache-products
    :handler get-product}
   {:method :get :path "/api/sitemap-entries" :name :catalog/sitemap :access :public :cache cache-sitemap
    :handler sitemap-entries}
   {:method :get :path "/api/admin/products" :name :catalog/admin-list :access :staff
    :parameters {:query schema/AdminProductsQuery} :handler admin-list}
   {:method :post :path "/api/admin/products" :name :catalog/admin-create :access :staff :idempotent? true
    :parameters {:body schema/CreateProduct} :handler admin-create}
   {:method :get :path "/api/admin/products/:id" :name :catalog/admin-get :access :staff :handler admin-get}
   {:method :patch :path "/api/admin/products/:id" :name :catalog/admin-patch :access :staff :keep-nils? true
    :parameters {:body schema/PatchProduct} :handler admin-patch}
   {:method :delete :path "/api/admin/products/:id" :name :catalog/admin-delete :access :staff :handler admin-delete}
   {:method :get :path "/api/admin/products/:id/stock-movements" :name :catalog/stock-history :access :staff
    :parameters {:query schema/PagingQuery} :handler stock-history}
   {:method :post :path "/api/admin/products/:id/stock-movements" :name :catalog/stock-adjust :access :staff
    :idempotent? true :parameters {:body schema/StockMovement} :handler stock-adjust}
   {:method :post :path "/api/admin/product-publications" :name :catalog/publish-many :access :staff
    :parameters {:body schema/Publications} :handler publish-many}])
