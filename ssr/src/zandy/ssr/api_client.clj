(ns zandy.ssr.api-client
  "The only namespace that calls the backend. JDK HttpClient, no library.
  Returns {:status int :body parsed-map} or {:status nil :error string} when
  the backend cannot be reached. Keys stay camelCase keywords, exactly as the
  browser sees them."
  (:require [clojure.string :as str]
            [jsonista.core :as json]
            [zandy.shared.routes :as routes])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse HttpResponse$BodyHandlers)
           (java.time Duration)))

(def ^:private mapper (json/object-mapper {:decode-key-fn keyword}))

(defn create [{:keys [api-base-url api-timeout-ms]}]
  {:client (-> (HttpClient/newBuilder)
               (.connectTimeout (Duration/ofMillis api-timeout-ms))
               (.build))
   :base (str/replace api-base-url #"/+$" "")
   :timeout-ms api-timeout-ms})

(defn- url [{:keys [base]} {:keys [path query]}]
  (str base path (when (seq query) (str "?" (routes/encode-query query)))))

(defn- request ^HttpRequest [api spec request-id]
  (-> (HttpRequest/newBuilder (URI/create (url api spec)))
      (.timeout (Duration/ofMillis (:timeout-ms api)))
      (.header "Accept" "application/json")
      (.header "X-Request-Id" (or request-id (str (random-uuid))))
      (.GET)
      (.build)))

(defn- parse [^HttpResponse resp]
  (let [body (.body resp)]
    {:status (.statusCode resp)
     :body (when-not (str/blank? body)
             (try (json/read-value body mapper)
                  (catch Exception _ nil)))}))

(defn- failure [^Throwable t]
  {:status nil :error (.getSimpleName (class (or (.getCause t) t)))})

(defn fetch-all
  "Run independent GETs in parallel. specs: key -> {:path :query}. Returns
  key -> result."
  [api specs request-id]
  (let [pending (into {}
                      (map (fn [[k spec]]
                             [k (.sendAsync ^HttpClient (:client api) (request api spec request-id)
                                            (HttpResponse$BodyHandlers/ofString))]))
                      specs)]
    (into {}
          (map (fn [[k fut]]
                 [k (try (parse (deref fut (+ (:timeout-ms api) 500) nil))
                         (catch Exception t (failure t)))]))
          pending)))

(defn fetch
  "One GET."
  [api spec request-id]
  (:r (fetch-all api {:r spec} request-id)))
