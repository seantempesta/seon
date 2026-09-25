---
type: review
status: independent review — start after fixes
created: 2026-09-25
source-pin: 19e9fe5f48eb366fb21bf85e6473bf613397ce71
scope: language requirements revision 19e9fe5f4 against its parent; migration readiness and semantics, no implementation
---

# Language requirements: migration readiness review

**Verdict: start after fixes.** The owner's goal is: “I want to start using this so we can move the docs and issues into the codebase.” The revised co-location rule and §7 give that work a useful destination. They do not yet give an implementer one consistent task model, an authority transfer from existing issues, or a landable authoring path. Fix the P0 items below before migrating authoritative records; do not make the migration wait for anonymous functions, inferred contracts, configuration relocation, or the whole query-render redesign.

Seon already indexes issue notes into database entities and has program metadata, reverse queries, contracts and render owners. The smallest composition is structured task declarations plus code-owned edges through those owners, followed by an explicitly bounded transfer of issue authority. It is not another issue service or task runner.

## Evidence and review boundary

I compared `19e9fe5f4^..19e9fe5f4`: **47 inserted / 13 deleted lines**, all in [the requirements](language-requirements-2026-09-24.md), henceforth **L**. Unless another pin is stated, file/line citations below are at `19e9fe5f4`. The named documents and both sections of [the one-REPL review](one-repl-language-review-2026-09-25.md), including its re-review of `e5f9b923e`, were read end to end. **R** denotes [one-repl-language](one-repl-language-2026-09-25.md); **Q** denotes [query-render-building-block](query-render-building-block-2026-09-25.md). They are designs, not installation evidence.

Read `AGENTS.md`, the data-oriented Clojure skill, all of `src/my/program.clj` and `src/seon/fn/analyzer.clj`, the issue index and lifecycle document, and the ten notes listed below. Supplementary source reads followed the actual row, identity, contract and issue writers. No code, tests, `bin/seon`, JVM/MCP probes, adoption, browser or provider calls were run. No other lane was contacted. Runtime health is not a dependency of this static review.

### What exists, and what the revision must not invent

| Source | Guarantee visible in source; inputs, recomputation and cost |
|---|---|
| `src/seon/fn/analyzer.clj:22–39,108–132` | clj-kondo metadata capture is already enabled and both namespace and Var normalization retain `:meta`. The analyzer does not simply discard arbitrary task metadata. Changed captured source goes through the existing analyzer; no second prose/parser pass is justified. |
| `src/seon/fn.clj:644–770` | `var-row` selects which metadata becomes function/test facts. It does not project `:seon/tasks` or `:seon/task`. This is an ingestion seam, alongside namespace/schema row production and the REPL declaration path. Adding keyword mentions alone would not establish the typed relations. |
| `src/seon/program.cljc:733–790,1004–1081`; `src/my/program.clj:179–226` | Destructuring entries and contract input/output/guard references already have owners. `reads-key` returns contract references, writes and mentions distinctly. Reuse these facts on changed declaration/schema content; a mention is not a read. |
| `docs/seon/issues/index.md:9–29`; `src/seon/issue.clj:726–735` | The issue index is already a query, not a second Markdown schedule. Its current implementation reads all titled issues, pulls, filters and sorts them. A target-bound task lookup must not copy that whole-population work into every function render. |
| `src/seon/issue.clj:284–320,322–338,495–517` | Existing reconciliation preserves assignment, creator, resolution and additive tests; changed paths select note work, and the transaction function re-decides the committed delta. Adapt this ownership contract. A task preload must not overwrite runtime decisions or retract an assigned entity when its file disappears. |
| `src/my/program.clj:68–77,144–157,228–245` | Reverse joins already exist. `callers` returns a typed report, not a symbol set; `render-referrers` currently scans pairs then filters. Bind task/subject at the database owner. Desired read cost is indexed lookup plus matched edges, not all declarations. |
| `resources/seon/schemas/seon.fn.edn:206–210`; `src/seon/program.cljc:17–55,364–408` | `:seon.fn/sym` is an identity naming a source/program family. An evaluation-owned closure cannot be made non-program merely by adding an owner reference to an otherwise ordinary function row. |

Dependency pins:

- `reference-code/malli`: `befcd302a8e626a67d84b80aabee0cd1b38dadb6`.
- `reference-code/clj-kondo`: `57252e07975710aa579b24f0d1b2b1e04195caa2`.
- `reference-code/sci`: `fcbd8862800e638dc0f8f5521111f999279cbcd2`.
- `reference-code/datahike`: `4157916b5a1b8d80d7f809c889bce1b098b1d75d`.

