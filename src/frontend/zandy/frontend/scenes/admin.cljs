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
(defscene product-new :title "Admin / new product" (admin/view (st "/admin/new-product" {:photos (ok {:items [{:id "ph1" :urls (:urls (fx/img 1))}]})} :forms {:product {:values {:status "draft" :stock "0" :images []}}})))
(defscene product-new-errors :title "Admin / new product, publish blocked" (admin/view (st "/admin/new-product" {:photos (ok {:items []})} :forms {:product {:values {:status "published" :stock "0" :images []} :errors {:name "Enter the product name." :price "A product needs a price before it can be published." :images "A product needs at least one photo before it can be published."}}})))
(defscene product-edit :title "Admin / edit product with stock panel"
  (admin/view (st "/admin/products/p1" {:product (ok (assoc product-item :version 3 :images [(fx/img 1)]))
                                       :movements (ok {:items [{:id "m1" :type "adjustment" :reason "restock" :delta 20 :quantityAfter 24 :at "2026-10-24T09:30:00Z" :by {:id "u1" :name "Sandra"}}
                                                               {:id "m2" :type "order" :delta -2 :quantityAfter 4 :orderNumber "ZT-7K3P9Q" :at "2026-10-24T10:30:00Z" :by nil}]})
                                       :photos (ok {:items []})}
                  :forms {:product {:values {:name "Radiance Vitamin C Serum 30 ml" :brand "Zandy Touch" :category "skincare" :status "published" :price "85000" :images [(fx/img 1)]} :init "p1"}})))
(defscene photos :title "Admin / photo library" (admin/view (st "/admin/photos" {:photos (ok {:items [{:id "ph1" :filename "SEED-SK-01-1.jpg" :urls (:urls (fx/img 1)) :assignedProductIds []}]}) :products (ok products)})))
(defscene photos-uploaded :title "Admin / photos, upload result" (admin/view (st "/admin/photos" {:photos (ok {:items []}) :products (ok products)}
                                                                                 :forms {:upload {:status :done :items [{:filename "a.jpg" :status "stored" :photo {:id "ph9"} :suggestedProductId "p1"} {:filename "huge.png" :status "rejected" :error {:message "This file is larger than 5 MB."}}]}})))
(defscene inbox :title "Admin / inbox, messages" (admin/view (st "/admin/inbox" {:messages (ok {:items [{:id "m1" :name "Amina" :phone "+256771234567" :message "Do you have this in stock?" :receivedAt "2026-10-24T09:30:00Z"}]})})))
(defscene inbox-subscribers :title "Admin / inbox, subscribers" (admin/view (st "/admin/inbox?tab=subscribers" {:subscribers (ok {:items [{:id "s1" :email "a@b.co" :subscribedAt "2026-10-24T09:30:00Z" :source "footer" :active true}]})})))
(def staff {:items [{:id "u1" :name "Sandra" :email "owner@example.test" :role "owner" :active true :lastSignInAt "2026-10-24T09:30:00Z"}
                    {:id "u2" :name "Grace" :email "grace@example.test" :role "support" :active true :lastSignInAt nil}]})
(defscene staff-list :title "Admin / staff" (admin/view (st "/admin/staff" {:staff (ok staff)})))
(defscene staff-temp :title "Admin / staff, temporary password shown once" (admin/view (assoc-in (st "/admin/staff" {:staff (ok staff)}) [:admin :ui :temp-password] {:for "Grace" :value "tmp-pass-1"})))
(defscene staff-forbidden :title "Admin / staff, support role" (admin/view (-> (st "/admin/staff" {:forbidden {:status :error}}) (assoc-in [:admin :session :user :role] "support"))))
(defscene account :title "Admin / my account" (admin/view (st "/admin/account" {})))
(defscene account-temp :title "Admin / my account, temporary password" (admin/view (assoc-in (st "/admin/account" {}) [:admin :session :user :mustChangePassword] true)))
