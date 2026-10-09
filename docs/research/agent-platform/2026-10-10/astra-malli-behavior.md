---
type: research
status: independent-second-opinion
created: 2026-10-10
tags: [malli, contracts, testing, performance]
---

# Behavioural contracts and performance qualification: Astra second opinion

## Recommendation

Use Malli's existing third child of `:=>` for cheap input/output relations, and ordinary task-tagged
`clojure.test/deftest` proofs for generated examples, independent models and metamorphic laws. Add cost
qualification as data and assertions in that same proof, measured by `seon.profile/measure`. No new
contract language, evaluator, property engine, scheduler, measurement counters or proof runner is needed.

There are three real gaps, not reasons to build parallel machinery:

1. **Fixed-seed `malli.generator/check` is not available at the installed pin.** Its checker ignores
   `:seed`, `:size` and `:max-size` when calling test.check. Use `malli.generator/generator` with
   `clojure.test.check/quick-check` now. If the desired authoring entrance must be `mg/check`, extend
   Malli's existing checker to forward test.check options and retain replay evidence.
2. **Named predicates authored in SCI do not yet travel cleanly through schema admission.** The branch
   probe below was refused. Fix predicate resolution and purity evidence at Seon's existing schema
   owners before promising agents that they can publish a new relational guard.
3. **The existing test owner needs a small, schema-validated cost obligation and comparison of measured
   rows.** A failing or unmeasured qualification must make the ordinary task proof non-green before
   review. Reuse its existing result, reach-digest and merge gates.

The ranking experiment demonstrates the desired discrimination: both implementations satisfy the
behavioral checks; a declared linear allocation/call envelope over 32, 64, 128 and 256 fixed-width
candidates accepts scoring once and rejects scoring inside comparisons. A second experiment over
`seon.id/id` accepts its constant allocation/call envelope and rejects rehashing every prefix while
returning the same final identity.

This is a finite, declared workload qualification, **not a mathematical proof of asymptotic complexity**.
Neither allocation nor armed-call counts can establish general CPU complexity. An allocation-free
unarmed quadratic loop can evade both; a task concerned with that work also needs an observable work
count or a trustworthy CPU measure. Wall time remains a bounded runner concern and diagnostic evidence.

## Independence, installation and baseline

I did not read the other lane's design card or generative-testing document. No source or test file was
edited. All experimental definitions lived in agent `astra-malli-behavior`'s SCI branch on cluster
`default`; JVM-mode forms inspected loaded resources, measured the existing compiled entrance, and
recorded tasks through an explicit default connection.

Read end to end: AGENTS.md; data-oriented-clojure, clojure-testing, repl and data-modeling skills;
the contracts guide. Also read the datahike skill for recording findings. Relevant dependency source:
Malli core, generator, instrument, error and util; test.check 1.1.1 from its resolved JAR; Clojure sort.
The testing skill already recommends bounded generators, fixed seeds and
`seon.test-support/assert-check!`, which refuses an empty trial count. Its existing advice to keep
wall-clock assertions out of ordinary tests remains sound.

| Evidence | Observed |
|---|---|
| Malli gitlink, checkout and loaded resource | `dd13ff81139159778edb0a67b491eccc0baa991d` |
| Loaded resource | `data/source/pins/reference-code-malli/dd13ff81139159778edb0a67b491eccc0baa991d/src/malli/core.cljc` |
| Upstream source inspected locally | Malli `origin/master` at `76603d587553c5a8d60fa64c9e631fa3addb4b2e` |
| Clojure reference checkout | `3bc2b3e91fdff462620d84bbdfd40379d9c562e5` |
| Live Seon archive at initial status | `ffe62e1c37f60566e68cb32943e870a1b4b47922` |
| Shared checkout during research | `9d47f6f8982ba4898578f48d4a571c4bc6ec4ce4`; other lanes can advance HEAD |
| First SCI form | `(seon.context/current-state)`: 13 ms / 4.4 MB; full request 306.9 ms |
| Initial health | faulted; 6 open faults, 24 errored records, 114 unknown tests; no missing boot layers |
| Initial memory | 706,500,464 heap-used; 530,339,696 heap-live; 4,294,967,296 heap-max |

