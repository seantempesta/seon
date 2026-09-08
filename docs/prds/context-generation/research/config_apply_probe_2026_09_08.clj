;; Run through MCP JVM evaluation after config apply and development adoption.
(require 'seon.operator 'seon.config 'seon.db 'seon.operator.runtime
         'seon.cluster.source)

(let [connection (seon.operator/connection "default")
      database @connection
      expected (:seon.config/desired-row
                (seon.config/compile-manifest
                 {:seon.boot/cluster-name "default"}))
      actual (seon.db/pull database '[*] [:seon.config/cluster "default"])
      cluster-row (seon.db/pull database '[*] [:seon.cluster/name "default"])
      instance (get @seon.operator.runtime/running-instances "default")
      published (:seon.source/commit-id
                 (seon.cluster.source/current (:seon.store/store instance)))
      adopted (:seon.source/commit-id cluster-row)]
  {:seon.config.probe/desired-count (count expected)
   :seon.config.probe/decisions-equal?
   (and (seq expected) (= expected (select-keys actual (keys expected))))
   :seon.config.probe/projection-symbol?
   (symbol? (:seon.config.web/search-result-projection actual))
   :seon.config.probe/adopted adopted
   :seon.config.probe/published published
   :seon.config.probe/converged?
   (and (some? adopted) (some? published) (= adopted published))})
