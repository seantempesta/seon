(deftest runtime-schema-unregister-removes-one-unused-global-schema
  (with-cluster
    (fn [cluster]
      (let [connection (:seon.db/connection cluster)
            schema-key :shared.runtime/unregister-me]
        (with-redefs
          [ai/complete
           (fn [_]
             {:seon.ai/text
              (str
               "(require '[seon.schema :as schema])\n"
               "(schema/register! :shared.runtime/unregister-me "
               "(vector :int {:seon.db/index true}))\n"
               "(schema/unregister! :shared.runtime/unregister-me)\n"
               "(seon.run/complete \"schema removed\")")})]
          (drive-agent! cluster "agent-a" 2)
          (let [db @connection
                results
                (mapv :seon.eval/shown (agent-evaluations (db/db connection)))]
            (is (= ":shared.runtime/unregister-me" (get results 2))
                "unregister has ordinary REPL return semantics")
            ;; Ruling 47 keeps the ctx-resolvable schema identity row while
            ;; unregister retracts its definition and installed DB schema.
            (is (stable-program-identity-row?
                 (db/pull db '[*] [:seon.schema/key schema-key])
                 :seon.schema/key schema-key nil))
            (is (not (contains? (:schema db) schema-key)))
            (is (not (contains?
                      (:seon.schema.projection/forms
                       (schema/projection-from-database db))
                      schema-key))
                "the run-local projection derives absence from db-after")))))))