The health findings predate this work, including maintenance/collection overruns. They are not a pass
or evidence against these isolated probes. No restart, reset, provider call or foreign lane interaction
was performed.

## What Malli already provides

| Seam | Guarantee, input, recomputation and cost |
|---|---|
| `malli.core/-function-info` | A compiled single arity yields input, output, optional guard, min/max and arity. Derive once when compiling/arming. A multi-arity `:function` itself returns nil; enumerate `malli.core/-function-schema-arities`. |
| `malli.core/-instrument-f` | Builds validators at arming, then validates input, calls the function once, validates output, and validates the guard against `[(vec args) value]`. Every enabled call pays the validators' work. |
| `malli.core/-instrument` | Default scope is `#{:input :output :guard}`. A report callback decides whether a violation throws. A callback that returns does not itself stop the call. |
| `malli.core/-->-schema` | Experimental `:->` expands positional children into `:=>` with `:cat`; its `:guard` property becomes `[:fn guard]`. Prefer explicit `:=>` for named guard schemas and regex arities. |
| `malli.generator/generator` | Compiles/caches a test.check generator on the schema object. Reuse the compiled schema; a fresh schema on every trial loses that benefit. |
| `malli.generator/generate` | Uses explicit `:seed` and `:size` (default size 30); returns the generated rose-tree root. |
| `malli.generator/sample` | `:size` controls sample count and the cycled size sequence, not an exact collection length. |
| `malli.generator/function-checker` | Generates arguments, applies the function, checks output and then the optional guard. Defaults to 100 trials per arity; `:malli.generator/=>iterations` overrides that. Returns nil or failure/shrink data. |
| `malli.generator/check` | Returns nil or a Malli explanation containing checker failure data. It requires the whole function schema, not just its input schema. |
| `clojure.test.check/quick-check` | Owns trials, seeded randomness, size progression and shrinking. Returns seed, trial count, first failure and smallest counterexample. |
| `malli.error/humanize` and `malli.error/error-message` | Own explanation messages, including named predicate error messages. No separate guard error printer is needed. |
| `malli.util/update-properties` | Derives a schema with changed generator properties; no new schema walker is needed for bounded proof views. |

These function-schema and property-testing seams are upstream mechanisms, not invented Seon APIs.
The seed omission also exists in the inspected upstream checker. The maintained fork adds, among other
things, symbolic `:gen/gen` resolution, validator-exception reporting and cheaper passing argument checks.
Its current `malli.error/levenshtein` uses two primitive rows; it is substantially different from the
historical implementation behind the 6.3-second incident.

A bare `m/validate` of a function schema normally checks callability, not behavior. Supply the function
checker or call `mg/check` to execute generated examples. Multi-arity checking checks every declared
arity; it is not overload dispatch by argument type. Arity groups must be unambiguous.

### Guard shape, errors and Seon's actual scope

A guard is a schema over a pair: arguments and return value. The predicate's own contract should accept
a **sequential** argument collection. Instrumentation supplies a vector, but `mg/check` can supply
the list produced by `:cat`. I first declared vector arguments and incorrectly rejected a valid
successor during `mg/check`; changing that to sequential made the valid implementation pass.

Prototype, evaluated on the SCI branch:

```clojure
(in-ns 'astra.malli-behavior)

(defn successor?
  {:malli/schema [:=> [:cat [:tuple [:sequential :int] :int]] :boolean]}
  [[[x] y]]
  (= (inc x) y))

(def successor-schema
  (malli.core/schema
   [:=> [:cat [:int {:min -10 :max 10}]] :int
    :astra.malli-behavior/relation]
   {:registry
    (merge (malli.core/default-schemas)
           {:astra.malli-behavior/relation
            [:fn {:error/message "Must be the successor."} successor?]})}))
```

