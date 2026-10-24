(ns zandy.ssr.stub-api
  "Development stub of the backend API, shaped exactly like api-contract.md.
  Used to build and click-test the frontend before (or without) the real
  backend. In-memory and stateful: orders reduce stock, idempotency keys
  replay, a staff session works. Not shipped: lives under ssr/dev.
  Run: cd ssr && clojure -M:stub [port]   (default 8080)"
  (:require [clojure.string :as str]
            [jsonista.core :as json])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetSocketAddress URLDecoder)
           (java.nio.charset StandardCharsets)
           (java.time Instant)))

(def mapper (json/object-mapper {:decode-key-fn keyword}))
(defn jread [s] (when-not (str/blank? s) (json/read-value s mapper)))
(defn jwrite [x] (json/write-value-as-string x))
(defn now [] (str (.truncatedTo (Instant/now) java.time.temporal.ChronoUnit/SECONDS)))

(def categories
  [{:id "c1" :slug "skincare" :name "Skincare" :description "Cleansers, serums and creams for everyday skin care." :sortOrder 1}
   {:id "c2" :slug "perfumes" :name "Perfumes" :description "Eau de parfum and body mists for every occasion." :sortOrder 2}
   {:id "c3" :slug "body-shaping" :name "Body-shaping" :description "Shapewear and body contouring care." :sortOrder 3}
   {:id "c4" :slug "body-care" :name "Body care" :description "Lotions, scrubs and oils for soft skin." :sortOrder 4}
   {:id "c5" :slug "weight-loss" :name "Weight loss" :description "Teas, drink mixes and tools for your wellness routine." :sortOrder 5}
   {:id "c6" :slug "health-and-wellness" :name "Health & Wellness" :description "Supplements and nutrition for daily wellbeing." :sortOrder 6}])

