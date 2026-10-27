(ns zandy.platform.log
  "Structured JSON log lines. This namespace decides which fields are safe to log."
  (:require [clojure.string :as str]
            [zandy.platform.json :as json])
  (:import (java.util Date)))

(def ^:dynamic *sink*
  "Function of one string (a JSON line). Rebound in tests to capture output."
  (fn [^String line] (.println System/out line)))

(def ^:private sensitive
  #{:password :new-password :current-password :temporary-password :token :csrf-token :cookie
    :authorization :phone :customer-phone :email :customer-email :name :customer-name
    :address-line :directions :message :note :password-hash :smtp-password :secret :body})

;; Allowlist-by-exclusion: any key in `sensitive` (credentials, contact details, free text) is replaced, at any depth.
;; Throwables are reduced to the class name and long strings are cut at 500 characters.
(defn- scrub [x]
  (cond
    (map? x) (into {} (map (fn [[k v]] [k (if (contains? sensitive k) "[redacted]" (scrub v))])) x)
    (sequential? x) (mapv scrub x)
    (instance? Throwable x) (str (.getName (class x)))
    (string? x) (if (> (count x) 500) (subs x 0 500) x)
    :else x))

(defn log!
  "Writes one JSON line: level, event name, timestamp and scrubbed context."
  [level event data]
  (try
    (*sink* (json/encode (merge {:ts (Date.) :level (name level) :event (name event)} (scrub data))))
    ;; Logging must never break a request.
    (catch Throwable _ nil)))

(defn info
  "Logs `event` (keyword) with context map `data` at info level. See log!."
  [event data] (log! :info event data))
(defn warn
  "Logs `event` (keyword) with context map `data` at warn level. See log!."
  [event data] (log! :warn event data))
(defn error
  "Logs `event` (keyword) with context map `data` at error level. See log!."
  [event data] (log! :error event data))

(defn exception-data
  "Context map {:exception class-name :exception-message single-line message} for logging an exception.
  The message is included, so callers must not put secrets in exception messages."
  [^Throwable ex]
  {:exception (.getName (class ex))
   :exception-message (str/replace (str (.getMessage ex)) #"\s+" " ")})
