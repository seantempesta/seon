(let [database (seon.db/db (seon.cluster.boot/connection "default"))]
  {:namespaces (mapv #(seon.db/pull database [:seon.ns/name :seon.ns/source :seon.program/definition-digest] %) [48031 48032])
   :tx (seon.db/pull database '[*] 536870928)})
