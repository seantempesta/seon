---
type: plan
status: launch specification; waits for step-4 landing and overlapping-path release
created: 2026-09-21
tags: [plan, schema, malli, bridge, instrumentation, measurement, dissolution]
---

# Bridge step 5 — instrument retained contracts

**Guarantee:** a wrapper is built from the supplied generation's retained
compiled function schema. There is no second compilation of that contract
in collection, dependency inspection, host wrapping, SCI installation,
first invocation, re-arming or restoration. Arming **N** functions from an
already acquired complete generation performs **zero named-declaration
compilations**, counted at the real construction seams. N is derived and
positive. An unchanged wrapper retains identity; a changed contract or
referenced declaration is enforced on the next arm. Private functions and
undeclared error returns remain checked on the same harness as the cluster.

This is the verbatim launch assignment for §4 step 5 of the binding
[Malli-native bridge PRD](malli-native-bridge-prd-2026-09-20.md). Its §2.5
retains Seon's wrapper around Malli, and §3 distinguishes redundant
compilation from policy that must survive. Read the prior
[step-3 spec](bridge-step3-stamp-spec-2026-09-21.md) and
[step-4 spec](bridge-step4-writer-diet-spec-2026-09-21.md), the
[accepted dissolution review](../research/bridge-dissolution-review-2026-09-20.md)
and [step-1 landing](../research/bridge-step1-registry-2026-09-20.md).
The current [error-conversion PRD §1.2](error-conversion-prd-2026-09-20.md)
governs facet permission: a complete declared facet may coexist with other
facets. Step 5 does not revive the older all-facets restriction.

## Launch verbatim on astra low, after step 4 lands

