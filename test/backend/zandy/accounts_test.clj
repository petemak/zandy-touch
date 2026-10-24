(ns zandy.accounts-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [zandy.test-helpers :as t]))

(deftest login-session-and-logout
  (let [ctx (t/make-ctx) app (t/app ctx)
        _ (t/create-user! ctx {:name "Sandra" :email "owner@example.com" :role :owner})]
    (testing "wrong password and unknown email give the same answer"
      (let [a (t/call app :post "/api/auth/sessions" :body {:email "owner@example.com" :password "wrong-password"})
            b (t/call app :post "/api/auth/sessions" :body {:email "nobody@example.com" :password "wrong-password"})]
        (is (= 401 (:status a) (:status b)))
        (is (= (get-in a [:body :error]) (get-in b [:body :error])))
        (is (= "invalidCredentials" (t/error-code a)))))
    (let [r (t/call app :post "/api/auth/sessions" :body {:email "Owner@Example.com" :password t/owner-password})
          cookie (get-in r [:headers "Set-Cookie"])]
      (is (= 201 (:status r)))
      (is (= "owner" (get-in r [:body :user :role])))
      (is (false? (get-in r [:body :user :must-change-password])))
      (testing "cookie attributes"
        (is (str/includes? cookie "HttpOnly"))
        (is (str/includes? cookie "Secure"))
        (is (str/includes? cookie "SameSite=Lax"))
        (is (str/includes? cookie "Path=/api"))
        (is (not (str/includes? cookie "Domain"))))
      (let [session {:cookie (first (str/split cookie #";")) :csrf (get-in r [:body :csrf-token])}]
        (testing "current session"
          (let [c (t/call app :get "/api/auth/sessions/current" :headers {"Cookie" (:cookie session)})]
            (is (= 200 (:status c)))
            (is (= (:csrf session) (get-in c [:body :csrf-token])))))
        (testing "no session"
          (is (= "unauthenticated" (t/error-code (t/call app :get "/api/auth/sessions/current")))))
        (testing "csrf required on staff mutations"
          (is (= "csrfInvalid" (t/error-code (t/call app :put "/api/admin/account/password" :body {:current-password "a" :new-password "b"}
                                                     :headers {"Cookie" (:cookie session)}))))
          (is (= "csrfInvalid" (t/error-code (t/call app :put "/api/admin/account/password" :body {:current-password "a" :new-password "b"}
                                                     :headers {"Cookie" (:cookie session) "X-CSRF-Token" "nope"})))))
        (testing "logout"
          (is (= 204 (:status (t/staff app session :delete "/api/auth/sessions/current" :idem false))))
          (is (= "unauthenticated" (t/error-code (t/call app :get "/api/auth/sessions/current" :headers {"Cookie" (:cookie session)}))))
          (is (= 204 (:status (t/call app :delete "/api/auth/sessions/current")))))))))

(deftest login-lockout
  (let [ctx (t/make-ctx) app (t/app ctx)
        _ (t/create-user! ctx {:name "Sandra" :email "owner@example.com" :role :owner})
        try-login (fn [pw] (t/call app :post "/api/auth/sessions" :body {:email "owner@example.com" :password pw}))]
    (dotimes [_ 5] (is (= 401 (:status (try-login "wrong-password")))))
    (let [r (try-login t/owner-password)]
      (is (= 429 (:status r)))
      (is (= "rateLimited" (t/error-code r)))
      (is (some? (get-in r [:headers "Retry-After"]))))
    (t/advance-clock! ctx (* 16 60 1000))
    (is (= 201 (:status (try-login t/owner-password))))))

(deftest session-expiry
  (let [ctx (t/make-ctx) app (t/app ctx)
        _ (t/create-user! ctx {:name "Sandra" :email "owner@example.com" :role :owner})
        s (t/login! app "owner@example.com" t/owner-password)]
    (t/advance-clock! ctx (* 11 60 60 1000))
    (is (= 200 (:status (t/call app :get "/api/auth/sessions/current" :headers {"Cookie" (:cookie s)}))))
    (t/advance-clock! ctx (* 11 60 60 1000))
    (is (= 200 (:status (t/call app :get "/api/auth/sessions/current" :headers {"Cookie" (:cookie s)})))
        "sliding: still valid 22 hours in because it was used 11 hours ago")
    (t/advance-clock! ctx (* 13 60 60 1000))
    (is (= 401 (:status (t/call app :get "/api/auth/sessions/current" :headers {"Cookie" (:cookie s)}))))))

(deftest roles-and-must-change-password
  (let [ctx (t/make-ctx) app (t/app ctx)
        owner (t/setup-owner! ctx app)
        created (t/staff app owner :post "/api/admin/staff" :body {:name "Grace Atim" :email "grace@example.com" :notify-on-new-order true})
        temp (get-in created [:body :temporary-password])]
    (is (= 201 (:status created)))
    (is (string? temp))
    (is (true? (get-in created [:body :staff :must-change-password])))
    (testing "duplicate email"
      (is (= "emailAlreadyInUse" (t/error-code (t/staff app owner :post "/api/admin/staff" :body {:name "Grace Two" :email "GRACE@example.com"})))))
    (testing "temporary password is never replayed"
      (let [k (str (random-uuid))
            a (t/staff app owner :post "/api/admin/staff" :body {:name "Hope Okello" :email "hope@example.com"} :idem k)
            b (t/staff app owner :post "/api/admin/staff" :body {:name "Hope Okello" :email "hope@example.com"} :idem k)]
        (is (string? (get-in a [:body :temporary-password])))
        (is (= "true" (get-in b [:headers "Idempotent-Replayed"])))
        (is (nil? (get-in b [:body :temporary-password])))
        (is (= (get-in a [:body :staff :id]) (get-in b [:body :staff :id])))))
    (let [grace (t/login! app "grace@example.com" temp)]
      (is (true? (get-in grace [:user :must-change-password])))
      (testing "everything but the password change is blocked"
        (is (= "passwordChangeRequired" (t/error-code (t/staff app grace :get "/api/admin/orders"))))
        (is (= 200 (:status (t/call app :get "/api/auth/sessions/current" :headers {"Cookie" (:cookie grace)})))))
      (testing "password change"
        (is (= 422 (:status (t/staff app grace :put "/api/admin/account/password" :idem false
                                     :body {:current-password temp :new-password "short"}))))
        (is (= 422 (:status (t/staff app grace :put "/api/admin/account/password" :idem false
                                     :body {:current-password temp :new-password "password123"}))))
        (is (= "invalidCredentials" (t/error-code (t/staff app grace :put "/api/admin/account/password" :idem false
                                                           :body {:current-password "wrong-wrong-1" :new-password "a-new-password-1"}))))
        (is (= 200 (:status (t/staff app grace :put "/api/admin/account/password" :idem false
                                     :body {:current-password temp :new-password "a-new-password-1"}))))
        (is (= 200 (:status (t/staff app grace :get "/api/admin/orders" :idem false)))))
      (testing "support cannot use owner endpoints"
        (is (= "forbidden" (t/error-code (t/staff app grace :get "/api/admin/staff" :idem false))))
        (is (= "forbidden" (t/error-code (t/staff app grace :post "/api/admin/staff" :body {:name "X Y" :email "x@example.com"}))))))
    (testing "owner cannot be modified or deactivated"
      (let [owner-id (get-in owner [:user :id])]
        (is (= "cannotModifyOwner" (t/error-code (t/staff app owner :delete (str "/api/admin/staff/" owner-id) :idem false))))
        (is (= "cannotModifyOwner" (t/error-code (t/staff app owner :patch (str "/api/admin/staff/" owner-id) :body {:name "New Name"}))))
        (is (= 200 (:status (t/staff app owner :patch (str "/api/admin/staff/" owner-id) :body {:notify-on-new-order false}))))
        (is (= "cannotModifyOwner" (t/error-code (t/staff app owner :post (str "/api/admin/staff/" owner-id "/password-resets")))))))
    (testing "deactivation ends sessions at once"
      (let [staff-id (get-in created [:body :staff :id])
            grace (t/login! app "grace@example.com" "a-new-password-1")]
        (is (= 204 (:status (t/staff app owner :delete (str "/api/admin/staff/" staff-id) :idem false))))
        (is (= 204 (:status (t/staff app owner :delete (str "/api/admin/staff/" staff-id) :idem false))))
        (is (= "unauthenticated" (t/error-code (t/call app :get "/api/auth/sessions/current" :headers {"Cookie" (:cookie grace)}))))
        (is (= "invalidCredentials" (t/error-code (t/call app :post "/api/auth/sessions" :body {:email "grace@example.com" :password "a-new-password-1"}))))))
    (testing "owner resets a support password"
      (let [h (t/staff app owner :post "/api/admin/staff" :body {:name "Ivy Auma" :email "ivy@example.com"})
            r (t/staff app owner :post (str "/api/admin/staff/" (get-in h [:body :staff :id]) "/password-resets"))]
        (is (= 201 (:status r)))
        (is (string? (get-in r [:body :temporary-password])))
        (is (= 201 (:status (t/call app :post "/api/auth/sessions" :body {:email "ivy@example.com" :password (get-in r [:body :temporary-password])}))))))))

(deftest owner-password-reset-by-email
  (let [ctx (t/make-ctx) app (t/app ctx)
        _ (t/create-user! ctx {:name "Sandra" :email "owner@example.com" :role :owner})
        _ (t/create-user! ctx {:name "Grace" :email "grace@example.com" :role :support})]
    (testing "always 202; only the owner gets a mail"
      (is (= 202 (:status (t/call app :post "/api/auth/password-resets" :body {:email "grace@example.com"}))))
      (is (= 202 (:status (t/call app :post "/api/auth/password-resets" :body {:email "nobody@example.com"}))))
      (is (empty? (t/sent-mail ctx)))
      (is (= 202 (:status (t/call app :post "/api/auth/password-resets" :body {:email "owner@example.com"}))))
      (is (= 1 (count (t/sent-mail ctx)))))
    (let [mail (first (t/sent-mail ctx))
          token (second (re-find #"token=([\w-]+)" (:text mail)))
          old (t/login! app "owner@example.com" t/owner-password)]
      (is (= "owner@example.com" (:to mail)))
      (testing "token is single use and ends sessions"
        (is (= 422 (:status (t/call app :put (str "/api/auth/password-resets/" token) :body {:new-password "short"}))))
        (is (= 200 (:status (t/call app :put (str "/api/auth/password-resets/" token) :body {:new-password "a-brand-new-pass-1"}))))
        (is (= "notFound" (t/error-code (t/call app :put (str "/api/auth/password-resets/" token) :body {:new-password "another-pass-123"}))))
        (is (= 401 (:status (t/call app :get "/api/auth/sessions/current" :headers {"Cookie" (:cookie old)}))))
        (is (= 201 (:status (t/call app :post "/api/auth/sessions" :body {:email "owner@example.com" :password "a-brand-new-pass-1"}))))))
    (testing "tokens expire after 60 minutes"
      (t/call app :post "/api/auth/password-resets" :body {:email "owner@example.com"})
      (let [token (second (re-find #"token=([\w-]+)" (:text (last (t/sent-mail ctx)))))]
        (t/advance-clock! ctx (* 61 60 1000))
        (is (= "notFound" (t/error-code (t/call app :put (str "/api/auth/password-resets/" token) :body {:new-password "yet-another-pass-1"}))))))
    (is (= "notFound" (t/error-code (t/call app :put "/api/auth/password-resets/unknown" :body {:new-password "yet-another-pass-1"}))))))
