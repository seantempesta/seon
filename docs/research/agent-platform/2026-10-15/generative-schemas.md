---
title: "Generative schemas and functions: accumulating knowledge"
type: research
status: active
created: 2026-10-15
---

# Generative schemas and functions (2026-10-15)

Owner goal (2026-10-15): when an agent defines a Malli schema, Seon checks that it generates and validates quickly.
Every function with a contract is tested generatively against it when it changes, with failures shrunk to the smallest
input. An agent viewing a function (for example from a `fn.ns/symbol` reference) sees shrunk example inputs and outputs.
Every schema and function an agent wrote is discovered and generatively tested when it changes, visible in the UI, and
must be green to exit a stage.

This document accumulates across lanes. Section 1 is this lane (`gen-dig`, research only, no source edited): how the libraries work, how Seon uses them, what is measured, and the facts and questions the next lanes rely on. Section 2 (lane `gen-web`) is the web research and section 3 (lane `gen-design`) the design.

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

## 2. Web research

Lane `gen-web` (Sonnet, 2026-10-15). Sources are cited inline. "Probe" means a throwaway `clojure -M` JVM on the Malli
local root (`dd13ff81139159778edb0a67b491eccc0baa991d`) and test.check 1.1.1 (and, for 2.7, the Datahike local root); no
Seon JVM, no default, no store was touched. Probe forms are under each answer. Where a web source does not say something,
that is stated, not guessed.

### 2.1 Shrinking through `gen/elements`, `gen/fmap`, `gen/such-that` (open question 1)

