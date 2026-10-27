(ns zandy.frontend.state-test
  "Unit tests of the pure action handlers in zandy.frontend.state and the admin
  handlers: navigation, product page, cart (validation, price
  acknowledgement), forms with idempotency keys, checkout results, import and
  stock flows. They run in Node (shadow-cljs :test build) and need no DOM."
  (:require [cljs.test :refer [deftest is testing]]
            [zandy.frontend.admin.core :as admin]
            [zandy.frontend.admin.state :as as]
            [zandy.frontend.admin.widgets :as w]
            [zandy.frontend.state :as s]
            [zandy.shared.cart :as cart]
            [zandy.shared.routes :as routes]
            [zandy.shared.ui.order :as order]))

(def serum
  "Sample product used by the tests."
  {:id "p1" :slug "serum" :name "Serum" :price 85000 :image nil :maxQuantity 10})

(defn st
  "Test state: the initial state at \"/\" with a loaded page, plus extra keys."
  [& {:as extra}]
  (merge (s/initial-state) {:route (routes/match-url "/") :page {:status :ok}} extra))

(defn effects-named
  "The effects in handler result `result` whose name is `fx-name`."
  [result fx-name] (filter #(= fx-name (first %)) (:fx result)))

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

(def valid-values
  "Checkout form values that pass client validation."
  {:name "Amina Nakato" :phone "0771 234 567" :city "Kampala" :area "Ntinda" :address-line "Plot 12" :payment-method "cash" :accepted? true})

(deftest checkout-validation
  (let [cities ["Kampala" "Jinja"]]
    (is (= {} (s/checkout-errors valid-values cities)))
    (is (contains? (s/checkout-errors (assoc valid-values :phone "123") cities) :phone))
    (is (contains? (s/checkout-errors (assoc valid-values :city "Gulu") cities) :city) "only the API's cities")
    (is (contains? (s/checkout-errors (assoc valid-values :accepted? false) cities) :accepted))
    (is (contains? (s/checkout-errors (assoc valid-values :email "nope") cities) :email))))

(defn checkout-state
  "State on /checkout with a cart, delivery areas and the order terms loaded."
  []
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

;; ---------------------------------------------------------------------------
;; Admin: navigation, product pictures, bulk import

(defn jpg
  "A picked JPEG file map named `n`."
  [n] {:name n :size 1000 :type "image/jpeg" :file n})

(deftest admin-navigation-has-no-photo-library
  (is (= ["Orders" "Products" "Import" "Inbox" "Staff" "My account" "Help"] (map #(nth % 2) admin/nav-items)))
  (is (nil? (routes/match-url "/admin/photos")))
  (is (= :admin-product-import (:name (routes/match-url "/admin/product-import")))))

(deftest no-route-asks-for-the-photo-library
  (doseq [url ["/admin/new-product" "/admin/products/p1" "/admin/product-import" "/admin/products"]]
    (let [reqs (as/route-requests (st :route (routes/match-url url)))]
      (is (not-any? #(re-find #"photo" (str (:path %))) (vals reqs)) url)))
  (is (= "draft" (get-in (as/route-requests (st :route (routes/match-url "/admin/product-import"))) [:drafts :query :status]))))

(deftest picture-checks-before-upload
  (is (nil? (as/picture-problem (jpg "a.jpg"))))
  (is (= "unsupportedMediaType" (:code (as/picture-problem {:name "a.pdf" :size 10 :type "application/pdf"}))))
  (is (= "fileTooLarge" (:code (as/picture-problem {:name "a.jpg" :size (inc (* 5 1024 1024)) :type "image/jpeg"}))))
  (testing "a missing type falls back to the extension"
    (is (nil? (as/picture-problem {:name "A.JPG" :size 10 :type ""})))))

(deftest product-pictures-upload-to-the-product
  (let [state (-> (st) (assoc-in [:admin :data :product :body] {:id "p1" :version 3 :images [{:id "a"}]})
                  (assoc-in [:forms :product :values :images] [{:id "a" :alt "x"}]))]
    (testing "bad files are rejected locally, good ones are sent with a fresh key"
      (let [r (s/handle state [:admin/product-images-picked [(jpg "ok.jpg") {:name "n.txt" :size 5 :type "text/plain"}]])]
        (is (= :uploading (get-in r [:state :forms :product-images :status])))
        (is (= "unsupportedMediaType" (get-in r [:state :forms :product-images :items 0 :error :code])))
        (let [[_ [_ pid files]] (first (effects-named r :fx/uuid))]
          (is (= "p1" pid))
          (is (= ["ok.jpg"] (map :name files))))))
    (testing "the request goes to the product's images endpoint with the key"
      (let [r (s/handle state [:admin/product-images-send "p1" [(jpg "ok.jpg")] "key-1"])
            [_ req] (first (effects-named r :fx/upload-files))]
        (is (= "/api/admin/products/p1/images" (:path req)))
        (is (= "key-1" (:idempotency-key req)))))
    (testing "only as many files as there is room for (6 per product)"
      (let [full (assoc-in state [:forms :product :values :images] (vec (repeat 5 {:id "z"})))
            r (s/handle full [:admin/product-images-picked [(jpg "1.jpg") (jpg "2.jpg")]])
            [_ [_ _ files]] (first (effects-named r :fx/uuid))]
        (is (= 1 (count files)))
        (is (= "tooManyImages" (get-in r [:state :forms :product-images :items 0 :error :code])))))
    (testing "attached pictures join the form without losing unsaved edits"
      (let [r (s/handle state [:admin/product-images-result [(jpg "ok.jpg")]
                               {:status 200 :body {:items [{:filename "ok.jpg" :status "attached" :image {:id "b" :alt nil :urls {:thumb "t"}}}]
                                                   :product {:id "p1" :version 4 :images [{:id "a"} {:id "b"}]}}}])]
        (is (= ["a" "b"] (map :id (get-in r [:state :forms :product :values :images]))))
        (is (= "x" (get-in r [:state :forms :product :values :images 0 :alt])))
        (is (= 4 (get-in r [:state :admin :data :product :body :version])))))
    (testing "a failed request is reported per file"
      (let [r (s/handle state [:admin/product-images-result [(jpg "ok.jpg")] {:status nil :error "offline"}])]
        (is (= "uploadFailed" (get-in r [:state :forms :product-images :items 0 :error :code])))))))

(deftest product-pictures-are-saved-with-the-patch-images-list
  (let [state (-> (st) (assoc-in [:admin :data :product :body] {:id "p1" :version 3})
                  (assoc-in [:forms :product :values :images] [{:id "b" :alt "Open jar" :urls {}} {:id "a" :alt "" :urls {}}]))
        r (s/handle state [:admin/product-images-save])
        [_ req] (first (effects-named r :fx/request))]
    (is (= :patch (:method req)))
    (is (= "/api/admin/products/p1" (:path req)))
    (is (= {:version 3 :images [{:id "b" :alt "Open jar"} {:id "a"}]} (:body req)))))

(deftest new-product-is-created-without-pictures
  (let [state (-> (st :forms {:product {:values (as/product-values {}) :key "k"}}) (assoc-in [:forms :product :values :name] "Serum")
                  (assoc-in [:forms :product :values :brand] "Zandy Touch") (assoc-in [:forms :product :values :category] "skincare"))
        [_ req] (first (effects-named (s/handle state [:admin/product-save nil]) :fx/request))]
    (is (not (contains? (:body req) :images)))
    (is (= 0 (get-in req [:body :stockQuantity])))))

(deftest publishing-without-a-picture-explains-the-next-step
  (let [state (-> (st :forms {:product {:values (assoc (as/product-values {}) :name "Serum" :brand "B" :category "skincare" :price "5000" :status "published")}}))
        r (s/handle state [:admin/product-save nil])]
    (is (re-find #"Save it as a draft" (get-in r [:state :forms :product :errors :images])))
    (is (empty? (effects-named r :fx/request)))))

(deftest csv-dry-run-comes-first-and-has-its-own-key
  (let [file {:name "p.csv" :size 100 :type "text/csv" :file :f}
        picked (:state (s/handle (st) [:admin/csv-picked [file]]))]
    (testing "the file is picked, size checked"
      (is (= "p.csv" (get-in picked [:forms :import-csv :file :name])))
      (is (get-in (:state (s/handle (st) [:admin/csv-picked [(assoc file :size (inc (* 1024 1024)))]])) [:forms :import-csv :error])))
    (testing "the real import is refused before a dry run"
      (is (empty? (:fx (s/handle picked [:admin/csv-check :real])))))
    (testing "the dry run asks for a key, then posts with dryRun=true"
      (is (seq (effects-named (s/handle picked [:admin/csv-check :dry]) :fx/uuid)))
      (let [r (s/handle picked [:admin/csv-key-ready :dry "k-dry"])
            [_ req b] (first (effects-named r :fx/upload-files))]
        (is (= "/api/admin/product-imports" (:path req)))
        (is (= {:dryRun "true"} (:query req)))
        (is (= "file" (:field req)))
        (is (= "k-dry" (:idempotency-key req)))
        (is (= [:admin/csv-result :dry] b))))
    (testing "after a dry run with rows to apply, the real import uses another key and no dryRun"
      (let [dry (:state (s/handle picked [:admin/csv-result :dry {:status 200 :body {:dryRun true :summary {:rows 2 :created 1 :updated 0 :rejected 1} :rows []}}]))
            r (s/handle dry [:admin/csv-key-ready :real "k-real"])
            [_ req] (first (effects-named r :fx/upload-files))]
        (is (as/can-import? (get-in dry [:forms :import-csv])))
        (is (nil? (:query req)))
        (is (= "k-real" (:idempotency-key req)))
        (is (not= "k-dry" (:idempotency-key req)))))
    (testing "a dry run that finds nothing to apply does not unlock the import"
      (let [dry (:state (s/handle picked [:admin/csv-result :dry {:status 200 :body {:summary {:rows 1 :created 0 :updated 0 :rejected 1} :rows []}}]))]
        (is (not (as/can-import? (get-in dry [:forms :import-csv]))))))
    (testing "a real import remembers the products it touched"
      (let [r (s/handle picked [:admin/csv-result :real {:status 200 :body {:summary {} :rows [{:status "created" :productId "p9"} {:status "rejected" :productId nil} {:status "updated" :productId "p2"}]}}])]
        (is (= ["p9" "p2"] (get-in r [:state :forms :import-review :imported-ids])))
        (is (seq (effects-named r :fx/dispatch)))))
    (testing "whole-file failures are explained"
      (let [r (s/handle picked [:admin/csv-result :dry {:status 422 :body {:error {:code "validationFailed" :message "x" :details [{:field "file" :code "unknownColumn" :message "Unknown column: colour"}]}}}])]
        (is (= ["Unknown column: colour"] (get-in r [:state :forms :import-csv :dry :errors])))))))

(deftest pictures-go-in-batches-of-20
  (let [files (mapv #(jpg (str "ZT-" % "-1.jpg")) (range 45))
        picked (:state (s/handle (st) [:admin/pictures-picked (conj files {:name "x.gif" :size 1 :type "image/gif"})]))]
    (is (= 45 (count (get-in picked [:forms :import-pictures :queue]))))
    (is (= 1 (count (get-in picked [:forms :import-pictures :results]))) "the gif is rejected locally")
    (let [started (:state (s/handle picked [:admin/pictures-upload]))
          sent-batch (fn [state] (let [r (s/handle state [:admin/pictures-next])
                                       [_ [_ batch]] (first (effects-named r :fx/uuid))]
                                   batch))]
      (is (= :uploading (get-in started [:forms :import-pictures :status])))
      (is (= 20 (count (sent-batch started))))
      (let [second-state (-> (s/handle started [:admin/pictures-batch-result (sent-batch started)
                                                {:status 200 :body {:items (mapv (fn [f] {:filename (:name f) :status "attached" :sku "S" :position 1}) (sent-batch started))}}])
                             :state)]
        (is (= 1 (get-in second-state [:forms :import-pictures :index])))
        (is (= 20 (count (sent-batch second-state))))
        (let [s3 (assoc-in second-state [:forms :import-pictures :index] 2)]
          (is (= 5 (count (sent-batch s3))) "the last batch holds the rest")
          (testing "the finished upload reloads the drafts"
            (let [done (s/handle (assoc-in s3 [:forms :import-pictures :index] 3) [:admin/pictures-next])]
              (is (= :done (get-in done [:state :forms :import-pictures :status])))
              (is (= [[:fx/dispatch [:admin/reload]]] (:fx done))))))))
    (testing "each batch is posted to product-images with its own key"
      (let [r (s/handle (st) [:admin/pictures-send [(jpg "a.jpg")] "k1"])
            [_ req] (first (effects-named r :fx/upload-files))]
        (is (= "/api/admin/product-images" (:path req)))
        (is (= "k1" (:idempotency-key req)))))
    (testing "a failed batch keeps its files for a retry and the run continues"
      (let [r (s/handle (assoc-in (st) [:forms :import-pictures] {:status :uploading :queue [(jpg "a.jpg")] :results [] :index 0})
                        [:admin/pictures-batch-result [(jpg "a.jpg")] {:status nil :error "offline"}])
            results (get-in r [:state :forms :import-pictures :results])]
        (is (= "uploadFailed" (get-in results [0 :error :code])))
        (is (= "a.jpg" (get-in results [0 :retry-file :name])))
        (is (= [[:fx/dispatch [:admin/pictures-next]]] (:fx r)))
        (let [again (s/handle (:state r) [:admin/pictures-retry])]
          (is (= ["a.jpg"] (map :name (get-in again [:state :forms :import-pictures :queue]))))
          (is (empty? (get-in again [:state :forms :import-pictures :results]))))))))

(def draft-ok
  "Sample draft that can be published."
  {:id "d1" :name "Cream" :price 5000 :image {:id "i"} :categorySlug "skincare"})
(def draft-bare
  "Sample draft with no price or picture."
  {:id "d2" :name "Oil" :price nil :image nil :categorySlug "body-care"})

(deftest drafts-say-what-they-lack-and-publish-only-complete-ones
  (is (= [] (as/draft-lacks draft-ok)))
  (is (= ["Price" "Picture"] (as/draft-lacks draft-bare)))
  (let [state (-> (st) (assoc-in [:admin :data :drafts :body :items] [draft-ok draft-bare {:id "d3" :name "Old" :price 1 :image {:id "x"} :categorySlug "skincare"}])
                  (assoc-in [:forms :import-review :imported-ids] ["d1" "d2"]))]
    (testing "after an import only its products are listed; the scope can be widened"
      (is (= ["d1" "d2"] (map :id (as/drafts-shown state))))
      (is (= 3 (count (as/drafts-shown (assoc-in state [:forms :import-review :scope] :all))))))
    (let [r (s/handle state [:admin/publish-complete])
          [_ req] (first (effects-named r :fx/request))]
      (is (= "/api/admin/product-publications" (:path req)))
      (is (= {:productIds ["d1"]} (:body req))))
    (testing "nothing complete means no request"
      (is (empty? (:fx (s/handle (assoc-in state [:admin :data :drafts :body :items] [draft-bare]) [:admin/publish-complete])))))
    (testing "the result names each product"
      (let [r (s/handle state [:admin/publish-result {"d1" "Cream"} {:status 200 :body {:results [{:productId "d1" :status "published"}]}}])]
        (is (= "Cream" (get-in r [:state :forms :import-review :publish :results 0 :name])))))))

(deftest more-draft-pages-are-fetched
  (let [r (s/handle (st) [:admin/loaded :drafts {:status 200 :body {:items [{:id "a"}] :page 1 :pageSize 100 :totalItems 150}}])
        [_ req] (first (effects-named r :fx/request))]
    (is (= 2 (get-in req [:query :page]))))
  (is (empty? (effects-named (s/handle (st) [:admin/loaded :drafts {:status 200 :body {:items [] :page 1 :pageSize 100 :totalItems 100}}]) :fx/request))))

(deftest result-lists-explain-rows-and-files
  (let [items (w/csv-row-items [{:line 4 :sku "ZT-1" :status "rejected" :warnings [{:field "openingStock" :code "ignoredForExisting"}]
                                 :errors [{:field "price" :code "invalid" :message "Price must be a whole number of shillings."}]}] false)]
    (is (= "Rejected" (:status-label (first items))))
    (is (= ["Price: Price must be a whole number of shillings."
            "Opening stock: Ignored because this product already exists. Change stock with Adjust stock."]
           (map :text (:messages (first items))))))
  (is (= "Would be created" (:status-label (first (w/csv-row-items [{:line 2 :sku "A" :status "created"}] true)))))
  (is (= "No product has a SKU at the start of this file name."
         (-> (w/picture-items [{:filename "x.jpg" :status "rejected" :error {:code "unknownSku"}}]) first :messages first :text)))
  (is (= "3 rows: 1 created, 1 updated, 1 rejected" (w/summary-text {:rows 3 :created 1 :updated 1 :rejected 1} false))))

;; ---------------------------------------------------------------------------
;; Admin: restore an archived product, category names, stock history

(defn- texts
  "All strings in a hiccup tree."
  [x]
  (cond (string? x) [x] (sequential? x) (mapcat texts x) (map? x) [] :else []))

(defn- has-text? [tree re] (boolean (some #(re-find re %) (texts tree))))

(defn- find-nodes [tree pred]
  (cond (and (vector? tree) (pred tree)) [tree]
        (sequential? tree) (mapcat #(find-nodes % pred) tree)
        :else []))

(def archived
  "Sample archived product."
  {:id "p9" :version 4 :name "Old Serum" :status "archived"})

(deftest restore-from-the-product-page-sends-the-version-and-published
  (let [state (-> (st) (assoc-in [:admin :data :product :body] archived))
        r (s/handle state [:admin/restore "p9" "Old Serum" "published"])
        [_ req] (first (effects-named r :fx/request))]
    (is (= :patch (:method req)))
    (is (= "/api/admin/products/p9" (:path req)))
    (is (= {:version 4 :status "published"} (:body req)))))

(deftest restore-from-the-list-reads-the-product-first
  (let [r (s/handle (st) [:admin/restore "p9" "Old Serum" "published"])
        [_ req] (first (effects-named r :fx/request))]
    (is (nil? (:method req)) "a GET: the list does not carry the version")
    (is (= "/api/admin/products/p9" (:path req)))
    (let [r2 (s/handle (:state r) [:admin/restore-read "p9" "Old Serum" "published" {:status 200 :body archived}])
          [_ req2] (first (effects-named r2 :fx/request))]
      (is (= {:version 4 :status "published"} (:body req2))))))

(deftest restore-success-reloads-and-says-so
  (let [state (-> (st) (assoc-in [:admin :ui :restoring] "p9") (assoc-in [:forms :product] {:init "p9"}))
        r (s/handle state [:admin/restored "p9" "published" "Old Serum" {:status 200 :body (assoc archived :status "published")}])]
    (is (re-find #"Old Serum restored and published" (get-in r [:state :admin :ui :notice])))
    (is (nil? (get-in r [:state :admin :ui :restoring])))
    (is (nil? (get-in r [:state :forms :product])) "A8 rebuilds its form from the reloaded product")
    (is (= [[:fx/dispatch [:admin/reload]]] (:fx r)))))

(deftest restore-rejected-shows-reasons-and-offers-a-draft
  (let [body {:error {:code "validationFailed" :message "Please check the highlighted fields."
                      :details [{:field "price" :code "priceRequired"} {:field "images" :code "imageRequired"}]}}
        r (s/handle (st) [:admin/restored "p9" "published" "Old Serum" {:status 422 :body body}])
        rej (get-in r [:state :admin :ui :restore-rejected])]
    (is (= ["It has no price." "It has no picture."] (:reasons rej)))
    (is (= "p9" (:id rej)))
    (is (nil? (get-in r [:state :admin :ui :notice])) "no success message")
    (testing "the notice offers Restore as draft instead, which sends status draft"
      (let [view (admin/view (-> (st :route (routes/match-url "/admin/products?status=archived"))
                                 (assoc-in [:admin :session] {:user {:role "owner" :name "S"}})
                                 (assoc-in [:admin :ui :restore-rejected] rej)
                                 (assoc-in [:admin :data :products] {:status :ok :body {:items [] :counts {}}})))]
        (is (has-text? view #"cannot be published yet"))
        (is (has-text? view #"It has no price"))
        (is (has-text? view #"Restore as draft instead")))
      (let [r2 (s/handle (assoc-in (:state r) [:admin :data :product :body] archived) [:admin/restore "p9" "Old Serum" "draft"])
            [_ req] (first (effects-named r2 :fx/request))]
        (is (= {:version 4 :status "draft"} (:body req)))
        (is (nil? (get-in r2 [:state :admin :ui :restore-rejected])) "cleared while it is retried")))
    (testing "a draft restore succeeds with its own wording"
      (let [r3 (s/handle (:state r) [:admin/restored "p9" "draft" "Old Serum" {:status 200 :body (assoc archived :status "draft")}])]
        (is (re-find #"restored as a draft" (get-in r3 [:state :admin :ui :notice])))
        (is (nil? (get-in r3 [:state :admin :ui :restore-rejected])))))))

(deftest restore-conflict-and-failure
  (let [r (s/handle (st) [:admin/restored "p9" "published" "Old Serum" {:status 409 :body {:error {:code "versionConflict"}}}])]
    (is (get-in r [:state :admin :ui :conflict]))
    (is (seq (effects-named r :fx/dispatch))))
  (is (get-in (s/handle (st) [:admin/restored "p9" "published" "Old Serum" {:status 500 :body {}}]) [:state :admin :ui :notice])))

(deftest saving-an-archived-product-does-not-restore-it
  (let [state (-> (st) (assoc-in [:admin :data :product :body] archived)
                  (assoc-in [:forms :product :values] (assoc (as/product-values archived) :category "skincare" :brand "Zandy")))
        r (s/handle state [:admin/product-save "p9"])
        [_ req] (first (effects-named r :fx/request))]
    (is (= "archived" (get-in state [:forms :product :values :status])))
    (is (not (contains? (:body req) :status)))))

(deftest archived-rows-and-page
  (let [row {:id "p9" :name "Old Serum" :sku "S-9" :categorySlug "health-and-wellness" :status "archived" :stockQuantity 2 :price 1000 :updatedAt "2026-10-24T22:30:00Z"}
        base (-> (st :route (routes/match-url "/admin/products?status=archived"))
                 (assoc-in [:admin :session] {:user {:role "owner" :name "S"}})
                 (assoc-in [:admin :categories] [{:slug "health-and-wellness" :name "Health & Wellness"}])
                 (assoc-in [:admin :data :products] {:status :ok :body {:items [row (assoc row :id "p1" :name "Live" :status "published")] :counts {}}}))
        view (admin/view base)
        row-of (fn [nm] (first (find-nodes view (fn [n] (and (= :tr (first n)) (has-text? n (re-pattern nm)))))))
        buttons (fn [tr] (set (mapcat texts (find-nodes tr (fn [n] (and (keyword? (first n)) (re-find #"^(a|button)" (name (first n))))))))) ]
    (is (every? (buttons (row-of "Old Serum")) ["Edit" "Restore"]))
    (is (not-any? (buttons (row-of "Old Serum")) ["Adjust stock" "Remove"]))
    (is (every? (buttons (row-of "Live")) ["Edit" "Adjust stock" "Remove"]))
    (is (not ((buttons (row-of "Live")) "Restore")))
    (is (has-text? (row-of "Old Serum") #"Health & Wellness") "category shown by name")
    (is (has-text? (row-of "Old Serum") #"25 Oct 2026") "updated date in Kampala time"))
  (testing "A8: notice, no Remove, no empty radio, Restore present"
    (let [p (assoc archived :slug "old" :images [] :stockQuantity 0 :updatedAt "2026-10-24T22:30:00Z")
          state (-> (st :route (routes/match-url "/admin/products/p9"))
                    (assoc-in [:admin :session] {:user {:role "owner" :name "S"}})
                    (assoc-in [:admin :data :product] {:status :ok :body p})
                    (assoc-in [:admin :data :movements] {:status :ok :body {:items []}})
                    (assoc-in [:forms :product] {:values (as/product-values p) :init "p9"}))
          view (admin/view state)]
      (is (has-text? view #"This product is removed from the shop \(archived\)\."))
      (is (has-text? view #"Restore"))
      (is (not (has-text? view #"Remove from shop")))
      (is (empty? (find-nodes view (fn [n] (and (= :input (first n)) (= "radio" (:type (second n))) (= "pf-status" (:name (second n))))))))
      (is (has-text? view #"01:30 \(Kampala time\)")))))

(deftest categories-are-cached-once-per-session
  (let [fx-paths (fn [r] (set (map (comp :path second) (effects-named r :fx/request))))
        route-state (fn [extra] (-> (st :route (routes/match-url "/admin/products")) (assoc-in [:admin :session] {:user {:role "owner"}}) (merge extra)))
        r1 (s/handle (route-state {}) [:admin/enter])]
    (is (contains? (fx-paths r1) "/api/categories"))
    (let [loaded (s/handle (:state r1) [:admin/categories-loaded {:status 200 :body {:items [{:slug "skincare" :name "Skincare"}]}}])]
      (is (= "Skincare" (as/category-name (:state loaded) "skincare")))
      (is (= "no-such" (as/category-name (:state loaded) "no-such")) "unknown slug falls back to itself")
      (is (not (contains? (fx-paths (s/handle (:state loaded) [:admin/enter])) "/api/categories")) "not requested again"))
    (is (= "skincare" (as/category-name (:state r1) "skincare")) "before it loads the slug is shown")
    (is (nil? (as/categories (:state (s/handle (:state r1) [:admin/categories-loaded {:status 500 :body {}}])))) "a failed load keeps falling back")))

(deftest import-drafts-and-product-form-show-category-names
  (let [cats [{:slug "perfumes" :name "Perfumes"} {:slug "body-shaping" :name "Body shaping (waist)"}]
        base (-> (st :route (routes/match-url "/admin/product-import"))
                 (assoc-in [:admin :session] {:user {:role "owner"}})
                 (assoc-in [:admin :categories] cats)
                 (assoc-in [:admin :data :drafts] {:status :ok :body {:items [{:id "d1" :name "Cream" :sku "C" :categorySlug "body-shaping" :price 5 :image {:id "i"}}]}}))]
    (is (has-text? (admin/view base) #"Body shaping \(waist\)")))
  (let [state (-> (st :route (routes/match-url "/admin/new-product"))
                  (assoc-in [:admin :session] {:user {:role "owner"}})
                  (assoc-in [:admin :categories] [{:slug "perfumes" :name "Perfumes Deluxe"}])
                  (assoc-in [:forms :product] {:values (as/product-values {}) :init :new}))]
    (is (has-text? (admin/view state) #"Perfumes Deluxe"))))

(deftest stock-history-is-readable
  (let [ms [{:id "1" :type "initial" :delta 10 :quantityAfter 10 :at "2026-10-24T09:30:00Z" :by {:name "Sandra"}}
            {:id "2" :type "adjustment" :reason "restock" :delta 5 :quantityAfter 15 :at "2026-10-24T09:30:00Z" :by {:name "Sandra"}}
            {:id "3" :type "adjustment" :reason "damagedOrLost" :delta -2 :quantityAfter 13 :at "2026-10-24T09:30:00Z" :by {:name "Sandra"}}
            {:id "4" :type "order" :delta -1 :quantityAfter 12 :orderNumber "ZT-7K3P9Q" :at "2026-10-24T22:30:00Z" :by nil}
            {:id "5" :type "orderCancelled" :delta 1 :quantityAfter 13 :orderNumber "ZT-7K3P9Q" :at "2026-10-24T23:30:00Z" :by {:name "Grace"}}
            {:id "6" :type "brandNew" :reason "mystery" :delta 3 :quantityAfter 16 :at "2026-10-24T09:30:00Z" :by nil}]
        view (w/stock-movement-list ms)]
    (doseq [re [#"Opening stock" #"Adjusted by staff" #"Customer order" #"Order cancelled, stock returned"
                #"Restock" #"Damaged or lost" #"\+5" #"−2" #"brandNew" #"mystery"
                #"25 Oct 2026, 01:30 \(Kampala time\)"]]
      (is (has-text? view re) (str re)))
    (testing "the order number links to the orders list searched by it"
      (let [links (find-nodes view (fn [n] (and (= :a (first n)) (re-find #"^/admin\?q=" (str (:href (second n)))))))]
        (is (= 2 (count links)))
        (is (= "/admin?q=ZT-7K3P9Q" (:href (second (first links)))))
        (is (= "/admin?q=ZT-7K3P9Q" (w/orders-search-path "ZT-7K3P9Q")))))
    (is (has-text? (w/stock-movement-list []) #"No movements yet"))))

(deftest orders-list-reads-q-from-the-url
  (let [state (st :route (routes/match-url "/admin?q=ZT-7K3P9Q"))]
    (is (= "ZT-7K3P9Q" (get-in (as/route-requests state) [:orders :query :q])))))

(def price-v
  "Sample cart validation where the price differs from the stored one."
  {:items [{:productId "p1" :unitPrice 90000 :name "Serum" :slug "serum" :image nil :problem nil}] :hasProblems false})

(deftest price-change-is-kept-until-seen
  (let [cart0 (cart/add-item cart/empty-cart serum 1 10)
        state (st :route (routes/match-url "/cart") :cart cart0)
        sig (cart/cart-signature cart0)]
    (testing "validation keeps the price the customer saw"
      (let [r (s/handle state [:cart/validated sig {:status 200 :body price-v}])]
        (is (= 85000 (get-in r [:state :cart :items 0 :price])))
        (is (= price-v (get-in r [:state :cart-check :result])))
        (testing "the Checkout click acknowledges it"
          (let [r2 (s/handle (:state r) [:cart/acknowledge-prices])]
            (is (= 90000 (get-in r2 [:state :cart :items 0 :price])))
            (is (seq (effects-named r2 :fx/save-cart)))))))
    (testing "after a 409 the cart shows the notice again"
      (let [acked (cart/acknowledge-prices cart0 {:items [{:productId "p1" :unitPrice 85000}]})
            back (:state (s/handle (st :route (routes/match-url "/checkout") :cart acked :order-key "k")
                                   [:checkout/result {:status 409 :body {:error {:code "cartOutdated" :message "x" :details []}}}]))
            r (s/handle (assoc back :route (routes/match-url "/cart")) [:cart/validated (cart/cart-signature acked) {:status 200 :body price-v}])
            line (first (:items (get-in r [:state :cart-check :result])))]
        (is (some? (order/price-changed-notice line (get-in r [:state :cart :items 0]))))))))

(deftest empty-checkout-goes-to-cart
  (testing "no items: replace /checkout with /cart"
    (let [r (s/handle (st) [:nav/go "/checkout"])]
      (is (some #(= [:fx/dispatch [:nav/replace "/cart"]] %) (:fx r)))))
  (testing "with items: stays"
    (let [r (s/handle (st :cart (cart/add-item cart/empty-cart serum 1 10)) [:nav/go "/checkout"])]
      (is (empty? (filter #(= [:fx/dispatch [:nav/replace "/cart"]] %) (:fx r)))))))

(deftest stock-dialog-mode-change-clears-stale-message
  (let [state (st :forms {:dialog {:values {:mode "remove"} :errors {:form "Stock cannot go below zero." :qty "x"}}})
        r (s/handle state [:form/set :dialog :mode "set"])]
    (is (nil? (get-in r [:state :forms :dialog :errors :form])))
    (is (= "set" (get-in r [:state :forms :dialog :values :mode])))))

(deftest cart-notice-shows-on-cart-only
  (let [r (s/handle (st :route (routes/match-url "/checkout") :cart (cart/add-item cart/empty-cart serum 1 10) :order-key "k")
                    [:checkout/result {:status 409 :body {:error {:code "cartOutdated" :message "x" :details []}}}])
        msg (get-in r [:state :ui :cart-notice])
        at-cart (s/handle (:state r) [:nav/go "/cart"])]
    (is (re-find #"Nothing was ordered" msg))
    (is (= msg (get-in at-cart [:state :ui :cart-notice])) "still there once /cart is shown")
    (is (nil? (get-in (s/handle (:state at-cart) [:nav/go "/shop"]) [:state :ui :cart-notice])) "gone after leaving the cart")))
