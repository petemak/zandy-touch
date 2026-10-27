#!/usr/bin/env bb
;; Reads JSON on stdin and prints the value at a path given as arguments.
;;   echo '{"items":[{"slug":"a"}]}' | bb json.clj items 0 slug     -> a
;; Strings print bare; other values print as JSON. A missing path prints nothing (exit 0).
(require '[cheshire.core :as json])
(let [data (try (json/parse-string (slurp *in*)) (catch Exception _ nil))
      path (mapv #(if (re-matches #"\d+" %) (parse-long %) %) *command-line-args*)
      v (get-in data path)]
  (when (some? v)
    (println (if (string? v) v (json/generate-string v)))))
