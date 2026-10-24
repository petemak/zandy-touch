(ns zandy.shared.json
  "Tiny JSON writer, safe to embed in HTML <script> blocks: '<', '>' and '&'
  are written as unicode escapes, so '</script>' can never end the block."
  (:require [clojure.string :as str]))

(defn- code [c]
  #?(:clj (int c) :cljs (.charCodeAt c 0)))

(defn- hex4 [n]
  (let [h #?(:clj (Integer/toHexString n) :cljs (.toString n 16))]
    (str (apply str (repeat (- 4 (count h)) "0")) h)))

(defn- escape-char [c]
  (case c
    \" "\\\""
    \\ "\\\\"
    \newline "\\n"
    \return "\\r"
    \tab "\\t"
    \< "\\u003c"
    \> "\\u003e"
    \& "\\u0026"
    (let [n (code c)]
      (if (or (< n 32) (= n 0x2028) (= n 0x2029))
        (str "\\u" (hex4 n))
        (str c)))))

(defn- write-string [s]
  (str "\"" (apply str (map escape-char s)) "\""))

(defn- key-str [k]
  (if (keyword? k)
    (if-let [n (namespace k)] (str n "/" (name k)) (name k))
    (str k)))

(defn write-str [x]
  (cond
    (nil? x) "null"
    (string? x) (write-string x)
    (keyword? x) (write-string (key-str x))
    (boolean? x) (str x)
    (number? x) (str x)
    (map? x) (str "{" (str/join "," (map (fn [[k v]] (str (write-string (key-str k)) ":" (write-str v))) x)) "}")
    (or (sequential? x) (set? x)) (str "[" (str/join "," (map write-str x)) "]")
    :else (write-string (str x))))
