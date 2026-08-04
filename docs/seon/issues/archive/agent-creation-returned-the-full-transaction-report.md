---
type: issue
status: resolved
severity: friction
tags: [issue, agent, database, render]
---

# Return a compact durable projection from agent creation

## Problem

`seon.cluster/ensure-entity!` returned Datahike's explicit-connection
transaction report. That report contains `db-before` and `db-after`, so a
single agent creation serialized the complete database value into the calling
agent's context. The research probe observed roughly 3 MB of irrelevant
result data.

## Evidence

The original probes and ugly-output reports are recorded in
[[docs/prds/sci-execution-runtime/research/session-curation-effect-visibility-2026-08-04]]
and
[[docs/prds/sci-execution-runtime/research/session-curation-replay-mechanics-opus-2026-08-04]].

Datahike defines a transaction report with `db-before`, `db-after`, tx-data,
tempids, and tx metadata in
`reference-code/datahike/src/datahike/db.cljc`. `ensure-entity!` now keeps that
system result inside the creation seam and derives exactly four useful fields
from its committed `db-after`: agent id, durable namespace, durable cluster,
and seeded bootstrap run id. Deriving from `db-after` is essential because a
second ensure resumes the existing agent unchanged rather than echoing a new
namespace from the request.

The new `:seon.cluster.agent/creation-result` schema declares the named
`seon.cluster.agent/render-creation-ai` and
`seon.cluster.agent/render-creation-html` producers. Important creation
results therefore never fall through to the generic map renderer.

## Owner

`seon.cluster/ensure-entity!` owns the atomic creation boundary;
`seon.cluster.agent` owns the creation transaction data and result renderers.
The global schema registry owns the creation-result declaration.

## Acceptance

Resolved by the path-limited fix that archives this note.

- `seon.bootstrap-test/creation-reads-one-held-plan-from-cluster-facts` proves
  first creation returns exactly the four-field projection and repeated ensure
  returns the original durable namespace.
- `seon.render-coverage-test/important-runtime-entities-declare-and-use-readable-faces`
  proves both named producers are declared on the result shape.
- `bin/test seon.config-test seon.bootstrap-test` passed 19 tests and 109
  assertions on 2026-08-04.
- The owner reported a clean isolated scratch boot of the current tree with
  agents and web ready, exercising root-agent creation through this seam.

The changed-test selector reached the separately owned slow
`seon.dev.fresh-operator-test` boundary described in the related config issue;
focused creation behavior is green. The unrelated render-coverage assertion
that caps the effective-config face at three lines still fails because that
face now includes the model registry. It is tracked separately and was not
changed here.
