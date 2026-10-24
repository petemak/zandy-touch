(ns zandy.ssr.render
  "HTML response: status, Cache-Control, ETag and security headers around
  zandy.shared.document/render-page."
  (:require [zandy.shared.document :as document]
            [zandy.shared.pages :as pages])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.util HexFormat)))

(def csp "default-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'")

(defn security-headers [{:keys [site-indexable]}]
  (cond-> {"Content-Security-Policy" csp
           "X-Content-Type-Options" "nosniff"
           "Referrer-Policy" "strict-origin-when-cross-origin"}
    (not site-indexable) (assoc "X-Robots-Tag" "noindex")))

(defn- etag [^String body]
  (let [md (MessageDigest/getInstance "SHA-1")
        digest (.digest md (.getBytes body StandardCharsets/UTF_8))]
    (str "W/\"" (subs (.formatHex (HexFormat/of) digest) 0 20) "\"")))

(defn- cache-control [cfg route page-status]
  (let [pc (:page-cache cfg)]
    (case page-status
      :not-found (:not-found pc)
      :unavailable "no-store"
      (case (:cache (:data route))
        :catalogue (:catalogue pc)
        :info (:info pc)
        (:shell pc)))))

(defn page-response
  "state: full page state. assets: {:css :js :font-preload}. if-none-match is
  the request header value."
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
