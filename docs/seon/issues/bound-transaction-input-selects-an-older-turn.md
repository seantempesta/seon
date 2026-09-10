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
