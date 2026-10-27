(ns zandy.ssr.stub-api
  "Development stub of the backend API (not shipped; lives under ssr/dev). JVM
  only. It is shaped like api-contract.md so the frontend server and the
  browser app can be built and click-tested without the real backend:
  in-memory and stateful (orders reduce stock, Idempotency-Key replays work, a
  staff session cookie works, product import and picture upload behave like
  the contract). It is not a model of the real business rules and is never
  used in tests of the backend. Run: cd ssr && clojure -M:stub [port] (default
  8080)."
  (:require [clojure.string :as str]
            [jsonista.core :as json])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetSocketAddress URLDecoder)
           (java.nio.charset StandardCharsets)
           (java.time Instant)))

(def mapper
  "JSON object mapper that reads keys as keywords."
  (json/object-mapper {:decode-key-fn keyword}))
(defn jread
  "Parses the JSON text `s` into a keyword-keyed map; blank text gives nil.
  Throws on invalid JSON."
  [s] (when-not (str/blank? s) (json/read-value s mapper)))
(defn jwrite
  "Serialises `x` to a JSON string."
  [x] (json/write-value-as-string x))
(defn now
  "Current time as an ISO-8601 UTC string truncated to whole seconds."
  [] (str (.truncatedTo (Instant/now) java.time.temporal.ChronoUnit/SECONDS)))

(def categories
  "The six fixed categories in the shape of GET /api/categories items."
  [{:id "c1" :slug "skincare" :name "Skincare" :description "Cleansers, serums and creams for everyday skin care." :sortOrder 1}
   {:id "c2" :slug "perfumes" :name "Perfumes" :description "Eau de parfum and body mists for every occasion." :sortOrder 2}
   {:id "c3" :slug "body-shaping" :name "Body-shaping" :description "Shapewear and body contouring care." :sortOrder 3}
   {:id "c4" :slug "body-care" :name "Body care" :description "Lotions, scrubs and oils for soft skin." :sortOrder 4}
   {:id "c5" :slug "weight-loss" :name "Weight loss" :description "Teas, drink mixes and tools for your wellness routine." :sortOrder 5}
   {:id "c6" :slug "health-and-wellness" :name "Health & Wellness" :description "Supplements and nutrition for daily wellbeing." :sortOrder 6}])

(def seed
  "Seed products as [sku name category-slug own-brand? price stock size].
  Includes a sold-out item (SK-04) and a low-stock item (SK-03) so the
  sold-out and limited-stock states can be clicked through."
  [["SK-01" "Radiance Vitamin C Serum 30 ml" "skincare" true 85000 24 "30 ml"]
   ["SK-02" "Gentle Foaming Face Cleanser 150 ml" "skincare" false 38000 40 "150 ml"]
   ["SK-03" "Daily Moisturising Cream 50 ml" "skincare" true 55000 3 "50 ml"]
   ["SK-04" "Mineral Sunscreen SPF 50, 60 ml" "skincare" false 62000 0 "60 ml"]
   ["PF-01" "Zandy Touch Signature Eau de Parfum 50 ml" "perfumes" true 150000 12 "50 ml"]
   ["PF-02" "Oud Evening Eau de Parfum 100 ml" "perfumes" false 220000 6 "100 ml"]
   ["PF-03" "Fresh Citrus Body Mist 100 ml" "perfumes" false 45000 30 "100 ml"]
   ["BS-01" "Waist Shaping Belt" "body-shaping" false 65000 15 nil]
   ["BS-02" "Firming Body Contour Cream 200 ml" "body-shaping" true 70000 18 "200 ml"]
   ["BS-03" "Seamless Shapewear Bodysuit" "body-shaping" false 95000 8 nil]
   ["BC-01" "Shea Butter Body Lotion 400 ml" "body-care" true 42000 35 "400 ml"]
   ["BC-02" "Coffee Body Scrub 250 g" "body-care" true 35000 20 "250 g"]
   ["BC-03" "Nourishing Body Oil 100 ml" "body-care" false 48000 14 "100 ml"]
   ["BC-04" "Moisturising Hand Cream 75 ml" "body-care" false 18000 50 "75 ml"]
   ["WL-01" "Herbal Slimming Tea, 30 bags" "weight-loss" false 30000 25 nil]
   ["WL-02" "Fibre Drink Mix, 14 sachets" "weight-loss" false 45000 12 nil]
   ["WL-03" "Kitchen Scale and Body Tape Set" "weight-loss" false 28000 22 nil]
   ["HW-01" "Zandy Touch Weight-Gain Porridge 500 g" "health-and-wellness" true 40000 30 "500 g"]
   ["HW-02" "Vitamin D3 Dietary Supplement, 60 capsules" "health-and-wellness" false 52000 22 nil]
   ["HW-03" "Daily Multivitamin, 90 tablets" "health-and-wellness" false 68000 16 nil]
   ["HW-04" "Omega-3 Fish Oil Dietary Supplement, 60 softgels" "health-and-wellness" false 75000 9 nil]
   ["HW-05" "Moringa Leaf Powder 200 g" "health-and-wellness" true 25000 28 "200 g"]
   ["EX-01" "Extra Sample Item One" "body-care" false 10000 10 nil]
   ["EX-02" "Extra Sample Item Two" "body-care" false 11000 10 nil]
   ["EX-03" "Extra Sample Item Three" "skincare" false 12000 10 nil]
   ["EX-04" "Extra Sample Item Four" "skincare" false 13000 10 nil]
   ["EX-05" "Extra Sample Item Five" "perfumes" false 14000 10 nil]])

