(ns zandy.frontend.api
  "The only namespace that talks to the network from the browser. Relative
  /api URLs only (one public origin, so the zt_session cookie works and no CORS
  exists). Callbacks receive {:status int :body map :headers map}, or
  {:status nil :error string} when the request itself failed."
  (:require [clojure.string :as str]
            [zandy.shared.routes :as routes]))

(defn- url
  "Request URL: `path` with the query map encoded by routes/encode-query (empty
  values dropped)."
  [path query]
  (let [qs (when (seq query) (routes/encode-query query))]
    (cond-> path (seq qs) (str "?" qs))))

(defn- header-map
  "Response headers as a map of lower-case name -> value."
  [^js headers]
  (let [m (atom {})]
    (.forEach headers (fn [v k] (swap! m assoc (str/lower-case k) v)))
    @m))

(defn request!
  "Sends one request to /api (never to another origin). Argument map: {:method
  (keyword, default :get) :path :query (map) :body (map, sent as JSON)
  :form-data (js FormData, for uploads; the browser sets the multipart
  boundary) :csrf (X-CSRF-Token for staff changes) :idempotency-key
  (Idempotency-Key header) :no-cache? (Cache-Control: no-cache, to bypass
  caches for a fresh stock check)} and `callback`. Cookies are sent with the
  same-origin credentials. The callback is called once with {:status int
  :headers {lower-case-name value} :body parsed-JSON-map-or-nil}, or {:status
  nil :error string} when the request itself failed (offline, blocked).
  Non-2xx responses are not errors here: the status and error body are passed
  on for the action handler to interpret. Side effect: network request."
  [{:keys [method path query body form-data csrf idempotency-key no-cache?]} callback]
  (let [method (str/upper-case (name (or method :get)))
        headers (cond-> #js {"Accept" "application/json"}
                  body (doto (aset "Content-Type" "application/json"))
                  csrf (doto (aset "X-CSRF-Token" csrf))
                  idempotency-key (doto (aset "Idempotency-Key" idempotency-key))
                  no-cache? (doto (aset "Cache-Control" "no-cache")))
        init (cond-> #js {:method method :headers headers :credentials "same-origin"}
               body (doto (aset "body" (js/JSON.stringify (clj->js body))))
               form-data (doto (aset "body" form-data)))]
    (-> (js/fetch (url path query) init)
        (.then (fn [^js resp]
                 (-> (.text resp)
                     (.then (fn [text]
                              (callback {:status (.-status resp)
                                         :headers (header-map (.-headers resp))
                                         :body (when-not (str/blank? text)
                                                 (try (js->clj (js/JSON.parse text) :keywordize-keys true)
                                                      (catch :default _ nil)))}))))))
        (.catch (fn [e] (callback {:status nil :error (str e)}))))))
