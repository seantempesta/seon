---
type: landing
lane: shrink-turn-opus
date: 2026-09-26
plan: docs/research/agent-platform/shrink-plan-turn-2026-09-26.md
status: P1 live and measured; P2 and gate patch committed; RESET NEEDED
---

# Landing: shrink-turn-opus, slices P1 and P2 (2026-09-26)

## Broken or unproven

- P1 (`d033a37e7`) is live on default pid 17853 at `15e6a31c4`, and its after-numbers are below.
- Two commits are not loaded yet and need **RESET NEEDED** plus the focused tests below:
  - P2 (`8822666ce`);
  - the gate patch (`9375f23c3`).
- During this lane, default's previous JVM died of an OOM (root's diagnosis). Separately, `bin/seon status` fails
  in babashka:
  - error: `Could not locate malli/core…` at `src/seon/error/refusal.clj:102`;
  - cause: bb loads `seon.error.refusal`, whose schema seam requires malli;
  - owner: this is foreign to turn.clj, and root owns it.

## P1: `d033a37e7`

**Change.** In `evaluate-sources`, the rows of earlier forms are applied through `with-settled` (`seon.db/with`) at
the top of the next form, which is the only place a form reads them.
- While the commit holds, only rows not yet applied are applied.
- A moved commit still rebuilds from `latest` with every row. A form's own durable write must stay visible to the
  forms after it, and the rows exist only in memory until settlement, so there is no smaller correct rebuild.
  The O(rows × commit moves) cost remains, and I name it rather than hide it.
- **Net −2 lines.**

**The defect.** Before this change, the loop applied every program row after its own form, including the last form.
That speculative database was never read. The defect was not filed as a task, because it is fixed in the same
commit. Independent confirmation: `docs/research/agent-platform/eval-trace-2026-09-26.md`, item 3.

**Before-measurements.** Taken on default pid 89341, branch `shrink-turn-p1-before`, as JVM-wide `seon.profile`
cell deltas around one MCP SCI run each:

| run | `with-settled` calls / ms | `resume-turn` ms | `evaluate-sources` ms |
|---|---|---|---|
| single contracted `defn`, first (arity cold) | 2 / 1,698 | 2,955 (2 runs counted JVM-wide) | 1,988 |
| single contracted `defn`, warm | 2 / 196 | 1,347 (2) | 445 |
| mixed 3 forms: `defn`, call, `defn` | 2 / 159 | 1,504 (1) | 959 |

The mixed run's second application, after its last form, is the one P1 removes.

**After-measurements.** Taken on default pid 17853 at `15e6a31c4` (which contains P1), fresh branch
`shrink-turn-p1-after`, same probe:

| run | `with-settled` calls / ms | `resume-turn` ms | `evaluate-sources` ms |
|---|---|---|---|
| single contracted `defn`, first | **0 / 0** | 581 | 118 |
| single contracted `defn`, warm | **0 / 0** | 561 | 116 |
| mixed 3 forms: `defn`, call, `defn` | **1 / 83** | 1,552 | 1,040 |

Reading the results:
- The single-`defn` run no longer calls `seon.db/with`.
- The mixed run's total is essentially unchanged: 1,552 ms against 1,504 before. Its `evaluate-sources` time of
  about 1 s lies outside `with-settled`, in the per-form evaluation and gate, which the eval-path audit covers. The
  run is still over one second and is not explained by this slice.
- The before and after rows come from different JVMs: pid 89341 was long-running and pid 17853 is fresh. Compare the
  `with-settled` columns; wall times differ with JVM age.

**REPL equivalence.** I ran a scratch-namespace probe of the loop's working-state algorithm, old against new, over
1,000 random schedules of (commit moves, program row or none) with lengths 1–5:
- every form saw the identical database;
- applications fell from 2,531 to 1,552;
- a single `defn` went from 1 application to 0;
- the mixed run went from 2 to 1.

**Static checks.**
- `clj-kondo --lint src script test --cache false`: 0 errors.
- `seon.contracts-compile-test/check` over turn.clj: `[]` (71 ms).

## P2 (commit below)

**Change.** `opening-deferred?` now reads the outside-wake anchor once. It reads `outside-wake-t` and passes the
value to a new private `spent-runs`; `episode-runs` is now `(spent-runs db agent-id nil)` and its signature is
unchanged for its six external callers. Before, it read the anchor twice per call: once inside `episode-runs` (when
no issue is assigned) and once for the failed-attempt window. Each read is one `agent-eid` query plus the
history seeks.

**Error checks.** The four-key error checks in `episode-runs`, `max-episode-runs`, `turns-left` and
`opening-deferred?` were replaced by exact tests over each value's declared union:
- `seon.db/read-refusal?` for `db/q` answers, whose declared refusal is `:seon.db/invalid-read-error`;
- `map?` for `[:or int nil error]` returns;
- `if-some` for `declarations-refusal`'s `[:maybe error]`.

The `outside-wake-t` refusal check is deleted: its contract returns `[:int {:min 0}]`, and a refused read throws
before it returns.

**Net −17 lines.**

**Static checks.**
- clj-kondo: 0 errors.
- `contracts-compile-test/check` over turn.clj: `[]` (230 ms).

**REPL equivalence on live data.** I loaded the new `spent-runs` and `opening-deferred?` into scratch namespace
`shrink-turn-p2-probe` on pid 17853 and compared them with the loaded old Vars over all 69 agents on default:
- `opening-deferred?` gave identical results for all 69 agents (all false);
- `episode-runs` gave identical results for all 69;
- time on a held value fell from 1.14 to 0.96 ms per agent (−16%).

No agent on default has an issue, so the issue-anchored branch is covered only by the focused tests. The saving is
larger on the fresh values a pass reads: about 1 ms per query (perf-eval-overhead landing).

## Gate patch: `9375f23c3`

I applied `tmp/one-commit-a/turn-gate-report-auto-check.patch` from one-commit-a-opus as-is.
`gate-function-install` now passes `:seon.test.accretion/auto-check` to `gate-report` only when a seeded check ran.
Without it, every gate with no seeded check was refused, which produced the accretion-test reds. clj-kondo shows
0 errors.

## What root must run after the restart

```
bin/test-check default --ns seon.turn-test
bin/test-check default --ns seon.turn-loop-test
bin/test-check default --ns seon.program-test
bin/test-check default --ns seon.cluster.problem-routing-test
bin/test-check default --ns seon.problems-test
bin/test-check default --ns seon.test.accretion-test
```

Clock row to take after the restart: `seon.problems/problems` on default, reading the `opening-deferred?` mean,
which was 13 ms per call.

## Sizes

turn.clj: 5,990 → 5,988 after P1 → 5,974 after P2 → 5,975 after the gate patch.
