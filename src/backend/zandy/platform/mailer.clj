(ns zandy.platform.mailer
  "Outbound email behind a protocol: Gmail SMTP (Angus Mail) in production, a log-only mailer in
  dev and tests. Credentials come from the environment only."
  (:require [zandy.platform.config :as config]
            [zandy.platform.log :as log])
  (:import (jakarta.mail Authenticator Message$RecipientType PasswordAuthentication Session Transport)
           (jakarta.mail.internet InternetAddress MimeMessage)
           (java.util Properties)))

(defprotocol Mailer
  "Protocol for outbound email. Implementations: LogMailer (dev and tests) and SmtpMailer (production)."
  (send! [this message] "Sends {:to :subject :text}. Throws on failure."))

(defrecord LogMailer [sent]
  Mailer
  (send! [_ {:keys [to subject] :as message}]
    (swap! sent conj message)
    (log/info :mail-logged {:to-count (count (if (sequential? to) to [to])) :subject subject})
    true))

(defn log-mailer
  "A Mailer that only records messages in memory and logs a line (recipient count and subject, not addresses). For dev and tests."
  [] (->LogMailer (atom [])))

(defn sent-messages
  "The vector of message maps a LogMailer has been asked to send, oldest first."
  [^LogMailer m] @(:sent m))

(defrecord SmtpMailer [from host port user password]
  Mailer
  (send! [_ {:keys [to subject text]}]
    (let [props (doto (Properties.)
                  (.put "mail.smtp.host" host)
                  (.put "mail.smtp.port" (str port))
                  (.put "mail.smtp.auth" "true")
                  (.put "mail.smtp.connectiontimeout" "10000")
                  (.put "mail.smtp.timeout" "15000")
                  (.put "mail.smtp.writetimeout" "15000"))
          ;; Port 465 is implicit TLS; other ports (587) start plain and upgrade with STARTTLS.
          _ (if (= 465 port)
              (.put props "mail.smtp.ssl.enable" "true")
              (.put props "mail.smtp.starttls.enable" "true"))
          session (Session/getInstance props (proxy [Authenticator] []
                                               (getPasswordAuthentication [] (PasswordAuthentication. user password))))
          msg (doto (MimeMessage. session)
                (.setFrom (InternetAddress. from))
                (.setSubject (str subject) "UTF-8")
                (.setText (str text) "UTF-8"))]
      (doseq [t (if (sequential? to) to [to])]
        (.addRecipient msg Message$RecipientType/TO (InternetAddress. ^String t)))
      (Transport/send msg)
      true)))

(defn make-mailer
  "Builds the Mailer for the config :mail map ({:impl :log|:smtp :host :port :user :password}).
  `from` is the sender address (the owner email). Returns a LogMailer or an SmtpMailer; no I/O until send!."
  [{:keys [impl host port user password]} from]
  (case impl
    :log (log-mailer)
    :smtp (->SmtpMailer from host port user password)))

(defn -main
  "Entry point of `clojure -M:send-test-mail`: sends one test message to OWNER_EMAIL over SMTP to check the settings.
  Side effects: one email, one log line. Throws if SMTP fails."
  [& _]
  (let [cfg (config/load-config (config/profile-from-env))
        owner (get-in cfg [:auth :owner-email])
        mailer (make-mailer (assoc (:mail cfg) :impl :smtp) owner)]
    (send! mailer {:to owner :subject "[Zandy Touch] Test message"
                   :text "This is a test message from the Zandy Touch backend. SMTP is configured correctly."})
    (log/info :test-mail-sent {})
    (shutdown-agents)))
