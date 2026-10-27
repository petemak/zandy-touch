(ns zandy.ssr.dev-proxy
  "Development stand-in for the edge proxy (architecture.md section 2.1 and
  D2). JVM only, not shipped (lives under ssr/dev). Listens on one port so the
  browser sees a single origin: /api/* and /media/* are forwarded to the
  backend (or to the stub API), everything else to the frontend server. That
  keeps the zt_session cookie and relative /api URLs working locally exactly
  as in production, with no CORS. Run: cd ssr && clojure -M:proxy [listen-port
  backend-port ssr-port] (defaults 8090, 8080, 8081)."
  (:require [clojure.string :as str])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetSocketAddress URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)))

(def skip
  "Lower-case names of hop-by-hop request and response headers the proxy does
  not copy (the JDK client and server set their own)."
  #{"host" "connection" "content-length" "transfer-encoding" "keep-alive" "upgrade" "expect"})

(defn handler
  "HttpHandler for the proxy. Arguments: `client` (java.net.http.HttpClient
  that does not follow redirects, so the browser sees them), and the upstream
  base URLs `backend` and `ssr`. For each request it picks the upstream by
  path prefix, copies method, headers and body, sets X-Client-Ip to 127.0.0.1
  (the backend's rate limiter reads it), relays status, headers and body, and
  adds X-Upstream (backend or ssr) to the response to help debugging. A
  failure to reach the upstream becomes a 502 text response. Side effect:
  network I/O."
  [client backend ssr]
  (reify HttpHandler
    (handle [_ ex]
      (let [^HttpExchange ex ex
            uri (.getRequestURI ex)
            path (.getRawPath uri)
            [upstream tag] (if (or (str/starts-with? path "/api/") (str/starts-with? path "/media/"))
                             [backend "backend"] [ssr "ssr"])
            target (URI/create (str upstream path (when-let [q (.getRawQuery uri)] (str "?" q))))
            body (.readAllBytes (.getRequestBody ex))
            b (HttpRequest/newBuilder target)]
        (doseq [[k vs] (.getRequestHeaders ex)
                :when (not (skip (str/lower-case k)))]
          (doseq [v vs] (.header b k v)))
        (.header b "X-Client-Ip" "127.0.0.1")
        (.method b (.getRequestMethod ex) (if (pos? (alength body)) (HttpRequest$BodyPublishers/ofByteArray body) (HttpRequest$BodyPublishers/noBody)))
        (try
          (let [resp (.send client (.build b) (HttpResponse$BodyHandlers/ofByteArray))
                out (.getResponseHeaders ex)]
            (doseq [[k vs] (.map (.headers resp)) :when (and (not (skip (str/lower-case k))) (not (str/starts-with? k ":")))]
              (doseq [v vs] (.add out k v)))
            (.add out "X-Upstream" tag)
            (let [bs ^bytes (.body resp) status (.statusCode resp)]
              (.sendResponseHeaders ex status (if (or (= 204 status) (= 304 status) (= "HEAD" (.getRequestMethod ex))) -1 (alength bs)))
              (when-not (or (= 204 status) (= 304 status) (= "HEAD" (.getRequestMethod ex)))
                (with-open [o (.getResponseBody ex)] (.write o bs)))))
          (catch Throwable t
            (let [bs (.getBytes (str "proxy error: " (.getMessage t)))]
              (.sendResponseHeaders ex 502 (alength bs))
              (with-open [o (.getResponseBody ex)] (.write o bs)))))))))

(defn -main
  "Starts the proxy. Arguments (strings, all optional): listen port (8090),
  backend port (8080), frontend server port (8081). Side effect: opens the
  listening socket and prints the port."
  [& [listen backend ssr]]
  (let [listen (or (some-> listen parse-long) 8090)
        s (HttpServer/create (InetSocketAddress. "0.0.0.0" (int listen)) 0)
        client (-> (HttpClient/newBuilder) (.followRedirects java.net.http.HttpClient$Redirect/NEVER) (.build))]
    (.createContext s "/" (handler client (str "http://127.0.0.1:" (or backend 8080)) (str "http://127.0.0.1:" (or ssr 8081))))
    (.setExecutor s (java.util.concurrent.Executors/newFixedThreadPool 16))
    (.start s)
    (.println System/out (str "dev proxy on " listen))))
