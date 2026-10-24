(ns zandy.features.notifications.logic
  "Pure email templates and outbox message builders."
  (:require [clojure.string :as str]))

(defn ugx [n] (str "UGX " (format "%,d" (long n))))

(defn outbox-entity
  "An outbox message as Datomic entity data (status and timestamps are added when it is enqueued)."
  [id kind to subject text]
  {:outbox/id id :outbox/kind kind :outbox/to to :outbox/subject subject :outbox/body-text text})

(defn- item-lines [items]
  (str/join "\n" (map (fn [{:keys [name quantity unit-price]}]
                        (str "- " name " x " quantity " (" (ugx unit-price) " each)"))
                      items)))

(defn new-order-alert
  "Alert for the owner and opted-in staff. No street address or email is included."
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

(defn customer-receipt [{:keys [order-number items subtotal contact-line]}]
  {:subject (str "[Zandy Touch] Your order " order-number)
   :text (str "Thank you for your order with Zandy Touch.\n\n"
              "Order number: " order-number "\n\n"
              (item-lines items) "\n\n"
              "Items subtotal: " (ugx subtotal) "\n"
              "Delivery fee: to be agreed with you before dispatch.\n"
              "You pay in cash or by mobile money on delivery.\n\n"
              contact-line "\n")})

(defn contact-message-mail [{:keys [name phone email message]}]
  {:subject (str "[Zandy Touch] Message from " name)
   :text (str "A message was sent through the contact form.\n\n"
              "Name: " name "\n"
              (when phone (str "Phone: " phone "\n"))
              (when email (str "Email: " email "\n"))
              "\n" message "\n")})

(defn password-reset-mail [{:keys [reset-url]}]
  {:subject "[Zandy Touch] Reset your password"
   :text (str "A password reset was requested for the shop owner account.\n\n"
              "Use this link within 60 minutes to choose a new password:\n" reset-url "\n\n"
              "If you did not ask for this, you can ignore this email.\n")})

(defn daily-cap-reached? [sent-last-24h cap] (>= sent-last-24h cap))
