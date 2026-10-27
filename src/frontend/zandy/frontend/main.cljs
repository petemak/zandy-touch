(ns zandy.frontend.main
  "Browser entry point of the public app and the admin area (architecture.md
  section 2.4, `frontend.main`). Runs in the browser only (ClojureScript). It
  owns the glue: boot the single state atom from the page the server embedded,
  render the whole tree into #app on every state change (Replicant diffs the
  DOM), and turn DOM events into action vectors that `transition!` feeds to
  the pure handlers in frontend.state; the returned effects are run by
  frontend.effects. Views are pure functions of state and event handlers are
  data."
  (:require [clojure.walk :as walk]
            [replicant.dom :as r-dom]
            [zandy.frontend.admin.core :as admin]
            [zandy.frontend.cart-store :as store]
            [zandy.frontend.effects :as effects]
            [zandy.frontend.router :as router]
            [zandy.frontend.state :as state :refer [!state]]
            [zandy.shared.pages :as pages]))

(defn root
  "Top-level view for a state. Argument: the state map. Returns the admin view
  (frontend.admin.core) for admin routes (A1 to A13) and the public page view
  (P1 to P17, shared.pages/public-view) otherwise. Pure."
  [st]
  (if (pages/admin-route? st)
    (admin/view st)
    (pages/public-view st)))

(defn render!
  "Renders the current state into the #app element with Replicant. Side effect:
  DOM updates. Does nothing when #app does not exist. Registered as a watch on
  the state atom, so every state change re-renders the whole tree."
  []
  (when-let [el (.getElementById js/document "app")]
    (r-dom/render el (root @!state))))

(defn transition!
  "Runs one action. Argument: an action vector such as [:cart/add product qty].
  Calls the pure handler (state/handle) on the current state, resets the state
  atom to the new state and runs each returned effect. Side effects: state
  change (which triggers a render) and whatever the effects do (fetch,
  localStorage, navigation, focus)."
  [action]
  (let [{:keys [state fx]} (state/handle @!state action)]
    (reset! !state state)
    (doseq [f fx] (effects/run-effect! f))))

(defn- form-data->map
  "Reads a form element into a map of string field name -> value (last value
  wins for repeated names)."
  [^js form]
  (let [fd (js/FormData. form)
        m (atom {})]
    (.forEach fd (fn [v k] (swap! m assoc k v)))
    @m))

(defn- placeholder?
  "True for the [:event/...] placeholder vectors that `enrich` replaces."
  [v]
  (and (vector? v) (= 1 (count v)) (keyword? (first v)) (= "event" (namespace (first v)))))

(defn- picked-files
  "Files from a file input change or a drop, as plain maps (the File object
  stays inside :file). The input is cleared so the same files can be picked
  again."
  [^js js-event]
  (let [^js target (.-target js-event)
        ^js dt (.-dataTransfer js-event)
        ^js file-list (or (some-> dt .-files) (.-files target))
        files (mapv (fn [^js f] {:name (.-name f) :size (.-size f) :type (.-type f) :file f})
                    (array-seq file-list))]
    (when (= "file" (.-type target)) (set! (.-value target) ""))
    files))

(defn enrich
  "Replaces the [:event/...] placeholders inside an action with values read
  from the DOM event, so views can describe \"the value of the input\" as data.
  Arguments: `action` (vector, nested placeholders allowed) and `js-event`.
  Supported placeholders: :event/target.value, :event/target.checked,
  :event/target.open, :event/key, :event/files (picked or dropped files as
  {:name :size :type :file} maps) and :event/form-data (a map of string field
  name to value). Returns the action with the values filled in. Pure apart
  from reading the event; picking files also clears the file input."
  [action ^js js-event]
  (let [target (some-> js-event .-target)]
    (walk/postwalk
     (fn [v]
       (if (placeholder? v)
         (case (first v)
           :event/target.value (.-value target)
           :event/target.checked (.-checked target)
           :event/target.open (.-open target)
           :event/key (.-key js-event)
           :event/files (picked-files js-event)
           :event/form-data (form-data->map target)
           v)
         v))
     action)))

