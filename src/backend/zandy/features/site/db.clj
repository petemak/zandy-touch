(ns zandy.features.site.db
  (:require [datomic.api :as d]))

(defn reachable?
  "True when the Datomic connection answers."
  [conn]
  (try (some? (d/basis-t (d/db conn))) (catch Throwable _ false)))
