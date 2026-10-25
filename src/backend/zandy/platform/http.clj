(ns zandy.platform.http
  "Builds the Pedestal connector from the route table. Pedestal's own router (Sawtooth) does the
  routing; everything else is composed per route from route data: access, rate limit, Malli
  validation, idempotency, error handling, JSON encoding and cache headers. No business logic."
  (:require [clojure.string :as str]
            [io.pedestal.connector :as conn]
            [io.pedestal.http.jetty :as jetty]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.route.definition.table :as table]
            [io.pedestal.interceptor :as interceptor]
            [ring.middleware.multipart-params :as multipart]
            [zandy.features.accounts.routes :as accounts-routes]
            [zandy.features.catalog.routes :as catalog-routes]
            [zandy.features.engagement.routes :as engagement-routes]
            [zandy.features.orders.routes :as orders-routes]
            [zandy.features.photos.routes :as photos-routes]
            [zandy.features.policies.routes :as policies-routes]
            [zandy.features.site.routes :as site-routes]
            [zandy.platform.auth-interceptors :as auth]
            [zandy.platform.errors :as errors]
            [zandy.platform.idempotency :as idempotency]
            [zandy.platform.json :as json]
            [zandy.platform.log :as log]
            [zandy.platform.rate-limit :as rate-limit]
            [zandy.platform.util :as util]
            [zandy.platform.validation :as validation]))

(defn route-table
  "The normalised route table: a vector of maps with :method :path :name :access and handler."
  []
  (vec (concat site-routes/routes
               policies-routes/routes
               catalog-routes/routes
               orders-routes/routes
               accounts-routes/routes
               engagement-routes/routes
               photos-routes/routes)))

