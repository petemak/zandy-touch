(ns zandy.ssr.system
  "Integrant system and -main."
  (:require [integrant.core :as ig]
            [zandy.ssr.api-client :as api-client]
            [zandy.ssr.config :as config]
            [zandy.ssr.http :as http]
            [zandy.ssr.launch-gate :as launch-gate]
            [zandy.ssr.log :as log]
            [zandy.ssr.static :as static]))

(defmethod ig/init-key :ssr/config [_ overrides] (config/load-config overrides))

(defmethod ig/init-key :ssr/api [_ {:keys [config]}] (api-client/create config))

(defmethod ig/init-key :ssr/launch-gate [_ {:keys [config api]}]
  (when (:site-indexable config) (launch-gate/check! api))
  :checked)

(defmethod ig/init-key :ssr/server [_ {:keys [config api]}]
  (let [ctx {:config config :api api :manifest (static/read-manifest config)}
        connector (http/start-connector ctx)]
    (log/info :server-started {:port (:port config) :indexable (:site-indexable config)})
    connector))

(defmethod ig/halt-key! :ssr/server [_ connector] (http/stop-connector connector))

(defn system-map
  ([] (system-map nil))
  ([overrides]
   {:ssr/config overrides
    :ssr/api {:config (ig/ref :ssr/config)}
    :ssr/launch-gate {:config (ig/ref :ssr/config) :api (ig/ref :ssr/api)}
    :ssr/server {:config (ig/ref :ssr/config) :api (ig/ref :ssr/api)
                 :gate (ig/ref :ssr/launch-gate)}}))

(defn start
  ([] (start nil))
  ([overrides] (ig/init (system-map overrides))))

(defn -main [& _]
  (try
    (let [system (start)]
      (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable (fn [] (ig/halt! system)))))
    (catch Throwable t
      (log/error :startup-failed {:message (.getMessage t) :data (pr-str (ex-data t))})
      (System/exit 1))))
