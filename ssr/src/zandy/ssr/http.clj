(ns zandy.ssr.http
  "The HTTP face of the frontend server (architecture.md section 2.3,
  `ssr.http`). JVM only. A Pedestal connector with one catch-all interceptor:
  `/healthz`, `/robots.txt`, `/sitemap.xml`, `/static/*` and every page route
  (matched with the shared Reitit table, used as a data matcher only). It adds
  the request id, the access log and error handling: any failure renders the
  calm 503 page with no-store, never a stack trace."
  (:require [clojure.string :as str]
            [io.pedestal.connector :as conn]
            [io.pedestal.http.jetty :as jetty]
            [jsonista.core :as json]
            [zandy.shared.pages :as pages]
            [zandy.shared.routes :as routes]
            [zandy.ssr.api-client :as api]
            [zandy.ssr.log :as log]
            [zandy.ssr.pages :as ssr-pages]
            [zandy.ssr.render :as render]
            [zandy.ssr.seo-routes :as seo]
            [zandy.ssr.static :as static]))

(defn- header
  "Value of request header `k` (lower-case name) from the Ring request `req`,
  or nil."
  [req k] (get-in req [:headers k]))

(defn- text-response
  "Plain-text response with `status` and `text`, no-store, the security headers
  and the request id."
  [status text request-id]
  {:status status
   :headers (merge (render/security-headers {:site-indexable true})
                   {"Content-Type" "text/plain; charset=utf-8" "Cache-Control" "no-store"
                    "X-Request-Id" request-id})
   :body text})

(defn- health
  "GET /healthz: pings the backend's /api/healthz. Returns 200 {\"status\":\"ok\"}
  when it answers 200, otherwise 503 {\"status\":\"degraded\"}. Always no-store.
  Side effect: one backend request."
  [{:keys [api]} request-id]
  (let [r (api/fetch api {:path "/api/healthz"} request-id)
        ok? (= 200 (:status r))]
    {:status (if ok? 200 503)
     :headers {"Content-Type" "application/json" "Cache-Control" "no-store" "X-Request-Id" request-id}
     :body (json/write-value-as-string {:status (if ok? "ok" "degraded")})}))

(defn- unavailable-response
  "The 503 page for an `unavailable` state, rendered without calling the
  backend. Arguments: ctx ({:config}), the matched `route` (or nil) and
  `request-id`."
  [{:keys [config]} route request-id]
  (let [state (assoc (pages/initial-state) :route (or route {:name :unavailable :path nil})
                     :page {:status :unavailable})]
    (render/page-response config (static/assets (static/current-manifest config)) state nil request-id)))

;; Why the 302 carries a short public Cache-Control: the CDN can answer
;; repeated junk URLs itself instead of sending them to this server.
(defn- page
  "Handles any page route. Matches the URL with the shared routes; redirects
  (302) listing URLs that carry unknown or invalid parameters to their clean
  form; otherwise loads the page state from the API, adds site-info to 404
  pages, logs a warning when API calls failed, and renders the response with
  ETag handling. Arguments: ctx, the Ring request and `request-id`."
  [{:keys [config api]} {:keys [uri query-string] :as req} request-id]
  (let [url (cond-> uri (seq query-string) (str "?" query-string))
        route (routes/match-url url)]
    (if-let [target (and route (ssr-pages/canonical-redirect route query-string))]
      {:status 302
       :headers {"Location" target "Cache-Control" "public, max-age=0, s-maxage=60" "X-Request-Id" request-id}
       :body ""}
      (let [{:keys [state failed]} (ssr-pages/load-state api route request-id)
            site (when (and (= :not-found (get-in state [:page :status])) (nil? (:site state)))
                   (ssr-pages/site-only api request-id))
            state (cond-> (update-in state [:route :path] #(or % uri))
                    site (assoc :site site))]
        (when failed
          (log/warn :api-unavailable {:request-id request-id :route (name (or (:name route) :unknown)) :failed (update-keys failed name)}))
        (render/page-response config (static/assets (static/current-manifest config)) state (header req "if-none-match") request-id)))))

(defn dispatch
  "Request handler: Ring request map -> Ring response map. Arguments: `ctx`
  ({:config :api ...}) and `req` ({:uri :query-string :request-method
  :headers}). Only GET and HEAD are allowed (405 with Allow otherwise). Routes
  /healthz, /robots.txt, /sitemap.xml, /favicon.svg, /static/* and then pages.
  Side effects: backend requests, file reads. Exceptions propagate to
  `handler`."
  [{:keys [config api] :as ctx} req]
  (let [request-id (or (header req "x-request-id") (str (random-uuid)))
        uri (:uri req)
        method (:request-method req)]
    (cond
      (not (#{:get :head} method))
      (assoc-in (text-response 405 "Method not allowed" request-id) [:headers "Allow"] "GET, HEAD")

      (= uri "/healthz") (health ctx request-id)
      (= uri "/robots.txt") (assoc-in (seo/robots config) [:headers "X-Request-Id"] request-id)
      (= uri "/sitemap.xml") (assoc-in (seo/sitemap api config request-id) [:headers "X-Request-Id"] request-id)
      (= uri "/favicon.svg") (static/serve config "assets/favicon.svg")
      (str/starts-with? uri "/static/") (static/serve config (subs uri (count "/static/")))
      :else (page ctx req request-id))))

(defn handler
  "Entry point for every request: wraps `dispatch` with a request id (taken
  from X-Request-Id or generated), the access log and error handling.
  Arguments as for `dispatch`. Returns the Ring response. An exception is
  logged and answered with the 503 page (or, if even that fails, a plain-text
  503), both with no-store. Side effect: one :request log line per request
  (route name, status and duration; never the query string)."
  [ctx req]
  (let [start (System/nanoTime)
        request-id (or (header req "x-request-id") (str (random-uuid)))
        req (assoc-in req [:headers "x-request-id"] request-id)
        resp (try
               (dispatch ctx req)
               (catch Throwable t
                 (log/error :request-failed {:request-id request-id
                                             :method (name (:request-method req))
                                             :error (.getName (class t))
                                             :message (.getMessage t)})
                 (try (unavailable-response ctx nil request-id)
                      (catch Throwable _
                        (text-response 503 "We are having trouble loading the shop. Please try again in a minute." request-id)))))
        route (some-> (routes/match-url (:uri req)) :name)]
    (log/info :request {:request-id request-id
                        :method (name (:request-method req))
                        :route (cond route (name route)
                                     (str/starts-with? (:uri req) "/static/") "static"
                                     :else "other")
                        :status (:status resp)
                        :duration-ms (long (/ (- (System/nanoTime) start) 1e6))})
    resp))

(defn start-connector
  "Starts the Pedestal/Jetty connector on 0.0.0.0 at the configured port with
  `handler` as its only interceptor. Argument: ctx ({:config :api :manifest}).
  Returns the started connector. Side effect: opens the listening socket."
  [{:keys [config] :as ctx}]
  (-> (conn/default-connector-map "0.0.0.0" (:port config))
      (conn/with-interceptor {:name ::dispatch
                              :enter (fn [context]
                                       (assoc context :response (handler ctx (:request context))))})
      (jetty/create-connector nil)
      (conn/start!)))

(defn stop-connector
  "Stops a connector returned by start-connector."
  [connector]
  (conn/stop! connector))
