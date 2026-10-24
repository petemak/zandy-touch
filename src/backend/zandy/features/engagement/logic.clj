(ns zandy.features.engagement.logic
  "Pure rules for subscriptions, unsubscribe tokens and the CSV export."
  (:require [clojure.string :as str]
            [zandy.platform.json :as json]))

(def consent-text-version "2026-10-01")

(defn csv-cell
  "Quotes a cell when needed and neutralises spreadsheet formulas (cells starting with = + - @)."
  [v]
  (let [s (if (nil? v) "" (str v))
        s (if (re-find #"^[=+\-@]" s) (str "'" s) s)]
    (if (re-find #"[\",\n\r]" s)
      (str "\"" (str/replace s "\"" "\"\"") "\"")
      s)))

(def csv-columns ["id" "email" "phone" "subscribedAt" "source" "active" "unsubscribeUrl"])

(defn subscribers-csv [rows]
  (str (str/join "," csv-columns) "\r\n"
       (str/join "" (map (fn [r]
                           (str (str/join "," (map csv-cell [(:id r) (:email r) (:phone r)
                                                              (some-> (:subscribed-at r) json/format-instant)
                                                              (some-> (:source r) name) (:active? r) (:unsubscribe-url r)]))
                                "\r\n"))
                         rows))))

(defn unsubscribe-url [base-url token]
  (str base-url "/unsubscribe?token=" token))
