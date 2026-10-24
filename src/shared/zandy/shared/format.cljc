(ns zandy.shared.format
  "Pure formatting helpers."
  (:require [clojure.string :as str]))

(defn group-thousands [n]
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

(defn first-name [full-name]
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

(defn iso->date
  "2026-10-24T09:30:00Z -> \"24 Oct 2026\" (UTC)."
  [iso]
  (when (and iso (>= (count iso) 10))
    (let [[y m d] (str/split (subs iso 0 10) #"-")
          months ["Jan" "Feb" "Mar" "Apr" "May" "Jun" "Jul" "Aug" "Sep" "Oct" "Nov" "Dec"]
          mi (dec (parse-long m))]
      (str (parse-long d) " " (nth months mi "?") " " y))))

(defn iso->date-time
  "2026-10-24T09:30:00Z -> \"24 Oct 2026, 09:30 UTC\"."
  [iso]
  (when (and iso (>= (count iso) 16))
    (str (iso->date iso) ", " (subs iso 11 16) " UTC")))

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

(defn truncate [s n]
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

(defn staff-status-label [status]
  (case status
    "placed" "New"
    "confirmed" "Confirmed"
    "approvedForDelivery" "Out for delivery"
    "delivered" "Delivered"
    "cancelled" "Cancelled"
    (str status)))

(defn payment-label [m]
  (case m
    "cash" "Cash"
    "mobileMoney" "Mobile money"
    (str m)))