> Implement **step 5 only: instrument retained contracts** in
> /Users/sean/src/seon, branch steward-platform. Read this specification,
> the bridge PRD, step-3 and step-4 specs, accepted dissolution review,
> step-1 landing and error-conversion PRD end to end. Read the actual
> steps 2–4 landing notes, current working edge, AGENTS §§1–5 and lane
> rules 11–16, and data-oriented-clojure, repl and clojure-testing skills.
> Read the dependency ledger below against the pinned source before edits.
>
> You are not alone in the codebase. Preserve unrelated edits; never
> revert another lane's work. Execute directly without further delegation.
> Launch after the coherent **step 4 — Remove duplicate native write
> checks** implementation lands, not after its design document appears.
> Inherit **step 1 — Registry: compile once, seal, carry**, **step 2 —
> Compiled walker**, and **step 3 — Carry only; durable stamp** by their
> actual landing commits. Check current path holders through the
> orchestrator; old ownership is not a permanent hold.
>
> Step 1 supplies retained named schemas and function contracts, serial
> fixed-input providers, the sealed registry, dependency closure, unchanged
> root reuse and live-Var predicates. Step 2 supplies compiled navigation,
> component transaction grammar and native storage parity. Step 3 supplies
> explicit construction/restoration, stamped acquired generations and
> candidate carriage through the writer and adoption. Step 4 supplies the
> measured native/logical write split with complete final owning-value and
> program checks retained. Consume those mechanisms; reopen none of them.
>
> **Own** src/seon/instrument.clj, src/seon/test/arm.clj, the contract
> installation seam in src/seon/sci/eval.clj, the existing instrument and
> schema regressions, and the narrow caller/acquisition changes in the
> per-file table. The landing note is
> docs/prds/steward-platform/research/bridge-step5-instrument-2026-09-21.md.
> Include every discovered caller of a changed or retired arity in this
> same slice. Refresh HEAD anchors after steps 2–4. Obtain held-path release
> before editing; never operate, resume, message or repair a foreign lane.
>
> **First bounded item: measure the landed baseline.** Use the canonical
> populated fixture and the actual gate arming owner, one foreground JVM
> under the repository slot. Record construction separately from arming,
> including provider calls keyed by declaration identity, out-of-provider
> raw contract compilation, registry copies, actual wrapper builds and
> installed/armable sets. Capture cold arming, unchanged re-arm and changed
> dependency re-arm, three trials each. Save exact source/input identities
> and reproducible measurement code before removing the old path.
>
> Resolve the function's compiled Schema with mr/schema on the acquired
> registry using its qualified symbol. Feed that exact object to the
> existing compiled-wrapper and m/-instrument. Derive arity information
> from it. No raw-contract m/schema, recursive dereference, var-registry
> fallback, second predicate binder, global function-schema lookup or
> locally compiled replacement on a miss. Missing or non-function entries
> positively refuse registration with function, expected contract and
> generation evidence before that function's root is replaced.
>
> Convert contract-definitions to consume the retained function dependency
> graph and canonical definitions. Do not recompile a contract to discover
> its references. Preserve current-wrapper?'s contract, dependency, callable
> and acquired-policy identity semantics. Do not key freshness solely by
> the whole population digest: unrelated declarations must preserve wrapper
> identity. Keep projection-local wrapper memoization; remove the duplicate
> plain base/refusal validator cache entries because Malli owns those.
>
> Convert both host and real SCI callers before deleting the raw compilation
> and binder. A supplied authored EDN string cannot remain an alternative
> compiler in wrap-interpreted. SCI selects the installed symbol's retained
> contract from its committed generation. Host/test acquisition must include
> every selected loaded contract before arming, private contracts included.
> Missing test/helper contracts are acquisition work, never justification
> for a hidden compile in apply! or a silently smaller armed set.
>
> Preserve the one existing wrapper, original-root metadata, actual-arity
> input/output/guard enforcement, refusal-before-body, error-facet permission,
> evidence and explanation grammar, and panic/record disposition. A Malli
> report callback returning a map does not prevent execution: retain the
> private throw/catch control flow and refusal-result validation. A body
> returning only an undeclared facet must refuse even through :map; a value
> matching a complete declared facet plus another facet must pass. Never
> turn an arbitrary body/reporter exception into a contract refusal.
>
> Keep state/restore! and the canonical preservation fixture. After a throw,
> restore the exact entering callable roots and Malli tooling registry;
> leave definitions replaced by reload as the loader left them. Restoration
> neither recompiles contracts nor reinstalls closures against old classes.
> Policy is acquired once at arming, never per wrapper or per call.
>
> Prove every regression below with complete canonical population, explicit
> acquired custody, real SCI and armed contracts. Count actual work, not
> calls to names deleted by the refactor. Exercise the first invocation of
> delayed host paths: zero apply! compiles with later hidden compiles fails.
> Use extra-schema only for synthetic declarations, program-fn-row and
> transacted! for fixture facts, and preservation scopes for global roots.
>
> Measure the same workloads after, with all three trials and medians,
> population and selection identities, exact provider and raw-compile
> counts, wrapper reuse and refusal values. No timing assertion in CI,
> no raised bound, and no claim that a cached no-op measured fresh arming.
> Save the repeatable probe in the existing test owner and results in the
> landing note. Canonical growth changes N; no 1,430-member roster.
>
> Before the coherent path-limited commit, require all changed production
> namespaces in one foreground JVM. Iterate with bin/test-fast --paths
> <all owned changed paths> -- seon.instrument-test seon.schema-test
> seon.test-support-test seon.test-runner-test, plus the SCI and caller
> suites below. Report executed, unchanged and unavailable separately
> with durable program/input/basis evidence. Fresh count/behavior witnesses
> must execute; reused green members are not fresh measurements.
>
> One foreground JVM at a time, no overlapping/background probes or tests,
> no worktrees, no cold bin/test, --all, --full, nested cold gate or baseline
> publication. Foreign breakage uses the fast HEAD-plus-owned-paths snapshot;
> continue independent work and name the exact boundary. If admission
> refuses, retain the unproved retirement uncommitted and report the path.
> Never edit foreign hunks to get a load. The orchestrator owns cold
> --paths/--platform and any missing-base preparation.
>
> Verify host and SCI behavior after coordinated live adoption, naming its
> source/population identity and whether it was hot reload, in-place adoption
> or an owned scratch fork. No default stop/restart/refork, hook re-enable,
> paid provider call or schema reset in this slice. Report MCP unavailability
> immediately; no hand-rolled prepl workaround. Clean only owned disposable
> roots after their recorded processes exit.
>
> **No second instrumentation system, global compiled-contract cache,
> malli.instrument/instrument! replacement, new durable contract mirror,
> compiler fork, predicate snapshot semantics, facet-rule redesign, weaker
> contracts, widened success union, writer diet, stamp change, generation
> reconstruction at calls, production regex or extra rendering clip point.**
> Do not absorb the remaining error-family, publication, test-selection or
> SCI acquisition work. Never delete enforcement to meet a LOC estimate.
>
> **Deliver:** one coherent path-limited caller/retirement commit; every
> changed path; refreshed caller and surviving-authority ledger; baseline
> and after times/counts with raw trials; canonical fast tallies; exact
> private/re-arm/restoration/facet/SCI evidence; gross deletions/additions;
> foreign, cold/platform and live-proof boundaries in the landing note.
> Estimate **2–3 lane-days**, excluding coordination and gate queues.
> Stop when committed. At a genuine broader design decision, stop before
> production edits with exactly three priced options, simplest viable
> constraint first/recommended, each stating guarantee, cost and sacrifice.

## Dependency ledger — retained objects, not a replacement instrumenter

