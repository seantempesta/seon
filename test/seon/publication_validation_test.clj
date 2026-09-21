(ns seon.publication-validation-test
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.test-support :as support]))

(deftest documentation-writes-do-not-check-unrelated-arities-or-renderers
  (support/with-database
   (fn [connection]
     (let [calls (atom [])
           vars [#'db/arity-mismatches-with #'db/write-render-target-error]
           wrappers (into {} (map (fn [v]
                                   (let [original @v]
                                     [v (fn [& args]
                                          (swap! calls conj (:name (meta v)))
                                          (apply original args))]))) vars)
           report (with-redefs-fn
                    wrappers
                    #(support/transacted!
                      connection
                      [{:seon.fn/sym 'seon.id/id
                        :seon.fn/doc "Publication validation documentation edit."}]))]
       (is (:db-after report))
       (is (empty? @calls))
       (is (= #{:seon.fn/doc :db/txInstant}
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
       (is (seq (get-in refusal [:seon.error/data :seon.fn/arity-mismatches])) (pr-str refusal))
       (is (= before (db/basis-t (db/db connection))))))))
