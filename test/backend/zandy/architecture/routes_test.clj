(ns zandy.architecture.routes-test
  "Fitness checks N1 (boundary validation) and N2 (server-side authorization) over the route table."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [zandy.architecture.contract :as contract]
            [zandy.platform.http :as http]
            [zandy.test-helpers :as t]))

(defn- concrete-path [path]
  (str/replace path #":[A-Za-z-]+" (str (random-uuid))))

(deftest every-route-declares-access
  (is (every? #(#{:public :staff :owner} (:access %)) (http/route-table))))

(deftest every-input-route-has-a-schema
  (doseq [r (http/route-table)
          :when (and (str/starts-with? (:path r) "/api") (#{:post :put :patch} (:method r)))]
    (is (or (:no-input? r) (:multipart? r) (get-in r [:parameters :body]))
        (str (:method r) " " (:path r) " has no body schema"))))

(deftest public-routes-equal-the-contract-allowlist
  (let [declared (->> (http/route-table) (filter #(= :public (:access %))) (map (juxt :method #(contract/route-norm (:path %)))) set)
        from-contract (->> (contract/endpoints) (filter #(= :public (:access %))) (map (juxt :method :norm)) set)]
    (is (= from-contract declared))))

(deftest access-levels-match-the-contract
  (let [declared (into {} (map (juxt (juxt :method #(contract/route-norm (:path %))) :access)) (http/route-table))]
    (doseq [{:keys [method norm access]} (contract/endpoints)]
      (is (= access (get declared [method norm])) (str method " " norm)))))

(deftest authorization-is-enforced-on-every-protected-route
  (let [ctx (t/make-ctx) app (t/app ctx)
        owner (t/setup-owner! ctx app)
        _ (t/create-user! ctx {:name "Grace" :email "grace@example.com" :role :support})
        support (t/login! app "grace@example.com" t/owner-password)
        routes (filter #(not= :public (:access %)) (http/route-table))]
    (is (seq routes))
    (doseq [r routes
            :let [path (concrete-path (:path r))
                  label (str (name (:method r)) " " (:path r))
                  idem {"Idempotency-Key" (str (random-uuid))}]]
      (testing label
        (when-not (:optional-session? r)
        (is (= 401 (:status (t/call app (:method r) path :headers idem
                                    :body (when (#{:post :put :patch} (:method r)) {}))))
            (str label " without a cookie"))))
      (when (= :owner (:access r))
        (is (= 403 (:status (t/call app (:method r) path :headers (merge idem {"Cookie" (:cookie support) "X-CSRF-Token" (:csrf support)})
                                    :body (when (#{:post :put :patch} (:method r)) {}))))
            (str label " as support")))
      (when (and (#{:post :put :patch :delete} (:method r)) (not (:optional-session? r)))
        (let [resp (t/call app (:method r) path :headers (merge idem {"Cookie" (:cookie owner)})
                           :body (when (#{:post :put :patch} (:method r)) {}))]
          (is (= "csrfInvalid" (t/error-code resp)) (str label " without a CSRF token")))))))

(deftest invalid-input-never-causes-a-server-error
  (let [ctx (t/make-ctx) app (t/app ctx)
        owner (t/setup-owner! ctx app)
        routes (filter #(and (str/starts-with? (:path %) "/api") (#{:post :put :patch} (:method %))) (http/route-table))]
    (doseq [r routes
            :let [path (concrete-path (:path r))
                  label (str (name (:method r)) " " (:path r))]
            :when (not (:multipart? r))]
      (let [resp (if (= :public (:access r))
                   (t/call app (:method r) path :body {:unexpected-key-xyz 1} :headers {"Idempotency-Key" (str (random-uuid))})
                   (t/staff app owner (:method r) path :body {:unexpected-key-xyz 1}))]
        (is (< (:status resp) 500) (str label " answered " (:status resp)))
        (when (get-in r [:parameters :body])
          (is (#{422 400} (:status resp)) (str label " answered " (:status resp))))))
    (testing "malformed JSON"
      (let [resp (t/call app :post "/api/orders" :raw-body "{not json" :headers {"Content-Type" "application/json" "Idempotency-Key" (str (random-uuid))})]
        (is (= "malformedRequest" (t/error-code resp))))
      (let [resp (t/call app :post "/api/cart-validations" :raw-body "{}" :headers {"Content-Type" "text/plain"})]
        (is (= "malformedRequest" (t/error-code resp)))))))
