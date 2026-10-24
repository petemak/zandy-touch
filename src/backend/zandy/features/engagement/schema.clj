(ns zandy.features.engagement.schema
  (:require [zandy.platform.validation :as v]))

(def Subscription
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
  [:map {:closed true} [:token [:string {:min 1 :max 300}]]])

(def ContactMessage
  [:and
   [:map {:closed true}
    [:name [:string {:min 2 :max 100}]]
    [:phone {:optional true} v/Phone]
    [:email {:optional true} v/Email]
    [:message [:string {:min 10 :max 1000}]]
    [:website {:optional true} [:string {:max 200}]]]
   [:fn {:error/field "phone" :error/code "required" :error/message "Give a phone number or an email address."}
    (fn [m] (or (:email m) (:phone m)))]])

(def ContactMessagesQuery v/PagingQuery)

(def SubscribersQuery
  [:map
   [:page {:optional true} v/Page]
   [:page-size {:optional true} v/PageSize]
   [:format {:optional true} [:enum "csv"]]])
