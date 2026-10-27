(ns zandy.shared.pages
  "Page data and page views for the public site, shared by the frontend server
  and the browser (architecture.md sections 2.4 and 2.5). `requests` says
  which API calls a route needs (api-contract.md section 12), `assemble` turns
  the responses into page state, `public-view` turns state into hiccup (P1 to
  P17). All pure, so the JVM and the browser produce the same result. Shared
  code (.cljc).

  Page state shape used throughout: {:route {:name :path :params :query :data}
  :site <GET /api/site-info body> :page {:status
  :ok|:not-found|:unavailable|:loading, :data <page data>} :cart {:items
  [...]} :cart-check {:status :idle|:loading|:ready|:error :result
  <validation>} :forms {form-key {:values :errors :status}} :refs
  {:delivery-areas :policies :policy-status} :ui {...} :last-order {:order
  ...}}."
  (:require [clojure.string :as str]
            [zandy.shared.pages.catalog :as catalog]
            [zandy.shared.pages.info :as info]
            [zandy.shared.pages.shop :as shop]
            [zandy.shared.ui.layout :as layout]))

(def page-size
  "Page size (24) used for product listings from /api/products."
  24)

(defn initial-state
  "State skeleton shared by the server and the browser."
  []
  {:cart {:items []}
   :cart-check {:status :idle}
   :forms {}
   :refs {}
   :ui {}})

(def sorts
  "Allowed values of the `sort` query parameter (\"newest\" is the default and is
  never kept in URLs)."
  #{"newest" "priceAsc" "priceDesc" "nameAsc"})
(def availabilities
  "Allowed values of the `availability` query parameter."
  #{"available" "soldOut"})

;; Why: only known, valid parameters may reach the API and the cache. Without
;; this, ?utm_x=... or ?page=999999 would create endless distinct CDN cache
;; keys and API calls for the same page.
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
  "Request description for GET /api/products for a listing page.
  Arguments: `query` (already normalised, keys :q :availability :sort :page)
  and an optional `category-slug`.
  Returns {:path <the products endpoint> :query {:pageSize 24 :page n ...}} with
  :categorySlug added when a slug is given. Pure."
  ([query] (products-request query nil))
  ([query category-slug]
   {:path "/api/products"
    :query (cond-> (merge {:pageSize page-size :page 1}
                          (select-keys query [:q :availability :sort :page]))
             category-slug (assoc :categorySlug category-slug))}))

(def site-request
  "Request description for GET /api/site-info, needed by every server-rendered
  page (header, footer, JSON-LD)."
  {:path "/api/site-info"})

(defn requests
  "Which API calls a matched route needs on the server (api-contract.md section
  12).
  Argument: route map {:name :params :query :data} from routes/match-url.
  Returns a map of key -> {:path :query}; the keys are what `assemble` expects
  in its responses (:site, :categories, :products, :product, :policy, :areas,
  ...). Browser-only routes (cart, checkout, admin ...) and unknown routes
  return {}. Pure."
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

(defn ok?
  "True when response `r` ({:status :body}) has a 2xx numeric status."
  [r] (let [s (:status r)] (and (number? s) (<= 200 s) (< s 300))))
(defn not-found?
  "True when response `r` ({:status :body}) has status 404."
  [r] (= 404 (:status r)))

;; Why :unavailable for any failed call: showing a half-built page (say, no
;; site info) would be cached by the CDN; a 503 with no-store is retried by
;; the next visitor instead.
(defn assemble
  "Turns API responses into the server-side page state.
  Arguments: `route` (as for `requests`) and `responses`, a map of request key
  -> {:status :body}.
  Returns {:site <site-info or nil> :page {:status :ok|:not-found|:unavailable
  :data ...}}. Routes without requests are :ok with nil data. A 404 on the
  primary resource (product or policy) or an unknown category slug gives
  :not-found; any other non-2xx response gives :unavailable (the caller maps
  this to 503). Pure; no I/O."
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

;; Shown while the browser fetches page data after a client-side navigation,
;; and as a stand-in for admin routes in server renders.
(defn- boot-placeholder []
  [:div.container [:p.loading "Loading"]])

(defn content
  "Main content (inside <main>) for a state.
  Argument: state with :route and :page. A :not-found page renders P16
  (info/not-found), :unavailable the calm error page, :loading a placeholder;
  otherwise the view for the route name (P1 to P11, P12 to P14 and P17, P5 to
  P8, P15). Admin routes render a placeholder here because the admin views are
  client-only. Returns hiccup. Pure."
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
  "Full public page: header, content and footer, via layout/page-frame.
  Argument: state as described in the namespace docstring. Returns hiccup.
  This is what both ssr (through shared.document) and the browser
  (frontend.main) render for public routes. Pure."
  [state]
  (layout/page-frame state (content state)))

(defn admin-route?
  "True when the state's matched route is an admin route (A1 to A13), so the
  browser renders the admin view instead of the public view. Pure."
  [state]
  (boolean (:admin? (:data (:route state)))))
