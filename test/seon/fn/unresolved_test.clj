(ns seon.fn.unresolved-test
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.fn :as functions]
            [seon.test-support :as support]))

(deftest ^{:seon.test/long "Canonical fixture acquisition, two analyzed declarations and two writes measured 50.04 s on the retained publication. The report alone took 3.08 s on a fresh live database value; whole-program fixture/writer work remains a performance finding."
           :seon.test/long-ms 60000}
  unresolved-calls-require-an-indexed-callee-namespace
  (support/with-database
   (fn [connection]
     (let [caller 'my.note/unresolved-report-probe
           missing 'my.note/unresolved-report-target
           row (support/program-fn-row
                (db/db connection) caller
                "(defn unresolved-report-probe [] (clojure.core/str (my.note/unresolved-report-target)))")]
       (support/transacted! connection [row])
       (let [report (functions/unresolved-callers (db/db connection))
             calls (:seon.program/unresolved-callers report)]
         (is (nil? (:seon.error/at report)) (pr-str report))
         (is (= [{:seon.program/identity [:seon.fn/sym caller]
                  :seon.fn/callee missing}]
                (filterv #(= [:seon.fn/sym caller] (:seon.program/identity %)) calls)))
         (is (not-any? #(= "clojure.core" (namespace (:seon.fn/callee %))) calls)))
       (support/transacted!
        connection
        [(support/program-fn-row (db/db connection) missing
                                 "(defn unresolved-report-target [] nil)")])
       (is (not-any? #(= [:seon.fn/sym caller] (:seon.program/identity %))
                     (:seon.program/unresolved-callers
                      (functions/unresolved-callers (db/db connection)))))))))
