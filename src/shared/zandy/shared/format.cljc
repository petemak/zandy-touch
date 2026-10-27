(ns zandy.shared.format
  "Pure formatting helpers (money, phone numbers, dates in Kampala time, labels
  for statuses and stock movements). Shared code (.cljc): runs on the JVM
  inside the frontend server (ssr) and in the browser, and must give identical
  text in both so server-rendered markup matches the client render. Depends on
  nothing else in the project (architecture.md section 2.4)."
  (:require [clojure.string :as str]))

(defn group-thousands
  "Integer `n` as text with comma thousands separators: 85000 -> \"85,000\",
  -1200 -> \"-1,200\". Fractions are dropped (`long`). Pure."
  [n]
  (let [s (str (abs (long n)))
        parts (loop [s s acc ()]
                (if (<= (count s) 3)
                  (cons s acc)
                  (recur (subs s 0 (- (count s) 3)) (cons (subs s (- (count s) 3)) acc))))]
    (str (when (neg? n) "-") (str/join "," parts))))

(defn ugx
  "85000 -> \"UGX 85,000\". nil -> nil."
  [n]
  (when (some? n) (str "UGX " (group-thousands n))))

(defn paragraphs
  "Plain text where a blank line separates paragraphs -> vector of strings."
  [text]
  (->> (str/split (or text "") #"\n\s*\n")
       (map str/trim)
       (remove str/blank?)
       vec))

(defn first-name
  "First word of `full-name` (\"Jane Doe\" -> \"Jane\"). nil or blank gives nil.
  Used in the order-placed greeting (P7)."
  [full-name]
  (first (str/split (str/trim (or full-name "")) #"\s+")))

(defn phone-display
  "+256706105779 -> \"+256 706 105 779\"; other shapes are returned as is."
  [phone]
  (if-let [[_ a b c] (and phone (re-matches #"\+256(\d{3})(\d{3})(\d{3})" phone))]
    (str "+256 " a " " b " " c)
    phone))

(defn digits
  "+256706105779 -> \"256706105779\" (for wa.me links)."
  [phone]
  (str/replace (or phone "") #"\D" ""))

;; Why a fixed offset: Africa/Kampala is UTC+3 with no daylight saving, so
;; plain arithmetic is exact. It avoids java.time on the server vs Intl in the
;; browser, which could format differently and cause a mismatch between the
;; server HTML and the client render.
(def kampala-offset-minutes
  "Africa/Kampala is UTC+3 all year (East Africa Time, no daylight saving)."
  180)

(def kampala-label
  "Text shown after a time to say which clock it is: \"Kampala time\"."
  "Kampala time")

(def ^:private month-names ["Jan" "Feb" "Mar" "Apr" "May" "Jun" "Jul" "Aug" "Sep" "Oct" "Nov" "Dec"])

(defn- leap? [y] (and (zero? (mod y 4)) (or (pos? (mod y 100)) (zero? (mod y 400)))))

(defn- days-in-month [y m]
  (case (long m) 2 (if (leap? y) 29 28) (4 6 9 11) 30 31))

(defn- add-days
  "Calendar date [y m d] moved by n days (n is small: -2 to +2)."
  [[y m d] n]
  (loop [y y m m d d n n]
    (cond
      (pos? n) (if (< d (days-in-month y m))
                 (recur y m (inc d) (dec n))
                 (if (= m 12) (recur (inc y) 1 1 (dec n)) (recur y (inc m) 1 (dec n))))
      (neg? n) (if (> d 1)
                 (recur y m (dec d) (inc n))
                 (if (= m 1) (recur (dec y) 12 31 (inc n)) (recur y (dec m) (days-in-month y (dec m)) (inc n))))
      :else [y m d])))

(defn- offset-minutes
  "\"Z\" -> 0, \"+03:00\" -> 180, \"-0130\" -> -90, nil -> 0 (the API sends UTC)."
  [s]
  (if-let [[_ sign h m] (and s (re-matches #"([+-])(\d{2}):?(\d{2})" s))]
    (* (if (= "-" sign) -1 1) (+ (* 60 (parse-long h)) (parse-long m)))
    0))

;; Parse the ISO text by regex (not Date/Instant) for the same reason:
;; identical results on the JVM and in the browser.
(defn kampala-parts
  "ISO 8601 instant -> {:y :m :d :hh :mm} on the Kampala wall clock, or nil when
  the text has no time part. Pure arithmetic with a fixed +3 offset, so the JVM
  (server rendering) and the browser give identical text. The value itself is
  never changed; only what is shown."
  [iso]
  (when-let [[_ y mo d h mi _ off] (and (string? iso)
                                        (re-find #"^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(:\d{2}(?:\.\d+)?)?(Z|[+-]\d{2}:?\d{2})?" iso))]
    (let [minutes (+ (* 60 (parse-long h)) (parse-long mi) (- (offset-minutes off)) kampala-offset-minutes)
          in-day (mod minutes 1440)
          [ky km kd] (add-days [(parse-long y) (parse-long mo) (parse-long d)] (quot (- minutes in-day) 1440))]
      {:y ky :m km :d kd :hh (quot in-day 60) :mm (mod in-day 60)})))

(defn- pad2 [n] (if (< n 10) (str "0" n) (str n)))

(defn- date-text [{:keys [y m d]}] (str d " " (nth month-names (dec m) "?") " " y))

(defn iso->date
  "2026-10-24T22:30:00Z -> \"25 Oct 2026\" (the date in Kampala). A text with only
  a date (no time) is shown as that date."
  [iso]
  (if-let [p (kampala-parts iso)]
    (date-text p)
    (when (and (string? iso) (re-matches #"\d{4}-\d{2}-\d{2}.*" iso))
      (let [[y m d] (str/split (subs iso 0 10) #"-")]
        (date-text {:y (parse-long y) :m (parse-long m) :d (parse-long d)})))))

(defn iso->date-time
  "2026-10-24T22:30:00Z -> \"25 Oct 2026, 01:30 (Kampala time)\"."
  [iso]
  (when-let [p (kampala-parts iso)]
    (str (date-text p) ", " (pad2 (:hh p)) ":" (pad2 (:mm p)) " (" kampala-label ")")))

;; ---------------------------------------------------------------------------
;; Stock movements (api-contract.md 8.3): one mapping for the history list and
;; the stock dialog.

(def stock-reasons
  "Reasons staff can pick when adjusting stock, in dialog order: [code label]."
  [["restock" "Restock"] ["stocktake" "Stocktake"] ["shopSale" "Sold in the shop"]
   ["damagedOrLost" "Damaged or lost"] ["returnedGoods" "Returned goods"] ["other" "Other"]])

(def movement-types
  "Stock-movement types returned by the API (api-contract.md 8.3) and their
  staff-facing labels, as [code label] pairs."
  [["initial" "Opening stock"] ["adjustment" "Adjusted by staff"] ["order" "Customer order"]
   ["orderCancelled" "Order cancelled, stock returned"]])

(defn- label-of
  "Label for a code; an unknown code is shown as it is, nil stays nil."
  [table code]
  (when (some? code) (or (some (fn [[c l]] (when (= c code) l)) table) (str code))))

(defn movement-type-label
  "Label for a stock-movement type code `t`; an unknown code is shown as is,
  nil gives nil."
  [t] (label-of movement-types t))
(defn stock-reason-label
  "Label for a stock-adjustment reason code `r` (see stock-reasons); unknown
  codes are shown as is, nil gives nil."
  [r] (label-of stock-reasons r))

(defn signed-delta
  "5 -> \"+5\", -2 -> \"\u22122\" (a real minus sign), 0 -> \"0\"."
  [n]
  (cond (nil? n) "" (pos? n) (str "+" n) (neg? n) (str "\u2212" (abs n)) :else "0"))

;; ---------------------------------------------------------------------------
;; Categories (api-contract.md section 2)

(defn category-name
  "Display name of the category with `slug`, looked up in the items of
  GET /api/categories. Falls back to the slug when the list is not loaded, the
  slug is unknown or the name is missing."
  [categories slug]
  (when (some? slug)
    (let [n (some (fn [c] (when (= slug (:slug c)) (:name c))) categories)]
      (if (str/blank? n) (str slug) n))))

(defn hhmm->label
  "\"08:00\" -> \"8:00 AM\", \"22:00\" -> \"10:00 PM\"."
  [hhmm]
  (let [[h m] (str/split (or hhmm "") #":")
        h (parse-long (or h "0"))
        suffix (if (< h 12) "AM" "PM")
        h12 (let [x (mod h 12)] (if (zero? x) 12 x))]
    (str h12 ":" m " " suffix)))

(defn hours-label
  "{:days \"Sunday\" :opens \"10:00\" :closes \"21:30\"} -> \"Sunday: 10:00 AM to 9:30 PM\""
  [{:keys [days opens closes]}]
  (str days ": " (hhmm->label opens) " to " (hhmm->label closes)))

(defn truncate
  "`s` cut to at most `n` characters, ending in an ellipsis when it was cut
  (trailing space before the ellipsis is removed). nil is treated as \"\". Used
  to keep meta descriptions and search titles short."
  [s n]
  (let [s (or s "")]
    (if (> (count s) n)
      (str (str/trimr (subs s 0 (dec n))) "…")
      s)))

(defn slug-title
  "Fallback label from a slug: health-and-wellness -> Health and wellness."
  [slug]
  (let [s (str/replace (or slug "") "-" " ")]
    (if (seq s) (str (str/upper-case (subs s 0 1)) (subs s 1)) s)))

(defn order-status-label
  "Customer-facing label (api-contract.md section 4)."
  [status]
  (case status
    "placed" "Received"
    "confirmed" "Confirmed"
    "approvedForDelivery" "On its way"
    "delivered" "Delivered"
    "cancelled" "Cancelled"
    (str status)))

(defn staff-status-label
  "Admin-facing label for an order status code (\"placed\" -> \"New\",
  \"approvedForDelivery\" -> \"Out for delivery\"). Unknown codes are shown as is.
  Compare order-status-label for customers."
  [status]
  (case status
    "placed" "New"
    "confirmed" "Confirmed"
    "approvedForDelivery" "Out for delivery"
    "delivered" "Delivered"
    "cancelled" "Cancelled"
    (str status)))

(defn payment-label
  "Label for a payment method code: \"cash\" -> \"Cash\", \"mobileMoney\" -> \"Mobile
  money\"; unknown codes are shown as is."
  [m]
  (case m
    "cash" "Cash"
    "mobileMoney" "Mobile money"
    (str m)))
