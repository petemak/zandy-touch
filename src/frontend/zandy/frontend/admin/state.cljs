(ns zandy.frontend.admin.state
  "Pure action handlers for the staff area. Same shape as zandy.frontend.state:
  (handle state action) -> {:state :fx}. Every request goes through the
  server's authorization; hiding a button here is convenience only."
  (:require [clojure.string :as str]
            [zandy.frontend.state :as s :refer [handle ret]]
            [zandy.shared.format :as fmt]
            [zandy.shared.routes :as routes]))

(def account-path "/admin/account")

(defn session [state] (get-in state [:admin :session]))
(defn role [state] (get-in (session state) [:user :role]))

(defn route-requests
  "Data each admin route needs: key -> request map."
  [state]
  (let [{route-name :name :keys [params query]} (:route state)
        page (or (some-> (:page query) parse-long) 1)]
    (case route-name
      :admin-orders {:orders {:path "/api/admin/orders"
                              :query (cond-> {:page page} (:status query) (assoc :status (:status query)) (:q query) (assoc :q (:q query)))}}
      :admin-order {:order {:path (str "/api/admin/orders/" (:id params))}}
      :admin-products {:products {:path "/api/admin/products"
                                  :query (cond-> {:page page}
                                           (:status query) (assoc :status (:status query))
                                           (:stock query) (assoc :stock (:stock query))
                                           (:q query) (assoc :q (:q query)))}}
      :admin-new-product {:photos {:path "/api/admin/photos" :query {:pageSize 100}}}
      :admin-product {:product {:path (str "/api/admin/products/" (:id params))}
                      :movements {:path (str "/api/admin/products/" (:id params) "/stock-movements")}
                      :photos {:path "/api/admin/photos" :query {:pageSize 100}}}
      :admin-photos {:photos (cond-> {:path "/api/admin/photos" :query {:pageSize 40}}
                              (= "true" (:unassigned query)) (assoc-in [:query :unassigned] "true"))
                     :products {:path "/api/admin/products" :query {:pageSize 100}}}
      :admin-inbox {:messages {:path "/api/admin/contact-messages"}
                    :subscribers {:path "/api/admin/subscribers"}}
      :admin-staff {:staff {:path "/api/admin/staff"}}
      {})))

(defn- load-fx [state]
  (vec (for [[k req] (route-requests state)]
         [:fx/request req [:admin/loaded k]])))

(defn- loading [state]
  (assoc-in state [:admin :data] (into {} (map (fn [k] [k {:status :loading}])) (keys (route-requests state)))))

(defn- landing-path
  "A temporary password must be changed before anything else."
  [user]
  (if (:mustChangePassword user) account-path "/admin"))

(defn- to-login [state]
  (ret (-> state (assoc-in [:admin :session] nil) (assoc-in [:admin :checked?] true))
       [[:fx/dispatch [:nav/replace "/admin/login"]]]))

(declare product-values)

(defn- enter-with-session [state]
  (let [{:keys [name data]} (:route state)
        user (:user (session state))]
    (cond
      (= :public (:access data))
      (ret state [[:fx/head]])

      (and (:mustChangePassword user) (not= :admin-account name))
      (ret state [[:fx/dispatch [:nav/replace "/admin/account"]]])

      (and (= :owner (:access data)) (not= "owner" (:role user)))
      (ret (assoc-in state [:admin :data] {:forbidden {:status :error}}) [[:fx/head]])

      :else
      (let [new? (= :admin-new-product name)
            state (cond-> (loading state)
                    (and new? (not= :new (get-in state [:forms :product :init])))
                    (assoc-in [:forms :product] {:values (product-values {}) :init :new}))]
        (ret state (into [[:fx/head]] (load-fx state)))))))

(defmethod handle :admin/enter [state _]
  (let [{:keys [data]} (:route state)]
    (cond
      (session state) (enter-with-session state)
      (= :public (:access data))
      (if (:checked? (:admin state))
        (ret state [[:fx/head]])
        ;; already signed in? go to the orders list
        (ret state [[:fx/request {:path "/api/auth/sessions/current"} [:admin/session-checked]] [:fx/head]]))
      :else
      (ret state [[:fx/request {:path "/api/auth/sessions/current"} [:admin/session-checked]]]))))

