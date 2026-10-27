(ns zandy.features.catalog.schema
  "Catalog slice, schema role: Malli schemas for the catalogue query strings and staff write bodies (contract 3, 8.1, 8.3). Maps that take a body are closed, so unknown fields are rejected with validationFailed."
  (:require [zandy.platform.validation :as v]))

(def uuid-string
  "Alias of validation/UuidString: a string holding a UUID."
  v/UuidString)
(def Page
  "Alias of validation/Page: integer >= 1."
  v/Page)
(def PageSize
  "Alias of validation/PageSize: integer 1 to 100."
  v/PageSize)

(def PublicProductsQuery
  "Query for GET /api/products: category-slug, q (2 to 80 chars), availability, own-brand, featured, sort and paging. Unknown parameters are dropped by the query transformer."
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
  "One entry of a product write's images list: {:id picture-uuid-string :alt optional nullable string up to 140}."
  [:map {:closed true}
   [:id uuid-string]
   [:alt {:optional true} [:maybe [:string {:max 140}]]]])

(def Sku
  "A SKU: 1 to 40 characters, letters, digits and \"-\" only (case is normalised to upper case by the logic layer)."
  [:and [:string {:min 1 :max 40}]
   [:re {:error/code "invalid" :error/message "A SKU may contain only letters, digits and -."} #"^[A-Za-z0-9-]+$"]])

(def Price
  "A price in whole UGX shillings (integer), 100 to 100,000,000."
  [:int {:min 100 :max 100000000}])

(def CreateProduct
  "Body of POST /api/admin/products. Required: name, brand, categorySlug, status (draft or published). Optional: price, descriptions, size, howToUse, ingredients, featured, sku, images (max 6) and the opening stockQuantity."
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
   [:sku {:optional true} Sku]
   [:images {:optional true} [:vector {:max 6} Image]]
   [:stock-quantity {:optional true} [:int {:min 0 :max 100000}]]])

(def PatchProduct
  "Body of PATCH /api/admin/products/{id}. Requires version (optimistic lock); every other field optional; nullable text fields and price/sku accept null to clear them. No stock field: stock only changes via stock movements."
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
   [:sku {:optional true} [:maybe Sku]]
   [:images {:optional true} [:vector {:max 6} Image]]])

(def AdminProductsQuery
  "Query for GET /api/admin/products: status, categorySlug, q, stock (out or low), paging and sort."
  [:map
   [:status {:optional true} [:enum "draft" "published" "archived"]]
   [:category-slug {:optional true} :string]
   [:q {:optional true} [:string {:max 80}]]
   [:stock {:optional true} [:enum "out" "low"]]
   [:page {:optional true} Page]
   [:page-size {:optional true} PageSize]
   [:sort {:optional true} [:enum "updatedAtDesc" "nameAsc" "stockAsc"]]])

(def StockMovement
  "Body of POST /api/admin/products/{id}/stock-movements: non-zero integer delta (+/-100000 units), a reason from the contract list, optional note and optional expectedQuantity (apply only if current stock equals it)."
  [:map {:closed true}
   [:delta [:and [:int {:min -100000 :max 100000}] [:fn {:error/code "invalid" :error/message "The change must not be zero."} (complement zero?)]]]
   [:reason [:enum "restock" "stocktake" "shopSale" "damagedOrLost" "returnedGoods" "other"]]
   [:note {:optional true} [:string {:max 300}]]
   [:expected-quantity {:optional true} [:int {:min 0}]]])

(def PagingQuery
  "Alias of validation/PagingQuery: optional page and pageSize."
  v/PagingQuery)

(def Publications
  "Body of POST /api/admin/product-publications: 1 to 500 product id strings."
  [:map {:closed true}
   [:product-ids [:vector {:min 1 :max 500} uuid-string]]])
