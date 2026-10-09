---
title: "Card BEHAVIOR-SPEC: a task's proof states behaviour, data and cost"
type: reference
status: active
created: 2026-10-10
---

# BEHAVIOR-SPEC A task's proof states behaviour, data and cost

Owner (2026-10-10): the agent that researches a task writes the tests it must pass as behaviour, not code; it writes
the Malli specs so the data is known and validated; it does the data modeling (reuse what exists, design around the
data flow); and the proof qualifies the solution by performance, rejecting slow ones at once. The implementer only
writes bodies. Research, sources and raw numbers:
[generative-testing](../../../research/agent-platform/2026-10-10/generative-testing.md). Lane: behavior-specs (Opus).
Nothing here is installed; the platform freeze holds, so the card names what must land and in which owner.

## What exists

- **Contracts.** About 2,891 `[:=> ...]` schemas, armed on every call with scope `#{:input :output :guard}`
  (`seon.instrument`, `seon.sci.arm/arm!`). Malli checks a guard `[:=> in out guard]` against `[args ret]`
  (`malli.core/-instrument-f`) and Seon already reports a refused guard as data: `:seon.instrument/check :guard`,
  `:seon.error/input`, `:seon.error/offending [args ret]`, `:seon.error/result-contract`. No function has a guard
  (zero arities carry `:seon.fn.arity/guard-refs`).
- **Generation.** `malli.generator/generator`, `generate {:seed :size}`, `:gen/*` properties; `malli.generator/check`
  over `malli.generator/function-checker` (100 trials, no seed, seed dropped from the result). Seon's property tests
  call `clojure.test.check/quick-check` with a fixed `:seed` (`seon.id-test`, `seon.program-test`) and assert with
  `seon.test-support/assert-check!`.
- **Cost.** `seon.profile/measure` gives the calling thread's allocation, wall ms and armed calls per symbol.
  `seon.sci.forgive-test/a-reply-reads-in-allocation-linear-in-its-length` already asserts a growth bound (4× input,
  at most 6× allocation), reading `ThreadMXBean` itself. COST-REG ([card](COST-REG.md)) turns a reaching test red when it
  costs more than main's evidence for the same test.
- **Tasks.** `:seon.task/entry` is a closed map (name, kind, intent, must-read, evidence); proofs are deftests tagged
  `:seon/tasks`. `seon.contracts/task` generates contract tasks whose proof is a row in `seon.contract-coverage-test`.

## 1. The proof is three things, in this order of cost

A designer turns "what must become true" into, for each function on the task's data flow:

1. **The contract**, `[:=> [:cat in...] out guard]`: input and output by registered schema names (the data model, §6),
   and a guard only for a relation cheaper than the body (§2). Armed on every call, in production and in every test.
2. **One property deftest** over generated inputs, for the relations too expensive to arm: a model or oracle
   comparison, a metamorphic relation, a round trip, idempotence, a postcondition over the whole input. Bounded and
   seeded (§3). Hughes's comparison (validity properties miss five of eight bugs; model-based and metamorphic
   properties find all) says: write at least one model-based or metamorphic property, never invariants alone.
3. **One growth deftest** when the task declares a cost (§4): the function's allocation across generated sizes n…8n,
   compared with the declared exponent and with a reference implementation.

Plus one or two concrete examples in a table (`are`), the cases a reader checks by eye. The examples are documentation
the runner executes; the property is the proof. All three are ordinary deftests tagged with the task, red before the
body exists.

## 2. Guard or property: the cost rule

A guard runs on every call, so it obeys the contracts guide's rule (contracts-guide §2.6, "the check must cost less
than the body"), measured, not assumed.

| Relation | Where | Why |
|---|---|---|
| Sizes: `(<= (count out) (count in))`, `(= (count out) (min k (count in)))` | guard | O(1) on counted collections |
| Order or distinctness of a bounded output (top-k, a page) | guard | O(k), k fixed |
| Membership of each output element in an input set or map | guard | O(k) lookups |
| Conservation of a small summary (sum of counts, total bytes) | guard if already computed, else property | must not recompute the body |
| Every output element in an input vector | property | O(n·k) scan |
| "No omitted element is better than the last chosen" (an oracle over all input) | property | as costly as the body |
| Idempotence, round trip, permutation invariance | property | calls the function again |
| Agreement with a model or a reference implementation | property | runs a second implementation |

Measured: the top-five guard on a 4,000-name ranking allocated 5,824 bytes against the body's 1,875,472 (0.3%).
Rules that follow from the probes:

- **Total.** A guard never throws: `(apply <= [])` threw for an empty output and Malli reported a correct sort as a
  guard failure. Handle the empty case.
- **Named and pure.** A guard is `[:fn 'ns/pred]`, a quoted symbol naming an admitted, contracted predicate of
  `[:tuple [:tuple in...] out]`; never an inline `fn`. It reads only its argument (no database, no world).
