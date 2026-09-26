---
type: design-review
date: 2026-09-27
status: complete; design revision required
reviewer: Astra
design-commit: ccab0b3a8
source-baseline: d1f6a37fb0f56b35a0d6c5a6e7cb7453e815d9ad
verdict: REVISE
task: 12913e5cd6b0
---

# Review: contracts outside the schema projection

**REVISE.** Separating function contracts from the data-schema projection addresses the measured cause. The
proposed cut is not yet safe: three production readers are missing, contract-derived caches would become stale,
the new cache has unresolved inputs and unbounded retention, and admission must move to the final writer.
Dropping the contract range alone also does not deliver the promised zero projection loads.

This reviews `contracts-out-of-projection-design-2026-09-27.md` at `ccab0b3a8`, including its measurement landing
note. Source citations below use the baseline above; its named implementation files equal those at the design
commit. Malli is pinned at `43c6dca8d2d8df3162b13725939954807be4a4ed`; Datahike at
`67b00071e020a7ccf310985d01b28128cdadec34`. This review changed only this document. It ran no tests, changed no
Vars, created no branches, and restarted no JVMs. The only runtime evaluations were serial, read-only MCP JVM probes
with explicit root `/Users/sean/src/seon` and cluster `default`.
Other work advanced HEAD and edited shared files during the review. Citations remain pinned to the recorded
baseline; those edits were neither reverted nor included in this review's commit.

## Answers to the six review questions

| Question | Finding |
|---|---|
| Reader completeness | Falsified. Add `instrument/apply!`, `call-preparation/arm-plan`, and `fn/contract-findings`; also migrate the caches and declaration schema described below. |
| Complete cache key | Conditional for fixed registry/options/bindings, false as an unconditional claim about the current holder. Predicate overlays share holders; external Var resolution and `var-registry` are additional inputs. Symbol-only derived keys are definitely incomplete after the cut. |
| Stored references and refusal query | All 2,355 contracted functions in the observed database have arities and matching reference facts. The proposed query executes. Nested refs and ordinary aliases work, but a supported local registry shadow hides a canonical dependency. Final-writer coverage and ongoing fact integrity remain required. |
| Safe slices | Not as written. S2 changes producers before every old consumer is converted; S4 omits a required schema-resource edit. |
| Expected cost | The direction is supported by the measurement. Zero loads is contradicted by the remaining admission-attribute dependency. The latency, allocation and call-count targets remain unverified estimates. |
| Simpler composition | Keep a pure, explicit contract compile/admission seam, pass its compiled value to arming, preparation and fact derivation, and perform cross-row checks once at the final writer. Keep program-dependent renderer checks outside the cached shape constructor. |

## R1 — Complete the reader migration before changing producers

The design's “15” is a grouping of sites rather than a complete accessor inventory. Independent searches covered
the projection's four function members, `projection-with-function-contract`, `function-arities-in`, the four
public function-query APIs, and registry/compile-options accesses in `src`, `script`, and Clojure tests.

These production readers are absent from the design:

| Reader | Evidence and consequence after S4 |
|---|---|
| `seon.instrument/apply!` | `src/seon/instrument.clj:865-883` requires `(mr/schema registry (var-symbol candidate))` before adding a changed Var to `contracts`. Without function registry entries it excludes every changed candidate, then restores its uninstrumented original. Converting `compiled-wrapper` alone does not fix incremental arming. |
| `seon.call-preparation/arm-plan` | `src/seon/call_preparation.clj:757-776` reads the function directly from the registry. Its absence produces nil and drops supplied-default preparation from wrappers; its caller is `instrument.clj:689-699`. Pass the exact compiled contract being armed. This also works before a candidate has a stored row. |
| `seon.fn/contract-findings` | `src/seon/fn.clj:2076-2109,2190-2202` already holds spec strings but inspects a compiled registry entry by function symbol. It must compile that held spec through the new seam. |

The design correctly identifies the other direct production groups: instrumentation selection at
`instrument.clj:596-608,725-771`; the preparation table at `call_preparation.clj:159-169,300,619`; render presence,
return alternatives and retained evidence at `render.clj:411-477,863-919`; debug evidence at
`render/web.clj:960-969`; first-load comparison at `test.clj:2132-2158`; index/publication at
`fn.clj:3078-3143,3246-3277`; turn admission at `turn.clj:1258-1355`; and SCI admission/installation at
`sci/eval.clj:917-956,2038-2074,4802-4819`. Its schema-removal sites also exist.

