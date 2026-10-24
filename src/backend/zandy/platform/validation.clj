(ns zandy.platform.validation
  "Boundary validation with Malli: input normalisation, decoding and error details in the contract shape."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.transform :as mt]
            [zandy.platform.errors :as errors]
            [zandy.platform.json :as json]))

(defn normalize
  "Trims strings, turns empty strings into nil, and (when drop-nils?) removes nil-valued map entries,
  so empty optional strings count as absent."
  [x drop-nils?]
  (cond
    (map? x) (let [m (update-vals x #(normalize % drop-nils?))]
               (if drop-nils? (into {} (remove (fn [[_ v]] (nil? v))) m) m))
    (sequential? x) (mapv #(normalize % drop-nils?) x)
    (string? x) (let [s (str/trim x)] (when-not (str/blank? s) s))
    :else x))

(defn- field-name [in]
  (reduce (fn [acc k]
            (cond
              (integer? k) (str acc "[" k "]")
              (keyword? k) (let [s (json/kebab->camel (name k))] (if (str/blank? acc) s (str acc "." s)))
              :else acc))
          "" in))

(defn- error->detail [{:keys [in schema type]}]
  (let [props (try (m/properties schema) (catch Throwable _ nil))
        field (or (:error/field props) (field-name in))
        code (or (:error/code props)
                 (case type
                   :malli.core/missing-key "required"
                   :malli.core/extra-key "unknownField"
                   "invalid"))
        message (or (:error/message props)
                    (case code
                      "required" "This field is required."
                      "unknownField" "This field is not allowed."
                      "Please check this field."))]
    {:field field :code code :message message}))

(defn details
  "Builds the error.details array from a Malli explanation."
  [explanation]
  (->> (:errors explanation)
       (map error->detail)
       (distinct)
       (reduce (fn [acc d] (if (some #(= (:field %) (:field d)) acc) acc (conj acc d))) [])
       (take 50)
       vec))

(defn check
  "Validates value against the schema. Returns the (decoded) value or throws validationFailed."
  ([schema value] (check schema value nil))
  ([schema value transformer]
   (let [value (if transformer (m/decode schema value transformer) value)]
     (if-let [explanation (m/explain schema value)]
       (errors/validation-failed (details explanation))
       value))))

(def query-transformer
  (mt/transformer mt/strip-extra-keys-transformer mt/string-transformer))

(def Uuid
  [:fn {:error/code "invalid" :error/message "Not a valid id."} uuid?])

(defn parse-uuid-or-404
  "Path ids that are not UUIDs cannot exist."
  [s]
  (or (parse-uuid (str s)) (errors/fail! :notFound "Not found.")))

(def phone-regex #"^(?:\+256|0)7\d{8}$")

(defn normalize-phone
  "Accepts 07xxxxxxxx or +2567xxxxxxxx (spaces allowed) and returns E.164, else nil."
  [s]
  (when (string? s)
    (let [t (str/replace s #"[\s-]" "")]
      (when (re-matches phone-regex t)
        (if (str/starts-with? t "0") (str "+256" (subs t 1)) t)))))

(def email-regex #"^[^\s@]+@[^\s@]+\.[^\s@]+$")

(def Phone [:fn {:error/code "invalidPhone" :error/message "Enter a valid Uganda phone number."} (fn [s] (some? (normalize-phone s)))])
(def Email [:and [:string {:max 200}] [:re {:error/code "invalidEmail" :error/message "Enter a valid email address."} email-regex]])

(def UuidString
  [:fn {:error/code "invalid" :error/message "Not a valid id."} (fn [s] (and (string? s) (some? (parse-uuid s))))])

(def Page [:int {:min 1}])
(def PageSize [:int {:min 1 :max 100}])
(def PagingQuery [:map [:page {:optional true} Page] [:page-size {:optional true} PageSize]])
