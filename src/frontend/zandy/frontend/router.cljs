(ns zandy.frontend.router
  "Client navigation with the History API. Route matching is shared with the
  server (zandy.shared.routes)."
  (:require [zandy.shared.routes :as routes]))

(defn current-url []
  (str (.-pathname js/location) (.-search js/location)))

(defn current-origin [] (.-origin js/location))

(defn push! [url]
  (.pushState js/history nil "" url))

(defn replace! [url]
  (.replaceState js/history nil "" url))

(defn listen!
  "Calls (f url) on back/forward."
  [f]
  (.addEventListener js/window "popstate" (fn [_] (f (current-url)))))

(defn match [url] (routes/match-url url))
