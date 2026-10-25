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

(defn- attach-seed-image!
  "Stores one seed image on the product through the same catalog function the upload endpoints use."
  [ctx product-id {:keys [file alt]}]
  (let [res (or (io/resource (str "seed/images/" file)) (throw (ex-info (str "Missing seed image " file) {})))
        tmp (java.io.File/createTempFile "seed-" (str "-" file))]
    (try
      (with-open [in (io/input-stream res)] (io/copy in tmp))
      (let [result (catalog/attach-file! ctx {:filename file :seed? true} product-id tmp)]
        (when-not (= :attached (:status result))
          (throw (ex-info (str "Seed image rejected: " file) {:error (:error result)})))
        (cond-> {:id (str (get-in result [:image :id]))} alt (assoc :alt alt)))
      (finally (io/delete-file tmp true)))))

(defn- seed-product!
  "Creates the product as a draft, attaches its pictures, then publishes it (a published product needs a picture)."
  [ctx {:keys [images status stock-quantity] :as p}]
  (let [fields (select-keys p [:name :brand :is-own-brand :category-slug :price :short-description :description
                               :size :how-to-use :ingredients :featured :sku])
        product (catalog/create-product! ctx {} (assoc fields :status "draft" :stock-quantity (or stock-quantity 0)))
        id (:id product)
        pictures (mapv #(attach-seed-image! ctx id %) images)]
    (when (not= :draft (or status :published))
      (let [current (catalog/get-product ctx id)]
        (catalog/update-product! ctx {} id
                                 {:version (:version current)
                                  :status (name status)
                                  :images pictures})))))

(defn seed!
  "Creates every seed product that does not exist yet. Returns the number created."
  [ctx]
  (reduce
   (fn [n {:keys [sku] :as p}]
     (if (catalog/sku-exists? ctx sku)
       n
       (do (seed-product! ctx (update p :status #(or % :published))) (inc n))))
   0
   (seed-products)))

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