Census HEAD: **b88ed9843** (committed source census).
Malli gitlink: **606083c5c5b388e84d169c7080af33ed3ec242ae**.
All source anchors below refer to that committed snapshot; concurrent
working bytes are not asserted to be a landed baseline.

The requested `reference-code/malli/src/malli/instrument.cljc` does not
exist at this pin. The actual owner is **instrument.clj**, also explicitly
corrected by bridge PRD §2.5. Cite and read that file, not a guessed suffix.

| Mechanism | Source and consequence |
|---|---|
| Var selection/root replacement | `reference-code/malli/src/malli/instrument.clj:18–40,152–162`: defaults to global m/function-schemas unless :data supplied; alters roots and retains originals. This does not supply cluster custody, Seon refusal policy or reload-safe restoration. |
| Metadata collection | `reference-code/malli/src/malli/instrument.clj:42–55,136–150`: -schema reads metadata/arglist schemas; CLJ collection walks ns-publics and registers them. Preserve Seon's ns-interns collection rather than replacing it with this narrower default. Primitive exclusion is at `:15–16,24–25`. |
| Global registration and => | `reference-code/malli/src/malli/core.cljc:3060–3116`: function-schemas is a global atom; function-schema calls schema; -register-function-schema! stores its result; => expands registration. It is tooling state, not an independent cluster's compiled population. Passing raw forms here to manufacture the arming data would compile again. |
| Compiled object identity | `reference-code/malli/src/malli/core.cljc:2550–2573`: schema returns a Schema argument unchanged; a named lookup can construct an outer pointer. Use mr/schema to obtain the retained symbol-keyed object, then give it directly to -instrument. |
| Instrumentation | `reference-code/malli/src/malli/core.cljc:3118–3143`: -instrument normalizes scope/report, calls schema on its supplied object and delegates to -instrument-f. This is not a new named-declaration compilation when the input already is a Schema. |
| Refusal control flow / actual arity | `reference-code/malli/src/malli/core.cljc:2202–2224,2276–2293`: validators report then normal control proceeds to the body unless report exits; multi-arity selects its child wrapper. Preserve throwing report and Seon's outer boundary disposition; guard sees arguments and returned value. Validator closure creation is distinct from named-schema compilation. |
| Registry retention | `reference-code/malli/src/malli/registry.cljc:17–22,81–104`: serial provider realization, fast immutable table and mr/schema lookup. Composite enumeration merges (`:54–59`); var-registry dereferences Vars (`:67–71`). Neither is needed to reinterpret an already retained contract. |
| Native caches and captured scope | `reference-code/malli/src/malli/core.cljc:345–361,1952–1977,2626–2657`: retained nodes own validator/explainer products and references retain their scope. Do not replace a generation by changing only lookup precedence. |
| First-party compile-once owner | `src/seon/schema.clj:419–466`: merges fixed schema/contract definitions, invokes m/function-schema for qualified-symbol provider identities and seals. `:1922–1926` retains those exact objects; `:2007–2013` derives function dependencies from them. |
| Existing product holder | `src/seon/schema.clj:315–379`: additional arity descriptors and projection-cache-value. Keep wrapper products here; plain validators remain on Malli objects. `:2378–2411` already admits incremental contracts using the retained compiled object. |

The step-1 research is evolving dated evidence. The assignment's **1,430
contracts as providers** is a historical population reference, not an
acceptance count: the current landing note's compiler table reports **1,448
fixture contracts / 3,241 schemas**, plus four synthetic schemas and one
contract, giving **4,694 providers**, maximum one each, and **4,842 sealed
entries**. Its earlier accepted checkpoint reports 4,678 providers. Do not
rewrite those observations into a single simultaneous run. The invariant
is exact key-set coverage and at most one provider per admitted identity,
then zero provider work during arming. The retained function objects are
already present; step 5 must consume them.

## Acquisition and caller conversion contract

Separate acquisition from arming in the actual callers, not merely in the
benchmark timer. An already acquired production generation has canonical
function forms, compiled contracts and dependency indexes. Arming selects
and wraps these without changing the generation. Construction/restoration
may compile each admitted declaration once; a changed declaration and its
reverse dependent closure are new generation work. Unchanged roots are
reused. No second compilation of the same admitted contract is permitted
for diagnostics, reference discovery, JVM tooling registration or SCI.

The current raw fallback is substantive: `compiled-wrapper:742–755`
uses authored metadata when the function map lacks a row. Moreover,
`test.arm/packaged-test-projection:35–46` currently constructs schemas only,
then loads tests later (`:242–257`). Simply deleting the fallback would
leave test/private/helper functions without contracts. Convert that order
at acquisition: load selected namespaces, collect their complete declarations,
canonicalize through the existing schema owner and acquire the complete
host/test generation once before calling apply!. Use the landed step-3
construction boundary and step-1 constructor/delta; never a test-only
compiler or one whole population construction per selected function.

