#!/usr/bin/env bb
;; Frontend fitness checks (architecture.md section 7, B3, B9b, B19).
;;   bb scripts/check/frontend_deps.clj graph      namespace edges and cycles, shared-code purity
;;   bb scripts/check/frontend_deps.clj api-calls  every /api path in frontend code is in api-contract.md
;;   bb scripts/check/frontend_deps.clj versions   replicant and reitit-core versions agree
;;   bb scripts/check/frontend_deps.clj all
(require '[babashka.fs :as fs]
         '[clojure.edn :as edn]
         '[clojure.set :as set]
         '[clojure.string :as str])

(def root
  "Project root directory (three levels above this file)."
  (-> *file* fs/absolutize fs/parent fs/parent fs/parent str))

(defn source-files
  "Every Clojure source file the checks look at: ssr/src, src/shared,
  src/frontend and ssr/dev. Returns maps {:file absolute-path :rel
  path-from-root :dir source-root}."
  []
  (for [dir ["ssr/src" "src/shared" "src/frontend" "ssr/dev"]
        f (fs/glob (str root "/" dir) "**.{clj,cljc,cljs}")]
    {:file (str f) :rel (str (fs/relativize root f)) :dir dir}))

(defn read-ns-form
  "The first (ns ...) form of `file`, read with both :clj and :cljs reader
  features, or nil. Unknown tagged literals are kept as plain data."
  [file]
  (with-open [r (java.io.PushbackReader. (clojure.java.io/reader file))]
    (binding [*default-data-reader-fn* (fn [_ v] v)]
      (loop []
        (let [form (read {:read-cond :allow :eof ::eof :features #{:clj :cljs}} r)]
          (cond (= form ::eof) nil
                (and (seq? form) (= 'ns (first form))) form
                :else (recur)))))))

(defn requires
  "Set of library symbols required by the ns form `ns-form` (:require clauses
  only; vectors and bare symbols)."
  [ns-form]
  (let [clauses (filter #(and (seq? %) (= :require (first %))) ns-form)]
    (set (for [c clauses, spec (rest c)
               :let [lib (cond (symbol? spec) spec (vector? spec) (first spec))]
               :when (symbol? lib)]
           lib))))

(def graph
  "Map of namespace symbol -> {:requires #{...} :dir source-root} for all
  source files."
  (into {}
        (keep (fn [{:keys [file dir]}]
                (when-let [form (read-ns-form file)]
                  [(second form) {:requires (requires form) :dir dir}])))
        (source-files)))

;; ---------------------------------------------------------------------------
;; Allowed edges. Each entry: namespace (or prefix ending in ".*") -> allowed
;; project namespaces (prefix match with ".*"). architecture.md 2.3 and 2.4,
;; plus the deviations reported to the architect: zandy.shared.pages (the
;; page-data mapping the architecture names) is used by ssr and the browser;
;; frontend.api uses shared.routes for query encoding; frontend.admin.state
;; extends the action handlers of frontend.state.

;; Why a closed list: the architecture is enforced by this file. An unexpected
;; require (say, shared code reaching into the browser namespaces) is a build
;; failure, not a review comment.
(def allowed
  "The allowed-edge list: namespace (or prefix ending in \".*\") -> project
  namespaces it may require. It encodes architecture.md sections 2.3 and 2.4
  and is the single place to change when the design changes. A namespace with
  no entry fails the check, so new namespaces must be added deliberately."
  {"zandy.shared.format" #{}
   "zandy.shared.cart" #{}
   "zandy.shared.json" #{}
   "zandy.shared.routes" #{}
   "zandy.shared.seo" #{"zandy.shared.format" "zandy.shared.json" "zandy.shared.routes"}
   "zandy.shared.ui.core" #{"zandy.shared.format"}
   "zandy.shared.ui.layout" #{"zandy.shared.cart" "zandy.shared.format" "zandy.shared.routes" "zandy.shared.ui.*"}
   "zandy.shared.ui.product" #{"zandy.shared.format" "zandy.shared.routes" "zandy.shared.ui.*"}
   "zandy.shared.ui.order" #{"zandy.shared.format" "zandy.shared.routes" "zandy.shared.ui.*"}
   "zandy.shared.ui.policy" #{"zandy.shared.format" "zandy.shared.ui.*"}
   "zandy.shared.pages.*" #{"zandy.shared.format" "zandy.shared.routes" "zandy.shared.cart" "zandy.shared.ui.*"}
   "zandy.shared.pages" #{"zandy.shared.pages.*" "zandy.shared.routes" "zandy.shared.ui.*"}
   "zandy.shared.document" #{"zandy.shared.json" "zandy.shared.pages" "zandy.shared.seo"}
   "zandy.frontend.api" #{"zandy.shared.routes"}
   "zandy.frontend.cart-store" #{"zandy.shared.cart"}
   "zandy.frontend.router" #{"zandy.shared.routes"}
   "zandy.frontend.state" #{"zandy.shared.*"}
   "zandy.frontend.effects" #{"zandy.frontend.api" "zandy.frontend.cart-store" "zandy.frontend.router" "zandy.shared.json" "zandy.shared.seo"}
   "zandy.frontend.admin.help-data" #{}
   "zandy.frontend.admin.help" #{"zandy.frontend.admin.help-data" "zandy.shared.*"}
   "zandy.frontend.admin.state" #{"zandy.frontend.admin.help" "zandy.frontend.state" "zandy.shared.*"}
   "zandy.frontend.admin.widgets" #{"zandy.shared.*"}
   "zandy.frontend.admin.core" #{"zandy.frontend.admin.help" "zandy.frontend.admin.state" "zandy.frontend.admin.widgets" "zandy.shared.*"}
   "zandy.frontend.main" #{"zandy.frontend.admin.core" "zandy.frontend.cart-store" "zandy.frontend.effects" "zandy.frontend.router"
                           "zandy.frontend.state" "zandy.shared.pages"}
   "zandy.frontend.scenes.*" #{"zandy.frontend.admin.core" "zandy.frontend.admin.help" "zandy.frontend.admin.widgets" "zandy.frontend.scenes.*" "zandy.shared.*"}
   "zandy.ssr.config" #{}
   "zandy.ssr.log" #{}
   "zandy.ssr.api-client" #{"zandy.shared.routes"}
   "zandy.ssr.pages" #{"zandy.ssr.api-client" "zandy.shared.pages" "zandy.shared.routes"}
   "zandy.ssr.render" #{"zandy.shared.document" "zandy.shared.pages"}
   "zandy.ssr.seo-routes" #{"zandy.ssr.api-client" "zandy.shared.routes"}
   "zandy.ssr.static" #{}
   "zandy.ssr.launch-gate" #{"zandy.ssr.api-client" "zandy.ssr.log"}
   "zandy.ssr.http" #{"zandy.ssr.*" "zandy.shared.pages" "zandy.shared.routes"}
   "zandy.ssr.system" #{"zandy.ssr.*"}
   "zandy.ssr.stub-api" #{}
   "zandy.ssr.dev-proxy" #{}})

(defn- matches? [pattern ns-sym]
  (let [n (str ns-sym)]
    (if (str/ends-with? pattern ".*")
      (str/starts-with? n (subs pattern 0 (dec (count pattern))))
      (= pattern n))))

(defn- rule-for [ns-sym]
  ;; exact entry wins, else the longest prefix entry
  (or (get allowed (str ns-sym))
      (->> allowed
           (filter (fn [[k _]] (and (str/ends-with? k ".*") (matches? k ns-sym))))
           (sort-by (comp count key) >)
           first second)))

(def forbidden-in-shared
  "Namespace prefixes that code in src/shared must never require
  (architecture.md section 2.4)."
  ["zandy.frontend" "zandy.ssr" "zandy.backend" "datomic" "io.pedestal"])

(defn- project-ns?
  "True for project namespaces (prefix \"zandy.\")."
  [s] (str/starts-with? (str s) "zandy."))

(defn find-cycle
  "A dependency cycle in `edges` (map ns -> set of ns), as a vector of
  namespaces, or nil."
  [edges]
  (let [visiting (atom #{}) done (atom #{})]
    (letfn [(visit [n path]
              (cond (@done n) nil
                    (@visiting n) (conj path n)
                    :else (do (swap! visiting conj n)
                              (let [r (some #(visit % (conj path n)) (get edges n))]
                                (swap! visiting disj n) (swap! done conj n) r))))]
      (some #(visit % []) (keys edges)))))

(defn check-graph
  "Check B3: every project require is an allowed edge, shared code requires
  nothing forbidden and does not use js/, and there is no cycle. Returns a
  vector of error strings (empty when the check passes)."
  []
  (let [errors (atom [])
        err! #(swap! errors conj %)
        edges (into {} (map (fn [[n {:keys [requires]}]] [n (set (filter (fn [r] (and (project-ns? r) (contains? graph r))) requires))])) graph)]
    (doseq [[n {:keys [requires dir]}] graph]
      (let [rule (rule-for n)]
        (when (nil? rule) (err! (str n " has no entry in the allowed-edge list")))
        (doseq [r requires
                :when (and rule (project-ns? r) (not (some #(matches? % r) rule)))]
          (err! (str n " requires " r " (not an allowed edge)"))))
      (when (= dir "src/shared")
        (doseq [r requires, f forbidden-in-shared
                :when (str/starts-with? (str r) f)]
          (err! (str n " (shared code) must not require " r)))))
    (doseq [{:keys [file rel dir]} (source-files)
            :when (= dir "src/shared")
            [i line] (map-indexed vector (str/split-lines (slurp file)))
            :when (re-find #"js/" line)]
      (err! (str rel ":" (inc i) " shared code must not use js/")))
    (when-let [c (find-cycle edges)] (err! (str "dependency cycle: " (str/join " -> " c))))
    @errors))

;; ---------------------------------------------------------------------------
;; API calls against the contract

(defn contract-routes
  "Endpoints listed in api-contract.md as `### METHOD /path` headings:
  [{:method :path}]."
  []
  (->> (str/split-lines (slurp (str root "/api-contract.md")))
       (keep #(re-matches #"### (GET|POST|PUT|PATCH|DELETE) (/\S+)" %))
       (map (fn [[_ m p]] {:method m :path p}))))

(defn- route-regex [path]
  (re-pattern (str "^" (str/replace (str/replace path #"\{[^}]+\}" "[^/]+") "/" "/") "$")))

(defn literal-in-contract?
  "True when the string `literal` from the source (a full path, or the start of
  a path built with `str`) matches an endpoint path of `routes`, with {param}
  segments matching any segment."
  [literal routes]
  (let [paths (map :path routes)]
    (or (some #(re-matches (route-regex %) literal) paths)
        ;; a prefix ending in "/" is the start of a path built with (str ...)
        (and (str/ends-with? literal "/")
             (some #(str/starts-with? % literal) paths))
        ;; a prefix without a trailing slash followed by a computed tail
        (some #(str/starts-with? % literal) paths))))

(defn check-api-calls
  "Check B9b: every \"/api/...\" string literal in shared, browser and ssr code
  is an endpoint of api-contract.md, so no endpoint is invented. Returns a
  vector of error strings."
  []
  (let [routes (contract-routes)
        errors (atom [])]
    (doseq [{:keys [file rel dir]} (source-files)
            :when (#{"src/shared" "src/frontend" "ssr/src"} dir)
            [i line] (map-indexed vector (str/split-lines (slurp file)))
            [_ lit] (re-seq #"\"(/api/[^\"?]*)" line)
            :when (not (literal-in-contract? lit routes))]
      (swap! errors conj (str rel ":" (inc i) " " lit " is not an endpoint of api-contract.md")))
    @errors))

;; ---------------------------------------------------------------------------
;; Shared dependency versions

(defn- shadow-version [lib]
  (let [deps (:dependencies (edn/read-string (slurp (str root "/shadow-cljs.edn"))))]
    (some (fn [[l v]] (when (= l lib) v)) deps)))

(defn- ssr-version [lib]
  (get-in (edn/read-string (slurp (str root "/ssr/deps.edn"))) [:deps lib :mvn/version]))

(defn check-versions
  "Check B19: replicant and reitit-core have the same version in ssr/deps.edn
  and shadow-cljs.edn (the browser and the server must render with the same
  library). Returns a vector of error strings."
  []
  (vec (for [[lib] [['no.cjohansen/replicant] ['metosin/reitit-core]]
             :let [a (ssr-version lib) b (shadow-version lib)]
             :when (or (nil? a) (not= a b))]
         (str lib ": ssr/deps.edn has " a ", shadow-cljs.edn has " b))))

;; ---------------------------------------------------------------------------

(defn run
  "Runs the check function `f`, prints PASS or FAIL with the errors, and
  returns 0 or 1 for the exit code."
  [name f]
  (let [errors (f)]
    (if (seq errors)
      (do (println (str "FAIL " name)) (run! #(println "  " %) errors) 1)
      (do (println (str "PASS " name)) 0))))

(let [cmd (or (first *command-line-args*) "all")
      results (case cmd
                "graph" [(run "graph" check-graph)]
                "api-calls" [(run "api-calls" check-api-calls)]
                "versions" [(run "versions" check-versions)]
                "all" [(run "graph" check-graph) (run "api-calls" check-api-calls) (run "versions" check-versions)]
                (do (println "usage: graph | api-calls | versions | all") [1]))]
  (System/exit (if (every? zero? results) 0 1)))
