---
title: "Generative schemas and functions: accumulating knowledge"
---

# Generative schemas and functions (2026-10-15)

Owner goal (2026-10-15): when an agent defines a Malli schema, Seon checks that it generates and validates quickly.
Every function with a contract is tested generatively against it when it changes, with failures shrunk to the smallest
input. An agent viewing a function (for example from a `fn.ns/symbol` reference) sees shrunk example inputs and outputs.
Every schema and function an agent wrote is discovered and generatively tested when it changes, visible in the UI, and
must be green to exit a stage.

This document accumulates across lanes. Section 1 is this lane (`gen-dig`, research only, no source edited): how the libraries work, how Seon uses them, what is measured, and the facts and questions the next lanes rely on. Sections 2 and 3 are reserved for the next lanes and are empty on purpose.

## Pins and evidence used

- Malli source: `reference-code/malli` at `dd13ff81139159778edb0a67b491eccc0baa991d`.
- test.check: Seon pins `org.clojure/test.check` `1.1.1` in `deps.edn`. The sources were read from
  `~/.m2/repository/org/clojure/test.check/1.1.1/test.check-1.1.1.jar` (read, never run).
- Probes: two throwaway JVM runs with `clojure -Sdeps` on the Malli local root plus test.check 1.1.1 (forms reproduced
  in section 1.8). No Seon JVM, no default, no store was touched.
- Earlier evidence: `docs/research/agent-platform/2026-10-15/dig-measured-gates.md` and
  `docs/research/agent-platform/2026-10-15/measured-gates-plan-opus.md` (section 7), and the Astra plan
  `measured-gates-plan-astra.md` (its exact counterexample is reproduced below).

## 1. How the libraries do it

### 1.1 Construction and generation are separate steps

- `malli.generator/generator` builds a test.check generator for a schema. It is cached on the compiled schema with
  `malli.core/-cached` under the `:generator` key, except while `::rec-gen` is set (recursion), where it bypasses the
  cache.
- `malli.generator/-create` picks the generator in a fixed order: `:gen/return`, then `:gen/elements`, then
  `:gen/schema`, then `:gen/gen`, then the schema's own `-generator` (`-schema-generator`). If none applies it fails with
  `::no-generator`. `:gen/fmap` is applied last, to whatever `-create` produced.
- `-schema-generator` is a multimethod on `(m/type schema options)` with a `::default` that uses
  `clojure.spec.gen.alpha/gen-for-pred` over the schema validator. Every core type has its own method.
- Failures raised while building are `::no-generator` (no generator declared or derivable) and `::m/invalid-schema`.
  Failures raised while generating are different: `:and` and `:not` use `clojure.test.check.generators/such-that`
  with `:max-tries 100`, and its exhaustion is thrown as `::such-that-failure` (`->such-that-opts`,
  `gen-such-that`). That exception happens at generate time, not at build time.
- `malli.generator/generate` and `malli.generator/sample` take `{:seed s :size n}`. `generate` defaults to size 30,
  `sample` to size 10 and returns the values, not the rose trees. The seed is passed to `random/make-random`.

### 1.2 Generator properties (the `:gen/*` keys)

- `:gen/return v`: returns `v`, not validated.
- `:gen/elements xs`: picks from `xs`, not validated. This is the probe result that matters most: `[:int {:gen/elements
  ["wrong"]}]` builds, generates `"wrong"`, and `m/validate` is false. The value fails its own schema and nothing reports
  it (the Malli README says "note, are not validated").
- `:gen/fmap f`: `gen/fmap` over the built generator; `f` is a function or, portably, a quoted form evaluated with
  `m/eval` (`-create-from-fmap`). Malli's README uses it to turn `:cat` sequences into vectors.
- `:gen/schema s`: generate from another schema (`-create-from-schema`). This is the Malli way to say "the generated
  value is a generated `s`" without writing a generator.