The production call sites of the four public function-query APIs are `render.clj:279,525,526,676,1239` and
`effect.clj:175`. There are six direct calls in this baseline, not seven. The surrounding render request often
has a database, but `source-producer?` itself receives only projection, symbol and arguments
(`render.clj:272-281`); the database or contract must be threaded through its callers. This is more than changing
the callee's signature. `error/declared-output-validators` is correctly described as already reading an explicit
form (`error.clj:1749-1772`).

Inside `schema.clj`, include the entire dependency chain, not just keyword reads: registry construction
(`524-581`), fingerprint and population options (`2341-2349,2530-2544`), predicate binding (`2105-2121`), declaration
validation/build (`2002-2291`), incremental replacement (`2510-2774`), row parsing and deleted predicates
(`2793-3023`), all three replacement APIs (`3110-3133,3401-3448`), renderer coherence (`1880-1965`), and cached
function arities (`3683-3752`). The public generic validator/explainer APIs take schema keys; they are not
additional function-symbol readers.

**Required regression:** from a projection with no function entries, prove incremental host arming still rejects
bad input, a new SCI candidate receives omitted defaults before persistence, and contract findings inspect its
declared slots. Preserve removal/disarming behavior when the function really has no admitted contract.

## R2 — Fix every contract-derived key and the supplied-default snapshot

The proposed `[::contract-arities sym]` in design §2 is stale as soon as the same symbol gets another contract in
an unchanged projection. The existing `[::function-arities function-symbol]` (`schema.clj:3683-3691`) and
`[::output-alternatives function-symbol]` (`render.clj:417-424`) have the same problem. A correctly keyed inner
compiler cannot repair an outer cache hit. This includes a cached absent contract becoming present.

The supplied-default cache has a separate, concrete dependency loss. `snapshot-read-attributes` derives its read
set from the row query, row attributes, and `schema/projection-attributes`
(`call_preparation.clj:359-378`). The row query mentions supplier symbol but not supplier spec (`111-119`). Today
the projection contributes `:seon.fn/spec`. After S4 that attribute disappears from the snapshot revision key
(`403-419`) and from the final-report reuse decision (`484-505`). A supplier-contract edit can then reuse its old
coherence verdict, even though `derive-snapshot` reads that contract (`285-312`).

**Required fix:** key arities and return alternatives by the actual compiled contract or its complete content
inputs. Give snapshot acquisition its own explicit contract/admission read dependencies; do not inherit them
from the data-shape projection. Keep supplied-default rows and calling environment separate from the compiled
Malli schema. The wrapper depends on the snapshot as well as the schema (`instrument.clj:689-699,732-735,848-857`);
the proposed cache must retain schemas, not prepared wrappers or original callables.

**Required regressions:** same projection, same symbol, two different specs; absent then present; two branches
with equal schemas but different contracts; historical reads after a newer lookup; supplier spec-only change
turning a coherent supplier incoherent; snapshot refresh and `report-snapshot` both refusing that change;
default-row-only changes affecting preparation without recompiling the contract.

## R3 — State and enforce the compile inputs; bound retention

The correct library seam exists. `m/function-schema` delegates to `m/schema` and verifies a function schema
(`reference-code/malli/src/malli/core.cljc:3130-3134`). With an explicit immutable registry, canonical EDN form,
resolved predicate bindings and one fixed option set, compiling against a retained population is sound.
Local `:registry` properties belong to the form; Malli composes them ahead of the supplied registry
(`core.cljc:2545-2554`). Existing compiled schema objects retain their own options (`2605-2608,2634-2639`).

The holder claim nevertheless has counterexamples:

1. `with-predicate-functions` is an `assoc`, not a constructor installing a fresh holder
   (`schema.clj:401-414`). A real caller overlays bindings on a constructed projection
   (`test/seon/instrument_test.clj:1530-1539`). Two maps can share `:compiled` while carrying different predicate
   bindings. `[sym form admission-source]` cannot distinguish them.
