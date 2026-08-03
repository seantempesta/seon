---
type: research
status: active
tags: [research, schema, testing, runtime]
---

# Malli patterns for validation and generative testing

## Ruling

A Seon schema is generatively testable only when all three statements are
true:

1. Malli can construct and run a generator for it on every runtime tier that
   owns a recurring test.
2. Every generated value validates against the same compiled schema and
   registry projection.
3. The generator explores the meaningful partitions of the accepted domain;
   it is not one convenient satisfying value attached merely to make
   `mg/generator` stop throwing.

For a predicate schema, write the contract as:

```clojure
[:fn {:error/message "..."
      :gen/schema honest-source-schema
      ;; Optional. It transforms source values into predicate values.
      :gen/fmap 'honest-data-only-transformation}
 'fully.qualified/predicate?]

```

or, only at a non-persisted boundary that can carry a real test.check
generator:

```clojure
[:fn {:gen/gen honest-test-check-generator}
 'fully.qualified/predicate?]

```

Every `[:fn ...]` predicate schema MUST carry an honest `:gen/schema` or
`:gen/gen`. In a `schema/register!` form, `:gen/schema` is the normal choice:
registered forms are canonical database facts and must round-trip as EDN
(`src/seon/schema.cljc:739-813`), while a test.check generator contains
function objects and is not durable data.

“Honest” has two requirements:

- **soundness** — every value the override can emit is accepted by the
  predicate. If `G` is the generator output domain and `A` is the predicate
  acceptance set, `G ⊆ A`;
- **useful coverage** — the generator is built from the domain's real
  partitions and boundaries. A single canned value is not an honest generator
  for an open domain merely because that value passes.

Soundness is verified in a recurring test by generating values and validating
every one against the predicate schema. For finite generators, exhaust the
domain. For open generators, soundness also needs an author-readable
construction argument; sampling can falsify subset membership but cannot prove
an infinite subset. Useful coverage is reviewed from the generator's shape and
asserted with named partition checks where necessary.

The ruled relational-property split remains unchanged:

1. registered schemas define admissible values;
2. `:=>` defines input and output shapes;
3. an optional third child, a pure `:fn` guard, validates one call's
   `[args result]` relation; and
4. explicit test.check properties own relations across calls or committed
   database facts.

This extends, and does not replace,
`spec-authorship-relational-properties-2026-07-26.md`.

## Dependency ledger

| Dependency or mechanism | Selected source | Grounded behavior |
|---|---|---|
| Malli | `metosin/malli 0.20.0`; vendored commit `80138076960e7820523b4cb932c5b5d1936d4e7f` | Generator selection, recursive generation, function checking, and instrumentation in `reference-code/malli/src/malli/{generator,core}.cljc` |
| test.check | `org.clojure/test.check 1.1.1` | Generators and `quick-check`; selected by Malli in `reference-code/malli/deps.edn:5` |
| Canonical schema registration | `seon.schema/register!` | EDN-round-trippable forms collected and later activated as one database-derived projection; `src/seon/schema.cljc:739-813` |
| Predicate binding | `seon.schema/register-core-predicate!` | Durable forms retain qualified symbols; the process-local cache supplies admitted callables during compilation; `src/seon/schema.cljc:310-355` |
| Existing property gate | Seon boundary suites | Fixed seed, `prop/for-all`, normal `deftest`, and complete shrunk result in `test/seon/db/codec_totality_test.cljc:58-83`, `test/seon/repl/parse_test.cljc:1144-1175`, and `test/seon/flow/loop_test.clj:488-507` |

## What Malli actually does

### Override selection is replacement, not validation

Malli merges type and instance properties, then chooses the first available
generator source in this order:

1. `:gen/return`;
2. `:gen/elements`;
3. `:gen/schema`;
4. `:gen/gen`; or
5. the schema type's built-in generator.

It then applies `:gen/fmap`, if present
(`reference-code/malli/src/malli/generator.cljc:455-490`).

Nothing in that selection validates the override's output against the original
schema. Upstream deliberately demonstrates this separation:

```clojure
(mg/sample [:and {:gen/return nil} int?] {:size 1000})
;; every value is nil, even though nil does not satisfy int?

```

