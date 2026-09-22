(ns seon.cluster.publication-report-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [seon.db :as db]
            [seon.fn :as fn]
            [seon.program :as program]
            [seon.test-support :as support]))

(deftest writer-reports-and-their-composed-datoms-name-the-same-changes
  (support/with-database
    (fn [connection]
      (let [digested (fn [row] (assoc row :seon.program/definition-digest
                                      (program/definition-digest row)))
            a 'publication.report/a
            b 'publication.report/b
            created (support/transacted! connection [(digested {:seon.ns/name a})
                                                     (digested {:seon.ns/name b})])
            before (:db-after created)
            changed (support/transacted! connection [(digested {:seon.ns/name a :seon.ns/doc "changed"})])
            removed (support/transacted! connection [[:db/retractEntity [:seon.ns/name b]]])
            reports [changed removed]
            after (:db-after removed)
            datoms (into [] (mapcat :tx-data) reports)
            expected #{[:seon.ns/name a] [:seon.ns/name b]}]
        (is (= #{a b} (set (db/q '[:find [?name ...] :in $ [?name ...]
                                   :where [_ :seon.ns/name ?name]] before [a b]))))
        (doseq [report reports]
          (is (= (fn/report-identities report)
                 (fn/report-identities (:db-before report) (:db-after report) (:tx-data report)))))
        (is (= expected (into #{} (mapcat fn/report-identities) reports)))
        (is (= expected (fn/report-identities before after datoms)))
        (is (= expected
               (fn/report-identities before after
                 (vec (d/datoms (d/since (d/history after) (db/basis-t before)) :eavt)))))
        (is (empty? (db/pull after '[*] [:seon.ns/name b])))))))
