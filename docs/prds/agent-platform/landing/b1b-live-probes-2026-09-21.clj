(require '[seon.dev.mcp :as mcp])
(doseq [request [{:code "(+ 1 1)" :mode "jvm" :read_only true}
                 {:code "(:seon.cluster/name (seon.db/pull (seon.db/db (seon.cluster.boot/connection \"default\")) [:seon.cluster/name] [:seon.cluster/name \"default\"]))" :mode "jvm" :read_only true}
                 {:code "(+ 1 1)" :mode "sci" :read_only true}]]
  (prn (#'mcp/execute-clj-eval
        (merge {:root "/Users/sean/src/seon" :cluster "default" :timeout_ms 5000} request))))
