(ns seon.test-failure-facts-test
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.test :as sut]
            [seon.program :as program]
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

(deftest publication-replaces-cardinality-many-tuples
  (support/with-database
    (fn [connection]
      (let [s "reach.facts/tuple-owner"]
        (is (:db-after (db/transact! connection
                        [{:seon.fn/sym s :seon.fn/call-arities
                          #{["clojure.core/inc" 1] ["clojure.core/+" 2] ["clojure.core/str" 1]}}])))
        (let [current (db/pull (db/db connection) '[*] [:seon.fn/sym s])]
          (is (:db-after (db/transact! connection
                          (program/exact-replacement-tx current
                            {:seon.fn/sym s :seon.fn/call-arities #{["clojure.core/dec" 1]}}))))
          (is (= #{["clojure.core/dec" 1]}
                 (set (:seon.fn/call-arities
                        (db/pull (db/db connection) [:seon.fn/call-arities] [:seon.fn/sym s]))))))))))

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

(deftest what-made-it-red-names-changed-functions
  (support/with-database
    (fn [connection]
      (let [s "changed.facts/check" a "changed.facts/a" b "changed.facts/b"]
        (is (:db-after (db/transact! connection
                        [{:db/id "a" :seon.fn/sym a :seon.fn/source "old"
                          :seon.fn/spec "[:=> [:cat] :int]"}
                         {:db/id "b" :seon.fn/sym b :seon.fn/source "old"}
                         {:seon.test/sym s :seon.fn/calls ["a"]}])))
        (runner/commit-results! connection (completion (db/db connection) s 1))
        (is (= :seon.test/unknown (:seon.error/kind (sut/changed-since-green (db/db connection) s))))
        (runner/commit-results! connection (completion (db/db connection) s 0))
        (db/transact! connection [[:db/add [:seon.fn/sym a] :seon.fn/source "intermediate"]])
        (runner/commit-results! connection (completion (db/db connection) s 0))
        (is (= [] (sut/changed-since-green (db/db connection) s))
            "the later green is recognized even when its zero counts are unchanged")
        (db/transact! connection [[:db.fn/retractAttribute [:seon.fn/sym a] :seon.fn/spec]
                                 [:db/add [:seon.fn/sym b] :seon.fn/source "unrelated"]])
        (runner/commit-results! connection (completion (db/db connection) s 1))
        (is (= [a] (mapv :seon.fn/sym (sut/changed-since-green (db/db connection) s)))
            "spec retraction counts; a changed function outside the tested closure does not")))))
