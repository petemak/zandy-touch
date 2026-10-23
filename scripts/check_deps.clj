(ns check-deps
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defn deps-edn-libs [f]
  (when (.exists (io/file f))
    (let [m (edn/read-string (slurp f))]
      (concat (keys (:deps m))
              (mapcat (comp keys :extra-deps val) (:aliases m))))))

(defn shadow-libs [f]
  (when (.exists (io/file f))
    (map first (:dependencies (edn/read-string (slurp f))))))

(let [allowed (set (edn/read-string (slurp "allowed-deps.edn")))
      found   (set (concat (deps-edn-libs "deps.edn")
                           (shadow-libs "shadow-cljs.edn")))
      extra   (sort (remove allowed found))]
  (when (seq extra)
    (binding [*out* *err*]
      (println "Libraries not in allowed-deps.edn:" (pr-str extra))
      (println "Remove them, or ask the owner to approve and add them."))
    (System/exit 2)))
