(ns zandy.ssr.seo-routes
  "robots.txt and sitemap.xml (architecture.md section 2.3, `ssr.seo-routes`).
  JVM only. Both depend on SITE_INDEXABLE: on staging (false) robots.txt
  blocks everything and the sitemap is empty, so a staging site is never
  indexed; in production robots.txt lists the private areas and the sitemap is
  built from GET /api/sitemap-entries."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [zandy.shared.routes :as routes]
            [zandy.ssr.api-client :as api]))

(defn robots
  "robots.txt response. Argument: config (:site-indexable, :site-base-url).
  Indexable sites allow crawling except the cart, checkout, order, tracking,
  unsubscribe and admin paths, and point to the sitemap; other sites get
  \"Disallow: /\". Cacheable for an hour at the CDN (s-maxage=3600). Pure."
  [{:keys [site-indexable site-base-url]}]
  {:status 200
   :headers {"Content-Type" "text/plain; charset=utf-8" "Cache-Control" "public, s-maxage=3600"}
   :body (if site-indexable
           (str "User-agent: *\nDisallow: /admin\nDisallow: /cart\nDisallow: /checkout\n"
                "Disallow: /order-placed\nDisallow: /track-order\nDisallow: /unsubscribe\n"
                "Sitemap: " (str/replace site-base-url #"/+$" "") "/sitemap.xml\n")
           "User-agent: *\nDisallow: /\n")})

(defn- escape-xml
  "Escapes &, < and > in `s` for use inside XML text."
  [s]
  (-> s (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;")))

(defn- url-entry
  "One sitemap <url> element for `base` + `path`, with <lastmod> when `lastmod`
  is given."
  [base path lastmod]
  (str "<url><loc>" (escape-xml (str base path)) "</loc>"
       (when lastmod (str "<lastmod>" (escape-xml lastmod) "</lastmod>"))
       "</url>"))

(def static-paths
  "Fixed public paths always in the sitemap (home, shop, about, contact,
  delivery and the four legal pages)."
  ["/" "/shop" "/about" "/contact" "/delivery-and-payment"
                   "/legal/terms-of-sale" "/legal/privacy" "/legal/returns-and-refunds" "/legal/order-terms"])

(defn sitemap
  "sitemap.xml response. Arguments: `api` (api-client), config
  (:site-indexable, :site-base-url) and `request-id`. Not indexable: an empty
  urlset. Indexable: the fixed paths plus every category and product from GET
  /api/sitemap-entries ({:products [{:slug :updatedAt}] :categories
  [{:slug}]}) with lastmod for products. If the API call fails it returns 503
  with Retry-After and no-store, so a crawler never caches a partial sitemap.
  Side effect: one backend request; reads resources/sitemap-head.xml."
  [api {:keys [site-indexable site-base-url]} request-id]
  (let [base (str/replace site-base-url #"/+$" "")
        xml-head (str/trim (slurp (io/resource "sitemap-head.xml")))
        headers {"Content-Type" "application/xml; charset=utf-8"}]
    (if-not site-indexable
      {:status 200 :headers (assoc headers "Cache-Control" "public, s-maxage=3600")
       :body (str xml-head "</urlset>")}
      (let [r (api/fetch api {:path "/api/sitemap-entries"} request-id)]
        (if (<= 200 (or (:status r) 0) 299)
          (let [{:keys [products categories]} (:body r)]
            {:status 200 :headers (assoc headers "Cache-Control" "public, s-maxage=3600")
             :body (str xml-head
                        (apply str (map #(url-entry base % nil) static-paths))
                        (apply str (map #(url-entry base (routes/path-for :category {:slug (:slug %)}) nil) categories))
                        (apply str (map #(url-entry base (routes/path-for :product {:slug (:slug %)}) (:updatedAt %)) products))
                        "</urlset>")})
          {:status 503 :headers {"Content-Type" "text/plain; charset=utf-8" "Cache-Control" "no-store" "Retry-After" "30"}
           :body "Sitemap temporarily unavailable"})))))
