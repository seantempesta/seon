---
type: plan
status: launch specifications; wait for bridge step 5 and held-path release
created: 2026-09-21
tags: [plan, contracts, instrumentation, malli, namespace-agents, test, errors]
---

# Wave 2 — contract enforcement and the first bounded campaigns

**Guarantee:** every eligible authored function is accounted for, including
private functions. Missing contracts become positive findings on current
function declarations. Publication cannot add new uncontracted identities or
remove an existing contract after the accepted inventory baseline. The gate reports the debt and distinguishes it
from failure to install a declared wrapper. The first campaigns prove this
mechanism on leaf functions and database consumers; wave 5 uses the same facts
to give a namespace agent one function to repair.

These are verbatim launch assignments. Include the common contract, priority
table, the selected lane's complete section, and the wave-5 handoff when
launching. They follow the shape and common launch contract of
[wave 1](wave-1-families-on-the-bridge-spec-2026-09-21.md). They are bounded
implementation assignments, not permission to start a repository-wide sweep.

## Read authority, baseline and prerequisites

Read end to end for this design: the wave-1 specification; the
[coverage and error-accuracy investigation](../research/instrumentation-coverage-and-error-accuracy-2026-09-19.md);
[bridge step 5](bridge-step5-instrument-spec-2026-09-21.md); the
[wave-3a task specification](wave-3a-task-family-spec-2026-09-21.md);
`src/seon/instrument.clj`, `src/seon/fn.clj`, and `src/seon/test/arm.clj`.
Also read the requested authority sections end to end:
[namespace plan §2 wave 2 and §§3/6/8](namespace-agents-plan-2026-09-19.md),
[program-facts PRD §1j](program-facts-are-the-runtime-prd-2026-09-17.md),
[error-conversion PRD §§1–2](error-conversion-prd-2026-09-20.md), and
[AGENTS](../../../../AGENTS.md) §§2.4/3/5 and lane rules 11–16. The complete
error-conversion PRD was read. The current working edge, finding declarations,
relevant tests, dependency sources and raw gate evidence were inspected.