- **One relation per predicate**, named for it (`ranked-by-distance?`), so a refusal names the broken relation.

## 3. Bounded, seeded, reproducible generation

- Input schemas carry their bounds (`{:max n}`, `:gen/max`, `:gen/elements` for domain tokens); a property passes
  `:max-size` (default 200 otherwise) and a fixed `:seed`, so every run checks the same inputs and a failure names a
  seed that reproduces it.
- **Malli fork change (first, small):** `malli.generator/function-checker` reads `::mg/=>seed` and `::mg/=>max-size`
  beside `::mg/=>iterations`, passes them to `clojure.test.check/quick-check`, and keeps `:seed` and `:num-tests` in
  its result. Then a contract's property is one call, `(mg/check schema f {::mg/=>seed s ::mg/=>max-size m
  ::mg/=>iterations n})`, and Seon writes no checker of its own. Until it lands, a test uses
  `tc/quick-check` over `(mg/generator input)` with the output and guard validators (the four lines of
  `function-checker`), to be deleted with the fork bump.
- Assert with `seon.test-support/assert-check!`: it requires a positive trial count and keeps the shrink data.

## 4. Qualifying performance: declared growth, measured on generated sizes

**The declaration.** The designer states what the work is proportional to: the size variable (names, lines,
declarations reached, changed declarations) and the exponent (0 constant, 1 linear, 2 quadratic). It lives in the
growth deftest as data, not on the task: the closed task entry needs no new key, and `:seon.profile/growth` is already
a profile row's name. If three or more proofs repeat the same shape, promote it to a declared property of the
function schema (`[:=> {:seon.cost/growth ...} ...]`), not before.

**The measurement.** For sizes n, 2n, 4n, 8n:

1. Build each input deterministically: `(mg/generate [:vector {:min n :max n} item] {:seed s})`, or a seeded unit
   replicated n times, or a prefix of real data (all declared names).
2. Warm at 8n once (JIT compiles, escape analysis settles), then measure each size ascending, three runs each, with
   `seon.profile/measure`; take the median of `:seon.profile/alloc-bytes`. Subtract the allocation at size 0 (the
   constant overhead, such as a query) so it does not flatten the exponent.
3. Fit the exponent as the least-squares slope of log(alloc) on log(n).
4. When the designer supplied a reference implementation (usually the model of §1.2), measure it the same way.

**The verdict** (red is an ordinary failing `is` whose data carries every number):

| signal | red when | catches |
|---|---|---|
| exponent | above declared + 0.25 | quadratic in a linear task (measured 2.00 against 1.01) |
| ratio to reference at 8n | above 2 | a log factor or a constant: the `sort-by`-keyed distance (11.9×, exponent only 1.08) |
| allocation absent | always (typed unknown, never a pass) | work on a virtual thread, which has no counter |

**Why allocation, ratios and sizes up to 8n.** Allocation repeated exactly across five warm runs (1,875,472 bytes ×5);
wall ms spread 4.7–9.8× at single-digit ms (COST-REG measurements). A ratio cancels the JVM and machine. Small sizes
hide the trap: the live `seon.error/names-in` carries the same keyed `sort-by` and costs only 1.8× the decorate-once
form on `seon.db`'s 280 names, below COST-REG's 2× factor and 64 MB floor; at 4,000 names the same pattern is 11.9×.
Armed calls are a second exact signal when the repeated work is a Seon function; allocation is the signal that sees
unarmed library work (`malli.error/levenshtein`).

**The cost of the proof.** One warm call at 8n plus three at each size is 53n units of body work, plus the reference.
The designer picks n so the reference's 8n call costs a few milliseconds (4,000 names: 3.5 ms; the whole check
about 30 ms). A candidate more than 20× the reference at any size stops the check there and reports red, so a slow
solution cannot make the step slow. The property check at 100 trials and max size 30 costs tens of ms. Both run in the
per-request test step as proofs, and unchanged results are reused by reach digest like any member.

**Composition with COST-REG.** The growth proof qualifies the algorithm before main has any evidence and at sizes the
reaching tests never reach; COST-REG then holds every reaching test, the growth proof included, to its main baseline
after merge. The reference ratio is the growth proof's own baseline, so it needs no captured evidence.

**Composition with contract tasks.** `seon.contracts/task` stays as it is for back-filled contracts. A designed task
adds, beside its contract row in `seon.contract-coverage-test`, its property and growth deftests tagged with the same
task; a generator for that triple is a later step once a few designed tasks have been run by hand.

## 5. What an agent sees when a proof fails

All data, through the value printer:

- **Guard, at any call:** the Seon instrument error, `:seon.instrument/check :guard`, `:seon.error/input`,
  `:seon.error/offending [args ret]`, `:seon.error/expected [:fn ns/pred]` (observed on the branch).
- **Property:** test.check's map as `assert-check!` keeps it: `:seed`, `:num-tests`, `:shrunk {:smallest ...}`, and
  with `mg/check` `:malli.core/explain-guard` or `explain-output` on the smallest input.
- **Growth:** `{:sizes [n 2n 4n 8n] :alloc-bytes [...] :exponent e :declared d :reference-alloc-bytes [...] :ratio r}`
  plus `measure`'s `:seon.profile/ranked-by-calls` at 8n, so the agent sees which functions did the extra work.

## 6. The designer does the data modeling

The designer owns the data before any body: what flows in, which function transforms it, where it is stored and
rendered. Its output for a task is: the existing schemas reused (by keyword); each new schema with its producer and
consumer named; the function contracts on that flow (input, output, guard); the behavioural proofs; the declared
growth. The implementer writes bodies against it.

**Procedure, with the tools verified at the REPL on 2026-10-10:**

1. **Find the data already flowing.** Producers and consumers of a key are a query over contract references:
   functions whose arities name `k` in `:seon.fn.arity/output-refs` (producers) or `:seon.fn.arity/input-refs`
   (consumers); 28 ms. For `:seon.ref/candidate`: producers `seon.ref/span-candidate`, `link-candidate`, `relative`,
   `candidate`; consumers `seon.ref/answer`, `resolve-path`. `(my.program/reads-key {:seon.schema/key k})` answers
   all contract references at once (`:seon.program/contract-refs`) plus writes; `seon.fn/functions-using` and
   `(my.program/search k)` find literal mentions.
2. **Search the registry by name.** `(-> (seon.db/db) seon.db/carried-projection :seon.schema.projection/registry
   malli.registry/schemas keys)`, filtered by namespace or name fragment: 6,823 entries, instant.
3. **Search by shape: missing.** Asking whether a structure already has a name (four registered names already mean
   `[:vector :qualified-symbol]`: `:seon.program/gating`, `:seon.render/viewer-functions`, `:seon.source/host-bound`,
   `:seon.source/unreached`) cost 738 MB and 449 ms through `m/form` over the compiled registry, and
   `:seon.schema/form` is stored as text, so a query would compare printed forms. Needed: a structural digest of each
   schema's form, stored and indexed at the writer, so "who already means this shape" is one indexed lookup.
4. **Read each reused schema's producer contract and one real value** (contracts-guide §3), and validate real values
   against the chosen schema before writing it into a contract.
5. **Name a new schema only for a new concept**, in its owner's `resources/seon/schemas/<ns>.edn`, with its generator
   (`:gen/*`) and bounds, so properties can generate it.

**Gap:** consumers often declare `:map` or inner keys instead of the producer's named schema, so the flow breaks in
the query: `:seon.batch/forgiven` has a producer (`seon.sci.forgive/forgive`) and no declared consumer. The designer
fixes that on the functions its task touches.

## 7. Prototypes (REPL, branch behavior-specs, default ffe62e1c3)

Kept here as code, not committed as tests: the guard cannot be admitted yet (§8), and the forgive property is red.

**A. Nearest names, the fan-out-9 trap.** Guard (cheap relation) and property (oracle over all input):

```clojure
(defn ranked-by-distance?
  "True when the ranked names' distances never decrease, none repeats, and at most five are kept."
  {:malli/schema [:=> [:cat [:tuple [:tuple :string [:vector :symbol]] [:vector :symbol]]] :boolean]}
  [[[wanted names] ranked]]
  (let [ds (mapv #(malli.error/levenshtein wanted (name %)) ranked)]
    (and (<= (count ranked) (min 5 (count names)))
         (= (count ranked) (count (distinct ranked)))
         (or (empty? ds) (apply <= ds)))))

(defn nearest
  "Up to five names nearest to wanted by edit distance, ties by name."
  {:malli/schema [:=> [:cat :string [:vector :symbol]] [:vector :symbol] [:fn 'my.ns/ranked-by-distance?]]}
  [wanted names]
  (into [] (comp (take 5) (map peek))
        (sort (map (fn [s] [(malli.error/levenshtein wanted (name s)) (str s) s]) names))))

;; property (test only): no omitted name is strictly closer than the last one kept
(prop/for-all [[wanted names] input-gen]
  (let [kept (nearest wanted names) d #(malli.error/levenshtein wanted (name %))]
    (or (< (count kept) 5) (every? #(<= (d (peek kept)) (d %)) (remove (set kept) names)))))
```

Both the correct form and the trap (`(sort-by (fn [s] [(levenshtein wanted (name s)) (str s)]) names)`) pass the guard
and the property: they return the same answer. Only the growth proof separates them:

| names | good: alloc, ms | trap: alloc, ms | ratio |
|---|---|---|---|
| 500 | 0.23 MB, 0.44 | 2.37 MB, 4.24 | 10.3 |
| 4,000 | 1.88 MB, 3.55 | 22.45 MB, 40.83 | 11.9 |
| exponent | 1.01 | 1.08 | |

Red on the reference ratio (11.9 > 2); the exponent alone (1.08 ≤ 1.25) would have passed it.

**B. `seon.sci.forgive/forgive`, a real transform on every request.** Metamorphic property: forgiving the forgiven
text repairs nothing and keeps its text and spans.

```clojure
(tc/quick-check 300 (prop/for-all [cs (mg/generator [:vector [:enum "(" ")" "[" "]" "{" "}" "\"" " " "a" ":k" "\n" ";" "\\" "#" "'" "`" "x/y" "1"]])]
                      (let [once (forgive/forgive (apply str cs) :code)
                            twice (forgive/forgive (:seon.batch/text once) :code)]
                        (and (= (:seon.batch/text once) (:seon.batch/text twice))
                             (not (find twice :seon.batch/repairs))
                             (= (:seon.batch/spans once) (:seon.batch/spans twice)))))
                :seed 20261010 :max-size 60)
;; => {:pass? false, :num-tests 17, :shrunk {:smallest [["a" "]" "a"]]}}
```

`"a]a"` forgives to `"aa"` with spans `[[0 1 1 1] [1 2 1 2]]` (two forms) and re-reads as one form `[[0 2 1 1]]`:
dropping a stray closer joins two tokens. Growth: exponent 1.05 over 200–1,600 lines (0.25 → 2.18 MB), linear.

**C. Line of each offset, a quadratic data transform** (the shape of `my.program`'s per-declaration newline count):
counting newlines from the start for each offset, exponent 2.00 (7.2 MB → 463 MB over 50–400 declarations); newline
positions once then binary search, exponent 1.01 (0.26 → 2.10 MB). Red on the exponent alone.

## 8. What must land first (owners and order)

1. **Agents cannot admit any `[:fn pred]` contract** (guard or not): `seon.schema` refuses it as
   `:seon.schema/unproved-predicate-purity` because no production code fills `pure-predicate-symbols`; observed for
   `'seon.db/database-value?` and `'clojure.core/map?`. Owner: `seon.schema` (Opus, platform). Without it no designed
   guard can merge. Smallest fix to evaluate: derive purity from the predicate's stored call graph, or admit predicates
   that are themselves admitted, contracted Seon functions.
2. **A predicate defined on the branch cannot be named**: `seon.schema/compilable-form` resolves only loaded host Vars.
   Same owner; the designer otherwise must merge the predicate before the contract that names it.
3. **An unquoted predicate in REPL metadata** fails as "No reader function for tag object" (`seon.program/read-edn`);
   the reply should say to quote it. Owner: `seon.program` / the REPL fix text.
4. **Malli fork: seed and max size in `function-checker`** (§3). Owner: root's batched fork bump.
5. **A growth helper in `seon.test-support`** returning the §5 data, built on `seon.profile/measure` (test code, after
   the freeze); `seon.sci.forgive-test`'s hand-read `ThreadMXBean` converts to it.
6. **A structural schema index** for shape search (§6.3). Owner: `seon.schema` writer.

## 9. Proposed AGENTS.md wording (root edits AGENTS.md after review)

Under "Write tasks as data an agent can be handed", replacing nothing, one paragraph:

> **Design the data and the behaviour before the body.** The agent that writes a task first models its data: it finds
> the producers and consumers of every key the work touches (contract references in `:seon.fn.arity/input-refs` and
> `output-refs`), reuses registered schemas by keyword, and names a new schema only for a new concept, in its owner's
> schema file with its generator. It then writes the proofs as behaviour, never as code: each function's contract with
> a guard for a relation cheaper than the body; one seeded, bounded property test for the costly relations, at least
> one of them model-based or metamorphic; and, when the work has a cost, one growth test that measures allocation over
> generated sizes n to 8n and fails above the declared exponent or above twice a reference implementation. The
> implementer writes bodies until those proofs are green.

## 10. Open questions for the owner

1. Purity of guard predicates (§8.1): derive from the call graph now, or admit any admitted contracted Seon predicate
   and rely on review? The second is small and unblocks guards today.
2. Reference ratio threshold: 2× (recommended, matches COST-REG's allocation factor), or per task?
3. Should the designer's reference implementation also be the property's model (one function, two uses), accepting
   Hughes's warning that the model must not copy the implementation? Recommended yes for growth, with the property
   stated as a postcondition where a copy would be the only model.
4. Promote the growth declaration into the function schema's properties after three uses, or keep it in tests?
5. Stateful/model-based testing (fugato style) for branch and merge lifecycles: worth a research card of its own?
