(do (require 'seon.contracts-compile-test)
(let [root "/tmp/seon-pass-cost-session/pass-cost/candidate-final"
      paths (seon.contracts-compile-test/source-files [(str root "/src") (str root "/test")])
      changed ["src/seon/db.clj" "src/seon/config.clj" "src/seon/sci/kernel.clj" "src/seon/turn.clj" "src/seon/turn/batch.clj" "test/seon/sci/kernel_test.clj" "test/seon/pass_cost_test.clj" "test/seon/pass_probe.clj"]
      request {:seon.contracts-compile-test/root root
               :seon.contracts-compile-test/paths paths
               :seon.contracts-compile-test/baselines
               (into {} (map (fn [p] [(str root "/" p) (slurp (str "/tmp/seon-pass-cost-session/pass-cost/baselines/" p))])) changed)
               :seon.contracts-compile-test/candidate (seon.db/carried-projection @(seon.cluster.boot/connection "default"))}
      mx (java.lang.management.ManagementFactory/getThreadMXBean)
      a (.getCurrentThreadAllocatedBytes mx) t (System/nanoTime)
      findings (seon.contracts-compile-test/check request)]
 {:ms (/ (- (System/nanoTime) t) 1e6) :allocated-bytes (- (.getCurrentThreadAllocatedBytes mx) a) :paths (count paths) :findings findings}))