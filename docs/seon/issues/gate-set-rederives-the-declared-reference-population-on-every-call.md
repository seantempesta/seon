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

## Test-system repeat, 2026-09-23 assignment

The published-branch selector now calls the existing bulk `gate-sets` once.
That does not make the relation query bounded by the changed function. In an
armed HEAD-plus-test-system-paths run over the canonical published fixture,
one changed `seon.test.bounds/silence-seconds` reached
`declared-reference-edges` (`src/seon/fn.clj:1402`) through `gate-sets-in:1448`
and `gate-sets:1494`. A sample of that worker showed Datahike
`execute-fused-scan-rel` / `execute-or` processing the declared-reference
rules. The body began at `23:06:28.587Z`, completed its baseline recording at
`23:06:51.247Z`, and still had not returned when its own worker PID 16920 was
terminated at `23:07:27.430Z`. No cold gate or default operation was run.

This is a remaining query cost, not repeated per-seed invocation and not
permission for a cache. The test-system assignment excludes `src/seon/fn.clj`;
ownership of this precise boundary was requested from the orchestrator.

## Indexed acquisition, 2026-09-23

Ownership was extended to `declared-reference-edges`. Its three relations
now query bound declared attributes instead of combining them through a
rule with an unbound attribute. The existing reverse AVET walk is unchanged.
Canonical measurement: 3563.590 ms before, 223.269 ms after, 10 edges in both.
The split and verification are recorded in
[the test-system landing note](../../prds/steward-platform/research/test-system-fork-2026-09-23.md).
No cache was introduced. The earlier held-default population measurement
is not repeated by this lane, which is forbidden to operate default.

Parity verification `81b1333a102d` completed in 4981.556 ms with 3 assertions,
zero failures/errors. Indexed acquisition was 322.143 ms in that fresh
worker. The baseline's three rules were queried separately and unioned;
all 10 edge pairs matched. The fn.clj change is still uncommitted because
the file contains foreign `unresolved-callers` and adoption-identity hunks;
the orchestrator explicitly ordered those to remain untouched.
