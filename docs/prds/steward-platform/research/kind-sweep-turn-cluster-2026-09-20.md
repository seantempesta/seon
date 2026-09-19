---
type: research
status: blocked
created: 2026-09-20
tags: [error-model, kind-retirement, turn, cluster]
---

# Kind sweep — turn-cluster landing note

## Result

No production or schema bytes landed. Two bounded conversion probes were
rolled back through `apply_patch`; the owned source and resource paths are
byte-identical to HEAD.

## Counts

| File | Before | After |
|---|---:|---:|
| `src/seon/turn.clj` | 72 | 72 |
| `src/seon/cluster.clj` | 33 | 33 |
| `src/seon/cluster/message.clj` | 17 | 17 |
| `src/seon/cluster/agent.clj` | 17 | 17 |
| `src/seon/cluster/prompt.clj` | 10 | 10 |
| `src/seon/cluster/source.clj` | 8 | 8 |
| `src/seon/cluster/wake.clj` | 5 | 5 |
| `src/seon/agent.clj` | 13 | 13 |

Counts are literal `:seon.error/kind` occurrences measured in the working
tree after rollback.

## Verification boundaries

1. `seon.cluster.wake` cannot require the mandated
   `seon.error/diagnostic` constructor on current HEAD. The load probe
   reported the concrete cycle
   `seon.ai → seon.repl → seon.error → seon.cluster.wake → seon.render.value → seon.ai`.
   `src/seon/error.clj` is held by lane `error-family-1a`; no second
   constructor or fetch-at-call-time workaround was introduced.
2. A separate `seon.cluster.source` probe used its existing `seon.error`
   dependency and declared a kindless source refusal facet. The isolated
   fast run completed
   `concurrent-source-refresh-is-bounded-and-names-the-holder-phase`, then
   the runner stopped with exit 124 after 320 seconds without reporter
   progress in
   `stale-incremental-upsert-preserves-the-newer-publication`. The dump
   placed the main thread in recursive projection construction through
   `seon.schema/fold-contract-validations` and
   `seon.schema/build-projection`. The probe was rolled back; no red slice
   was committed.
3. The shared tree contained uncommitted work in `bin/test`, the render
   lane, and the held error/SCI files. None was edited or included in the
   path overlay.

## Facets and debt

No facet declaration landed. No new base-three debt landed. The conversion
still owes every site counted above.

## PRD section 6 stop conditions

None of the five enumerated semantic stop conditions was asserted. The
blockers are implementation and verification dependencies at held seams,
recorded above rather than misclassified as a PRD schema decision.

## Test tally

`bin/test-fast --paths src/seon/cluster/source.clj
resources/seon/schemas/seon.cluster.source.edn --
seon.cluster.source-test`: exit 124; one test completed before the suite
liveness watchdog stopped the second test. No green tally is claimed.

The orchestrator still owes the cold path gate and platform proof after the
held constructor/load cycle and projection liveness boundary are resolved
and the sweep is completed.
