(ns seon.test-provenance-test
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.test.runner :as runner]
            [seon.test-support :as support]))

(defn completion
  [database passes]
  (let [run (runner/provenance database)]
    {:seon.test.runner/results
     [{:seon.test/sym "provenance.example/check"
       :seon.test/pass-count passes
       :seon.test/fail-count (if (pos? passes) 0 1)
       :seon.test/error-count 0}]
     :seon.test/run-basis-t (:seon.test.run/basis-t run)
     :seon.test/run-at (:seon.test.run/at run)
     :seon.test.run/provenance run}))

(deftest runs-are-distinct-immutable-and-linked-atomically
  (support/with-database
    (fn [connection]
      (let [captured (completion @connection 1)
            first-run (:seon.test.run/provenance captured)
            stored (runner/commit-results! connection captured)
            again (assoc-in captured [:seon.test.run/provenance :seon.test.run/id]
                            "second-provenance-run")
            second-stored (runner/commit-results! connection again)
            runs (db/q '[:find [?id ...] :where [_ :seon.test.run/id ?id]] @connection)]
        (is (= 1 (:seon.test/pass-count (first stored))) (pr-str stored))
        (is (= 1 (:seon.test/pass-count (first second-stored))) (pr-str second-stored))
        (is (= #{(:seon.test.run/id first-run) "second-provenance-run"} (set runs)))
        (is (nil? (get-in @connection [:schema :seon.test.run/program-digest :db/unique]))
            "many runs may test the same program")
        (is (= first-run
               (dissoc (db/pull @connection '[*]
                                [:seon.test.run/id (:seon.test.run/id first-run)]) :db/id)))
        (is (= "second-provenance-run"
               (get-in (db/pull @connection
                                '[{:seon.test/run [:seon.test.run/id]}]
                                [:seon.test/sym "provenance.example/check"])
                       [:seon.test/run :seon.test.run/id])))
        (let [basis (db/basis-t @connection)
              refusal (support/refusal-data
                       #(runner/commit-results!
                         connection
                         (assoc-in captured
                                   [:seon.test.run/provenance :seon.test.run/program-digest]
                                   (apply str (repeat 64 "b")))))]
          (is (not= support/committed refusal))
          (is (= basis (db/basis-t @connection)) "the refused write is atomic"))))))

(deftest program-identity-excludes-results-and-includes-admitted-source
  (support/with-database
    (fn [connection]
      (db/transact! connection
                    [{:seon.test/sym "provenance.example/check"
                      :seon.schema.admission/source :core
                      :seon.test/source "(deftest check (is true))"}])
      (let [before (runner/program-digest @connection)
            captured (completion @connection 1)]
        (runner/commit-results! connection captured)
        (is (= before (runner/program-digest @connection)))
        (is (:db-after
             (db/transact! connection
                           [[:db/add [:seon.test/sym "provenance.example/check"]
                             :seon.test/source "(deftest check (is false))"]])))
        (is (not= before (runner/program-digest @connection)))
        (is (= before (:seon.test.run/program-digest
                       (:seon.test.run/provenance captured))))))))

