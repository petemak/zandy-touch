(ns zandy.platform.config
  "Reads resources/config.edn with Aero and validates it with Malli. Fails fast at boot."
  (:require [aero.core :as aero]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [malli.core :as m]
            [malli.error :as me]))

(def ^:private nilable-string [:maybe [:string {:min 1}]])

(def Config
  [:map
   [:profile [:enum :dev :test :prod]]
   [:http [:map [:port pos-int?] [:host :string]]]
   [:datomic [:map [:uri [:string {:min 1}]] [:auto-migrate? :boolean]]]
   [:seed-on-boot? :boolean]
   [:auth [:map
           [:session-hmac-secret nilable-string]
           [:owner-email nilable-string]
           [:owner-initial-password nilable-string]]]
   [:client-ip-header [:string {:min 1}]]
   [:mail [:map
           [:impl [:enum :log :smtp]]
           [:host :string] [:port pos-int?]
           [:user nilable-string] [:password nilable-string]
           [:daily-cap pos-int?]]]
   [:media [:map [:dir [:string {:min 1}]] [:processor [:enum :vips :stub]]]]
   [:site [:map
           [:base-url :string] [:business-name :string] [:tagline :string]
           [:phone :string] [:whatsapp nilable-string] [:email nilable-string]
           [:shop-address nilable-string]
           [:opening-hours [:vector [:map [:days :string] [:opens :string] [:closes :string]]]]
           [:social [:map [:instagram nilable-string] [:tiktok nilable-string]]]
           [:countries [:vector [:map [:code :string] [:name :string] [:cities [:vector :string]]]]]
           [:allow-other-city? :boolean]]]
   [:catalog [:map [:max-quantity-per-line pos-int?] [:low-stock-threshold pos-int?]]]
   [:limits [:map-of :keyword [:map [:limit pos-int?] [:window-ms pos-int?]]]]])

(defn- prod-problems
  "Extra rules that only apply to the prod profile."
  [{:keys [auth mail media datomic]}]
  (cond-> []
    (nil? (:owner-email auth)) (conj "OWNER_EMAIL is required")
    (nil? (:session-hmac-secret auth)) (conj "SESSION_HMAC_SECRET is required")
    (and (:session-hmac-secret auth) (< (count (:session-hmac-secret auth)) 32))
    (conj "SESSION_HMAC_SECRET must be at least 32 characters")
    (nil? (:user mail)) (conj "SMTP_USER is required")
    (nil? (:password mail)) (conj "SMTP_PASSWORD is required")
    (not= :smtp (:impl mail)) (conj "the smtp mailer is required in prod")
    (= :stub (:processor media)) (conj "the stub image processor is not allowed in prod")
    (str/starts-with? (str (:uri datomic)) "datomic:mem:") (conj "DATOMIC_URI must not be in-memory in prod")))

(defn validate!
  "Returns config when valid, otherwise throws ex-info listing every problem (never values)."
  [config]
  (let [explained (m/explain Config config)
        problems (cond-> []
                   explained (into (map (fn [[k v]] (str k " " (pr-str v)))
                                        (me/humanize explained)))
                   (and (not explained) (= :prod (:profile config))) (into (prod-problems config)))]
    (when (seq problems)
      (throw (ex-info (str "Invalid configuration: " (str/join "; " problems))
                      {:problems problems})))
    config))

(defn load-config
  "Reads config.edn for the profile (:dev, :test or :prod) and validates it. In non-prod profiles a
  missing SESSION_HMAC_SECRET is replaced by a random per-process value."
  [profile]
  (let [raw (aero/read-config (io/resource "config.edn") {:profile profile})
        cfg (assoc raw :profile profile)
        cfg (cond-> cfg
              (and (not= :prod profile) (nil? (get-in cfg [:auth :session-hmac-secret])))
              (assoc-in [:auth :session-hmac-secret] (str (random-uuid) (random-uuid)))
              (and (not= :prod profile) (nil? (get-in cfg [:auth :owner-email])))
              (assoc-in [:auth :owner-email] "owner@example.invalid"))]
    (validate! cfg)))

(defn profile-from-env
  "ZANDY_PROFILE env var, else the zandy.profile system property, else :prod (the safe default)."
  []
  (keyword (or (System/getenv "ZANDY_PROFILE") (System/getProperty "zandy.profile") "prod")))
