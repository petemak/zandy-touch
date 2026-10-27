(ns zandy.ssr.render
  "Turns a page state into an HTTP response (architecture.md section 2.3,
  `ssr.render`). JVM only. Calls zandy.shared.document/render-page and adds
  the status, Cache-Control per page type, a weak ETag with 304 handling,
  Retry-After on 503 and the security headers including the CSP."
  (:require [zandy.shared.document :as document]
            [zandy.shared.pages :as pages])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.util HexFormat)))

(def csp
  "Content-Security-Policy sent with every page: same-origin only, data:
  images, no framing. No inline scripts are needed because the JSON-LD and the
  initial state are non-executable data blocks."
  "default-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'")

(defn security-headers
  "Security headers for any response. Argument: config (uses :site-indexable).
  Returns a map with the CSP, X-Content-Type-Options and Referrer-Policy; when
  the site is not indexable (staging) it adds X-Robots-Tag: noindex as a
  second guard next to robots.txt."
  [{:keys [site-indexable]}]
  (cond-> {"Content-Security-Policy" csp
           "X-Content-Type-Options" "nosniff"
           "Referrer-Policy" "strict-origin-when-cross-origin"}
    (not site-indexable) (assoc "X-Robots-Tag" "noindex")))

(defn- etag
  "Weak ETag (first 20 hex characters of the SHA-1 of the HTML) for the
  response body `body`."
  [^String body]
  (let [md (MessageDigest/getInstance "SHA-1")
        digest (.digest md (.getBytes body StandardCharsets/UTF_8))]
    (str "W/\"" (subs (.formatHex (HexFormat/of) digest) 0 20) "\"")))

(defn- cache-control
  "Cache-Control value for a page. Arguments: config (uses :page-cache), the
  matched `route` (its :cache key: :catalogue or :info) and the page status.
  The 404 page uses :not-found, an unavailable page \"no-store\", catalogue
  pages the short lifetime, info pages the longer one, and shell pages (cart,
  checkout, admin ...) the :shell value (\"no-cache\" with an ETag). Values come
  from PAGE_CACHE_* configuration (architecture.md D2)."
  [cfg route page-status]
  (let [pc (:page-cache cfg)]
    (case page-status
      :not-found (:not-found pc)
      :unavailable "no-store"
      (case (:cache (:data route))
        :catalogue (:catalogue pc)
        :info (:info pc)
        (:shell pc)))))

;; Why ETag only on 200: error pages must never be revalidated into a cached
;; "not modified". Cache-Control comes from config so lifetimes can be tuned
;; without a release.
(defn page-response
  "Builds the Ring response for a page. Arguments: `cfg` (config), `assets`
  ({:css :js :font-preload}, from static/assets), `state` (full page state),
  `if-none-match` (request header value or nil) and `request-id`. Returns
  {:status :headers :body}: the status comes from the page status (200, 404,
  503); a 200 carries an ETag and gets an empty 304 when it matches
  if-none-match; a 503 carries Retry-After 30 and no-store. Renders the HTML
  on every call (the CDN does the caching); no other side effects."
  [cfg assets state if-none-match request-id]
  (let [page-status (get-in state [:page :status])
        status (pages/status->http page-status)
        html (document/render-page {:state state :base-url (:site-base-url cfg) :assets assets})
        tag (etag html)
        headers (merge (security-headers cfg)
                       {"Content-Type" "text/html; charset=utf-8"
                        "Cache-Control" (cache-control cfg (:route state) page-status)
                        "X-Request-Id" request-id}
                       (when (= 503 status) {"Retry-After" "30"})
                       (when (= 200 status) {"ETag" tag}))]
    (if (and (= 200 status) (= tag if-none-match))
      {:status 304 :headers (dissoc headers "Content-Type") :body ""}
      {:status status :headers headers :body html})))
