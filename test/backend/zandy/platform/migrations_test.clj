(ns zandy.platform.migrations-test
  "Fitness check N4c: versioned migrations."
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [zandy.platform.datomic :as datomic]
            [zandy.platform.migrations :as m]))

(defn- fresh [] (datomic/connect! (str "datomic:mem://mig-" (random-uuid))))

(deftest migrations-apply-once-and-are-recorded
  (let [conn (fresh)
        files (m/load-migrations)]
    (is (= (count files) (m/migrate! conn)))
    (is (= (set (map (comp str :id) files))
           (set (map first (d/q '[:find ?id :where [_ :migration/id ?id]] (d/db conn))))))
    (testing "re-running is a no-op"
      (is (= 0 (m/migrate! conn))))
    (testing "an instance checks the ledger at boot"
      (is (true? (m/verify-current! conn))))
    (testing "a changed checksum aborts"
      @(d/transact conn [{:migration/id ":0002-catalog-schema" :migration/checksum "tampered"}])
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"changed after it was applied" (m/migrate! conn))))))

(deftest ledger-entry-without-a-file-aborts
  (let [conn (fresh)]
    (m/migrate! conn)
    @(d/transact conn [{:migration/id ":0099-ghost" :migration/checksum "x"}])
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no migration file" (m/migrate! conn)))))

(deftest numbering-gaps-abort
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"gap" (m/check-numbering! [{:number 1} {:number 3}])))
  (is (nil? (m/check-numbering! [{:number 1} {:number 2}]))))

(deftest a-stale-ledger-refuses-to-start
  (let [conn (fresh)]
    (is (thrown? Throwable (m/verify-current! conn)) "nothing applied")
    (m/migrate! conn)
    (is (true? (m/verify-current! conn)))))

(deftest schema-lives-only-in-migrations
  (is (= 6 (count (d/q '[:find ?e :where [?e :category/slug]] (let [c (fresh)] (m/migrate! c) (d/db c)))))))
