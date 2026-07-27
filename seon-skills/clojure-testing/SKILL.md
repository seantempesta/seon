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
> **`datahike`**; what shape to register and why → **`data-modeling`**;
> errors-as-values / no-bare-keys mindset → **`data-oriented-clojure`**.

**The CLJS build is OFF** (owner ruling 2026-07-27) — `bin/test-cljs` and
`bin/test-writer` serve the `src-old/`/`test-old/` quarry and are NOT the gate.
Nothing new goes there, and a `cljs.test` namespace is not a Seon test today.

## Running

```bash
bin/test                        # every *_test.clj / *_test.cljc under test/
bin/test seon.cluster.run-test  # exactly these namespaces
```

Source classpath, in-memory Datahike, no artifact and no operator — seconds per
cycle, and the exit code is the verdict. Use the selection while iterating and
the full run at the natural unit boundary. There is no separate build step to
wait on and no live process to contend with.

`bin/test` discovers a namespace by file name: a test file must end in
`_test.clj` or `_test.cljc` under `test/`, mirroring its `src/` namespace. A
test in any other location is invisible to the gate, which means **not
covered** however green it looked when you ran it by hand.

## Fresh in-memory Datahike per test

Every database test opens its own `:memory` store (a fresh random `:id`, so
tests never see each other's data), installs the attributes it needs, and
releases in a `finally`. There is no ambient connection and nothing to `set!` —
pass the connection.

```clojure
(ns seon.cluster.run-test
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.schema.datahike :as schema.datahike]))

(def ^:private model-attributes
  [:seon.cluster.run/id :seon.cluster.run/opened-at :seon.cluster.run/closed-at])

(defn- with-model-database [body]
  (let [configuration {:store {:backend :memory :id (random-uuid)}
                       :schema-flexibility :write}
        _ (d/create-database configuration)
        connection (d/connect configuration)]
    (try
      (d/transact connection
                  (schema.datahike/malli->datahike-schema model-attributes))
      (body connection)
      (finally
        (d/release connection)
        (d/delete-database configuration)))))
```

**Attributes must be INSTALLED before they are transacted.** Under
`:schema-flexibility :write` a registered-but-uninstalled attribute throws
`Bad entity attribute … not defined in current schema` (REPL-verified
2026-07-27). `schema/register!` teaches the Malli registry; transacting
`(schema.datahike/malli->datahike-schema attrs)` is what teaches the database.
Listing the attributes explicitly, as above, is a feature: the list is the
test's declared surface, and a missing entry fails loudly instead of silently
widening.

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
    (with-model-database
      (fn [connection]
        (d/transact connection [{::name "Alpha"}])
        (is (= "Alpha" (d/q '[:find ?n . :where [_ ::name ?n]] @connection)))))))
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

## Common failure patterns

| Symptom | Likely cause | Fix |
|---|---|---|
| `Bad entity attribute … not defined in current schema` | attribute registered but not installed on this connection | add it to the fixture's attribute list |
| "Unregistered attributes" from a Seon boundary | missing `schema/register!` | register it in the owning ns |
| Empty `#{}` from a query that should match | attr misspelled, type mismatch, or a ref-join written as keyword-in-slot | see the `datahike` skill's read traps |
| A property passes but the code is wrong | the property observes only the returned value | observe durable facts independently of the return |
| Tests pass alone, fail together | the fixture shares one store across trials | fresh `:id` per test AND per generative trial |
| `:malli.core/invalid-input/output` | args/return don't match `:malli/schema` | read the explain — fix the call or the schema, don't coerce |

## Generative checks stay inside the same suite

Malli generators do not create a third test mechanism. Put the property in a
normal `clojure.test` namespace and run it through `bin/test`. Database
properties use a fresh connection and exercise the same
`schema/register!` → install → transact → read-back boundary as the
application.

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

Grounding and the pitfall catalog:
`docs/prds/sci-execution-runtime/research/malli-generative-patterns-2026-07-26.md`
+ `research/spec-authorship-relational-properties-2026-07-26.md` (the guard
vs state-transition boundary).

## Structure dissolves failure classes

Before writing a test, ask which CLASS the failure belongs to and what
construction makes the class unrepresentable. Move the invariant to one choke
point — a total codec, an admission gate, a computed classification, a
transition that refuses inside the transaction — and keep ONE regression per
class. A pile of point tests fencing symptoms is the sign the invariant has no
owner.

## Key test files

| File | What it teaches |
|---|---|
| `test/seon/cluster/run_test.clj` | the whole shape: fixture, deterministic clock, refusal-as-value, model-based state-machine property |
| `test/seon/flow/loop_test.clj` | exercising a `core.async.flow` graph from a test |
| `reference-code/datahike/` | the fork's source — read it, don't guess semantics |