(defn- modified-click?
  "True when a click should be left to the browser (modifier key or a
  non-primary button)."
  [^js e]
  (or (.-metaKey e) (.-ctrlKey e) (.-shiftKey e) (.-altKey e) (pos? (.-button e))))

;; Plain left clicks on internal links become client navigation. Modified
;; clicks (ctrl, meta, shift, middle button) and target=_blank links are left
;; to the browser so "open in new tab" keeps working.
(defn- run-action! [action ^js js-event]
  (case (first action)
    :dom/prevent-default (when js-event (.preventDefault js-event))
    :nav/link
    (let [anchor (some-> js-event .-currentTarget)]
      (when-not (or (nil? js-event) (modified-click? js-event)
                    (= "_blank" (some-> anchor (.getAttribute "target"))))
        (.preventDefault js-event)
        (transition! action)))
    (transition! action)))

(defn dispatch-event
  "Replicant's global dispatch function, installed in `init`. Arguments:
  `event-data` (Replicant's map; the DOM event is under :replicant/dom-event)
  and `actions` (the handler vector from the view). Each action is enriched
  and run in order. Side effects: those of the actions."
  [event-data actions]
  (let [js-event (:replicant/dom-event event-data)]
    (doseq [action actions]
      (when (vector? action)
        (run-action! (enrich action js-event) js-event)))))

(defn- read-embedded
  "The initial state the server embedded in <script id=\"initial-state\">, parsed
  into a keyword-keyed map, or nil when it is missing or invalid."
  []
  (when-let [^js el (.getElementById js/document "initial-state")]
    (try (js->clj (js/JSON.parse (.-textContent el)) :keywordize-keys true)
         (catch :default _ nil))))

;; Why status :ok for routes with no requests: cart, checkout and the like
;; have no server data, so waiting for a page load would show "Loading" for
;; nothing.
(defn- boot-state
  "First state of the app. Argument: the embedded state (or nil). Combines the
  shared initial state, the stored cart and last order, the matched route and
  the embedded :site and :page. A path that matches no route gets the
  :not-found page status."
  [embedded]
  (let [route (router/match (router/current-url))
        embedded-page (:page embedded)
        status (keyword (or (:status embedded-page) "loading"))
        status (if (and (= :loading status) route (not (seq (pages/requests route)))) :ok status)]
    (cond-> (assoc (state/initial-state)
                   :cart (store/load-cart)
                   :last-order (store/load-last-order)
                   :route (or route {:name :not-found :path (.-pathname js/location)})
                   :site (:site embedded)
                   :page (assoc embedded-page :status status))
      (nil? route) (assoc-in [:page :status] :not-found))))

;; Server render then client re-render: the server already put full HTML in
;; #app. Here Replicant renders the same views from the embedded state, and
;; its first render clears the container, so the server markup is replaced by
;; identical markup (a visible takeover does not happen, but the content,
;; scroll position and SEO tags come from the server HTML). Pages the server
;; does not render (cart, checkout, admin ...) arrive with :loading and are
;; rendered here for the first time.
(defn init
  "Starts the app: installs the effects shell and the Replicant dispatch, reads
  the embedded initial state, makes it the state atom, registers the render
  watch and the back/forward listener, renders, and then either loads the page
  (when the server sent no data) or runs the route's enter effects. Side
  effects: DOM, history listener, fetch."
  []
  (effects/init! transition! (fn [] @!state))
  (r-dom/set-dispatch! dispatch-event)
  (let [embedded (read-embedded)
        st (boot-state embedded)]
    (reset! !state st)
    (add-watch !state ::render (fn [_ _ _ _] (render!)))
    (router/listen! (fn [url] (transition! [:nav/popstate url])))
    (render!)
    (if (= :loading (get-in st [:page :status]))
      (transition! [:nav/replace (router/current-url)])
      (doseq [f (state/route-enter st)] (effects/run-effect! f)))))
