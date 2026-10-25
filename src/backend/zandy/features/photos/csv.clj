(ns zandy.features.photos.csv
  "Pure core of the bulk product import (contract 8.2): decoding the CSV text, checking the headings,
  validating each row and deciding what the row does. No I/O here; the shell is zandy.features.photos.imports."
  (:require [clojure.data.csv :as csv]
            [clojure.string :as str]
            [malli.core :as m]
            [zandy.platform.validation :as validation])
  (:import (java.io StringWriter)
           (java.nio ByteBuffer)
           (java.nio.charset CharacterCodingException CodingErrorAction StandardCharsets)))

(def max-rows 500)
(def max-bytes (* 1024 1024))

(def columns
  "Column headings in template order."
  ["sku" "name" "brand" "ownBrand" "category" "price" "shortDescription" "description"
   "size" "howToUse" "ingredients" "featured" "openingStock"])

(def ^:private column-keys
  "Lower-case heading -> the key of the cell map (kebab-case, which validation turns back into the heading)."
  (into {} (map (fn [c] [(str/lower-case c) (keyword (str/lower-case (str/replace c #"([a-z])([A-Z])" "$1-$2")))])) columns))

;; ---- decoding ------------------------------------------------------------------------------------

(defn decode-text
  "Strict UTF-8 decoding of the file bytes; a leading byte-order mark is dropped. Returns the text, or nil
  when the bytes are not UTF-8 text."
  [^bytes bs]
  (let [bom? (and (>= (alength bs) 3) (= [-17 -69 -65] [(aget bs 0) (aget bs 1) (aget bs 2)]))
        start (if bom? 3 0)
        decoder (doto (.newDecoder StandardCharsets/UTF_8)
                  (.onMalformedInput CodingErrorAction/REPORT)
                  (.onUnmappableCharacter CodingErrorAction/REPORT))]
    (try
      (let [text (str (.decode decoder (ByteBuffer/wrap bs start (- (alength bs) start))))]
        (when-not (str/includes? text "\u0000") text))
      (catch CharacterCodingException _ nil))))

(defn detect-separator
  "Comma or semicolon, whichever the heading line uses more (separators inside quotes are not counted)."
  [text]
  (let [line (first (str/split text #"\r\n|\n|\r" 2))
        [commas semis] (loop [cs (seq line) quoted? false commas 0 semis 0]
                         (if-let [c (first cs)]
                           (cond (= c \") (recur (rest cs) (not quoted?) commas semis)
                                 quoted? (recur (rest cs) quoted? commas semis)
                                 (= c \,) (recur (rest cs) quoted? (inc commas) semis)
                                 (= c \;) (recur (rest cs) quoted? commas (inc semis))
                                 :else (recur (rest cs) quoted? commas semis))
                           [commas semis]))]
    (if (> semis commas) \; \,)))

(defn- blank-row? [cells] (every? str/blank? cells))

(defn parse-table
  "Reads the CSV text. Returns {:rows [{:line n :cells {key string-or-nil}} ...] :columns [...]}, or
  {:file-errors [{:field :code :message}]} when the file as a whole cannot be used. `line` is the
  spreadsheet row number: the heading row is 1, so the first product row is 2 (blank rows are counted,
  not reported)."
  [text]
  (let [records (try (vec (csv/read-csv text :separator (detect-separator text)))
                     (catch Exception _ ::malformed))]
    (if (= ::malformed records)
      {:file-errors [{:field "file" :code "invalidCsv" :message "The file is not a valid CSV file (check the quotation marks)."}]}
      (let [header (mapv (comp str/lower-case str/trim) (or (first records) []))
            keys* (mapv column-keys header)
            unknown (for [[h k] (map vector header keys*) :when (and (not (str/blank? h)) (nil? k))] h)
            dupes (for [[h n] (frequencies (remove str/blank? header)) :when (> n 1)] h)
            data (map-indexed (fn [i r] {:line (+ 2 i) :record r}) (rest records))
            data (remove #(blank-row? (:record %)) data)
            problems (concat
                      (when-not (some #{:sku} keys*)
                        [{:field "sku" :code "missingColumn" :message "The first line must hold the column headings, including sku."}])
                      (for [h unknown]
                        {:field h :code "unknownColumn" :message (str "Unknown column \"" h "\". Use the columns of the template.")})
                      (for [h dupes]
                        {:field h :code "duplicateColumn" :message (str "The column \"" h "\" appears twice.")})
                      (when (> (count data) max-rows)
                        [{:field "file" :code "tooManyRows"
                          :message (str "The file has more than " max-rows " product rows. Split it into smaller files.")}]))]
        (if (seq problems)
          {:file-errors (vec problems)}
          {:rows (mapv (fn [{:keys [line record]}]
                         (let [padded (concat record (repeat (max 0 (- (count keys*) (count record))) ""))
                               cells (->> (map vector keys* padded)
                                          (keep (fn [[k v]] (when k (let [t (str/trim (str v))] [k (when-not (str/blank? t) t)]))))
                                          (into {}))
                               stray (or (some (fn [[k v]] (and (nil? k) (not (str/blank? v)))) (map vector keys* padded))
                                         (some #(not (str/blank? %)) (drop (count keys*) padded)))]
                           {:line line :cells cells :stray-cell? (boolean stray)}))
                       data)})))))

;; ---- cell conversion and row validation --------------------------------------------------------

(defn- yes-no [s]
  (case (some-> s str/lower-case) "yes" true "no" false nil))

(defn- whole-number [s]
  (let [t (str/replace s #"[,\s ]" "")]
    (when (re-matches #"\d{1,12}" t) (parse-long t))))

(defn- convert
  "Turns the text cells into typed values. Returns [values errors]; a cell that cannot be read yields an error
  and no value."
  [cells]
  (reduce
   (fn [[vals errs] [k v]]
     (if (nil? v)
       [vals errs]
       (case k
         (:own-brand :featured)
         (if-some [b (yes-no v)]
           [(assoc vals k b) errs]
           [vals (conj errs {:field (if (= k :own-brand) "ownBrand" "featured") :code "invalid" :message "Use yes or no."})])
         :price
         (if-let [n (whole-number v)]
           [(assoc vals k n) errs]
           [vals (conj errs {:field "price" :code "invalid" :message "Price must be a whole number of shillings."})])
         :opening-stock
         (if-let [n (and (re-matches #"[\d,\s ]+" v) (whole-number v))]
           [(assoc vals k n) errs]
           [vals (conj errs {:field "openingStock" :code "invalid" :message "Opening stock must be a whole number."})])
         [(assoc vals k v) errs])))
   [{} []]
   cells))

(defn- text [field min max]
  [:string {:min min :max max
            :error/message (if (> min 0)
                             (format "%s must be %d to %d characters." field min max)
                             (format "%s must be at most %d characters." field max))}])

(def RowSchema
  [:map {:closed true}
   [:sku [:and [:string {:min 1 :max 40 :error/message "SKU must be 1 to 40 characters."}]
          [:re {:error/code "invalid" :error/message "A SKU may contain only letters, digits and -."} #"^[A-Za-z0-9-]+$"]]]
   [:name {:optional true} (text "Name" 2 120)]
   [:brand {:optional true} (text "Brand" 1 80)]
   [:own-brand {:optional true} :boolean]
   [:category {:optional true} :string]
   [:price {:optional true} [:int {:min 100 :max 100000000 :error/message "Price must be between 100 and 100,000,000 shillings."}]]
   [:short-description {:optional true} (text "Short description" 0 200)]
   [:description {:optional true} (text "Description" 0 4000)]
   [:size {:optional true} (text "Size" 0 40)]
   [:how-to-use {:optional true} (text "How to use" 0 1000)]
   [:ingredients {:optional true} (text "Ingredients" 0 1000)]
   [:featured {:optional true} :boolean]
   [:opening-stock {:optional true} [:int {:min 0 :max 100000 :error/message "Opening stock must be between 0 and 100000."}]]])

(defn normalize-sku [s] (some-> s str/trim str/upper-case))

(defn- resolve-category
  "The category slug for a name or slug typed in the sheet, or nil."
  [categories s]
  (let [t (str/lower-case (str/trim s))
        slugged (-> t (str/replace "&" " and ") (str/replace #"[^a-z0-9]+" "-") (str/replace #"^-+|-+$" ""))]
    (some (fn [{:keys [slug name]}]
            (when (or (= t slug) (= slugged slug) (= t (str/lower-case name))) slug))
          categories)))

(defn- schema-errors [values]
  (when-let [explanation (m/explain RowSchema values)]
    (validation/details explanation)))

(defn evaluate
  "Validates every row and decides its action. `categories` is a seq of {:slug :name}; `known-skus` the set of
  upper-case SKUs that already exist. Returns one map per row:
  {:line :sku :action :create/:update/:rejected :fields <typed values> :warnings :errors}."
  [rows categories known-skus]
  (:out
   (reduce
    (fn [{:keys [seen out]} {:keys [line cells stray-cell?]}]
      (let [[values conv-errors] (convert cells)
            sku (normalize-sku (:sku cells))
            existing? (and sku (contains? known-skus sku))
            slug (when-let [c (:category cells)] (resolve-category categories c))
            values (cond-> (dissoc values :category)
                     slug (assoc :category slug)
                     sku (assoc :sku sku))
            errors (vec
                    (concat
                     conv-errors
                     (when (nil? sku) [{:field "sku" :code "required" :message "Every row needs a SKU."}])
                     (when (and sku (contains? seen sku))
                       [{:field "sku" :code "duplicateSku" :message "This SKU appears earlier in the file; only its first row is used."}])
                     (when (and (:category cells) (nil? slug))
                       [{:field "category" :code "unknownCategory" :message "This is not one of the shop's categories."}])
                     (when stray-cell?
                       [{:field "row" :code "extraCell" :message "This row has more cells than the file has column headings."}])
                     (when-not existing?
                       (for [[k label] [[:name "name"] [:brand "brand"] [:category "category"]]
                             :when (nil? (get cells k))]
                         {:field label :code "required" :message (str "A new product needs a " label ".")}))
                     ;; schema checks on the values that were read (the category was resolved above)
                     (remove (fn [e] (some #(= (:field e) (:field %)) conv-errors))
                             (schema-errors values))))
            errors (->> errors (reduce (fn [acc e] (if (some #(and (= (:field %) (:field e)) (= (:code %) (:code e))) acc) acc (conj acc e))) []))
            warnings (when (and existing? (some? (:opening-stock cells)) (empty? errors))
                       [{:field "openingStock" :code "ignoredForExisting"}])]
        {:seen (cond-> seen sku (conj sku))
         :out (conj out {:line line :sku sku
                         :action (cond (seq errors) :rejected existing? :update :else :create)
                         :fields values :warnings (vec warnings) :errors errors})}))
    {:seen #{} :out []}
    rows)))

(defn report
  "The response body of the import from the evaluated and applied rows. Each row has :status
  (\"created\" \"updated\" \"rejected\"), :product-id, :warnings and :errors."
  [dry-run? rows]
  {:dry-run (boolean dry-run?)
   :summary {:rows (count rows)
             :created (count (filter #(= "created" (:status %)) rows))
             :updated (count (filter #(= "updated" (:status %)) rows))
             :rejected (count (filter #(= "rejected" (:status %)) rows))}
   :rows (mapv #(select-keys % [:line :sku :status :product-id :warnings :errors]) rows)})

;; ---- template ----------------------------------------------------------------------------------------

(def template-rows
  [["ZT-SKIN-001" "Radiance Face Serum" "Zandy Touch" "yes" "Skincare" "85000"
    "A light daily serum." "A light serum for daily use, morning and evening.\n\nShake gently before use." "30 ml"
    "Apply two drops to clean skin." "" "yes" "20"]
   ["ZT-PERF-002" "Evening Mist" "Example Brand" "no" "Perfumes" "120,000"
    "A soft evening body mist." "" "100 ml" "" "" "no" "10"]])

(defn template-csv
  "The CSV template: heading line in column order and two example rows."
  []
  (let [w (StringWriter.)]
    (csv/write-csv w (into [columns] template-rows) :newline :cr+lf)
    (str w)))