The assertion is at
`reference-code/malli/test/malli/generator_test.cljc:237-240`. Upstream also
shows `:gen/schema`, `:gen/gen`, `:gen/elements`, and `:gen/fmap` as independent
overrides at lines 224-253. Therefore “Malli generated it” is not evidence that
the value validates.

`:gen/fmap` is downstream of every source. Its output, rather than its input,
must satisfy the predicate. A sound `:gen/schema` can become dishonest through
an unsound `:gen/fmap`. An EDN code form in `:gen/fmap` is evaluated through
Malli's optional SCI evaluator
(`reference-code/malli/src/malli/core.cljc:2881-2900`); the recurring generator
runner must include SCI. The selected
`bin/test-writer` classpath composes the `:host` alias for that reason, while a
bare `-M:writer` probe correctly reports `:malli.core/sci-not-available` for
the quoted mapper.

### Built-in schemas generally generate by construction

Malli directly derives generators for maps, collections, enums, tuples,
strings, numbers, and regex sequence expressions. For example, string length
constraints are passed to a vector of alphanumeric characters
(`generator.cljc:97-116`), and maps combine generators for their entries
(`generator.cljc:166-185`).

These schemas still need the standing generate-then-validate test. Upstream's
own principal generator test checks both deterministic generation and
`m/validate` over every sample
(`reference-code/malli/test/malli/generator_test.cljc:20-51`).

### Predicate schemas do not acquire a generator from the predicate

The default generator route asks spec/test.check for a generator for a known
predicate. Some symbols work and some do not. In the selected dependency:

- `pos-int?` and `some?` generate;
- Malli has a special generator for `ifn?`;
- `fn?` has no generator; and
- a general `[:fn predicate]` has no generator.

Upstream asserts `:malli.generator/no-generator` for a bare `[:fn ...]` at
`reference-code/malli/test/malli/generator_test.cljc:203-207`.

Relying on an incidental spec generator is not the house pattern. A named
predicate owns its generator explicitly so the accepted and generated domains
are reviewable together.

### `:=>` and `mi/check`

Malli constructs `:=>` from two or three children. The input must be `:cat` or
`:catn`; the optional third child is the guard
(`reference-code/malli/src/malli/core.cljc:2138-2202`).

Instrumentation defaults to `#{:input :output :guard}` and validates the guard
against `[args value]` after the call
(`reference-code/malli/src/malli/core.cljc:2203-2221,3110-3131`).

`malli.generator/function-checker`:

1. generates arguments from the input schema;
2. applies the function;
3. validates the output; and
4. validates `[args result]` with the guard when present.

The implementation is
`reference-code/malli/src/malli/generator.cljc:526-556`; its default is 100
trials. `malli.instrument/check` applies that checker to selected registered
function Vars and returns `nil` or a map from symbol to explanation
(`reference-code/malli/src/malli/instrument.clj:125-134`).

The check must be asserted. Calling it without an `is` can silently leave a
failure map unused. In the selected version, `mi/check` does not forward
generator options to `mg/check`
(`reference-code/malli/src/malli/instrument.clj:125-134` and
`instrument.cljs:137-146`). It therefore uses the function checker's fixed
default of 100 trials and cannot accept a fixed test.check seed. Preserve the
returned failure, which contains shrinking evidence. Use explicit
`tc/quick-check` with a fixed seed for state-transition properties and any gate
where exact replay is required.

Malli's generator for a function schema proves that input and output schemas
contain no inferred relation: it creates a function that ignores its arguments
and returns a generated output
(`reference-code/malli/src/malli/generator.cljc:312-317`).

## Ruled authoring pattern

### Value schema

Prefer an ordinary structural schema. It is both clearer and more generative
than a predicate:

```clojure
;; Prefer this.
[:string {:min 1 :max 200}]

;; Do not restate it as a predicate plus a custom generator.
[:fn {:gen/schema [:string {:min 1 :max 200}]}
 'my.domain/nonblank-short-string?]

```

Use `:fn` only when the acceptance rule cannot be expressed structurally. Then:

1. define one named, total, pure predicate;
2. register its qualified symbol with `register-core-predicate!`;
3. derive an EDN-readable `:gen/schema` from the actual accepted domain;
4. use `:gen/fmap` only for a total data-to-data construction;
5. validate every generated output against the full predicate schema; and
6. assert useful partitions, not only soundness.

`src/seon/db/protocol.cljc:212-218` is a sound starting example:

