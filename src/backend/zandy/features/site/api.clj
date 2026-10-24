(ns zandy.features.site.api
  "Site configuration helpers for other slices."
  (:require [zandy.features.site.logic :as logic]))

(def city-allowed? logic/city-allowed?)
(def canonical-city logic/canonical-city)
