(ns zandy.platform.util
  "Small pure-ish helpers shared across slices."
  (:require [clojure.string :as str])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest SecureRandom)
           (java.util Base64 Date)
           (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)))

(defn media-urls
  "The public URLs of a photo's variants."
  [photo-id]
  (into {} (map (fn [v] [v (str "/media/" photo-id "-" (name v) ".webp")])) [:thumb :medium :large]))

(defn now
  "Current time from the context clock (a function), so tests can control it."
  ^Date [ctx]
  ((:clock ctx)))

(defn plus-ms [^Date d ms] (Date. (+ (.getTime d) (long ms))))

(defn- hex [^bytes bs] (apply str (map #(format "%02x" (bit-and % 0xff)) bs)))

(defn sha256-hex ^String [^String s]
  (hex (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s StandardCharsets/UTF_8))))

(defn sha256-bytes-hex ^String [^bytes bs]
  (hex (.digest (MessageDigest/getInstance "SHA-256") bs)))

(defn hmac-hex ^String [^String secret ^String message]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. (.getBytes secret StandardCharsets/UTF_8) "HmacSHA256"))
    (hex (.doFinal mac (.getBytes message StandardCharsets/UTF_8)))))

(def ^:private secure-random (SecureRandom.))

(defn random-token
  "A 256-bit URL-safe random token."
  ^String []
  (let [bs (byte-array 32)]
    (.nextBytes secure-random bs)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bs)))

(defn constant-time-equals? [^String a ^String b]
  (and (some? a) (some? b)
       (MessageDigest/isEqual (.getBytes a StandardCharsets/UTF_8) (.getBytes b StandardCharsets/UTF_8))))

(defn compact
  "Removes nil values from a map (Datomic rejects nil)."
  [m]
  (into {} (remove (fn [[_ v]] (nil? v))) m))

(defn canonical
  "Recursively sorts map keys so equal data hashes equally."
  [x]
  (cond
    (map? x) (into (sorted-map-by #(compare (str %1) (str %2))) (map (fn [[k v]] [k (canonical v)])) x)
    (sequential? x) (mapv canonical x)
    :else x))

(defn fingerprint [x] (sha256-hex (pr-str (canonical x))))

(defn client-ip
  "The client address from the header named by CLIENT_IP_HEADER (set by the edge), else the peer."
  [ctx req]
  (let [h (some-> (get-in ctx [:config :client-ip-header]) str/lower-case)
        v (some-> (get-in req [:headers h]) str/trim)]
    (if (str/blank? v) (or (:remote-addr req) "unknown") v)))

(defn paginate
  "Page of a sequence in the contract's pagination shape."
  [items page page-size]
  (let [page (or page 1) page-size (or page-size 24)]
    {:items (vec (take page-size (drop (* (dec page) page-size) items)))
     :page page :page-size page-size :total-items (count items)}))
