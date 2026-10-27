(ns zandy.frontend.router
  "Client navigation with the History API (architecture.md section 2.4,
  `frontend.router`). Runs in the browser only. Route matching is shared with
  the server (zandy.shared.routes), so the same URL is the same page on both
  sides. Only this namespace and frontend.effects touch window.history."
  (:require [zandy.shared.routes :as routes]))

(defn current-url
  "Path plus query string of the current location, for example \"/shop?q=serum\"
  (no origin, no hash)."
  []
  (str (.-pathname js/location) (.-search js/location)))

(defn current-origin
  "Origin of the site as the browser sees it; used to build absolute canonical
  and Open Graph URLs when the head is updated after navigation."
  [] (.-origin js/location))

(defn push!
  "Adds `url` to the history (pushState) without reloading. Side effect: the
  address bar changes."
  [url]
  (.pushState js/history nil "" url))

(defn replace!
  "Replaces the current history entry with `url`. Side effect: the address bar
  changes without a new back-button step."
  [url]
  (.replaceState js/history nil "" url))

(defn listen!
  "Calls (f url) when the user goes back or forward (popstate). Side effect:
  registers a window listener."
  [f]
  (.addEventListener js/window "popstate" (fn [_] (f (current-url)))))

(defn match
  "Matches `url` against the shared route table; returns {:name :path :params
  :query :data} or nil."
  [url] (routes/match-url url))
