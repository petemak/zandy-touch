(ns zandy.frontend.state-test
  (:require [cljs.test :refer [deftest is testing]]
            [zandy.frontend.admin.state]
            [zandy.frontend.state :as s]
            [zandy.shared.cart :as cart]
            [zandy.shared.routes :as routes]))

(def serum {:id "p1" :slug "serum" :name "Serum" :price 85000 :image nil :maxQuantity 10})

(defn st [& {:as extra}]
  (merge (s/initial-state) {:route (routes/match-url "/") :page {:status :ok}} extra))

(defn effects-named [result fx-name] (filter #(= fx-name (first %)) (:fx result)))

(deftest add-to-cart-persists-and-announces
  (let [r (s/handle (st) [:cart/add serum 2])]
    (is (= 2 (cart/count-items (get-in r [:state :cart]))))
    (is (seq (effects-named r :fx/save-cart)))
    (is (get-in r [:state :ui :product :added?]))
    (is (re-find #"added to your cart" (get-in r [:state :ui :announcement])))))

(deftest changing-the-cart-renews-the-order-key
  (let [r (s/handle (st :order-key "k1") [:cart/add serum 1])]
    (is (nil? (get-in r [:state :order-key])))))

(deftest cart-validation-ignores-stale-results
  (let [state (st :cart (cart/add-item cart/empty-cart serum 1 10))
        stale (s/handle state [:cart/validated "other-signature" {:status 200 :body {:items [] :hasProblems false}}])]
    (is (nil? (get-in stale [:state :cart-check :result])))))

(deftest navigation-loads-page-data
  (let [r (s/handle (st) [:nav/go "/shop?q=serum"])]
    (is (= :loading (get-in r [:state :page :status])))
    (is (= "/shop?q=serum" (get-in r [:state :pending-url])))
    (is (seq (effects-named r :fx/page-load)))
    (is (seq (effects-named r :fx/push-url))))
  (testing "shell pages need no data"
    (let [r (s/handle (st) [:nav/go "/cart"])]
      (is (= :ok (get-in r [:state :page :status])))
      (is (seq (effects-named r :fx/dispatch)))))
  (testing "unknown path is a 404 page"
    (is (= :not-found (get-in (s/handle (st) [:nav/go "/nope"]) [:state :page :status])))))

(deftest stale-page-loads-are-ignored
  (let [state (assoc (st) :pending-url "/shop?page=2")
        r (s/handle state [:page/loaded "/shop" {}])]
    (is (= (:page state) (get-in r [:state :page])))))

(deftest product-recheck-adjusts-quantity-and-explains
  (let [state (st :route (routes/match-url "/products/serum")
                  :page {:status :ok :data {:slug "serum" :availability "available" :maxQuantity 10 :price 85000}}
                  :ui {:product {:qty 6}})
        r (s/handle state [:product/rechecked "serum" {:status 200 :body {:slug "serum" :availability "available" :maxQuantity 3 :price 85000}}])]
    (is (= 3 (get-in r [:state :ui :product :qty])))
    (is (re-find #"Only 3" (get-in r [:state :ui :product :stock-note]))))
  (let [state (st :route (routes/match-url "/products/serum") :page {:status :ok :data {:slug "serum" :availability "available" :maxQuantity 5}} :ui {:product {:qty 1}})
        r (s/handle state [:product/rechecked "serum" {:status 200 :body {:slug "serum" :availability "soldOut" :maxQuantity 0}}])]
    (is (= "This product has just sold out." (get-in r [:state :ui :product :stock-note])))))

(def valid-values {:name "Amina Nakato" :phone "0771 234 567" :city "Kampala" :area "Ntinda" :address-line "Plot 12" :payment-method "cash" :accepted? true})

(deftest checkout-validation
  (let [cities ["Kampala" "Jinja"]]
    (is (= {} (s/checkout-errors valid-values cities)))
    (is (contains? (s/checkout-errors (assoc valid-values :phone "123") cities) :phone))
    (is (contains? (s/checkout-errors (assoc valid-values :city "Gulu") cities) :city) "only the API's cities")
    (is (contains? (s/checkout-errors (assoc valid-values :accepted? false) cities) :accepted))
    (is (contains? (s/checkout-errors (assoc valid-values :email "nope") cities) :email))))

(defn checkout-state []
  (st :route (routes/match-url "/checkout")
      :cart (cart/add-item cart/empty-cart serum 2 10)
      :refs {:delivery-areas {:countries [{:cities ["Kampala"]}]} :policies {"orderTerms" {:version "2026-10-01"}}}
      :forms {:checkout {:values valid-values}}))

(deftest checkout-submit-needs-an-idempotency-key-first
  (let [r (s/handle (checkout-state) [:checkout/submit])]
    (is (seq (effects-named r :fx/uuid)))
    (is (empty? (effects-named r :fx/request)))))

(deftest checkout-submit-sends-the-order-with-agreement-version
  (let [r (s/handle (assoc (checkout-state) :order-key "key-1") [:checkout/submit])
        [_ req] (first (effects-named r :fx/request))]
    (is (= :post (:method req)))
    (is (= "/api/orders" (:path req)))
    (is (= "key-1" (:idempotency-key req)))
    (is (= "2026-10-01" (get-in req [:body :agreement :version])))
    (is (= [{:productId "p1" :quantity 2 :expectedUnitPrice 85000}] (get-in req [:body :items])))
    (is (= "" (get-in req [:body :website])))
    (is (= :submitting (get-in r [:state :forms :checkout :status])))))

(deftest checkout-results
  (let [state (assoc (checkout-state) :order-key "key-1")
        order {:order {:orderNumber "ZT-ABC123"}}]
    (testing "success clears the cart and goes to the confirmation"
      (let [r (s/handle state [:checkout/result {:status 201 :body order}])]
        (is (empty? (get-in r [:state :cart :items])))
        (is (= order (get-in r [:state :last-order])))
        (is (nil? (get-in r [:state :order-key])))
        (is (some #(= [:fx/dispatch [:nav/go "/order-placed"]] %) (:fx r)))))
    (testing "cartOutdated returns to the cart, keeps the form, renews the key"
      (let [r (s/handle state [:checkout/result {:status 409 :body {:error {:code "cartOutdated" :message "x" :details []}}}])]
        (is (= valid-values (get-in r [:state :forms :checkout :values])))
        (is (nil? (get-in r [:state :order-key])))
        (is (some #(= [:fx/dispatch [:nav/go "/cart"]] %) (:fx r)))))
    (testing "terms version changed: reload the terms and ask again"
      (let [r (s/handle state [:checkout/result {:status 409 :body {:error {:code "agreementVersionOutdated" :message "x"}}}])]
        (is (false? (get-in r [:state :forms :checkout :values :accepted?])))
        (is (get-in r [:state :forms :checkout :errors :accepted]))
        (is (some #(= [:fx/dispatch [:agreement/load]] %) (:fx r)))
        (is (= "key-1" (:order-key (:state r))) "same key: nothing was stored")))
    (testing "network failure keeps the key so a retry cannot double-order"
      (let [r (s/handle state [:checkout/result {:status nil :error "x"}])]
        (is (= "key-1" (get-in r [:state :order-key])))
        (is (get-in r [:state :forms :checkout :retry?]))
        (is (= :idle (get-in r [:state :forms :checkout :status])))))
    (testing "server field errors land on the fields"
      (let [r (s/handle state [:checkout/result {:status 422 :body {:error {:code "validationFailed" :message "x"
                                                                           :details [{:field "customer.phone" :code "invalidPhone" :message "Enter a valid Uganda phone number."}]}}}])]
        (is (= "Enter a valid Uganda phone number." (get-in r [:state :forms :checkout :errors :phone])))))))

(deftest forms-clear-errors-on-input
  (let [state (st :forms {:contact {:errors {:name "Enter your name." :message "x"}}})
        r (s/handle state [:form/input :contact :name "Amina"])]
    (is (= "Amina" (get-in r [:state :forms :contact :values :name])))
    (is (= {:message "x"} (get-in r [:state :forms :contact :errors])))))

(deftest admin-status-change-validation
  (let [state (-> (st) (assoc-in [:admin :data :order :body :order] {:id "o1" :version 3 :status "approvedForDelivery"}))]
    (testing "delivered needs a payment method"
      (is (get-in (s/handle state [:admin/status-change "delivered"]) [:state :forms :dialog :errors :paid])))
    (testing "cancel needs a reason"
      (is (get-in (s/handle state [:admin/status-change "cancelled"]) [:state :forms :dialog :errors :reason])))
    (testing "valid change sends the version"
      (let [r (s/handle (assoc-in state [:forms :dialog :values] {:paid "cash"}) [:admin/status-change "delivered"])
            [_ req] (first (effects-named r :fx/request))]
        (is (= 3 (get-in req [:body :version])))
        (is (= "cash" (get-in req [:body :paymentMethodUsed])))))))

(deftest admin-stock-set-to-counted-number-sends-expected-quantity
  (let [state (-> (st) (assoc-in [:forms :dialog] {:values {:mode "set" :qty "10" :reason "stocktake"} :key "k"}))
        r (s/handle state [:admin/stock-submit "p1" 3])
        [_ req] (first (effects-named r :fx/request))]
    (is (= 7 (get-in req [:body :delta])))
    (is (= 3 (get-in req [:body :expectedQuantity])))
    (is (= "k" (:idempotency-key req)))))