2. A predicate absent from the shape population is resolved outside it. `runtime-predicate` returns a Var
   (`schema.clj:146-171`); `compilable-form` also falls back to the loaded namespace (`290-305`). Retaining a Var
   correctly observes a new root of that same Var. It does not prove that a symbol always names that Var after
   `ns-unmap`/namespace removal and re-interning. Explicit branch predicate callables are likewise distinct
   inputs. Resolve the relevant bindings before choosing the cache key, or establish their immutability at a
   named owner. Do not cache a failed unresolved lookup as permanent absence.
3. The armed option tag distinguishes two option *kinds*, not changing inputs within a kind. `mr/var-registry`
   dereferences a Var to obtain a schema (`registry.cljc:67-71`); Malli then compiles that value
   (`core.cljc:2616-2618`). Changing a Var holding `:int` to `:string` changes compilation without changing the
   form's Var object or the shape projection. Host collection covers all loaded namespaces
   (`instrument.clj:789-799`), so the durable EDN constraint cannot simply be assumed for all armed contracts.
4. Predicate/error behavior must retain its existing inputs. `bind-contract-predicates` handles `:error/fn`
   overrides (`instrument.clj:428-457`); `compilable-form` does not perform that map transformation
   (`schema.clj:274-324`). Merely extending predicate discovery with `runtime-predicate` is not equivalent.
   Malli's evaluator also reads loaded qualified values, and supports `::disable-sci`/`::sci-options`
   (`core.cljc:2934-2956`); its function validator can use `::function-checker` (`2145-2173`). The current shape
   constructor supplies only `:registry`, so these options are fixed absent there. Specify the exact accepted
   option set at the new seam; any supported additional options must enter its identity.

Admission is more than compiling Malli: it uses schema admissions, purity and reference traversal
(`schema.clj:1470-1650`). The source enum is the relevant function-admission field in the current checker, but
the caller must obtain it from the same function row. Missing stored provenance currently defaults to agent
(`schema.clj:2869-2874`). `declared-contract` returning only EDN leaves this acquisition unspecified. Preserve
that default and distinguish unreadable/malformed contracts from absence. Do not reuse an admission verdict
under a different predicate binding or purity population.

There is also a retention defect. The holder is an unbounded basic cache (`schema.clj:332-355,367-381`). Once the
projection survives arbitrary defns, it retains every contract version and every branch-only symbol until that
projection dies. The central cache weighs a projection once by its number of schema forms
(`schema.clj:2335-2339`; `cache.clj:115-124,159-164`), so later holder growth does not enter the byte budget.
“Does not outlive the projection” is not a bound when that projection is the deliberately long-lived object.

**Required fix:** use the existing bounded cache for an unbounded stream of contract versions, with a complete
resolved-input key and an explicit retention/weight policy. Alternatively prove a bounded owner for those
entries within the central budget. An armed-mode discriminator alone is insufficient. For mutable Var-schema
inputs, resolving their schema contents into the key or leaving that exceptional compile uncached is smaller
than inventing invalidation. Keep predicate Var invocation live where that is the declared semantics.

**Required regressions:** overlays sharing a base holder; same-Var predicate root replacement versus symbol
removal/recreation; distinct branch predicate bindings; armed Var-schema root changes; `:error/fn` binding
parity; agent/core admission parity; deleted predicates in historical contracts; many versions under one
unchanged projection staying within the configured retention budget. Preserve the existing deleted-predicate
diagnostic behavior (`schema.clj:2808-2840`) when contract predicates leave that scan.

## R4 — The stored facts are present, but reference completeness needs repair

The actual producer calls `compiled-references` for input, output and guard (`program.cljc:1033-1071`), and
`with-contract-facts` is used by file indexing (`fn.clj:3124-3133`) and SCI (`sci/eval.clj:941-953,3847-3856`). The
three attributes are indexed (`resources/seon/schemas/seon.fn.arity.edn:2-10`). Empty reference sets correctly
have no datoms; their absence is not evidence that a zero-ref contract lacks analysis.

Live evidence at basis `536871395`: **2,355 contracted functions, zero missing arity roots, zero mismatches**
between each stored arity's three sets and recomputation from its current projection registry entry. The full
comparison took **407 ms** in MCP JVM mode. The proposed query also ran unchanged for
`:seon.db/database-value`: **554 symbols, 8 ms**, at basis `536871414`. These observations establish presence
and parity for that population; they do not prove the walker or all future writes correct.

