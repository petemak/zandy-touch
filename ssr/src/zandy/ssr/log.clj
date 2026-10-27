(ns zandy.ssr.log
  "Structured JSON logging for the frontend server (architecture.md section
  2.3, `ssr.log`). JVM only. One JSON object per line on stdout with time,
  level, service \"ssr\" and event, in the same shape as the backend logs.
  Callers pass request ids and counts; they must never pass query strings,
  phone numbers or email addresses."
  (:require [jsonista.core :as json])
  (:import (java.time Instant)))

(defn- write-line!
  "Writes `line` and a newline to *out* and flushes, holding a lock so lines
  from concurrent requests are never interleaved."
  [^String line]
  (let [out *out*]
    (locking out
      (.write out line)
      (.write out "\n")
      (.flush out))))

(defn event
  "Logs one event. Arguments: `level` (:info, :warn or :error), `event-name`
  (keyword) and `data` (flat map of JSON-friendly values, merged over :time,
  :level, :service and :event). Side effect: one JSON line on stdout. Throws
  if `data` holds values jsonista cannot encode."
  [level event-name data]
  (write-line!
   (json/write-value-as-string
    (merge {:time (str (Instant/now))
            :level (name level)
            :service "ssr"
            :event (name event-name)}
           data))))

(defn info
  "Logs an :info event. `event-name` is a keyword, `data` a flat map merged
  into the JSON object. Side effect: one line on stdout."
  [event-name data] (event :info event-name data))
(defn warn
  "Logs a :warn event; same arguments as `info`."
  [event-name data] (event :warn event-name data))
(defn error
  "Logs an :error event; same arguments as `info`."
  [event-name data] (event :error event-name data))
