(ns seon.repl-differential-p2-test
  "P2 rows of the REPL against plain SCI: SCI owns the reader policy, so data
  readers, record literals and response-shaped maps read as they do in SCI."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.repl-differential-test :as differential]
            [seon.test-support :as support]))

(def rows
  "`[label code expected]`: the value of `code`'s last form is `expected`, the
  value plain SCI gives. A nil `expected` is a form SCI refuses to read."
  [["record-literal-reads" "(in-ns 'p2.rec) (defrecord R [x]) (:x #p2.rec.R{:x 8})" 8]
   ["set-data-readers-applies-to-the-next-form" "(set! *data-readers* {'p/t inc}) #p/t 1" 2]
   ["a-seon-repl-keyed-map-is-data" "{:seon.repl/value 2}" {:seon.repl/value 2}]
   ["a-seon-repl-tagged-map-is-data" "(:seon.repl/value #:seon.repl{:value 3})" 3]
   ["read-eval-stays-refused" "#=(+ 1 2)" nil]])

(deftest sci-owns-the-reader-policy
  (support/with-running-agent
   (fn [running agent-id _]
     (doseq [[label code expected] rows]
       (testing label
         (is (= expected (#'differential/answered code agent-id running))))))))
