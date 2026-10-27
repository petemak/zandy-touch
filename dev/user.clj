(ns user
  "REPL helpers. (go) starts the dev system (in-memory Datomic, log mailer, seeded); (halt) stops it."
  (:require [integrant.core :as ig]
            [zandy.platform.system :as system]))

(defonce ^:private running (atom nil))

(defn go
  "Starts the dev system (profile :dev: in-memory Datomic, log mailer, seed data) unless already running.
  Side effects: boots the whole Integrant system including the HTTP server and background jobs.
  Returns :started."
  []
  (when-not @running
    (reset! running (system/start :dev)))
  :started)

(defn halt
  "Stops the running dev system, if any, and forgets it. Returns :stopped."
  []
  (when-let [s @running]
    (system/stop s)
    (reset! running nil))
  :stopped)

(defn ctx
  "The running system's handler context map (the ::system/ctx component: :config :conn :clock :mailer ...), for REPL experiments. Returns nil when the system is not running."
  [] (::system/ctx @running))

(comment (go) (halt) ig/init)
