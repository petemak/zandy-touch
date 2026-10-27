#!/usr/bin/env bb
;; Fitness checks B2, B3 and B4 for the backend.
;;   bb scripts/check/backend_deps.clj slices   slice A may only require zandy.features.B.api
;;   bb scripts/check/backend_deps.clj graph    namespace edges match the allowed list (architecture 2.2) and are acyclic
;;   bb scripts/check/backend_deps.clj attrs    a Datomic attribute namespace is only used by its owning component
;; Run from the project root. Exit code 0 means pass.
#_{:clj-kondo/ignore [:duplicate-require]}
(require '[clojure.edn :as edn] '[clojure.java.io :as io] '[clojure.string :as str])

(def src-root
  "Directory scanned for backend namespaces."
  "src/backend")

(defn clj-files
  "All .clj files under src-root, as java.io.File objects."
  []
  (->> (file-seq (io/file src-root)) (filter #(str/ends-with? (.getName %) ".clj"))))

(defn ns-info
  "Reads the ns form of file `f` without evaluating it. Returns {:ns \"name\" :requires [\"ns\" ...] :file path}; aliases and refer options are ignored."
  [f]
  (let [form (edn/read-string {:default (fn [_ v] v)} (slurp f))
        requires (->> (drop 2 form)
                      (filter #(and (seq? %) (= :require (first %))))
                      (mapcat rest)
                      (map #(if (vector? %) (first %) %))
                      (filter symbol?)
                      (map str))]
    {:ns (str (second form)) :requires requires :file (.getPath f)}))

(def infos
  "ns-info of every backend namespace (computed once)."
  (mapv ns-info (clj-files)))

(defn component
  "zandy.platform.http -> platform.http, zandy.features.orders.routes -> features.orders, zandy.seed.core -> seed"
  [ns-name]
  (let [parts (str/split ns-name #"\.")]
    (case (second parts)
      "platform" (str "platform." (nth parts 2))
      "features" (str "features." (nth parts 2))
      "seed" "seed"
      nil)))

(defn feature-api?
  "True when the namespace name ends in \".api\", the only namespace of a slice that others may require."
  [ns-name] (str/ends-with? ns-name ".api"))

;; ---- slices ------------------------------------------------------------------------------------
(defn check-slices
  "Fitness check B2. Returns problem strings: a feature namespace requiring a namespace of another slice other than its .api, or a layer folder (controllers, services, models) under src-root."
  []
  (concat
   (for [{:keys [ns requires]} infos
         :let [a (component ns)]
         :when (and a (str/starts-with? a "features."))
         r requires
         :let [b (component r)]
         :when (and b (str/starts-with? b "features.") (not= a b)
                    (not (feature-api? r)))]
     (str ns " requires " r " (only <slice>.api may be required from another slice)"))
   (for [d (file-seq (io/file src-root))
         :when (and (.isDirectory d) (#{"controllers" "services" "models"} (.getName d)))]
     (str "layer folder not allowed: " (.getPath d)))))

;; ---- graph -------------------------------------------------------------------------------------
(def shared
  "Platform components any component may require (architecture 2.2: errors, log, datomic, util, validation, json)."
  #{"platform.errors" "platform.log" "platform.datomic" "platform.util" "platform.validation" "platform.json"})

(def allowed
  "The allowed dependency edges (architecture 2.2): component to the set of components it may require, or :all (platform.system). Shared components are allowed everywhere; the comments mark the two edges added beyond the architecture table."
  {"platform.config" #{}
   "platform.errors" #{}
   "platform.json" #{}
   "platform.util" #{}
   "platform.validation" #{"platform.errors" "platform.json"}
   "platform.log" #{"platform.json"}
   "platform.datomic" #{"platform.config" "platform.log" "platform.errors"}
   "platform.migrations" #{"platform.datomic" "platform.config" "platform.log"}
   "platform.media" #{"platform.config" "platform.log"}
   "platform.mailer" #{"platform.config" "platform.log"}
   "platform.rate-limit" #{"platform.datomic" "platform.errors"}
   "platform.idempotency" #{"platform.datomic" "platform.errors"}
   "platform.auth-interceptors" #{"platform.errors" "features.accounts"}
   "platform.http" #{"platform.config" "platform.log" "platform.errors" "platform.json" "platform.rate-limit"
                     "platform.idempotency" "platform.auth-interceptors"
                     "features.site" "features.policies" "features.catalog" "features.orders" "features.accounts"
                     "features.engagement" "features.photos"}
   "platform.system" :all
   "features.site" #{}
   "features.policies" #{}
   "features.notifications" #{"platform.mailer"}
   "features.catalog" #{"platform.media"}
   "features.photos" #{"features.catalog" "platform.media"}
   ;; accounts also uses platform.rate-limit for the login lockout (deviation recorded in the report)
   "features.accounts" #{"features.notifications" "platform.rate-limit" "platform.config" "platform.datomic"}
   ;; orders also asks features.site for the delivery city list (deviation recorded in the report)
   "features.orders" #{"features.catalog" "features.policies" "features.notifications" "features.accounts" "features.site"}
   "features.engagement" #{"features.notifications"}
   "seed" #{"features.catalog" "platform.config" "platform.datomic" "platform.log" "platform.media" "platform.migrations"}})

(defn edges
  "Distinct [from-component to-component] pairs found in the requires of the backend namespaces (a component depending on itself is skipped)."
  []
  (distinct
   (for [{:keys [ns requires]} infos
         :let [a (component ns)]
         :when a
         r requires
         :let [b (component r)]
         :when (and b (not= a b))]
     [a b])))

(defn find-cycle
  "Depth-first search for a cycle in the edge list `es`. Returns a vector of components that starts and ends with the same one, or nil when the graph is acyclic."
  [es]
  (let [g (reduce (fn [m [a b]] (update m a (fnil conj #{}) b)) {} es)]
    (letfn [(visit [node path done]
              (cond
                (some #{node} path) [(conj path node) done]
                (done node) [nil done]
                :else
                (loop [ns (g node) done done]
                  (if-let [n (first ns)]
                    (let [[cycle done'] (visit n (conj path node) done)]
                      (if cycle [cycle done'] (recur (rest ns) done')))
                    [nil (conj done node)]))))]
      (loop [nodes (keys g) done #{}]
        (when-let [n (first nodes)]
          (let [[cycle done'] (visit n [] done)]
            (or cycle (recur (rest nodes) done'))))))))

(defn check-graph
  "Fitness check B3. Returns problem strings for edges missing from `allowed` and for a dependency cycle."
  []
  (let [es (edges)]
    (concat
     (for [[a b] es
           :let [ok (get allowed a)]
           :when (not (or (= ok :all) (contains? ok b) (contains? shared b)))]
       (str "edge not allowed: " a " -> " b))
     (when-let [c (find-cycle es)] [(str "cycle: " (str/join " -> " c))]))))

;; ---- attributes ---------------------------------------------------------------------------------
(def owners
  "Attribute namespace -> owning component. Slices are written as keywords (:catalog is features.catalog)."
  {"category" :catalog "product" :catalog "product-image" :catalog "photo" :catalog "stock-movement" :catalog
   "order" :orders "order-line" :orders "order-event" :orders "acceptance" :orders
   "policy" :policies
   "user" :accounts "session" :accounts "reset-token" :accounts
   "subscriber" :engagement "contact-message" :engagement
   "outbox" :notifications
   "ratelimit" :platform.rate-limit "idempotency" :platform.idempotency "migration" :platform.migrations})

(defn owner-component
  "Component name for an owners value: a keyword like :catalog means \"features.catalog\"; names starting with \"platform.\" are used as written."
  [k]
  (if (str/starts-with? (name k) "platform.") (name k) (str "features." (name k))))

(defn file-component
  "Component that owns the source file at `path` (for example src/backend/zandy/platform/rate_limit.clj is \"platform.rate-limit\")."
  [path]
  (let [ns-name (-> path (str/replace (str src-root "/") "") (str/replace #"\.clj$" "") (str/replace "/" ".") (str/replace "_" "-"))]
    (component ns-name)))

(defn check-attrs
  "Fitness check B4. Returns problem strings for every source file that mentions a Datomic attribute namespace (such as :order/ or :user/) owned by another component. The scan is textual, so it also sees keywords in comments and docstrings; documentation must not name another component's attributes."
  []
  (for [f (clj-files)
        :let [text (slurp f) comp (file-component (.getPath f))]
        [_ ns-part] (re-seq #"(?<![\w-:]):([a-z][a-z-]*)/[a-z]" text)
        :let [owner (some-> (get owners ns-part) owner-component)]
        :when (and owner (not= owner comp)
                   ;; migrations load the policy documents (architecture 3.2: policies are written by migrations only)
                   (not (and (= ns-part "policy") (= comp "platform.migrations"))))
        :let [hit ns-part]]
    (str (.getPath f) ": uses attribute namespace :" hit "/ owned by " owner)))

;; Entry point: the mode argument selects the check; any problem makes the script exit with code 1.
(let [mode (first *command-line-args*)
      problems (distinct (case mode
                           "slices" (check-slices)
                           "graph" (check-graph)
                           "attrs" (check-attrs)
                           (do (println "usage: backend_deps.clj slices|graph|attrs") (System/exit 2))))]
  (if (seq problems)
    (do (doseq [p problems] (println "FAIL" p)) (System/exit 1))
    (println (str mode " ok (" (count infos) " namespaces)"))))
