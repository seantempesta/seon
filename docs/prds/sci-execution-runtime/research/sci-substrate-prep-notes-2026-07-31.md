---
type: research
status: active
tags: [research, sci, runtime, testing]
---

# Interpreted-corpus substrate preparation

Units 1 and 2 for rulings #20–#21. Unit 3, the per-basis corpus ctx holder,
is deliberately outside this change.

## Dependency ledger

- Maintained SCI fork before the change:
  `reference-code/sci` at `1305a90a1ab9`,
  branch `seon`. SCI captures `:interrupt-fn` when it creates an interpreted
  function (`reference-code/sci/src/sci/impl/fns.cljc:39-40,63-64`) and
  `sci/fork` retains every ctx key while replacing only `:env`
  (`reference-code/sci/src/sci/core.cljc:318-323`).
- Vendored Clojure inventory source:
  `reference-code/clojure` at
  `b18d3adc5b5f4d5d0ccea966203fb67a614d5c3d`. Its `:added "1.11"`
  definitions establish the comparison set.
- First-party guard owner and recurring proof:
  `src/seon/sci/eval.clj` and `test/seon/sci/eval_test.clj`.
- Fork owner and proof:
  `reference-code/sci/src/sci/impl/namespaces.cljc` and
  `reference-code/sci/test/sci/core_test.cljc`.
- Pre-existing measurements and probes:
  `sci-precomputed-analysis-2026-07-31.md` and `tmp/sci-precompute/`.

## Unit 1 — one process-wide interrupt guard

### Falsifier

Before the change, the base had no configured guard and each fork minted a
different one:

```clojure
{:base-guard-configured? false
 :forks-share-guard? false
 :fork-shares-base? false}
```

That made a function created in the base capture `nil`, so invoking it from an
armed fork ran without an interrupt. The new regression creates an infinite
function in `eval/base` on one thread and invokes it through a fork on another;
before the fix it returned the test backstop `::hung` and failed four
assertions.

### Result

One delayed process holder now owns the guard. The guard is installed in the
base ctx before any base function can be created
(`src/seon/sci/eval.clj:143-196,265-288`). `fork` is now only
`(sci/fork (base))`, so the base and every fork share the identical interrupt
function and `ThreadLocal` arm holder (`src/seon/sci/eval.clj:290-294`). The
per-fork guard construction and reassociation were deleted.

Arming remains per evaluation and per invoking thread
(`src/seon/sci/eval.clj:296-334`). The new recurring case proves that a
base-created function can be invoked and cut from any thread that arms itself
through `evaluate` (`test/seon/sci/eval_test.clj:193-213`). Existing recurring
cases continue to prove:

- direct loop interrupt and uncatchability
  (`test/seon/sci/eval_test.clj:150-175`);
- a function defined by an earlier evaluation uses the current limit
  (`test/seon/sci/eval_test.clj:177-191`);
- an acquired function uses the current limit
  (`test/seon/sci/eval_test.clj:215-242`);
- sibling threads arm independently
  (`test/seon/sci/eval_test.clj:289-315`);
- disarm removes exactly the current thread's state
  (`test/seon/sci/eval_test.clj:317-346`); and
- wrapped interrupts retain SCI's unforgeable marker class
  (`test/seon/sci/eval_test.clj:431-444`).

Eight selected guard regressions passed 40 assertions with zero failures or
errors. The instrumentation/acquisition boundary passed 1 test / 7 assertions.
The path-limited root commit is `87450e9e0251ded1be3272b10363593101b488db`.

### Exact thread residual

The design closes the creation-thread defect, not cross-thread propagation.
Any JVM thread may invoke a base-created function safely when `evaluate` arms
that same thread first. A different worker thread spawned by an armed
evaluation has no `ThreadLocal` arm and the guard is intentionally a no-op on
that worker. Propagating the arm would break the proven sibling isolation.
Agent-path execution must therefore remain synchronous on its armed workload
thread; an offloaded host call remains part of the already documented compiled
host-call ceiling.

