(ns zandy.shared.seo
  "Head tags per page: title, description, canonical, Open Graph, robots and
  one JSON-LD block. Pure; the same data feeds the server-rendered <head> and
  the browser's head updates after client navigation."
  (:require [clojure.string :as str]
            [zandy.shared.format :as fmt]
            [zandy.shared.json :as json]
            [zandy.shared.routes :as routes]))

(def default-og-image "/static/assets/share/og-default.jpg")

(defn- cat-name [state slug]
  (or (some #(when (= slug (:slug %)) (:name %)) (get-in state [:page :data :categories]))
      (fmt/slug-title slug)))

(defn- category-record [state slug]
  (some #(when (= slug (:slug %)) %) (get-in state [:page :data :categories])))

(defn- q-of [state] (get-in state [:route :query :q]))

(defn- variant?
  "Filtered, searched, sorted or paginated listing."
  [state]
  (let [q (:query (:route state))]
    (boolean (some #(seq (get q %)) [:q :availability :sort :page]))))

(def ^:private weekdays ["Monday" "Tuesday" "Wednesday" "Thursday" "Friday" "Saturday" "Sunday"])

(defn- day-index [d]
  (first (keep-indexed (fn [i x] (when (= x d) i)) weekdays)))

(defn- day-range [days]
  (let [[a b] (map str/trim (str/split (or days "") #" to "))
        ia (day-index a)
        ib (if b (day-index b) ia)]
    (when (and ia ib (>= ib ia))
      (subvec weekdays ia (inc ib)))))

(defn- hours-spec [hours]
  (vec (keep (fn [{:keys [days opens closes]}]
               (when-let [d (day-range days)]
                 {"@type" "OpeningHoursSpecification" "dayOfWeek" d "opens" opens "closes" closes}))
             hours)))

(defn- store-ld [site base]
  (let [{:keys [businessName tagline phone email shopAddress openingHours social]} site
        same-as (vec (remove nil? [(:instagram social) (:tiktok social)]))]
    (cond-> {"@type" "Store"
             "@id" (str base "/#store")
             "name" (or businessName "Zandy Touch")
             "url" (str base "/")
             "image" (str base default-og-image)
             "currenciesAccepted" "UGX"
             "paymentAccepted" "Cash, Mobile money"}
      tagline (assoc "slogan" tagline)
      phone (assoc "telephone" phone)
      email (assoc "email" email)
      shopAddress (assoc "address" {"@type" "PostalAddress"
                                    "streetAddress" shopAddress
                                    "addressLocality" "Kampala"
                                    "addressCountry" "UG"})
      (seq openingHours) (assoc "openingHoursSpecification" (hours-spec openingHours))
      (seq same-as) (assoc "sameAs" same-as))))

(defn- product-ld [p base]
  (let [url (str base (routes/path-for :product {:slug (:slug p)}))
        imgs (vec (keep #(some->> (get-in % [:urls :large]) (str base)) (:images p)))]
    (cond-> {"@type" "Product"
             "@id" (str url "#product")
             "name" (:name p)
             "url" url
             "description" (or (:shortDescription p) (fmt/truncate (:description p) 200))
             "brand" {"@type" "Brand" "name" (:brand p)}
             "category" (get-in p [:category :name])
             "offers" {"@type" "Offer"
                       "url" url
                       "price" (str (:price p))
                       "priceCurrency" (or (:currency p) "UGX")
                       "availability" (if (= "available" (:availability p))
                                        "https://schema.org/InStock"
                                        "https://schema.org/OutOfStock")
                       "seller" {"@id" (str base "/#store")}}}
      (seq imgs) (assoc "image" imgs))))

(defn- ld-block [graph]
  {"@context" "https://schema.org" "@graph" graph})

(defn- meta-for
  "Returns {:title :description :path :robots? :og-image :og-type :ld-extra}."
  [{:keys [route site page] :as state}]
  (let [n (:name route)
        biz (or (:businessName site) "Zandy Touch")
        slug (get-in route [:params :slug])
        data (:data page)]
    (case (:status page)
      :not-found {:title "Page Not Found | Zandy Touch"
                  :description "We could not find that page. Browse our skincare, perfumes and wellness products or contact the shop in Kampala."
                  :path (:path route) :noindex? true}
      :unavailable {:title "Temporarily Unavailable | Zandy Touch"
                    :description "The shop is having trouble loading. Please try again in a minute or call the shop in Kampala."
                    :path (:path route) :noindex? true}
      (case n
        :home {:title "Zandy Touch | Skincare, Perfumes and Wellness in Uganda"
               :description "Beauty and wellness products from a Kampala shop: skincare, perfumes, body care and more. Order online and pay on delivery in cash or mobile money."
               :path "/"}
        :shop (if (q-of state)
                {:title (str "Search results for “" (fmt/truncate (q-of state) 40) "” | Zandy Touch")
                 :description "Search results from the Zandy Touch shop in Kampala. Pay on delivery in cash or mobile money."
                 :path "/shop" :noindex? true}
                {:title "Shop All Products | Zandy Touch"
                 :description "Buy beauty products online in Uganda: skincare, perfumes, body shaping, body care, weight loss and wellness. Pay on delivery with cash or mobile money."
                 :path "/shop" :noindex? (variant? state)})
        :category (let [c (category-record state slug)
                        nm (cat-name state slug)]
                    {:title (str nm " in Uganda | Zandy Touch")
                     :description (fmt/truncate
                                   (str (when (:description c) (str (:description c) " "))
                                        "Shop " nm " from Zandy Touch in Kampala. Pay on delivery.")
                                   158)
                     :path (routes/path-for :category {:slug slug})
                     :noindex? (variant? state)})
        :product (let [p data]
                   {:title (str (:name p) " | " (get-in p [:category :name]) " | Zandy Touch")
                    :description (fmt/truncate
                                  (or (:shortDescription p)
                                      (str (:name p) " by " (:brand p) " at Zandy Touch in Kampala, Uganda."))
                                  158)
                    :path (routes/path-for :product {:slug slug})
                    :og-image (get-in p [:images 0 :urls :large])
                    :og-type "product"
                    :ld-extra (when (:slug p) [:product p])})
        :about {:title "About Zandy Touch | Beauty with Confidence"
                :description "Zandy Touch is a beauty and wellness shop in Kampala. Learn about our own brand, our values and where to find the shop."
                :path "/about"}
        :contact {:title "Contact Zandy Touch | Kampala, Uganda"
                  :description "Call, WhatsApp or message Zandy Touch in Kampala, Uganda. Find the shop address, opening hours and our contact form."
                  :path "/contact"}
        :delivery {:title "Delivery and Payment | Zandy Touch"
                   :description "How delivery works across Uganda and how to pay: cash or mobile money on delivery, with the delivery fee agreed with you before dispatch."
                   :path "/delivery-and-payment"}
        :legal-terms-of-sale {:title "Terms of Sale | Zandy Touch"
                              :description "The terms that apply when you buy from Zandy Touch: prices, ordering, delivery and payment on delivery."
                              :path "/legal/terms-of-sale"}
        :legal-privacy {:title "Privacy Policy | Zandy Touch"
                        :description "What personal information Zandy Touch collects when you order or contact us, why, how long we keep it and how to ask for removal."
                        :path "/legal/privacy"}
        :legal-returns {:title "Returns and Refunds | Zandy Touch"
                        :description "How returns and refunds work for orders from Zandy Touch in Uganda."
                        :path "/legal/returns-and-refunds"}
        :legal-order-terms {:title "Order Terms | Zandy Touch"
                            :description "The order terms you accept when you place an order with Zandy Touch: prices, delivery fee, cancellation and payment on delivery."
                            :path "/legal/order-terms"}
        :cart {:title "Your Cart | Zandy Touch" :description "Review the items in your Zandy Touch cart." :path "/cart" :noindex? true}
        :checkout {:title "Checkout | Zandy Touch" :description "Enter your delivery details and place your Zandy Touch order. Pay on delivery." :path "/checkout" :noindex? true}
        :order-placed {:title "Order Received | Zandy Touch" :description "Your Zandy Touch order has been received." :path "/order-placed" :noindex? true}
        :track-order {:title "Track Your Order | Zandy Touch" :description "Check the status of your Zandy Touch order with your order number and phone." :path "/track-order" :noindex? true}
        :unsubscribe {:title "Unsubscribe | Zandy Touch" :description "Stop receiving Zandy Touch news." :path "/unsubscribe" :noindex? true}
        {:title (str "Staff area | " biz " Admin")
         :description "Zandy Touch staff area."
         :path (or (:path route) "/admin") :noindex? true}))))

(defn head
  "Head data for a page state. `base-url` has no trailing slash."
  [state base-url]
  (let [{:keys [title description path noindex? og-image og-type ld-extra]} (meta-for state)
        base (str/replace (or base-url "") #"/+$" "")
        canonical (str base (if (= "/" path) "/" path))
        site (:site state)
        store (store-ld site base)
        graph (case (first ld-extra)
                :product [store (product-ld (second ld-extra) base)]
                [store])]
    {:title title
     :description description
     :canonical canonical
     :robots (when noindex? "noindex, follow")
     :og {:title title
          :description description
          :url canonical
          :type (or og-type "website")
          :image (str base (or og-image default-og-image))
          :site-name "Zandy Touch"}
     :jsonld (ld-block graph)}))

(defn head->hiccup
  "Hiccup for the <head> children."
  [{:keys [title description canonical robots og jsonld]}]
  (cond-> [[:title title]
           [:meta {:name "description" :content description}]
           [:link {:rel "canonical" :href canonical}]
           [:meta {:property "og:title" :content (:title og)}]
           [:meta {:property "og:description" :content (:description og)}]
           [:meta {:property "og:image" :content (:image og)}]
           [:meta {:property "og:url" :content (:url og)}]
           [:meta {:property "og:type" :content (:type og)}]
           [:meta {:property "og:site_name" :content (:site-name og)}]
           [:meta {:name "twitter:card" :content "summary"}]]
    robots (conj [:meta {:name "robots" :content robots}])
    true (conj [:script {:type "application/ld+json" :innerHTML (json/write-str jsonld)}])))