- `:gen/gen g`: a test.check generator, or a symbol that `m/eval` resolves. A symbol that does not yield a generator
  throws `::no-generator` (`-create-from-gen`). Not serializable as a value.
- `:gen/min`, `:gen/max`, `:gen/infinite?`, `:gen/NaN?` bound collections and doubles (README "Generators" section).
- `::m/=>iterations` is not a generator property: it is `::=>iterations` in `function-checker` (section 1.5).

### 1.3 Composite schemas

- `-and-gen`: `(gen-such-that schema (m/validator schema options) (-child-gen schema options))`. It generates from the
  FIRST child only, then keeps the values the whole `:and` validates, with `gen/such-that` and max 100 tries. Malli's
  README says so ("place the schema that is most likely to generate valid values ... as the first child").
- Probe (section 1.8): `[:and base-map [:map [:error/operation [:= :x]]]]` builds (`mg/generator` returns a
  generator, `built=true`) and then `mg/generate` throws `:malli.generator/such-that-failure` (about 35 ms cold). So a
  build-only check passes such a schema. This is why `seon.test.accretion/generatable?` (boolean, builds only) can answer
  true for a schema that cannot generate.
- Probe (section 1.8): `(malli.util/merge base-map override-map)` produces a single `:map` at definition time. Its
  generator builds, `mg/generate` yields a value that validates, and 20 samples at seed 1 all validate. Malli's
  `malli.util/merge` and the `:merge` schema are the way out. (The `:merge` schema form itself was not registered in the
  probe JVM: `[:merge ...]` failed as `::m/invalid-schema`, so `:merge` needs the `malli.util` schema registry, not only
  `malli.core/default-schemas`. Use the function `malli.util/merge` at definition time.)
- `:or` and `:orn` use `-or-gen` (`gen/one-of` over the children, dropping unreachable ones). `:multi` uses
  `-multi-gen`, which merges keyword-dispatch maps into entries. `:map` uses `-map-gen` (`gen-tuple` over `m/entries`,
  each entry optional or required, then `-build-map`). `:map-of` uses `gen-vector-distinct-by`. `:vector`, `:sequential`
  use `-coll-gen`; `:set` uses `-coll-distinct-gen`.
- `:maybe` generates `nil` or the child (`gen-maybe`). `:enum` uses `gen-elements` over its children.
- `:fn` and any predicate without a generator use `::default` (a `gen-for-pred` of the validator). That is a filter over
  `gen/any` and is the usual source of `such-that` failures for predicate-heavy schemas.

### 1.4 Recursion

- No recursion limit exists in this Malli revision. A search of `reference-code/malli` for `recursion-limit` or
  `recursion_limit` returns nothing. The owner's note named `::mg/recursion-limit`; it does not exist here.
- Recursive `:ref` schemas use `-ref-gen`. The generator for `(m/deref schema)` is built with
  `::rec-gen {ref-id scalar-ref-gen}`, where `scalar-ref-gen` is `-never-gen` (no values). If the base case is used,
  it is wrapped in `gen/recursive-gen`, whose size-driven growth (the rose tree of each recursive position) stops
  generating the recursive case as size shrinks. The Malli source comment says the naive depth cutoff makes exponentially
  large cases, and that this algorithm makes linear ones.
- Consequence: recursion is bounded by `size`, not by a counter. A recursive schema's cost grows with `size`, and
  `generate` at size 30 (the default) is the cost to measure.
- `-unreachable-gen?` and `-never-gen` let a parent choose a fallback: `[:maybe M]` generates nil when M is
  unreachable, `[:vector M]` generates `[]`, `[:map [:a M]]` is unreachable.

### 1.5 Functions: instrument, check, function-checker

