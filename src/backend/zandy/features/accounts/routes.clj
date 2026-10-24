(ns zandy.features.accounts.routes
  "Staff authentication, account and staff management endpoints (contract sections 6 and 10)."
  (:require [clojure.string :as str]
            [zandy.features.accounts.api :as api]
            [zandy.features.accounts.db :as db]
            [zandy.features.accounts.logic :as logic]
            [zandy.features.accounts.passwords :as passwords]
            [zandy.features.accounts.schema :as schema]
            [zandy.features.notifications.api :as notifications]
            [zandy.platform.errors :as errors]
            [zandy.platform.log :as log]
            [zandy.platform.rate-limit :as rate-limit]
            [zandy.platform.util :as util]
            [zandy.platform.validation :as v])
  (:import (java.security SecureRandom)
           (java.util Date)))

(def ^:private secure-random (SecureRandom.))

(defn- cookie-header [token max-age]
  (str "zt_session=" token "; HttpOnly; Secure; SameSite=Lax; Path=/api; Max-Age=" max-age))

(defn- session-body [ctx user session]
  {:user (logic/public-user user) :csrf-token (api/csrf-token ctx session)})


;; ---- POST /api/auth/sessions ----

(defn- login-limit [ctx] (get-in ctx [:config :limits :login]))

(defn login [{:keys [ctx parameters] :as req}]
  (let [{:keys [email password]} (:body parameters)
        email (str/lower-case email)
        conn (:conn ctx)
        now (util/now ctx)
        ip-key (str "login-ip:" (util/client-ip ctx req))
        email-key (str "login-email:" email)
        cfg (login-limit ctx)]
    (rate-limit/check! conn ip-key cfg now)
    (rate-limit/check! conn email-key cfg now)
    (let [user (db/find-user-by-email conn email)
          ok? (if (and user (:active? user))
                (passwords/check-password? password (:password-hash user))
                (passwords/check-dummy! password))]
      (if-not ok?
        (do (rate-limit/hit! conn ip-key cfg now)
            (rate-limit/hit! conn email-key cfg now)
            (errors/fail! :invalidCredentials "Email or password is incorrect."))
        (let [token (util/random-token)
              id-hash (util/sha256-hex token)
              expires (logic/session-expiry now now)]
          (db/create-session! conn id-hash (:eid user) now expires)
          (db/record-sign-in! conn (:eid user) now)
          (let [session {:id-hash id-hash}]
            {:status 201
             :headers {"Set-Cookie" (cookie-header token (quot logic/session-absolute-ms 1000))}
             :body (session-body ctx user session)}))))))

(defn current-session [{:keys [ctx user session]}]
  {:status 200 :body (session-body ctx user session)})

(defn sign-out [{:keys [ctx session]}]
  (when session (db/revoke-session! (:conn ctx) (:eid session)))
  {:status 204 :headers {"Set-Cookie" (cookie-header "" 0)} :body nil})

;; ---- password resets (owner) ----

(defn request-reset [{:keys [ctx parameters]}]
  (let [email (str/lower-case (get-in parameters [:body :email]))
        conn (:conn ctx)
        now (util/now ctx)
        user (db/find-user-by-email conn email)]
    (when (and user (:active? user) (= :owner (:role user)))
      (let [token (util/random-token)]
        (db/create-reset-token! conn (util/sha256-hex token) (:eid user) (util/plus-ms now logic/reset-token-ms))
        (let [url (str (get-in ctx [:config :site :base-url]) "/admin/reset-password?token=" token)
              mail (notifications/password-reset-mail {:reset-url url})]
          (notifications/send-now! ctx (assoc mail :to (:email user))))))
    {:status 202 :body {:requested true}}))

(defn- check-new-password! [password field]
  (when-let [problem (logic/password-problem password)]
    (errors/validation-failed [(errors/field-error field "weakPassword" problem)])))

(defn complete-reset [{:keys [ctx parameters]}]
  (let [conn (:conn ctx)
        now (util/now ctx)
        token (get-in parameters [:path :token])
        rec (db/reset-token-by-hash conn (util/sha256-hex token))]
    (when (or (nil? rec) (:used-at rec) (>= (.getTime ^Date now) (.getTime ^Date (:expires-at rec))))
      (errors/fail! :notFound "This reset link is not valid or has expired."))
    (let [password (get-in parameters [:body :new-password])]
      (check-new-password! password "newPassword")
      (db/set-password! conn (:user-eid rec) (passwords/hash-password password))
      (db/use-reset-token! conn (:eid rec) now)
      (db/revoke-user-sessions! conn (:user-eid rec) nil)
      {:status 200 :body {:password-changed true}})))

(defn change-password [{:keys [ctx parameters user session]}]
  (let [{:keys [current-password new-password]} (:body parameters)]
    (when-not (passwords/check-password? current-password (:password-hash user))
      (errors/fail! :invalidCredentials "The current password is incorrect."))
    (check-new-password! new-password "newPassword")
    (db/set-password! (:conn ctx) (:eid user) (passwords/hash-password new-password))
    (db/revoke-user-sessions! (:conn ctx) (:eid user) (:eid session))
    {:status 200 :body {:password-changed true}}))