### Per-entrance cost

On this laptop under JDK 26, seven warmed samples of 5,000,000 calls to the
actual process guard measured:

- armed median: **2.77 ns per entrance**;
- unarmed median after `stop!`: **1.46 ns per entrance**.

The armed samples were 2.68–2.95 ns and included the existing entry count,
atomic deadline read, and every-1,024th allocation sample. The holder change
does not add work to the entrance function; cost remains nanosecond-class.

## Unit 2 — complete Clojure 1.11 core surface in SCI

The full vendored Clojure 1.11 inventory is 12 public functions:
`abs`, `seq-to-map-for-destructuring`, `random-uuid`, `iteration`,
`update-vals`, `update-keys`, `parse-long`, `parse-double`, `parse-uuid`,
`parse-boolean`, `NaN?`, and `infinite?`.

`seq-to-map-for-destructuring` was already exposed by SCI
(`reference-code/sci/src/sci/impl/namespaces.cljc:968-994`). The maintained
fork now exposes the other 11 on the JVM, including the prompt-omitted
`parse-boolean`, `NaN?`, and `infinite?`, using SCI Vars so the same source also
runs against the fork's Clojure 1.10.3 profile
(`reference-code/sci/src/sci/impl/namespaces.cljc:996-1175,1649-2080`).

The fork regression covers parsing, numeric edge cases, map metadata,
`iteration` laziness/reduction, and UUID round trips
(`reference-code/sci/test/sci/core_test.cljc:1818-1879`). The maintained fork
commit is `937d392a008e`; it is local on branch
`seon` and was not pushed because the separate publication lane owns remotes.

The complete fork suite passed on both supported versions:

- Clojure 1.10.3: 380 tests / 1,410 assertions / 0 failures / 0 errors;
- Clojure 1.11.1: 380 tests / 1,410 assertions / 0 failures / 0 errors.

## Corpus recount

The current corpus contains 1,512 function rows; 168 contain a host interop
form (11%). The prior report's 1,496 / 169 snapshot changed because the
first-party tree changed, not because modern core functions are interop.

Clj-kondo call analysis found 58 uses of the added core functions in 55
functions across 35 first-party files. Production accounts for 10 functions
in eight files:

- `src/seon/cluster.clj` — `commit-fault!`;
- `src/seon/cluster/export.clj` — `export!`;
- `src/seon/cluster/loop.cljc` — `error-tx`, `turn`;
- `src/seon/cluster/source.clj` — `scratch-branch`;
- `src/seon/cluster/store.clj` — `fresh-connection`, `fresh-file-lock`;
- `src/seon/render/data.clj` — `parse-cursor`;
- `src/seon/schema.cljc` — `bound-forms`; and
- `src/seon/test/runner.clj` — `-main`.

The remaining 45 functions are in 27 test files. The modern-core blocker is
removed from all 35 files. The coarse import-aware corpus installation probe
advanced from the report's 37 / 51 files to 40 / 51: `cluster/loop.cljc`,
`render/data.clj`, and `test/runner.clj` now install completely. The other five
production files above still stop later on independently missing imported
classes, which Unit 3 is already specified to derive from corpus import rows.
No missing modern core function remains in the 11 residual failures.

## Gate boundary outside these units

The combined Seon selection ran 19 tests / 75 assertions. Every guard and
instrumentation regression passed, but the namespace cannot be claimed green:
the protected `public-walk-is-callable-through-an-agent-sci-eval` test expects
a string while the current render/admission path returns
`{:seon.sci.admit/truncated-string ... :elided true}`, producing one failure
and one follow-on `ClassCastException` (`test/seon/sci/eval_test.clj:263-287`).
The same failure was present before Unit 1, and no render, cluster, or admission
file was changed here.

One recount attempt also met concurrent schema-population churn:
`seon.test-support/with-database` tried to seed `:seon.db.process/id` before
that attribute was installed. The successful 1,512 / 168 recount above was
captured before that external boundary moved. Neither external failure was
worked around or edited in this unit.
