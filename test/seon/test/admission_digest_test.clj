(ns seon.test.admission-digest-test
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
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
