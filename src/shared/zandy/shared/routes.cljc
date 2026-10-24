(ns zandy.shared.routes
  "Page route table (Reitit data, matching only). Used by the frontend server
  and by the browser router. No static route sits beside a {param} route at
  the same position (design-spec.md section 0)."
  (:require [clojure.string :as str]
            [reitit.core :as r]
            #?(:cljs [goog.string :as gstr])))

(def routes
  [["/" {:name :home :ssr? true :cache :catalogue}]
   ["/shop" {:name :shop :ssr? true :cache :catalogue}]
   ["/categories/:slug" {:name :category :ssr? true :cache :catalogue}]
   ["/products/:slug" {:name :product :ssr? true :cache :catalogue}]
   ["/about" {:name :about :ssr? true :cache :info}]
   ["/contact" {:name :contact :ssr? true :cache :info}]
   ["/delivery-and-payment" {:name :delivery :ssr? true :cache :info}]
   ["/legal/terms-of-sale" {:name :legal-terms-of-sale :ssr? true :cache :info :policy "termsOfSale"}]
   ["/legal/privacy" {:name :legal-privacy :ssr? true :cache :info :policy "privacy"}]
   ["/legal/returns-and-refunds" {:name :legal-returns :ssr? true :cache :info :policy "returns"}]
   ["/legal/order-terms" {:name :legal-order-terms :ssr? true :cache :info :policy "orderTerms"}]
   ["/cart" {:name :cart :shell? true}]
   ["/checkout" {:name :checkout :shell? true}]
   ["/order-placed" {:name :order-placed :shell? true}]
   ["/track-order" {:name :track-order :shell? true}]
   ["/unsubscribe" {:name :unsubscribe :shell? true}]
   ["/admin/login" {:name :admin-login :shell? true :admin? true :access :public}]
   ["/admin/forgot-password" {:name :admin-forgot-password :shell? true :admin? true :access :public}]
   ["/admin/reset-password" {:name :admin-reset-password :shell? true :admin? true :access :public}]
   ["/admin" {:name :admin-orders :shell? true :admin? true :access :staff}]
   ["/admin/orders/:id" {:name :admin-order :shell? true :admin? true :access :staff}]
   ["/admin/products" {:name :admin-products :shell? true :admin? true :access :staff}]
   ["/admin/new-product" {:name :admin-new-product :shell? true :admin? true :access :staff}]
   ["/admin/products/:id" {:name :admin-product :shell? true :admin? true :access :staff}]
   ["/admin/photos" {:name :admin-photos :shell? true :admin? true :access :staff}]
   ["/admin/inbox" {:name :admin-inbox :shell? true :admin? true :access :staff}]
   ["/admin/account" {:name :admin-account :shell? true :admin? true :access :staff}]
   ["/admin/staff" {:name :admin-staff :shell? true :admin? true :access :owner}]])

(def router (r/router routes))

(defn url-decode [s]
  #?(:clj (java.net.URLDecoder/decode ^String s "UTF-8")
     :cljs (gstr/urlDecode s)))

(defn url-encode [s]
  #?(:clj (-> (java.net.URLEncoder/encode ^String s "UTF-8") (str/replace "+" "%20"))
     :cljs (gstr/urlEncode s)))

(defn parse-query
  "\"a=1&b=x%20y\" -> {:a \"1\" :b \"x y\"}. Repeated keys: last wins."
  [qs]
  (if (str/blank? qs)
    {}
    (into {}
          (keep (fn [pair]
                  (let [[k v] (str/split pair #"=" 2)]
                    (when-not (str/blank? k)
                      [(keyword (url-decode k))
                       (url-decode (or v ""))]))))
          (str/split qs #"&"))))

(defn encode-query
  "{:a 1 :q \"x y\"} -> \"a=1&q=x%20y\" (sorted keys so URLs are stable)."
  [m]
  (->> (sort-by (comp name key) m)
       (keep (fn [[k v]]
               (when (and (some? v) (not= "" (str v)))
                 (str (url-encode (name k)) "=" (url-encode (str v))))))
       (str/join "&")))

(defn split-url
  "\"/shop?q=a#x\" -> [\"/shop\" \"q=a\"]."
  [url]
  (let [no-hash (first (str/split url #"#" 2))
        [path qs] (str/split no-hash #"\?" 2)]
    [path qs]))

(defn match-url
  "Match a path (optionally with a query string). Returns
  {:name :path :params :query :data} or nil."
  [url]
  (let [[path qs] (split-url url)
        path (if (and (> (count path) 1) (str/ends-with? path "/"))
               (subs path 0 (dec (count path)))
               path)]
    (when-let [m (r/match-by-path router path)]
      {:name (-> m :data :name)
       :path path
       :params (:path-params m)
       :query (parse-query qs)
       :data (:data m)})))

(defn path-for
  "Build a path for a route name, with optional path params and query map."
  ([route-name] (path-for route-name nil nil))
  ([route-name params] (path-for route-name params nil))
  ([route-name params query]
   (let [m (r/match-by-name router route-name (or params {}))
         path (if (r/partial-match? m)
                (throw (ex-info "Missing path params" {:route route-name :params params}))
                (:path m))
         qs (when (seq query) (encode-query query))]
     (cond-> path (seq qs) (str "?" qs)))))

(defn with-query [path query]
  (let [qs (encode-query query)]
    (cond-> path (seq qs) (str "?" qs))))
