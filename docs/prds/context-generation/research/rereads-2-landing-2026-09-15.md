---
type: research
status: active
tags: [research, agent, database, test]
---

# Rereads 2 — 2026-09-15

## Grounding

Read end to end: AGENTS.md; the assigned empty-change issue; run 5's
`explain_probe_run5_2026_09_15.edn` including its complete `:text`; the
context-renders landing; the plan README and working edge. Read the
turn PRD §§13–15, the since-diff/system-turn/evaluation owners, the REPL
change grammar and `seon.db/diff`. Inspected commits `0dca8534e`,
`0c70a1cb4`, and `1f18b99fc` at their owning seams.

Dependency ledger:

- SCI's reusable context and fork: `reference-code/sci/src/sci/core.cljc:330`.
  `seon.turn/evaluate-sources` uses the real acquired agent context.
- Datahike writer transaction functions receive the current database:
  `reference-code/datahike/src/datahike/db/transaction.cljc:1152`.
  `seon.turn/system-turn` already compares its input history at that seam.
- Read plans and revisions: `seon.db/read-evidence`,
  `read-evidence-current?`, and `read-evidence-changes`; component replacement
  reuses `seon.turn/receipt-read-evidence-tx` and the existing schema codec.
- Editscript's equality and changed paths:
  `reference-code/editscript/src/editscript/diff/quick.cljc:76`.
  `seon.db/diff` and `apply-diff` already reconstruct previous shown values.
- Canonical fixture and real graph: `seon.context-blocks-fixture/submit!`,
  `seon.test-support/with-database`, and the existing loop proof.

## Rule 1: silent evidence refresh

Equality is decided on the previous reconstructed shown value and the new
shown value before recording. Silent evaluations consume no ordinal and
append neither evaluations nor system turns. Their prior evaluation keeps
its exact shown bytes and receives fresh component evidence and read basis.
The existing writer history comparison protects both refresh and append.
Previews make the same emission decision without transacting.

## Read-only run 5 measurement

Default was alive at PID 23729, prepl 54412. The requested turn
`59cf96d21042` exists and belongs to Juniper. At basis **536874142**, its
stored history contains **57 evaluations and 8 system turns**.

**Zero whole system turns would have been silent. Four empty-change
evaluations would have been omitted**, all within `e2e89497c3c2`, which
also contains two nonempty changes. The model's report correctly identifies
empty emissions; it does not establish that a whole turn was empty.

| System turn | Evaluations | Empty changes |
|---|---:|---:|
| `aa071259cfd8` | 9 | 0 |
| `88b3faaa6dd0` | 1 | 0 |
| `ed1c18ce290d` | 1 | 0 |
| `d19b85b41bbc` | 1 | 0 |
| `e2e89497c3c2` | 6 | 4 |
| `d3597309628b` | 1 | 0 |
| `594f1130d30b` | 4 | 0 |
| `404bfad994bc` | 1 | 0 |

Reproduction, JVM mode with explicit database custody (no evaluation replay):

```clojure
(let [database @(seon.operator/connection "default")
      rows (seon.eval/of-agent database "juniper")
      groups (group-by #(get-in % [:seon.cluster.eval/run :db/id]) rows)]
  (mapv
   (fn [[eid evaluations]]
     (let [turn (seon.db/pull database
                             '[:seon.turn/id :seon.turn.work/situation
                               {:seon.turn/attempts [:db/id]}] eid)
           empty-change? #(= {:seon.repl/changes {}}
                             (seon.repl/shown-value (:seon.eval/shown % "")))]
       {:seon.turn/id (:seon.turn/id turn)
        :seon.probe/system? (and (empty? (:seon.turn/attempts turn))
                                (not= :call (:seon.turn.work/situation turn)))
        :seon.probe/evaluations (count evaluations)
        :seon.probe/empty-changes (count (filter empty-change? evaluations))
        :seon.probe/silent? (every? empty-change? evaluations)}))
   groups))
```

## Verification and ownership

Verification in progress. Default is read-only for this assignment; no
stop, refork, restart, or reseed was performed. Concurrent edits were present
in agent, function, render, transcript, CSS and unrelated tests/docs. Gates
use HEAD plus only this lane's paths. No foreign session was operated.