```clojure
(schema/register!
 ::ordinary-wire-value
 [:fn {:error/message "must be eager ordinary protocol data"
       :gen/schema
       [:or :nil :boolean :int :double :string :keyword :symbol
        [:vector {:max 8}
         [:or :nil :boolean :int :string :keyword]]]}
  'seon.db.protocol/ordinary-wire-value?])

```

Every arm is accepted by `ordinary-wire-value?`. The generator is sound, but it
is not complete: the predicate also accepts maps, sets, lists, UUIDs, instants,
and tier-specific values. A contract package that depends on those partitions
must extend the generator or add named arm properties. Soundness does not mean
coverage.

`src/seon/effect.cljc:46-55` similarly gives `::args` and `::value` a
sound-but-narrow map-of-keyword-to-string generator even though
`ordinary-request-value?` accepts a recursive ordinary-data domain at lines
17-31. It generates, but a totality claim over all accepted request values
would be overstated.

### Finite accepted domains

When the accepted domain is genuinely finite, express it as `:gen/schema`
instead of the lower-level `:gen/elements`:

```clojure
[:fn {:gen/schema [:enum 1 "tempid"]}
 'seon.db.protocol/datahike-id?]

```

The current schema at `src/seon/db/protocol.cljc:405-409` uses
`:gen/elements [1 "tempid"]`. Both elements validate, so it is sound, but it
does not satisfy the new canonical predicate-authoring form. Better still,
because the predicate accepts the structural union, use the useful open
generator:

```clojure
[:fn {:gen/schema [:or :int :string]}
 'seon.db.protocol/datahike-id?]

```

A finite enum is useful only if the domain is finite or its members deliberately
cover named partitions. The pull selector and query schemas at
`src/seon/db/protocol.cljc:436-449,480-488` contain several useful grammar
examples, but examples are not a grammar generator. They support smoke
coverage, not a claim that the open Datahike grammar has been generated.

### Regex-constrained strings

On the selected classpath, an un-overridden CLJ `:re` generator requires
test.chuck; CLJS does not provide Malli's regex generator. Upstream explicitly
skips regex generation in CLJS and tests the missing dependency in CLJ
(`reference-code/malli/test/malli/generator_test.cljc:27-28,172-201`).

Do not assume a validating regex schema generates on both tiers. Generate the
language structurally:

```clojure
[:re
 {:gen/schema
  [:vector {:min 64 :max 64}
   [:enum \0 \1 \2 \3 \4 \5 \6 \7 \8 \9 \a \b \c \d \e \f]]
  :gen/fmap '(fn [characters] (apply str characters))}
 "^[0-9a-f]{64}$"]

```

`src/seon/dev/restore/schema.cljc:18-22` currently makes its digest constructible
with two valid `:gen/elements`; this is sound but covers only the all-zero and
all-`f` cases. `src/my/blob/schema.cljc:19-24`,
`src/seon/launch.cljc:26-37`, and
`src/seon/runtime/lifecycle.cljc:11-14` have un-overridden regex schemas and
therefore cannot support the same cross-tier generator claim.

A length constraint is not a semantic digest constraint.
`src/seon/embed.clj:902-912` registers `:seon.embed/source-hash` as any
64-character string. Malli generates a valid 64-character alphanumeric string,
but most values are not hexadecimal. Generation is aligned with that authored
validator; the authored validator is too open for any consumer that assumes a
SHA-256 hex digest.

### Recursive schemas

Use Malli `:schema` plus `:ref` and preserve a reachable base case. The
`::plan-node` shape in `src/my/plan.cljc:95-104` is well formed for generation:
recursive children are optional, so the map without `::children` is a base
case.

Malli's implementation warns that naïvely dereferencing and truncating refs
creates exponentially large values. Its `-ref-gen` instead identifies a ref by
name plus dynamic scope and uses test.check `recursive-gen`
(`reference-code/malli/src/malli/generator.cljc:27-51,205-310`).

Do not defeat that mechanism with a hand-recursive `:gen/gen`. Do not make
every recursive arm non-empty. Upstream records that `:gen/min 2` on a
recursive vector makes a sample schema infinite
(`reference-code/malli/test/malli/generator_test.cljc:388-395`), and asserts
that recursive schemas without a base case throw at lines 712-723.

The standing test must generate recursive schemas at several explicit sizes,
validate every result, and cap a measurable projection such as node count or
encoded bytes. A schema can be valid and generative while still producing
impractically large fixtures.

