(ns seon.test.admission-digest-test
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.test :as sut]
            [seon.test.runner :as runner]
            [seon.test-support :as support]))

(def ^:private observed (atom {}))

(defn observe-program-write
  "A transaction function recording what the writer's in-transaction value answers."
  [database tag basis-t]
  (swap! observed assoc tag (runner/program-written-since? database basis-t))
  [])

(deftest an-admission-confirms-the-tested-digest-without-deriving-the-program
  ;; admit-run reads the writer's in-transaction value, which carries no commit
  ;; id, so the digest memo cannot name it; it derived the whole program there
  ;; (9.6 s per admission on default, 2026-09-23). A value with no program
  ;; write since the tested basis has the tested digest.
  (support/with-database
    (fn [connection]
      (reset! observed {})
      (support/transacted!
       connection
       [[:db/add [:seon.fn/sym 'seon.id/id] :seon.fn/doc "A changed declaration."]])
      (let [tested (db/db connection)
            basis (db/basis-t tested)
            digest (runner/program-digest tested)]
        (support/transacted!
         connection
         [[:db/add [:seon.test/sym 'seon.id-test/data-shape-and-explicit-length-determine-identity]
           :seon.test/pass-count 1]])
        (support/transacted! connection [[:db.fn/call #'observe-program-write ::result-write basis]])
        (is (string? digest) (pr-str digest))
        (is (false? (::result-write @observed)) "a result write is no program write")
        (support/transacted!
         connection
         [[:db/add [:seon.fn/sym 'seon.id/digest] :seon.fn/doc "Another changed declaration."]
          [:db.fn/call #'observe-program-write ::program-write basis]])
        (is (true? (::program-write @observed))
            "a program write earlier in the same transaction is seen")
        (is (not= digest (runner/program-digest (db/db connection))))))))

(deftest a-request-records-its-batch-in-one-transaction
  ;; Recording was one writer transaction per member (65 per batch of 64, about
  ;; 210-500 ms each on default); a request now writes its admission and one
  ;; recording transaction for the batch, and each member keeps its own facts.
  (support/with-database
    (fn [connection]
      (let [members #{'seon.id-test/an-evaluation-id-is-stable-short-and-a-symbol
                      'seon.id-test/data-shape-and-explicit-length-determine-identity
                      'seon.config-test/optional-setting-flags-assert-only-true}
            before (db/basis-t (db/db connection))
            result (sut/run {:seon.test/execution (support/execution-handle connection)
                             :seon.test/recording-connection connection
                             :seon.test/policy :named
                             :seon.test/identities members})]
        (is (true? (:seon.test/passed? result)) (sut/tally result))
        (is (= 3 (:seon.test/executed-count result)))
        (is (= 2 (- (db/basis-t (db/db connection)) before))
            "one admission transaction and one recording transaction")
        (is (= members (set (map :seon.test/sym (runner/run-results (db/db connection) (:seon.test.run/id result))))))))))
