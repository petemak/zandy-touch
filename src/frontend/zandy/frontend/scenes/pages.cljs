(ns zandy.frontend.scenes.pages
  "Portfolio scenes for the whole public pages P1 to P8 and the error pages:
  loaded, empty, error and edge states (sold out, no photo, cart problems,
  checkout errors, tracking results). Each scene renders
  shared.pages/public-view for a fixture state. Browser only; not reachable
  from frontend.main."
  (:require [portfolio.replicant :refer-macros [defscene]]
            [zandy.frontend.scenes.fixtures :as fx :refer [base-state]]
            [zandy.shared.pages :as pages]))

(defn- ok [data] {:status :ok :data data})
(defn- view [state] (pages/public-view state))

(def home-data
  "Page data for P1 Home: categories plus the new, own-brand and featured
  listings."
  {:categories fx/categories :new fx/listing :own (update fx/listing :items #(vec (take 2 %))) :featured fx/listing})

(defscene home :title "Page / home" (view (base-state "/" (ok home-data))))
(defscene home-minimal :title "Page / home, no own-brand or featured" (view (base-state "/" (ok (assoc home-data :own {:items []} :featured {:items []})))))
(defscene shop :title "Page / shop" (view (base-state "/shop" (ok {:products fx/listing :categories fx/categories}))))
(defscene shop-more :title "Page / shop, more to load" (view (base-state "/shop" (ok {:products fx/listing-more :categories fx/categories}))))
(defscene shop-search :title "Page / shop, search results" (view (base-state "/shop?q=serum" (ok {:products (assoc fx/listing :totalItems 1 :items [fx/serum]) :categories fx/categories}))))
(defscene shop-empty :title "Page / shop, nothing matched" (view (base-state "/shop?q=zzz" (ok {:products {:items [] :page 1 :pageSize 24 :totalItems 0} :categories fx/categories}))))
(defscene category :title "Page / category" (view (base-state "/categories/skincare" (ok {:products fx/listing :categories fx/categories}))))
(defscene product :title "Page / product, available" (view (base-state "/products/glow-serum" (ok fx/product-detail))))
(defscene product-sold-out :title "Page / product, sold out" (view (base-state "/products/glow-serum" (ok (assoc fx/product-detail :availability "soldOut" :maxQuantity 0)))))
(defscene product-no-photo :title "Page / product, no photo" (view (base-state "/products/glow-serum" (ok (assoc fx/product-detail :images [])))))
(defscene cart-empty :title "Page / cart, empty" (view (base-state "/cart" (ok nil))))
(defscene cart-ok :title "Page / cart, validated" (view (base-state "/cart" (ok nil) :cart {:items fx/cart-items} :cart-check {:status :ready :result fx/validation-ok})))
(defscene cart-checking :title "Page / cart, checking" (view (base-state "/cart" (ok nil) :cart {:items fx/cart-items} :cart-check {:status :loading})))
(defscene cart-problems :title "Page / cart, problems" (view (base-state "/cart" (ok nil) :cart {:items fx/cart-items} :cart-check {:status :ready :result fx/validation-problems})))
(defscene cart-error :title "Page / cart, validation unavailable" (view (base-state "/cart" (ok nil) :cart {:items fx/cart-items} :cart-check {:status :error})))
(def checkout-refs
  "Reference data checkout needs: delivery areas and the approved order terms."
  {:delivery-areas fx/areas :policies {"orderTerms" fx/policy} :policy-status {"orderTerms" :ready}})
(defscene checkout-empty :title "Page / checkout, empty cart" (view (base-state "/checkout" (ok nil))))
(defscene checkout-form :title "Page / checkout, new" (view (base-state "/checkout" (ok nil) :cart {:items fx/cart-items} :cart-check {:status :ready :result fx/validation-ok} :refs checkout-refs)))
(defscene checkout-errors :title "Page / checkout, field errors" (view (base-state "/checkout" (ok nil) :cart {:items fx/cart-items} :cart-check {:status :ready :result fx/validation-ok} :refs checkout-refs
                                                                                     :forms {:checkout {:values {:name "A" :phone "123" :accepted? false} :errors {:name "Enter your full name." :phone "Enter a valid Uganda phone number, for example 0771 234 567." :city "Choose your city." :accepted "Please accept the order terms to place your order."}}})))
(defscene checkout-submitting :title "Page / checkout, placing order" (view (base-state "/checkout" (ok nil) :cart {:items fx/cart-items} :cart-check {:status :ready :result fx/validation-ok} :refs checkout-refs
                                                                                        :forms {:checkout {:values {:accepted? true} :status :submitting}})))
(defscene checkout-retry :title "Page / checkout, network failure (safe retry)" (view (base-state "/checkout" (ok nil) :cart {:items fx/cart-items} :cart-check {:status :ready :result fx/validation-ok} :refs checkout-refs
                                                                                     :forms {:checkout {:values {:accepted? true} :retry? true}})))
(defscene checkout-terms-error :title "Page / checkout, terms failed to load" (view (base-state "/checkout" (ok nil) :cart {:items fx/cart-items} :refs (assoc-in checkout-refs [:policy-status "orderTerms"] :error))))
(defscene order-placed :title "Page / order placed" (view (base-state "/order-placed" (ok nil) :last-order {:order fx/order})))
(defscene order-placed-none :title "Page / order placed, nothing to show" (view (base-state "/order-placed" (ok nil))))
(defscene track :title "Page / track, form" (view (base-state "/track-order" (ok nil))))
(defscene track-result :title "Page / track, result" (view (base-state "/track-order" (ok nil) :forms {:track {:values {:order-number "ZT-7K3P9Q" :phone "0771234567"} :result fx/tracking}})))
(defscene track-error :title "Page / track, not found" (view (base-state "/track-order" (ok nil) :forms {:track {:errors {:form "We could not find an order with those details. Check the order number and phone number."}}})))
(defscene about :title "Page / about" (view (base-state "/about" (ok nil))))
(defscene contact :title "Page / contact" (view (base-state "/contact" (ok nil))))
(defscene contact-errors :title "Page / contact, errors" (view (base-state "/contact" (ok nil) :forms {:contact {:values {:name "A" :message "short"} :errors {:name "Enter your name." :phone "Give a phone number or an email address." :message "Write between 10 and 1000 characters."}}})))
(defscene contact-done :title "Page / contact, sent" (view (base-state "/contact" (ok nil) :forms {:contact {:status :done}})))
(defscene contact-minimal :title "Page / contact, unconfirmed details hidden" (view (assoc (base-state "/contact" (ok nil)) :site fx/site-minimal)))
(defscene delivery :title "Page / delivery and payment" (view (base-state "/delivery-and-payment" (ok fx/areas))))
(defscene legal-draft :title "Page / legal, draft text" (view (base-state "/legal/order-terms" (ok fx/policy))))
(defscene legal-approved :title "Page / legal, approved" (view (base-state "/legal/privacy" (ok fx/policy-approved))))
(defscene unsubscribe :title "Page / unsubscribe" (view (base-state "/unsubscribe?token=abc" (ok nil))))
(defscene unsubscribe-done :title "Page / unsubscribe, done" (view (base-state "/unsubscribe?token=abc" (ok nil) :forms {:unsubscribe {:status :done}})))
(defscene not-found :title "Page / not found" (view (base-state "/nope" {:status :not-found})))
(defscene unavailable :title "Page / service unavailable (503)" (view (base-state "/shop" {:status :unavailable})))
(defscene loading :title "Page / loading" (view (base-state "/shop" {:status :loading})))
