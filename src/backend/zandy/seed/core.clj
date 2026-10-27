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

(defn seed-products
  "The seed product definitions read from resources/seed/products.edn (a vector of maps with :sku :name :brand :category-slug :price :images ...)."
  [] (read-resource "seed/products.edn"))

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
        ;; Created as a draft first: a published product must already have a picture, so pictures are attached before publishing.
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
  "Creates every seed product whose SKU does not exist yet, so running it again adds nothing. Argument: ctx (:config :conn :clock :media-store :image-processor). Returns the number created.
  Side effects: Datomic transactions and media files per product and picture, through the same catalog functions the API uses."
  [ctx]
  (reduce
   (fn [n {:keys [sku] :as p}]
     (if (catalog/sku-exists? ctx sku)
       n
       (do (seed-product! ctx (update p :status #(or % :published))) (inc n))))
   0
   (seed-products)))

(defn -main
  "Entry point of `clojure -M:seed`: migrates (or verifies migrations), then seeds the catalogue into the configured database.
  Shuts down agents at the end because Datomic's non-daemon threads would otherwise keep the JVM alive against a real transactor."
  [& _]
  (let [cfg (config/load-config (config/profile-from-env))
        conn (datomic/connect! (get-in cfg [:datomic :uri]))]
    (if (get-in cfg [:datomic :auto-migrate?])
      (migrations/migrate! conn)
      (migrations/verify-current! conn))
    ;; A minimal ctx: seeding needs only the catalog functions, not the mailer or user lookup.
    (let [ctx {:config cfg :conn conn :clock (fn [] (java.util.Date.))
               :media-store (media/local-store (get-in cfg [:media :dir]))
               :image-processor (get-in cfg [:media :processor])}
          n (seed! ctx)]
      (log/info :seed-done {:created n})
      (datomic/release! conn)
      (shutdown-agents))))
