(ns seon.cluster.adoption-record-test
  "An adoption's rows and its record are one transaction (`seon.fn/index!`)."
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.fn]
            [seon.schema :as schema]
            [seon.test-support :as support]))

(deftest adopted-rows-and-their-record-land-in-one-transaction-or-not-at-all
  (let [connection (:seon.db/connection (support/execution-handle nil))
        function-symbol (symbol "seon.cluster.adoption-record-test" "adopted-value")
        row (support/program-fn-row (db/db connection) function-symbol "(defn adopted-value [] 1)")
        _ (support/transacted! connection [row])
        published (db/db connection)
        _ (support/transacted! connection [[:db/retractEntity [:seon.fn/sym function-symbol]]])
        adopt! (fn [tx-data]
                 (let [previous (db/db connection)
                       projection (db/carried-projection previous)]
                   (schema/call-with-projection
                    projection
                    #(try (seon.fn/index! {:seon.db/connection connection
                                           :seon.schema/projection projection
                                           :seon.source/database published
                                           :seon.source/previous-database previous
                                           :seon.reconcile/adopt-identities #{[:seon.fn/sym function-symbol]}
                                           :seon.db/tx-data tx-data})
                          (catch clojure.lang.ExceptionInfo refused refused)))))
        adopted-tx #(:tx (first (db/datoms (db/db connection) :aevt :seon.fn/sym
                                           (:db/id (db/pull (db/db connection) [:db/id]
                                                            [:seon.fn/sym function-symbol])))))]
    (let [refused (adopt! [[:db.fn/call (fn [_] (throw (ex-info "record refused" {})))]])]
      (is (instance? clojure.lang.ExceptionInfo refused))
      (is (nil? (:db/id (db/pull (db/db connection) [:db/id] [:seon.fn/sym function-symbol])))
          "a refused record leaves the rows unwritten"))
    (adopt! [{:db/id :db/current-tx :seon.test/adoption-inputs #{"adopted-value.clj"}}])
    (let [database (db/db connection)
          tx (adopted-tx)]
      (is (some? (:db/id (db/pull database [:db/id] [:seon.fn/sym function-symbol]))))
      (is (= #{"adopted-value.clj"}
             (set (map :v (db/datoms database :eavt tx :seon.test/adoption-inputs))))
          "the record is on the transaction that wrote the row"))))
