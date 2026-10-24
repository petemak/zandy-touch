(ns zandy.features.catalog.schema
  (:require [zandy.platform.validation :as v]))

(def uuid-string v/UuidString)
(def Page v/Page)
(def PageSize v/PageSize)

(def PublicProductsQuery
  [:map
   [:category-slug {:optional true} :string]
   [:q {:optional true} [:string {:min 2 :max 80}]]
   [:availability {:optional true} [:enum "available" "soldOut"]]
   [:own-brand {:optional true} :boolean]
   [:featured {:optional true} :boolean]
   [:sort {:optional true} [:enum "newest" "priceAsc" "priceDesc" "nameAsc"]]
   [:page {:optional true} Page]
   [:page-size {:optional true} PageSize]])

(def Image
  [:map {:closed true}
   [:id uuid-string]
   [:alt {:optional true} [:maybe [:string {:max 140}]]]])

(def Price [:int {:min 100 :max 100000000}])

(def CreateProduct
  [:map {:closed true}
   [:name [:string {:min 2 :max 120}]]
   [:brand [:string {:min 1 :max 80}]]
   [:is-own-brand {:optional true} :boolean]
   [:category-slug :string]
   [:price {:optional true} [:maybe Price]]
   [:short-description {:optional true} [:string {:max 200}]]
   [:description {:optional true} [:string {:max 4000}]]
   [:size {:optional true} [:string {:max 40}]]
   [:how-to-use {:optional true} [:string {:max 1000}]]
   [:ingredients {:optional true} [:string {:max 1000}]]
   [:featured {:optional true} :boolean]
   [:status [:enum "draft" "published"]]
   [:sku {:optional true} [:string {:max 40}]]
   [:images {:optional true} [:vector {:max 6} Image]]
   [:stock-quantity {:optional true} [:int {:min 0 :max 100000}]]])

(def PatchProduct
  [:map {:closed true}
   [:version [:int {:min 1}]]
   [:name {:optional true} [:string {:min 2 :max 120}]]
   [:brand {:optional true} [:string {:min 1 :max 80}]]
   [:is-own-brand {:optional true} :boolean]
   [:category-slug {:optional true} :string]
   [:price {:optional true} [:maybe Price]]
   [:short-description {:optional true} [:maybe [:string {:max 200}]]]
   [:description {:optional true} [:maybe [:string {:max 4000}]]]
   [:size {:optional true} [:maybe [:string {:max 40}]]]
   [:how-to-use {:optional true} [:maybe [:string {:max 1000}]]]
   [:ingredients {:optional true} [:maybe [:string {:max 1000}]]]
   [:featured {:optional true} :boolean]
   [:status {:optional true} [:enum "draft" "published"]]
   [:sku {:optional true} [:maybe [:string {:max 40}]]]
   [:images {:optional true} [:vector {:max 6} Image]]])

(def AdminProductsQuery
  [:map
   [:status {:optional true} [:enum "draft" "published" "archived"]]
   [:category-slug {:optional true} :string]
   [:q {:optional true} [:string {:max 80}]]
   [:stock {:optional true} [:enum "out" "low"]]
   [:page {:optional true} Page]
   [:page-size {:optional true} PageSize]
   [:sort {:optional true} [:enum "updatedAtDesc" "nameAsc" "stockAsc"]]])

(def StockMovement
  [:map {:closed true}
   [:delta [:and [:int {:min -100000 :max 100000}] [:fn {:error/code "invalid" :error/message "The change must not be zero."} (complement zero?)]]]
   [:reason [:enum "restock" "stocktake" "shopSale" "damagedOrLost" "returnedGoods" "other"]]
   [:note {:optional true} [:string {:max 300}]]
   [:expected-quantity {:optional true} [:int {:min 0}]]])

(def PagingQuery v/PagingQuery)
