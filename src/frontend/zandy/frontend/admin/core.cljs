(ns zandy.frontend.admin.core
  "Staff area views A1 to A13. Client-rendered only. All views are pure
  functions of the state; handlers are data."
  (:require [clojure.string :as str]
            [zandy.frontend.admin.help :as help]
            [zandy.frontend.admin.state :as admin-state]
            [zandy.frontend.admin.widgets :as widgets]
            [zandy.shared.format :as fmt]
            [zandy.shared.routes :as routes]
            [zandy.shared.ui.core :as ui]
            [zandy.shared.ui.layout :as layout]))

;; ---------------------------------------------------------------------------
;; Helpers

(defn- data [state k] (get-in state [:admin :data k]))
(defn- forms [state id] (get-in state [:forms id]))
(defn- admin-ui [state] (get-in state [:admin :ui]))

(defn- with-data
  "Render `f` with the loaded body, or a loading/error notice."
  [d f]
  (case (:status d)
    :ok (f (:body d))
    :error (ui/notice {:kind :error} (or (:error d) "We could not load this. ") " "
                      (ui/button {:variant :secondary :on-click [[:admin/reload]]} "Try again"))
    (ui/spinner-text "Loading")))

(defn- link-q [path query] (routes/with-query path (into {} (remove (comp nil? val)) query)))

(defn- pager [path query {:keys [page pageSize totalItems]}]
  (when (and page (> totalItems pageSize))
    (let [last-page (long (Math/ceil (/ totalItems pageSize)))]
      [:nav.pagination {:aria-label "Pages"}
       (when (> page 1) (ui/link (link-q path (assoc query :page (dec page))) {:class ["button" "button--secondary"]} "Previous"))
       [:span (str "Page " page " of " last-page)]
       (when (< page last-page) (ui/link (link-q path (assoc query :page (inc page))) {:class ["button" "button--secondary"]} "Next"))])))

(defn- notice-bar [state]
  (let [{:keys [notice conflict]} (admin-ui state)]
    [:div
     (when notice [:div (ui/notice {:kind :success :live? true} [:span notice " "] (ui/button {:variant :secondary :on-click [[:admin/dismiss-notice]]} "Dismiss"))])
     (when conflict [:div (ui/notice {:kind :warning :live? true} [:span conflict " "] (ui/button {:variant :secondary :on-click [[:admin/dismiss-conflict]]} "Dismiss"))])]))

(defn- submit-btn [status label busy-label]
  (ui/button {:type "submit" :disabled? (= :submitting status)} (if (= :submitting status) busy-label label)))

(defn- ugx [n] (or (fmt/ugx n) "Not set"))

;; ---------------------------------------------------------------------------
;; Shell

