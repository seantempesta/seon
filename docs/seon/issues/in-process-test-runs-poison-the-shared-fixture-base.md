---
type: issue
status: open
severity: blocker
tags: [issue, test-fixture, bounded-execution, dev-mcp, repl]
---

# One interrupted in-process test run poisons the shared fixture base for the whole JVM

## Problem

`seon.test-support/database-base` is a `delay`. Clojure's `Delay` caches a
THROWN exception as permanently as a value: once the first force fails, every
later `@database-base` rethrows that same stale throwable, with the original
stack, forever. In a long-lived `default` JVM shared by every lane that
triages reds in-process, one failed force therefore converts a single local
accident into a JVM-wide outage: every `seon.test/run` in every lane then
reports the identical error, and it names something that is no longer true.

That is the project's recurring failure class in reverse — a check that keeps
reporting a condition after the condition is gone — and it is what a lane
meets first, so it costs the diagnosis time of every lane after it.

Two distinct first-force failures were observed on 2026-09-16 during the
batch-19 reds triage.

### 1. An MCP evaluation timeout interrupts the population

`mcp__seon__eval_clj` bounds a JVM evaluation at 30 s. A `seon.test/run` issued
directly from that call is interrupted when the bound fires. The interrupt
lands inside `cluster/populate-source!`, and the base delay caches:

```clojure
{:seon.error/kind :seon.fn/index-refused
 :seon.fn/index-phase :seon.fn/population
 :seon.fn/transaction-result
 {:seon.error/kind :seon.db/unknown-failure
  :seon.error/message "java.lang.InterruptedException"
  :seon.db/transaction-outcome-unknown true}}
```

After that, `seon.cluster.wake-test/a-fault-wakes-the-steward-of-the-failing-functions-namespace`
— which had passed 6/0/0 twice, seconds earlier in the same JVM — reported
that same population error, as did every other test tried.

### 2. `default`'s classpath has no `:test` alias dependencies

`create-base` acquires a cluster SCI ctx, and `seon.sci.eval/host-namespace!`
loads every first-party program namespace, including the test namespaces.
Under `bin/test` those load; inside `default` they do not, because `default`
runs without the `:test` alias:

- `seon.dev.dependency-cache-test` → `dev-cache` → `clojure.tools.build.api`
  — "Could not locate clojure/tools/build/api__init.class";
- `seon.flow-test` → `clojure.core.async.flow-monitor` → `muuntaja.core`.

`host-namespace!` is correct to refuse loudly. The defect is that the refusal
is then cached by the base delay and no longer describes the JVM's actual
state once the namespace is loadable: after loading
`clojure.tools.build.api` and `seon.dev.dependency-cache-test` by hand, every
subsequent run still failed with the identical, now-false, cached exception.

## Evidence

Measured in `default` (pid 53378) on 2026-09-16 during the batch-19 triage:

```clojure
;; the delay reports realized, and rethrows
(realized? @#'seon.test-support/database-base)          ;; => true
@@#'seon.test-support/database-base                     ;; throws the cached error
```

Recovery required replacing the Var's delay with a fresh one, wrapped in the
cluster's projection (`create-base` itself needs a handed projection; without
one `seon.program` refuses with `:seon.schema/missing-projection`), after
pre-loading the whole `:test` classpath — `clojure -A:test -Spath` — into a
`DynamicClassLoader`. 225 of the 226 test namespaces load from `default`'s own
classpath; `seon.flow-test` needs the extra entries. After the rebuild the
same tests ran green in-process: `virtual-loop-end-to-end` 229/0/0,
`routine-status-declares-unmeasured-store-size` 3/0/0.

In the same session `default` (pid 7595) also died of an unrelated dev panic,
so a second JVM was needed; the base of the fresh JVM was poisoned by cause 2
on its very first force.

## Owner

`test/seon/test_support.clj` (`database-base`, `create-base`) with
`src/seon/test.clj` (`seon.test/run`). The bound that fires is
`mcp__seon__eval_clj`'s, but a bound firing must not corrupt shared state.

## Acceptance

1. A failed base construction is not cached as a terminal value: the next
   force retries, so a transient interrupt or a since-resolved classpath gap
   stops reporting after its cause is gone. Whatever replaces the raw delay
   states what it reports when the base is ABSENT rather than failed.
2. An in-process `seon.test/run` whose caller is interrupted leaves the shared
   base usable: the interrupt ends that run, not the JVM's fixture.
3. `default`'s development classpath carries the `:test` alias entries, or
   `create-base` declares that test namespaces it cannot serve are outside
   this process's callable surface rather than refusing the whole base.
4. One regression asserts the class: force the base with a construction that
   throws, then force it again with the cause removed, and observe a usable
   base rather than the first throwable.

## Re-observed by config-apply-cost — 2026-09-16

After the owner's restart to default PID 37572, the canonical delay was
initially unrealized. Running
`seon.config-test/converged-apply-uses-carried-projection-and-remains-exact`
through `seon.test/run` on a future, with an explicit 270000 ms execution
bound and database-derived provenance, completed in 32756.598 ms rather
than timing out. Run 49224 recorded 2 pass / 0 fail / 1 error: cold SCI
acquisition could not load `seon.dev.dependency-cache-test` because
`clojure.tools.build.api` was absent. Dereferencing the now-realized base
again returned the cached `:seon.sci.eval/namespace-unloadable` exception.
This reproduces classpath cause 2 independently of an MCP timeout.
The lane obeyed the base-poison stop rule; it did not repair the classpath,
replace the delay, or restart default. Its implementation and measurement
boundary are in
[the config landing](../../prds/context-generation/research/turn-bookkeeping-cost-2026-09-16.md).
