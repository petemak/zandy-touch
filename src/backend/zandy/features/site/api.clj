(ns zandy.features.site.api
  "Site configuration helpers for other slices."
  (:require [zandy.features.site.logic :as logic]))

(def city-allowed?
  "Public API of the site slice: true when `city` is acceptable for `country-code` under the delivery-area configuration. Arguments: (config country-code city); see logic/city-allowed?."
  logic/city-allowed?)
(def canonical-city
  "Public API of the site slice: the configured spelling of `city` for `country-code`, or `city` unchanged. Arguments: (config country-code city)."
  logic/canonical-city)
