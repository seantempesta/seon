; Evaluate this form through MCP in default JVM; no test JVM is started.
(#'seon.test/with-test-loader
 (fn []
   (let [manifest (seon.fn/build-manifest {:seon.fn/roots ["src" "test"]})
         names (vec (sort (distinct (keep :seon.test/sym
                          (mapcat :seon.fn.file/rows
                                  (:seon.fn.manifest/artifacts manifest))))))]
     (doseq [n (distinct (map #(symbol (namespace (symbol %))) names))]
       (require n))
     (let [vars (mapv #(find-var (symbol %)) names)
           expensive (#'seon.test.runner/expensive-fixture-tests manifest)]
       (assert (every? some? vars))
       (#'seon.test.runner/verify-fixture-observations! manifest vars)
       {:selected (count vars) :expensive (vec (sort expensive)) :refusals 0}))))
