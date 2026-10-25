(ns zandy.frontend.state
  "The single state atom and the pure action handlers. Views are pure
  functions of this state; event handlers in the views are data (vectors of
  actions); a handler takes the state and an action and returns
  {:state new-state :fx [effects]}. Effects are data too and run in
  zandy.frontend.effects."
  (:require [clojure.string :as str]
            [zandy.shared.cart :as cart]
            [zandy.shared.format :as fmt]
            [zandy.shared.pages :as pages]
            [zandy.shared.routes :as routes]))

(defonce !state (atom nil))

(defn initial-state []
  (merge (pages/initial-state)
         {:route nil :site nil :page {:status :loading} :last-order nil
          :admin {:session nil :data {} :ui {}}}))

(defmulti handle
  "(handle state action) -> {:state :fx}. Unknown actions are an error."
  (fn [_state [action-name & _]] action-name))

(defmethod handle :default [state action]
  {:state state :fx [[:fx/log-error (str "Unknown action " (pr-str (first action)))]]})

(defn ret
  ([state] {:state state :fx []})
  ([state fx] {:state state :fx (vec (remove nil? fx))}))

;; ---------------------------------------------------------------------------
;; Navigation and page loading

(defn route-enter
  "Effects to run when a route becomes current (after the page data is there)."
  [state]
  (let [{route-name :name :keys [params]} (:route state)]
    (cond-> [[:fx/head]]
      (nil? (:site state)) (conj [:fx/request {:path "/api/site-info"} [:site/loaded]])
      (= :product route-name) (conj [:fx/request {:path (str "/api/products/" (:slug params)) :no-cache? true}
                                     [:product/rechecked (:slug params)]])
      (#{:cart :checkout} route-name) (conj [:fx/dispatch [:cart/validate]])
      ;; nothing to check out: go to the cart (Journey 1 side path)
      (and (= :checkout route-name) (empty? (get-in state [:cart :items])))
      (conj [:fx/dispatch [:nav/replace "/cart"]])
      (= :checkout route-name) (into [[:fx/request {:path "/api/delivery-areas"} [:refs/areas-loaded]]
                                      [:fx/dispatch [:agreement/load]]])
      (:admin? (:data (:route state))) (conj [:fx/dispatch [:admin/enter]]))))

(defn- reset-page-ui [state]
  (-> state
      (assoc-in [:ui :product] {:qty 1})
      (assoc-in [:ui :gallery] {:index 0})
      (assoc-in [:ui :search-open?] false)
      (assoc-in [:ui :announcement] nil)))

(defn start-route
  "Make `route` current. Public routes with API data load it; others render at
  once."
  [state route {:keys [push? scroll?] :or {push? true scroll? true}} url]
  (let [state (-> state (assoc :route route :pending-url url) reset-page-ui)
        ;; the "cart changed while you were checking out" message belongs to the cart page only
        state (cond-> state (not= :cart (:name route)) (update :ui dissoc :cart-notice))
        reqs (when route (pages/requests route))
        pre (cond-> []
              push? (conj [:fx/push-url url]))]
    (cond
      (nil? route)
      (ret (assoc state :route {:name :not-found :path (first (routes/split-url url))} :page {:status :not-found})
           (into pre [[:fx/head] [:fx/scroll-top]]))

      (seq reqs)
      (ret (assoc state :page {:status :loading})
           (into pre [[:fx/page-load url reqs] (when scroll? [:fx/scroll-top])]))

      :else
      (let [state (assoc state :page {:status :ok :data nil})]
        (ret state (into pre (concat (when scroll? [[:fx/scroll-top]]) (route-enter state))))))))

(defmethod handle :nav/go [state [_ url]]
  (start-route state (routes/match-url url) {} url))

(defmethod handle :nav/link [state [_ url]]
  (start-route state (routes/match-url url) {} url))

(defmethod handle :nav/popstate [state [_ url]]
  (start-route state (routes/match-url url) {:push? false :scroll? false} url))

(defmethod handle :nav/replace [state [_ url]]
  (let [r (start-route state (routes/match-url url) {:push? false} url)]
    (update r :fx #(into [[:fx/replace-url url]] %))))

(defmethod handle :page/loaded [state [_ url results]]
  (if (not= url (:pending-url state))
    (ret state) ;; a newer navigation superseded this one
    (let [{:keys [site page]} (pages/assemble (:route state) results)
          state (cond-> (assoc state :page page)
                  site (assoc :site site))
          state (if (= :ok (:status page))
                  (assoc-in state [:ui :announcement] nil)
                  state)]
      (ret state (if (= :ok (:status page)) (route-enter state) [[:fx/head]])))))

(defmethod handle :site/loaded [state [_ {:keys [status body]}]]
  (if (= 200 status) (ret (assoc state :site body)) (ret state)))

(defmethod handle :refs/areas-loaded [state [_ {:keys [status body]}]]
  (if (= 200 status) (ret (assoc-in state [:refs :delivery-areas] body)) (ret state)))

(defmethod handle :ui/search-toggled [state [_ open?]]
  (ret (assoc-in state [:ui :search-open?] (boolean open?))))

(defmethod handle :search/submit [state [_ form-data]]
  (let [q (:q (pages/normalise-query {:q (get form-data "q")}))]
    (if q
      (handle state [:nav/go (routes/path-for :shop nil {:q q})])
      (ret (assoc-in state [:ui :announcement] "Please type at least 2 characters to search.")))))

(defmethod handle :filters/submit [state [_ base-path form-data]]
  (let [q (pages/normalise-query {:q (get form-data "q") :availability (get form-data "availability") :sort (get form-data "sort")})]
    (handle state [:nav/go (routes/with-query base-path q)])))

(defmethod handle :filters/submit-form [state _]
  (ret state [[:fx/submit-form ".filter-form"]]))

(defmethod handle :products/load-more [state _]
  (let [{:keys [route page]} state
        listing (get-in page [:data :products])
        next-page (inc (or (:page listing) 1))
        slug (get-in route [:params :slug])
        req (pages/products-request (assoc (pages/normalise-query (:query route)) :page next-page) slug)]
    (ret (assoc-in state [:page :loading-more?] true)
         [[:fx/request req [:products/more-loaded]]])))

(defmethod handle :products/more-loaded [state [_ {:keys [status body]}]]
  (if (= 200 status)
    (let [url (routes/with-query (:path (:route state)) (assoc (pages/normalise-query (:query (:route state))) :page (:page body)))]
      (ret (-> state
               (update-in [:page :data :products] (fn [old] (assoc body :items (into (vec (:items old)) (:items body)))))
               (assoc-in [:page :loading-more?] false)
               (assoc-in [:route :query :page] (str (:page body)))
               (assoc-in [:ui :announcement] (str "Loaded " (count (:items body)) " more products.")))
           [[:fx/replace-url url]]))
    (ret (-> state (assoc-in [:page :loading-more?] false)
             (assoc-in [:ui :announcement] "We could not load more products. Please try again.")))))

(defmethod handle :gallery/select [state [_ i]]
  (ret (assoc-in state [:ui :gallery :index] i)))

;; ---------------------------------------------------------------------------
;; Product page

(defn- product-max [state]
  (or (get-in state [:page :data :maxQuantity]) 1))

(defmethod handle :product/set-qty [state [_ q]]
  (let [mx (max 1 (product-max state))]
    (ret (assoc-in state [:ui :product :qty] (-> q (max 1) (min mx))))))

(defmethod handle :product/set-qty-input [state [_ raw]]
  (handle state [:product/set-qty (or (parse-long (str raw)) 1)]))

(defmethod handle :product/rechecked [state [_ slug {:keys [status body]}]]
  (let [cur (get-in state [:page :data])]
    (if (and (= 200 status) (= slug (:slug cur)) (= :product (get-in state [:route :name])))
      (let [new-max (:maxQuantity body)
            old-max (:maxQuantity cur)
            qty (get-in state [:ui :product :qty] 1)
            note (cond
                   (and (= "soldOut" (:availability body)) (not= "soldOut" (:availability cur)))
                   "This product has just sold out."
                   (and (pos? new-max) (< new-max old-max) (> qty new-max))
                   (str "Stock changed. Only " new-max " can be ordered now; we have adjusted your quantity.")
                   (and (pos? new-max) (< new-max old-max))
                   (str "Stock changed. You can order up to " new-max "."))]
        (ret (-> state
                 (update-in [:page :data] merge (select-keys body [:availability :maxQuantity :price]))
                 (assoc-in [:ui :product :qty] (-> qty (min (max 1 new-max)) (max 1)))
                 (assoc-in [:ui :product :stock-note] note)
                 (assoc-in [:ui :announcement] note))))
      (ret state))))

;; ---------------------------------------------------------------------------
;; Cart

(defn- cart-changed [state new-cart]
  (let [state (-> state (assoc :cart new-cart) (assoc :order-key nil))]
    (ret state [[:fx/save-cart new-cart]])))

(defn- with-validate [{:keys [state fx]}]
  (let [on-cart? (#{:cart :checkout} (get-in state [:route :name]))]
    {:state state :fx (cond-> fx on-cart? (conj [:fx/dispatch [:cart/validate]]))}))

(defmethod handle :cart/add [state [_ product qty]]
  (let [new-cart (cart/add-item (:cart state) product qty (:maxQuantity product))]
    (-> (cart-changed state new-cart)
        (update :state assoc-in [:ui :product :added?] true)
        (update :state assoc-in [:ui :announcement]
                (str (:name product) " added to your cart. " (cart/count-items new-cart) " items in the cart."))
        (update :fx conj [:fx/focus "added-notice"]))))

(defmethod handle :cart/set-qty [state [_ id q]]
  (with-validate (cart-changed state (cart/set-quantity (:cart state) id q 10))))

(defmethod handle :cart/set-qty-input [state [_ id raw]]
  (handle state [:cart/set-qty id (or (parse-long (str raw)) 1)]))

(defmethod handle :cart/remove [state [_ id]]
  (-> (with-validate (cart-changed state (cart/remove-item (:cart state) id)))
      (update :state assoc-in [:ui :announcement] "Item removed from your cart.")))

(defmethod handle :cart/validate [state _]
  (if (empty? (get-in state [:cart :items]))
    (ret (assoc state :cart-check {:status :idle}))
    (ret (assoc-in state [:cart-check :status] :loading)
         [[:fx/request {:method :post :path "/api/cart-validations" :body (cart/validation-request (:cart state))}
           [:cart/validated (cart/cart-signature (:cart state))]]])))

(defmethod handle :cart/acknowledge-prices [state _]
  (let [new-cart (cart/acknowledge-prices (:cart state) (get-in state [:cart-check :result]))]
    (ret (assoc state :cart new-cart) [[:fx/save-cart new-cart]])))

(defmethod handle :cart/validated [state [_ sig {:keys [status body]}]]
  (cond
    (not= sig (cart/cart-signature (:cart state))) (ret state)
    (= 200 status)
    (let [new-cart (cart/apply-validation (:cart state) body)]
      (ret (-> state
               (assoc :cart new-cart)
               (assoc :cart-check {:status :ready :result body}))
           [[:fx/save-cart new-cart]]))
    :else (ret (assoc state :cart-check {:status :error}))))

;; ---------------------------------------------------------------------------
;; Generic forms

(defn- form-path [form-id] [:forms form-id])

(defmethod handle :form/input [state [_ form-id k v]]
  (ret (-> state
           (assoc-in (conj (form-path form-id) :values k) v)
           (update-in (conj (form-path form-id) :errors) dissoc k))))

(defmethod handle :form/set [state [_ form-id k v]]
  (let [r (handle state [:form/input form-id k v])]
    ;; a form-level message (e.g. "Stock cannot go below zero.") belongs to the
    ;; previous choice; clear it when the choice changes.
    (cond-> r
      (= :dialog form-id) (update-in [:state :forms :dialog :errors] dissoc :form))))

(defmethod handle :form/noop [state _] (ret state))

(defmethod handle :form/reset [state [_ form-id]]
  (ret (update state :forms dissoc form-id)))

(defmethod handle :form/key-ready [state [_ form-id action key]]
  (handle (assoc-in state (conj (form-path form-id) :key) key) action))

(defn- need-key
  "If the form has no idempotency key yet, generate one and re-dispatch."
  [state form-id action]
  (when-not (get-in state (conj (form-path form-id) :key))
    (ret state [[:fx/uuid [:form/key-ready form-id action]]])))

(defn- email? [s] (boolean (re-matches #"[^@\s]+@[^@\s]+\.[^@\s]+" (str s))))

(defn uganda-phone? [s]
  (boolean (re-matches #"(\+256|0)7\d{8}" (str/replace (str s) #"\s+" ""))))

(defn detail->form-errors
  "Map API error details [{:field :message}] to {form-key message}."
  [details field-map]
  (into {} (keep (fn [{:keys [field message]}]
                   (when-let [k (get field-map field)] [k message])))
        details))

;; Newsletter ---------------------------------------------------------------

(defmethod handle :newsletter/submit [state [_ source :as action]]
  (let [form (get-in state [:forms :newsletter])
        email (str/trim (str (get-in form [:values :email])))]
    (cond
      (not (email? email))
      (ret (assoc-in state [:forms :newsletter :errors] {:email "Enter a valid email address."}))
      :else
      (or (need-key state :newsletter action)
          (ret (-> state (assoc-in [:forms :newsletter :status] :submitting) (assoc-in [:forms :newsletter :errors] {}))
               [[:fx/request {:method :post :path "/api/subscriptions" :idempotency-key (get-in form [:key])
                              :body {:email email :phone nil :consent true :source source :website ""}}
                 [:newsletter/result]]])))))

(defmethod handle :newsletter/result [state [_ {:keys [status body]}]]
  (cond
    (#{200 201} status) (ret (assoc-in state [:forms :newsletter] {:status :done}))
    (= 422 status) (ret (-> state (assoc-in [:forms :newsletter :status] :idle)
                            (assoc-in [:forms :newsletter :errors] (merge {:form "Please check the email address."}
                                                                          (detail->form-errors (get-in body [:error :details]) {"email" :email})))))
    (= 429 status) (ret (-> state (assoc-in [:forms :newsletter :status] :idle)
                            (assoc-in [:forms :newsletter :errors :form] "Too many attempts. Please wait a minute and try again.")))
    :else (ret (-> state (assoc-in [:forms :newsletter :status] :idle)
                   (assoc-in [:forms :newsletter :errors :form] "We could not subscribe you right now. Please try again.")))))

;; Contact ------------------------------------------------------------------

(defn- contact-errors [{:keys [name phone email message]}]
  (cond-> {}
    (< (count (str/trim (str name))) 2) (assoc :name "Enter your name.")
    (and (str/blank? phone) (str/blank? email)) (assoc :phone "Give a phone number or an email address.")
    (and (not (str/blank? phone)) (not (uganda-phone? phone))) (assoc :phone "Enter a valid Uganda phone number.")
    (and (not (str/blank? email)) (not (email? email))) (assoc :email "Enter a valid email address.")
    (not (<= 10 (count (str/trim (str message))) 1000)) (assoc :message "Write between 10 and 1000 characters.")))

(defmethod handle :contact/submit [state [_ :as action]]
  (let [form (get-in state [:forms :contact])
        v (:values form)
        errors (contact-errors v)]
    (if (seq errors)
      (ret (assoc-in state [:forms :contact :errors] errors) [[:fx/focus "error-summary"]])
      (or (need-key state :contact action)
          (ret (-> state (assoc-in [:forms :contact :status] :submitting) (assoc-in [:forms :contact :errors] {}))
               [[:fx/request {:method :post :path "/api/contact-messages" :idempotency-key (:key form)
                              :body {:name (str/trim (:name v)) :phone (not-empty (str/trim (str (:phone v))))
                                     :email (not-empty (str/trim (str (:email v)))) :message (str/trim (:message v)) :website ""}}
                 [:contact/result]]])))))

(defmethod handle :contact/result [state [_ {:keys [status body]}]]
  (cond
    (= 201 status) (ret (assoc-in state [:forms :contact] {:status :done}))
    (= 422 status) (ret (-> state (assoc-in [:forms :contact :status] :idle)
                            (assoc-in [:forms :contact :errors]
                                      (merge {:form "Please check the highlighted fields."}
                                             (detail->form-errors (get-in body [:error :details])
                                                                  {"name" :name "phone" :phone "email" :email "message" :message})))
                            ) [[:fx/focus "error-summary"]])
    (= 429 status) (ret (-> state (assoc-in [:forms :contact :status] :idle)
                            (assoc-in [:forms :contact :errors :form] "Too many messages. Please wait a minute and try again.")))
    :else (ret (-> state (assoc-in [:forms :contact :status] :idle)
                   (assoc-in [:forms :contact :errors :form] "We could not send your message. Please try again, or call us.")))))

;; Tracking -----------------------------------------------------------------

(defmethod handle :track/submit [state _]
  (let [{:keys [order-number phone]} (get-in state [:forms :track :values])
        errors (cond-> {}
                 (str/blank? order-number) (assoc :order-number "Enter your order number.")
                 (not (uganda-phone? phone)) (assoc :phone "Enter the phone number used for the order."))]
    (if (seq errors)
      (ret (assoc-in state [:forms :track :errors] errors))
      (ret (-> state (assoc-in [:forms :track :status] :submitting) (assoc-in [:forms :track :errors] {}) (assoc-in [:forms :track :result] nil))
           [[:fx/request {:method :post :path "/api/order-trackings"
                          :body {:orderNumber (str/upper-case (str/trim order-number)) :phone (str/trim phone)}}
             [:track/result]]]))))

(defmethod handle :track/result [state [_ {:keys [status body]}]]
  (cond
    (= 200 status) (ret (-> state (assoc-in [:forms :track :status] :idle) (assoc-in [:forms :track :result] body)
                            (assoc-in [:ui :announcement] (str "Order " (:orderNumber body) ": " (fmt/order-status-label (:status body))))))
    (= 404 status) (ret (-> state (assoc-in [:forms :track :status] :idle)
                            (assoc-in [:forms :track :errors :form] "We could not find an order with those details. Check the order number and phone number.")))
    (= 429 status) (ret (-> state (assoc-in [:forms :track :status] :idle)
                            (assoc-in [:forms :track :errors :form] "Too many attempts. Please wait a minute and try again.")))
    :else (ret (-> state (assoc-in [:forms :track :status] :idle)
                   (assoc-in [:forms :track :errors :form] "We could not look up your order right now. Please try again.")))))

;; Unsubscribe --------------------------------------------------------------

(defmethod handle :unsubscribe/submit [state _]
  (ret (assoc-in state [:forms :unsubscribe :status] :submitting)
       [[:fx/request {:method :post :path "/api/unsubscribes" :body {:token (get-in state [:route :query :token])}}
         [:unsubscribe/result]]]))

(defmethod handle :unsubscribe/result [state [_ {:keys [status]}]]
  (if (= 200 status)
    (ret (assoc-in state [:forms :unsubscribe] {:status :done}))
    (ret (-> state (assoc-in [:forms :unsubscribe :status] :idle)
             (assoc-in [:forms :unsubscribe :errors :form] "We could not unsubscribe you right now. Please try again.")))))

;; ---------------------------------------------------------------------------
;; Policies and the order terms

(defmethod handle :agreement/load [state _]
  (ret (assoc-in state [:refs :policy-status "orderTerms"] :loading)
       [[:fx/request {:path "/api/policies/orderTerms"} [:agreement/loaded]]]))

(defmethod handle :agreement/loaded [state [_ {:keys [status body]}]]
  (if (= 200 status)
    (ret (-> state (assoc-in [:refs :policies "orderTerms"] body) (assoc-in [:refs :policy-status "orderTerms"] :ready)))
    (ret (assoc-in state [:refs :policy-status "orderTerms"] :error))))

;; ---------------------------------------------------------------------------
;; Checkout

(def checkout-field-map
  {"customer.name" :name "customer.phone" :phone "customer.email" :email
   "customer.preferredContact" :preferred-contact "delivery.city" :city "delivery.area" :area
   "delivery.addressLine" :address-line "delivery.directions" :directions
   "paymentMethod" :payment-method "note" :note "agreement" :accepted "agreement.version" :accepted
   "agreement.type" :accepted})

(defn checkout-errors
  "Client-side convenience validation; the server remains the authority."
  [{:keys [name phone email city area address-line payment-method accepted? note]} cities]
  (cond-> {}
    (not (<= 2 (count (str/trim (str name))) 100)) (assoc :name "Enter your full name.")
    (not (uganda-phone? phone)) (assoc :phone "Enter a valid Uganda phone number, for example 0771 234 567.")
    (and (not (str/blank? email)) (not (email? email))) (assoc :email "Enter a valid email address.")
    (not (some #{city} cities)) (assoc :city "Choose your city.")
    (not (<= 2 (count (str/trim (str area))) 80)) (assoc :area "Enter your area or neighbourhood.")
    (not (<= 3 (count (str/trim (str address-line))) 200)) (assoc :address-line "Enter your street address.")
    (not (#{"cash" "mobileMoney"} payment-method)) (assoc :payment-method "Choose how you will pay.")
    (> (count (str note)) 500) (assoc :note "Keep the note under 500 characters.")
    (not accepted?) (assoc :accepted "Please accept the order terms to place your order.")))

(defn order-body [state]
  (let [v (get-in state [:forms :checkout :values])
        trim #(some-> % str/trim not-empty)]
    {:customer {:name (str/trim (:name v)) :phone (str/trim (:phone v)) :email (trim (:email v))
                :preferredContact (or (:preferred-contact v) "phone")}
     :delivery {:countryCode "UG" :city (:city v) :area (str/trim (:area v))
                :addressLine (str/trim (:address-line v)) :directions (trim (:directions v))}
     :paymentMethod (:payment-method v)
     :note (trim (:note v))
     :items (cart/order-items (:cart state) (get-in state [:cart-check :result]))
     :agreement {:type "orderTerms" :version (get-in state [:refs :policies "orderTerms" :version])}
     :website ""}))

(defmethod handle :checkout/accept [state [_ checked?]]
  (ret (-> state (assoc-in [:forms :checkout :values :accepted?] (boolean checked?))
           (update-in [:forms :checkout :errors] dissoc :accepted))))

(defmethod handle :checkout/key-ready [state [_ key]]
  (handle (assoc state :order-key key) [:checkout/submit]))

(defmethod handle :checkout/submit [state _]
  (let [form (get-in state [:forms :checkout])
        cities (get-in state [:refs :delivery-areas :countries 0 :cities])
        errors (checkout-errors (merge (:values form) {:accepted? (get-in form [:values :accepted?])}) cities)]
    (cond
      (= :submitting (get-in state [:forms :checkout :status])) (ret state)
      (seq errors)
      (ret (-> state (assoc-in [:forms :checkout :errors] errors) (assoc-in [:forms :checkout :retry?] false))
           [[:fx/focus "error-summary"]])

      (nil? (:order-key state))
      (ret state [[:fx/uuid [:checkout/key-ready]]])

      :else
      (ret (-> state (assoc-in [:forms :checkout :status] :submitting)
               (assoc-in [:forms :checkout :errors] {})
               (assoc-in [:forms :checkout :retry?] false))
           [[:fx/request {:method :post :path "/api/orders" :idempotency-key (:order-key state) :body (order-body state)}
             [:checkout/result]]]))))

(defmethod handle :checkout/result [state [_ {:keys [status body]}]]
  (let [code (get-in body [:error :code])
        details (get-in body [:error :details])
        idle (assoc-in state [:forms :checkout :status] :idle)]
    (cond
      (#{200 201} status)
      (ret (-> state
               (assoc :cart cart/empty-cart :cart-check {:status :idle} :order-key nil :last-order body)
               (assoc-in [:forms :checkout] {}))
           [[:fx/save-cart cart/empty-cart] [:fx/save-order body]
            [:fx/dispatch [:nav/go "/order-placed"]]])

      (= "cartOutdated" code)
      (ret (-> idle (assoc :order-key nil)
               (assoc-in [:ui :cart-notice] "Some items in your cart changed while you were checking out. Nothing was ordered. Please review the marked items."))
           [[:fx/dispatch [:nav/go "/cart"]]])

      (= "agreementVersionOutdated" code)
      (ret (-> idle
               (assoc-in [:forms :checkout :values :accepted?] false)
               (assoc-in [:forms :checkout :errors :accepted] "The order terms have changed. Please read and accept the new version."))
           [[:fx/dispatch [:agreement/load]] [:fx/focus "error-summary"]])

      (= 422 status)
      (ret (assoc-in idle [:forms :checkout :errors]
                     (merge {:form "Please check the highlighted fields."}
                            (detail->form-errors details checkout-field-map)))
           [[:fx/focus "error-summary"]])

      (= 429 status)
      (ret (assoc-in idle [:forms :checkout :errors :form] "Too many attempts. Please wait a minute and try again."))

      :else ;; network failure or server error: keep the key so a retry cannot double-order
      (ret (assoc-in idle [:forms :checkout :retry?] true)))))

(defmethod handle :copy-text [state [_ text]]
  (ret (assoc-in state [:ui :announcement] "Order number copied.") [[:fx/copy text]]))

;; Focus and misc DOM actions are handled in the dispatcher (they need the
;; event), but fall back to effects:
(defmethod handle :dom/focus-id [state [_ id]] (ret state [[:fx/focus id]]))
(defmethod handle :dom/prevent-default [state _] (ret state))
