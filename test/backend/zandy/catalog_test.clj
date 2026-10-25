(ns zandy.catalog-test
  (:require [clojure.test :refer [deftest is testing]]
            [zandy.test-helpers :as t]))

(defn- names [resp] (map :name (get-in resp [:body :items])))

(deftest public-listing-search-sort
  (let [ctx (t/make-ctx) app (t/app ctx)
        _ (t/seed-product! ctx {:name "Alpha Serum" :price 50000 :stock 5 :category "skincare"})
        _ (t/seed-product! ctx {:name "Bravo Cream" :price 30000 :stock 0 :category "skincare"})
        _ (t/seed-product! ctx {:name "Charlie Perfume" :price 90000 :stock 2 :category "perfumes" :own-brand true})
        _ (t/seed-product! ctx {:name "Delta Oil" :price 10000 :stock 7 :category "body-care"})]
    (testing "default order is newest, sold out last"
      (let [r (t/call app :get "/api/products")]
        (is (= 200 (:status r)))
        (is (= 4 (get-in r [:body :total-items])))
        (is (= "Bravo Cream" (last (names r))))))
    (testing "sort"
      (is (= ["Delta Oil" "Alpha Serum" "Charlie Perfume" "Bravo Cream"] (names (t/call app :get "/api/products?sort=priceAsc"))))
      (is (= ["Charlie Perfume" "Alpha Serum" "Delta Oil" "Bravo Cream"] (names (t/call app :get "/api/products?sort=priceDesc"))))
      (is (= ["Alpha Serum" "Charlie Perfume" "Delta Oil" "Bravo Cream"] (names (t/call app :get "/api/products?sort=nameAsc")))))
    (testing "filters"
      (is (= ["Alpha Serum" "Bravo Cream"] (sort (names (t/call app :get "/api/products?categorySlug=skincare")))))
      (is (= ["Bravo Cream"] (names (t/call app :get "/api/products?availability=soldOut"))))
      (is (= ["Charlie Perfume"] (names (t/call app :get "/api/products?ownBrand=true"))))
      (is (= [] (names (t/call app :get "/api/products?categorySlug=nope")))))
    (testing "text search is a case-insensitive substring"
      (is (= ["Charlie Perfume"] (names (t/call app :get "/api/products?q=ARLIE"))))
      (is (= 422 (:status (t/call app :get "/api/products?q=a")))))
    (testing "paging and bad parameters"
      (let [r (t/call app :get "/api/products?pageSize=2&page=2")]
        (is (= 2 (count (get-in r [:body :items]))))
        (is (= 2 (get-in r [:body :page-size]))))
      (is (= 422 (:status (t/call app :get "/api/products?sort=bogus"))))
      (is (= 422 (:status (t/call app :get "/api/products?pageSize=1000")))))
    (testing "item shape"
      (let [item (first (get-in (t/call app :get "/api/products?sort=nameAsc") [:body :items]))]
        (is (= "available" (:availability item)))
        (is (= 5 (:max-quantity item)))
        (is (= "UGX" (:currency item)))
        (is (true? (:is-new item)))
        (is (= "skincare" (:category-slug item)))
        (is (re-matches #"/media/[0-9a-f-]{36}-thumb\.webp" (get-in item [:image :urls :thumb])))
        (is (not (contains? item :stock-quantity)))))
    (testing "categories carry product counts"
      (let [items (get-in (t/call app :get "/api/categories") [:body :items])]
        (is (= 2 (:product-count (first (filter #(= "skincare" (:slug %)) items)))))
        (is (= 0 (:product-count (first (filter #(= "weight-loss" (:slug %)) items)))))))))

(deftest product-detail-and-caching
  (let [ctx (t/make-ctx) app (t/app ctx)
        p (t/seed-product! ctx {:name "Glow Serum" :stock 3})
        _ (t/seed-product! ctx {:name "Sibling One" :stock 3})
        r (t/call app :get "/api/products/glow-serum")]
    (is (= 200 (:status r)))
    (is (= 3 (get-in r [:body :max-quantity])))
    (is (= "skincare" (get-in r [:body :category :slug])))
    (is (= 1 (count (get-in r [:body :related]))))
    (is (= 1 (count (get-in r [:body :images]))))
    (is (nil? (get-in r [:body :size])))
    (is (= "public, max-age=30, stale-while-revalidate=60" (get-in r [:headers "Cache-Control"])))
    (is (nil? (get-in r [:headers "Set-Cookie"])))
    (is (nil? (get-in r [:headers "Vary"])))
    (testing "If-None-Match gives 304"
      (let [etag (get-in r [:headers "ETag"])
            r2 (t/call app :get "/api/products/glow-serum" :headers {"If-None-Match" etag})]
        (is (some? etag))
        (is (= 304 (:status r2)))))
    (testing "errors are not cached"
      (let [nf (t/call app :get "/api/products/nope")]
        (is (= 404 (:status nf)))
        (is (= "no-store" (get-in nf [:headers "Cache-Control"])))
        (is (= "notFound" (t/error-code nf)))))
    (is p)))

(deftest cart-validation
  (let [ctx (t/make-ctx) app (t/app ctx)
        a (t/seed-product! ctx {:name "Alpha" :price 10000 :stock 3})
        b (t/seed-product! ctx {:name "Bravo" :price 5000 :stock 0})
        post (fn [items] (t/call app :post "/api/cart-validations" :body {:items items}))
        item (fn [p q] {:product-id (str (:id p)) :quantity q})]
    (let [r (post [(item a 2)])]
      (is (= 200 (:status r)))
      (is (= 20000 (get-in r [:body :items-subtotal])))
      (is (false? (get-in r [:body :has-problems])))
      (is (nil? (get-in r [:body :delivery-fee])))
      (is (= "Agreed with you before dispatch" (get-in r [:body :delivery-fee-note]))))
    (let [r (post [(item a 5) (item b 1) {:product-id (str (random-uuid)) :quantity 1}])
          lines (get-in r [:body :items])]
      (is (= ["insufficientStock" "soldOut" "unavailable"] (map :problem lines)))
      (is (= 3 (:max-quantity (first lines))))
      (is (= 0 (get-in r [:body :items-subtotal])))
      (is (true? (get-in r [:body :has-problems]))))
    (is (= 422 (:status (post []))))
    (is (= 422 (:status (post [(item a 11)]))))
    (is (= 422 (:status (post [(item a 1) (item a 2)]))))))

(deftest admin-product-lifecycle
  (let [ctx (t/make-ctx) app (t/app ctx)
        owner (t/setup-owner! ctx app)
        base {:name "Test Serum" :brand "Zandy Touch" :is-own-brand true :category-slug "skincare" :status "draft"}]
    (testing "draft without a price"
      (let [r (t/staff app owner :post "/api/admin/products" :body base)]
        (is (= 201 (:status r)))
        (is (= "test-serum" (get-in r [:body :slug])))
        (is (nil? (get-in r [:body :price])))
        (is (= 1 (get-in r [:body :version])))))
    (testing "publishing needs price and image"
      (let [r (t/staff app owner :post "/api/admin/products" :body (assoc base :name "Second One" :status "published"))
            codes (set (map :code (get-in r [:body :error :details])))]
        (is (= 422 (:status r)))
        (is (= #{"priceRequired" "imageRequired"} codes))))
    (testing "publish with price, image and opening stock"
      (let [c (t/staff app owner :post "/api/admin/products"
                       :body (assoc base :name "Test Serum" :price 85000 :sku "ts-01" :stock-quantity 12))
            up (t/upload-to-product! app owner (get-in c [:body :id]) [["a.jpg" (t/jpeg-bytes)]])
            photo-id (get-in up [:body :items 0 :image :id])
            r (t/staff app owner :patch (str "/api/admin/products/" (get-in c [:body :id]))
                       :body {:version (get-in up [:body :product :version]) :status "published"
                              :images [{:id photo-id :alt "Bottle"}]})
            r (assoc r :status (if (= 200 (:status r)) 201 (:status r)))]
        (is (= 201 (:status c)))
        (is (= 200 (:status up)))
        (is (= "Bottle" (get-in r [:body :images 0 :alt])))
        (is (= "test-serum-2" (get-in r [:body :slug])) "slugs are made unique")
        (is (= "TS-01" (get-in r [:body :sku])))
        (is (= 12 (get-in r [:body :stock-quantity])))
        (let [hist (t/staff app owner :get (str "/api/admin/products/" (get-in r [:body :id]) "/stock-movements") :idem false)]
          (is (= ["initial"] (map :type (get-in hist [:body :items])))))
        (testing "visible on the storefront"
          (is (= 200 (:status (t/call app :get "/api/products/test-serum-2")))))
        (testing "duplicate sku"
          (let [d (t/staff app owner :post "/api/admin/products"
                           :body (assoc base :name "Third" :sku "TS-01"))]
            (is (= "skuAlreadyInUse" (get-in d [:body :error :details 0 :code])))))
        (let [id (get-in r [:body :id])]
        (testing "stockQuantity is not patchable"
          (is (= 422 (:status (t/staff app owner :patch (str "/api/admin/products/" id) :body {:version 1 :stock-quantity 5})))))
        (testing "patch with version"
          (let [pr (t/staff app owner :patch (str "/api/admin/products/" id) :body {:version 3 :price 90000 :size "30 ml"})]
            (is (= 200 (:status pr)))
            (is (= 4 (get-in pr [:body :version])))
            (is (= 90000 (get-in pr [:body :price])))
            (is (= "test-serum-2" (get-in pr [:body :slug]))))
          (is (= "versionConflict" (t/error-code (t/staff app owner :patch (str "/api/admin/products/" id) :body {:version 1 :price 1000})))))
        (testing "a published product cannot lose its price or images"
          (is (= "priceRequired" (get-in (t/staff app owner :patch (str "/api/admin/products/" id) :body {:version 4 :price nil})
                                         [:body :error :details 0 :code])))
          (is (= "imageRequired" (get-in (t/staff app owner :patch (str "/api/admin/products/" id) :body {:version 4 :images []})
                                         [:body :error :details 0 :code]))))
        (testing "clearing an optional field"
          (let [pr (t/staff app owner :patch (str "/api/admin/products/" id) :body {:version 4 :size nil})]
            (is (nil? (get-in pr [:body :size])))))
        (testing "archive hides it everywhere and repeats cleanly"
          (is (= 204 (:status (t/staff app owner :delete (str "/api/admin/products/" id) :idem false))))
          (is (= 204 (:status (t/staff app owner :delete (str "/api/admin/products/" id) :idem false))))
          (is (= 404 (:status (t/call app :get "/api/products/test-serum-2"))))
          (is (= "archived" (get-in (t/staff app owner :get (str "/api/admin/products/" id) :idem false) [:body :status])))
          (is (not-any? #(= id (:id %)) (get-in (t/staff app owner :get "/api/admin/products" :idem false) [:body :items])))
          (is (some #(= id (:id %)) (get-in (t/staff app owner :get "/api/admin/products?status=archived" :idem false) [:body :items]))))
        (testing "publishing an archived product restores it"
          (let [v (get-in (t/staff app owner :get (str "/api/admin/products/" id) :idem false) [:body :version])
                pr (t/staff app owner :patch (str "/api/admin/products/" id) :body {:version v :status "published"})]
            (is (= "published" (get-in pr [:body :status])))
            (is (= 200 (:status (t/call app :get "/api/products/test-serum-2")))))))))
    (testing "unknown category"
      (is (= "unknownCategory" (get-in (t/staff app owner :post "/api/admin/products" :body (assoc base :category-slug "bedroom"))
                                       [:body :error :details 0 :code]))))
    (testing "admin list shape"
      (let [l (t/staff app owner :get "/api/admin/products?sort=nameAsc" :idem false)]
        (is (= 200 (:status l)))
        (is (contains? (:body l) :counts))
        (is (= "Sandra" (get-in l [:body :items 0 :updated-by :name])))))))

(deftest stock-adjustments
  (let [ctx (t/make-ctx) app (t/app ctx)
        owner (t/setup-owner! ctx app)
        p (t/seed-product! ctx {:stock 3})
        path (str "/api/admin/products/" (:id p) "/stock-movements")
        adjust (fn [body] (t/staff app owner :post path :body body))]
    (let [r (adjust {:delta 20 :reason "restock" :note "Supplier delivery"})]
      (is (= 201 (:status r)))
      (is (= 23 (get-in r [:body :stock-quantity])))
      (is (= 23 (get-in r [:body :movement :quantity-after])))
      (is (= "adjustment" (get-in r [:body :movement :type])))
      (is (= "Sandra" (get-in r [:body :movement :by :name]))))
    (testing "cannot go below zero"
      (let [r (adjust {:delta -24 :reason "shopSale"})]
        (is (= 422 (:status r)))
        (is (= "stockCannotBeNegative" (get-in r [:body :error :details 0 :code])))))
    (testing "expectedQuantity protects against concurrent changes"
      (is (= "versionConflict" (t/error-code (adjust {:delta -3 :reason "stocktake" :expected-quantity 5}))))
      (is (= 201 (:status (adjust {:delta -3 :reason "stocktake" :expected-quantity 23})))))
    (testing "idempotent retry"
      (let [k (str (random-uuid))
            a (t/staff app owner :post path :body {:delta 1 :reason "other"} :idem k)
            b (t/staff app owner :post path :body {:delta 1 :reason "other"} :idem k)]
        (is (= (get-in a [:body :movement :id]) (get-in b [:body :movement :id])))
        (is (= 21 (get-in b [:body :stock-quantity])))))
    (testing "validation"
      (is (= 422 (:status (adjust {:delta 0 :reason "restock"}))))
      (is (= 422 (:status (adjust {:delta 1 :reason "mystery"}))))
      (is (= 404 (:status (t/staff app owner :post (str "/api/admin/products/" (random-uuid) "/stock-movements")
                                   :body {:delta 1 :reason "restock"})))))
    (testing "history newest first"
      (let [h (t/staff app owner :get path :idem false)]
        (is (= 4 (get-in h [:body :total-items])) "initial, restock, stocktake, other")))))

