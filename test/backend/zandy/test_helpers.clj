(ns zandy.test-helpers
  "Shared test setup: an isolated in-memory database, a controllable clock, the log mailer and a
  temporary media directory. Requests go through the real Pedestal interceptor chain in-process."
  (:require [clojure.string :as str]
            [datomic.api :as d]
            [io.pedestal.connector.test :as pt]
            [zandy.features.accounts.db :as accounts-db]
            [zandy.features.accounts.passwords :as passwords]
            [zandy.platform.config :as config]
            [zandy.platform.datomic :as datomic]
            [zandy.platform.http :as http]
            [zandy.features.catalog.api :as catalog]
            [zandy.platform.json :as json]
            [zandy.platform.log :as log]
            [zandy.platform.mailer :as mailer]
            [zandy.platform.migrations :as migrations]
            [zandy.platform.system :as system])
  (:import (java.nio.file Files)
           (java.util Date)))

(alter-var-root #'log/*sink* (constantly (fn [_] nil)))

(def owner-password "correct-horse-1")

(defn make-ctx
  "A fresh context. Override keys with `overrides` (e.g. {:clock f})."
  ([] (make-ctx {}))
  ([overrides]
   (let [cfg (-> (config/load-config :test)
                 (assoc-in [:datomic :uri] (str (or (System/getenv "ZANDY_TEST_DATOMIC_URI_PREFIX") "datomic:mem://") "test-" (random-uuid))))
         media-dir (str (Files/createTempDirectory "zandy-test-media" (make-array java.nio.file.attribute.FileAttribute 0)))
         cfg (assoc-in cfg [:media :dir] media-dir)
         ;; generous limits so ordinary tests are not throttled; the login limit stays real
         cfg (update cfg :limits (fn [ls] (into {} (map (fn [[k v]] [k (if (= k :login) v (assoc v :limit 100000))])) ls)))
         conn (datomic/connect! (get-in cfg [:datomic :uri]))
         clock (atom (Date.))]
     (migrations/migrate! conn)
     (system/build-ctx cfg conn (merge {:clock (fn [] @clock) :mailer (mailer/log-mailer) ::clock clock} overrides)))))

(defn dev-storage?
  "True when the tests run against a real transactor (ZANDY_TEST_DATOMIC_URI_PREFIX=datomic:dev://host:4334/)."
  []
  (some? (System/getenv "ZANDY_TEST_DATOMIC_URI_PREFIX")))

(defn advance-clock! [ctx ms]
  (swap! (::clock ctx) (fn [^Date d] (Date. (+ (.getTime d) ms)))))

(defn app [ctx] (http/connector ctx))

(defn call
  "Performs a request. Returns {:status :headers :body} with the JSON body parsed (kebab keys)."
  [connector method path & {:keys [body headers raw-body]}]
  (let [headers (cond-> (merge {} headers)
                  (and body (not (contains? headers "Content-Type"))) (assoc "Content-Type" "application/json"))
        resp (pt/response-for connector method path
                              :headers headers
                              :body (cond raw-body raw-body body (json/encode body)))
        ct (get-in resp [:headers "Content-Type"] "")
        parsed (if (and (str/starts-with? ct "application/json") (seq (:body resp)))
                 (json/decode (:body resp))
                 (:body resp))]
    (assoc resp :body parsed :raw (:body resp))))

(defn error-code [resp] (get-in resp [:body :error :code]))

(defn create-user!
  [ctx {:keys [name email role password must-change? notify?] :or {role :support password owner-password}}]
  (accounts-db/create-user! (:conn ctx)
                            {:id (random-uuid) :name name :email email :role role
                             :password-hash (passwords/hash-password password)
                             :must-change-password? (boolean must-change?)
                             :notify-on-new-order? (boolean notify?) :created-at (Date.)}))

(defn login!
  "Signs in and returns {:cookie :csrf :user} to use with `staff`."
  [connector email password]
  (let [resp (call connector :post "/api/auth/sessions" :body {:email email :password password})
        set-cookie (get-in resp [:headers "Set-Cookie"])]
    (when-not (= 201 (:status resp)) (throw (ex-info "login failed" {:resp resp})))
    {:cookie (first (str/split set-cookie #";")) :csrf (get-in resp [:body :csrf-token]) :user (get-in resp [:body :user])}))

(defn staff
  "A request as a signed-in staff member (cookie, CSRF token, and an Idempotency-Key unless given)."
  [connector session method path & {:keys [body headers idem]}]
  (call connector method path
        :body body
        :headers (merge {"Cookie" (:cookie session) "X-CSRF-Token" (:csrf session)}
                        (when-not (= idem false) {"Idempotency-Key" (or idem (str (random-uuid)))})
                        headers)))

(defn setup-owner!
  "Creates the owner and signs in. Returns the session."
  [ctx connector]
  (create-user! ctx {:name "Sandra" :email "owner@example.com" :role :owner :notify? true})
  (login! connector "owner@example.com" owner-password))

(defn sent-mail [ctx] (mailer/sent-messages (:mailer ctx)))

(defn db [ctx] (d/db (:conn ctx)))

(defn jpeg-bytes
  "A small solid-colour JPEG."
  ([] (jpeg-bytes 64 48))
  ([w h]
   (let [img (java.awt.image.BufferedImage. w h java.awt.image.BufferedImage/TYPE_INT_RGB)
         out (java.io.ByteArrayOutputStream.)]
     (javax.imageio.ImageIO/write img "jpg" out)
     (.toByteArray out))))

(defn temp-jpeg!
  "A temporary JPEG file (attach-file! reads from a file)."
  ([] (temp-jpeg! 64 48))
  ([w h]
   (let [f (java.io.File/createTempFile "test-pic" ".jpg")]
     (java.nio.file.Files/write (.toPath f) ^bytes (jpeg-bytes w h) (make-array java.nio.file.OpenOption 0))
     f)))

(defn seed-product!
  "A published product with one picture, made through the same catalog functions the API uses.
  Returns the product map from the catalog."
  [ctx {:keys [name price stock sku category brand own-brand]
        :or {name "Glow Serum" price 85000 stock 10 category "skincare" brand "Zandy Touch"}}]
  (let [p (catalog/create-product! ctx {}
                                   (cond-> {:name name :brand brand :is-own-brand (boolean own-brand) :category-slug category
                                            :price price :status "draft" :stock-quantity stock}
                                     sku (assoc :sku sku)))
        r (catalog/attach-file! ctx {:filename "x.jpg"} (:id p) (temp-jpeg!))]
    (catalog/update-product! ctx {} (:id p)
                             {:version (:version (catalog/get-product ctx (:id p)))
                              :status "published"
                              :images [{:id (str (get-in r [:image :id])) :alt "Photo"}]})))

(defn order-body
  "A valid POST /api/orders body for the products given as [[product qty] ...]."
  ([items] (order-body items {}))
  ([items overrides]
   (merge {:customer {:name "Amina Nakato" :phone "0771234567" :email "amina@example.com" :preferred-contact "whatsapp"}
           :delivery {:country-code "UG" :city "Kampala" :area "Ntinda" :address-line "Plot 12, Minister's Village Rd"
                      :directions "Blue gate"}
           :payment-method "mobileMoney"
           :note "Please call after 5pm"
           :items (mapv (fn [[p q]] {:product-id (str (:id p)) :quantity q :expected-unit-price (:price p)}) items)
           :agreement {:type "orderTerms" :version "2026-10-01"}}
          overrides)))

(defn place-order!
  ([app items] (place-order! app items (str (random-uuid)) {}))
  ([app items idem overrides]
   (call app :post "/api/orders" :body (order-body items overrides) :headers {"Idempotency-Key" idem})))

(defn multipart
  "Builds a multipart/form-data body from [[filename bytes] ...] in the field `field` (default \"files\").
  Returns {:content-type :body}."
  ([files] (multipart "files" files))
  ([field files]
   (let [boundary "----zandytestboundary"
         out (java.io.ByteArrayOutputStream.)
         w (fn [^String s] (.write out (.getBytes s "UTF-8")))]
     (doseq [[filename ^bytes bs] files]
       (w (str "--" boundary "\r\nContent-Disposition: form-data; name=\"" field "\"; filename=\"" filename "\"\r\nContent-Type: application/octet-stream\r\n\r\n"))
       (.write out bs)
       (w "\r\n"))
     (w (str "--" boundary "--\r\n"))
     {:content-type (str "multipart/form-data; boundary=" boundary)
      :body (java.io.ByteArrayInputStream. (.toByteArray out))})))

(defn post-multipart!
  "POSTs a multipart body as staff. `idem` false sends no Idempotency-Key."
  [connector session path field files & {:keys [idem]}]
  (let [{:keys [content-type body]} (multipart field files)]
    (call connector :post path
          :raw-body body
          :headers (cond-> {"Content-Type" content-type "Cookie" (:cookie session) "X-CSRF-Token" (:csrf session)}
                     (not= idem false) (assoc "Idempotency-Key" (or idem (str (random-uuid))))))))

(defn upload-images!
  "POST /api/admin/product-images (files named by SKU)."
  [connector session files & {:keys [idem]}]
  (post-multipart! connector session "/api/admin/product-images" "files" files :idem idem))

(defn upload-to-product!
  "POST /api/admin/products/{id}/images."
  [connector session product-id files & {:keys [idem]}]
  (post-multipart! connector session (str "/api/admin/products/" product-id "/images") "files" files :idem idem))

(defn import-csv!
  "POST /api/admin/product-imports with CSV text (or bytes)."
  [connector session csv & {:keys [idem dry-run]}]
  (post-multipart! connector session (str "/api/admin/product-imports" (when dry-run "?dryRun=true")) "file"
                   [["products.csv" (if (string? csv) (.getBytes ^String csv "UTF-8") csv)]] :idem idem))