;; ---- owner: staff management ----

(defn- new-temp-password []
  (logic/temporary-password #(.nextInt secure-random (int %))))

(defn list-staff [{:keys [ctx]}]
  {:status 200 :body {:items (mapv logic/staff-view (db/list-users (:conn ctx)))}})

(defn create-staff [{:keys [ctx parameters]}]
  (let [{:keys [name email notify-on-new-order]} (:body parameters)
        conn (:conn ctx)
        email (str/lower-case email)
        temp (new-temp-password)]
    (when (db/find-user-by-email conn email)
      (errors/fail! :emailAlreadyInUse "A staff member with this email already exists."))
    (db/create-user! conn {:id (random-uuid) :name name :email email :role :support
                           :password-hash (passwords/hash-password temp) :must-change-password? true
                           :notify-on-new-order? (boolean notify-on-new-order) :created-at (util/now ctx)})
    (let [staff (db/find-user-by-email conn email)
          body (fn [pw] {:staff (assoc (select-keys (logic/staff-view staff) [:id :name :email :role :active :notify-on-new-order])
                                       :must-change-password true)
                         :temporary-password pw})]
      {:status 201 :body (body temp) :replay-body (body nil)})))

(defn- staff-target [ctx id-str]
  (let [id (v/parse-uuid-or-404 id-str)
        user (db/find-user-by-id (:conn ctx) id)]
    (or user (errors/fail! :notFound "Staff member not found."))))

(defn patch-staff [{:keys [ctx parameters]}]
  (let [user (staff-target ctx (get-in parameters [:path :id]))
        {:keys [name notify-on-new-order] :as body} (:body parameters)]
    (when (and (= :owner (:role user)) (contains? body :name))
      (errors/fail! :cannotModifyOwner "The owner account cannot be changed here."))
    (db/update-user! (:conn ctx) (:eid user)
                     (cond-> {} name (assoc :user/name name)
                             (contains? body :notify-on-new-order) (assoc :user/notify-on-new-order? notify-on-new-order)))
    {:status 200 :body {:staff (logic/staff-view (db/find-user-by-id (:conn ctx) (:id user)))}}))

(defn deactivate-staff [{:keys [ctx parameters]}]
  (let [user (staff-target ctx (get-in parameters [:path :id]))]
    (when (= :owner (:role user))
      (errors/fail! :cannotModifyOwner "The owner account cannot be deactivated."))
    (db/update-user! (:conn ctx) (:eid user) {:user/active? false})
    (db/revoke-user-sessions! (:conn ctx) (:eid user) nil)
    {:status 204 :body nil}))

(defn reset-staff-password [{:keys [ctx parameters]}]
  (let [user (staff-target ctx (get-in parameters [:path :id]))
        temp (new-temp-password)]
    (when (= :owner (:role user))
      (errors/fail! :cannotModifyOwner "Use the forgot-password link for the owner account."))
    (db/set-temporary-password! (:conn ctx) (:eid user) (passwords/hash-password temp))
    (db/revoke-user-sessions! (:conn ctx) (:eid user) nil)
    (log/info :staff-password-reset {})
    {:status 201 :body {:temporary-password temp} :replay-body {:temporary-password nil}}))

(def routes
  [{:method :post :path "/api/auth/sessions" :name :accounts/login :access :public
    :parameters {:body schema/Login} :handler login}
   {:method :get :path "/api/auth/sessions/current" :name :accounts/current :access :staff
    :allow-must-change? true :handler current-session}
   {:method :delete :path "/api/auth/sessions/current" :name :accounts/sign-out :access :staff
    :optional-session? true :allow-must-change? true :handler sign-out}
   {:method :post :path "/api/auth/password-resets" :name :accounts/request-reset :access :public
    :rate-limit :reset-request :parameters {:body schema/ResetRequest} :handler request-reset}
   {:method :put :path "/api/auth/password-resets/:token" :name :accounts/complete-reset :access :public
    :rate-limit :reset-request :parameters {:body schema/NewPassword} :handler complete-reset}
   {:method :put :path "/api/admin/account/password" :name :accounts/change-password :access :staff
    :allow-must-change? true :parameters {:body schema/ChangePassword} :handler change-password}
   {:method :get :path "/api/admin/staff" :name :accounts/list-staff :access :owner :handler list-staff}
   {:method :post :path "/api/admin/staff" :name :accounts/create-staff :access :owner :idempotent? true
    :parameters {:body schema/CreateStaff} :handler create-staff}
   {:method :patch :path "/api/admin/staff/:id" :name :accounts/patch-staff :access :owner
    :parameters {:body schema/PatchStaff} :handler patch-staff}
   {:method :delete :path "/api/admin/staff/:id" :name :accounts/deactivate-staff :access :owner
    :handler deactivate-staff}
   {:method :post :path "/api/admin/staff/:id/password-resets" :name :accounts/reset-staff-password
    :access :owner :idempotent? true :no-input? true :handler reset-staff-password}])
