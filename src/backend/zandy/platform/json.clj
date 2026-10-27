(ns zandy.platform.json
  "JSON encoding with camelCase keys on the wire and kebab-case keywords inside, ISO 8601 UTC instants."
  (:require [clojure.string :as str]
            [jsonista.core :as j])
  (:import (com.fasterxml.jackson.core JsonGenerator)
           (java.time Instant ZoneOffset)
           (java.time.format DateTimeFormatter)
           (java.util Date)))

;; All instants on the wire are UTC with a trailing Z. Kampala time (UTC+3, no daylight saving) is applied only when the frontend displays a time, so the API never depends on a server time zone.
(def ^:private iso (-> (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss'Z'") (.withZone ZoneOffset/UTC)))

(defn format-instant
  "Formats a java.util.Date or java.time.Instant as ISO 8601 UTC with a Z suffix and whole seconds (the API always speaks UTC; Kampala time is a display concern of the frontend)."
  ^String [inst]
  (.format ^DateTimeFormatter iso (if (instance? Date inst) (.toInstant ^Date inst) ^Instant inst)))

(defn kebab->camel
  "Converts a kebab-case key name to camelCase for the wire; a trailing \"?\" is dropped (:featured? becomes \"featured\")."
  [s]
  (let [[h & more] (str/split (str/replace s #"\?$" "") #"-")]
    (apply str h (map str/capitalize more))))

(defn camel->kebab
  "Converts a camelCase wire key name to kebab-case (\"pageSize\" becomes \"page-size\")."
  [s]
  (-> s (str/replace #"([a-z0-9])([A-Z])" "$1-$2") str/lower-case))

(def ^:private mapper
  (j/object-mapper
   {:decode-key-fn (comp keyword camel->kebab)
    :encode-key-fn (fn [k] (kebab->camel (if (keyword? k) (if-let [n (namespace k)] (str n "/" (name k)) (name k)) (str k))))
    :encoders {Date (fn [d ^JsonGenerator g] (.writeString g (format-instant d)))
               Instant (fn [d ^JsonGenerator g] (.writeString g (format-instant d)))}}))

(defn encode
  "Encodes Clojure data to a JSON string: kebab-case keys become camelCase, Dates and Instants become ISO 8601 UTC strings."
  ^String [x] (j/write-value-as-string x mapper))

(defn decode
  "Parses a JSON string (or stream) into Clojure data with camelCase keys converted to kebab-case keywords."
  [s] (j/read-value s mapper))