### One-call relation with a generative guard

`seon.effect/op-id` currently has a real three-argument input schema and string
output at `src/seon/effect.cljc:90-97`. A contract package can strengthen that
two-child schema with a pure relation:

```clojure
(defn op-id-result?
  "Whether `result` is the replay identity for the supplied coordinates."
  [[[run-id form-ordinal effect-ordinal] result]]
  (= result (pr-str [run-id form-ordinal effect-ordinal])))

(schema/register-core-predicate!
 'seon.effect/op-id-result?
 op-id-result?)

(defn op-id
  {:malli/schema
   [:=> [:catn [::run-id [:string {:min 1}]]
                 [::form-ordinal [:int {:min 0}]]
                 [::effect-ordinal [:int {:min 0}]]]
    [:string {:min 1}]
    [:fn
     {:error/message "must equal the replay identity for these coordinates"
      :gen/schema
      [:tuple [:string {:min 1}]
       [:int {:min 0}]
       [:int {:min 0}]]
      :gen/fmap
      '(fn [[run-id form-ordinal effect-ordinal]]
         [[run-id form-ordinal effect-ordinal]
          (pr-str [run-id form-ordinal effect-ordinal])])}
     'seon.effect/op-id-result?]]}
  [run-id form-ordinal effect-ordinal]
  (pr-str [run-id form-ordinal effect-ordinal]))

```

The guard generator is honest: it derives `[args result]` pairs by the same
pure data relation and every emitted pair satisfies the guard. It is not used
to generate the function check's arguments; `mi/check` generates those from
the input schema. The guard override exists because every predicate schema is
independently constructible and lintable.

The recurring CLJ test asserts `mi/check` rather than discarding its result.
It also passes explicit `:data`. Seon intentionally never populates Malli's
process-global function-schema registry
(`src/seon/instrument.cljc:1-9`), so `mi/collect!` would create a second
authority.

```clojure
(ns seon.effect-generative-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.walk :as walk]
            [malli.core :as m]
            [malli.instrument :as mi]
            [seon.effect :as effect]))

(deftest op-id-satisfies-its-generative-contract
  (let [;; Compile the one canonical metadata form while supplying the admitted
        ;; predicate callable that its durable qualified symbol names.
        compiled
        (m/function-schema
         (walk/postwalk-replace
          {'seon.effect/op-id-result? effect/op-id-result?}
          (:malli/schema (meta #'effect/op-id))))
        failures
        (mi/check
         {:data
          {'seon.effect
           {'op-id
            {:schema compiled
             :ns 'seon.effect
             :name 'op-id}}}})]
    (is (nil? failures)
        (str "op-id generative contract failed: " (pr-str failures)))))

```

The test-side replacement is compilation, not a second contract: the schema is
read from the function's canonical metadata and the map supplies exactly the
callable named by its durable symbol. A reusable standing test should derive
that binding map from the active projection's predicate functions.

This check covers 100 generated valid inputs, output validation, and the
one-call guard. It does not cover replay, a second call, or committed facts.
Those remain explicit seeded `tc/quick-check` properties under the ruled
pattern in `spec-authorship-relational-properties-2026-07-26.md`.

## Representative Seon audit

The source tree currently has 111 files containing `schema/register!`. This is
a representative audit, not a waiver for unlisted registrations; the future
standing lint must enumerate `schema/registered-schemas`.

