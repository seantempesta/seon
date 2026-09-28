(do (require '[seon.schema.datahike :as schema.datahike]) (def ^:dynamic *receipt* nil) (def ^:dynamic *open* nil) (def provenance-forms #:seon.db.transaction
{:process [:string {:min 1 :seon.db/index true
                    :description "Observed writer process generation (pid and start instant); retained after the process exits."}]
 :operation [:qualified-symbol {:seon.db/index true
                               :description "Caller-named operation, first named transaction function, or seon.db/transact! for an unnamed plain write."}]
 :agent [:string {:min 1 :seon.db/index true
                  :description "Observed calling agent id; absent for a system write with no agent world."}]}
)
(defn stamp-transaction
  "Attach writer observations once, including the first schema-installing write.
  Process and agent names are historical observations, not living entity refs.
  A caller may name its operation; otherwise the first named transaction function
  identifies it, or `transact!` does. No agent is invented for a system caller."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.store/transaction
                       [:maybe :map] :seon.db.process/id] :seon.store/transaction]}
  [database transaction world process-id]
  (let [request (if (map? transaction) transaction {:tx-data transaction})
        attributes [:seon.db.transaction/process :seon.db.transaction/operation :seon.db.transaction/agent]
        missing (filterv #(nil? (get (datahike.db.interface/-schema database) %)) attributes)
        forms (when (seq missing) (select-keys provenance-forms attributes))
        projection (when forms (schema/declaration-projection forms))
        declarations (when forms
                       (into (schema.datahike/malli->datahike-schema-in projection missing)
                             (when (get (datahike.db.interface/-schema database) :seon.schema/key)
                               (schema/canonical-schema-entities projection forms))))
        operation (or (:seon.db.transaction/operation request)
                      (some (fn [op]
                              (when (and (vector? op) (= :db.fn/call (first op)))
                                (let [f (second op)]
                                  (cond (qualified-symbol? f) f
                                        (var? f) (symbol (str (ns-name (:ns (meta f)))) (str (:name (meta f))))))))
                            (:tx-data request))
                      'seon.db/transact!)
        agent (or (:seon.db.transaction/agent request) (:seon.agent/id world))
        stamp (cond-> {:db/id :db/current-tx
                        :seon.db.transaction/process process-id
                        :seon.db.transaction/operation operation}
                agent (assoc :seon.db.transaction/agent agent)
                *receipt* (assoc :seon.db/receipt *receipt*))]
    (assoc request :tx-data (conj (into (into (vec declarations) (when *receipt* *open*))
                                       (:tx-data request)) stamp))))


:ready)