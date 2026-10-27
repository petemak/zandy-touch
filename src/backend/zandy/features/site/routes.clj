(ns zandy.features.site.routes
  "Business info, delivery areas (from configuration) and the health check (contract sections 2 and 13)."
  (:require [zandy.features.site.logic :as logic]
            [zandy.features.site.db :as db]
            [zandy.platform.errors :as errors]))

(defn site-info
  "Handler for GET /api/site-info. Request: Ring map with :ctx. Returns 200 with the public business info from config."
  [{:keys [ctx]}]
  {:status 200 :body (logic/site-info (:config ctx))})

(defn delivery-areas
  "Handler for GET /api/delivery-areas. Returns 200 with the configured countries and cities."
  [{:keys [ctx]}]
  {:status 200 :body (logic/delivery-areas (:config ctx))})

(defn healthz
  "Handler for GET /api/healthz. Returns 200 {:status \"ok\"} when the Datomic connection answers, otherwise a serviceUnavailable (503) error response. Never cached."
  [{:keys [ctx]}]
  (if (db/reachable? (:conn ctx))
    {:status 200 :headers {"Cache-Control" "no-store"} :body {:status "ok"}}
    (errors/error-response :serviceUnavailable "The service is temporarily unavailable.")))

(def routes
  "Route data for the site slice (all :public; the two config-derived reads carry :cache headers)."
  [{:method :get :path "/api/site-info" :name :site/site-info :access :public
    :cache "public, max-age=300, stale-while-revalidate=600" :handler site-info}
   {:method :get :path "/api/delivery-areas" :name :site/delivery-areas :access :public
    :cache "public, max-age=3600, stale-while-revalidate=3600" :handler delivery-areas}
   {:method :get :path "/api/healthz" :name :site/healthz :access :public :handler healthz}])
