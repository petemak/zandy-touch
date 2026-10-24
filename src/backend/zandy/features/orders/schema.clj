(ns zandy.features.orders.schema
  (:require [zandy.platform.validation :as v]))

(def uuid-string v/UuidString)

(def CartValidation
  [:map {:closed true}
   [:items [:vector {:min 1 :max 20}
            [:map {:closed true}
             [:product-id uuid-string]
             [:quantity [:int {:min 1}]]]]]])

(def PlaceOrder
  [:map {:closed true}
   [:customer [:map {:closed true}
               [:name [:string {:min 2 :max 100}]]
               [:phone v/Phone]
               [:email {:optional true} v/Email]
               [:preferred-contact [:enum "phone" "whatsapp"]]]]
   [:delivery [:map {:closed true}
               [:country-code [:enum "UG"]]
               [:city [:string {:min 2 :max 60}]]
               [:area [:string {:min 2 :max 80}]]
               [:address-line [:string {:min 3 :max 200}]]
               [:directions {:optional true} [:string {:max 300}]]]]
   [:payment-method [:enum "cash" "mobileMoney"]]
   [:note {:optional true} [:string {:max 500}]]
   [:items [:vector {:min 1 :max 20}
            [:map {:closed true}
             [:product-id uuid-string]
             [:quantity [:int {:min 1}]]
             [:expected-unit-price [:int {:min 0}]]]]]
   [:agreement [:map {:closed true}
                [:type [:enum "orderTerms"]]
                [:version [:string {:min 1 :max 40}]]]]
   [:website {:optional true} [:string {:max 200}]]])

(def OrderTracking
  [:map {:closed true}
   [:order-number [:string {:min 3 :max 20}]]
   [:phone v/Phone]])

(def AdminOrdersQuery
  [:map
   [:status {:optional true} [:enum "placed" "confirmed" "approvedForDelivery" "delivered" "cancelled"]]
   [:q {:optional true} [:string {:max 80}]]
   [:page {:optional true} v/Page]
   [:page-size {:optional true} v/PageSize]
   [:sort {:optional true} [:enum "placedAtDesc" "placedAtAsc"]]])

(def PatchOrder
  [:map {:closed true}
   [:version [:int {:min 1}]]
   [:delivery-fee {:optional true} [:int {:min 0 :max 1000000}]]
   [:staff-note {:optional true} [:string {:max 1000}]]])

(def StatusChange
  [:map {:closed true}
   [:version [:int {:min 1}]]
   [:status [:enum "placed" "confirmed" "approvedForDelivery" "delivered" "cancelled"]]
   [:note {:optional true} [:string {:max 300}]]
   [:payment-method-used {:optional true} [:enum "cash" "mobileMoney"]]
   [:cancel-reason {:optional true} [:string {:min 3 :max 300}]]])
