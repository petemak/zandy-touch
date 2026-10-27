(ns zandy.frontend.state
  "The single application state and its pure action handlers (architecture.md
  section 2.4, `frontend.state`). Browser only (ClojureScript). Views
  (shared.pages, frontend.admin.core) are pure functions of the state atom
  `!state`; their event handlers are data, vectors of actions such as
  [:cart/add product 2]. `handle` takes the state and one action and returns
  {:state new-state :fx [effects]}; effects are data too and are run by
  frontend.effects. Nothing here touches the DOM, the network or storage, so
  every handler can be unit-tested. The admin namespace adds its own :admin/*
  methods to the same multimethod.

  State keys: :route (matched route), :site (site-info), :page {:status
  :data}, :cart, :cart-check {:status :result}, :forms {form-id {:values
  :errors :status :key}}, :refs (delivery areas and policies), :ui (UI-only
  flags such as :announcement, :search-open?, :gallery, :product,
  :cart-notice), :last-order, :order-key (idempotency key of the order being
  placed), :pending-url, and :admin {:session :data :ui :categories
  :help-title}."
  (:require [clojure.string :as str]
            [zandy.shared.cart :as cart]
            [zandy.shared.format :as fmt]
            [zandy.shared.pages :as pages]
            [zandy.shared.routes :as routes]))

(defonce ^{:doc "The application state atom. Holds nil until frontend.main/init
  sets it from the page the server embedded. Every change re-renders the whole
  tree."}
  !state
  (atom nil))

(defn initial-state
  "Fresh state for the browser: the shared skeleton
  (shared.pages/initial-state) plus no route, no site, a :loading page, no
  last order and an empty :admin map. Pure."
  []
  (merge (pages/initial-state)
         {:route nil :site nil :page {:status :loading} :last-order nil
          :admin {:session nil :data {} :ui {}}}))

(defmulti handle
  "Multimethod dispatching on the action name (first element of the action
  vector). `(handle state action)` returns {:state new-state :fx [effects]};
  it never has side effects itself. Unknown actions log an error effect
  instead of throwing. Methods are grouped below by area (navigation, product,
  cart, forms, checkout) and admin methods are defined in
  frontend.admin.state."
  (fn [_state [action-name & _]] action-name))

;; Any action nobody handles: state unchanged, logs the unknown action name to
;; the console (:fx/log-error).
(defmethod handle :default [state action]
  {:state state :fx [[:fx/log-error (str "Unknown action " (pr-str (first action)))]]})

(defn ret
  "Builds a handler result. `(ret state)` has no effects; `(ret state fx)`
  takes a seq of effects and drops nils, so handlers can write conditional
  effects with `when`. Returns {:state :fx}."
  ([state] {:state state :fx []})
  ([state fx] {:state state :fx (vec (remove nil? fx))}))

;; ---------------------------------------------------------------------------
;; Navigation and page loading

;; Product pages are served from the CDN cache for up to about 90 seconds
;; (architecture.md D2), so the browser re-checks the product (without cache)
;; as soon as it is shown. Stock or price changes then appear before the
;; customer adds to the cart.
(defn route-enter
  "Effects to run when a route has become current and its page data is there.
  Argument: state with :route. Always updates the head (:fx/head). Adds: a
  site-info request when the site is unknown; on product pages a no-cache
  re-fetch of the product (stock and price may have changed since the cached
  HTML); on cart and checkout a cart validation; on checkout the delivery
  areas and the order terms; on admin routes [:admin/enter]. Returns a vector
  of effects."
  [state]
  (let [{route-name :name :keys [params]} (:route state)]
    (cond-> [[:fx/head]]
      (nil? (:site state)) (conj [:fx/request {:path "/api/site-info"} [:site/loaded]])
      (= :product route-name) (conj [:fx/request {:path (str "/api/products/" (:slug params)) :no-cache? true}
                                     [:product/rechecked (:slug params)]])
      (#{:cart :checkout} route-name) (conj [:fx/dispatch [:cart/validate]])
      ;; Empty-cart redirect: someone who opens /checkout with nothing in the
      ;; cart (a bookmark, the back button after an order) is sent to the cart
      ;; with a history replace, so Back does not return to an empty checkout.
      ;; The cart validation and the checkout data requests that were queued
      ;; above still run harmlessly; they could be skipped.
      ;; nothing to check out: go to the cart (Journey 1 side path)
      (and (= :checkout route-name) (empty? (get-in state [:cart :items])))
      (conj [:fx/dispatch [:nav/replace "/cart"]])
      (= :checkout route-name) (into [[:fx/request {:path "/api/delivery-areas"} [:refs/areas-loaded]]
                                      [:fx/dispatch [:agreement/load]]])
      (:admin? (:data (:route state))) (conj [:fx/dispatch [:admin/enter]]))))

;; Per-page UI (quantity, gallery position, open search, announcement) must
;; not leak from one page to the next.
(defn- reset-page-ui [state]
  (-> state
      (assoc-in [:ui :product] {:qty 1})
      (assoc-in [:ui :gallery] {:index 0})
      (assoc-in [:ui :search-open?] false)
      (assoc-in [:ui :announcement] nil)))

(defn start-route
  "Makes `route` current. Arguments: state, `route` (from routes/match-url, nil
  for unknown), options {:push? (push history, default true) :scroll? (scroll
  to top, default true)} and the `url`. Resets per-page UI state, drops the
  cart notice outside the cart page, and returns {:state :fx}: for a route
  with API requests the page becomes :loading and :fx/page-load is emitted;
  otherwise the page is :ok at once and the route-enter effects run; an
  unknown route becomes the not-found page."
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

;; [:nav/go url] Client navigation to `url` (pushes history, scrolls to top).
;; Public routes with API data go to :loading and emit :fx/page-load; shell
;; routes render at once and run the route-enter effects. Unknown paths become
;; the P16 not-found page.
(defmethod handle :nav/go [state [_ url]]
  (start-route state (routes/match-url url) {} url))

;; [:nav/link url] Same as :nav/go; dispatched by every internal link click
;; (ui/link). main/run-action! lets modified clicks through to the browser
;; first.
(defmethod handle :nav/link [state [_ url]]
  (start-route state (routes/match-url url) {} url))

;; [:nav/popstate url] Back/forward button: shows the route for `url` without
;; pushing history or scrolling.
(defmethod handle :nav/popstate [state [_ url]]
  (start-route state (routes/match-url url) {:push? false :scroll? false} url))

;; [:nav/replace url] Like :nav/go but replaces the current history entry
;; (used for redirects such as the empty checkout to the cart, and for the
;; first load of a client-rendered page).
(defmethod handle :nav/replace [state [_ url]]
  (let [r (start-route state (routes/match-url url) {:push? false} url)]
    (update r :fx #(into [[:fx/replace-url url]] %))))

;; [:page/loaded url results] All page requests of a navigation are back. A
;; result for an old `url` is ignored (the user navigated again meanwhile).
;; Otherwise assembles the page with shared.pages/assemble and runs the
;; route-enter effects, or only updates the head for 404/503 pages.
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

;; [:site/loaded response] Stores GET /api/site-info when the page came
;; without it; ignores failures (header and footer then use defaults).
(defmethod handle :site/loaded [state [_ {:keys [status body]}]]
  (if (= 200 status) (ret (assoc state :site body)) (ret state)))

;; [:refs/areas-loaded response] Stores GET /api/delivery-areas for the
;; checkout city select.
(defmethod handle :refs/areas-loaded [state [_ {:keys [status body]}]]
  (if (= 200 status) (ret (assoc-in state [:refs :delivery-areas] body)) (ret state)))

;; [:ui/search-toggled open?] Remembers whether the header search disclosure
;; is open, so a re-render does not close it.
(defmethod handle :ui/search-toggled [state [_ open?]]
  (ret (assoc-in state [:ui :search-open?] (boolean open?))))

;; [:search/submit form-data] Header or not-found search. A term under 2
;; characters only announces a hint for screen readers; otherwise navigates to
;; /shop?q=...
(defmethod handle :search/submit [state [_ form-data]]
  (let [q (:q (pages/normalise-query {:q (get form-data "q")}))]
    (if q
      (handle state [:nav/go (routes/path-for :shop nil {:q q})])
      (ret (assoc-in state [:ui :announcement] "Please type at least 2 characters to search.")))))

;; [:filters/submit base-path form-data] Filter and sort form: navigates to
;; base-path with the normalised q, availability and sort.
(defmethod handle :filters/submit [state [_ base-path form-data]]
  (let [q (pages/normalise-query {:q (get form-data "q") :availability (get form-data "availability") :sort (get form-data "sort")})]
    (handle state [:nav/go (routes/with-query base-path q)])))

;; [:filters/submit-form] Select or checkbox changed: asks the shell to submit
;; .filter-form (:fx/submit-form), which comes back as :filters/submit.
(defmethod handle :filters/submit-form [state _]
  (ret state [[:fx/submit-form ".filter-form"]]))

;; [:products/load-more] Load more link: marks the page as loading more and
;; requests the next page of the same listing (:fx/request, result to
;; :products/more-loaded).
(defmethod handle :products/load-more [state _]
  (let [{:keys [route page]} state
        listing (get-in page [:data :products])
        next-page (inc (or (:page listing) 1))
        slug (get-in route [:params :slug])
        req (pages/products-request (assoc (pages/normalise-query (:query route)) :page next-page) slug)]
    (ret (assoc-in state [:page :loading-more?] true)
         [[:fx/request req [:products/more-loaded]]])))

;; [:products/more-loaded response] Appends the new items to the listing,
;; updates ?page= in the address bar (:fx/replace-url) and announces the
;; count. On failure it announces an error and keeps the items.
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

;; [:gallery/select i] Shows image number `i` in the product gallery.
(defmethod handle :gallery/select [state [_ i]]
  (ret (assoc-in state [:ui :gallery :index] i)))

;; ---------------------------------------------------------------------------
;; Product page

(defn- product-max [state]
  (or (get-in state [:page :data :maxQuantity]) 1))

;; [:product/set-qty q] Sets the chosen quantity on P4, clamped to
;; 1..maxQuantity.
(defmethod handle :product/set-qty [state [_ q]]
  (let [mx (max 1 (product-max state))]
    (ret (assoc-in state [:ui :product :qty] (-> q (max 1) (min mx))))))

;; [:product/set-qty-input raw] Typed quantity: parses the text (1 when
;; invalid) and delegates to :product/set-qty.
(defmethod handle :product/set-qty-input [state [_ raw]]
  (handle state [:product/set-qty (or (parse-long (str raw)) 1)]))

;; [:product/rechecked slug response] Result of the no-cache re-fetch that
;; route-enter makes on every product page (the HTML may be up to ~90 s old in
;; the CDN). Updates price, availability and maxQuantity, lowers the chosen
;; quantity if needed and shows a stock note.
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

;; Why :order-key is cleared: the idempotency key identifies one order attempt
;; for one cart. A changed cart is a different order, so it must get a new
;; key; reusing the old one would make the server answer 409
;; idempotencyKeyReused or replay the old order.
(defn- cart-changed [state new-cart]
  (let [state (-> state (assoc :cart new-cart) (assoc :order-key nil))]
    (ret state [[:fx/save-cart new-cart]])))

;; Prices and stock can change while the customer edits the cart, so cart
;; edits on the cart or checkout page ask the server again.
(defn- with-validate [{:keys [state fx]}]
  (let [on-cart? (#{:cart :checkout} (get-in state [:route :name]))]
    {:state state :fx (cond-> fx on-cart? (conj [:fx/dispatch [:cart/validate]]))}))

;; [:cart/add product qty] Adds to the cart (capped by maxQuantity), saves it
;; (:fx/save-cart), shows the added notice and focuses it. Does not call the
;; API: the cart reserves nothing.
(defmethod handle :cart/add [state [_ product qty]]
  (let [new-cart (cart/add-item (:cart state) product qty (:maxQuantity product))]
    (-> (cart-changed state new-cart)
        (update :state assoc-in [:ui :product :added?] true)
        (update :state assoc-in [:ui :announcement]
                (str (:name product) " added to your cart. " (cart/count-items new-cart) " items in the cart."))
        (update :fx conj [:fx/focus "added-notice"]))))

;; [:cart/set-qty product-id qty] Changes a line (clamped to 1..10 here; the
;; server's maxQuantity comes back from validation), saves, and re-validates
;; when on the cart or checkout page.
(defmethod handle :cart/set-qty [state [_ id q]]
  (with-validate (cart-changed state (cart/set-quantity (:cart state) id q 10))))

;; [:cart/set-qty-input product-id raw] Typed quantity; delegates to
;; :cart/set-qty.
(defmethod handle :cart/set-qty-input [state [_ id raw]]
  (handle state [:cart/set-qty id (or (parse-long (str raw)) 1)]))

;; [:cart/remove product-id] Removes a line, saves, re-validates on
;; cart/checkout and announces it.
(defmethod handle :cart/remove [state [_ id]]
  (-> (with-validate (cart-changed state (cart/remove-item (:cart state) id)))
      (update :state assoc-in [:ui :announcement] "Item removed from your cart.")))

;; [:cart/validate] POST /api/cart-validations for the cart. Tagged with the
;; cart signature so an answer for an older cart is ignored (:cart/validated).
;; An empty cart resets the check to :idle.
(defmethod handle :cart/validate [state _]
  (if (empty? (get-in state [:cart :items]))
    (ret (assoc state :cart-check {:status :idle}))
    (ret (assoc-in state [:cart-check :status] :loading)
         [[:fx/request {:method :post :path "/api/cart-validations" :body (cart/validation-request (:cart state))}
           [:cart/validated (cart/cart-signature (:cart state))]]])))

;; [:cart/acknowledge-prices] The customer is going on to checkout, so the
;; validated prices count as seen: the stored prices are replaced by the
;; validated ones and saved. Dispatched by the Checkout link on P5 right
;; before :nav/link.
(defmethod handle :cart/acknowledge-prices [state _]
  (let [new-cart (cart/acknowledge-prices (:cart state) (get-in state [:cart-check :result]))]
    (ret (assoc state :cart new-cart) [[:fx/save-cart new-cart]])))

;; [:cart/validated signature response] Stores the validation result. Names,
;; slugs and images are refreshed in the stored cart, but prices are NOT (see
;; shared.cart/apply-validation), so the cart can show "Price changed".
;; Ignored when the cart changed meanwhile.
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

;; [:form/input form-id key value] Stores a field value in (:forms form-id)
;; and clears that field's error.
(defmethod handle :form/input [state [_ form-id k v]]
  (ret (-> state
           (assoc-in (conj (form-path form-id) :values k) v)
           (update-in (conj (form-path form-id) :errors) dissoc k))))

;; [:form/set form-id key value] Same as :form/input for radio groups; for the
;; admin dialog it also clears the form-level message, which belonged to the
;; previous choice.
(defmethod handle :form/set [state [_ form-id k v]]
  (let [r (handle state [:form/input form-id k v])]
    ;; a form-level message (e.g. "Stock cannot go below zero.") belongs to the
    ;; previous choice; clear it when the choice changes.
    (cond-> r
      (= :dialog form-id) (update-in [:state :forms :dialog :errors] dissoc :form))))

;; [:form/noop] Does nothing (for fields whose input event needs a handler,
;; such as the disabled country field).
(defmethod handle :form/noop [state _] (ret state))

;; [:form/reset form-id] Forgets the state of a form.
(defmethod handle :form/reset [state [_ form-id]]
  (ret (update state :forms dissoc form-id)))

;; [:form/key-ready form-id action key] A fresh idempotency key arrived for a
;; form; stores it and re-runs `action`.
(defmethod handle :form/key-ready [state [_ form-id action key]]
  (handle (assoc-in state (conj (form-path form-id) :key) key) action))

;; Idempotency (api-contract.md 1.6): every create-type POST from a form
;; carries one Idempotency-Key for the lifetime of that form submission. The
;; key is generated once through the :fx/uuid effect (handlers cannot call
;; random-uuid and stay pure), stored in the form and kept through retries, so
;; a double click or a network retry cannot send two different requests.
(defn- need-key
  "If the form has no idempotency key yet, generate one and re-dispatch."
  [state form-id action]
  (when-not (get-in state (conj (form-path form-id) :key))
    (ret state [[:fx/uuid [:form/key-ready form-id action]]])))

(defn- email? [s] (boolean (re-matches #"[^@\s]+@[^@\s]+\.[^@\s]+" (str s))))

(defn uganda-phone?
  "True when `s` (spaces ignored) is a Uganda mobile number: +256 or 0, then 7
  and eight digits. Convenience check only; the server validates again."
  [s]
  (boolean (re-matches #"(\+256|0)7\d{8}" (str/replace (str s) #"\s+" ""))))

(defn detail->form-errors
  "Maps API error details [{:field :message}] to {form-key message}.
  `field-map` maps API field names to form keys; fields without a mapping are
  dropped. Pure."
  [details field-map]
  (into {} (keep (fn [{:keys [field message]}]
                   (when-let [k (get field-map field)] [k message])))
        details))

;; Newsletter ---------------------------------------------------------------

;; [:newsletter/submit source] Validates the email, makes sure the form has an
;; idempotency key (generating one first), then POST /api/subscriptions with
;; consent true. The honeypot field `website` is sent empty.
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

;; [:newsletter/result response] 200/201 shows the thank-you; 422, 429 and
;; other failures show a form message.
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

;; [:contact/submit] Validates the contact form (convenience; the server
;; decides), gets a per-form idempotency key and POSTs /api/contact-messages.
;; Errors focus the error summary.
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

;; [:contact/result response] 201 shows the thank-you; 422 maps API details to
;; fields; 429 and others show a form message.
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

;; [:track/submit] Validates order number and phone, then POST
;; /api/order-trackings (a lookup, not a mutation, so no idempotency key).
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

;; [:track/result response] 200 stores the result and announces the status;
;; 404, 429 and other failures show a form message.
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

;; [:unsubscribe/submit] POST /api/unsubscribes with the token from the URL.
(defmethod handle :unsubscribe/submit [state _]
  (ret (assoc-in state [:forms :unsubscribe :status] :submitting)
       [[:fx/request {:method :post :path "/api/unsubscribes" :body {:token (get-in state [:route :query :token])}}
         [:unsubscribe/result]]]))

;; [:unsubscribe/result response] 200 shows the confirmation; anything else a
;; retry message.
(defmethod handle :unsubscribe/result [state [_ {:keys [status]}]]
  (if (= 200 status)
    (ret (assoc-in state [:forms :unsubscribe] {:status :done}))
    (ret (-> state (assoc-in [:forms :unsubscribe :status] :idle)
             (assoc-in [:forms :unsubscribe :errors :form] "We could not unsubscribe you right now. Please try again.")))))

;; ---------------------------------------------------------------------------
;; Policies and the order terms

;; [:agreement/load] Loads GET /api/policies/orderTerms for the checkout
;; agreement panel (status :loading, then :ready or :error).
(defmethod handle :agreement/load [state _]
  (ret (assoc-in state [:refs :policy-status "orderTerms"] :loading)
       [[:fx/request {:path "/api/policies/orderTerms"} [:agreement/loaded]]]))

;; [:agreement/loaded response] Stores the order terms (their version is sent
;; with the order) or marks the panel :error.
(defmethod handle :agreement/loaded [state [_ {:keys [status body]}]]
  (if (= 200 status)
    (ret (-> state (assoc-in [:refs :policies "orderTerms"] body) (assoc-in [:refs :policy-status "orderTerms"] :ready)))
    (ret (assoc-in state [:refs :policy-status "orderTerms"] :error))))

;; ---------------------------------------------------------------------------
;; Checkout

(def checkout-field-map
  "API error `details` field names (api-contract.md, POST /api/orders) ->
  checkout form keys, used to show server-side errors next to the right field."
  {"customer.name" :name "customer.phone" :phone "customer.email" :email
   "customer.preferredContact" :preferred-contact "delivery.city" :city "delivery.area" :area
   "delivery.addressLine" :address-line "delivery.directions" :directions
   "paymentMethod" :payment-method "note" :note "agreement" :accepted "agreement.version" :accepted
   "agreement.type" :accepted})

(defn checkout-errors
  "Client-side convenience validation for the checkout form: name 2 to 100
  characters, a Uganda phone, optional well-formed email, a city from the
  delivery areas, area 2 to 80, street address 3 to 200, a payment method, a
  note under 500 characters and accepted terms. Arguments: the form values
  (with :accepted?) and the seq of allowed `cities`. Returns {form-key
  message}, empty when valid. The server remains the authority."
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

(defn order-body
  "Body of POST /api/orders from the state. Reads the checkout form values, the
  cart with validated prices as expectedUnitPrice (shared.cart/order-items),
  and the accepted order-terms version. Trims text, turns empty optional
  values into nil, fixes the country to \"UG\" and sends the honeypot field
  `website` empty. Pure."
  [state]
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

;; [:checkout/accept checked?] Records acceptance of the order terms and
;; clears its error.
(defmethod handle :checkout/accept [state [_ checked?]]
  (ret (-> state (assoc-in [:forms :checkout :values :accepted?] (boolean checked?))
           (update-in [:forms :checkout :errors] dissoc :accepted))))

;; [:checkout/key-ready key] The order's idempotency key was generated; stores
;; it as :order-key and submits again.
(defmethod handle :checkout/key-ready [state [_ key]]
  (handle (assoc state :order-key key) [:checkout/submit]))

;; [:checkout/submit] Places the order. Ignored while already submitting.
;; Client checks first (frontend.state/checkout-errors), then one idempotency
;; key per cart (:order-key; cleared whenever the cart changes), then POST
;; /api/orders with the validated prices as expectedUnitPrice.
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

;; [:checkout/result response] 200/201: clears the cart, stores the order for
;; P7 (:fx/save-cart, :fx/save-order) and navigates to /order-placed. 409
;; cartOutdated: back to the cart with a notice, new key. 409
;; agreementVersionOutdated: reload the terms and ask to accept again. 422:
;; field errors. 429: wait message. Network or 5xx errors keep the key and
;; show the retry notice.
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

;; [:copy-text text] Copies the order number to the clipboard (:fx/copy) and
;; announces it.
(defmethod handle :copy-text [state [_ text]]
  (ret (assoc-in state [:ui :announcement] "Order number copied.") [[:fx/copy text]]))

;; Focus and misc DOM actions are handled in the dispatcher (they need the
;; event), but fall back to effects:
;; [:dom/focus-id id] Focus the element with `id` (skip link, error summary
;; links).
(defmethod handle :dom/focus-id [state [_ id]] (ret state [[:fx/focus id]]))
;; [:dom/prevent-default] Handled in main/run-action! (it needs the event);
;; here it is a no-op so it can sit in an action list.
(defmethod handle :dom/prevent-default [state _] (ret state))