| Source | Generation verdict | Contract verdict |
|---|---|---|
| `src/seon/db/protocol.cljc:212-218` | Generates and samples validate | Sound `:gen/schema`; accepted-domain coverage is incomplete |
| `src/seon/ai/tokens.cljc:41-47` | Generates and samples validate | Sound ordinary printable subset; deliberately narrower than the predicate |
| `src/seon/sci/eval.clj:130-149` | Generates and samples validate | Useful union covering success values and complete error maps |
| `src/seon/db/protocol.cljc:405-409` | `:gen/elements` generates valid values | Sound, but migrate to canonical `:gen/schema`; current coverage is narrow |
| `src/seon/db/protocol.cljc:436-449,480-488` | Curated elements generate valid grammar examples | Examples do not generate the open pull/query grammars |
| `src/seon/dev/restore/schema.cljc:10-22` | Overrides make regex shapes constructible | Sound boundary elements; too narrow for general digest coverage |
| `src/my/plan.cljc:95-104` | Optional recursive child supplies a base case | Good recursive shape; needs size-bounded standing samples |
| `src/seon/flow.clj:57-60,73-75` | Bare `[:fn predicate]` has no generator | Fails the ruled predicate pattern |
| `src/seon/flow.clj:63-72,102-105` and other raw `'fn?` registrations | `fn?` has no Malli generator | These callback contracts should use function schemas or be excluded from generative contract packages with an explicit runtime-boundary reason |
| `src/my/blob/schema.cljc:20`, `src/seon/launch.cljc:26-37` | Un-overridden regex generation is unavailable on the selected JVM and in CLJS | Add a structural cross-tier generator |
| `src/seon/embed.clj:906` | Generates values valid under the authored length-only schema | The validator is too open if the value is semantically a hexadecimal digest |
| `src/seon/effect.cljc:65-74` | The counter override is `[= nil]`, which fails Malli construction; replacing it with the constructible `:nil` generator would still emit a value that fails `invocation-counter?` | Malformed and dishonest token placeholder; a real atom cannot be durable generated data, so redesign the contract boundary rather than fake one |

The last row is the clearest conversion test. `::request-context` mixes durable
coordinates with an invocation-local atom. There is no honest EDN
`:gen/schema` for a JVM/CLJS atom. The right repair is not a better fake atom;
it is to keep the pure coordinates in a generative data contract and construct
the process-local counter at the runtime owner.

## Pitfall catalog

### Generator construction is not generation

`mg/generator` can return a generator that later fails for a particular size.
Recursive unreachable cases and `such-that` exhaustion fail during generation.
The lint must call `mg/generate`, not merely construct the generator.

### `such-that` gives up

Malli implements `:and`, `:not`, and several qualified predicates by filtering
a base generator. Its `such-that` wrapper allows 100 attempts and then throws
`:malli.generator/such-that-failure`
(`reference-code/malli/src/malli/generator.cljc:127-131`).

Upstream demonstrates the failure with `[:and pos? neg?]` and `[:not :any]`
(`reference-code/malli/test/malli/generator_test.cljc:1063-1079`).

Do not encode a sparse domain as a broad generator plus a predicate filter.
Construct the accepted domain directly with `:gen/schema` or `:gen/gen`.

### Distinct collections can also give up

Sets and map-of generators use a 100-attempt distinctness budget
(`generator.cljc:106-113`). A set requiring two instances of `[= 1]` and a
map requiring two identical keys throw
`:malli.generator/distinct-generator-failure`
(`generator_test.cljc:1068-1075`). Cardinality constraints must be satisfiable
by the child generator's actual diversity.

### Recursive blowup and missing base cases

The built-in ref algorithm exists specifically to avoid naïve exponential
growth. A custom recursive generator can reintroduce that failure. A recursive
schema with no reachable scalar/base case is unsatisfiable, while positive
minimums can force infinite expansion. Preserve the built-in algorithm, test
several sizes, and assert a size projection.

### Generator drift is silent

`:gen/schema`, `:gen/gen`, `:gen/elements`, `:gen/return`, and `:gen/fmap`
replace or transform generation without an automatic post-validation filter.
The exact probe:

```clojure
(let [s [:fn {:gen/schema [:= 0]} pos?]
      v (mg/generate s {:seed 7})]
  {:value v :valid? (m/validate s v)})
{:value 0, :valid? false}

```

The override generated successfully. Only the explicit validator exposed the
lie.

### Canned values can green-wash an open domain

A valid `:gen/return` or two valid `:gen/elements` values make construction
green but may exercise none of the boundaries a consumer relies on. Treat
partition coverage separately from soundness. A singleton generator is honest
only for a singleton domain or a test explicitly named as an example/smoke
case, never as the schema's general generator.

### Regex support is tier-dependent

The selected JVM classpath produced:

```clojure
(mg/generate [:re "^[0-9a-f]{64}$"] {:seed 7})
;; throws :test-chuck-not-available

(mg/generate
 [:re {:gen/elements
       ["0000000000000000000000000000000000000000000000000000000000000000"]}
 "^[0-9a-f]{64}$"]
 {:seed 7})
"0000000000000000000000000000000000000000000000000000000000000000"

```

