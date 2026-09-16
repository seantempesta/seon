---
type: research
status: active
tags: [research, test, runtime, class/p3]
---

# Turn test reds — batch 19 continuation

Started 2026-09-16 02:36 UTC, bounded to 04:06 UTC, on
`steward-platform` at `5c9135c077f1d8124f8a4da252866743aa64cd32`.
The batch-19 report names 19 tests and 60 FAIL/ERROR blocks. This is a
continuation of [the earlier class work](turn-test-reds-2026-09-16.md),
not a claim that the namespace is green.

Read the batch-19 report end to end, AGENTS.md, issues README, the active
roadmap, and the previous landing record. The earlier record lists the
class-mining authority and member notes read end to end. Applied
data-oriented-clojure, repl, clojure-testing, datahike, seon-context-config,
and llm-providers skills.

Default PID 7595 remained alive. Its MCP status again refused an occurrence
count; direct read-only JVM evaluation and explicit default custody worked.
The [existing tool issue](../../../seon/issues/runtime-status-refuses-error-occurrence-count.md)
records the complete refusal boundary. Default was never restarted or reforked.

Fresh-base construction temporarily replaces fixture delays. To avoid replacing
those roots underneath other lanes in default, the serial MCP probes use an
isolated HEAD worktree and development JVM, cluster `turn-test-reds19`.
No test-runner JVM, `bin/test`, or `bin/test-fast` is used. Each test uses
the canonical database fixture, a fresh branch and SCI context, and armed
contracts. The final cold/platform gate belongs to the orchestrator.

## Class table

Pending the serial fresh-base census and candidate probes.

## Dependency ledger and boundary

Error identities and occurrence recording are owned by `seon.error/recording`
and its writer function `commit-call` (`src/seon/error.clj:1365`, `:1288`).
`latest-fact` is the existing diagnostic projection (`:1437`). The turn
recorder composes their transaction data and must return committed evidence
from the transaction report (`src/seon/turn.clj:3747`). Datahike's transaction
report supplies `:db-after`; no second evidence registry is needed.

All explicitly protected owners remain untouched. Shared uncommitted program,
schema, test-runner and issue edits are preserved. The snapshot excludes them;
its result is an isolated verification boundary, not proof of default adoption.


## Slice 1 — occurrence evidence

**Guarantee:** the attempt recorder returns the error owner's prepared
complete diagnostic only after committing that same recording; transaction
identity rows are never interpreted as diagnostics.

The final candidate uses `error/recording` directly and retains its descriptor,
including its transaction data and prepared fact. The failed intermediate
candidate read back raw refs; the armed notice contract correctly refused a
pulled map where the diagnostic requires a ref. That candidate was discarded.
The final change adds no projection owner or fallback.

The shared `durable-fact` test observer now acquires occurrences through the
existing `error/latest-fact` projection. Cold acquisition makes the same
observation directly. Partial truncation and reasoning-only failures retain
their exact payload assertions. Backup still asserts exactly two provider calls,
unchanged prompt, the committed diagnostic's projection, and linked attempts.

Exact in-process test set (each passed before the edit and after adoption):

- `seon.cluster.turn-test/a-partial-stream-truncation-is-a-durable-nonfailure-attempt-fact`: 6/0/0.
- `seon.cluster.turn-test/reasoning-only-time-limit-persists-its-flat-diagnostic`: 9/0/0.
- `seon.cluster.turn-test/an-unpaid-failure-with-a-backup-makes-exactly-two-calls`: 15/0/0.
- `seon.sci.eval-test/one-unloadable-row-cannot-prevent-cold-acquisition`: 6/0/0.

Counts are pass/fail/error assertions. Final candidate run refs: 44630–44633.
Adopted run refs: 48355–48358. Source adoption:
`6aaa0591-f886-5b55-ac01-4c9d24d84214`; snapshot advanced to committed
`9c03c1ec1` before overlay/adoption, preserving the newly landed call-edge work.
The full baseline is 19 tests, **64 passes / 49 failures / 13 errors**.
No test JVM was launched.

[Probe](turn-test-reds-batch19-probe-2026-09-16.clj),
[baseline](turn-test-reds-batch19-baseline-2026-09-16.edn),
[candidate](turn-test-reds-batch19-occurrence-candidate2-2026-09-16.edn),
[adopted results](turn-test-reds-batch19-occurrence-adopted-2026-09-16.edn),
[complete recorder values](turn-test-reds-batch19-occurrence-values-2026-09-16.edn).

Default's hook queued publication; its observed source at 02:59 UTC was still
`6aa9fe1b-203b-5ca1-bea2-9047ea996105`. The passing adoption claim is for the
isolated development JVM, not default. Existing global markdown lint reports
12 unrelated gitlink-citation errors in the agents-md audit note.
