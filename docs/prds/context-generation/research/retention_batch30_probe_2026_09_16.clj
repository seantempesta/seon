; Read-only MCP JVM probe on default, session retention-sweep.
(require 'seon.operator 'seon.db 'seon.config 'seon.schema)

(let [connection (seon.operator/connection "default")
      compiled (seon.config/compile-manifest {:seon.boot/cluster-name "default"})
      expected (:seon.config/desired-row compiled)
      actual (seon.db/pull @connection [:*] [:seon.config/cluster "default"])
      carried (seon.db/pull (seon.db/db connection) [:*]
                            [:seon.config/cluster "default"])
      prior-digest
      (seon.schema/sha-256
       [(.getBytes
         ^String
         (seon.schema/canonical-data-string
          (assoc (:seon.config/effective compiled)
                 :seon.config.blob/max-bytes 536870912))
         java.nio.charset.StandardCharsets/UTF_8)])]
  {:exact-pull actual
   :exact-equality (= expected (select-keys actual (keys expected)))
   :expected-count (count expected)
   :exact-selected-count (count (select-keys actual (keys expected)))
   :carried-differences
   (into {}
         (keep (fn [[k v]]
                 (when (not= v (get carried k ::absent))
                   [k {:expected v :actual (get carried k ::absent)}])))
         expected)
   :removed-dial-in-expected (contains? expected :seon.config.blob/max-bytes)
   :digest-with-removed-dial prior-digest
   :stored-equals-prior
   (= prior-digest (:seon.config/applied-manifest-digest carried))})
