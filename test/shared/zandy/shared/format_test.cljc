(ns zandy.shared.format-test
  (:require [clojure.test :refer [deftest is]]
            [zandy.shared.format :as fmt]))

(deftest money
  (is (= "UGX 85,000" (fmt/ugx 85000)))
  (is (= "UGX 0" (fmt/ugx 0)))
  (is (= "UGX 1,234,567" (fmt/ugx 1234567)))
  (is (nil? (fmt/ugx nil))))

(deftest text
  (is (= ["One" "Two"] (fmt/paragraphs "One\n\nTwo")))
  (is (= "Amina" (fmt/first-name "Amina Nakato")))
  (is (= "+256 706 105 779" (fmt/phone-display "+256706105779")))
  (is (= "256706105779" (fmt/digits "+256706105779"))))

(deftest dates-and-hours
  (is (= "24 Oct 2026" (fmt/iso->date "2026-10-24T09:30:00Z")))
  (is (= "24 Oct 2026, 09:30 UTC" (fmt/iso->date-time "2026-10-24T09:30:00Z")))
  (is (= "Monday to Saturday: 8:00 AM to 10:00 PM" (fmt/hours-label {:days "Monday to Saturday" :opens "08:00" :closes "22:00"})))
  (is (= "Sunday: 10:00 AM to 9:30 PM" (fmt/hours-label {:days "Sunday" :opens "10:00" :closes "21:30"}))))

(deftest labels
  (is (= "On its way" (fmt/order-status-label "approvedForDelivery")))
  (is (= "Out for delivery" (fmt/staff-status-label "approvedForDelivery"))))
