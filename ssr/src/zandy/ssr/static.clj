(ns zandy.ssr.static
  "Serve STATIC_DIR under /static/ and read the asset manifest."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io File)))

(def content-types
  {"js" "text/javascript; charset=utf-8" "css" "text/css; charset=utf-8"
   "svg" "image/svg+xml" "png" "image/png" "jpg" "image/jpeg" "webp" "image/webp"
   "woff2" "font/woff2" "webmanifest" "application/manifest+json" "json" "application/json"
   "ico" "image/x-icon" "map" "application/json" "txt" "text/plain; charset=utf-8"})

(def immutable "public, max-age=31536000, immutable")
(def one-day "public, max-age=86400, stale-while-revalidate=604800")

(defn- extension [^String name]
  (let [i (.lastIndexOf name ".")] (when (pos? i) (subs name (inc i)))))

(defn cache-control-for
  "Fingerprinted bundles, CSS and fonts are immutable for a year; logo, icons,
  badges and favicons keep their names, so they revalidate daily
  (architecture.md section 6, D2)."
  [rel]
  (cond
    (or (str/starts-with? rel "js/") (str/starts-with? rel "css/")
        (str/starts-with? rel "design-tokens.")
        (str/starts-with? rel "assets/fonts/")) immutable
    :else one-day))

(defn resolve-file
  "The file for a path below /static/, or nil. Refuses anything that escapes
  the directory."
  [static-dir rel]
  (let [root (.getCanonicalFile (io/file static-dir))
        f (.getCanonicalFile (io/file root rel))]
    (when (and (.startsWith (.getPath f) (str (.getPath root) File/separator))
               (.isFile f))
      f)))

(defn serve [{:keys [static-dir]} rel]
  (if-let [^File f (and (seq rel) (resolve-file static-dir rel))]
    {:status 200
     :headers {"Content-Type" (get content-types (extension (.getName f)) "application/octet-stream")
               "Cache-Control" (cache-control-for rel)
               "X-Content-Type-Options" "nosniff"}
     :body f}
    {:status 404
     :headers {"Content-Type" "text/plain; charset=utf-8" "Cache-Control" "no-store"}
     :body "Not found"}))

(defn read-manifest
  "asset-manifest.edn maps logical names to hashed file names. Missing in
  development: unhashed names are used."
  [{:keys [static-dir]}]
  (let [f (io/file static-dir "asset-manifest.edn")]
    (if (.isFile f) (edn/read-string (slurp f)) {})))

(defn current-manifest
  "Read per request (a few hundred bytes), so a new release is picked up without a restart."
  [config]
  (read-manifest config))

(defn assets
  "{:css [hrefs] :js href :font-preload [hrefs]} for the document head."
  [manifest]
  (let [p (fn [logical default] (str "/static/" (get manifest logical default)))]
    {:css [(p "design-tokens.css" "design-tokens.css")
           (p "css/app.css" "css/app.css")]
     :js (p "js/main.js" "js/main.js")
     :font-preload ["/static/assets/fonts/inter-latin-400.woff2"
                    "/static/assets/fonts/playfair-display-latin-600.woff2"]}))
