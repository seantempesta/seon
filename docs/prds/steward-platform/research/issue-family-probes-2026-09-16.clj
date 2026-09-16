{:seon.dev.mcp/root "/Users/sean/src/seon"
 :seon.dev.mcp/cluster "default"
 :seon.dev.mcp/mode :jvm
 :seon.dev.mcp/read-only true
 :seon.issue.research/forms
 [(let [d @(seon.operator/connection "default")
        p (seon.schema/projection-from-database d)]
    {:issue-declared? (boolean (malli.registry/-schema
                               (:seon.schema.projection/registry p)
                               :seon.issue/id))
     :retraction-preflight
     (#'seon.db/write-error
      d p [[:db/retract [:seon.agent/id "root"] :seon.agent/plan]])
     :basis (seon.db/basis-t d)
     :source-roots seon.cluster/source-roots
     :verified-arglists (:arglists (meta #'seon.test/verified?))})
  (let [d @(seon.operator/connection "default")]
    {:creation
     (seon.cluster.agent/creation-tx
      {:seon.agent/id "issue-family-probe"
       :seon.ns/name 'my.agents.issue-family-probe
       :seon.cluster/name "default"})
     :opening
     (seon.turn/generated-run-tx
      d {:seon.agent/id "issue-family-probe"
         :seon.turn/id "issue-family-probe"
         :seon.db.process/id "probe"
         :seon.turn/opened-tx "datomic.tx"
         :seon.turn/starting-ns [:seon.ns/name 'my.agents.issue-family-probe]})})]}
