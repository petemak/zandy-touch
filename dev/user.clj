(ns user
  "REPL helpers. (go) starts the dev system (in-memory Datomic, log mailer, seeded); (halt) stops it."
  (:require [integrant.core :as ig]
            [zandy.platform.system :as system]))

(defonce ^:private running (atom nil))

(defn go []
  (when-not @running
    (reset! running (system/start :dev)))
  :started)

(defn halt []
  (when-let [s @running]
    (system/stop s)
    (reset! running nil))
  :stopped)

(defn ctx [] (::system/ctx @running))

(comment (go) (halt) ig/init)