(defmethod handle :admin/session-checked [state [_ {:keys [status body]}]]
  (if (= 200 status)
    (let [st (assoc-in state [:admin :session] {:user (:user body) :csrf (:csrfToken body)})]
      (if (= :public (:access (:data (:route st))))
        (ret st [[:fx/dispatch [:nav/replace "/admin"]]])
        (enter-with-session st)))
    (if (= :public (:access (:data (:route state))))
      (ret (assoc-in state [:admin :checked?] true) [[:fx/head]])
      (to-login state))))

(defmethod handle :admin/loaded [state [_ k {:keys [status body]}]]
  (cond
    (= 401 status) (to-login state)
    (= 403 status)
    (if (= "passwordChangeRequired" (get-in body [:error :code]))
      (ret state [[:fx/dispatch [:nav/replace "/admin/account"]]])
      (ret (assoc-in state [:admin :data k] {:status :error :error (get-in body [:error :message])})))
    (= 200 status)
    (let [st (assoc-in state [:admin :data k] {:status :ok :body body})
          init? (and (= :product k) (not= (:id body) (get-in state [:forms :product :init])))]
      (ret st (when init? [[:fx/dispatch [:admin/product-init body]]])))
    :else (ret (assoc-in state [:admin :data k] {:status :error :error (get-in body [:error :message])}))))

(defmethod handle :admin/reload [state _]
  (ret (loading state) (load-fx state)))

;; ---------------------------------------------------------------------------
;; Sign in, sign out, password

(defn- form-err [state form-id msg]
  (-> state (assoc-in [:forms form-id :status] :idle) (assoc-in [:forms form-id :errors :form] msg)))

(defmethod handle :admin/login [state _]
  (let [{:keys [email password]} (get-in state [:forms :login :values])]
    (if (or (str/blank? email) (str/blank? password))
      (ret (assoc-in state [:forms :login :errors] {:form "Enter your email and password."}))
      (ret (-> state (assoc-in [:forms :login :status] :submitting) (assoc-in [:forms :login :errors] {}))
           [[:fx/request {:method :post :path "/api/auth/sessions" :body {:email (str/trim email) :password password}}
             [:admin/login-result]]]))))

(defmethod handle :admin/login-result [state [_ {:keys [status body]}]]
  (cond
    (= 201 status)
    (ret (-> state (assoc-in [:admin :session] {:user (:user body) :csrf (:csrfToken body)})
             (assoc :forms (dissoc (:forms state) :login)))
         [[:fx/dispatch [:nav/replace (landing-path (get-in body [:user]))]]])
    (= 401 status) (ret (form-err state :login "Email or password is incorrect."))
    (= 429 status) (ret (form-err state :login "Too many attempts. Please wait a few minutes and try again."))
    :else (ret (form-err state :login "We could not sign you in right now. Please try again."))))

(defmethod handle :admin/logout [state _]
  (ret (-> state (assoc-in [:admin :session] nil) (assoc-in [:admin :data] {}))
       [[:fx/request {:method :delete :path "/api/auth/sessions/current" :csrf (get-in state [:admin :session :csrf])} [:admin/logged-out]]]))

(defmethod handle :admin/logged-out [state _]
  (ret (assoc-in state [:admin :checked?] true) [[:fx/dispatch [:nav/go "/admin/login"]]]))

(defmethod handle :admin/forgot [state _]
  (let [email (get-in state [:forms :forgot :values :email])]
    (if (str/blank? email)
      (ret (assoc-in state [:forms :forgot :errors] {:email "Enter your email address."}))
      (ret (assoc-in state [:forms :forgot :status] :submitting)
           [[:fx/request {:method :post :path "/api/auth/password-resets" :body {:email (str/trim email)}} [:admin/forgot-result]]]))))

(defmethod handle :admin/forgot-result [state _]
  (ret (assoc-in state [:forms :forgot] {:status :done})))

(defmethod handle :admin/reset [state _]
  (let [pw (get-in state [:forms :reset :values :password])
        token (get-in state [:route :query :token])]
    (cond
      (< (count (str pw)) 10) (ret (assoc-in state [:forms :reset :errors] {:password "Use at least 10 characters."}))
      :else (ret (assoc-in state [:forms :reset :status] :submitting)
                 [[:fx/request {:method :put :path (str "/api/auth/password-resets/" token) :body {:newPassword pw}} [:admin/reset-result]]]))))

