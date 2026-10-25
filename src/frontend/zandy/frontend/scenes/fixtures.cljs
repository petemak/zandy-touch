(ns zandy.frontend.scenes.fixtures
  "Sample data shaped exactly like api-contract.md responses, for scenes."
  (:require [zandy.shared.pages :as pages]
            [zandy.shared.routes :as routes]))

(def site
  {:businessName "Zandy Touch" :tagline "Beauty with Confidence" :phone "+256706105779" :whatsapp "+256706105779"
   :email "sandratonanyonjo@gmail.com" :shopAddress "Ring roads, Rubaga Rd, opposite Red Cross, Kampala, Uganda"
   :openingHours [{:days "Monday to Saturday" :opens "08:00" :closes "22:00"} {:days "Sunday" :opens "10:00" :closes "21:30"}]
   :social {:tiktok "https://www.tiktok.com/@zandytouch" :facebook "https://www.facebook.com/nanyonjo.sandra.1/" :instagram nil} :currency "UGX" :paymentMethods ["cash" "mobileMoney"] :maxQuantityPerLine 10})

(def site-minimal (assoc site :whatsapp nil :email nil :shopAddress nil :openingHours nil))
(def site-social (assoc-in site [:social] {:tiktok "https://www.tiktok.com/@zandytouch" :facebook "https://www.facebook.com/nanyonjo.sandra.1/" :instagram "https://www.instagram.com/example"}))
(def site-no-social (assoc-in site [:social] {:tiktok nil :facebook nil :instagram nil}))

(def sample-photo
  "A neutral inline SVG so scenes need no image files."
  "data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 4 5'><rect width='4' height='5' fill='gainsboro'/><rect x='1.4' y='1.4' width='1.2' height='2.2' rx='0.2' fill='gray'/></svg>")

(defn img [n] {:id (str "ph" n) :alt "Cosmetic jar on a plain background"
               :urls {:thumb sample-photo :medium sample-photo :large sample-photo}})

(def serum {:id "p1" :slug "glow-serum" :name "Radiance Vitamin C Serum 30 ml" :brand "Zandy Touch" :isOwnBrand true
            :categorySlug "skincare" :price 85000 :currency "UGX" :shortDescription "A daily serum." :availability "available"
            :maxQuantity 10 :isNew true :featured false :image (img 1)})
(def cleanser (assoc serum :id "p2" :slug "cleanser" :name "Gentle Foaming Face Cleanser 150 ml" :brand "Sample Brand" :isOwnBrand false :price 38000 :isNew false))
(def sold-out (assoc cleanser :id "p3" :slug "sunscreen" :name "Mineral Sunscreen SPF 50, 60 ml" :availability "soldOut" :maxQuantity 0))
(def low (assoc serum :id "p4" :slug "cream" :name "Daily Moisturising Cream 50 ml" :maxQuantity 3))
(def products [serum cleanser sold-out low])

(def product-detail
  {:id "p1" :slug "glow-serum" :name "Radiance Vitamin C Serum 30 ml" :brand "Zandy Touch" :isOwnBrand true
   :category {:slug "skincare" :name "Skincare"} :price 85000 :currency "UGX" :availability "available" :maxQuantity 10 :isNew true
   :shortDescription "A daily serum for a fresh look." :description "A lightweight serum.\n\nUse after cleansing."
   :size "30 ml" :howToUse "Apply two drops morning and evening." :ingredients nil
   :images [(img 1) (img 2)] :related [cleanser low] :updatedAt "2026-10-20T08:00:00Z"})

(def categories
  (mapv (fn [[i [slug nm]]] {:id (str "c" i) :slug slug :name nm :description (str nm " products.") :sortOrder i :productCount 4})
        (map-indexed (fn [i x] [(inc i) x])
                     [["skincare" "Skincare"] ["perfumes" "Perfumes"] ["body-shaping" "Body-shaping"]
                      ["body-care" "Body care"] ["weight-loss" "Weight loss"] ["health-and-wellness" "Health & Wellness"]])))

