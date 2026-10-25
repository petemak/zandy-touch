(ns zandy.shared.document-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [zandy.shared.document :as document]
            [zandy.shared.pages :as pages]
            [zandy.shared.routes :as routes]))

(def site {:businessName "Zandy Touch" :tagline "Beauty with Confidence" :phone "+256706105779"
           :shopAddress "Kampala" :openingHours [{:days "Monday to Saturday" :opens "08:00" :closes "22:00"}] :social {:tiktok "https://www.tiktok.com/@zandytouch" :facebook "https://www.facebook.com/nanyonjo.sandra.1/" :instagram nil}})

(defn render [url page]
  (document/render-page {:state (assoc (pages/initial-state) :route (routes/match-url url) :site site :page page)
                         :base-url "https://example.test" :assets {:css ["/a.css"] :js "/m.js"}}))

(defn n [html re] (count (re-seq re html)))

(def product {:id "p1" :slug "glow" :name "Glow \"Serum\" <b>" :brand "Zandy Touch" :price 85000 :currency "UGX" :availability "available"
              :maxQuantity 10 :category {:slug "skincare" :name "Skincare"} :shortDescription "A serum." :images [] :related []})

(deftest seo-head-is-in-the-first-html
  (doseq [[url page] [["/" {:status :ok :data {:categories [] :new {:items []} :own {:items []} :featured {:items []}}}]
                      ["/shop" {:status :ok :data {:products {:items [] :page 1 :pageSize 24 :totalItems 0}}}]
                      ["/products/glow" {:status :ok :data product}]
                      ["/about" {:status :ok}]
                      ["/legal/privacy" {:status :ok :data {:title "Privacy" :version "v" :bodyBlocks [] :reviewStatus "approved"}}]]]
    (let [html (render url page)]
      (testing url
        (is (= 1 (n html #"<title>")) "one title")
        (is (= 1 (n html #"name=\"description\"")))
        (is (= 1 (n html #"rel=\"canonical\"")))
        (is (= 1 (n html #"property=\"og:title\"")))
        (is (= 1 (n html #"property=\"og:image\"")))
        (is (= 1 (n html #"application/ld\+json")))
        (is (= 1 (n html #"<h1[ >]")) "one h1")))))

(deftest titles-are-unique-per-page
  (let [title (fn [url page] (second (re-find #"<title>([^<]*)</title>" (render url page))))
        titles [(title "/" {:status :ok :data {}}) (title "/about" {:status :ok}) (title "/contact" {:status :ok})
                (title "/delivery-and-payment" {:status :ok :data {}})]]
    (is (= (count titles) (count (set titles))))
    (is (not-any? #(= "Zandy Touch" %) titles))))

(deftest product-page-carries-product-and-offer
  (let [html (render "/products/glow" {:status :ok :data product})]
    (is (str/includes? html "\"@type\":\"Product\""))
    (is (str/includes? html "\"@type\":\"Offer\""))
    (is (str/includes? html "https://schema.org/InStock"))
    (is (str/includes? html "\"price\":\"85000\""))
    (is (str/includes? html "\"@type\":\"Store\""))
    (is (str/includes? html "<link rel=\"canonical\" href=\"https://example.test/products/glow\">"))))

(deftest text-is-escaped
  (let [html (render "/products/glow" {:status :ok :data product})]
    (is (not (str/includes? html "<b>")) "product name markup is escaped")
    (testing "no closing script tag can come out of embedded JSON"
      (let [state-json (second (re-find #"id=\"initial-state\"[^>]*>(.*?)</script>" html))]
        (is (not (str/includes? state-json "<")))))))

(deftest noindex-and-status
  (is (str/includes? (render "/cart" {:status :loading}) "content=\"noindex, follow\""))
  (is (str/includes? (render "/shop?q=serum" {:status :ok :data {:products {:items [] :page 1 :pageSize 24 :totalItems 0}}}) "noindex"))
  (is (not (str/includes? (render "/shop" {:status :ok :data {:products {:items [] :page 1 :pageSize 24 :totalItems 0}}}) "noindex")))
  (testing "canonical of a filtered shop view is /shop"
    (is (str/includes? (render "/shop?availability=available" {:status :ok :data {:products {:items [] :page 1 :pageSize 24 :totalItems 0}}})
                       "rel=\"canonical\" href=\"https://example.test/shop\""))))

(deftest hidden-when-unknown
  (let [html (document/render-page {:state (assoc (pages/initial-state) :route (routes/match-url "/contact")
                                                  :site (assoc site :whatsapp nil :email nil :shopAddress nil :openingHours nil) :page {:status :ok})
                                    :base-url "https://example.test" :assets {}})]
    (is (not (str/includes? html "wa.me")))
    (is (not (str/includes? html "mailto:")))
    (is (not (str/includes? html "instagram.com")))))

(deftest social-profiles-in-footer-and-json-ld
  (let [html (render "/about" {:status :ok})]
    (testing "configured profiles are linked with their icons in the footer"
      (is (str/includes? html "href=\"https://www.tiktok.com/@zandytouch\""))
      (is (str/includes? html "href=\"https://www.facebook.com/nanyonjo.sandra.1/\""))
      (is (str/includes? html "icon-tiktok"))
      (is (str/includes? html "icon-facebook")))
    (testing "the links sit under a Follow us heading and show their names"
      (is (str/includes? html ">Follow us</h2>"))
      (is (str/includes? html "site-footer__social-label\">TikTok</span>"))
      (is (str/includes? html "site-footer__social-label\">Facebook</span>")))
    (testing "a null profile is hidden (Instagram is not configured yet)"
      (is (not (str/includes? html "instagram.com")))
      (is (not (str/includes? html "icon-instagram"))))
    (testing "non-null profiles are the Store sameAs"
      (is (str/includes? html "\"sameAs\":[\"https://www.tiktok.com/@zandytouch\",\"https://www.facebook.com/nanyonjo.sandra.1/\"]"))))
  (testing "Instagram appears once configured"
    (let [html (document/render-page {:state (assoc (pages/initial-state) :route (routes/match-url "/about")
                                                    :site (assoc-in site [:social :instagram] "https://www.instagram.com/zandytouch") :page {:status :ok})
                                      :base-url "https://example.test" :assets {}})]
      (is (str/includes? html "icon-instagram"))
      (is (str/includes? html "https://www.instagram.com/zandytouch\"]"))))
  (testing "no social list and no sameAs when nothing is configured"
    (let [html (document/render-page {:state (assoc (pages/initial-state) :route (routes/match-url "/about")
                                                    :site (assoc site :social {:tiktok nil :facebook nil :instagram nil}) :page {:status :ok})
                                      :base-url "https://example.test" :assets {}})]
      (is (not (str/includes? html "site-footer__social")))
      (is (not (str/includes? html "Follow us")))
      (is (not (str/includes? html "sameAs"))))))
