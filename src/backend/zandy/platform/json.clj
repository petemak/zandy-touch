(ns zandy.platform.json
  "JSON encoding with camelCase keys on the wire and kebab-case keywords inside, ISO 8601 UTC instants."
  (:require [clojure.string :as str]
            [jsonista.core :as j])
  (:import (com.fasterxml.jackson.core JsonGenerator)
           (java.time Instant ZoneOffset)
           (java.time.format DateTimeFormatter)
           (java.util Date)))

(def ^:private iso (-> (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss'Z'") (.withZone ZoneOffset/UTC)))

(defn format-instant ^String [inst]
  (.format ^DateTimeFormatter iso (if (instance? Date inst) (.toInstant ^Date inst) ^Instant inst)))

(defn kebab->camel [s]
  (let [[h & more] (str/split (str/replace s #"\?$" "") #"-")]
    (apply str h (map str/capitalize more))))

(defn camel->kebab [s]
  (-> s (str/replace #"([a-z0-9])([A-Z])" "$1-$2") str/lower-case))

(def ^:private mapper
  (j/object-mapper
   {:decode-key-fn (comp keyword camel->kebab)
    :encode-key-fn (fn [k] (kebab->camel (if (keyword? k) (if-let [n (namespace k)] (str n "/" (name k)) (name k)) (str k))))
    :encoders {Date (fn [d ^JsonGenerator g] (.writeString g (format-instant d)))
               Instant (fn [d ^JsonGenerator g] (.writeString g (format-instant d)))}}))

(defn encode ^String [x] (j/write-value-as-string x mapper))

(defn decode [s] (j/read-value s mapper))