(def listing {:items products :page 1 :pageSize 24 :totalItems 4})
(def listing-more (assoc listing :totalItems 60))

(def policy {:type "orderTerms" :version "2026-10-01" :title "Order terms" :reviewStatus "placeholder"
             :summary ["Prices shown are product prices in UGX and exclude delivery." "The delivery fee is agreed with you before dispatch."]
             :bodyBlocks [{:type "heading" :text "1. Prices and delivery"} {:type "paragraph" :text "Draft paragraph."}
                          {:type "list" :items ["First point" "Second point"]}]
             :contentHash "sha256:abc" :effectiveFrom "2026-10-01T00:00:00Z"})
(def policy-approved (assoc policy :reviewStatus "approved"))

(def cart-items [{:product-id "p1" :slug "glow-serum" :name "Radiance Vitamin C Serum 30 ml" :price 85000 :image (img 1) :quantity 2}
                 {:product-id "p2" :slug "cleanser" :name "Gentle Foaming Face Cleanser 150 ml" :price 38000 :image nil :quantity 1}])

(def validation-ok {:items [{:productId "p1" :slug "glow-serum" :name "Radiance Vitamin C Serum 30 ml" :unitPrice 85000 :quantity 2 :lineTotal 170000
                             :availability "available" :maxQuantity 10 :purchasable true :image (img 1) :problem nil}
                            {:productId "p2" :slug "cleanser" :name "Gentle Foaming Face Cleanser 150 ml" :unitPrice 38000 :quantity 1 :lineTotal 38000
                             :availability "available" :maxQuantity 10 :purchasable true :image nil :problem nil}]
                    :itemsSubtotal 208000 :currency "UGX" :deliveryFee nil :hasProblems false})
(def validation-problems
  (-> validation-ok
      (assoc-in [:items 0] {:productId "p1" :slug "glow-serum" :name "Radiance Vitamin C Serum 30 ml" :unitPrice 90000 :quantity 2 :lineTotal 0
                            :availability "available" :maxQuantity 1 :purchasable false :image (img 1) :problem "insufficientStock"})
      (assoc-in [:items 1 :problem] "soldOut")
      (assoc-in [:items 1 :purchasable] false)
      (assoc :hasProblems true)))

(def order {:id "o1" :orderNumber "ZT-7K3P9Q" :status "placed" :placedAt "2026-10-24T09:30:00Z" :version 1
            :customer {:name "Amina Nakato" :phone "+256771234567" :email "amina@example.com" :preferredContact "whatsapp"}
            :delivery {:countryCode "UG" :city "Kampala" :area "Ntinda" :addressLine "Plot 12, Minister's Village Rd" :directions "Blue gate"}
            :paymentMethod "mobileMoney" :note "Call after 5pm"
            :items [{:productId "p1" :name "Radiance Vitamin C Serum 30 ml" :unitPrice 85000 :quantity 2 :lineTotal 170000}]
            :itemsSubtotal 170000 :currency "UGX" :deliveryFee nil :total nil :staffNote nil})

(def tracking {:orderNumber "ZT-7K3P9Q" :status "confirmed" :placedAt "2026-10-24T09:30:00Z"
               :items [{:name "Radiance Vitamin C Serum 30 ml" :unitPrice 85000 :quantity 2 :lineTotal 170000}]
               :itemsSubtotal 170000 :deliveryFee 10000 :total 180000 :currency "UGX" :paymentMethod "mobileMoney" :city "Kampala"
               :timeline [{:status "placed" :at "2026-10-24T09:30:00Z"} {:status "confirmed" :at "2026-10-24T10:15:00Z"}]})

(def areas {:countries [{:code "UG" :name "Uganda" :cities ["Kampala" "Entebbe" "Jinja" "Mbale" "Mbarara" "Fort Portal"]}] :allowOtherCity false})

(defn base-state
  "A page state for `url` with page data."
  [url page & {:as extra}]
  (merge (pages/initial-state)
         {:route (routes/match-url url) :site site :page page}
         extra))
