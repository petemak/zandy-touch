(ns zandy.shared.format-test
  "Tests of shared.format: UGX and phone formatting, Kampala (+3) dates and
  labels. Runs on the JVM and in the Node test build."
  (:require [clojure.test :refer [deftest is testing]]
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
  (is (= "Monday to Saturday: 8:00 AM to 10:00 PM" (fmt/hours-label {:days "Monday to Saturday" :opens "08:00" :closes "22:00"})))
  (is (= "Sunday: 10:00 AM to 9:30 PM" (fmt/hours-label {:days "Sunday" :opens "10:00" :closes "21:30"}))))

(deftest labels
  (is (= "On its way" (fmt/order-status-label "approvedForDelivery")))
  (is (= "Out for delivery" (fmt/staff-status-label "approvedForDelivery"))))

;; Kampala time: UTC+3, no daylight saving. Runs on the JVM and in Node, so the
;; server-rendered text and the browser text are checked against the same strings.
(deftest kampala-time
  (testing "plain conversion, labelled"
    (is (= "24 Oct 2026, 12:30 (Kampala time)" (fmt/iso->date-time "2026-10-24T09:30:00Z")))
    (is (= "25 Oct 2026, 17:40 (Kampala time)" (fmt/iso->date-time "2026-10-25T14:40:00Z"))))
  (testing "crosses midnight into the next day, month and year"
    (is (= "25 Oct 2026, 01:30 (Kampala time)" (fmt/iso->date-time "2026-10-24T22:30:00Z")))
    (is (= "25 Oct 2026" (fmt/iso->date "2026-10-24T22:30:00Z")))
    (is (= "1 Nov 2026, 00:00 (Kampala time)" (fmt/iso->date-time "2026-10-31T21:00:00Z")))
    (is (= "1 Jan 2027, 02:59 (Kampala time)" (fmt/iso->date-time "2026-12-31T23:59:59Z")))
    (is (= "1 Mar 2028" (fmt/iso->date "2028-02-29T21:00:00Z")) "leap year")
    (is (= "29 Feb 2028" (fmt/iso->date "2028-02-29T20:59:59Z"))))
  (testing "fractional seconds and offsets are understood"
    (is (= "24 Oct 2026, 12:30 (Kampala time)" (fmt/iso->date-time "2026-10-24T09:30:00.123Z")))
    (is (= "24 Oct 2026, 09:30 (Kampala time)" (fmt/iso->date-time "2026-10-24T09:30:00+03:00")))
    (is (= "25 Oct 2026, 00:30 (Kampala time)" (fmt/iso->date-time "2026-10-24T20:00:00-01:30"))))
  (testing "no UTC label, nil and bad input"
    (is (not (re-find #"UTC" (fmt/iso->date-time "2026-10-24T09:30:00Z"))))
    (is (nil? (fmt/iso->date-time nil)))
    (is (nil? (fmt/iso->date-time "2026-10-24")))
    (is (= "24 Oct 2026" (fmt/iso->date "2026-10-24")) "a date without a time is not shifted")
    (is (nil? (fmt/iso->date nil)))))

(deftest stock-movement-labels
  (testing "types"
    (is (= "Opening stock" (fmt/movement-type-label "initial")))
    (is (= "Adjusted by staff" (fmt/movement-type-label "adjustment")))
    (is (= "Customer order" (fmt/movement-type-label "order")))
    (is (= "Order cancelled, stock returned" (fmt/movement-type-label "orderCancelled"))))
  (testing "reasons"
    (is (= ["Restock" "Stocktake" "Sold in the shop" "Damaged or lost" "Returned goods" "Other"]
           (map #(fmt/stock-reason-label (first %)) fmt/stock-reasons))))
  (testing "unknown codes show the raw value, nil stays nil"
    (is (= "somethingNew" (fmt/movement-type-label "somethingNew")))
    (is (= "mystery" (fmt/stock-reason-label "mystery")))
    (is (nil? (fmt/stock-reason-label nil))))
  (testing "signed change"
    (is (= "+5" (fmt/signed-delta 5)))
    (is (= "\u22122" (fmt/signed-delta -2)))
    (is (= "0" (fmt/signed-delta 0)))))

(deftest category-names
  (let [cats [{:slug "skincare" :name "Skincare"} {:slug "health-and-wellness" :name "Health & Wellness"} {:slug "odd" :name ""}]]
    (is (= "Health & Wellness" (fmt/category-name cats "health-and-wellness")))
    (is (= "unknown-slug" (fmt/category-name cats "unknown-slug")) "unknown slug falls back to the slug")
    (is (= "odd" (fmt/category-name cats "odd")) "blank name falls back to the slug")
    (is (= "skincare" (fmt/category-name nil "skincare")) "not loaded yet")
    (is (nil? (fmt/category-name cats nil)))))
