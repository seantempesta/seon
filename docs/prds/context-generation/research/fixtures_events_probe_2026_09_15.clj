(ns fixtures-events-probe-2026-09-15
  (:require [seon.operator]
            [seon.schema]
            [seon.cluster]
            [seon.config]
            [seon.oversight]
            [seon.db]))

(let [connection (seon.operator/connection "default")
      database @connection
      projection (seon.schema/projection-from-database database)]
  (seon.schema/call-with-projection
   projection
   (fn []
     (let [requirements (#'seon.cluster/activation-requirements database)
           configuration (seon.config/effective database "default")]
       {:probe/basis (:max-tx database)
        :probe/activation-counts
        (into {} (map (fn [[k v]] [k (count v)]))
              (dissoc requirements :seon.activation/projection))
        :probe/catalog-count (count (:seon.schema.projection/catalog projection))
        :probe/ping-timeout-ms (:seon.config.flow/ping-timeout-ms configuration)
        :probe/missing-ping (seon.oversight/proc-ping :probe/absent nil)
        :probe/message-count
        (seon.db/q '[:find (count ?m) . :where [?m :seon.message/id]] database)
        :probe/old-ordinal-installed?
        (boolean (get (:schema database) :seon.cluster.message/ordinal))}))))
