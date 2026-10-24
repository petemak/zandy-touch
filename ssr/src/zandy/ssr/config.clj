(ns zandy.ssr.config
  "Frontend server configuration: Aero reads ssr-config.edn (environment
  variables), Malli validates the result at boot. Fails fast."
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [malli.core :as m]
            [malli.error :as me]))

(def Config
  [:map {:closed true}
   [:port [:int {:min 1 :max 65535}]]
   [:api-base-url [:re #"^https?://.+"]]
   [:site-base-url [:re #"^https?://.+"]]
   [:site-indexable :boolean]
   [:static-dir [:string {:min 1}]]
   [:api-timeout-ms [:int {:min 100 :max 60000}]]
   [:page-cache [:map {:closed true}
                 [:catalogue :string] [:info :string] [:shell :string] [:not-found :string]]]])

(defn validate!
  [config]
  (if (m/validate Config config)
    config
    (throw (ex-info "Invalid frontend server configuration"
                    {:errors (me/humanize (m/explain Config config))}))))

(defn load-config
  "Reads and validates the configuration. `overrides` is merged last (tests)."
  ([] (load-config nil))
  ([overrides]
   (-> (aero/read-config (io/resource "ssr-config.edn"))
       (merge overrides)
       validate!)))
