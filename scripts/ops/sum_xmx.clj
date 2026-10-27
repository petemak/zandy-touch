#!/usr/bin/env bb
;; Acceptance check D-2: sums every -Xmx in deploy/compose.yaml and compares with architecture.md D3.
;;   bb scripts/ops/sum_xmx.clj [--total-ram-mb 8192]
;;   bb scripts/ops/sum_xmx.clj --layout small      4 GB staging-only host (compose.yaml + compose.small.yaml)
;; Also lists the -Xmx defaults found in the Dockerfiles and fails when a service in compose.yaml has
;; no -Xmx, or when a Dockerfile default differs from the production value in D3.
(require '[babashka.fs :as fs]
         '[clj-yaml.core :as yaml]
         '[clojure.string :as str])

(def root (-> *file* fs/absolutize fs/parent fs/parent fs/parent str))

;; architecture.md D3, MB. Any measured change must be recorded here AND in deploy/DECISIONS.md.
(def opts (into {} (map vec (partition 2 *command-line-args*))))
(def small? (= "small" (get opts "--layout")))
(def d3 (if small?
          ;; 4 GB staging-only host: the staging values of D3; the transactor is sized below D3 (512m, see
          ;; compose.small.yaml and DECISIONS.md) and must be confirmed by measurement on the real host.
          {:transactor 512 :backend-staging 512 :ssr-staging 192}
          {:transactor 1024 :backend 768 :ssr 256 :backend-staging 512 :ssr-staging 192}))
(def active (if small? #{:transactor :backend-staging :ssr-staging :caddy} (set (keys (:services (yaml/parse-string (slurp (str root "/deploy/compose.yaml"))))))))
(def ram-mb (parse-long (get opts "--total-ram-mb" (if small? "4096" "8192"))))
(def reserve-mb (if small? 1200 1500))

(defn deep-merge [a b]
  (cond (and (map? a) (map? b)) (merge-with deep-merge a b)
        (some? b) b
        :else a))

(defn xmx-mb [s]
  (when-let [[_ n u] (re-find #"-Xmx(\d+)([mMgG])" (str s))]
    (* (parse-long n) (if (#{"g" "G"} u) 1024 1))))

(def compose
  (let [base (yaml/parse-string (slurp (str root "/deploy/compose.yaml")))]
    (if small?
      (deep-merge base (yaml/parse-string (slurp (str root "/deploy/compose.small.yaml"))))
      base)))

(def found
  (into {}
        (for [[svc cfg] (:services compose)
              :when (active svc)
              :let [mb (or (xmx-mb (get-in cfg [:environment :JAVA_OPTS]))
                           (xmx-mb (str/join " " (:command cfg))))]
              :when (or mb (contains? d3 svc))]
          [svc mb])))

(def problems (atom []))
(defn problem! [& xs] (swap! problems conj (apply str xs)))

(println "D-2 memory plan: -Xmx per service (compose.yaml) against architecture.md D3")
(doseq [[svc want] d3]
  (let [got (get found svc)]
    (printf "  %-16s -Xmx%-5s D3 %-5s %s%n" (name svc) (or got "none") want
            (cond (nil? got) "MISSING" (= got want) "ok" :else "DIFFERS"))
    (cond (nil? got) (problem! (name svc) " has no -Xmx in compose.yaml")
          (not= got want) (problem! (name svc) " is " got "m, D3 says " want "m (record a measured change in sum_xmx.clj and DECISIONS.md)"))))

(let [heap-total (reduce + (vals (select-keys found (keys d3))))
      limits (for [[svc cfg] (:services compose) :when (and (:mem_limit cfg) (active svc))]
               [svc (let [[_ n u] (re-find #"(\d+)([mg])" (:mem_limit cfg))] (* (parse-long n) (if (= u "g") 1024 1)))])
      limit-total (reduce + (map second (remove #(= :datomic-tools (first %)) limits)))]
  (println (format "  sum of heaps          %d MB" heap-total))
  (println (format "  sum of mem_limit      %d MB (%s)" limit-total (if small? "staging-only host: transactor, backend-staging, ssr-staging, caddy" "all long-running containers, both environments")))
  (println (format "  host RAM              %d MB; leave >= %d MB for OS, page cache and one-shot tasks" ram-mb reserve-mb))
  (when (> limit-total (- ram-mb reserve-mb))
    (problem! "sum of mem_limit " limit-total " MB leaves less than " reserve-mb " MB on a " ram-mb " MB host"))
  (doseq [[svc mb] limits
          :let [heap (get found svc)]
          :when (and heap (< mb (+ heap 192)))]
    (problem! (name svc) " mem_limit " mb "m is not above its heap " heap "m by 192m for metaspace and native memory")))

(println "Dockerfile defaults (informational; compose overrides them):")
(doseq [f ["Dockerfile.backend" "Dockerfile.ssr" "deploy/transactor/Dockerfile"]
        :let [txt (slurp (str root "/" f))
              mb (xmx-mb txt)]]
  (printf "  %-32s -Xmx%s%n" f (or mb "none"))
  (when (and (not small?) (= f "Dockerfile.backend") (not= mb (:backend d3))) (problem! f " default differs from D3"))
  (when (and (not small?) (= f "Dockerfile.ssr") (not= mb (:ssr d3))) (problem! f " default differs from D3"))
  (when (and (not small?) (= f "deploy/transactor/Dockerfile") (not= mb (:transactor d3))) (problem! f " default differs from D3")))

(if (seq @problems)
  (do (println "D-2 FAIL") (doseq [p @problems] (println " -" p)) (System/exit 1))
  (println "D-2 PASS"))