Ordinary nested references, explicit `:ref` inside a map, and a local string alias to a canonical key all produced
the expected canonical reference in a separate 3 ms probe. The failure is scoped-name shadowing:

```clojure
;; Canonical forms: {:review/a :int, :review/b :string}
[:=> [:cat [:schema {:registry {:review/a :review/b}} :review/a]] :int]
```

The compiled input accepts `["x"]`, so its meaning depends on canonical `:review/b`. Yet
`compiled-references` returns only `#{:review/a}`. Calling `assert-complete-contract!` with agent admission and
the two core schema admissions returns `[]` for this contract (3 ms). The canonical reverse graph has no edge
from `:review/a` to `:review/b`, so changing/removing `:review/b` never selects this function through §3's query.
The walker stops on a matching *name*, without proving it resolves to the canonical declaration
(`schema.clj:64-79`; Malli registry precedence at `registry.cljc:54-59`, local registry composition at
`core.cljc:2551-2553`). This is an existing walker defect inherited by the proposal, not a new query defect.

Presence alone is also not a continuing invariant. The row schema makes arities and their reference sets optional
(`resources/seon/schemas/seon.fn.edn:158-167`; `seon.fn.arity.edn:23-31`). Backfill checks root/argument-count
presence, not reference-set equality (`fn.clj:3188-3198`). The writer's digest check proves the digest of supplied
row content, not that its reference sets were derived from its spec (`db.clj:4313-4326,4352-4401`).

**Required fix:** repair reference discovery at its owner so local scope is respected, or demonstrate an existing
admission rule that excludes the counterexample; the observed checker does not. Establish canonical-writer
maintenance of exact arity facts on contract replacement and an explicit policy for missing/stale facts before
trusting the index. Select affected contracts using the appropriate before/candidate schema closures and validate
the *surviving candidate* contract, allowing repair or removal in the same transaction. Admission-only schema
changes also matter: the existing selection includes them (`schema.clj:2670-2677`).

The query performs three reference-attribute branches plus owner/symbol joins, with deduplication. “One AVET
probe per key” is not a demonstrated plan or its entire cost. The observed 8 ms proves execution, not a planner
guarantee. Price it as indexed reference lookups plus joins and the affected contracts' compilation/reference
walks; inspect the installed plan before promising tighter bounds.

**Required regressions:** input/output/guard refs, nested maps and unions, canonical alias chains, local aliases,
the shadowing example, local recursive registries, empty-ref contracts, missing/stale arity facts, ref replacement
without residual datoms, schema-admission-only changes, and same-transaction contract repair/removal.

## R5 — Put the refusal at the final writer and keep renderer coherence separate

The “two write paths” are not the authority covering every schema/program change. `validate-write-report`
acquires the final database's projection (`db.clj:4481-4503`). Today that acquisition compiles the contracts and
checks renderer coherence (`schema.clj:2998-3013`). Removing contracts from acquisition removes those checks
from ordinary writes, publication, adoption and merge paths that do not call `turn/row-tx` or SCI admission.
`write-report-error` validates stored values, digests, renderer *existence* and call arities
(`db.clj:4403-4479`); `write-render-target-error` checks only whether the named function exists
(`4086-4124`). Neither is a substitute for contract compilation/coherence.

Renderer coherence is a relation between schema rows and function contracts. Today a contract-only change
rechecks every schema naming the affected renderer (`schema.clj:2763-2773`). A `contract-of` callback supplied
only when the shape projection builds will not run when a renderer's contract changes and the shapes stay
identical. Keeping that callback in constructor options would also introduce program/closure identity into the
cache key the design is trying to remove (`schema.clj:2332-2339`).

**Required fix:** make the final-report gate mandatory in S3, checking newly changed contracts and contracts
affected by schema changes against `:db-after`. Share that pure checking operation with early admission where
useful. Trigger renderer coherence on either side of its relation changing; perform it outside the cached shape
constructor. Cover simultaneous repairs and deletions using final rows. No second program registry is needed.

**Required regressions:** direct admitted database write and publication/merge schema changes; invalid new spec
without a schema edit; renderer spec changes from accepting its declared shape to rejecting it; renderer contract
removal; same-transaction renderer/schema repair. An unrelated data write must perform none of these checks.

## R6 — Revise the slice boundaries and declaration resource together

