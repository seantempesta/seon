; Run forms separately through MCP JVM mode on default. These forms neither
; restart default nor submit a provider request. The full-acquisition probe
; exceeded the MCP 60000 ms bound; it is not a completed timing result.
(comment
  (let [database (seon.db/db (seon.operator/connection "default"))
        projection (seon.schema/projection-from-database database)]
    (seon.schema/call-with-projection
     projection
     (fn []
       (let [start (System/nanoTime)
             ctx (seon.sci.eval/build-base-ctx)]
         {:s3/minimal-ms (/ (- (System/nanoTime) start) 1e6)
          :s3/agent-rows
          (seon.db/q '[:find ?sym :where
                       [?f :seon.fn/sym ?sym]
                       [?f :seon.schema.admission/source :agent]] database)
          :s3/ns-row (seon.db/pull database '[*] [:seon.ns/name 'seon.id])
          :s3/functions
          (seon.db/q '[:find (count ?f) . :where [?f :seon.fn/source _]]
                    database)}))))

  (let [database @(seon.operator/connection "default")
        projection (seon.schema/projection-from-database database)]
    (seon.schema/call-with-projection
     projection
     (fn []
       (let [start (System/nanoTime)
             minimal (seon.sci.eval/build-base-ctx)
             minimal-ms (/ (- (System/nanoTime) start) 1e6)
             start (System/nanoTime)
             acquired (seon.sci.eval/cluster-ctx database)]
         {:s3/minimal-ms minimal-ms
          :s3/acquisition-ms (/ (- (System/nanoTime) start) 1e6)
          :s3/function-count
          (seon.db/q '[:find (count ?f) . :where [?f :seon.fn/source _]] database)
          :s3/agent-rows
          (seon.db/q '[:find ?sym :where [?f :seon.fn/sym ?sym]
                       [?f :seon.schema.admission/source :agent]] database)}))))

  ; Load only the owned new Var; this is not development adoption.
  (let [database (seon.db/db (seon.operator/connection "default"))
        projection (seon.schema/projection-from-database database)
        form
        (with-open [r (java.io.PushbackReader.
                       (clojure.java.io/reader "src/seon/program.cljc"))]
          (loop []
            (let [form (read {:read-cond :allow :features #{:clj} :eof ::eof} r)]
              (cond
                (= ::eof form) (throw (ex-info "Owned overrides definition absent" {}))
                (and (seq? form) (= 'defn (first form)) (= 'overrides (second form))) form
                :else (recur)))))]
    (seon.schema/call-with-projection
     projection
     (fn []
       (binding [*ns* (the-ns 'seon.program)] (eval form))
       {:s3/proof-surface :hot-reloaded-owned-jvm-var
        :s3/overrides ((resolve 'seon.program/overrides) database)
        :s3/basis-t (seon.db/basis-t database)}))))


; PID 33583, after the owner's reset. Execute each form separately.
; JVM setup/observation forms are separate from single-evaluation source turns.
(comment
  (let [d (seon.db/db (seon.operator/connection "default"))
        p (or (seon.db/carried-projection d)
              (seon.schema/projection-from-database d))]
    (seon.schema/call-with-projection
     p
     (fn []
       (let [start (System/nanoTime)
             ctx (seon.sci.eval/build-base-ctx)]
         {:s3/basis (:max-tx d)
          :s3/build-ms (/ (- (System/nanoTime) start) 1e6)
          :s3/context? (map? ctx)
          :s3/functions
          (seon.db/q '[:find (count ?f) . :where [?f :seon.fn/source]] d)}))))

  ; Repeat creation separately for b; create c only AFTER acceptance.
  (let [c (seon.operator/connection "default")
        r (seon.db/transact!
           c
           (seon.cluster.agent/creation-tx
            {:seon.cluster/name "default" :seon.agent/id "s3-provenance-a"
             :seon.ns/name 'my.agents.s3-provenance-a}))]
    (if (:seon.error/kind r) r {:s3/created true}))

  ; Baseline once for a and once for b establishes retained contexts.
  (let [i (get @seon.operator.runtime/running-instances "default")]
    (seon.turn/virtual-turn!
     {:seon.turn.loop/cluster (:seon.turn.loop/cluster i)
      :seon.agent/routing (:seon.agent/routing i)
      :seon.agent/id "s3-provenance-b"
      :seon.cluster.reply/text "(seon.id/valid? 8 \"s3-probe\")"}))

  ; The valid? attempt was refused by unresolved test bindings. The accepted
  ; probe used uuid-text, whose normal graph-derived gate set was empty.
  (let [c (seon.operator/connection "default") d (seon.db/db c)]
    (def s3-original-uuid
      (seon.db/pull d '[*] [:seon.fn/sym "seon.eval.drive/uuid-text"]))
    (seon.db/transact!
     c [[:db/add [:seon.agent/id "s3-provenance-a"]
         :seon.agent/namespace [:seon.ns/name 'seon.eval.drive]]]))

  (let [i (get @seon.operator.runtime/running-instances "default")]
    (seon.turn/virtual-turn!
     {:seon.turn.loop/cluster (:seon.turn.loop/cluster i)
      :seon.agent/routing (:seon.agent/routing i)
      :seon.agent/id "s3-provenance-a"
      :seon.cluster.reply/text
      "(defn- uuid-text {:malli/schema [:=> [:cat] :string]} [] \"s3-accepted-database-definition\")"}))

  ; MCP SCI mode, shared base, one evaluation:
  (seon.eval.drive/uuid-text)

  ; Ordinary next turn for b, and first turn for newly created c.
  (let [i (get @seon.operator.runtime/running-instances "default")]
    (seon.turn/virtual-turn!
     {:seon.turn.loop/cluster (:seon.turn.loop/cluster i)
      :seon.agent/routing (:seon.agent/routing i)
      :seon.agent/id "s3-provenance-b"
      :seon.cluster.reply/text "(seon.eval.drive/uuid-text)"}))

  ; The observed source-tx was 536870917. Current core, inferred agent.
  (let [d (seon.db/db (seon.operator/connection "default"))
        t (seon.db/q '[:find ?t . :where
                      [?f :seon.fn/sym "seon.id/valid?"]
                      [?f :seon.fn/source _ ?t]] d)]
    {:s3/core-source-tx t
     :s3/inferred (seon.schema/admission-from-asserting-transaction d t)
     :s3/current (seon.db/pull d '[:seon.schema.admission/source]
                               [:seon.fn/sym "seon.id/valid?"])})

  ; Cleanup is explicit; it does not prove automatic database reversion.
  (let [c (seon.operator/connection "default") d (seon.db/db c)]
    (seon.db/transact!
     c
     (seon.program/exact-replacement-tx
      (seon.db/pull d '[*] [:seon.fn/sym "seon.eval.drive/uuid-text"])
      s3-original-uuid)))

  (let [i (get @seon.operator.runtime/running-instances "default")
        base (:seon.sci.eval/ctx i)
        v (ns-resolve 'seon.eval.drive 'uuid-text)]
    (sci.core/add-namespace!
     base 'seon.eval.drive
     {'uuid-text (sci.core/copy-var* v (sci.core/create-ns 'seon.eval.drive))})
    (doseq [id ["s3-provenance-a" "s3-provenance-b" "s3-provenance-c"]]
      (seon.cluster.agent/acquire-context! (:seon.turn.loop/cluster i) id))
    {:s3/cleanup :restored-jvm-root-through-existing-base-diff}))