Keep the distinction between an acquired host tooling program and the
cluster's stamped durable program. Loaded test/helper contracts enter the
explicit host/test construction world; they do not mutate a cluster's
stamp or overwrite its function rows. Production acquisition supplies its
admitted contracts. A host with no cluster arguments retains the explicitly
acquired host generation already required by step 3. Invocation with supplied
custody uses that generation's retained contract; no package or authored
metadata substitution when it is missing. Audit the default host path as
well as the supplied path. If steps 1–4 already converted an acquisition
owner, verify it rather than adding a second acquisition.

Use the existing request owner for any necessary carried host value and
give it a complete contract; convert every caller with it. Collection
derives loaded declarations; it never validates by recompiling them.
Metadata changes in tests must be admitted into the next explicit host
generation before re-arming. Source/adoption tests must prove the new
loaded definition and admitted contract agree, not accept an uncommitted
metadata form as a runtime alternative contract.

For `wrap-interpreted`, remove the spec-edn argument and its parsing after
converting all callers: the remaining inputs are function symbol, acquired
projection, mode, caps, callable and the existing optional arm-request.
The committed function row remains the durable authored source; the caller
selects its retained compiled contract by the same symbol. Keep both
existing policy-supply use cases, without an old-signature compatibility
arity. Synthetic wrapper tests first admit their contract into a generation.

`contract-definitions` must use the existing function-dependencies and
schema-dependencies indexes to derive the referenced canonical definitions;
the raw `schema/direct-references` path at `schema.clj:781–799` recompiles
its argument. Preserve local-ref shadowing and canonical reach from the
step-1 graph. Missing dependency evidence is a registration refusal, never
an empty dependency set that lets a stale wrapper look current.

Keep the existing wrapper cache keyed by generation-local custody, function,
original callable and policy (including caps/recorder). Do not use just the
symbol, or reuse a wrapper for another original/policy. Unrelated changes
preserve the host Var wrapper through current-wrapper?'s semantic comparison;
two independently constructed equivalent generations need not share all
inner wrapper objects. A whole-generation stamp mismatch alone must not
re-root every unrelated function.

## Per-file implementation instructions — committed HEAD anchors

| Path / current owner | Exact change and retained boundary |
|---|---|
| `src/seon/instrument.clj:736` compiled-wrapper | Resolve/check the retained function Schema; pass it directly to m/-instrument. Remove `:742–755` raw fallback/binding/options compilation. Keep arities, permissions, marker, refusal validation, original call and disposition. Replace duplicate plain validator entries at `:758–763` with retained-root acquisitions. |
| Same, `:420` predicate-callable, `:428` bind-contract-predicates | Retire after all wrapper/test callers convert. Step 1's predicate binding owns this work, including error/fn and live Vars. Remove clojure.walk require only if its last use is gone. Do not delete the schema owner's binder. |
| Same, `:823` contract-definitions; `:846` current-wrapper?; `:862` arm-var! | Derive dependencies from the acquired graph; remove var-registry/recompiling traversal. Preserve original-root and contract/dependency/policy evidence, no double wrapping, unrelated identity, supplied versus acquired host custody and delayed-path behavior. The older AGENTS ~593 reference now resolves to these owners. |
| Same, `:462` wrap-interpreted; `:900` collect-contracts!; `:929` apply! | Remove raw SCI spec input with callers. Keep ns-interns, bound/nonprimitive eligibility, positive failures, once-acquired policy and counts. Complete acquisition precedes apply!; delete its schemas-only bootstrap construction at `:997–999` after callers supply the landed host world. The older private-arming ~687 anchor is now collect-contracts! at 900. |
| Same, `:1035` function-schemas*, `:1040` state, `:1056` replaced-definitions, `:1082` restore! | Preserve exact snapshot/restore and reload exclusion. No =>/collect! registration to rebuild state. Correct the stale comment claiming arming reads the global registry; retain the tooling state itself. |
| `src/seon/test/arm.clj:35,141,167,242` | Complete one explicit host/test acquisition with selected loaded contracts, then pass it to the same apply! as production. Keep full program loading, source-derived armable set coverage and positive missing-subject checks. No hardcoded contract count or public-only collection. |
| `src/seon/sci/eval.clj:674–690` install-function-contract! | Convert wrap-interpreted call to symbol plus acquired generation; preserve committed-row selection, real SCI binding, recorder and policy. No row reparse/rebuild or per-function policy acquisition newly introduced by this cut. Consume landed step-3/1a fixes. |
| `src/seon/schema.clj:419,1851,2007,2323,2378,3151` | Conditional narrow acquisition/compiled-access support only if needed after step 3. Reuse constructor, materialization and function-contract delta, retained graph and roots. No second compiled table. Complete contracts on changed functions; no broad compiler redesign. |
| `src/seon/cluster.clj:2588–2615`; `script/seon/fresh_operator.clj:1923–1949` instrument-form | Convert supplied arming inputs only where required after step 3. Include delayed ns-resolve/generated forms in census. Preserve adoption ordering: JVM arming precedes SCI acquisition and successful adoption marker. No publisher/operator lifecycle redesign. |
| `src/seon/test/runner.clj:1789,1831`; `src/seon/test/fast.clj:52` | Verify shared test.arm delegation and retained decision/generation on subsequent commands; modify only actual changed callers. No alternate fast/cold arming path, selection policy or nested process. |
| `test/seon/instrument_test.clj:53,186,320,343,471,814,873,885,920,967,1140,1203,1231,1293` | Extend existing classes below. Replace local manual restoration at 53 with the canonical preservation helper if still present; it currently restores captured roots without reload exclusion. Convert synthetic raw-wrapper inputs and benchmarks to explicit acquired contracts. Keep original guard/dial/evidence assertions. |
| `test/seon/schema_test.clj:86,1263–1274`; `test/seon/test_support_test.clj:296–316`; `test/seon/test_runner_test.clj:294,325,353,1206` | Extend compile/provider regression; convert retired private-binder assertion to retained identity/missing-entry proof. Preserve canonical throw restoration and actual gate-owner parity. Do not replace behavior with a mocked apply!. |
| `test/seon/test_support.clj:1099–1125` preservation fixture | Prefer unchanged; use its existing instrument/state and restore! finally. Only narrow acquisition helper adjustment if required for canonical complete host contracts. Never add a competing preservation fixture. |
| `resources/seon/schemas/seon.instrument.edn`; existing schema projection/request contracts | Conditional in-memory contracts for changed inputs only. No stored schema changes or reset. Refresh registry discovery first; resource and loaded consumer land in one publication after path release. |
| `AGENTS.md` §§1–5; applicable instrumentation paragraphs in repository skills | Update only claims invalidated by this implementation, with refreshed file:line. Keep historical notes dated; working edge and issue index are orchestrator-owned. |

