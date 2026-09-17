---
type: plan
status: active — reset implementation; modeling study §1i controls
created: 2026-09-17
tags: [schema, reset, program-graph, symbols, deletion, provenance]
---

# One reset: ordered schema and writer batch

**RESET NEEDED. Released seams are authorized.** This is the single integration edit list for G6,
not authorization to reset during implementation. Step 1 was committed at
`4c547a3c3`; step 2 uses `bin/test-fast --paths`, never `bin/test`, and
never restarts or resets default. The explicit step-2 restriction overrides
the assignment's later generic cold-gate paragraph. The orchestrator runs the
cold gates after the one reset. No migration or compatibility population.

## Message publication — §1h applied together under §1i

**RESET NEEDED.** The message lane prepares one publication containing permanent
listened `:seon.message/to`, settlement claims at `:seon.turn/handled`, subject
`:seon.message/about` as the existing nonempty-string token grammar, sender-only
inside classification, and separate `:seon.message/assignment` evaluation-ID
values. `resolve-about`, inbox moves and read-tx are deleted. The same publication
retypes `:seon.eval/origin` to an issue-ID value with its writer, turn carry-forward
and outline consumers. The read-only census at basis 536871060 found about/origin/inbox zero and
read-tx two current datoms. The installed about ref type and removal of those
read-tx facts belong to the coordinated reset. No migration is introduced.
The older message inventory's inbox-move and about-as-inside wording below is
superseded by this ruling. Refreshes/refresh-call were already integrated at
`78cc3b9b7`; they are not reapplied.

