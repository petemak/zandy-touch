(ns zandy.frontend.effects
  "The imperative shell of the browser app (architecture.md section 2.4,
  `frontend.effects`). Runs in the browser only. Pure handlers in
  frontend.state return effects as data ([:fx/request ...], [:fx/push-url url]
  ...); `run-effect!` executes them: network through frontend.api, navigation
  through frontend.router, the cart through frontend.cart-store, plus DOM
  focus, scrolling, printing, clipboard, timers, console logging and the
  document head. This is the only place those side effects happen."
  (:require [clojure.string :as str]
            [zandy.frontend.api :as api]
            [zandy.frontend.cart-store :as store]
            [zandy.frontend.router :as router]
            [zandy.shared.json :as json]
            [zandy.shared.seo :as seo]))

(defonce ^:private !dispatch (atom nil))
(defonce ^:private !state-getter (atom nil))

;; Why: on phones the category links sit in a horizontally scrolling strip. A
;; link reached by keyboard (Tab) can be partly out of view; scrollIntoView
;; with inline "nearest" brings it fully into view, and the CSS scroll-padding
;; and scroll-margin keep it clear of the strip's edges. One document-wide
;; listener is cheaper than one per link and survives re-renders.
(defn- reveal-nav-link!
  "Keyboard focus on a category link inside the scrolling phone strip: scroll
  it fully into view (the browser may leave a half-visible link clipped).
  scrollIntoView honours the strip's scroll-padding and the link's scroll-margin."
  [^js e]
  (let [^js el (.-target e)]
    (when (and el (.closest el ".site-nav__list"))
      (.scrollIntoView el #js {:inline "nearest" :block "nearest"}))))

(defn init!
  "Connects the shell to the app. Arguments: `dispatch` (a function that takes
  an action vector, `transition!` in main) and `state-getter` (a function that
  returns the current state). Also registers the document-wide focusin handler
  for the category strip. Side effect: event listener."
  [dispatch state-getter]
  (reset! !dispatch dispatch)
  (reset! !state-getter state-getter)
  (.addEventListener js/document "focusin" reveal-nav-link!))

(defn- dispatch!
  "Dispatches `action` through the function given to `init!`; does nothing
  before init."
  [action] (when-let [f @!dispatch] (f action)))

(defn- csrf
  "The staff session's CSRF token from the current state, or nil."
  [] (get-in (@!state-getter) [:admin :session :csrf]))

;; Why: the CSRF token is only needed on state-changing staff calls
;; (api-contract.md 1.7). Adding it here means no handler has to remember it.
;; Cookies are sent by api/request! itself.
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

(defn- set-meta!
  "Sets attribute `attr-name` to `content` on the first element matching
  `selector`, if it exists."
  [selector attr-name content]
  (when-let [^js el (.querySelector js/document selector)]
    (.setAttribute el attr-name content)))

(defn- apply-head!
  "Updates the live document head after client navigation. Argument: the head
  map from shared.seo/head. Sets the title, description, canonical link, Open
  Graph tags, adds or removes the robots meta tag, and rewrites the JSON-LD
  script. Side effect: DOM changes in <head>. Why: after a client-side
  navigation the server's head is stale, and the SEO rules say every page has
  its own title, description and structured data."
  [{:keys [title description canonical robots og jsonld]}]
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

(defn- focus!
  "Focuses the element with id `id` on the next animation frame (after the
  render that creates it). Used for error summaries, dialogs and \"added to
  cart\" notices so keyboard and screen reader users land in the right place."
  [id]
  (js/requestAnimationFrame
   (fn [] (when-let [^js el (.getElementById js/document id)]
            (.focus el #js {:preventScroll false})))))

(defn run-effect!
  "Executes one effect vector [name a b]. Effects: [:fx/dispatch action];
  [:fx/request req action-prefix] (fetch, then dispatch the prefix with the
  result appended; staff changes get the CSRF token added); [:fx/uuid
  action-prefix] (dispatch with a fresh UUID appended, used for idempotency
  keys); [:fx/page-load url {key request}] (run the requests in parallel, then
  dispatch [:page/loaded url results]); [:fx/upload-files {:path :files :field
  :query :idempotency-key} action-prefix] (multipart POST); [:fx/push-url url]
  and [:fx/replace-url url]; [:fx/scroll-top]; [:fx/print]; [:fx/head] (update
  the document head for the current state); [:fx/save-cart cart] and
  [:fx/save-order order] (storage); [:fx/focus id], [:fx/focus-main];
  [:fx/submit-form selector]; [:fx/copy text]; [:fx/delay ms action];
  [:fx/log-error message]. An unknown effect is logged to the console, not
  thrown. Side effects as listed."
  [[fx-name a b]]
  (case fx-name
    :fx/dispatch (dispatch! a)
    :fx/request (api/request! (with-credentials a) (fn [result] (dispatch! (conj b result))))
    ;; Why generated here: handlers must stay pure and cannot call
    ;; random-uuid; the key is then dispatched back as an action.
    :fx/uuid (dispatch! (conj a (str (random-uuid))))
    ;; All requests of a page start together, and the page is assembled only
    ;; when the last one is back, so the view never renders half of its data.
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