The contract seam is Malli's `schema`, `form`, `properties`, `children` and cached `validator` (`reference-code/malli/src/malli/core.cljc:2561–2643`). These inspect/compile declared schemas; none proves arbitrary behavioral equivalence or infers a function's output from its argument binding. Compilation belongs on changed contract/projection content, not every call or render.

## Ranked findings

### P0 — resolve before authoritative migration

**P0-1. There are still competing subject, proof and completion authorities.** These are executable disagreements, not editorial repetition:

| Conflicting rows | Consequence | Smallest correction |
|---|---|---|
| 2.2: subjects are “never written onto the entity”; 1.2 and 7.1–7.4: subject declarations carry task IDs | An implementer can correctly follow either row and build opposite indexes. | §7 wins. Code owns existing-subject edges, tests own proof edges, EDN owns task data. Preserve only the explicit runtime/unborn-subject exception. |
| 1.7's status, 3b.2 and 5b.1 author `subjects`/`done-when`; 7.3–7.4 forbid those duplicate lists | Two definitions of task relevance and proof identity; source edits can disagree with EDN. | Remove obsolete authoring examples and the `done-when` compiler from the first migration. A linked regression remains a program test. If non-program task-local proofs are retained later, name their distinct producer and lifetime; do not author a second copy of a regression. |
| 2.6: green attempt means settled; 7.6: root acceptance and same-merge edge removal mean settled | A worker could close an issue without accepted delivery. Removing a subject also looks “obsolete” under 2.6, even after success. | §7.6 plus AGENTS.md's named acceptance wins. Green is evidence, not settlement. Accepted outcome precedes absence-based classification; missing proof is unknown. Preserve the reviewed proof identity and its tested inputs. |
| 3.2: a requirement never merges; 7.3: proofs are `deftest`s; 3.13: every proof is only `(fn [db] …)` | It is unclear whether a naming regression is program, and whether behavioral/effect tests are allowed. | Database predicates take a held database; ordinary behavioral regressions use the existing harness and injected worlds. Both produce current evidence. A linked `deftest` is program; its task/run/decision is data. No blanket db-only execution rule or second truthiness runner. |
| 2.7 derives needs; 5b.2 authors needs; 3.11/5c.1 enforce a changes scope that 5b.1 supersedes | Calls, prerequisites and permission become conflated. | Author `needs`; derive suggestions from calls, never quoted names. Root accepts the reviewed delta/subset through the existing gate. Delete obsolete scope enforcement instead of recreating a permission model. |

Also define settlement after subject-edge removal: the proof-to-task edge and acceptance evidence remain available. An EDN reconciliation must not resurrect a settled task, erase a frozen proof, or infer settlement from an empty query. A closed task is still the same identity.

**P0-2. §7 lacks the partition and authority-transfer transaction.** AGENTS.md “The program” excludes tasks from program merge. §7 places task IDs on program declarations and requires dangling IDs to refuse. If a task exists only on an attempt branch, merging its annotated function onto a destination without that task cannot satisfy both rules.

Specify two coordinated admissions: root reconciles the task's data on the destination through the task/issue writer; the program gate then validates code/test edges against that destination. Do not smuggle task rows through program merge. A single source publication may admit related data and program facts coherently, but their ownership and merge partitions remain distinct. Store task IDs as declared keyword-valued edges if they must survive branch partitioning; validate them at the writer, rather than silently resolving them to branch-local entity IDs. This representation is a recommendation, not an installed schema.

The migration also needs an explicit old-issue → task association, retained historical citations, and one current completion authority. `seon.issue/start!`, settlement, protected tests, detectors, class membership and existing merge issue inputs have callers. “Create tasks and leave issue parsing running” produces two owners. “Delete the old note” can retract an entity still referenced by them (`issue.clj:476–490`). Neither is a valid transfer.

The smallest pilot converts only named records through the existing owner, makes their old entry points resolve the same current task, and stops treating their Markdown as current input. Unmigrated records retain the old owner. This is a bounded migration boundary, not two writable representations of one task. Reject duplicates within an authored population; re-reading the same qualified ID must reconcile idempotently, not trigger 7.5's duplicate refusal.

**P0-3. “Start using it” has an unfulfilled authoring/landing dependency.** L1.10 and AGENTS.md require REPL authoring and gated write-back. R's M1 only lands an existing-function replacement using existing tests; general additions, new tests and task/schema data-file write-back are not delivered by it (R:413–439,630). The re-review still rejects publish-then-compare equivalence (review:532–578).

