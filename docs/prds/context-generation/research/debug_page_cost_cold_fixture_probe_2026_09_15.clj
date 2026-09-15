;; Evaluate in default through MCP JVM mode. The dated base is a read-only
;; gate artifact; create-base clones it, and close-base! deletes the clone.
;; Substitute another retained gate base path when that artifact is retired.
(require 'seon.schema 'seon.test.runner 'seon.test-support 'seon.sci.eval
         'seon.test 'seon.operator 'seon.render.retained-test)
(seon.schema/call-with-projection (#'seon.test.runner/packaged-test-projection "debug-page-cost-probe") (fn [] (let [base (#'seon.test-support/create-base "/Users/sean/src/seon/target/test-published-bases/368dc5a6094e5093f70f2183b471a5418dab5b37aa422ebf9adf102682f075da/base")] (try (with-redefs-fn {#'seon.test-support/database-base (delay base) #'seon.sci.eval/fork-cluster-ctx (fn ([_ d c] (seon.sci.eval/cluster-ctx d c)) ([_ d c p] (seon.sci.eval/cluster-ctx d c p)))} #(seon.test/run #'seon.render.retained-test/equal-committed-database-skips-read-replay (seon.operator/connection "default"))) (finally (#'seon.test-support/close-base! base))))))
