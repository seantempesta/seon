(ns seon.test-reaching-test
  (:require [clojure.test :as t :refer [deftest is]]
            [seon.db :as db]
            [seon.fn :as functions]
            [seon.id :as id]
            [seon.test :as sut]
            [seon.test.runner :as runner]
            [seon.test-support :as support]))

(deftest reaching-is-the-program-graph-relation
  (support/with-database
    (fn [connection]
      (let [database (db/db connection)
            actual (sut/reaching {:seon.db/db database
                                  :seon.test/changed ['my.note/add!]})]
        (is (:db/id (db/pull database [:db/id] [:seon.fn/sym "my.note/add!"])))
        (is (vector? actual))
        (is (= (set (functions/tests-reaching database "my.note/add!")) (set actual)))
        (is (= :seon.test/unknown
               (:seon.error/kind
                (sut/reaching {:seon.db/db database
                               :seon.test/changed ['absent.function/no-row]}))))))))

(defn- with-test [connection body assertion]
  (support/seed-cluster! connection "default")
  (let [namespace-name (symbol (str "reaching.probe" (id/id)))
        namespace-object (create-ns namespace-name)
        test-symbol (str namespace-name "/probe")
        source (list 'clojure.test/deftest 'probe body)
        test-var (binding [*ns* namespace-object]
                   (clojure.core/refer 'clojure.core)
                   (eval source))]
    (try
      (db/transact! connection
                    [{:seon.ns/name namespace-name}
                     {:seon.test/sym test-symbol
                      :seon.schema.admission/source :core
                      :seon.test/ns [:seon.ns/name namespace-name]
                      :seon.test/source (pr-str source)}])
      (assertion test-symbol test-var)
      (finally (remove-ns namespace-name)))))

(deftest check-records-provenance-and-verifies-green
  (support/with-database
    (fn [connection]
      (with-test connection '(clojure.test/is (= 4 (+ 2 2)))
        (fn [test-symbol _]
          (let [basis (db/basis-t (db/db connection))
                result (sut/check {:seon.db/connection connection
                                   :seon.test/changed [test-symbol]
                                   :seon.test/paths ["src/seon/id.clj"]})
                database (db/db connection)
                runs (db/q '[:find [?run ...] :where [?run :seon.test.run/id]] database)]
            (is (= [test-symbol] (:seon.test/passed result)) (pr-str result))
            (is (= basis (:seon.test.run/basis-t result)))
            (is (= 1 (count runs)))
            (is (= basis (:seon.test.run/basis-t (db/pull database '[*] (first runs)))))
            (is (true? (sut/verified? database test-symbol (:seon.test.run/program-digest result))))
            (is (= ["bin/test" "--paths" "src/seon/id.clj" "--platform"]
                   (second (:seon.test/next-tier result))))))))))

(deftest red-check-names-failure-and-stops-escalation
  (support/with-database
    (fn [connection]
      (with-test connection '(clojure.test/is false "expected red reaching probe")
        (fn [test-symbol _]
          (let [result (binding [t/report (constantly nil)]
                         (sut/check {:seon.db/connection connection
                                     :seon.test/changed [test-symbol]}))]
            (is (= :none (:seon.test/next-tier result)) (pr-str result))
            (is (= test-symbol (get-in result [:seon.test/failed 0 :seon.test/sym])))
            (is (.contains (get-in result [:seon.test/failed 0 :seon.test/failure-message] "")
                           "expected red reaching probe"))
            (is (= [test-symbol] (get-in result [:seon.test/failed 0 :seon.test/changed])))
            (is (= 1 (:seon.test/fail-count
                       (db/pull (db/db connection) '[*] [:seon.test/sym test-symbol]))))))))))

(deftest widened-hook-check-reports-and-runs-nothing
  (support/with-database
    (fn [connection]
      (support/seed-cluster! connection "default")
      (let [result (sut/check {:seon.db/connection connection
                               :seon.test/changed []
                               :seon.test/paths ["deps.edn"]
                               :seon.test/namespaces ['seon.id-test]
                               :seon.test/defer-widened? true})]
        (is (string? (:seon.test/widened result)) (pr-str result))
        (is (= [] (:seon.test/tests result)))
        (is (= ["bin/test" "--paths" "deps.edn" "--" "seon.id-test"]
               (first (:seon.test/next-tier result))))
        (is (empty? (db/q '[:find [?run ...] :where [?run :seon.test.run/id]] (db/db connection))))
        (let [unbounded (sut/check {:seon.db/connection connection
                                    :seon.test/changed []
                                    :seon.test/paths ["deps.edn"]
                                    :seon.test/defer-widened? true})]
          (is (= [] (:seon.test/tests unbounded)))
          (is (= ["bin/test" "--paths" "deps.edn"]
                 (first (:seon.test/next-tier unbounded)))))))))

(deftest a-test-completion-bound-is-recorded-as-a-named-error
  (support/with-database
    (fn [connection]
      (with-test connection '(.await (java.util.concurrent.CountDownLatch. 1))
        (fn [test-symbol test-var]
          (let [provenance (runner/provenance (db/db connection))
                result (binding [t/report (constantly nil)]
                         (sut/run test-var connection
                                  {:seon.test.run/provenance provenance
                                   :seon.test/remaining-ms 50}))]
            (is (= 1 (:seon.test/error-count result)) (pr-str result))
            (is (.contains (:seon.test/failure-message result "") test-symbol))
            (is (= 1 (:seon.test/error-count
                       (db/pull (db/db connection) '[*] [:seon.test/sym test-symbol]))))))))))

(deftest empty-check-does-not-acquire-run-provenance
  (support/with-database
    (fn [connection]
      (support/seed-cluster! connection "default")
      (let [seals (db/q '[:find [?entity ...] :where [?entity :seon.source/digest]]
                        (db/db connection))
            removed (db/transact! connection
                                  (mapv #(vector :db.fn/retractAttribute % :seon.source/digest) seals))]
        (is (seq seals))
        (is (:db-after removed) (pr-str removed))
        (is (:seon.error/kind (runner/provenance (db/db connection))))
        (let [result (sut/check {:seon.db/connection connection
                                 :seon.test/changed []
                                 :seon.test/paths ["docs/README.md"]})]
          (is (= [] (:seon.test/tests result)) (pr-str result))
          (is (nil? (:seon.test.run/program-digest result)))
          (is (empty? (:seon.test/failed result)))
          (is (empty? (db/q '[:find [?run ...] :where [?run :seon.test.run/id]]
                            (db/db connection)))))))))
