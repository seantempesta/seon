(ns seon.schema-retirement-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.program :as program]
            [seon.test-support :as support]))

(deftest schema-retirement-keeps-source-write-obligations
  (support/with-database
   (fn [connection]
     (let [attribute :retirement.fixture/value
           writer 'retirement.fixture/write!
           lookup [:seon.fn/sym writer]
           source "(defn write! [connection] (seon.db/transact! connection [{:retirement.fixture/value 1}]))"
           before (db/db connection)
           absent-row (support/program-fn-row before writer source)]
       (is (nil? (:db/id (db/pull before [:db/id] [:seon.schema/key attribute]))))
       (is (contains? (:seon.fn/writes absent-row) attribute)
           "analysis records a literal write even before its schema exists")
       (support/transacted!
        connection
        [(program/declaration-row
          (db/carried-projection before)
          {:seon.ns/name 'retirement.fixture
           :seon.ns/source "(ns retirement.fixture)"} :all :agent)
         (program/declaration-row
          (db/carried-projection before)
          {:seon.schema/key attribute :seon.schema/form ":int"}
          :all :agent)])
       (let [row (support/program-fn-row (db/db connection) writer source)]
         (is (= (:seon.fn/writes absent-row) (:seon.fn/writes row))
             "declaration membership cannot change source write facts")
         (support/transacted! connection [row]))
       (let [before (db/db connection)
             basis (db/basis-t before)
             refusal (db/transact! connection [[:db/retractEntity [:seon.schema/key attribute]]])
             blockers (:seon.program/referrers refusal)]
         (is (= #{attribute} (set (:seon.fn/writes (db/pull before [:seon.fn/writes] lookup)))))
         (is (true? (:seon.db/transaction-refused refusal))
             (pr-str refusal))
         (is (some #(and (= attribute (:seon.program/subject %))
                         (= :seon.fn/writes (:seon.program/relation %))
                         (= writer (get-in % [:seon.program/referrer :seon.fn/sym])))
                   blockers)
             (pr-str refusal))
         (is (str/includes? (:seon.error/message refusal) (str writer)))
         (is (= basis (db/basis-t (db/db connection))))
         (is (some? (:db/id (db/pull (db/db connection) [:db/id] [:seon.schema/key attribute])))))
       (let [converted (support/program-fn-row
                        (db/db connection) writer "(defn write! [connection] nil)")
             report (support/transacted!
                     connection
                     [[:db/retractEntity [:seon.schema/key attribute]]
                      [:db.fn/retractAttribute lookup :seon.fn/writes]
                      [:db.fn/retractAttribute lookup :seon.fn/keywords]
                      [:db.fn/retractAttribute lookup :seon.fn/calls]
                      [:db/retract lookup :seon.fn/call-arities ['seon.db/transact! 2]]
                      converted])
             after (:db-after report)]
         (is (nil? (:db/id (db/pull after [:db/id] [:seon.schema/key attribute]))))
         (is (= (:seon.fn/source converted) (:seon.fn/source (db/pull after [:seon.fn/source] lookup))))
         (is (empty? (:seon.fn/writes (db/pull after [:seon.fn/writes] lookup)))))))))