The local registry holds the **named function value**, not an anonymous predicate. It demonstrates
Malli composition; it is not proof of persisted Seon registration. With this schema, an instrumented
`identity` called on 4 throws Malli data with `:type :malli.core/invalid-guard`, `:args [4]` and
`:value 4`. `mg/check` of `inc` returned nil; checking `identity` shrank to `:smallest [(0)]`
and `:malli.core/result 0`.

A separate structural guard verified the installed Seon path without depending on predicate registration:

```clojure
(defn guarded-five
  {:malli/schema [:=> [:cat :int] :int [:tuple [:cat :int] [:= 5]]]}
  [x] x)

(guarded-five 4)
(guarded-five 5)
```

The first returned a REPL error with `:seon.error/type :seon.instrument/contract-error`,
`:seon.instrument/check :guard`, function `astra.malli-behavior/guarded-five`, problem path
`[1]`, offending 4, input `[4]`, and expected `[:= 5]`. The second returned 5.
The refusal took 1 ms. This proves that Seon's installed SCI arming enables guards.

Source and loaded resource agree: `seon.instrument/compiled-wrapper` calls `m/-instrument` with
`:scope #{:input :output :guard}`; `seon.instrument/wrap-interpreted` uses that same owner.
`seon.sci.arm/arm!` invokes contract installation at the observed definition.
The existing `seon.instrument-test/jvm-and-interpreted-functions-arm-the-declared-guard` covers both
surfaces. Malli reports after the function returns: a guard cannot undo side effects. Keep guards pure.
A `:fn` predicate exception is turned into a failed predicate by Malli's safe predicate path; the
refusal may therefore mean the predicate itself is wrong. Test the predicate independently too.

### Writing and publishing a named guard

The intended persisted declaration is one registered keyword pointing to a qualified predicate symbol,
with an error message and a bounded generator of **valid argument/result pairs**. For example, after
its predicate is admitted, the successor relation can use:

```clojure
[:fn {:error/message "Must be the successor."
      :gen/schema [:enum [[0] 1] [[1] 2]]}
 astra.malli-behavior/successor?]
```

This two-example generator is a registration fixture, not the successor function's behavioral coverage.
The behavioral proof generates the function's input domain separately.

Do not claim this publication path works today. Registering the SCI-defined successor predicate with
`seon.schema/register!` was refused by `seon.schema/malli-form?` as an invalid definition
(result `efe607af38666`, 6 ms). Inspection reaches `seon.schema/loaded-predicate-var`, which resolves
host namespaces. A built-in predicate exposed two further boundaries:

- `[:fn {:error/message "Must be empty." :gen/elements [[]]} 'clojure.core/empty?]`
  was refused as `:seon.schema.edn/dishonest-generator` (80 ms / 58.3 MB).
- Changing its generator to `:gen/schema [:= []]` reached the next gate, which refused
  `:seon.schema/unproved-predicate-purity` (11 ms / 17.6 MB).

`seon.schema.edn/predicate-declarations` accepts only `:gen/schema` or `:gen/gen` as evidence
of a generator, while `seon.schema.internal/guarded-predicate-properties-complete?` accepts
`:gen/schema`, `:gen/elements` or `:gen/return`. The gates disagree. Neither a load nor an
exemption is an acceptable substitute for branch-aware admission.

### Which relations belong on every call

Good candidates are scalar arithmetic bounds, a returned identity equal to an input identity, a returned
index within an input's counted bounds, and a result vector's count bounded by an explicit limit.
A vector count is cheap; counting a lazy sequence may realize the whole input. A short output can carry
cheap checks even when the input is large.

Put full ranking optimality, multiset preservation, graph reachability, database queries, independent
recomputation and metamorphic executions in proofs. They should not run as production guards.
Relations need not restate input/output shape checks that already ran. Do not call the guarded function
from its guard, create a recursive guard cycle, or make failure explanation recompute an expensive oracle.

