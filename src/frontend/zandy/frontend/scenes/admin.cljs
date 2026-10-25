(ns zandy.frontend.scenes.admin
  "Portfolio scenes for the staff area."
  (:require [portfolio.replicant :refer-macros [defscene]]
            [zandy.frontend.admin.core :as admin]
            [zandy.frontend.scenes.fixtures :as fx]
            [zandy.shared.pages :as pages]
            [zandy.shared.routes :as routes]))

(def owner {:user {:id "u1" :name "Sandra" :email "owner@example.test" :role "owner" :mustChangePassword false} :csrf "x"})

(defn- st [url data & {:as extra}]
  (merge (pages/initial-state)
         {:route (routes/match-url url) :site fx/site :page {:status :ok}
          :admin {:session owner :data data :ui {}}}
         extra))

(defn- ok [body] {:status :ok :body body})
(def loading {:status :loading})

(def order-list-item {:id "o1" :orderNumber "ZT-7K3P9Q" :status "placed" :placedAt "2026-10-24T09:30:00Z" :customerName "Amina Nakato"
                      :customerPhone "+256771234567" :city "Kampala" :itemCount 2 :itemsSubtotal 170000 :deliveryFee nil :total nil :currency "UGX" :paymentMethod "mobileMoney"})
(def orders {:items [order-list-item (assoc order-list-item :id "o2" :orderNumber "ZT-AAAAAA" :status "confirmed" :deliveryFee 10000 :total 180000)]
             :page 1 :pageSize 25 :totalItems 2 :counts {:placed 1 :confirmed 1 :approvedForDelivery 0 :delivered 3 :cancelled 1}})

(defscene login :title "Admin / sign in" (admin/view (assoc-in (st "/admin/login" {}) [:admin :session] nil)))
(defscene login-error :title "Admin / sign in, wrong password" (admin/view (assoc-in (st "/admin/login" {} :forms {:login {:errors {:form "Email or password is incorrect."}}}) [:admin :session] nil)))
(defscene forgot :title "Admin / forgot password" (admin/view (st "/admin/forgot-password" {})))
(defscene forgot-done :title "Admin / forgot password, sent" (admin/view (st "/admin/forgot-password" {} :forms {:forgot {:status :done}})))
(defscene reset :title "Admin / reset password" (admin/view (st "/admin/reset-password?token=abc" {})))
(defscene orders-loaded :title "Admin / orders" (admin/view (st "/admin" {:orders (ok orders)})))
(defscene orders-empty :title "Admin / orders, none" (admin/view (st "/admin?status=delivered" {:orders (ok (assoc orders :items [] :totalItems 0))})))
(defscene orders-loading :title "Admin / orders, loading" (admin/view (st "/admin" {:orders loading})))
(defscene orders-error :title "Admin / orders, failed" (admin/view (st "/admin" {:orders {:status :error :error "We could not load this."}})))
(def order-body {:order (assoc fx/order :version 1 :staffNote nil)
                 :agreementAcceptance {:type "orderTerms" :version "2026-10-01" :contentHash "sha256:abc" :acceptedAt "2026-10-24T09:30:00Z" :customerName "Amina Nakato" :customerPhone "+256771234567"}
                 :history [{:status "placed" :at "2026-10-24T09:30:00Z" :by nil :note nil}]})
