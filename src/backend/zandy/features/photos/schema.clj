(ns zandy.features.photos.schema
  (:require [zandy.platform.validation :as v]))

(def PhotosQuery
  [:map
   [:unassigned {:optional true} :boolean]
   [:page {:optional true} v/Page]
   [:page-size {:optional true} v/PageSize]])

(def Assignments
  [:map {:closed true}
   [:assignments [:vector {:min 1 :max 100}
                  [:map {:closed true}
                   [:photo-id v/UuidString]
                   [:product-id v/UuidString]]]]])
