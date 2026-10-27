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
  "Validates `value` against Malli `schema`, optionally decoding it first with `transformer`.
  Returns the decoded value, or throws validationFailed (422) with details built from the Malli explanation."
  ([schema value] (check schema value nil))
  ([schema value transformer]
   (let [value (if transformer (m/decode schema value transformer) value)]
     (if-let [explanation (m/explain schema value)]
       (errors/validation-failed (details explanation))
       value))))

(def query-transformer
  "Malli transformer for query strings: unknown keys are dropped and strings are coerced to numbers, booleans and keywords per the schema."
  (mt/transformer mt/strip-extra-keys-transformer mt/string-transformer))

(def Uuid
  "Malli schema: a java.util.UUID value (error code \"invalid\")."
  [:fn {:error/code "invalid" :error/message "Not a valid id."} uuid?])

(defn parse-uuid-or-404
  "Parses path id `s` as a UUID. Throws notFound (404) when it is not one, since such an id cannot exist."
  [s]
  (or (parse-uuid (str s)) (errors/fail! :notFound "Not found.")))

(def phone-regex
  "Pattern for a Ugandan mobile number in either local (07xxxxxxxx) or +2567xxxxxxxx form, after spaces and dashes are removed."
  #"^(?:\+256|0)7\d{8}$")

(defn normalize-phone
  "Accepts 07xxxxxxxx or +2567xxxxxxxx (spaces allowed) and returns E.164, else nil."
  [s]
  (when (string? s)
    (let [t (str/replace s #"[\s-]" "")]
      (when (re-matches phone-regex t)
        (if (str/starts-with? t "0") (str "+256" (subs t 1)) t)))))

(def email-regex
  "Deliberately loose email pattern: something@something.something with no spaces."
  #"^[^\s@]+@[^\s@]+\.[^\s@]+$")

(def Phone
  "Malli schema: a string that normalize-phone accepts (error code \"invalidPhone\")."
  [:fn {:error/code "invalidPhone" :error/message "Enter a valid Uganda phone number."} (fn [s] (some? (normalize-phone s)))])
(def Email
  "Malli schema: a string of at most 200 characters matching email-regex (error code \"invalidEmail\")."
  [:and [:string {:max 200}] [:re {:error/code "invalidEmail" :error/message "Enter a valid email address."} email-regex]])

(def UuidString
  "Malli schema: a string that parses as a UUID (error code \"invalid\")."
  [:fn {:error/code "invalid" :error/message "Not a valid id."} (fn [s] (and (string? s) (some? (parse-uuid s))))])

(def Page
  "Malli schema: 1-based page number, integer >= 1."
  [:int {:min 1}])
(def PageSize
  "Malli schema: page size, integer 1 to 100."
  [:int {:min 1 :max 100}])
(def PagingQuery
  "Malli map schema for the optional page and pageSize query parameters."
  [:map [:page {:optional true} Page] [:page-size {:optional true} PageSize]])
