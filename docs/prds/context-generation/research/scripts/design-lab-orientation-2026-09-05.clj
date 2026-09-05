(do
  (require 'seon.cluster 'seon.operator 'seon.db 'seon.sci.kernel
           'seon.schema 'malli.core)
  (let [instance (#'seon.cluster/mcp-instance "default")
      connection (seon.operator/connection "default")
      database (seon.db/db connection)
      projection (seon.sci.kernel/context-projection (:seon.sci.eval/ctx instance))
      contract [:function [:=> [:cat :int] :int]
                          [:=> [:cat :string :string] :string]]
      trial (assoc-in projection
                      [:seon.schema.projection/function-contracts 'user/example]
                      contract)
      accepts (seon.schema/function-accepts-in? trial 'user/example [7])
      returns (seon.schema/function-returns-in? trial 'user/example :string)
      arities (malli.core/-function-schema-arities
               (malli.core/function-schema contract))]
  {:seon.research/basis (seon.db/basis-t database)
   :seon.research/commit (seon.db/commit-id database)
   :seon.research/source-commit (get instance :seon.source/commit-id :seon.research/not-observed-at-this-path)
   :seon.research/agents
   (seon.db/q '[:find ?id ?ns :where
                [?a :seon.cluster.agent/id ?id]
                [?a :seon.cluster.agent/namespace ?n]
                [?n :seon.ns/name ?ns]] database)
   :seon.research/plan-identities
   (seon.db/q {:query '[:find ?id :where [?e :my.plan.item/id ?id]]
               :args [database] :limit 1})
   :seon.research/root-attributes
   (seon.db/q '[:find [?attr ...] :where
                [?e :seon.cluster.agent/id "root"] [?e ?attr _]] database)
   :seon.research/arity-check
   {:seon.research/accepts accepts
    :seon.research/returns returns
    :seon.research/joint-match
    (boolean (some (fn [arity]
                     (let [{:keys [input output]} (malli.core/-function-info arity)]
                       (and ((malli.core/validator input) [7])
                            (= :string (malli.core/form output)))))
                   arities))}}))
