---
type: research
status: active
tags: [research, schema, testing, runtime]
---

# Spec authorship for relational properties

## Ruling

A two-child Malli function schema,

```clojure
[:=> input-schema output-schema]

```

is a shape contract. It does not state how the output must relate to the
particular input that produced it.

A three-child Malli function schema may add one pure, single-invocation
relation:

```clojure
[:=> input-schema output-schema guard-schema]

```

The guard validates `[args result]`. `malli.instrument` checks it on an
instrumented call, and `malli.instrument/check` checks it over generated valid
inputs. Neither mechanism invents a relation that the author did not put in
the guard.

State-transition properties remain explicit generated tests. Replay,
idempotency, exactly-one committed identity, crash recovery, concurrency, and
read-your-writes all compare more than one invocation or observe database
facts. Packing those effects into a guard would hide the real system boundary
and make a pure schema predicate perform capabilities. The house pattern is:

1. registered schemas define admissible values;
2. `:=>` defines input and output shapes;
3. an optional `:fn` guard defines a pure relation for one call; and
4. a `test.check` property generates a scenario, invokes the recurring
   production boundary, and asserts relations among outputs and facts.

An example test is documentation. A comment describing a property is prose.
Neither is generative contract evidence.

## Dependency ledger

| Dependency or mechanism | Selected source | Evidence used here |
|---|---|---|
| Malli | `metosin/malli 0.20.0`; vendored commit `80138076960e` | `reference-code/malli/src/malli/core.cljc`, `generator.cljc`, `instrument.clj` |
| test.check | `org.clojure/test.check 1.1.1` | Malli dependency plus Seon's existing `tc/quick-check` properties |
| Function instrumentation | Seon's database-derived program graph | `src/seon/instrument.cljc`; this audit relies on Malli semantics, not a second collector |
| Durable replay | `seon.db/transact!` → writer request receipt | `src/seon/db.cljc`, `src/seon/db/protocol.cljc`, `src/seon/db/writer.clj` |
| Executing-form identity | eval receipt and run-loop claim epoch | `src/seon/eval/receipt.cljc`, `src/seon/agent/run/core.cljc`, `src/seon/agent/driver.clj` |

Existing first-party property idioms are
`test/seon/db/codec_totality_test.cljc:58-83`,
`test/seon/repl/parse_test.cljc:1144-1165`, and
`test/seon/flow/loop_test.clj:488-507`. They use a normal `deftest`, a
`prop/for-all` property, a fixed replayable seed, and an assertion that prints
the shrunk counterexample.

## What Malli actually validates

### `:=>` construction

Malli constructs `:=>` from two or three children and records them as input,
output, and optional guard
(`reference-code/malli/src/malli/core.cljc:2138-2161`). The input must be
`:cat` or `:catn` (`core.cljc:2154-2155`).

Without a function checker, validating a function *as a value* proves only
that it is callable. With a function checker, the schema delegates behavioral
checking to that checker (`core.cljc:2163-2182`). Therefore this is not a
property test:

```clojure
(m/validate [:=> [:cat :int] :int] some-function)

```

unless the schema options install `malli.generator/function-checker`.

### Instrumented calls

The `:=>` wrapper validates:

- argument count and input shape;
- the returned value against the output shape; and
- `[args value]` against the guard, when one is present and guard scope is
  enabled.

Those are three separate checks in
`reference-code/malli/src/malli/core.cljc:2203-2221`.
`malli.core/-instrument` defaults scope to
`#{:input :output :guard}` (`core.cljc:3110-3130`). Malli's own test proves a
guard receives the arguments and result and reports `:malli.core/invalid-guard`
when their relation is false
(`reference-code/malli/test/malli/core_test.cljc:2717-2778`).

Thus this is relational:

```clojure
(defn result-has-same-id?
  [[[request] result]]
  (= (::id request) (::id result)))

[:=> [:cat ::request]
     ::result
     [:fn 'my.contract/result-has-same-id?]]

```

This is not:

```clojure
[:=> [:cat ::request] ::result]

```

even when both maps happen to contain an `::id` key.

### `mi/check`

`malli.instrument/check` selects registered function schemas and delegates each
one to `malli.generator/check`
(`reference-code/malli/src/malli/instrument.clj:125-134`).
`malli.generator/function-checker` then:

1. generates arguments from the input schema;
2. invokes the function;
3. validates the result against the output schema; and
4. validates `[args result]` against the optional guard.

The implementation is explicit at
`reference-code/malli/src/malli/generator.cljc:526-556`; default trial count is
100 at line 528. `malli.generator/check` merely installs that checker while
explaining a function (`generator.cljc:558-562`).

Consequences:

- With a two-child `:=>`, `mi/check` proves generated valid inputs do not throw
  and return output-shaped values. It proves no input→output relation.
- With a third-child guard, `mi/check` also proves that authored relation for
  the sampled calls.
- `mi/check` returns `nil` or a map of failures. A suite must assert that
  result; merely calling it is not a recurring test.
- Its input generator is only as strong as the input schemas and their
  generator overrides.
- It cannot observe database facts after the call unless the function itself
  returns them, and a pure guard must not query the database to compensate.

Malli's function generator makes the absence of inference especially clear:
for a `:=>` it generates a function that ignores all arguments and returns a
generated output value
(`reference-code/malli/src/malli/generator.cljc:312-317`). Input and output
schemas alone contain no relation to recover.

## Ruled authoring pattern

### Pure one-call relation

Use a named, total, capability-free predicate over `[args result]`. Register it
through the existing core-predicate mechanism when the contract must survive
program-graph persistence and SCI compilation. Keep the request and response
schemas independently useful.

```clojure
(defn same-operation?
  [[[request] result]]
  (= (:seon.capability/op-id request)
     (:seon.capability/op-id result)))

(schema/register-core-predicate!
 'seon.effect/same-operation?
 same-operation?)

(defn request!
  {:malli/schema
   [:=> [:cat ::request]
    ::result
    [:fn {:error/message "result must retain the request operation id"}
     'seon.effect/same-operation?]]}
  [request]
  ...)

```

The predicate may compare only the supplied immutable values. If proving it
would require a query, a clock, a random value, another invocation, or a
process failure, it is not a function guard.

### Explicit generated state-transition property

Keep the generator and property in the normal recurring test namespace:

```clojure
(defn- replay-property
  [invoke! observe-facts {:keys [op-id tx-data]}]
  (let [first-result (invoke! op-id tx-data)
        second-result (invoke! op-id tx-data)
        facts (observe-facts)]
    (and (= (:seon.db/t first-result)
            (:seon.db/t second-result))
         (true? (:seon.capability/replayed? second-result))
         (= 1 (count facts)))))

(deftest one-operation-commits-once
  (let [check
        (tc/quick-check
         100
         (prop/for-all [scenario scenario-generator]
           (with-fresh-system
             #(replay-property invoke! observe-facts scenario)))
         :seed 20260726)]
    (is (true? (:result check))
        (str "replay property failed: " (pr-str check)))))

```

Rules for this pattern:

- Generate the logical identity and domain inputs, not a mock's expected
  output.
- Invoke the same public/recurring boundary production uses.
- Observe committed facts independently of returned envelopes.
- Assert both the returned relation and the durable relation.
- Fix the gate seed and print the complete shrunk check on failure.
- Keep examples beside the property only when they teach an important call
  shape.
- Do not replace the writer with an atom that implements the desired replay
  semantics. A fake may test envelope plumbing, never durable idempotency.

### `mi/check` and explicit properties are complementary

Run `mi/check` when the unit is a pure or ordinary function schema and generated
input/output/guard checking is meaningful. Write an explicit property whenever
the success statement contains verbs such as “twice,” “after commit,” “after
resume,” “exactly one entity,” or “same basis.” The explicit property may use
Malli's input generator; it does not need a second schema system.

## Step-1 per-file audit

| File | Shape verdict | Relational-property verdict | Required contract action |
|---|---|---|---|
| `src/seon/effect.cljc` | Pass: closed request and success envelopes; errors remain flat values. | Incomplete: `request!` has a two-child `:=>` at line 190. No guard relates family/op-id in the request to the result. | A future contract revision may add a pure guard. The sealed contract cannot be changed now, so explicit tests must carry the relation. |
| `src/my/message.cljc` | Pass: request and concise result shapes are named and closed. | Not expressible as a one-call guard: “one committed message after two executions” is a database transition. | Explicit generated database property through the real message/effect path. |
| `src/my/db.cljc` | Pass for the ruled public shapes. | Not expressible at this function boundary: `transact` receives no op-id; the run loop injects it. The two-child schema at line 91 cannot relate an ambient identity to its output. | Generate executing-form context, call twice, compare bases/replay marker, and count committed facts. |
| `src/seon/agent/driver.clj` | Implementation support, not a contract source. | Fails the kill/resume relation: `effect-request-context` derives op-id with claim epoch at lines 672-689, while recovery increments that epoch in `src/seon/agent/run/core.cljc:176-190`. | Resolve under the effect-identity owner; do not weaken the property. |
| `src/seon/sci/ctx.clj` | Computed program-function input is the right source shape. | Structural property is absent from the sealed suite: deleting a function fact must delete its binding. | Explicit program-graph regression; no literal-list assertion. |
| `src/seon/agent/message.cljc` | Existing owner returns a concise message result. | Fails deterministic replay construction: line 515 reuses the op-id but lines 519-525 allocate a fresh message candidate for each call. Generated candidates participate in the logical transaction hash. | Derive or recover the same allocation from the logical effect identity; prove with the database property. |
| `src/seon/db.cljc` | Existing owner accepts the op-id and surfaces `replayed?`. | Partial evidence only: `test/seon/db/portable_test.cljc:236-258` tests a deterministic leaf, not the Step-1 family arm against the durable writer. | Keep that unit test and add the public Step-1 property. |
| `test/my/effect_contract_test.clj` | Passes examples and stub-era envelope assertions. | Fail: lines 51-71 contain two pending tests whose “real properties” are comments; neither uses a generator nor asserts committed facts. | Replace the stub assertions with executable generated properties and remove pending metadata. |

