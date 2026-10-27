(ns zandy.features.site.db
  "Site slice, db role: the only I/O of the slice, a Datomic reachability probe used by the health check."
  (:require [datomic.api :as d]))

(defn reachable?
  "True when the Datomic connection answers."
  [conn]
  (try (some? (d/basis-t (d/db conn))) (catch Throwable _ false)))
