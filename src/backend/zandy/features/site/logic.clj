(ns zandy.features.site.logic
  "Pure mapping from configuration to the public site responses.")

(defn site-info [{:keys [site catalog]}]
  {:business-name (:business-name site)
   :tagline (:tagline site)
   :phone (:phone site)
   :whatsapp (:whatsapp site)
   :email (:email site)
   :shop-address (:shop-address site)
   :opening-hours (:opening-hours site)
   :social {:instagram (get-in site [:social :instagram]) :tiktok (get-in site [:social :tiktok])}
   :currency "UGX"
   :payment-methods ["cash" "mobileMoney"]
   :max-quantity-per-line (:max-quantity-per-line catalog)})

(defn delivery-areas [{:keys [site]}]
  {:countries (:countries site)
   :allow-other-city (:allow-other-city? site)})

(defn city-allowed?
  "True when the city is in the configured list for the country (case-insensitive), or other cities are allowed."
  [{:keys [site]} country-code city]
  (let [country (some #(when (= (:code %) country-code) %) (:countries site))
        known (set (map #(.toLowerCase ^String %) (:cities country)))]
    (boolean (and country
                  (or (contains? known (.toLowerCase ^String city))
                      (:allow-other-city? site))))))

(defn canonical-city [{:keys [site]} country-code city]
  (let [country (some #(when (= (:code %) country-code) %) (:countries site))]
    (or (some #(when (.equalsIgnoreCase ^String % city) %) (:cities country)) city)))
