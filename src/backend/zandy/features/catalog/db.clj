(ns zandy.features.catalog.db
  "Catalogue persistence: categories, products, product pictures, stock movements."
  (:require [datomic.api :as d]
            [zandy.platform.datomic :as datomic]
            [zandy.platform.util :as util]))

(def product-pattern
  [:db/id :product/id :product/slug :product/sku :product/name :product/brand :product/own-brand?
   :product/price :product/short-description :product/description :product/size :product/how-to-use
   :product/ingredients :product/featured? :product/status :product/first-published-at
   :product/stock-quantity :product/version :product/created-at :product/updated-at
   {:product/updated-by [:db/id]}
   {:product/category [:category/slug :category/name]}
   {:product/images [:db/id :product-image/id :product-image/position :product-image/alt
                     {:product-image/photo [:db/id :photo/id]}]}])

(defn- product-map [m]
  (when (:product/id m)
    {:eid (:db/id m) :id (:product/id m) :slug (:product/slug m) :sku (:product/sku m)
     :name (:product/name m) :brand (:product/brand m) :own-brand? (boolean (:product/own-brand? m))
     :price (:product/price m) :short-description (:product/short-description m)
     :description (:product/description m) :size (:product/size m) :how-to-use (:product/how-to-use m)
     :ingredients (:product/ingredients m) :featured? (boolean (:product/featured? m))
     :status (:product/status m) :first-published-at (:product/first-published-at m)
     :stock-quantity (or (:product/stock-quantity m) 0) :version (or (:product/version m) 1)
     :created-at (:product/created-at m) :updated-at (:product/updated-at m)
     :updated-by-eid (get-in m [:product/updated-by :db/id])
     :category (when-let [c (:product/category m)] {:slug (:category/slug c) :name (:category/name c)})
     :images (mapv (fn [i] {:eid (:db/id i) :id (:product-image/id i) :position (:product-image/position i)
                            :alt (:product-image/alt i)
                            :photo-id (get-in i [:product-image/photo :photo/id])
                            :photo-eid (get-in i [:product-image/photo :db/id])})
                   (:product/images m))}))

