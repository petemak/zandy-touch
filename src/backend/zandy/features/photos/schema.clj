(ns zandy.features.photos.schema)

(def ImportQuery
  [:map
   [:dry-run {:optional true} :boolean]])