(defscene order-new :title "Admin / order, no fee yet" (admin/view (st "/admin/orders/o1" {:order (ok order-body)})))
(defscene order-fee :title "Admin / order, fee recorded" (admin/view (st "/admin/orders/o1" {:order (ok (-> order-body (assoc-in [:order :deliveryFee] 10000) (assoc-in [:order :total] 180000)))})))
(defscene order-dispatched :title "Admin / order, out for delivery" (admin/view (st "/admin/orders/o1" {:order (ok (-> order-body (assoc-in [:order :status] "approvedForDelivery") (assoc-in [:order :deliveryFee] 10000)))})))
(defscene order-delivered :title "Admin / order, closed" (admin/view (st "/admin/orders/o1" {:order (ok (-> order-body (assoc-in [:order :status] "delivered") (assoc-in [:order :deliveryFee] 10000) (assoc-in [:order :total] 180000)))})))
(defscene order-cancel-dialog :title "Admin / order, cancel dialog" (admin/view (update (st "/admin/orders/o1" {:order (ok order-body)}) :admin assoc-in [:ui :dialog] {:type :status :target "cancelled"})))
(defscene order-deliver-dialog :title "Admin / order, delivered dialog with error" (admin/view (-> (st "/admin/orders/o1" {:order (ok (assoc-in order-body [:order :status] "approvedForDelivery"))} :forms {:dialog {:errors {:paid "Choose how the customer paid."}}}) (assoc-in [:admin :ui :dialog] {:type :status :target "delivered"}))))
(defscene order-conflict :title "Admin / order, version conflict" (admin/view (assoc-in (st "/admin/orders/o1" {:order (ok order-body)}) [:admin :ui :conflict] "This record was updated by someone else a moment ago.")))
(def product-item {:id "p1" :slug "glow-serum" :sku "SEED-SK-01" :name "Radiance Vitamin C Serum 30 ml" :brand "Zandy Touch" :categorySlug "skincare" :price 85000 :currency "UGX"
                   :stockQuantity 24 :availability "available" :lowStock false :status "published" :image (fx/img 1) :updatedAt "2026-10-24T09:30:00Z" :updatedBy {:id "u1" :name "Sandra"}})
(def products {:items [product-item (assoc product-item :id "p2" :name "Daily Moisturising Cream" :stockQuantity 3 :lowStock true)
                       (assoc product-item :id "p3" :name "Mineral Sunscreen" :stockQuantity 0 :availability "soldOut")
                       (assoc product-item :id "p4" :name "Unpriced draft" :price nil :status "draft" :stockQuantity 0)]
               :page 1 :pageSize 25 :totalItems 4 :counts {:out 1 :low 1}})
(defscene products-list :title "Admin / products" (admin/view (st "/admin/products" {:products (ok products)})))
(defscene products-stock-dialog :title "Admin / products, adjust stock dialog" (admin/view (-> (st "/admin/products" {:products (ok products)} :forms {:dialog {:values {:mode "set"}}}) (assoc-in [:admin :ui :dialog] {:type :stock :id "p2" :current 3 :name "Daily Moisturising Cream"}))))
(defscene products-stock-conflict :title "Admin / products, stock conflict" (admin/view (-> (st "/admin/products" {:products (ok products)} :forms {:dialog {:values {:mode "set" :qty "5"} :errors {:form "The stock changed while you were working. The latest count has been loaded. Please check it and confirm again."}}}) (assoc-in [:admin :ui :dialog] {:type :stock :id "p2" :current 3 :name "Daily Moisturising Cream"}))))
(defscene products-archive-dialog :title "Admin / products, remove dialog" (admin/view (assoc-in (st "/admin/products" {:products (ok products)}) [:admin :ui :dialog] {:type :archive :id "p1" :name "Radiance Vitamin C Serum 30 ml"})))
(defscene product-new :title "Admin / new product (pictures wait for the first save)" (admin/view (st "/admin/new-product" {} :forms {:product {:values {:status "draft" :stock "0" :images []}}})))
(defscene product-new-errors :title "Admin / new product, publish blocked" (admin/view (st "/admin/new-product" {} :forms {:product {:values {:status "published" :stock "0" :images []} :errors {:name "Enter the product name." :price "A product needs a price before it can be published." :images "A product needs at least one picture before it can be published. Save it as a draft, add pictures, then publish."}}})))
(def edit-values {:name "Radiance Vitamin C Serum 30 ml" :brand "Zandy Touch" :category "skincare" :status "published" :price "85000"})
(def movements (ok {:items [{:id "m1" :type "adjustment" :reason "restock" :delta 20 :quantityAfter 24 :at "2026-10-24T09:30:00Z" :by {:id "u1" :name "Sandra"}}
                            {:id "m2" :type "order" :delta -2 :quantityAfter 4 :orderNumber "ZT-7K3P9Q" :at "2026-10-24T10:30:00Z" :by nil}]}))
(defn- edit-product [images forms-extra]
  (admin/view (st "/admin/products/p1" {:product (ok (assoc product-item :version 3 :images [(fx/img 1) (fx/img 2)])) :movements movements}
                  :forms (merge {:product {:values (assoc edit-values :images images) :init "p1"}} forms-extra))))
