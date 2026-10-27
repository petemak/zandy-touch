(ns zandy.features.notifications.logic
  "Pure email templates and outbox message builders."
  (:require [clojure.string :as str]))

(defn ugx
  "Formats a whole-shilling amount as \"UGX 12,500\" (comma thousands separator)."
  [n] (str "UGX " (format "%,d" (long n))))

(defn outbox-entity
  "An outbox message as Datomic entity data (status and timestamps are added by db/enqueue-tx).
  Arguments: id (uuid), kind (keyword such as :newOrderAlert, :customerReceipt, :contactMessage), recipient address, subject, plain-text body."
  [id kind to subject text]
  {:outbox/id id :outbox/kind kind :outbox/to to :outbox/subject subject :outbox/body-text text})

(defn- item-lines [items]
  (str/join "\n" (map (fn [{:keys [name quantity unit-price]}]
                        (str "- " name " x " quantity " (" (ugx unit-price) " each)"))
                      items)))

(defn new-order-alert
  "Alert email for the owner and opted-in staff. Argument: {:order-number :customer-name :customer-phone :city :items [{:name :quantity :unit-price}] :subtotal (UGX) :admin-url}. Returns {:subject :text}. No street address or email is included. Pure."
  [{:keys [order-number customer-name customer-phone city items subtotal admin-url]}]
  {:subject (str "[Zandy Touch] New order " order-number)
   :text (str "A new order was placed.\n\n"
              "Order: " order-number "\n"
              "Customer: " customer-name ", " customer-phone "\n"
              "City: " city "\n\n"
              (item-lines items) "\n\n"
              "Items subtotal: " (ugx subtotal) "\n"
              "Delivery fee: to be agreed with the customer.\n\n"
              "Open the order: " admin-url "\n")})

(defn customer-receipt
  "Order receipt email for the customer. Argument: {:order-number :items [{:name :quantity :unit-price}] :subtotal (UGX) :contact-line}. Returns {:subject :text}. States that the delivery fee is agreed before dispatch. Pure."
  [{:keys [order-number items subtotal contact-line]}]
  {:subject (str "[Zandy Touch] Your order " order-number)
   :text (str "Thank you for your order with Zandy Touch.\n\n"
              "Order number: " order-number "\n\n"
              (item-lines items) "\n\n"
              "Items subtotal: " (ugx subtotal) "\n"
              "Delivery fee: to be agreed with you before dispatch.\n"
              "You pay in cash or by mobile money on delivery.\n\n"
              contact-line "\n")})

(defn contact-message-mail
  "Owner alert for a contact-form message. Argument: {:name :phone :email :message} (phone and email optional). Returns {:subject :text}. Pure."
  [{:keys [name phone email message]}]
  {:subject (str "[Zandy Touch] Message from " name)
   :text (str "A message was sent through the contact form.\n\n"
              "Name: " name "\n"
              (when phone (str "Phone: " phone "\n"))
              (when email (str "Email: " email "\n"))
              "\n" message "\n")})

(defn password-reset-mail
  "Password-reset email. Argument: {:reset-url}. Returns {:subject :text}; the link is stated to be valid for 60 minutes. Pure."
  [{:keys [reset-url]}]
  {:subject "[Zandy Touch] Reset your password"
   :text (str "A password reset was requested for the shop owner account.\n\n"
              "Use this link within 60 minutes to choose a new password:\n" reset-url "\n\n"
              "If you did not ask for this, you can ignore this email.\n")})

(defn daily-cap-reached?
  "True when `sent-last-24h` messages is at or above the daily send cap."
  [sent-last-24h cap] (>= sent-last-24h cap))
