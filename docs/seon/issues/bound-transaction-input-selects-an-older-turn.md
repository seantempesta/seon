---
type: issue
status: open
severity: blocker
tags: [issue, database, runtime, wave/contract-gate]
created: 2026-09-10
---

# A bound transaction input selects an older turn

## Problem

The continuation regression found a query selecting an older accepted
reply after the latest turn had returned `:wait`. There were no pending
wakes. Supplying the latest transaction as a datom-pattern input did not
constrain the result. An explicit equality predicate does constrain it.
The dependency root cause is not yet localized; do not infer a particular
optimizer owner from this symptom.

## Evidence

On Datahike gitlink `cdcb5792db8bd599487f099437265d18a31164a5`, the
supported MCP JVM probe used default's immutable database value. No
connection was transacted. At observation, turn entity 79570 had id
`3ded5c0bc8a4` and identity transaction 536872782, the latest closed turn.
The following form adds a hypothetical disposition with Datahike `with`:

```clojure
(let [original @(seon.operator/connection "default")
      database (:db-after
                (datahike.api/with
                 original [[:db/add 79570 :seon.turn/disposition :wait]]))
      latest 536872782]
  {:seon.test/bound
   (seon.db/q
    '[:find ?turn . :in $ ?agent-id ?t
      :where [?agent :seon.agent/id ?agent-id]
      [?turn :seon.turn/agent ?agent]
      [?turn :seon.turn/id _ ?t]
      [?turn :seon.turn/closed-tx _]
      [?turn :seon.turn/reply-size _]
      [?turn :seon.turn/attempts ?attempt]
      (not [?attempt :seon.ai.attempt/error _])
      (not [?turn :seon.turn/disposition _])]
    database "juniper" latest)
   :seon.test/filtered
   (seon.db/q
    '[:find ?turn . :in $ ?agent-id ?latest
      :where [?agent :seon.agent/id ?agent-id]
      [?turn :seon.turn/agent ?agent]
      [?turn :seon.turn/id _ ?t]
      [(= ?t ?latest)]
      [?turn :seon.turn/closed-tx _]
      [?turn :seon.turn/reply-size _]
      [?turn :seon.turn/attempts ?attempt]
      (not [?attempt :seon.ai.attempt/error _])
      (not [?turn :seon.turn/disposition _])]
    database "juniper" latest)})
```

Observed in 1,110 ms: `{:seon.test/bound 79286,
:seon.test/filtered nil}`. The hypothetical latest entity still pulled
with its expected identity and `:seon.turn/disposition :wait`.

Independently, the canonical armed Flow fixture had latest closed identity
transaction 536870958 (`c7572304964e`, disposition `:wait`). The first query
shape made `continuing-reply?` true and produced three provider attempts
instead of two. The closing-transaction observation reported no pending
wakes. `seon.turn/continuing-reply?` now uses the equality-predicate shape;
the recurring read-then-done regression protects this caller.

## Acceptance criteria

Reduce this to dependency-level facts, verify both Seon and raw Datahike
query paths, locate the owner, and make bound transaction inputs obey the
same constraint as the explicit predicate. Preserve result and read-evidence
semantics under both forms. No dependency files changed in loop-continue.

## Re-verified at HEAD (2026-09-15)

OPEN, CONFIRMED. Read-only MCP JVM probe on default returned in 24 ms: `{:seon.triage/subject [106420 536879236], :seon.triage/bound 106106, :seon.triage/filtered nil}`. It used a hypothetical database from `with`, with no live transaction. Exact form:

```clojure
(let [original @(seon.operator/connection "default") [eid latest] (last (sort-by second (seon.db/q '[:find ?e ?t :where [?a :seon.agent/id "juniper"] [?e :seon.turn/agent ?a] [?e :seon.turn/id _ ?t] [?e :seon.turn/closed-tx _]] original))) database (:db-after (datahike.api/with original [[:db/add eid :seon.turn/disposition :wait]]))] {:seon.triage/subject [eid latest] :seon.triage/bound (seon.db/q '[:find ?turn . :in $ ?agent-id ?t :where [?agent :seon.agent/id ?agent-id] [?turn :seon.turn/agent ?agent] [?turn :seon.turn/id _ ?t] [?turn :seon.turn/closed-tx _] [?turn :seon.turn/reply-size _] [?turn :seon.turn/attempts ?attempt] (not [?attempt :seon.ai.attempt/error _]) (not [?turn :seon.turn/disposition _])] database "juniper" latest) :seon.triage/filtered (seon.db/q '[:find ?turn . :in $ ?agent-id ?latest :where [?agent :seon.agent/id ?agent-id] [?turn :seon.turn/agent ?agent] [?turn :seon.turn/id _ ?t] [(= ?t ?latest)] [?turn :seon.turn/closed-tx _] [?turn :seon.turn/reply-size _] [?turn :seon.turn/attempts ?attempt] (not [?attempt :seon.ai.attempt/error _]) (not [?turn :seon.turn/disposition _])] database "juniper" latest)})
```

Audited HEAD `7e35df213` pins Datahike `cdcb5792db8bd599487f099437265d18a31164a5` (`git ls-tree 7e35df213 reference-code/datahike`), matching the original report. A simpler bound pattern returned one correct row; the full joined query above exposes the defect. `src/seon/turn.clj:2728` retains the equality workaround. The generic query defect remains a blocker for trustworthy generated context despite that protected caller. Fix sketch: reduce the joined transaction-input discrepancy in `reference-code/datahike/src/datahike/query.cljc`, preserve result/read-evidence semantics, and retain the workaround until verified.

surface: context-generation