(defn assert-route-table!
  "Fails at startup when a route has no :access, or two routes share a method and path."
  [routes]
  (doseq [r routes]
    (when-not (#{:public :staff :owner} (:access r))
      (throw (ex-info "Route without :access" {:route (select-keys r [:method :path :name])}))))
  (let [dupes (->> routes (map (juxt :method :path)) frequencies (filter #(> (val %) 1)))]
    (when (seq dupes) (throw (ex-info "Duplicate routes" {:dupes (keys dupes)}))))
  routes)

;; ---- ring-style wrappers, composed per route -------------------------------------------------

(defn- no-store-headers [] {"Cache-Control" "no-store"})

(defn- encode-body [body]
  (if (or (map? body) (sequential? body)) (json/encode body) body))

(defn wrap-response
  "Encodes JSON bodies and applies cache headers, ETag and 304 handling."
  [handler route]
  (fn [req]
    (let [resp (handler req)
          status (:status resp 200)
          json? (or (map? (:body resp)) (sequential? (:body resp)))
          body (encode-body (:body resp))
          headers (cond-> (or (:headers resp) {})
                    json? (assoc "Content-Type" "application/json; charset=utf-8"))
          ok? (<= 200 status 299)
          cache (:cache route)
          headers (cond
                    (get headers "Cache-Control") headers
                    (and ok? (= :get (:method route)) cache) (assoc headers "Cache-Control" cache)
                    :else (merge (no-store-headers) headers))]
      (if (and ok? cache (= :get (:method route)) (string? body))
        (let [etag (str "\"" (subs (util/sha256-hex body) 0 32) "\"")
              inm (get-in req [:headers "if-none-match"])]
          (if (and inm (some #(= (str/trim %) etag) (str/split inm #",")))
            {:status 304 :headers (assoc headers "ETag" etag) :body nil}
            (assoc resp :body body :headers (assoc headers "ETag" etag))))
        (assoc resp :body body :headers headers)))))

(defn wrap-errors [handler _ctx route]
  (fn [req]
    (try
      (handler req)
      (catch Throwable ex
        (or (errors/->response ex)
            (do (log/error :unhandled-exception
                           (merge {:request-id (:request-id req) :route (:path route)} (log/exception-data ex)))
                (errors/error-response :internalError "Something went wrong. Please try again.")))))))

(defn- read-body-string [req]
  (if-let [in (:body req)]
    (let [bs (.readNBytes ^java.io.InputStream in 1048577)]
      (when (> (alength bs) 1048576)
        (errors/fail! :malformedRequest "The request body is too large."))
      (String. bs "UTF-8"))
    ""))

(defn- json-content-type? [req]
  (some-> (or (:content-type req) (get-in req [:headers "content-type"]))
          str/lower-case
          (str/starts-with? "application/json")))

(defn- query-map [req]
  (into {} (map (fn [[k v]] [(keyword (json/camel->kebab (name k))) v])) (:query-params req)))

(defn- file-parts [params]
  (mapcat (fn [v] (cond (and (map? v) (:tempfile v)) [v] (sequential? v) (filter :tempfile v) :else []))
          (vals params)))

(defn- multipart-fingerprint
  "Hash of everything the client sent: the query, the plain fields and every file's name and content."
  [params query]
  (util/fingerprint
   [query
    (into (sorted-map) (remove (fn [[_ v]] (or (and (map? v) (:tempfile v)) (sequential? v)))) params)
    (for [{:keys [filename ^java.io.File tempfile]} (file-parts params)]
      [filename (util/sha256-bytes-hex (java.nio.file.Files/readAllBytes (.toPath tempfile)))])]))

(defn wrap-validate
  "Decodes and validates query, JSON body and path against the route's Malli schemas."
  [handler route]
  (let [{:keys [body query]} (:parameters route)
        mutating? (contains? #{:post :put :patch :delete} (:method route))]
    (fn [req]
      (let [q (validation/check (or query [:map]) (query-map req) validation/query-transformer)]
        (cond
          (:multipart? route)
          (let [req (multipart/multipart-params-request req {})
                params (:multipart-params req)]
            (try
              (handler (assoc req :parameters {:query q :path (:path-params req) :body nil}
                              :multipart params :fingerprint (multipart-fingerprint params q)))
              ;; uploads are temporary files: remove them whatever happens (also on an idempotent replay)
              (finally (doseq [{:keys [^java.io.File tempfile]} (file-parts params)]
                         (.delete tempfile)))))

          (and body mutating?)
          (do
            (when-not (json-content-type? req)
              (errors/fail! :malformedRequest "The request must be JSON (Content-Type: application/json)."))
            (let [text (read-body-string req)
                  data (try (if (str/blank? text) {} (json/decode text))
                            (catch Throwable _ (errors/fail! :malformedRequest "The request body is not valid JSON.")))
                  _ (when-not (map? data) (errors/fail! :malformedRequest "The request body must be a JSON object."))
                  data (validation/normalize data (not (:keep-nils? route)))
                  data (or data {})
                  valid (validation/check body data)]
              (handler (assoc req :parameters {:query q :path (:path-params req) :body valid}
                              :fingerprint (util/fingerprint valid)))))

          :else
          (handler (assoc req :parameters {:query q :path (:path-params req)}
                          :fingerprint (util/fingerprint (or q {})))))))))

(defn wrap-rate-limit [handler ctx route]
  (if-let [limit-key (:rate-limit route)]
    (fn [req]
      (let [cfg (get-in ctx [:config :limits limit-key])
            k (str (name limit-key) ":" (auth/client-ip ctx req))]
        (rate-limit/consume! (:conn ctx) k cfg (util/now ctx))
        (handler req)))
    handler))

(defn wrap-idempotency [handler ctx route]
  (if-not (:idempotent? route)
    handler
    (fn [req]
      (let [k (get-in req [:headers "idempotency-key"])]
        (cond
          (str/blank? k) (errors/fail! :missingIdempotencyKey "The Idempotency-Key header is required.")
          (nil? (parse-uuid (str/trim k))) (errors/fail! :malformedRequest "The Idempotency-Key header must be a UUID.")
          :else
          (let [scoped (str (str/upper-case (name (:request-method req))) " " (:uri req) " " (str/lower-case (str/trim k)))
                claim (idempotency/claim! (:conn ctx) scoped (:fingerprint req) (util/now ctx))]
            (case (:status claim)
              :replay (-> (:response claim) (assoc-in [:headers "Idempotent-Replayed"] "true"))
              :conflict (errors/fail! :idempotencyKeyReused "This Idempotency-Key was already used with a different request.")
              :busy (errors/fail! :serviceUnavailable "The original request is still being processed. Please retry shortly." nil {"Retry-After" "1"})
              :owner
              (let [token (:token claim)]
                (try
                  (let [resp (handler req)]
                    (if (< (:status resp 200) 400)
                      (do (idempotency/complete! (:conn ctx) scoped token
                                                 {:status (or (:replay-status resp) (:status resp 200))
                                                  :body (if (contains? resp :replay-body) (:replay-body resp) (:body resp))})
                          (dissoc resp :replay-body :replay-status))
                      (do (idempotency/release! (:conn ctx) scoped token) resp)))
                  (catch Throwable t
                    (idempotency/release! (:conn ctx) scoped token)
                    (throw t)))))))))))

(defn compose
  "Builds the ring-style handler for one route (outermost first: response, errors, auth, rate limit,
  validation, idempotency, handler)."
  [ctx route]
  (let [h (-> (:handler route)
              (wrap-idempotency ctx route)
              (wrap-validate route)
              (wrap-rate-limit ctx route)
              (auth/wrap-auth ctx route)
              (wrap-errors ctx route)
              (wrap-response route))]
    (fn [req] (h (assoc req :ctx ctx)))))

;; ---- pedestal glue ----------------------------------------------------------------------------

(defn- route-interceptor [ctx r]
  (let [h (compose ctx r)]
    (interceptor/interceptor
     {:name (:name r)
      :enter (fn [c] (assoc c :response (h (:request c))))})))

(defn- template->regex [path]
  (re-pattern (str "^" (str/replace path #":[^/]+" "[^/]+") "$")))

(defn- finalize [resp]
  (update resp :body encode-body))

(defn- edge-interceptor [routes]
  (let [matchers (mapv (fn [r] [(:method r) (template->regex (:path r))]) routes)]
    (interceptor/interceptor
     {:name ::edge
      :enter
      (fn [c]
        (let [req (update (:request c) :headers update-keys str/lower-case)
              inbound (get-in req [:headers "x-request-id"])
              rid (if (and inbound (re-matches #"[A-Za-z0-9._-]{8,64}" inbound)) inbound (str (random-uuid)))]
          (assoc c :request (assoc req :request-id rid) ::started (System/nanoTime))))
      :leave
      (fn [c]
        (let [req (:request c)
              resp (or (:response c)
                       (let [allowed (->> matchers (filter (fn [[_ re]] (re-matches re (:uri req)))) (map (comp str/upper-case name first)))]
                         (if (seq allowed)
                           (-> (errors/error-response :methodNotAllowed "This method is not allowed here." nil {"Allow" (str/join ", " (distinct allowed))})
                               finalize
                               (assoc-in [:headers "Content-Type"] "application/json; charset=utf-8"))
                           (-> (errors/error-response :notFound "Not found.") finalize
                               (assoc-in [:headers "Content-Type"] "application/json; charset=utf-8")))))
              resp (-> resp
                       (assoc-in [:headers "X-Request-Id"] (:request-id req))
                       (assoc-in [:headers "X-Content-Type-Options"] "nosniff"))]
          (log/info :request {:request-id (:request-id req)
                              :method (name (:request-method req))
                              :route (some-> c :route :path)
                              :status (:status resp)
                              :duration-ms (long (/ (- (System/nanoTime) (::started c 0)) 1000000))})
          (assoc c :response resp)))
      :error
      (fn [c ex]
        (log/error :edge-error (merge {:request-id (get-in c [:request :request-id])} (log/exception-data ex)))
        (assoc c :response (-> (errors/error-response :internalError "Something went wrong. Please try again.")
                               finalize
                               (assoc-in [:headers "Content-Type"] "application/json; charset=utf-8"))))})))

(defn connector
  "Builds the (unstarted) Pedestal connector. `ctx` holds the long-lived components
  ({:config :conn :clock :mailer :media}). Tests call it with response-for; the server starts it."
  [ctx]
  (let [routes (assert-route-table! (route-table))
        entries (mapv (fn [r] [(:path r) (:method r) (route-interceptor ctx r) :route-name (:name r)]) routes)
        routing (table/table-routes {} entries)]
    (-> (conn/default-connector-map (get-in ctx [:config :http :host]) (get-in ctx [:config :http :port]))
        (conn/with-interceptors [(edge-interceptor routes) route/query-params])
        (conn/with-routes routing)
        (jetty/create-connector nil))))
