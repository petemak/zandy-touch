(ns zandy.features.photos.logic
  "Photos slice, logic role: pure rules for picture file names (contract 8.2): `<SKU>[-<1..6>].<ext>`, SKU matched case-insensitively,
  longest SKU wins."
  (:require [clojure.string :as str]))

(def unknown-sku-message
  "Message returned when no known SKU starts the file name."
  "No product has a SKU at the start of this file name.")
(def bad-suffix-message
  "Message returned when a SKU matches but what follows it is not -1 to -6."
  "After the SKU the file name may only have -1 to -6 (the picture position), for example ZT-SKIN-001-2.jpg.")

(defn base-name
  "The file name without any directory part (browsers send the bare name, other clients may not)."
  [filename]
  (last (str/split (str filename) #"[/\\]")))

(defn stem
  "The file name without its extension, upper-cased."
  [filename]
  (str/upper-case (str/replace (base-name filename) #"\.[A-Za-z0-9]{1,8}$" "")))

(defn match-file
  "Matches a picture file name against the known SKUs (upper-case strings). Returns {:sku s :position n-or-nil}
  or {:error {:code \"unknownSku\" :message ...}}. `ZT-SKIN-0011.jpg` does not belong to `ZT-SKIN-001`: after the
  SKU only nothing or -1 to -6 may follow. When several SKUs fit, the longest wins."
  [filename skus]
  (let [s (stem filename)
        fits (for [sku skus
                   :when (str/starts-with? s sku)
                   :let [rest* (subs s (count sku))
                         [_ pos] (re-matches #"(?:-([1-6]))?" rest*)]
                   ;; Only nothing or -1..-6 may follow the SKU, so ZT-SKIN-0011.jpg does not attach to ZT-SKIN-001. The number after the dash is the 1-based picture slot.
                   :when (re-matches #"(?:-[1-6])?" rest*)]
               {:sku sku :position (some-> pos parse-long)})]
    ;; Longest SKU first: with SKUs AB and AB-1 on file, "AB-1.jpg" belongs to AB-1 rather than to slot 1 of AB.
    (if-let [best (first (sort-by (comp - count :sku) fits))]
      best
      {:error {:code "unknownSku"
               :message (if (some #(str/starts-with? s %) skus) bad-suffix-message unknown-sku-message)}})))
