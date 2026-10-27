(ns zandy.features.catalog.logic
  "Pure catalogue rules: availability, public and admin views, search, sorting, publishing rules."
  (:require [clojure.string :as str]
            [zandy.platform.util :as util])
  (:import (java.text Normalizer Normalizer$Form)
           (java.util Date)))

(def new-product-days
  "A product counts as \"new\" for this many days after it was first published."
  30)
(def max-images
  "Maximum pictures per product (the slots are numbered 1 to this)."
  6)
(def day-ms
  "Milliseconds in a day."
  (* 24 60 60 1000))

(defn slugify
  "URL slug of `s`: accents removed, lower case, \"&\" as \"and\", other runs of non-alphanumerics become \"-\", at most 80 characters; \"product\" when nothing is left. Pure."
  [s]
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

(defn normalize-sku
  "Trimmed upper-case SKU, or nil for nil. SKUs are compared and stored upper-case, so \"ab-1\" and \"AB-1\" are the same."
  [sku] (when sku (str/upper-case (str/trim sku))))

(defn availability
  "Public availability from a stock count: :available when stock is positive (nil counts as 0), otherwise :soldOut."
  [stock] (if (pos? (or stock 0)) :available :soldOut))

(defn max-quantity
  "How many units one cart line may hold: min(max-per-line, stock), never below 0. Nil stock counts as 0."
  [stock max-per-line]
  (max 0 (min max-per-line (or stock 0))))

(defn new?
  "True when `first-published-at` (instant or nil) is less than new-product-days before `now` (a java.util.Date)."
  [first-published-at ^Date now]
  (boolean (and first-published-at
                (< (- (.getTime now) (.getTime ^Date first-published-at)) (* new-product-days day-ms)))))

(defn low-stock?
  "True when stock is above zero but at or below `threshold` (a sold-out product is not \"low\")."
  [stock threshold] (and (pos? stock) (<= stock threshold)))

;; Pictures are ordered by their slot (:position, 0-based).
(defn- sorted-images [images] (sort-by :position images))

(defn image-view
  "Public view of a product picture entry {:photo-id :alt}: {:id :alt :urls}. A missing alt falls back to `product-name`."
  [{:keys [photo-id alt]} product-name]
  {:id photo-id :alt (or alt product-name) :urls (util/media-urls photo-id)})

;; Staff views return the stored alt as is, nil when none is set. The public view substitutes the product name, but staff must see that the field is really empty so they can fill it in.
(defn admin-image-view
  "Staff view of a picture: the stored alt text, nil when none is set (no fallback to the product name)."
  [{:keys [photo-id alt]}]
  {:id photo-id :alt alt :urls (util/media-urls photo-id)})

(defn first-image
  "Public image view of the product's first picture (lowest :position), or nil. Product map needs :images and :name."
  [{:keys [images name]}]
  (when-let [i (first (sorted-images images))] (image-view i name)))

(defn summary-view
  "Public list item (contract 3) from a product map and opts {:max-qty per-line maximum, :now java.util.Date}.
  Returns {:id :slug :name :brand :is-own-brand :category-slug :price :currency \"UGX\" :short-description :availability :max-quantity :is-new :featured :image}. Pure."
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

(defn detail-view
  "Contract 3 product detail body from a product map, the already-built `related` summaries and opts {:max-qty :now}. Includes all pictures in position order. Pure."
  [{:keys [id slug name brand own-brand? category price description short-description size how-to-use
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

(defn matches-text?
  "True when lower-cased text `q` occurs in the product's name, brand, short description or description (case-insensitive substring)."
  [q {:keys [name brand short-description description]}]
  (let [q (str/lower-case q)]
    (boolean (some #(and % (str/includes? (str/lower-case %) q)) [name brand short-description description]))))

(defn filter-public
  "Filters a sequence of product maps by the public query keys :category-slug :q :availability (\"available\" or \"soldOut\") :own-brand :featured (true only filters). Pure."
  [products {:keys [category-slug q availability own-brand featured]}]
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
        ;; Sold-out products always come last, whatever the chosen sort.
        sold-out? #(if (pos? (:stock-quantity %)) 0 1)]
    (sort-by (fn [p] [(sold-out? p) (by p)]) products)))

(def paginate
  "Alias of util/paginate: (items page page-size) to {:items :page :page-size :total-items}."
  util/paginate)

(defn related-products
  "Up to `n` other products from the same category as `product` (a product map), newest first, available before sold out."
  [products product n]
  (->> products
       (filter #(and (not= (:id %) (:id product))
                     (= (get-in % [:category :slug]) (get-in product [:category :slug]))))
       (#(sort-public % "newest"))
       (take n)))

;; ---- admin views -------------------------------------------------------------------------------

(defn admin-summary
  "Staff list item from a product map, the updated-by user summary (or nil) and opts {:low threshold}. Includes the SKU, stock, low-stock flag, status and the first picture with its real alt (nil when unset). Pure."
  [{:keys [id slug sku name brand own-brand? category price stock-quantity status featured? updated-at] :as p}
                     updated-by {:keys [low]}]
  {:id id :slug slug :sku sku :name name :brand brand :is-own-brand (boolean own-brand?)
   :category-slug (:slug category) :price price :currency "UGX"
   :stock-quantity stock-quantity :availability (availability stock-quantity)
   :low-stock (low-stock? stock-quantity low) :status status :featured (boolean featured?)
   :image (some-> (first (sorted-images (:images p))) admin-image-view) :updated-at updated-at :updated-by updated-by})

(defn admin-detail
  "Staff detail body from a product map, the updated-by summary and opts {:low}. Includes version, all pictures in position order with stored alt (nil when unset), created-at and updated-at. Pure."
  [{:keys [id slug sku name brand own-brand? category price short-description description size how-to-use
                            ingredients featured? status version stock-quantity images created-at updated-at] :as _p}
                    updated-by {:keys [low]}]
  {:id id :slug slug :sku sku :name name :brand brand :is-own-brand (boolean own-brand?)
   :category-slug (:slug category) :price price :currency "UGX"
   :short-description short-description :description description :size size :how-to-use how-to-use
   :ingredients ingredients :featured (boolean featured?) :status status :version version
   :stock-quantity stock-quantity :availability (availability stock-quantity)
   :low-stock (low-stock? stock-quantity low)
   :images (mapv admin-image-view (sorted-images images))
   :created-at created-at :updated-at updated-at :updated-by updated-by})

(defn filter-admin
  "Admin product list filter. The `stock` tabs (out, low) list published products only, like
  `admin-counts`, unless an explicit `status` is given, so a tab's count matches its rows."
  [products {:keys [status category-slug q stock]} low]
  (let [q (some-> q str/lower-case)
        status (or status (when (#{"out" "low"} stock) "published"))]
    (cond->> products
      true (filter (fn [p] (if status (= (keyword status) (:status p)) (not= :archived (:status p)))))
      category-slug (filter #(= category-slug (get-in % [:category :slug])))
      q (filter (fn [p] (some #(and % (str/includes? (str/lower-case %) q)) [(:name p) (:brand p) (:sku p)])))
      (= stock "out") (filter #(zero? (:stock-quantity %)))
      (= stock "low") (filter #(low-stock? (:stock-quantity %) low)))))

(defn sort-admin
  "Sorts staff products by \"nameAsc\", \"stockAsc\" (lowest stock first) or, by default, most recently updated first."
  [products sort-key]
  (case sort-key
    "nameAsc" (sort-by #(str/lower-case (:name %)) products)
    "stockAsc" (sort-by (juxt :stock-quantity #(str/lower-case (:name %))) products)
    (sort-by #(- (.getTime ^Date (:updated-at %))) products)))

(defn admin-counts
  "Counts of published products that are out of stock and low on stock: {:out n :low n} (shown as tab badges in the admin list)."
  [products low]
  (let [published (filter #(= :published (:status %)) products)]
    {:out (count (filter #(zero? (:stock-quantity %)) published))
     :low (count (filter #(low-stock? (:stock-quantity %) low) published))}))

;; ---- publishing rule ----------------------------------------------------------------------------

(defn publish-problems
  "Validation details [{:field :code :message}] when a product that is (or is becoming) :published lacks a price (priceRequired), a picture (imageRequired) or a category (invalid on categorySlug).
  Argument: a map with :status (keyword) :price :images :category. Returns nil for products that are not published, [] when all is well."
  [{:keys [status price images category]}]
  (when (= :published status)
    (cond-> []
      (nil? price) (conj {:field "price" :code "priceRequired" :message "A product needs a price before it can be published."})
      (empty? images) (conj {:field "images" :code "imageRequired" :message "A product needs at least one image before it can be published."})
      (nil? category) (conj {:field "categorySlug" :code "invalid" :message "Choose a category."}))))

;; ---- product pictures --------------------------------------------------------------------------

(defn plan-slot
  "Where a new picture goes. `images` are the product's pictures (each with :position, 0-based).
  `position` is the 1-based slot the file asked for, or nil to append. A positioned picture replaces
  whatever is in that slot (also when the product already has the maximum). An appended picture goes after
  the last one, or into the first free slot when the last slot is taken. Returns
  {:index n :replaces <image or nil>} or {:error :tooManyImages}."
  [images position]
  (let [by-pos (into {} (map (juxt :position identity)) images)]
    (cond
      position {:index (dec position) :replaces (get by-pos (dec position))}
      (>= (count images) max-images) {:error :tooManyImages}
      :else (let [nxt (inc (reduce max -1 (keys by-pos)))]
              {:index (if (< nxt max-images) nxt (first (remove by-pos (range max-images))))
               :replaces nil}))))

(defn reconcile-images
  "Matches the `images` list of a product write ({:id :alt} entries) against the product's existing
  pictures. Returns {:kept [{:image existing :alt alt}] :removed [existing ...] :problems [...]}.
  Pictures can only be reordered, re-labelled or removed: an id that is not on this product, or listed
  twice, is a problem."
  [existing entries]
  (let [by-photo (into {} (map (juxt #(some-> (:photo-id %) str str/lower-case) identity)) existing)
        step (fn [[acc seen probs] [i {:keys [id alt]}]]
               (let [k (some-> id str str/lower-case)
                     image (get by-photo k)]
                 (cond
                   (nil? image) [acc seen (conj probs {:field (str "images[" i "].id") :code "unknownImage"
                                                       :message "This picture is not on this product."})]
                   (contains? seen k) [acc seen (conj probs {:field (str "images[" i "].id") :code "duplicateImage"
                                                             :message "This picture is listed twice."})]
                   :else [(conj acc {:image image :alt alt}) (conj seen k) probs])))
        [kept seen problems] (reduce step [[] #{} []] (map-indexed vector entries))]
    {:kept kept
     :removed (vec (remove #(contains? seen (some-> (:photo-id %) str str/lower-case)) existing))
     :problems problems}))
