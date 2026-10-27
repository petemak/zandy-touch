(ns zandy.features.orders.schema
  "Orders slice, schema role: Malli schemas for cart validation, order placement, tracking and the staff order endpoints (contract 3 cart-validations, 4, 7). Bodies are closed maps; unknown fields are rejected."
  (:require [zandy.platform.validation :as v]))

(def uuid-string
  "Alias of validation/UuidString: a string holding a UUID."
  v/UuidString)

(def CartValidation
  "Body of POST /api/cart-validations: 1 to 20 items {:product-id :quantity >= 1}."
  [:map {:closed true}
   [:items [:vector {:min 1 :max 20}
            [:map {:closed true}
             [:product-id uuid-string]
             [:quantity [:int {:min 1}]]]]]])

(def PlaceOrder
  "Body of POST /api/orders: customer (name, Ugandan phone, optional email, preferred contact), delivery address (country UG), payment method, optional note, 1 to 20 items with the price the customer saw (:expected-unit-price, whole UGX), the order-terms agreement version, and the honeypot field website."
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
  "Body of POST /api/order-trackings: order number and the phone used when ordering."
  [:map {:closed true}
   [:order-number [:string {:min 3 :max 20}]]
   [:phone v/Phone]])

(def AdminOrdersQuery
  "Query for GET /api/admin/orders: status, q, page, pageSize and sort (placedAtDesc or placedAtAsc)."
  [:map
   [:status {:optional true} [:enum "placed" "confirmed" "approvedForDelivery" "delivered" "cancelled"]]
   [:q {:optional true} [:string {:max 80}]]
   [:page {:optional true} v/Page]
   [:page-size {:optional true} v/PageSize]
   [:sort {:optional true} [:enum "placedAtDesc" "placedAtAsc"]]])

(def PatchOrder
  "Body of PATCH /api/admin/orders/{id}: version (optimistic lock) and optionally deliveryFee (UGX, 0 to 1,000,000) and staffNote."
  [:map {:closed true}
   [:version [:int {:min 1}]]
   [:delivery-fee {:optional true} [:int {:min 0 :max 1000000}]]
   [:staff-note {:optional true} [:string {:max 1000}]]])

(def StatusChange
  "Body of POST /api/admin/orders/{id}/status-changes: version, target status, optional note, paymentMethodUsed (required for delivered) and cancelReason (required for cancelled)."
  [:map {:closed true}
   [:version [:int {:min 1}]]
   [:status [:enum "placed" "confirmed" "approvedForDelivery" "delivered" "cancelled"]]
   [:note {:optional true} [:string {:max 300}]]
   [:payment-method-used {:optional true} [:enum "cash" "mobileMoney"]]
   [:cancel-reason {:optional true} [:string {:min 3 :max 300}]]])
