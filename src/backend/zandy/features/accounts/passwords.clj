(ns zandy.features.accounts.passwords
  "Argon2id password hashing (Password4j). OWASP-recommended parameters: 19 MiB, 2 iterations."
  (:import (com.password4j Argon2Function Password)
           (com.password4j.types Argon2)))

(def ^:private argon2 (Argon2Function/getInstance 19456 2 1 32 Argon2/ID))

(defn hash-password
  "Argon2id hash (random salt, parameters embedded in the result string) of `password`. CPU and memory heavy by design (about 19 MiB). Returns the hash string."
  ^String [^String password]
  (.getResult (.with (.addRandomSalt (Password/hash password)) argon2)))

(defn check-password?
  "True when `password` matches the stored Argon2id hash `hashed`."
  [^String password ^String hashed]
  (.with (Password/check password hashed) argon2))

;; Hash of a random value, computed once, so check-dummy! costs the same as a real check.
(def ^:private dummy-hash (delay (hash-password (str (random-uuid)))))

(defn check-dummy!
  "Runs a full password check against a throwaway hash and returns false. Used when the email is unknown or the account is inactive, so response time does not reveal which emails exist."
  [^String password]
  (check-password? password @dummy-hash)
  false)
