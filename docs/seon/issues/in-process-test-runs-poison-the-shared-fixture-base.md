---
type: issue
status: resolved
severity: blocker
tags: [issue, test-fixture, bounded-execution, dev-mcp, repl]
---

# One interrupted in-process test run poisons the shared fixture base for the whole JVM

## Resolution — 2026-09-16

`test/seon/test_support.clj` now shares one daemon construction through a
completion, caching only a successfully constructed base. A failed attempt
returns `:seon.test-support/database-base-unavailable` to its callers and
the next request retries. The constructor uses the system classloader and
an explicitly carried schema projection; it inherits neither a caller's
test loader nor its interrupt or evaluation bound. `defonce` preserves an
existing healthy base across test namespace reloads.

`failed-base-construction-retries-without-caller-interruption` exercises the
real canonical constructor: first failure, interrupted waiter, successful
second construction, actual program population and SCI context, and reuse.
On default PID 45917 it passed **11/0/0 in 39435 ms**, through `seon.test/run`
with armed contracts on a daemon thread and `remaining-ms 100000`.
The classpath acquisition defect was independently fixed by `653d4d4ef`.
No default restart or shared-base reconstruction was performed for this fix.

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

## 2026-09-16, post-refork JVM (pid 95853): construction fails from an MCP thread

The `dir-elision-floor` lane followed the base construction rule exactly —
`(future @seon.test-support/database-base)` on a daemon thread, from a plain
MCP `jvm` evaluation, with no bound and no `seon.test/run` around it. Note
that `test/` is not on `default`'s classpath, so the delay has to be reached
through `seon.test`'s own loader:

```clojure
(#'seon.test/with-test-loader #(requiring-resolve 'seon.test-support/database-base))
```

The future completed with a flat error value and the delay stayed unrealized:

```clojure
#:seon.error{:kind :seon.test-support/database-base-unavailable
             :message "Canonical fixture base construction failed: Schema
                       declaration resolution requires the projection handed
                       to the operation."}
```

So it is not only interruption that leaves lanes without an in-process run:
construction itself refuses here, on a §2.1 grounds — the declaration
resolution wants a handed projection that the loader thread does not carry.
No in-process regression was possible in that JVM; the lane relied on the
cold gate. Not investigated further (out of that lane's scope), filed so the
next lane does not spend its budget rediscovering it.

## Second shape, 2026-09-16 (source-analysis lane, pid 17352)

The delay is not the only thing that poisons. Running a whole test namespace
through `seon.test/run` in-process, one deftest at a time, left the base
delay REALIZED and intact — `@@seon.test-support/database-base` still returns
its `::configuration`/`::connection`/`seon.sci.eval/ctx` map — while its
Datahike connection's writer was shut down. Every later `with-database` fork
from it refuses with

    :seon.error/kind :seon.db/unknown-failure
    :seon.db/transaction-outcome-unknown true
    "Writer is shut down; release and reconnect."

so tests that passed minutes earlier in the same JVM now error, and the
`realized?` check the operating rule prescribes reports the base healthy.
That is this project's absence-of-signal class inside the health check
itself: a realized delay is not a live connection.

Trigger, observed: `seon.fn-test/indexing-uses-a-prebuilt-manifest-without-analysis`
hit `seon.test/run`'s declared 20,000 ms bound inside `with-database`. The
bound firing left the fixture's writer released. It also leaked that test's
`with-redefs` of `seon.fn.analyzer/analyze` (with-redefs never unwinds when
the run is abandoned), so the NEXT deftest in the sweep failed with that
test's `"analysis must not run"` throw — a red with no relation to its own
subject. In isolation that victim,
`seon.fn-test/settled-form-records-calls-across-every-program-namespace`,
ran 12/0/0.

Two derived defects worth separating: a base health probe must test the
connection, not `realized?`; and a bound firing inside `with-database`
should release the fixture rather than leave a half-live one for every
later lane in the JVM.
