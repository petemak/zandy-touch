(ns zandy.ssr.seo-routes
  "robots.txt and sitemap.xml. Blocked on staging (SITE_INDEXABLE=false)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [zandy.shared.routes :as routes]
            [zandy.ssr.api-client :as api]))

(defn robots [{:keys [site-indexable site-base-url]}]
  {:status 200
   :headers {"Content-Type" "text/plain; charset=utf-8" "Cache-Control" "public, s-maxage=3600"}
   :body (if site-indexable
           (str "User-agent: *\nDisallow: /admin\nDisallow: /cart\nDisallow: /checkout\n"
                "Disallow: /order-placed\nDisallow: /track-order\nDisallow: /unsubscribe\n"
                "Sitemap: " (str/replace site-base-url #"/+$" "") "/sitemap.xml\n")
           "User-agent: *\nDisallow: /\n")})

(defn- escape-xml [s]
  (-> s (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;")))

(defn- url-entry [base path lastmod]
  (str "<url><loc>" (escape-xml (str base path)) "</loc>"
       (when lastmod (str "<lastmod>" (escape-xml lastmod) "</lastmod>"))
       "</url>"))

(def static-paths ["/" "/shop" "/about" "/contact" "/delivery-and-payment"
                   "/legal/terms-of-sale" "/legal/privacy" "/legal/returns-and-refunds" "/legal/order-terms"])

(defn sitemap [api {:keys [site-indexable site-base-url]} request-id]
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
