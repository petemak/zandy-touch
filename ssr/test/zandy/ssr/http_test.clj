(ns zandy.ssr.http-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [zandy.ssr.api-client :as api-client]
            [zandy.ssr.config :as config]
            [zandy.ssr.http :as http]
            [zandy.ssr.stub-api :as stub]))

(def ^:dynamic *ctx* nil)

(defn- ctx-for [api-port]
  (let [cfg (config/load-config {:api-base-url (str "http://127.0.0.1:" api-port)
                                 :site-base-url "https://shop.example.test"
                                 :api-timeout-ms 2000
                                 :static-dir "../target/static"})]
    {:config cfg :api (api-client/create cfg) :manifest {}}))

(use-fixtures :once
  (fn [t]
    (let [server (stub/start 0)
          port (.getPort (.getAddress server))]
      (try (binding [*ctx* (ctx-for port)] (t))
           (finally (.stop server 0))))))

(defn- get! [path & [headers]]
  (http/handler *ctx* {:request-method :get :uri (first (str/split path #"\?")) :query-string (second (str/split path #"\?"))
                       :headers (or headers {})}))

(deftest pages-render-with-seo-in-first-html
  (doseq [path ["/" "/shop" "/categories/skincare" "/products/radiance-vitamin-c-serum-30-ml" "/about" "/contact"
                "/delivery-and-payment" "/legal/terms-of-sale" "/legal/order-terms"]]
    (let [{:keys [status body headers]} (get! path)]
      (testing path
        (is (= 200 status))
        (is (= 1 (count (re-seq #"<title>" body))))
        (is (= 1 (count (re-seq #"rel=\"canonical\"" body))))
        (is (= 1 (count (re-seq #"application/ld\+json" body))))
        (is (= 1 (count (re-seq #"<h1[ >]" body))))
        (is (str/includes? (get headers "Content-Security-Policy") "connect-src 'self'"))
        (is (get headers "ETag"))))))

(deftest cache-headers
  (is (str/includes? (get-in (get! "/") [:headers "Cache-Control"]) "s-maxage=60"))
  (is (str/includes? (get-in (get! "/about") [:headers "Cache-Control"]) "s-maxage=600"))
  (is (= "no-cache" (get-in (get! "/cart") [:headers "Cache-Control"])))
  (is (= "no-cache" (get-in (get! "/admin/products") [:headers "Cache-Control"]))))

(deftest not-found-pages
  (let [{:keys [status body headers]} (get! "/products/does-not-exist")]
    (is (= 404 status))
    (is (str/includes? body "We could not find that page</h1>"))
    (is (str/includes? (get headers "Cache-Control") "s-maxage=60")))
  (is (= 404 (:status (get! "/categories/unknown"))))
  (is (= 404 (:status (get! "/totally/unknown")))))

(deftest conditional-get
  (let [r1 (get! "/about")
        r2 (get! "/about" {"if-none-match" (get-in r1 [:headers "ETag"])})]
    (is (= 304 (:status r2)))))

(deftest listing-queries-are-normalised
  (let [r (get! "/shop?utm_source=x&q=serum")]
    (is (= 302 (:status r)))
    (is (= "/shop?q=serum" (get-in r [:headers "Location"]))))
  (is (= 200 (:status (get! "/shop?q=serum")))))

(deftest document-shells-are-noindex
  (let [body (:body (get! "/checkout"))]
    (is (str/includes? body "content=\"noindex, follow\""))))

(deftest robots-and-sitemap
  (testing "staging: indexing blocked"
    (is (= "User-agent: *\nDisallow: /\n" (:body (get! "/robots.txt"))))
    (is (= "noindex" (get-in (get! "/") [:headers "X-Robots-Tag"])))
    (is (not (str/includes? (:body (get! "/sitemap.xml")) "<loc>"))))
  (testing "production: sitemap lists product URLs on the configured base URL"
    (binding [*ctx* (assoc-in *ctx* [:config :site-indexable] true)]
      (let [robots (:body (get! "/robots.txt"))
            sitemap (:body (get! "/sitemap.xml"))]
        (is (str/includes? robots "Sitemap: https://shop.example.test/sitemap.xml"))
        (is (str/includes? sitemap "<loc>https://shop.example.test/products/radiance-vitamin-c-serum-30-ml</loc>"))
        (is (nil? (get-in (get! "/") [:headers "X-Robots-Tag"])))))))

(deftest static-files
  (is (= 404 (:status (get! "/static/../deps.edn"))))
  (is (= 404 (:status (get! "/static/nothing.js")))))

(deftest api-down-gives-calm-503
  (binding [*ctx* (ctx-for 1)]
    (let [{:keys [status headers body]} (get! "/")]
      (is (= 503 status))
      (is (= "no-store" (get headers "Cache-Control")))
      (is (get headers "Retry-After"))
      (is (str/includes? body "We are having trouble loading the shop"))
      (is (not (str/includes? body "Exception"))))))

(deftest only-get-and-head
  (is (= 405 (:status (http/handler *ctx* {:request-method :post :uri "/" :headers {}})))))