(defn slugify
  "URL slug for the text `s`: lower case, runs of other characters become \"-\"."
  [s] (-> s str/lower-case (str/replace #"[^a-z0-9]+" "-") (str/replace #"^-|-$" "")))

(defonce ^{:doc "The stub's whole in-memory state in one atom: :products (with
  :stock, :status, :photos, :version), :orders, :idem (idempotency replay table
  keyed by [scope key]), :sessions, :movements, :messages, :subscribers, :staff
  and :policies. defonce, so a REPL reload keeps the data."}
  db
  (atom {:products (vec (map-indexed
                         (fn [i [sku nm cat own? price stock size]]
                           {:id (str "p" (inc i)) :slug (slugify nm) :sku (str "SEED-" sku) :name nm :brand (if own? "Zandy Touch" "Sample Brand")
                            :isOwnBrand own? :categorySlug cat :price price :stock stock :size size :status "published"
                            :featured (zero? (mod i 5)) :version 1 :createdAt (now) :updatedAt (now)
                            :photos [{:id (str "ph" (inc i)) :alt nil}]})
                         seed))
         :orders []
         :idem {}
         :sessions #{}
         :movements []
         :messages []
         :subscribers []
         :staff [{:id "u1" :name "Sandra" :email "owner@example.test" :role "owner" :active true :notifyOnNewOrder true :createdAt (now) :lastSignInAt nil}]
         :policies {}}))

(def policy-text
  "Title and summary lines of the four policy documents served by the stub."
  {"orderTerms" ["Order terms" ["Prices shown are product prices in UGX and exclude delivery." "The delivery fee is agreed with you before dispatch." "You may cancel before dispatch." "You pay on delivery in cash or mobile money."]]
   "termsOfSale" ["Terms of sale" []]
   "privacy" ["Privacy policy" ["We keep your contact details for 6 months."]]
   "returns" ["Returns and refunds" []]})

(def approved?
  "Atom: when true the stub reports its policies as reviewStatus \"approved\",
  otherwise \"placeholder\". Lets the launch gate (ssr.launch-gate) be exercised
  from a REPL."
  (atom false))

(defn policy
  "GET /api/policies/{type} body for `type` (\"orderTerms\", \"termsOfSale\",
  \"privacy\" or \"returns\"), or nil for an unknown type. The version is fixed
  (\"2026-10-01\"); place-order rejects any other agreement version."
  [type]
  (when-let [[title summary] (get policy-text type)]
    {:type type :version "2026-10-01" :title title :reviewStatus (if @approved? "approved" "placeholder")
     :summary summary
     :bodyBlocks [{:type "heading" :text "1. Overview"}
                  {:type "paragraph" :text (str "This is draft text for the " title ". It is a placeholder until reviewed.")}
                  {:type "list" :items ["First point" "Second point"]}]
     :contentHash "sha256:stub" :effectiveFrom "2026-10-01T00:00:00Z"}))

(defn image-of
  "API image object {:id :alt :urls {:thumb :medium :large}} for a stored photo
  `ph`; the URLs point at the stub's own /media/ placeholder SVGs."
  [ph]
  {:id (:id ph) :alt (:alt ph)
   :urls {:thumb (str "/media/" (:id ph) "-thumb.webp") :medium (str "/media/" (:id ph) "-medium.webp") :large (str "/media/" (:id ph) "-large.webp")}})

(defn img
  "First image of product `p` as an API image, or nil when it has no photo."
  [p] (some-> (first (:photos p)) image-of))
(defn images-of
  "All images of product `p` as a vector of API images."
  [p] (mapv image-of (:photos p)))

(defn avail
  "\"available\" when product `p` has stock, otherwise \"soldOut\"."
  [p] (if (pos? (:stock p)) "available" "soldOut"))
(defn max-q
  "Largest quantity a customer may order of `p`: the stock, at most 10."
  [p] (min 10 (:stock p)))

(defn summary
  "Product summary in the shape of the GET /api/products items for stored
  product `p`. The stub always reports isNew true."
  [p]
  {:id (:id p) :slug (:slug p) :name (:name p) :brand (:brand p) :isOwnBrand (:isOwnBrand p)
   :categorySlug (:categorySlug p) :price (:price p) :currency "UGX"
   :shortDescription (str "A " (str/lower-case (:name p)) " for everyday use.")
   :availability (avail p) :maxQuantity (max-q p) :isNew true :featured (:featured p) :image (img p)})

(defn published
  "Lazy seq of the stored products whose status is \"published\"."
  [] (filter #(= "published" (:status %)) (:products @db)))

(defn paged
  "Page of `items`. Arguments: `items`, a map of string query parameters (uses
  \"page\" and \"pageSize\") and the default page size. Returns {:items :page
  :pageSize :totalItems}."
  [items {:strs [page pageSize]} default-size]
  (let [page (or (some-> page parse-long) 1) size (or (some-> pageSize parse-long) default-size)]
    {:items (vec (take size (drop (* (dec page) size) items))) :page page :pageSize size :totalItems (count items)}))

(defn list-products
  "GET /api/products. Argument: `q`, the map of string query parameters (q,
  categorySlug, availability, ownBrand, featured, sort, page, pageSize).
  Returns the paged list of summaries; sold-out products are always sorted
  last, as in the contract."
  [q]
  (let [needle (some-> (q "q") str/lower-case)
        items (cond->> (published)
                (q "categorySlug") (filter #(= (q "categorySlug") (:categorySlug %)))
                needle (filter #(or (str/includes? (str/lower-case (:name %)) needle) (str/includes? (str/lower-case (:brand %)) needle)))
                (q "availability") (filter #(= (q "availability") (avail %)))
                (= "true" (q "ownBrand")) (filter :isOwnBrand)
                (= "true" (q "featured")) (filter :featured))
        sorted (case (q "sort")
                 "priceAsc" (sort-by :price items) "priceDesc" (sort-by (comp - :price) items)
                 "nameAsc" (sort-by :name items) items)
        sorted (sort-by #(if (pos? (:stock %)) 0 1) sorted)]
    (update (paged sorted q 24) :items #(mapv summary %))))

(defn detail
  "GET /api/products/{slug} body for stored product `p`: the summary plus
  category, description, images and up to four related products."
  [p]
  (let [c (some #(when (= (:categorySlug p) (:slug %)) %) categories)]
    (merge (dissoc (summary p) :categorySlug :image :shortDescription :featured)
           {:category {:slug (:slug c) :name (:name c)}
            :shortDescription (str "A " (str/lower-case (:name p)) " for everyday use.")
            :description (str "About the " (:name p) ".\n\nUse as directed. This is stub text for development.")
            :size (:size p) :howToUse "Apply a small amount daily." :ingredients nil
            :images (images-of p)
            :related (vec (map summary (take 4 (remove #(= (:id p) (:id %)) (filter #(= (:categorySlug p) (:categorySlug %)) (published))))))
            :updatedAt (:updatedAt p)})))

(defn err
  "Builds an error response [status {:error {:code :message :details}}] in the
  contract's error shape. `details` (optional) is a vector of {:field :code
  :message}."
  [status code msg & [details]]
  [status {:error (cond-> {:code code :message msg} details (assoc :details details))}])

(defn order-total
  "Sum of the :lineTotal of order `lines`."
  [lines] (reduce + (map :lineTotal lines)))

(defn order-view
  "Order as the API returns it: adds :itemsSubtotal, :currency, the delivery
  fee note and :total (nil until a delivery fee is set)."
  [o]
  (let [sub (order-total (:items o))]
    (assoc o :itemsSubtotal sub :currency "UGX" :deliveryFeeNote "Agreed with you before dispatch"
           :total (when (some? (:deliveryFee o)) (+ sub (:deliveryFee o))))))

(defn place-order
  "POST /api/orders. Arguments: the parsed JSON `body` ({:customer :delivery
  :paymentMethod :items :agreement}) and the idempotency key (unused here;
  replay is handled by the caller). Returns [201 {:order
  :agreementAcceptance}] and reduces stock, or an error: 409
  agreementVersionOutdated, 422 validationFailed (phone), 409 cartOutdated
  with per-line problems (unavailable, priceChanged, soldOut,
  insufficientStock). Side effect: updates the db atom."
  [body _idem-key]
  (let [by-id (into {} (map (juxt :id identity)) (:products @db))
        problems (vec (keep (fn [{:keys [productId quantity expectedUnitPrice]}]
                              (let [p (by-id productId)]
                                (cond
                                  (or (nil? p) (not= "published" (:status p))) {:productId productId :code "unavailable" :message "No longer available."}
                                  (not= (:price p) expectedUnitPrice) {:productId productId :code "priceChanged" :message "The price changed." :currentPrice (:price p)}
                                  (zero? (:stock p)) {:productId productId :code "soldOut" :message "Sold out."}
                                  (< (:stock p) quantity) {:productId productId :code "insufficientStock" :message "Not enough stock." :availableQuantity (:stock p)})))
                            (:items body)))]
    (cond
      (not= "2026-10-01" (get-in body [:agreement :version]))
      (err 409 "agreementVersionOutdated" "The order terms have changed." [{:field "agreement.version" :code "currentVersion" :message "2026-10-01"}])
      (not (re-matches #"(\+256|0)7\d{8}" (str/replace (or (get-in body [:customer :phone]) "") " " "")))
      (err 422 "validationFailed" "Please check the highlighted fields." [{:field "customer.phone" :code "invalidPhone" :message "Enter a valid Uganda phone number."}])
      (seq problems) (err 409 "cartOutdated" "Some items in your cart have changed." problems)
      :else
      (let [lines (mapv (fn [{:keys [productId quantity]}]
                          (let [p (by-id productId)]
                            {:productId productId :name (:name p) :unitPrice (:price p) :quantity quantity :lineTotal (* quantity (:price p))}))
                        (:items body))
            phone (str/replace (get-in body [:customer :phone]) " " "")
            phone (if (str/starts-with? phone "0") (str "+256" (subs phone 1)) phone)
            o {:id (str "o" (inc (count (:orders @db)))) :orderNumber (str "ZT-" (str/upper-case (subs (str (random-uuid)) 0 6)))
               :status "placed" :placedAt (now) :version 1
               :customer (assoc (:customer body) :phone phone) :delivery (:delivery body)
               :paymentMethod (:paymentMethod body) :note (:note body) :items lines :deliveryFee nil
               :staffNote nil :paymentMethodUsed nil :cancelReason nil :stockRestored false
               :history [{:status "placed" :at (now) :by nil :note nil}]}]
        (swap! db (fn [d] (-> d
                              (update :orders conj o)
                              (update :products (fn [ps] (mapv (fn [p] (if-let [l (some #(when (= (:id p) (:productId %)) %) lines)] (update p :stock - (:quantity l)) p)) ps))))))
        [201 {:order (dissoc (order-view o) :history)
              :agreementAcceptance {:type "orderTerms" :version "2026-10-01" :acceptedAt (now)}}]))))

(defn validate-cart
  "POST /api/cart-validations. Argument: body {:items [{:productId
  :quantity}]}. Returns [200 {:items [line] :itemsSubtotal :hasProblems ...}]
  where each line has the current :unitPrice, :maxQuantity and a :problem
  (unavailable, soldOut, insufficientStock) or nil."
  [body]
  (let [by-id (into {} (map (juxt :id identity)) (:products @db))
        lines (mapv (fn [{:keys [productId quantity]}]
                      (let [p (by-id productId)
                            problem (cond (or (nil? p) (not= "published" (:status p))) "unavailable"
                                          (zero? (:stock p)) "soldOut"
                                          (< (:stock p) quantity) "insufficientStock")]
                        {:productId productId :slug (:slug p) :name (:name p) :unitPrice (:price p) :quantity quantity
                         :lineTotal (* quantity (or (:price p) 0)) :availability (when p (avail p)) :maxQuantity (when p (max-q p))
                         :purchasable (nil? problem) :image (some-> p img) :problem problem}))
                    (:items body))]
    [200 {:items lines :itemsSubtotal (reduce + (map #(if (:purchasable %) (:lineTotal %) 0) lines)) :currency "UGX"
          :deliveryFee nil :deliveryFeeNote "Agreed with you before dispatch" :hasProblems (boolean (some :problem lines))}]))

(defn admin-product
  "Product as the admin API returns it (summary plus sku, stockQuantity,
  lowStock, status, version, timestamps and descriptive fields)."
  [p]
  (merge (summary p) {:sku (:sku p) :stockQuantity (:stock p) :lowStock (<= 1 (:stock p) 5) :status (:status p)
                      :version (:version p) :updatedBy {:id "u1" :name "Sandra"} :updatedAt (:updatedAt p)
                      :createdAt (:createdAt p) :size (:size p) :description (or (:description p) "Stub description.") :howToUse (:howToUse p)
                      :ingredients (:ingredients p) :shortDescription (or (:shortDescription p) "Short description.")
                      :images (images-of p)}))

;; ---- product pictures, bulk import and publishing (api-contract.md 8.2) ----

(defn find-product
  "The stored product with id `id`, or nil."
  [id] (some #(when (= id (:id %)) %) (:products @db)))

(defn reorder-photos
  "PATCH `images`: the list replaces the product's pictures; unknown ids are
  ignored. Arguments: the current `photos` and `images` ([{:id :alt}]).
  Returns the new vector of photos."
  [photos images]
  (let [by-id (into {} (map (juxt :id identity)) photos)]
    (vec (keep (fn [{:keys [id alt]}] (when-let [ph (by-id id)] (assoc ph :alt alt))) images))))

(defn multipart-parts
  "Parts of a multipart body that carry a file: [{:name :filename :content}].
  The stub reads the body as text, which is enough for file names and CSV.
  Argument: `raw`, the body text. Returns nil when the body is not multipart."
  [raw]
  (let [raw (str raw)
        boundary (first (str/split-lines raw))]
    (when (str/starts-with? boundary "--")
      (vec (keep (fn [part]
                   (let [[hdr content] (str/split part #"\r\n\r\n" 2)]
                     (when-let [fname (second (re-find #"filename=\"([^\"]*)\"" (str hdr)))]
                       {:name (second (re-find #"name=\"([^\"]*)\"" hdr)) :filename fname
                        :content (str/replace (or content "") #"\r\n$" "")})))
                 (str/split raw (re-pattern (java.util.regex.Pattern/quote boundary))))))))

;; Why compare file names and contents instead of raw bytes: a multipart
;; boundary is different on every request, so the raw body of a retry never
;; equals the original. This mirrors the real contract, where a dry run and
;; the real import must use different keys (the contract treats the same key
;; with different content as a conflict).
(defn with-idempotency
  "Idempotency-Key handling for the upload and import endpoints. Arguments:
  request `headers`, a `scope` (the endpoint, so the same key on another
  endpoint is a separate slot), the raw body `raw` and `f` (a function that
  does the work and returns [status body]). No key gives 400
  missingIdempotencyKey. Same key and same content replays the first response
  with an Idempotent-Replayed header; same key with different content gives
  409 idempotencyKeyReused. Only a 200 result is remembered. Side effect:
  updates the db atom."
  [headers scope raw f]
  (let [k (get headers "Idempotency-key")
        body (mapv (juxt :filename :content) (multipart-parts raw))]
    (cond
      (nil? k) (err 400 "missingIdempotencyKey" "Idempotency-Key header is required.")
      :else (let [slot [scope k] stored (get-in @db [:idem slot])]
              (cond
                (and stored (not= (:body-hash stored) (hash body))) (err 409 "idempotencyKeyReused" "This key was used with different content.")
                stored [200 (:response stored) {"Idempotent-Replayed" "true"}]
                :else (let [[status resp :as r] (f)]
                        (when (= 200 status) (swap! db assoc-in [:idem slot] {:body-hash (hash body) :response resp}))
                        r))))))

(def image-extensions
  "File extensions accepted for product pictures."
  #{"jpg" "jpeg" "png" "webp"})

(defn file-ext
  "Lower-case extension of file name `fname`, or nil."
  [fname] (some-> (re-find #"\.([^.]+)$" fname) second str/lower-case))

(defn file-problem
  "Why an uploaded file `{:filename :content}` is not acceptable: {:code
  \"unsupportedMediaType\"|\"fileTooLarge\" :message}, or nil when it is fine
  (JPEG, PNG or WebP, at most 5 MB)."
  [{:keys [filename content]}]
  (cond
    (not (image-extensions (file-ext filename))) {:code "unsupportedMediaType" :message "Only JPEG, PNG or WebP pictures can be used."}
    (> (count (str content)) (* 5 1024 1024)) {:code "fileTooLarge" :message "This file is larger than 5 MB."}))

(defn add-photo!
  "Adds a stub picture to product `product-id` at `position` (1-based; appends
  when nil or out of range) and bumps the product's :version. Returns the new
  photo. Side effect: updates the db atom."
  [product-id fname position]
  (let [ph {:id (str "up-" (subs (str (random-uuid)) 0 8)) :alt nil}]
    (swap! db update :products
           (fn [ps] (mapv (fn [p] (if (= product-id (:id p))
                                    (-> p
                                        (update :version inc)
                                        (update :photos (fn [phs]
                                                          (if (and position (<= 1 position (count phs))) (assoc phs (dec position) ph) (conj phs ph)))))
                                    p)) ps)))
    ph))

(defn attached-result
  "Per-file result item {:status \"attached\" ...} for the upload endpoints
  (api-contract.md 8.2)."
  [fname p ph position]
  {:filename fname :status "attached" :productId (:id p) :sku (:sku p) :position position :image (image-of ph) :error nil})

(defn rejected-result
  "Per-file result item {:status \"rejected\" :error {:code :message}} for the
  upload endpoints."
  [fname code msg]
  {:filename fname :status "rejected" :productId nil :sku nil :position nil :image nil :error {:code code :message msg}})

(defn attach-to-product
  "POST /api/admin/products/{id}/images: attaches 1 to 6 uploaded files to
  product `id`. `body` is the raw multipart text. Returns [200 {:items
  [per-file result] :product admin-product}], 404 for an unknown product or
  422 for a bad file count. Side effect: updates the db atom."
  [id body]
  (let [files (multipart-parts body)]
    (cond
      (nil? (find-product id)) (err 404 "notFound" "Not found.")
      (or (empty? files) (> (count files) 6)) (err 422 "validationFailed" "Please check the highlighted fields." [{:field "files" :code "invalid" :message "Send 1 to 6 files."}])
      :else
      (let [items (mapv (fn [{:keys [filename] :as f}]
                          (let [p (find-product id)]
                            (cond
                              (file-problem f) (rejected-result filename (:code (file-problem f)) (:message (file-problem f)))
                              (>= (count (:photos p)) 6) (rejected-result filename "tooManyImages" "This product already has 6 pictures.")
                              :else (let [ph (add-photo! id filename nil)]
                                      (attached-result filename (find-product id) ph (count (:photos (find-product id))))))))
                        files)]
        [200 {:items items :product (admin-product (find-product id))}]))))

(defn sku-match
  "Finds the product whose SKU is the longest one that the file name starts
  with (case-insensitive), and the optional \"-1\" to \"-6\" position after it.
  Argument: file name `fname`. Returns [product position-or-nil], or nil when
  no SKU matches."
  [fname]
  (let [base (str/replace fname #"\.[^.]+$" "")
        lower (str/lower-case base)
        p (->> (:products @db) (filter #(and (:sku %) (str/starts-with? lower (str/lower-case (:sku %))))) (sort-by (comp - count :sku)) first)]
    (when p
      (let [rest (subs base (count (:sku p)))]
        [p (some-> (re-matches #"-([1-6])" rest) second parse-long)]))))

(defn attach-by-sku
  "POST /api/admin/product-images: attaches up to 20 uploaded files, matching
  each file name to a product SKU (see sku-match; \"-1\" to \"-6\" after the SKU
  picks the position). Returns [200 {:items [...]}] with a result per file
  (unknownSku, tooManyImages and file problems are rejected per file) or 422
  for a bad file count. Side effect: updates the db atom."
  [body]
  (let [files (multipart-parts body)]
    (if (or (empty? files) (> (count files) 20))
      (err 422 "validationFailed" "Please check the highlighted fields." [{:field "files" :code "invalid" :message "Send 1 to 20 files."}])
      [200 {:items (mapv (fn [{:keys [filename] :as f}]
                           (if-let [prob (file-problem f)]
                             (rejected-result filename (:code prob) (:message prob))
                             (if-let [[p position] (sku-match filename)]
                               (let [p (find-product (:id p))]
                                 (if (and (nil? position) (>= (count (:photos p)) 6))
                                   (rejected-result filename "tooManyImages" "This product already has 6 pictures.")
                                   (let [ph (add-photo! (:id p) filename position)
                                         p2 (find-product (:id p))]
                                     (attached-result filename p2 ph (or position (count (:photos p2)))))))
                               (rejected-result filename "unknownSku" "No product has a SKU at the start of this file name."))))
                         files)}])))

(def import-columns
  "Column names of the product import CSV, in template order."
  ["sku" "name" "brand" "ownBrand" "category" "price" "shortDescription" "description" "size" "howToUse" "ingredients" "featured" "openingStock"])

(def import-template
  "The CSV template served by GET /api/admin/product-import-template: the
  header and two example rows."
  (str (str/join "," import-columns) "\n"
       "ZT-SKIN-001,Brightening cream 50 g,Zandy Touch,yes,skincare,45000,A daily cream.,A longer description.,50 g,Apply in the evening.,,no,20\n"
       "ZT-PERF-002,Eau de parfum 50 ml,Sample Brand,no,Perfumes,120000,,,50 ml,,,yes,5\n"))

(defn parse-csv
  "Minimal CSV reader. Argument: `text`. Handles a leading byte-order mark,
  quoted cells, doubled quotes and a comma or semicolon delimiter (chosen from
  the header line). Returns a vector of rows (vectors of strings)."
  [text]
  (let [text (str/replace (str text) #"^﻿" "")
        delim (let [h (first (str/split-lines text))] (if (> (count (re-seq #";" h)) (count (re-seq #"," h))) \; \,))]
    (loop [chars (seq text) field (StringBuilder.) row [] rows [] in-q? false]
      (if-let [c (first chars)]
        (cond
          in-q? (cond (and (= c \") (= \" (second chars))) (do (.append field \") (recur (nnext chars) field row rows true))
                      (= c \") (recur (next chars) field row rows false)
                      :else (do (.append field c) (recur (next chars) field row rows true)))
          (= c \") (recur (next chars) field row rows true)
          (= c delim) (recur (next chars) (StringBuilder.) (conj row (str field)) rows false)
          (= c \return) (recur (next chars) field row rows false)
          (= c \newline) (recur (next chars) (StringBuilder.) [] (conj rows (conj row (str field))) false)
          :else (do (.append field c) (recur (next chars) field row rows false)))
        (cond-> rows (or (seq row) (pos? (.length field))) (conj (conj row (str field))))))))

(defn yes-no
  "Parses a yes/no cell (\"yes\", \"y\", \"true\" / \"no\", \"n\", \"false\", blank): true,
  false, or nil when it is anything else."
  [v] (case (str/lower-case (str v)) ("yes" "y" "true") true ("no" "n" "false" "") false nil))

(defn find-category
  "Slug of the category whose slug or name equals the cell `v`
  (case-insensitive), or nil."
  [v]
  (let [v (str/lower-case (str/trim v))]
    (some #(when (or (= v (:slug %)) (= v (str/lower-case (:name %)))) (:slug %)) categories)))

(defn row-fields
  "Validated fields of one import row. Arguments: `row` (map of column name to
  cell) and `new?` (true for a SKU that does not exist yet, which makes name,
  brand and category required). Returns [fields errors]; only non-empty cells
  count, and errors are {:field :code :message}."
  [row new?]
  (let [cell (fn [k] (str/trim (str (get row k ""))))
        err (fn [field code msg] {:field field :code code :message msg})
        errors (cond-> []
                 (and new? (str/blank? (cell "name"))) (conj (err "name" "required" "A new product needs a name."))
                 (and new? (str/blank? (cell "brand"))) (conj (err "brand" "required" "A new product needs a brand."))
                 (and new? (str/blank? (cell "category"))) (conj (err "category" "required" "A new product needs a category."))
                 (and (not (str/blank? (cell "category"))) (nil? (find-category (cell "category")))) (conj (err "category" "invalid" (str "No category called " (cell "category") ".")))
                 (and (not (str/blank? (cell "price"))) (not (re-matches #"\d+" (str/replace (cell "price") #"[, ]" "")))) (conj (err "price" "invalid" "Price must be a whole number of shillings."))
                 (and (not (str/blank? (cell "ownBrand"))) (nil? (yes-no (cell "ownBrand")))) (conj (err "ownBrand" "invalid" "Use yes or no."))
                 (and (not (str/blank? (cell "featured"))) (nil? (yes-no (cell "featured")))) (conj (err "featured" "invalid" "Use yes or no."))
                 (and (not (str/blank? (cell "openingStock"))) (not (re-matches #"\d+" (cell "openingStock")))) (conj (err "openingStock" "invalid" "Opening stock must be a whole number.")))]
    [(cond-> {}
       (not (str/blank? (cell "name"))) (assoc :name (cell "name"))
       (not (str/blank? (cell "brand"))) (assoc :brand (cell "brand"))
       (not (str/blank? (cell "ownBrand"))) (assoc :isOwnBrand (yes-no (cell "ownBrand")))
       (not (str/blank? (cell "category"))) (assoc :categorySlug (find-category (cell "category")))
       (not (str/blank? (cell "price"))) (assoc :price (some-> (re-matches #"\d+" (str/replace (cell "price") #"[, ]" "")) parse-long))
       (not (str/blank? (cell "shortDescription"))) (assoc :shortDescription (cell "shortDescription"))
       (not (str/blank? (cell "description"))) (assoc :description (cell "description"))
       (not (str/blank? (cell "size"))) (assoc :size (cell "size"))
       (not (str/blank? (cell "howToUse"))) (assoc :howToUse (cell "howToUse"))
       (not (str/blank? (cell "ingredients"))) (assoc :ingredients (cell "ingredients"))
       (not (str/blank? (cell "featured"))) (assoc :featured (yes-no (cell "featured")))
       (not (str/blank? (cell "openingStock"))) (assoc :stock (or (parse-long (cell "openingStock")) 0)))
     errors]))

(defn import-products
  "POST /api/admin/product-imports (and its dry run). Arguments: `body` (raw
  multipart text holding one CSV file) and `dry-run?`. Returns [200 {:dryRun
  :summary {:rows :created :updated :rejected} :rows [{:line :sku :status
  \"created\"|\"updated\"|\"rejected\" :productId :warnings :errors}]}] or 422 for a
  missing file, missing or unknown columns, or more than 500 rows. New
  products are created as drafts; existing SKUs are updated (opening stock is
  ignored with a warning). A dry run changes nothing. Side effect: updates the
  db atom unless `dry-run?`."
  [body dry-run?]
  (let [file (first (multipart-parts body))
        table (when file (parse-csv (:content file)))
        header (some->> (first table) (mapv #(str/lower-case (str/trim %))))
        canonical (into {} (map (juxt str/lower-case identity)) import-columns)
        header (some->> header (mapv #(get canonical % %)))
        fail (fn [field code msg] (err 422 "validationFailed" "Please check the highlighted fields." [{:field field :code code :message msg}]))]
    (cond
      (nil? file) (fail "file" "required" "Choose a CSV file.")
      (not (some #{"sku"} header)) (fail "file" "missingColumn" "The first line must contain a sku column.")
      (some #(not (some #{%} import-columns)) header) (fail "file" "unknownColumn" (str "Unknown column: " (first (remove (set import-columns) header))))
      (> (count (rest table)) 500) (fail "file" "tooManyRows" "A file can have at most 500 rows.")
      :else
      (let [seen (atom #{})
            rows (vec (map-indexed
                       (fn [i cells]
                         (let [row (zipmap header cells)
                               sku (str/trim (str (get row "sku" "")))
                               existing (when-not (str/blank? sku) (some #(when (= (str/lower-case sku) (str/lower-case (str (:sku %)))) %) (:products @db)))
                               [fields errors] (row-fields row (nil? existing))
                               errors (cond-> errors
                                        (str/blank? sku) (conj {:field "sku" :code "required" :message "Every row needs a SKU."})
                                        (@seen (str/lower-case sku)) (conj {:field "sku" :code "duplicateSku" :message "This SKU appears more than once in the file."}))
                               warnings (if (and existing (contains? fields :stock)) [{:field "openingStock" :code "ignoredForExisting"}] [])
                               line (+ 2 i)]
                           (swap! seen conj (str/lower-case sku))
                           (cond
                             (seq errors) {:line line :sku sku :status "rejected" :productId nil :warnings [] :errors errors}
                             existing
                             (do (when-not dry-run?
                                   (swap! db update :products (fn [ps] (mapv #(if (= (:id existing) (:id %)) (-> % (merge (dissoc fields :stock)) (update :version inc)) %) ps))))
                                 {:line line :sku sku :status "updated" :productId (:id existing) :warnings warnings :errors []})
                             :else
                             (let [id (str "p" (inc (count (:products @db))))]
                               (when-not dry-run?
                                 (swap! db update :products conj
                                        (merge {:id id :slug (str (slugify (:name fields)) "-" (str/lower-case sku)) :sku sku :isOwnBrand false :price nil :stock 0 :status "draft"
                                                :featured false :version 1 :createdAt (now) :updatedAt (now) :photos []}
                                               fields)))
                               {:line line :sku sku :status "created" :productId (if dry-run? nil id) :warnings [] :errors []}))))
                       (rest table)))
            n #(count (filter (fn [r] (= % (:status r))) rows))]
        [200 {:dryRun dry-run? :summary {:rows (count rows) :created (n "created") :updated (n "updated") :rejected (n "rejected")} :rows rows}]))))

(defn publish-one!
  "Publishes product `id` if it has a price and a picture. Returns {:productId
  :status \"published\"} or {:status \"rejected\" :errors [{:field :code}]}. Side
  effect: updates the db atom."
  [id]
  (let [p (find-product id)
        errors (cond-> []
                 (nil? p) (conj {:field "id" :code "notFound"})
                 (and p (nil? (:price p))) (conj {:field "price" :code "priceRequired"})
                 (and p (empty? (:photos p))) (conj {:field "images" :code "imageRequired"}))]
    (if (seq errors)
      {:productId id :status "rejected" :errors errors}
      (do (swap! db update :products (fn [ps] (mapv #(if (= id (:id %)) (assoc % :status "published" :updatedAt (now)) %) ps)))
          {:productId id :status "published"}))))


(defn order-list-item
  "Order as shown in the admin orders list (GET /api/admin/orders items)."
  [o]
  (let [sub (order-total (:items o))]
    {:id (:id o) :orderNumber (:orderNumber o) :status (:status o) :placedAt (:placedAt o)
     :customerName (get-in o [:customer :name]) :customerPhone (get-in o [:customer :phone])
     :city (get-in o [:delivery :city]) :itemCount (count (:items o)) :itemsSubtotal sub
     :deliveryFee (:deliveryFee o) :total (when (some? (:deliveryFee o)) (+ sub (:deliveryFee o)))
     :currency "UGX" :paymentMethod (:paymentMethod o) :updatedAt (:placedAt o)}))

(defn update-order
  "Replaces the order with id `id` in the db by (f order). Side effect: updates
  the db atom."
  [id f]
  (swap! db update :orders (fn [os] (mapv #(if (= id (:id %)) (f %) %) os))))

(defn handle
  "The stub's router. Arguments: `method` (keyword), `path`, `q` (map of string
  query parameters), `body` (parsed JSON map, or the raw string for multipart)
  and `headers` (map). Returns [status body] or [status body extra-headers].
  Staff routes check the zt_session cookie against the in-memory session set;
  unknown routes return the contract's 404 error."
  [method path q body headers]
  (let [seg (vec (remove str/blank? (str/split path #"/")))
        staff? (contains? (:sessions @db) (some->> (get headers "Cookie") (re-find #"zt_session=([^;]+)") second))]
    (case [method (vec (take 2 seg))]
      [:get ["api" "healthz"]] [200 {:status "ok"}]
      [:get ["api" "site-info"]]
      [200 {:businessName "Zandy Touch" :tagline "Beauty with Confidence" :phone "+256706105779" :whatsapp "+256706105779"
            :email "sandratonanyonjo@gmail.com" :shopAddress "Ring roads, Rubaga Rd, opposite Red Cross, Kampala, Uganda"
            :openingHours [{:days "Monday to Saturday" :opens "08:00" :closes "22:00"} {:days "Sunday" :opens "10:00" :closes "21:30"}]
            :social {:tiktok "https://www.tiktok.com/@zandytouch" :facebook "https://www.facebook.com/nanyonjo.sandra.1/" :instagram nil} :currency "UGX" :paymentMethods ["cash" "mobileMoney"] :maxQuantityPerLine 10}]
      [:get ["api" "delivery-areas"]]
      [200 {:countries [{:code "UG" :name "Uganda" :cities ["Kampala" "Entebbe" "Jinja" "Mbale" "Mbarara" "Fort Portal"]}] :allowOtherCity false}]
      [:get ["api" "categories"]]
      [200 {:items (mapv (fn [c] (assoc c :productCount (count (filter #(= (:slug c) (:categorySlug %)) (published))))) categories)}]
      [:get ["api" "sitemap-entries"]]
      [200 {:products (mapv (fn [p] {:slug (:slug p) :updatedAt (:updatedAt p)}) (published))
            :categories (mapv #(select-keys % [:slug]) categories) :generatedAt (now)}]
      [:get ["api" "policies"]] (if-let [p (policy (nth seg 2 nil))] [200 p] (err 404 "notFound" "Not found."))
      [:get ["api" "products"]]
      (if (= 2 (count seg))
        [200 (list-products q)]
        (if-let [p (some #(when (= (nth seg 2) (:slug %)) %) (published))] [200 (detail p)] (err 404 "notFound" "That product could not be found.")))
      [:post ["api" "cart-validations"]] (validate-cart body)
      [:post ["api" "orders"]]
      (let [k (get headers "Idempotency-key")]
        (cond
          (nil? k) (err 400 "missingIdempotencyKey" "Idempotency-Key header is required.")
          (get-in @db [:idem k]) (let [[_ b] (get-in @db [:idem k])] [200 b {"Idempotent-Replayed" "true"}])
          :else (let [[s _ :as r] (place-order body k)]
                  (when (= 201 s) (swap! db assoc-in [:idem k] r))
                  r)))
      [:post ["api" "order-trackings"]]
      (if-let [o (some #(when (and (= (str/upper-case (str (:orderNumber body))) (:orderNumber %))
                                   (= (str/replace (str (:phone body)) #"^0" "+256") (get-in % [:customer :phone]))) %) (:orders @db))]
        [200 (let [v (order-view o)]
               {:orderNumber (:orderNumber o) :status (:status o) :placedAt (:placedAt o)
                :items (mapv #(dissoc % :productId) (:items o)) :itemsSubtotal (:itemsSubtotal v)
                :deliveryFee (:deliveryFee o) :total (:total v) :currency "UGX" :paymentMethod (:paymentMethod o)
                :city (get-in o [:delivery :city]) :timeline (mapv #(select-keys % [:status :at]) (:history o))})]
        (err 404 "orderNotFound" "We could not find an order with those details."))
      [:post ["api" "subscriptions"]]
      (if (and (:email body) (re-matches #".+@.+\..+" (:email body)))
        (do (swap! db update :subscribers conj body) [201 {:subscribed true}])
        (err 422 "validationFailed" "Please check the highlighted fields." [{:field "email" :code "invalidEmail" :message "Enter a valid email address."}]))
      [:post ["api" "unsubscribes"]] [200 {:unsubscribed true}]
      [:post ["api" "contact-messages"]]
      (if (< (count (str (:message body))) 10)
        (err 422 "validationFailed" "Please check the highlighted fields." [{:field "message" :code "tooShort" :message "Write at least 10 characters."}])
        (do (swap! db update :messages conj (assoc body :id (str (random-uuid)) :receivedAt (now))) [201 {:received true}]))
      ;; ---- auth
      (cond
        (and (= :post method) (= ["api" "auth" "sessions"] seg))
      (if (and (= "owner@example.test" (:email body)) (= "correct-horse-battery" (:password body)))
          (let [tok (str (random-uuid))]
            (swap! db update :sessions conj tok)
            [201 {:user {:id "u1" :name "Sandra" :email (:email body) :role "owner" :mustChangePassword false} :csrfToken "csrf-stub"}
             {"Set-Cookie" (str "zt_session=" tok "; Path=/api; HttpOnly; SameSite=Lax")}])
          (err 401 "invalidCredentials" "Email or password is incorrect."))
        (and (= :post method) (= ["api" "auth" "password-resets"] seg)) [202 {:requested true}]
        (and (= :get method) (= ["api" "auth" "sessions" "current"] seg))
        (if staff? [200 {:user {:id "u1" :name "Sandra" :email "owner@example.test" :role "owner" :mustChangePassword false} :csrfToken "csrf-stub"}]
            (err 401 "unauthenticated" "Please sign in."))
        (and (= :delete method) (= ["api" "auth" "sessions" "current"] seg))
        (do (swap! db update :sessions disj (some->> (get headers "Cookie") (re-find #"zt_session=([^;]+)") second)) [204 nil])
        (and (= :put method) (= "password-resets" (nth seg 2 nil))) [200 {:passwordChanged true}]
        (not staff?) (err 401 "unauthenticated" "Please sign in.")
        (and (= :get method) (= ["api" "admin" "orders"] seg))
        (let [os (:orders @db)
              os (cond->> os (q "status") (filter #(= (q "status") (:status %))))]
          [200 (assoc (update (paged (reverse os) q 25) :items #(mapv order-list-item %))
                      :counts (into {} (map (fn [s] [s (count (filter #(= s (:status %)) (:orders @db)))]) ["placed" "confirmed" "approvedForDelivery" "delivered" "cancelled"])))])
        (and (= :get method) (= "orders" (nth seg 2 nil)) (= 4 (count seg)))
        (if-let [o (some #(when (= (nth seg 3) (:id %)) %) (:orders @db))]
          [200 {:order (dissoc (order-view o) :history)
                :agreementAcceptance {:type "orderTerms" :version "2026-10-01" :contentHash "sha256:stub" :acceptedAt (:placedAt o)
                                      :customerName (get-in o [:customer :name]) :customerPhone (get-in o [:customer :phone])}
                :history (:history o)}]
          (err 404 "notFound" "Not found."))
        (and (= :patch method) (= "orders" (nth seg 2 nil)))
        (let [id (nth seg 3) cur (some #(when (= id (:id %)) %) (:orders @db))]
          (cond (not= (:version body) (:version cur)) (err 409 "versionConflict" "This order was updated a moment ago.")
                :else (do (update-order id #(-> % (cond-> (contains? body :deliveryFee) (assoc :deliveryFee (:deliveryFee body))
                                                          (contains? body :staffNote) (assoc :staffNote (:staffNote body)))
                                                (update :version inc)))
                          [200 {:order (order-view (some #(when (= id (:id %)) %) (:orders @db)))}])))
        (and (= :post method) (= "status-changes" (nth seg 4 nil)))
        (let [id (nth seg 3) cur (some #(when (= id (:id %)) %) (:orders @db)) st (:status body)
              allowed {"placed" #{"confirmed" "cancelled"} "confirmed" #{"approvedForDelivery" "cancelled"} "approvedForDelivery" #{"delivered" "cancelled"}}]
          (cond (= st (:status cur)) [200 {:order (order-view cur) :stockRestored false}]
                (not= (:version body) (:version cur)) (err 409 "versionConflict" "This order was updated a moment ago.")
                (not (get-in allowed [(:status cur) st])) (err 409 "invalidStatusTransition" "That change is not allowed from the current status.")
                (and (#{"confirmed" "approvedForDelivery"} st) (nil? (:deliveryFee cur))) (err 409 "deliveryFeeRequired" "Record the delivery fee first.")
                (and (= "delivered" st) (nil? (:paymentMethodUsed body))) (err 422 "validationFailed" "Please check the highlighted fields." [{:field "paymentMethodUsed" :code "required" :message "Choose how the customer paid."}])
                (and (= "cancelled" st) (< (count (str (:cancelReason body))) 3)) (err 422 "validationFailed" "Please check the highlighted fields." [{:field "cancelReason" :code "required" :message "Give a reason."}])
                :else (let [restore? (and (= "cancelled" st) (#{"placed" "confirmed"} (:status cur)))]
                        (update-order id #(-> % (assoc :status st :paymentMethodUsed (:paymentMethodUsed body) :cancelReason (:cancelReason body) :stockRestored restore?)
                                              (update :version inc)
                                              (update :history conj {:status st :at (now) :by {:id "u1" :name "Sandra"} :note (:note body)})))
                        (when restore?
                          (swap! db update :products (fn [ps] (mapv (fn [p] (if-let [l (some #(when (= (:id p) (:productId %)) %) (:items cur))] (update p :stock + (:quantity l)) p)) ps))))
                        [201 {:order (order-view (some #(when (= id (:id %)) %) (:orders @db))) :stockRestored restore?}])))
        (and (= :get method) (= ["api" "admin" "products"] seg))
        (let [ps (:products @db)
              ps (cond->> ps (q "status") (filter #(= (q "status") (:status %))) (not (q "status")) (remove #(= "archived" (:status %)))
                          (q "q") (filter #(str/includes? (str/lower-case (:name %)) (str/lower-case (q "q"))))
                          (= "out" (q "stock")) (filter #(zero? (:stock %))) (= "low" (q "stock")) (filter #(<= 1 (:stock %) 5)))]
          [200 (assoc (update (paged ps q 25) :items #(mapv admin-product %))
                      :counts {:out (count (filter #(zero? (:stock %)) (:products @db))) :low (count (filter #(<= 1 (:stock %) 5) (:products @db)))})])
        (and (= :get method) (= "products" (nth seg 2 nil)) (= 4 (count seg)))
        (if-let [p (some #(when (= (nth seg 3) (:id %)) %) (:products @db))] [200 (admin-product p)] (err 404 "notFound" "Not found."))
        (and (= :post method) (= ["api" "admin" "products"] seg))
        (let [id (str "p" (inc (count (:products @db))))
              p {:id id :slug (slugify (:name body)) :sku (:sku body) :name (:name body) :brand (:brand body) :isOwnBrand (boolean (:isOwnBrand body))
                 :categorySlug (:categorySlug body) :price (:price body) :stock (or (:stockQuantity body) 0)
                 :status (:status body) :featured (boolean (:featured body)) :version 1 :createdAt (now) :updatedAt (now) :photos []}]
          (if (and (= "published" (:status body)) (nil? (:price body)))
            (err 422 "validationFailed" "Please check the highlighted fields." [{:field "price" :code "priceRequired" :message "A product needs a price before it can be published."}])
            (do (swap! db update :products conj p) [201 (admin-product p)])))
        (and (= :patch method) (= "products" (nth seg 2 nil)))
        (let [id (nth seg 3)]
          (if (not= (:version body) (:version (find-product id)))
            (err 409 "versionConflict" "This product was updated a moment ago.")
            (do (swap! db update :products (fn [ps] (mapv #(if (= id (:id %))
                                                       (-> % (merge (-> (select-keys body [:name :brand :isOwnBrand :price :status :featured :sku :categorySlug :shortDescription :description :size :howToUse :ingredients])))
                                                           (cond-> (contains? body :images) (assoc :photos (reorder-photos (:photos %) (:images body))))
                                                           (update :version inc))
                                                       %) ps)))
          [200 (admin-product (some #(when (= id (:id %)) %) (:products @db)))])))
        (and (= :delete method) (= "products" (nth seg 2 nil)))
        (do (swap! db update :products (fn [ps] (mapv #(if (= (nth seg 3) (:id %)) (assoc % :status "archived") %) ps))) [204 nil])
        (and (= :post method) (= "stock-movements" (nth seg 4 nil)))
        (let [id (nth seg 3) p (some #(when (= id (:id %)) %) (:products @db))]
          (cond (and (:expectedQuantity body) (not= (:expectedQuantity body) (:stock p))) (err 409 "versionConflict" "Stock changed meanwhile." [{:field "expectedQuantity" :code "currentVersion" :message (str (:stock p))}])
                (neg? (+ (:stock p) (:delta body))) (err 422 "validationFailed" "Please check the highlighted fields." [{:field "delta" :code "stockCannotBeNegative" :message "Stock cannot go below zero."}])
                :else (let [after (+ (:stock p) (:delta body)) m {:id (str (random-uuid)) :type "adjustment" :reason (:reason body) :delta (:delta body) :quantityAfter after :note (:note body) :orderNumber nil :at (now) :by {:id "u1" :name "Sandra"}}]
                        (swap! db (fn [d] (-> d (update :products (fn [ps] (mapv #(if (= id (:id %)) (assoc % :stock after) %) ps))) (update :movements conj (assoc m :productId id)))))
                        [201 {:movement m :stockQuantity after :availability (if (pos? after) "available" "soldOut")}])))
        (and (= :get method) (= "stock-movements" (nth seg 4 nil)))
        [200 (paged (reverse (filter #(= (nth seg 3) (:productId %)) (:movements @db))) q 25)]
        (and (= :post method) (= "images" (nth seg 4 nil)) (= 5 (count seg)))
        (with-idempotency headers (str path) body (fn [] (attach-to-product (nth seg 3) body)))
        (and (= :post method) (= ["api" "admin" "product-images"] seg))
        (with-idempotency headers (str path) body (fn [] (attach-by-sku body)))
        (and (= :post method) (= ["api" "admin" "product-imports"] seg))
        (with-idempotency headers (str path "?" (get q "dryRun")) body (fn [] (import-products body (= "true" (q "dryRun")))))
        (and (= :get method) (= ["api" "admin" "product-import-template"] seg))
        [200 import-template {"Content-Type" "text/csv; charset=utf-8" "Content-Disposition" "attachment; filename=\"zandy-touch-products.csv\""}]
        (and (= :post method) (= ["api" "admin" "product-publications"] seg))
        [200 {:results (mapv publish-one! (:productIds body))}]
        (and (= :get method) (= ["api" "admin" "contact-messages"] seg)) [200 (paged (reverse (:messages @db)) q 25)]
        (and (= :get method) (= ["api" "admin" "subscribers"] seg))
        [200 (paged (map-indexed (fn [i s] {:id (str "s" i) :email (:email s) :phone (:phone s) :subscribedAt (now) :source (:source s) :active true :unsubscribeUrl "http://stub/unsubscribe?token=x"}) (:subscribers @db)) q 50)]
        (and (= :get method) (= ["api" "admin" "staff"] seg)) [200 {:items (:staff @db)}]
        (and (= :post method) (= ["api" "admin" "staff"] seg))
        (let [s {:id (str "u" (inc (count (:staff @db)))) :name (:name body) :email (:email body) :role "support" :active true :notifyOnNewOrder (boolean (:notifyOnNewOrder body)) :mustChangePassword true}]
          (swap! db update :staff conj s)
          [201 {:staff s :temporaryPassword "tmp-pass-1"}])
        (and (= :delete method) (= "staff" (nth seg 2 nil))) [204 nil]
        (and (= :post method) (= "password-resets" (nth seg 4 nil))) [201 {:temporaryPassword "tmp-pass-2"}]
        (and (= :put method) (= ["api" "admin" "account" "password"] seg)) [200 {:passwordChanged true}]
        :else (err 404 "notFound" "Not found.")))))

(defn svg-for
  "Placeholder SVG text for the stub's /media/ images; the hue depends on the
  number in `id` so products look different. `variant` is only printed in the
  picture."
  [id variant]
  (let [n (or (some-> (re-find #"\d+" (str id)) parse-long) 0)
        hue (* 37 n)]
    (str "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 400 500'><rect width='400' height='500' fill='hsl(" hue ",25%,92%)'/>"
         "<rect x='150' y='140' width='100' height='220' rx='18' fill='hsl(" hue ",30%,45%)'/>"
         "<text x='200' y='430' font-size='22' text-anchor='middle' fill='#444'>stub " id " " variant "</text></svg>")))

;; Responses carry the same Cache-Control as the contract's public GETs but
;; with a short lifetime, so the dev browser does not hold stale data for
;; long.
(defn exchange-handler
  "HttpHandler for the stub server. Reads the request (query, JSON or raw
  multipart body, headers), serves /media/*.webp placeholders (immutable
  cache) or calls `handle`, and writes the JSON response. Public GETs get
  \"public, max-age=30\"; admin, auth and error responses get no-store. An
  exception becomes a 500 internalError JSON body."
  []
  (reify HttpHandler
    (handle [_ ex]
      (let [^HttpExchange ex ex
            method (keyword (str/lower-case (.getRequestMethod ex)))
            uri (.getRequestURI ex)
            path (.getRawPath uri)
            q (into {} (keep (fn [p] (let [[k v] (str/split p #"=" 2)] (when (seq k) [(URLDecoder/decode k "UTF-8") (URLDecoder/decode (or v "") "UTF-8")]))))
                    (str/split (or (.getRawQuery uri) "") #"&"))
            raw (String. (.readAllBytes (.getRequestBody ex)) StandardCharsets/UTF_8)
            ctype (or (.getFirst (.getRequestHeaders ex) "Content-Type") "")
            body (if (str/includes? ctype "json") (try (jread raw) (catch Exception _ nil)) raw)
            headers (into {} (map (fn [[k v]] [k (first v)])) (.getRequestHeaders ex))
            resp (.getResponseHeaders ex)
            send! (fn [status ^bytes bs ctype extra]
                    (.set resp "Content-Type" ctype)
                    (doseq [[k v] extra] (.set resp k v))
                    (.set resp "X-Request-Id" (or (get headers "X-request-id") "stub"))
                    (.sendResponseHeaders ex status (if (= 204 status) -1 (alength bs)))
                    (when-not (= 204 status) (with-open [o (.getResponseBody ex)] (.write o bs))))]
        (try
          (if-let [[_ id variant] (re-matches #"/media/(.+)-(thumb|medium|large)\.webp" path)]
            (send! 200 (.getBytes (svg-for id variant) StandardCharsets/UTF_8) "image/svg+xml" {"Cache-Control" "public, max-age=31536000, immutable"})
            (let [[status b extra] (handle method path q body headers)
                  public-get? (and (= :get method) (not (str/includes? path "/admin")) (not (str/includes? path "/auth")))]
              (send! status (.getBytes (cond (string? b) b b (jwrite b) :else "") StandardCharsets/UTF_8) "application/json"
                     (merge {"Cache-Control" (cond (not (<= 200 status 299)) "no-store" public-get? "public, max-age=30" :else "no-store")} extra))))
          (catch Throwable t
            (send! 500 (.getBytes (jwrite {:error {:code "internalError" :message (str "stub: " (.getMessage t))}}) StandardCharsets/UTF_8) "application/json" {})))))))

(defn start
  "Starts the stub HTTP server on `port` with 8 threads. Returns the
  HttpServer."
  [port]
  (let [server (HttpServer/create (InetSocketAddress. "0.0.0.0" (int port)) 0)]
    (.createContext server "/" (exchange-handler))
    (.setExecutor server (java.util.concurrent.Executors/newFixedThreadPool 8))
    (.start server)
    server))

(defn -main
  "Entry point (`clojure -M:stub [port]`, default 8080). Side effect: starts
  the server and prints the port."
  [& [port]]
  (start (or (some-> port parse-long) 8080))
  (.println System/out (str "stub API on " (or port 8080))))