(defn all-products [db]
  (->> (d/q '[:find [?e ...] :where [?e :product/id]] db)
       (d/pull-many db product-pattern)
       (keep product-map)
       vec))

(defn published-products [db]
  (filterv #(= :published (:status %)) (all-products db)))

(defn product-by-id [db id]
  (product-map (d/pull db product-pattern [:product/id id])))

(defn product-by-slug [db slug]
  (product-map (d/pull db product-pattern [:product/slug slug])))

(defn product-by-sku [db sku]
  (product-map (d/pull db product-pattern [:product/sku sku])))

(defn products-by-ids [db ids]
  (into {} (keep (fn [id] (when-let [p (product-by-id db id)] [id p]))) ids))

;; ---- categories ----------------------------------------------------------------------------------

(defn categories [db]
  (->> (d/q '[:find [(pull ?e [:category/id :category/slug :category/name :category/description :category/sort-order]) ...]
              :where [?e :category/id]] db)
       (sort-by :category/sort-order)
       (mapv (fn [c] {:id (:category/id c) :slug (:category/slug c) :name (:category/name c)
                      :description (:category/description c) :sort-order (:category/sort-order c)}))))

(defn category-eid [db slug] (d/entid db [:category/slug slug]))

;; ---- writes ----------------------------------------------------------------------------------------

(defn create-product-tx
  "tx-data for a new product with its opening stock movement."
  [{:keys [id slug sku name brand own-brand? category-eid price short-description description size how-to-use
           ingredients featured? status stock-quantity user-eid now movement-id]}]
  (let [pid "new-product"]
    (cond-> [(util/compact
              {:db/id pid :product/id id :product/slug slug :product/sku sku :product/name name :product/brand brand
               :product/own-brand? (boolean own-brand?) :product/category category-eid :product/price price
               :product/short-description short-description :product/description description :product/size size
               :product/how-to-use how-to-use :product/ingredients ingredients :product/featured? (boolean featured?)
               :product/status status :product/stock-quantity (or stock-quantity 0) :product/version 1
               :product/created-at now :product/updated-at now :product/updated-by user-eid
               :product/first-published-at (when (= :published status) now)})]
      (pos? (or stock-quantity 0))
      (conj (util/compact
             {:stock-movement/id movement-id :stock-movement/type :initial :stock-movement/delta stock-quantity
              :stock-movement/quantity-after stock-quantity :stock-movement/at now
              :stock-movement/product pid :stock-movement/by user-eid})))))

(def ^:private change-attrs
  "Product write fields: change key -> datomic attribute."
  {:name :product/name :brand :product/brand :own-brand? :product/own-brand? :category-eid :product/category
   :sku :product/sku :short-description :product/short-description :description :product/description
   :size :product/size :how-to-use :product/how-to-use :ingredients :product/ingredients
   :featured? :product/featured? :status :product/status :price :product/price})

(defn update-product-tx
  "tx-data for a partial update guarded by the version (:db/cas). A nil value clears the attribute.
  Images are replaced separately with replace-images-tx."
  [existing changes {:keys [user-eid now]}]
  (let [pid (:eid existing)
        old-values {:sku (:sku existing) :price (:price existing) :size (:size existing)
                    :short-description (:short-description existing) :description (:description existing)
                    :how-to-use (:how-to-use existing) :ingredients (:ingredients existing)}]
    (vec
     (concat
      [[:db/cas pid :product/version (:version existing) (inc (:version existing))]
       [:db/add pid :product/updated-at now]]
      (when user-eid [[:db/add pid :product/updated-by user-eid]])
      (mapcat (fn [[k attr]]
                (when (contains? changes k)
                  (let [v (get changes k)]
                    (if (nil? v)
                      (when-let [old (get old-values k)] [[:db/retract pid attr old]])
                      [[:db/add pid attr v]]))))
              change-attrs)
      (when (and (= :published (:status changes)) (nil? (:first-published-at existing)))
        [[:db/add pid :product/first-published-at now]])))))

(defn reorder-images-tx
  "tx-data that makes the product's picture list equal `kept` (a vector of {:image <existing image> :alt alt},
  in the new order). Pictures not kept are removed; retracting the product-image also retracts its photo
  record (the :product-image/photo reference is a component)."
  [existing kept removed]
  (let [pid (:eid existing)]
    (vec (concat
          (mapcat (fn [i] [[:db/retract pid :product/images (:eid i)] [:db/retractEntity (:eid i)]]) removed)
          (mapcat (fn [idx {:keys [image alt]}]
                    (concat [[:db/add (:eid image) :product-image/position idx]]
                            (cond alt [[:db/add (:eid image) :product-image/alt alt]]
                                  (:alt image) [[:db/retract (:eid image) :product-image/alt (:alt image)]])))
                  (range) kept)))))

(defn attach-picture-tx
  "tx-data that stores a new picture on the product at `index` (0-based), replacing `replaces` when given.
  Guarded by the product version."
  [product {:keys [photo-id filename content-type width height index replaces user-eid now seed?]}]
  (let [pid (:eid product)]
    (vec (concat
          [[:db/cas pid :product/version (:version product) (inc (:version product))]
           [:db/add pid :product/updated-at now]]
          (when user-eid [[:db/add pid :product/updated-by user-eid]])
          (when replaces [[:db/retract pid :product/images (:eid replaces)] [:db/retractEntity (:eid replaces)]])
          [{:db/id pid
            :product/images
            [{:product-image/id (random-uuid)
              :product-image/position index
              :product-image/photo (util/compact
                                    {:photo/id photo-id :photo/original-filename filename :photo/content-type content-type
                                     :photo/width width :photo/height height :photo/uploaded-at now
                                     :photo/seed? (when seed? true) :photo/uploaded-by user-eid})}]}]))))

(defn archive-tx [existing {:keys [user-eid now]}]
  (let [pid (:eid existing)]
    (vec (concat [[:db/cas pid :product/version (:version existing) (inc (:version existing))]
                  [:db/add pid :product/status :archived]
                  [:db/add pid :product/updated-at now]]
                 (when user-eid [[:db/add pid :product/updated-by user-eid]])))))

(defn transact! [conn tx] (datomic/transact! conn tx))

;; ---- stock movements --------------------------------------------------------------------------------

(defn movements [db product-eid]
  (->> (d/q '[:find [(pull ?m [:stock-movement/id :stock-movement/type :stock-movement/reason :stock-movement/delta
                               :stock-movement/quantity-after :stock-movement/note :stock-movement/order-number
                               :stock-movement/at {:stock-movement/by [:db/id]}]) ...]
              :in $ ?p :where [?m :stock-movement/product ?p]] db product-eid)
       (sort-by :stock-movement/at #(compare %2 %1))
       (mapv (fn [m] {:id (:stock-movement/id m) :type (:stock-movement/type m) :reason (:stock-movement/reason m)
                      :delta (:stock-movement/delta m) :quantity-after (:stock-movement/quantity-after m)
                      :note (:stock-movement/note m) :order-number (:stock-movement/order-number m)
                      :at (:stock-movement/at m) :by-eid (get-in m [:stock-movement/by :db/id])}))))

(defn movement-by-id [db id]
  (let [m (d/pull db [:stock-movement/id :stock-movement/type :stock-movement/reason :stock-movement/delta
                      :stock-movement/quantity-after :stock-movement/note :stock-movement/order-number
                      :stock-movement/at {:stock-movement/by [:db/id]}]
                  [:stock-movement/id id])]
    {:id (:stock-movement/id m) :type (:stock-movement/type m) :reason (:stock-movement/reason m)
     :delta (:stock-movement/delta m) :quantity-after (:stock-movement/quantity-after m)
     :note (:stock-movement/note m) :order-number (:stock-movement/order-number m)
     :at (:stock-movement/at m) :by-eid (get-in m [:stock-movement/by :db/id])}))

(defn current-db [conn] (d/db conn))
