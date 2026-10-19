---
title: Closed runtime — independent Astra plan
date: 2026-10-19
status: proposed
type: design
---

# Closed runtime — independent Astra plan

Status: proposed; no implementation phase is green. Independent plan by lane
closed-astra, 2026-10-19. This document does not incorporate the other planner's
work. Source baseline is 2cdfa6a85; the comparison performance baseline is
716cc7527.

## Decision

Keep the exact, lexical code identity proposed in
[branch-runtime](branch-runtime.md#lane-runtime-fix2). Remove the dependencies
that make a schema projection depend on the whole runtime. Ordinary code calls
ordinary functions. Code selected from data is resolved once by its executing
runtime and supplied as a value **with its code identity**. Host inspection occurs
at acquisition or the entrant, and its observed data is passed to pure functions.

The existing flow is declarations → projection → validation/storage, with
call-preparation deriving how a contract accepts omitted inputs. Clojure's
namespace dependencies, Malli's registries and compiled validators, and Datahike's
final transaction report already own the necessary mechanisms. This plan removes
late namespace discovery from the middle of that flow. It adds no registry,
writer, evaluator, cache, retry or caller-closure rebinding.

Do not implement an analyzer exemption for quoted requiring-resolve and call the
work finished. It would repair an incomplete graph while retaining the wrong
dependency direction. Conversely, do not call a function closed merely because
a callback was passed into it: the callback's implementation is an input to the
derivation's identity.

## Evidence and limits

Read end to end: AGENTS.md; branch-runtime.md; requiring-resolve-inventory.md;
runtime-review.md; runtime-rereview.md; the Clojure, REPL, Datahike, modeling and
testing skills. Rechecked the relevant source and the landed waves
2f7e1c043, 9f7caa06a and e7af24f14.

Default was ready but faulted, running archived a92ba5b944, not source HEAD.
It reported an existing schema bridge failure associated with task
4cae87e6cadf and prior heap-watch/agent-resolution failures. No restart, reload,
root SCI request or source mutation was made. The live namespace
`seon.sci.runtime` did not expose the new reach-index implementation. An initial
lookup of the index in the wrong namespace failed; its reply was
result/eff6f3e143abd, 52.1 ms request time, 6.8 MB allocated. This is unavailable
installed evidence, not a passing runtime proof.

All experiments below were bounded JVM-mode reads with explicit connection
`(seon.cluster.boot/connection "default")`. The graph experiment uses the
committed reach-index's open-set fixed point as a local pure function over held
database facts; it does not install a replacement function. No throwaway runtime
implementation was made. In particular, the closure census is a **graph
substitution experiment**, not evidence that the proposed implementation already
closes those functions.

| Observation | Result handle | Evaluation / whole request | Allocation |
|---|---|---:|---:|
| Original graph: 7,683 names; 3,447 open; 78 cache callers, 50 open | result/e13fe77319e0b | 62 / 121 ms | 302.2 MB |
| Explicit-edge substitution including derived-shape | result/e8c4b35331a1a | 111 / 159.3 ms | 477.1 MB |
| Four derivations' actual reachable sets | result/edd2c17477d37 | 97 / 158.8 ms | 498.9 MB |
| Remaining directly open functions | result/e81410627e70a | 107 / 192.1 ms | 596.4 MB |
| Two Malli registries, same name, different callable | result/ea42d58e6b164 | 28 / 77.8 ms | 11.4 MB |
| Distinct stored callers of db/transact! or db/with | result/ed4dc8dd27d80 | 167 / 235.4 ms | 399.4 MB |

The graph allocations are high for the small computation: acquisition materializes
the stored call relation. They are one-off read costs, not projected runtime key
costs. A product operation must reuse the content-valid graph; it must not repeat
this census per lookup. No operation above exceeded one second.

Pinned dependency sources checked:

| Dependency | Revision | Seam and guarantee |
|---|---|---|
| Clojure | 3bc2b3e91fdff462620d84bbdfd40379d9c562e5 | Ordinary namespace/Var linkage; compiler demunging and stack frame data, rather than loading a namespace to classify a frame |
| Malli | dd13ff81139159778edb0a67b491eccc0baa991d | `malli.registry/-schema`, `malli.core/-lookup`, `malli.core/-fn-schema`, `malli.core/eval`, `malli.core/validator` |
| Datahike | ccba220be1556a5f9b7fde2fc7620d0e0c019e0e | `datahike.api/with`, `datahike.api/transact`, `datahike.db.transaction/validate-report`: judge the final report, including attempted idempotent writes |
| SCI | 33039c6e49b47096a68d2964db9be382525bf75e | Forked contexts own name resolution; host Vars are not substitutes for a branch's interpreted definitions |

## Ownership changes

### Cycle 2: already removed; retain the leaf

Commit 2f7e1c043 already moved the pure problem explanation into
`seon.error.problem`. The database no longer needs five delayed error-resolution
seams. Render declarations name the actual error renderers directly.
The commit changed 280 added / 290 deleted lines overall. Do not repeat this
phase or count its ten-line reduction as future savings.

The old live graph still contains those names. The census below substitutes their
real callees instead of deleting the edges and assuming independence. On HEAD,
verify the equivalent leaf reach with freshly published analysis.

### Cycle 3: the bridge's ambient acquisition is on the wrong side

`seon.schema.datahike` implements a transformation of a schema projection into
Datahike declarations/values. That transformation must consume the projection;
it must not acquire the current projection from `seon.schema`. Its back edge is
what makes the schema owner's delayed bridge calls necessary.

Move the ambient encode/decode entry wrappers to their existing upper owner and
convert every caller in that same commit. Keep the pure, explicit-projection
bridge below schema. For the bridge's validator lookup, use the handed registry
and Malli's validator directly. Move the existing projection cache/identity
helper implementation to the existing lower schema-internal owner where needed;
do not duplicate it in the bridge. Then replace the five bridge -in seams and
storage/value schema seams with ordinary calls.

The resource side has the same direction: packaged EDN reading/merging is below
schema admission. Separate that resource operation from the functions in
`seon.schema.edn` that admit schemas. Move the structural schema operation needed
by config derivation down with its existing pure owner, or keep admission above
the resource reader. The resulting graph must have resource read → forms and
schema → pure bridge, with no reverse request for a current schema.

The census also exposed `seon.program/derived-shape`: its two late bridge calls
must become ordinary calls in this slice. Omitting it leaves the supposedly
closed projection graph open. This is why merely removing the original 28
opening edges is not an adequate plan.

Costs: read packaged resources once per changed resource content; compile
validators once per registry/schema content; transform only the input attributes.
No namespace discovery and no per-attribute projection acquisition.

### Cycle 6: schema acquisition does not belong inside error construction

`seon.error.refusal/checking-projection` and
`seon.error.refusal/operation-projection` discover schema state while constructing
an error. Schema construction itself uses those error constructors. This is the
wrong back edge; moving the delayed calls to another namespace would retain it.

Split acquisition from construction at the existing owners. The schema owner
prepares the error variant's compiled explainer and identity projector from the
evaluation's held registry. The entrant supplies frame observations. Pass these
as explicit values to the constructor; omitted declared inputs can use the
existing schema-named evaluation bindings. The constructor builds and validates
the one error map with those values. Schema acquisition failures use the lower
diagnostic constructor, with explicit inputs, so they cannot recursively acquire
the projection that just failed.

The portable operator path must remain portable: it supplies its explicit
diagnostic inputs and does not acquire Malli/Caffeine through a delayed optional
seam. Preserve the current declared error variants, cause and reference
semantics. Do not add a second error shape or an unvalidated success route.
Acquisition of a compiled explainer is Malli's work; validation of the constructed
variant remains at its producer.

Delete schema-seam, checking-projection and operation-projection only in the
commit that changes their last consumers. The optional cache seam disappears
from the pure constructor as well. Keep errors-as-data at the actual interop
edge/entrant; do not catch defects inside this composition.

This is a platform slice because it changes entrant bindings and error
construction during boot, even though much of the implementation is pure src.

### Frame classification: pass the observation, not access to the JVM

`seon.error.refusal/classify-namespace` currently combines classpath resources,
dependency roots, and `find-ns`. `authored-namespace?` caches by namespace name,
although a source-less REPL namespace can subsequently appear. Neither loading
nor a name-only cache identifies that observation correctly.

At the entrant, capture the relevant runtime namespace membership and classpath
classification inputs alongside the throwable's frames. The pure frame selector
consumes this immutable observation. Cache the classpath-derived classification
by its actual classpath/resource identity. Runtime membership comes from the
executing context's facts at observation time, including source-less REPL
namespaces; do not freeze it at boot. Classification must cost the observed frames
and distinct namespaces, not enumerate every namespace for every error.

`prepared-call-prefix` only needs the qualified name of the preparation
function. Derive the compiler-compatible prefix from that quoted name; looking
up the Var to recover its name is unnecessary. Preserve the existing frame
selection behavior with cause-chain and preparation-frame regressions.

The census includes `seon.error.refusal/referenced` and
`seon.schema/projection-bindings` because of Var-sensitive operations; the latter's
opening can originate in its contract, not its body. Do not simply delete
`var?` from the analyzer's opening set. Keep host/SCI representation semantics
explicit, move the reference observation to the acquisition input where it is
actually needed, and distinguish analyzed contract dependencies from executable
body dependencies using their existing source ownership. The ordinary bindings
map itself does not inspect arbitrary program definitions.

Observed current classification on default: seon.db true, clojure.core false,
user true. The proof must additionally exercise a newly created source-less
namespace in a disposable context.

### Projection builder: identity belongs to the code doing the work

`seon.db/projection-builder`, `loaded-builder` and `builder-digest` infer the
builder through loaded namespaces, process instances and stored reach. The
executing definition already holds its exact lexical code identity. Use that
identity plus the declaration inputs and selected predicate identities.

A database value is a data input, not an assertion about which implementation is
executing against it. Compiled main reading a branch database must not claim to
be that branch's builder. Conversely, a branch implementation reading historical
data must identify its own code.

`seon.db/content-projection` accepts derivation callbacks. The wrapper's lexical
identity alone cannot identify an arbitrary callback. Supply the callback and
its code identity together from the runtime that resolved it; include both the
wrapper's relevant code and selected implementation in the key. This applies
equally to lookup, present?, retained values and nearest-base selection. A base
computed by a different builder is not the same derivation.

Convert connection construction sites to attach their existing projection state
explicitly through the existing owner. Delete the process-instance scan in
`connection-projection-state` once every constructor is converted. This is
projection state ownership, not validators-on-connection. An arbitrary immutable
database read does not acquire hidden process state.

### Predicates: Malli resolves schema names; an executing runtime resolves code

Malli's registry maps names to schemas. `malli.core/-lookup` consults that
registry; `malli.core/-fn-schema` evaluates its predicate child once and retains
the callable. These are distinct operations. A registry does not automatically
make every quoted predicate in a :fn form branch-aware.

The pinned Malli fork's `malli.core/-loaded-qualified-value`, introduced by
3517a3cd, checks the JVM namespace before its SCI path, even before disable-sci
can refuse symbolic evaluation. The upstream implementation before that change
used SCI for symbol/string/sequence expressions; a callable was passed through.
Giving the fork a branch predicate's qualified symbol can therefore select a
JVM Var. Do not build the design on this shortcut.

A bounded live experiment used two composite registries with the same schema
name and different callable predicates:

```clojure
(let [a (malli.registry/composite-registry
          {'closed-astra/predicate [:fn pos?]} (malli.core/default-schemas))
      b (malli.registry/composite-registry
          {'closed-astra/predicate [:fn neg?]} (malli.core/default-schemas))
      va (malli.core/validator 'closed-astra/predicate
           {:registry a :malli.core/disable-sci true})
      vb (malli.core/validator 'closed-astra/predicate
           {:registry b :malli.core/disable-sci true})]
  [(mapv va [-1 1]) (mapv vb [-1 1])])
;; => [[false true] [true false]]
```

Passing pos? as a callable preserved object identity. Passing the symbol
clojure.core/pos? returned its loaded Var in this fork, despite disable-sci true.
That second observation is precisely the wrong shortcut for branch code.

Resolve a schema's selected predicate names once in its executing runtime.
Give Malli callable-bearing schemas in that projection's registry; preserve
authored symbolic forms as declarations. The projection key includes the
selected definitions' content identities, namespace declarations and expanded
macro inputs, using the same runtime code identity mechanism as ordinary code.
A mutable Var object's identity alone is not sufficient.

Remove loaded-predicate-var and runtime-predicate's host fallback, core-predicate
registration by JVM name, and the unloaded-predicate environment constructed by
guessing loaded namespaces. A declaration whose executable predicate is
unavailable remains readable; attempting validation reports the specific
unavailable executable. It must not load code or silently substitute main.

The same rule covers schema identity projectors, :error/fn, generator callables,
supplier functions and optional hooks: schema data selects a value, acquisition
resolves it once, and its content identity travels as an input. Do not invent a
parallel global predicate registry beside the projection.

### Cycle 7: prepared arity is a producer fact, not a writer compilation

The ruling against a single boot-built validator-on-connection is supported.
The API accepts arbitrary connections/database values, and the live graph has
126 distinct stored functions calling db/transact! or db/with (tests included).
This is a different counting unit from the earlier 64 call sites; I did not
reproduce the roughly 25 connection sources and do not claim to refute that
inventory.

`seon.call-preparation/prepared-arities` already derives the accepted arity
bounds from a plan. `seon.db/prepared-arity-bounds` reconstructs them in the
writer, through delayed snapshot/plan calls. These are two derivations of one
fact. There is also a potential semantic difference: the producer retains the
declared minimum for a variadic arity, whereas the database reconstruction
subtracts supplied slots. The red table must settle the intended behavior.

`:seon.fn/prepared-arities` exists as an in-memory schema alias used in reports;
it is **not** currently an installed stored attribute. A live schema inspection
confirmed absence; querying it as an attribute was refused. Do not describe this
phase as merely reading facts already present.

Have the canonical declaration/preparation producer emit the prepared arity
facts with its plan, in the same admitted change. Declare the stored ownership
and relationship in the schema population; use a distinct storage name if its
meaning differs from the existing value schema. Record any new vocabulary before
using it. Changes to contracts, supplier declarations and function declarations
recompute affected facts from their actual input dependencies.

The checked writer compares those facts in the **final transaction report**.
A changed caller and repaired callee in one transaction must be accepted;
deleting/changing a supplier must not leave stale bounds accepted. The producer
and its dependency updates must run atomically before the final report check.
Use existing content digests/ownership and the canonical writer, not a new
freshness stamp or a trust exemption for authored facts.

Datahike's final-report hook already sees :db-after and attempted idempotent
writes, and accepts nil / refuses a non-nil result. Keep that model whole.
Delete the writer's preparation snapshot and plan compilation only when the
stored producer and all same-transaction repairs are proven. A pre-read of a
connection is not equivalent.

## Closure census and the remaining open code

The open-set computation starts with functions directly calling an opening
operation and propagates through the inverse call relation to a fixed point.
It uses the same opening operations as the committed reach index. The cache
caller population is functions directly calling lookup or lookup-within.

| Substitution on the held live graph | Of the original 50 that become closed |
|---|---:|
| Remove opening edges at the 28 projection-reachable open functions | 22 |
| Also add the actual callees formerly hidden behind lazy resolution | 5 |
| Also convert derived-shape's two bridge resolutions | **25** |

The first row is a deliberately inadequate model. The second explains why
counting deleted requiring-resolve forms is not evidence of closure. The third
is the model this plan proposes; the graph has 1,785 open functions globally,
down from 3,447. It does not claim all those global changes are independently
verified behaviors.

The four derivations then reach 163, 100, 132 and 553 functions respectively:
content-projection, declaration-projection, build-projection and config/effective.
The first three do not reach the hub change seon.db/as-of-key. Config/effective
**does**. Its invalidation is legitimate; the plan must not promise all four
remain cached on the hub row.

The 25 modeled closures are:

```clojure
[seon.cluster/derivation-keys
 seon.cluster/stored-derivation-schema-keys
 seon.cluster.agent/basis-valid?
 seon.cluster.agent/branch-admission-end
 seon.cluster.agent/branch-end
 seon.cluster.source/merge-base
 seon.config/effective
 seon.context/declared-tests
 seon.db/content-projection
 seon.db/request-view
 seon.fn/declared-edge-attributes
 seon.fn/gate-edges
 seon.program/authored-shapes
 seon.schema/build-projection
 seon.schema/declaration-projection
 seon.schema/direct-references
 seon.sci.eval/loaded-authored
 seon.sci.eval/loaded-digests
 seon.task/task-basis
 seon.task/tasks-tx
 seon.task/tasks-tx-in
 seon.test/destructive-reach
 seon.test/host
 seon.test.runner/reach-attribute
 seon.test.runner/retain-results!]
```

The remaining 25 cache callers are:

```clojure
[seon.cluster/branch-declaration-changes
 seon.cluster.source/file-proof
 seon.cluster.source/member-contributions
 seon.cluster.source/members-of
 seon.cluster.source/publication
 seon.cluster.source/unmerged-count
 seon.context/conflicts
 seon.db/behavior-digest
 seon.embed/corpus
 seon.health/problems-unit
 seon.health/profile-unit
 seon.health/replaced-roots
 seon.program/content-digest
 seon.program/own-changed-identities
 seon.program/program-attributes
 seon.sci.eval/derive-program-context
 seon.sci.eval/overlay-plan
 seon.sci.eval/program-context
 seon.sci.eval/record-refusals-once!
 seon.test/observable-changes
 seon.test.runner/current-results
 seon.test.runner/reach-entries
 seon.test.runner/reach-graph
 seon.test.runner/reached-graph
 seon.test.runner/tests-reaching]
```

Their direct opening dependencies are preserved in the census appendix. Some
names are already retired by the landed waves (notably the old preparation
error-output discovery), because the live program predates HEAD. Do not launch
deletion tasks for already deleted functions. A new publication census on the
candidate branch is a prerequisite to phase acceptance.

Can open code disappear entirely? **Not from a system exposing eval, arbitrary
name lookup and host introspection.** It can disappear from pure derivations
whose inputs are complete. Literal qualified lookup is an analyzable dependency
and ordinarily should become a direct call. Data-selected code is a dependency
of the supplied executable value, not a static call invented for every possible
symbol. Arbitrary eval consumes a runtime; until a narrower sound dependency is
established, that runtime's complete identity is a legitimate input.

For the remaining families:

- Program/schema reads: replace ambient discovery with held declarations,
  compiled registry and selected callable identities.
- References and documentation: resolve once in the viewing runtime, return the
  resolved entity/value and provenance, then format that value. A reference lookup
  is intentional product behavior; remove repeated discovery, not lookup itself.
- Instrumentation/profile/health: JVM Var identity and root replacement are
  observations at the host boundary. Supply the observations to the cached report.
  Never pretend SCI and JVM Var representations are interchangeable.
- SCI installation/evaluation: loading host namespaces, interpreting code and
  installing bindings belong to the runtime owner. Cache by explicit runtime and
  compiler inputs; these entry operations may remain open.
- Tests/merge/publication: consume the same declared program and runtime identity
  as the executor; no separate loaded-identity heuristic or implicit other-branch
  read.

A quoted symbol resolved really is a dependency when it denotes executable code.
Record that at resolution/acquisition using the analyzer/runtime's actual
resolution, including aliases and macro expansion. Do not label every quoted
symbol a call: quoted data, names displayed in documentation and unresolved
future declarations are different. Do not restore the previously reverted
literal-lookup heuristic as the implementation of closure.

## Cost prediction against 716cc7527

These are forecasts for the completed composition, **not measured wins**.
The original exact-key experiment was dynamically propagated; a fully lexical
implementation was not benchmarked here.

| Row | 716cc7527 measured | Existing exact-key experiment | Completed-plan forecast |
|---|---|---|---|
| :seon-db-nine-forms | 1,203.7 MB; 146,625 calls; 1,182 ms | 2,964.3–2,973.5 MB; about 348,800 calls; 2,925–2,978 ms | 1,250–1,450 MB (1.04–1.20×); 150k–190k calls; 1.1–1.6 s |
| :hub-behavior-change | 897.7 MB; 198,696 calls; 1,175 ms | 5,207.7–5,210.5 MB; about 869,400 calls; 6,403–7,220 ms | 1,050–1,300 MB (1.17–1.45×); 220k–300k calls; 1.3–2.0 s |
| :similar-by-name-warm | 25.8 MB; 7,432 calls; 51 ms | 23.8–24.2 MB; about 7,600 calls; 89–107 ms | 24–28 MB (0.93–1.09×); 7.4k–8.5k calls; 45–80 ms |

Basis: the exact hub run recorded two db/projection misses totaling 4,418 ms,
one schema/projection miss at 645 ms, eight config/effective misses at 554 ms,
and a declaration-projection miss at 539 ms. These times are nested and
**cannot be added or converted into exclusive allocation savings**. Removing
the broad projection dependency should remove the dominant amplification;
config/effective and several remaining open callers still invalidate. That is
why the hub forecast has a wider and worse range than nine-forms.

These ranges are engineering estimates, not statistical confidence intervals.
Actual allocation after each slice decides whether the plan survives. Run
parent and candidate in one quiet throwaway JVM through the same agent request
path. Cache order must include both runtimes first, repeated reads, historical
databases and composed branch changes.

Target acceptance is no allocation regression against 716cc7527, with the
three expensive projection caches retaining values when only an unrelated
definition changed. The forecast does not establish that target: further work
on remaining callers may be necessary. Existing one-second overruns remain
visible costs, not newly blessed budgets. The performance phase must name their
responsible contracted calls; no performance commit is justified solely by a
closed-function count.

## Loadable phases and red proofs

All phases are proposed. New proof names below are planned deftests or rows,
not existing green/red evidence. Root must add/run each red before assigning
implementation. No task or source change is filed by this docs-only lane.
Each slice converts its last callers and deletes its superseded path in that
same commit, then publishes and boots the candidate. Phase order keeps error
construction available while the lower schema dependencies move.

| Phase | Complete slice and proof | Gate | Expected probe cost |
|---|---|---|---|
| 0 | Add composed-runtime rows to seon.sci.runtime-test/a-composed-runtime-answers-what-its-own-source-answers: parent A/h1 then child B/h2; branch-only predicate; namespace/macro inputs; lazy/closure/thread escapes. Add proposed seon.sci.runtime-test/closed-projection-reach-is-derived-from-real-calls. Capture three-row parent allocation. | Platform: runtime/test runner, isolated check | Pure reach assertion <200 ms, <64 MB once graph held; each small composed call <100 ms, <8 MB warm; expensive integration rows retain their separately reported baseline |
| 1 | Finish cycle 3, including resource layering and derived-shape direct calls. Extend seon.schema.datahike-test/encode-transaction-resolves-the-declaration-population-once with a closed-call-reach assertion; run canonical-population-native-parity and compiled-entity-composition-keeps-scope-and-entry-boundaries. | Platform: schema publication/bridge | One explicit-projection transform <20 ms, <4 MB for the bounded test population; one registry acquisition, no per-attribute acquisition |
| 2 | Separate error acquisition/construction and frame observations; remove cycle 6 and classifier discovery. Extend seon.error.refusal-test/error-is-buildable-where-no-projection-is-handed and the-frame-comes-from-the-root-cause with explicit runtime observation/source-less namespace rows; run constructor-consumes-only-the-throwable-input, a-place-in-an-error-is-named-never-printed-through and error-constructor-builds-the-declared-alternative. | Platform: error/boot/entrant bindings; portable operator check | After acquisition, one error over ≤16 frames <5 ms, <0.5 MB; classification proportional to distinct observed namespaces |
| 3 | Resolve executable schema inputs in their runtime; pass callable-bearing Malli registry and content identities; delete host predicate fallback and registration. Add opposite-cache-order/mixed-branch rows to seon.schema-test/one-predicate-symbol-cannot-name-two-environments-callables and a-rebound-predicate-recompiles-the-keys-naming-it; run two-projections-never-exchange-a-compiled-validator and unloaded-predicate readability/error tests. | Platform: schema/compiler publication | Warm validator call <1 ms, <0.05 MB; changed one-predicate projection <20 ms, <4 MB; unchanged content compiles zero predicates |
| 4 | Store prepared arity from its one producer; writer judges final facts, converting supply/declaration updates atomically. Extend seon.db-test/arity-components-and-callers-are-checked-in-the-final-state with variadic, supply-change, same-tx repair and idempotent attempted-write rows. Run seon.call-preparation-test/a-two-slot-arity-prepares-only-unique-partial-placements and seon.db-test/a-first-population-is-judged-by-the-declarations-it-installs. | Platform: writer + stored schema; publish/reload live-store branch | Bounded one-function write <100 ms, <8 MB; zero writer-side preparation compilation; work proportional to changed declarations and affected callers |
| 5 | Lexical exact key plus explicit callback identity; remove builder/process guesses; convert lookup/present/retained/base selection together. Proposed seon.db-test/projection-cache-uses-the-executing-builder and seon.cache-test/all-cache-accesses-use-the-same-code-identity, plus existing runtime composition proof. | Platform: compiler/cache/runtime binding | Warm key selection constant in unrelated branch changes; <1 ms, <0.05 MB for fixed reached set; same content zero recomputation |
| 6 | Remaining pure consumers take already acquired values. Split into file-disjoint ≤15-minute slices only after HEAD census; each gets a red cache-order/reaching proof, never a bulk name sweep. Proposed seon.test.runner-test/reach-results-use-the-executed-runtime for runner identity; proposed seon.program-test/content-digest-needs-no-loaded-namespace for pure program digest. | Ordinary src via branch evidence where only pure consumers change; any evaluator/runner owner is platform | Changed-input work only; each held-data digest/report <20 ms, <4 MB at its bounded fixture size; no namespace enumeration |
| 7 | Run actual post-publication reach count and the three-row allocation comparison. Delete dynamic key propagation and any redundant builder key path only when all callers have lexical/input identities. Extend composed-runtime test with every relevant R1–R7 regression and proposed seon.sci.runtime-test/exact-keys-cost-only-their-dependencies. | Platform; central affected integration after root's batched restart | No allocation regression versus parent, with same-run wall-time comparison; report all forecast misses rather than widening a budget |

Source motion alone does not prove phase 1 or 2: publish/reload on a branch of
the live store and compile their consumers. Phase 3 must use actual branch
execution, not merely two hand-built Malli registries. Phase 4 is blocked unless
the final-state producer model handles supplier changes without writer-side
recompilation; do not substitute a connection-local validator to get green.

The cost estimates in this table are bounds for the small named probes,
derived from their fixed input sizes, not permission to run unbounded work.
They become measured assertion rows. Whole-loop regression rows carry their
existing measured costs until the producer is fixed.

## Deletion budget and rejected alternative

Measured current form spans with edamame metadata, including whole forms and
their existing contracts:

| Old implementation removed/replaced | Old lines |
|---|---:|
| db projection-builder, loaded-builder, builder-digest | 90 |
| db's three delayed preparation seams | 6 |
| db prepared-arity-bounds duplicate derivation | 22 |
| db delayed program aliases on this path | 8 |
| schema's eight delayed resource/bridge seams | 20 |
| runtime-predicate, loaded-predicate-var, register-core-predicate!, core-predicate-registered?, unloaded-predicate-functions, predicate-callable | 161 |
| refusal cache-seam, schema-seam, operation-projection, checking-projection, prepared-call-prefix | 48 |
| **Old form lines displaced** | **355** |

This is not a promised net deletion. Replacements include explicit acquisition
arguments, stored arity schema/producer work, contracts and regression rows.
Moved bridge helpers and classifier code are not deletions and are not counted.
The rewritten arity report and connection-state accessor are also not counted.
Two inline derived-shape resolutions and the eventual dynamic-key wrapper are
additional removals, excluded from 355 to avoid pretending their final diff is
known. The already landed cycle-2 reduction is excluded.

The simplest viable alternative is to retain the exact whole-runtime key for
every open derivation and do no ownership repair. It is correct but the measured
hub cost is approximately 5.8× baseline allocation and 5.4–6.1× wall time.
Reject it for ordinary operations. The even smaller alternative, a hand list of
closed functions or a literal-resolve exemption, does not prove the selected
predicate/callback/runtime inputs and risks repeating R1–R7.

The present plan is deliberately not a wholesale requiring-resolve ban. Audit
each remaining site against its purpose: direct static calls replace load-cycle
workarounds; runtime lookup remains an explicit operation only where the product
actually resolves user-selected names or executes code.

## Census reproduction

The following form is a read-only JVM experiment. It reproduces the substitution
model, not an installed implementation. On a newly published HEAD branch, first
run the original index with no substitutions and compare its actual names to
these predictions. The legacy names below intentionally match default's held
program for reproducibility.

```clojure
(let
 [database
  @(seon.cluster.boot/connection "default")
  names
  (seon.db/q
   '[:find [?sym ...] :where [?f :seon.fn/sym ?sym]]
   database)
  rows
  (seon.db/q
   '[:find
     ?sym
     (distinct ?callee)
     :where
     [?f :seon.fn/sym ?sym]
     [?f :seon.fn/calls ?callee]]
   database)
  calls
  (into
   (zipmap names (repeat #{}))
   (map (fn [[sym callees]] [sym (set callees)]))
   rows)
  opening
  '#{clojure.core/intern
     clojure.core/alter-var-root
     clojure.core/all-ns
     clojure.core/ns-interns
     clojure.core/bound?
     clojure.core/ns-imports
     clojure.core/ns-unalias
     clojure.core/var-get
     clojure.core/requiring-resolve
     clojure.core/find-ns
     clojure.core/find-var
     clojure.core/the-ns
     clojure.core/ns-publics
     clojure.core/eval
     clojure.core/load
     clojure.core/var?
     clojure.core/resolve
     clojure.core/ns-refers
     clojure.core/load-file
     clojure.core/ns-aliases
     clojure.core/create-ns
     clojure.core/ns-unmap
     clojure.core/remove-ns
     clojure.core/ns-resolve
     clojure.core/require
     clojure.core/load-string
     clojure.core/ns-map}
  open-of
  (fn
   [calls]
   (let
    [callers
     (reduce-kv
      (fn
       [held sym called]
       (reduce
        (fn* [%1 %2] (update %1 %2 (fnil conj []) sym))
        held
        (filter calls called)))
      {}
      calls)]
    (loop
     [held
      (into
       #{}
       (keep (fn [[sym called]] (when (some opening called) sym)))
       calls)
      todo
      (vec held)]
     (if-let
      [sym (peek todo)]
      (let
       [fresh (remove held (get callers sym))]
       (recur (into held fresh) (into (pop todo) fresh)))
      held))))
  reach-of
  (fn
   [calls sym]
   (loop
    [held #{sym} todo [sym]]
    (if-let
     [at (peek todo)]
     (let
      [fresh
       (remove
        held
        (filter (fn* [%1] (contains? calls %1)) (get calls at)))]
      (recur (into held fresh) (into (pop todo) fresh)))
     held)))
  cache-callers
  (into
   #{}
   (keep
    (fn
     [[s cs]]
     (when
      (some '#{seon.cache/lookup-within seon.cache/lookup} cs)
      s)))
   calls)
  baseline
  (open-of calls)
  cut
  '#{seon.error.refusal/referenced
     seon.schema/schema-datahike-malli->datahike-attr-in
     seon.db/error-scalar-text
     seon.db/program-definition-digest
     seon.schema/schema-datahike-storage-schema
     seon.schema/schema-datahike-storable-attribute-in?
     seon.error.refusal/checking-projection
     seon.schema/projection-bindings
     seon.schema/schema-datahike-assert-storable-schema!
     seon.error.refusal/operation-projection
     seon.schema/schema-datahike-value-schema
     seon.db/error-explain-problem
     seon.db/projection-builder
     seon.schema/loaded-predicate-var
     seon.schema/runtime-predicate
     seon.db/error-problem-sentence
     seon.schema/schema-edn-packaged-forms
     seon.db/call-preparation-plan-for
     seon.db/stored-namespace-context
     seon.error.refusal/classify-namespace
     seon.error.refusal/prepared-call-prefix
     seon.db/program-shapes-in
     seon.error.refusal/schema-seam
     seon.db/program-canonical-entity
     seon.db/call-preparation-report-snapshot
     seon.schema/unloaded-predicate-functions
     seon.db/connection-projection-state
     seon.error.refusal/cache-seam}
  changed
  (reduce
   (fn*
    [%1 %2]
    (update %1 %2 (fn [cs] (into #{} (remove opening) cs))))
   calls
   cut)
  replacements
  '{seon.schema/schema-datahike-malli->datahike-attr-in
    #{seon.schema.datahike/malli->datahike-attr-in},
    seon.db/error-scalar-text #{seon.error/scalar-text},
    seon.db/program-definition-digest
    #{seon.program/definition-digest},
    seon.schema/schema-datahike-storage-schema
    #{seon.schema.datahike/storage-schema},
    seon.schema/schema-datahike-storable-attribute-in?
    #{seon.schema.datahike/storable-attribute-in?},
    seon.schema/schema-datahike-assert-storable-schema!
    #{seon.schema.datahike/assert-storable-schema!},
    seon.schema/schema-datahike-value-schema
    #{seon.schema.datahike/value-schema},
    seon.db/error-explain-problem #{seon.error/explain-problem},
    seon.db/error-problem-sentence #{seon.error/problem-sentence},
    seon.schema/schema-edn-packaged-forms
    #{seon.schema.edn/packaged-forms},
    seon.db/stored-namespace-context #{},
    seon.db/program-shapes-in #{seon.program/shapes-in},
    seon.db/program-canonical-entity #{seon.program/canonical-row},
    seon.error.refusal/cache-seam #{seon.cache/lookup}}
  changed
  (reduce-kv
   (fn [m s targets] (update m s into targets))
   changed
   replacements)
  after
  (open-of changed)
  changed
  (->
   changed
   (update
    'seon.program/derived-shape
    (fn*
     [%1]
     (into
      (into #{} (remove opening) %1)
      '#{seon.schema.datahike/malli->datahike-schema-in
         seon.schema.datahike/vector-shaped-attr-in?}))))
  after
  (open-of changed)]
 {:baseline-open-cache-callers (count (filter baseline cache-callers)),
  :newly-closed
  (vec (sort (remove after (filter baseline cache-callers)))),
  :still-open (vec (sort (filter after cache-callers))),
  :open-functions (count after)})
```

The final model's directly open functions reachable from the 25 remaining cache
callers were:

```clojure
[seon.call-preparation/error-declared-schema-keys
 seon.call-preparation/error-output-validators
 seon.call-preparation/supply-from-row
 seon.cluster.source/contribution seon.cluster.source/members-of
 seon.db/program-behavior-digest seon.db/stamp-transaction
 seon.embed/texts-of seon.error/proof
 seon.error.refusal/current-world seon.error.refusal/frame-function
 seon.error.refusal/interpreted-frames seon.error.refusal/var-digest
 seon.fn.analyzer/qualify-schema-symbols
 seon.instrument/definition-location seon.instrument/example-forms
 seon.instrument/jvm-arglists seon.instrument/replaced-roots
 seon.problems/indexed-function-var? seon.problems/stale-vars
 seon.profile/pain seon.program/base-context-injected-symbols
 seon.program/content-digest seon.program/declaration-digest-map
 seon.program/derived-program-attributes seon.program/differing-declarations
 seon.program/fold-content-digest seon.program/overrides
 seon.program/own-changed-identities seon.program/projected-values
 seon.ref/alias-form? seon.ref/at-level seon.ref/library-definition
 seon.ref/named? seon.ref/namespace-name seon.ref/resolve-name
 seon.render.value/config-defaults seon.render.value/datum
 seon.render.value/render-agent-render-profile
 seon.render.value/render-declared-projection
 seon.render.value/render-viewer seon.render.value/walk-with-related
 seon.schema/canonical-schema-entities seon.schema/database-projection
 seon.schema/identity-only-descriptors-in seon.schema/predicate-callable
 seon.schema/schema-datahike-database-attributes-in
 seon.schema/schema-datahike-storable-properties-in
 seon.sci.admit/sci-kernel-interrupted?
 seon.sci.eval/acquire-program! seon.sci.eval/build-base-ctx
 seon.sci.eval/copy-host-var seon.sci.eval/host-namespace!
 seon.sci.eval/install-caller-host-bindings!
 seon.sci.eval/install-first-party-namespaces!
 seon.sci.eval/install-jvm-root! seon.sci.eval/interpret-overlay!
 seon.sci.eval/load-host-namespace seon.sci.eval/source-namespace?
 seon.sci.lookup/program-dir-var
 seon.test.runner/loaded-identity seon.test.runner/task-identity]
```

## Landing verification

This lane changes documentation only: zero source/test lines. Full working-tree
clj-kondo over src script test with --cache false completed in 10,208 ms:
zero errors, 1,851 warnings (exit 2 for warnings). No warnings were reclassified
as passes or fixed in foreign files.

Foreign boundary: default runs the reverted archive and has existing faults;
another lane owns in-flight operator/class-cache work. The commit candidate is
HEAD plus this document only. No source/test, dependency or other plan was edited.
No claims about installed closure, performance gains or newly red tests are made.