The requested step-5 path under `research/` does not exist. Its maintained
specification is the **plan/** link above. Use it, not a new duplicate.

All implementation coordinates below refer to committed HEAD
**`857d6731302f9dd40cf39320d8fc7af7f7317f27`**, inspected on 2026-09-20 local
time while the working edge carries 2026-09-21 checkpoints. They are source
coordinates, not assertions about the installed development program. Refresh
coordinates against the actual prerequisite commits at launch. Paths starting
`schemas/` abbreviate `resources/seon/schemas/`.

**2a lands after bridge step 5**, including its actual step-1–4 prerequisites
and cold proof. Step 5 changes how wrappers consume retained compiled
contracts; designing against the present raw-form fallback would undo it.
2b and each 2c lane launch after 2a's positive-finding/parity/ratchet slice has
landed and its orchestrator proof is recorded. They may overlap only where
paths are released and the three-editing-lane ceiling permits it. Wave 1's
error producer repairs must land before interpreting a campaign's downstream
refusal as evidence that its own output contract is wrong.

§1j's 2026-09-17 ruling that **every function carries a complete Malli
contract, private included**, remains binding. Its older suggestion that an
error need not be listed in a return contract is superseded by the later
namespace-plan D12/program-facts §1q and error-conversion rule 1.2: a domain
output explicitly names every complete facet it can return, including facets
it propagates from callees. A base-only error is not a complete domain result.

### Dated counts, not a launch roster

| Population | Dated evidence and consequence |
|---|---|
| 2026-09-19 source audit | 3,574 definitions; 1,237 contracted; 2,337 missing. Public 1,168/1,215; private 69/2,331. These are dated measurements, not constants for a checker. |
| Unchecked database readers | The earlier live program census found **352 private database readers** unchecked. The later source audit found **246 uncontracted direct source database readers**. Different populations, including indexed test helpers in the live census; do not subtract one from the other or claim they disagree. |
| W4 historical total | **297 missing contracts across seven namespaces**, not 297 in the three namespaces assigned here. The requested subset is operator.state 78/80 missing, sci.reader 44/46, print 64/83: **186 missing of 209 definitions**, 23 already contracted, zero actual direct database reads in that audit. |
| W1 requested subset | turn: 28 uncontracted direct database readers; render.transcript: 22; plan: 15, dated 2026-09-19. Re-derive membership at launch; source has moved. A current lexical inspection found only 16 obvious transcript candidates and is not an authoritative replacement census. |
| Wave 5 | The plan's **remaining ~1,800 functions** is an approximate scheduling statement. Derive the real remaining eligible population from accepted program facts after these campaigns. Never store 1,800 as a target or launch another human sweep to reach it. |

A quoted `(seon.db/pull …)` emitted by print's requery renderer is source
text for a later evaluation, not a database read by the renderer. Conversely,
operator.state's zero database reads does not make its process, filesystem
and lock operations pure. Preserve those effects and their bounds.

## Common launch contract — include with every assignment

> Work in /Users/sean/src/seon, branch steward-platform. Read this common
> contract, the priority table, your complete lane section and its binding
> sources before edits. Read AGENTS §§2.4/3/5 and lane rules 11–16, the
> current working edge and actual prerequisite landing notes. Apply the
> data-oriented-clojure, repl and clojure-testing skills; apply data-modeling
> and datahike before any declaration or writer change. Inspect dependencies
> at the supplied source coordinates before relying on their semantics.
>
> You are not alone in the codebase. Preserve unrelated edits and untracked
> files. Execute this assignment directly, without delegation. Own only the
> paths named in your section and its named landing note. Obtain held-path
> release from the orchestrator before editing; do not resume, message,
> operate or repair another lane's session. A foreign failure is a named
> verification boundary, never evidence against your slice. Continue
> independent owned work; use the supported HEAD-plus-owned-paths snapshot.
>
> Start implementation at the running default with a small read-only probe,
> subject to its current publication pause. Report missing/degraded MCP
> tools immediately and link the existing issue or record a new one in your
> implementation landing. Never stop, restart or refork default. Do not
> resume publication during a shared source cut. The orchestrator batches
> resets and re-enables the hook. A schema incompatibility is RESET NEEDED
> with the commit, not permission for a second ordinary development root.
>
> Use the acquired sealed generation and retained compiled contracts from
> bridge step 5. No wrapper-local compiler, registry, fallback schema,
> dynamic discovery, new arming path or process-global cache. Private Vars
> remain in ns-interns discovery. Preserve the existing reporter, recorder,
> panic/record dial, private boundary marker, per-arity permissions,
> referenced-contract rearming and unchanged wrapper identity.
>
> Declare real argument and result shapes, every arity and variadic bound,
> private helpers included. Discover existing schema keys before adding one.
> Domain outputs name exact returned error facets, including passthroughs.
> Never add :seon.error/value, :seon.error/base, a generic error predicate,
> :any, :some, :map or [:maybe X] simply to silence a failure. A genuinely
> polymorphic boundary needs the existing declared exemption and a reason
> proven from its callers. Maps stay open; nil is not stored. Symbols stay
> symbols. A returned declared facet with additional valid facets is legal.
>
> Iterate foreground, one JVM at a time, with
> `bin/test-fast --paths <every owned changed file> -- <named namespaces>`.
> Never run cold gates, --all, --full, slot/silence overrides or overlapping
> JVMs. No worktrees. At snapshot admission refusal name the exact omitted
> or held path; do not substitute foreign checkout bytes or repair its owner.
> Before each code commit, serially prove the owned namespaces load with
> `clojure -M -e "(require '<owned namespace> …)"` on the coherent owned
> snapshot; if it cannot be formed, report that boundary instead of claiming
> a load proof. The orchestrator owns cold --paths and --platform proof.
>
> Use with-database, explicit projection/environment, program-fn-row,
> transacted!, apply-config! and seed-cluster! where appropriate. Real
> database, real SCI, armed contracts; no mocked database, hand-rostered
> schema or weakened fixture to admit a malformed value. Tests that change
> instrumentation use test-support/preserving-instrumentation-state and
> release resources even after failure. Await exact facts with the declared
> backstop, never sleep or quiescence. A missing subject is unknown, not
> green. Preserve the positive controls proving execution and population.
>
> A resource and every loaded consumer of its changed meaning land in one
> coherent publication. Do not delete/rename a public Var before all callers
> are converted in the same loadable slice. Do not migrate disposable data.
> Record exact reset requirements for the orchestrator's batch.
>
> Commit coherent slices with `git commit --only -- <explicit owned paths>`;
> never broad staging, reset, restore or checkout of shared files. The final
> landing note records exact commands, executed/unchanged/failed counts,
> findings before/after, actual gate input/basis, changed paths, remaining
> unsupported declarations, live proof and cold proof still owed. Name
> whether a live probe exercised a hot-reloaded Var, in-place development
> adoption or a fresh fork. Browser rendering requires its own observation.
> Stop after landing or at a genuine new design decision with exactly three
> priced options, simplest viable constraint first and recommended. Never
> quietly enlarge this assignment into another owner or campaign.

## First work in every campaign — the six recorded output failures

Read both raw files before selecting work:
`tmp/orchestrator/gate-step1-2026-09-21.log` and
`tmp/orchestrator/gate-step1-reds.txt`. The table totals **95 ERROR entries
at `instrument.clj:768`**. It is not a count of unique functions, and not
proof that all 95 were the special undeclared-facet check: line 768 is the
wrapper's refusal throw. Both malformed success values and incomplete error
values reach it. The six functions below are the first triage list for 2b
and **each** 2c launch, before its bulk contract work.

| Priority function, committed HEAD coordinate | Raw log coordinate / observed failure | Assigned action |
|---|---|---|
| **`seon.db/transact-call`, `src/seon/db.clj:4263`** | `:4182`, missing `:db-before` under the former transaction-report output | Upstream db/error-family owner first. HEAD already includes a widened exact database result from the 1a repair; re-probe the actual landing, do not repeat an obsolete repair. |
| **`seon.problems/problems`, `src/seon/problems.clj:371`** | `:6835`, error data consumed as a receipt row at `[:seon.problems/errored-receipts 0 :seon.cluster.eval/id]` | Upstream problems/error-family owner first. Trace refused reads and collection handling; do not make an error into a row by widening the collection schema. |
| **`seon.cluster.message/delivery`, `src/seon/cluster/message.clj:171`** | `:4774–4775`, nested `[:seon.error/values 0 :seon.error/at]` absent | Upstream message/error-family owner first. Repair producer evidence; a missing required base member is not fixed by listing a facet. |
| **`seon.ai/complete`, `src/seon/ai.clj:1458`, contract `:1475`** | `:5586`, missing `:seon.ai/text` | Upstream AI/error-family owner first. Determine the actual returned provider/callee facet. No paid provider request is needed to reproduce a locally refused boundary. |
| **`seon.plan/plan!`, `src/seon/plan.clj:1291`** | `:5288`, missing `:my.plan/converged?` | **2c-plan's first owned function**, despite already having a contract. Trace the writer's returned refusal and declare/propagate the complete actual facet. |
| **`seon.turn/system-turn`, `src/seon/turn.clj:2094`** | `:9930`, `:seon.render.walk/units` expected a vector, got nil | **2c-turn's first owned function**, despite already having a contract. Resolve error-as-success use at its actual producer/consumer boundary. Do not make units nullable to admit a refusal. |

The first four are **coordination dependencies, not extra campaign-owned
namespaces**. A campaign records the current upstream verdict and supplied
value, then fixes its own propagation contracts and consumers. If the actual
producer is still broken, preserve the evidence and continue the other owned
functions. The orchestrator assigns/reuses the existing class owner; no
campaign edits db/problems/message/ai on its own. Transcript audits the paths
through problems and turn before adding private contracts. W4 records that
these are upstream failures, not a reason to introduce database dependencies
into leaves.

The class is already tracked in
[database errors read as rows](../../../seon/issues/a-database-reads-error-value-is-read-as-a-row-by-its-caller.md).
The older standalone `contract-arming-parity-omits-functions-without-contracts`
issue name in the plan is historical; use the consolidated issue rather than
creating a duplicate. The currently observed
[runtime-status refusal](../../../seon/issues/runtime-status-refuses-error-occurrence-count.md)
also belongs to the upstream boundary, not to W4.

## Dependency ledger and held-path order

| Mechanism | Read source / first-party use | Consequence |
|---|---|---|
| Definition provenance | `reference-code/clj-kondo/src/clj_kondo/impl/analyzer.clj:941,1076–1077,1852,1945–1946`; `src/seon/fn.clj:322,593,670–682` | Use analyzer-derived `:seon.fn/defined-by`, not symbol names, visibility, regex or a manual function roster. Static and runtime declaration paths must retain equivalent provenance. |
| Private contract collection | `reference-code/malli/src/malli/instrument.clj:41–49`; Seon's `src/seon/instrument.clj:900` | Malli reads Var or complete per-arglist metadata; its stock collector uses ns-publics. Preserve Seon's ns-interns collector and index the same authored contract meaning. |
| Compiled function instrumentation | `reference-code/malli/src/malli/core.cljc:2202–2223`; `src/seon/instrument.clj:736,862,929`; step-5 spec | Supply the generation's retained compiled schema to m/-instrument. Input/output/guard checks remain real. A missing retained schema is an acquisition refusal, never permission to compile at first call. |
| Primitive functions | `reference-code/malli/src/malli/instrument.clj:15–38`; `src/seon/instrument.clj:52,69` | Primitive interfaces can prevent ordinary wrapping. Report their actual disposition; neither metadata presence nor a skipped Var proves installed enforcement. |
| Transaction authority | `reference-code/datahike/src/datahike/db/transaction.cljc:1153–1154`; `src/seon/fn.clj:3046,3212,3270`; `src/seon/program.cljc:928` | Compare against the authority's current database, or an immutable previous publication whose identity the accepting writer checks. An empty construction branch is not evidence that all source identities are new. |
| Finding storage | `transaction.cljc:718–770`; `src/seon/program.cljc:753,874,908`; `schemas/seon.fn.edn:110` | Cardinality-many absence cannot prove a check ran. Exact declaration replacement must remove obsolete findings and carry positive check evidence. No copied caller count or separate finding registry. |
| Deletion / task refs | `transaction.cljc:998–1015`; wave-3a §§schema/trigger | Retraction removes declaration findings; history retains past observations. Task subject refs sweep, leaving unavailable acceptance, not automatic task success. |
| Gate arming | `src/seon/test/arm.clj:35,141,167,212,242`; `test/seon/test/runner_test.clj:756`; `test/seon/instrument_test.clj:1141` | Existing parity compares schema-bearing armable Vars with wrappers and misses undeclared contracts. Extend this owner; do not add a separate gate executable. |

| Held owner at design checkpoint | Paths relevant here | Release condition |
|---|---|---|
| Publication dissolution / bridge step 2 | dirty `src/seon/fn.clj`, `src/seon/cluster/source.clj`, `src/seon/cluster.clj`, schema owners, source schemas and canonical fixture | Their coherent producer/population cut lands. 2a refreshes its seam and caller census; it must not restore retired manifest/acquisition machinery merely because this baseline names it. |
| Bridge step 5 / error-family instrumentation | `src/seon/instrument.clj`, `src/seon/test/arm.clj`, instrumentation schemas/tests and acquisition dependencies | Actual step-5 landing and orchestrator proof; error-family wrapper changes integrated. |
| Wave 1c, wave 3a and turn/cluster kind conversion | `src/seon/turn.clj`, turn schemas/tests; task rename can affect transcript and plan | One owner at a time. Launch the corresponding 2c lane only after its complete namespace and resources are released. Do not operate a parked lane. |
| Wave 1d plan/config | `src/seon/plan.clj`, `schemas/my.plan*.edn`, `schemas/seon.plan*.edn`, plan tests | 1d's changed relation semantics and all callers land before 2c-plan. |
| Ops-effects kind conversion | ai/problems/plan/background and their schema/test owners; the 10:10 working-edge entry releases my.background to that lane | In particular, 2c-plan waits for ops-effects as well as 1d. Those upstream fixes are not campaign ownership. |
| Test-system kind conversion | `src/seon/test/arm.clj`, runner/accretion and their schemas/tests; canonical `test/seon/test_support.clj` remains under publication work | 2a waits for the arming/runner slice to land. No fixture or test-system repair by a campaign while held. |

Held means **currently dirty under another owner**, not permanent ownership.
Recheck status before launch and before naming a boundary. The later inspected
working-edge entries record 1a through `db53f32fb`, my-protocol through
`9875331d1`, and active ops-effects/test-system work. These supersede the
initial dirty-file snapshot; they do not change the fixed source coordinates.
Do not infer that a clean file is held from this dated table. The orchestrator
owns scheduling, source-hook pause/re-enable and serial cold gates; this document does not
start or modify those operations.

## 2a — the one enforcement seam

**Guarantee:** the indexed eligible population has an explicit current check
result; missing contracts are durable positive facts. Publication carries
existing debt but cannot grow it through a new identity or loss of a contract.
The gate accounts for every eligible declaration, including those with no
Malli metadata, and proves actual private wrapper installation.

### Launch verbatim — gpt-6-astra low; estimate 2–3 engineer-days

> Implement wave 2a after bridge step 5 and the publication owner have landed.
> Apply the common contract and all tables in this section. Read the coverage
> investigation, program-facts §1j, error PRD §§1–2, step-5 landing and the
> existing consolidated error-as-row issue's current status first.
>
> Extend the existing declaration analysis/publication owner in seon.fn and
> the existing gate arming owner in seon.test.arm. Query eligibility from
> :seon.fn/defined-by. Persist the missing-contract result ON each eligible
> function declaration, including private ones; make the gate read those
> facts. Carry positive check evidence so absent findings cannot mean either
> unchecked or clean. Use the exact schema delta below. Do not introduce a
> second analyzer, finding entity family or schema compiler.
>
> Reuse manifest-data and assert-capability-contracts! at their actual landed
> locations. At this baseline they are fn.clj:2206 and :2020, not the old
> :2090 cited in the audit. Preserve the stronger capability-handler checks.
> Full publication, incremental replacement, runtime analyzed declarations
> and gate snapshot acquisition must agree on eligibility and check results.
> Add the new-identity/lost-contract ratchet at the existing acceptance
> authority, using its supplied immutable basis and actual final declaration.
> Do not derive grandfathering from an empty scratch branch or a local file.
>
> Extend parity to account for eligible declarations without contracts,
> contracted eligible declarations, unsupported instrumentation and missing
> provenance separately. Existing debt is reported with declaration identity
> and evidence; missing declared wrappers or unavailable census evidence
> refuse. Keep step 5's retained-schema identity and zero wrapper compilation
> guarantees. Prove the canonical regressions below, record exact before and
> after counts and the unsupported population, commit path-limited, then stop.

### Owned paths and exact source seams

| Owned path | Work at committed HEAD / launch constraint |
|---|---|
| `src/seon/fn.clj` | `function-definition? :322`, `var-row :593` and provenance/spec `:670–682`; `analyzed-form :891`, `source-rows :1174`; artifact `:1289`; existing contract-findings `:1600` and missing-spec `:1753`; `assert-capability-contracts! :2020`, `manifest-data :2206`, `replace-manifest-artifacts :2230`; toolchain digest `:2254`; complete/incremental build `:2358`; canonical rows/contract facts `:2581,2620`; reconciliation/index `:3046,3212`. One classifier and one declaration-result producer reused across these paths. |
| `resources/seon/schemas/seon.fn.edn`, `seon.fn.contract.edn`, `seon.fn.contract.finding.edn`, `seon.fn.finding.edn` in that directory | Exact delta below. Existing `defined-by :17`, entity map `:110`, spec `:178`, sym identity `:179`. Keep the current report vocabulary; distinguish stored attrs from transient report forms. |
| `src/seon/test/arm.clj`, `resources/seon/schemas/seon.instrument.edn` | Extend arming result/report contracts only as required to expose the accounting, using the acquired graph. `arm-contracts! :167` / parity `:212`; no shell-side scan or second armer. |
| `src/seon/instrument.clj` | Only parity/disposition access and preservation of error-facet enforcement as needed; `armable :69`, `compiled-wrapper :736`, `arm-var! :862`, `collect-contracts! :900`, `apply! :929`. Step 5 owns wrapper compilation; do not redo it. |
| `src/seon/cluster/source.clj`, `src/seon/program.cljc` | Narrow, **after release**, to connect the ratchet to the publication acceptance/basis and exact replacement if the landed owner needs it. Reuse existing source custody/CAS; no new lifecycle. program's canonical-row/exact replacement already derives owned attributes from the entity map, so do not add an attribute list there. |
| `test/seon/fn_test.clj`, `test/seon/instrument_test.clj`, `test/seon/test/runner_test.clj`, `test/seon/dev/source_instrumentation_test.clj`, `test/seon/cluster/source_test.clj` | Extend existing source/contract, parity, adoption and replacement regressions. The canonical fixture helper may be changed in `test/seon/test_support.clj` **only after release**, if its analyzed declarations need the new positive check fact; use the real producer, never invented schema metadata. |
| `docs/prds/steward-platform/research/wave-2a-enforcement-2026-09-21.md` | Required implementation landing evidence, ratchet phase and deletion budget. Update the existing consolidated issue only for the class actually proved fixed. The orchestrator owns plan/working-edge/index integration. |

A necessary caller outside this closed set is a coordination boundary. Name
its qualified symbol/path before changing ownership. Do not turn the narrow
publication integration into a db writer, schema engine or cluster rewrite.

### Exact declaration delta and eligibility

Search the actual merged population first. These are **new attributes proposed
by this specification**, not claims that they exist at HEAD.

| Attribute / existing owner | Declared shape and storage | Meaning and deletion |
|---|---|---|
| **`:seon.fn/contract-findings`** in `seon.fn.edn` | `[:set :seon.fn.contract/finding]`, optional on `:seon.fn/fn`; many keyword values, indexed for finding selection | “Value: reasons the current declaration's completed contract check found an unenforceable boundary. Exact replacement removes reasons no longer true; retracting the declaration removes its current observations. History retains previous observations.” Missing contract contributes exactly `:seon.fn.contract.finding/missing-spec`, once. No finding IDs, refs, components or copied counts. |
| **`:seon.fn/contract-checked-digest`** in `seon.fn.edn` | Reuse the non-identity 64-character value shape `:seon.program/analyzed-source-digest` (`seon.program.edn:1`), with this attribute's own description; one string value, optional on historical stored rows, required by successful current checked publication | “Value: positive evidence that contract checking completed for this declaration and checker input. An absent or mismatched value means unavailable check evidence, never a clean declaration.” Derive with `(seon.id/digest 64 parts)` from an ordered vector of the canonical declaration inputs (symbol, defined-by, authored spec or its absence, analyzed source digest) and the existing analysis toolchain digest. Do not mint another hash algorithm or version registry. |
| `:seon.fn.contract/finding` in `seon.fn.contract.edn:2` | Keep its existing closed reason values. Add a reason only for a distinct check outcome with a regression and docstring. | Existing missing-spec and completeness reasons remain the vocabulary. Do not use an enum reason as an entity kind. Caller count and finding position remain derived report data. |
| `seon.fn.contract.finding.edn:4,7` | Existing `error-facet-analysis-unavailable` and `undeclared-error-facet` literal schemas | These are declarations, not proof a producer exists. Reuse for a justified diagnostic/report; no fabricated static facet analysis from a call-edge set. Runtime wrapper proof remains authoritative for an actual returned value. |
| `seon.fn.finding.edn:1` | Existing relative-path schema only | This file is not already a durable finding entity. Do not pretend that a transient artifact/manifest finding or a printed warning meets the declaration-fact requirement. |

The check digest is an observation of **this check**, not a second source
identity. Do not inherit `:seon.source/digest`'s unique-identity property
(`schemas/seon.source.edn:27`); this is an ordinary value attribute. The existing
analyzed-source-digest proves analysis ran, not that an older analyzer checked
contracts. Include the checker/toolchain input so a
new checker cannot accept old unchecked artifacts. Reuse existing cache input
invalidation (`fn/toolchain-digest`); do not hash the resulting manifest back
into its own finding rows. At publication, validate the digest/result against
the producer's authoritative input, never trust caller-asserted findings as a
way to clear debt. Exact replacement retracts stale finding datoms while
updating the checked evidence in the same accepted declaration.

Eligibility is a query over analyzer provenance for **authored callable
bodies**: defn/defn-, and other authored callable declaration forms supported
by the existing analyzer, including authored macros where admitted. Generated
defrecord/deftype constructors and protocol declaration Vars have no authored
function body at that Var and are excluded for that recorded reason. No
prefix, namespace-name, public-only, internal? or preassembled symbol list
may decide eligibility. Discover actual distinct defined-by values and
account for each. Missing/unrecognized provenance is unavailable evidence,
not ineligible. Preserve it through runtime analysis as well as file analysis.

Multimethod declarations/method bodies and primitive-hinted functions require
explicit coverage dispositions. A primitive authored function still needs a
contract and a finding when missing; if the dependency cannot wrap it, report
that limitation positively and do not count it as armed. A defmethod body
without an independent function identity must be reported through the
existing source-attributed lint/finding mechanism (`fn/lint-rows :1211`), with
its file/span and actual defining form. Do not invent a fake function identity
or quietly drop it. This slice reports unsupported wrapping; solving the
primitive interface or inventing multimethod instrumentation is out of scope.

Completeness uses the existing `contract-findings` owner, accreted in place.
Its current report (`fn.clj:1600–1753`) examines rows and emits transient
missing-spec results; it is not stored coverage. Reuse its actual checks and
polymorphic-exemption semantics, but derive eligibility and stored missing
results from the one producer. A wrapper being present does not prove that a
bare-map result or unconstrained input is a useful contract.

### Publication ratchet and gate verdict

1. **Inventory baseline:** the first accepted checked publication records
   existing uncontracted source declarations and all unavailable dispositions.
   The first construction of a disposable store may establish that baseline;
   it must report nonzero debt honestly. Ordinary publication into a fresh
   construction branch is not a new baseline when an accepted source exists.
   No mutable exemption list, environment flag or launch option bypasses this.
2. **No new debt:** with an accepted source basis, refuse a final eligible
   declaration lacking a contract if its identity is new, or if that identity
   previously had a contract. An already uncontracted current identity may be
   republished with its positive finding. A delete/recreate does not recover
   an exemption from historical existence. Keep capability-handler refusal
   (`assert-capability-contracts! :2053`) strict even in the baseline.
3. **One acceptance authority:** the source writer compares against the
   accepted immutable basis it actually admits, checking source identity/CAS
   there. In-place declaration replacement uses its current writer database.
   A stale pre-read, source file existence or empty scratch database cannot
   authorize a new identity. Runtime installation must use the same rule;
   its existing contracted-only admission must not silently hide a rejected
   uncontracted declaration. A refusal names the subject and actual missing
   contract. It does not claim a finding was committed for a rejected row.
4. **Gate accounting:** derive eligible declarations independently of Malli
   metadata. Report the exact persisted missing findings and checked basis.
   For supported contracted functions, compare with actual installed wrapper
   identities. Report unsupported methods/primitive Vars and unavailable
   provenance explicitly. Missing graph, zero subject population, missing
   checked evidence or missing expected wrapper is a typed refusal. The
   grandfathered backlog is a visible advisory debt result, not a claim that
   all functions are contracted. A new-debt publication refusal fails the gate.
5. **Remove the temporary exemption only at zero:** W4 plus these W1 slices
   do not finish the backlog. Wave 5 closes it. At a positively checked zero
   eligible missing population, remove baseline-debt acceptance in a later
   coherent slice and enforce all eligible functions. Keep explicit
   unsupported coverage reporting; never report full enforcement while it
   remains unavailable. No calendar date or fixed count activates this cut.

Do not add a general static error-flow interpreter. Call edges prove possible
calls, not returned facets. A known actual returned undeclared facet is a
refusal; inability to infer one statically is an explicit unavailable analysis
result, not evidence that the function returns no errors.

### Canonical regression matrix

| Class / owner | Required observation |
|---|---|
| Positive missing contract — `seon.fn-test` | Use the canonical initial checked-publication fixture, with real analyzed public/private eligible definitions before its first source acceptance (or explicitly supplied prior accepted debt). Never bypass the ratchet with a fixture-only admission flag. For one selected missing function, query **exactly one** `[eid :seon.fn/contract-findings :seon.fn.contract.finding/missing-spec]` datom plus matching positive checked digest. Republish twice: still one current finding. Query through the real gate census and see that identity reported. No log capture is the only assertion. |
| Clean versus unchecked — `seon.fn-test` | Add a complete contract and republish: no current missing finding, positive current check evidence remains, history retains prior finding. Remove/check-stale evidence or remove the subject: result is unavailable, never clean or complete. A zero-population fixture refuses rather than proving parity. |
| Provenance — `seon.fn-test` | Same meaning through file and runtime analyzed declarations; generated protocol/type Vars excluded by actual defined-by; authored private body included. Unknown provenance and an unsupported defmethod/primitive fixture are positively reported. Quoted source text does not invent a read/call. |
| Ratchet — `seon.fn-test`, `seon.cluster.source-test` | Accepted baseline debt republishes; new uncontracted identity and contracted→uncontracted replacement refuse with subject/evidence and no accepted-source mutation. Complete and incremental publication agree. A fresh construction branch with an existing accepted source cannot reset the baseline. Stale supplied source basis refuses. A complete new definition succeeds. |
| Private parity — `seon.instrument-test`, `seon.test.runner-test` | A contracted private function is present in the canonical gate's installed wrappers and the cluster's installed wrappers. Call it with an invalid input and observe its named refusal. Also invoke valid input successfully, with positive subject counts. Use existing real arming tests (`instrument_test.clj:1141`, `runner_test.clj:756`), not a mocked apply!/armable set. |
| Exact output facets — `seon.instrument-test` and real SCI instrumentation | In the canonical acquired generation, a valid declared returned facet passes unchanged; the same value carrying another valid facet also passes; a complete error carrying only an undeclared facet refuses. Assert function identity, arity, offending returned value/evidence and expected declaration. A missing base/facet member refuses as malformed. Exercise JVM and actual SCI in supported panic/record modes without altering reporter semantics. |
| Retained compiled contracts — step-5 regressions | Arming, first invocation, rearming after relevant schema change, restoration and SCI wrapping use the supplied retained schema; no raw/named recompilation in wrappers, positive schema/wrapper counts, unrelated wrappers retain identity. These are existing step-5 guarantees to preserve, not a second implementation. |

Fast selection: `seon.fn-test seon.instrument-test seon.test.runner-test
seon.dev.source-instrumentation-test seon.cluster.source-test`; include
`seon.sci.eval-instrumentation-test` when the wrapper/SCI path changes. Select
only owned changed paths; adapt to actual prerequisite test renames.

Live proof after adoption: pull one current uncontracted declaration and its
finding/check evidence, then one contracted private declaration and its real
wrapper disposition in the same cluster/program basis. Invoke the latter's
known invalid input and inspect the complete refusal. Do not claim the
first warm fixture run proves development adoption. The orchestrator runs
the cold owned-path gate and platform proof and records RESET NEEDED if the
new attrs or prerequisite schemas cannot be adopted in place.

**Must NOT:** complete the backlog, change capability custody, relax output
facets, implement a second static analyzer, copy compiled schemas into facts,
introduce a missing-contract log-only warning, count skipped functions as
armed, add a task registry, route agents by namespace membership, resurrect
step-5 fallback compilation, or let the gate's declared-schema set define its
own completeness denominator. Delete superseded duplicate missing-spec
selection when the one producer replaces it; retain useful report APIs.

## 2b — W4 leaves, one bounded campaign

**Guarantee:** the requested three namespaces have complete useful contracts
for their eligible authored functions, and the seam reports their exact
remaining unsupported population. This is the cheap end-to-end proof of
selection → contract repair → armed execution → finding removal.

### Launch verbatim — gpt-5.6-sol low; estimate 12–18 engineer-hours

> Implement wave 2b after 2a's proven landing. Own only seon.operator.state,
> seon.sci.reader and seon.print and the listed schemas/tests. Read the common
> contract and six-function priority table first; record upstream status,
> then derive your actual missing population through 2a. The dated requested
> subset is 186 missing contracts, not 297. Do not enlarge scope to make the
> mistaken total true. Work the three namespaces serially in this one lane.
>
> Read each body and callers before declaring its inputs and all results.
> Contract private helpers as well as public functions. Start with the cheap
> reader/print transformations, then the operator effect boundaries. Preserve
> actual nil-returning Clojure APIs with an honest contract where necessary;
> never store nil or erase a legitimate runtime result just to avoid a schema.
> Name any justified polymorphic shape and its existing exemption. Do not
> relabel effectful operator functions as pure because they do not read db.
>
> Exercise the real arming/compiled-contract seam, prove the owned missing
> facts are removed only when current contracts exist, and preserve exact
> reader bytes, print grammar/elisions, process identity, locking and cleanup.
> Reuse existing behavioral regressions; add only meaningful missing boundary
> class coverage. Record measured counts and unsupported dispositions, commit
> path-limited with the named landing note, then stop. The other W4 namespaces
> and remaining ~1,800 functions are not this human campaign.

### Owned files, grounding and checks

| Owned paths | Exact starting coordinates / invariant / fast namespace |
|---|---|
| `src/seon/operator/state.clj`; existing `resources/seon/schemas/seon.operator*.edn` only for shapes this namespace actually uses | `run-process! :76`, `canonical-path :177`, `process-start-instant :187`, `process-identity-alive? :197`, `terminate-recorded-process! :223`, `read-edn :302`, `write-edn! :314`, `delete-edn! :330`, `lock-holder :439`, `with-lifecycle-lock! :577`, `with-control-lock! :693`, `process-census :1205`, `cleanup-root-under-lock! :1393`. Read their callers and actual Java/process APIs locally. Preserve PID/start-instant checks, deadlines, lock ownership and symlink-safe cleanup. |
| `src/seon/sci/reader.cljc`; `resources/seon/schemas/seon.sci.reader*.edn` that exist at launch | `fence-line? :501`, `read :874`; traverse the whole namespace, all arities and private helpers. Preserve source offsets, exact bytes, reader forms and refusal facets. CLJ execution only; no deleted CLJS engine reconstruction. |
| `src/seon/print.cljc`; `resources/seon/schemas/seon.print*.edn` | `sink? :23`, `text-sink :232`, `hiccup-sink :296`, `tee-sink :317`, `value-at :401`, `compare-values :411`, `requery-form :442`, `render-elision-ai :455`, `references :831`, `emit-text :881`, `emit-hiccup :891`, `emit-both :901`, `node? :951`, `elision :982`, `enrich-elisions :1131`, `fit :1302`. Preserve sink/protocol semantics and actual object shapes. Do not move clipping or add HTML clipping. |
| `test/seon/operator_test.clj`, `test/seon/dev/fresh_operator_test.clj`, `test/seon/dev/fresh_operator_export_test.clj`, `test/seon/dev/fresh_operator_reset_test.clj`, `test/seon/sci/reader_test.clj`, `test/seon/print_test.clj` | Reuse current behavior tests for the affected functions. Run reader/print fast tests first; run operator tests reaching changed boundaries with their isolated canonical roots, never the real default lifecycle. A contract-only edit does not authorize an actual default reset. |
| `docs/prds/steward-platform/research/wave-2b-leaf-contracts-2026-09-21.md` | Selection basis, exact missing/complete/unsupported before/after counts by namespace, contracts/exemptions added, returned facets, tests and live proof. |

Resource globs above are responsibility boundaries, not permission to edit
all matching files: enumerate the exact existing keys/paths at launch, change
only the named namespaces' shapes and retain shared semantics. If a missing
family file is necessary, create the one canonical schema owner with a
fully namespaced name; never a duplicate pulled/entity schema. Shared schema
ownership must be released before editing. No `src/seon/operator.clj`, shell
launcher, schema bridge or capability implementation expansion.

Canonical acceptance: use 2a's real checked declaration population to show
that **every** eligible owned function has a current complete contract, with
positive population counts and exact unsupported dispositions. Through the
canonical fixture and armed real functions, assert a representative valid
call and invalid input per distinct new boundary class; reader refusal facets
and operator refusal results must pass only their declared outputs. Retain
2a's exact-one-finding, private gate/cluster parity and declared/undeclared
facet regressions in the fast selection as a shared class proof; do not copy
those tests three times. Existing reader/print tests prove observable bytes
unchanged. Operator cleanup tests retain a symlinked sentinel and assert it
survives; acquire resources inside scopes and clean only the fixture roots.

Fast namespace groups, serially as affected:
`seon.sci.reader-test seon.print-test`;
`seon.operator-test seon.dev.fresh-operator-test
seon.dev.fresh-operator-export-test seon.dev.fresh-operator-reset-test`;
then the affected 2a class regressions under `seon.fn-test
seon.instrument-test`. Do not launch an operator probe JVM beside a fast run.

Live proof: after actual development adoption, query the owned finding census
and invoke one private reader or print helper through the real supported
boundary with known valid/invalid data. Use nondestructive operator identity
inspection for its live proof. The orchestrator owns the cold paths/platform
proof. Only newly required schema changes enter its reset batch.

**Must NOT:** widen to fs.jvm/web.jvm/shell.jvm/edit, implement new effects,
add a db read to leaves, alter clipping/reader grammar, modify default process
state, add regexes to production, silence unsupported instrumentation, rebuild
Malli contracts in functions, or count a blanket :any/:map contract as repair.

## 2c — W1 database consumers, one namespace per lane

**Guarantee:** an owned database consumer has an honest argument contract and
an exact result contract, and handles an actual refused read/write before
using it as success data. Adding metadata alone is not completion when the
body still turns an error into rows, nil units or missing success fields.

These are **three separate launches**, sharing the following acceptance
matrix. They may run in parallel only after shared owners release their
paths; never launch all three beside a still-running 2b lane. The dated
28/22/15 counts select priority work, not an assertion that every remaining
function in those namespaces is already contracted.

### Shared 2c regression and implementation contract

> Begin with the six functions in the priority table, in that order. Record
> current upstream producer status, then fix your first owned priority
> function before the private database-reading list. Read error PRD rule 1.2
> and trace actual callee result schemas through every returned branch.
> Declare exact propagated facets; preserve the complete value when returning
> it. Branch on the owning facet's distinguishing declared member rather
> than a generic error/base predicate or legacy kind. Do not fabricate missing
> timestamps/identity/provenance on another owner's malformed error.
>
> Derive the current owned direct database readers and their missing findings
> from the program graph; inspect indirect callers affected by the new
> contracts. Complete the owned priority function and those readers first.
> Add contracts to newly changed helpers in the same slice. Do not expand to
> every non-reading function merely because it shares the namespace. If a
> typed callee refusal reveals a real owned consumer defect, fix it with its
> contract. If it reveals an upstream producer defect, record the exact
> returned value and boundary, continue independent work, and do not widen
> your output to accept a malformed value.

| Required class, canonical fixture and real arming | Assertion |
|---|---|
| Real database refusal | Use with-database and its complete projection; obtain a real typed refusal through a supported invalid read/write or invalid declared argument. Do not redefine db/q, pull or transact! to return a invented map. Assert the refusal has its required base and facet members before testing propagation. |
| Declared propagation | The owned function returns or handles that exact facet without interpreting it as a collection, plan, unit vector or success map. Its wrapper accepts the declared complete facet. Assert relevant unchanged database facts on refusal, not only the return type. |
| Undeclared return | Through the existing instrumentation regression owner, a real function returning a valid but undeclared facet is refused, naming function and offending value. A declared facet plus extra facet passes; incomplete base/facet refuses. Reuse 2a's JVM/SCI class proof and add an owned returned-branch regression only where it catches the consumer defect. |
| Private arming | At least one repaired private database reader is actually called under gate and cluster instrumentation with positive installation/execution evidence. Contract presence alone is insufficient. |
| Finding settlement | Before: selected function has exactly one stored missing-spec finding and current checked evidence. After accepted contract: subject still exists, contract is current, finding absent, check evidence current and actual selected gate evidence available. Absent subject/check/run cannot pass. |
| Semantic success | Existing success-path behavior remains: same domain values, ordering and durable transitions. A union widened to error without an exercised refusal branch is not proof that the body propagates it. |

Follow the common canonical helpers, instrumentation preservation and event
bounds. No copied db fixture schema, private global mutation, ad hoc returned
error map, or live history wipe under a running agent graph. Preserve query
bounds and pass the supplied database/projection; adding a contract does not
authorize fresh global configuration lookup on each call.

### 2c-turn — launch verbatim, gpt-5.6-sol low; 8–14 engineer-hours

> Implement only the seon.turn W1 contract slice after 2a, wave 1c, the
> turn/cluster error conversion and any touching wave-3a caller conversion
> have landed. Read the common and shared 2c contracts. Start with
> seon.turn/system-turn at turn.clj:2094 and the raw units=nil refusal. Trace
> the actual upstream result before changing the output schema. Then repair
> the current uncontracted database readers in this namespace, using the
> dated 28-function inventory below as inspection coordinates, not a roster.
>
> Own src/seon/turn.clj, the existing resources/seon/schemas/seon.turn*.edn
> declarations required by these functions, test/seon/turn_test.clj and
> test/seon/cluster/turn_test.clj. Shared schema entries require release.
> Do not edit src/seon/cluster.clj, db, message, ai, problems or transcript.
> Preserve opening/system-turn/ordinary-turn semantics, transaction authority,
> evaluation evidence and wake settlement. No new flow, timeout, retry,
> clipping site or lifecycle. Record exact findings and returned facets in
> docs/prds/steward-platform/research/wave-2c-turn-contracts-2026-09-21.md,
> commit the coherent owned slice path-limited and stop.

HEAD inspection coordinates for the 28 missing direct-read candidates:

| Coordinates in `src/seon/turn.clj` | Functions |
|---|---|
| 64,349,551,560,823,911,1007 | result-blob-threshold; running-receipts; resolve-namespace-name; current-transaction-instant; current-receipt; settlement-form; current-schema-data-attributes |
| 1081,1161,1237,1454,1755,1947,1981 | identity-ref; declaration-diverged-since-open?; row-tx; stored-record-content; run-receipts; latest-evaluations; read-only-evaluation? |
| 2039,2329,2342,2474,2483,2597,2845 | issue-origin-read?; agent-run; next-ordinal; form-run-id; assignment-facts; agent-eid; continuing-reply? |
| 3449,3794,3892,4016,4049,4469,4825 | evaluation-terminal-data; attempts; record-attempt!; fold-evaluations; fold-namespace; evaluation-entity-id; generate-turn |

These are literal existing identifiers, including historical spellings; do
not turn this contract slice into a public rename without its full caller
slice. Follow changes from the prerequisite task/evaluation conversions.
Inspect already-contracted owners that call these functions, especially
`open-call :394`, `close-call :441`, `record-evaluated-call :1481`,
`next-agent-work :2868`, `latest-answering-turn-t :2960`, `evaluate-sources
:4498`, and `turn :4915`, for returned-facet propagation.

Regressions: shared 2c matrix plus system-turn's refused generated-read path,
positive units on success, and no spurious settled/handled facts on refusal.
Use the canonical agent/turn fixture and real SCI; never a hand-built partial
turn. Fast namespaces: `seon.turn-test seon.cluster.turn-test` plus affected
`seon.instrument-test`/`seon.fn-test` shared regressions. Live proof after
adoption queries one repaired private reader and executes the bounded
system-turn case on authorized fixture data, preserving the running graph's
custody. The orchestrator supplies cold/platform and any reset-boundary proof.

**Must NOT:** claim the 28 covers all turn functions, accept nil units to make
the old gate pass, change reply/wake rules, restore :kind predicates, or fix a
foreign producer in this lane. Complexity beyond an owned propagation repair
is a decision with three priced options, not permission to rewrite turn.clj.

### 2c-transcript — launch verbatim, gpt-5.6-sol low; 7–12 engineer-hours

> Implement only the seon.render.transcript W1 contract slice after 2a and
> release from task/render caller conversions. Read the common and shared
> 2c contracts. Triage the six priority functions first, especially the
> problems reader and system-turn paths feeding this renderer. Derive the
> current direct database readers; the audit's 22 is dated, not a required
> number of new edits. Trace complete actual values before adding unions.
>
> Own src/seon/render/transcript.clj, its existing canonical
> resources/seon/schemas/seon.render.transcript*.edn declarations,
> test/seon/render/transcript_test.clj and
> test/seon/render/transcript_run_test.clj. Other render/eval/turn resource
> owners are coordination boundaries. Preserve chronological history,
> selected turns, saved shown text, live HTML values, and the single AI
> projection boundary. No clipping in HTML or historical re-rendering.
> Record exact findings, returned facets, render observations and proof in
> docs/prds/steward-platform/research/wave-2c-transcript-contracts-2026-09-21.md,
> commit path-limited and stop.

Current HEAD inspection candidates, derived by reading direct call sites;
re-query the accepted graph at launch to settle the difference from 22:

| Coordinates in `src/seon/render/transcript.clj` | Functions |
|---|---|
| 181,217,514,682,754,812,884,1157 | selected-run-entity-ids; message-order-facts; reasoning-attempts; selected-run-identities; entry-basis; turn-header; agent-config; turn-kind |
| 1228,1257,1485,1792,1852,2039,2108,2152 | session-stall; session-header; emission-label; turn-effects; ledger-turn-body; fault-problems; session-budget; session-problems |

Inspect already-contracted consumers as well: `history-entries :761`,
`agent-history :902`, `format-history-ai :967`, `turn-rows :1144`,
`ledger-acquisition :1704`, `ledger-rows :1812`, `render-captured-prefix
:2060`, `render-ledger :2329`, `render-runtime-html :2381`. A render boundary
must remain total on its ordinary values; do not propagate an internal error
where the declared renderer owes an honest rendered diagnostic. Test the
actual returned/rendered result of that owner rather than assuming every
consumer should simply return its callee's error unchanged.

Regressions: shared 2c matrix plus a refused problems/history read cannot be
iterated as receipt rows; stored shown text remains byte-identical; ordinary
AI/HTML output remains total with correctly rendered error evidence. Fast
namespaces: `seon.render.transcript-test seon.render.transcript-run-test` and
affected shared `seon.fn-test seon.instrument-test`. Live proof after adoption
observes the real debug history surface as well as the database/wrapper facts.
Record browser paint separately; a successful render call is not browser
proof. The orchestrator owns cold/platform proof.

**Must NOT:** weaken ordinary-value total rendering, rerun historical
expressions, add a transcript store or alternate error renderer, clip HTML,
change turn selection to avoid the failing value, or edit problems/turn to
clear this lane's verification boundary.

### 2c-plan — launch verbatim, gpt-5.6-sol low; 6–10 engineer-hours

> Implement only the seon.plan W1 contract slice after 2a, wave 1d, the
> ops-effects kind conversion and any touching wave-3a task conversion have
> landed. Read the common and shared 2c contracts. Start with seon.plan/plan! at plan.clj:1291 and the actual
> returned writer refusal behind the missing :my.plan/converged? diagnostic.
> Then contract the current missing direct database readers, using the dated
> 15-function inventory below as grounding. Preserve transaction ownership
> and exact dependency/query/acceptance semantics.
>
> Own src/seon/plan.clj, existing resources/seon/schemas/seon.plan*.edn and
> my.plan*.edn entries required by these functions after their release,
> test/seon/plan_test.clj and test/seon/contracts_plan_test.clj. Do not edit
> src/my/plan.clj, task/issue owners, db, turn or the canonical fixture while
> held elsewhere. A required caller conversion outside ownership is reported
> before widening the slice. Record facts, output facets and proof in
> docs/prds/steward-platform/research/wave-2c-plan-contracts-2026-09-21.md,
> commit path-limited and stop.

| Coordinates in `src/seon/plan.clj` | Functions |
|---|---|
| 155,198,230,352,589 | subject-eid; owned-ids; foreign-open-work; agent-plan-pull; owned-step-eid! |
| 609,669,689,787,812 | next-position; query-deadline; stale-issue-tests; done-query-result; completion-tx |
| 846,931,1062,1117,1162 | complete-step-call; start-step-call; scalar-retractions; stored-comparables; compile-tree |

Regressions: shared 2c matrix plus plan!'s actual refused transaction leaves
plan state unchanged, names the exact returned facet and passes that declared
facet through its wrapper. A success still returns the declared plan result.
Unavailable/stale/empty acceptance evidence cannot complete a plan step;
contract work must preserve wave 1d's relation guarantees. Exercise
transaction functions through the real writer, not only direct helper calls.
Fast namespaces: `seon.plan-test seon.contracts-plan-test`; run
`my.plan-test` against committed caller bytes when compatible, reporting its
foreign boundary if not; include affected shared `seon.fn-test
seon.instrument-test`. Live proof after adoption uses a canonical authorized
agent fixture, querying the unchanged plan after refusal and the repaired
private wrapper. The orchestrator owns cold/platform and reset proof.

**Must NOT:** weaken completion queries, turn an error into converged=false
success, copy task instructions into a plan objective, add a second plan/task
family, bypass the final writer, or broaden a peer ref's deletion policy to
make a contract pass.

## Wave 5 — the remaining functions are the first templated task class

**The remaining ~1,800 functions are not a human contract campaign.** Do not
launch W2/W3/W5 alphabetical sweeps or the other W4 namespaces from this
specification. After 2a and these bounded proofs, namespace agents consume
one current uncontracted function at a time through the task family specified
in [wave 3a](wave-3a-task-family-spec-2026-09-21.md), especially its schema table,
§Trigger writer, and canonical regressions. A template is its ordinary linked
data rendered through existing pairs; no stored template entity or task queue.

### What 2a must leave queryable

For each eligible missing declaration, publication records the **existing
function identity** `:seon.fn/sym`, its analyzer-derived `:seon.fn/defined-by`,
canonical source, authored arglists, namespace relation, private flag, analyzed
source digest, and applicable source file/form span, together with the new
**current checked digest** and **missing-spec finding datom**. These land
atomically as one accepted declaration. Agent-authored definitions legitimately
without a file/span retain their source and admission provenance; do not invent
a filesystem target. Missing required subject/source/provenance evidence is
unavailable, not an empty task.

Caller relations, current gate set, past recorded reach, exact contract
positions and current namespace responsibility are **derived at read time**
from that same supplied database. Do not copy their counts or lists onto the
function to ease task construction. The schema checker doesn't assign an
agent, store a task ID on the declaration, or send a wake.

2a exposes **`seon.fn/uncontracted-functions` (new)** as an ordinary contracted
read over its supplied database: select current checked eligible declarations
with the persisted missing-spec reason, return their real identities and
linked evidence, and return a typed unavailable result when the census cannot
be established. It reuses the existing contract-findings producer/query, not a
second scan. Its qualified symbol can be the installed detector identity when
wave 5 registers this class. Wave 3a's renamed public-without-contract detector
preserves its public-only contract; do not silently change that function's
meaning or take ownership of its in-flight rename.

### Mapping to wave 3a, not another task model

| Task input / fact from the actual wave-3a shape | Contract task binding |
|---|---|
| detector plus subject identity | Detector is the installed `seon.fn/uncontracted-functions` declaration. Subject identity is `[:seon.fn/sym qualified-function-symbol]`, preserving the symbol value. |
| `seon.task/subject-id` → `:seon.task/id` | Use wave-3a's one identity derivation: `seon.id/id` of the sorted map containing `:seon.task/detector`'s qualified symbol and the subject identity attribute/value. Never hash a prose title, current source bytes or finding occurrence to create another task per edit. |
| `:seon.task/subject` | Peer ref to the one living function declaration; required by the detected-work trigger at creation even though optional in the general stored task schema. A swept subject leaves unavailable acceptance. |
| `:seon.task/detector`, `:seon.task/namespaces` | Real detector/function and namespace refs supply context. Namespace membership does not select recipients. |
| `:seon.task/title`, `:seon.task/problem`, status | Author the repair instructions once on the task: complete this function's contract, trace returned facets, prove real enforcement. Link the subject for current source instead of copying its source into durable task prose. Use the existing status schema. |
| `:seon.task/tests`, `:seon.task/runs` | Current gate-set test refs plus actual recorded runs provide acceptance evidence. Preserve wave-3a's append-only-after-assignment/creator guard. No tests, stale basis or unrun members are unavailable acceptance. No synthetic successful run. |
| `:seon.task/files`, `:seon.task/keys`, auxiliary functions | Cite actual file/span through wave-3a's owned citation components and relevant schema refs. Link auxiliary callees only when useful; do not duplicate the primary function in `/functions` solely to render it. |
| `:seon.task/agent` and occurrence | Hand detector/subject to wave-3a's existing trigger writer. It resolves identity against its current database, creates task+agent atomically or updates the existing task's agent occurrence, then activates after commit. Pure census/generate does not automatically start the entire backlog. |

The agent's bounded work is one function plus the inseparable local helper
or schema/caller repair needed to make its real contract valid. It receives
the same exact-facet and canonical-fixture rules as 2c. It does not receive
blanket permission to broaden a schema, edit another active agent's files or
launch a namespace-wide sweep.

**Done is positive evidence:** the same subject is present; it is still the
intended authored declaration; a current useful contract and matching checked
digest are present; the missing finding has been retracted by accepted
publication; its supported wrapper is actually installed; and the task's
required tests have current recorded successful evidence for the repaired
program/input/basis. Missing subject, detector, check evidence, wrapper or
required test is unavailable. Deletion, empty finding selection and detector
silence cannot complete this task. A source change during work makes the old
acceptance evidence stale and requires the existing basis check, not another
task identity or bespoke lock.

2a proves facts and queryability; wave 3a supplies the task/trigger mechanism;
wave 5 supplies registration, render selection and task execution. Neither
2a nor the human campaigns may launch the remaining backlog. The
orchestrator removes the temporary backlog exemption only after the positive
zero-debt proof described above.

## Design-lane verification boundary

This document is the design lane's only authored file and its landing note.
No source, test, resource, issue or working-edge edits; no JVM, test run,
worktree, default lifecycle operation or other-lane operation was launched.
The source coordinates were inspected from the fixed committed HEAD; foreign
dirty edits were preserved. The 95-entry tally was checked against the raw
summary, and the six failure examples against the raw gate log. Counts in the
coverage study remain dated; launch-time graph census and runtime proof are
explicit implementation obligations.

One read-only MCP runtime_status request to root `/Users/sean/src/seon`,
cluster `default`, advertised PID 24777 alive but returned a health refusal
at `2026-09-20T07:39:30Z`: `seon.problems/problems` refused its return at
`[:seon.problems/error-signatures 0 :seon.error/at]` (missing required member).
This was reported immediately. It is recorded here under the one-document
constraint and linked to the existing runtime-status issue above; no live
health, adoption freshness or arming proof is claimed. No follow-up evaluation
or substitute prepl sender was used.

The design proof is source/authority inspection, evidence reconciliation,
link/coordinate validation and a path-limited document commit. Implementation
fast tallies, real private-wrapper parity, reset/adoption, browser observation
and cold/platform evidence are owed by the named implementation lanes and
orchestrator, not supplied by this design commit.