Ingestion adds schema/data declarations and metadata; migrating proofs may add tests. Name their accepted-row → file → reindex route before assigning the migration as REPL work. An author manually editing EDN/source is a possible explicitly ruled bootstrap concession, not compliance with 1.10. Do not solve this by silently widening M1, claiming `my.edit/form!` is gated write-back, or introducing another editor. The review can start immediately; authoritative migration cannot claim this path complete from source alone.

### P1 — correctness requirements at the relevant slice

**P1-1. The literal-handle promise is not satisfied by the stated language.** L1.9 requires the same ID to be findable by grep at every definition/referrer and followable in one REPL call. R:487–490 explicitly narrows that promise; L has not accepted that narrowing. `(defn f …)` in `n` does not contain `n/f`, namespace-map shorthand hides full keywords, and aliases/syntax quote can hide a reference's final name. L5b.6 explicitly recommends namespace-map shorthand. Generated runtime tasks and evaluation-owned functions have no source file at all.

Keep the owner's discoverability goal. For the first authored task population, write full qualified task tokens in both EDN and metadata; render the exact callable form and namespace that resolves them. Before generalizing, root must resolve the literal scope: source-backed IDs need canonical literal spelling or an explicitly permitted derived source anchor; runtime identities need a durable evaluation/task handle and source provenance, not a fictitious grep hit. Do not add a manually maintained reverse-reference file. Current `(my.program/callers {:seon.program/subject 'n/f})` is a real shape; bare `(callers 'f)` and `(task :id)` are target conveniences requiring declared bindings and contracts.

**P1-2. Migration must preserve historical uncertainty, not import prose as truth.** 7.11 cannot mean all `*.md`: `README.md` and `index.md` are not issues, and the existing selector also handles archived notes (`issue.clj:124–164`). Some open notes record partially fixed defects, retired contracts, class membership or pending verification. §6 itself mixes failures with “DONE on branch … not delivered.” Transfer authored intent and dated evidence, then verify current status. Never manufacture a red regression or mark settled just because a commit or historical pass exists.

There is no schema in L for severity, root ordering, class relations, historical acceptance, or structured Markdown evidence handles. A readable ranked view cannot derive root's priority from a dependency DAG alone. Retain existing severity/ordering facts where real; let root supply missing priority. Paths and Git revisions need a structured reference shape and one read call too. Symbol/keyword-only edges in 7.9 cannot encode every `must-read` promised in 7.11.

**P1-3. “Installed” and “missing” need corrections.** L11.1 says installed based on commits, whereas Q:24–29 reports the observed JVM still using two selection stages. That is a historical conflicting observation, not a current health claim from this review: change the cell to source-present / installed-unverified until root repeats the probe. L7.9's “analyzer drops” should name row projection. L7.9b must not price seed ingestion from zero: `fn.clj:704–709` already stores seed occurrences and derives seed keywords. Verify the missing traversable relation separately. `seon.task/create!` and the task schema/resource directory are targets, absent at this pin.

**P1-4. Rendering must acquire once and preserve the observed view.** 5c.3's raw `Execution error` text cannot create an error-only stringify path contrary to 12.2; the ordinary error renderer owns that face. 11.3 shares a pipeline and held inputs, not necessarily identical selected producers for two different faces/viewers. Saved AI bytes are historical; a newly rendered HTML/current view is labelled accordingly (R:247–258). 5.8's complete-source renderer still participates in whole-unit prompt admission; it does not authorize unbounded eager traversal or clipping source again elsewhere.

Q:170–177 executes query sources in `render-call`, while Q:281 and 347–348 also says openings evaluate/record their forms through the REPL. The task slice must choose one acquisition per opening: evaluate the task query once through the ordinary entrant, retain its result/read evidence, then render that value. Do not query once to render and again to record. Q:265–267's “valid forever” query+basis claim also exceeds retention: a branch-scoped time without retained history and source custody is not a permanent handle. L1.9 needs an honest unavailable result when its historical target is gone.

**P1-5. Defaults and function identity need the semantics below before broad use.** These are independent of importing explicit task metadata. Do not make inference or canonicalization a prerequisite for a task with a complete explicit contract.

**P1-6. Error recording and delivery still disagree.** L8.2's “no routing” and 8.4's paid-wake suppression cannot erase AGENTS.md's “store and deliver core faults.” Separate origin-branch storage, durable cluster visibility and ordinary notification from permission to spend on a model turn. The latest owner wake ruling can be preserved without equating “visible in a query” with delivered. Root should make the two authorities say the same thing; no new cursor/mailbox is justified. The 8.6 decision is below.

**P1-7. Configuration has a missing layer.** L13.2 omits the typed environment layer present between deployment overlays and cluster facts in R:542–554 and the current compiler. State its precedence explicitly rather than silently dropping it. The re-review's agentless-reader correction still applies: a reader of effective cluster configuration must include cluster overrides, not only the compiled defaults/deployments. This is a separate owner cut, not a prerequisite for static task ingestion.

### P2 — simplify and qualify the laws

- L1.1's “everything stored … unique fully qualified name” overstates entity identity: scalar values and identity-less owned components exist. Require declared identities for independently addressable entities, preserving native types.
- L1.3's “plain Clojure only” needs the already-ruled qualified-definition extension in 1.10 named as an entrance feature. A target is not a claim stock Clojure accepts it.
- L5.7's “intent is the only authored prose” applies to a task brief, not schema reasons, decision reasons, docs and evidence required elsewhere. L5a.2's “missing symbol is you create this” must require positive declared delivery; failed/unknown analysis is not authorization.
- L9.1 should distinguish an ordinary boolean `false` from absence. A predicate can validly answer false; it must not encode a failed read. The no-error-as-nil rule remains.
- L12.2's schema-key wording must not introduce an error-kind stamp or attach a map key to an arbitrary scalar. Preserve the flat named error schema and typed value; schema identity is declaration/provenance, not a second discriminator.

## 1.11–1.14: smallest correct semantics

### 1.11 — one function mechanism, distinct lifetimes

**Risk: P0 if implemented using today's `:seon.fn/sym` program identity unchanged.** That identity is globally unique in the branch and declares its source family. Reusing it for runtime closures can pollute program digests, adoption, coverage and write-back; Clojure's counter-shaped printed names do not provide cross-session/restart identity. Two closures from the same lexical form may capture different values, and a function body may construct closures repeatedly or lazily.

Use the same analysis, contract compilation, invocation and render machinery, with an evaluation-owned non-program record. Its identity must derive through `seon.id` from the owning evaluation and the relevant occurrence; its readable generated symbol is a label, not an installed Var. Reuse declared function data shapes where they fit, but use a distinct data identity/owning component unless the program partition is redesigned and proved for both cases. Never put closure instances into the namespace map merely to obtain lookup/default behavior. Do not serialize captures or invoke a closure again to recover its source.

Distinguish the analyzed lexical declaration from a live closure instance. Record source, resolver context, contract and analysis once per relevant declaration; associate an admitted/rendered instance with that evidence through existing evaluation-owned memory. Do not promise eager rows for an unbounded number of latent closures. Root must say whether “every function” quantifies declarations, constructed instances, or functions crossing the evaluation boundary; the minimal first implementation covers analyzed functions exposed by that boundary and reports unsupported provenance, rather than claiming full coverage.

**First render is part of the contract.** The function's contract and preparation plan must exist before selection, fit checking or invocation in its producing evaluation. The first render cannot query for a function row that will only be committed after shown text is produced. Carry the analyzed descriptor as a real evaluation value into the same preparation owner; settle its durable evidence with the evaluation. Later renders reuse the same evidence, never cause first-use program publication. Refusal/arming/settlement failure must not expose an accepted callable. Restart makes live captures unavailable while source and shown text can remain.

A nested `fn` inside an accepted `defn` is still part of that program's source/digest; “never writes back” refers to the separate runtime instance, not deletion of its lexical body from program source. Nor does a generated runtime name make an anonymous predicate valid inside a durable program contract: AGENTS.md and `schema/canonical-definition` (`schema.clj:642–673`) require resolvable declared predicate identity. Preserve that distinction.

### 1.12 — derive an input declaration, not an entire contract

Use resolved binding keys from the ordinary reader/analyzer and the supplied projection. Destructuring `{:keys [seon.db/db]}` names a key, but ordinary Clojure destructuring alone neither requires that key nor describes the result. Preserve aliases, nested bindings, `:as`, per-arity bindings and local-name collisions; do not infer from strings or the local name `db`.

Recommended minimal rule: derivation is a declared authoring mode for the input map; a fully qualified registered key contributes its named schema. In that mode a key without a local default is required; an explicitly optional key or a `:or` binding is optional, with Clojure's default expression preserved and evaluated only by normal execution. Do not run `:or` expressions during analysis. Optionality belongs to the map entry, not to the global attribute's value schema. Unknown keys and unsupported mixed binding forms refuse derivation with the path, while an ordinary explicit contract remains available.

A complete explicit `:malli/schema` is authoritative when present. Do not silently intersect, widen, override its optionality, or merge derived arities into it. Keep derived bindings as explanatory facts; diagnose an expressly requested derivation that disagrees. Output and guards remain explicitly declared, including deliberate polymorphism allowed by 10.1. Merely deriving the input is not a complete contract and does not satisfy admission. The function-face design still owes the exact opt-in/output authoring syntax; until then, use explicit complete contracts in migration code.

### 1.13 — omission is not falsiness; declared supply is not ambient authority

Only declared schema names with an existing supplier are eligible. A declaration with no supplier remains a normal argument requirement, not an instruction to synthesize a value. Preserve caller `nil`/`false` and let validation accept/refuse them; never replace them with defaults. Use map membership, not truthiness. Optional entries may be supplied when omitted under this owner's rule; do not infer that the same optionality authorizes an absent required positional argument.

Prepare one call using the held environment, database/projection and contract. Preserve ordinary positional arity/argument order; when omitted-position placement or multi-arity dispatch is ambiguous, return a named refusal instead of shifting caller values by guessed type. Explicit complete calls continue to work. Nested maps and variadic boundaries need declared behavior, not arbitrary recursive injection.

Selection must assess the arguments that the same preparation plan will supply; invocation uses that plan. A renderer declaring `[value db]` must not be rejected by a one-argument fit and then accepted only if manually called. Inline closures use their descriptor, not a fake program Var. Current `call-preparation/hook` explicitly passes through a callee with no program identity (`call_preparation.clj:1538–1541`); fixing only the render caller is insufficient. Raw host calls with no supplied world do not acquire hidden custody; the “every function” rule describes the shared Seon call entrance, with a host caller passing its world explicitly.

### 1.14 — canonicalize once without changing schema identity

**Risk: P0 for blind shape substitution.** Two named schemas may accept the same values yet have different suppliers, renderers, properties and future evolution. Replacing an inline schema by the first structurally equal registered schema can change invocation under 1.13. Equal current Malli forms are not proof of equal Seon semantics. This is also directly in tension with AGENTS.md “structural resemblance is not identity.”

Keep canonicalization in the existing schema/declaration owner. Only substitute a named schema when its identity is established by the authored key/reference or an unambiguous, explicitly accepted normalization rule, and preserve properties and scope. Otherwise retain the inline form and show a suggested name. Do not scan the registry for a best match at each call. Root must narrow 1.14's equality rule before implementing it; silently choosing a name would violate the stronger owner intent of semantic equality.

The canonical form must be idempotent; it preserves quote/reader resolution, private/name metadata, contracts and source semantics. Keep exact submitted bytes as historical evaluation evidence, separately from accepted canonical source. Both file write-back and the REPL's source renderer read the same accepted canonical source; display feedback is not another rewrite. A live closure's generated evaluation identity is not injected into reusable program source.

`program/definition-digest` hashes authored declaration content and semantic metadata (`program.cljc:391–408`). Reformatting/replacing a contract can therefore change today's digest. Do not promise raw-before/raw-after hash equality. Normalize both through the same declared algorithm and projection, compare semantic facts and canonical digests, and invalidate affected proof when semantic dependencies changed. A finite example test is not a proof of arbitrary function equivalence. Validate staged write-back equivalence before publication, as the prior review requires.

## The first three slices

These are review constraints for Opus's revised implementation design, not authority to edit held files. The first three useful deliverables are **ingestion → a small migration → rendering**, not the entire task execution platform. All proofs below are future root/implementation evidence; none was run here.

### Slice 1 — ingest task EDN and `:seon/tasks`, then reverse lookup

**Deliverable.** Admit the minimal task schema with keyword identity `:seon.task/id`, intent and structured evidence/needs; create `resources/seon/tasks/<owning-ns>.edn`. Reuse the existing issue writer/reconciliation authority for task data and the existing declaration producers for metadata. Project `:seon/tasks` on functions/namespaces/schemas and `:seon/task` on tests; support lookup in both directions from one held database. Preserve runtime/unborn subjects as a separately declared origin. Do not build a task scheduler, a new runner, proof compiler, graph cache or generalized seed walker.

Read EDN as data without evaluation; retain full literal task IDs. Source- and REPL-authored metadata must produce the same facts/digests through the row owner. Task EDN and annotations are admitted coherently so file ordering cannot produce dangling references. The data reconciliation happens outside program merge as P0-2 specifies. A missing task is distinguishable from a refused read or missing analysis.

**Proof.** On a branch of a live store: ingest one task and two real subject annotations plus one existing naming regression; resolve task → both subjects/proof and each subject → task. Repeat unchanged ingestion with zero writes. Remove an annotation and observe only that edge disappear. Duplicate/dangling/malformed input refuses at the final writer without partial publication. An EDN edit preserves a retained runtime decision/test reference. A source reload and a REPL replacement agree on facts. Cross-branch program merge neither copies task history nor admits an unresolved target. No cold rebuild/reset is acceptable proof of adoption.

**Budget and blockers.** Review ceiling: **+100 production/schema lines, +60 focused regression lines; 0 currently demonstrated production deletions**. These are stop limits, not estimates that this much code is sufficient. No existing task subsystem is available to delete, and all-note `parse-note` is still needed. Thus this enabling cut is growth, not a self-funding cleanup; AGENTS.md requires a smaller composition or an explicitly justified growth ruling before integration, and crossing roughly 100 total additions requires that ruling before writing. Price the actual writer/caller diff first. The justified purpose is to retire Markdown-as-current-task-input per migrated record in slice 2, then retire parsing after complete conversion; future savings are not current credit. Blockers: P0-1–3, evidence-reference schema, actual adoption/authoring path, and an owner-approved feasible budget. 1.11–1.14 are not blockers.

### Slice 2 — migrate N = 3 defect records, with the old authority retired per record

Choose these three **§6 defects first**, as 7.12 requires. This is three defect records, **one existing issue Markdown file and two entries currently living in L**, not a claim that three issue files have migrated. This small cohort proves a normal defect, a historical branch success awaiting delivery, and an apparently repaired but unverified issue. It must precede bulk import of the ten sampled notes.

| Task identity and EDN file | Existing evidence and subject | Required disposition |
|---|---|---|
| `:seon.sci.eval.task/definitions-bypass-entry-gate`; `seon.sci.eval.edn` | L:222; R:38–46,160–189; subject `seon.sci.eval/evaluate` | Raised/needs verification until the actual current regression is identified or written. Preserve the distinction between unindexed change and absent contract. No copied speculative matcher fix. |
| `:seon.schema.task/agents-can-validate`; `seon.schema.edn` | L:224 names this exact ID and `exp-validate-2`; subject namespace `seon.schema` pending verification of the precise producer | Preserve “passed on a historical branch, not delivered” as evidence. Do not call it settled or rerun the old branch's execution. Verify retained branch/evaluation evidence; if unavailable, record that and perform a new ordinary proof. |
| `:seon.dev.mcp.task/mcp-jvm-eval-drops-reader-metadata-on-a-defn-name`; `seon.dev.mcp.edn` | L:230 and `docs/seon/issues/mcp-jvm-eval-drops-reader-metadata-on-a-defn-name.md`; producer `seon.dev.mcp/jvm-evaluation-form` | The note's cause is unverified, but current source explicitly carries original source bytes to retain metadata (`script/seon/dev/mcp.clj:486–499`). Migration is a current-proof/acceptance task, not an instruction to reimplement that fix. |

All file names in this table are under `resources/seon/tasks/`. The last row deliberately exposes a real limit: program roots are `src` and `test` (`fn.clj:33–35`), while its producer is a bridge script. A keyword mention is not a published function row. Before importing this row, provide the owner's reviewed, structured external-source handle with one read call, or extend the ordinary indexing owner deliberately. Do not invent a `:seon.fn/sym` row or silently relabel a symptom in `seon.cluster` as the producer. This is a blocker of the proposed three-record cohort, not a reason to expand every script into the program automatically.

**Deliverable.** Three task identities, current subject links wherever an admitted subject exists, and naming tests only where real regressions exist. Raised tasks are usable for triage without fake placeholder tests (4.5). Preserve the note and dated evidence; replace the current actionable copies in §6 with task handles. For the old issue entity, transfer/alias current responsibility through the existing owner and exclude its evidence document from current issue parsing. Do not delete its historical evidence or leave two active status writers. Root updates the ranked issue/plan views; an assigned lane does not edit the schedule.

**Proof.** Each ID is literally found in EDN and every new authored annotation, and one REPL call returns current subjects, proofs or explicit no-proof status, evidence handle and acceptance state. Old issue references reach the same current work. Reconciliation is idempotent; changing the retained Markdown body cannot change task status/subjects. Reproduce the applicable behavior before claiming red/green; historical fixed-but-unaccepted evidence is not current failure or settlement. Preserve creator, protected tests, assignment and decisions if present. A plan/decomposition view must retain needs and root ordering without turning historical designs into executable instructions.

**Budget and blockers.** **≤36 new EDN lines, ≤6 annotation lines, ≤20 production lines for the cohort's existing-owner transfer, ≤30 migration-regression lines.** No new defect fixes or lifecycle engine. Remove the three migrated actionable entries from L (only a **partial physical line** at L:230; preserve its other defects) and current issue-authority metadata where retired. The **41-line issue note remains evidence: zero deletion credit**. New EDN is relocated authored data, not source shrinkage. Blockers: slice 1, verified current proof/status, the bridge handle, and the data/file acceptance route. If transfer needs a new registry or cannot fit the bounded owner change, stop and revise the existing issue owner; do not append a general migration framework. Once these three work, migrate the remaining §6 records before a bulk issue cohort.

### Slice 3 — render `(task :id)` and a function's open tasks

**Deliverable.** One task read returns a typed task view from a held database, joining reverse subjects and naming proofs. The public agent binding makes `(task :qualified.task/id)` an actual one-call entry, with explicit-world equivalent for JVM callers. A function's existing program report includes open task IDs plus those exact expansion forms. Use the common per-face selection and existing floor; define the task view's default pair on its schema only if the existing value render is insufficient. Do not repeat queries inside AI/HTML renderers, expand every linked task by default, or introduce a task-specific tree/renderer registry.

**Proof.** The same held task value drives both faces. The AI shows the exact task ID, state/evidence and working expansion calls; the browser shows the same facts and separately verified paint. The function view returns both open task handles when two apply, omits an accepted/settled one by the single state derivation, and distinguishes missing evidence from an empty set. Rendering performs no program/task writes. An opening evaluates the query once and stores its shown bytes; rereading history does not rerun it. A large reverse relation uses the shared floor's cuts, with unknown totals/retrievability stated honestly. Test a failed read and unavailable historical handle, not just the happy path.

**Budget and blockers.** **≤50 production/schema lines and ≤40 focused regression lines**, with **0 guaranteed production deletion credit** while old issue views still serve unmigrated records. Convert existing issue render/query callers rather than adding parallel completion derivation. `issue/status-view` is an 11-line definition (`issue.clj:846–856`) that can retire only if all its AI/HTML callers receive the acquired view; book those 11 lines once only after that conversion. Do not count the 15 definition lines of `render-ai`/`render-html` (`:858–864` and `:866–873`) as deletion if their behavior merely moves. Blockers: slices 1–2, installed selection/default behavior and one-query opening semantics. The full Q `units` rollout, permanent cuts for arbitrary lazy results and configuration relocation are not necessary to render these finite task maps.

**Performance evidence for all three:** measure parent/change acquisition separately from warm ingest/read/render, transaction datoms, allocated bytes and retained memory. Target ordinary operations below one second; a target-bound reverse lookup scales with matching edges, changed ingestion with changed inputs plus required validation. Report query bounds and partial/unknown evidence explicitly. More than one second needs named attribution; more than ten needs owner authorization and an issue. Do not cite v0's 3.4/9 ms or Q's tiny read probe as measurements of these slices. The proposed budgets carry genuine growth; neither this review nor retained Markdown pays for it by fictitious source deletions.

## Recommendations on the two OPEN items

**8.6 — record ordinary MCP JVM evaluations, with an explicit destination and actor.** Recommend root's direction, but replace “without custody” with “without implicit execution custody”: JVM mode does not inject a connection or authorize operations. The request still names root, cluster and recording branch, and the ordinary recorder owns evaluation identity, complete error value and exact shown text. Record success and failure once; do not classify every thrown probe mistake as a core platform fault. Core faults follow the same storage/delivery policy as other entrants. Never relabel an outside caller as root without the root/session attribution actually being supplied.

Keep below-store recovery possible through the existing raw diagnostic PREPL, with an explicit **unrecorded/unavailable** result when the store/recorder cannot be used. That is a narrow exception needing an owner ruling in AGENTS.md and R:259–266, not a silent fallback store or claimed successful recording. No second result store or retry. If the owner declines that exception, fail closed for recorded MCP evaluation and retain raw operator diagnosis as a separately named surface. Record/refusal/first-observation semantics must be settled before deleting the remaining host path; none blocks importing finite task data through a working recorded entrant.

**9.5 — adopt non-realization at the floor.** An unrealized lazy value has a typed “not yet realized” presentation with its existing result/evaluation handle and availability, never `nil`, `[]`, zero count or a guessed total. Inspect type/known realization state without `seq`, `count`, equality, hashing or printing its tail. A realized head does not imply a realized tail; checking one `realized?` flag is not permission to traverse everything. Do not dereference arbitrary delays/futures or serialize closures to make a cut work.

An explicit ordinary evaluation may realize a caller-chosen bounded prefix under the execution owner's bound and record that new result. The floor and browser expander never perform hidden realization; even `take n` can block or run effects while realizing an element, so it is not an execution-termination guarantee. This strengthens 11.4's “affordable” rule for unknown lazy work. Keep `nil` and false as real values with their own faces. Arbitrary lazy retrieval is not needed for the first task views.

## Ten-note sample: what migration must preserve

All paths below are relative to `docs/seon/issues/`; these are evidence observations, not newly verified defect statuses.

| Note | Migration consequence |
|---|---|
| `repl-parity-divergences.md` | A class with multiple historical counts, promoted tests and retired requirements; cannot become one newly failing regression by copying its title. |
| `mcp-jvm-eval-drops-reader-metadata-on-a-defn-name.md` | 41 lines, a concrete probe, explicitly unverified cause; current bridge source already describes its repair. Preserve pending proof. |
| `dynamic-in-ns-cannot-persist-definition-namespace.md` | A durable namespace-settlement failure with linked concurrency evidence; distinguish actual observed namespace from reader prediction. |
| `candidate-context-shares-parent-program-metadata.md` | Source audit plus partial live identity observations, expressly not a reproduced complete corruption; retain those limits. |
| `program-namespace-retraction-needs-shared-schema-writer.md` | Names a deliberate refusal and existing writer seam; migration must not treat the refusal as an accidental missing function. |
| `anonymous-runtime-contracts-have-recurred.md` | Long historical census, partial fixes and policy predating 10.1. Preserve evidence; do not resurrect an `:any`/`:some` ban. |
| `class-anonymous-contracts-cannot-survive-publication.md` | Class/member relation and durable predicate-identity requirement. Runtime anonymous functions in 1.11 do not make captured predicates durable. |
| `sci-test-declarations-drop-explicit-usage-metadata.md` | Static/REPL metadata parity defect; useful ingestion proof class, but a dated citation alone is not current reproduction. |
| `issue-indexing-at-publication-costs-13-seconds.md` | Several later measurements qualify the headline and obsolete older causes. Transfer dated measurements separately from current intent/status. |
| `namespace-page-repeats-renderer-unavailable.md` | Several rendering/acquisition subcases and pending browser verification; retain shared class identity and concrete acceptance without marking all subcases fixed. |

`index.md:9–21` and `README.md:21–34` already reject a second schedule. `README.md:59–74` describes assignment, current-test completion and protected tests. Those guarantees survive migration even where the underlying implementation needs fresh proof. L7.11 should say “convert the existing query and authoring authority,” not imply a new generated index is the missing mechanism.

## Rows to delete or fold in place

Keep the requirements living; dated prototype measurements belong in their evidence files. These removals are document cleanup, **not implementation deletion credit**.

| Remove/fold | Retained authority |
|---|---|
| Delete 2.2's old subject-storage rule and 1.7's obsolete authored-key status example | 1.2 and 7.1–7.4 |
| Delete 3b.2's authored-`done-when` compilation from the migration contract; delete superseded 5b.1 and incompatible 3.11 scope fields | 7.3 proof edges; 3b.3 decisions; AGENTS.md merge law. Retain task-local proof work only as a separately scoped future requirement if still wanted. |
| Fold 2.6 into 7.6; delete the competing settled/obsolete definitions | One lifecycle derivation with named root acceptance and current evidence |
| Fold 2.7/3b.5/5b.4 into 5b.2 | Explicit needs, derived suggestions, calls versus quoted mentions, same-cause kept distinct |
| Delete correction-log rows 5a.5 and 5a.7 from the living table after retaining one factual graph-capabilities sentence | `program/contract-facts`, `my.program/reads-key`, test call edges; the dated reviews retain the correction history |
| Fold 5a.6 into 7.9 | One ingestion requirement with the corrected producer |
| Fold 5.1 and 5a.1 into the context policy, explicitly including raised-task subjects/evidence | 7.10 plus 5a.2–5a.4; proofs alone do not seed a raised task |
| Delete 5.5 and 5c.4 as independent laws; retain their brief/transcript acceptance examples under §11 | 11.1–11.4 |
| Fold 5.11 into 7.11 | One task-derived plan/index requirement, preserving root's ordering authority |
| Remove 4.7 and 5.10 as requirements; move their historical numbers to linked experiment evidence | Per-slice installed performance proof |
| Remove each migrated actionable §6 entry, leaving its handle and pinned evidence | 7.12 and the task's single current authority |

Do **not** delete 1.9, 1.10, 7.4–7.8, 11.4 or 12.1 as mere duplication: they state distinct navigation, authoring, ownership, lifetime and boundary guarantees. Fix their collisions. Preserve the owner's rulings while deleting older competing mechanisms.

## Delivery limits

This review changes only this file. Production source/test delta is **0 / 0**; all budgets above are proposed ceilings, not measured diffs. Runtime timings, allocation, retained memory, installation, adoption, arming and browser paint are **unmeasured**. No foreign runtime boundary blocked the review. The unrelated `reference-code/http-kit` state was preserved. Only document structure/whitespace and commit scope are checked here. Commit is restricted to this path with `git commit --only`; the path is released after that commit. No push, merge, issue-index edit or implementation approval is implied.