(defscene product-edit :title "Admin / edit product with stock panel and pictures" (edit-product [(fx/img 1) (fx/img 2)] {}))
(defscene product-edit-no-pictures :title "Admin / edit product, no pictures yet" (admin/view (st "/admin/products/p1" {:product (ok (assoc product-item :version 3 :status "draft" :images [])) :movements movements}
                                                                                              :forms {:product {:values (assoc edit-values :status "draft" :images []) :init "p1"}})))
(defscene product-edit-uploading :title "Admin / edit product, pictures uploading" (edit-product [(fx/img 1) (fx/img 2)] {:product-images {:status :uploading :items []}}))
(defscene product-edit-upload-errors :title "Admin / edit product, upload with errors"
  (edit-product [(fx/img 1) (fx/img 2) (fx/img 3)]
                {:product-images {:items [{:filename "front.jpg" :status "attached" :position 3 :image (fx/img 3) :error nil}
                                          {:filename "huge.png" :status "rejected" :error {:code "fileTooLarge" :message "This file is larger than 5 MB."}}
                                          {:filename "notes.pdf" :status "rejected" :error {:code "unsupportedMediaType" :message "Only JPEG, PNG or WebP pictures can be used."}}]}}))
(defscene product-edit-publish-blocked :title "Admin / edit product, image required"
  (admin/view (st "/admin/products/p1" {:product (ok (assoc product-item :version 3 :status "draft" :images [])) :movements movements}
                  :forms {:product {:values (assoc edit-values :images []) :init "p1"
                                    :errors {:images "A product needs at least one picture before it can be published."}}})))
(defscene inbox :title "Admin / inbox, messages" (admin/view (st "/admin/inbox" {:messages (ok {:items [{:id "m1" :name "Amina" :phone "+256771234567" :message "Do you have this in stock?" :receivedAt "2026-10-24T09:30:00Z"}]})})))
(defscene inbox-subscribers :title "Admin / inbox, subscribers" (admin/view (st "/admin/inbox?tab=subscribers" {:subscribers (ok {:items [{:id "s1" :email "a@b.co" :subscribedAt "2026-10-24T09:30:00Z" :source "footer" :active true}]})})))
(def staff {:items [{:id "u1" :name "Sandra" :email "owner@example.test" :role "owner" :active true :lastSignInAt "2026-10-24T09:30:00Z"}
                    {:id "u2" :name "Grace" :email "grace@example.test" :role "support" :active true :lastSignInAt nil}]})
(defscene staff-list :title "Admin / staff" (admin/view (st "/admin/staff" {:staff (ok staff)})))
(defscene staff-temp :title "Admin / staff, temporary password shown once" (admin/view (assoc-in (st "/admin/staff" {:staff (ok staff)}) [:admin :ui :temp-password] {:for "Grace" :value "tmp-pass-1"})))
(defscene staff-forbidden :title "Admin / staff, support role" (admin/view (-> (st "/admin/staff" {:forbidden {:status :error}}) (assoc-in [:admin :session :user :role] "support"))))
(defscene account :title "Admin / my account" (admin/view (st "/admin/account" {})))
(defscene account-temp :title "Admin / my account, temporary password" (admin/view (assoc-in (st "/admin/account" {}) [:admin :session :user :mustChangePassword] true)))

;; A9 bulk import ---------------------------------------------------------------
(def draft-ready (assoc product-item :id "d1" :sku "ZT-SKIN-001" :name "Brightening cream 50 g" :status "draft"))
(def draft-no-picture (assoc product-item :id "d2" :sku "ZT-PERF-002" :name "Eau de parfum 50 ml" :status "draft" :image nil))
(def draft-no-price (assoc product-item :id "d3" :sku "ZT-BODY-003" :name "Shea body butter" :status "draft" :price nil))
(def draft-nothing (assoc product-item :id "d4" :sku "ZT-HLTH-004" :name "Moringa powder 200 g" :status "draft" :price nil :image nil))
(defn- drafts [& items] (ok {:items (vec items) :page 1 :pageSize 100 :totalItems (count items)}))
(defn- import-page [forms-extra & [data]]
  (admin/view (st "/admin/product-import" (merge {:drafts (drafts)} data) :forms forms-extra)))

