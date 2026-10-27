;; Run inside the backend image:  entrypoint.sh clj /tmp/count_db.clj   (env COUNT_URI = Datomic URI)
;; Prints "orders=N products=N photos=N users=N" and, with COUNT_DELETE=1, deletes the database afterwards.
(require '[datomic.api :as d])
(let [uri (System/getenv "COUNT_URI")
      conn (d/connect uri)
      db (d/db conn)
      n (fn [a] (count (d/q '[:find ?e :in $ ?a :where [?e ?a]] db a)))]
  (println (format "orders=%d products=%d photos=%d users=%d"
                   (n :order/id) (n :product/id) (n :photo/id) (n :user/id)))
  (flush)
  (when (= "1" (System/getenv "COUNT_DELETE"))
    (d/release conn)
    (d/delete-database uri))
  (System/exit 0))
