(ns zandy.features.engagement.schema
  "Engagement slice, schema role: Malli request schemas for subscriptions, unsubscribes, contact messages and the staff list queries. All maps are closed (unknown fields are rejected)."
  (:require [zandy.platform.validation :as v]))

(def Subscription
  "POST /api/subscriptions body: optional email and phone (at least one), consent must be true, source one of footer/orderConfirmation/home, and the honeypot field website."
  [:and
   [:map {:closed true}
    [:email {:optional true} v/Email]
    [:phone {:optional true} v/Phone]
    [:consent [:= {:error/code "consentRequired" :error/message "Please tick the box to agree."} true]]
    [:source [:enum "footer" "orderConfirmation" "home"]]
    [:website {:optional true} [:string {:max 200}]]]
   [:fn {:error/field "email" :error/code "required" :error/message "Give an email address or a phone number."}
    (fn [m] (or (:email m) (:phone m)))]])

(def Unsubscribe
  "POST /api/unsubscribes body: {:token string}."
  [:map {:closed true} [:token [:string {:min 1 :max 300}]]])

(def ContactMessage
  "POST /api/contact-messages body: name, message, at least one of phone and email, and the honeypot field website."
  [:and
   [:map {:closed true}
    [:name [:string {:min 2 :max 100}]]
    [:phone {:optional true} v/Phone]
    [:email {:optional true} v/Email]
    [:message [:string {:min 10 :max 1000}]]
    [:website {:optional true} [:string {:max 200}]]]
   [:fn {:error/field "phone" :error/code "required" :error/message "Give a phone number or an email address."}
    (fn [m] (or (:email m) (:phone m)))]])

(def ContactMessagesQuery
  "Query for GET /api/admin/contact-messages: optional page and pageSize."
  v/PagingQuery)

(def SubscribersQuery
  "Query for GET /api/admin/subscribers: optional page, pageSize and format=csv."
  [:map
   [:page {:optional true} v/Page]
   [:page-size {:optional true} v/PageSize]
   [:format {:optional true} [:enum "csv"]]])
