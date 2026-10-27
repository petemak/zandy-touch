(ns zandy.features.photos.schema
  "Photos slice, schema role: Malli schema for the query string of the product import endpoint. The upload bodies are multipart and are validated by hand in routes and imports.")

(def ImportQuery
  "Query for POST /api/admin/product-imports: optional dryRun boolean (validate and report without writing)."
  [:map
   [:dry-run {:optional true} :boolean]])
