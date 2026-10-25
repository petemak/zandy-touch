(ns zandy.frontend.effects
  "The imperative shell: runs the effects returned by the pure handlers in
  zandy.frontend.state. Network goes through zandy.frontend.api, navigation
  through zandy.frontend.router, the cart through zandy.frontend.cart-store."
  (:require [clojure.string :as str]
            [zandy.frontend.api :as api]
            [zandy.frontend.cart-store :as store]
            [zandy.frontend.router :as router]
            [zandy.shared.json :as json]
            [zandy.shared.seo :as seo]))

(defonce ^:private !dispatch (atom nil))
(defonce ^:private !state-getter (atom nil))

(defn init! [dispatch state-getter]
  (reset! !dispatch dispatch)
  (reset! !state-getter state-getter))

(defn- dispatch! [action] (when-let [f @!dispatch] (f action)))

(defn- csrf [] (get-in (@!state-getter) [:admin :session :csrf]))

(defn- with-credentials
  "Staff mutations carry the CSRF token (api-contract.md 1.7)."
  [req]
  (if (and (not (:csrf req))
           (or (str/starts-with? (str (:path req)) "/api/admin")
               (= :delete (:method req)))
           (not= :get (or (:method req) :get)))
    (assoc req :csrf (csrf))
    req))

;; ---------------------------------------------------------------------------
;; Head (title, description, canonical, Open Graph, robots, JSON-LD)

(defn- set-meta! [selector attr-name content]
  (when-let [^js el (.querySelector js/document selector)]
    (.setAttribute el attr-name content)))

(defn- apply-head! [{:keys [title description canonical robots og jsonld]}]
  (set! (.-title js/document) title)
  (set-meta! "meta[name=description]" "content" description)
  (set-meta! "link[rel=canonical]" "href" canonical)
  (set-meta! "meta[property='og:title']" "content" (:title og))
  (set-meta! "meta[property='og:description']" "content" (:description og))
  (set-meta! "meta[property='og:image']" "content" (:image og))
  (set-meta! "meta[property='og:url']" "content" (:url og))
  (set-meta! "meta[property='og:type']" "content" (:type og))
  (let [existing (.querySelector js/document "meta[name=robots]")]
    (cond
      (and robots existing) (.setAttribute existing "content" robots)
      robots (let [m (.createElement js/document "meta")]
               (.setAttribute m "name" "robots") (.setAttribute m "content" robots)
               (.appendChild (.-head js/document) m))
      existing (.remove existing)))
  (when-let [^js ld (.querySelector js/document "script[type='application/ld+json']")]
    (set! (.-textContent ld) (json/write-str jsonld))))

;; ---------------------------------------------------------------------------

(defn- focus! [id]
  (js/requestAnimationFrame
   (fn [] (when-let [^js el (.getElementById js/document id)]
            (.focus el #js {:preventScroll false})))))

(defn run-effect!
  "Execute one effect."
  [[fx-name a b]]
  (case fx-name
    :fx/dispatch (dispatch! a)
    :fx/request (api/request! (with-credentials a) (fn [result] (dispatch! (conj b result))))
    :fx/uuid (dispatch! (conj a (str (random-uuid))))
    :fx/page-load
    ;; a = url, b = {key {:path :query ...}}: run all in parallel, then dispatch.
    (let [results (atom {})
          pending (atom (count b))]
      (doseq [[k spec] b]
        (api/request! spec (fn [r]
                             (swap! results assoc k r)
                             (when (zero? (swap! pending dec))
                               (dispatch! [:page/loaded a @results]))))))
    :fx/upload-files
    ;; a = {:path :files [{:file <js File>}] :field (default "files") :query :idempotency-key}.
    ;; Multipart; the browser sets the boundary header itself.
    (let [fd (js/FormData.)
          field (or (:field a) "files")]
      (doseq [f (:files a)] (.append fd field (:file f)))
      (api/request! (with-credentials (cond-> {:method :post :path (:path a) :form-data fd :idempotency-key (:idempotency-key a)}
                                        (:query a) (assoc :query (:query a))))
                    (fn [r] (dispatch! (conj b r)))))
    :fx/push-url (router/push! a)
    :fx/replace-url (router/replace! a)
    :fx/scroll-top (.scrollTo js/window 0 0)
    :fx/print (.print js/window)
    :fx/head (apply-head! (seo/head (@!state-getter) (router/current-origin)))
    :fx/save-cart (store/save-cart! a)
    :fx/save-order (store/save-last-order! a)
    :fx/focus (focus! a)
    :fx/focus-main (focus! "main")
    :fx/submit-form (when-let [^js f (.querySelector js/document a)] (.requestSubmit f))
    :fx/copy (some-> js/navigator .-clipboard (.writeText a))
    :fx/delay (js/setTimeout #(dispatch! b) a)
    :fx/log-error (js/console.error a)
    (js/console.error (str "Unknown effect " fx-name))))
