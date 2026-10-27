(ns zandy.ssr.config
  "Frontend server configuration (architecture.md section 2.3, `ssr.config`).
  Runs on the JVM only. Aero reads resources/ssr-config.edn, which takes every
  environment-specific value from environment variables (PORT, API_BASE_URL,
  SITE_BASE_URL, SITE_INDEXABLE, STATIC_DIR, API_TIMEOUT_MS, PAGE_CACHE_*),
  and Malli validates the result at boot. The server fails fast with an
  exception when the configuration is invalid."
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [malli.core :as m]
            [malli.error :as me]))

(def Config
  "Malli schema of the validated configuration (closed map): :port,
  :api-base-url and :site-base-url (http or https URLs), :site-indexable
  (boolean), :static-dir, :api-timeout-ms (100 to 60000) and :page-cache
  {:catalogue :info :shell :not-found}, each a Cache-Control header value (see
  architecture.md D2)."
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
  "Checks `config` against the Config schema. Returns the config unchanged when
  valid; throws ex-info \"Invalid frontend server configuration\" with {:errors
  <humanised Malli errors>} otherwise."
  [config]
  (if (m/validate Config config)
    config
    (throw (ex-info "Invalid frontend server configuration"
                    {:errors (me/humanize (m/explain Config config))}))))

(defn load-config
  "Reads ssr-config.edn from the classpath (Aero, reading environment
  variables), merges `overrides` (a map of config keys, used by tests) and
  validates the result. Returns the config map. Throws ex-info when invalid or
  when the resource is missing."
  ([] (load-config nil))
  ([overrides]
   (-> (aero/read-config (io/resource "ssr-config.edn"))
       (merge overrides)
       validate!)))
