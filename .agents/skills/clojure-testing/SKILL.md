---
name: clojure-testing
description: "Test patterns for Seon. Use when writing or debugging a test, when a test needs a fresh in-memory Datahike connection, when choosing between an example test and a generative property, or when a suite is green for the wrong reason. Covers bin/test and its focused selection, clojure.test shape, the per-test database fixture, seeded test.check state-transition properties, and the honest-generator rules."
---

# Clojure Testing — JVM, synchronous, one gate

The suite is **Clojure on the JVM**, run by `bin/test`. Tests double as the
worked manual for the surface they cover — read `test/seon/cluster/run_test.clj`
as the canonical example: in-memory Datahike fixture, deterministic clock,
example tests that teach the call shapes, and a seeded state-machine property
over the real database.

> Hand-offs: what a Datahike transaction/query actually does →
> **`datahike`**; what shape to declare and why → **`data-modeling`**;
> errors-as-values / no-bare-keys mindset → **`data-oriented-clojure`**.

**The CLJS build is OFF.** The one current correctness gate is `bin/test`; it
discovers only `*_test.clj` and `*_test.cljc` beneath fresh `test/`
(`AGENTS.md:65-67`; `bin/test:1-23,41-62`). Do not restore or invent a CLJS
or writer test command to satisfy an old instruction.

## Running

```bash
bin/test                        # platform tier, then tests reaching changed code
bin/test --full                 # every *_test.clj / *_test.cljc under test/
bin/test seon.cluster.run-test  # every test in exactly these namespaces
```

The gate creates an isolated operator root and the exit code is the verdict.
Use one explicit multi-namespace selection while iterating. Bare `bin/test`
runs the declared platform regressions first and stops if they fail, then runs
the tests whose program-graph reachability covers code changed since the last
recorded green basis. A missing basis, removed file, or unmodeled gate input
widens selection conservatively. `--all` runs every non-long test after the
platform tier; `--full` also includes long tests, and `SEON_TEST_FULL=1` is its
environment equivalent. Explicit namespace selections are always complete and
record no green basis (`bin/test:1-24,43-57`;
`src/seon/test/runner.clj:1-1200`).

`bin/test` discovers a namespace by file name: a test file must end in
`_test.clj` or `_test.cljc` under `test/`, mirroring its `src/` namespace. A
test in any other location is invisible to the gate, which means **not
covered** however green it looked when you ran it by hand.

### Root suite plus an owned vendored fork

A change crossing Seon and a maintained dependency has two owners and needs
both discovered suites:

```bash
# Seon's acceptance boundary, from the repository root
bin/test seon.datahike-fork-test

# Datahike's direct owner regression, from the submodule root
(cd reference-code/datahike &&
  bb kaocha --focus datahike.test.query-planner-test)
```

Derive the submodule command instead of importing the root runner by habit:
its `bb kaocha` task forwards arbitrary arguments to
`clojure -M:test -m kaocha.runner`
(`reference-code/datahike/bb.edn:46-51`;
`reference-code/datahike/bb/src/tools/test.clj:8-13`), `tests.edn` declares the
Kaocha suites and namespace pattern
(`reference-code/datahike/tests.edn:1-30`), and the test file's `ns` form gives
the focus value
(`reference-code/datahike/test/datahike/test/query_planner_test.clj:1-9`).
The planner repair proved this exact split: the root acceptance test and the
96-test/396-assertion fork focus both passed
(`docs/seon/issues/archive/datahike-planner-and-caches-carry-three-smaller-defects.md`
“Evidence”).

Run the root test to prove Seon pins the behavior it depends on; run the fork
focus to prove the implementation in its owning project. One does not replace
the other.

One honesty fact about the gate itself:

- **A namespace with zero `deftest`s reports green.** `run-tests` returns
  `0 fail 0 error` and the exit code is 0. Green is not evidence that anything
  ran — check the test count.

Run a selection as one `bin/test ns-a ns-b` invocation. The runner accepts all
explicit namespace arguments before starting its single JVM (`bin/test:41-150`);
separate invocations are separate JVMs.

## Fresh in-memory Datahike per test

Use the production population owner through `seon.test-support/with-database`.
It opens a fresh `:memory` store, calls `cluster/populate-source!` to install
the current `resources/seon/schemas/` population and program rows, and
releases and deletes it in a `finally`. There is no process-global connection
(`test/seon/test_support.clj:379-475`).

