---
type: research
date: 2026-09-17
tags: [test, in-process, bounds, wave/steward-platform]
---

# `seon.test/check` excludes declared-long tests and reports what expired

Closes the two defects filed in
[in-process-check-selects-declared-long-tests](../../../seon/issues/in-process-check-selects-declared-long-tests.md).

## What was wrong

1. `check-in-process` selected by `:seon.fn/calls` reach with no `:seon.test/long`
   filter, so a change to a widely reached function pulled real-boot drills into
   the development JVM. `grep -n "seon.test/long" src/seon/test.clj` returned
   nothing before this change.
2. `check` ran `check-in-process` inside a `FutureTask` bounded by
   `:seon.test/check-time-limit-ms` and, on expiry, returned
   `(unknown @progress ...)` — one bare `:seon.test/unknown`. Every verdict the
   loop had already recorded was discarded, and `:seon.test/next-tier :none`
   read like a verdict. This is the project's recurring absence-of-signal class.

## The missing fact

`:seon.test/long` was NOT a program-row attribute. `resources/seon/schemas/seon.test.edn`
indexed `:seon.test/fixture-observation` but not `long`; the cold runner's
`long-reason` (`src/seon/test/runner.clj:623`) reads Var metadata, which a check
that has deliberately not loaded the Var cannot do. Per AGENTS.md §2.2 the fix is
to declare the fact at the indexing seam and query it.

Declared at BOTH seams that lift a test marker from Var metadata onto a row,
exactly as `:seon.test/fixture-observation` already is:

- `src/seon/fn.clj:553` (static analysis / publication)
- `src/seon/sci/eval.clj:410` (agent-admitted definitions)

Schema, all accretive (`resources/seon/schemas/seon.test.edn`):

- attribute `:seon.test/long` — the declared nonblank reason
- `[:seon.test/long {:optional true} :seon.test/long]` on the `:seon.test/test` row
- `:seon.test/long-excluded`, mirroring `:seon.test/destructive-excluded`
  (`{:seon.test/sym, :seon.test/long, :seon.test/command}`)
- `:seon.test/expired`, reusing the existing `:seon.test/unknown-error` class
- `:seon.test/long-excluded` and `:seon.test/expired` on `:seon.test/check-result`
- `:seon.test/include-long?` on `:seon.test/check-request`

## The two fixes

**Exclusion** (`src/seon/test.clj`, `check-in-process`): a `long-excluded` vector
is derived next to the existing `deferred` computation by pulling
`:seon.test/long` per selected symbol; its symbols are removed from `runnable`;
the vector lands on the result as `:seon.test/long-excluded` and in `feedback` as
`long-excluded <count>` plus one named line per test with its declared reason and
the cold command `bin/test -- <ns>` (explicit namespaces run complete, so that
command genuinely runs it). `:seon.test/include-long? true` on the request skips
the exclusion entirely and spends the same one allowance.

**Honest expiry** (`src/seon/test.clj`, `expired-result` + `check`): the atom the
check already shared for progress now carries `{:seon.test/progress,
:seon.test/recorded, :seon.test/pending}`. `check-in-process` publishes `initial`
before the first Var is touched and the accumulated result after every completed
run, so on expiry `expired-result` derives the answer from what the check
genuinely holds: the recorded verdicts, `:seon.test/next-tier :none`,
`:seon.test/pending` naming the remaining selection, and `:seon.test/expired`
carrying the typed unknown that names what did not return. With nothing recorded
(a selection that never reached the loop) it still returns the bare typed
unknown, unchanged.

## Remaining mirror to converge

`seon.test.runner/long-reason` (`src/seon/test/runner.clj:622`) still reads
`:seon.test/long` off Var metadata rather than the row it now indexes. The cold
runner holds the Vars and the manifest rows, so both derive from the same one
declaration and cannot disagree for a published tree; converging it to a row read
is a separate one-function slice in the runner, which this lane does not own.

## Regressions (`test/seon/test_reaching_test.clj`)

- `the-long-declaration-is-indexed-onto-the-test-row` — `seon.fn/rows` over a
  spat probe file lifts `:seon.test/long` onto the test row (the fact the check
  queries is derived from the declaration, not hand-supplied).