(defmethod handle :admin/reset-result [state [_ {:keys [status]}]]
  (if (= 200 status)
    (ret (assoc-in state [:forms :reset] {:status :done}))
    (ret (form-err state :reset "This link is no longer valid. Ask for a new one."))))

(defmethod handle :admin/change-password [state _]
  (let [{:keys [current new]} (get-in state [:forms :password :values])]
    (if (or (str/blank? current) (< (count (str new)) 10))
      (ret (assoc-in state [:forms :password :errors]
                     (cond-> {} (str/blank? current) (assoc :current "Enter your current password.")
                             (< (count (str new)) 10) (assoc :new "Use at least 10 characters."))))
      (ret (assoc-in state [:forms :password :status] :submitting)
           [[:fx/request {:method :put :path "/api/admin/account/password" :body {:currentPassword current :newPassword new}}
             [:admin/change-password-result]]]))))

(defmethod handle :admin/change-password-result [state [_ {:keys [status body]}]]
  (case status
    200 (ret (-> state (assoc-in [:forms :password] {:status :done})
                 (assoc-in [:admin :session :user :mustChangePassword] false)))
    401 (ret (-> state (assoc-in [:forms :password :status] :idle) (assoc-in [:forms :password :errors :current] "Your current password is not correct.")))
    422 (ret (form-err state :password (or (get-in body [:error :details 0 :message]) "Please choose a different password.")))
    (ret (form-err state :password "We could not change the password right now."))))

;; ---------------------------------------------------------------------------
;; Generic mutations with an idempotency key

(defmethod handle :admin/key-ready [state [_ form-id action key]]
  (handle (assoc-in state [:forms form-id :key] key) action))

(defn- conflict-banner [state]
  (assoc-in state [:admin :ui :conflict] "This record was updated by someone else a moment ago. The latest version has been loaded; please check it and try again."))

(defmethod handle :admin/dismiss-conflict [state _]
  (ret (assoc-in state [:admin :ui :conflict] nil)))

;; ---------------------------------------------------------------------------
;; Orders

(defmethod handle :admin/order-fee-input [state [_ v]]
  (ret (assoc-in state [:forms :fee :values :fee] v)))

(defmethod handle :admin/order-save [state _]
  (let [order (get-in state [:admin :data :order :body :order])
        {:keys [fee note]} (get-in state [:forms :fee :values])
        fee (if (some? fee) fee (some-> (:deliveryFee order) str))
        fee-n (when-not (str/blank? (str fee)) (parse-long (str/replace (str fee) "," "")))
        note (if (some? note) note (:staffNote order))]
    (cond
      (and (not (str/blank? (str fee))) (or (nil? fee-n) (neg? fee-n) (> fee-n 1000000)))
      (ret (assoc-in state [:forms :fee :errors] {:fee "Enter a fee between 0 and 1,000,000 UGX."}))
      :else
      (ret (-> state (assoc-in [:forms :fee :status] :submitting) (assoc-in [:forms :fee :errors] {}))
           [[:fx/request {:method :patch :path (str "/api/admin/orders/" (:id order))
                          :body (cond-> {:version (:version order)}
                                  (some? fee-n) (assoc :deliveryFee fee-n)
                                  (some? note) (assoc :staffNote note))}
             [:admin/order-saved]]]))))

(defmethod handle :admin/order-saved [state [_ {:keys [status body]}]]
  (case status
    200 (ret (-> state (assoc-in [:admin :data :order :body :order] (:order body))
                 (assoc-in [:forms :fee] {:status :done})))
    409 (ret (-> state conflict-banner (assoc-in [:forms :fee :status] :idle)) [[:fx/dispatch [:admin/reload]]])
    401 (to-login state)
    (ret (form-err state :fee (or (get-in body [:error :message]) "Could not save.")))))

(defmethod handle :admin/dialog [state [_ dialog]]
  (ret (-> state (assoc-in [:admin :ui :dialog] dialog) (assoc-in [:forms :dialog] {:values (:defaults dialog)}))
       [[:fx/focus "dialog-title"]]))

