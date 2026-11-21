(ns seon.store-query-test
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [datahike.reads :as reads]
            [seon.rules :as rules]
            [seon.schema :as schema]
            [seon.schema.datahike :as sdh]
            [seon.schema.index :as six]
            [seon.store.query :as sq]))

(defn- fresh-mem-conn []
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history? true
             :writer {:backend :sync}}
        _ (d/create-database cfg)
        conn (d/connect cfg)
        decls (sdh/declarations (:registry @schema/base-registry))]
    (d/transact conn {:tx-data decls})
    conn))

(deftest count-range-and-slice-datoms-with-reads-and-as-of-test
  (let [conn (fresh-mem-conn)]
    (dotimes [i 12]
      (d/transact conn {:tx-data [{:seon.task/id (keyword "seon.demo" (str "t" (format "%02d" i)))
                                   :seon.task/title (str "Task " i)}]}))
    (let [db @conn
          [[cnt prev] rset] (reads/with-reads
                              (fn []
                                [(sq/count-range db [nil :seon.task/id nil])
                                 (sq/preview-slice db [nil :seon.task/id nil] {:limit 3 :offset 0 :reverse? true})]))
          ridx (reads/reads-index rset)]
      (is (= 12 cnt))
      (is (= 12 (:total prev)))
      (is (= 3 (count (:datoms prev))))
      (is (:more? prev))
      (is (contains? ridx [:a :seon.task/id]))
      (let [fwd (mapv :v (sq/slice-datoms db [nil :seon.task/id nil] {:limit 3 :offset 2 :reverse? false}))
            rev (mapv :v (sq/slice-datoms db [nil :seon.task/id nil] {:limit 3 :offset 0 :reverse? true}))]
        (is (= 3 (count fwd)))
        (is (= 3 (count rev)))
        (is (not= fwd rev)))
      ;; AsOfDB fallback works cleanly too
      (let [asof (d/as-of db (:max-tx db))]
        (is (= 12 (sq/count-range asof [nil :seon.task/id nil])))
        (is (= 2 (count (sq/slice-datoms asof [nil :seon.task/id nil] {:limit 2 :reverse? true}))))))))

(deftest rules-db-count-db-slice-and-facet-clauses-test
  (let [conn (fresh-mem-conn)]
    (dotimes [i 6]
      (d/transact conn {:tx-data [{:seon.task/id (keyword "seon.demo" (str "r" i))
                                   :seon.task/title (str "Rule Task " i)}]}))
    (let [db @conn
          test-rules [{:seon.rule/id :rule.test/count-tasks
                       :seon.rule/stratum 10
                       :seon.rule/enabled? true
                       :seon.rule/when [[:db-count :branch [nil :seon.task/id nil] '?n]]
                       :seon.rule/assert [[:summary :task-count '?n]]}
                      {:seon.rule/id :rule.test/slice-top2
                       :seon.rule/stratum 20
                       :seon.rule/enabled? true
                       :seon.rule/when [[:db-slice :branch ['?e :seon.task/id '?tid] {:limit 2 :reverse? true}]
                                        [:facet '?e '?sch]]
                       :seon.rule/assert [['?tid :previewed-as '?sch]]}]
          res (rules/step-rules nil (rules/empty-wm) {:branch db :main db} test-rules nil)]
      (is (= 6 (rules/wm-scalar (:wm res) :summary :task-count)))
      (let [previewed (rules/wm-entities-with (:wm res) :previewed-as)]
        (is (= 2 (count previewed)))
        (is (every? #(= :seon.task/entity (rules/wm-scalar (:wm res) % :previewed-as)) previewed))))))

(deftest core-graph-schemas-error-tasks-proofs-references-test
  (let [ix (schema/entity-index (d/db (fresh-mem-conn)))]
    (testing "entity-index tracks new ref targets and reaching edges when schemas are present"
      (let [forms (assoc (:forms ix)
                         :seon.agent/tasks [:set {:seon.db/to [:seon.task/entity]} :seon.store/ref]
                         :seon.task/proofs [:set {:seon.db/to [:seon.code.var/entity]} :seon.store/ref]
                         :seon.code.doc/references [:set {:seon.db/to [:seon.code.var/entity :seon.code.ns/entity :seon.schema/entity]} :seon.store/ref])
            ix2 (six/build-entity-index forms)]
        (is (some #{:seon.agent/tasks} (six/reaching ix2 :seon.task/entity)))
        (is (some #{:seon.task/proofs} (six/reaching ix2 :seon.code.var/entity)))
        (is (some #{:seon.code.doc/references} (six/reaching ix2 :seon.schema/entity)))))))
