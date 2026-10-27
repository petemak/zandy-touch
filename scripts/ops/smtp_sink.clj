;; A tiny SMTP server that accepts everything and prints one line per message to stdout:
;;   MSG {:to ["a@b"] :subject "..." :message-id "<...>"}
;; Used by two-instance-smoke.sh so the real SMTP mailer can be exercised without sending any mail.
;; Works with babashka (bb scripts/ops/smtp_sink.clj 2525) and with plain Clojure (clojure.main <file> 2525).
(require '[clojure.string :as str])
(import '(java.net ServerSocket)
        '(java.io BufferedReader InputStreamReader PrintWriter))

(def port (parse-long (or (first *command-line-args*) (System/getenv "SINK_PORT") "2525")))

(defn handle [sock]
  (with-open [s sock
              in (BufferedReader. (InputStreamReader. (.getInputStream s)))
              out (PrintWriter. (.getOutputStream s) true)]
    (let [say (fn [line] (.print out (str line "\r\n")) (.flush out))]
      (say "220 sink ESMTP")
      (loop [rcpts [] data? false lines []]
        (when-let [line (.readLine in)]
          (cond
            data?
            (if (= line ".")
              (let [hdr (fn [h] (some #(when (str/starts-with? (str/lower-case %) (str h ":")) (str/trim (subs % (inc (count h))))) lines))]
                (println "MSG" (pr-str {:to rcpts :subject (hdr "subject") :message-id (hdr "message-id")}))
                (flush)
                (say "250 queued")
                (recur [] false []))
              (recur rcpts true (conj lines line)))
            :else
            (let [u (str/upper-case line)]
              (cond
                (str/starts-with? u "EHLO") (do (say "250-sink") (say "250-AUTH PLAIN LOGIN") (say "250 8BITMIME") (recur rcpts false lines))
                (str/starts-with? u "HELO") (do (say "250 sink") (recur rcpts false lines))
                (str/starts-with? u "AUTH PLAIN") (do (say "235 ok") (recur rcpts false lines))
                (str/starts-with? u "AUTH LOGIN") (do (say "334 VXNlcm5hbWU6") (.readLine in) (say "334 UGFzc3dvcmQ6") (.readLine in) (say "235 ok") (recur rcpts false lines))
                (str/starts-with? u "MAIL FROM") (do (say "250 ok") (recur [] false []))
                (str/starts-with? u "RCPT TO") (do (say "250 ok") (recur (conj rcpts (second (re-find #"<([^>]*)>" line))) false lines))
                (= u "DATA") (do (say "354 go") (recur rcpts true []))
                (= u "RSET") (do (say "250 ok") (recur [] false []))
                (= u "NOOP") (do (say "250 ok") (recur rcpts false lines))
                (= u "QUIT") (say "221 bye")
                :else (do (say "250 ok") (recur rcpts false lines))))))))))

(with-open [server (ServerSocket. port)]
  (println "sink listening on" port) (flush)
  (while true
    (let [sock (.accept server)]
      (future (try (handle sock) (catch Exception e (println "sink error" (.getMessage e)) (flush)))))))
