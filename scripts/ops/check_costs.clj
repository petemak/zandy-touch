;; Acceptance check D-4: every TOTAL_..._USD line in deploy/COSTS.md is at most 15 USD per month.
;; OVER_CEILING_..._USD lines name combinations that must not be chosen; they are printed as warnings.
(require '[babashka.fs :as fs])
(let [root (-> *file* fs/absolutize fs/parent fs/parent fs/parent str)
      txt (slurp (str root "/deploy/COSTS.md"))
      totals (for [[_ k v] (re-seq #"(?m)^(TOTAL_[A-Z0-9_]*USD|TOTAL_MONTHLY_USD):\s*([0-9.]+)" txt)] [k (parse-double v)])
      over (for [[_ k v] (re-seq #"(?m)^(OVER_CEILING_[A-Z0-9_]*USD):\s*([0-9.]+)" txt)] [k (parse-double v)])]
  (when (empty? totals) (println "D-4 FAIL: no TOTAL_...USD line in deploy/COSTS.md") (System/exit 1))
  (doseq [[k v] totals] (println (format "  %-28s %.2f %s" k v (if (> v 15.0) "OVER 15" "ok"))))
  (doseq [[k v] over] (println (format "  WARN %-23s %.2f (combination not to be chosen)" k v)))
  (if (some #(> (second %) 15.0) totals)
    (do (println "D-4 FAIL: a total is above the 15 USD ceiling") (System/exit 1))
    (println "D-4 PASS: all totals at most 15 USD per month")))
