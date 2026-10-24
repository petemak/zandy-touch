(ns zandy.features.site.routes
  "Business info, delivery areas (from configuration) and the health check (contract sections 2 and 13)."
  (:require [zandy.features.site.logic :as logic]
            [zandy.features.site.db :as db]
            [zandy.platform.errors :as errors]))

(defn site-info [{:keys [ctx]}]
  {:status 200 :body (logic/site-info (:config ctx))})

(defn delivery-areas [{:keys [ctx]}]
  {:status 200 :body (logic/delivery-areas (:config ctx))})

(defn healthz [{:keys [ctx]}]
  (if (db/reachable? (:conn ctx))
    {:status 200 :headers {"Cache-Control" "no-store"} :body {:status "ok"}}
    (errors/error-response :serviceUnavailable "The service is temporarily unavailable.")))

(def routes
  [{:method :get :path "/api/site-info" :name :site/site-info :access :public
    :cache "public, max-age=300, stale-while-revalidate=600" :handler site-info}
   {:method :get :path "/api/delivery-areas" :name :site/delivery-areas :access :public
    :cache "public, max-age=3600, stale-while-revalidate=3600" :handler delivery-areas}
   {:method :get :path "/api/healthz" :name :site/healthz :access :public :handler healthz}])
