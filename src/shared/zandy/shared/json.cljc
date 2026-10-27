(ns zandy.shared.json
  "Tiny JSON writer, safe to embed in HTML <script> blocks: '<', '>' and '&'
  are written as unicode escapes, so '</script>' can never end the block."
  (:require [clojure.string :as str]))

(defn- code
  "Character code of `c`: a char on the JVM, a one-character string in the
  browser."
  [c]
  #?(:clj (int c) :cljs (.charCodeAt c 0)))

(defn- hex4
  "Lower-case hex of integer `n`, left-padded with zeros to 4 digits (for
  \\uXXXX escapes)."
  [n]
  (let [h #?(:clj (Integer/toHexString n) :cljs (.toString n 16))]
    (str (apply str (repeat (- 4 (count h)) "0")) h)))

;; Why: JSON placed in an HTML <script> must not contain a literal
;; "</script>"; escaping < > & as unicode escapes keeps the JSON valid and the
;; HTML safe.
(defn- escape-char
  "JSON text for one character `c`. Quote, backslash and control characters get
  the usual escapes. '<', '>' and '&' always become \\u escapes (so the output
  can sit inside an HTML <script> block), and so do U+2028 and U+2029 (line
  separators that break old JavaScript parsers)."
  [c]
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

(defn- write-string
  "JSON string literal (with quotes) for the text `s`."
  [s]
  (str "\"" (apply str (map escape-char s)) "\""))

(defn- key-str
  "Map key as JSON text: keywords lose the colon and keep a namespace as
  \"ns/name\"; anything else goes through `str`."
  [k]
  (if (keyword? k)
    (if-let [n (namespace k)] (str n "/" (name k)) (name k))
    (str k)))

(defn write-str
  "Serialises `x` to a JSON string.
  Arguments: any mix of nil, strings, keywords (written as strings), booleans,
  numbers, maps (keys via key-str), sequential collections and sets (written
  as arrays). Anything else is written as a string via `str`.
  Returns a string. No side effects; does not throw for the supported types.
  Used for the embedded initial state and the JSON-LD block, both placed
  inside <script> tags by shared.document and shared.seo."
  [x]
  (cond
    (nil? x) "null"
    (string? x) (write-string x)
    (keyword? x) (write-string (key-str x))
    (boolean? x) (str x)
    (number? x) (str x)
    (map? x) (str "{" (str/join "," (map (fn [[k v]] (str (write-string (key-str k)) ":" (write-str v))) x)) "}")
    (or (sequential? x) (set? x)) (str "[" (str/join "," (map write-str x)) "]")
    :else (write-string (str x))))
