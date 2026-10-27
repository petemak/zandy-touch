(ns zandy.features.accounts.schema
  "Accounts slice, schema role: Malli schemas for sign-in, password reset and change, and staff management bodies (contract 6 and 10). Closed maps. Password strength is checked in logic/password-problem, not here, so its error is a weakPassword field error."
  (:require [zandy.platform.validation :as v]))

(def Login
  "Body of POST /api/auth/sessions: {:email :password}."
  [:map {:closed true}
   [:email [:and [:string {:max 200}] [:re {:error/code "invalidEmail" :error/message "Enter a valid email address."} v/email-regex]]]
   [:password [:string {:min 1 :max 200}]]])

(def ResetRequest
  "Body of POST /api/auth/password-resets: {:email}. Deliberately not format-checked, so the response never depends on the address."
  [:map {:closed true} [:email [:string {:max 200}]]])

(def NewPassword
  "Body of PUT /api/auth/password-resets/{token}: {:new-password}."
  [:map {:closed true} [:new-password [:string {:min 1 :max 200}]]])

(def ChangePassword
  "Body of PUT /api/admin/account/password: {:current-password :new-password}."
  [:map {:closed true}
   [:current-password [:string {:min 1 :max 200}]]
   [:new-password [:string {:min 1 :max 200}]]])

(def CreateStaff
  "Body of POST /api/admin/staff: {:name :email} and optional :notify-on-new-order."
  [:map {:closed true}
   [:name [:string {:min 2 :max 100}]]
   [:email v/Email]
   [:notify-on-new-order {:optional true} :boolean]])

(def PatchStaff
  "Body of PATCH /api/admin/staff/{id}: optional :name and :notify-on-new-order."
  [:map {:closed true}
   [:name {:optional true} [:string {:min 2 :max 100}]]
   [:notify-on-new-order {:optional true} :boolean]])