```clojure
(ns seon.cluster.run-test
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.test-support :as test-support]))

(deftest canonical-population-is-installed
  (test-support/with-database
    (fn [connection]
      (d/transact connection [{:seon.cluster.run/id "r1"}])
      (is (= "r1"
             (d/q '[:find ?id .
                    :where [_ :seon.cluster.run/id ?id]]
                  @connection))))))
```

**Attributes must be INSTALLED before they are transacted.** Under
`:schema-flexibility :write` a loaded-but-uninstalled attribute throws.
Ordinary tests use the production population owner above. Only a test whose
subject is schema installation passes explicit synthetic Datahike declarations
through `:seon.test-support/extra-schema`.

`d/transact` accepts BOTH `{:tx-data [...] :tx-meta {...}}` and raw
vector/sequence forms. The production fixture itself uses the arg-map form;
never flag it as invalid. Datahike normalizes both in
`reference-code/datahike/src/datahike/api/impl.cljc:30-48`.

Give the test a **deterministic clock** rather than reading the wall clock —
Seon's transitions take time as an explicit input precisely so a property can
supply it:

```clojure
(def ^:private t0-ms 1785000000000)
(defn- at [offset-ms] (java.util.Date. (long (+ t0-ms offset-ms))))
```

## Example tests — plain and synchronous

No `async`, no `done`, no Promise rails. Call the function, assert on the
returned value AND on the durable facts, because a call can return something
agreeable while the write did not happen:

```clojure
(deftest reads-back-a-row
  (testing "a transacted value comes back out"
    (test-support/with-database
      (fn [connection]
        (d/transact connection [{:seon.cluster.run/id "r1"}])
        (is (= "r1"
               (d/q '[:find ?id .
                      :where [_ :seon.cluster.run/id ?id]]
                    @connection)))))))
```

For a boundary that returns an envelope, assert on `::ok?` explicitly —
`(is (true? ok?))`, never a truthiness check that a map would also pass.

## Refusal is a result — assert both rails

A transaction function that fences an ineligible transition ABORTS the whole
transaction by throwing. That refusal is the contract, so a test asserts it as
a value, not with a bare `thrown?`:

```clojure
(defn- deepest-ex-data [error]
  (loop [throwable error, found nil]
    (if throwable
      (recur (ex-cause throwable) (or (not-empty (ex-data throwable)) found))
      found)))
```

Then assert that the eligible command committed, the ineligible one refused,
AND that the refused attempt left the database unchanged. A refusal test that
only checks the throw does not prove atomicity.

**Do not stop at `(ex-data error)`** — the outer wrapper's data is `{}`.
Datahike's `throwable-promise` wraps rather than discards: after the outer
`ex-info` and its `ExecutionException`, the original throwable retains the
refusing transition's data at the third link. Walk the complete `ex-cause`
chain and select the deepest non-empty `ex-data`, as above (probe and output:
`docs/prds/sci-execution-runtime/research/n3-plan-2026-07-27.md` §8). Assert
the specific refusal rule from that value and independently assert that the
database was unchanged. A chain with no classifiable data is an unknown test
failure, never an expected refusal; do not fall back to message matching.

### Schema lifecycle tests assert current and historical rails

For global schema replacement/removal, exercise the production terminal
transaction rather than mutating Malli or Datahike registries directly. Assert
refusal and unchanged basis while direct, transitive, or entity-child data is
current; retract that data and assert the operation succeeds. For removal,
also assert schema/function dependents refuse. With history enabled, query the
old value and historical `:seon.schema/form` at the same `as-of` basis and
rebuild its validator; independently assert Datahike's physical schema map is
current-only. Keep one explicit `:seon.db/no-history? true` trial proving its
old value is intentionally unavailable
(`test/seon/schema_usage_guard_test.clj:80-397`;
`docs/prds/sci-execution-runtime/research/schema-removal-history-probe-2026-07-30.md`).

## Common failure patterns