S2 already changes index/publication to build shapes only and SCI to keep the previous projection. Meanwhile
`function-arities-in` and renderer checks inside `schema.clj` are scheduled in §4/S4, and the missing readers in
R1 are unscheduled. Saying “the projection still holds contracts” does not preserve them in projections those
converted producers stop populating. In particular a new candidate's arming plan needs its new compiled contract,
not the previous symbol entry or a database row that does not exist yet.

S4 is explicitly assigned only `schema.clj`/fingerprint, but
`resources/seon/schemas/seon.schema.edn:58-63` requires `:seon.schema/function-contract-rows` in
`:seon.schema/projection-input`. `projection-from-rows` declares that input schema (`schema.clj:2848-2854`).
Dropping the range without changing its declaration makes the armed loader reject its own input.

**Required order:** first add the explicit compile/admission seam with complete inputs; then convert every
consumer, including all derived keys and public API callers, while preserving legacy producer behavior; next
install final-writer contract/reference/renderer validation; finally remove producer population, range, old APIs
and required resource members together. Do not stop producers earlier. Convert tests referencing the removed
APIs/maps in that same cut, including the split schema acquisition/nil-contract tests, publication, SCI,
instrumentation, render coverage and test-accretion fixtures. No runtime load proof was run for this review.

S1 should also specify where database reads for `declared-contract` live. `seon.program` currently obtains its
database dependency indirectly; `seon.db` deliberately late-resolves program helpers (`db.clj:4273-4281`). Keep
that load direction explicit, use the database owner, and return its read refusals. A pure form compiler needs no
database dependency.

## R7 — Narrow the performance promise to what the proposed work actually removes

The measured contrast is credible evidence for separating contracts: the landing note records four definitions
of each kind on pid 89341, p50 **134 ms versus 757 ms**, **157 MB versus 1.21 GB allocated**, and approximately
**26,300 versus 189,900 armed calls**. These are historical measurements, not measurements of this design or the
currently running root.

However, `projection-attributes` retains `:seon.schema.admission/source` after dropping the function range
(`schema.clj:3063-3067`). New function rows receive that attribute (`program.cljc:1242-1243`; file rows at
`fn.clj:3328-3329`). Both `projection-cache-key` and `declaration-content-key` consume the entire attribute,
including non-schema entities (`db.clj:1303-1326,1328-1360`). A new contracted function therefore still changes
both keys. Merely filtering the loader's admission join to schema identities does not filter those keys. A load
may return the same projection via the unchanged-input path (`schema.clj:2620-2626`), but that is not zero loads.
Distinguish new definitions, edits that leave admission datoms unchanged, and deletion.

**Required fix:** make the projection's semantic admission inputs schema-scoped at acquisition/content-key
ownership, while preserving invalidation for actual schema admission changes. Update the “no db.clj edit” claim
and distinguish conservative revision misses from expensive loads. Measure both projection identity and actual
derivations; equality alone is insufficient.

The listed load cost (about 63-88 ms per defn at 1.75 loads) and first-use tables (up to about 105 ms per new
projection) do not independently account for the full roughly 620 ms difference. They may also overlap other
profiled costs. The 150-250 ms / 200 MB / 30-40k targets need an installed replay rather than additive subtraction.
The claim of one compile needs plumbing: `program/contract-facts` currently compiles again
(`program.cljc:1096-1099`), and arming has a distinct option set. Reuse the new compiled result where semantics
match; label remaining compiles explicitly. Likewise avoid rebuilding the population-wide validation base or
`core-references?` scan for every contract (`schema.clj:2030-2065`).

**Required regression/measurement:** through the actual agent path, define a fresh contracted symbol, replace
its contract, change only its body, delete it, and repeat on a branch sharing the population. Count projection
loads, content-key work, table derivations and contract compiles. Record warm p50, allocation, armed calls and
retained memory under repeated versions; preserve the same parent/change probe and workload. No such replay or
memory measurement was performed here.

## Smaller composition and acceptance condition

Keep the selected separation. The smaller implementation within that direction is to compile an explicitly
supplied form once, pass the compiled schema to existing `arity-facts`, contract-fact derivation and wrapper
construction, and have database owners resolve rows only where callers lack them. Use the existing central
cache, with resolved immutable inputs, for contract products that need reuse. Keep shape construction pure over
shape inputs; let one final-writer checker own program/schema relations. Early checks can call that same checker
with candidate values. This avoids a program callback retained by each projection, duplicate symbol lookups,
duplicate compilation and separate refusal policies at each entrance.