- `malli.core/-instrument` wraps a function with a function schema. Its props are `:schema`, `:scope` (default
  `#{:input :output :guard}`), `:report` (default `-fail!`) and `:gen` (a function `schema -> schema -> value` returning
  the function's result instead of calling it).
- `malli.instrument/-strument!` applies `-instrument` to every function schema in the registry. `-strument-each!`
  continues past failing Vars and returns `{:instrumented ... :failed ...}`.
- `malli.instrument/check`: instruments every registered function schema with `:mode` set to a function that calls
  `mg/check` on the original Var value and records the explanation. It returns nil or `symbol -> explanation`. This is the
  built-in "check every function" entry point (its docstring: "Checks all registered function schemas using generative
  testing"). Seon does not call it (section 1.7).
- `malli.generator/function-checker` is the check body. For each arity it:
  1. generates the input from the `:input` schema, with `quick-check` and `::=>iterations` (default 100);
  2. applies the function and validates the output (`:output`) and the optional `:guard`;
  3. on failure takes `smallest` = `(-> shrunk :smallest first)`, the shrunk input;
  4. runs `(m/explain input smallest)`; if the input is valid, applies the function to `smallest` and runs
     `(m/explain output result)`; if the output is wrong, `explain-guard` for the guard;
  5. returns the shrink result with `::m/result`, and `::m/explain-input`, `::m/explain-output` or `::m/explain-guard`.
  Malli tests use it as `::m/function-checker mg/function-checker` in `test/malli/core_test.cljc` and
  `test/malli/error_test.cljc`.
- `m/explain` with a `::m/function-checker` option is what `mg/check` (`malli.generator/check`) does: it sets the
  checker and explains the function value.

### 1.6 test.check: quick-check, shrinking, properties

- `clojure.test.check/quick-check [num-tests property & {:keys [seed max-size reporter-fn]}]` (`max-size` default 200).
  Size grows from 0 to `max-size` across trials (`gen/make-size-range-seq`), so early trials are small.
- Passing result: `{:num-tests :pass? true :result true :seed :time-elapsed-ms}`. Failing result: `{:fail [args]
  :failed-after-ms :failing-size :num-tests :pass? false :result :result-data :seed :shrunk}`. `:shrunk` holds
  `:total-nodes-visited`, `:depth`, `:smallest` (the shrunk args vector), `:result`, `:result-data` and
  `:time-shrinking-ms`.
- Shrinking is `shrink-loop` over the rose tree of the failing trial. It searches depth-first: a child that still fails
  becomes the new smallest and its children are tried next, a passing child is skipped, and the left-most failing node
  at the deepest failing level is the answer. So a shrink is a property of the generator's rose tree. Generators built
  from `fmap` and `gen/vector` shrink through their children (test.check behaviour as read in `shrink-loop`; the
  shrink of `such-that` and `gen/elements` is not verified here).
- `clojure.test.check.generators/such-that [pred gen max-tries-or-opts]`: default max tries 10. Malli passes its own 100
  (`->such-that-opts`) and its own `:ex-fn`.
- `clojure.test.check.properties/for-all` (macro) and `for-all*` (function) build a property from bindings and a body.
  `clojure.test.check.clojure-test/defspec` wraps a property as a clojure.test deftest and reports the
  `quick-check` result with its seed.

### 1.7 How Seon uses them today

Grep over `src`, `test` and `resources` (2026-10-15):

- `malli.generator` in `src`: `src/seon/error.clj` requires it and calls `mg/generate` at size 1, seed 0 for error
  examples (its docstring at the `mg/generate` site names `::m/invalid-schema`, `::mg/no-generator` and such-that
  exhaustion). About 40 `clojure.test.check.generators` uses there build contract-based error values.
- `src/seon/test/accretion.clj`: `generatable?` calls `mg/generator` only, and catches `::mg/no-generator` and
  `::m/invalid-schema` to answer a boolean. `schema-row` and `schema-row-in` accrete the boolean as
  `:seon.schema/generatable?`. It never calls `mg/generate` and never validates a value. It is true for the failing
  `[:and base-map [:map ...]]` case above.
- `src/seon/schema.clj:336` mentions `malli.generator/-create-from-gen` in a comment only.
- `src/seon/instrument.clj` has no `malli.generator` call and no `:gen` option: contracts are checked at call time
  (inputs prevent entry, outputs refuse), not generated.
- `:seon.config.test/auto-check-cases` is declared in `resources/seon/schemas/seon.config.test.edn` and set in
  `test/seon/program_test.clj` and `test/seon/config_application_test.clj`. Nothing in `src` or `script` reads it. Its
  reader was in the install gate deleted by `c537242c0`.
- Tests: about twenty files call `mg/generate` with seeds (for example `test/seon/schema_test.clj`,
  `test/seon/flow_test.clj`). `test/seon/repl_test.clj`, `test/seon/edit_test.clj` and others use
  `clojure.test.check` `quick-check`/`for-all`. The only `function-checker` use in Seon is
  `test/seon/refusal_grammar_test.clj` (`::mg/=>iterations 1` on `[:=> [:cat [:= 7]] :int]`).
- `every-packaged-schema-answers-generatability-as-a-boolean` (`test/seon/test/accretion_test.clj`) asserts a boolean for
  every packaged schema (declared-long, 12 s). It generates nothing.

### 1.8 What is measured, and what is extrapolated

Measured by someone (cite the note; not re-run here):

- 3,813 packaged schema keys (`measured-gates-plan-opus.md` section 7; the Astra plan records the same count on archive
  `3ef0189186576ca859510c18cd9fe02270c38d1e`).
- First 300 keys, sorted: 30 fail `:malli.generator/such-that-failure`. Every one is an error variant written
  `[:and :seon.error/base [:map ...]]`. These 30 cost 20.7 s and about 105 GB of allocation. The 268 that generate cost
  460 ms in total, and 2 have no generator.
- Next 430 non-error keys: 8 such-that failures (`:seon.ai/completion`, `:seon.boot/instance`,
  `:seon.call-preparation/refusals`, ...), each 0.5 to 0.9 s, plus 7 with no generator and 3 invalid schemas
  (`:seon.await/*`). 18 keys take more than 10 ms (for example `:seon.batch/request` at 105 ms).
- Medians for 10 values: generate 41 us, validate 3 us. p99: 30 ms and 343 us. No generated value failed its own
  validator in those samples.
- Astra's counterexample (`measured-gates-plan-astra.md`): `[:int {:gen/elements ["wrong"]}]` builds, and
  `(m/validate s (mg/generate s {:seed 0 :size 1}))` is false. Reproduced by probe 1 above.

Extrapolated, NOT counted: "about 400 of 3,813 fail `mg/generate`" is the Opus plan's estimate. The full corpus was not
run (a chunk hit the 30 s SCI time limit). It is 269 `*error` keys (30 of 30 sampled failed) plus about 4% of the other
keys. Nobody has counted it. The first design step must count it in a throwaway check JVM, not default.

Probe forms (throwaway JVM, Malli local root, test.check 1.1.1, `(require [malli.core :as m] [malli.generator :as mg] [malli.util :as mu])`):

```clojure
(def base [:map [:error/type [:= :a]] [:error/operation :keyword] [:error/message :string]])
(mg/generate [:and base [:map [:error/operation [:= :x]]]] {:seed 0 :size 10}) ;; throws :malli.generator/such-that-failure
(m/validate [:int {:gen/elements ["wrong"]}] (mg/generate [:int {:gen/elements ["wrong"]}] {:seed 0 :size 1})) ;; false
(mg/generate (mu/merge base [:map [:error/operation [:= :x]]]) {:seed 0 :size 10}) ;; valid
```

Results:

- `[:and base-map [:map ...]]`: `mg/generator` builds (`true`); `mg/generate` at seed 0, size 10 throws
  `:malli.generator/such-that-failure`, about 35 ms.
- `[:int {:gen/elements ["wrong"]}]`: builds; generated value validates false.
- `(malli.util/merge base-map override-map)`: generates a valid value; 20 samples at seed 1 all valid.
- `:merge` as a bare schema form: `::m/invalid-schema` on build and generate.

### 1.9 Facts the design can rely on

- `malli.generator/generator` only builds; `malli.generator/generate` and `malli.generator/sample` produce values and
  are where `:and` and `:not` fail (`-and-gen`, `gen-such-that`, `->such-that-opts`).
- A boolean build check (`seon.test.accretion/generatable?`) cannot detect a schema that fails to generate. The probe
  shows it returns true for the failing `:and` case.
- `:gen/elements` and `:gen/return` values are never validated by Malli, so a check must call `m/validate` on every
  generated value (`malli.core/validate`).
- `:and` generates from its first child only. Schemas that put a broad or predicate child first (or a `[:map ...]` where a
  base map is required) fail. The fix Malli recommends is `malli.util/merge` (or a `:map` written out), not `:and`.
- `malli.generator/generate` is deterministic for a given `:seed`, `:size` and generator. `:size` sets recursion depth
  for recursive `:ref` schemas and the size of collections.
- `clojure.test.check/quick-check` returns the seed and, on failure, `:shrunk :smallest`, the shrunk arguments. That is
  the shrunk input the owner asked for. `:shrunk` also has `:depth` and `:total-nodes-visited`.
- `malli.generator/function-checker` gives the same shape of result for a function: a shrunk input, then
  `::m/explain-input` if the input fails its schema, else the output's explanation (`::m/explain-output`), else the guard's.
- `malli.generator/check` (through `::m/function-checker`) is the Malli entry point for one function value, and
  `malli.instrument/check` is the one for every registered function schema. Neither is in Seon.
- Malli has no recursion counter. Recursive schemas are bounded by `size` (`gen/recursive-gen`); a size-dial in Seon
  would be a size, not a `recursion-limit`.
- The dead dial `:seon.config.test/auto-check-cases` has no reader; any new check dial needs a reader in the same change.

### 1.10 Open questions for web research (for the next lane)

1. test.check shrinking through `gen/elements`, `gen/fmap` and `gen/such-that`: does each shrink toward a simpler value
   of the declared type, and what do `gen/elements` shrinks look like for a `:enum` of keywords?
2. How do established Clojure libraries (for example spec-alpha and `clojure.spec.gen.alpha`, Hypothesis-style
   generators, Schema or Plumatic generative checks, `ttcn`/`spec.test.alpha` `check`) handle generation for predicate
   schemas where `such-that` exhausts? Is there a known, documented way to make a generator from a predicate without
   failing (`gen/fmap` from a base generator, or `gen/bind`)?
3. What is the documented guidance on `size` and seed for CI: a fixed seed for every run (determinism) versus a fresh
   seed per run (coverage), and how do `quick-check`'s `:seed` and `:max-size` interact with a time budget?
4. What are the recognized ways to bound generation time in a property-test runner (test.check `:max-size`, a
   per-trial timeout, a count budget), and does test.check have a per-trial deadline or is it the caller's job?
5. For function checks, what do Malli users do for functions with side effects or unbounded output (the `:fn`, `:=>`
   and `:gen` props in `malli.core/-instrument`), and is `malli.generator/function-checker` safe to run against a
   function that is expensive per call?
6. Is there a documented way to check that a Malli schema's generated values all validate, as a single property (the
   `malli.generator/check` pattern, or a `for-all` over `mg/generator` with `m/validate`), and what does the library
   recommend for failures reported as shrunk values in a browser or UI?
7. Confirm the `:merge` schema registration requirement: which namespace registers `:merge` into the default schema
   registry in this Malli revision (probe: `[:merge ...]` failed `::m/invalid-schema` without `malli.util` schemas)?
8. Count (in a throwaway JVM, not default): how many of the 3,813 packaged keys fail `mg/generate` with a fixed seed and
   size, grouped by exception type and time. This is the number the design sizes its work against.

## 2. Web research (next lane)

## 3. Design (next lane)