(def nav-items
  [[:admin-orders "/admin" "Orders" #{:admin-orders :admin-order}]
   [:admin-products "/admin/products" "Products" #{:admin-products :admin-new-product :admin-product}]
   [:admin-product-import "/admin/product-import" "Import" #{:admin-product-import}]
   [:admin-inbox "/admin/inbox" "Inbox" #{:admin-inbox}]
   [:admin-staff "/admin/staff" "Staff" #{:admin-staff} :owner]
   [:admin-account "/admin/account" "My account" #{:admin-account}]
   [:admin-help "/admin/help" "Help" #{:admin-help :admin-help-topic}]])

(defn- page-title
  "The page's one h1 with the small \"How do I...?\" link beside it. No link while
  a temporary password must be changed (every other page redirects away then)."
  [state & children]
  (let [user (get-in state [:admin :session :user])]
    [:div.page-head
     (into [:h1.page-title] children)
     (when-not (:mustChangePassword user)
       (help/how-do-i (get-in state [:route :name]) (:role user)))]))

(defn- shell [state content]
  (let [user (get-in state [:admin :session :user])
        current (get-in state [:route :name])
        locked? (:mustChangePassword user)]
    [:div.admin
     [:a.skip-link {:href "#main" :on {:click [[:dom/focus-id "main"]]}} "Skip to content"]
     [:header.admin-top
      [:div.admin-top__bar.container
       (layout/brand-logo :horizontal "Zandy Touch staff area" "admin-top__logo")
       [:div.admin-top__user
        [:span (str (:name user) " (" (:role user) ")")]
        (ui/button {:variant :secondary-on-dark :on-click [[:admin/logout]]} "Sign out")]]
      (when-not locked?
        [:nav.container {:aria-label "Staff"}
         [:ul.admin-nav
          (for [[k path label active role] nav-items
                :when (or (nil? role) (= "owner" (:role user)))]
            ^{:key k} [:li (ui/link path {:aria-current (when (active current) "page")} label)])]])]
     [:main#main.admin-main.container {:tabindex "-1"}
      (notice-bar state)
      content]
     ]))

(defn- dialog
  "Modal dialog. Focus moves to the title; Escape and the Cancel button close it."
  [{:keys [title]} & children]
  [:div.dialog-scrim
   [:div.dialog {:role "dialog" :aria-modal "true" :aria-labelledby "dialog-title"
                 :on {:keydown [[:admin/dialog-keydown [:event/key]]]}}
    [:h2#dialog-title {:tabindex "-1"} title]
    (into [:div] children)]])

;; ---------------------------------------------------------------------------
;; A1 to A3, A11

(defn- login-page [state]
  (let [f (forms state :login)]
    [:main#main.container {:tabindex "-1"}
     [:div.admin-card.login-card
      (layout/logo "logo-horizontal-light" "Zandy Touch" "admin-top__logo")
      [:h1 "Staff sign in"]
      [:form {:novalidate true :on {:submit [[:dom/prevent-default] [:admin/login]]}}
       (when-let [m (get-in f [:errors :form])] (ui/notice {:kind :error :live? true} m))
       (ui/text-field {:id "login-email" :label "Email" :type "email" :autocomplete "username" :value (get-in f [:values :email])
                       :on-input [:form/input :login :email [:event/target.value]]})
       (ui/text-field {:id "login-password" :label "Password" :type "password" :autocomplete "current-password" :value (get-in f [:values :password])
                       :on-input [:form/input :login :password [:event/target.value]]})
       (submit-btn (:status f) "Sign in" "Signing in")]
      [:p (ui/link "/admin/forgot-password" {:class "text-link"} "Forgot password?")]]]))

(defn- forgot-page [state]
  (let [f (forms state :forgot)]
    [:main#main.container {:tabindex "-1"}
     [:div.admin-card.login-card
      [:h1 "Forgot password"]
      (if (= :done (:status f))
        (ui/notice {:kind :success :live? true} "If that address belongs to the owner account, a reset link has been sent to it.")
        [:form {:novalidate true :on {:submit [[:dom/prevent-default] [:admin/forgot]]}}
         [:p "Enter the owner email. Support staff should ask the owner for a new temporary password."]
         (ui/text-field {:id "forgot-email" :label "Email" :type "email" :value (get-in f [:values :email]) :error (get-in f [:errors :email])
                         :on-input [:form/input :forgot :email [:event/target.value]]})
         (submit-btn (:status f) "Send reset link" "Sending")])
      [:p (ui/link "/admin/login" {:class "text-link"} "Back to sign in")]]]))

(defn- reset-page [state]
  (let [f (forms state :reset)]
    [:main#main.container {:tabindex "-1"}
     [:div.admin-card.login-card
      [:h1 "Choose a new password"]
      (if (= :done (:status f))
        [:div (ui/notice {:kind :success :live? true} "Your password has been changed.") (ui/link-button "/admin/login" {} "Sign in")]
        [:form {:novalidate true :on {:submit [[:dom/prevent-default] [:admin/reset]]}}
         (when-let [m (get-in f [:errors :form])] (ui/notice {:kind :error :live? true} m))
         (ui/text-field {:id "reset-password" :label "New password" :type "password" :autocomplete "new-password"
                         :hint "At least 10 characters." :value (get-in f [:values :password]) :error (get-in f [:errors :password])
                         :on-input [:form/input :reset :password [:event/target.value]]})
         (submit-btn (:status f) "Change password" "Saving")])]]))

(defn- account-page [state]
  (let [user (get-in state [:admin :session :user])
        f (forms state :password)]
    (shell state
           [:div
            (page-title state "My account")
            (when (:mustChangePassword user)
              (ui/notice {:kind :warning :live? true} "You are using a temporary password. Choose your own password to continue."))
            [:div.admin-card
             [:dl.dl [:div [:dt "Name"] [:dd (:name user)]] [:div [:dt "Email"] [:dd (:email user)]] [:div [:dt "Role"] [:dd (:role user)]]]]
            [:div.admin-card
             [:h2 "Change password"]
             (if (= :done (:status f))
               (ui/notice {:kind :success :live? true} "Your password has been changed.")
               [:form {:novalidate true :on {:submit [[:dom/prevent-default] [:admin/change-password]]}}
                (when-let [m (get-in f [:errors :form])] (ui/notice {:kind :error :live? true} m))
                (ui/text-field {:id "pw-current" :label "Current password" :type "password" :autocomplete "current-password"
                                :value (get-in f [:values :current]) :error (get-in f [:errors :current])
                                :on-input [:form/input :password :current [:event/target.value]]})
                (ui/text-field {:id "pw-new" :label "New password" :type "password" :autocomplete "new-password" :hint "At least 10 characters."
                                :value (get-in f [:values :new]) :error (get-in f [:errors :new])
                                :on-input [:form/input :password :new [:event/target.value]]})
                (submit-btn (:status f) "Change password" "Saving")])]])))

;; ---------------------------------------------------------------------------
;; A4 Orders list

(def order-tabs [[nil "All"] ["placed" "New"] ["confirmed" "Confirmed"] ["approvedForDelivery" "Out for delivery"] ["delivered" "Delivered"] ["cancelled" "Cancelled"]])

(defn- orders-page [state]
  (let [query (get-in state [:route :query])
        d (data state :orders)]
    (shell state
           [:div
            (page-title state "Orders")
            (with-data d
              (fn [{:keys [items counts] :as body}]
                [:div
                 [:ul.tabs
                  (for [[st label] order-tabs]
                    ^{:key (or st "all")}
                    [:li (ui/link (link-q "/admin" {:status st :q (:q query)})
                                  {:class "tab" :aria-current (when (= st (:status query)) "true")}
                                  label (when st [:span (str "(" (get counts (keyword st) 0) ")")]))])]
                 [:form.toolbar {:action "/admin" :method "get" :on {:submit [[:dom/prevent-default] [:admin/search "/admin" [:event/form-data]]]}}
                  (when (:status query) [:input {:type "hidden" :name "status" :value (:status query)}])
                  (ui/text-field {:id "order-q" :name "q" :label "Search by order number, name or phone" :value (:q query)
                                  :on-input [:form/noop]})
                  (ui/button {:type "submit" :variant :secondary} "Search")]
                 (if (empty? items)
                   (ui/empty-state {:title "No orders here" :text "Orders placed in the shop appear in this list."})
                   [:div.table-wrap
                    [:table.data-table
                     [:thead [:tr [:th "Order"] [:th "Placed"] [:th "Customer"] [:th "City"] [:th.num "Items"] [:th.num "Subtotal"] [:th.num "Fee"] [:th "Payment"] [:th "Status"]]]
                     [:tbody
                      (for [o items]
                        ^{:key (:id o)}
                        [:tr {:class (when (= "placed" (:status o)) "is-new")}
                         [:td {:data-label "Order"} (ui/link (str "/admin/orders/" (:id o)) {:class "row-link"} (:orderNumber o))]
                         [:td {:data-label "Placed"} (fmt/iso->date-time (:placedAt o))]
                         [:td {:data-label "Customer"} (:customerName o)]
                         [:td {:data-label "City"} (:city o)]
                         [:td.num {:data-label "Items"} (:itemCount o)]
                         [:td.num {:data-label "Subtotal"} (fmt/ugx (:itemsSubtotal o))]
                         [:td.num {:data-label "Fee"} (if (nil? (:deliveryFee o))
                                                         (if (#{"placed" "confirmed" "approvedForDelivery"} (:status o)) [:strong "Not set"] "None")
                                                         (fmt/ugx (:deliveryFee o)))]
                         [:td {:data-label "Payment"} (fmt/payment-label (:paymentMethod o))]
                         [:td {:data-label "Status"} (ui/order-status-pill (:status o))]])]]])
                 (pager "/admin" (select-keys query [:status :q]) body)]))])))

;; ---------------------------------------------------------------------------
;; A5 Order detail

(defn- order-dialog [state order]
  (let [{:keys [type target]} (:dialog (admin-ui state))
        f (forms state :dialog)
        v (:values f)
        errs (:errors f)
        sub (:itemsSubtotal order)
        total (when (some? (:deliveryFee order)) (+ sub (:deliveryFee order)))]
    (when type
      (dialog {:title (case target "confirmed" "Confirm order" "approvedForDelivery" "Approve delivery" "delivered" "Mark delivered" "cancelled" "Cancel order")}
              [:form {:novalidate true :on {:submit [[:dom/prevent-default] [:admin/status-change target]]}}
               (when-let [m (:form errs)] (ui/notice {:kind :error :live? true} m))
               (when total [:p (str "Order total: " (fmt/ugx total) " (items " (fmt/ugx sub) " plus delivery " (fmt/ugx (:deliveryFee order)) ").")])
               (case target
                 "delivered" (ui/radio-group {:name "paid" :legend "How did the customer pay?" :value (:paid v) :error (:paid errs)
                                              :on-change [:form/set :dialog :paid] :options [["cash" "Cash"] ["mobileMoney" "Mobile money"]]})
                 "cancelled" [:div
                              (if (= "approvedForDelivery" (:status order))
                                (ui/notice {:kind :warning} "Stock will not be returned automatically. Adjust stock if the goods come back.")
                                (ui/notice {:kind :info} "Stock for these items will be returned."))
                              (ui/textarea-input {:id "cancel-reason" :label "Reason (not shown to the customer)" :value (:reason v) :error (:reason errs)
                                                  :on-input [:form/input :dialog :reason [:event/target.value]]})]
                 nil)
               (ui/text-field {:id "status-note" :label "Note" :optional? true :value (:note v) :on-input [:form/input :dialog :note [:event/target.value]]})
               [:div.dialog__actions
                (ui/button {:variant :secondary :on-click [[:admin/dialog-close]]} "Back")
                (ui/button {:type "submit" :disabled? (= :submitting (:status f))} (if (= "cancelled" target) "Cancel order" "Confirm"))]]))))

(defn- order-page [state]
  (shell state
         (with-data (data state :order)
           (fn [{:keys [order agreementAcceptance history]}]
             (let [fee-form (forms state :fee)
                   fee-val (if (contains? (:values fee-form) :fee) (get-in fee-form [:values :fee]) (some-> (:deliveryFee order) str))
                   note-val (if (contains? (:values fee-form) :note) (get-in fee-form [:values :note]) (:staffNote order))
                   open? (#{"placed" "confirmed" "approvedForDelivery"} (:status order))
                   fee-set? (some? (:deliveryFee order))
                   st (:status order)
                   sub (:itemsSubtotal order)
                   fee-n (some-> fee-val str (str/replace "," "") parse-long)]
               [:div
                (ui/breadcrumb [["Orders" "/admin"] [(:orderNumber order) nil]])
                (page-title state (:orderNumber order) " " (ui/order-status-pill st))
                [:p (str "Placed " (fmt/iso->date-time (:placedAt order)))]
                [:div.admin-grid
                 [:div
                  [:section.admin-card {:aria-labelledby "cust-title"}
                   [:h2#cust-title "Customer and delivery"]
                   [:dl.dl
                    [:div [:dt "Name"] [:dd (get-in order [:customer :name])]]
                    [:div [:dt "Phone"] [:dd (ui/external-link (str "tel:" (get-in order [:customer :phone])) {} (fmt/phone-display (get-in order [:customer :phone])))
                                         " " (ui/external-link (ui/whatsapp-href (get-in order [:customer :phone]) (str "Hello, about your Zandy Touch order " (:orderNumber order)))
                                                               {:rel "noopener"} "WhatsApp")]]
                    (when (get-in order [:customer :email]) [:div [:dt "Email"] [:dd (get-in order [:customer :email])]])
                    [:div [:dt "Preferred contact"] [:dd (if (= "whatsapp" (get-in order [:customer :preferredContact])) "WhatsApp" "Phone call")]]
                    [:div [:dt "Address"] [:dd (str (get-in order [:delivery :addressLine]) ", " (get-in order [:delivery :area]) ", " (get-in order [:delivery :city]))]]
                    (when (get-in order [:delivery :directions]) [:div [:dt "Directions"] [:dd (get-in order [:delivery :directions])]])
                    [:div [:dt "Payment preference"] [:dd (fmt/payment-label (:paymentMethod order))]]
                    (when (:paymentMethodUsed order) [:div [:dt "Paid by"] [:dd (fmt/payment-label (:paymentMethodUsed order))]])
                    (when (:note order) [:div [:dt "Customer note"] [:dd (:note order)]])
                    (when (:cancelReason order) [:div [:dt "Cancel reason"] [:dd (:cancelReason order)]])]]
                  [:section.admin-card {:aria-labelledby "items-title"}
                   [:h2#items-title "Items"]
                   [:div.table-wrap
                    [:table.data-table
                     [:thead [:tr [:th "Product"] [:th.num "Unit price"] [:th.num "Qty"] [:th.num "Line total"]]]
                     [:tbody (for [[i l] (map-indexed vector (:items order))]
                               ^{:key i} [:tr [:td {:data-label "Product"} (:name l)] [:td.num {:data-label "Unit price"} (fmt/ugx (:unitPrice l))]
                                          [:td.num {:data-label "Qty"} (:quantity l)] [:td.num {:data-label "Line total"} (fmt/ugx (:lineTotal l))]])]]]
                   [:p (str "Subtotal: " (fmt/ugx sub))]]
                  [:section.admin-card {:aria-labelledby "hist-title"}
                   [:h2#hist-title "History"]
                   [:ol.timeline
                    (for [[i h] (map-indexed vector history)]
                      ^{:key i}
                      [:li.timeline__step.is-reached (ui/icon :check :sm)
                       [:span.timeline__label (str (fmt/staff-status-label (:status h)) (when (:by h) (str " by " (get-in h [:by :name]))))
                        (when (:note h) (str " - " (:note h)))]
                       [:span.timeline__time (fmt/iso->date-time (:at h))]])]]]
                 [:div
                  [:section.admin-card {:aria-labelledby "fee-title"}
                   [:h2 {:id "fee-title"} "Delivery fee and total"]
                   (if open?
                     [:form {:novalidate true :on {:submit [[:dom/prevent-default] [:admin/order-save]]}}
                      (ui/text-field {:id "order-fee" :label "Agreed delivery fee (UGX)" :inputmode "numeric" :value fee-val :error (get-in fee-form [:errors :fee])
                                      :hint "Required before you can confirm or dispatch. 0 means free."
                                      :on-input [:form/input :fee :fee [:event/target.value]]})
                      (ui/textarea-input {:id "order-staff-note" :label "Internal note" :optional? true :value note-val :maxlength 1000
                                          :on-input [:form/input :fee :note [:event/target.value]]})
                      [:p [:strong (str "Total: " (if fee-n (fmt/ugx (+ sub fee-n)) "enter the fee to see the total"))]]
                      (when (get-in fee-form [:errors :form]) (ui/notice {:kind :error :live? true} (get-in fee-form [:errors :form])))
                      (when (= :done (:status fee-form)) (ui/notice {:kind :success :live? true} "Saved."))
                      (ui/button {:type "submit" :disabled? (= :submitting (:status fee-form))} "Save")]
                     [:p (str "Fee: " (ugx (:deliveryFee order)) (when (:total order) (str ". Total: " (fmt/ugx (:total order)))))])]
                  [:section.admin-card {:aria-labelledby "act-title"}
                   [:h2#act-title "Actions"]
                   (when (and open? (not fee-set?) (#{"placed" "confirmed"} st))
                     [:p.field__hint "Record the delivery fee first."])
                   [:div.row-actions
                    (when (= "placed" st) (ui/button {:disabled? (not fee-set?) :on-click [[:admin/dialog {:type :status :target "confirmed"}]]} "Confirm order"))
                    (when (= "confirmed" st) (ui/button {:disabled? (not fee-set?) :on-click [[:admin/dialog {:type :status :target "approvedForDelivery"}]]} "Approve delivery"))
                    (when (= "approvedForDelivery" st) (ui/button {:on-click [[:admin/dialog {:type :status :target "delivered"}]]} "Mark delivered"))
                    (when open? (ui/button {:variant :secondary :on-click [[:admin/dialog {:type :status :target "cancelled"}]]} "Cancel order"))]
                   (when-not open? [:p "This order is closed."])]
                  [:section.admin-card {:aria-labelledby "agr-title"}
                   [:h2#agr-title "Agreement record"]
                   [:p (str "Order terms version " (:version agreementAcceptance) " accepted by " (:customerName agreementAcceptance) ", "
                            (:customerPhone agreementAcceptance) ", on " (fmt/iso->date-time (:acceptedAt agreementAcceptance)) ".")]
                   [:p.field__hint (:contentHash agreementAcceptance)]]]]
                (order-dialog state order)])))))

;; ---------------------------------------------------------------------------
;; A6 Products

(defn- restore-rejected-notice
  "Shown when the shop refused to publish a product being restored: the reasons,
  and the offer to restore it as a draft instead."
  [state]
  (when-let [{:keys [id name reasons]} (:restore-rejected (admin-ui state))]
    [:div#restore-rejected {:tabindex "-1"}
     (ui/notice {:kind :error :live? true}
                [:p [:strong (str (or name "This product") " cannot be published yet.")]]
                [:ul (for [[i r] (map-indexed vector reasons)] ^{:key i} [:li r])]
                [:p "Add what is missing on the product page, or restore it as a draft now and publish it later."]
                [:div.row-actions
                 (ui/button {:on-click [[:admin/restore id name "draft"]]} "Restore as draft instead")
                 (ui/link (str "/admin/products/" id) {:class ["button" "button--secondary"]} "Edit product")
                 (ui/button {:variant :secondary :on-click [[:admin/restore-dismiss]]} "Dismiss")])]))

(defn- restore-button [state p]
  (ui/button {:disabled? (= (:id p) (:restoring (admin-ui state)))
              :on-click [[:admin/restore (:id p) (:name p) "published"]]}
             (if (= (:id p) (:restoring (admin-ui state))) "Restoring" "Restore")))

(defn- stock-cell [p]
  (let [n (:stockQuantity p)]
    [:span
     [:span {:class (cond (zero? n) "stock-zero" (:lowStock p) "stock-low")} n] " "
     (cond (zero? n) (ui/badge :sold-out) (:lowStock p) (ui/badge :low-stock))]))

(defn- stock-dialog [state product-id current name]
  (let [f (forms state :dialog) v (:values f) errs (:errors f)]
    (dialog {:title (str "Adjust stock: " name)}
            [:form {:novalidate true :on {:submit [[:dom/prevent-default] [:admin/stock-submit product-id current]]}}
             [:p (str "Current count: " current)]
             (when-let [m (:form errs)] (ui/notice {:kind :error :live? true} m))
             (ui/radio-group {:name "stock-mode" :legend "What do you want to do?" :value (or (:mode v) "add")
                              :on-change [:form/set :dialog :mode]
                              :options [["add" "Add stock"] ["remove" "Remove stock"] ["set" "Set to counted number"]]})
             (ui/text-field {:id "stock-qty" :label (if (= "set" (:mode v)) "Counted number" "Quantity") :inputmode "numeric"
                             :value (:qty v) :error (:qty errs) :on-input [:form/input :dialog :qty [:event/target.value]]})
             (ui/select-input {:id "stock-reason" :label "Reason" :value (:reason v) :error (:reason errs) :placeholder "Choose a reason"
                               :on-change [:form/input :dialog :reason [:event/target.value]]
                               :options fmt/stock-reasons})
             (ui/text-field {:id "stock-note" :label "Note" :optional? true :value (:note v) :on-input [:form/input :dialog :note [:event/target.value]]})
             [:div.dialog__actions
              (ui/button {:variant :secondary :on-click [[:admin/dialog-close]]} "Cancel")
              (ui/button {:type "submit" :disabled? (= :submitting (:status f))} "Confirm")]])))

(defn- products-page [state]
  (let [query (get-in state [:route :query])
        d (data state :products)
        dlg (:dialog (admin-ui state))]
    (shell state
           [:div
            [:div.section__head (page-title state "Products") (ui/link-button "/admin/new-product" {} "New product")]
            (restore-rejected-notice state)
            (with-data d
              (fn [{:keys [items counts] :as body}]
                [:div
                 [:ul.tabs
                  [:li (ui/link (link-q "/admin/products" {:q (:q query)}) {:class "tab" :aria-current (when-not (or (:stock query) (:status query)) "true")} "All")]
                  [:li (ui/link (link-q "/admin/products" {:stock "out"}) {:class "tab" :aria-current (when (= "out" (:stock query)) "true")} (str "Out of stock (" (:out counts 0) ")"))]
                  [:li (ui/link (link-q "/admin/products" {:stock "low"}) {:class "tab" :aria-current (when (= "low" (:stock query)) "true")} (str "Low stock (" (:low counts 0) ")"))]
                  [:li (ui/link (link-q "/admin/products" {:status "archived"}) {:class "tab" :aria-current (when (= "archived" (:status query)) "true")} "Archived")]]
                 [:form.toolbar {:action "/admin/products" :method "get" :on {:submit [[:dom/prevent-default] [:admin/search "/admin/products" [:event/form-data]]]}}
                  (ui/text-field {:id "product-q" :name "q" :label "Search by name or SKU" :value (:q query) :on-input [:form/noop]})
                  (ui/button {:type "submit" :variant :secondary} "Search")]
                 (if (empty? items)
                   (ui/empty-state {:title "No products here"})
                   [:div.table-wrap
                    [:table.data-table
                     [:thead [:tr [:th "Photo"] [:th "Name"] [:th "SKU"] [:th "Category"] [:th.num "Price"] [:th "Stock"] [:th "Status"] [:th "Updated"] [:th "Actions"]]]
                     [:tbody
                      (for [p items]
                        ^{:key (:id p)}
                        [:tr
                         [:td {:data-label "Photo"} (when-let [u (get-in p [:image :urls :thumb])] [:img {:src u :alt "" :width 48 :height 60 :loading "lazy"}])]
                         [:td {:data-label "Name"} (ui/link (str "/admin/products/" (:id p)) {} (:name p))]
                         [:td {:data-label "SKU"} (:sku p)]
                         [:td {:data-label "Category"} (admin-state/category-name state (:categorySlug p))]
                         [:td.num {:data-label "Price"} (if (:price p) (fmt/ugx (:price p)) [:strong "No price"])]
                         [:td {:data-label "Stock"} (stock-cell p)]
                         [:td {:data-label "Status"} (ui/pill (case (:status p) "published" :success "archived" :muted :info) (str/capitalize (:status p)))]
                         [:td {:data-label "Updated"} (str (fmt/iso->date (:updatedAt p)) (when (:updatedBy p) (str " by " (get-in p [:updatedBy :name]))))]
                         [:td {:data-label "Actions"}
                          [:div.row-actions
                           (ui/link (str "/admin/products/" (:id p)) {:class ["button" "button--secondary"]} "Edit")
                           (if (= "archived" (:status p))
                             (restore-button state p)
                             (list
                              (ui/button {:variant :secondary :on-click [[:admin/dialog {:type :stock :id (:id p) :current (:stockQuantity p) :name (:name p) :defaults {:mode "add"}}]]} "Adjust stock")
                              (ui/button {:variant :secondary :on-click [[:admin/dialog {:type :archive :id (:id p) :name (:name p)}]]} "Remove")))]]])]]])
                 (pager "/admin/products" (select-keys query [:status :stock :q]) body)]))
            (case (:type dlg)
              :stock (stock-dialog state (:id dlg) (:current dlg) (:name dlg))
              :archive (dialog {:title (str "Remove " (:name dlg) "?")}
                               [:p "This archives the product. It leaves the shop, search and sitemap. Orders and stock history are kept. You can bring it back later from the Archived tab with Restore."]
                               [:div.dialog__actions (ui/button {:variant :secondary :on-click [[:admin/dialog-close]]} "Cancel")
                                (ui/button {:on-click [[:admin/archive (:id dlg)]]} "Archive product")])
              nil)])))

;; ---------------------------------------------------------------------------
;; A7 / A8 Product form

(defn- picture-section
  "A8: the ProductImageManager. A7: pictures need the product to exist, so the
  section says so and appears in full after the first Save."
  [state product-id]
  (if (nil? product-id)
    [:section#pictures.admin-card {:tabindex "-1" :aria-labelledby "pictures-title"}
     [:h2#pictures-title "Pictures"]
     [:p "Save the product first, then add its pictures here. A product needs at least one picture before it can be published."]]
    (let [pi (forms state :product-images)]
      [:section#pictures.admin-card {:tabindex "-1"}
       (widgets/product-image-manager
        {:images (get-in state [:forms :product :values :images])
         :saved-images (get-in (data state :product) [:body :images])
         :error (get-in state [:forms :product :errors :images])
         :upload {:status (:status pi) :items (:items pi)}
         :save (:save pi)})])))

(defn- product-form [state product-id]
  (let [f (forms state :product)
        v (:values f)
        errs (:errors f)
        in (fn [k] [:form/input :product k [:event/target.value]])
        ;; names from GET /api/categories; the fixed six until that has loaded
        cats (if-let [loaded (seq (admin-state/categories state))]
               (mapv (fn [c] [(:slug c) (or (not-empty (:name c)) (:slug c))]) loaded)
               ui/nav-categories)
        archived? (= "archived" (:status v))]
    [:form {:novalidate true :on {:submit [[:dom/prevent-default] [:admin/product-save product-id]]}}
     (ui/error-summary (vec (for [[k id] [[:name "pf-name"] [:brand "pf-brand"] [:category "pf-category"] [:price "pf-price"] [:sku "pf-sku"] [:images "pictures"]] :let [m (get errs k)] :when m] {:id id :message m})))
     (when-let [m (:form errs)] (ui/notice {:kind :error :live? true} m))
     [:section.admin-card
      [:h2 "Basics"]
      (ui/text-field {:id "pf-name" :label "Name" :value (:name v) :error (:name errs) :on-input (in :name)})
      (ui/text-field {:id "pf-brand" :label "Brand" :value (:brand v) :error (:brand errs) :on-input (in :brand)})
      (ui/checkbox {:id "pf-own" :label "Zandy Touch own brand" :checked? (:own-brand? v) :on-change [:form/input :product :own-brand? [:event/target.checked]]})
      (ui/select-input {:id "pf-category" :label "Category" :value (:category v) :error (:category errs) :placeholder "Choose a category"
                        :on-change (in :category) :options cats})
      (ui/text-field {:id "pf-sku" :label "SKU" :optional? true :value (:sku v) :error (:sku errs) :on-input (in :sku)})]
     [:section.admin-card
      [:h2 "Price"]
      (ui/text-field {:id "pf-price" :label "Price (UGX)" :inputmode "numeric" :value (:price v) :error (:price errs)
                      :hint "A product needs a price before it can be published." :on-input (in :price)})]
     (when (nil? product-id)
       [:section.admin-card
        [:h2 "Stock"]
        (ui/text-field {:id "pf-stock" :label "Opening stock" :inputmode "numeric" :value (:stock v)
                        :hint "Orders reduce this number. At 0 the product shows as sold out." :on-input (in :stock)})])
     [:section.admin-card
      [:h2 "Descriptions"]
      (ui/text-field {:id "pf-short" :label "Short description" :optional? true :maxlength 200 :value (:short v) :on-input (in :short)})
      (ui/textarea-input {:id "pf-desc" :label "Description" :optional? true :rows 6 :maxlength 4000 :value (:description v) :on-input (in :description)})
      (ui/text-field {:id "pf-size" :label "Size" :optional? true :value (:size v) :on-input (in :size)})
      (ui/textarea-input {:id "pf-how" :label "How to use" :optional? true :value (:how-to-use v) :on-input (in :how-to-use)})
      (ui/textarea-input {:id "pf-ing" :label "Ingredients" :optional? true :value (:ingredients v) :on-input (in :ingredients)})]
     (picture-section state product-id)
     [:section.admin-card
      [:h2 "Publishing"]
      (if archived?
        ;; Draft or Published would both be wrong here: the product is archived
        [:div.field
         [:p.field__label "Status"]
         [:p (ui/pill :muted "Archived") " Removed from the shop. Use Restore at the top of this page to bring it back."]]
        (ui/radio-group {:name "pf-status" :legend "Status" :value (:status v) :on-change [:form/set :product :status]
                         :options [["draft" "Draft (not visible to customers)"] ["published" "Published"]]}))
      (ui/checkbox {:id "pf-featured" :label "Featured on the home page" :checked? (:featured? v) :on-change [:form/input :product :featured? [:event/target.checked]]})]
     (when (= :done (:status f)) (ui/notice {:kind :success :live? true} "Saved."))
     [:div.row-actions
      (ui/button {:type "submit" :variant (when archived? :secondary) :disabled? (= :submitting (:status f))} (if (= :submitting (:status f)) "Saving" "Save"))
      (when (and product-id (= "published" (:status v)))
        (ui/link-button (str "/products/" (get-in state [:admin :data :product :body :slug])) {:variant :secondary} "View on shop"))]]))

(defn- stock-panel [state p]
  (let [movements (get-in (data state :movements) [:body :items])
        dlg (:dialog (admin-ui state))]
    [:section.admin-card {:aria-labelledby "stock-title"}
     [:h2#stock-title (ui/icon :stock :sm) " Stock"]
     [:p [:strong {:class (cond (zero? (:stockQuantity p)) "stock-zero" (:lowStock p) "stock-low")} (:stockQuantity p)] " " (cond (zero? (:stockQuantity p)) (ui/badge :sold-out) (:lowStock p) (ui/badge :low-stock))]
     (ui/button {:variant :secondary :on-click [[:admin/dialog {:type :stock :id (:id p) :current (:stockQuantity p) :name (:name p) :defaults {:mode "add"}}]]} "Adjust stock")
     [:h3 (ui/icon :history :sm) " Recent movements"]
     (widgets/stock-movement-list movements)
     (when (= :stock (:type dlg)) (stock-dialog state (:id dlg) (:current dlg) (:name dlg)))]))

(defn- new-product-page [state]
  (shell state
         [:div
          (ui/breadcrumb [["Products" "/admin/products"] ["New product" nil]])
          (page-title state "New product")
          (product-form state nil)]))

(defn- archived-notice
  "A8 for an archived product: it says so first, and Restore is the main action."
  [state p]
  [:div
   (ui/notice {:kind :warning :id "archived-notice"}
              [:p [:strong "This product is removed from the shop (archived)."]]
              [:p "Customers cannot see it, search finds nothing and it is out of the sitemap. Orders and stock history are kept."]
              (restore-button state p))
   (restore-rejected-notice state)])

(defn- edit-product-page [state]
  (shell state
         (with-data (data state :product)
           (fn [p]
             (let [archived? (= "archived" (:status p))]
               [:div
                (ui/breadcrumb [["Products" "/admin/products"] [(:name p) nil]])
                (page-title state (:name p) (when archived? [:span " " (ui/pill :muted "Archived")]))
                (when archived? (archived-notice state p))
                [:p.field__hint (str "Last updated " (fmt/iso->date-time (:updatedAt p)) (when (:updatedBy p) (str " by " (get-in p [:updatedBy :name]))))]
                (stock-panel state p)
                (product-form state (:id p))
                (when-not archived?
                  [:section.admin-card
                   [:h2 "Remove product"]
                   [:p "Removing archives the product. Orders and stock history are kept."]
                   (ui/button {:variant :secondary :on-click [[:admin/dialog {:type :archive :id (:id p) :name (:name p)}]]} "Remove from shop")
                   (when (= :archive (:type (:dialog (admin-ui state))))
                     (let [dlg (:dialog (admin-ui state))]
                       (dialog {:title (str "Remove " (:name dlg) "?")}
                               [:p "This archives the product. It leaves the shop, search and sitemap."]
                               [:div.dialog__actions (ui/button {:variant :secondary :on-click [[:admin/dialog-close]]} "Cancel")
                                (ui/button {:on-click [[:admin/archive (:id dlg)]]} "Archive product")])))])])))))

;; ---------------------------------------------------------------------------
;; ---------------------------------------------------------------------------
;; A9 Bulk product import

(defn- kilobytes [n] (str (max 1 (long (Math/ceil (/ n 1024)))) " KB"))

(defn- csv-step [state]
  (let [f (forms state :import-csv)
        {:keys [dry real]} f
        report (or (:report real) (:report dry))
        real? (some? (:report real))
        running? (or (= :running (:status dry)) (= :running (:status real)))
        file-errors (seq (concat (:errors dry) (:errors real)))
        field-error (:error f)
        described (str/join " " (cond-> [(ui/field-hint-id "import-csv")] field-error (conj (ui/field-error-id "import-csv"))))]
    [:section.admin-card {:aria-labelledby "import-step1"}
     [:h2#import-step1 "Step 1: the spreadsheet"]
     [:p "Fill in one row per product in Excel or Google Sheets and save the file as CSV UTF-8. New products are created as drafts. A row whose SKU already exists updates that product. An import never changes the stock of an existing product; use Adjust stock for that."]
     [:p (ui/external-link "/api/admin/product-import-template" {:class ["button" "button--secondary"] :download "zandy-touch-products.csv"}
                           "Download the template")]
     [:details
      [:summary "Columns in the file"]
      [:p [:code "sku"] " (required, the key), " [:code "name"] ", " [:code "brand"] ", " [:code "ownBrand"] " (yes or no), "
       [:code "category"] " (the category name, for example Body care), " [:code "price"] " (whole UGX, empty for a draft), "
       [:code "shortDescription"] ", " [:code "description"] ", " [:code "size"] ", " [:code "howToUse"] ", "
       [:code "ingredients"] ", " [:code "featured"] " (yes or no) and " [:code "openingStock"] " (new products only). "
       "A new product needs a name, brand and category."]]
     (ui/field-wrap {:id "import-csv" :label "Your CSV file" :hint "CSV, up to 500 rows and 1 MB." :error field-error}
                    [:input.input {:id "import-csv" :type "file" :accept ".csv,text/csv" :disabled running?
                                   :aria-describedby described :aria-invalid (when field-error "true")
                                   :on {:change [[:admin/csv-picked [:event/files]]]}}])
     (when-let [file (:file f)]
       [:p "Selected: " [:strong (:name file)] (str " (" (kilobytes (:size file)) ")")])
     [:div.row-actions
      (ui/button {:variant :secondary :disabled? (or (nil? (:file f)) running?) :on-click [[:admin/csv-check :dry]]}
                 (if (= :running (:status dry)) "Checking the file" "Check the file"))
      (ui/button {:disabled? (not (admin-state/can-import? f)) :on-click [[:admin/csv-check :real]]}
                 (if (= :running (:status real)) "Importing" "Import the file"))]
     (when (and (:file f) (not real?) (not (admin-state/can-import? f)))
       [:p.field__hint "Check the file first. Importing is available once the check finds rows to apply."])
     (when file-errors
       (ui/notice {:kind :error :live? true}
                  [:p "The file could not be imported. Nothing was changed."]
                  [:ul (for [[i line] (map-indexed vector file-errors)] ^{:key i} [:li line])]))
     (when real?
       (ui/notice {:kind :success :live? true} "Import finished. Add the pictures in step 2, then publish in step 3."))
     (when report
       (widgets/import-result-list
        {:heading (if real? "Import result" "Check result: nothing has been changed yet")
         :summary (widgets/summary-text (:summary report) (not real?))
         :items (widgets/csv-row-items (:rows report) (not real?))}))]))

(defn- pictures-step [state]
  (let [f (forms state :import-pictures)
        {:keys [queue results index total]} f
        uploading? (= :uploading (:status f))
        done? (= :done (:status f))
        batches (if (pos? (or total 0)) (long (Math/ceil (/ total admin-state/batch-size))) 0)
        sent (min (or total 0) (* (or index 0) admin-state/batch-size))
        attached (count (filter #(= "attached" (:status %)) results))
        retryable (count (filter :retry-file results))]
    [:section.admin-card {:aria-labelledby "import-step2"}
     [:h2#import-step2 "Step 2: the pictures"]
     [:p "Name each picture after its product's SKU, with an optional -1 to -6 for the order, for example "
      [:code "ZT-SKIN-001-1.jpg"] " and " [:code "ZT-SKIN-001-2.jpg"] ". A picture with a position replaces the one at that position. "
      "A picture whose SKU matches no product is rejected and not kept. A product can have up to 6 pictures."]
     (widgets/upload-dropzone {:id "import-pictures" :label "Choose the product pictures"
                               :hint "JPEG, PNG or WebP, up to 5 MB each. Any number of files; they are sent 20 at a time."
                               :accept "image/jpeg,image/png,image/webp" :multiple? true :busy? uploading?
                               :on-files [:admin/pictures-picked]})
     (when (and (seq queue) (not uploading?))
       [:div.row-actions
        [:span (str (count queue) (if (= 1 (count queue)) " picture is" " pictures are") " ready to upload.")]
        (ui/button {:on-click [[:admin/pictures-upload]]} (str "Upload " (count queue) (if (= 1 (count queue)) " picture" " pictures")))])
     (when uploading?
       (widgets/progress-bar {:id "import-progress" :value (or index 0) :max batches
                              :label (str "Uploading batch " (min (inc (or index 0)) batches) " of " batches
                                          " (" sent " of " total " pictures sent)")}))
     (when done?
       (ui/notice {:kind (if (= attached (count results)) :success :warning) :live? true}
                  (str "Upload finished: " attached " attached, " (- (count results) attached) " not attached.")))
     (when (seq results)
       (widgets/import-result-list {:heading "Results per picture" :items (widgets/picture-items results)}))
     (when (or (pos? retryable) (seq results))
       [:div.row-actions
        (when (pos? retryable)
          (ui/button {:on-click [[:admin/pictures-retry]]} (str "Try again for " retryable (if (= 1 retryable) " picture" " pictures") " that did not upload")))
        (when (and (seq results) (not uploading?))
          (ui/button {:variant :secondary :on-click [[:admin/pictures-clear]]} "Clear results"))])]))

(defn- publish-outcome [{:keys [status error results]}]
  (case status
    :error (ui/notice {:kind :error :live? true} error)
    :done (let [ok (filter #(= "published" (:status %)) results)
                bad (remove #(= "published" (:status %)) results)]
            (ui/notice {:kind (if (seq bad) :warning :success) :live? true}
                       [:p (str (count ok) (if (= 1 (count ok)) " product published." " products published."))]
                       (when (seq bad)
                         [:div [:p "These stay drafts:"]
                          [:ul (for [r bad]
                                 ^{:key (:productId r)}
                                 [:li (str (or (:name r) "A product") ": "
                                           (str/join ", " (for [e (:errors r)] (get widgets/code-text (:code e) (:code e)))))])]])))
    nil))

(defn- drafts-step [state]
  (let [review (forms state :import-review)
        imported? (seq (:imported-ids review))
        all? (= :all (:scope review))]
    [:section.admin-card {:aria-labelledby "import-step3"}
     [:h2#import-step3 "Step 3: review and publish"]
     [:p "Drafts are invisible to customers. A draft can be published when it has a price, at least one picture and a category."]
     (with-data (data state :drafts)
       (fn [_]
         (let [items (admin-state/drafts-shown state)
               complete (filter (comp empty? admin-state/draft-lacks) items)
               running? (= :running (get-in review [:publish :status]))]
           [:div
            (when imported?
              [:p (if all? "Showing every draft in the shop. " "Showing the products from this import. ")
               (ui/button {:variant :secondary :on-click [[:admin/drafts-scope (if all? :imported :all)]]}
                          (if all? "Show only this import" "Show all drafts"))])
            (if (empty? items)
              (ui/empty-state {:title "No drafts to review" :text "Imported products appear here until they are published."})
              [:div.table-wrap
               [:table.data-table
                [:thead [:tr [:th "Picture"] [:th "Name"] [:th "SKU"] [:th "Category"] [:th "Still needs"] [:th "Fix"]]]
                [:tbody
                 (for [p items]
                   (let [lacks (admin-state/draft-lacks p)]
                     ^{:key (:id p)}
                     [:tr
                      [:td {:data-label "Picture"} (when-let [u (get-in p [:image :urls :thumb])] [:img {:src u :alt "" :width 48 :height 60 :loading "lazy"}])]
                      [:td {:data-label "Name"} (:name p)]
                      [:td {:data-label "SKU"} (:sku p)]
                      [:td {:data-label "Category"} (if (str/blank? (:categorySlug p)) "None" (admin-state/category-name state (:categorySlug p)))]
                      [:td {:data-label "Still needs"}
                       (if (empty? lacks)
                         (ui/pill :success "Ready to publish")
                         [:span.pill-row (for [l lacks] ^{:key l} (ui/pill :warning l))])]
                      [:td {:data-label "Fix"}
                       (when (seq lacks)
                         (ui/link (str "/admin/products/" (:id p)) {:class ["button" "button--secondary"]}
                                  "Edit product" (ui/visually-hidden (str ": " (:name p)))))]]))]]])
            (publish-outcome (:publish review))
            [:div.row-actions
             (ui/button {:disabled? (or (empty? complete) running?) :on-click [[:admin/publish-complete]]}
                        (if running? "Publishing" (str "Publish all complete drafts (" (count complete) ")")))
             (ui/link-button "/admin/products" {:variant :secondary} "Go to products")]])))]))

(defn- import-page [state]
  (shell state
         [:div
          (page-title state "Import products")
          [:p "Add or update many products at once, with their pictures. Work through the three steps; you can leave and come back, and drafts stay in the shop until you publish them."]
          (csv-step state)
          (pictures-step state)
          (drafts-step state)]))

;; ---------------------------------------------------------------------------
;; A10 Inbox

(defn- inbox-page [state]
  (let [tab (or (get-in state [:route :query :tab]) "messages")]
    (shell state
           [:div
            (page-title state "Messages and subscribers")
            [:ul.tabs
             [:li (ui/link "/admin/inbox" {:class "tab" :aria-current (when (= "messages" tab) "true")} "Messages")]
             [:li (ui/link "/admin/inbox?tab=subscribers" {:class "tab" :aria-current (when (= "subscribers" tab) "true")} "Subscribers")]]
            (if (= "messages" tab)
              (with-data (data state :messages)
                (fn [{:keys [items]}]
                  (if (empty? items) (ui/empty-state {:title "No messages yet"})
                      [:ul (for [m items]
                             ^{:key (:id m)}
                             [:li.admin-card [:h2 (:name m)]
                              [:p.field__hint (str (fmt/iso->date-time (:receivedAt m)) " " (or (:phone m) "") " " (or (:email m) ""))]
                              [:p (:message m)]])])))
              [:div
               [:p (ui/external-link "/api/admin/subscribers?format=csv" {:class ["button" "button--secondary"] :download "subscribers.csv"} (ui/icon :upload :sm) "Export CSV")]
               (with-data (data state :subscribers)
                 (fn [{:keys [items]}]
                   (if (empty? items) (ui/empty-state {:title "No subscribers yet"})
                       [:div.table-wrap
                        [:table.data-table
                         [:thead [:tr [:th "Contact"] [:th "Source"] [:th "Subscribed"] [:th "Active"]]]
                         [:tbody (for [s items]
                                   ^{:key (:id s)} [:tr [:td {:data-label "Contact"} (or (:email s) (:phone s))] [:td {:data-label "Source"} (:source s)]
                                                    [:td {:data-label "Subscribed"} (fmt/iso->date (:subscribedAt s))] [:td {:data-label "Active"} (if (:active s) "Yes" "No")]])]]])))])])))

;; ---------------------------------------------------------------------------
;; A12 Staff

(defn- staff-page [state]
  (let [f (forms state :staff)
        tp (:temp-password (admin-ui state))
        dlg (:dialog (admin-ui state))]
    (shell state
           (if (:forbidden (get-in state [:admin :data]))
             (ui/notice {:kind :error} "Only the owner can manage staff accounts.")
             [:div
              (page-title state "Staff accounts")
              (when tp
                (ui/notice {:kind :warning :live? true}
                           [:p (str "Temporary password for " (:for tp) ". It is shown only once. Pass it on in person or by WhatsApp; it is never emailed.")]
                           [:p [:strong#temp-password (or (:value tp) "Not available (this was a repeated request)")]]
                           (ui/button {:variant :secondary :on-click [[:admin/dismiss-temp]]} "I have noted it")))
              (with-data (data state :staff)
                (fn [{:keys [items]}]
                  [:div.table-wrap
                   [:table.data-table
                    [:thead [:tr [:th "Name"] [:th "Email"] [:th "Role"] [:th "Active"] [:th "Last sign-in"] [:th "Actions"]]]
                    [:tbody
                     (for [u items]
                       ^{:key (:id u)}
                       [:tr [:td {:data-label "Name"} (:name u)] [:td {:data-label "Email"} (:email u)] [:td {:data-label "Role"} (:role u)]
                        [:td {:data-label "Active"} (if (:active u) "Yes" "No")] [:td {:data-label "Last sign-in"} (or (fmt/iso->date-time (:lastSignInAt u)) "Never")]
                        [:td {:data-label "Actions"}
                         (when (and (= "support" (:role u)) (:active u))
                           [:div.row-actions
                            (ui/button {:variant :secondary :on-click [[:admin/dialog {:type :staff-reset :id (:id u) :name (:name u)}]]} "Reset password")
                            (ui/button {:variant :secondary :on-click [[:admin/dialog {:type :staff-deactivate :id (:id u) :name (:name u)}]]} "Deactivate")])]])]]]))
              [:section.admin-card {:aria-labelledby "add-title"}
               [:h2 {:id "add-title"} "Add a support employee"]
               [:form {:novalidate true :on {:submit [[:dom/prevent-default] [:admin/staff-add]]}}
                (when-let [m (get-in f [:errors :form])] (ui/notice {:kind :error :live? true} m))
                (ui/text-field {:id "staff-name" :label "Name" :value (get-in f [:values :name]) :error (get-in f [:errors :name]) :on-input [:form/input :staff :name [:event/target.value]]})
                (ui/text-field {:id "staff-email" :label "Email" :type "email" :value (get-in f [:values :email]) :error (get-in f [:errors :email]) :on-input [:form/input :staff :email [:event/target.value]]})
                (ui/checkbox {:id "staff-notify" :label "Email me new-order alerts" :checked? (get-in f [:values :notify?]) :on-change [:form/input :staff :notify? [:event/target.checked]]})
                (submit-btn (:status f) "Add employee" "Adding")]]
              (case (:type dlg)
                :staff-reset (dialog {:title (str "Reset password for " (:name dlg) "?")}
                                     [:p "A new temporary password is issued and their sessions end."]
                                     [:div.dialog__actions (ui/button {:variant :secondary :on-click [[:admin/dialog-close]]} "Cancel")
                                      (ui/button {:on-click [[:admin/staff-reset (:id dlg) (:name dlg)]]} "Reset password")])
                :staff-deactivate (dialog {:title (str "Deactivate " (:name dlg) "?")}
                                          [:p "Their sessions end immediately. Their name stays on past history."]
                                          [:div.dialog__actions (ui/button {:variant :secondary :on-click [[:admin/dialog-close]]} "Cancel")
                                           (ui/button {:on-click [[:admin/staff-deactivate (:id dlg)]]} "Deactivate")])
                nil)]))))

;; ---------------------------------------------------------------------------
;; A13 Help

(defn- help-page [state]
  (let [role (admin-state/role state)]
    (shell state
           (case (get-in state [:route :name])
             :admin-help (help/list-view role)
             :admin-help-topic (help/topic-view role (get-in state [:route :params :topic]))))))

;; ---------------------------------------------------------------------------

(defn view [state]
  (let [session (get-in state [:admin :session])
        route-name (get-in state [:route :name])
        ]
    (cond
      (= :admin-login route-name) (login-page state)
      (= :admin-forgot-password route-name) (forgot-page state)
      (= :admin-reset-password route-name) (reset-page state)
      (nil? session) [:main#main.container {:tabindex "-1"} (ui/spinner-text "Checking your session")]
      :else
      (case route-name
        :admin-orders (orders-page state)
        :admin-order (order-page state)
        :admin-products (products-page state)
        :admin-new-product (new-product-page state)
        :admin-product (edit-product-page state)
        :admin-product-import (import-page state)
        :admin-inbox (inbox-page state)
        :admin-account (account-page state)
        :admin-staff (staff-page state)
        (:admin-help :admin-help-topic) (help-page state)
        [:main#main.container (ui/spinner-text "Loading")]))))
