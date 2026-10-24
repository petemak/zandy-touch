(ns zandy.features.accounts.schema
  (:require [zandy.platform.validation :as v]))

(def Login
  [:map {:closed true}
   [:email [:and [:string {:max 200}] [:re {:error/code "invalidEmail" :error/message "Enter a valid email address."} v/email-regex]]]
   [:password [:string {:min 1 :max 200}]]])

(def ResetRequest
  [:map {:closed true} [:email [:string {:max 200}]]])

(def NewPassword
  [:map {:closed true} [:new-password [:string {:min 1 :max 200}]]])

(def ChangePassword
  [:map {:closed true}
   [:current-password [:string {:min 1 :max 200}]]
   [:new-password [:string {:min 1 :max 200}]]])

(def CreateStaff
  [:map {:closed true}
   [:name [:string {:min 2 :max 100}]]
   [:email v/Email]
   [:notify-on-new-order {:optional true} :boolean]])

(def PatchStaff
  [:map {:closed true}
   [:name {:optional true} [:string {:min 2 :max 100}]]
   [:notify-on-new-order {:optional true} :boolean]])
