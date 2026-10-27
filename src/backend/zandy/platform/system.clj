(ns zandy.platform.system
  "The Integrant system map and -main. The only namespace that knows every component."
  (:require [integrant.core :as ig]
            [io.pedestal.connector :as conn]
            [zandy.features.accounts.api :as accounts]
            [zandy.features.accounts.bootstrap :as bootstrap]
            [zandy.features.engagement.api :as engagement]
            [zandy.features.notifications.api :as notifications]
            [zandy.features.orders.api :as orders]
            [zandy.platform.config :as config]
            [zandy.platform.datomic :as datomic]
            [zandy.platform.http :as http]
            [zandy.platform.idempotency :as idempotency]
            [zandy.platform.log :as log]
            [zandy.platform.mailer :as mailer]
            [zandy.platform.media :as media]
            [zandy.platform.migrations :as migrations]
            [zandy.platform.util :as util]
            [zandy.seed.core :as seed])
  (:import (java.util.concurrent Executors ScheduledExecutorService TimeUnit)))

(defn build-ctx
  "Builds the handler context map shared by every handler.
  Keys: :config (validated config), :conn (Datomic connection), :clock (function returning a java.util.Date),
  :mailer (Mailer), :media-store (MediaStore), :image-processor (:vips or :stub), :user-summaries (function from user entity ids to summary maps).
  `overrides` is a map merged over the defaults (tests replace :clock, :mailer, :media-store). No side effects."
  [config conn overrides]
  (let [base (merge {:config config
                     :conn conn
                     :clock (fn [] (java.util.Date.))
                     :mailer (mailer/make-mailer (:mail config) (get-in config [:auth :owner-email]))
                     :media-store (media/local-store (get-in config [:media :dir]))
                     :image-processor (get-in config [:media :processor])}
                    overrides)]
    (assoc base :user-summaries (fn [eids] (accounts/user-summaries base eids)))))

(defmethod ig/init-key ::config [_ {:keys [profile]}] (config/load-config profile))

(defmethod ig/init-key ::conn [_ {:keys [config]}]
  (let [conn (datomic/connect! (get-in config [:datomic :uri]))]
    ;; Dev and test migrate on boot; with auto-migrate off (prod) instances only verify the ledger, so migrations run once as a separate pre-deploy step (no boot race between instances).
    (if (get-in config [:datomic :auto-migrate?])
      (migrations/migrate! conn)
      (migrations/verify-current! conn))
    ;; Outside prod the owner is created automatically; in prod it is created only by the bootstrap-owner command (no secrets in seed data).
    (when (not= :prod (:profile config))
      (bootstrap/bootstrap-owner! conn (:auth config)))
    conn))

(defmethod ig/halt-key! ::conn [_ conn] (datomic/release! conn))

(defmethod ig/init-key ::ctx [_ {:keys [config conn]}]
  (let [ctx (build-ctx config conn {})]
    (when (:seed-on-boot? config) (seed/seed! ctx))
    ctx))

(defn- every [^ScheduledExecutorService pool seconds f]
  (.scheduleWithFixedDelay pool
                           (fn [] (try (f) (catch Throwable ex (log/error :scheduled-job-failed (log/exception-data ex)))))
                           seconds seconds TimeUnit/SECONDS))

(defmethod ig/init-key ::jobs [_ {:keys [ctx]}]
  (let [pool (Executors/newScheduledThreadPool 1)
        claimer (str "backend-" (random-uuid))]
    ;; Every instance runs these jobs; this is safe because outbox messages are claimed with :db/cas and the clean-ups are idempotent.
    (every pool 15 #(notifications/process-once! ctx claimer))
    (every pool 3600 (fn []
                       (idempotency/cleanup! (:conn ctx) (util/now ctx))
                       (orders/run-retention! ctx)
                       (engagement/run-retention! ctx)
                       (notifications/purge-old! ctx (util/plus-ms (util/now ctx) (- orders/retention-ms)))))
    pool))

(defmethod ig/halt-key! ::jobs [_ ^ScheduledExecutorService pool] (.shutdownNow pool))

(defmethod ig/init-key ::server [_ {:keys [ctx]}]
  (let [connector (http/connector ctx)]
    (conn/start! connector)
    (log/info :server-started {:port (get-in ctx [:config :http :port])})
    connector))

(defmethod ig/halt-key! ::server [_ connector] (conn/stop! connector))

(defn system-map
  "The Integrant system map for `profile` (:dev, :test or :prod): config, conn, ctx, jobs, server, wired by refs."
  [profile]
  {::config {:profile profile}
   ::conn {:config (ig/ref ::config)}
   ::ctx {:config (ig/ref ::config) :conn (ig/ref ::conn)}
   ::jobs {:ctx (ig/ref ::ctx)}
   ::server {:ctx (ig/ref ::ctx) :jobs (ig/ref ::jobs)}})

(defn start
  "Initialises the whole system for `profile` and returns the running system map. Side effects: connects to Datomic, may migrate/seed, starts background jobs and the HTTP server."
  [profile] (ig/init (system-map profile)))
(defn stop
  "Halts a system returned by start (server, jobs, then the Datomic connection)."
  [system] (ig/halt! system))

(defn -main
  "Entry point of the server (`clojure -M:run`): starts the system for the profile in the environment and blocks.
  Registers a shutdown hook that stops the system."
  [& _]
  (let [system (start (config/profile-from-env))]
    (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable (fn [] (stop system))))
    ;; Block forever: the server runs on its own threads; the shutdown hook above stops it on SIGTERM.
    @(promise)))
