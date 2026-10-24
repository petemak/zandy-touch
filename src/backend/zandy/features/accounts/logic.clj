(ns zandy.features.accounts.logic
  "Pure account rules: password policy, session expiry, temporary passwords."
  (:require [clojure.string :as str])
  (:import (java.util Date)))

(def session-sliding-ms (* 12 60 60 1000))
(def session-absolute-ms (* 7 24 60 60 1000))
(def touch-interval-ms (* 5 60 1000))
(def reset-token-ms (* 60 60 1000))

(def common-passwords
  #{"password" "password1" "password12" "password123" "1234567890" "12345678910" "qwertyuiop"
    "qwerty12345" "1q2w3e4r5t" "iloveyou123" "welcome123" "admin12345" "letmein1234"
    "zandytouch" "zandytouch1" "zandytouch12" "zandytouch123" "0123456789" "abcdefghij"})

(defn password-problem
  "Returns a short message when the password is not acceptable, else nil."
  [password]
  (cond
    (< (count password) 10) "Use at least 10 characters."
    (> (count password) 200) "Use at most 200 characters."
    (contains? common-passwords (str/lower-case password)) "This password is too common. Choose another."
    :else nil))

(defn session-expiry
  "The new expiry: 12 hours from now, but never beyond 7 days after the session was created."
  [^Date created-at ^Date now]
  (let [sliding (+ (.getTime now) session-sliding-ms)
        absolute (+ (.getTime created-at) session-absolute-ms)]
    (Date. (long (min sliding absolute)))))

(defn session-active? [{:keys [^Date expires-at revoked?]} ^Date now]
  (and (not revoked?) expires-at (< (.getTime now) (.getTime expires-at))))

(defn touch-due? [^Date last-seen-at ^Date now]
  (or (nil? last-seen-at) (>= (- (.getTime now) (.getTime last-seen-at)) touch-interval-ms)))

(def temp-password-alphabet "ABCDEFGHJKMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789")

(defn temporary-password
  "A readable temporary password built from the given random index function (n -> 0..n-1)."
  [rand-index]
  (apply str (repeatedly 14 #(nth temp-password-alphabet (rand-index (count temp-password-alphabet))))))

(defn public-user [{:keys [id name email role must-change-password?]}]
  {:id id :name name :email email :role role :must-change-password must-change-password?})

(defn staff-view [{:keys [id name email role active? notify-on-new-order? created-at last-sign-in-at]}]
  {:id id :name name :email email :role role :active active?
   :notify-on-new-order notify-on-new-order? :created-at created-at :last-sign-in-at last-sign-in-at})
