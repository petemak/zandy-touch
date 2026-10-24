(ns zandy.shared.pages
  "Page data and page views, shared by the frontend server and the browser.
  `requests` says which API calls a route needs (api-contract.md section 12),
  `assemble` turns the responses into page state, `view` turns state into
  hiccup. All three are pure."
  (:require [clojure.string :as str]
            [zandy.shared.pages.catalog :as catalog]
            [zandy.shared.pages.info :as info]
            [zandy.shared.pages.shop :as shop]
            [zandy.shared.ui.layout :as layout]))

(def page-size 24)

(defn initial-state
  "State skeleton shared by the server and the browser."
  []
  {:cart {:items []}
   :cart-check {:status :idle}
   :forms {}
   :refs {}
   :ui {}})

(def sorts #{"newest" "priceAsc" "priceDesc" "nameAsc"})
(def availabilities #{"available" "soldOut"})

(defn normalise-query
  "Keep only the known listing parameters, in a valid form. A search term
  shorter than 2 characters is dropped, a longer one is cut to 80. Unknown
  parameters never reach the cache key or the API."
  [query]
  (let [q (some-> (:q query) str/trim)
        q (when (and q (>= (count q) 2)) (subs q 0 (min 80 (count q))))
        page (some-> (:page query) parse-long)]
    (cond-> {}
      q (assoc :q q)
      (availabilities (:availability query)) (assoc :availability (:availability query))
      (and (sorts (:sort query)) (not= "newest" (:sort query))) (assoc :sort (:sort query))
      (and page (> page 1) (<= page 1000)) (assoc :page page))))

(defn products-request
  ([query] (products-request query nil))
  ([query category-slug]
   {:path "/api/products"
    :query (cond-> (merge {:pageSize page-size :page 1}
                          (select-keys query [:q :availability :sort :page]))
             category-slug (assoc :categorySlug category-slug))}))

(def site-request {:path "/api/site-info"})

(defn requests
  "Map of key -> {:path :query} for a matched route. Browser-only routes need
  none on the server (their data comes from the browser)."
  [{route-name :name :keys [params query data]}]
  (let [q (normalise-query query)]
    (case route-name
      :home {:site site-request
             :categories {:path "/api/categories"}
             :new {:path "/api/products" :query {:sort "newest" :pageSize 8}}
             :own {:path "/api/products" :query {:ownBrand "true" :pageSize 4}}
             :featured {:path "/api/products" :query {:featured "true" :pageSize 8}}}
      :shop {:site site-request
             :products (products-request q)
             :categories {:path "/api/categories"}}
      :category {:site site-request
                 :categories {:path "/api/categories"}
                 :products (products-request q (:slug params))}
      :product {:site site-request
                :product {:path (str "/api/products/" (:slug params))}}
      (:about :contact) {:site site-request}
      :delivery {:site site-request
                 :areas {:path "/api/delivery-areas"}}
      (:legal-terms-of-sale :legal-privacy :legal-returns :legal-order-terms)
      {:site site-request
       :policy {:path (str "/api/policies/" (:policy data))}}
      {})))

(defn ok? [r] (let [s (:status r)] (and (number? s) (<= 200 s) (< s 300))))
(defn not-found? [r] (= 404 (:status r)))

(defn assemble
  "responses: key -> {:status :body}. Returns {:site .. :page {:status
  :ok|:not-found|:unavailable :data ..}}."
  [{route-name :name :keys [params] :as route} responses]
  (let [reqs (requests route)
        results (into {} (map (fn [k] [k (get responses k)])) (keys reqs))
        primary (case route-name
                  :product :product
                  (:legal-terms-of-sale :legal-privacy :legal-returns :legal-order-terms) :policy
                  nil)
        site (get results :site)
        body (fn [k] (:body (get results k)))]
    (cond
      (empty? reqs)
      {:page {:status :ok :data nil}}

      (and primary (not-found? (get results primary)))
      {:site (when (ok? site) (:body site)) :page {:status :not-found}}

      (not-every? ok? (vals results))
      {:site (when (ok? site) (:body site)) :page {:status :unavailable}}

      :else
      {:site (body :site)
       :page
       (case route-name
         :home {:status :ok
                :data {:categories (:items (body :categories))
                       :new (body :new) :own (body :own) :featured (body :featured)}}
         :shop {:status :ok
                :data {:products (body :products) :categories (:items (body :categories))}}
         :category (if (some #(= (:slug params) (:slug %)) (:items (body :categories)))
                     {:status :ok
                      :data {:products (body :products) :categories (:items (body :categories))}}
                     {:status :not-found})
         :product {:status :ok :data (body :product)}
         :delivery {:status :ok :data (body :areas)}
         (:legal-terms-of-sale :legal-privacy :legal-returns :legal-order-terms)
         {:status :ok :data (body :policy)}
         {:status :ok :data nil})})))

(defn status->http
  "HTTP status for a page status."
  [page-status]
  (case page-status
    :not-found 404
    :unavailable 503
    200))

(defn- boot-placeholder []
  [:div.container [:p.loading "Loading"]])

(defn content
  "Main content for a state (without the frame)."
  [{:keys [route page] :as state}]
  (case (:status page)
    :not-found (info/not-found state)
    :unavailable (info/unavailable state)
    :loading (boot-placeholder)
    (case (:name route)
      :home (catalog/home state)
      :shop (catalog/shop state)
      :category (catalog/category state)
      :product (catalog/product-page state)
      :about (info/about state)
      :contact (info/contact state)
      :delivery (info/delivery state)
      (:legal-terms-of-sale :legal-privacy :legal-returns :legal-order-terms) (info/legal state)
      :cart (shop/cart-page state)
      :checkout (shop/checkout-page state)
      :order-placed (shop/order-placed-page state)
      :track-order (shop/track-page state)
      :unsubscribe (info/unsubscribe state)
      (if (:admin? (:data route))
        (boot-placeholder)
        (info/not-found state)))))

(defn public-view
  "Full public page: frame plus content."
  [state]
  (layout/page-frame state (content state)))

(defn admin-route? [state]
  (boolean (:admin? (:data (:route state)))))
