(ns seon.publication-validation-test
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.profile :as profile]
            [seon.test-support :as support]))

(defn- armed-calls
  "Calls of `symbols` so far, summed over their armed wrappers' profile cells."
  {:malli/schema [:=> [:cat [:vector :qualified-symbol]] [:map-of :qualified-symbol :int]]}
  [symbols]
  (into {} (map (fn [sym]
                  [sym (transduce (comp (filter #(= sym (:seon.profile/sym %)))
                                        (map #(:seon.profile/calls (profile/observation %))))
                                  + 0 (profile/cells))]))
        symbols))

(deftest documentation-writes-do-not-check-unrelated-arities-or-renderers
  ;; The armed wrappers' call counters are the observation: no Var is replaced.
  ;; The refused arity write is the positive control that the counters see the
  ;; arity check at all. The counters are JVM-wide, so concurrent writers on
  ;; other threads can only make the doc-write delta larger, never hide a call.
  (support/with-database
   (fn [connection]
     (let [checks '[seon.db/report-arity-comparison seon.db/write-render-target-error]
           before (armed-calls checks)
           ;; A doc is an owned attribute: the revision carries the digest of
           ;; the entity it leaves.
           report (support/transacted!
                   connection
                   [(support/revision (db/db connection) '[:seon.fn/sym seon.id/id]
                                      {:seon.fn/doc "Publication validation documentation edit."})])
           after-doc (armed-calls checks)
           refused (db/transact! connection [[:db/add [:seon.fn/sym 'seon.id/id]
                                              :seon.fn/call-arities ['seon.id/id 99]]])
           after-arity (armed-calls checks)]
       (is (:db-after report))
       (is (= {'seon.db/report-arity-comparison 0 'seon.db/write-render-target-error 0}
              (merge-with - after-doc before))
           "a documentation write runs neither the arity nor the renderer check")
       (is (:seon.db/transaction-refused refused))
       (is (pos? (- (get after-arity 'seon.db/report-arity-comparison)
                    (get after-doc 'seon.db/report-arity-comparison)))
           "a call-arity write does run the arity check the counters observe")
       (is (= #{:seon.fn/doc :seon.program/definition-digest :db/txInstant}
              (set (map :a (:tx-data report)))))))))

(deftest ^{:seon.test/long "Canonical fixture plus final arity/default validation measured 5.078 s; the current exported base refuses supplier coherence before the intended arity diagnostic."
           :seon.test/long-ms 10000}
  changed-call-facts-still-refuse-an-invalid-arity
  (support/with-database
   (fn [connection]
     (let [before (db/basis-t (db/db connection))
           refusal (db/transact!
                    connection
                    [[:db/add [:seon.fn/sym 'seon.id/id]
                      :seon.fn/call-arities ['seon.id/id 99]]])]
       (is (:seon.db/transaction-refused refusal))
       (is (seq (get-in refusal [:seon.error/data :seon.fn/arity-mismatches])))
       (is (= before (db/basis-t (db/db connection))))))))