### Dated caller census and coherent retirement

At this HEAD, production `wrap-interpreted` calls are its own forwarding
arity at `instrument.clj:479` and SCI installation at `sci/eval.clj:684`.
`compiled-wrapper` has four internal acquisition sites (`:512,879,888,1003`)
plus tests. Refresh this search, including quotes, aliases and generated
forms, before changing signatures:

```bash
rg -n 'wrap-interpreted|compiled-wrapper|predicate-callable|bind-contract-predicates|arm-var!|collect-contracts!' src test script
rg -n 'instrument/apply!|seon.instrument|arm-contracts!|initialize-contracts!' src/seon/test src/seon/cluster.clj script/seon/fresh_operator.clj test
```

Direct changed-signature/private-owner test callers are in
`test/seon/instrument_test.clj`, `test/seon/schema_test.clj`,
`test/seon/error/refusal_test.clj:68`, `test/seon/refusal_grammar_test.clj:97`,
`test/seon/render/value_test.clj:926`, and
`test/seon/render_coverage_test.clj:470`. These conversions are owned after
release; do not retire a callable while these still require it.

Acquisition/request caller verification also includes
`test/seon/adoption_contract_freshness_test.clj:65`,
`test/seon/registry_isolation_test.clj:81,87`,
`test/seon/cluster/cohost_boot_test.clj:86`, `test/seon/context_test.clj:22`,
`test/seon/db_test.clj:467`, `test/seon/error_test.clj:477`,
`test/seon/sci/eval_instrumentation_test.clj:46`,
`test/seon/sci/eval_test.clj:331,424,1652`, and the operator/source
instrumentation tests. They are conditional edits, not permission to sweep
unrelated contracts. Convert only where the new acquisition contract
requires it. Update all actual callers before deleting old compilation;
the current census is evidence, not a future maintained roster.

## Canonical regressions — behavior and counts together

All tests use the complete canonical fixture and its actual acquired
generation. A nonempty population and positive selected/installed subjects
are prerequisites. Generation construction and first-use behavior are
observed independently; changing a test to a schemas-only map is forbidden.

