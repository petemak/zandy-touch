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

;; Secure and Path=/api: the cookie is never sent to page requests (which keeps them CDN-cacheable) and never over plain HTTP. Max-Age 0 clears it.
(defn- cookie-header [token max-age]
  (str "zt_session=" token "; HttpOnly; Secure; SameSite=Lax; Path=/api; Max-Age=" max-age))

(defn- session-body [ctx user session]
  {:user (logic/public-user user) :csrf-token (api/csrf-token ctx session)})


;; ---- POST /api/auth/sessions ----

(defn- login-limit [ctx] (get-in ctx [:config :limits :login]))

(defn login
  "Handler for POST /api/auth/sessions (public). Body: {:email :password}.
  Returns 201 {:user :csrfToken} and sets the zt_session cookie (HttpOnly, Secure, SameSite=Lax, Path=/api, 7 days).
  Throws invalidCredentials (401) for a wrong password, unknown email or inactive account (same response for all), rateLimited (429) after too many failures per IP or per email.
  Side effects: failed attempts count against the login limits; success creates a session and records the sign-in time."
  [{:keys [ctx parameters] :as req}]
  (let [{:keys [email password]} (:body parameters)
        email (str/lower-case email)
        conn (:conn ctx)
        now (util/now ctx)
        ip-key (str "login-ip:" (util/client-ip ctx req))
        email-key (str "login-email:" email)
        cfg (login-limit ctx)]
    ;; Limits are read first and only failed attempts are counted below, so a legitimate user is never locked out by successful sign-ins and a flood causes few writes. Both the client address and the email are limited.
    (rate-limit/check! conn ip-key cfg now)
    (rate-limit/check! conn email-key cfg now)
    (let [user (db/find-user-by-email conn email)
          ok? (if (and user (:active? user))
                (passwords/check-password? password (:password-hash user))
                ;; Unknown or inactive account: do a throwaway hash check so the response time is the same as for a wrong password.
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

(defn current-session
  "Handler for GET /api/auth/sessions/current (staff, allowed while a password change is pending). Returns 200 {:user :csrfToken} for the current session."
  [{:keys [ctx user session]}]
  {:status 200 :body (session-body ctx user session)})

(defn sign-out
  "Handler for DELETE /api/auth/sessions/current (optional session). Revokes the session if there is one and clears the cookie. Always 204, so signing out twice is fine."
  [{:keys [ctx session]}]
  (when session (db/revoke-session! (:conn ctx) (:eid session)))
  {:status 204 :headers {"Set-Cookie" (cookie-header "" 0)} :body nil})

;; ---- password resets (owner) ----

(defn request-reset
  "Handler for POST /api/auth/password-resets (public, rate limited). Body: {:email}.
  Always returns 202 {:requested true}, whatever the email. Only for an active owner account: stores a one-hour token (hash only) and emails the reset link directly (never through the outbox, never stored in plain text)."
  [{:keys [ctx parameters]}]
  (let [email (str/lower-case (get-in parameters [:body :email]))
        conn (:conn ctx)
        now (util/now ctx)
        user (db/find-user-by-email conn email)]
    ;; Only the owner can reset by email (staff get temporary passwords from the owner). Nothing differs in the response whether or not this branch ran.
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

(defn complete-reset
  "Handler for PUT /api/auth/password-resets/:token (public, rate limited). Body: {:new-password}. Returns 200 {:passwordChanged true}.
  Throws notFound (404) for an unknown, used or expired token and validationFailed (weakPassword) for a poor password.
  Side effects: stores the new hash, marks the token used, revokes all of the user's sessions."
  [{:keys [ctx parameters]}]
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

(defn change-password
  "Handler for PUT /api/admin/account/password (staff, allowed while a change is pending). Body: {:current-password :new-password}. Returns 200 {:passwordChanged true}.
  Throws invalidCredentials (401) when the current password is wrong, validationFailed (weakPassword). Side effects: new hash and clears must-change-password; revokes the user's other sessions but keeps the current one."
  [{:keys [ctx parameters user session]}]
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

(defn list-staff
  "Handler for GET /api/admin/staff (owner). Returns 200 {:items [staff views]}, the owner first."
  [{:keys [ctx]}]
  {:status 200 :body {:items (mapv logic/staff-view (db/list-users (:conn ctx)))}})

(defn create-staff
  "Handler for POST /api/admin/staff (owner, idempotent). Body: {:name :email :notify-on-new-order}. Returns 201 {:staff :temporaryPassword}; the staff member must change the password at first sign-in.
  Throws emailAlreadyInUse (409). The temporary password is shown once: the stored idempotent replay carries null instead. Side effect: one transaction with an Argon2id hash."
  [{:keys [ctx parameters]}]
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
      ;; One-time secrets are never replayed: the idempotency store keeps the body with a nil password.
      {:status 201 :body (body temp) :replay-body (body nil)})))

(defn- staff-target [ctx id-str]
  (let [id (v/parse-uuid-or-404 id-str)
        user (db/find-user-by-id (:conn ctx) id)]
    (or user (errors/fail! :notFound "Staff member not found."))))

(defn patch-staff
  "Handler for PATCH /api/admin/staff/:id (owner). Body: optional :name, :notify-on-new-order. Returns 200 {:staff}. Throws notFound, and cannotModifyOwner (409) when renaming the owner."
  [{:keys [ctx parameters]}]
  (let [user (staff-target ctx (get-in parameters [:path :id]))
        {:keys [name notify-on-new-order] :as body} (:body parameters)]
    (when (and (= :owner (:role user)) (contains? body :name))
      (errors/fail! :cannotModifyOwner "The owner account cannot be changed here."))
    (db/update-user! (:conn ctx) (:eid user)
                     (cond-> {} name (assoc :user/name name)
                             (contains? body :notify-on-new-order) (assoc :user/notify-on-new-order? notify-on-new-order)))
    {:status 200 :body {:staff (logic/staff-view (db/find-user-by-id (:conn ctx) (:id user)))}}))

(defn deactivate-staff
  "Handler for DELETE /api/admin/staff/:id (owner). Deactivates the account (it is kept) and revokes its sessions. Returns 204. Throws notFound or cannotModifyOwner (409)."
  [{:keys [ctx parameters]}]
  (let [user (staff-target ctx (get-in parameters [:path :id]))]
    (when (= :owner (:role user))
      (errors/fail! :cannotModifyOwner "The owner account cannot be deactivated."))
    (db/update-user! (:conn ctx) (:eid user) {:user/active? false})
    (db/revoke-user-sessions! (:conn ctx) (:eid user) nil)
    {:status 204 :body nil}))

(defn reset-staff-password
  "Handler for POST /api/admin/staff/:id/password-resets (owner, idempotent). Sets a new temporary password, revokes the staff member's sessions and returns 201 {:temporaryPassword} (shown once; a replay carries null). Throws notFound or cannotModifyOwner (409, the owner uses the forgot-password link)."
  [{:keys [ctx parameters]}]
  (let [user (staff-target ctx (get-in parameters [:path :id]))
        temp (new-temp-password)]
    (when (= :owner (:role user))
      (errors/fail! :cannotModifyOwner "Use the forgot-password link for the owner account."))
    (db/set-temporary-password! (:conn ctx) (:eid user) (passwords/hash-password temp))
    (db/revoke-user-sessions! (:conn ctx) (:eid user) nil)
    (log/info :staff-password-reset {})
    ;; One-time secret: a replay of this request gets null instead of the password.
    {:status 201 :body {:temporary-password temp} :replay-body {:temporary-password nil}}))

(def routes
  "Route data for the accounts slice. Login is public; the account endpoints allow a user who must still change the password; staff management is :owner only."
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
