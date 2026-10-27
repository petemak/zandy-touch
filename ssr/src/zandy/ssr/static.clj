(ns zandy.ssr.static
  "Static files and the asset manifest (architecture.md section 2.3,
  `ssr.static`). JVM only. Serves STATIC_DIR under /static/ with the cache
  headers of architecture.md D2, refusing paths that escape the directory, and
  reads asset-manifest.edn (logical name -> fingerprinted file name) to build
  the CSS and script links of each page."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io File)))

(def content-types
  "File extension -> Content-Type for the files served from STATIC_DIR;
  anything else is served as application/octet-stream."
  {"js" "text/javascript; charset=utf-8" "css" "text/css; charset=utf-8"
   "svg" "image/svg+xml" "png" "image/png" "jpg" "image/jpeg" "webp" "image/webp"
   "woff2" "font/woff2" "webmanifest" "application/manifest+json" "json" "application/json"
   "ico" "image/x-icon" "map" "application/json" "txt" "text/plain; charset=utf-8"})

(def immutable
  "Cache-Control for fingerprinted files that never change under the same name."
  "public, max-age=31536000, immutable")
(def one-day
  "Cache-Control for assets whose names do not change (logo, icons, badges,
  favicons): revalidate daily, serve stale for a week while refreshing."
  "public, max-age=86400, stale-while-revalidate=604800")

(defn- extension
  "File extension of `name` without the dot, or nil when there is none."
  [^String name]
  (let [i (.lastIndexOf name ".")] (when (pos? i) (subs name (inc i)))))

(defn cache-control-for
  "Cache-Control value for the path `rel` below /static/. Fingerprinted bundles
  (js/), CSS (css/, design-tokens.*) and fonts (assets/fonts/) are immutable
  for a year; everything else (logo, icons, badges, favicons, photos)
  revalidates daily. Pure."
  [rel]
  (cond
    (or (str/starts-with? rel "js/") (str/starts-with? rel "css/")
        (str/starts-with? rel "design-tokens.")
        (str/starts-with? rel "assets/fonts/")) immutable
    :else one-day))

(defn resolve-file
  "The file for the path `rel` below `static-dir`, or nil. Refuses anything
  that resolves outside the directory (such as ../ sequences) and anything
  that is not a regular file. Reads the file system only."
  [static-dir rel]
  (let [root (.getCanonicalFile (io/file static-dir))
        f (.getCanonicalFile (io/file root rel))]
    (when (and (.startsWith (.getPath f) (str (.getPath root) File/separator))
               (.isFile f))
      f)))

(defn serve
  "Serves one static file. Arguments: config (:static-dir) and `rel` (path
  below /static/). Returns a 200 response whose :body is the java.io.File,
  with Content-Type, Cache-Control and nosniff; or a 404 text response with
  no-store when the file is missing, empty or outside the directory."
  [{:keys [static-dir]} rel]
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
  "Reads asset-manifest.edn from the static directory. Argument: config
  (:static-dir). Returns a map of logical name -> file name; {} when the file
  does not exist (development, where unhashed names are used). Throws if the
  file exists but is not valid EDN."
  [{:keys [static-dir]}]
  (let [f (io/file static-dir "asset-manifest.edn")]
    (if (.isFile f) (edn/read-string (slurp f)) {})))

(defn current-manifest
  "Same as read-manifest, called on every page request. Why: the file is tiny,
  and reading it each time means a new release's fingerprinted bundles are
  used without restarting the server."
  [config]
  (read-manifest config))

(defn assets
  "Asset links for the document head. Argument: `manifest` (map from
  read-manifest). Returns {:css [design-tokens, app.css] :js main.js
  :font-preload [two woff2 fonts]}, all as /static/ URLs, using the
  fingerprinted names from the manifest or the plain names when none is
  listed. Pure."
  [manifest]
  (let [p (fn [logical default] (str "/static/" (get manifest logical default)))]
    {:css [(p "design-tokens.css" "design-tokens.css")
           (p "css/app.css" "css/app.css")]
     :js (p "js/main.js" "js/main.js")
     :font-preload ["/static/assets/fonts/inter-latin-400.woff2"
                    "/static/assets/fonts/playfair-display-latin-600.woff2"]}))