| Class / recurring owner | Required proof |
|---|---|
| Compile-once, `seon.schema-test/retained-generation-recompiles-dependent-roots` (`:86`) and instrument test | Capture provider counts from construction, prove every canonical schema/function entry is a Schema, and record identical retained objects passed to m/-instrument. Arming N>0 functions changes neither provider counts nor retained contracts; out-of-provider named-contract compilation is zero. Force fresh wrapper products and inspect first invocation under supplied and host custody. Missing/wrong-kind contract produces an actual registration refusal and leaves the target original unchanged. |
| Gate/cluster parity, instrument `the-selection-is-declared-vars-with-schemas-and-nothing-else` (`:1140`) and test-runner suite | Run actual test.arm/arm-contracts! and actual cluster arming seam with equivalent acquired inputs, within preservation scopes. Compare program armable identity sets with installed program identities, not only counts; account separately for selected test/tooling extras. Assert nonempty program, no missing contracted Var, exact retained-schema identity and identical valid/invalid behavior. Keep missing program-root/no-namespace detection. No mocked apply! or hand-selected production namespace list. |
| Private arming, same regression | Remove the real private-integer-boundary wrapper, prove :private metadata and nonmembership, re-arm through gate owner and prove membership plus good input, bad input and bad output behavior. Invalid input body counter stays zero; positive valid call proves the subject exists. |
| Re-arm, instrument `authored-contract-changes-rearm-without-reloading` (`:885`), `referenced-contract-changes-rearm-only-dependent-wrappers` (`:920`), adoption-contract-freshness | Admit changed function contract and, separately, a transitive alias/leaf change through canonical generation owners. Keep original callable, observe changed retained contract/behavior and changed dependent wrapper; unrelated wrapper remains identical. Repeat no-op arm: all relevant wrappers identical. No named compilation during either arm; changed-closure provider work occurred once in prior acquisition. Old generation remains usable; local refs keep their shadowed meaning. |
| Restoration through throw, `seon.test-support-test/an-instrumentation-test-restores-the-entering-contracts-on-failure` (`:298`) | Enter with real wrappers, remove one, mutate scoped tooling state and throw. After canonical finally, compare every original root by identical? and full Malli function-schema state; no compile or accidental re-arm. Extend instrument `restoring-instrumentation-state-never-reinstalls-a-replaced-definition` (`:1293`) to retain reload exclusion and truthful returned replaced set. Do not reload a real pooled-worker namespace. |
| Undeclared facet, instrument host (`:186`) and real SCI (`:343`) | Both panic and record: broad ordinary output validation accepts a complete error whose only facet is undeclared; independent facet check must refuse, naming function, actual arity, declared/actual evidence and original result. Verify refusal-result shape and recorded fault where record mode applies. Also prove declared complete facet passes, declared+extra facet passes, base-only through broad success refuses, incomplete declared facet refuses, and arity two's permission does not authorize arity one. No fake Malli explanation when its ordinary validator accepted. |
| Input/arity/guard and SCI parity, instrument (`:280,320,343,471`), SCI eval-instrumentation | Wrong input and wrong arity never execute the body in either dial. Valid invocation executes once. Wrong output/guard refuse with exact input/result evidence, matching host/real SCI semantics. Wrapper's own refusal bypasses the body's output contract without recursion; arbitrary body or recorder failures retain their own causes. |
| Isolation and policy, registry-isolation plus instrument acquisition (`:814`) | Two acquired generations disagree on the same contract and keep independent behavior. A shared host root dispatches by supplied custody without rewriting global m/function-schemas. Changed policy/caps/recorder or original callable cannot reuse old inner wrapper; unchanged acquired policy does. Supplied complete policy causes zero defaults acquisition; omitted policy is acquired once at its existing boundary. Live predicate Var replacement retains step-1 semantics. |
| Native explanation/evidence, existing instrument/refusal suites | Preserve :in and :path unchanged; assert offending value, violated member and value location, not literal schema-path bytes. Keep caller/arglist, registration, guard, malformed refusal and reporter failure evidence. No new clipping and no evidence-bound increase. |

No call to every arbitrary production body is required for the N-function
measurement: wrapper construction covers the full derived set. First-call
and refusal witnesses use declared safe fixture functions through the real
host/SCI seams; explicitly force any delayed wrapper acquisition for the
full selected set without executing effectful bodies. Count attempted and
successful builds separately so a refused acquisition is not zero work.

## Before/after arming measurement contract

**No new arming time or runtime compile count was measured by this design
lane.** No JVM is authorized here. The step-1 provider measurements above
prove retained population construction in that historical run, not step-5
arming speed. The dissolution review's 34.120/0.1515 ms value-validation
numbers and the writer's timings are different workloads. Do not use them
as an arming baseline or promise a speedup from them.

Implementation records the landed step-4 baseline first, then the candidate,
using one foreground, bounded, slot-held JVM at a time. Keep the same
canonical population, actual selected loaded Vars, policy and source inputs
for the comparison. Retain baseline function values locally within the
canonical restoration scope, or run sequential bounded baseline/candidate
requests; never use worktrees or leave old production definitions installed.
Record any JIT/cache/warmup asymmetry. Measurement code lives in the existing
instrument/schema test owners; do not leave the only reproducer in tmp/.