(def csv-file {:name "zandy-touch-products.csv" :size 2411})
(def dry-report
  {:dryRun true :summary {:rows 4 :created 2 :updated 1 :rejected 1}
   :rows [{:line 2 :sku "ZT-SKIN-001" :status "created" :productId nil :warnings [] :errors []}
          {:line 3 :sku "ZT-PERF-002" :status "updated" :productId "p2" :warnings [{:field "openingStock" :code "ignoredForExisting"}] :errors []}
          {:line 4 :sku "ZT-BODY-003" :status "rejected" :productId nil :warnings []
           :errors [{:field "price" :code "invalid" :message "Price must be a whole number of shillings."}
                    {:field "category" :code "invalid" :message "No category called Bedroom."}]}
          {:line 5 :sku "ZT-HLTH-004" :status "created" :productId nil :warnings [] :errors []}]})
(def real-report (-> dry-report (assoc :dryRun false)
                     (update :rows (fn [rows] (mapv #(cond-> % (= "created" (:status %)) (assoc :productId "d1")) rows)))))

(defscene import-empty :title "Admin / import, nothing chosen yet" (import-page {}))
(defscene import-csv-chosen :title "Admin / import, CSV chosen" (import-page {:import-csv {:file csv-file}}))
(defscene import-csv-too-big :title "Admin / import, CSV too large" (import-page {:import-csv {:error "That file is larger than 1 MB. Split it into smaller files of up to 500 rows."}}))
(defscene import-csv-checking :title "Admin / import, checking the file" (import-page {:import-csv {:file csv-file :dry {:status :running}}}))
(defscene import-dry-run :title "Admin / import, check result with field errors and warnings"
  (import-page {:import-csv {:file csv-file :dry {:status :ok :report dry-report}}}))
(defscene import-file-rejected :title "Admin / import, whole file rejected"
  (import-page {:import-csv {:file csv-file :dry {:status :error :errors ["Unknown column: colour" "The first line must contain a sku column."]}}}))
(defscene import-done :title "Admin / import, import finished"
  (import-page {:import-csv {:file csv-file :dry {:status :ok :report dry-report} :real {:status :ok :report real-report}}
                :import-review {:imported-ids ["d1" "d2" "d3" "d4"]}}
               {:drafts (drafts draft-ready draft-no-picture draft-no-price draft-nothing)}))
(defscene import-pictures-ready :title "Admin / import, pictures chosen"
  (import-page {:import-pictures {:status :ready :queue (vec (repeat 87 {:name "ZT-SKIN-001-1.jpg" :size 400000 :type "image/jpeg"})) :results []}}))
(defscene import-pictures-uploading :title "Admin / import, pictures uploading (batch 3 of 5)"
  (import-page {:import-pictures {:status :uploading :queue (vec (repeat 87 {:name "a.jpg"})) :index 2 :total 87
                                  :results (vec (repeat 40 {:filename "ZT-SKIN-001-1.jpg" :status "attached" :sku "ZT-SKIN-001" :position 1}))}}))
(def picture-results
  [{:filename "ZT-SKIN-001-1.jpg" :status "attached" :sku "ZT-SKIN-001" :position 1 :image (fx/img 1) :error nil}
   {:filename "ZT-SKIN-001-2.jpg" :status "attached" :sku "ZT-SKIN-001" :position 2 :image (fx/img 2) :error nil}
   {:filename "holiday.jpg" :status "rejected" :error {:code "unknownSku" :message "No product has a SKU at the start of this file name."}}
   {:filename "ZT-PERF-002-1.png" :status "rejected" :error {:code "fileTooLarge" :message "This file is larger than 5 MB."}}
   {:filename "ZT-BODY-003-4.jpg" :status "rejected" :error {:code "tooManyImages" :message "This product already has 6 pictures."}}
   {:filename "ZT-HLTH-004-1.jpg" :status "rejected" :error {:code "uploadFailed" :message "The upload did not reach the server. Try again."} :retry-file {:name "ZT-HLTH-004-1.jpg"}}])
(defscene import-pictures-results-with-errors :title "Admin / import, picture results with errors and a retry"
  (import-page {:import-pictures {:status :done :queue [] :results picture-results}}))
(defscene import-pictures-all-attached :title "Admin / import, every picture attached"
  (import-page {:import-pictures {:status :done :queue [] :results (subvec picture-results 0 2)}}))
(defscene import-drafts-mixed :title "Admin / import, drafts that still lack something"
  (import-page {:import-review {:imported-ids ["d1" "d2" "d3" "d4"]}} {:drafts (drafts draft-ready draft-no-picture draft-no-price draft-nothing)}))
(defscene import-drafts-all-complete :title "Admin / import, all drafts complete"
  (import-page {} {:drafts (drafts draft-ready (assoc draft-ready :id "d5" :sku "ZT-SKIN-005" :name "Glow serum 30 ml"))}))
(defscene import-drafts-none :title "Admin / import, no drafts" (import-page {}))
(defscene import-drafts-all-scope :title "Admin / import, showing every draft"
  (import-page {:import-review {:imported-ids ["d1"] :scope :all}} {:drafts (drafts draft-ready draft-no-price)}))
(defscene import-published-partly :title "Admin / import, publish result with rejections"
  (import-page {:import-review {:imported-ids ["d1" "d2" "d3" "d4"]
                                :publish {:status :done :results [{:productId "d1" :name "Brightening cream 50 g" :status "published"}
                                                                  {:productId "d3" :name "Shea body butter" :status "rejected" :errors [{:field "price" :code "priceRequired"}]}]}}}
               {:drafts (drafts draft-no-picture draft-no-price)}))
(defscene import-published-all :title "Admin / import, everything published"
  (import-page {:import-review {:imported-ids ["d1"] :publish {:status :done :results [{:productId "d1" :name "Brightening cream 50 g" :status "published"}]}}}
               {:drafts (drafts)}))
(defscene import-drafts-loading :title "Admin / import, drafts loading" (import-page {} {:drafts loading}))
(defscene import-drafts-error :title "Admin / import, drafts failed to load" (import-page {} {:drafts {:status :error :error "We could not load this."}}))

;; Restore, readable stock history, category names, Kampala time -----------------
(def categories-loaded
  [{:slug "skincare" :name "Skincare"} {:slug "body-shaping" :name "Body-shaping"} {:slug "health-and-wellness" :name "Health & Wellness"}])
(defn- with-categories [state] (assoc-in state [:admin :categories] categories-loaded))

(def archived-item (assoc product-item :id "p5" :name "Discontinued Body Oil" :categorySlug "health-and-wellness" :status "archived" :stockQuantity 7
                          :updatedAt "2026-10-24T22:30:00Z"))
(def archived-products {:items [archived-item (assoc archived-item :id "p6" :name "Old Perfume" :categorySlug "perfumes" :price nil :image nil)]
                        :page 1 :pageSize 25 :totalItems 2 :counts {:out 0 :low 0}})
(defscene products-archived-tab :title "Admin / products, Archived tab (Edit and Restore; category names; Kampala date)"
  (admin/view (with-categories (st "/admin/products?status=archived" {:products (ok archived-products)}))))
(defscene products-archived-restoring :title "Admin / products, Archived tab, restoring"
  (admin/view (-> (st "/admin/products?status=archived" {:products (ok archived-products)}) with-categories (assoc-in [:admin :ui :restoring] "p5"))))
(defscene products-restore-rejected :title "Admin / products, restore rejected (reasons and restore as a draft)"
  (admin/view (-> (st "/admin/products?status=archived" {:products (ok archived-products)}) with-categories
                  (assoc-in [:admin :ui :restore-rejected] {:id "p6" :name "Old Perfume" :reasons ["It has no price." "It has no picture."]}))))
(defscene products-restored-notice :title "Admin / products, restored"
  (admin/view (-> (st "/admin/products?status=archived" {:products (ok (assoc archived-products :items [(second (:items archived-products))]))}) with-categories
                  (assoc-in [:admin :ui :notice] "Discontinued Body Oil restored and published."))))

(def archived-body (assoc product-item :id "p5" :name "Discontinued Body Oil" :status "archived" :version 6 :images [(fx/img 1)]))
(defn- archived-edit [ui-extra]
  (admin/view (-> (st "/admin/products/p5" {:product (ok archived-body) :movements movements}
                      :forms {:product {:values (assoc edit-values :name "Discontinued Body Oil" :status "archived" :images [(fx/img 1)]) :init "p5"}})
                  with-categories
                  (update-in [:admin :ui] merge ui-extra))))
(defscene product-edit-archived :title "Admin / edit product, archived (notice, Restore, no Remove, no status radios)" (archived-edit {}))
(defscene product-edit-archived-rejected :title "Admin / edit product, archived, restore rejected"
  (archived-edit {:restore-rejected {:id "p5" :name "Discontinued Body Oil" :reasons ["It has no price."]}}))

(def all-movements
  (ok {:items [{:id "m1" :type "initial" :reason nil :delta 20 :quantityAfter 20 :at "2026-10-20T08:00:00Z" :by {:id "u1" :name "Sandra"}}
               {:id "m2" :type "adjustment" :reason "restock" :delta 10 :quantityAfter 30 :at "2026-10-21T08:00:00Z" :by {:id "u1" :name "Sandra"}}
               {:id "m3" :type "adjustment" :reason "stocktake" :delta -1 :quantityAfter 29 :at "2026-10-21T09:00:00Z" :by {:id "u2" :name "Grace"}}
               {:id "m4" :type "adjustment" :reason "shopSale" :delta -2 :quantityAfter 27 :at "2026-10-21T10:00:00Z" :by {:id "u2" :name "Grace"}}
               {:id "m5" :type "adjustment" :reason "damagedOrLost" :delta -3 :quantityAfter 24 :at "2026-10-22T10:00:00Z" :by {:id "u2" :name "Grace"}}
               {:id "m6" :type "adjustment" :reason "returnedGoods" :delta 2 :quantityAfter 26 :at "2026-10-22T11:00:00Z" :by {:id "u1" :name "Sandra"}}
               {:id "m7" :type "adjustment" :reason "other" :delta 1 :quantityAfter 27 :at "2026-10-23T11:00:00Z" :by {:id "u1" :name "Sandra"}}
               {:id "m8" :type "order" :reason nil :delta -2 :quantityAfter 25 :orderNumber "ZT-7K3P9Q" :at "2026-10-24T22:30:00Z" :by nil}
               {:id "m9" :type "orderCancelled" :reason nil :delta 2 :quantityAfter 27 :orderNumber "ZT-7K3P9Q" :at "2026-10-25T08:00:00Z" :by {:id "u1" :name "Sandra"}}
               {:id "m10" :type "futureType" :reason "futureReason" :delta 1 :quantityAfter 28 :at "2026-10-25T09:00:00Z" :by nil}]}))
(defscene stock-history-every-type-and-reason :title "Admin / stock history, every type and reason (and an unknown code)"
  (admin/view (with-categories (st "/admin/products/p1" {:product (ok (assoc product-item :version 3 :images [(fx/img 1)])) :movements all-movements}
                                   :forms {:product {:values (assoc edit-values :images [(fx/img 1)]) :init "p1"}}))))
(defscene stock-history-empty :title "Admin / stock history, none yet"
  (admin/view (st "/admin/products/p1" {:product (ok (assoc product-item :version 3 :images [(fx/img 1)])) :movements (ok {:items []})}
                  :forms {:product {:values (assoc edit-values :images [(fx/img 1)]) :init "p1"}})))
(defscene import-drafts-category-names :title "Admin / import drafts with category names"
  (admin/view (with-categories (st "/admin/product-import" {:drafts (drafts (assoc draft-ready :categorySlug "health-and-wellness") (assoc draft-no-price :categorySlug "unknown-slug"))}))))
(defscene product-new-category-names :title "Admin / new product, category names from the API"
  (admin/view (with-categories (st "/admin/new-product" {} :forms {:product {:values {:status "draft" :stock "0" :images []}}}))))
(defscene dates-kampala :title "Admin / dates in Kampala time (order crossing midnight, messages, subscribers, staff sign-in)"
  [:div
   (admin/view (st "/admin" {:orders (ok {:items [(assoc order-list-item :placedAt "2026-10-24T22:30:00Z")] :page 1 :pageSize 25 :totalItems 1 :counts {}})}))
   (admin/view (st "/admin/inbox" {:messages (ok {:items [{:id "m1" :name "Amina" :phone "+256771234567" :message "Late message" :receivedAt "2026-12-31T23:59:00Z"}]})}))
   (admin/view (st "/admin/staff" {:staff (ok staff)}))])