The design's deletion estimate is not yet an implementation estimate: the missing readers, snapshot dependencies,
cache retention, reference integrity, final writer and resource migration are outside its priced substitutions.
Recount after revising those sections. Acceptance requires a revised design naming those owners and slice
boundaries, followed by the listed behavior regressions and installed cost evidence during implementation.

## Read-only evidence and limits

Initial `bin/seon status` / MCP `runtime_status` reported pid **12212**, start instant
`2026-09-26T22:05:18.940Z`, degraded readiness and no database connection. The root was still advancing during
this review; a later read found a real connection. No recovery action was taken. The later loaded program identity
was `6ab841c1-c082-5523-a8b5-5cd5fa6ab705`. This does not establish whole-runtime health or installation of the
proposed API. `function-contract` and `declared-contract` remain proposed APIs.

The decisive stored-fact probe was the following single JVM form (407 ms; result shown above):

```clojure
(let [database @(seon.cluster.boot/connection "default")
      projection (seon.db/carried-projection database)
      registry (:seon.schema.projection/registry projection)
      canonical (set (keys (:seon.schema.projection/forms projection)))
      rows (seon.db/q
            '[:find ?sym
              (pull ?f [{:seon.fn/arities
                         [:seon.fn.arity/order :seon.fn.arity/input-refs
                          :seon.fn.arity/output-refs :seon.fn.arity/guard-refs]}])
              :where [?f :seon.fn/spec _] [?f :seon.fn/sym ?sym]] database)
      mismatches
      (into []
            (keep
             (fn [[sym row]]
               (let [compiled (malli.registry/schema registry sym)
                     expected
                     (when compiled
                       (mapv
                        (fn [arity]
                          (let [info (malli.core/-function-info arity)]
                            (mapv (fn [role]
                                    (if-let [schema (get info role)]
                                      (seon.schema/compiled-references schema canonical) #{}))
                                  [:input :output :guard])))
                        (malli.core/-function-schema-arities compiled)))
                     actual
                     (mapv (fn [arity]
                             (mapv #(set (get arity %))
                                   [:seon.fn.arity/input-refs :seon.fn.arity/output-refs
                                    :seon.fn.arity/guard-refs]))
                           (sort-by :seon.fn.arity/order (:seon.fn/arities row)))]
                 (when (not= expected actual)
                   {:sym sym :expected expected :actual actual}))))
            rows)]
  {:basis (:max-tx database) :contracted (count rows)
   :mismatch-count (count mismatches) :mismatches (vec (take 10 mismatches))})
```

The reference counterexample and admission check were this single JVM form (3 ms):

```clojure
(let [forms {:review/a :int :review/b :string}
      registry (malli.registry/fast-registry
                (merge (malli.core/default-schemas) (update-vals forms malli.core/schema)))
      options {:registry registry}
      form [:=> [:cat [:schema {:registry {:review/a :review/b}} :review/a]] :int]
      compiled (malli.core/function-schema form options)]
  {:admission
   (seon.schema/assert-complete-contract!
    {:seon.schema/identity 'review/f :seon.schema/definition form
     :seon.schema/forms forms :seon.schema/admission {:seon.schema.admission/source :agent}
     :seon.schema/admissions {:review/a {:seon.schema.admission/source :core}
                              :review/b {:seon.schema.admission/source :core}}
     :seon.schema/compiled compiled
     :seon.schema/compiled-schemas (update-vals forms malli.core/schema)
     :seon.schema/schema-dependencies {:review/a #{} :review/b #{}}
     :seon.schema/registry registry :seon.schema/compile-options options
     :seon.schema/canonical-keys (set (keys forms))})
   :refs (seon.schema/compiled-references compiled (set (keys forms)))})
;; => {:admission [], :refs #{:review/a}}
```

An initial multi-case probe put explicit `:ref` directly in a seqex slot and failed with
`:malli.core/potentially-recursive-seqex`; it produced no successful batch result. The corrected probe put that
ref inside a map and returned `#{:review/b}` for nested, explicit-ref and ordinary local-alias cases, versus
`#{:review/a}` for the shadowed case. No stored fixture or production mutation was used. Probe timings are MCP
evaluation times, not end-to-end agent performance. Allocation and retained memory were not measured.
