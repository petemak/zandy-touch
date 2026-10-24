(ns zandy.features.orders.routes
  "Cart validation, order placement, tracking and staff order processing (contract sections 3, 4, 7)."
  (:require [clojure.string :as str]
            [zandy.features.accounts.api :as accounts]
            [zandy.features.catalog.api :as catalog]
            [zandy.features.notifications.api :as notifications]
            [zandy.features.orders.db :as db]
            [zandy.features.orders.logic :as logic]
            [zandy.features.orders.schema :as schema]
            [zandy.features.policies.api :as policies]
            [zandy.features.site.api :as site]
            [zandy.platform.errors :as errors]
            [zandy.platform.log :as log]
            [zandy.platform.util :as util]
            [zandy.platform.validation :as v])
  (:import (java.security SecureRandom)))

(def ^:private secure-random (SecureRandom.))

(defn- max-qty [ctx] (get-in ctx [:config :catalog :max-quantity-per-line]))

(defn- quantity-problems [ctx items path]
  (vec (keep-indexed
        (fn [i {:keys [quantity]}]
          (when (> quantity (max-qty ctx))
            (errors/field-error (str path "[" i "].quantity") "invalid"
                                (str "You can order at most " (max-qty ctx) " of one product."))))
        items)))

(defn- duplicate-problems [items path]
  (let [dupes (->> items (map :product-id) frequencies (filter #(> (val %) 1)) keys set)]
    (vec (keep-indexed (fn [i {:keys [product-id]}]
                         (when (contains? dupes product-id)
                           (errors/field-error (str path "[" i "].productId") "duplicate" "List each product only once.")))
                       items))))

;; ---- cart validation -------------------------------------------------------------------------------

(defn cart-validation [{:keys [ctx parameters]}]
  (let [items (get-in parameters [:body :items])
        problems (concat (quantity-problems ctx items "items") (duplicate-problems items "items"))
        _ (when (seq problems) (errors/validation-failed (vec problems)))
        ids (mapv (comp parse-uuid :product-id) items)
        info (catalog/cart-info ctx ids)
        lines (mapv (fn [it id] (logic/cart-line {:product-id id :quantity (:quantity it)} (get info id) (max-qty ctx)))
                    items ids)]
    {:status 200 :body (logic/cart-response (mapv #(update % :problem (fn [p] (some-> p name))) lines))}))

;; ---- placing an order --------------------------------------------------------------------------------

(defn- fake-order
  "The honeypot answer: an order-shaped body that stores nothing and cannot be tracked."
  [ctx body now]
  (let [items-info (catalog/cart-info ctx (map (comp parse-uuid :product-id) (:items body)))
        items (mapv (fn [it]
                      (let [info (get items-info (parse-uuid (:product-id it)))
                            price (or (:price info) (:expected-unit-price it))]
                        {:product-id (parse-uuid (:product-id it)) :name (or (:name info) "Product")
                         :unit-price price :quantity (:quantity it) :line-total (* price (:quantity it))}))
                    (:items body))
        c (:customer body)
        d (:delivery body)]
    {:order {:id (random-uuid) :order-number (logic/order-number #(.nextInt secure-random (int %)))
             :status :placed :placed-at now
             :customer {:name (:name c) :phone (v/normalize-phone (:phone c)) :email (:email c) :preferred-contact (:preferred-contact c)}
             :delivery {:country-code (:country-code d) :city (:city d) :area (:area d)
                        :address-line (:address-line d) :directions (:directions d)}
             :payment-method (:payment-method body) :note (:note body)
             :items items :items-subtotal (logic/subtotal items) :currency "UGX"
             :delivery-fee nil :delivery-fee-note logic/delivery-fee-note :total nil}
     :agreement-acceptance {:type "orderTerms" :version (get-in body [:agreement :version]) :accepted-at now}}))

(defn- unique-conflict-on [ex attr]
  (loop [e ex]
    (when e
      (or (let [m (ex-message e)] (and m (str/includes? m "unique") (str/includes? m attr)))
          (recur (ex-cause e))))))

(defn- outbox-messages [ctx order-number order-id body items-info]
  (let [items (mapv (fn [it]
                      (let [info (get items-info (parse-uuid (:product-id it)))]
                        {:name (:name info) :quantity (:quantity it) :unit-price (:expected-unit-price it)}))
                    (:items body))
        subtotal (logic/subtotal items)
        c (:customer body)
        base (get-in ctx [:config :site :base-url])
        alert (notifications/new-order-alert
               {:order-number order-number :customer-name (:name c) :customer-phone (v/normalize-phone (:phone c))
                :city (get-in body [:delivery :city]) :items items :subtotal subtotal
                :admin-url (str base "/admin/orders/" order-id)})
        alerts (mapv (fn [to] (notifications/outbox-entity (random-uuid) :newOrderAlert to (:subject alert) (:text alert)))
                     (accounts/order-alert-recipients ctx))
        contact (str "Questions? Call or WhatsApp " (get-in ctx [:config :site :phone]) ".")
        receipt (when-let [email (:email c)]
                  (let [m (notifications/customer-receipt {:order-number order-number :items items :subtotal subtotal :contact-line contact})]
                    [(notifications/outbox-entity (random-uuid) :customerReceipt email (:subject m) (:text m))]))]
    (vec (concat alerts receipt))))

(defn- place-spec [ctx req body policy order-number order-id n-lines]
  (let [c (:customer body) d (:delivery body)]
    {:now (util/now ctx) :order-id order-id :order-number order-number
     :idem-key (str "POST " (:uri req) " " (str/lower-case (str/trim (get-in req [:headers "idempotency-key"]))))
     :customer (util/compact {:name (:name c) :phone (v/normalize-phone (:phone c)) :email (:email c)
                              :preferred-contact (keyword (:preferred-contact c))})
     :delivery (util/compact {:country-code (:country-code d)
                              :city (site/canonical-city (:config ctx) (:country-code d) (:city d))
                              :area (:area d) :address-line (:address-line d) :directions (:directions d)})
     :payment-method (keyword (:payment-method body)) :note (:note body)
     :lines (mapv (fn [it] {:product-id (parse-uuid (:product-id it)) :quantity (:quantity it)
                            :expected-price (:expected-unit-price it)})
                  (:items body))
     :acceptance {:id (random-uuid) :policy-type (:type policy) :policy-version (:version policy)
                  :content-hash (:content-hash policy)}
     :event-id (random-uuid)
     :movement-ids (vec (repeatedly n-lines random-uuid))}))

(defn place-order [{:keys [ctx parameters] :as req}]
  (let [body (:body parameters)
        now (util/now ctx)]
    (if (some? (:website body))
      {:status 201 :body (fake-order ctx body now)}
      (let [problems (concat (quantity-problems ctx (:items body) "items") (duplicate-problems (:items body) "items"))
            _ (when (seq problems) (errors/validation-failed (vec problems)))
            d (:delivery body)
            _ (when-not (site/city-allowed? (:config ctx) (:country-code d) (:city d))
                (errors/validation-failed [(errors/field-error "delivery.city" "invalid" "Choose one of the delivery cities.")]))
            policy (policies/current-policy ctx "orderTerms")
            _ (when (not= (:version policy) (get-in body [:agreement :version]))
                (errors/fail! :agreementVersionOutdated "The order terms have changed. Please review and accept them again."
                              [{:field "agreement.version" :code "currentVersion" :message (:version policy)}]))
            items-info (catalog/cart-info ctx (map (comp parse-uuid :product-id) (:items body)))
            order-id (random-uuid)
            idem (str "POST " (:uri req) " " (str/lower-case (str/trim (get-in req [:headers "idempotency-key"]))))]
        (loop [attempt 0]
          (let [order-number (logic/order-number #(.nextInt secure-random (int %)))
                spec (-> (place-spec ctx req body policy order-number order-id (count (:items body)))
                         (assoc :outbox (outbox-messages ctx order-number order-id body items-info)))
                outcome (try (db/place-order! (:conn ctx) spec) :placed
                             (catch Throwable ex
                               (cond
                                 (unique-conflict-on ex ":order/idempotency-key") :replay
                                 (and (unique-conflict-on ex ":order/number") (< attempt 5)) :retry
                                 :else (throw ex))))]
            (case outcome
              :retry (recur (inc attempt))
              :replay (let [o (db/order-by-idempotency-key-fresh (:conn ctx) idem)]
                        {:status 200
                         :body {:order (logic/order-view o) :agreement-acceptance (logic/acceptance-view (:acceptance o))}})
              :placed (let [o (db/order-by-id-fresh (:conn ctx) order-id)]
                        (log/info :order-placed {:order-id order-id :lines (count (:items body))})
                        {:status 201 :replay-status 200
                         :body {:order (logic/order-view o) :agreement-acceptance (logic/acceptance-view (:acceptance o))}}))))))))

;; ---- tracking ---------------------------------------------------------------------------------------------

(defn track-order [{:keys [ctx parameters]}]
  (let [{:keys [order-number phone]} (:body parameters)
        o (db/order-by-number-fresh (:conn ctx) (str/upper-case order-number))]
    (if (and o (= (:customer-phone o) (v/normalize-phone phone)))
      {:status 200 :body (logic/tracking-view o)}
      (errors/fail! :orderNotFound "We could not find an order with those details."))))

;; ---- staff -----------------------------------------------------------------------------------------------------

(defn admin-list [{:keys [ctx parameters]}]
  (let [q (:query parameters)
        orders (db/all-orders (db/current-db (:conn ctx)))
        phone (some-> (:q q) v/normalize-phone)
        filtered (-> orders (logic/filter-orders (assoc q :phone phone)))
        sorted (if (= "placedAtAsc" (:sort q))
                 (sort-by #(.getTime ^java.util.Date (:placed-at %)) filtered)
                 (sort-by #(- (.getTime ^java.util.Date (:placed-at %))) filtered))
        page (util/paginate sorted (:page q) (or (:page-size q) 25))]
    {:status 200
     :body (-> page
               (update :items #(mapv logic/list-item %))
               (assoc :counts (logic/status-counts orders)))}))

(defn- admin-order-body [ctx o]
  (let [users ((:user-summaries ctx) (map :by-eid (:events o)))]
    {:order (logic/admin-order-view o)
     :agreement-acceptance (logic/admin-acceptance-view (:acceptance o))
     :history (logic/history-view (:events o) users)}))

(defn- find-order! [ctx id-str]
  (let [id (v/parse-uuid-or-404 id-str)]
    (or (db/order-by-id-fresh (:conn ctx) id) (errors/fail! :notFound "Order not found."))))

(defn admin-get [{:keys [ctx parameters]}]
  {:status 200 :body (admin-order-body ctx (find-order! ctx (get-in parameters [:path :id])))})

(def ^:private open-statuses #{:placed :confirmed :approvedForDelivery})

(defn admin-patch [{:keys [ctx parameters user]}]
  (let [body (:body parameters)
        o (find-order! ctx (get-in parameters [:path :id]))]
    (when-not (or (contains? body :delivery-fee) (contains? body :staff-note))
      (errors/validation-failed [(errors/field-error "deliveryFee" "required" "Send a delivery fee or a staff note.")]))
    (when-not (contains? open-statuses (:status o))
      (errors/fail! :invalidStatusTransition "This order is closed and can no longer be changed."))
    (when (not= (:version body) (:version o))
      (errors/fail! :versionConflict "This order was changed by someone else. Please reload it."))
    (try (db/patch-order! (:conn ctx) o {:delivery-fee (:delivery-fee body) :staff-note (:staff-note body)
                                          :user-eid (:eid user) :now (util/now ctx)})
         (catch Throwable ex
           (if (str/includes? (str (ex-message ex) (some-> ex ex-cause ex-message)) "cas")
             (errors/fail! :versionConflict "This order was changed by someone else. Please reload it.")
             (throw ex))))
    {:status 200 :body {:order (logic/admin-order-view (db/order-by-id-fresh (:conn ctx) (:id o)))}}))

(defn status-change [{:keys [ctx parameters user]}]
  (let [body (:body parameters)
        o (find-order! ctx (get-in parameters [:path :id]))
        status (keyword (:status body))]
    (if (= status (:status o))
      {:status 200 :body {:order (logic/admin-order-view o) :stock-restored false}}
      (let [problems (cond-> []
                       (and (= status :delivered) (nil? (:payment-method-used body)))
                       (conj (errors/field-error "paymentMethodUsed" "required" "Say whether the customer paid by cash or mobile money."))
                       (and (= status :cancelled) (nil? (:cancel-reason body)))
                       (conj (errors/field-error "cancelReason" "required" "Give a reason for cancelling (3 to 300 characters).")))]
        (when (seq problems) (errors/validation-failed problems))
        (db/change-status! (:conn ctx)
                           (util/compact {:order-id (:id o) :version (:version body) :status status :note (:note body)
                                          :by (:id user) :payment-method-used (some-> (:payment-method-used body) keyword)
                                          :cancel-reason (:cancel-reason body) :now (util/now ctx)
                                          :event-id (random-uuid)
                                          :movement-ids (vec (repeatedly (count (:lines o)) random-uuid))}))
        (let [after (db/order-by-id-fresh (:conn ctx) (:id o))]
          {:status (if (> (:version after) (:version o)) 201 200)
           :body {:order (logic/admin-order-view after)
                  :stock-restored (and (not (:stock-restored? o)) (:stock-restored? after))}})))))

(def routes
  [{:method :post :path "/api/cart-validations" :name :orders/cart-validation :access :public
    :parameters {:body schema/CartValidation} :handler cart-validation}
   {:method :post :path "/api/orders" :name :orders/place :access :public :idempotent? true
    :rate-limit :orders :parameters {:body schema/PlaceOrder} :handler place-order}
   {:method :post :path "/api/order-trackings" :name :orders/track :access :public
    :rate-limit :order-tracking :parameters {:body schema/OrderTracking} :handler track-order}
   {:method :get :path "/api/admin/orders" :name :orders/admin-list :access :staff
    :parameters {:query schema/AdminOrdersQuery} :handler admin-list}
   {:method :get :path "/api/admin/orders/:id" :name :orders/admin-get :access :staff :handler admin-get}
   {:method :patch :path "/api/admin/orders/:id" :name :orders/admin-patch :access :staff
    :parameters {:body schema/PatchOrder} :handler admin-patch}
   {:method :post :path "/api/admin/orders/:id/status-changes" :name :orders/status-change :access :staff
    :parameters {:body schema/StatusChange} :handler status-change}])
