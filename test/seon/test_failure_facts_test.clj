(ns seon.test-failure-facts-test
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.test.runner :as runner]
            [seon.test-support :as support]))

(defn- completion [database test-symbol failures]
  (let [run (runner/provenance database)]
    {:seon.db/db database
     :seon.test.run/provenance run
     :seon.test/run-basis-t (:seon.test.run/basis-t run)
     :seon.test/run-at (:seon.test.run/at run)
     :seon.test.runner/results
     [{:seon.test/sym test-symbol :seon.test/pass-count (if (zero? failures) 1 0)
       :seon.test/fail-count failures :seon.test/error-count 0}]}))

(deftest recorded-reach-belongs-to-the-tested-value-and-is-replaced
  (support/with-database
    (fn [connection]
      (let [s "reach.facts/check"
            a "reach.facts/a" b "reach.facts/b"]
        (is (:db-after (db/transact! connection
                        [{:db/id "a" :seon.fn/sym a}
                         {:db/id "b" :seon.fn/sym b}
                         {:seon.test/sym s :seon.fn/calls ["a"]}])))
        (let [tested (db/db connection)
              captured (completion tested s 1)]
          (is (:db-after (db/transact! connection
                          [[:db.fn/retractAttribute [:seon.test/sym s] :seon.fn/calls]
                           [:db/add [:seon.test/sym s] :seon.fn/calls [:seon.fn/sym b]]])))
          (is (vector? (runner/commit-results! connection captured)))
          (is (= #{a} (set (db/q '[:find [?s ...] :in $ ?test
                                  :where [?t :seon.test/sym ?test]
                                         [?t :seon.test/reach ?f]
                                         [?f :seon.fn/sym ?s]] (db/db connection) s))))
          (is (= (get (runner/reach-digests tested [s]) s)
                 (:seon.test/reach-digest (db/pull (db/db connection)
                                           [:seon.test/reach-digest] [:seon.test/sym s]))))
          (is (vector? (runner/commit-results! connection (completion (db/db connection) s 0))))
          (is (= #{b} (set (db/q '[:find [?s ...] :in $ ?test
                                  :where [?t :seon.test/sym ?test]
                                         [?t :seon.test/reach ?f]
                                         [?f :seon.fn/sym ?s]] (db/db connection) s)))))))))