- test.check shrinking is integrated: the generator returns a rose tree, so a shrunk value always comes from the same
  generator and keeps its invariants. Hypothesis's maintainer makes the same argument for "integrated" over type-based
  shrinking ([Hypothesis: integrated shrinking](https://hypothesis.works/articles/integrated-shrinking/)), and says test.check
  and Hypothesis implement it differently with largely the same user-visible result.
- The test.check docs say shrinking "is entirely deterministic and based on the properties of the basic generators and the
  combinators", independent of `size` ([growth-and-shrinking](https://github.com/clojure/test.check/blob/master/doc/growth-and-shrinking.md)).
  They do not document `such-that` or `elements` shrinking; the source does
  ([generators.cljc](https://github.com/clojure/test.check/blob/master/src/main/clojure/clojure/test/check/generators.cljc)):
  - `gen/elements` picks an index in `[0, count)` and shrinks the index toward 0, so values shrink toward EARLIER elements
    of the collection.
  - `gen/fmap` applies `f` to every node of the shrink tree, so shrunk values stay inside `f`'s range.
  - `gen/such-that` filters the shrink tree by the predicate, so a shrunk candidate is only offered if it still satisfies it.
  - `gen/bind` shrinks the outer value then the inner; the docs warn it shrinks poorly and recommend `fmap` over an unsized
    generator where possible.
- Probe (all on test.check 1.1.1 + Malli): shrinking works through all of them, and always lands inside the declared type.
  - `(gen/elements [:a :b :c :d])` failing on `#{:c :d}` shrinks to `:c`; Malli `[:enum :a :b :c]` failing on `:c` gives `:c`.
    So an `:enum` shrinks to its first failing member in declaration order: ORDER MEMBERS FROM SIMPLEST TO MOST COMPLEX.
  - `(gen/such-that even? gen/small-integer 100)` failing when `|x|>10` shrinks to `14` (even; `11` and `13` rejected).
  - `(gen/fmap str gen/small-integer)` failing on `count>2` shrinks to `"-10"`.
  - Malli `[:vector :int]` failing on `count>=3` shrinks to `[0 0 0]`; `[:map [:a :int] [:b :string] [:c {:optional true} :keyword]]`
    failing on `:a>5` shrinks to `{:a 6, :b ""}` (the optional key is dropped, the unrelated string is emptied).
  - Malli `[:and :int [:fn even?]]` failing on `|x|>10` shrinks to `-12`; `[:int {:gen/elements [5 9 100]}]` failing on `>8` gives `9`.
  - Consequence: a shrunk example is the simplest value in the DECLARED domain, so it is a good "example input" for an agent.

### 2.2 Generating from a predicate when `such-that` exhausts (open question 2)

- Spec has the identical failure and the identical mechanism. The Spec guide: for a predicate with no mapped generator
  (`even?`), `s/and` generates from the first predicate that has one and filters with test.check's `such-that`; too narrow a
  filter fails with "Couldn't satisfy such-that predicate after 100 tries"
  ([Spec guide](https://clojure.org/guides/spec)). The Cognitect post shows the same failure for
  `(s/and string? #(str/includes? % "hi"))` ([Combining specs with and](https://cognitect.com/blog/2016/8/24/combining-specs-with-and)).
- The documented fixes, in the guide's preference order: (1) let the library build it; (2) compose generators with
  `fmap`/`tuple`/`such-that`; (3) `s/with-gen` (the guide: use it "when filtering would be inefficient or impossible");
  (4) test.check directly. The guide also says spec does not trust custom generators (their output is still validated).
  Spec's own `s/merge` docs say it can generate maps satisfying the union of predicates "which `and` does not do"
  ([spec.alpha API](https://clojure.github.io/spec.alpha/clojure.spec.alpha-api.html)); Malli's equivalent is `malli.util/merge`.
- A forum thread suggests raising `such-that`'s `:max-tries`, but raising the limit only hides a sparse predicate
  ([ask.clojure 4223](https://ask.clojure.org/index.php/4223/when-a-generator-fails-to-gen-state-which-spec-pred-failed)). Hypothesis
  agrees in spirit: heavy `filter` raises an `Unsatisfiable`/`filter_too_much` health check, and the docs steer to construction
  ([Hypothesis strategies](https://hypothesis.readthedocs.io/en/latest/reference/strategies.html), [settings/HealthCheck](https://hypothesis.readthedocs.io/en/latest/reference/api.html)).
- Malli's own text agrees: "Generators for `:and` schemas work by generating values from the first child, and then filtering
  ... place the schema that is most likely to generate valid values ... as the first child"
  (`README.md`, "Generators"; [upstream](https://github.com/metosin/malli/blob/master/README.md)).
- Probe (Malli `dd13ff8`, seed 0): the same value can be generated without a filter by choosing where the constraint lives.
  - `[:and :any [:fn (fn [x] (and (int? x) (zero? (mod x 997))))]]` throws `::mg/such-that-failure` (34 ms).
  - `[:and {:gen/fmap #(* 997 %)} :int [:fn ...mod 997...]]` generates `0` (0.4 ms): construct by `fmap`, keep the predicate
    as the validator. Its generated values validate by construction.
  - `[:and {:gen/schema :int} [:fn int?]]` generates (1.2 ms); `[:fn {:gen/gen (gen/fmap #(* 997 %) gen/small-integer)} pred]` generates (0.3 ms).
- Answer: yes. The documented, library-native way is "a base generator plus `gen/fmap` (Malli `:gen/fmap` / `:gen/schema`), declared
  on the schema", never a bigger retry budget. `gen/bind` is possible but shrinks poorly (2.1).
- For the 199 `*error` schemas of form `[:and :seon.error/base [:map ...]]` (section 1.8), the same principle gives the cure:
  `malli.util/merge` at definition time, a single `:map` (section 1.3 probe). See 2.7 for `:merge` registration.

### 2.3 `size`, seed and `num-tests` guidance for CI (open question 3)

- Size: `quick-check` starts at size 0 and grows by 1 per trial, cycling after 199 (`(cycle (range 200))`); a run of fewer than
  about 10 trials sees only tiny sizes, so the docs suggest `gen/scale` to spread them
  ([growth-and-shrinking](https://github.com/clojure/test.check/blob/master/doc/growth-and-shrinking.md)). `gen/sample`
  likewise "starts with small values". `gen/nat` and friends are bounded by `size`; `gen/large-integer` is not.
- Seed: test.check uses ONE seed for all trials, so a seed replays a whole run, not one trial; to replay a single failure you
  also need the size, and a shrunk value additionally needs the shrink path or the value itself
  ([ask.clojure 7624](https://ask.clojure.org/index.php/7624/rerunning-a-particular-failure-is-difficult), cross-referenced to TCHECK-21; the thread notes
  shrunk values were never generated from a known seed and size). Practical consequence: keep the shrunk args, not the seed.
- Hypothesis (the best-documented CI policy): `max_examples` default 100; a development profile runs fewer examples, a CI
  profile more; `derandomize=True` seeds from a hash of the test, giving identical runs "at the price of less likely
  to find novel breakages" (its CI default is True) ([Hypothesis settings](https://hypothesis.readthedocs.io/en/latest/reference/api.html),
  [settings tutorial](https://hypothesis.readthedocs.io/en/latest/tutorial/settings.html)). Failures are saved to an example
  database and replayed first (2.9). So the established combination is: deterministic fast gate + a persistent store of minimal
  failures, with a fresh seed only in a separate, non-gating discovery run.
- Spec: `stest/check` default `:num-tests` is 1000, set through `{:clojure.spec.test.check/opts {:num-tests n}}` (a bare
  `:num-tests` is silently ignored) ([spec.test.alpha source](https://raw.githubusercontent.com/clojure/spec.alpha/master/src/main/clojure/clojure/spec/test/alpha.clj),
  [Clojurians log](https://clojurians-log.clojureverse.org/clojure-spec/2020-06-12)). Malli's `::mg/=>iterations` default is 100.
- NEW, measured: Malli's `function-checker` calls `(check/quick-check =>iterations)` and passes NO seed
  (`malli.generator/function-checker`; only `::=>iterations` is read). So `mg/check` is non-deterministic per call
  and `::mg/seed` is ignored. Probe, function `(defn bad [x] (if (> x 17) "no" x))` against `[:=> [:cat :int] :int]`, 100 runs
  with `::mg/seed` set, count of runs that missed the bug: n=10: 82 of 100; n=100: 0; n=1000: 0. The shrunk counterexample
  was `[(18)]`, identical every time (shrinking is deterministic; generation is not).
  Two consequences: (a) a green `mg/check` at 10 iterations is weak evidence (it only sees sizes 0-9, per the doc above);
  (b) a check cannot be keyed by digest AND be reproducible unless Seon builds the property itself
  (`prop/for-all*` over `mg/generator` of the input schema, `tc/quick-check n prop :seed s`), which is a few lines and gives
  `:seed` back.

### 2.4 Bounding generation time (open question 4)

- test.check has no per-trial deadline and no run time limit. `quick-check` takes `num-tests`, `:seed`, `:max-size`,
  `:reporter-fn`; the CHANGELOG through 1.1.2 mentions no timeouts, and timing keys are only reported
  (`:time-elapsed-ms`, `:time-shrinking-ms`) ([CHANGELOG](https://github.com/clojure/test.check/blob/master/CHANGELOG.markdown)).
  The ask.clojure thread references a shrink-abort "time limit" idea (TCHECK-96) that is a proposal, not a shipped option.
  So: bounding time is the caller's job, by (i) `num-tests`, (ii) `gen/scale`/`gen/resize` or `:max-size` on size,
  (iii) a wall clock around the whole call.
- Hypothesis does it in the library, which is the useful contrast: `deadline` per example (default 200 ms, off on CI),
  health checks `too_slow`, `filter_too_much`, `data_too_large` as performance warnings rather than errors, and
  `max_examples` ([Hypothesis settings](https://hypothesis.readthedocs.io/en/latest/reference/api.html)). The cost model is
  per-example, reported as its own finding.
- Probe: a function that blocks (`Thread/sleep 100000`) inside `mg/check` cannot be interrupted by `deref` timeout alone: the
  `future` returns `:timeout` after 300 ms while the call continues. This matches the Seon law "Timeout or cancellation is not
  termination" (SCI interrupts interpreted code only). A function check that calls user code needs a bounded executor and an
  awaited exit, not just a deadline.

### 2.5 Function checks: side effects, expensive calls, `:gen` (open question 5)

- Malli docs: the function checker is not on by default because it "uses generative testing, which is orders of magnitude
  slower than normal validation and requires an extra dependency"; "Generative testing is best effort, not a silver bullet"
  (`docs/function-schemas.md`; [upstream](https://github.com/metosin/malli/blob/master/docs/function-schemas.md)). The docs give no guidance on side
  effects. `:gen` on `m/-instrument` replaces the body with `mg/generate` of the output schema (useful as a stand-in for an
  effectful dependency, but it is the SAME output for the same seed: [malli #1039](https://www.goodfirstissue.org/metosin/malli/issues/1039)).
- Spec's answer is the same shape: `stest/check` calls the real function with generated `:args`, with generator overrides
  keyed by spec name ([Spec guide](https://clojure.org/guides/spec)); `exercise-fn` runs the real function on 10 samples. Neither
  library isolates side effects for you.
- Probe (cost, warm JVM, Malli `dd13ff8`): `[:=> [:cat :int] :int]` over `inc`: 0.4 ms at 10 iterations, 1.3 ms at 100.
  `[:=> [:cat [:map [:a :int] [:b :string]]] [:map [:a :int]]]` over `select-keys`: 0.8 ms and 9.7 ms. A function that takes 20 ms per
  call (sleep): 234 ms at 10, 2.4 s at 100: cost = iterations x per-call cost, so an expensive function must get a small budget.
  Cold first call 2.7 to 4.7 ms (generator build + JIT).
- Failure shape (probe): `(:errors (mg/check schema f opts))` is a seq whose first item has `:check` holding
  `:smallest` (the args vector, `[(18)]`), `:total-nodes-visited`, `:depth`, `:time-shrinking-ms`, `::m/result` (the bad output,
  `"no"`); for a throwing function, `:result` is the exception message and `::m/result` the Throwable (so a stored copy must go
  through the value printer, not keep the Throwable). A function whose output violates a map schema shrinks to the minimal
  input (`{:a 0, :b ""}`).
- Answer: `function-checker` is safe only for pure, cheap functions; for anything with a connection, a context, the filesystem
  or the clock, Seon must either fill the binding from the world (the existing default-by-schema-name mechanism, which makes
  the check not a pure generative one), or report it `unknown` (never green).

### 2.6 Checking that a schema's values validate; reporting shrunk values (open question 6)

- Malli's README lists no single "schema generates valid values" check, and `mg/check` is for functions. The standard
  answer is a `for-all` property over the generator (the form in the [test.check intro](https://github.com/clojure/test.check/blob/master/doc/intro.md)) asserting `m/validate`.
  Spec's analogue is `(s/exercise spec n)`, which generates and conforms each sample (default 10) and returns `[val conformed]`
  ([spec.alpha API](https://clojure.github.io/spec.alpha/clojure.spec.alpha-api.html)); and "Spec does not trust custom
  generators", i.e. it validates generated output, which Malli does not (`:gen/elements`, `:gen/return` are not validated).
- Probe: `(tc/quick-check 20 (prop/for-all [x (mg/generator s)] (m/validate s x)))` on `[:int {:gen/elements ["wrong"]}]` returns
  `{:pass? false :fail ["wrong"] :shrunk {:smallest ["wrong"] ...}}` in 0.3 ms. This is the single property that turns the
  Astra counterexample (section 1.8) into a red check with a shrunk value, and it is 3 lines.
- For display, the sources converge: Hypothesis stateful testing prints the failing steps "pretty close to Python code" that can
  be pasted into a test ([Hypothesis stateful](https://hypothesis.readthedocs.io/en/latest/stateful.html)); `print_blob` prints a
  replay token ([replaying failures](https://hypothesis.readthedocs.io/en/latest/tutorial/replaying-failures.html)). Spec's
  `abbrev-result` unwraps a failure to `s/describe` of the spec plus the failure's ex-data (`:clojure.spec.test.alpha/args`)
  ([spec.test.alpha source](https://raw.githubusercontent.com/clojure/spec.alpha/master/src/main/clojure/clojure/spec/test/alpha.clj)).
  Both show: the minimal argument value, the offending output, and the spec's own explain, not the seed. That maps onto Seon's
  `m/explain` + the value printer, as data.

### 2.7 `:merge` registration (open question 7), and the round trip to the database

- Probe: `(m/schema [:merge [:map [:a :int]] [:map [:b :int]]])` with the default registry throws `::m/invalid-schema`.
  With `{:registry (merge (m/default-schemas) (malli.util/schemas))}` it builds, and `mg/generate` (seed 1) gives
  `{:a -8522515, :b -131695845}`. So `:merge`, `:union` and `:select-keys` come from `malli.util/schemas`, not
  `m/default-schemas`; README says "declarative versions ... in `malli.util/schemas`" (`README.md` "Merging schemas"; same link as above).
  `malli.util/merge` is a plain function and needs no registry, so it is the right tool for definition-time composition.
- Database shape ("replicates to the DB"), probe on the Datahike local root (`62f94893`) with an in-memory store, write-flexible
  schema, a Malli `[:map [:p/name :string] [:p/age :int] [:p/tags {:optional true} [:set :keyword]] [:p/email {:optional true} :string]]`,
  property `transact generated map with tempid -> pull '[*] -> compare`, 100 trials: 261 ms (about 2.6 ms per transact + pull).
  First run FAILED and shrank to `{:p/name "" :p/age 0 :p/tags #{}}`: a cardinality-many attribute given `#{}` is not stored, so
  the pull has no key. This is exactly the Seon "absence is no key" law, found by the round trip in one run and shrunk to the minimum;
  the schema `[:set ...]` admits an empty set, the database does not keep it. After normalizing empty to absent the 100 trials pass.
  Meaning: a generated-value round trip through the writer finds schema/DB mismatches a pure Malli check cannot.
- Prior art for the pattern, not a recipe: Datahike's own `datahike.test.model-test` runs `defspec` properties (100 and 30 trials)
  of a model against the real database (reference-code, `test/datahike/test/model_test.clj`). I found no published
  transact-then-pull property for Datomic; a Clojurians thread (found by search, not re-read in full) advises speccing what comes OUT of `d/pull`
  more than tx-data ([Clojurians datomic 2016-10-11](https://clojurians-log.clojureverse.org/datomic/2016-10-11)).

### 2.8 Counting the packaged schema keys (open question 8)

This is local, not web, but the design is sized against it. Probe: read every `resources/seon/schemas/*.edn` into one map (3,814
forms), register with `mr/fast-registry` over `m/default-schemas`, and for each key `mg/generate` at `{:seed 0 :size 10}`, then
`m/validate` the value, 3 s wall-clock per key, in a throwaway `clojure -M` JVM on the repo deps with NO Seon namespace loaded,
single-threaded. Whole run 250 s.

| outcome | keys | cumulative time |
|---|---|---|
| generated, validates | 3,130 | 1.6 s |
| `::mg/such-that-failure` | 225 (199 are named `*error`) | 218.8 s (about 1 s each) |
| `::mg/no-generator` | 32 | 0.03 s |
| `::m/invalid-schema` | 10 | 0.001 s |
| 3 s timeout | 9 (`:seon.test/*error`, `:seon.test.run/*`, `:seon.turn/*-error`...) | 28.3 s |
| `:sci/error` (unresolved `:gen/gen` or `:fn` symbol, e.g. `seon.error/throwable?`) | 408 | 1.4 s |

Read it honestly: (1) no generated value failed its own validator among the 3,130 (one seed, one size); (2) the 408 `:sci/error`
are an artifact of not loading Seon's namespaces (the symbols live in `seon.error`, `seon.schema`...), so their true outcome is
UNMEASURED here and must be re-run with the namespaces loaded; (3) the such-that failures are 6% of keys but 99.6% of the time, which
confirms the section 1.8 extrapolation shape (it said about 400 fail; this probe counts 225 + 32 + 10 + 9 = 276 for sure plus up to
408 unknown); (4) the timeouts hit the 3 s bound and their `future` was not interrupted (see 2.4). The failing keys list is in the
probe's output only (`tmp` is gitignored); the producer rule is simple: the 199 error variants need `malli.util/merge` and the other
26 need a look.

### 2.9 Showing examples and persisting minimal failures (from the brief)

- Showing examples: Spec's `exercise`/`exercise-fn` return sample `[args ret]` pairs (10 by default) as the documented way to see
  what a spec/function does ([ClojureDocs exercise-fn](https://clojuredocs.org/clojure.spec.alpha/exercise-fn), [Spec guide](https://clojure.org/guides/spec)).
  Hypothesis `example()` is "designed for use in a REPL" and refuses to run inside `@given`; it is not reproducible by design
  ([Hypothesis strategies](https://hypothesis.readthedocs.io/en/latest/reference/strategies.html)). test.check's `gen/sample`
  warns that its first values come from small sizes. For an agent view, the useful variant is a SEEDED sample (Malli `mg/sample`
  with `:seed`, deterministic: probe confirms `(= (mg/sample s {:seed 5 :size 10}) (mg/sample s {:seed 5 :size 10}))`), with a few
  sizes (2.1 says shrunk values are simplest in-domain). Shrunk counterexamples are the minimal input; a passing sample shows range.
- Recursive schemas: size drives depth (probe, `[:or :int [:vector [:ref ::tree]]]`): printed length 4, 149, 1313 characters at
  size 5, 30, 100. A size dial, not a depth counter (Malli has no recursion limit here; Spec has `*recursion-limit*`, a soft limit
  that picks a non-recursive branch after N recursions: [spec.alpha API](https://clojure.github.io/spec.alpha/clojure.spec.alpha-api.html)).
- Persisting minimal failures, three designs:
  - Hypothesis: a database of failing inputs, replayed first on the next run (explain phase), keyed by the test, pluggable
    (directory, Redis, GitHub artifacts); the docs warn entries are invalidated by Hypothesis upgrades AND by editing the test, and
    recommend `@example` (explicit inputs, run every time, no shrinking) as the permanent regression
    ([replaying failures](https://hypothesis.readthedocs.io/en/latest/tutorial/replaying-failures.html)).
  - proptest (Rust): a `proptest-regressions` file of SEEDS next to the source, "commit these", replayed before new cases; seeds
    survive strategy changes because they still generate a valid value
    ([failure persistence](https://proptest-rs.github.io/proptest/proptest/failure-persistence.html)).
  - test.check: nothing built in; the community stores the shrunk args and pastes them into `gen/return`
    ([ask.clojure 7624](https://ask.clojure.org/index.php/7624/rerunning-a-particular-failure-is-difficult)).
  Pattern common to all: the replayed object is the MINIMAL FAILING INPUT (or seed), and it runs BEFORE new generation.
- Stateful/QuickCheck state machines: shrinking a command sequence removes steps while preconditions are re-checked on an advanced
  model, and a shrink step is kept only if the failure persists; the library requires deterministic generation, no run-time-only values
  ([Well-Typed/IOHK on quickcheck-state-machine](https://iohk.io/blog/an-in-depth-look-at-quickcheck-state-machine/),
  [Hackage](https://hackage.haskell.org/package/quickcheck-state-machine)). I found no persistence of minimal sequences in that library.
  Relevance for Seon is low now (agent evaluations are not generated command sequences) and high later if branches' agent behavior is model-checked.

### Recommendations the design should weigh

- Build the schema check as an explicit property, not `mg/generator` alone: `quick-check n (for-all [x (mg/generator s)] (m/validate s x))`
  with a fixed `:seed`. It catches the three failure kinds in one result: build error, `such-that` exhaustion (2.1, 1.1) and values that
  fail their own schema (`:gen/elements`, 2.6), and it returns a shrunk value as data. Replace the boolean `generatable?` as the fact;
  keep its name only if the meaning changes in the same commit.
- Fix generation at the producer, not with retries: schemas of the form `[:and base [:map ...]]` become `malli.util/merge` (the 199 `*error`
  variants), predicate schemas get `:gen/fmap`/`:gen/schema`/`:gen/gen` on the schema itself (2.2). Never raise `:max-tries`.
- Do not use `mg/check` as is: it ignores any seed (2.3) and at 10 iterations missed a trivial bug 82 percent of the time. Write the
  function property in Seon (`prop/for-all*` over `mg/generator` of the `:input`, `tc/quick-check` with `:seed`, size range chosen by
  `gen/scale`), reusing the arity decomposition from `malli.core/-function-info` / `m/-function-schema-arities`, and keep
  `function-checker`'s result shape (`::m/explain-input|output|guard`, shrunk args) so the display is the library's.
- Make determinism the gate and discovery separate: fixed seed (derived from the digest) at a modest `num-tests` (100 is cheap for pure
  functions: 1 to 10 ms, 2.5) for the exit rule; fresh seeds only in a non-gating background run, which on failure records the shrunk args.
- Persist the minimal failing INPUT (the shrunk args, as data, with the digest it was found against), and replay it first. This is
  what every mature system converges on (2.9); test.check seeds cannot replay one trial. Treat a stored counterexample like Hypothesis's
  `@example`: a regression that outlives edits to the function, and drop it when the function's contract no longer admits it.
- Bound by cost, not by wall clock alone: `num-tests` x per-call cost is the budget (2.5); test.check has no timeout and a blocked call
  is not interruptible (2.4), so run the check in a bounded executor whose exit is awaited, report a timeout as the named missing terminal
  event, and mark any function with binding inputs, effects or no measured per-call cost `unknown` rather than green.
- Treat generate and validate cost as part of the schema check result (per-key ms, from the property's own timing): 41 us median is the
  baseline; the corpus showed a handful of keys at 100 ms to 1 s (1.8, 2.8), so a dial on per-value cost flags them.
- Round-trip entity and attribute schemas through the writer in the check (generate, transact on a disposable branch, pull, compare
  after the "absent is no key" normalization). One probe found a real mismatch (empty cardinality-many set) in 18 ms and shrank it to a
  minimal map (2.7). Run it on the branch's database value, never a mock.
- Show shrunk and seeded examples in the function/reference view: deterministic `mg/sample` at a fixed seed plus the shrunk counterexample
  when red, cached by digest; order `:enum` members simplest-first because shrinking lands on the first failing member (2.1).
- Exit rule: "all green" needs "no unknown": an untested scratch function is unknown, so it blocks exit and the agent unmaps it. But
  never gate real-data workloads (owner constraint in `tmp/measured-gates-round.md`); the checks are on generated values only.
- Before sizing the work, rerun the count (2.8) with Seon's namespaces loaded in a throwaway JVM so the 408 `:sci/error` keys are
  classified; the design should quote that number, not 276 or 400.

## 3. Design: generative checks at each stage

Lane `gen-design` (Opus, 2026-10-15), on HEAD 40db32d49. It replaces the hybrid of the two measured-gates plans
(`measured-gates-plan-opus.md` 222e9ab61, `measured-gates-plan-astra.md` 1ff9589e2). It is docs only and uses no REPL
probe; every claim about current code below was read at its symbol. **What exists:** the runner runs deftests as
members. It keys each result by reach digest (`seon.test.runner/reach-digests`), and keys a task-reading test by its
input's content as well (`seon.test.runner/task-inputs`, `task-identity`, `task-reach-digests`, reader `seon.test/task`).
The branch's changes since its fork come from `seon.test/branch-delta`, which `seon.step/changed` reads and the done rule
judges as `:seon.program/identities`. Omitted inputs are filled from `seon.batch/world-members`. A function is shown
through `seon.render.ns/function-ai`. Nothing generates from a schema except the build-only
`seon.test.accretion/generatable?`, and nothing checks a function against its `:=>`. **Approach in one line:** a
derived check is an ordinary test member that judges one subject. It does this by generalising the task input into a
subject input, so selection, digest, reuse, cost, the done rule and the view are all the installed runner's.

### 3.1 One mechanism: two derived deftests, one subject input

- **Two deftests, written once** in `test/seon/check_test.clj`: `schema-generates-and-validates` and
  `function-meets-its-contract`. Each reads `(seon.test/subject)`, which is `seon.test/task` generalised: the supplied
  input is the subject's declaration, and a task is one kind of subject. No deftest is written per schema or function
  (Opus §1). The bodies call one contracted namespace, `seon.check` (new, `src/seon/check.clj`). It holds the
  properties and nothing else.
- **Reach digest** = `id[closure(check deftest), closure(subject)]`. This is the fold `task-reach-digests` already does,
  with `subject-identity` (the subject closure's content digest) in place of `task-identity`. When the same schema is on
  two branches, both have one digest and one cached result (rule 3). That sharing is the goal: it is a cache hit, not a
  read across branches. A subject whose content has not changed never runs again.
- **Seed** = `seon.check/seed`, the first 63 bits of the member's reach digest. The check is therefore deterministic for
  its content. It also gets fresh coverage on every change, because a new digest gives a new seed (§2.3: Hypothesis's
  `derandomize` and proptest's committed seeds converge on this).
- **Schema property** (`seon.check/schema-property`):
  `(tc/quick-check n (prop/for-all* [(mg/generator s)] (m/validate s %)) :seed seed :max-size size)`.
  - It catches all three failure kinds as one member result:
    - a build error (`::mg/no-generator`, `::m/invalid-schema`), thrown and recorded by the runner (the entrant boundary)
      with its `ex-data`;
    - such-that exhaustion (`::mg/such-that-failure`, thrown at generate time, §1.1);
    - a generated value that fails its own schema (`:gen/elements`, `:gen/return`, §2.6), returned with
      `:shrunk :smallest`.
  - `n` = `:seon.config.check/values` (20) and `size` = `:seon.config.check/size` (10).
  - Setup and steady state are measured separately (Astra): the generator and validator are built once, cached on the
    compiled schema by `m/-cached`, and timed apart from the trials.
- **Function property** (`seon.check/function-property`): Seon's own `prop/for-all*`, because
  `malli.generator/function-checker` calls `(check/quick-check =>iterations)` without a seed (read at
  `malli.generator/function-checker`; §2.3 measured an 82% miss at 10 trials).
  - For each arity from `m/-function-schema-arities` and `m/-function-info`, it generates `:input` with
    `mg/generator`, applies the subject's Var value (whatever the branch runs, compiled or interpreted), and validates
    `:output` and `:guard` with `m/validator`.
  - It runs `quick-check` with `:seed`, `n` = `:seon.config.check/iterations` (100: §2.5 measured 1–10 ms for pure
    functions) and `:max-size`.
  - On failure it builds **Malli's result shape** from the shrunk args: `::m/explain-input`, `::m/explain-output`,
    `::m/explain-guard` and `::m/result`. The body is about 15 lines that compose Malli's seams; it does not copy the
    checker.
  - Root's library batch files the upstream gap: forward `:seed` and `:max-size` through `function-checker`. When it
    lands, `function-property` shrinks to a call of `mg/function-checker`, and that change names the deletion.
- **Unknown is a typed result and is never green.** The check answers `{:seon.check/unknown reason}` in three cases:
  - `:world-input`: an input slot whose schema is a `seon.batch/world-members` key or `:seon.db/database-value`, or a
    closure that reaches a function with such a slot. This is derived from the reach graph the digest already walks, not
    from a list. Effects go through declared bindings by law, so this is also the effect detector. A host effect hidden
    behind an undeclared binding is that function's own defect, and running the check exposes it.
  - `:unimplemented`: the shrunk failure's `ex-data` names `:seon.error/unimplemented`, as the Design stub does.
  - `:unmeasured`: the member has no attributed allocation.

  Filling world inputs from the member's disposable test world, where database effects die with its branch, comes
  later, after §3.6's phase 0 has counted these functions.

### 3.2 Shrunk counterexample: stored as data, replayed first, dropped when inadmissible

- A red member keeps `:seon.check/counterexample`. It holds the shrunk args (or the schema's failing value) as the value
  printer's printed value, plus the `m/form` digest of the input schema it was found against. It goes through
  `seon.blob/inline-or-stage` when it is large. A Throwable result is stored as its printed value, never as the object
  (§2.5).
- **Replay first:** before it generates, `function-property` reads the latest counterexample for its subject on its own
  branch (a query by `:seon.test.member/subject`, never another branch). If `(m/validate input args)` holds, it runs
  those args as trial 0 (`:seon.check/replayed`). This is Hypothesis's database and test.check's `gen/return` habit
  (§2.9).
- **Drop when the contract no longer admits it:** when the validation fails, the counterexample is listed in the new
  member as `:seon.check/dropped`. It is never run and never carried further.
- A counterexample worth keeping becomes a row in an authored table test. The reply shows the form that adds it, as
  data. That is Hypothesis's `@example`, and it is what lets the regression merge.

### 3.3 The database round trip for entity schemas

- The trigger is a schema that declares `:seon.db/attributes true` (253 packaged ones). Its schema check adds a
  property:
  1. generate each attribute's value;
  2. call `seon.db/with` on the member's test-world value with `{:db/id "e" attr v}`, which applies the writer's own
     checks and is never committed;
  3. pull `[attr]` and compare it with `v` after Seon's own "absence is no key" law: an empty cardinality-many value
     equals absent.
- This is the check that found `[:set :keyword]` admitting `#{}`, a value the database does not keep. It found it in one
  run and shrank it to the minimum (§2.7). The fix is at the producer: `[:set {:min 1} …]`.
- Ref attributes and digest-checked identities are relational, so a generated value cannot satisfy them alone. They
  answer `{:seon.check/unknown :relational}`. Whole entities stay proven by authored tests through the canonical writers.

### 3.4 Discovery, examples and the live view: one derivation

- **Discovery:** the subjects are `(seon.step/changed branch :seon.schema/key)` ∪ `(… :seon.fn/sym)`. This is the same
  `seon.test/branch-delta` as the merge list's candidates (rulings 5a), checked in the eval request's test step whenever
  a subject's reach digest moves.
  - Derived checks judge their subjects but are **not seeds of the merge list**. Tests curate what merges (rule 6), and
    if checks were seeds, every contracted scratch function would merge.
  - Dependents that the branch did not change are rechecked on the merge candidate, where their digest differs (5b). That
    comes later.
- **One function, `seon.check/subjects`** `[branch dials] → [{subject kind verdict cost counterexample examples data}]`,
  is read by three consumers:
  - the current-state block (`seon.context/current-state`);
  - the agent's web page (`seon.render.web`, C0);
  - the done rule's judged input.
- **`seon.check/verdict`** `[member dials] → :green|:red|:unknown|:over` applies the cost law to the stored observation.
  The dials are not in the digest, so changing a dial re-judges without re-running (Astra).
- **Live view panel:**
  - for each changed schema: does it generate (verdict and failure kind), and generate/validate bytes and µs per node;
  - the data present: for an entity schema, the branch's count of entities with its identity attribute, one
    `:avet` index count. This is view only and never a gate (owner: real data);
  - for each changed function: is it checked (verdict or unknown reason), its shrunk counterexample with the explain
    path, and whether a seed reaches it ("merges" or "stays on the branch", rulings 5a).
- **Examples for agents:**
  - The check member also records three seeded `[args ret]` pairs. They come from `mg/sample` of the input at sizes 1, 3
    and 10 with `seon.check/seed`, applied in the member, and shrinking makes small values the simplest in their domain
    (§2.1).
  - `seon.render.ns/function-ai` reads them, with the counterexample, from the subject's member at its current digest
    and shows them as `:seon.check/examples`. The schema render shows sample values the same way.
  - The render only reads; the evaluation that changed the function ran the check (AGENTS.md "Presentation"). It is
    cached by the function's content digest, so a reference resolving to `fn.ns/symbol` costs one cached read.

### 3.5 Stage exits and the gate, in `seon.cluster.source/done-refusals`

`done-refusals` stays the one rule. It runs no test, and its judged input gains `(seon.check/subjects branch dials)`.
TASK-CHAIN §2.4 and §4 are amended in the implementing commit (the design is locked). Two clauses change:

- **Widened `:seon.source/stray-red-error`:** a derived check whose subject is in `:seon.program/identities` and whose
  verdict is `:red` or `:over` refuses at every task. An `:unknown :unimplemented` passes mid-chain and refuses on the
  last task, the same terminal clause stray reds use.
- **New, on the last task only:** a function changed on the branch that the merge list leaves "on the branch" refuses
  (`:seon.source/unmerged-change-error`, registered in `seon.source.edn`). The agent either tests it or unmaps it
  (`my.program/ns-unmap!`). This is the owner's workspace cleanup, and the form for each choice is shown beside it. An
  unknown check is never green, but a function a green seed reaches is proven by that seed.

Per stage, that gives:

- **Design** (schemas): every changed schema's check is `:green`. A stub's function check answers `:unimplemented`.
- **Prove** (tests): the proofs are `seon.test.runner/designed-red?`, strengthened so that a result whose only failing
  reports are bound failures, harness errors or generator errors is not designed. "Red for the stated reason, fast" is
  then the existing `seon.step.prove/handoff` `:not-designed-red`.
- **Implement** (functions): the proofs are green, every changed function's check is green or (if unknown) reached by a
  green seed, and nothing is left on the branch unmerged. This clears scratch.

**The gate signal** is a member's body-thread allocation and its armed calls, which repeat exactly when warm. Wall
milliseconds are only the execution bound and are never compared. The gate applies only to Seon's own checks over
bounded generated inputs: derived checks and authored test members. An eval request, a render or a model call over
real data is measured and shown, and never refused (owner, `tmp/measured-gates-round.md`).

The cost law is per unit of work. Dials live under `:seon.config.check/*`:

- `gen-bytes-per-node` and `validate-bytes-per-node`, per generated or validated node;
- `call-bytes-per-node` and `calls-per-call`, per function call per input node.

Each ships at 10× the phase-0 median. That gives the owner's "10× is wrong work" with no factor dial (Opus §2). An
over-target result is data: `{:unit :observed :target :values :nodes}`, beside the member's `:seon.test.member/top`.
The two hard-coded 5000 ms limits (`seon.test/run`, `seon.test/declared-bound-ms`) become `:seon.config.test/bound-ms`,
with `bound-mb` added beside it. `duration-failures` becomes `bound-failures`.

### 3.6 Fix at the producer first, then the phases

**Producer fixes**, before any gate:

- **The error variants.** About 208 schema forms in `resources/seon/schemas` are `[:and :seon.error/base [:map …]]`
  (§2.8 counted 199 `*error` keys failing `such-that`). One rewrite-clj script turns each into
  `[:merge :seon.error/base [:map …]]`.
- **Registering `malli.util`.** `:merge` needs `malli.util/schemas` (§2.7). Today nothing registers it: ten sites in
  `seon.config`, `seon.schema`, `seon.schema.internal`, `seon.fn.analyzer` and `seon.test.accretion` each compose a bare
  `(m/default-schemas)`. They become one var, `seon.schema.internal/default-schemas` =
  `(merge (m/default-schemas) (malli.util/schemas))`. It is cljc, so the bb-loadable edit hook (ruling 10) keeps
  loading.
- **The `:and` readers.** Their readers convert in the same commit:
  - `seon.schema.internal/extends-schema?` and `entity-maps` read `:and` children;
  - `seon.error`'s declared-schema-keys tests `(= :and (m/type …))`.

  They must read `:merge` the same way (`m/children`, or `m/deref` to the merged `:map`). Otherwise the sweep silently
  drops every variant from the declared set.
- **Predicate schemas.** Predicate schemas whose generator exhausts get `:gen/schema` or `:gen/fmap` on their own
  registration (§2.2), never more `:max-tries`.
- **Later.** The errors cut's `:multi` on `:seon.error/type` supersedes the per-variant form.

| # | Phase | Kind | Red proof (data assertion) | Deletes |
|---|---|---|---|---|
| 0 | Count the corpus with Seon's namespaces loaded, in a throwaway check JVM, never on default: the 408 `:sci/error` keys classified, per-node medians, dependents per key, world-input share. Committed as `script/seon/check_corpus.clj` plus evidence. | host-bound (`script/`) | `seon.error-test/an-error-variant-generates` (`are`: `:my.edit/no-match-error`, `:my.shell/error` generate and validate at seed 0). Red today (§1.8). | — |
| 1 | Producer fixes above: `default-schemas` with `malli.util`, the `:merge` sweep, its three readers, and predicate generators. Published and reloaded on a branch of the live store. | host-bound (schema resources) | phase 0's test green; `seon.schema.internal-test/a-merge-variant-extends-the-error-base` (`extends-schema?` true for a `[:merge :seon.error/base …]` key) | 10 bare `m/default-schemas` sites |
| 2 | Subject input: `seon.test/subject`, `:seon.test.member/subject`, `subject-identity` in the reach fold | host-bound (runner; patch to the chain lane: `src/seon/test.clj` and `test/runner.clj` are critical path) | `seon.test.runner-test/a-subject-check-runs-once-per-subject-content`: the same schema on two branches gives one execution and one `reused-run`; a changed form gives a new run | — |
| 3 | `seon.check/schema-property` and `seed`; `schema-generates-and-validates`; selection of changed subjects | host-bound (selection) | `seon.check-test/a-changed-schema-is-checked-in-its-reply` rows: `[:and :int [:fn neg?] [:fn pos?]]` → red `::mg/such-that-failure`; `[:int {:gen/elements ["wrong"]}]` → red, `:smallest ["wrong"]`; `[:map [:a :int]]` → green; the same seed twice gives an equal trace | `generatable?`, `schema-row`, `schema-row-in`, `:seon.schema/generatable?` (with its `seon.program` reader), `every-packaged-schema-answers-generatability-as-a-boolean`, accretion's `:auto-check` schemas and render branch, `:seon.config.test/auto-check-cases` and its 2 test writes |
| 4 | `seon.check/function-property`; `function-meets-its-contract`; unknown reasons | REPL-first (`seon.check`) | `seon.check-test/a-function-is-checked-against-its-contract` rows: `(fn [x] (if (> x 17) "no" x))` vs `[:=> [:cat :int] :int]` → red, `:smallest [18]`, `::m/explain-output`, at 100 iterations; a `:seon.db/connection` slot → `:unknown :world-input`; a stub → `:unknown :unimplemented` | — |
| 5 | `done-refusals`: widened stray-red and terminal `unmerged-change-error`; `designed-red?` strengthened | REPL-first patch to the chain lane (`source.clj`); host-bound for the variant schema | new rows in `seon.cluster.source-test/done-refusals-judges-every-task-alike`: a red check of a changed key refuses; a stub passes mid-chain and refuses last; a changed function no seed reaches refuses last | — |
| 6 | Cost dials, `verdict`, `bound-failures`, `bound-ms`/`bound-mb` | host-bound (config) | `seon.check-test/a-dial-change-rejudges-without-rerunning`: lowering `gen-bytes-per-node` turns the same member `:over` with `{:unit :observed :target}` and 0 new executions | the two hard-coded 5000s |
| 7 | Counterexample stored, replayed, dropped | REPL-first | `seon.check-test/a-counterexample-is-replayed-first-and-dropped-when-inadmissible`: the next digest replays `[18]` as trial 0 (`:seon.check/replayed`); after `[:=> [:cat [:int {:max 10}]] :int]` it is listed in `:seon.check/dropped` and the check is green | — |
| 8 | Entity round trip through `seon.db/with` | REPL-first | `seon.check-test/an-entity-schema-round-trips-through-the-writer`: `[:set :keyword]` → red `:smallest` holding `#{}`; `[:set {:min 1} :keyword]` → green; a ref attribute → `:unknown :relational` | — |
| 9 | Examples in `function-ai` and the schema render; `seon.check/subjects` in the block and on the page | REPL-first (render, context) | `seon.render.ns-test/a-function-view-shows-its-checked-examples` (3 `[args ret]` pairs, equal across two renders, one cache read); `seon.context-test/the-block-shows-each-changed-subject-check` | FEEDBACK-LOOP §2 item 5 and stages 14–15 (`seon.schema.cost/*`, never built), revised in place |

**Needed now:** phases 0–5. That is the count, the producer fix, the subject input, both properties and the exit
clauses, which together are the owner's "every schema and function checked when it changes, green to exit".
**Later:** phases 6–9, world inputs filled from the test world, dependents rechecked at merge, non-gating fresh-seed
discovery runs that only record counterexamples, and the upstream Malli seed forwarding. HEAD boots after each phase,
and each deletion goes in the commit that converts its last reader.

**Risks:**

- Phase 0 may show that many contracted functions are `:world-input`. Then phase 5's "reached by a green seed" carries
  most of Implement's exit, and world-filled checks move to "now".
- `:merge`'s proxy may surprise other readers of `m/type` (phase 1 greps them all).
- A compiled host call inside a check cannot be interrupted. It is bounded by the runner's existing actual-exit
  ownership, not by a new deadline.

### 3.x Owner rulings after the design (2026-10-15, root): existing code, mining, and what a run's cost means

- **Rigour for new work; mining for old.** The exit gate judges only what a branch changed since its merge base. Loading
  and indexing `src` never runs checks and never blocks boot. Main's existing schemas and functions are checked in the
  bulk tier root runs centrally (each once per content digest); each failure becomes a generated task in its owner's
  tasks file (as `seon.contracts/task` does for uncontracted functions), its shrunk counterexample the red proof. Root
  fixes critical ones; the rest are the agent task queue ("we can mine from there").
- **Cost from a check's runs.** Record per run: armed calls (exact, JIT-independent: the gate signal) and allocation
  (stable, but C2 escape analysis can remove allocations once a method compiles: read it from later runs, not the
  first). Wall time is recorded as evidence, never gated: 100 runs measure the interpreter and C1, not C2 (HotSpot
  tiered thresholds: C1 at about 200 invocations, C2 at about 5,000 plus back-edges).
- **Branch functions are never JIT-compiled.** They run interpreted in SCI; only the interpreter is compiled. A branch
  function's compiled performance exists only after merge and reload, so a JIT-warm benchmark (criterium-style warmup)
  is a separate, explicit measurement of compiled functions on main, never part of a stage exit.