- `an-in-process-check-excludes-a-declared-long-test-and-names-it` — exclusion by
  name, with the reason and the cold command in the result and in `feedback`; the
  probe's marker directory proves the body never ran.
- `the-declared-opt-in-includes-the-long-test` — `:seon.test/include-long? true`
  runs it; the marker exists and the run facts are recorded.
- `an-expired-check-reports-the-verdicts-it-already-recorded` — two probes (one
  trivial, one waiting) under a MEASURED allowance return the completed verdict,
  its recorded run facts, `:seon.test/pending`, and `:seon.test/expired` naming
  the unreturned test — never a bare unknown.

## Verification boundary

- clj-kondo: 0 errors on all four edited source files and the test file.
- The five new/changed schema keys compile against the live projection on
  `default` (pid 38993, jvm mode): `:seon.test/long`, `:seon.test/long-excluded`,
  `:seon.test/expired`, `:seon.test/check-result`, `:seon.test/check-request` all
  `true`.
- **`bin/seon init --dev default` FAILED at JVM instrumentation, not on this
  slice**: `:malli.core/invalid-schema {:schema :seon.maintenance/collection-record}`
  from the maintenance lane's uncommitted `resources/seon/schemas/seon.maintenance.edn`.
  Every reload, including `seon.test`, `seon.fn` and `seon.sci.eval`, completed
  first; the adoption commit was not recorded.
- **No in-process regression run was performed.** The orchestrator paused all
  in-process runs and transacting probes on `default` (store growth measurement)
  before the probes could be exercised. The four regressions above are unrun; the
  batched gate (`tmp/orchestrator/gate-requests/check-long.txt`) is their first
  proof.

## Batch 69 B2 red, and the root cause (2026-09-17, second pass)

Cold, `an-expired-check-reports-the-verdicts-it-already-recorded` failed 9
assertions: `:seon.test/tests []`, `:seon.test/passed []`, both probes pending,
`:seon.test/expired` naming `a-completes`.

Reproduced in process on `default` (pid 63433, hot-reloaded test namespace
through `seon.test`'s own loader, daemon thread, `:seon.test/remaining-ms`
100000): identical, `:seon.test/elapsed-ms 3004.75`, expiry message
`bound of 3000 ms. Pending: …/a-completes`.

**The production path was not at fault.** The check published exactly the state
it held: progress `a-completes`, pending both probes, nothing recorded — because
the bound fired BEFORE the first trivial run returned. The per-run publish
already happens after the verdict is folded and before the loop recurs; the
result was honest and the regression's claim was the wrong one.

The defect was in the fixture: `3000` was a tuned wall-clock constant standing in
for an observable event — "one complete trivial check finished" — and one
complete in-process check (effective config, selection queries, run provenance,
namespace loading and contract arming, the run itself, and
`commit-results!`) costs more than that on the gate machine. `with-expiring-selection`
now admits a third probe that the check never selects, measures one complete
trivial check on it, and derives the allowance as
`(max 5000 (* 4 measured-elapsed-ms))`, seeding it through
`seon.test-support/seed-cluster!`'s manifest arity. The regression also asserts
that the expiry message names that derived allowance, so a future drift in the
derivation cannot pass silently.

### In-process proof (pid 63433, loader-reloaded namespace, daemon thread)

| test | pass | fail | error |
|---|---|---|---|
| `the-long-declaration-is-indexed-onto-the-test-row` | 1 | 0 | 0 |
| `an-in-process-check-excludes-a-declared-long-test-and-names-it` | 8 | 0 | 0 |
| `the-declared-opt-in-includes-the-long-test` | 4 | 0 | 0 |
| `an-expired-check-reports-the-verdicts-it-already-recorded` | 15 | 0 | 0 |

Verification boundary for this pass: the change is TEST-FILE ONLY, proven against
the hot-reloaded test namespace; `src/seon/test.clj` is unchanged since
`4e22d2256`. `bin/seon init --dev default` was not re-run by this lane — it was
refusing tree-wide during this window (`seon.cluster.store/file-lock-object?` had
no admitted callable, then the maintenance schema). While reading results,
`default`'s effective config was missing `:seon.config.agent/write-refusal-bound`,
so every MCP return value rendered as that refusal; results were read by spitting
them to `tmp/` from inside the JVM. Both are foreign and reported.
