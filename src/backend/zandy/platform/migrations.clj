(ns zandy.platform.migrations
  "Versioned migrations: resources/migrations/NNNN-description.edn.
  Each file is {:id :description :tx-data [...] :dbfns [{:ident :params :file}] :policy-files [...]}.
  A ledger of :migration/* entities records what ran. The runner refuses to continue when an applied
  file's checksum changed, a ledger id has no file, or the numbering has a gap."
  (:require [clojure.edn :as edn]
            [clojure.instant]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datomic.api :as d]
            [zandy.platform.config :as config]
            [zandy.platform.datomic :as datomic]
            [zandy.platform.log :as log])
  (:import (java.security MessageDigest)
           (java.util Date)))

(def ^:private index-resource "migrations/index.edn")

(defn- sha256 [^String s]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" %) (.digest md (.getBytes s "UTF-8"))))))

(defn- slurp-resource [path]
  (if-let [r (io/resource path)]
    (slurp r)
    (throw (ex-info (str "Missing migration resource " path) {:path path}))))

(defn- read-forms [text]
  (let [rdr (java.io.PushbackReader. (java.io.StringReader. text))]
    (loop [forms []]
      (let [f (edn/read {:eof ::eof :readers {'inst clojure.instant/read-instant-date}} rdr)]
        (if (= f ::eof) forms (recur (conj forms f)))))))

(defn- dbfn-tx
  "A db function file holds a map {:params [...]} followed by the body forms."
  [{:keys [ident file]}]
  (let [[{:keys [params]} & body] (read-forms (slurp-resource file))]
    {(keyword "db" "ident") ident ; a function install, not a schema attribute
     :db/fn (d/function {:lang "clojure"
                         :params params
                         :code (str/join "\n" (map pr-str body))})}))

(defn- policy-tx [file]
  (let [p (edn/read-string (slurp-resource file))
        hash (str "sha256:" (sha256 (pr-str [(:title p) (:summary p) (:blocks p)])))]
    {:policy/key (str (:type p) "/" (:version p))
     :policy/type (:type p)
     :policy/version (:version p)
     :policy/title (:title p)
     :policy/review-status (:review-status p)
     :policy/summary (pr-str (:summary p))
     :policy/blocks (pr-str (:blocks p))
     :policy/content-hash hash
     :policy/effective-from (:effective-from p)
     :policy/current? true}))

(defn load-migrations
  "Reads every migration listed in migrations/index.edn, in order, with its checksum."
  []
  (let [names (edn/read-string (slurp-resource index-resource))]
    (vec
     (for [name names]
       (let [path (str "migrations/" name)
             text (slurp-resource path)
             m (edn/read-string {:readers {'inst clojure.instant/read-instant-date}} text)
             extra-files (concat (map :file (:dbfns m)) (:policy-files m))
             checksum (sha256 (str/join "\n" (cons text (map slurp-resource extra-files))))]
         (assoc m :file name :checksum checksum
                :number (parse-long (re-find #"^\d{4}" name))))))))

(defn- migration-tx-data [m]
  (vec (concat (:tx-data m)
               (map dbfn-tx (:dbfns m))
               (map policy-tx (:policy-files m)))))

(defn- ledger-installed? [db]
  (some? (d/entid db :migration/id)))

(defn- ledger [db]
  (if (ledger-installed? db)
    (into {} (d/q '[:find ?id ?cs :where [?e :migration/id ?id] [?e :migration/checksum ?cs]] db))
    {}))

(defn check-numbering! [migrations]
  (let [nums (map :number migrations)]
    (when-not (= nums (range 1 (inc (count nums))))
      (throw (ex-info "Migration numbering has a gap or is out of order" {:numbers nums})))))

(defn pending
  "Returns the migrations still to apply, after verifying ledger consistency."
  [conn migrations]
  (check-numbering! migrations)
  (let [applied (ledger (d/db conn))
        by-id (into {} (map (juxt (comp str :id) identity)) migrations)]
    (doseq [[id _] applied]
      (when-not (contains? by-id id)
        (throw (ex-info (str "Ledger entry has no migration file: " id) {:id id}))))
    (doseq [[id checksum] applied]
      (when (not= checksum (:checksum (get by-id id)))
        (throw (ex-info (str "Migration file changed after it was applied: " id) {:id id}))))
    (vec (remove #(contains? applied (str (:id %))) migrations))))

(defn migrate!
  "Applies every pending migration. Each migration and its ledger entry go in one transaction
  (the first migration installs the ledger itself, so its entry follows in a second transaction)."
  [conn]
  (let [todo (pending conn (load-migrations))]
    (doseq [m todo]
      (let [entry {:migration/id (str (:id m))
                   :migration/checksum (:checksum m)
                   :migration/applied-at (Date.)}
            tx (migration-tx-data m)]
        (if (ledger-installed? (d/db conn))
          @(d/transact conn (conj tx entry))
          (do @(d/transact conn tx)
              @(d/transact conn [entry])))
        (log/info :migration-applied {:id (str (:id m))})))
    (count todo)))

(defn verify-current!
  "Boot-time check for instances: the ledger must list every migration file."
  [conn]
  (let [todo (pending conn (load-migrations))]
    (when (seq todo)
      (throw (ex-info "Database is not migrated. Run `clojure -M:migrate` first."
                      {:pending (mapv (comp str :id) todo)})))
    true))

(defn -main [& _]
  (let [cfg (config/load-config (config/profile-from-env))
        conn (datomic/connect! (get-in cfg [:datomic :uri]))
        n (migrate! conn)]
    (log/info :migrations-done {:applied n})
    (datomic/release! conn)
    (shutdown-agents)))
