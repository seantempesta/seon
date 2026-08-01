(ns seon.db-test
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.db :as db]
            [seon.test-support :as test-support]))

(def ^:private exam-query
  '[:find (count ?key) .
    :where
    [_ :seon.schema/key ?key]
    [(namespace ?key) ?namespace]
    [(= ?namespace "my.message")]])

(def ^:private nonzero-source-query
  '[:find (count ?key) .
    :in ?wanted-namespace $
    :where
    [_ :seon.schema/key ?key]
    [(namespace ?key) ?namespace]
    [(= ?namespace ?wanted-namespace)]])

(def ^:private schema-pattern
  [:seon.schema/key :seon.schema/form])

(def ^:private schema-ref
  [:seon.schema/key :my.message/content])

(def ^:private missing-schema-ref
  [:seon.schema/key :seon.db-test/missing])

(deftest explicit-and-current-database-forms-are-equivalent
  (test-support/with-database
   (fn [connection]
     (let [database @connection
           expected (d/q exam-query database)]
       (testing "explicit database forms never consult ambient custody"
         (binding [db/*conn* nil]
           (is (= expected (db/q database exam-query)))
           (is (= expected (db/q exam-query database)))))
       (binding [db/*conn* connection]
         (testing "q inserts current database at source position zero"
           (is (= expected (db/q exam-query)))
           (is (= (db/q database exam-query)
                  (db/q exam-query))))
         (testing "q inserts current database at the parsed source position"
           (is (= (db/q database nonzero-source-query "my.message")
                  (db/q nonzero-source-query "my.message"))))
         (testing "pull has equivalent explicit and current forms"
           (is (= (db/pull database schema-pattern schema-ref)
                  (db/pull schema-pattern schema-ref))))
         (testing "pull-many has equivalent explicit and current forms"
           (is (= (db/pull-many database schema-pattern
                                [schema-ref missing-schema-ref schema-ref])
                  (db/pull-many schema-pattern
                                [schema-ref missing-schema-ref schema-ref])))))))))

(deftest current-database-resolves-once-per-call
  (test-support/with-database
   (fn [connection]
     (let [calls (atom 0)
           datahike-db d/db]
       (with-redefs [d/db (fn [bound-connection]
                            (swap! calls inc)
                            (datahike-db bound-connection))]
         (binding [db/*conn* connection]
           (db/q exam-query)
           (is (= 1 @calls))
           (db/pull schema-pattern schema-ref)
           (is (= 2 @calls))
           (db/pull-many schema-pattern [schema-ref])
           (is (= 3 @calls))))))))

(deftest unbound-current-database-is-a-flat-error
  (let [result (binding [db/*conn* nil]
                 (db/q exam-query))]
    (is (= :seon.db/missing-connection-binding
           (:seon.error/kind result)))
    (is (string? (:seon.error/message result)))
    (is (= 'seon.db/*conn*
           (get-in result [:seon.error/data :seon.db/binding])))))

(deftest query-and-pull-append-evidence-only-when-captured
  (test-support/with-database
   (fn [connection]
     (let [entries (atom [])]
       (binding [db/*conn* connection]
         (db/q exam-query)
         (is (empty? @entries)))
       (binding [db/*conn* connection
                 db/*capture-context* entries]
         (db/q exam-query)
         (db/pull schema-pattern schema-ref)
         (db/pull-many schema-pattern [schema-ref missing-schema-ref]))
       (is (= [0 0 0]
              (mapv :seon.db/source-argument-position @entries)))
       (is (every? #(contains? % :datahike.read/dependency-plan)
                   @entries))))))

(deftest pull-many-preserves-input-alignment-with-one-shared-plan
  (test-support/with-database
   (fn [connection]
     (let [database @connection
           entity-ids [schema-ref missing-schema-ref schema-ref]
           calls (atom [])
           pull-many-with-evidence d/pull-many-with-evidence
           entries (atom [])]
       (with-redefs [d/pull-many-with-evidence
                     (fn [db-value pattern eids]
                       (swap! calls conj [db-value pattern eids])
                       (pull-many-with-evidence db-value pattern eids))]
         (binding [db/*capture-context* entries]
           (let [result (db/pull-many database schema-pattern entity-ids)]
             (is (= [(d/pull database schema-pattern schema-ref)
                     nil
                     (d/pull database schema-pattern schema-ref)]
                    result))
             (is (= 3 (count result))))))
       (is (= 1 (count @calls)))
       (is (= [[database schema-pattern entity-ids]] @calls))
       (is (= 1 (count @entries)))
       (is (= (:datahike.read/dependency-plan
               (pull-many-with-evidence database schema-pattern entity-ids))
              (:datahike.read/dependency-plan (first @entries))))))))

(deftest slice-one-exposes-no-lazy-entity-read
  (test-support/with-database
   (fn [connection]
     (let [pulled (db/pull @connection schema-pattern schema-ref)]
       (is (nil? (ns-resolve 'seon.db 'entity)))
       (is (map? pulled))
       (is (not (instance? datahike.impl.entity.Entity pulled)))))))

(deftest the-exam-query-returns-the-fixtures-true-count
  (test-support/with-database
   (fn [connection]
     (let [database @connection
           fixture-count (d/q exam-query database)]
       (is (pos-int? fixture-count))
       (binding [db/*conn* connection]
         (is (= fixture-count (db/q exam-query))))))))

(deftest malformed-reads-return-flat-errors
  (test-support/with-database
   (fn [connection]
     (doseq [result [(db/q @connection '[:find])
                     (db/pull-many @connection schema-pattern ["not-an-eid"])]]
       (is (= :seon.db/invalid-read (:seon.error/kind result)))
       (is (string? (:seon.error/message result)))
       (is (map? (:seon.error/data result)))))))
