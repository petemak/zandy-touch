(ns zandy.features.accounts.logic
  "Pure account rules: password policy, session expiry, temporary passwords."
  (:require [clojure.string :as str])
  (:import (java.util Date)))

(def session-sliding-ms
  "A session lasts 12 hours from its last refresh (milliseconds)."
  (* 12 60 60 1000))
(def session-absolute-ms
  "A session never lasts longer than 7 days from sign-in (milliseconds)."
  (* 7 24 60 60 1000))
(def touch-interval-ms
  "Minimum time between last-seen / expiry refreshes of a session: 5 minutes (milliseconds), to keep database writes low."
  (* 5 60 1000))
(def reset-token-ms
  "Lifetime of a password-reset link: 1 hour (milliseconds)."
  (* 60 60 1000))

(def common-passwords
  "Lower-case passwords rejected as too common (compared case-insensitively)."
  #{"password" "password1" "password12" "password123" "1234567890" "12345678910" "qwertyuiop"
    "qwerty12345" "1q2w3e4r5t" "iloveyou123" "welcome123" "admin12345" "letmein1234"
    "zandytouch" "zandytouch1" "zandytouch12" "zandytouch123" "0123456789" "abcdefghij"})

(defn password-problem
  "Returns a short customer-readable message when `password` is not acceptable (under 10 or over 200 characters, or on the common-passwords list), else nil. Pure."
  [password]
  (cond
    (< (count password) 10) "Use at least 10 characters."
    (> (count password) 200) "Use at most 200 characters."
    (contains? common-passwords (str/lower-case password)) "This password is too common. Choose another."
    :else nil))

(defn session-expiry
  "The new expiry (java.util.Date): `now` plus 12 hours, but never later than 7 days after the session's `created-at`. Pure."
  [^Date created-at ^Date now]
  (let [sliding (+ (.getTime now) session-sliding-ms)
        absolute (+ (.getTime created-at) session-absolute-ms)]
    (Date. (long (min sliding absolute)))))

(defn session-active?
  "True when the session map {:expires-at :revoked?} is not revoked and `now` (java.util.Date) is before its expiry. Pure."
  [{:keys [^Date expires-at revoked?]} ^Date now]
  (and (not revoked?) expires-at (< (.getTime now) (.getTime expires-at))))

(defn touch-due?
  "True when the session has never been seen or its last-seen-at (java.util.Date) is at least touch-interval-ms before `now`."
  [^Date last-seen-at ^Date now]
  (or (nil? last-seen-at) (>= (- (.getTime now) (.getTime last-seen-at)) touch-interval-ms)))

(def temp-password-alphabet
  "Characters for temporary passwords: letters and digits without look-alikes (no I, l, O, 0, 1)."
  "ABCDEFGHJKMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789")

(defn temporary-password
  "A readable 14-character temporary password drawn from temp-password-alphabet using `rand-index` (a function from n to a random integer in 0..n-1; pass a SecureRandom-backed one)."
  [rand-index]
  (apply str (repeatedly 14 #(nth temp-password-alphabet (rand-index (count temp-password-alphabet))))))

(defn public-user
  "The user fields the browser may see after sign-in: {:id :name :email :role :must-change-password}. Never includes the hash."
  [{:keys [id name email role must-change-password?]}]
  {:id id :name name :email email :role role :must-change-password must-change-password?})

(defn staff-view
  "Owner-facing staff list item from a user map: {:id :name :email :role :active :notify-on-new-order :created-at :last-sign-in-at}."
  [{:keys [id name email role active? notify-on-new-order? created-at last-sign-in-at]}]
  {:id id :name name :email email :role role :active active?
   :notify-on-new-order notify-on-new-order? :created-at created-at :last-sign-in-at last-sign-in-at})
