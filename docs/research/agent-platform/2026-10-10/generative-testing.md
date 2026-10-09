---
title: "Generative and behaviour testing: Malli, clojure.spec, test.check, and empirical cost"
type: research
status: active
created: 2026-10-10
---

# Generative and behaviour testing

Lane behavior-specs, 2026-10-10, default at ffe62e1c3, malli fork dd13ff8113, test.check 1.1.1. Source claims cite
`reference-code/<project>` by fully qualified symbol; REPL claims were evaluated on agent branch `behavior-specs`
(MCP `eval_clj`, SCI mode). The design that uses these findings is
[BEHAVIOR-SPEC](../../../seon/schedule/cards/BEHAVIOR-SPEC.md).

## 1. Malli (source read, then probed)

**Function schemas carry a relation.** `[:=> input output guard]` takes an optional third child, a schema validated
against `[args ret]` (`malli.core/-=>-schema`, `-function-info` adds `:guard`). `[:-> {:guard f} in... out]` is
sugar: `malli.core/-->-schema` turns the `:guard` property into `[:fn f]`.

**Instrumentation checks the guard on every call.** `malli.core/-instrument-f` builds `validate-guard` once and, when
`:guard` is in `:scope`, checks `[(vec args) value]` after the body and reports `:malli.core/invalid-guard` with
`{:guard :value :args :schema}`. A guard that throws is reported as `:malli.core/invalid-schema-at-call` with
`:arm :guard`, never as the body's error. A function with no guard pays nothing (the pair is built only when a guard
exists). `malli.core/-instrument` defaults `:scope` to `#{:input :output :guard}` in our fork; `malli.instrument`'s
`collect!` defaults `:malli/scope` to `#{:input :output}` (guard off) and upstream's
[function-schemas doc](https://github.com/metosin/malli/blob/master/docs/function-schemas.md) says the same. Seon arms
with `{:scope #{:input :output :guard}}` (`seon.instrument`, the host arming call) and already turns
`:malli.core/invalid-guard` into a Seon error with `:seon.error/member :seon.fn.arity/guard`, `:seon.error/input` and
`:seon.error/result-contract` (`seon.instrument` boundary refusal).

**`mg/check` and `mg/function-checker`.** `malli.generator/check` explains the function against its schema with
`::m/function-checker` bound to `malli.generator/function-checker`, which: generates inputs from the input schema
(`malli.generator/generator`), runs `clojure.test.check/quick-check` with `::mg/=>iterations` trials (default 100),
validates output and then guard on `[args ret]`, and on failure returns test.check's `:shrunk` map plus
`::m/explain-input`, `::m/explain-output` or `::m/explain-guard` on the smallest input. Multi-arity `:function`
schemas check each arity.

**Gap (fork and upstream alike): no seed, no max size, seed dropped.** `function-checker` calls
`(check/quick-check =>iterations prop)` with no `:seed` or `:max-size`, and returns only `shrunk`, so the failing seed
is lost. Probe: the same reversed-sort check run twice shrank to `[0 1]` and then to `[-1 0]`. Seon's rule (generated
inputs bounded, every seed passes, a failure reproducible) cannot be met through `mg/check` today. Upstream master
has the same code ([generator.cljc](https://github.com/metosin/malli/blob/master/src/malli/generator.cljc)). The
smallest fix is in our fork: read `::mg/=>seed` and `::mg/=>max-size` beside `::mg/=>iterations`, pass them to
`quick-check`, and keep `:seed` and `:num-tests` in the returned map. Until then a test calls
`clojure.test.check/quick-check` with `:seed`/`:max-size` over `(mg/generator input)` and the same two validators,
which repeats four lines of `function-checker`.

**Generators.** `malli.generator/generate` and `sample` take `{:seed :size}`; `:gen/min`, `:gen/max`,
`:gen/elements`, `:gen/fmap`, `:gen/schema`, `:gen/gen`, `:gen/return` select or shape generators
(`malli.generator/-create-from-*`). Collection bounds `{:min n :max n}` pin a size exactly, which is what a scaling
check needs. Size otherwise follows test.check's schedule (below).

**`malli.instrument` `:gen`.** `-instrument` with `:gen` replaces the body by a generated return value
(`malli.instrument/-strument-var!`). That lets callers run against a contract before its body exists; it rebinds Vars
(`alter-var-root`), which Seon forbids in tests, so in Seon it is a design-time REPL tool only.

**Probed guard behaviour and cost.** A guard on a five-element ranking over 4,000 names cost 5,824 bytes per call
against the body's 1,875,472 (0.3%); input and output validation of `[:vector :symbol]` over 4,000 elements allocated
168 bytes. Allocation was identical across five warm runs. A guard must be total: `[:fn (fn [[[xs] ys]] (apply <= ys))]`
threw on the empty vector (`(<=)` has no zero arity) and `mg/check` reported it as a guard failure of a correct sort.

## 2. clojure.spec

`s/fdef` takes `:args`, `:ret` and `:fn`; `:fn` receives `{:args conformed-args :ret conformed-ret}` and states the
relation ([spec guide](https://clojure.org/guides/spec), the `ranged-rand` example). `stest/instrument` checks only
`:args`; `stest/check` generates from `:args` and checks `:ret` and `:fn`. The stated reason: instrument checks callers,
check tests the implementation ([ClojureVerse](https://clojureverse.org/t/spec-instrumentation-only-checks-fdef-args/11018)).
Orchestra exists only to instrument `:ret`/`:fn` too. Why `:fn` is rare in practice: it receives conformed values
(tagged branches, not the raw arguments), a relation is often as expensive as the body, and `:args` generators for
domain data are hard to write, so `stest/check` is seldom run. Malli differs in the two ways that matter here: the
guard sees raw `[args ret]`, and Seon arms the guard on every call, so a cheap relation is always on.

## 3. test.check

- Properties: `prop/for-all`, `prop/for-all*`; `tc/quick-check n prop :seed s :max-size m :reporter-fn f`;
  `defspec` wraps a property as a deftest. Seon already uses `tc/quick-check` with fixed `:seed` in
  `seon.id-test` and `seon.program-test`; `seon.test-support/assert-check!` keeps the shrink data in the report.
- Size: trial i uses size `(mod i 200)` by default, so early trials are small; `gen/sized`, `gen/resize`, `gen/scale`
  control it. Shrinking walks a rose tree and does not depend on size; `gen/bind` shrinks poorly, `gen/fmap` well
  ([growth-and-shrinking](https://github.com/clojure/test.check/blob/master/doc/growth-and-shrinking.md)).
- Stateful/model-based: a model (initial state, commands with preconditions, next-state and postconditions) generates
  command sequences that shrink as sequences. Libraries: [stateful-check](https://github.com/MichaelDrogalis/stateful-check),
  [check.statem](https://github.com/jeffh/check.statem), and David Nolen's fugato
  ([clojure.org talk](https://clojure.org/events/2025/intro-to-fugato-by-david-nolen-900299105)), which models the
  problem first and runs the generated commands against the system. For Seon this fits writer and branch lifecycles
  (create, write, merge, retire) where the model is a plain map.

## 4. What kinds of properties find bugs

John Hughes, [How to Specify It!](https://research.chalmers.se/publication/517894) (2019/2020), five approaches over a
binary search tree with eight injected bugs: validity (invariants), postconditions, metamorphic properties,
inductive properties, model-based properties. Results: validity properties miss five of the eight bugs; every bug is
found by at least one postcondition, one metamorphic and one model-based property; model-based properties find bugs
fastest and "offer the best return on investment, in combination with validity properties". The pitfall named:
replicating the implementation in the test. Metamorphic testing (relate two calls: idempotence `f(f x) = f x`,
permutation invariance, adding an element changes the result predictably) answers the oracle problem when no model
exists ([Chen et al., ACM CSUR 2018](https://doi.org/10.1145/3143561)). Round-trip (`read (print x) = x`),
commutativity and conservation (counts, multisets) are the cheap special cases.

**Probe that found a defect in minutes.** An idempotence property for `seon.sci.forgive/forgive` (`:code` mode:
forgiving the forgiven text repairs nothing, keeps the text and the spans) failed on trial 17 (seed 20261010) and
shrank to `"a]a"`: the stray closer is dropped, giving `"aa"`, whose spans `[[0 1 1 1] [1 2 1 2]]` name two forms,
while reading `"aa"` again gives one form `[[0 2 1 1]]`. Dropping a closer between two tokens joins them. Replacing a
dropped closer by a space would keep every offset and the token boundary.

## 5. Measuring growth empirically

- **trend-prof** (Goldsmith, Aiken, Wilkerson, [FSE 2007](https://doi.org/10.1145/1287624.1287681)): fit each basic
  block's execution count to `a + b·n` or `a·n^b` over workloads of increasing size; report blocks whose exponent is
  higher than expected. **AlgoProf** (Zaparanuks, Hauswirth, [PLDI 2012](https://sape.inf.usi.ch/publications/pldi12)):
  infer the input size and the cost per algorithm automatically. **aprof** (Coppa, Demetrescu, Finocchi,
  [input-sensitive profiling, PLDI 2012](https://www.diag.uniroma1.it/en/publication/14517)): per-routine cost as a
  function of the input it read. **SlowFuzz** ([CCS 2017](https://arxiv.org/abs/1708.08437)) and **PerfFuzz**
  ([ISSTA 2018](https://2018.ecoop.org/details/issta-2018-Technical-Papers/1/PerfFuzz-Automatically-Generating-Pathological-Inputs))
  search for inputs that maximize cost: worst cases, not typical growth.
- The shared method: choose the size variable, run the same code at several sizes, fit the exponent on a log-log
  scale, compare with the expected exponent. Counts (executions, allocation) fit cleanly; wall time is noisy.
- [criterium](https://github.com/hugoduncan/criterium) estimates wall time with JIT warm-up and bootstrap statistics:
  right for a benchmark, too slow for a per-request proof. [clj-async-profiler](https://github.com/clojure-goes-fast/clj-async-profiler)
  answers where time or allocation goes (`:event :alloc`), the diagnosis after a growth check fails.

**Seon measurements** (`seon.profile/measure`, thread allocation, median of 3 warm runs, SCI-interpreted subjects):

| subject | sizes | alloc at n → 8n | fitted exponent | verdict |
|---|---|---|---|---|
| line of each offset, newlines counted from the start per offset | 50–400 decls | 7.2 MB → 463 MB | 2.00 | quadratic |
| same, newline positions found once then binary search | 50–400 decls | 0.26 MB → 2.10 MB | 1.01 | linear |
| `seon.sci.forgive/forgive` (compiled) | 200–1,600 lines | 0.25 MB → 2.18 MB | 1.05 | linear |
| nearest five names, distance once per name | 500–4,000 names | 0.23 MB → 1.88 MB | 1.01 | linear |
| nearest five names, distance inside `sort-by`'s key (the fan-out-9 trap) | 500–4,000 names | 2.37 MB → 22.45 MB | 1.08 | n log n, 12× the constant |

Two conclusions. The exponent alone separates quadratic from linear cleanly (2.00 against 1.01). It does not separate
the `sort-by` trap: n log n over an 8× range raises the exponent by only about 0.1, so that trap must be judged by its
cost relative to a reference implementation at the largest size (11.9×). And small inputs hide it: the live
`seon.error/names-in` (the same keyed `sort-by` over a namespace's names) costs 1.86 MB against 1.02 MB for the
decorate-once form on `seon.db`'s 280 names, 1.8×, under COST-REG's 2× factor and its 64 MB floor.

## 6. Seon admission gaps found while probing guards

1. **An agent cannot name any predicate in a contract it writes.** `seon.schema` refuses every `[:fn pred]` in an
   `:agent`-sourced contract as `:seon.schema/unproved-predicate-purity` unless the predicate is in
   `pure-predicate-symbols`, and no production code supplies that set (only `seon.schema.edn-test` does).
   `[:fn 'seon.db/database-value?]` and `[:fn 'clojure.core/map?]` were both refused. The guard was still armed in the
   agent's SCI context and fired correctly (`:seon.instrument/check :guard`, `:seon.error/input [1]`,
   `:seon.error/offending [[1] 2]`), but the definition was not admitted, so it cannot merge.
2. **A predicate defined on the agent's branch cannot be named at all**: `seon.schema/malli-form?` compiles through
   `seon.schema/compilable-form`, which resolves predicates only from loaded host Vars, so the contract is refused as
   not a Malli form.
3. **An unquoted predicate in REPL metadata** (`{:malli/schema [... [:fn clojure.core/map?]]}`) is evaluated to a
   function object by SCI and refused as "No reader function for tag object" from `seon.program/read-edn`; quoting
   (`[:fn 'clojure.core/map?]`) reaches the real check. The message does not say to quote.
4. **No guard exists anywhere**: zero arities carry `:seon.fn.arity/guard-refs` on default.
5. **Registry shape search is missing.** Searching the 6,823 registry entries by form through `m/form` cost 738 MB and
   449 ms; `:seon.schema/form` is stored as a string, so a structural query needs printing, which the one-printer law
   forbids. Producer/consumer search works: functions whose arities name a key in `:seon.fn.arity/output-refs`
   (producers) or `input-refs` (consumers) answered in 28 ms.

Smaller findings: `my.program/ns-unmap!` of one scratch def took 799 ms and 795 MB; a scratch `def` whose source names
a since-redefined symbol (`user/bad`) re-raised "Cannot interpret user/bad" at `seon.sci.eval/install-row!` on every
later request (77 occurrences), also after `ns-unmap!`.
