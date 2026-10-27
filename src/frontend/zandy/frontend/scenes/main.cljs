(ns zandy.frontend.scenes.main
  "Entry point of the Portfolio component workbench (the :portfolio shadow-cljs
  build). Browser only. It is separate from zandy.frontend.main, so scenes
  never reach the production bundle. It requires every scenes namespace so
  their scenes register."
  (:require [portfolio.ui :as portfolio]
            [replicant.dom :as r-dom]
            [zandy.frontend.scenes.admin]
            [zandy.frontend.scenes.components]
            [zandy.frontend.scenes.help]
            [zandy.frontend.scenes.pages]
            [zandy.frontend.scenes.widgets]))

(defn init
  "Starts Portfolio. In Portfolio, view event handlers (action vectors) are not
  run, only logged to the console, which keeps scenes free of side effects.
  Side effects: Replicant dispatch function, Portfolio UI."
  []
  ;; Handlers are data; in Portfolio they are only logged.
  (r-dom/set-dispatch! (fn [_ actions] (js/console.info "action" (pr-str actions))))
  (portfolio/start! {:config {:css-paths ["/static/design-tokens.css" "/static/css/app.css"]
                              }}))
