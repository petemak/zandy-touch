(ns zandy.shared.document
  "Full HTML document for a public page, rendered with replicant.string from
  the same views the browser uses. Called by the frontend server only."
  (:require [replicant.string :as rs]
            [zandy.shared.json :as json]
            [zandy.shared.pages :as pages]
            [zandy.shared.seo :as seo]))

(defn embedded-state
  "What the browser needs to render the same page without refetching."
  [state]
  (select-keys state [:site :page]))

(defn document-hiccup
  "{:state :base-url :assets {:css [hrefs] :js href :font-preload [hrefs]}}"
  [{:keys [state base-url assets]}]
  (let [head (seo/head state base-url)]
    [:html {:lang "en"}
     (-> [:head
          [:meta {:charset "utf-8"}]
          [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]]
         (into (seo/head->hiccup head))
         (into [[:link {:rel "icon" :href "/static/assets/favicon.svg" :type "image/svg+xml"}]
                [:link {:rel "icon" :href "/static/assets/favicon-32.png" :type "image/png" :sizes "32x32"}]
                [:link {:rel "apple-touch-icon" :href "/static/assets/apple-touch-icon-180.png"}]
                [:link {:rel "manifest" :href "/static/assets/site.webmanifest"}]])
         (into (for [f (:font-preload assets)]
                 [:link {:rel "preload" :as "font" :type "font/woff2" :href f :crossorigin "anonymous"}]))
         (into (for [href (:css assets)]
                 [:link {:rel "stylesheet" :href href}])))
     [:body
      [:div#app (pages/public-view state)]
      [:script {:id "initial-state" :type "application/json"
                :innerHTML (json/write-str (embedded-state state))}]
      (when (:js assets)
        [:script {:src (:js assets) :defer true}])]]))

(defn render-page
  "Returns the HTML string."
  [opts]
  (str "<!DOCTYPE html>" (rs/render (document-hiccup opts))))
