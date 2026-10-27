(ns zandy.shared.document
  "Full HTML document for a public page, built on the JVM by the frontend
  server (architecture.md section 2.5, step 4) with replicant.string from the
  same views the browser uses. Includes the SEO head, the page body inside
  #app and the embedded initial state. Shared code (.cljc) but called only by
  ssr.render."
  (:require [replicant.string :as rs]
            [zandy.shared.json :as json]
            [zandy.shared.pages :as pages]
            [zandy.shared.seo :as seo]))

(defn embedded-state
  "The part of `state` the browser needs to render the same page without
  refetching: just :site and :page. Returns a map."
  [state]
  (select-keys state [:site :page]))

(defn document-hiccup
  "Hiccup for the whole <html> element of a public page.
  Argument map: :state (page state with :route :site :page ...), :base-url
  (site origin without trailing slash, used for canonical and Open Graph URLs)
  and :assets ({:css [hrefs] :js href :font-preload [hrefs]}).
  The head holds the SEO tags (title, description, canonical, Open Graph,
  optional robots, JSON-LD) from shared.seo, icons, manifest, font preloads
  and stylesheets. The body holds #app with the page view, the JSON
  initial-state script (<script id=\"initial-state\">) and the deferred bundle
  script when :js is given. Pure."
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
      ;; Why #app holds the full page: crawlers, link previews and slow phones
      ;; get complete HTML, and browsing works without JavaScript. This is not
      ;; a true takeover: when the bundle loads, frontend.main renders the
      ;; same view into #app from the embedded state, and Replicant clears the
      ;; container on its first render, so the server markup is replaced by
      ;; identical markup (see architecture-review.md ruling on DA7).
      [:div#app (pages/public-view state)]
      ;; The JSON is written by shared.json/write-str, which escapes < > & so
      ;; the state can never close the script tag early.
      [:script {:id "initial-state" :type "application/json"
                :innerHTML (json/write-str (embedded-state state))}]
      (when (:js assets)
        [:script {:src (:js assets) :defer true}])]]))

(defn render-page
  "Renders `opts` (see document-hiccup) to a complete HTML string starting with
  the doctype. Pure; the caller (ssr.render) sets status and headers."
  [opts]
  (str "<!DOCTYPE html>" (rs/render (document-hiccup opts))))
