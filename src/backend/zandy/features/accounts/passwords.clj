(ns zandy.features.accounts.passwords
  "Argon2id password hashing (Password4j). OWASP-recommended parameters: 19 MiB, 2 iterations."
  (:import (com.password4j Argon2Function Password)
           (com.password4j.types Argon2)))

(def ^:private argon2 (Argon2Function/getInstance 19456 2 1 32 Argon2/ID))

(defn hash-password ^String [^String password]
  (.getResult (.with (.addRandomSalt (Password/hash password)) argon2)))

(defn check-password? [^String password ^String hashed]
  (.with (Password/check password hashed) argon2))

(def ^:private dummy-hash (delay (hash-password (str (random-uuid)))))

(defn check-dummy!
  "Spends the same time as a real check, so unknown emails cannot be told apart by timing."
  [^String password]
  (check-password? password @dummy-hash)
  false)
