(ns zandy.features.catalog.logic
  "Pure catalogue rules: availability, public and admin views, search, sorting, publishing rules."
  (:require [clojure.string :as str]
            [zandy.platform.util :as util])
  (:import (java.text Normalizer Normalizer$Form)
           (java.util Date)))

(def new-product-days 30)
(def max-images 6)
(def day-ms (* 24 60 60 1000))

(defn slugify [s]
  (-> (Normalizer/normalize (str s) Normalizer$Form/NFD)
      (str/replace #"\p{M}" "")
      str/lower-case
      (str/replace #"&" " and ")
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"^-+|-+$" "")
      (as-> s (if (str/blank? s) "product" (subs s 0 (min 80 (count s)))))
      (str/replace #"-+$" "")))

(defn unique-slug
  "base, or base-2, base-3 ... the first not in `taken` (a set)."
  [base taken]
  (if-not (contains? taken base)
    base
    (first (remove taken (map #(str base "-" %) (iterate inc 2))))))

(defn normalize-sku [sku] (when sku (str/upper-case (str/trim sku))))

(defn availability [stock] (if (pos? (or stock 0)) :available :soldOut))

(defn max-quantity [stock max-per-line]
  (max 0 (min max-per-line (or stock 0))))

(defn new? [first-published-at ^Date now]
  (boolean (and first-published-at
                (< (- (.getTime now) (.getTime ^Date first-published-at)) (* new-product-days day-ms)))))

(defn low-stock? [stock threshold] (and (pos? stock) (<= stock threshold)))

(defn- sorted-images [images] (sort-by :position images))

(defn image-view [{:keys [photo-id alt]} product-name]
  {:id photo-id :alt (or alt product-name) :urls (util/media-urls photo-id)})

(defn first-image [{:keys [images name]}]
  (when-let [i (first (sorted-images images))] (image-view i name)))

(defn summary-view
  "The public list item."
  [{:keys [id slug name brand own-brand? category price short-description stock-quantity first-published-at featured?] :as p}
   {:keys [max-qty now]}]
  {:id id :slug slug :name name :brand brand :is-own-brand (boolean own-brand?)
   :category-slug (:slug category) :price price :currency "UGX"
   :short-description short-description
   :availability (availability stock-quantity)
   :max-quantity (max-quantity stock-quantity max-qty)
   :is-new (new? first-published-at now)
   :featured (boolean featured?)
   :image (first-image p)})

(defn detail-view [{:keys [id slug name brand own-brand? category price description short-description size how-to-use
                           ingredients images stock-quantity first-published-at updated-at]}
                   related {:keys [max-qty now]}]
  {:id id :slug slug :name name :brand brand :is-own-brand (boolean own-brand?)
   :category (select-keys category [:slug :name])
   :price price :currency "UGX" :availability (availability stock-quantity)
   :max-quantity (max-quantity stock-quantity max-qty) :is-new (new? first-published-at now)
   :short-description short-description :description description
   :size size :how-to-use how-to-use :ingredients ingredients
   :images (mapv #(image-view % name) (sorted-images images))
   :related related :updated-at updated-at})

;; ---- search, filter, sort, paginate ------------------------------------------------------------

(defn matches-text? [q {:keys [name brand short-description description]}]
  (let [q (str/lower-case q)]
    (boolean (some #(and % (str/includes? (str/lower-case %) q)) [name brand short-description description]))))

(defn filter-public [products {:keys [category-slug q availability own-brand featured]}]
  (cond->> products
    category-slug (filter #(= category-slug (get-in % [:category :slug])))
    q (filter #(matches-text? q %))
    (= availability "available") (filter #(pos? (:stock-quantity %)))
    (= availability "soldOut") (filter #(zero? (:stock-quantity %)))
    (true? own-brand) (filter :own-brand?)
    (true? featured) (filter :featured?)))

(defn sort-public
  "Sold-out products always sort after available ones; `sort` orders within each group."
  [products sort-key]
  (let [by (case sort-key
             "priceAsc" (fn [p] [(:price p) (str/lower-case (:name p))])
             "priceDesc" (fn [p] [(- (:price p)) (str/lower-case (:name p))])
             "nameAsc" (fn [p] [(str/lower-case (:name p))])
             (fn [p] [(- (or (some-> (:first-published-at p) .getTime) 0)) (str/lower-case (:name p))]))
        sold-out? #(if (pos? (:stock-quantity %)) 0 1)]
    (sort-by (fn [p] [(sold-out? p) (by p)]) products)))

(def paginate util/paginate)

(defn related-products [products product n]
  (->> products
       (filter #(and (not= (:id %) (:id product))
                     (= (get-in % [:category :slug]) (get-in product [:category :slug]))))
       (#(sort-public % "newest"))
       (take n)))

;; ---- admin views -------------------------------------------------------------------------------

(defn admin-summary [{:keys [id slug sku name brand own-brand? category price stock-quantity status featured? updated-at] :as p}
                     updated-by {:keys [low]}]
  {:id id :slug slug :sku sku :name name :brand brand :is-own-brand (boolean own-brand?)
   :category-slug (:slug category) :price price :currency "UGX"
   :stock-quantity stock-quantity :availability (availability stock-quantity)
   :low-stock (low-stock? stock-quantity low) :status status :featured (boolean featured?)
   :image (first-image p) :updated-at updated-at :updated-by updated-by})

(defn admin-detail [{:keys [id slug sku name brand own-brand? category price short-description description size how-to-use
                            ingredients featured? status version stock-quantity images created-at updated-at] :as _p}
                    updated-by {:keys [low]}]
  {:id id :slug slug :sku sku :name name :brand brand :is-own-brand (boolean own-brand?)
   :category-slug (:slug category) :price price :currency "UGX"
   :short-description short-description :description description :size size :how-to-use how-to-use
   :ingredients ingredients :featured (boolean featured?) :status status :version version
   :stock-quantity stock-quantity :availability (availability stock-quantity)
   :low-stock (low-stock? stock-quantity low)
   :images (mapv #(image-view % name) (sorted-images images))
   :created-at created-at :updated-at updated-at :updated-by updated-by})

(defn filter-admin [products {:keys [status category-slug q stock]} low]
  (let [q (some-> q str/lower-case)]
    (cond->> products
      true (filter (fn [p] (if status (= (keyword status) (:status p)) (not= :archived (:status p)))))
      category-slug (filter #(= category-slug (get-in % [:category :slug])))
      q (filter (fn [p] (some #(and % (str/includes? (str/lower-case %) q)) [(:name p) (:brand p) (:sku p)])))
      (= stock "out") (filter #(zero? (:stock-quantity %)))
      (= stock "low") (filter #(low-stock? (:stock-quantity %) low)))))

(defn sort-admin [products sort-key]
  (case sort-key
    "nameAsc" (sort-by #(str/lower-case (:name %)) products)
    "stockAsc" (sort-by (juxt :stock-quantity #(str/lower-case (:name %))) products)
    (sort-by #(- (.getTime ^Date (:updated-at %))) products)))

(defn admin-counts [products low]
  (let [published (filter #(= :published (:status %)) products)]
    {:out (count (filter #(zero? (:stock-quantity %)) published))
     :low (count (filter #(low-stock? (:stock-quantity %) low) published))}))

;; ---- publishing rule ----------------------------------------------------------------------------

(defn publish-problems
  "Detail entries for a product that is (or is becoming) published but lacks price, image or category."
  [{:keys [status price images category]}]
  (when (= :published status)
    (cond-> []
      (nil? price) (conj {:field "price" :code "priceRequired" :message "A product needs a price before it can be published."})
      (empty? images) (conj {:field "images" :code "imageRequired" :message "A product needs at least one image before it can be published."})
      (nil? category) (conj {:field "categorySlug" :code "invalid" :message "Choose a category."}))))