Default PID 66052 is read-only for this lane. The orchestrator owns publication,
reset, reseed, cold gates and the deployed unanswered-wakes after-proof. See
[the lane's evidence and exact boundary](../research/message-wake-model-2026-09-17.md).

## Current owner correction — program deletion admission

Read end to end: **J**, [program-graph deletion semantics](../research/deletion-semantics-program-graph-2026-09-16.md)
(revised at `2871e77e1`, especially §2.5, §2.6 and §5); **K**,
[agent/turn deletion semantics](../research/deletion-semantics-agents-and-turns-2026-09-16.md);
and **X**, [reset schema recommendations](../research/reset-schema-recommendations-2026-09-16.md).
The owner's latest assignment and these corrections supersede H's universal
living-ref proposal. No classification property, universal enforcement or
Datahike fork extension belongs to this implementation. Existing required refs
already refuse a sweep through whole-entity validation; optional refs sweep.
Components must validate with their parents (G5), not acquire invented identities.

The next reset-gated production seam is the indexed symbol-edge retype plus
`seon.db/write-report-error` refusal and canonical regressions. Calls,
references, namespace requirements and declared test subjects are current
obligations; reach is historical evidence, reported stale and never a blocker.
Values preserve the evidence needed to distinguish a repair from Datahike's
incoming-ref sweep. The complete caller list is data; only rendering elides.
The detection is removed prior identity values of affected entities, including surviving entities after rename or identity-only retraction, followed
by indexed lookups against the final database. Fresh complete publication has
no deleted identities and reports unresolved edges positively.

Agents are never deleted; no deletion API, tombstone or closure lifecycle is
added. Listeners are out of this assignment. Declaration capability-fn deletion
and shared shape-row reclamation remain owner questions (J §5.2–3). Q2 AST
removal remains unapproved. These explicit boundaries override recommendations
in J/K/X that would expand this seam.

### Seven schema corrections (X wins over the earlier tables where they differ)

| Finding | Corrected edit and publication boundary | Required proof |
|---|---|---|
| X1 issue status | **Keep `:seon.issue/status`.** It is the current authority, not a mirror: 1,742 status datoms versus zero resolved-tx in the dated probe. Any later removal first needs replacement writers from authored note states; no migration is introduced. | Terminal notes remain terminal; opened instant remains authored observation, not import transaction. |
| X2 nonstored contracts | `:seon.eval/outcome`, `:seon.maintenance.result/value`, `:seon.test/failure-identity`, `:seon.render/rendered`, `:seon.cluster.wake/offer-result` are **contract-only work**, outside reset-gated groups. They are not five stored maps: their forms differ, and all five were absent from the installed schema. | Ask `storable-attribute-in?` and inspect installed schema before asserting storage semantics. |
| X3 exception class | The throwable-class symbol sighting was signature input, not a datom writer. Merge class attributes only with actual writers; separately fix db's string under symbol-typed exception-class. | Assert stored symbol and the rendered fault, not merely signature construction. |
| X4 tuple members | Keep value tuples, never ref members. Datahike's heterogeneous check accepts all-invalid members. A cardinality-many tuple write is a set of tuples, not a bare tuple. | Assert stored callee is qualified-symbol and arity is integer; test wrong types and bare-tuple input explicitly. |
| X5 pulled forms | Derive `:as`, defaults, component-reverse cardinality and unexpanded `:db/ident`; refuse unsupported recursion/limit selectors. Pull's silent default cap is 1,000. | More than 1,000 edges remain complete in admission/reporting via datoms; bounded pull reports its cut as an elision, never apparent completeness. |
| X6 literal/EDN shape | The codec requires a genuinely mixed `[:or …]` without explicit value-type. A bare map, vector of maps or single-type union does not engage it. | Every -edn replacement declares its actual mixed literal arms; otherwise retain a documented opaque string. Round-trip and reject noncanonical bytes. |
| X7 render codec exits | ai/html/form storage changes leave the EDN codec; they are **not** program-symbol flips. Keep them in the render pair publication with all readers. | Read native renderer symbols and independently validate rendered text/Hiccup. |

G4 is strengthened by X §3: `explode` erases `#{}` before the final report or
a transaction function can observe it. Require the exact analyzed-input digest
datom; only that fact licenses interpreting absent relation datoms as a known
empty result. There is no submission-time or db.fn/call empty-set workaround.
Recorded reach requires its own computation digest; a definition digest is not
evidence that reach ran. Unknown analyzer symbols must normalize at the analyzer
or use the existing codec at print/read seams, never a third encoding.

K's per-family evidence replaces blanket living-ref language throughout the
older inventory: required refs already refuse; optional current-step, runtime
trigger, causal-chain and sibling-attempt pointers may deliberately sweep.
Historical origin/about/effect-evaluation/occurrence/plan dependency mentions
need their actual identity-value carrier and all writers/readers in one later
publication. Do not infer a new generic deletion policy from this inventory.
The evaluation author deletion also waits for a proven derivation: X measured
24 renderer facts versus 69 system stamps, a 45-row disagreement. Its deletion
cannot be justified by renderer presence alone.

## Tier 1 integration correction — unbreakable connections

Read [unbreakable connections](../research/unbreakable-connections-2026-09-16.md)
end to end (owner inventory `b4c7e86c9`). Its Tier 1 is authorized before the
reset; none of these checks introduces a stored fact or universal deletion rule.

| Item | Integration order and evidence |
|---|---|
| Core file requirement | **Prerequisite discovered:** 802 live core function rows have no file, including manufactured external stubs. `fn/desired-rows` stamps these core. Remove them in G1/G2 first; do not impose the requirement on agent `definition-row`, which deliberately removes file coordinates. The canonical `program-fn-row` helper also needs a writer fix in that group. [Evidence and boundary](../../../seon/issues/core-program-stubs-prevent-required-file-provenance.md). |
| Required namespace/task refs | Canonical regression must name the surviving function/task in the existing whole-entity refusal and prove no commit. No deletion-policy property. |
| Call-site arity | **Landed Tier 1 implementation:** one shared report at final admission, including child-only edits, supplied-default changes and same-transaction repairs. Only raw-count mismatches need preparation plans. The pre-reset read-only observation was 8,939 checked, 46,999 unchecked, zero mismatches, 395 ms; it is dated evidence, not a claim about the regenerated store. |
| Render target admission | **Landed Tier 1:** check final stored schema forms for named AI/HTML renderers and require their function rows. Complete bootstrap now admits canonical schema rows with functions in `index!`'s existing combined population transaction. Incremental schema-only publication and branch acquisition retain their existing canonical-schema reconciliation. |

**Owner review accepted preparation-aware admission (2026-09-17):**
source counts are compared with ranges derived by `seon.call-preparation`
from its existing invocation plans. `(my.message/inbox)` accepts source
counts 0 and 1; its declared count remains 1. Partial omissions retain the
runtime's value-dependent placement check. No supplier executes at write
admission. The raw-count candidate is superseded, not an implementation to
apply. The refusal carries caller, callee, source count, declared ranges and
prepared ranges, without presentation clipping.

**File provenance moves to group 2, the edge-retype publication:** delete
external-stub and identity minting, then require function file provenance
with every remaining constructor, including the canonical `program-fn-row`
helper, changed in that same publication. Preserve the authored-definition
provenance contract; do not make agent `definition-row` impossible. This is
an accepted ordering decision, not a Tier 1 prerequisite still awaiting a
ruling. The edge retype still needs the orchestrator's coordinated reset. The Tier 1
consumers join that same publication: `seon.db/write-render-target-error`
drops its current symbol-to-string lookup conversion, and the shared arity
report, call-preparation plans and their canonical fixtures consume the native
function identity and call-tuple value types. Do not leave these newly landed
readers outside the symbols inventory.

`src/seon/test/selection.clj` was released at `d6659f21a`; its earlier hold
is no longer a boundary. At the final status check, `test/seon/test_support.clj`
and the no-default-cluster source files are also released (`cdfc01058`).
`src/seon/program.cljc`, `resources/seon/schemas/seon.program.edn` and
`src/seon/schema.clj` remain held (program-read and admission-provenance work).
Do not edit those foreign hunks.
The requested `seon.render-test` namespace does not exist at this HEAD;
use `seon.render.entity-pairs-test` for the render-pair regression surface.

**Tier 1 landed after review:** render-target and preparation-aware arity
checks are enforced at final report admission; required namespace/task refs
have canonical refusal regressions. The earlier
[boundary note](../../context-generation/research/reset-tier1-admission-boundary-2026-09-17.md)
records the rejected raw-count attempt, not the current implementation.
The seven-namespace run completed 189 tests / 1,832 assertions with 17
failures, all from the known stored-versus-pulled grammar assertion in one
schema regression. The other 165 tests passed. The clean schema test now
asserts authored-key preservation and the installed cardinality-many vector
grammar, retaining row admission and reference-target checks; it no longer
claims a stored schema validates a wildcard pull. The focused schema rerun passed 24 tests / 632 assertions, zero failures
and errors, with unchanged production bytes. Group 1's derived reader contracts remain incomplete and the
[existing issue](../../../seon/issues/wildcard-pulled-collections-do-not-satisfy-entity-set-contracts.md)
stays open. This checkpoint was reviewed and accepted at `5a5359205`; the resumed edge batch and its writer-cost prerequisite are recorded below.
Tier 1 needs no reset; the edge retype still does. The current
[implementation note](../../context-generation/research/reset-tier1-prepared-admission-2026-09-17.md)
records the exact test boundary and live observation.


## Previous review correction — superseded on deletion by J/K/X above

Read [the high-effort evaluation/deletion review](../research/design-review-eval-path-and-deletion-contract-2026-09-17.md)
end to end, including both designs, every N1–N11 writer verification, both
reproducible probes and its failed-gate boundary. Label **H** below names that
review. H superseded the original Q1 alternatives; J/K/X and the current owner
direction now supersede H Design 2. Q2 option A remains the requested direction
once ruled; no AST deletion is authorized.

Authorized now: released/unowned derived pull forms, plain digest format,
G5 groundwork and released group 4/5 seams. G5 ownership passes to this
integrator unless the edges continuation reports otherwise; its bridge/schema
files are currently held, so ownership does not authorize overlapping edits.
The previously proposed Datahike expanded-deletion report extension is removed
from this reset's prerequisites. The authorized value-edge refusal needs no
fork change. The capture-history seam landed separately at `79c106925`.

Explicit held files at resumption: config.clj, schedule.clj, sci/eval.clj,
cluster.clj, cluster/source.clj, schema/edn.clj, schema/datahike.clj,
render/transcript.clj, search.clj, bootstrap_drive.clj, instrument.clj,
test/selection.clj, test/runner.clj, cluster/wake.clj, bin/test, bin/_test-slot
and their modified tests; prompt.clj and repl.clj remain assignment-held even
if clean. Recheck git status before every edit, and wait for an explicit
release of assignment-held files. Never operate the other sessions.

## Evidence and precedence

Read in the requested order: AGENTS.md §0–§3 and §5–§7, the
[Datahike skill](../../../../.claude/skills/datahike/SKILL.md), then each of the
following named sections end to end (whole documents where no section is
specified). These short labels identify source findings in the tables:

- **P**: [program-facts PRD](program-facts-are-the-runtime-prd-2026-09-17.md),
  §1f G1–G6 and §4 S2.
- **E**: [approved edges plan](../research/edges-are-symbols-plan-2026-09-16.md),
  including approval and implementation notes; its §7 continuation remains owned.
- **R**: [schema design review](../research/schema-design-review-2026-09-17.md),
  all N1–N44, B.1–B.12, C.1–C.6 and D.1–D.5.
- **A**: [schema-key audit](../research/schema-key-audit-2026-09-16.md),
  complete “Reset edit list grouped by resource” and shared writer changes.
- **S**: [symbols inventory](../research/symbols-everywhere-inventory-2026-09-17.md),
  end to end.
- **D**: [Datahike deletion study](../research/datahike-deletion-and-the-program-graph-2026-09-16.md),
  §8 including §8.1.

The data-modeling and data-oriented-clojure skills also ground this plan.
Source probe HEAD: `0c7711e590397182c5860326c6599a6ad7848416`.
Source line anchors are dated evidence, not claims about future line numbers.
The shared tree contains foreign edits in config, schedule, SCI evaluation,
schema bridge, source publication, instrumentation, runner/selection and tests.
None is changed here. Ownership comes from the assignment, not inferred from
whether a file happened to be clean. No other lane was messaged or operated.

`bin/seon status`: default PID 41413 alive; no orphan JVMs. MCP runtime status
answered: three plumbing procs replied; 2 error signatures, 11 errored
evaluations, 95 failed tests, 3 stale Vars. These are inherited observations,
not attribution or a green baseline. No new live evaluation, mutation, gate,
publication, browser observation or reset was performed for this plan.

### Contradictions resolved for review

| Conflict | Resolution and reason |
|---|---|
| A/D retired-tx and referenced-tx versus P G1/G3 | **P wins.** No retirement attribute, identity stub, synthetic historical event or second validator. Retraction plus history is the model. |
| S2's required calls key/empty-set acceptance versus P G4 | **G4 and approved E §1 win.** Require analyzed-source-digest; construct all computed relations, but normalize absent many-datoms to logical empty sets only under positive analysis evidence. Missing provenance refuses at the final writer, including native datoms and transaction-function output. |
| P G4's file/evaluation examples versus E's single digest | **Approved E wins the concrete encoding.** Digest exact analyzed input on both seams; retain admission source separately. Do not require a ref to a compactable evaluation. A digest is source identity, not a claim of analyzer version or resolver-environment completeness. |
| E/S retain subject/pending-subject, capability refs and schedule refs versus R N7/N9/N10 | **H N7/N9/N10 wins.** Subject and scheduled handler become symbols; Owner correction: delete declaration fn/capability-fn; its existing handler symbol joins the strict deletion refusal set. The historical effect ref may go only after exact dispatched-handler symbol is handed to and persisted by its writer. Held consumers remain boundaries. |
| S says citation/error/renderer refs are already correct versus R N5/N6/N13 | **R wins.** These record names and historical observations. Keep token values and derive resolution; remove redundant refs. Error citation tokens are signature strings, not function symbols. |
| R N15 suggests definition digest proves recorded reach | **E §3 and G4 win.** Definition analysis and reach computation are different events. Keep reach-digest required conditionally on a successfully recorded closure. Delete reach-unknown only when failed/absent reach is represented as a typed refusal and cannot record a green run. Definition digest alone must never imply computed-empty reach. |
| R N17 globally requires block/name versus A's two contribution constructors | **A's constructor evidence wins.** Require name/hash/tokens for capture contributions; append contributions require agent and retain their distinct selection shape. Do not break append-tx or infer its shape from an empty evaluations set. |
| A/S retype fn.ast/type versus R C.2 | **Q2 controls.** Do not spend work repairing a family scheduled for removal/merge. Both options remove the old family; provenance replaces its analysis-presence test. |
| S says delete search :symbol tags versus S §1.3's correction | **S §1.3 wins.** Preserve all identifier tokenizer properties, including the keyword identity site. |
| R N27 says binding shape derives from children/entries | Empty sequential and map bindings have no member datoms. **G4 wins over that derivation.** Delete the redundant shape only after deriving from the required stored binding/form through the Clojure reader, including `{}` and `[]`; never use collection presence as the replacement. |
| R C.1/N12 origin assumptions versus H rollout inventory | **K and the current owner direction win.** Historical origins need an explicit identity carrier; required/optional refs keep their existing dial. Audit transaction/effect provenance per family; no universal enforcement and no cascading transaction entities. |
| R D.2 superseded-by ref versus R C.1 name mentions | Use a superseded-by **issue-id value**, not a dangling name-ref; it is a historical statement that must survive target removal. Resolution is a query. |
| R N38/N39 all instants become tx refs | Use tx refs for database transitions only; retain actual occurrence/provider event instants and imported issue opening dates as observations with distinct semantics. Never reinterpret an old date as the transaction that imported it. |
| R D.4 calls nil-enum removal accretion | It narrows a boundary; **AGENTS §2.5 wins**. Treat wake offer-result as a contract-only narrowing with all callers changed (X2), not a reset-gated storage retype. |
| R B.4 says four AST resources; C.2 says two | At this HEAD the targeted resources are `seon.fn.ast.edn` and `seon.fn.ast.entry.edn`, plus the attribute in `seon.fn.edn`. Q2 records the actual grep; no invented deletion paths. |
| S reset --force versus this assignment's exact default sequence | **Assignment wins.** Only the orchestrator executes the four-command default sequence below. Do not down unrelated clusters or delete the shared store. |
| R B.12 render/form versus current vocabulary | Retire form as a third output selector; render functions choose executable forms within their existing AI/HTML pair. Change the ten declaring schema sites and walk consumers before deleting the key. Preserve rendered HTML under its own Hiccup value key, not a string-only output slot. |

### Dependency ledger

| Dependency mechanism | Pinned source and first-party seam | Consequence |
|---|---|---|
| Datahike incoming-ref sweep and component cascade | Gitlink `73afe78271a289861da236c5ac3457e64349653f`; `reference-code/datahike/src/datahike/db/transaction.cljc:998–1015`, `:831–836`; `seon.fn/reconcile-tx-in`, `seon.turn/row-tx` | Convert name edges and install Q1 before enabling entity deletion on the new population. |
| Transaction functions and atomic report validation | Same dependency `transaction.cljc:1153–1154`, `:1206–1215`, `:1276`; `src/seon/db.clj:3009` write-report-error, `:3141` retain-transaction, `:3176` transact-call | One writer-time before/after decision, including expanded transaction functions; no client pre-read or duplicate transaction simulation. |
| Empty many-values and pull component expansion | Same dependency `transaction.cljc:739–770`; `pull_api.cljc:345–357`; `seon.db/write-entity-value` | Positive evidence plus logical empty sets; schema-derived complete component traversal, including beyond pull's default 1,000 limit. |
| Malli registry and derived map contracts | Gitlink `3517a3cd9271b2083780ac7be1725493905bca2e`; `reference-code/malli/src/malli/util.cljc`; `seon.schema` projection and `seon.schema.datahike` codec | Derive reader forms from entity plus selector, not a parallel hand-authored map. Reuse the existing union codec. |

## Publication order and ownership

A group is a coherent **publication unit**, not a suggestion to expose an EDN
edit before the matching loaded consumers. Prepare changes together in an
isolated HEAD-plus-owned-paths snapshot when necessary. If a declared group
shares changed consumers with another, combine their publication; do not ship
an intermediate string/ref/symbol mixture. The implementation commits may be
smaller review slices, but an incompatible publication waits for its whole
unit. Default must not be claimed to run the new incompatible population before the orchestrator's
single final refork; safe compatible adoptions may still occur. Refusal to adopt an incompatible schema is RESET NEEDED,
not permission to reset or to weaken contracts.

| Order / group | Atomic contents and dependency | Ownership at assignment |
|---|---|---|
| 0 — landed prerequisite | Platform declaration, fixture exclusion and runner partition. Preserve these while changing program identity/result schemas. | **Landed** `f54771e84` (test schema/program), `8d4b3689f` (runner/gates); inspected commit stats. No reimplementation. |
| 1 — writer and declaration foundation | First remove measured publication overhead while retaining final-report authority: reuse attribute validation/codec/normalization plans and format index identity sort keys once; prove complete publication timing and incomplete-create refusal. Then Q1 program value-edge check (authorized, combined with group 2 retype), complete G5 parent validation, canonical stored/selector-derived forms, plain digest format. Constructors of newly checked components travel here. No deletion enabled before this unit accepts valid writes and rejects invalid native/expanded writes. | **Integrator owns G5 groundwork** unless E reports otherwise; bridge/schema overlaps remain held. **Authorized:** Q1 program deletion refusal; no universal ref rule. Derived forms/digest are authorized only on released files; source digest is currently modified. |
| 2 — program names and provenance | All symbol identities and aliases, indexed calls/references/reach/writes/schema references/arity references, source digest and required derivables, full constructors, every graph reader and emitted form. Subject and capability changes extend E. Name-valued schedule/citation readers that join program identities travel in this same publication. Includes canonical fixtures. | **Integrator now owns the released fn/program/db and schema seam.** Held source/selection/SCI consumers must land or be released in the same coherent unit. **No-default-cluster lane owns config/schedule/sci-eval edits.** R extensions are **unowned**, to integrate only after owner release; never assume its schedule work already implements N9. |
| 3 — entity deletion | Reconcile and ns-unmap/schema removal use retractEntity; delete tombstone fallback/minting/pending resolution after groups 1–2; preserve test result evidence as values; deleted result subject refuses. Issue deletion keeps existing retention rules. | **Integrator owns released deletion/refusal work.** Held source/SCI publication seams remain boundaries; no tombstone removal before their value consumers land. |
| 4 — lifecycle and names beyond graph | Evaluation consolidation/read observation; test result/run/failure derivation; error signature/occurrence cleanup; typed issue citations/status transitions; listeners excluded by current assignment; agent/plan/import required constructors. Requires 1–2 and coherent ownership release. | **Unowned**, with held `sci/eval`, schedule/config, runner and turn seams coordinated through the orchestrator. |
| 5 — component values and remaining families | Literal codec consolidation, maintenance root/components and transition names, provider descriptor fields, render pair output split, capture requirements, wake enum. Run consumers with narrowed declarations in each publication. | **Unowned**; no-default-cluster config/schedule work remains protected; R provider/render extensions are not implicitly covered by it. |
| 6 — AST decision and final cleanup | Q2 replaces reconciliation/backfill reads first, removes old AST writers/attributes/resources last. Remove stale mirrors/docs and verify no old lookup/string/ref consumer remains. | **Unowned**, E names fn/program/turn so wait for explicit release. |
| 7 — one reset and cold proof | All group iteration evidence collected; orchestrator publishes/reforks once, reseeds, converges, runs namespace cold gates and live proofs. | **Orchestrator only**. This integrator never runs reset or bin/test in step 2. |

Groups 1–2 may need one combined publication because required component
validation and identity types reach the same constructors. Group 3 cannot run
against a half-converted population. In particular, already-landed issue
retraction is not evidence that every historical mention and coherent deletion caller has been converted.

The resource tables below are the edit inventory; the group column gives its
only ordering. `add` includes declaration/docstring additions without changing
stored data. Where a row has several operations they are spelled individually
in kind. Every new required obligation applies to the **resulting entity**, not
every patch map. All regressions use the canonical database fixture, real SCI
when relevant, and armed contracts; absence of a subject is a failure, never a
vacuous pass. A's per-resource optional-key lists are retained below after
removing keys superseded by these decisions.

## Resource edit tables

### `resources/seon/schemas/my.plan.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 1 | Audit actual storable refs by their writer: components cascade; required refs refuse through existing whole-entity validation; optional refs may deliberately sweep. Historical tokens become identity values. Document the chosen behavior; no universal enforcement or fork extension (K/X). | R C.1; Q1 | add | `seon.agent/update-settings-call` (`src/seon/agent.clj:40`); `seon.cluster.agent/creation-tx` (`src/seon/cluster/agent.clj:122`); `seon.plan/add-step-call` (`src/seon/plan.clj:516`); `seon.plan/compile-tree` (`src/seon/plan.clj:1028`); `seon.plan/completion-tx` (`src/seon/plan.clj:702`); `seon.plan/start-step-call` (`src/seon/plan.clj:811`) | seon.plan, my.plan and agent plan rendering | seon.db-test: installed-ref census distinguishes stored refs from API forms; required-ref sweep refuses, optional sweep remains intentional, components validate with parent |
| 4 | Add absence-condition docstrings for surviving optional entries: `:my.plan/current-step`, `:my.plan/objective`, `:my.plan/steps`, `:seon.agent/plan`. Keep optionality unless the specific required/transition row above changes it; for renamed entries apply the condition to the replacement. | A my.plan reset row | add | `seon.agent/update-settings-call` (`src/seon/agent.clj:40`); `seon.cluster.agent/creation-tx` (`src/seon/cluster/agent.clj:122`); `seon.plan/add-step-call` (`src/seon/plan.clj:516`); `seon.plan/compile-tree` (`src/seon/plan.clj:1028`); `seon.plan/completion-tx` (`src/seon/plan.clj:702`); `seon.plan/start-step-call` (`src/seon/plan.clj:811`) | seon.plan, my.plan and agent plan rendering | my.plan-test: canonical constructor succeeds; legal absent lifecycle fields remain absent and malformed final entity refuses |

### `resources/seon/schemas/seon.activation.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 1 | Retain required scalar/one-valued entries; cardinality-many membership is optional with positive construction evidence; add explicit component/empty-collection and per-family deletion-behavior docstrings where applicable. No identity invented for observation maps. Validate actual constructor result through the canonical parent/selector; absence is not evidence of coverage. | A seon.activation reset row; P G5 | add | `seon.cluster.source/activation-seal-tx` (`src/seon/cluster/source.clj:223`); `seon.cluster/derive-activation` (`src/seon/cluster.clj:1346`) | seon.cluster acquisition and activation queries | seon.source-reconciliation-test: actual canonical constructor accepted, malformed required value refused; child-only update checks owning root |

### `resources/seon/schemas/seon.activation.lookup.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 1 | Retain required scalar/one-valued entries; cardinality-many membership is optional with positive construction evidence; add explicit component/empty-collection and per-family deletion-behavior docstrings where applicable. No identity invented for observation maps. Validate actual constructor result through the canonical parent/selector; absence is not evidence of coverage. | A seon.activation.lookup reset row; P G5 | add | `seon.cluster.source/activation-seal-tx` (`src/seon/cluster/source.clj:223`); `seon.cluster/activation-lookup-row` (`src/seon/cluster.clj:1230`) | seon.cluster activation lookup resolution | seon.source-reconciliation-test: actual canonical constructor accepted, malformed required value refused; child-only update checks owning root |

### `resources/seon/schemas/seon.blob.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 1 | Alias digest to the canonical identity-free digest format while retaining its own blob semantics. | R N35 | retype | seon.blob digest/staging; schema bridge | blob lookup and effect/error readers | seon.schema-test: same exact 64-hex constraint through all digest aliases |

### `resources/seon/schemas/seon.call-preparation.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 1 | Retain required scalar/one-valued entries; cardinality-many membership is optional with positive construction evidence; add explicit component/empty-collection and per-family deletion-behavior docstrings where applicable. No identity invented for observation maps. Validate actual constructor result through the canonical parent/selector; absence is not evidence of coverage. | A seon.call-preparation reset row; P G5 | add | `seon.config/default-population` (`src/seon/config.clj:300`); `seon.config/population-transaction-data` (`src/seon/config.clj:422`) | seon.call-preparation suppliers and SCI calls | seon.call-preparation-test: actual canonical constructor accepted, malformed required value refused; child-only update checks owning root |

### `resources/seon/schemas/seon.cluster.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 4 | G4 correction: make cluster instructions/toolkit collection entries optional in the stored cluster map. Preserve required cluster/config identity as construction provenance; an authored empty collection stores no datom. Do not infer configuration acquisition from member presence. | required-many blocker (seal triage 543f03258); P G4 | required | cluster constructors and config acquisition | cluster readers normalize absent members only from acquired config provenance | canonical cluster with empty instructions/toolkit admits; incomplete cluster without required config refuses |
| 1 | Retain required scalar/one-valued entries; cardinality-many membership is optional with positive construction evidence; add explicit component/empty-collection and per-family deletion-behavior docstrings where applicable. No identity invented for observation maps. Validate actual constructor result through the canonical parent/selector; absence is not evidence of coverage. | A seon.cluster reset row; P G5 | add | `seon.cluster/ensure-cluster-entity!` (`src/seon/cluster.clj:2492`) | seon.operator and cluster custody/acquisition | seon.db-test: actual canonical constructor accepted, malformed required value refused; child-only update checks owning root |
| 1 | Audit actual storable refs by their writer: components cascade; required refs refuse through existing whole-entity validation; optional refs may deliberately sweep. Historical tokens become identity values. Document the chosen behavior; no universal enforcement or fork extension (K/X). | R C.1; Q1 | add | `seon.cluster/ensure-cluster-entity!` (`src/seon/cluster.clj:2492`) | seon.operator and cluster custody/acquisition | seon.db-test: installed-ref census distinguishes stored refs from API forms; required-ref sweep refuses, optional sweep remains intentional, components validate with parent |

### `resources/seon/schemas/seon.cluster.instruction.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 1 | Retain required scalar/one-valued entries; cardinality-many membership is optional with positive construction evidence; add explicit component/empty-collection and per-family deletion-behavior docstrings where applicable. No identity invented for observation maps. Validate actual constructor result through the canonical parent/selector; absence is not evidence of coverage. | A seon.cluster.instruction reset row; P G5 | add | `seon.cluster.instruction/seed-rows` (`src/seon/cluster/instruction.clj:59`); `seon.config/default-population` (`src/seon/config.clj:300`) | seon.bootstrap instruction rendering | seon.schema-test: actual canonical constructor accepted, malformed required value refused; child-only update checks owning root |

### `resources/seon/schemas/seon.config.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 1 | Retain required scalar/one-valued entries; cardinality-many membership is optional with positive construction evidence; add explicit component/empty-collection and per-family deletion-behavior docstrings where applicable. No identity invented for observation maps. Validate actual constructor result through the canonical parent/selector; absence is not evidence of coverage. | A seon.config reset row; P G5 | add | `seon.agent/update-settings-call` (`src/seon/agent.clj:40`); `seon.cluster.agent/creation-tx` (`src/seon/cluster/agent.clj:122`) | seon.config/effective, agent overlays and environment acquisition | seon.config-test: actual canonical constructor accepted, malformed required value refused; child-only update checks owning root |

### `resources/seon/schemas/seon.db.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 1 | No classification property or fork change. In the existing final report, refuse deleted program identities still named by surviving value obligations in db-after (Q1/J). Separate transaction ref grammar from selector-derived pull forms; remove fake-0 validation and temporary widened ref form only with the replacement. | P G3/G5; E §4; A db/shared writers; R C.1 | add; retype; delete | seon.db/write-report-error, retain-transaction, write-entity-value, write-value; seon.schema.datahike bridge | Every seon.db pull/transaction boundary and schema-derived validator | seon.db-test: map/datom/nested db.fn/call deletion, sibling rollback, numeric dangling target, both operation orders, >1,000 components, child-only edits and missing root |

### `resources/seon/schemas/seon.dev.mcp.artifact.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 1 | Retain required scalar/one-valued entries; cardinality-many membership is optional with positive construction evidence; add explicit component/empty-collection and per-family deletion-behavior docstrings where applicable. No identity invented for observation maps. Validate actual constructor result through the canonical parent/selector; absence is not evidence of coverage. | A seon.dev.mcp.artifact reset row; P G5 | add | `seon.cluster/mcp-project` (`src/seon/cluster.clj:338`) | MCP artifact page/value reader | seon.schema-test: actual canonical constructor accepted, malformed required value refused; child-only update checks owning root |

### `resources/seon/schemas/seon.fn.binding.child.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 1 | Declare child ownership/child form; retain role as reader grammar (:element/:rest/:as), with docstring; retain required ordinal. | A child; E §4; R N27 | add | seon.program/binding-child | parent component validator and binding reader | seon.program-test: malformed nested child refuses; legitimate empty binding passes |

### `resources/seon/schemas/seon.issue.citation.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 1 | Audit actual storable refs by their writer: components cascade; required refs refuse through existing whole-entity validation; optional refs may deliberately sweep. Historical tokens become identity values. Document the chosen behavior; no universal enforcement or fork extension (K/X). | R C.1; Q1 | add | `seon.issue/adopt-tx` (`src/seon/issue.clj:713`); `seon.issue/file-citations` (`src/seon/issue.clj:210`); `seon.issue/index-tx` (`src/seon/issue.clj:262`) | seon.issue note/file citation rendering | seon.db-test: installed-ref census distinguishes stored refs from API forms; required-ref sweep refuses, optional sweep remains intentional, components validate with parent |
| 4 | Add absence-condition docstrings for surviving optional entries: `:seon.issue.citation/end-row`, `:seon.issue.citation/row`. Keep optionality unless the specific required/transition row above changes it; for renamed entries apply the condition to the replacement. | A seon.issue.citation reset row | add | `seon.issue/adopt-tx` (`src/seon/issue.clj:713`); `seon.issue/file-citations` (`src/seon/issue.clj:210`); `seon.issue/index-tx` (`src/seon/issue.clj:262`) | seon.issue note/file citation rendering | seon.issue-deletion-test: canonical constructor succeeds; legal absent lifecycle fields remain absent and malformed final entity refuses |

### `resources/seon/schemas/seon.lint.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 1 | Audit actual storable refs by their writer: components cascade; required refs refuse through existing whole-entity validation; optional refs may deliberately sweep. Historical tokens become identity values. Document the chosen behavior; no universal enforcement or fork extension (K/X). | R C.1; Q1 | add | `seon.fn/artifact` (`src/seon/fn.clj:1217`); `seon.fn/lint-rows` (`src/seon/fn.clj:1171`) | seon.fn lint reconciliation and issue detectors | seon.db-test: installed-ref census distinguishes stored refs from API forms; required-ref sweep refuses, optional sweep remains intentional, components validate with parent |
| 4 | Add absence-condition docstrings for surviving optional entries: `:seon.lint/fn`, `:seon.schema.admission/source`. Keep optionality unless the specific required/transition row above changes it; for renamed entries apply the condition to the replacement. | A seon.lint reset row | add | `seon.fn/artifact` (`src/seon/fn.clj:1217`); `seon.fn/lint-rows` (`src/seon/fn.clj:1171`) | seon.fn lint reconciliation and issue detectors | seon.fn-test: canonical constructor succeeds; legal absent lifecycle fields remain absent and malformed final entity refuses |

### `resources/seon/schemas/seon.maintenance.request.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 1 | Audit actual storable refs by their writer: components cascade; required refs refuse through existing whole-entity validation; optional refs may deliberately sweep. Historical tokens become identity values. Document the chosen behavior; no universal enforcement or fork extension (K/X). | R C.1; Q1 | add | `seon.schedule/fire-call` (`src/seon/schedule.clj:313`); `seon.schedule/request-entity` (`src/seon/schedule.clj:278`) | seon.maintenance operations and schedule execution | seon.db-test: installed-ref census distinguishes stored refs from API forms; required-ref sweep refuses, optional sweep remains intentional, components validate with parent |
| 4 | Add absence-condition docstrings for surviving optional entries: `:seon.config.maintenance/log-max-bytes`, `:seon.config.maintenance/log-retained-files`, `:seon.config.maintenance/min-usable-bytes`, `:seon.config.maintenance/min-usable-ratio`, `:seon.config.operator/event-silence-backstop-ms`. Keep optionality unless the specific required/transition row above changes it; for renamed entries apply the condition to the replacement. | A seon.maintenance.request reset row | add | `seon.schedule/fire-call` (`src/seon/schedule.clj:313`); `seon.schedule/request-entity` (`src/seon/schedule.clj:278`) | seon.maintenance operations and schedule execution | seon.maintenance-schema-test: canonical constructor succeeds; legal absent lifecycle fields remain absent and malformed final entity refuses |

### `resources/seon/schemas/seon.ns.alias.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 1 | Retain required scalar/one-valued entries; cardinality-many membership is optional with positive construction evidence; add explicit component/empty-collection and per-family deletion-behavior docstrings where applicable. No identity invented for observation maps. Validate actual constructor result through the canonical parent/selector; absence is not evidence of coverage. | A seon.ns.alias reset row; P G5 | add | `seon.fn/namespace-row` (`src/seon/fn.clj:293`); `seon.program/canonical-namespace-components` (`src/seon/program.cljc:877`); `seon.sci.eval/binding-rows` (`src/seon/sci/eval.clj:529`) | SCI namespace alias acquisition | seon.program-test: actual canonical constructor accepted, malformed required value refused; child-only update checks owning root |

### `resources/seon/schemas/seon.ns.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 1 | Audit actual storable refs by their writer: components cascade; required refs refuse through existing whole-entity validation; optional refs may deliberately sweep. Historical tokens become identity values. Document the chosen behavior; no universal enforcement or fork extension (K/X). | R C.1; Q1 | add | `seon.cluster.agent/creation-tx` (`src/seon/cluster/agent.clj:122`); `seon.cluster.agent/steward-call` (`src/seon/cluster/agent.clj:97`); `seon.cluster.source/mintable-identity` (`src/seon/cluster/source.clj:305`); `seon.fn/namespace-row` (`src/seon/fn.clj:293`); `seon.program/declaration-row` (`src/seon/program.cljc:906`); `seon.sci.eval/binding-rows` (`src/seon/sci/eval.clj:529`); `seon.test.runner/record-tx` (`src/seon/test/runner.clj:2095`) | SCI acquisition, namespace render and stewardship queries | seon.db-test: installed-ref census distinguishes stored refs from API forms; required-ref sweep refuses, optional sweep remains intentional, components validate with parent |
| 4 | Add absence-condition docstrings for surviving optional entries: `:seon.ns/aliases`, `:seon.ns/context-relevant?`, `:seon.ns/doc`, `:seon.ns/imports`, `:seon.ns/refers`, `:seon.ns/requires`, `:seon.ns/source`, `:seon.ns/steward`, `:seon.schema.admission/source`. Keep optionality unless the specific required/transition row above changes it; for renamed entries apply the condition to the replacement. | A seon.ns reset row | add | `seon.cluster.agent/creation-tx` (`src/seon/cluster/agent.clj:122`); `seon.cluster.agent/steward-call` (`src/seon/cluster/agent.clj:97`); `seon.cluster.source/mintable-identity` (`src/seon/cluster/source.clj:305`); `seon.fn/namespace-row` (`src/seon/fn.clj:293`); `seon.program/declaration-row` (`src/seon/program.cljc:906`); `seon.sci.eval/binding-rows` (`src/seon/sci/eval.clj:529`); `seon.test.runner/record-tx` (`src/seon/test/runner.clj:2095`) | SCI acquisition, namespace render and stewardship queries | seon.program-test: canonical constructor succeeds; legal absent lifecycle fields remain absent and malformed final entity refuses |

### `resources/seon/schemas/seon.ns.refer.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 1 | Retain required scalar/one-valued entries; cardinality-many membership is optional with positive construction evidence; add explicit component/empty-collection and per-family deletion-behavior docstrings where applicable. No identity invented for observation maps. Validate actual constructor result through the canonical parent/selector; absence is not evidence of coverage. | A seon.ns.refer reset row; P G5 | add | `seon.fn/namespace-row` (`src/seon/fn.clj:293`); `seon.program/canonical-namespace-components` (`src/seon/program.cljc:877`); `seon.sci.eval/binding-rows` (`src/seon/sci/eval.clj:529`) | SCI namespace refer acquisition | seon.program-test: actual canonical constructor accepted, malformed required value refused; child-only update checks owning root |

### `resources/seon/schemas/seon.operator.log.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 1 | Retain required scalar/one-valued entries; cardinality-many membership is optional with positive construction evidence; add explicit component/empty-collection and per-family deletion-behavior docstrings where applicable. No identity invented for observation maps. Validate actual constructor result through the canonical parent/selector; absence is not evidence of coverage. | A seon.operator.log reset row; P G5 | add | `seon.operator/rotate-logs!` (`src/seon/operator.clj:544`) | operator maintenance log consumers | seon.maintenance-test: actual canonical constructor accepted, malformed required value refused; child-only update checks owning root |

### `resources/seon/schemas/seon.runtime.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 1 | Audit actual storable refs by their writer: components cascade; required refs refuse through existing whole-entity validation; optional refs may deliberately sweep. Historical tokens become identity values. Document the chosen behavior; no universal enforcement or fork extension (K/X). | R C.1; Q1 | add | `seon.cluster.agent/creation-tx` (`src/seon/cluster/agent.clj:122`); `seon.issue/start-tx` (`src/seon/issue.clj:885`); `seon.turn/open-call` (`src/seon/turn.clj:351`); `seon.turn/record-evaluated-call` (`src/seon/turn.clj:1569`) | agent arming, seon.turn and wake matching | seon.db-test: installed-ref census distinguishes stored refs from API forms; required-ref sweep refuses, optional sweep remains intentional, components validate with parent |
| 4 | Add absence-condition docstrings for surviving optional entries: `:seon.runtime/listens`, `:seon.runtime/trigger`, `:seon.runtime/turns`. Keep optionality unless the specific required/transition row above changes it; for renamed entries apply the condition to the replacement. | A seon.runtime reset row | add | `seon.cluster.agent/creation-tx` (`src/seon/cluster/agent.clj:122`); `seon.issue/start-tx` (`src/seon/issue.clj:885`); `seon.turn/open-call` (`src/seon/turn.clj:351`); `seon.turn/record-evaluated-call` (`src/seon/turn.clj:1569`) | agent arming, seon.turn and wake matching | seon.turn-loop-test: canonical constructor succeeds; legal absent lifecycle fields remain absent and malformed final entity refuses |

### `resources/seon/schemas/seon.schedule.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 1 | Retain required scalar/one-valued entries; cardinality-many membership is optional with positive construction evidence; add explicit component/empty-collection and per-family deletion-behavior docstrings where applicable. No identity invented for observation maps. Validate actual constructor result through the canonical parent/selector; absence is not evidence of coverage. | A seon.schedule reset row; P G5 | add | `seon.config/default-population` (`src/seon/config.clj:300`); `seon.schedule/root-maintenance-seed-call` (`src/seon/schedule.clj:72`) | seon.schedule next/fire logic | seon.schedule-test: actual canonical constructor accepted, malformed required value refused; child-only update checks owning root |
| 1 | Audit actual storable refs by their writer: components cascade; required refs refuse through existing whole-entity validation; optional refs may deliberately sweep. Historical tokens become identity values. Document the chosen behavior; no universal enforcement or fork extension (K/X). | R C.1; Q1 | add | `seon.config/default-population` (`src/seon/config.clj:300`); `seon.schedule/root-maintenance-seed-call` (`src/seon/schedule.clj:72`) | seon.schedule next/fire logic | seon.db-test: installed-ref census distinguishes stored refs from API forms; required-ref sweep refuses, optional sweep remains intentional, components validate with parent |

### `resources/seon/schemas/seon.schedule.fire.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 1 | Retain required scalar/one-valued entries; cardinality-many membership is optional with positive construction evidence; add explicit component/empty-collection and per-family deletion-behavior docstrings where applicable. No identity invented for observation maps. Validate actual constructor result through the canonical parent/selector; absence is not evidence of coverage. | A seon.schedule.fire reset row; P G5 | add | `seon.schedule/fire-call` (`src/seon/schedule.clj:313`) | seon.schedule execution/recovery | seon.schedule-test: actual canonical constructor accepted, malformed required value refused; child-only update checks owning root |
| 1 | Audit actual storable refs by their writer: components cascade; required refs refuse through existing whole-entity validation; optional refs may deliberately sweep. Historical tokens become identity values. Document the chosen behavior; no universal enforcement or fork extension (K/X). | R C.1; Q1 | add | `seon.schedule/fire-call` (`src/seon/schedule.clj:313`) | seon.schedule execution/recovery | seon.db-test: installed-ref census distinguishes stored refs from API forms; required-ref sweep refuses, optional sweep remains intentional, components validate with parent |

### `resources/seon/schemas/seon.source.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 1 | Make source/digest a format without inherited identity; place uniqueness only on genuine identified uses (including the source entity's digest identity); delete false identity overrides at run/reach/signature uses. Preserve the existing hex constraint, no new regex. | R N35/N36 | retype; add; delete | seon.schema.datahike attribute projection; seon.cluster.source/source rows; seon.config population | seon.source, activation and run/reach readers | seon.schema-test: identical digests can belong to distinct nonidentity rows; source identity still upserts; nonhex digest refuses |

### `resources/seon/schemas/seon.turn.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 1 | Audit actual storable refs by their writer: components cascade; required refs refuse through existing whole-entity validation; optional refs may deliberately sweep. Historical tokens become identity values. Document the chosen behavior; no universal enforcement or fork extension (K/X). | R C.1; Q1 | add | `seon.turn/close-call` (`src/seon/turn.clj:397`); `seon.turn/open-call` (`src/seon/turn.clj:351`); `seon.turn/plan-call` (`src/seon/turn.clj:574`); `seon.turn/record-attempt!` (`src/seon/turn.clj:3947`); `seon.turn/record-evaluated-call` (`src/seon/turn.clj:1569`); `seon.turn/recover-call` (`src/seon/turn.clj:1803`); `seon.turn/refresh-call` (`src/seon/turn.clj:794`); `seon.turn/system-run-call` (`src/seon/turn.clj:655`) | turn loop, recovery, context and history rendering | seon.db-test: installed-ref census distinguishes stored refs from API forms; required-ref sweep refuses, optional sweep remains intentional, components validate with parent |
| 4 | Add absence-condition docstrings for surviving optional entries: `:seon.turn.work/situation`, `:seon.turn/attempts`, `:seon.turn/closed-tx`, `:seon.turn/disposition`, `:seon.turn/reply`, `:seon.turn/reply-blob`, `:seon.turn/reply-size`, `:seon.turn/starting-ns`, `:seon.turn/trigger`. Keep optionality unless the specific required/transition row above changes it; for renamed entries apply the condition to the replacement. | A seon.turn reset row | add | `seon.turn/close-call` (`src/seon/turn.clj:397`); `seon.turn/open-call` (`src/seon/turn.clj:351`); `seon.turn/plan-call` (`src/seon/turn.clj:574`); `seon.turn/record-attempt!` (`src/seon/turn.clj:3947`); `seon.turn/record-evaluated-call` (`src/seon/turn.clj:1569`); `seon.turn/recover-call` (`src/seon/turn.clj:1803`); `seon.turn/refresh-call` (`src/seon/turn.clj:794`); `seon.turn/system-run-call` (`src/seon/turn.clj:655`) | turn loop, recovery, context and history rendering | seon.turn-test: canonical constructor succeeds; legal absent lifecycle fields remain absent and malformed final entity refuses |

### `resources/seon/schemas/seon.effect.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 2 | Persist the exact admitted dispatched-handler symbol on the effect row, handed through from dispatch rather than re-read from the owner; only then delete its historical capability-fn ref. Update owner lookup refs to symbols and audit owner/evaluation historical provenance before enforcement. | S §2.7; R N10/J2 | delete; retype | seon.effect/open-call, request*, metadata joins | effect reach, render/capability lookup | seon.effect-test: handler deletion preserves named capability and produces typed unresolved result |
| 4 | Add absence-condition docstrings for surviving optional entries: `:seon.effect/content-blobs`, `:seon.effect/duration-ms`, `:seon.effect/eval`, `:seon.effect/file`, `:seon.effect/form-span`, `:seon.effect/notify`, `:seon.effect/program`, `:seon.effect/result-blob`, `:seon.effect/result-size`, `:seon.effect/to`. Keep optionality unless the specific required/transition row above changes it; for renamed entries apply the condition to the replacement. | A seon.effect reset row | add | `seon.effect/interrupt-call` (`src/seon/effect.clj:355`); `seon.effect/interruption-stamps` (`src/seon/effect.clj:388`); `seon.effect/open-call` (`src/seon/effect.clj:250`); `seon.effect/request*` (`src/seon/effect.clj:665`); `seon.effect/settle-call` (`src/seon/effect.clj:303`) | effect reach, render/capability lookup | seon.effect-test: handler deletion preserves named capability and produces typed unresolved result |
| 5 | request-edn/result-edn become request/result logical values under contracted unions and bridge codec; preserve bounded inline projection/full blob distinction and sizes. Rename receipt map to result; opened/settled/interrupted-at database transitions become corresponding -tx refs, retaining separately measured duration. content-blobs becomes set of attachment digests (writer evidence below). | A effect; R N19/N33/N39/B.11/J3/J7 | rename; retype; add | seon.effect/open-call, settle-call, interrupt-call, interruption-stamps, staged-result, settle-value! | effect await/recovery, blob GC, render/effect readers | seon.effect-test: actual handler results/errors and attachments round-trip; transition tx has instant; interruption terminal fact coherent |

### `resources/seon/schemas/seon.fn.arity.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 2 | G4 correction: arguments is optional in the stored arity row; zero arguments emits no member datom. Keep required argument-count/min plus the parent definition analyzed-source-digest as positive construction evidence; validate the component with its parent. | required-many blocker; P G4/G5 | required | seon.program/arity-row and all declaration constructors | call-preparation and arity reports | zero-argument declaration round-trips; missing required count/provenance refuses, not silently empty |
| 2 | input-refs/output-refs/guard-refs become indexed qualified-keyword sets; arity becomes nonnegative integer or :varargs, not printed EDN. Declare arguments child schema for G5; zero arguments valid under parent provenance. | A arity; R N4/N30; E §4 | retype; add | seon.program/arity-row, schema-references | seon.fn reach/arity reports; seon.call-preparation; schema/accretion queries | seon.program-test: fixed/variadic/zero arities round-trip; schema deletion preserves reference keyword; malformed argument refuses at parent path |
| 4 | Add absence-condition docstrings for surviving optional entries: `:seon.fn.arity/guard`, `:seon.fn.arity/guard-refs`, `:seon.fn.arity/guard-schema`, `:seon.fn.arity/input-refs`, `:seon.fn.arity/max`, `:seon.fn.arity/output-refs`. Keep optionality unless the specific required/transition row above changes it; for renamed entries apply the condition to the replacement. | A seon.fn.arity reset row | add | `seon.program/arity-row` (`src/seon/program.cljc:683`) | seon.fn reach/arity reports; seon.call-preparation; schema/accretion queries | seon.program-test: fixed/variadic/zero arities round-trip; schema deletion preserves reference keyword; malformed argument refuses at parent path |

### `resources/seon/schemas/seon.fn.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 2 | Retype sym, caller, callee and tuple callee to qualified-symbol; calls/references to indexed qualified-symbol sets; writes to indexed qualified-keyword set. Keep namespace/file/arities relations. Require function file with stub removal and every constructor (including program-fn-row) in this publication. Require source/arglists/private? and analyzed-source-digest. Logical empty calls/references/writes/keywords/call-arities derive only under provenance. Remove pending-calls and retyped uses of reference-to; DELETE declaration capability-fn ref under the latest owner ruling; its existing :seon.effect/capability symbol joins the refusal set; no retired-tx. | P G1–G4/S2; E §1–3; S §1.1; A fn; R N1/N2/N10 | retype; required; delete | seon.fn var-row/analyzed-form/reconcile-tx-in; seon.program; seon.sci.eval; seon.turn relation-assertions/row-tx | seon.fn gate-sets/reach/output graph; seon.test, runner, selection; seon.db sink reach; effect, bootstrap, run, render/ns/test, issue/detect; all S §2 sites | seon.fn-test, seon.test-reaching-test: nonempty actual symbol datoms, tuple schema, deleting B with surviving A refuses atomically; repairing A in the same transaction succeeds; stale reach is reported, redefinition preserves identity; unknown analyzer namespace is not minted as an unreadable symbol |
| 4 | Add absence-condition docstrings for surviving optional entries: `:seon.effect/capability`, `:seon.fn/arglists-override?`, `:seon.fn/arities`, `:seon.fn/call-arities`, `:seon.fn/destroys`, `:seon.fn/doc`, `:seon.fn/doc-order`, `:seon.fn/external-sink`, `:seon.fn/file`, `:seon.fn/form-span`, `:seon.fn/internal?`, `:seon.fn/keywords`, `:seon.fn/macro?`, `:seon.fn/projection-boundary`, `:seon.fn/references`, `:seon.fn/spec`, `:seon.fn/workload`, `:seon.fn/writes`, `:seon.test/subject`. Keep optionality unless the specific required/transition row above changes it; for renamed entries apply the condition to the replacement. | A seon.fn reset row | add | `seon.cluster.source/mintable-identity` (`src/seon/cluster/source.clj:305`); `seon.fn/backfill-contract-facts!` (`src/seon/fn.clj:2229`); `seon.fn/reconcile-tx-in` (`src/seon/fn.clj:2557`); `seon.fn/var-row` (`src/seon/fn.clj:584`); `seon.program/contract-facts` (`src/seon/program.cljc:729`); `seon.program/declaration-row` (`src/seon/program.cljc:906`); `seon.program/with-contract-facts` (`src/seon/program.cljc:792`); `seon.sci.eval/declared-row` (`src/seon/sci/eval.clj:1902`); `seon.sci.eval/definition-row` (`src/seon/sci/eval.clj:392`); `seon.turn/row-tx` (`src/seon/turn.clj:1280`) | seon.fn gate-sets/reach/output graph; seon.test, runner, selection; seon.db sink reach; effect, bootstrap, run, render/ns/test, issue/detect; all S §2 sites | seon.fn-test, seon.test-reaching-test: nonempty actual symbol datoms, tuple schema, deleting B with surviving A refuses atomically; repairing A in the same transaction succeeds; stale reach is reported, redefinition preserves identity; unknown analyzer namespace is not minted as an unreadable symbol |
| 6 | Remove ast attribute and map entry only after Q2 reader replacement; remove backfill's nil-AST analysis test. Keep canonical spec and schema.shape facts. | R N16/N29; B.4/C.2; Q2 | delete | seon.program/with-contract-facts; seon.fn/backfill-contract-facts!; seon.turn declared projection | seon.fn reconciliation comparison; canonical shape consumers | seon.program-test: repeated publication is no-op, contract change detected, no fn.ast datoms |

### `resources/seon/schemas/seon.fn.file.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 2 | Alias digest to source/digest; require path and analyzed file digest; retain unresolved-references as symbol values. Relative-root absence means outside a declared source root. No retirement arm. | E §1; A file; R N35 | retype; add | seon.fn/artifact, text-context; seon.program/declaration-row | reconciliation, publication digests, file display | seon.fn-test: external-root path remains legal; invalid digest refuses; empty analysis retains digest |
| 4 | Add absence-condition docstrings for surviving optional entries: `:seon.fn.file/relative-root`, `:seon.fn/unresolved-references`, `:seon.schema.admission/source`. Keep optionality unless the specific required/transition row above changes it; for renamed entries apply the condition to the replacement. | A seon.fn.file reset row | add | `seon.fn/artifact` (`src/seon/fn.clj:1217`); `seon.program/declaration-row` (`src/seon/program.cljc:906`) | reconciliation, publication digests, file display | seon.fn-test: external-root path remains legal; invalid digest refuses; empty analysis retains digest |

### `resources/seon/schemas/seon.instrument.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 2 | fn and contract-violated alias use qualified-symbol, remove string construction at every error constructor. | S §1.1/§2.11; A instrument; R N13 | retype | seon.instrument error construction/invocation | seon.error occurrence writing, render errors, contract-definition acquisition | seon.instrument-test: real armed violation preserves function symbol through stored occurrence |

### `resources/seon/schemas/seon.issue.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 2 | Cited functions/tests indexed qualified-symbol sets; namespaces symbols; keys qualified-keywords; errors signature strings; runs/issues id strings. Parse known-family tokens by declared cites identity grammar without requiring a current row. Query missing targets positively; retain ambiguous/unclassified raw-token evidence until its family is known. Missing obligated success tests remain explicit incomplete/refused results, never omitted or counted verified. Remove unresolved storage only when that evidence has a complete replacement. | R N5/D.1; S citation; A issue | retype; delete | seon.issue/citation-attributes, citation-index, index-tx, adopt-tx, generate, tests-tx | issue opening/render/detect, completion, retention validation | seon.issue-test: valid unresolved tokens persist before/after target deletion, no citation loss, malformed token gives diagnostic, protected issue test retention still enforced on values |
| 4 | KEEP status (X1). Any later deletion requires positive resolved/superseded writers from authored note states first; no migration. Add opened-tx for database creation; retain imported opening date under its observation semantics. Preserve creator/agent living refs and append-only authority. Index/adopt removed note emits retractEntity, not identity-only row. | P G1; E issue seam; R N26/N38/C.1; A issue | add | seon.issue/index-tx, adopt-tx, create-tx, start-tx, exhaust-tx, add-tx | issue index/opening/completion/render, note frontmatter serialization | seon.issue-deletion-test and seon.issue-test: archived ordinary issue/citations gone currently, history retained; started last-test removal refuses and rolls back sibling writes |
| 4 | Add absence-condition docstrings for surviving optional entries: `:seon.issue/agent`, `:seon.issue/budget`, `:seon.issue/budget-exhausted-tx`, `:seon.issue/commits`, `:seon.issue/created-by`, `:seon.issue/detector`, `:seon.issue/errors`, `:seon.issue/files`, `:seon.issue/functions`, `:seon.issue/issues`, `:seon.issue/keys`, `:seon.issue/members`, `:seon.issue/namespaces`, `:seon.issue/opened`, `:seon.issue/path`, `:seon.issue/resolved-tx`, `:seon.issue/runs`, `:seon.issue/tests`. Keep optionality unless the specific required/transition row above changes it; for renamed entries apply the condition to the replacement. | A seon.issue reset row | add | `seon.issue/add-tx` (`src/seon/issue.clj:985`); `seon.issue/adopt-tx` (`src/seon/issue.clj:713`); `seon.issue/create-tx` (`src/seon/issue.clj:823`); `seon.issue/exhaust-tx` (`src/seon/issue.clj:939`); `seon.issue/generate` (`src/seon/issue.clj:483`); `seon.issue/index-tx` (`src/seon/issue.clj:262`); `seon.issue/start-tx` (`src/seon/issue.clj:885`); `seon.issue/tests-tx` (`src/seon/issue.clj:1038`) | issue opening/render/detect, completion, retention validation | seon.issue-test: valid unresolved tokens persist before/after target deletion, no citation loss, malformed token gives diagnostic, protected issue test retention still enforced on values |

### `resources/seon/schemas/seon.program.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 2 | Add analyzed-source-digest, using the shared digest format; require on fn/test definitions through their maps. Delete declaration-required-attributes mirror after canonical schema derives obligations. Preserve digest through canonical-row and declaration-row, never synthesize it at record-tx. | P G4/S2; E §1; A required rows | add; required; delete | seon.fn/var-row, source-rows, text-context; seon.program/canonical-row, declaration-row; seon.sci.eval/definition-row, declared-row | seon.fn reconciliation; SCI acquisition; final whole-entity validator | seon.program-test and seon.sci.eval-test: index and real SCI declaration both persist exact analyzed-input digest; omitted digest rolls back |

### `resources/seon/schemas/seon.schedule.task.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 2 | function qualified-symbol with fire-time resolution; delete reference-to property here. Keep task identity/schedule relation required. Symbol portfolio and config constructors publish together. | R N9; S §2.8; A task | retype; delete | seon.schedule/root-maintenance-seed-call, portfolio; seon.config/default-population | seon.schedule/fire-call and execution handler lookup | seon.schedule-test: removed handler yields typed unknown and terminal evidence, no silently incomplete task |

### `resources/seon/schemas/seon.schema.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 2 | namespace-name becomes symbol; references indexed qualified-keyword set. Keep shape/generatable? obligations tied to actual analysis; no schema identity stubs. Retain search tokenizer and symbol render properties. | S §1.1; A schema; R N3 | retype; add | seon.schema/canonical-schema-rows, register!; seon.program/with-contract-facts, declaration-row; seon.test.accretion/schema-row | seon.schema dependency traversal; call-preparation; instrument; fn graph; issue/detect | seon.schema-test: delete referenced key leaves keyword edge and unresolved query; native namespace symbol; invalid declaration refuses |
| 4 | Add absence-condition docstrings for surviving optional entries: `:seon.db.id/generator`, `:seon.schema/generatable?`, `:seon.schema/ns`, `:seon.schema/references`, `:seon.schema/shape`. Keep optionality unless the specific required/transition row above changes it; for renamed entries apply the condition to the replacement. | A seon.schema reset row | add | `seon.program/declaration-row` (`src/seon/program.cljc:906`); `seon.program/with-contract-facts` (`src/seon/program.cljc:792`); `seon.schema/canonical-schema-rows` (`src/seon/schema.clj:2928`); `seon.schema/register!` (`src/seon/schema.clj:1333`); `seon.test.accretion/schema-row` (`src/seon/test/accretion.clj:33`) | seon.schema dependency traversal; call-preparation; instrument; fn graph; issue/detect | seon.schema-test: delete referenced key leaves keyword edge and unresolved query; native namespace symbol; invalid declaration refuses |

### `resources/seon/schemas/seon.test.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 2 | G4 correction: adoption-identities/adoption-inputs are optional stored collections on adoption. Keep required adoption-cluster on the producing `:db/current-tx` entity as the positive completion fact: cluster/adopt records it only after indexing, reload, SCI acquisition and arming succeed. No new marker is needed; interpret absent members as empty only when that transaction fact exists. | required-many blocker; P G4 | required | cluster adoption constructor and publication verification | adoption readers and tests | successful adoption with empty members is present via provenance; absent provenance is incomplete and refuses a success claim |
| 2 | sym qualified-symbol; reach indexed qualified-symbol set; subject qualified-symbol; delete pending-subject and pending-calls entries. changed drops string arm; destructive-path is symbol vector. Require ns/source/analyzed-source-digest; preserve platform and fixture-exclusion declarations from landed commits. | P G2/G4/S2; E §1–3; S §1.1; A test; R N7 | retype; required; delete | seon.fn/var-row; seon.program/declaration-row; seon.sci.eval; seon.turn; seon.test.runner/record-tx; source preservation | seon.test reach/changed-since-green, selection, runner digests, effect/accretion, render/test, issue checks | seon.test-reaching-test and seon.test.runner-test: >1,000 members survive, no-op recording, subject-target deletion refuses while the subject survives; stale reach symbols survive and select reruns |
| 4 | Require completed result counts/run together; require reach-digest on successful recorded closure, not on unrun definition. Delete reach-unknown only after failed computation refuses recording. Delete failing-assertions, run-at/run-basis-t copies; derive result schema from entity plus selector; remove reach digest identity-negation. The failure-identity format alias is separate contract-only work (X2). | A result/test; R N15/N32/N35/N36/N40/B.3 | required; delete; retype | seon.test.runner/provenance, record-tx, failure handling; seon.cluster.source/result-preservation-tx | seon.test/verified?, changed-since-green; render/test/transcript; issue completion | seon.test.runner-test: analyzed-but-unrun is unknown, computed-empty reach is known, failed reach never green, counts cannot default to zero |
| 4 | Add absence-condition docstrings for surviving optional entries: `:seon.fn/call-arities`, `:seon.fn/file`, `:seon.fn/form-span`, `:seon.fn/keywords`, `:seon.fn/references`, `:seon.fn/writes`, `:seon.test/error-count`, `:seon.test/fail-count`, `:seon.test/failure-message`, `:seon.test/failures`, `:seon.test/fixture-observation`, `:seon.test/long`, `:seon.test/long-ms`, `:seon.test/pass-count`, `:seon.test/reach`, `:seon.test/reach-digest`, `:seon.test/run`, `:seon.test/subject`, `:seon.test/usage`. Keep optionality unless the specific required/transition row above changes it; for renamed entries apply the condition to the replacement. | A seon.test reset row | add | `seon.cluster.source/mintable-identity` (`src/seon/cluster/source.clj:305`); `seon.cluster.source/result-preservation-tx` (`src/seon/cluster/source.clj:392`); `seon.cluster/development-source-refresh!` (`src/seon/cluster.clj:2211`); `seon.fn/var-row` (`src/seon/fn.clj:584`); `seon.program/declaration-row` (`src/seon/program.cljc:906`); `seon.sci.eval/definition-row` (`src/seon/sci/eval.clj:392`); `seon.test.runner/record-tx` (`src/seon/test/runner.clj:2095`) | seon.test reach/changed-since-green, selection, runner digests, effect/accretion, render/test, issue checks | seon.test-reaching-test and seon.test.runner-test: >1,000 members survive, no-op recording, subject-target deletion refuses while the subject survives; stale reach symbols survive and select reruns |

### `resources/seon/schemas/my.note.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 4 | Add absence-condition docstrings for surviving optional entries: `:my.note/about`. Keep optionality unless the specific required/transition row above changes it; for renamed entries apply the condition to the replacement. | A my.note reset row | add | `seon.note/add-note-call` (`src/seon/note.clj:177`); `seon.note/forget-note-call` (`src/seon/note.clj:211`) | my.note and namespace render | seon.render.entity-pairs-test: note authored/read forms execute; about semantics follow the separately ruled identity-carrier decision |
| 5 | Remove render/form declarations with AI/HTML form selection; document optional about semantics and its pending identity-carrier decision (K §7). | A note; R B.12/C.1 | delete; add | seon.note/add-note-call, forget-note-call; note pair | my.note and namespace render | seon.render.entity-pairs-test: note authored/read forms execute; about semantics follow the separately ruled identity-carrier decision |

### `resources/seon/schemas/my.plan.item.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 4 | Require position; retain completion/needs/subject optional lifecycle meanings; subject/needs are identity-token observations per K; steps are components. No completed/open flag. | A item; P G5 | required; add | seon.plan/add-step-call, compile-tree, entry-tx-map, update-step-call; seon.issue/create-tx | plan ordering, completion, rendering | my.plan-test: canonical constructor supplies position, partial valid update works, missing position in final row refuses |
| 4 | Add absence-condition docstrings for surviving optional entries: `:my.plan.item/about`, `:my.plan.item/agent`, `:my.plan.item/completed-tx`, `:my.plan.item/description`, `:my.plan.item/done-query`, `:my.plan.item/done-when`, `:my.plan.item/needs`, `:my.plan.item/steps`, `:my.plan.item/subject`. Keep optionality unless the specific required/transition row above changes it; for renamed entries apply the condition to the replacement. | A my.plan.item reset row | add | `seon.issue/create-tx` (`src/seon/issue.clj:823`); `seon.plan/add-step-call` (`src/seon/plan.clj:516`); `seon.plan/compile-tree` (`src/seon/plan.clj:1028`); `seon.plan/completion-tx` (`src/seon/plan.clj:702`); `seon.plan/entry-tx-map` (`src/seon/plan.clj:928`); `seon.plan/update-step-call` (`src/seon/plan.clj:837`) | plan ordering, completion, rendering | my.plan-test: canonical constructor supplies position, partial valid update works, missing position in final row refuses |

### `resources/seon/schemas/seon.agent.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 4 | Require namespace/plan/runtime/settings on complete agent; constructors create valid empty owned plan and settings/runtime. Keep agent identity; no agent deletion API or tombstone. Positive closure lifecycle is outside this reset. Document existing relation/component consequences. Remove render/form property only with render function conversion. | A agent; R A.2/C.1/B.12 | required; add; delete | seon.cluster.agent/creation-tx; seon.agent/update-settings-call; seon.plan/compile-tree | agent acquisition, render, message/issue ownership | seon.cluster.agent-identity-test: complete creation succeeds; missing required component refuses; agent identity remains; no deletion API or closure lifecycle introduced |

### `resources/seon/schemas/seon.ai.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 4 | Add absence-condition docstrings for surviving optional entries: `:seon.ai.attempt/delay-ms`, `:seon.ai.attempt/error`, `:seon.ai.attempt/failover-from`, `:seon.ai.attempt/finish-reason`, `:seon.ai.attempt/model`, `:seon.ai.attempt/reasoning`, `:seon.ai.attempt/reasoning-blob`, `:seon.ai.attempt/reasoning-size`, `:seon.ai.attempt/settings`, `:seon.ai.attempt/truncation`, `:seon.ai.usage/cached-tokens`, `:seon.ai.usage/completion-tokens`, `:seon.ai.usage/prompt-tokens`, `:seon.ai.usage/total-tokens`, `:seon.ai/http-status`, `:seon.ai/output-observed?`, `:seon.ai/request-transmitted?`, `:seon.ai/response-started?`. Keep optionality unless the specific required/transition row above changes it; for renamed entries apply the condition to the replacement. | A seon.ai reset row | add | `seon.turn/record-attempt!` (`src/seon/turn.clj:3947`) | provider HTTP boundary and attempt render | seon.ai-test: identical outgoing provider body and normalized usage after rename |
| 5 | Rename extra-body-edn to extra-body-payload retaining documented provider bytes; attempt map drops usage-edn with typed usage writer. Preserve positive transmitted/started/observed facts and optional provider outcome fields. | R N23; A ai | rename; delete; add | seon.ai request construction; seon.config population; seon.turn/record-attempt! | provider HTTP boundary and attempt render | seon.ai-test: identical outgoing provider body and normalized usage after rename |

### `resources/seon/schemas/seon.ai.model.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 4 | Add absence-condition docstrings for surviving optional entries: `:seon.ai.model/cached-input-usd-per-mtok`, `:seon.ai.model/context-window-tokens`, `:seon.ai.model/input-modalities`, `:seon.ai.model/input-usd-per-mtok`, `:seon.ai.model/last-latency-ms`, `:seon.ai.model/last-tokens-per-second`, `:seon.ai.model/last-used-at`, `:seon.ai.model/max-output-tokens`, `:seon.ai.model/output-usd-per-mtok`, `:seon.ai.model/thinking-dials`. Keep optionality unless the specific required/transition row above changes it; for renamed entries apply the condition to the replacement. | A seon.ai.model reset row | add | `seon.ai/model-observation-tx` (`src/seon/ai.clj:993`); `seon.config/default-population` (`src/seon/config.clj:300`); `seon.config/population-transaction-data` (`src/seon/config.clj:422`) | AI pricing/rate-limit/model rendering | seon.ai-test: two providers share generic schema without conflating windows; actual future schedule is not inferred active from recording time |
| 5 | Move windows/rate limits to provider descriptor relation. Replace deepseek-off-peak-windows/window-id/utc-start/utc-end/regular-price-factor/peak-price-factor/pricing-schedule-status and meta-search-usd-per-kquery/free-requests-per-minute/free-tokens-per-minute/paid-requests-per-minute/paid-tokens-per-minute by generic attributes. Activation is a positive transition fact (effective time retained when externally announced). latest-* overwrite docstrings state unrecoverable and absence is not never-used. | R N28/N42/B.8/C.5/J5; A model | rename; add; delete | seon.config population/default manifest; seon.ai/model-observation-tx and provider pricing construction | AI pricing/rate-limit/model rendering | seon.ai-test: two providers share generic schema without conflating windows; actual future schedule is not inferred active from recording time |

### `resources/seon/schemas/seon.cluster.eval.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 4 | Move stored map to seon.eval owner; delete receipt alias. Keep author stamp until its replacement derivation accounts for X's 45-row disagreement. Require read-basis-transaction on the evidence-capturing read path as positive observation; empty evidence plus basis is known-empty, missing observation unknown. triage-edn becomes triage typed value via codec. Keep evidence child schemas linked to canonical parent. | A cluster eval; R N14/N20/N24/B.1/B.11; E §4 | delete; required; rename; retype | seon.turn/receipt-read-evidence-tx, start/settle/recover/refresh and generated source constructors; seon.sci.eval | seon.turn since-diff; seon.repl authorship/history; evaluation consumers | seon.rereads-test and seon.eval-test: empty read observation remains distinguishable and invalidates on relevant insertion; writes/effects never rerun; missing observation reported unknown |
| 4 | Add absence-condition docstrings for surviving optional entries: `:seon.cluster.eval/comment`, `:seon.cluster.eval/error`, `:seon.cluster.eval/interrupted-at`, `:seon.cluster.eval/ns`, `:seon.cluster.eval/output`, `:seon.cluster.eval/read-basis-transaction`, `:seon.cluster.eval/read-evidence`, `:seon.cluster.eval/refreshes`, `:seon.cluster.eval/source`, `:seon.db/read-request`, `:seon.db/read-result-digest`, `:seon.error/kind`, `:seon.eval/duration-ms`, `:seon.eval/origin`, `:seon.eval/renderer`, `:seon.eval/shown`, `:seon.print/length`, `:seon.print/level`, `:seon.problems/id`, `:seon.sci.eval/ending-ns`, `:seon.test.accretion/case-count`, `:seon.test.accretion/executed-count`, `:seon.test.accretion/gate-fail-count`, `:seon.test.accretion/gate-pass-count`, `:seon.test.accretion/gate-test-count`, `:seon.test.accretion/gate-tests`, `:seon.test.accretion/report-blob`, `:seon.test.accretion/report-size`, `:seon.test.accretion/seed`, `:seon.test.accretion/status`. Keep optionality unless the specific required/transition row above changes it; for renamed entries apply the condition to the replacement. | A seon.cluster.eval reset row | add | `seon.turn/append-generated-call` (`src/seon/turn.clj:696`); `seon.turn/plan-call` (`src/seon/turn.clj:574`); `seon.turn/receipt-read-evidence-tx` (`src/seon/turn.clj:1495`); `seon.turn/receipt-row` (`src/seon/turn.clj:891`); `seon.turn/receipt-settle-call` (`src/seon/turn.clj:1712`); `seon.turn/receipt-start-call` (`src/seon/turn.clj:913`); `seon.turn/record-evaluated-call` (`src/seon/turn.clj:1569`); `seon.turn/recover-call` (`src/seon/turn.clj:1803`); `seon.turn/refresh-call` (`src/seon/turn.clj:794`); `seon.turn/source-rows` (`src/seon/turn.clj:527`); `seon.turn/system-run-call` (`src/seon/turn.clj:655`); `seon.turn/system-turn` (`src/seon/turn.clj:2189`) | seon.turn since-diff; seon.repl authorship/history; evaluation consumers | seon.rereads-test and seon.eval-test: empty read observation remains distinguishable and invalidates on relevant insertion; writes/effects never rerun; missing observation reported unknown |

### `resources/seon/schemas/seon.context.capture.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 4 | Add absence-condition docstrings for surviving optional entries: `:seon.ai.tokens/characters`, `:seon.context.capture/contributions`, `:seon.context.capture/prompt`, `:seon.error/kind`, `:seon.error/message`. Keep optionality unless the specific required/transition row above changes it; for renamed entries apply the condition to the replacement. | A seon.context.capture reset row | add | `seon.context/capture-tx` (`src/seon/context.clj:490`) | token calibration/context debug readers | seon.context-capture-test: exact historical prompt survives later capture; nested contributions validate |
| 5 | Keep prompt as exact provider calibration evidence, not recomputable current text; remove no-history? so past captures remain observable. Retain basis and contribution ownership; documented optional errors/character count. | A capture; R B.7/J6 | delete; add | seon.context/capture-tx | token calibration/context debug readers | seon.context-capture-test: exact historical prompt survives later capture; nested contributions validate |

### `resources/seon/schemas/seon.context.contribution.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 4 | Require block/name/hash/tokens on capture arm; require agent on append arm; preserve empty evaluations as empty under explicit arm obligations. Do not globally require capture keys on append-tx. | A contribution; R N17 | required; add | seon.context/append-tx, compact-tx, contribution-row | seon.context block selection and captured context reader | seon.context-capture-test and seon.context-test: both constructors validate, empty append evaluations does not switch to capture |
| 4 | Add absence-condition docstrings for surviving optional entries: `:seon.context.contribution/agent`, `:seon.context.contribution/error`, `:seon.context.contribution/evaluations`, `:seon.error/kind`. Keep optionality unless the specific required/transition row above changes it; for renamed entries apply the condition to the replacement. | A seon.context.contribution reset row | add | `seon.context/append-tx` (`src/seon/context.clj:160`); `seon.context/compact-tx` (`src/seon/context.clj:226`); `seon.context/contribution-row` (`src/seon/context.clj:468`) | seon.context block selection and captured context reader | seon.context-capture-test and seon.context-test: both constructors validate, empty append evaluations does not switch to capture |

### `resources/seon/schemas/seon.error.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 4 | throwable-class symbol; delete exception-class and fn ref, use instrument/fn symbol. Rename data-edn to data-projection (bounded shown string, not arbitrary logical data). Keep signature versus occurrence separation; retain actual event instant and frame symbols. | S §1.1/§2.10; A error; R N13/N21/B.6/J3 | retype; delete; rename | seon.error/prepare, commit-call, fault projection; seon.instrument | error renderers, occurrence query, issue detectors and attribution | seon.error-class-schema-test and seon.db-test: real throwable recorded, symbol survives fn deletion, projection/full blob coherent |
| 4 | Add absence-condition docstrings for surviving optional entries: `:seon.error/frame`, `:seon.error/id`, `:seon.error/issue`, `:seon.error/occurrences`, `:seon.error/regressions`, `:seon.error/resolved-tx`. Keep optionality unless the specific required/transition row above changes it; for renamed entries apply the condition to the replacement. | A seon.error reset row | add | `seon.error/commit-call` (`src/seon/error.clj:1306`); `seon.error/prepare` (`src/seon/error.clj:516`) | error renderers, occurrence query, issue detectors and attribution | seon.error-class-schema-test and seon.db-test: real throwable recorded, symbol survives fn deletion, projection/full blob coherent |

### `resources/seon/schemas/seon.error.occurrence.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 4 | Keep occurrence/process as the sole occurrence process fact; remove error/process duplicate from occurrence constructors/map. Retain typed instrument/fn and the canonical symbol exception-class, agent/turn optional only with declared provenance conditions. | A occurrence; R B.6/N13 | delete; retype; add | seon.error/prepare, commit-call; fault committer | occurrence readers and rendered provenance | seon.error-class-schema-test: occurrence names one actual process, source fault retained through deleting function |
| 4 | Add absence-condition docstrings for surviving optional entries: `:seon.error.occurrence/agent`, `:seon.error.occurrence/data-blob`, `:seon.error.occurrence/proc-fn`, `:seon.error.occurrence/turn`, `:seon.error/cid`, `:seon.error/data-size`, `:seon.error/dropped-fault-count`, `:seon.error/dropped-fault-digest`, `:seon.error/op`, `:seon.error/proc`, `:seon.error/throwable-class`, `:seon.instrument/args`, `:seon.instrument/arm`, `:seon.instrument/expected`, `:seon.instrument/fn`. Keep optionality unless the specific required/transition row above changes it; for renamed entries apply the condition to the replacement. | A seon.error.occurrence reset row | add | `seon.error/commit-call` (`src/seon/error.clj:1306`); `seon.error/prepare` (`src/seon/error.clj:516`) | occurrence readers and rendered provenance | seon.error-class-schema-test: occurrence names one actual process, source fault retained through deleting function |

### `resources/seon/schemas/seon.eval.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 4 | Own the single stored evaluation entity; replace reader-shaped entity, preserve pair, derive read shape with reader-only :t. Delete renderer-fn; outcome removal is separate contract-only work (X2). Retain renderer symbol and explicit terminal shown/error/interruption facts. Historical origins that must survive source deletion become target identity values; audit transaction/effect provenance by per-family semantics (K), without universal enforcement. | A eval; R N6/N12/N18/N25/B.1 | delete; required; add | seon.turn/source-rows, record-evaluated-call, settlement/recovery; seon.sci.eval result projection | seon.eval/of-agent; seon.repl; context and transcript readers | seon.eval-test and seon.repl-parity-test: creation/terminal groups coherent, comment without executable source valid, renderer deletion preserves symbol, origin deletion follows Q1 |

### `resources/seon/schemas/seon.fn.argument.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 4 | Add absence-condition docstrings for surviving optional entries: `:seon.fn.argument/rest-element-schema`, `:seon.fn.argument/rest-tail-schema`. Keep optionality unless the specific required/transition row above changes it; for renamed entries apply the condition to the replacement. | A seon.fn.argument reset row | add | `seon.program/argument-row` (`src/seon/program.cljc:659`); `seon.program/label-facts` (`src/seon/program.cljc:645`) | seon.call-preparation; function contract/render readers | seon.program-test: order preserved after shuffled datoms; literal labels round-trip; rest binding validates |
| 5 | Keep order; delete index (argument-row writes both from the same order). Replace label-edn/keyword/string/symbol variants with one label literal union using bridge codec. Preserve rest-tail/rest-element obligations and binding/schema component ownership. | A argument; R N22/N30/N34/C.4 | delete; add; retype | seon.program/argument-row, label-facts | seon.call-preparation; function contract/render readers | seon.program-test: order preserved after shuffled datoms; literal labels round-trip; rest binding validates |

### `resources/seon/schemas/seon.fn.binding.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 4 | Add absence-condition docstrings for surviving optional entries: `:seon.fn.binding/children`, `:seon.fn.binding/entries`, `:seon.fn.binding/symbol`. Keep optionality unless the specific required/transition row above changes it; for renamed entries apply the condition to the replacement. | A seon.fn.binding reset row | add | `seon.program/binding-row` (`src/seon/program.cljc:391`) | call-preparation/destructuring and render readers | seon.program-test: {}, [], symbol, rest/as and nested destructuring all retain semantics |
| 5 | Delete shape only with reader-derived classification from required binding/form; keep symbol, entries and children as data, G5-owned. Empty map and empty vector must remain distinguishable. | A binding; R N27; precedence resolution | delete; add | seon.program/binding-row | call-preparation/destructuring and render readers | seon.program-test: {}, [], symbol, rest/as and nested destructuring all retain semantics |

### `resources/seon/schemas/seon.listen.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| excluded | Listener redesign/co-deletion is outside this assignment. H N8 and K disagree on the remedy; retain the recorded issue and do not enable universal enforcement to choose implicitly. | H N8; R N8 corrected; A listen | add | seon.config/default-population; seon.issue/start-tx; runtime listen constructors | seon.cluster.wake matching and schema-driven subscriptions | seon.cluster.wake-test: future listener owner proves scoped deletion cannot widen matching; no listener change in this seam |
| 4 | Add absence-condition docstrings for surviving optional entries: `:seon.listen/entity`, `:seon.listen/value`. Keep optionality unless the specific required/transition row above changes it; for renamed entries apply the condition to the replacement. | A seon.listen reset row | add | `seon.config/default-population` (`src/seon/config.clj:300`); `seon.issue/start-tx` (`src/seon/issue.clj:885`) | seon.cluster.wake matching and schema-driven subscriptions | seon.cluster.wake-test: future listener owner proves scoped deletion cannot widen matching; no listener change in this seam |

### `resources/seon/schemas/seon.maintenance.receipt.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 4 | Add absence-condition docstrings for surviving optional entries: `:seon.maintenance.receipt/error`, `:seon.maintenance.receipt/result`. Keep optionality unless the specific required/transition row above changes it; for renamed entries apply the condition to the replacement. | A seon.maintenance.receipt reset row | add | `seon.schedule/fire-call` (`src/seon/schedule.clj:313`); `seon.schedule/recover-tx` (`src/seon/schedule.clj:459`); `seon.schedule/settle-call` (`src/seon/schedule.clj:419`) | maintenance state/render/queries and recovery | seon.schedule-test: result lifecycle transitions coherent, interrupted recovery recorded, no legacy receipt key in new facts |
| 5 | Merge the former receipt lifecycle into the existing seon.maintenance.result root (one id, request/task/fire/handler plus operation components); delete the receipt resource and redundant result-to-result link. started/completed/interrupted-at database transitions become -tx refs. All referring attributes/default manifest/tests change together. | A maintenance lifecycle; R N39/B.11/J3 | rename; retype | seon.schedule/fire-call, settle-call, recover-tx | maintenance state/render/queries and recovery | seon.schedule-test: result lifecycle transitions coherent, interrupted recovery recorded, no legacy receipt key in new facts |

### `resources/seon/schemas/seon.maintenance.result.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 5 | G4 correction: optional stored member entries for cluster-cleanup remaining/removed, collect collect-branches, census claim-errors/dead/processes/roots/unclaimed/unresponsive, census-process advertisements, and reap reap-refused/reap-roots/reap-stopped-processes/eligible-root-claims (14 keys). Use each actual operation’s required scalar construction evidence to distinguish its empty success from skipped, absent or failed sub-operations; a parent completion alone does not license every optional child. Child observations validate with that parent. Never require a many-key to prove execution. | required-many blocker; P G4/G5 | required | seon.maintenance operation projections and seon.schedule settlement | maintenance result readers and renderers | all four completed operations with empty results round-trip; unfinished/missing producing transaction is explicit incomplete, not a completed empty result |
| 4 | Add absence-condition docstrings for surviving optional entries: `:seon.dev.process/generation`, `:seon.error/kind`, `:seon.operator.claim/path`. Keep optionality unless the specific required/transition row above changes it; for renamed entries apply the condition to the replacement. | A seon.maintenance.result reset row | add | `seon.maintenance/claim-error` (`src/seon/maintenance.clj:106`); `seon.maintenance/collection-component` (`src/seon/maintenance.clj:200`); `seon.maintenance/process-identity` (`src/seon/maintenance.clj:73`); `seon.maintenance/process-observation` (`src/seon/maintenance.clj:81`); `seon.maintenance/project-cluster-cleanup-result` (`src/seon/maintenance.clj:213`); `seon.maintenance/project-collect-result` (`src/seon/maintenance.clj:177`); `seon.maintenance/project-process-census-result` (`src/seon/maintenance.clj:115`); `seon.maintenance/project-reap-result` (`src/seon/maintenance.clj:141`); `seon.maintenance/root-claim` (`src/seon/maintenance.clj:92`); `seon.schedule/settle-call` (`src/seon/schedule.clj:419`) | maintenance render/query and whole-parent validator | seon.maintenance-schema-test: all four real projections valid; malformed last nested child rejects root and sibling writes; id-only root refuses |
| 5 | The same root that receives the merged maintenance lifecycle declares actual optional operation component attributes (census/reap/collect/cleanup), exactly one operation shape on successful completed result by attribute presence (open creation requires start provenance; error settlement requires error). The value map is nonstored: its replacement by a derived API union is contract-only work (X2), not a reset gate. G5 validates all nested components; no synthetic identities for anonymous children. | R C.3/N44/B.9; A result; E §4 | add; delete; retype | seon.maintenance project-process-census-result/project-reap-result/project-collect-result/project-cluster-cleanup-result and nested constructors; seon.schedule/settle-call | maintenance render/query and whole-parent validator | seon.maintenance-schema-test: all four real projections valid; malformed last nested child rejects root and sibling writes; id-only root refuses |

### `resources/seon/schemas/seon.message.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 4 | Delete pulled/pulled-reference mirrors after selector derivation. Declare per-family semantics (K): required to refuses; inbox moves at settlement; caused-by may sweep; about identity carrier preserves wake classification. No blanket living rule. Remove form properties with render group. | A message; R B.2/C.1/B.12 | delete; add | seon.cluster.message/inbound-tx, send-call; seon.error/message-tx | my.message, unread queries, message pairs and wake matching | seon.db-test and seon.render.entity-pairs-test: actual nested pulls validate; agent deletion is not an API; current message ownership and wake classification remain unchanged |
| 4 | Add absence-condition docstrings for surviving optional entries: `:my.message/reason`, `:seon.message/about`, `:seon.message/caused-by`, `:seon.message/from`, `:seon.message/inbox`, `:seon.message/read-tx`. Keep optionality unless the specific required/transition row above changes it; for renamed entries apply the condition to the replacement. | A seon.message reset row | add | `seon.cluster.message/inbound-tx` (`src/seon/cluster/message.clj:198`); `seon.cluster.message/send-call` (`src/seon/cluster/message.clj:705`); `seon.error/message-tx` (`src/seon/error.clj:1295`) | my.message, unread queries, message pairs and wake matching | seon.db-test and seon.render.entity-pairs-test: actual nested pulls validate; agent deletion is not an API; current message ownership and wake classification remain unchanged |

### `resources/seon/schemas/seon.ns.import.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 4 | Require target-class from both index and SCI binding constructors; unresolved target returns diagnostic, not malformed import. Parent namespace validates component. | A import; E §4 | required; add | seon.fn/namespace-row; seon.program/canonical-namespace-components; seon.sci.eval/binding-rows | SCI namespace binding acquisition; parent validation | seon.program-test and seon.sci.eval-test: imports work through both seams; unresolved class refuses with named target |

### `resources/seon/schemas/seon.operator.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 4 | Add absence-condition docstrings for surviving optional entries: `:seon.operator/low-space?`. Keep optionality unless the specific required/transition row above changes it; for renamed entries apply the condition to the replacement. | A seon.operator reset row | add | `seon.operator/observe-footprint!` (`src/seon/operator.clj:259`) | operator footprint observation consumers | seon.maintenance-test: canonical constructor succeeds; legal absent lifecycle fields remain absent and malformed final entity refuses |

### `resources/seon/schemas/seon.render.transcript.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 4 | Delete pulled-transaction hand mirror after exact history-selector derived form is used; no history clipping or rewriting saved shown text. | A transcript; R B.2 | delete | seon.render.transcript history projection | transcript pair and web debug | seon.render.transcript-test: historical shown bytes unchanged, native pulled refs validate |

### `resources/seon/schemas/seon.schema.shape.child.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 4 | Add absence-condition docstrings for surviving optional entries: `:seon.schema.shape.child/schema`. Keep optionality unless the specific required/transition row above changes it; for renamed entries apply the condition to the replacement. | A seon.schema.shape.child reset row | add | `seon.fn.schema-shape/child-row` (`src/seon/fn/schema_shape.clj:196`) | schema shape decode and call-preparation | seon.schema-test: ordered literal/schema children round-trip and invalid child rolls back parent |
| 5 | Replace value-edn with value literal union; preserve required order and identity. Child has schema or literal value as declared, not an absent branch inferred as health. | A child; R N22/C.4 | rename; retype | seon.fn.schema-shape/child-row | schema shape decode and call-preparation | seon.schema-test: ordered literal/schema children round-trip and invalid child rolls back parent |

### `resources/seon/schemas/seon.schema.shape.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 4 | Add absence-condition docstrings for surviving optional entries: `:seon.schema.shape/children`, `:seon.schema.shape/entries`, `:seon.schema.shape/properties`. Keep optionality unless the specific required/transition row above changes it; for renamed entries apply the condition to the replacement. | A seon.schema.shape reset row | add | `seon.fn.schema-shape/encode-form` (`src/seon/fn/schema_shape.clj:257`) | seon.schema, seon.call-preparation, seon.fn, seon.issue.detect | seon.program-test: tuple/cat/function forms reconstruct in order, nested malformed component refuses |
| 5 | Keep canonical ordered shape decomposition as sole contract tree. Components declare child/entry schemas; type describes Malli operator. Q2 reuses this owner, not a second taxonomy. | A shape; R B.4/C.4; E G5 | add | seon.fn.schema-shape/encode-form; seon.program/contract-facts | seon.schema, seon.call-preparation, seon.fn, seon.issue.detect | seon.program-test: tuple/cat/function forms reconstruct in order, nested malformed component refuses |

### `resources/seon/schemas/seon.schema.shape.entry.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 4 | Add absence-condition docstrings for surviving optional entries: `:seon.schema.shape.entry/properties`. Keep optionality unless the specific required/transition row above changes it; for renamed entries apply the condition to the replacement. | A seon.schema.shape.entry reset row | add | `seon.fn.schema-shape/entry-row` (`src/seon/fn/schema_shape.clj:208`); `seon.fn.schema-shape/typed-key-facts` (`src/seon/fn/schema_shape.clj:171`) | canonical shape readers and binding reconstruction | seon.schema-test: same key grammar at map-entry and binding-entry; no hand-maintained second taxonomy |
| 5 | Replace copied typed key entries by canonical literal key concern; preserve required order, fingerprint and optional properties. | A entry; R C.4 | delete; add | seon.fn.schema-shape/entry-row | canonical shape readers and binding reconstruction | seon.schema-test: same key grammar at map-entry and binding-entry; no hand-maintained second taxonomy |

### `resources/seon/schemas/seon.test.failure.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 4 | Populate durable canonical path and line on the successful recognized-site writer path FIRST, then delete the file ref. Current writer drops reported-file/line, so a portability fallback alone is insufficient (H N11). Preserve original reported text separately only when meaningful. Delete seen-count/last-seen-at; derive occurrence aggregate from recorded runs/history. Delete value/report authored mirrors; selector-derived forms retain ordinal and context tuples. Enforce expected/actual inline/blob coherence; remove signature identity negation. | A failure; R N11/N31/N36/N41/B.2/J4 | delete; add | seon.test.runner/failure-report, prepare-failures!, failure-replacement-tx; seon.cluster.source preservation | seon.test/render failure readers; runner result projection | seon.test.runner-test: missing file row does not lose path; repeated failure aggregate and ordering agree with history; invalid inline/blob pair refuses |
| 4 | Add absence-condition docstrings for surviving optional entries: `:seon.test.failure/actual`, `:seon.test.failure/actual-blob`, `:seon.test.failure/actual-size`, `:seon.test.failure/contexts`, `:seon.test.failure/expected`, `:seon.test.failure/expected-blob`, `:seon.test.failure/expected-size`, `:seon.test.failure/line`, `:seon.test.failure/message`, `:seon.test.failure/reported-file`, `:seon.test.failure/signature`, `:seon.test.failure/throwable`. Keep optionality unless the specific required/transition row above changes it; for renamed entries apply the condition to the replacement. | A seon.test.failure reset row | add | `seon.cluster.source/result-preservation-tx` (`src/seon/cluster/source.clj:392`); `seon.test.runner/failure-replacement-tx` (`src/seon/test/runner.clj:2057`); `seon.test.runner/failure-report` (`src/seon/test/runner.clj:210`); `seon.test.runner/prepare-failures!` (`src/seon/test/runner.clj:1992`) | seon.test/render failure readers; runner result projection | seon.test.runner-test: missing file row does not lose path; repeated failure aggregate and ordering agree with history; invalid inline/blob pair refuses |

### `resources/seon/schemas/seon.test.run.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 4 | Require basis-t/branch/program-digest; remove inherited digest identity false. Derive provenance projection from canonical run (tested-branch remains a distinct optional fact), delete hand-written duplicate. | A run; R B.10/N36 | required; delete | seon.test.runner/provenance, record-tx; seon.cluster.source/record-results-at-head! | test evidence preservation/selection/rendering | seon.test.runner-test: absent provenance fails recording atomically, exact tested branch/basis survive adoption |
| 4 | Add absence-condition docstrings for surviving optional entries: `:seon.test.run/git-sha`, `:seon.test.run/tested-branch`. Keep optionality unless the specific required/transition row above changes it; for renamed entries apply the condition to the replacement. | A seon.test.run reset row | add | `seon.cluster.source/record-results-at-head!` (`src/seon/cluster/source.clj:494`); `seon.test.runner/provenance` (`src/seon/test/runner.clj:1977`); `seon.test.runner/record-tx` (`src/seon/test/runner.clj:2095`) | test evidence preservation/selection/rendering | seon.test.runner-test: absent provenance fails recording atomically, exact tested branch/basis survive adoption |

### `resources/seon/schemas/seon.wake.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 4 | Delete unanswered hand-written pulled shape after deriving its selector form. | R B.2 | delete | wake query/result projection | seon.turn wake answering and cluster wake | seon.cluster.wake-test: exact unanswered query/selector result validates and answer basis unchanged |

### `resources/seon/schemas/my.background.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 5 | Rename receipt contract to result and update all call/output contracts; retain same effect fact family. | R B.11 | rename | my.background and seon.background result construction | background callers/render | seon.background-test: background result contract works through actual effect owner |

### `resources/seon/schemas/my.turn.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 5 | Remove both render/form properties with existing AI/HTML pair conversion; no new form output family. | R B.12 | delete | turn pair declarations/renderer | render walk and turn UI | seon.repl-parity-test: turn forms and stored evaluations preserve visible behavior |

### `resources/seon/schemas/seon.ai.attempt.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 5 | Delete usage-edn; store typed seon.ai.usage fields once. settings-edn becomes settings-payload opaque provider snapshot string with explicit docstring, distinct from logical settings. Actual attempt instant stays. | R N23/J3; A AI attempt | delete; rename | seon.ai attempt construction; seon.turn/record-attempt! | usage totals/billing and provider request debug | seon.ai-test: provider usage maps to typed fields; no duplicated usage bytes, opaque snapshot preserved |

### `resources/seon/schemas/seon.cluster.wake.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| contract-only | Replace offer-result true/false/nil with :delivered/:refused/:no-channel; all callers handle total outcomes explicitly. | R N43 | retype | seon.cluster.wake offer/delivery functions | agent armer and wake callers | seon.cluster.wake-test: each actual channel outcome named; absent channel never healthy |

### `resources/seon/schemas/seon.flow.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 5 | Alias workload to seon.fn/workload; delete duplicate enum. Keep explicit :io/:compute assignments. | R N37 | retype; delete | flow graph constructors | flow proc contract consumers | seon.schema-test: both names resolve to one canonical workload definition |

### `resources/seon/schemas/seon.fn.binding.entry.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 5 | Replace default-edn by default literal union; use the single map-entry key concern instead of typed siblings/key-kind; retain spelling as genuine :explicit/:keys/:strs/:syms grammar with docstring. | A binding entry; R N22/N27/C.4 | rename; retype; delete; add | seon.program/binding-entry-row; seon.fn.schema-shape/typed-key-facts | binding reconstruction/contract readers | seon.program-test: keyword/string/symbol keys and nil nested default round-trip without storing top-level nil |

### `resources/seon/schemas/seon.render.cost.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 5 | Add event identity from seon.id and relation to the actual render/evaluation being measured; writer carries subject rather than infers it later. Keep shape/profile/token/instant facts and explicit relation lifecycle. | R A.2; A cost | add; required | seon.render/render-cost-fact and its callers | cost/calibration queries and parent validation | seon.render.entity-pairs-test: recorded cost points to actual subject; no subjectless cost accepted |

### `resources/seon/schemas/seon.render.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 5 | ai/html storage leaves the EDN codec for native qualified-symbol values (X7); this is a render-consumer publication, not the program-symbol retype. Move rendered AI value to rendered and HTML value to existing hiccup shape under a distinct output value key. Retire form selector/property; AI/HTML functions choose forms. Update failure/output maps and all schema property projections before narrowing. | S §1.1; A render; R B.12 | retype; delete; add | seon.render output construction; seon.schema property projection; seon.instrument contract-definition acquisition; ten pair declarations | render walk, value, ns, test, repl, web and transcript | seon.render.entity-pairs-test and seon.repl-parity-test: generated forms execute, AI text/HTML values satisfy separate contracts, stored pair datoms symbols |

### `resources/seon/schemas/seon.schema.map-entry.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 5 | One key literal union through existing codec plus fingerprint; delete key-edn, key-kind and eight typed sibling storage keys. Retain a typed search projection only if a real query needs it, derived from the one value rather than another stored authority. | R N22/C.4; A key-kind | add; delete | seon.fn.schema-shape/typed-key-facts, entry-row; seon.program/binding-entry-row | schema shape decode, binding readers and queries over literal keys | seon.schema-test: all eight scalar key cases and collection literal case round-trip; fingerprints stable |

### `resources/seon/schemas/seon.test.accretion.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 5 | Rename report-edn to report-payload; preserve opaque report bytes/no-history policy explicitly, with blob/size coherence and no duplicate entity schema. | R N23 | rename; add | seon.test.accretion report construction; turn settlement | accretion result rendering | seon.test.accretion-test: report inline/blob path unchanged and old key absent |

### `resources/seon/schemas/seon.fn.ast.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 6 | Delete resource and ast-node writer after Q2 replacement. Do not separately retype type, add child ordinals or add provenance sentinels here. | A AST superseded; R C.2/B.4/N16/N29 | delete | seon.program/ast-node, contract-facts; seon.fn backfill | Q2's seon.fn reconciliation and seon.turn field exclusion | seon.program-test: canonical schema.shape preserves contract structure; no production fn.ast read/write remains |

### `resources/seon/schemas/seon.fn.ast.entry.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 6 | Delete resource and ast-entry/property-entries/scalar-entry legacy constructors with the AST owner. No literal-codec work on a deleted family. | R C.2; A AST entry; N22 | delete | seon.program AST constructors | AST parent only; remove its links | seon.program-test: canonical map entry/tuple ordering survives AST removal |

### `resources/seon/schemas/seon.maintenance.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 5 | Replace receipt-facts with the merged result facts; collection-record joins result id/completed-tx; result-entity request/response derive the actual operation union rather than unconstrained map. Keep result-projection symbols. | R B.11/C.3, transitive consumer of maintenance merge | rename; delete; retype | seon.maintenance/result-entity and report/collection constructors; seon.schedule/settle-call | maintenance render/report/collector | seon.maintenance-schema-test and seon.schedule-test: one lifecycle/result root, all operation components validate, no duplicate result identity or receipt facts |

### `resources/seon/schemas/seon.cluster.status.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 1 | Include the ref alias in the installed-attribute census. Classify at its actual storable use, never infer a stored relation merely from an API alias mentioning ref. | R C.1, ref census completion | add | cluster status projection and seon.schema bridge | operator cluster status | seon.schema-test: every installed ref classified; nonstored observations do not acquire entity ownership |

### `resources/seon/schemas/seon.context.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 4 | Derive comparison baseline/current evaluation ref result shapes from their actual selectors; API vectors do not imply stored cardinality or new living relations. | A derived reader rule; R C.1 census | retype | seon.context comparison projection | context comparison/debug | seon.context-test: comparison validates actual pulled evaluations without a duplicate entity map |

### `resources/seon/schemas/seon.sci.eval.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 2/4 | Follow symbol identity and canonical evaluation changes through result/output aliases, including ns ref read shape. This is a held no-default-cluster seam until released. Do not change its custody behavior here. | S §2.3; A evaluation; R B.1 | retype | seon.sci.eval evaluated result construction | seon.turn settlement and SCI callers | seon.sci.eval-test: real evaluation produces the canonical admitted result and symbols, no inferred default custody |

### `resources/seon/schemas/seon.print.edn`

| Group | Edit | Source finding | Kind | Writers in the same publication | Readers in the same publication | Regression that proves it |
|---|---|---|---|---|---|---|
| 1 | Preserve genuinely polymorphic reference grammar (symbol or ref) at the value-render boundary; classify only installed stored attributes, not this API union. Document this census exclusion. | R C.1; A ref grammar | add | seon.print value construction and schema bridge | value renderer | seon.schema-test: ordinary symbol values render without being treated as entity custody |

### Cross-resource completeness rules

The 76 resource tables above are a dated checklist, not a new schema registry.
Before the reset, derive its coverage again from the canonical merged
population. A resource added or removed by an active continuation must be
reconciled into this list. The required **installed-ref census** checks actual
storable attributes after aliases/property projection; a text count of ref
mentions includes API schemas; native installed ref/component properties are the authority.

For each surviving storable ref in **every** table: existing component
ownership cascades, required keys refuse a sweep through whole-entity validation,
and optional refs may deliberately sweep (K). There is no classification property
or universal living-ref rule. For each mention that must survive: store a value of
the target identity type. Verify the identity domain before narrowing it (H N3).
This catches transitive sites not named in R N1–N11, including maintenance
handler and lint function references. `seon.maintenance.receipt/handler`
becomes a qualified-symbol value in the merged result root; a historical
handler observation must survive deletion. `seon.lint/fn` likewise records a
qualified-symbol observation rather than mutable entity custody. Their
writers are `seon.schedule/fire-call` and `seon.fn/lint-rows`; maintenance
queries and lint/detector readers join through the symbol. Extend the same
real-handler/deleted-definition regressions, not a second string-ref path.

S §2 remains the exact coercion-site inventory: fn/program, sci/eval,
test/selection/runner/arm/accretion, issue/opening/detect, effect, schedule,
turn, error, instrument, render/ns/test/transcript/web, problems,
cluster/source/agent, call-preparation, plan, db, schema, repl, bootstrap,
run, sci/kernel, search, maintenance and `script/seon/fresh_operator.clj`.
All lookup refs and Datalog constants use the new native identity, and every
emitted executable symbol is quoted as data where the called API expects data.
Fixture literals and lookup refs change with those readers, including
`test/fixtures/call_graph_fidelity/declarations.txt` and
`test/seon/run4_replies.edn`. No blanket replacement of genuine strings.

A's optional conditions remain required documentation work, but deletion wins
over documenting a removed attribute. Renamed lifecycle facts inherit their
specific pre/post-transition condition; they are not all required at creation.
Stored nil remains forbidden. A codec union may describe a nested literal nil
without allowing an absent top-level attribute to be stored as nil; use an
explicit literal container for nil-valued keys/defaults where needed.

**R B.5 is retained:** argument and binding are parent/component concerns, not duplicate families; both remain. Only their duplicate scalar encodings and missing parent validation change. Likewise B.6 keeps error signature versus occurrence, B.7 keeps capture versus contribution, and B.9 keeps the distinct operation component trees.

The maintenance merge deliberately avoids naming a second family “execution”
or making two unrelated `result/id` attributes. Existing result operation
components remain components of the one lifecycle root, and handler/request
facts must be supplied before the root's new required creation group is armed.
A completed result requires exactly one operation component or error; an open
result is identified by its start transaction and lacks terminal evidence.

R's generic provider names map as follows, all under `:seon.ai.model/` with
their owning entity changed to the provider descriptor or its window child:
`deepseek-off-peak-windows → off-peak-windows`, `deepseek-window-id → window-id`,
`deepseek-utc-start/end → window-utc-start/end`,
`deepseek-regular-price-factor → regular-price-factor`,
`deepseek-peak-price-factor → peak-price-factor`,
`deepseek-pricing-schedule-status → pricing-window-active-tx`,
`meta-search-usd-per-kquery → search-usd-per-kquery`, and the four
`meta-{free,paid}-{requests,tokens}-per-minute` keys lose `meta-`.
External announced effective time remains an observed value; recording a
schedule is not activating it. Provider changes load the llm-providers skill
before implementation and retain the current provider request semantics.

**J7 resolved from source:** `src/seon/effect.clj:568` obtains content-blobs by
mapping digest over content-stages; `:350–354` asserts each digest as a separate
datom. `rg -n 'content-blobs' src` found only those write paths. There is no
reader assembling ordered bytes from this collection. Use a set of attachments;
do not add ordinals. **N34 resolved:** `src/seon/program.cljc:691–692` writes
both argument/order and argument/index from exactly `(long order)`; keep order.

## Q1 — settled: program deletion refuses surviving obligations

J §2.1–2.6 and the owner's current instruction replace the universal living-ref
proposal. No fork change and no override. The final-report validator already
receives attempted and effective datoms, before and final databases. An affected
eid contributes every prior program identity value that no longer resolves in the final database, including identity retraction or rename while other datoms survive.
For each deleted identity, read indexed obligations in the final database:
function calls/references and test subjects by qualified symbol; namespace
requirements by namespace symbol. A same-transaction replacement that no longer
names the target is a repair. Deleting the caller too also removes its
obligation. Redefining the same identity is not deletion.

The flat refusal carries **every** surviving caller and the edge attribute,
grouped by deleted identity, with the before/final basis as evidence. Never
take, truncate or clip the data payload. Use datoms/AVET rather than wildcard
pull so the dependency's 1,000-member cap cannot hide a caller. Presentation
elision belongs to the existing render profile.

Test reach is excluded from refusal. Keep its symbol members, report the
tests whose recorded closure became stale on a committed deletion, and have
changed-since-green select unresolved members as changed dependencies. A
declared subject is an obligation and does refuse. A test deletion has no
program-edge obligation merely because it is a test; ordinary retention rules
still apply (K §5f records the issue-tests coupling until citation values land).

The schema-key extension uses keyword obligations (writes, schema references,
arity input/output/guard refs) after those values and every writer/consumer
land together. J's dated connection-key example is 182 grouped breaks
(4 writes, 32 schema references, 146 input refs); keyword mentions are excluded.
Do not reconstruct erased ref edges and pretend they distinguish a repair.

Three origin proofs belong to the same acceptance:
1. Real SCI admission reaches the writer and returns the flat refusal mid-turn;
   no declaration transaction commits.
2. Incremental publication with a surviving caller outside the changed set
   refuses; the previous source commit stays selected and bin/seon init reports
   the complete refactoring payload legibly. Deletion plus caller repairs in
   the same publication succeeds.
3. Fresh complete publication contains no deleted old identity, so this rule
   is vacuous. Report unresolved edge symbols positively as the work list.

Canonical regressions use the real named declarations from J: open? (dated
5 callers), seon.turn (176 declarations / 67 requirers), connection (182
grouped declaration breaks), and a test deletion. Derive current membership
from the fixture; the dated counts are evidence to compare, not a hand-rostered
fixture or an assertion that HEAD cannot accrete. Include native and expanded
transaction calls, atomic refusal, repair/delete in either order, >1,000
callers, and unresolved fresh edges. Measure indexed check cost; no latency
number is claimed before a run.

RESET NEEDED for ref-to-symbol types. No default lifecycle action, bin/test,
capability-fn decision, listener edit, agent deletion or shape reclamation is
authorized by this seam.

## Q2 — option A authorized by §1i owner direction

Pinned falsification command (ordinary tooling, no production regex):

```sh
git grep -n -E 'seon\.fn\.ast|:seon\.fn/ast' \
  0c7711e590397182c5860326c6599a6ad7848416 -- src/
```

**Result: 29 matching lines in three files.** `src/seon/program.cljc` has
24 construction lines; `src/seon/fn.clj:2277,2283,2294,2314` has four
backfill/pull/retraction reads; `src/seon/turn.clj:1192` has one exclusion of
the AST field when forming the declared map. (The two separate searches for
`seon.fn.ast` and `:seon.fn/ast` overlap once; do not add their counts.)
The **literal “no production reader” claim is false**: backfill reads AST
presence and retracts it. The narrower claim holds at this HEAD: no
independent runtime consumer traverses the stored fn.ast tree to recover its
semantics. Do not omit those maintenance readers from the edit.

| Option | Guarantee | Price | What we give up |
|---|---|---|---|
| **A — merge the remaining role into existing schema.shape, then delete fn.ast (recommended under R C.2's literal fallback)** | Preserve the canonical contract shape through existing arity input/output/guard and schema.shape owners; route reconciliation to provenance plus those actual contract facts. Remove AST construction, presence checks, attribute and both resources in one final publication. Prove public contract queries and changed-contract detection still work. No second AST encoding and no mandatory new tree when the canonical shape is already present. | Estimate **1 engineering day**, mostly fn/program/turn and canonical fixture regressions; zero proposed dependency changes. Storage loses the redundant forest; measure datoms before/after rather than claiming the review's approximate 31 declarations as an exact count. | The old fn.ast addressability disappears. Existing canonical shape/arity facts answer the contract query; arbitrary AST-specific paths are not preserved. |
| B — delete without carrying any AST-specific role forward | Remove the same writers/readers/resources; provenance alone answers “analysis ran,” and existing spec/arity facts remain as-is. Prove no external semantic consumer relied on the forest. | Estimate **half a day**, plus the same armed regressions and final grep. Smaller implementation if no canonical-shape gap is found. | No promise to preserve even a hypothetical AST-only contract query. If tests or a new reader establish such a requirement, this option must return to A before deletion. |

Option A is authorized by the modeling-study owner direction (bd5923a8c). A honors the review's “if a reader exists, merge” rule without inventing a new
shape family. Re-run the pinned query at the final integration HEAD and inspect
any new hit before removal; the working tree is being edited by E now.
Under either option the group-6 prerequisite is a green repeated-publication
and changed-contract regression, not grep alone. R N29's lost child order is
resolved by schema.shape's existing ordinals rather than patched on doomed AST
attributes. Do not run AST deletion while E owns its source files.

## Reset procedure — orchestrator only, after review and implementation

1. Collect group commits, held-file release reports and namespace fast results.
   Verify the complete integrated schema/constructors/readers are present.
   Read each E/no-default-cluster continuation's final report before takeover.
   Record HEAD, tree residue, source publication identity and outstanding
   boundaries here. Preserve unrelated edits; no branch switch or broad stage.
2. Confirm the old-default reverse-walk capture exists and is readable before
   destruction. Use the E probe and captured result below; do not relabel it a
   post-reset number. Confirm no live runner owns any obsolete test basis/root
   before removing only invalid basis/exhaust. Do not sweep foreign live runs.
3. With the complete batch ready, the orchestrator executes **exactly in this
   order**, awaiting each successful exit (the following is a sequence, not a
   shell chain that ignores failure):

   ```sh
   bin/seon stop default
   bin/seon init default --force
   bin/seon start
   bin/seon init --dev default
   ```

   This is the owner's selected boundary. Confirm forced init acquired the new
   publication and installed the new attribute types before proceeding; if it
   refuses, report the exact operator error, do not improvise a second reset or
   migrate the old store. Other clusters retain their custody. Build manifests
   come from publication, never hand-patched serialized rows.
4. Reach new default with MCP runtime status and explicit root/cluster. Reseed
   through the **canonical Juniper fixture**, keeping provider calls disabled.
   Current existing live wrapper (revalidate after renamed schema consumers
   land) can be invoked as one JVM form:

   ```clojure
   (do
     (load-file "docs/prds/context-generation/research/juniper_fixture_2026_09_06.clj")
     ((resolve 'juniper-fixture-2026-09-06/install!) "default"))
   ```

   That wrapper calls `seon.context-blocks-fixture/install-running!` from
   `test/seon/context_blocks_fixture.clj`, using default's actual handle,
   routing and projection. Its canonical installer disarms before replacing
   scenario/history facts and waits the armer acknowledgement. Do not call
   seed! against a running agent graph or implement a second installer. A
   renamed-key fix required by the batch lands in this wrapper and canonical
   fixture before reset. A refusal is a failed reseed, not a partially seeded
   “ready” system.
5. Converge: wait on adoption/readiness facts with declared bounds. Compare
   default's `:seon.source/commit-id` to `seon.cluster.source/current`, verify
   installed fn/test identity type symbol, calls/references/reach symbol-many
   with indexes, tuple callee symbol, required source digest and the ruled Q1 behavior using native ref/component properties (no classifier). Confirm actual Juniper orders, plan, namespace, opening
   evaluation and routing are present. Observe namespace/debug page separately;
   a matching source id does not prove paint. Record source ids, basis,
   schema facts and any health errors rather than declaring silence green.
6. First cold gates are **serial**, namespace-scoped, by the orchestrator:

   ```sh
   bin/test --platform
   bin/test -- seon.db-test seon.schema-test seon.program-test seon.fn-test
   bin/test -- seon.test-reaching-test seon.test.selection-test seon.test.runner-test seon.source-reconciliation-test
   bin/test -- seon.sci.eval-test seon.eval-test seon.rereads-test seon.repl-parity-test
   bin/test -- seon.issue-test seon.issue-deletion-test seon.schedule-test seon.maintenance-schema-test seon.effect-test
   bin/test -- seon.config-test seon.ai-test seon.instrument-test seon.error-class-schema-test my.plan-test
   bin/test -- seon.context-test seon.context-capture-test seon.cluster.wake-test seon.render.entity-pairs-test seon.render.transcript-test
   ```

   These namespaces exist in the inspected tree (issue-deletion is E's in-flight
   regression). Re-anchor names after consolidation. If foreign in-flight files
   still exist, the orchestrator substitutes `bin/test --paths <all explicit
   batch paths> -- <same namespaces>` so the gate proves HEAD plus the complete
   batch; never just schema paths without their writers. Record cold gate run
   ids, namespace counts and exact failures. No `--all`/`--full` lane run and
   no green claim based on fast iterations alone.
7. Execute and save the live proofs below. Commit the measured evidence and
   authority updates with the integrated batch. The single-reset obligation is
   complete only when those live facts and cold gates are proven, not when the
   four commands exited. Implementation must update AGENTS/PRD stale rules with
   their owning schema commits; this plan does not silently edit them early.

### Live proofs after the one reset

| Proof | Exact observation to record | Pass condition |
|---|---|---|
| Reverse-walk parity number | Run [E's probe](../research/edges-symbol-walk-probe-2026-09-16.clj) against one immutable new-default database; record source commit, basis, actual identity/edge counts, map acquisition cost, median/p95, edge bytes and set equality. Baseline [captured EDN](../research/edges-symbol-walk-baseline-2026-09-16.edn): basis 536871871, 6,975 identities, 65,119 edges, 260,697 printed edge bytes; three-target ref median **452.444458 ms**, p95 **562.141042 ms**; closures 116/87/3,056. | Write the **new mapped-symbol median/p95 and ratio** beside baseline; compare equivalent captured populations for parity and label changed populations separately. Include map cost once per operation, deleted/redefined targets, empty closures and cycles. Probe covers calls/references, not the complete declared-dispatch/file-uncertainty gate query. Before-reset numbers alone never establish parity. |
| One actual deletion on default | Create disposable A calling B through ordinary agent admission. Attempt B deletion alone: capture the flat refusal and unchanged basis. Repair A and delete B in one transaction, then query history and stale test reach. Separately query unresolved symbols from the fresh publication as its positive work list. | First deletion refuses with complete A→B payload; repaired deletion commits; stale reach retains B and selects rerun. History sees B before deletion. The unresolved query answers explicitly (empty for the repaired pair), never by swept absence. |
| One agent-authored definition provenance | Submit a contracted definition through fixture submit!/real agent turn, not shared debug SCI or direct hand-built program row. Pull admitted source/namespace/admission/digest and recorded evaluation. Compare digest with the analyzer's exact input including its resolver prelude. | A 64-hex digest matches actual analyzed input; definition is agent-admitted, callable, and remains proven analyzed when its calls set is empty. No requirement to fake a file or keep a compactable evaluation ref. |
| One archived issue retracted | Create/index a disposable ordinary note via existing issue owner; retain before basis and component citation identities. Move its note to archive through the existing index/adoption path; do not pick a protected/started issue. Query current issue and component citations and their history. | Current issue and owned citations absent; historical issue present; cited noncomponent targets survive. Started issue last-test removal still refuses under its separate regression; no forced bypass. |

Unresolved-call query shape to verify after deletion (public owner may return
names/basis around these rows, but must not lose test callers):

```clojure
'[:find ?caller ?callee
  :where
  [?caller :seon.program/analyzed-source-digest]
  [?caller :seon.fn/calls ?callee]
  (not-join [?callee] [_ :seon.fn/sym ?callee])]
```

Record success with a positive result envelope even when there are zero
unresolved calls. Missing population/schema/query failure is typed unknown.
The probe scripts and results are durable research artifacts; before-reset
captures remain dated evidence and are never rewritten as the new result.

## Verification boundary and stop

Step 1 changed **only this plan**. Its evidence was source/authority reading,
source grep, existing commit inspection, descriptor status and MCP health.
Step 1 made no production edit, gate, new performance measurement, default mutation,
restart/reset, publication or browser proof. Step 2 evidence follows below. Foreign source edits remain
untouched; their existence is a boundary for the affected implementation seam,
not a reason to omit an unowned resource from this plan.

After review, implement released unowned groups in order, each green under
`bin/test-fast --paths <explicit owned paths> -- <affected namespaces>`.
If the shared tree cannot load, use `git worktree add tmp/reset-batch-wt HEAD`,
link its `reference-code` to this checkout, share the test-slot admission path,
and overlay only the owned diff; await the bounded test process and remove
only this lane's scratch root/worktree afterwards. Record any foreign failure
by raw log/path and verification boundary, not an unproven attribution.
Never resume, message or edit another lane's session. A held file blocks its
seam, not independent authorized work.

**Step 2 authorizes Q1 program deletion refusal on released seams. Report at the first coherent schema/refusal/test landing or a concrete held-consumer boundary. Q2 option A is authorized; preservation and repeated-publication proofs precede removal.**

## First released seam — capture prompt history (group 5, R J6)

Owned production file: `resources/seon/schemas/seon.context.capture.edn` only.
Owned new regression: `test/seon/context_capture_history_test.clj`.
Both were clean/unowned before editing. No prompt assembly, provider, turn,
shared fixture or held runner file was changed. G5/digest work remains bounded
by modified schema/datahike, schema/edn and seon.source.edn; it was not silently
abandoned or implemented over another lane's hunks.

Live read before edit (MCP JVM, explicit default custody, 7 ms): 31 captures;
`:seon.context.capture/prompt` installed as string with `:db/noHistory true`.
The canonical regression uses seed-cluster!, agent/creation-tx, turn/open-tx,
context/capture-tx and transacted!, then retracts that capture and queries
history/as-of. It does not hand-build a capture/turn schema or bypass admission.

Red fast iteration, HEAD `9e807c084329db4fa7918dbac72e1ead14c39888` plus the
new test: **1 test, 6 assertions, 2 failures, 0 errors**. Current prompt and
character count passed. After retraction as-of prompt was nil; history prompt
rows were empty. Complete command:

```sh
bin/test-fast --paths test/seon/context_capture_history_test.clj -- seon.context-capture-history-test
```

Issue: [captured prompt history is disabled](../../../seon/issues/captured-prompt-history-is-disabled.md), open until the reset-boundary cold/live proof.

Fix: remove no-history? from the prompt leaf and document its temporal evidence
semantics. The same capture writer and identity remain. This retains future
prompt history; it cannot restore already-discarded history in old default.
The integration reset remains the live boundary. Green fast iteration at HEAD
`8e74014d6b22f414861f62633ae0fda309e40f73` plus the two owned paths:
**1 test, 6 assertions, 0 failures, 0 errors**, exit 0, completed
2026-09-16T23:34:36Z with contracts armed in panic mode. Command:

```sh
bin/test-fast --paths resources/seon/schemas/seon.context.capture.edn test/seon/context_capture_history_test.clj -- seon.context-capture-history-test
```

No bin/test or default lifecycle command was run. The runner removed its
selected snapshot after exit. This is fast iteration evidence, not the
orchestrator's pending cold/live reset proof.

Operational observations: the first attempt printed the in-flight runner's
`BASHPID: unbound variable` warning and was terminated by TERM during the session
interruption; it established no verdict. The resumed red run completed. Its
snapshot and the green snapshot additionally reported a generated `.phase-watchdog`
file. Those are held bin/test observations, not an attribution of the test
failures, and this lane did not edit the runner. Fast test slots remained at
three; no separate worktree or second slot pool was created.

Live publication boundary: hook publication
`e104fbd7-5510-463b-b435-9a2466dfeed5` returned
`:seon.cluster.source/scratch-schema-refused` with
`:seon.schema/unresolved-predicate seon.search/handle?` (no admitted callable
in the source projection). Evidence is its result under `tmp/source-publications/`
and `logs/current-source-failure.log`. This is the exact refusal, not a proven
attribution to another editor. `search.clj` is assignment-held; it was not
edited. No adoption convergence or live behavior claim is made. This boundary
does not block the owned HEAD-plus-paths fast test or authorize a reset.

## Program deletion seam — held-consumer boundary after J/K/X correction

At `2ee83761db526af9dec990024ba8322dd92045a9`, fn/program/db and the named
schema resources are clean. Cluster/source were modified at entry and became
clean during this investigation. The remaining **direct retype dependency** is
modified, assignment-held `src/seon/test/selection.clj:115–125`: `row-edges`
accepts only vector-valued calls/references/subjects. Changing those producers
to symbols makes the existing selector drop every edge, silently returning an
incomplete test set. HEAD-plus-owned-paths cannot fix this semantic dependency:
the HEAD version has the same vector filter. A schema-only commit would violate
the required schema/consumer publication unit and would make a green selected
run vacuous.

Required matching edit: `row-edges` must translate symbol edge members into
the same identity domain used by its seed/reverse index, or convert that whole
walk to symbols; its regression must select an unchanged test through a
changed callee using the new artifact shape. This is part of the requested
schema/refusal seam, not a separate generic runner cleanup. Release this
specific consumer or have its current owner land the matching change. No other
lane was messaged or operated. No source/schema retype was exposed while this
consumer still discards it.

Further origin boundaries remain the assignment-held SCI evaluation seam and
the publication consumers until their release is established. At entry,
`cluster/namespace-requires` joined requirement values as eids, and
`cluster.source` preserved reach through identity refs/tombstones. These must
change with the retype even though those files are now clean. The schema-key
182-break extension also reaches currently modified `src/seon/schema.clj`.
Do not describe any of these origins as proven from a direct writer test alone.

Read-only default probe (5 ms): calls/requires both `:db.type/ref`; open? callers
are recover-call, receipt-run, render-ai, require-open-run and open-run-tx-call
in seon.turn (5); seon.turn requiring namespaces = 67. The committed probe and
landing note retain the exact query. This verifies the inherited model, not
the proposed refusal. Default remains PID 41413; no transaction or lifecycle
operation was performed. No bin/test or fast test was run for this docs-only
correction. Production implementation is **not landed** at this boundary.

## Resumed edge batch — writer-cost prerequisite (2026-09-17)

Tier 1 `5a5359205` is owner-reviewed and accepted. The next publication is
the edge retype, with **RESET NEEDED** retained. The two new blocker notes
were read end to end: required-many declarations and unbounded final-report
validation. The 19 remaining collection keys are folded into their resource
tables above, not a second edit inventory. Activation's six keys were already
fixed at `543f03258`. Required collection presence is never an event; the
producing scalar/ref fact and parent validation own that distinction.

Before exposing the incompatible edge schemas, measure and fix the final-report
validation cost, retaining its atomic writer decision and all expanded/swept
operations. Existing validators are already cached per projection, so simply
adding another validator cache is not evidence of a fix. The sparse
`seon.id/id` write in source-test targets a populated canonical identity:
it must be tested separately from an incomplete create.

Entry boundary: `src/seon/program.cljc`, `resources/seon/schemas/seon.program.edn`,
and `src/seon/schema.clj` carry foreign uncommitted edits. These directly bound
the coordinated definition/provenance and schema-key deletion seams; they do
not prevent the owned database validator investigation. Recheck before landing.
Default was verified alive at PID 33583; no lifecycle command was run.

While measuring, `src/seon/sci/eval.clj` became dirty again: its in-flight
`build-base-ctx` now requires a projection whereas HEAD still accepts zero
arguments. The source-test `removed-source` regression is a direct consumer
and must change with that release. No compatibility arity is invented and no
foreign SCI hunk is included in the current path-isolated proof.


### Writer-cost prerequisite checkpoint

Measured full program publication to a fresh source store, with the canonical
manifest already built: **75,941.046542 ms before → 41,644.180417 ms after**
(45.16% reduction). This is not an end-to-end reset measurement. Final-report
admission still checks every attempted attribute and affected entity. Native,
expanded, swept-reference, render-target and prepared-arity decisions remain
on the writer. The sparse source test now distinguishes an existing complete
row's upsert from an incomplete create; both are proven on the canonical fixture.

The combined fast run's fn/source namespaces passed; its sole database failure
was a stale config/effective caller after `cdfc01058`. Supplying the fixture's
explicit cluster preserved the intended row-identity refusal. The corrected
database/schema run passed **75 tests / 1,007 assertions**, with unchanged
production bytes. Exact runs and limitations are in the
[writer-cost landing note](../../context-generation/research/reset-writer-cost-2026-09-17.md).

Rechecked at `7e193e85b`: `resources/seon/schemas/seon.program.edn` and
`src/seon/test/selection.clj` are released. `src/seon/program.cljc`,
`src/seon/schema.clj`, and `src/seon/sci/eval.clj` remain foreign-modified direct
consumers of the coordinated edge/provenance change. This checkpoint lands
the measured prerequisite only and stops for review. **The edge retype is not
landed; RESET NEEDED remains for that publication.** Default stayed PID 33583;
no default publication, restart or reset was performed by this lane.

## Worktree integration and owner corrections — 2026-09-17

The reset publication is implemented on branch `reset-batch` in
`tmp/reset-batch-wt`, based initially on `b7863b176`. S3 and program-ops hold
`program.cljc`, `schema.clj`, and `sci/eval.clj` in the shared checkout;
this branch changes its own copies only. Rebase here with
`git -C tmp/reset-batch-wt rebase steward-platform` as those commits land.
The orchestrator, after the holders release and the branch's fast results
are reviewed, merges from the main `steward-platform` checkout with
`git merge --ff-only reset-batch`. **RESET NEEDED**: do not adopt this
incompatible schema publication into the existing default branch. The
orchestrator owns the cold gate, platform proof, merge and single reset.
The worktree links its `tmp/test-slots` to the main checkout's slot directory;
fast iterations use `SEON_TEST_SLOTS=3` and explicit owned paths.

The latest owner rulings supersede every conflicting option above:

* Deletion has no escape. All repairs are in the same transaction and checked
  against `:db-after`; otherwise the complete refusal is the work given to
  agents. An agent's unreferenced definition retracts normally. A fresh reset
  publication is not an escape: it retracts no prior definition. Any complete
  publication that actually removes a live identity obeys the same rule.
* Delete the declaration's `:seon.fn/capability-fn` ref. The existing exact
  handler symbol `:seon.effect/capability` is authoritative and joins the
  deletion refusal set. This overturns the earlier G2 exception/N10 ruling;
  do not introduce a second handler-symbol attribute.
* Agents are never retracted. Design `:seon.agent/archived-tx` as an optional
  ref to the transaction that archived the agent, asserted with
  `:db/current-tx` by the agent lifecycle writer. `archived?` derives from
  this datom's presence; `open?` derives from its absence on an existing
  agent. Missing agent identity is unknown, not an open agent. UI population
  excludes archived agents; historical messages, sender refs, turns and
  ownership remain intact. No boolean, tombstone, or deletion API is added.
  This is the archive design for the reset batch; implementation must include
  the lifecycle writer, agent/UI readers and idempotent archive regression
  together, after the relevant shared holders release.

Implementation and fast verification are in progress; this section is not a
claim that the branch is ready to merge.

Worktree progress: rebased onto `steward-platform` at `affac5672`; retained the
new program-operation implementation and the runner's artifact-based fixture
selection, converting their reset-facing symbol consumers. Git cannot rebase
through a symlinked submodule parent: temporarily expose empty gitlink directories
for the rebase, then restore the requested `reference-code` link. Any active
owned test snapshot links directly to the main dependency checkout throughout.
No shared source path or foreign session was modified. Verification remains in
progress; this is not a merge approval.

Verification checkpoint (not green): `reset-edge-fast-3.log` reached the canonical
fixture and reported two setup errors from the remaining string-valued supplier
lookup refs. Those config declarations now use symbols. `reset-edge-fast-4.log`
then stopped during namespace loading; the fast runner exposed only the compiler
wrapper, so its diagnostic now retains the exception cause chain. The next
snapshot includes that diagnostic and the rebase. No default operation was run.
The working implementation and regressions remain uncommitted until verified.


## Modeling-study correction ledger — bd5923a8c, §1i controls

Read the modeling study end to end. This ledger supersedes contradictory earlier
pricing and historical decision text above; it does not claim implementation
proof. The incomplete-create premise was refuted at 770cf35d3: retain that
refusal. G5 closes the different identity-less entity coverage hole.

| Study table row | Change to this batch and implementation obligation | Current reset-worktree evidence |
|---|---|---|
| Symbol edges | Explicit indexes on calls/references/reach; no redundant identity index flags. Preserve advisory reach separately. | Schema edits implemented; fast 26 measured equal-population closure parity (table below). |
| Strict deletion | Compare affected prior identity values to final names, including retraction/rename on surviving eids; no escape for complete republish. Fresh population reports unresolved tokens positively. | Detection widened; final-state and publication refusal regressions passed fast 22. |
| G4 digest | Writer derives digest from exact analyzed bytes including resolver prelude. Complete definition required; core-file and agent provenance separate arms. Retain publication provenance; no compactable evaluation dependency. | Digest and separate provenance arms present; exact-input regression passed in fast 22. |
| Nineteen required-many keys | Optional stored memberships: cluster 2, arity 1, adoption 2, maintenance 14. Positive existing construction facts replace false required collections. No marker booleans; activation six already landed separately. | Optionality implemented; the four actual maintenance constructors and empty/partial outcomes passed fast 26. |
| Maintenance evidence | Parent completion cannot prove a skipped child operation. Validate four actual constructors, empty success, partial/error and absent result using their own scalar evidence. | Canonical constructor proof passed fast 26; no marker booleans added. |
| G5 components | Discover roots through before AND after; complete traversal under declared bound, not wildcard pull. Test 1,001 children, child-only edit/unlink, multiple parents, cycles and identity-less orphan rows. Owned occurrence links differ from shared shape refs. | New explicit obligations; implementation incomplete. |
| Archived agents | Install archived-tx and lifecycle writer; derive archived?/open? for UI. Never retract agents, even coordinated removal of their incoming refs. Archive does not stop graphs or detach ownership. | Co-deletion proof deleted; archive writer and derived open?/archived? passed fast 22. Namespace agent listings omit archived identities; historical direct reads remain available. |
| Capability handler | Delete declaration capability-fn; existing handler symbol is a final deletion obligation. | In worktree schema and final validator; declared handler reach regression passed fast 22. |
| Message subject/sender/protocol | §1h in one publication; option A accepted: preserve existing token grammar, remove resolution/inside-about classification; from marks inside; assignment/declination separate facts. | message-wake-model owns seon.message.edn/seon.wake.edn; do not edit. Record its landing before integration. |
| Message handling | Delete inbox-move pattern; listened to plus handling-turn claim and answering-t rule. Final authority decides claims; listeners are notifications, not claims. | Same foreign message-wake-model boundary; no substitute mechanism here. |
| Origin | eval/origin becomes issue/id value; retain generated-by display. No generic ref carrier. | Issue-ID value and reader changes implemented; deletion-survival regression passed fast 26. |
| Refreshes | Delete attribute and both refresh-call functions; latest-read derivation remains ordinal-based. No new digest/index. | Attribute deletion and regression replacement now also landed upstream; obsolete functions removed here, no remaining production references. |
| fn.ast | Option A authorized: preserve reconciliation/backfill via canonical schema.shape plus provenance/arity facts, then delete AST writers/resources. Shared shape roots remain ordinary refs. | Historical grep found maintenance consumers; the pinned production grep is empty and reconciliation/backfill regressions passed fast 22 and 27. |
| Optional refs | “Required when present” supplies no deletion guarantee. A surviving outcome/provenance condition or observation value must establish the requirement. Attempts need not have error/failover facts. | Replaces blanket recommendation; audit affected family rows before implementation. |
| Issue status | Keep authoritative status until positive writers cover imported resolved AND superseded notes. | Already retained. |
| -at→-tx | Recording/transition time only. Preserve distinct external occurrence time and never infer asynchronous success solely from transaction provenance. | Qualifies earlier rename inventory. |
| Pulled contracts | Derive selector contracts; unsupported selectors refuse honestly. Limit/recursion are valid dependency features. Complete validation versus bounded presentation with elision are separate obligations. | Existing derivation retained; G5 proof incomplete. |
| Shared shapes/GC | No new collector. Measure logical roots first; storage GC cannot retract live unreferenced shape rows or erase temporal history reachable from head. | Existing shared shape model retained; reclamation remains out of scope. |

RESET NEEDED. Work remains exclusively on `reset-batch`; no default adoption or
reset is performed by this lane. After green review and rebase onto
`steward-platform`, the orchestrator merges with `git merge --ff-only reset-batch`
and runs the reset procedure above. The message/wake resource boundary is a
publication dependency, not permission to weaken or separately publish §1h.

### Integration evidence — 2026-09-17, reset-batch worktree

The complete modeling study has been read end to end. The correction ledger
above governs this branch. Implementation remains **uncommitted and incomplete**;
this is not a merge authorization or a green reset group. Fast iteration 16
(`tmp/reset-edge-fast-16.log`) ran 38 tests / 288 assertions with one parity
failure and no errors; the eleven reset-edge regressions passed. Broader fast
iteration 15 ran 187 tests / 1,766 assertions with 148 failures / 24 errors,
including old ref-valued fixture edges and incomplete synthetic definitions.
Those results must not be represented as cold-gate proof.

The implementation now detects identity removal/rename, stores indexed edge
values, refuses agent identity retraction, and derives archive state from the
positive archive transaction. AST writers/resources have been removed in the
worktree and arity input/return shape links retain their shared ownership.
The outstanding G5 owning-root traversal and completeness regressions remain
required; attribute validation and identity-selected entity validation alone do
not close the identity-less child hole. The message/wake resources remain the
foreign message-wake-model lane's boundary; merge its landing in the same reset
publication. No default process operation or live schema adoption was performed.

**RESET NEEDED.** Preserve this worktree until the group is green and reviewed;
the orchestrator then runs `git merge --ff-only reset-batch` from
`steward-platform` and owns the reset and cold proof.

### Rebased integration checkpoint — 2026-09-17

The worktree now incorporates `024991490`, including message-wake seam 1
`a50424f6b`. This branch did not edit `seon.message.edn` or `seon.wake.edn`;
that owner's complete §1h publication remains the foreign boundary. The
upstream invalid-note fix superseded this branch's duplicate classifier, and
upstream immutable test-run diagnostics were preserved while removing stub
minting. The upstream uniqueness regression now exercises a real identity
conflict, so no synthetic replacement attribute is needed.

Fast 22, before this rebase and the origin/refresh follow-up: **189 tests,
1,811 assertions, zero failures and zero errors**. This is fast iteration,
not a cold gate or reset proof. Fast 23 runs the subsequent origin,
maintenance, fault-recording and program-operation changes against its captured
pre-rebase snapshot; another integration run is required after the rebase.

Pinned production grep after the rebase found **zero matches** for
`identity-tombstone`, `write-tombstone`, `function-identity-call`, `seon.fn.ast`,
`:seon.fn/ast`, or `:seon.fn/capability-fn` under `src` and `resources`.
Reconciliation/backfill behavior passed in fast 22. G5's complete owning-root
validator and its traversal budget are still outstanding, as are the
reverse-walk timing and reset-boundary live proofs. **RESET NEEDED; not yet
ready to merge.**

### Fast 26 evidence and remaining query-cost decision

Fast 26: **125 tests, 900 assertions, one failure, zero errors**. The sole
failure is `seon.db-test/ten-carried-queries-stay-within-twice-raw-query-cost`:
ten raw queries took **481,540 ns**, ten armed Seon queries **1,178,209 ns**
after warming the exact measured query on both paths. The existing absolute
five-millisecond query check passes. The reset's schema-reference query is now
a direct keyword-value relation instead of a join through schema entity refs.
Do not call this a green run or attribute the ratio to a foreign lane.

The owner was asked which performance contract controls: retain the existing
absolute budget and report the ratio (recommended, about 15 minutes); optimize
the wrapper to retain the two-times-raw ratio (about 1–3 hours, a separate
query-owner change); or retain this failing assertion as a landing boundary.
No threshold has been relaxed. The G5 budget-location question also remains
unanswered; no partial universal/component validator is enabled to hide that
missing work.

The reproducible equal-population regression is
`test/seon/reset_edge_parity_test.clj`. Both representations carry the same
29,584 call/reference/subject edges; each timed walk acquires its identity map
and scans three indexed relations. All three complete closures matched:

| Seed | Reached identities | Reference ms | Symbol ms |
|---|---:|---:|---:|
| `seon.turn/open?` | 2,093 | 40.738917 | 43.133041 |
| `seon.db/q` | 2,702 | 43.999125 | 46.434416 |
| `seon.id/id` | 2,220 | 33.821709 | 36.169542 |

These are fast-26 snapshot measurements, not live default counts or the final
post-merge gate. The initial experiment's per-node pulls were replaced with
the identity map the production gate-set owner already acquires; the table
above measures that actual arrangement.

N10 is now being completed in this same unpublished group: effect receipts
require `:seon.effect/capability`, captured from the exact handler selected for
dispatch before entering the writer. `:seon.effect/capability-fn` and its
writer-side re-resolution are deleted. The symbol attribute is indexed;
only function declarations carrying it participate in deletion refusal.
Historical effect observations do not prevent handler deletion. Fast 27 passed its targeted armed proof: 152 tests, 1,500 assertions, zero
failures and zero errors. This is not a cold gate or reset-boundary proof.


### G5 decision boundary at the implementation checkpoint

G5 is not enabled by this checkpoint. AGENTS §2.5 requires an owner design
choice before introducing hours of cross-owner work; the unresolved choice is
where the complete traversal obtains its declared work budget:

1. **Projection-carried node budget (recommended; about 1–2 hours).** Declare
   the bound with the schema projection and hand it to the final validator.
   Every writer shares one bound and a typed refusal when completeness cannot
   be established. It requires the projection/config acquisition seam to carry
   the value; no constructor-specific escape exists.
2. **Every transaction constructor supplies the budget (about 3–5 hours).**
   Work is bounded per request and callers may choose smaller bounds. Every
   constructor and transaction-function expansion must preserve the value;
   missing bounds must refuse. This adds a cross-writer contract and gives up
   a single projection-owned policy.
3. **Bound by the supplied database population (under one hour for the bound,
   component regressions additional).** A visited set and finite population
   ensure termination without a new configuration value. This gives up an
   independent latency/work ceiling as the database grows, so it is not the
   recommended implementation of the declared-work-bound requirement.

The traversal itself must discover all before/after owners via indexed
attribute/value seeks (Datahike has no VAET), expand complete EAVT child values,
validate typed owned children, and refuse incomplete/cyclic ownership rather
than accept id-only placeholders. No budget or validator is silently chosen
in this checkpoint. The identity-less-entity issue stays open. The query-cost
boundary is also recorded in
[its issue](../../../seon/issues/the-carried-query-ratio-fails-after-symbol-edge-retyping.md).


Fast 28: **32 tests / 180 assertions / zero failures / zero errors** for the
complete reset-edge regression namespace, equal-population reverse-walk proof,
and effect owner. Both newest deletion cases passed. Exact parity timings and
current fixture referrer counts are in the integration landing note. The
message/wake follow-up `57581f12f` landed during this run and must be incorporated
before the final publication. G5 and the query-cost assertion remain explicit
review boundaries; this checkpoint is not permission to reset.
