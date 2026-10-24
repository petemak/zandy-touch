(ns zandy.seed.core
  "Seeds the sample catalogue (22 published products and 1 draft, SKUs start with SEED-) through the
  same catalog functions the API uses. Idempotent by SKU. Run: clojure -M:seed"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [zandy.features.catalog.api :as catalog]
            [zandy.platform.config :as config]
            [zandy.platform.datomic :as datomic]
            [zandy.platform.log :as log]
            [zandy.platform.media :as media]
            [zandy.platform.migrations :as migrations]))

(defn- read-resource [path]
  (edn/read-string (slurp (or (io/resource path) (throw (ex-info (str "Missing seed resource " path) {}))))))

(defn seed-products [] (read-resource "seed/products.edn"))

(defn- store-seed-image!
  "Stores one seed image through the media pipeline. Returns the photo id."
  [ctx file-name]
  (let [res (or (io/resource (str "seed/images/" file-name)) (throw (ex-info (str "Missing seed image " file-name) {})))
        tmp (java.io.File/createTempFile "seed-" (str "-" file-name))]
    (try
      (with-open [in (io/input-stream res)] (io/copy in tmp))
      (let [photo-id (random-uuid)
            result (media/process-upload! (:media-store ctx) (:image-processor ctx) photo-id tmp)]
        (if-let [ok (:ok result)]
          (do (catalog/create-photo! ctx {:id photo-id :original-filename file-name :content-type (:content-type ok)
                                          :width (:width ok) :height (:height ok) :seed? true})
              photo-id)
          (throw (ex-info (str "Seed image rejected: " file-name) {:error (:error result)}))))
      (finally (io/delete-file tmp true)))))

(defn seed!
  "Creates every seed product that does not exist yet. Returns the number created."
  [ctx]
  (let [existing? (fn [sku] (catalog/sku-exists? ctx sku))]
    (reduce
     (fn [n {:keys [sku images status stock-quantity] :as p}]
       (if (existing? sku)
         n
         (let [photo-ids (mapv (fn [{:keys [file alt]}] {:id (str (store-seed-image! ctx file)) :alt alt}) images)
               fields (-> p
                          (dissoc :images :stock)
                          (assoc :images photo-ids :stock-quantity (or stock-quantity 0)
                                 :status (name (or status :published))))]
           (catalog/create-product! ctx {} (select-keys fields [:name :brand :is-own-brand :category-slug :price :short-description
                                                                 :description :size :how-to-use :ingredients :featured :status
                                                                 :sku :images :stock-quantity]))
           (inc n))))
     0
     (seed-products))))

(defn -main [& _]
  (let [cfg (config/load-config (config/profile-from-env))
        conn (datomic/connect! (get-in cfg [:datomic :uri]))]
    (if (get-in cfg [:datomic :auto-migrate?])
      (migrations/migrate! conn)
      (migrations/verify-current! conn))
    (let [ctx {:config cfg :conn conn :clock (fn [] (java.util.Date.))
               :media-store (media/local-store (get-in cfg [:media :dir]))
               :image-processor (get-in cfg [:media :processor])}
          n (seed! ctx)]
      (log/info :seed-done {:created n})
      (datomic/release! conn)
      (shutdown-agents))))
