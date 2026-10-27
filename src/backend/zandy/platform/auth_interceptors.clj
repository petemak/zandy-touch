(ns zandy.platform.auth-interceptors
  "Enforces route :access (:public, :staff, :owner), mustChangePassword and CSRF from route data."
  (:require [zandy.features.accounts.api :as accounts]
            [zandy.platform.errors :as errors]
            [zandy.platform.util :as util]))

(def session-cookie
  "Name of the staff session cookie (HttpOnly, Path=/api; only its SHA-256 is stored)."
  "zt_session")

(defn cookie-value
  "Value of the cookie `cookie-name` in the Ring request `req` (reads the \"cookie\" header), or nil."
  [req cookie-name]
  (some->> (get-in req [:headers "cookie"])
           (re-seq #"([^=;\s]+)=([^;]*)")
           (some (fn [[_ k v]] (when (= k cookie-name) v)))))

(def ^:private mutating #{:post :put :patch :delete})

(defn wrap-auth
  "Ring middleware enforcing a route's access rules.
  Arguments: handler; ctx (the system context map); route data keys :access (:public, :staff or :owner),
  :optional-session? (let anonymous requests through, used by login and logout) and
  :allow-must-change? (let a user who must change the password through).
  Reads the zt_session cookie and, when valid, adds :session and :user to the request (also on :public routes).
  Throws (as API errors): unauthenticated (401), forbidden (403, owner-only route), passwordChangeRequired (403),
  csrfInvalid (403, mutating methods without a valid X-CSRF-Token).
  Side effect: accounts/authenticate may refresh the session's last-seen-at (at most every 5 minutes)."
  [handler ctx {:keys [access optional-session? allow-must-change?]}]
  (fn [req]
    (let [token (cookie-value req session-cookie)
          auth (when token (accounts/authenticate ctx token))
          req (cond-> req auth (assoc :session (:session auth) :user (:user auth)))]
      (if (= access :public)
        (handler req)
        (cond
          (and (nil? auth) optional-session?) (handler req)
          (nil? auth) (errors/fail! :unauthenticated "Please sign in.")
          (and (= access :owner) (not= :owner (get-in auth [:user :role])))
          (errors/fail! :forbidden "You do not have access to this.")
          (and (get-in auth [:user :must-change-password?]) (not allow-must-change?))
          (errors/fail! :passwordChangeRequired "Please set a new password before continuing.")
          ;; CSRF is checked last, and only for mutating methods, so a GET never needs the token.
          ;; The order of the checks decides which error code wins: 401, 403 forbidden, 403 passwordChangeRequired, 403 csrfInvalid.
          (and (contains? mutating (:request-method req))
               (not (accounts/csrf-valid? ctx (:session auth) (get-in req [:headers "x-csrf-token"]))))
          (errors/fail! :csrfInvalid "Your session check failed. Please reload the page and try again.")
          :else (handler req))))))

(def client-ip
  "Alias of zandy.platform.util/client-ip: the caller address used as the rate-limit identity."
  util/client-ip)
