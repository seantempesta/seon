---
type: issue
status: open
severity: friction
tags: [issue, program-graph, test-selection, performance, seon.fn]
---

# gate-set re-derives the declared-reference population on every call

## Problem

`seon.fn/gate-set` (`src/seon/fn.clj:1293`) — the one reach derivation
`tests-reaching` and the gate's test selection share — calls
`declared-reference-edges` (`src/seon/fn.clj:1280`) on every invocation
(`src/seon/fn.clj:1311`). That is a whole-database Datalog query over every
declaration naming `:seon.fn/reference-to`, re-run per asked function, whose
answer does not depend on the function asked. The per-function walk that
follows is cheap by comparison.

Measured on cluster `default`, 2026-09-17 (basis `:t` 536871793, 5030
declarations, 1196 public source-bearing):

| call | wall time |
|---|---|
| `tests-reaching` once | ~5–16 ms |
| `seon.issue.detect/public-without-reaching-test` over 1091 `src` candidates | **17 s** |
| `seon.issue.detect/public-without-reaching-test` over 1196 candidates | **18 s** |

For contrast, the recursive-Datalog equivalent `seon.fn/functions-without-tests`
(`src/seon/fn.clj:1388`) took **111 s** on the same database, so the walk is
already the right shape — it is the hoistable query that dominates it.

Any whole-population consumer pays this: the new first-task detector
(`src/seon/issue/detect.clj`, `public-without-reaching-test`), and the
admittance bar of ruling C1 / the merge gate of C2, which ask the same
question for every changed identity.

## Done when

`gate-set` derives the declared-reference population once for a supplied
population, or a caller asking about many functions can hand it in — the
existing single-function arity keeping its exact meaning (accretion, not
breakage) — and a whole-population run is one query plus the walks. The
existing `seon.fn` regressions stay green and a measurement in the owning
PRD's `research/` records the new number for the same 1196-candidate run.

Found by the first-task-detector lane, 2026-09-17
([landing note](../../prds/steward-platform/research/first-task-detectors-2026-09-17.md)).
`src/seon/fn.clj` was another lane's file at the time, so this is filed rather
than fixed.

## Review follow-up implementation, 2026-09-17

`seon.fn/gate-sets` (`src/seon/fn.clj:1371`) now acquires declared dispatch
and unresolved-file relations once per supplied operation. The detector
hands its candidate population to that owner (`src/seon/issue/detect.clj:280`).
No process cache is added. The exact-selection fixture counts one declaration
query for four requested symbols (`test/seon/fn_test.clj:2647`).

At held default basis 536871803, the current population has 1,202 candidates,
not the earlier 1,196. Previous single-function loop: 9,041.292 ms;
final bulk operation: 7,517.956 ms; detector: 7,044.122 ms / 166 subjects.
These are hot-loaded, read-only measurements, not an adopted or cold proof.
The issue remains open pending the orchestrator's gate; see the
[landing note](../../prds/steward-platform/research/call-graph-fidelity-fix-2026-09-17.md).
