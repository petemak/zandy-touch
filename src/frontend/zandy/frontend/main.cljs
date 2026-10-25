(ns zandy.frontend.main
  "Browser entry. One state atom; the whole tree is re-rendered on every state
  change and Replicant's diffing updates the DOM. Event handlers in the views
  are data; `dispatch-event` turns them into state transitions."
  (:require [clojure.walk :as walk]
            [replicant.dom :as r-dom]
            [zandy.frontend.admin.core :as admin]
            [zandy.frontend.cart-store :as store]
            [zandy.frontend.effects :as effects]
            [zandy.frontend.router :as router]
            [zandy.frontend.state :as state :refer [!state]]
            [zandy.shared.pages :as pages]))

(defn root [st]
  (if (pages/admin-route? st)
    (admin/view st)
    (pages/public-view st)))

(defn render! []
  (when-let [el (.getElementById js/document "app")]
    (r-dom/render el (root @!state))))

(defn transition!
  "Run one action: pure handler, swap the state, run the effects."
  [action]
  (let [{:keys [state fx]} (state/handle @!state action)]
    (reset! !state state)
    (doseq [f fx] (effects/run-effect! f))))

(defn- form-data->map [^js form]
  (let [fd (js/FormData. form)
        m (atom {})]
    (.forEach fd (fn [v k] (swap! m assoc k v)))
    @m))

(defn- placeholder? [v]
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
  "Replace [:event/...] placeholders in an action with values read from the
  DOM event."
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

(defn- modified-click? [^js e]
  (or (.-metaKey e) (.-ctrlKey e) (.-shiftKey e) (.-altKey e) (pos? (.-button e))))

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
  "Replicant's global dispatch: called with the event data and the handler,
  a vector of actions."
  [event-data actions]
  (let [js-event (:replicant/dom-event event-data)]
    (doseq [action actions]
      (when (vector? action)
        (run-action! (enrich action js-event) js-event)))))

(defn- read-embedded []
  (when-let [^js el (.getElementById js/document "initial-state")]
    (try (js->clj (js/JSON.parse (.-textContent el)) :keywordize-keys true)
         (catch :default _ nil))))

(defn- boot-state [embedded]
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

(defn init []
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
