(do
  (require 'seon.cluster 'seon.operator 'seon.db 'seon.env
           'seon.sci.kernel 'sci.core)
  (let [instance (#'seon.cluster/mcp-instance "default")
        connection (seon.operator/connection "default")
        database (seon.db/db connection)
        ctx (:seon.sci.eval/ctx instance)
        environment (seon.env/of ctx)
        fork (sci.core/fork ctx)
        carriers [:seon.sci.kernel/program-snapshot
                  :seon.sci.kernel/installed-functions
                  :seon.sci.eval/projection-state]]
    {:seon.research/basis (seon.db/basis-t database)
     :seon.research/commit (seon.db/commit-id database)
     :seon.research/environment-present? (seon.env/environment? environment)
     :seon.research/connection-matches?
     (identical? connection (:seon.db/connection environment))
     :seon.research/projection-matches?
     (identical? (:seon.schema/projection environment)
                 (seon.sci.kernel/context-projection ctx))
     :seon.research/sci-env-distinct? (not (identical? (:env ctx) (:env fork)))
     :seon.research/carriers
     (mapv (fn [carrier-key]
             {:seon.research/key carrier-key
              :seon.research/present? (some? (get ctx carrier-key))
              :seon.research/shared? (identical? (get ctx carrier-key)
                                               (get fork carrier-key))})
           carriers)}))