| Symptom | Likely cause | Fix |
|---|---|---|
| `Bad entity attribute … not defined in current schema` | current EDN population was not installed on this connection | use `test-support/with-database`; add `extra-schema` only when installation is the subject |
| "Unregistered attributes" from a Seon boundary | missing declaration or activation under `resources/seon/schemas/` | add it to the owning family and use the production population owner |
| Empty `#{}` from a query that should match | attr misspelled, type mismatch, or a ref-join written as keyword-in-slot | see the `datahike` skill's read traps |
| A property passes but the code is wrong | the property observes only the returned value, or its checker never reads the facts the command wrote | observe durable facts independently of the return; extend the checker |
| Tests pass alone, fail together | the fixture shares one store, or restores less than it replaced | fresh `:id` per test AND per mutating generative trial; nothing global to restore |
| A property fails differently on rerun | a random or wall-clock input inside the property body | derive every input from the seed |
| The suite is green and a reviewer still finds blockers | the failure class has no representative: teardown faults, concurrency, interactions | add the missing class, not more cases of a covered one |
| `:malli.core/invalid-input/output` | args/return don't match `:malli/schema` | read the explain — fix the call or the schema, don't coerce |

## Generative checks stay inside the same suite

Malli generators do not create a third test mechanism. Put the property in a
normal `clojure.test` namespace and run it through `bin/test`.

Database isolation follows the operation:

- A **mutating state-transition property** creates a fresh connection inside
  each trial because commits from trial 1 would otherwise contaminate trial 2
  and every shrink step.
- A **pure property over one immutable database value** may build that value
  once outside `quick-check` and reuse it for every trial. Reopening 100
  connections adds setup but no isolation when the property never transacts.
  The planner acceptance property does exactly this with one `db/empty-db`
  value (`test/seon/datahike_fork_test.clj:12-49`; failure analysis:
  `docs/seon/issues/archive/datahike-planner-and-caches-carry-three-smaller-defects.md`
  “Skill evaluation” / `clojure-testing`).

Only the mutating case must exercise the complete EDN population → install →
transact → read-back boundary as the application.

A generator gate is three separate assertions, never one:

1. the generator constructs and runs at fixed seeds and several sizes;
2. every emitted value validates against the exact compiled schema/registry
   (`:gen/*` overrides REPLACE generation — Malli never checks their output);
3. owner-named domain partitions and recursive size bounds are exercised.

Every predicate schema needs an honest `:gen/schema`/`:gen/gen` —
`:gen/return`, `:gen/elements`, or a token placeholder do not satisfy the
house rule for an open domain. On failure print the schema key, seed, size,
generated value, explanation, and the complete shrunk check.

Function contracts split by what the property must observe:

- **One-call `[args result]` relations**: a three-child `:=>` guard; assert
  `(nil? (mi/check ...))` inside a discovered `deftest` with explicit
  `:data` — never populate or scan Malli's process-global function-schema
  registry, and never merely *call* `mi/check` (it returns failures; a suite
  must assert them). Malli 0.20.0 runs 100 trials and does not forward
  generator options.
- **State transitions** (idempotency, "twice", "after recovery", committed
  facts): explicit `(tc/quick-check ... :seed fixed-seed)` invoking the
  production boundary, observing database facts independently of returned
  values, asserting `(:result check)` and printing the complete check.

The state-transition shape that catches the most is a **model property**: a
pure model decides, for every generated command, whether the transition must
commit or refuse; the real database runs the same command; a disagreement in
either direction is a counterexample, and durable invariants are re-asserted
after every command. `test/seon/cluster/run_test.clj` implements exactly this.

### Four rules that decide whether the property is worth anything

1. **For MUTATING state transitions, one database per TRIAL, not per
   property.** Create the connection INSIDE `prop/for-all`. The pure model
   resets every trial and every shrink step, so the mutable test world it
   compares against must too. A pure property that only reads one immutable
   database value is the explicit exception above. Receipt: a sealed mutating
   property was unsatisfiable by *any* implementation because
   `with-model-database` wrapped `quick-check` instead of the body — from trial
   2 the oracle reasoned about an empty world against a database still holding
   trial 1's runs (`c2d3a96af`, fixed `1d6947069`).
2. **Every generated input is a function of the seed.** No `random-uuid`, no
   `System/currentTimeMillis`, no unordered-set iteration inside a property
   body. A `:seed` with a random input is decoration: the trial cannot be
   replayed and a shrunk counterexample cannot be reproduced. Receipt: a gate
   with time seeds produced 83 failures at 22:03 and 85 at 22:55 from identical
   source, so it could not distinguish a regression from a sample.
