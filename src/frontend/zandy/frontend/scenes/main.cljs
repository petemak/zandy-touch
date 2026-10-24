(ns zandy.frontend.scenes.main
  "Portfolio entry. Not reachable from zandy.frontend.main, so scenes never
  reach the production bundle."
  (:require [portfolio.ui :as portfolio]
            [replicant.dom :as r-dom]
            [zandy.frontend.scenes.admin]
            [zandy.frontend.scenes.components]
            [zandy.frontend.scenes.pages]))

(defn init []
  ;; Handlers are data; in Portfolio they are only logged.
  (r-dom/set-dispatch! (fn [_ actions] (js/console.info "action" (pr-str actions))))
  (portfolio/start! {:config {:css-paths ["/static/design-tokens.css" "/static/css/app.css"]
                              }}))
