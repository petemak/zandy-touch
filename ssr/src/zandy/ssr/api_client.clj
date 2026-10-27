(ns zandy.ssr.api-client
  "The only namespace in the frontend server that calls the backend
  (architecture.md section 2.3, `ssr.api-client`). JVM only. Uses the JDK
  HttpClient, sends GET with Accept: application/json and an X-Request-Id, and
  never throws for network failures: it returns {:status int :body parsed-map}
  for any HTTP response, or {:status nil :error \"ExceptionName\"} when the
  backend cannot be reached or times out. JSON keys stay camelCase keywords,
  exactly as the browser sees them."
  (:require [clojure.string :as str]
            [jsonista.core :as json]
            [zandy.shared.routes :as routes])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse HttpResponse$BodyHandlers)
           (java.time Duration)))

(def ^:private mapper
  "JSON object mapper that reads keys as keywords without renaming them."
  (json/object-mapper {:decode-key-fn keyword}))

(defn create
  "Builds the API client used by every call. Argument: config map with
  :api-base-url (trailing slashes are stripped) and :api-timeout-ms (used for
  connecting and for each request). Returns {:client HttpClient :base string
  :timeout-ms int}. No network traffic."
  [{:keys [api-base-url api-timeout-ms]}]
  {:client (-> (HttpClient/newBuilder)
               (.connectTimeout (Duration/ofMillis api-timeout-ms))
               (.build))
   :base (str/replace api-base-url #"/+$" "")
   :timeout-ms api-timeout-ms})

(defn- url
  "Full request URL: base, path and the query encoded with the shared
  routes/encode-query (sorted keys, so identical requests have identical
  URLs)."
  [{:keys [base]} {:keys [path query]}]
  (str base path (when (seq query) (str "?" (routes/encode-query query)))))

(defn- request
  "Builds the GET HttpRequest for `spec` with the per-request timeout, Accept
  header and X-Request-Id (the given id, or a new UUID)."
  ^HttpRequest [api spec request-id]
  (-> (HttpRequest/newBuilder (URI/create (url api spec)))
      (.timeout (Duration/ofMillis (:timeout-ms api)))
      (.header "Accept" "application/json")
      (.header "X-Request-Id" (or request-id (str (random-uuid))))
      (.GET)
      (.build)))

(defn- parse
  "Turns an HttpResponse into {:status :body}; the body is parsed JSON, or nil
  when it is empty or not valid JSON."
  [^HttpResponse resp]
  (let [body (.body resp)]
    {:status (.statusCode resp)
     :body (when-not (str/blank? body)
             (try (json/read-value body mapper)
                  (catch Exception _ nil)))}))

(defn- failure
  "Turns an exception into {:status nil :error \"<simple class name of the root
  cause>\"}. The message is left out on purpose so it cannot leak URLs into
  logs."
  [^Throwable t]
  {:status nil :error (.getSimpleName (class (or (.getCause t) t)))})

;; Why start every request before waiting for any: a page needs 2 to 5
;; independent calls (api-contract.md section 12), so the page costs about the
;; slowest call, not the sum. The deref adds 0.5 s to the request timeout so
;; the HTTP client's own timeout normally fires first and reports the real
;; cause.
(defn fetch-all
  "Runs independent GET requests in parallel. Arguments: `api` (from `create`),
  `specs` (map of key -> {:path request-path :query {..}}) and `request-id`
  (forwarded as X-Request-Id; a new UUID is used when nil). Returns a map of
  the same keys to {:status :body} or {:status nil :error name}. Never throws;
  a body that is not valid JSON gives :body nil. Side effect: HTTP requests to
  the backend."
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
  "One GET. Arguments: `api`, `spec` ({:path :query}) and `request-id`. Returns
  the same result map as `fetch-all` for a single request."
  [api spec request-id]
  (:r (fetch-all api {:r spec} request-id)))
