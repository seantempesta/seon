---
type: research
status: active
tags: [research, schema, database, runtime]
---

# Error graph — verified integration boundary, 2026-09-16

Implementation has not landed. The assignment's design-stop rule applies:
the requested writer return shape has consumers in protected `src/seon/turn.clj`.
No production definitions or schema were changed; no issue is closed.

## Authority and intended construction

Read AGENTS.md and docs/seon/issues/README.md end to end, and
docs/prds/steward-platform/plan/namespace-data-model-2026-09-16.md
§0, §7, §8 and §9 end to end. Read the structural-kill tables in
docs/prds/sci-execution-runtime/research/issue-class-mining-2026-08-11.md.
The specific assignment names §9, not one of that report's class notes;
there is no supplied class-member list to close. The related
[resolution issue](../../../seon/issues/fault-resolution-has-no-declared-fact.md)
was read end to end and remains open.

Applied skills: data-oriented-clojure, data-modeling, datahike, repl;
read clojure-testing for the required live regression boundary.

The wanted guarantee: one error identity survives JVM replacement, and
one Datahike transaction function atomically updates its per-agent,
per-turn-or-process occurrence and derives its notification recipients.

## Verified baseline

Source reference HEAD: `3c35a62127424075c2c7f585fb88e04e10c652c7` on
`steward-platform`. The shared tree has unrelated edits, including the
protected turn, program, function and schema-bridge owners; none were changed.
The live JVM is pid 69622, prepl 55914. `bin/seon status` and MCP
runtime_status answered. No stop, refork or restart was performed.

Read-only JVM queries with `(seon.db/db (seon.operator/connection "default"))`
returned 4 error entities, 4 signature holders, 0 error steward holders,
and 3 process entities (7 ms). An earlier query counted 4,709 function rows.
These are dated observations, not maintained inventories.

The installed projection returned:

```clojure
{:seon.error/occurrences [:int {:min 1}]
 :seon.error/process [:string {:min 1}]
 :seon.error/signature [:re {:seon.db/index true} "^[0-9a-f]{64}$"]
 :seon.error/throwable-class [:string {:min 1}]
 :seon.fn/sym [:string {:min 1 :seon.db/identity true
                       :seon.search/index :symbol}]}
```

The selected installed forms contained no `:seon.error/fn`,
`:seon.error/resolved-tx`, or `:seon.error.occurrence/id`.
Therefore the assignment's statement that process is already a ref is
false at this boundary. Preserve the old process text as evidence and add
the occurrence process ref as specified. Existing function identity lookup
refs currently require a string identity value; changing the whole program
identity type is not an error-owner change.

`occurrences` is not unused: error/notice writes its numeric presentation
value (`src/seon/error.clj:674`), error/log-line consumes it (`:1156`), and
problems/log-report supplies it (`src/seon/problems.clj:567`). Those readers
must change with its component-set declaration. This is within the proposed
error/problems scope (the namespace is `seon.problems`).

## The protected dependency, verified rather than inferred

At HEAD, `src/seon/turn.clj:3511` and `:3542` call
`(error/value (first recording))` in refusal-terminal-data and
settle-batch-refusal!. Working-tree lines were 3514 and 3545 when read.
Both functions require the first transaction item to be a fact map.
`src/seon/schedule.clj:454` similarly reads `(:db/id (first error-tx))`
and writes that identity to its maintenance receipt at `:458`.
The cluster committer also reads and modifies the first row
(`src/seon/cluster.clj:2544`); that region is owned by this lane.

The committed [probe](error-graph-probe-2026-09-16.clj) executes the real
commit-tx constructor with effective live configuration, then passes the
proposed first transaction item through the actual armed error/value boundary.
The complete small result was read (6 ms, `windowed? false`):

