(ns seon.test-resolution-test
  "A core test whose namespace is registered but absent from the JVM resolves
  and runs: `seon.test/resolve-test` loads it rather than returning no Var."
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.schema :as schema]
            [seon.test :as sut]
            [seon.test-resolution-fixture]
            [seon.test-support :as test-support]))

(deftest a-registered-but-absent-test-namespace-resolves-and-runs
  (test-support/with-database
   (fn [connection]
     (let [database (db/db connection)
           ctx (test-support/fork-cluster-ctx connection)
           identity-symbol 'seon.test-resolution-fixture/resolves-after-removal
           request {:seon.db/db database :seon.db/connection connection
                    :seon.test/identity identity-symbol :seon.sci.eval/ctx ctx
                    :seon.schema/projection (schema/projection-from-database database)
                    :seon.test/class-loader (clojure.lang.RT/baseLoader)}]
       (is (:db/id (db/pull database [:db/id] [:seon.test/sym identity-symbol])) "the fixture is an admitted core test")
       ;; The state the walk-graph red found: registered in `*loaded-libs*`, absent from the JVM.
       (remove-ns 'seon.test-resolution-fixture)
       (is (contains? @@#'clojure.core/*loaded-libs* 'seon.test-resolution-fixture))
       (let [resolved (sut/resolve-test request)]
         (is (var? resolved))
         (is (some? (find-ns 'seon.test-resolution-fixture)) "resolution loaded the absent namespace")
         (when (var? resolved)
           (is (true? ((:test (meta resolved)))) "the resolved test body runs; its last `is` returns true")))))))
