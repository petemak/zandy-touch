(ns zandy.features.engagement.routes
  "Newsletter subscriptions, unsubscribes, contact messages and the staff views of them
  (contract sections 5 and 9)."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [zandy.features.engagement.db :as db]
            [zandy.features.engagement.logic :as logic]
            [zandy.features.engagement.schema :as schema]
            [zandy.features.notifications.api :as notifications]
            [zandy.platform.util :as util]
            [zandy.platform.validation :as v]))

(defn- unsub-token [ctx id]
  (let [secret (get-in ctx [:config :auth :session-hmac-secret])
        sig (subs (util/hmac-hex secret (str "unsubscribe:" id)) 0 32)]
    (str id "." sig)))

(defn- token->id [ctx token]
  (let [[id sig] (str/split (str token) #"\." 2)
        uuid (some-> id parse-uuid)]
    (when (and uuid sig (util/constant-time-equals? sig (second (str/split (unsub-token ctx uuid) #"\." 2))))
      uuid)))

(defn subscribe [{:keys [ctx parameters]}]
  (let [{:keys [email phone source website]} (:body parameters)
        conn (:conn ctx)]
    (if (some? website)
      {:status 201 :body {:subscribed true}}
      (let [d (db/current-db conn)
            phone (some-> phone v/normalize-phone)
            email (some-> email str/lower-case)
            existing (or (and email (db/subscriber-by-email d email)) (and phone (db/subscriber-by-phone d phone)))]
        (if existing
          (do (when-not (:active? existing) (db/reactivate! conn (:eid existing)))
              {:status 200 :body {:subscribed true}})
          (try
            (db/subscribe! conn {:email email :phone phone :source (keyword source)
                                 :consent-text-version logic/consent-text-version :now (util/now ctx)})
            {:status 201 :body {:subscribed true}}
            (catch Throwable _
              ;; email and phone belong to two different existing records: treat as already subscribed
              {:status 200 :body {:subscribed true}})))))))

(defn unsubscribe [{:keys [ctx parameters]}]
  (when-let [id (token->id ctx (get-in parameters [:body :token]))]
    (when-let [s (db/subscriber-by-id (db/current-db (:conn ctx)) id)]
      (db/deactivate! (:conn ctx) (:eid s))))
  {:status 200 :body {:unsubscribed true}})

(defn contact-message [{:keys [ctx parameters]}]
  (let [{:keys [name phone email message website]} (:body parameters)]
    (if (some? website)
      {:status 201 :body {:received true}}
      (let [now (util/now ctx)
            phone (some-> phone v/normalize-phone)
            mail (notifications/contact-message-mail {:name name :phone phone :email email :message message})
            owner (get-in ctx [:config :auth :owner-email])
            outbox (notifications/enqueue-tx
                    [(notifications/outbox-entity (random-uuid) :contactMessage owner (:subject mail) (:text mail))]
                    now)]
        (db/save-contact-message! (:conn ctx) {:id (random-uuid) :name name :phone phone :email email :message message :now now} outbox)
        {:status 201 :body {:received true}}))))

(defn admin-contact-messages [{:keys [ctx parameters]}]
  (let [q (:query parameters)
        page (util/paginate (db/contact-messages (db/current-db (:conn ctx))) (:page q) (or (:page-size q) 25))]
    {:status 200 :body page}))

(defn admin-subscribers [{:keys [ctx parameters]}]
  (let [q (:query parameters)
        base (get-in ctx [:config :site :base-url])
        rows (mapv #(assoc % :unsubscribe-url (logic/unsubscribe-url base (unsub-token ctx (:id %))))
                   (db/all-subscribers (db/current-db (:conn ctx))))]
    (if (= "csv" (:format q))
      {:status 200
       :headers {"Content-Type" "text/csv; charset=utf-8"
                 "Content-Disposition" "attachment; filename=\"subscribers.csv\""}
       :body (logic/subscribers-csv (filter :active? rows))}
      (let [page (util/paginate rows (:page q) (or (:page-size q) 50))]
        {:status 200
         :body (update page :items (fn [items] (mapv #(-> % (select-keys [:id :email :phone :subscribed-at :source :active? :unsubscribe-url]) (set/rename-keys {:active? :active})) items)))}))))

(def routes
  [{:method :post :path "/api/subscriptions" :name :engagement/subscribe :access :public :idempotent? true
    :rate-limit :subscriptions :parameters {:body schema/Subscription} :handler subscribe}
   {:method :post :path "/api/unsubscribes" :name :engagement/unsubscribe :access :public
    :rate-limit :unsubscribe :parameters {:body schema/Unsubscribe} :handler unsubscribe}
   {:method :post :path "/api/contact-messages" :name :engagement/contact :access :public :idempotent? true
    :rate-limit :contact :parameters {:body schema/ContactMessage} :handler contact-message}
   {:method :get :path "/api/admin/contact-messages" :name :engagement/admin-contact-messages :access :staff
    :parameters {:query schema/ContactMessagesQuery} :handler admin-contact-messages}
   {:method :get :path "/api/admin/subscribers" :name :engagement/admin-subscribers :access :staff
    :parameters {:query schema/SubscribersQuery} :handler admin-subscribers}])
