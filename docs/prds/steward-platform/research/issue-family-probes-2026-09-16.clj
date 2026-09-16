{:seon.dev.mcp/root "/Users/sean/src/seon"
 :seon.dev.mcp/cluster "default"
 :seon.dev.mcp/mode :jvm
 :seon.dev.mcp/read-only true
 :seon.issue.research/forms
 '[(let [d @(seon.operator/connection "default")
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

;; Execution probes. Quoted forms are replay instructions, not load-time work.
{:seon.issue.research/commit "fe9aeb336"
 :seon.issue.research/forms
 '[(let [c (seon.operator/connection "default")]
     (seon.test/run
      #'seon.cluster.source-test/latest-test-evidence-survives-rebuilding-from-an-older-base
      c {:seon.test/remaining-ms 180000
         :seon.test.run/provenance (seon.test.runner/provenance (seon.db/db c))}))
   (let [c (seon.operator/connection "default")]
     (seon.test/run
      #'seon.cluster.source-test/incremental-first-party-publication-retains-complete-scalar-rows
      c {:seon.test/remaining-ms 120000
         :seon.test.run/provenance (seon.test.runner/provenance (seon.db/db c))}))
   (let [d (seon.db/db (seon.operator/connection "default"))]
     (seon.db/pull d
      '[:seon.ns/name {:seon.ns/steward [:seon.agent/id]}]
      [:seon.ns/name 'seon.render.web]))
   (let [d (seon.db/db (seon.operator/connection "default"))]
     (seon.db/q '[:find [?id ...] :in $ ?name
                  :where [?n :seon.ns/name ?name] [?f :seon.fn/ns ?n]
                         [?i :seon.issue/functions ?f] [?i :seon.issue/id ?id]]
                d 'seon.render.web))
   (my.issue/status {:seon.issue/id "d1f11894d81f"})]
 :seon.issue.research/virtual-turn "d46823b798c6"
 :seon.issue.research/opening "b93f168916f7"
 :seon.issue.research/paid-agent "856c73b784fb"}