3. **Your coverage is the invariant checker, not the command generator.** A
   command whose resulting facts nothing observes buys runtime, not coverage.
   Receipt: a `:done` receipt could be upserted back to `:running` and both
   writes committed, while the green model property emitted receipt commands
   but never compared receipt facts with its own map. Extend the checker before
   extending the generator.
4. **The oracle must re-derive the invariant, not restate the
   implementation.** For run custody, presence of the exact process is the
   fence: absence means unheld; release, close, and source publication require the requesting
   process; claim may take unheld custody or recover custody from a process
   absent from the supplied live-process set. There is no epoch or lease.
   Derive that independently in the model, as the current oracle does
   (`test/seon/cluster/run_test.clj:1-1467`), then ask adversarially
   which implementation assumption the oracle may merely repeat.

### The classes properties do not reach

- **Teardown is untested code.** Nothing exercises a `finally`, `release!`, or
  `stop!` in anger. A cleanup path that guards an invariant — a fence, a lock,
  a lease — needs a fault injected into it and the invariant re-asserted, and
  it must fail CLOSED (retaining a fence is safe; dropping it is data loss).
  Receipt: a failed `d/release` propagated while its `finally` invalidated the
  flock, leaving a live writer with no cross-process fence.
- **Concurrency.** Sequential tests cannot see a generation race. Anything
  fenced by `(pid, start-instant)`, an epoch, or a generation gets a test that
  drives two operations at once — with latches, never sleeps. Receipt: a
  delayed `stop!` killed a replacement instance that had started in between.
- **Interactions between two covered halves.** Receipt: one test proved the
  in-process refusal, another proved the cross-process fence; neither did both
  at once, and the real bug was that `fcntl` drops every lock on a file when
  any descriptor closes, so performing the refusal silently unlocked the store
  (`test/seon/cluster/store_test.clj:1-533` is the admitted falsifier).

Live falsifiers — real sockets, real files, real child JVMs, real SIGKILL —
belong IN the suite, discovered by the runner: a proof that ran once in a lane
counts as not covered. Write one per interaction class, never one per scenario.
Wait on an observed event (a ready file, a latch); a clock is only the backstop
for a foreign process, and its firing is a bug report
(`test/seon/cluster/store_test.clj:1-533`).

Grounding and the pitfall catalog:
`docs/prds/sci-execution-runtime/research/malli-generative-patterns-2026-07-26.md`
and `research/spec-authorship-relational-properties-2026-07-26.md` (the guard
vs state-transition boundary).

## Structure dissolves failure classes

Before writing a test, ask which CLASS the failure belongs to and what
construction makes the class unrepresentable. Move the invariant to one choke
point — a total codec, an admission gate, a computed classification, a
transition that refuses inside the transaction — and keep ONE regression per
class. A pile of point tests fencing symptoms is the sign the invariant has no
owner.

## Tests are queryable program facts

Static indexing records direct first-party calls from each `:seon.test` row
through the shared cardinality-many `:seon.fn/calls` attribute.
`seon.fn/tests-reaching` derives direct and transitive dependent tests from
facts rather than naming conventions (`src/seon/fn.clj:292-323,402-439`;
`resources/seon/schemas/seon.test.edn:7-17`;
`test/seon/fn_test.clj:716-770`).

## Key test files

| File | What it teaches |
|---|---|
| `test/seon/cluster/run_test.clj` | the whole shape: fixture, deterministic clock, refusal-as-value, model-based state-machine property |
| `test/seon/cluster/boot_test.clj` | a live falsifier in-suite: real prepl sockets, project-local `tmp/` fixtures, a ruling (the ten-second bound) asserted as a test |
| `test/seon/cluster/store_test.clj` | cross-process falsifiers with a real child JVM, event-driven readiness, and the two-halves interaction test |
| `test/seon/flow/loop_test.clj` | exercising a `core.async.flow` graph from a test |
| `test/seon/concurrency_streams_test.clj` | latch-driven unique-namespace collision and 12-message ordering/loss proof (`:1-18,57-149`) |
| `test/seon/concurrency_independence_test.clj` | long N-agent, one-cluster fact-space harness (`:1-35,508-527`); currently red for two harness defects and not yet a passing gate |
| `reference-code/datahike/` | the fork's source — read it, don't guess semantics |

Full history — the buried harnesses, the eight root causes, and the testing
constitution: `docs/prds/sci-execution-runtime/research/testing-story-2026-07-27.md`.
