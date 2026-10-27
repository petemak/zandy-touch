(ns zandy.ssr.system
  "Integrant system and `-main` of the frontend server (architecture.md section
  2.3, `ssr.system`). JVM only. Wires config, API client, launch gate and the
  HTTP server in that order; any failure at startup is logged and exits with
  status 1."
  (:require [integrant.core :as ig]
            [zandy.ssr.api-client :as api-client]
            [zandy.ssr.config :as config]
            [zandy.ssr.http :as http]
            [zandy.ssr.launch-gate :as launch-gate]
            [zandy.ssr.log :as log]
            [zandy.ssr.static :as static]))

(defmethod ig/init-key :ssr/config [_ overrides] (config/load-config overrides))

(defmethod ig/init-key :ssr/api [_ {:keys [config]}] (api-client/create config))

;; Only checked when the site is indexable: staging and development start
;; without the policy check (see zandy.ssr.launch-gate).
(defmethod ig/init-key :ssr/launch-gate [_ {:keys [config api]}]
  (when (:site-indexable config) (launch-gate/check! api))
  :checked)

;; The manifest is read once here for the context, but pages re-read it per
;; request (static/current-manifest).
(defmethod ig/init-key :ssr/server [_ {:keys [config api]}]
  (let [ctx {:config config :api api :manifest (static/read-manifest config)}
        connector (http/start-connector ctx)]
    (log/info :server-started {:port (:port config) :indexable (:site-indexable config)})
    connector))

(defmethod ig/halt-key! :ssr/server [_ connector] (http/stop-connector connector))

(defn system-map
  "Integrant configuration map. Argument: optional `overrides` (config keys
  merged over the file configuration; tests). Keys: :ssr/config, :ssr/api,
  :ssr/launch-gate (runs before the server) and :ssr/server. Pure."
  ([] (system-map nil))
  ([overrides]
   {:ssr/config overrides
    :ssr/api {:config (ig/ref :ssr/config)}
    :ssr/launch-gate {:config (ig/ref :ssr/config) :api (ig/ref :ssr/api)}
    :ssr/server {:config (ig/ref :ssr/config) :api (ig/ref :ssr/api)
                 :gate (ig/ref :ssr/launch-gate)}}))

(defn start
  "Starts the system. Argument: optional `overrides` as for system-map. Returns
  the running Integrant system. Side effects: reads configuration, may contact
  the backend (launch gate), opens the server port."
  ([] (start nil))
  ([overrides] (ig/init (system-map overrides))))

(defn -main
  "Process entry point (`clojure -M:run`). Starts the system and registers a
  shutdown hook that halts it. On any failure it logs :startup-failed and
  calls System/exit 1, so a bad configuration or a failed launch gate stops
  the process and the deploy."
  [& _]
  (try
    (let [system (start)]
      (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable (fn [] (ig/halt! system)))))
    (catch Throwable t
      (log/error :startup-failed {:message (.getMessage t) :data (pr-str (ex-data t))})
      (System/exit 1))))
