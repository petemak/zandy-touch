#!/usr/bin/env bb
;; Copies design-tokens.css, assets/ and styles/*.css into target/static/ and
;; writes target/static/asset-manifest.edn (logical name -> hashed file name),
;; which the frontend server reads at boot. Source files are never modified
;; (check B16). Run after `npx shadow-cljs release app`:
;;   bb scripts/build/assets.clj
(require '[babashka.fs :as fs]
         '[clojure.edn :as edn]
         '[clojure.string :as str])
(import '(java.security MessageDigest))

(def root (-> *file* fs/absolutize fs/parent fs/parent fs/parent str))
(def out (str root "/target/static"))

(defn sha10 [^bytes bs]
  (let [d (.digest (MessageDigest/getInstance "SHA-256") bs)]
    (subs (apply str (map #(format "%02x" %) d)) 0 10)))

(defn hashed-name [rel bs]
  (let [i (str/last-index-of rel ".")]
    (str (subs rel 0 i) "." (sha10 bs) (subs rel i))))

(defn write-hashed! [logical dest-rel ^bytes bs]
  (let [rel (hashed-name dest-rel bs)
        f (fs/path out rel)]
    (fs/create-dirs (fs/parent f))
    (fs/write-bytes f bs)
    [logical rel]))

;; 1. design tokens (hashed) -------------------------------------------------
(def tokens (fs/read-all-bytes (str root "/design-tokens.css")))
(def tokens-entry (write-hashed! "design-tokens.css" "design-tokens.css" tokens))

;; 2. assets (copied as they are; file names carry no hash, see architecture 4.2)
(when (fs/exists? (str out "/assets")) (fs/delete-tree (str out "/assets")))
(fs/copy-tree (str root "/assets") (str out "/assets"))

;; 3. styles: concatenated in file-name order into one hashed file
(def style-files (sort (map str (fs/glob (str root "/styles") "*.css"))))
(def app-css (.getBytes (str/join "\n" (map slurp style-files)) "UTF-8"))
(def css-entry (write-hashed! "css/app.css" "css/app.css" app-css))

;; 4. the shadow-cljs bundle (written by `shadow-cljs release app`)
(def js-entry
  (let [m (str out "/js/manifest.edn")]
    (when (fs/exists? m)
      (let [mods (edn/read-string (slurp m))
            main (first (filter #(= :main (:module-id %)) mods))]
        (when main ["js/main.js" (str "js/" (:output-name main))])))))

(def manifest (into {} (remove nil?) [tokens-entry css-entry js-entry]))
(spit (str out "/asset-manifest.edn") (pr-str manifest))

;; 5. Portfolio host page (unhashed names, same folder layout)
(let [p (str root "/target/portfolio")]
  (fs/create-dirs (str p "/static/css"))
  (fs/copy (str root "/design-tokens.css") (str p "/static/design-tokens.css") {:replace-existing true})
  (fs/write-bytes (str p "/static/css/app.css") app-css)
  (when (fs/exists? (str p "/static/assets")) (fs/delete-tree (str p "/static/assets")))
  (fs/copy-tree (str root "/assets") (str p "/static/assets"))
  (fs/copy (str root "/scripts/build/portfolio-index.html") (str p "/index.html") {:replace-existing true}))

(println "asset manifest:" manifest)
