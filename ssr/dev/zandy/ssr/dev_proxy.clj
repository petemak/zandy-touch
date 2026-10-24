(ns zandy.ssr.dev-proxy
  "Local stand-in for the edge proxy (architecture.md section 2.1): /api/* and
  /media/* go to the backend, everything else to the frontend server, so the
  browser sees one origin.
  Run: cd ssr && clojure -M:proxy [listen-port backend-port ssr-port]"
  (:require [clojure.string :as str])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetSocketAddress URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)))

(def skip #{"host" "connection" "content-length" "transfer-encoding" "keep-alive" "upgrade" "expect"})

(defn handler [client backend ssr]
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

(defn -main [& [listen backend ssr]]
  (let [listen (or (some-> listen parse-long) 8090)
        s (HttpServer/create (InetSocketAddress. "0.0.0.0" (int listen)) 0)
        client (-> (HttpClient/newBuilder) (.followRedirects java.net.http.HttpClient$Redirect/NEVER) (.build))]
    (.createContext s "/" (handler client (str "http://127.0.0.1:" (or backend 8080)) (str "http://127.0.0.1:" (or ssr 8081))))
    (.setExecutor s (java.util.concurrent.Executors/newFixedThreadPool 16))
    (.start s)
    (.println System/out (str "dev proxy on " listen))))
