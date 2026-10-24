(ns zandy.ssr.log
  "Structured JSON log lines on stdout, same shape as the backend: one object
  per line with time, level, event and context. Request ids are included;
  query strings, phone numbers and emails never are."
  (:require [jsonista.core :as json])
  (:import (java.time Instant)))

(defn- write-line! [^String line]
  (let [out *out*]
    (locking out
      (.write out line)
      (.write out "\n")
      (.flush out))))

(defn event
  "Log one event. `level` is :info, :warn or :error; `data` a flat map."
  [level event-name data]
  (write-line!
   (json/write-value-as-string
    (merge {:time (str (Instant/now))
            :level (name level)
            :service "ssr"
            :event (name event-name)}
           data))))

(defn info [event-name data] (event :info event-name data))
(defn warn [event-name data] (event :warn event-name data))
(defn error [event-name data] (event :error event-name data))