Measured on this branch, 2,000 calls to the same Malli-instrumented `inc`:

| Scope | Wall ms, three samples | Allocated bytes, each sample | Armed calls |
|---|---|---|---:|
| input + output | 0.43, 0.40, 0.39 | 270,376 | 0 |
| input + output + named SCI successor predicate | 1.66, 1.55, 1.28 | 1,774,568 | 2,000 |

The median incremental cost was about **0.575 microseconds and 752 bytes per call**.
This includes the separately armed, interpreted predicate and pair construction; it is not the overhead
of a compiled host predicate in isolation. The producer here is trivial, so even this constant-time
guard dominates its body. Measure representative guarded boundaries, not an assumed universal price.
The loop and schema compilation were identical/outside the measured call, respectively.

## Deterministic behavioral proofs

The seed counterexample is reproducible as a procedure, though the ignored-seed outputs are deliberately
not reproducible:

```clojure
(let [schema
      [:=> [:cat [:int {:gen/gen
                       (clojure.test.check.generators/no-shrink
                        (clojure.test.check.generators/choose 0 1000000000))}]]
       :int]
      opts {:seed 42 :size 1 :max-size 1
            :malli.generator/=>iterations 1}]
  (mapv (fn [_]
          (:smallest
           (:check (first (:errors (malli.generator/check schema str opts))))))
        (range 3)))
```

Observed `[[(748092538)] [(386394011)] [(386394011)]]`.
The duplicate last two is not a determinism guarantee. Direct `quick-check` with seed 42 and max-size 1
returned the same failure `[(278601130)]` in all three runs, with `:num-tests 1`.
The checker source calls only `(quick-check =>iterations property)`: options never reach that call.
It also drops the initial seed/trial envelope from its failure return and reruns the smallest case to
explain it. Do not use it on uncontrolled effects.

Use this existing-library composition today:

```clojure
(let [input [:cat [:string {:min 1 :max 8}]
             [:vector {:max 16} [:string {:min 1 :max 8}]]]
      valid? (malli.core/validator input)
      property
      (clojure.test.check.properties/for-all*
       [(malli.generator/generator input)]
       (fn [[q xs :as args]]
         (and (valid? args)
              (= (rank-good q xs) (rank-bad q xs))
              (= (rank-good q xs)
                 (rank-good q (vec (reverse xs)))))))]
  (clojure.test.check/quick-check 100 property :seed 42010 :max-size 16))
```

Observed `{:pass? true :num-tests 100 :seed 42010}`, body 13 ms / 26.9 MB.
Here the deliberately slower full sorting function is the reference result, and reverse-input invariance
is an additional law. A production nearest proof must also exercise empty candidates, duplicates, equal
distances, namespace preference, lexical ties, short/long names and limits. Do not assert that a
particular helper ran exactly once: assert ranking behavior and the task's resource envelope. A future
implementation using selection, a heap or another algorithm can qualify.

Put the property in the existing proof `deftest`, tag it with the task symbol in `:seon/tasks`,
and pass the full quick-check result to `seon.test-support/assert-check!`. Use the same mechanism for
a stronger model/oracle; do not put the model in the production guard. Every generated value must validate
against the input schema before invocation. This matters because Malli generator overrides are not
proofs of validity: `(mg/generate [:int {:gen/return "bad"}] {:seed 42})` returned `"bad"`,
and `m/validate` returned false.

Generator properties at this pin have precedence: `:gen/return`, `:gen/elements`, `:gen/schema`,
then `:gen/gen` or the type's default generator; `:gen/fmap` maps the chosen generator afterward.
`:gen/min` and `:gen/max` narrow supported numeric/collection bounds; they cannot exceed a declared
bound. `:gen/infinite?` and `:gen/NaN?` affect floating generators. Symbolic `:gen/gen` resolution
is a maintained-fork feature. A mapping or override may produce invalid values: validate its outputs.
Prefer constructive generators over rejection-heavy predicates, and preserve shrinkable representations.

