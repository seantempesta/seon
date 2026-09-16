---
type: research
status: active
tags: [schema, reset, deletion, program-graph]
---

# Reset deletion contract: correction and consumer boundary

Read all three named authorities end to end: the revised
[program-graph study](../../steward-platform/research/deletion-semantics-program-graph-2026-09-16.md),
[agents/turns study](../../steward-platform/research/deletion-semantics-agents-and-turns-2026-09-16.md),
and [schema recommendations](../../steward-platform/research/reset-schema-recommendations-2026-09-16.md).
The [single reset plan](../../steward-platform/plan/reset-batch-2026-09-17.md)
now replaces universal living-ref enforcement/fork prerequisites with the
owner's value-edge program deletion refusal and folds in X1–X7. It preserves
issue status, separates nonstored contract work, specifies actual codec shapes,
tuple type assertions and pull limits, and retains G4's required provenance
datom. The post-reset deletion proof now expects refusal before repair.

No production implementation is claimed. At HEAD
`2ee83761db526af9dec990024ba8322dd92045a9`, the released schema files are clean,
but modified/held `src/seon/test/selection.clj:115–125` still excludes every
non-vector edge from `row-edges`. Symbol producers therefore require that
consumer change in the same publication. The HEAD consumer has the same
restriction: an owned-paths snapshot would isolate edits but not repair this
dependency. The plan records the exact required edit and the SCI/publication
origin boundaries. No foreign file or session was edited or operated.

Dependency ledger read directly: Datahike
`reference-code/datahike/src/datahike/db/transaction.cljc:998–1015`
sweeps incoming refs, and `:1206–1216` rejects a non-nil final-report diagnostic
atomically. `src/seon/db.clj:3009–3041` already owns that callback and computes
affected eids from attempted and effective datoms. Symbol edges preserve the
final-state evidence that sweep would erase. No fork extension is needed.

Read-only MCP query below returned in 5 ms, without windowing: both types are
`:db.type/ref`; the five callers are seon.turn/recover-call, receipt-run,
render-ai, require-open-run, open-run-tx-call; 67 namespaces require seon.turn.
These are baseline observations, not implementation proof.

```clojure
(let [database (seon.db/db (seon.operator/connection "default"))]
  {:calls-type
   (:db/valueType
    (seon.db/pull database [:db/valueType] [:db/ident :seon.fn/calls]))
   :callers
   (seon.db/q '[:find [?name ...] :in $ ?target
                :where [?f :seon.fn/sym ?target]
                [?c :seon.fn/calls ?f] [?c :seon.fn/sym ?name]]
              database "seon.turn/open?")
   :requires-type
   (:db/valueType
    (seon.db/pull database [:db/valueType] [:db/ident :seon.ns/requires]))
   :requires-count
   (seon.db/q '[:find (count ?n) . :in $ ?name
                :where [?target :seon.ns/name ?name]
                [?n :seon.ns/requires ?target]]
              database 'seon.turn)})
```

Default PID 41413 stayed alive. No write, restart, reset, bin/test or fast
iteration was performed for this documentation correction. RESET NEEDED remains
the eventual type-change boundary. Markdown hook reports 30 pre-existing
gitlink-citation errors in the historical agents-md audit; they are outside
the two owned documents. No new scratch root or background test process exists.
