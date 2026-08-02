---
type: issue
status: open
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
does not carry current run, session-image, capability, or fault semantics.

## Evidence

- `src/seon/flow.clj:723-970` explicitly describes the retained functions as
  "fake", "prototype", "fixture", or "simulates": `seeded-outcome`,
  `lineage-status`, `escalate-lineage?`, `planner-proc`,
  `namespace-owner-proc`, `source-enumerator-proc`, `indexer-proc`,
  `eval-proc`, and `mailbox-proc`.
- `test/seon/flow/loop_test.clj:1-15` calls itself the standing fake-agent
  generate-code loop and declares a raw prototype Datahike schema. It is the
  only reader of the lineage/planner/owner functions.
- `test/seon/flow_test.clj:91-210,489-599,921-981` is the only reader of the
  simulated eval/mailbox procs. `source-enumerator-proc` and `indexer-proc`
  have no reader outside their own definitions.
- The FLOW section of `resources/seon/schema.edn` retains callback, outcome, lineage,
  planner, namespace-owner, source-enumerator, indexer, eval, and mailbox
  schemas solely for this prototype closure.
- The live half must remain: `seon.flow`'s work launcher, capacity observer,
  fault committer, and error fan-out are called by current cluster/agent
  graphs. The cut boundary is the prototype tail and its exact test/schema
  readers, not the namespace wholesale.
- Mechanical residue marks the seam: `src/seon/flow.clj:18-21` imports unused
  `TimeUnit`; `src/seon/cluster/loop.cljc:35-53` requires unused
  `clojure.core.async.flow` and `seon.cluster.wake` aliases after the central
  loop deletion.

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
