(ns zandy.platform.auth-interceptors
  "Enforces route :access (:public, :staff, :owner), mustChangePassword and CSRF from route data."
  (:require [zandy.features.accounts.api :as accounts]
            [zandy.platform.errors :as errors]
            [zandy.platform.util :as util]))

(def session-cookie "zt_session")

(defn cookie-value [req cookie-name]
  (some->> (get-in req [:headers "cookie"])
           (re-seq #"([^=;\s]+)=([^;]*)")
           (some (fn [[_ k v]] (when (= k cookie-name) v)))))

(def ^:private mutating #{:post :put :patch :delete})

(defn wrap-auth
  "Attaches :session and :user when the cookie is valid, then enforces the route's access level."
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
          (and (contains? mutating (:request-method req))
               (not (accounts/csrf-valid? ctx (:session auth) (get-in req [:headers "x-csrf-token"]))))
          (errors/fail! :csrfInvalid "Your session check failed. Please reload the page and try again.")
          :else (handler req))))))

(def client-ip util/client-ip)