Seed, generator/schema content, test.check/Malli revisions, trial count and maximum size together identify
a replay. Seed alone does not survive a changed generator. Functional failures can use test.check's
shrinking. A noisy timing failure must not trigger uncontrolled shrinking or reruns of effects.

## Performance experiments and the qualification

### Ranking reproduction

The historical incident is recorded by commit `77367741d`: 6.3 s / 15.4 GB for comparator recomputation,
then 177 ms / 728 MB after scoring each candidate once, before the primitive-row distance improvement.
Do not present those historical figures as measurements of today's fork.

The following definitions were evaluated on this lane's branch. The input is the real nearest-ranking
operation restricted to one common namespace, so the namespace tie component is constant. This isolates
ranking cost from database fixture creation and queries; it is not a substitute for the full public
`my.program/source` proof.

```clojure
(defn distance
  {:malli/schema [:=> [:cat :string :string] :int]}
  [a b]
  (malli.error/levenshtein a b))

(defn rank-bad
  {:malli/schema [:=> [:cat :string [:vector :string]] [:vector :string]]}
  [q xs]
  (vec (take 5 (sort-by (fn [s] [(distance q s) s]) xs))))

(defn rank-good
  {:malli/schema [:=> [:cat :string [:vector :string]] [:vector :string]]}
  [q xs]
  (->> xs
       (map (fn [s] [[(distance q s) s] s]))
       (sort-by first)
       (take 5)
       (mapv second)))

(def cases
  (mapv (fn [n]
          [n (malli.generator/generate
              [:vector {:min n :max n} [:string {:min 8 :max 8}]]
              {:seed 42010 :size n})])
        [32 64 128 256]))
```

Each implementation ran once at every size before the three measured repetitions. Inputs were fully
generated before measurement. Each measure was `(seon.profile/measure (fn [] (rank-good "nearestx" xs)))`
or its bad counterpart; returned vectors were fully realized. Every paired output was equal.

| Candidates | Good ms (3 samples) | Good allocation (3 samples, bytes) | Good calls | Bad ms (3 samples) | Bad allocation (3 samples, bytes) | Bad calls |
|---:|---|---|---:|---|---|---:|
| 32 | .10, .05, .05 | 44136, 43296, 43296 | 33 | .22, .23, .21 | 186704, 186528, 186384 | 255 |
| 64 | .08, .08, .08 | 87136, 87136, 87280 | 65 | .47, .53, .45 | 451680, 451680, 451680 | 623 |
| 128 | .15, .14, .14 | 180128, 180272, 180128 | 129 | 1.16, 1.14, 1.10 | 1077344, 1077344, 1077344 | 1491 |
| 256 | .31, .30, .34 | 376768, 376912, 376768 | 257 | 2.53, 2.55, 2.44 | 2521568, 2521568, 2521568 | 3495 |

All these measured windows recorded zero GC collections and zero reads. The distance calls were exactly
32/64/128/256 versus 254/622/1490/3494; the table includes the one ranking entrance.
`clojure.core/sort-by` invokes its key function in its comparator. Sorting with a precomputed
`first` key is cheap; putting edit distance there multiplies that work by comparisons.

As an installed full-function check, JVM mode measured:

```clojure
(seon.profile/measure
 (fn []
   (#'my.program/nearest
    (seon.db/db (seon.cluster.boot/connection "default"))
    'seon.task/creat!)))
```

It returned `seon.task/create!` first, followed by four other candidates, in **21.11 ms,
24,412,840 allocated bytes, 112 armed calls and two reads**. This is a read-only observation of the
loaded implementation, not a generated scaling experiment.

### Second real function: structural identity

`seon.id/id` hashes a flat integer vector with constant working allocation and a constant number of
armed calls; its CPU work grows with input length. The deliberately wasteful but behaviorally equivalent
mutation hashes every prefix and returns the last identity:

```clojure
(defn hash-repeated
  {:malli/schema [:=> [:cat [:vector :int]] :string]}
  [xs]
  (peek (mapv (fn [n] (seon.id/id (subvec xs 0 n)))
              (range 1 (inc (count xs))))))

(def hash-cases
  (mapv (fn [n]
          [n (malli.generator/generate
              [:vector {:min n :max n} [:int {:min 0 :max 100}]]
              {:seed 42010 :size n})])
        [32 64 128 256]))
```

The mutation's declared experiment domain is nonempty vectors; it is not a proposed general replacement.
Measure `seon.id/id` and `hash-repeated` exactly as above. Warm each at every size, then collect three
samples. Equality held at all four sizes. A second fixed-seed 100-trial check over nonempty vectors of
at most 16 integers also established equality with the mutation and invariance under `mapv identity`;
it passed in 4 ms / 18.9 MB.

| Length | Good ms (3 samples) | Good allocation (3 samples, bytes) | Good calls | Bad ms (3 samples) | Bad allocation (3 samples, bytes) | Bad calls |
|---:|---|---|---:|---|---|---:|
| 32 | .02, .01, .01 | 11840, 11408, 11408 | 3 | .15, .14, .09 | 370416, 369768, 369768 | 97 |
| 64 | .01, .01, .02 | 11408, 11408, 11408 | 3 | .20, .22, .18 | 737384, 737384, 737384 | 193 |
| 128 | .01, .01, .01 | 11408, 11408, 11408 | 3 | .52, .47, .44 | 1472680, 1472680, 1472680 | 385 |
| 256 | .02, .01, .01 | 11408, 11408, 11408 | 3 | 1.38, 1.23, 1.28 | 2946216, 2946216, 2946216 | 769 |

This example also exposes a trap in the proposed policy: declaring “linear” for every metric would let
the bad allocation and call growth pass. Declare the metric's actual obligation. Allocation is not
retained heap, peak space, or CPU work; repeated constant-allocation hashing can still do quadratic CPU work.

### Executed classifier

For every pair of increasing sizes, normalize the observed ratio by the declared growth ratio.
For the example growth function `g(n)=n^p`, use p=1 for ranking allocation/calls and p=0 for identity
allocation/calls. Across the three samples, use the most pessimistic ratio to pass, and the most
optimistic ratio to establish a failure. The gap is unknown, never green.

This exact local function was evaluated (rows hold `:n`, `:good`, `:bad` and full measure maps):

```clojure
(fn [rows kind metric exponent]
  (let [pairs
        (for [a rows b rows
              :when (< (:n a) (:n b))
              :let [as (mapv metric (kind a))
                    bs (mapv metric (kind b))
                    scale (Math/pow (/ (double (:n b)) (:n a)) exponent)]]
          {:lower (/ (apply min bs) (* scale (apply max as)))
           :upper (/ (apply max bs) (* scale (apply min as)))})
        lower (apply max (map :lower pairs))
        upper (apply max (map :upper pairs))]
    {:lower lower :upper upper
     :verdict (cond (<= upper 1.25) :pass
                    (> lower 1.25) :fail
                    :else :unknown)}))
```

| Subject/metric | Worst normalized ratio interval | Verdict |
|---|---:|---|
| ranking, good allocation | 1.07919–1.08818 | pass |
| ranking, bad allocation | 1.68821–1.69111 | fail |
| ranking, good calls | .99612–.99612 | pass |
| ranking, bad calls | 1.71324–1.71324 | fail |
| identity, good allocation | 1.00000–1.00000 | pass |
| identity, bad allocation | 7.95380–7.96774 | fail |
| identity, good calls | 1.00000–1.00000 | pass |
| identity, bad calls | 7.92784–7.92784 | fail |

The 1.25 allowance is an **illustrative research parameter**, not a calibrated or shipped threshold.
It was applied after observing these experiments. A real task must declare its envelope before a
candidate is submitted and establish it against legitimate alternatives plus known bad mutations.
These sample min/max intervals are conservative sample envelopes, not statistical confidence intervals.