The source schemas remain sealed. This audit proposes no production edit.

## Proposed diffs, not applied

### Activate the property surface

The exact fixture should reuse the recurring writer/driver test owner rather
than duplicate database setup. The semantic change to
`test/my/effect_contract_test.clj` is:

```diff
 (ns my.effect-contract-test
   (:require [clojure.test :refer [deftest is testing]]
+            [clojure.test.check :as tc]
+            [clojure.test.check.generators :as gen]
+            [clojure.test.check.properties :as prop]
             ...))

-(deftest ^:seon.contract/pending message-identity-is-derived
-  ;; ACTIVATE ... property in prose
-  (testing "today: the stub cannot send at all ..."
-    (is (= :seon.effect/not-implemented ...))))
+(deftest message-identity-is-derived
+  (let [check
+        (tc/quick-check
+         100
+         (prop/for-all
+          [run-id nonblank-id-gen
+           ordinal gen/nat
+           epoch (gen/choose 1 1000000)]
+          (with-step1-system
+            #(let [context (executing-form-context run-id ordinal epoch)
+                   first (invoke-message! context)
+                   second (invoke-message! context)
+                   messages (messages-for context)]
+               (and (= (:seon.agent.message/id first)
+                       (:seon.agent.message/id second))
+                    (= 1 (count messages))))))
+         :seed 20260726)]
+    (is (true? (:result check))
+        (str "message identity property failed: " (pr-str check)))))

-(deftest ^:seon.contract/pending replay-identity-makes-writes-idempotent
-  ;; ACTIVATE ... property in prose
-  (testing "today: the stub cannot write at all"
-    (is (= :seon.effect/not-implemented ...))))
+(deftest replay-identity-makes-writes-idempotent
+  (let [check
+        (tc/quick-check
+         100
+         (prop/for-all
+          [op-id nonblank-id-gen
+           value gen/int]
+          (with-step1-system
+            #(let [tx-data [{:step1.property/id op-id
+                             :step1.property/value value}]
+                   first (invoke-transact! op-id tx-data)
+                   second (invoke-transact! op-id tx-data)]
+               (and (= (:seon.db/t first) (:seon.db/t second))
+                    (true? (:seon.capability/replayed? second))
+                    (= [[value]] (facts-for op-id))))))
+         :seed 20260726)]
+    (is (true? (:result check))
+        (str "transaction replay property failed: " (pr-str check)))))

```

`with-step1-system`, `executing-form-context`, and the observation queries in
the sketch name responsibilities, not a license for a new test mechanism.
They should be narrow helpers over the existing writer and run-loop fixtures.

### Optional pure guard in a future contract revision

This is not allowed while the Step-1 schema is sealed. It shows where the
single-call relation belongs when the next contract is authored:

```diff
 (defn request!
-  {:malli/schema [:=> [:cat ::request] ::result]}
+  {:malli/schema
+   [:=> [:cat ::request]
+    ::result
+    [:fn 'seon.effect/result-retains-request-identity?]]}
   [request]
   ...)

```

The guard would cover only a successful result retaining the request family
and op-id. It would not replace either replay property.

## Contract frictions exposed

1. The sealed schemas contain no relational guard. That is legitimate to work
   around only with stronger explicit properties, never by loosening a schema.
2. The specified `(run, ordinal, epoch)` identity is stable only within one
   claim epoch. Kill/resume increments epoch, so the same unfinished form gets
   a different op-id.
3. One op-id per form cannot address two different effects executed by that
   form. The writer correctly treats one request id plus different logical
   transaction hashes as a conflict.
4. Message allocation occurs before the writer hashes the logical request, and
   generated candidates are part of that hash
   (`src/seon/db/protocol.cljc:2077-2089`). Retrying with the same op-id and a
   fresh message candidate is not the same logical request.

These are contract/implementation frictions to resolve at the identity owner.
They are not reasons to weaken the two required properties.
