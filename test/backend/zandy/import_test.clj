(ns zandy.import-test
  "Bulk product import, its template and bulk publication (contract 8.2)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [zandy.features.catalog.db :as catalog-db]
            [zandy.features.photos.csv :as csv]
            [zandy.test-helpers :as t]))

(defn- product [ctx sku] (catalog-db/product-by-sku (t/db ctx) sku))
(defn- product-count [ctx] (count (catalog-db/all-products (t/db ctx))))
(defn- row [r i] (get-in r [:body :rows i]))
(defn- codes [row k] (set (map :code (get row k))))

(def example-csv
  "A small import file used by the tests: one new product, one update of an existing SKU (with an ignored opening stock) and one row with a bad price."
  (str "sku,name,brand,ownBrand,category,price,shortDescription,openingStock\n"
       "NEW-1,New One,Zandy Touch,yes,Body care,\"85,000\",Short,12\n"
       "OLD-1,,,,,99 000,,50\n"
       "BAD-1,Bad Price,B,no,skincare,abc,,\n"
       "new-1,Same Again,B,no,skincare,5000,,\n"
       ",No Sku,B,no,skincare,5000,,\n"
       "NEW-2,Gap Row,B,maybe,Nonsense,5000,,\n"))

(deftest import-creates-updates-and-rejects-rows
  (let [ctx (t/make-ctx) app (t/app ctx) owner (t/setup-owner! ctx app)
        old (t/seed-product! ctx {:name "Old Name" :sku "OLD-1" :stock 7 :price 40000})
        r (t/import-csv! app owner example-csv)]
    (is (= 200 (:status r)))
    (is (false? (get-in r [:body :dry-run])))
    (is (= {:rows 6 :created 1 :updated 1 :rejected 4} (get-in r [:body :summary])))
    (testing "a new SKU creates a draft with its opening stock as an initial movement"
      (is (= "created" (:status (row r 0))))
      (is (= 2 (:line (row r 0))))
      (let [p (product ctx "NEW-1")]
        (is (= (:product-id (row r 0)) (str (:id p))))
        (is (= :draft (:status p)))
        (is (= 85000 (:price p)))
        (is (= "body-care" (get-in p [:category :slug])))
        (is (true? (:own-brand? p)))
        (is (= 12 (:stock-quantity p)))
        (is (= ["initial"] (map #(name (:type %)) (catalog-db/movements (t/db ctx) (:eid p)))))))
    (testing "an existing SKU updates the non-empty cells, never the stock or the status"
      (is (= "updated" (:status (row r 1))))
      (is (= (str (:id old)) (:product-id (row r 1))))
      (is (= [{:field "openingStock" :code "ignoredForExisting"}] (:warnings (row r 1))))
      (let [p (product ctx "OLD-1")]
        (is (= 99000 (:price p)) "price with a space separator")
        (is (= "Old Name" (:name p)) "an empty cell leaves the field unchanged")
        (is (= 7 (:stock-quantity p)))
        (is (= :published (:status p)))))
    (testing "rejected rows say why and change nothing"
      (is (= "rejected" (:status (row r 2))))
      (is (= [{:field "price" :code "invalid" :message "Price must be a whole number of shillings."}] (:errors (row r 2))))
      (is (nil? (product ctx "BAD-1")))
      (is (= #{"duplicateSku"} (codes (row r 3) :errors)) "the second NEW-1 row, case-insensitive")
      (is (= "New One" (:name (product ctx "NEW-1"))))
      (is (= #{"required"} (codes (row r 4) :errors)))
      (is (nil? (:sku (row r 4))))
      (is (= #{"invalid" "unknownCategory"} (codes (row r 5) :errors)))
      (is (every? nil? (map :product-id [(row r 2) (row r 3) (row r 4) (row r 5)]))))
    (testing "a new product needs a name, brand and category"
      (let [r2 (t/import-csv! app owner "sku,price\nNEW-9,5000\n")]
        (is (= #{"required"} (codes (row r2 0) :errors)))
        (is (= #{"name" "brand" "category"} (set (map :field (:errors (row r2 0))))))))))

(deftest dry-run-changes-nothing
  (let [ctx (t/make-ctx) app (t/app ctx) owner (t/setup-owner! ctx app)
        _ (t/seed-product! ctx {:sku "OLD-1" :stock 7})
        before (product-count ctx)
        r (t/import-csv! app owner example-csv :dry-run true)]
    (is (= 200 (:status r)))
    (is (true? (get-in r [:body :dry-run])))
    (is (= {:rows 6 :created 1 :updated 1 :rejected 4} (get-in r [:body :summary])))
    (is (= "created" (:status (row r 0))))
    (is (nil? (:product-id (row r 0))))
    (is (= before (product-count ctx)))
    (is (= 85000 (:price (product ctx "OLD-1"))) "seed price untouched")))

(deftest published-and-archived-keep-their-status
  (let [ctx (t/make-ctx) app (t/app ctx) owner (t/setup-owner! ctx app)
        pub (t/seed-product! ctx {:sku "PUB-1" :name "Pub"})
        arch (t/seed-product! ctx {:sku "ARC-1" :name "Arc"})
        _ (t/staff app owner :delete (str "/api/admin/products/" (:id arch)) :idem false)
        r (t/import-csv! app owner "sku,name,description\nPUB-1,Pub Renamed,\nARC-1,,Now described\n")]
    (is (= ["updated" "updated"] (map :status (get-in r [:body :rows]))))
    (is (= :published (:status (product ctx "PUB-1"))))
    (is (= "Pub Renamed" (:name (product ctx "PUB-1"))))
    (is (= :archived (:status (product ctx "ARC-1"))))
    (is (= "Now described" (:description (product ctx "ARC-1"))))
    (is (= (:id pub) (:id (product ctx "PUB-1"))))))

(deftest csv-dialects
  (let [ctx (t/make-ctx) app (t/app ctx) owner (t/setup-owner! ctx app)]
    (testing "semicolon separator, BOM, mixed-case headings, CRLF, quoted newline"
      (let [text (str "﻿" "SKU;Name;BRAND;Category;Price;Description\r\n"
                      "SEMI-1;Semi One;B;Perfumes;\"1 200 000\";\"Line one\n\nLine two; with semicolon\"\r\n")
            r (t/import-csv! app owner text)]
        (is (= 200 (:status r)))
        (is (= "created" (:status (row r 0))) (pr-str (row r 0)))
        (let [p (product ctx "SEMI-1")]
          (is (= 1200000 (:price p)))
          (is (= "Line one\n\nLine two; with semicolon" (:description p)))
          (is (= "perfumes" (get-in p [:category :slug]))))))
    (testing "BOM written as bytes, blank rows skipped but counted for line numbers"
      (let [bs (byte-array (concat (map unchecked-byte [0xEF 0xBB 0xBF])
                                   (.getBytes "sku,name,brand,category\n,,,\nBOM-1,Bom,B,skincare\n" "UTF-8")))
            r (t/import-csv! app owner bs)]
        (is (= 1 (get-in r [:body :summary :rows])))
        (is (= 3 (:line (row r 0))))))
    (testing "non-ASCII text survives"
      (let [r (t/import-csv! app owner "sku;name;brand;category\nUTF-1;Crème éclat;Maison;skincare\n")]
        (is (= "Crème éclat" (:name (product ctx "UTF-1"))))
        (is (= "created" (:status (row r 0))))))
    (testing "category by name, by slug and by display name with an ampersand"
      (let [r (t/import-csv! app owner (str "sku,name,brand,category\nC-1,Aa,B,Body care\nC-2,Aa,B,body-shaping\n"
                                            "C-3,Aa,B,Health & Wellness\nC-4,Aa,B,health-and-wellness\nC-5,Aa,B,Bedroom\n"))]
        (is (= ["created" "created" "created" "created" "rejected"] (map :status (get-in r [:body :rows]))))))))

(deftest whole-file-errors
  (let [ctx (t/make-ctx) app (t/app ctx) owner (t/setup-owner! ctx app)
        detail-codes (fn [r] (set (map :code (get-in r [:body :error :details]))))]
    (testing "no file"
      (let [r (t/post-multipart! app owner "/api/admin/product-imports" "other" [["x.csv" (.getBytes "sku\nA-1\n")]])]
        (is (= 422 (:status r)))
        (is (= "validationFailed" (t/error-code r)))
        (is (= #{"required"} (detail-codes r)))))
    (testing "not UTF-8 text"
      (let [r (t/import-csv! app owner (byte-array [0x73 0x6b 0x75 0x0a 0xff 0xfe 0xfd]))]
        (is (= 422 (:status r)))
        (is (= #{"notUtf8"} (detail-codes r)))))
    (testing "missing sku heading"
      (let [r (t/import-csv! app owner "name,brand\nA,B\n")]
        (is (= 422 (:status r)))
        (is (= #{"missingColumn"} (detail-codes r)))))
    (testing "empty file"
      (is (= #{"missingColumn"} (detail-codes (t/import-csv! app owner "")))))
    (testing "unknown column"
      (let [r (t/import-csv! app owner "sku,name,colour\nA-1,A,red\n")]
        (is (= 422 (:status r)))
        (is (= #{"unknownColumn"} (detail-codes r)))
        (is (= "colour" (get-in r [:body :error :details 0 :field])))))
    (testing "more than 500 rows"
      (let [rows (str/join (map #(str "R-" % ",N" % ",B,skincare\n") (range 501)))
            r (t/import-csv! app owner (str "sku,name,brand,category\n" rows))]
        (is (= 422 (:status r)))
        (is (= #{"tooManyRows"} (detail-codes r)))
        (is (zero? (count (catalog-db/all-products (t/db ctx)))) "no row applied"))
      (let [rows (str/join (map #(str "R-" % ",N" % ",B,skincare\n") (range 500)))
            r (t/import-csv! app owner (str "sku,name,brand,category\n" rows) :dry-run true)]
        (is (= 200 (:status r)) "500 rows are fine")
        (is (= 500 (get-in r [:body :summary :created])))))
    (testing "over 1 MB"
      (let [r (t/import-csv! app owner (str "sku,name\n" (apply str (repeat (* 1024 1024) "x"))))]
        (is (= 413 (:status r)))
        (is (= "fileTooLarge" (t/error-code r)))))
    (testing "unbalanced quotes"
      (let [r (t/import-csv! app owner "sku,name\nA-1,\"never closed\n")]
        (is (= 422 (:status r)))))
    (testing "a row with more cells than headings"
      (let [r (t/import-csv! app owner "sku,name,brand,category\nX-1,Aa,B,skincare,stray\n")]
        (is (= "rejected" (:status (row r 0))))
        (is (= #{"extraCell"} (codes (row r 0) :errors)))))))

(deftest template-round-trips-through-the-import
  (let [ctx (t/make-ctx) app (t/app ctx) owner (t/setup-owner! ctx app)
        r (t/staff app owner :get "/api/admin/product-import-template" :idem false)]
    (is (= 200 (:status r)))
    (is (= "text/csv; charset=utf-8" (get-in r [:headers "Content-Type"])))
    (is (= "attachment; filename=\"zandy-touch-products.csv\"" (get-in r [:headers "Content-Disposition"])))
    (is (= "no-store" (get-in r [:headers "Cache-Control"])))
    (is (str/starts-with? (:raw r) (str (str/join "," csv/columns) "\r\n")))
    (let [imported (t/import-csv! app owner (:raw r))]
      (is (= {:rows 2 :created 2 :updated 0 :rejected 0} (get-in imported [:body :summary])) (pr-str (:body imported)))
      (is (= "A light serum for daily use, morning and evening.\n\nShake gently before use." (:description (product ctx "ZT-SKIN-001"))))
      (is (= 120000 (:price (product ctx "ZT-PERF-002")))))))

(deftest imported-drafts-are-published-in-bulk
  (let [ctx (t/make-ctx) app (t/app ctx) owner (t/setup-owner! ctx app)
        _ (t/import-csv! app owner (str "sku,name,brand,category,price\n"
                                        "P-OK,Complete,B,skincare,5000\n"
                                        "P-NOPIC,No Picture,B,skincare,5000\n"
                                        "P-NOPRICE,No Price,B,skincare,\n"))
        ok (product ctx "P-OK") nopic (product ctx "P-NOPIC") noprice (product ctx "P-NOPRICE")
        _ (t/upload-images! app owner [["P-OK.jpg" (t/jpeg-bytes)] ["P-NOPRICE-1.jpg" (t/jpeg-bytes)]])
        ids (mapv #(str (:id %)) [ok nopic noprice])
        publish (fn [ids] (t/staff app owner :post "/api/admin/product-publications" :body {:product-ids ids}))
        r (publish (conj ids (str (random-uuid))))
        results (get-in r [:body :results])]
    (is (= 200 (:status r)))
    (is (= ["published" "rejected" "rejected" "rejected"] (map :status results)))
    (is (= ids (map :product-id (take 3 results))))
    (is (= [{:field "images" :code "imageRequired"}] (map #(select-keys % [:field :code]) (:errors (nth results 1)))))
    (is (= [{:field "price" :code "priceRequired"}] (map #(select-keys % [:field :code]) (:errors (nth results 2)))))
    (is (= "notFound" (get-in results [3 :errors 0 :code])))
    (is (= :published (:status (product ctx "P-OK"))))
    (is (= :draft (:status (product ctx "P-NOPIC"))))
    (is (= 200 (:status (t/call app :get (str "/api/products/" (:slug ok))))))
    (testing "publishing again changes nothing"
      (let [v (:version (product ctx "P-OK"))
            r2 (publish [(first ids)])]
        (is (= ["published"] (map :status (get-in r2 [:body :results]))))
        (is (= v (:version (product ctx "P-OK"))))))
    (testing "validation"
      (is (= 422 (:status (publish []))))
      (is (= 422 (:status (publish ["nope"]))))
      (is (= 422 (:status (publish (repeat 501 (first ids)))))))))