CLJS has no built-in regex generator in this Malli version. Cross-tier schemas
must own a structural override.

### Open structural schemas can be semantically weak

The selected string generator is alphanumeric and honors length. The probe:

```clojure
(let [v (mg/generate [:string {:min 64 :max 64}] {:seed 7})]
  {:valid? (m/validate [:string {:min 64 :max 64}] v)
   :hex? (boolean (re-matches #"[0-9a-f]{64}" v))
   :value v})
{:valid? true,
 :hex? false,
 :value "RTySbMo08C00Za6qusTAglS0L69D361GiaZ7hyvR2uPrAZ6D0oLkeXV2766PI4GE"}

```

This is not generator drift. It is evidence that the validator itself does not
state the semantic domain.

## Mechanical lint

The future standing test should consume the one canonical registry projection,
not scan source text as its authority:

```clojure
(let [projection
      (or (schema/current-projection)
          (schema/build-projection (schema/registered-schemas)))
      options (:seon.schema.projection/compile-options projection)]
  ...)

```

For every entry in `schema/registered-schemas` and every canonical function
contract in `:seon.schema.projection/function-contracts`, it should:

1. compile the schema with `options`;
2. walk the compiled schema, including refs, and reject every `:fn` node whose
   properties lack `:gen/schema` and `:gen/gen`;
3. reject persisted `:gen/gen` values that are not EDN-round-trippable;
4. construct its generator;
5. generate at a fixed matrix of seeds and sizes, including size 0, 1, a normal
   size, and a larger recursive size;
6. validate every generated value against the exact compiled schema and report
   the registry key, seed, size, generated value, and `m/explain`;
7. fail on generator construction, `such-that`, distinctness, unreachable, and
   regex dependency errors; and
8. enforce owner-declared coverage checks for open predicate domains and
   recursively assert a bounded size projection.

Construction and sample validation detect “non-generating” and
“dishonestly-generating” schemas mechanically:

```clojure
(defn generated-value-failure
  [schema-key compiled options seed size]
  (try
    (let [value (mg/generate compiled {:seed seed :size size})]
      (when-not (m/validate compiled value options)
        {:seon.schema/key schema-key
         :seon.schema/seed seed
         :seon.schema/size size
         :seon.schema/value value
         :seon.schema/explain (m/explain compiled value options)}))
    (catch Throwable error
      {:seon.schema/key schema-key
       :seon.schema/seed seed
       :seon.schema/size size
       :seon.schema/generator-error (ex-message error)
       :seon.schema/generator-data (ex-data error)})))

```

The final standing property should assert that the collection of failures is
empty and print the complete collection. It should live in a namespace
discovered by both relevant runners when the registry is portable. A CLJ-only
pass cannot certify a CLJS regex or predicate generator.

The lint must not automatically exempt “runtime object” schemas. An exemption
would hide the exact contract debt it is meant to expose. A callback can
usually be expressed as `:=>`/`:function`, for which Malli can generate
instrumented functions. A native handle that cannot be constructed from data
belongs at a platform boundary; its owning package must either supply an honest
runtime generator or state why that schema is validation-only and outside the
generative contract package.

## Verified probes

No probe script was created. These forms were evaluated directly with
`clojure -M:writer -e` and, for the EDN `:gen/fmap`, the normal
`clojure -M:writer:host -e` composition against the selected dependency:

```clojure
;; Bare predicate.
(mg/generate [:fn (fn [x] (instance? java.util.concurrent.Executor x))]
             {:seed 7})
;; throws :malli.generator/no-generator

;; Unsatisfiable filter.
(mg/generate [:and pos? neg?] {:seed 7})
;; throws :malli.generator/such-that-failure with :max-tries 100

;; Optional recursive child, matching src/my/plan.cljc:95-104.
(let [schema
      [:schema
       {:registry
        {:user/node
         [:map
          [:title [:string {:min 1}]]
          [:children {:optional true}
           [:vector [:ref :user/node]]]]}}
       [:ref :user/node]]
      values (vec (mg/sample schema {:seed 7 :size 20}))]
  {:all-valid? (every? #(m/validate schema %) values)
   :sample-count (count values)
   :max-printed-chars (apply max (map (comp count pr-str) values))})
{:all-valid? true, :sample-count 20, :max-printed-chars 184}

```

The relational example was also executed as a test-local function schema:

```clojure
{:guard-all-valid? true
 :mi-check nil
 :guard-samples
 ([["0" 1 1] "[\"0\" 1 1]"]
  [["g" 1 0] "[\"g\" 1 0]"]
  [["Wg5" 0 1] "[\"Wg5\" 0 1]"])}

```

This proves the worked guard generator is sound for the sampled values and that
the filtered `mi/check` invocation succeeds. It does not substitute for the
recurring `deftest`.

## Proposed skill edits, not applied

### `.agents/skills/data-modeling/SKILL.md`

```diff
 ## The schema IS the generator — generative testing

-A registered schema yields a test.check generator for free
-(`malli.generator/generate`, `/sample`) — so the data model *drives* its own
-tests.
+A registered schema is a generator contract only after construction and
+generate-then-validate have passed on every owning tier. Malli generator
+overrides replace generation; Malli does not prove their output satisfies the
+original schema.
+
+Every `[:fn ...]` predicate schema MUST carry an honest `:gen/schema` or
+`:gen/gen`. Prefer EDN-readable `:gen/schema` in `schema/register!` forms;
+registered forms are database facts, while test.check generator objects are
+not durable data. Honest means every emitted value satisfies the predicate and
+the generator covers meaningful domain partitions. A canned satisfier is not
+an honest generator for an open domain.
+
+After authoring a predicate schema, add a recurring property that generates at
+fixed seeds/sizes and validates every value against the same compiled registry
+projection. Fail on construction, generation, or validation. Treat
+`:gen/fmap` output as the value that must validate.
+
+Use a three-child `:=>` only for a pure relation over one `[args result]`
+pair. Give that guard's `:fn` its own honest generator. Relations involving
+two calls, a commit, replay, resume, or observed facts remain explicit seeded
+test.check state-transition properties.
+
+Regex generation is tier-dependent in Malli 0.20.0. Cross-tier `:re` schemas
+own a structural generator. Recursive schemas retain a reachable base case and
+are sampled at several sizes with an asserted size bound.

-(mg/generate ::source-entity)
-(mg/sample   ::rating 5)
+(let [values (mg/sample ::source-entity {:seed 20260726 :size 50})]
+  (assert (every? #(m/validate ::source-entity %) values)))

```

Also replace any wording that says a schema “yields a generator for free” with
“is eligible for the standing generator contract”; the present wording hides
bare `:fn`, `fn?`, regex, recursive, and dishonest-override failures.

### `.agents/skills/clojure-testing/SKILL.md`

```diff
 ## Generative checks stay inside the same suite

 Malli generators work in ClojureScript (`mg/generate`, `mg/sample`), but they do
 not create a third test mechanism. Put the property in a normal `cljs.test`
 namespace and run it through `bin/test-cljs`.
+
+A generator gate has three separate assertions:
+
+1. the generator constructs and runs at fixed seeds and several sizes;
+2. every emitted value validates against the exact compiled schema/registry;
+3. owner-named partitions and recursive size bounds are exercised.
+
+For every predicate schema, statically require an honest `:gen/schema` or
+`:gen/gen`; `:gen/elements`, `:gen/return`, and a token placeholder do not
+satisfy the house rule for an open predicate domain. Print the schema key,
+seed, size, generated value, explanation, and complete shrunk check on failure.
+
+For ordinary or pure functions, pass explicit `:data` derived from the
+database projection's canonical function contracts and assert
+`(nil? (mi/check ...))` in a discovered `deftest`. Do not populate or scan
+Malli's process-global function-schema registry, and do not merely call
+`mi/check`. In Malli 0.20.0 `mi/check` uses 100 trials and does not forward
+generator options. A three-child `:=>` checks one-call `[args result]`
+relations.
+
+Use explicit `(tc/quick-check ... :seed fixed-seed)` for state transitions,
+replay, idempotency, commits, and all multi-invocation properties. Invoke the
+production boundary, observe database facts independently, assert
+`(:result check)`, and print the complete check so shrinking evidence survives.
+
+A CLJ-only generator pass cannot certify CLJS. Regex and other tier-dependent
+schemas need recurring coverage on every owning runner.

```

The two skills should link this research note and
`spec-authorship-relational-properties-2026-07-26.md` together: this note owns
generator soundness and constructibility; the earlier note owns the boundary
between one-call guards and explicit state-transition properties.
