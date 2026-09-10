---
type: research
status: active
tags: [context, render, documentation, testing]
---

# Context nits — 2026-09-09

Read the named issue and chart PRD end to end, including §9.4. AGENTS.md
has no §10 heading: read its opening verbatim turn-PRD §10 lane rules.
Read the roadmap entry and working edge and the Clojure, REPL, testing,
and Datastar skills. This bounded assignment has no delegated agents.

## Dependency ledger

Datahike's pull implementation at
`reference-code/datahike/src/datahike/pull_api.cljc:246` returns absent
pull results as nil. The existing first-party owners are
`src/seon/note.clj:61` and `src/seon/render/transcript.clj:1077`.
SCI's macro vars and namespace installation own `doc` and `dir` through
`src/seon/sci/eval.clj`'s existing program-documentation projection;
`reference-code/sci/src/sci/core.cljc:260` owns interning. Contract
reports originate in `src/seon/instrument.clj:293` and cross the one
`src/seon/sci/eval.clj` evaluation boundary before value rendering.

## Slice 1

The trigger selector names message id, content, and the sender's agent id.
The notes form extracts the reverse edge with `[]` as the empty value.
The canonical database fixture executes both generated forms through real
SCI with armed contracts, then adds a note and executes the same read.
Exact source and shown bytes are in
[the capture](context-nits-slice1-bytes-2026-09-09.txt).

Path-isolated gate: 6 tests / 176 assertions, zero failures or errors.
Separate path-isolated platform gate: 84 tests / 505 assertions, green.
`SEON_TEST_WORKERS=3`; no full suite. Capture rerun: 1 test / 8 assertions.
The loop regression's expected runtime form was updated with its selector.

Live initial MCP JVM probe returned the bare sender `#:db{:id 36216}`
and nil notes in 1128 ms. Default PID remains 83040; no lifecycle operation
was performed. Adoption and final page observations are recorded below.

## Verification boundaries

MCP runtime status timed out with health and Flow unknown; JVM evaluation
answered. Recorded in the existing
[component-probe issue](../../../seon/issues/default-component-probe-times-out-after-adoption.md).
CUA reported no available browser. The assigned debug URL's served HTML
was fetched successfully; this proves HTTP content, not browser paint.
The evidence lane's `src/seon/db.clj` and `test/seon/read_evidence_test.clj`
edits are excluded from these gates and untouched. An existing shared
publication held the operator lifecycle lock; no foreign session was
operated. Untracked build/, workers/, and config/virtual-turns.edn were
preserved.