(def seed
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

(defn slugify [s] (-> s str/lower-case (str/replace #"[^a-z0-9]+" "-") (str/replace #"^-|-$" "")))

(defonce db
  (atom {:products (vec (map-indexed
                         (fn [i [sku nm cat own? price stock size]]
                           {:id (str "p" (inc i)) :slug (slugify nm) :sku (str "SEED-" sku) :name nm :brand (if own? "Zandy Touch" "Sample Brand")
                            :isOwnBrand own? :categorySlug cat :price price :stock stock :size size :status "published"
                            :featured (zero? (mod i 5)) :version 1 :createdAt (now) :updatedAt (now)
                            :photos [(str "ph" (inc i))]})
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
  {"orderTerms" ["Order terms" ["Prices shown are product prices in UGX and exclude delivery." "The delivery fee is agreed with you before dispatch." "You may cancel before dispatch." "You pay on delivery in cash or mobile money."]]
   "termsOfSale" ["Terms of sale" []]
   "privacy" ["Privacy policy" ["We keep your contact details for 6 months."]]
   "returns" ["Returns and refunds" []]})

(def approved? (atom false))

(defn policy [type]
  (when-let [[title summary] (get policy-text type)]
    {:type type :version "2026-10-01" :title title :reviewStatus (if @approved? "approved" "placeholder")
     :summary summary
     :bodyBlocks [{:type "heading" :text "1. Overview"}
                  {:type "paragraph" :text (str "This is draft text for the " title ". It is a placeholder until reviewed.")}
                  {:type "list" :items ["First point" "Second point"]}]
     :contentHash "sha256:stub" :effectiveFrom "2026-10-01T00:00:00Z"}))

(defn img [p]
  (when-let [ph (first (:photos p))]
    {:id ph :alt (str (:name p) " on a plain background")
     :urls {:thumb (str "/media/" ph "-thumb.webp") :medium (str "/media/" ph "-medium.webp") :large (str "/media/" ph "-large.webp")}}))

(defn avail [p] (if (pos? (:stock p)) "available" "soldOut"))
(defn max-q [p] (min 10 (:stock p)))

(defn summary [p]
  {:id (:id p) :slug (:slug p) :name (:name p) :brand (:brand p) :isOwnBrand (:isOwnBrand p)
   :categorySlug (:categorySlug p) :price (:price p) :currency "UGX"
   :shortDescription (str "A " (str/lower-case (:name p)) " for everyday use.")
   :availability (avail p) :maxQuantity (max-q p) :isNew true :featured (:featured p) :image (img p)})

(defn published [] (filter #(= "published" (:status %)) (:products @db)))

(defn paged [items {:strs [page pageSize]} default-size]
  (let [page (or (some-> page parse-long) 1) size (or (some-> pageSize parse-long) default-size)]
    {:items (vec (take size (drop (* (dec page) size) items))) :page page :pageSize size :totalItems (count items)}))

(defn list-products [q]
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

(defn detail [p]
  (let [c (some #(when (= (:categorySlug p) (:slug %)) %) categories)]
    (merge (dissoc (summary p) :categorySlug :image :shortDescription :featured)
           {:category {:slug (:slug c) :name (:name c)}
            :shortDescription (str "A " (str/lower-case (:name p)) " for everyday use.")
            :description (str "About the " (:name p) ".\n\nUse as directed. This is stub text for development.")
            :size (:size p) :howToUse "Apply a small amount daily." :ingredients nil
            :images (vec (keep identity [(img p)]))
            :related (vec (map summary (take 4 (remove #(= (:id p) (:id %)) (filter #(= (:categorySlug p) (:categorySlug %)) (published))))))
            :updatedAt (:updatedAt p)})))

(defn err [status code msg & [details]]
  [status {:error (cond-> {:code code :message msg} details (assoc :details details))}])

(defn order-total [lines] (reduce + (map :lineTotal lines)))

(defn order-view [o]
  (let [sub (order-total (:items o))]
    (assoc o :itemsSubtotal sub :currency "UGX" :deliveryFeeNote "Agreed with you before dispatch"
           :total (when (some? (:deliveryFee o)) (+ sub (:deliveryFee o))))))

(defn place-order [body _idem-key]
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

(defn validate-cart [body]
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

(defn admin-product [p]
  (merge (summary p) {:sku (:sku p) :stockQuantity (:stock p) :lowStock (<= 1 (:stock p) 5) :status (:status p)
                      :version (:version p) :updatedBy {:id "u1" :name "Sandra"} :updatedAt (:updatedAt p)
                      :createdAt (:createdAt p) :size (:size p) :description "Stub description." :howToUse nil :ingredients nil
                      :shortDescription "Short description." :images (vec (keep identity [(img p)]))}))

(defn order-list-item [o]
  (let [sub (order-total (:items o))]
    {:id (:id o) :orderNumber (:orderNumber o) :status (:status o) :placedAt (:placedAt o)
     :customerName (get-in o [:customer :name]) :customerPhone (get-in o [:customer :phone])
     :city (get-in o [:delivery :city]) :itemCount (count (:items o)) :itemsSubtotal sub
     :deliveryFee (:deliveryFee o) :total (when (some? (:deliveryFee o)) (+ sub (:deliveryFee o)))
     :currency "UGX" :paymentMethod (:paymentMethod o) :updatedAt (:placedAt o)}))

(defn update-order [id f]
  (swap! db update :orders (fn [os] (mapv #(if (= id (:id %)) (f %) %) os))))

(defn handle
  "Returns [status body] or [status body headers]."
  [method path q body headers]
  (let [seg (vec (remove str/blank? (str/split path #"/")))
        staff? (contains? (:sessions @db) (some->> (get headers "Cookie") (re-find #"zt_session=([^;]+)") second))]
    (case [method (vec (take 2 seg))]
      [:get ["api" "healthz"]] [200 {:status "ok"}]
      [:get ["api" "site-info"]]
      [200 {:businessName "Zandy Touch" :tagline "Beauty with Confidence" :phone "+256706105779" :whatsapp "+256706105779"
            :email "sandratonanyonjo@gmail.com" :shopAddress "Ring roads, Rubaga Rd, opposite Red Cross, Kampala, Uganda"
            :openingHours [{:days "Monday to Saturday" :opens "08:00" :closes "22:00"} {:days "Sunday" :opens "10:00" :closes "21:30"}]
            :social {:instagram nil :tiktok nil} :currency "UGX" :paymentMethods ["cash" "mobileMoney"] :maxQuantityPerLine 10}]
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
                 :status (:status body) :featured (boolean (:featured body)) :version 1 :createdAt (now) :updatedAt (now) :photos (mapv :id (:images body))}]
          (if (and (= "published" (:status body)) (nil? (:price body)))
            (err 422 "validationFailed" "Please check the highlighted fields." [{:field "price" :code "priceRequired" :message "A product needs a price before it can be published."}])
            (do (swap! db update :products conj p) [201 (admin-product p)])))
        (and (= :patch method) (= "products" (nth seg 2 nil)))
        (let [id (nth seg 3)]
          (swap! db update :products (fn [ps] (mapv #(if (= id (:id %))
                                                       (-> % (merge (-> (select-keys body [:name :brand :isOwnBrand :price :status :featured :sku :categorySlug]))) (update :version inc))
                                                       %) ps)))
          [200 (admin-product (some #(when (= id (:id %)) %) (:products @db)))])
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
        (and (= :get method) (= ["api" "admin" "photos"] seg))
        [200 (paged (map (fn [p] {:id (first (:photos p)) :filename (str (:sku p) "-1.jpg") :urls (:urls (img p)) :assignedProductIds [(:id p)] :uploadedAt (:createdAt p)}) (take 12 (:products @db))) q 40)]
        (and (= :post method) (= ["api" "admin" "photos"] seg))
        [201 {:items (mapv (fn [fname] {:filename fname :status "stored"
                                        :photo {:id (str "up-" (slugify fname)) :urls {:thumb "/media/ph1-thumb.webp" :medium "/media/ph1-medium.webp" :large "/media/ph1-large.webp"} :assignedProductIds []}
                                        :suggestedProductId nil :error nil})
                           (re-seq #"(?<=filename=\")[^\"]+" (str body)))}]
        (and (= :post method) (= ["api" "admin" "photo-assignments"] seg))
        [200 {:results (mapv #(assoc % :status "assigned") (:assignments body))}]
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

(defn svg-for [id variant]
  (let [n (or (some-> (re-find #"\d+" (str id)) parse-long) 0)
        hue (* 37 n)]
    (str "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 400 500'><rect width='400' height='500' fill='hsl(" hue ",25%,92%)'/>"
         "<rect x='150' y='140' width='100' height='220' rx='18' fill='hsl(" hue ",30%,45%)'/>"
         "<text x='200' y='430' font-size='22' text-anchor='middle' fill='#444'>stub " id " " variant "</text></svg>")))

(defn exchange-handler []
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
              (send! status (.getBytes (or (some-> b jwrite) "") StandardCharsets/UTF_8) "application/json"
                     (merge {"Cache-Control" (cond (not (<= 200 status 299)) "no-store" public-get? "public, max-age=30" :else "no-store")} extra))))
          (catch Throwable t
            (send! 500 (.getBytes (jwrite {:error {:code "internalError" :message (str "stub: " (.getMessage t))}}) StandardCharsets/UTF_8) "application/json" {})))))))

(defn start [port]
  (let [server (HttpServer/create (InetSocketAddress. "0.0.0.0" (int port)) 0)]
    (.createContext server "/" (exchange-handler))
    (.setExecutor server (java.util.concurrent.Executors/newFixedThreadPool 8))
    (.start server)
    server))

(defn -main [& [port]]
  (start (or (some-> port parse-long) 8080))
  (.println System/out (str "stub API on " (or port 8080))))