## Minimal integration and honest limits

Start with one ordinary proof using existing libraries and a pure comparison of measure maps. Only when
sharing the policy is useful, place that comparison beside the existing profile/test owners. The missing
data is an optional task cost obligation: workload/size meaning, fixed seeds, exact size schedule,
positive repetition count, measurement scope and per-metric growth envelope with absolute bounds.
Attach that obligation to the existing task/proof relationship, not a second proof registry. All such
policy numbers must be declared schema/config values, not constants hidden in implementation code.
No new fields are installed by this document.

For arbitrary growth, supply a named pure function over size rather than invent a complexity DSL.
Separate independent dimensions: candidate count, name length, result limit, database population,
changed definitions, or changed inputs. Nearest scoring costs depend on both string lengths and
candidate count; sorting adds comparison work. Increasing all dimensions together can hide the cause.

The ordinary proof must validate the obligation and inputs, acquire its fixture outside the measured
window, invoke the actual armed candidate, realize the output required by its consumer inside the
window, and then check the output/model separately. Retain exact samples, sizes, seed and semantic result
evidence through the existing test result path. A performance failure is an ordinary failed assertion;
missing allocation/call evidence is unknown and refuses qualification. There is no review-only waiver
or “green because nothing ran.”

The eventual shared comparator needs cases this compact prototype intentionally does not implement:
zero denominators, missing counters, counter scope mismatch, measurement floor, absolute budgets and
bounded repetition. Prefer cross-multiplication for integral counts and an additive fixed overhead term
where justified, not division by zero or a guessed substitute. All-pairs comparison over four fixed
sizes is six comparisons per metric; general k sizes cost O(k²), trivial for this declared small schedule.
The expensive operation is measuring the candidate, not computing verdicts.

Use several fixed seeds and explicit adversarial partitions, alternating/interleaving implementation
order when comparing candidates. This experiment used one seed and good-before-bad ordering; it does not
calibrate a general classifier. Random names at 32–256 candidates are a witness for this regression,
not coverage of sorted, reverse-sorted, all-tied or unusual Unicode names. Test variable name length
separately. `:size n` alone is not an exact n-element workload: use declared min=max and verify actual
size, as these probes did.

Warm execution and first acquisition are separate obligations. Do not amortize an unacceptable first
call away. Fresh distinct inputs are needed for changed-input obligations; test cache hits independently.
Fixed seeds make workloads replayable, not JVM allocation/JIT/GC timing. Shared-JVM armed counts and GC
are process-wide at today's `seon.profile/measure`; unrelated work can contaminate them. Thread-local
allocation misses delegated work and is absent on unsupported/virtual threads. The currently running
measurement lane may improve this; these results rely only on the measured-call API observed here.

The proof's reuse identity must include input/generator content, task cost policy, guard and oracle
dependencies, measured program/contract digests, measurement implementation/scope and relevant dependency
versions. A changed threshold or measurement mechanism must not reuse an old green. Reuse Seon's
existing input/reach evidence; do not add invalidation stamps. Do not treat documentation as program
input: `seon.test.cache/input-roots` owns that boundary.

A passing finite envelope cannot exclude a later crossover, data-dependent bad case, hidden unarmed work
or deliberate constant overhead masking growth. Absolute bounds, workload diversity and inspection of
what each metric actually measures complement scaling. Aggregate armed calls are useful rejection
evidence, but not an implementation-neutral instruction to retain a particular helper. A future faster
algorithm can use fewer calls or none; zero-call evidence needs an appropriate alternative metric.

## Recorded gaps and verification

Recorded on default through `seon.task/create!` with explicit connection and root as creator; the three
writes completed in a 130 ms JVM form. These tasks are findings, not assigned implementation work, and
no success proof was invented or launched:

- `seon.schema/branch-guard-predicate-registers`, task id `3f8eedbecc52`.
- `seon.schema.edn/predicate-generator-admission-agrees`, task id `87992926b065`.
- `seon.test/malli-check-replays-a-fixed-seed`, task id `046e5390d5f5`.

Related existing findings: `seon.sci.eval/a-contract-predicate-is-refused-both-ways`,
`seon.schema/an-agent-contract-naming-an-unregistered-schema-is-refused-as-a-platform-fault`, and
`seon.schema/a-registration-refusal-carries-an-error-type`; the registration refusals here likewise
lacked a type in their rendered value.

Probe mistakes were also inspected: an initial `clojure.repl/source-fn` inspection failed reading
`::m/result` outside the source namespace; loaded resource text then established the pin and scope.
An initial task-creation call accidentally evaluated the must-read symbols; its contract refused the
function objects before a task write. Quoting the declaration fixed the request. Neither is evidence
that the target behavior passed.

All reported prototype form bodies were below one second. The largest measurement-table form was
46 ms / 38.4 MB; the ranking definition/setup/equality request was 519.7 ms overall. The counters exclude
the surrounding REPL acquisition/recording work by design. No retained-heap or peak-space claim is made.

Validation:

- `clj-kondo --lint src script test --cache false`: 6,428 ms, **0 errors**, 1,462 warnings.
  These are full-tree warnings; this document changes no source or tests. Lint's warning exit was 2.
- `bin/seon check docs/research/agent-platform/2026-10-10/astra-malli-behavior.md`
  with separate `--test` arguments for
  `seon.instrument-test/jvm-and-interpreted-functions-arm-the-declared-guard` and
  `seon.id-test/identity-is-a-function-of-the-values-structure`: **passed**, run `fad213960462`,
  2 executed / 0 reused / 37 assertions passed / 0 failures / 0 errors. Named tests took 8,939 ms;
  the complete isolated check took 51,346 ms (boot 26,819 ms; tests phase 9,017 ms).
  The owner explicitly requested this isolated check. It is not an ordinary sub-second operation;
  existing `seon.test/one-test-run-costs-seconds-around-a-147-ms-body` records test overhead work.
- The candidate archive was `dddc3aa2df6a88ddc506f43d9412605ca01a52f9`. Publication took
  3,451 ms and adoption 1,224 ms. The check reported 274 adopted identities from committed HEAD's
  difference against the older running archive, not from this Markdown document. No foreign working
  source was loaded into default. The two named tests had no reds in this run; the broad tier was not run.

Cleanup and foreign boundary:

Self-retirement of `:agent-3acdc70164a2` returned `:my.agent/retiring? true` in 38 ms, but a roster read
after the request still found it. This is covered by the existing
`my.agent/retire-branch-refuses-a-finished-agent-as-admission-closed` task's run-exit retirement requirement.
The root SCI roster read itself triggered root's pending test step: 10,297.2 ms of a 10,402.6 ms request.
It surfaced unrelated reds in `my.edit-test/public-entries-declare-resolvable-io-capabilities`,
`my.fs-test/public-entries-declare-resolvable-io-capabilities`, and
`my.message-test/inbox-lists-this-agents-messages-newest-last` (the last also errored), with many tests
pending. These are observations of root's existing state, not failures introduced by this doc-only lane;
their causes were not investigated. Existing `my.agent/a-read-as-another-agent-blocks-its-retirement`
records this read-triggered test-step class. They do not negate the isolated named check above.

Cleanup then used JVM mode, explicit default connection/store/routing, and `my.agent/retire-branch!`
for this lane's branch only. It returned `:my.agent/retired? true`, and
`seon.cluster.registry/roster` confirmed absence in the same form (61 ms). That request's settlement
took 2,109.8 ms, total 2,191.8 ms; the cause of that recording delay was not profiled.
The three finding tasks were pulled back successfully from default before branch retirement.

Source/test line delta is zero. The only committed path is this research document. The lane commit
repeats the two named checks through its mandatory gate; its final outcome is reported with the commit.
