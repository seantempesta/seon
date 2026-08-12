---
type: issue
status: resolved
severity: friction
tags: [issue, deletion, flow, testing]
---

# Delete Flow prototype procs beside the live agent graphs

## Problem

The tail of `seon.flow` still implements the pre-runtime fake-agent testbed:
seeded outcomes, lineage escalation, a fake planner, fake namespace owners,
fixture source/index procs, a simulated eval proc, and a generic mailbox.
Current agent execution instead lives in per-agent graphs under
`seon.cluster.agent`, but tests and schema rows keep the prototype public and
green beside that owner.

This is the old-model shape surviving as fresh code. It enlarges the indexed
program graph and lets runtime work accidentally cite a test mechanism that
does not carry current run, agent defs, capability, or fault semantics.

## Evidence

- `src/seon/flow.clj:947-1184` explicitly describes the retained functions as
  "fake", "prototype", "fixture", or "simulates": `seeded-outcome`,
  `lineage-status`, `escalate-lineage?`, `planner-proc`,
  `namespace-owner-proc`, `source-enumerator-proc`, `indexer-proc`,
  `eval-proc`, and `mailbox-proc`.
- `test/seon/flow/loop_test.clj:1-15` calls itself the standing fake-agent
  generate-code loop and declares a raw prototype Datahike schema. It is the
  only reader of the lineage/planner/owner functions.
- `test/seon/flow_test.clj:127-208,1473-1530` is the only other reader of the
  simulated eval/mailbox procs. `source-enumerator-proc` and `indexer-proc`
  have no reader outside their own definitions.
- `resources/seon/schemas/seon.flow.edn:1-220` retains outcome, lineage,
  planner, namespace-owner, source-enumerator, indexer, eval, and mailbox
  schemas solely for this prototype closure.
- The live half must remain: `seon.flow`'s work launcher, capacity observer,
  fault committer, and error fan-out are called by current cluster/agent
  graphs. The cut boundary is the prototype tail and its exact test/schema
  readers, not the namespace wholesale.
- The fake-agent suite alone is 707 lines, and the source tail is 238 lines
  before its schema and extra test-only readers are counted.

## Owner

The current per-agent Flow graph owner in `seon.cluster.agent`, with
`seon.flow` retaining only shared launch/fault mechanisms actually called by
those graphs.

## Acceptance

- Delete the prototype tail, its fake-agent loop suite, the simulated
  eval/mailbox-only test sections, and every schema entry that has no
  surviving source consumer.
- Preserve and rerun the tests for the live work launcher, bounded compute
  submission, capacity observation, fault fan-out, and hot-reloaded Var
  behavior against current agent graphs.
- The fresh program graph contains no public fake/prototype/fixture proc, and
  `rg` finds no `planner-proc`, `namespace-owner-proc`,
  `source-enumerator-proc`, `indexer-proc`, `eval-proc`, or `mailbox-proc`.
- Clojure lint reports no unused import/require at the cut seam.

## Resolution

The audit-finding unit deleted the 238-line production tail, the 707-line
fake-agent suite, 85 lines of prototype-only schema, and the dependent
prototype sections of `seon.flow-test`. Current fault fan-out tests now inject
ordinary Flow error/report values directly, and the Flow Monitor proof uses the
production work-launcher graph. Focused proof: `bin/test seon.flow-test` ran
17 tests / 172 assertions with zero failures and zero errors. The path-limited
implementation commit is `56aaee5e3`.