```clojure
{:first-row
 {:db/id "seon.error/fact-error-graph-read-only"
  :seon.error/process "error-graph-process-a"
  :seon.error/signature
  "e7f7a62ee3106175521bf78c2fe44cbe4e05953a9f368973b568d7bee3c3803d"}
 :process-changes-signature true
 :steward-holders 0
 :transaction-function-value
 {:seon.error/kind :seon.instrument/contract-violated
  :seon.error/message
  "seon.error/value refused fact at []: expected a map, got a vector. Fix: Supply a map at []. Contract: :seon.error/fact."}}
```

The probe transacts nothing. It proves the return-shape dependency and the
current process-dependent signature; it does not prove the future writer.
An initial larger observation was elided by MCP; the smaller repeated probe
above provides the complete relevant result. An attempted `*1` follow-up
did not recover that prior result and is not evidence.

Required caller repair: obtain the flat error value and stable ref from
preparation, independently of the transaction vector; submit commit-tx only
through the writer. Update both turn callers and maintenance settlement with
that one contract. Do not teach error/value to execute transaction vectors
or preserve a second recurrence implementation.

## Dependency ledger

- `src/seon/id.clj:30`: id hashes pr-str; sorted-map input supplies canonical
  attribute ordering. No new hash implementation is needed.
- `reference-code/datahike/src/datahike/db/transaction.cljc:1152`:
  `:db.fn/call` invokes its function with the transaction database.
- `src/seon/program.cljc:761`: declaration-row is the existing admission
  owner; `src/seon/sci/eval.clj:430` demonstrates its use. A stub admission
  contract must be verified there before inventing any error-local minting.
- `src/seon/flow.clj:126`: var-process receives the step Var;
  `src/seon/cluster/agent.clj:422` builds graph definitions. No durable
  proc-keyword → function ref declaration was found in the Flow schema.
  The [missing relation issue](../../../seon/issues/flow-error-proc-has-no-declared-step-function-ref.md)
  records this residual; no function is guessed from a keyword.

## Exactly three integration options

Estimates are engineering estimates, not measured durations.

1. **Coordinate the caller contract first — recommended; 3–5 engineer-hours
   for the complete change and verification.** Have the turn owner change
   its two consumers, include maintenance settlement, then land the four
   requested slices. Guarantee: one writer decides identity upsert, counts
   and notifications. Give up independent landing while turn.clj is protected.
2. **Retain a leading prepared map plus one transaction call; 3–5 hours.**
   Preserve map-reading callers and move mutable count/routing decisions
   into the call. Guarantee: atomic occurrence counts with the old outer
   return shape. Give up the requested single-call transaction vector;
   the precise ownership of preparation and identity must be ruled first.
3. **Land only identity/schema preparation; 1–2 hours.** Keep the present
   writer until caller ownership is available. Guarantee: only the parts
   proved in that partial slice. Give up structural closure, atomic counts,
   reader conversion and issue closure in this assignment.

The owner decision was requested; no option has been assumed approved.

## Verification and closure boundary

No production function changed, so no changed-form installation or in-process
regression run occurred. No test JVM, bin/test, or bin/test-fast was launched.
No gate passed and no live post-adoption proof exists. The gate-request file
records NOT READY rather than requesting a misleading green check.
RESET NEEDED is not asserted: schema adoption has not been attempted.

The resolution member remains open: its missing attribute was verified,
and neither a resolution writer nor its two-state render regression exists
from this work. The new Flow relation issue remains open. The error identity,
occurrence writer and notification conversion remain unimplemented.
No background shell or scratch cluster was created.

Documentation hook boundary: the Markdown hook reported 12 issues, with
visible errors citing dependency gitlinks in
`docs/prds/context-generation/research/agents-md-audit-2026-09-15.md`.
Its output was elided, so this note does not classify all 12. That foreign
document was preserved. The probe's initial missing-require lint findings
were corrected with explicit requires; the final exact form was replayed
through MCP and again returned the complete result in 6 ms.
`git diff --check` reported no whitespace errors.
The orchestrator must add the new Flow issue to its owned issue index.