For each phase publish three raw trials and the median of each column:

| Phase | Separate measurements and expected after invariant |
|---|---|
| Canonical generation acquisition | Wall ms; schema count S, contract count C; providers keyed by identity; raw contract compilations outside providers; fast-registry constructions/copied entries. Canonical realization includes contracts and reuses unaffected roots; excluded from arming time but never hidden from total time. |
| Fresh arming N functions | Collection, dependency/freshness inspection, inner-wrapper construction, Var installation and total wall ms. Record N, selected symbol-set digest, pending count, actual wrapper builds and installed set. **Zero named-schema/contract provider calls and zero out-of-provider contract compilations.** |
| First use / delayed host path | Force acquired wrapper construction and safe representative invocations in both custody paths, with phase time and compilation counters still active. **Zero named compilations**, no fallback registry construction. No absence-of-call success. |
| Unchanged re-arm | Same roots/policy/world; time and identity comparisons. Zero newly required wrapper builds and named compilations; identical host wrappers. Report selection time honestly, even when it dominates. |
| Changed contract/dependency | Time candidate acquisition separately; record changed/dependent provider identities and unaffected object reuse. Then time arming; zero named compilations there, changed dependent behavior, unrelated wrapper identity preserved. |
| Real SCI installation | Existing canonical real-SCI setup/install path; time wrapper portion separately from context construction/evaluation. Retained symbol-contract identity, zero second contract compilation. Record sample size explicitly rather than claiming it equals N host Vars. |

Count providers by wrapping the actual lazy-registry provider during
generation construction, as `schema_test.clj:100–116` does. Leave the
counter active through later phases. Also observe raw non-Schema function
compilation in m/schema/m/function-schema outside that provider: a second
raw root compile can reuse named children without calling a provider, so
provider-zero alone is insufficient. Classify actual inputs and call phase,
not stack-name guesses; use scoped counters delegating to real originals.
Observe schema/direct-references or its landed replacement and registry
allocation too. Calls to m/schema with existing Schema arguments, ordinary
validator closure construction, and -instrument's multi-arity recursion are
not named-declaration compilation; report API counts separately.

Assert counters are connected with positive construction controls and
positive wrapper-build observations. Cold means fresh selected wrapper
products, not the launcher's already-warm wrapper cache; derive a fresh
canonical generation outside the timer where necessary. Always report
construction+arming total alongside the split so work moved earlier is
visible. A smaller selected set invalidates the comparison. No timing
threshold in CI: compilation counts, set coverage, identity and behavior
are deterministic acceptance conditions; timing is evidence.

Record source commit and exact relevant SHA-256s, Malli pin, population
stamp/input digest, policy, N/S/C, request identity, Java/Clojure version,
host contention and warmup. Preserve actual refusals, not only exception
messages. Report reused greens with their program/input/tested-basis
confidence; unrecorded execution is not a durable verdict. If measurement
cannot execute at a foreign boundary, report unavailable and retain the
old path until its removal has the required proof.

## Measured deletion budget and estimate

Physical inclusive spans at census HEAD, including contracts/comments and
separating blank lines. A review surface is not an instruction to delete
all its behavior. These rows are nonoverlapping:

| Owner span | LOC | Disposition |
|---|---:|---|
| `src/seon/instrument.clj:420–459` predicate-callable + bind-contract-predicates | 40 | Delete duplicate 8+32-line binding owners after caller conversion; retained schema construction already binds predicates. |
| `:736–822` compiled-wrapper | 87 | Keep wrapper. Raw acquisition at `:742–755` is 14 mixed lines to replace with retained lookup/refusal; `:758–763` is 6 lines of redundant plain validator caching to simplify. Remaining policy is not deletion credit. |
| `:823–845` contract-definitions | 23 | Replace raw re-compilation/composite scope with existing graph reads; preserve canonical dependency evidence. |
| `:846–861` current-wrapper? | 16 | Retain semantic reuse; adapt only contract access/evidence as needed. |
| `:862–899` arm-var! | 38 | Retain root/original/reload behavior; remove routes that create a second compiled contract. |
| **Core nonoverlapping review surface** | **204** | 40 + 87 + 23 + 16 + 38. Most of it stays. |

The **60-line immediate removal/replacement surface** is the sum of 40 binding
lines, 14 mixed raw-acquisition lines and 6 duplicate-cache lines, already inside
the 204 above, not additional savings. Removing the 3-line schemas-only
bootstrap at `:997–999` is caller conversion inherited in part from step 3;
do not double-credit its work. SCI parsing/signature and caller reductions
are measured at landing. Preserve collect-contracts! `:900–911` (12 LOC)
and restore! `:1082–1117` (36 LOC); neither is a deletion target.

