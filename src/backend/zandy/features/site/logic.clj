(ns zandy.features.site.logic
  "Pure mapping from configuration to the public site responses.")

(defn site-info
  "Contract 2 GET /api/site-info body, built from the config map keys :site and :catalog.
  Returns {:business-name :tagline :phone :whatsapp :email :shop-address :opening-hours :social {...} :currency \"UGX\" :payment-methods :max-quantity-per-line}. Pure."
  [{:keys [site catalog]}]
  {:business-name (:business-name site)
   :tagline (:tagline site)
   :phone (:phone site)
   :whatsapp (:whatsapp site)
   :email (:email site)
   :shop-address (:shop-address site)
   :opening-hours (:opening-hours site)
   :social {:instagram (get-in site [:social :instagram])
           :tiktok (get-in site [:social :tiktok])
           :facebook (get-in site [:social :facebook])}
   :currency "UGX"
   :payment-methods ["cash" "mobileMoney"]
   :max-quantity-per-line (:max-quantity-per-line catalog)})

(defn delivery-areas
  "Contract 2 GET /api/delivery-areas body from config :site. Returns {:countries [{:code :name :cities [..]}] :allow-other-city boolean}. Pure."
  [{:keys [site]}]
  {:countries (:countries site)
   :allow-other-city (:allow-other-city? site)})

(defn city-allowed?
  "True when `city` is in the configured city list of `country-code` (case-insensitive) or config :site :allow-other-city? is true; false for an unknown country.
  Arguments: (config country-code city); config is the full config map. Pure."
  [{:keys [site]} country-code city]
  (let [country (some #(when (= (:code %) country-code) %) (:countries site))
        known (set (map #(.toLowerCase ^String %) (:cities country)))]
    (boolean (and country
                  (or (contains? known (.toLowerCase ^String city))
                      (:allow-other-city? site))))))

(defn canonical-city
  "The configured spelling of `city` in the country `country-code` (case-insensitive match), else `city` unchanged. Arguments: (config country-code city). Pure."
  [{:keys [site]} country-code city]
  (let [country (some #(when (= (:code %) country-code) %) (:countries site))]
    (or (some #(when (.equalsIgnoreCase ^String % city) %) (:cities country)) city)))
