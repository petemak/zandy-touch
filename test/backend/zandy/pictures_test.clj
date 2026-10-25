(ns zandy.pictures-test
  "Product pictures (contract 8.2): upload onto a product, bulk upload named by SKU, the images list of
  PATCH, and the absence of any photo library."
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [zandy.features.catalog.db :as catalog-db]
            [zandy.features.photos.logic :as logic]
            [zandy.platform.http :as http]
            [zandy.test-helpers :as t]))

(defn- draft! [app owner body]
  (:body (t/staff app owner :post "/api/admin/products"
                  :body (merge {:name "Pic Product" :brand "B" :category-slug "skincare" :status "draft"} body))))

(defn- photo-count [ctx] (count (d/q '[:find ?p :where [?p :photo/id]] (t/db ctx))))
(defn- image-count [ctx] (count (d/q '[:find ?i :where [?i :product-image/id]] (t/db ctx))))
(defn- jpg [] (t/jpeg-bytes))

(deftest match-file-names-to-skus
  (let [skus ["AB-1" "AB-1-2" "ZT-SKIN-001"]]
    (is (= {:sku "ZT-SKIN-001" :position nil} (logic/match-file "ZT-SKIN-001.jpg" skus)))
    (is (= {:sku "ZT-SKIN-001" :position 2} (logic/match-file "zt-skin-001-2.JPG" skus)) "case-insensitive")
    (is (= {:sku "AB-1-2" :position nil} (logic/match-file "AB-1-2.png" skus)) "longest SKU wins")
    (is (= {:sku "AB-1" :position 3} (logic/match-file "ab-1-3.webp" skus)))
    (is (= {:sku "AB-1" :position 1} (logic/match-file "AB-1-1.jpg" skus)))
    (is (= "unknownSku" (get-in (logic/match-file "holiday.jpg" skus) [:error :code])))
    (is (= "unknownSku" (get-in (logic/match-file "ZT-SKIN-0011.jpg" skus) [:error :code])) "must end at the SKU")
    (is (= "unknownSku" (get-in (logic/match-file "ZT-SKIN-001-7.jpg" skus) [:error :code])) "positions are 1 to 6")
    (is (= "unknownSku" (get-in (logic/match-file "ZT-SKIN-001_front.jpg" skus) [:error :code])))
    (is (= {:sku "ZT-SKIN-001" :position 4} (logic/match-file "C:\\photos\\ZT-SKIN-001-4.jpg" skus)) "directory part ignored")))

(deftest upload-onto-a-product
  (let [ctx (t/make-ctx) app (t/app ctx) owner (t/setup-owner! ctx app)
        p (draft! app owner {:sku "pp-1"})
        id (:id p)
        r (t/upload-to-product! app owner id [["a.jpg" (jpg)] ["notes.jpg" (.getBytes "not an image at all, just text")] ["b.jpg" (jpg)]])
        [a bad b] (get-in r [:body :items])]
    (is (= 200 (:status r)))
    (testing "per-file results"
      (is (= ["attached" "rejected" "attached"] (map :status [a bad b])))
      (is (= [1 nil 2] (map :position [a bad b])))
      (is (= "PP-1" (:sku a)))
      (is (= id (:product-id a)))
      (is (nil? (get-in a [:image :alt])))
      (is (nil? (:error a)))
      (is (= "unsupportedMediaType" (get-in bad [:error :code])))
      (is (nil? (:image bad))))
    (testing "the product comes back with both pictures, in the order sent"
      (is (= (map #(get-in % [:image :id]) [a b]) (map :id (get-in r [:body :product :images]))))
      (is (= 2 (count (get-in r [:body :product :images])))))
    (testing "rejected files are not kept"
      (is (= 2 (photo-count ctx)))
      (is (= 2 (image-count ctx))))
    (testing "variants are served with immutable caching"
      (let [m (t/call app :get (get-in a [:image :urls :thumb]))]
        (is (= 200 (:status m)))
        (is (= "public, max-age=31536000, immutable" (get-in m [:headers "Cache-Control"])))
        (is (= 404 (:status (t/call app :get (str "/media/" (random-uuid) "-thumb.webp")))))
        (is (= 404 (:status (t/call app :get "/media/..%2Fconfig.edn"))))))
    (testing "the seventh picture is rejected with tooManyImages"
      (let [r2 (t/upload-to-product! app owner id (repeat 4 ["c.jpg" (jpg)]))
            r3 (t/upload-to-product! app owner id [["d.jpg" (jpg)]])]
        (is (= ["attached" "attached" "attached" "attached"] (map :status (get-in r2 [:body :items]))))
        (is (= 6 (count (get-in r2 [:body :product :images]))))
        (is (= "tooManyImages" (get-in r3 [:body :items 0 :error :code])))
        (is (= 6 (photo-count ctx)) "nothing stored for the rejected file")))
    (testing "validation"
      (is (= 422 (:status (t/upload-to-product! app owner id (repeat 7 ["x.jpg" (jpg)])))) "more than 6 files")
      (is (= 422 (:status (t/upload-to-product! app owner id []))) "no files")
      (is (= 404 (:status (t/upload-to-product! app owner (random-uuid) [["x.jpg" (jpg)]]))))
      (is (= 404 (:status (t/upload-to-product! app owner "not-a-uuid" [["x.jpg" (jpg)]])))))))

(deftest images-list-on-patch-reorders-relabels-and-deletes
  (let [ctx (t/make-ctx) app (t/app ctx) owner (t/setup-owner! ctx app)
        p (draft! app owner {:price 5000})
        other (draft! app owner {:name "Other One"})
        up (t/upload-to-product! app owner (:id p) [["a.jpg" (jpg)] ["b.jpg" (jpg)] ["c.jpg" (jpg)]])
        [a b c] (map #(get-in % [:image :id]) (get-in up [:body :items]))
        other-up (t/upload-to-product! app owner (:id other) [["z.jpg" (jpg)]])
        foreign (get-in other-up [:body :items 0 :image :id])
        path (str "/api/admin/products/" (:id p))
        patch (fn [v images] (t/staff app owner :patch path :body {:version v :images images}))
        v (get-in up [:body :product :version])]
    (testing "admin views return the stored alt (nil when unset), the public view falls back to the name"
      (is (nil? (get-in up [:body :product :images 0 :alt])))
      (is (nil? (get-in (t/staff app owner :get path :idem false) [:body :images 0 :alt])))
      (is (nil? (get-in (t/staff app owner :get "/api/admin/products?q=Pic" :idem false) [:body :items 0 :image :alt]))))
    (testing "reorder and relabel"
      (let [r (patch v [{:id c :alt "Third"} {:id a}])]
        (is (= 200 (:status r)))
        (is (= [c a] (map :id (get-in r [:body :images]))))
        (is (= "Third" (get-in r [:body :images 0 :alt])))
        (is (= (inc v) (get-in r [:body :version])))))
    (testing "a picture left out of the list is deleted, with its photo record and files"
      (is (= 3 (photo-count ctx)) "a, c and the other product's picture")
      (is (= 404 (:status (t/call app :get (str "/media/" b "-thumb.webp")))))
      (is (nil? (d/q '[:find ?p . :in $ ?id :where [?p :photo/id ?id]] (t/db ctx) (parse-uuid b)))))
    (testing "only pictures already on this product can be listed"
      (let [r (patch (inc v) [{:id foreign}])]
        (is (= 422 (:status r)))
        (is (= "unknownImage" (get-in r [:body :error :details 0 :code]))))
      (let [r (patch (inc v) [{:id c} {:id c}])]
        (is (= "duplicateImage" (get-in r [:body :error :details 0 :code])))))
    (testing "an empty list removes every picture"
      (let [r (patch (inc v) [])]
        (is (= 200 (:status r)))
        (is (= [] (get-in r [:body :images])))
        (is (= 1 (photo-count ctx)) "only the other product's picture is left")
        (is (= 404 (:status (t/call app :get (str "/media/" a "-thumb.webp")))))))
    (testing "create cannot list pictures: the product does not exist yet"
      (let [r (t/staff app owner :post "/api/admin/products"
                       :body {:name "Fresh" :brand "B" :category-slug "skincare" :status "draft" :images [{:id foreign}]})]
        (is (= "unknownImage" (get-in r [:body :error :details 0 :code])))))))

(deftest bulk-upload-by-sku
  (let [ctx (t/make-ctx) app (t/app ctx) owner (t/setup-owner! ctx app)
        a (draft! app owner {:sku "AB-1" :name "Product A"})
        b (draft! app owner {:sku "AB-1-2" :name "Product B"})
        r (t/upload-images! app owner [["AB-1-2.jpg" (jpg)]
                                       ["ab-1-3.jpg" (jpg)]
                                       ["AB-1-1.jpg" (jpg)]
                                       ["holiday.jpg" (jpg)]
                                       ["AB-10.jpg" (jpg)]
                                       ["AB-1-6.jpg" (.getBytes "text, not an image")]])
        items (get-in r [:body :items])
        detail (fn [id] (:body (t/staff app owner :get (str "/api/admin/products/" id) :idem false)))]
    (is (= 200 (:status r)))
    (is (= ["attached" "attached" "attached" "rejected" "rejected" "rejected"] (map :status items)))
    (testing "the longest SKU wins and the SKU match ignores case"
      (is (= [(:id b) (:id a) (:id a)] (map :product-id (take 3 items))))
      (is (= ["AB-1-2" "AB-1" "AB-1"] (map :sku (take 3 items)))))
    (testing "a file without a position is appended, one with a position takes that slot"
      (is (= [1 3 1] (map :position (take 3 items)))
          "AB-1-2.jpg is product AB-1-2 appended; ab-1-3 asks for slot 3; AB-1-1 asks for slot 1")
      (is (= 2 (count (:images (detail (:id a))))))
      (is (= 1 (count (:images (detail (:id b)))))))
    (testing "rejections"
      (is (= "unknownSku" (get-in items [3 :error :code])))
      (is (nil? (get-in items [3 :product-id])))
      (is (= "unknownSku" (get-in items [4 :error :code])) "AB-10 is not AB-1")
      (is (= "unsupportedMediaType" (get-in items [5 :error :code])))
      (is (= "AB-1" (get-in items [5 :sku])) "the product is known even when the file is bad"))
    (testing "a positioned file replaces the picture in that slot and deletes the old one"
      (let [before (map :id (:images (detail (:id a))))
            r2 (t/upload-images! app owner [["AB-1-1.jpg" (jpg)]])
            after (map :id (:images (detail (:id a))))
            new-id (get-in r2 [:body :items 0 :image :id])]
        (is (= "attached" (get-in r2 [:body :items 0 :status])))
        (is (= (count before) (count after)))
        (is (= new-id (first after)) "slot 1 is the new picture")
        (is (= 404 (:status (t/call app :get (str "/media/" (first before) "-thumb.webp")))))
        (is (= 3 (photo-count ctx)) "the replaced picture's record is gone")))
    (testing "a gap is allowed: positions fill in whatever order the files arrive"
      (let [c (draft! app owner {:sku "GAP-1" :name "Gap"})
            _ (t/upload-images! app owner [["GAP-1-3.jpg" (jpg)]])
            _ (t/upload-images! app owner [["GAP-1-1.jpg" (jpg)]])
            r3 (t/upload-images! app owner [["GAP-1-2.jpg" (jpg)]])
            imgs (:images (detail (:id c)))]
        (is (= 3 (count imgs)))
        (is (= 2 (get-in r3 [:body :items 0 :position])))))
    (testing "20 files at most"
      (is (= 422 (:status (t/upload-images! app owner (repeat 21 ["AB-1.jpg" (jpg)]))))))))

(deftest there-is-no-photo-library
  (let [ctx (t/make-ctx) app (t/app ctx) owner (t/setup-owner! ctx app)
        p (draft! app owner {:sku "LIB-1"})
        up (t/upload-to-product! app owner (:id p) [["a.jpg" (jpg)]])
        photo-id (get-in up [:body :items 0 :image :id])]
    (testing "the old endpoints are gone"
      (is (= 404 (:status (t/staff app owner :get "/api/admin/photos" :idem false))))
      (is (= 404 (:status (t/staff app owner :delete (str "/api/admin/photos/" photo-id) :idem false))))
      (is (= 404 (:status (t/staff app owner :post "/api/admin/photos" :body {}))))
      (is (= 404 (:status (t/staff app owner :post "/api/admin/photo-assignments" :body {}))))
      (is (not-any? #(re-find #"/photos|photo-assignments" (:path %)) (http/route-table))))
    (testing "every stored photo record belongs to exactly one product"
      (let [owners (d/q '[:find ?photo (count ?p) :where [?photo :photo/id] [?i :product-image/photo ?photo] [?p :product/images ?i]]
                        (t/db ctx))]
        (is (= (photo-count ctx) (count owners)))
        (is (every? #(= 1 (second %)) owners))))
    (testing "removing the picture from its product removes the record"
      (t/staff app owner :patch (str "/api/admin/products/" (:id p)) :body {:version (get-in up [:body :product :version]) :images []})
      (is (zero? (photo-count ctx))))))

(deftest a-published-product-keeps-at-least-one-picture
  (let [ctx (t/make-ctx) app (t/app ctx) owner (t/setup-owner! ctx app)
        p (t/seed-product! ctx {:name "Keeper"})
        cur (catalog-db/product-by-id (t/db ctx) (:id p))
        r (t/staff app owner :patch (str "/api/admin/products/" (:id p)) :body {:version (:version cur) :images []})]
    (is (= "imageRequired" (get-in r [:body :error :details 0 :code])))
    (is (= 1 (photo-count ctx)) "nothing was deleted by the rejected request")))