Committed files measured: instrument **1,120 lines**, SHA-256
`bdd00c6404b74f6a1dcdcc3376bd59407605a80d26f09e98e6a694079396a08a`;
test.arm **257 lines**, SHA-256
`f3a5166d34d7570d5d4f674d33d226f904a3380a61e6bee9077f7b5216ecf96f`.
Reproduce spans with `git show <census>:<path>` and physical line numbers;
refresh after prerequisite landings and report `git diff --numstat` for
the actual slice. Gross removed/added code, moved acquisition and test
additions are separate columns.

PRD §4 estimated **40–100 gross deletions / 30–80 additions, 1–2 lane-days,
3–5 files**. Keep the LOC numbers as provisional production ranges. The
present caller census includes SCI, host/test acquisition and synthetic
wrapper tests across more than five files; price this launch at **2–3
lane-days**: roughly half a day for baseline/caller conversion grounding,
one day for retained access and acquisition/callers, half to one and a half
days for counted regressions, measurement and live proof. Coordination,
foreign repairs and orchestrator cold gates are excluded. A completed
step-3 caller conversion may reduce this; no broad hardening is budgeted.

## Gates, live proof and held paths

Refresh commands to the actual changed production owners and all owned
paths. These are future implementation commands, not design-lane runs:

```bash
clojure -M -e "(require 'seon.instrument 'seon.schema 'seon.test.arm 'seon.sci.eval)"
bin/test-fast --paths <all owned changed paths> -- seon.instrument-test seon.schema-test seon.test-support-test seon.test-runner-test
bin/test-fast --paths <all owned changed paths> -- seon.sci.eval-instrumentation-test seon.registry-isolation-test seon.adoption-contract-freshness-test seon.error.refusal-test seon.refusal-grammar-test
```

Add render.value/render-coverage and other actual converted caller suites
from the census; add cluster/cohost/operator coverage when those seams
change. Do not run the subprocess test-runner integration namespace or
launch a child cold gate from a lane. Name any process-only proof handed
to the orchestrator. Cold `bin/test --paths <same slice> -- <affected
namespaces>` and `bin/test --platform` are orchestrator obligations.
No zero-execution measurement is accepted as a fresh compile-count proof.

After coordinated adoption, observe a real loaded private contract and a
safe host invalid invocation, then a real SCI-installed fixture function
with valid and invalid inputs and an undeclared error return. Use disposable
fixture facts, canonical recorder in record mode and explicit cluster/root
custody. Record body counts, complete returned/thrown refusal and stored
fault evidence as applicable. Verify same-generation re-arm preserves a
chosen unrelated real wrapper. Compare source/population identities; do
not infer adoption from a file edit or from tests alone. Page observation
is needed only if a surfaced error/page is claimed as proof. No paid run.

At the design census, `instrument.clj` had foreign uncommitted additions
of the row-acquisition error facet at lines 556 and 594. Other overlapping
dirty paths included schema, cluster, SCI eval, error/refusal, schema
resources and test_support. Those edits are preserved, not prerequisites
silently included in HEAD measurements. Step 2 owns compiled navigation;
step 3 then owns acquired custody; step 4 owns writer checks; 1a owns shared
error/SCI facet conversion; publication/test work may overlap acquisition.
Check actual status and landing notes at launch and ask the orchestrator
to release overlaps. Never message or repair their sessions yourself.

No reset is expected: step 5 consumes the completed step-3 stamp/reset and
landed error declarations. An unexpected need for stored schema changes or
a new global authority is a genuine design boundary, not implicit scope.
Do independent census/probe work while paths are held; never commit a
retirement whose callers cannot load.

## Design-lane verification boundary

Read the bridge PRD, dissolution review, step-1 landing and step-3/step-4
launch specs **end to end**, and instrument.clj and test/arm.clj end to end
at the cited HEAD; inspected instrument's concurrent diff separately.
Read supplied AGENTS §§1–5 and lane rules 11–16, the roadmap entry/current
working-edge checkpoint, data-oriented-clojure and clojure-testing skills,
the cited error-conversion rule, dependency owners and caller/test seams.
The work-item date is the requested 2026-09-21, within the 2026-09-20
session. Older AGENTS line anchors are resolved by owner name above.

Only this document is authored. No source/test/resource edits, JVM launches,
MCP evaluations, cluster operations, gates, worktrees or foreign lane
operations. The explicit no-JVM/no-worktree restriction governs over the
generic fallback paragraph. No runtime health or fresh timing is inferred
from static inspection. Source hashes, spans/arithmetic, caller/authority
links and whitespace are this design's verification. Canonical execution,
arming times/counts, adopted live behavior and cold/platform proof remain
the named implementation/orchestrator obligations. Commit only this
document path and stop.
