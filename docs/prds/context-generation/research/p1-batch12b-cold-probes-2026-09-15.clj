(do
  (require 'seon.sci.eval-test 'seon.render-simplification-test)
  [
(let [test-symbol 'seon.sci.eval-test/runtime-function-rows-carry-parsed-contract-facts
      test-namespace (symbol (namespace test-symbol))
      source-path "test/seon/sci/eval_test.clj"
      connection (seon.operator/connection "default")]
  (binding [*ns* (the-ns test-namespace)]
    (let [original
          (with-open [reader (java.io.PushbackReader. (clojure.java.io/reader source-path))]
            (first
             (filter #(and (seq? %) (= (symbol (name test-symbol)) (second %)))
                     (take-while #(not= ::eof %)
                                 (repeatedly #(read {:eof ::eof} reader))))))
          cold
          (clojure.walk/postwalk
           (fn [form]
             (cond
               (and (seq? form)
                    (contains? '#{test-support/with-database support/with-database}
                               (first form))
                    (= 2 (count form)))
               (list (first form)
                     {:seon.test-support/fresh-store? true
                      :seon.test/fixture-observation
                      "Verify batch-12b against a new population, without warmed base state."}
                     (second form))
               (and (seq? form)
                    (contains? '#{test-support/fork-cluster-ctx support/fork-cluster-ctx}
                               (first form)))
               (list 'sci/fork (list 'eval/cluster-ctx (list 'db/db (second form)) (second form)))
               :else form))
           original)]
      (try
        (clojure.core/eval cold)
        (seon.test/run
         (resolve test-symbol) connection
         {:seon.test.run/provenance (seon.test.runner/provenance (seon.db/db connection))
          :seon.test/remaining-ms 120000})
        (finally (clojure.core/eval original))))))
(let [test-symbol 'seon.sci.eval-test/static-and-runtime-contracted-definitions-publish-identical-facts
      test-namespace (symbol (namespace test-symbol))
      source-path "test/seon/sci/eval_test.clj"
      connection (seon.operator/connection "default")]
  (binding [*ns* (the-ns test-namespace)]
    (let [original
          (with-open [reader (java.io.PushbackReader. (clojure.java.io/reader source-path))]
            (first
             (filter #(and (seq? %) (= (symbol (name test-symbol)) (second %)))
                     (take-while #(not= ::eof %)
                                 (repeatedly #(read {:eof ::eof} reader))))))
          cold
          (clojure.walk/postwalk
           (fn [form]
             (cond
               (and (seq? form)
                    (contains? '#{test-support/with-database support/with-database}
                               (first form))
                    (= 2 (count form)))
               (list (first form)
                     {:seon.test-support/fresh-store? true
                      :seon.test/fixture-observation
                      "Verify batch-12b against a new population, without warmed base state."}
                     (second form))
               (and (seq? form)
                    (contains? '#{test-support/fork-cluster-ctx support/fork-cluster-ctx}
                               (first form)))
               (list 'sci/fork (list 'eval/cluster-ctx (list 'db/db (second form)) (second form)))
               :else form))
           original)]
      (try
        (clojure.core/eval cold)
        (seon.test/run
         (resolve test-symbol) connection
         {:seon.test.run/provenance (seon.test.runner/provenance (seon.db/db connection))
          :seon.test/remaining-ms 120000})
        (finally (clojure.core/eval original))))))
(let [test-symbol 'seon.sci.eval-test/public-walk-is-callable-through-an-agent-sci-eval
      test-namespace (symbol (namespace test-symbol))
      source-path "test/seon/sci/eval_test.clj"
      connection (seon.operator/connection "default")]
  (binding [*ns* (the-ns test-namespace)]
    (let [original
          (with-open [reader (java.io.PushbackReader. (clojure.java.io/reader source-path))]
            (first
             (filter #(and (seq? %) (= (symbol (name test-symbol)) (second %)))
                     (take-while #(not= ::eof %)
                                 (repeatedly #(read {:eof ::eof} reader))))))
          cold
          (clojure.walk/postwalk
           (fn [form]
             (cond
               (and (seq? form)
                    (contains? '#{test-support/with-database support/with-database}
                               (first form))
                    (= 2 (count form)))
               (list (first form)
                     {:seon.test-support/fresh-store? true
                      :seon.test/fixture-observation
                      "Verify batch-12b against a new population, without warmed base state."}
                     (second form))
               (and (seq? form)
                    (contains? '#{test-support/fork-cluster-ctx support/fork-cluster-ctx}
                               (first form)))
               (list 'sci/fork (list 'eval/cluster-ctx (list 'db/db (second form)) (second form)))
               :else form))
           original)]
      (try
        (clojure.core/eval cold)
        (seon.test/run
         (resolve test-symbol) connection
         {:seon.test.run/provenance (seon.test.runner/provenance (seon.db/db connection))
          :seon.test/remaining-ms 120000})
        (finally (clojure.core/eval original))))))
(let [test-symbol 'seon.render-simplification-test/distance-spends-only-real-ref-hops-and-caps-win
      test-namespace (symbol (namespace test-symbol))
      source-path "test/seon/render_simplification_test.clj"
      connection (seon.operator/connection "default")]
  (binding [*ns* (the-ns test-namespace)]
    (let [original
          (with-open [reader (java.io.PushbackReader. (clojure.java.io/reader source-path))]
            (first
             (filter #(and (seq? %) (= (symbol (name test-symbol)) (second %)))
                     (take-while #(not= ::eof %)
                                 (repeatedly #(read {:eof ::eof} reader))))))
          cold
          (clojure.walk/postwalk
           (fn [form]
             (cond
               (and (seq? form)
                    (contains? '#{test-support/with-database support/with-database}
                               (first form))
                    (= 2 (count form)))
               (list (first form)
                     {:seon.test-support/fresh-store? true
                      :seon.test/fixture-observation
                      "Verify batch-12b against a new population, without warmed base state."}
                     (second form))
               (and (seq? form)
                    (contains? '#{test-support/fork-cluster-ctx support/fork-cluster-ctx}
                               (first form)))
               (list 'sci/fork (list 'eval/cluster-ctx (list 'db/db (second form)) (second form)))
               :else form))
           original)]
      (try
        (clojure.core/eval cold)
        (seon.test/run
         (resolve test-symbol) connection
         {:seon.test.run/provenance (seon.test.runner/provenance (seon.db/db connection))
          :seon.test/remaining-ms 120000})
        (finally (clojure.core/eval original))))))
])
