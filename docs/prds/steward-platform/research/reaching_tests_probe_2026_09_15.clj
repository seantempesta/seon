;; Run in default's JVM after obtaining the declared test dependency classpath:
;; clojure -Spath -M:test > tmp/reaching-test-classpath.txt
;; This command resolves paths only; it runs no tests. The check below starts
;; no subprocess. Its own total and per-Var deadlines apply in this JVM.
(require 'clojure.string 'clojure.java.io 'seon.operator 'seon.schema
         'seon.instrument 'seon.test)
(let [loader (clojure.lang.DynamicClassLoader. (clojure.lang.RT/baseLoader))
      paths (java.util.StringTokenizer.
             (clojure.string/trim (slurp "tmp/reaching-test-classpath.txt"))
             java.io.File/pathSeparator)]
  (doseq [path (enumeration-seq paths)]
    (.addURL loader (.toURL (.toURI (clojure.java.io/file path)))))
  (with-bindings {clojure.lang.Compiler/LOADER loader}
    (doseq [namespace-name '[dev-cache seon.dev.dependency-cache-test
                            seon.test-support seon.test-reaching-test]]
      (require namespace-name :reload))
    (def reaching-verification
      (future
        (let [connection (seon.operator/connection "default")
              projection (seon.schema/projection-from-database @connection)]
          (seon.schema/call-with-projection
           projection
           (fn []
             (seon.instrument/apply!
              {:seon.config/on-core-error :panic
               :seon.schema/projection projection})
             (let [result
                   (seon.test/check
                    {:seon.db/connection connection
                     :seon.test/changed [[:seon.ns/name 'seon.test-reaching-test]]
                     :seon.test/paths ["src/seon/test.clj" "src/my/test.clj"
                                       "test/seon/test_reaching_test.clj"]})]
               (spit "tmp/reaching-verification.edn" (pr-str result))
               result))))))))
