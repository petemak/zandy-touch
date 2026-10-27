#!/usr/bin/env bb
;; Fitness check B23 (part 1): the seed image manifest is complete and consistent.
;; Run from the project root: bb scripts/check/seed_manifest.clj
#_{:clj-kondo/ignore [:duplicate-require]}
(require '[clojure.edn :as edn] '[clojure.java.io :as io] '[clojure.string :as str])

(def images-dir
  "Directory with the seed pictures and their manifest.edn and SOURCES.md."
  "resources/seed/images")
(def manifest
  "The parsed manifest.edn: one map per picture file with :file :sku :alt :source-url :author :licence :retrieved (and :placeholder? for neutral placeholders that still need a real photo)."
  (edn/read-string (slurp (str images-dir "/manifest.edn"))))
(def products
  "The seed products read from resources/seed/products.edn."
  (edn/read-string (slurp "resources/seed/products.edn")))
(def sources-md
  "The text of SOURCES.md, where every picture must be credited."
  (slurp (str images-dir "/SOURCES.md")))
(def required
  "Manifest keys that must be non-blank for every picture."
  [:file :sku :alt :source-url :author :licence :retrieved])
(def licences
  "Licences accepted for seed pictures."
  #{"CC0" "Public Domain"})

(def on-disk
  "Names of the .jpg files in images-dir."
  (->> (.listFiles (io/file images-dir)) (map #(.getName %)) (filter #(str/ends-with? % ".jpg")) set))
(def in-manifest
  "Set of file names listed in the manifest."
  (set (map :file manifest)))

(def problems
  "Lazy sequence of problem strings: files without manifest entries and vice versa, missing fields, disallowed licences, pictures not credited in SOURCES.md, published products without enough pictures, SKUs not starting with SEED-, and product images missing from the manifest."
  (concat
   (for [f (remove in-manifest on-disk)] (str "file without manifest entry: " f))
   (for [f (remove on-disk in-manifest)] (str "manifest entry without file: " f))
   (for [e manifest k required :when (str/blank? (str (get e k)))] (str (:file e) ": missing " k))
   (for [e manifest :when (not (licences (:licence e)))] (str (:file e) ": licence not CC0 or Public Domain: " (:licence e)))
   (for [e manifest :when (not (str/includes? sources-md (:file e)))] (str (:file e) ": not listed in SOURCES.md"))
   (for [p products
         :when (not= :draft (:status p))
         :let [n (count (filter #(= (:sku p) (:sku %)) manifest))
               need (if (#{"SEED-SK-01" "SEED-PF-01"} (:sku p)) 2 1)]
         :when (< n need)]
     (str (:sku p) ": needs " need " image(s), manifest has " n))
   (for [p products :when (not (str/starts-with? (:sku p) "SEED-"))] (str (:sku p) ": SKU must start with SEED-"))
   (for [p products i (:images p) :when (not (in-manifest (:file i)))] (str (:sku p) ": image not in manifest: " (:file i)))))

(if (seq problems)
  (do (doseq [p problems] (println "FAIL" p)) (System/exit 1))
  (println (format "seed manifest ok: %d images (%d placeholders), %d products"
                   (count manifest) (count (filter :placeholder? manifest)) (count products))))