(defmethod handle :admin/dialog-close [state _]
  (ret (-> state (assoc-in [:admin :ui :dialog] nil) (assoc :forms (dissoc (:forms state) :dialog)))))

(defmethod handle :admin/status-change [state [_ target]]
  (let [order (get-in state [:admin :data :order :body :order])
        v (get-in state [:forms :dialog :values])
        errors (cond-> {}
                 (and (= "delivered" target) (not (#{"cash" "mobileMoney"} (:paid v)))) (assoc :paid "Choose how the customer paid.")
                 (and (= "cancelled" target) (not (<= 3 (count (str/trim (str (:reason v)))) 300))) (assoc :reason "Give a reason (3 to 300 characters)."))]
    (if (seq errors)
      (ret (assoc-in state [:forms :dialog :errors] errors))
      (ret (assoc-in state [:forms :dialog :status] :submitting)
           [[:fx/request {:method :post :path (str "/api/admin/orders/" (:id order) "/status-changes")
                          :body (cond-> {:version (:version order) :status target}
                                  (not (str/blank? (:note v))) (assoc :note (:note v))
                                  (= "delivered" target) (assoc :paymentMethodUsed (:paid v))
                                  (= "cancelled" target) (assoc :cancelReason (str/trim (:reason v))))}
             [:admin/status-changed target]]]))))

(defmethod handle :admin/status-changed [state [_ target {:keys [status body]}]]
  (let [code (get-in body [:error :code])]
    (cond
      (#{200 201} status)
      (ret (-> state (assoc-in [:admin :data :order :body :order] (:order body))
               (assoc-in [:admin :ui :dialog] nil)
               (assoc-in [:admin :ui :notice]
                         (if (:stockRestored body) "Order cancelled. Stock for these items has been returned."
                             (if (= "cancelled" target) "Order cancelled. Stock was not returned automatically; adjust stock if the goods come back."
                                 (str "Order marked: " (fmt/staff-status-label target)))))
               (update :forms dissoc :dialog))
           [[:fx/dispatch [:admin/reload]]])
      (= 409 status) (ret (-> state (assoc-in [:forms :dialog :status] :idle)
                              (assoc-in [:forms :dialog :errors :form] (get-in body [:error :message]))
                              (cond-> (= "versionConflict" code) conflict-banner))
                          (when (= "versionConflict" code) [[:fx/dispatch [:admin/reload]]]))
      (= 401 status) (to-login state)
      :else (ret (assoc-in state [:forms :dialog :errors :form] (or (get-in body [:error :details 0 :message]) (get-in body [:error :message] "Could not save.")))))))

;; ---------------------------------------------------------------------------
;; Products and stock

(def product-field-map
  {"name" :name "brand" :brand "categorySlug" :category "price" :price "sku" :sku "status" :status "images" :images})

(defn product-values [p]
  {:name (:name p) :brand (:brand p) :own-brand? (boolean (:isOwnBrand p)) :category (:categorySlug p)
   :sku (:sku p) :price (some-> (:price p) str) :short (:shortDescription p) :description (:description p)
   :size (:size p) :how-to-use (:howToUse p) :ingredients (:ingredients p) :featured? (boolean (:featured p))
   :status (or (:status p) "draft") :stock "0" :images (vec (map #(select-keys % [:id :alt :urls]) (:images p)))})

(defmethod handle :admin/product-init [state [_ p]]
  (ret (assoc-in state [:forms :product] {:values (product-values p) :init (or (:id p) :new)})))

(defn- product-body [v new?]
  (let [t #(some-> % str str/trim not-empty)
        price (some-> (t (:price v)) (str/replace "," "") parse-long)]
    (cond-> {:name (str/trim (str (:name v))) :brand (str/trim (str (:brand v))) :isOwnBrand (boolean (:own-brand? v))
             :categorySlug (:category v) :price price :shortDescription (t (:short v)) :description (t (:description v))
             :size (t (:size v)) :howToUse (t (:how-to-use v)) :ingredients (t (:ingredients v))
             :featured (boolean (:featured? v)) :status (:status v) :sku (t (:sku v))
             :images (mapv (fn [i] (cond-> {:id (:id i)} (not (str/blank? (:alt i))) (assoc :alt (:alt i)))) (:images v))}
      new? (assoc :stockQuantity (or (some-> (t (:stock v)) parse-long) 0)))))

(defn- product-errors [v]
  (cond-> {}
    (< (count (str/trim (str (:name v)))) 2) (assoc :name "Enter the product name.")
    (str/blank? (:brand v)) (assoc :brand "Enter the brand.")
    (str/blank? (:category v)) (assoc :category "Choose a category.")
    (and (= "published" (:status v)) (str/blank? (str (:price v)))) (assoc :price "A product needs a price before it can be published.")
    (and (= "published" (:status v)) (empty? (:images v))) (assoc :images "A product needs at least one photo before it can be published.")))

(defmethod handle :admin/product-save [state [_ product-id :as action]]
  (let [form (get-in state [:forms :product])
        v (:values form)
        errors (product-errors v)
        new? (nil? product-id)]
    (cond
      (seq errors) (ret (assoc-in state [:forms :product :errors] errors) [[:fx/focus "error-summary"]])
      (and new? (nil? (:key form))) (ret state [[:fx/uuid [:admin/key-ready :product action]]])
      :else
      (ret (-> state (assoc-in [:forms :product :status] :submitting) (assoc-in [:forms :product :errors] {}))
           [[:fx/request (if new?
                           {:method :post :path "/api/admin/products" :idempotency-key (:key form) :body (product-body v true)}
                           {:method :patch :path (str "/api/admin/products/" product-id)
                            :body (assoc (product-body v false) :version (get-in state [:admin :data :product :body :version]))})
             [:admin/product-saved new?]]]))))

(defmethod handle :admin/product-saved [state [_ new? {:keys [status body]}]]
  (let [_ nil]
    (cond
      (#{200 201} status)
      (ret (-> state (assoc-in [:forms :product :status] :done)
               (assoc-in [:admin :ui :notice] "Product saved.")
               (assoc-in [:admin :ui :saved-slug] (:slug body)))
           (if new?
             [[:fx/dispatch [:nav/go (str "/admin/products/" (:id body))]]]
             [[:fx/dispatch [:admin/reload]] [:fx/focus "main"]]))
      (= 409 status) (ret (-> state conflict-banner (assoc-in [:forms :product :status] :idle)) [[:fx/dispatch [:admin/reload]]])
      (= 422 status) (ret (-> state (assoc-in [:forms :product :status] :idle)
                              (assoc-in [:forms :product :errors] (merge {:form "Please check the highlighted fields."}
                                                                         (s/detail->form-errors (get-in body [:error :details]) product-field-map))))
                          [[:fx/focus "error-summary"]])
      (= 401 status) (to-login state)
      :else (ret (form-err state :product "We could not save the product. Please try again.")))))

(defmethod handle :admin/product-image-add [state [_ photo]]
  (let [imgs (get-in state [:forms :product :values :images])]
    (if (or (>= (count imgs) 6) (some #(= (:id photo) (:id %)) imgs))
      (ret state)
      (ret (-> state (update-in [:forms :product :values :images] conj {:id (:id photo) :urls (:urls photo) :alt ""})
               (update-in [:forms :product :errors] dissoc :images))))))

(defmethod handle :admin/product-image-remove [state [_ i]]
  (ret (update-in state [:forms :product :values :images] (fn [v] (vec (concat (subvec v 0 i) (subvec v (inc i))))))))

(defmethod handle :admin/product-image-move [state [_ i d]]
  (ret (update-in state [:forms :product :values :images]
                  (fn [v] (let [j (+ i d)]
                            (if (and (<= 0 j) (< j (count v)))
                              (-> v (assoc i (nth v j)) (assoc j (nth v i)))
                              v))))))

(defmethod handle :admin/product-image-alt [state [_ i alt]]
  (ret (assoc-in state [:forms :product :values :images i :alt] alt)))

(defmethod handle :admin/archive [state [_ id]]
  (ret (assoc-in state [:admin :ui :dialog] nil)
       [[:fx/request {:method :delete :path (str "/api/admin/products/" id)} [:admin/archived]]]))

(defmethod handle :admin/archived [state [_ {:keys [status]}]]
  (if (= 204 status)
    (ret (assoc-in state [:admin :ui :notice] "Product removed from the shop (archived).")
         [[:fx/dispatch (if (= :admin-product (get-in state [:route :name])) [:nav/go "/admin/products"] [:admin/reload])]])
    (ret (assoc-in state [:admin :ui :notice] "Could not archive the product."))))

(defmethod handle :admin/stock-submit [state [_ product-id current :as action]]
  (let [form (get-in state [:forms :dialog])
        {:keys [mode qty reason note]} (:values form)
        n (parse-long (str/trim (str qty)))
        errors (cond-> {}
                 (or (nil? n) (neg? n) (and (not= "set" mode) (zero? n)) (> n 100000)) (assoc :qty "Enter a whole number.")
                 (str/blank? reason) (assoc :reason "Choose a reason."))
        delta (if n (case mode "add" n "remove" (- n) "set" (- n current)) 0)]
    (cond
      (seq errors) (ret (assoc-in state [:forms :dialog :errors] errors))
      (and delta (zero? delta)) (ret (assoc-in state [:forms :dialog :errors :qty] "The count already matches."))
      (nil? (:key form)) (ret state [[:fx/uuid [:admin/key-ready :dialog action]]])
      :else
      (ret (assoc-in state [:forms :dialog :status] :submitting)
           [[:fx/request {:method :post :path (str "/api/admin/products/" product-id "/stock-movements") :idempotency-key (:key form)
                          :body (cond-> {:delta delta :reason reason :expectedQuantity current}
                                  (not (str/blank? note)) (assoc :note note))}
             [:admin/stock-result]]]))))

(defmethod handle :admin/stock-result [state [_ {:keys [status body]}]]
  (cond
    (= 201 status) (ret (-> state (assoc-in [:admin :ui :dialog] nil) (update :forms dissoc :dialog)
                            (assoc-in [:admin :ui :notice] (str "Stock updated. Now " (:stockQuantity body) ".")))
                        [[:fx/dispatch [:admin/reload]]])
    (= 409 status) (ret (-> state (assoc-in [:forms :dialog :status] :idle)
                            (assoc-in [:forms :dialog :errors :form]
                                      "The stock changed while you were working. The latest count has been loaded. Please check it and confirm again."))
                        [[:fx/dispatch [:admin/reload]]])
    (= 422 status) (ret (-> state (assoc-in [:forms :dialog :status] :idle)
                            (assoc-in [:forms :dialog :errors :form] (or (get-in body [:error :details 0 :message]) "Please check the values."))))
    (= 401 status) (to-login state)
    :else (ret (assoc-in state [:forms :dialog :errors :form] "Could not update stock."))))

;; ---------------------------------------------------------------------------
;; Photos

(defmethod handle :admin/upload [state [_ :as action]]
  (if-not (get-in state [:forms :upload :key])
    (ret state [[:fx/uuid [:admin/key-ready :upload action]]])
    (ret (assoc-in state [:forms :upload :status] :submitting)
         [[:fx/upload {:path "/api/admin/photos" :input "photo-files" :idempotency-key (get-in state [:forms :upload :key])} [:admin/uploaded]]])))

(defmethod handle :admin/uploaded [state [_ {:keys [status body]}]]
  (cond
    (= 201 status) (ret (assoc-in state [:forms :upload] {:status :done :items (:items body)}) [[:fx/dispatch [:admin/reload]]])
    (= 401 status) (to-login state)
    :else (ret (assoc-in state [:forms :upload] {:status :idle :error (or (get-in body [:error :message]) "Upload failed.")}))))

(defmethod handle :admin/assign [state [_ photo-id product-id]]
  (if (str/blank? product-id)
    (ret state)
    (ret state [[:fx/request {:method :post :path "/api/admin/photo-assignments" :body {:assignments [{:photoId photo-id :productId product-id}]}}
                 [:admin/assigned photo-id]]])))

(defmethod handle :admin/assigned [state [_ _photo-id {:keys [status body]}]]
  (let [r (first (:results body))]
    (ret (assoc-in state [:admin :ui :notice]
                   (if (and (= 200 status) (= "assigned" (:status r))) "Photo attached to the product."
                       (or (get-in r [:error :message]) "Could not attach the photo.")))
         [[:fx/dispatch [:admin/reload]]])))

(defmethod handle :admin/photo-delete [state [_ id]]
  (ret (assoc-in state [:admin :ui :dialog] nil)
       [[:fx/request {:method :delete :path (str "/api/admin/photos/" id)} [:admin/photo-deleted]]]))

(defmethod handle :admin/photo-deleted [state _]
  (ret (assoc-in state [:admin :ui :notice] "Photo deleted.") [[:fx/dispatch [:admin/reload]]]))

;; ---------------------------------------------------------------------------
;; Staff

(defmethod handle :admin/staff-add [state [_ :as action]]
  (let [form (get-in state [:forms :staff])
        {:keys [name email notify?]} (:values form)
        errors (cond-> {}
                 (< (count (str/trim (str name))) 2) (assoc :name "Enter the name.")
                 (not (re-matches #"[^@\s]+@[^@\s]+\.[^@\s]+" (str email))) (assoc :email "Enter a valid email address."))]
    (cond
      (seq errors) (ret (assoc-in state [:forms :staff :errors] errors))
      (nil? (:key form)) (ret state [[:fx/uuid [:admin/key-ready :staff action]]])
      :else (ret (assoc-in state [:forms :staff :status] :submitting)
                 [[:fx/request {:method :post :path "/api/admin/staff" :idempotency-key (:key form)
                                :body {:name (str/trim name) :email (str/trim email) :notifyOnNewOrder (boolean notify?)}}
                   [:admin/staff-added]]]))))

(defmethod handle :admin/staff-added [state [_ {:keys [status body]}]]
  (cond
    (= 201 status) (ret (-> state (assoc :forms (dissoc (:forms state) :staff))
                            (assoc-in [:admin :ui :temp-password] {:for (get-in body [:staff :name]) :value (:temporaryPassword body)}))
                        [[:fx/dispatch [:admin/reload]]])
    (= 409 status) (ret (-> state (assoc-in [:forms :staff :status] :idle) (assoc-in [:forms :staff :errors :email] "That email is already in use.")))
    :else (ret (form-err state :staff (or (get-in body [:error :message]) "Could not add the account.")))))

(defmethod handle :admin/staff-reset [state [_ id nm]]
  (ret (assoc-in state [:admin :ui :dialog] nil)
       [[:fx/uuid [:admin/staff-reset-go id nm]]]))

(defmethod handle :admin/staff-reset-go [state [_ id nm key]]
  (ret state [[:fx/request {:method :post :path (str "/api/admin/staff/" id "/password-resets") :idempotency-key key}
               [:admin/staff-reset-result nm]]]))

(defmethod handle :admin/staff-reset-result [state [_ nm {:keys [status body]}]]
  (if (= 201 status)
    (ret (assoc-in state [:admin :ui :temp-password] {:for nm :value (:temporaryPassword body)}))
    (ret (assoc-in state [:admin :ui :notice] "Could not reset the password."))))

(defmethod handle :admin/staff-deactivate [state [_ id]]
  (ret (assoc-in state [:admin :ui :dialog] nil)
       [[:fx/request {:method :delete :path (str "/api/admin/staff/" id)} [:admin/staff-deactivated]]]))

(defmethod handle :admin/staff-deactivated [state _]
  (ret (assoc-in state [:admin :ui :notice] "Account deactivated.") [[:fx/dispatch [:admin/reload]]]))

(defmethod handle :admin/dismiss-temp [state _]
  (ret (assoc-in state [:admin :ui :temp-password] nil)))

(defmethod handle :admin/dismiss-notice [state _]
  (ret (assoc-in state [:admin :ui :notice] nil)))

(defmethod handle :admin/search [state [_ path form-data]]
  (let [q (str/trim (str (get form-data "q")))
        status (get form-data "status")]
    (handle state [:nav/go (routes/with-query path (cond-> {} (not (str/blank? q)) (assoc :q q) (not (str/blank? status)) (assoc :status status)))])))

(defmethod handle :admin/dialog-keydown [state [_ k]]
  (if (= "Escape" k) (handle state [:admin/dialog-close]) (ret state)))
