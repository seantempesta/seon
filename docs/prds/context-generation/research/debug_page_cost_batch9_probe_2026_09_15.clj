;; MCP JVM probe on default: clone the Batch 9 base and use fresh SCI contexts.
;; The canonical owner closes/deletes every private clone in finally.
(require 'seon.schema 'seon.test.runner 'seon.test-support 'seon.sci.eval
         'seon.test 'seon.operator 'seon.render.web-context-test)
(seon.schema/call-with-projection (#'seon.test.runner/packaged-test-projection "debug-page-cost-probe") (fn [] (let [base (#'seon.test-support/create-base "/Users/sean/src/seon/target/test-published-bases/e2de8452a8db597a861690bd6587d0c521a85ab22bfc68d4a6331dfaec72fc93/base")] (try (with-redefs-fn {#'seon.test-support/database-base (delay base) #'seon.sci.eval/fork-cluster-ctx (fn ([_ d c] (seon.sci.eval/cluster-ctx d c)) ([_ d c p] (seon.sci.eval/cluster-ctx d c p)))} #(seon.test/run #'seon.render.web-context-test/unrelated-adoption-preserves-pages-with-an-unchanged-sci-snapshot (seon.operator/connection "default"))) (finally (#'seon.test-support/close-base! base))))))
