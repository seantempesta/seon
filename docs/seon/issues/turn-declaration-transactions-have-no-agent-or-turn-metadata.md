---
type: issue
status: open
severity: friction
created: 2026-09-17
tags: [issue, program-graph, provenance, turn]
---

# Turn declaration transactions have no agent or turn metadata

The S1 assignment says to preserve agent/turn provenance in transaction
metadata. Its live Juniper proof found that the declaration transaction
carries only `:db/txInstant`. Agent attribution remains reachable through
the turn and its evaluations, and declarations carry admission source
`:agent`, but the transaction itself does not name that agent or turn.

On default PID 53320, turn `404bfad994bc` closed at transaction 536871253.
The declaration `my.agents.juniper.s1/caller` was asserted at that transaction.
This query returned exactly one tuple:

```clojure
(seon.db/q
 '[:find ?tx ?attribute ?value
   :where
   [?f :seon.fn/sym "my.agents.juniper.s1/caller"]
   [?f :seon.fn/source _ ?tx]
   [?tx ?attribute ?value]]
 database)
;; #{[536871253 :db/txInstant #inst "2026-09-16T18:25:35.697-00:00"]}
```

The existing settlement transaction in `src/seon/turn.clj`'s
`settle-batch!` submits `{:tx-data ...}` without `:tx-meta`.
S1 changes neither that function nor the database transaction owner, so
this is an observed existing boundary, not evidence that source analysis
removed metadata. The follow-up should put provenance at the transaction
owner and prove it with the canonical turn fixture.
