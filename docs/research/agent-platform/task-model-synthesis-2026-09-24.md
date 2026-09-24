---
type: research
status: design proposal; installation unverified
created: 2026-09-24
scope: one task family, task evidence, attempts, explicit acceptance
---

# One task family, built by improving the existing owners

Seon already has detector identity, an issue writer and settlement, plan queries, branch custody, and a test/merge gate. Evolve those owners into tasks that point at subjects, with separate attempts and task-owned requirements; derive the views from their facts.
Delete the note-to-issue pipeline, scalar task assignment, duplicated plan lifecycle, and implicit “tests OR detector” completion. Add no task runner, scheduler, reference database, prose parser, or namespace-sized opening.

## Evidence and limits

**VERIFIED — source**, at checkout `ca25bfe5264a45bbc8f6f039e366867ec7954903`, branch `refactor/agent-platform`: the seams cited below were read. **VERIFIED — live** means the particular read/probe recorded here, not adoption of that checkout. **UNVERIFIED — proposal** applies to every new attribute/API/acceptance rule in this document. There is no installed `seon.task` namespace in the probed JVM.

The default JVM was alive with hook publication off, running archive `ce73846828a5cc32798ef630b5a574f777646f30`, PID 90471, start `2026-09-23T22:18:14.324Z`. `runtime_status` reported 16 error signatures, 34 errored evaluations under its legacy `errored-receipts` key, one failed run and one failed-test unknown. Proc responses are availability evidence, not a clean platform gate. No lifecycle operation was performed.

Read end to end: audit `part-00.edn/.md` through `part-03.edn/.md`; [16 scenarios](task-scenarios-2026-09-24.md:27); [toy and three dogfood runs](toy-self-documenting-experiment-2026-09-24.md:17); [Astra review](astra-review-self-documenting-system-2026-09-23.md:13); [context draft](design-namespace-agent-context-draft-2026-09-23.md:20). The audit's “live” is source relevance, explicitly not reproduction (`issue-audit/part-00.md:10`). This synthesis also reads B3 and D1's owning specifications; it is a research recommendation, not another implementation plan.

Across 436 audit records: relevance live 182 / unknown 169 / obsolete 85; triggers error 102, review 90, test failure 83, over-bound 62, query 37, staleness 19, design gap 18, ruling 11, dependency 8, user 4, other 2. There are 255 trigger sketches, not 255 installed detectors. Exits: example 180, empty query 67, property 57, bound 41, measurement 25, decision 19, other 47. Sizes: small 229 / medium 146 / large 61. These require proof, observation, and judgment, not just code tests. Do not import all 436 as repairs: the index itself is a reference record, obsolete claims need disposition, and unknown claims need investigation.

The owner-supplied `exp-note-1` run is **REPORTED, not independently replayed**: top-level `seon.schema/register!` returned its key with `:outcome :ok` but no declaration; inside `do` it refused an isolated registration delta; `:my.note/about` ref→symbol storage was unspecified; suggested `seon/schema` and `seon.task/describe` were uncallable; refusals were about 15 KB; MCP reported the wrong namespace and dropped defn-name metadata; an earlier refusal cost 1.26 s / 1.7 GB, including 1.0 s in `derive-snapshot`. This is mandatory acceptance input, not proof that each suspected cause is established.

### Existing mechanisms and their actual boundaries

| Owner, source anchor | VERIFIED source guarantee; remaining gap |
|---|---|
| `src/seon/issue.clj:538` `subject-id`; `:549` `subject-row`; `:653` `generate` | Identity derives from detector plus identity value. Conversion still resolves subjects to refs, losing missing-subject observations. `:592` invokes a resolved host detector: differing branch code needs execution custody, not an unconditional host Var. |
| `src/seon/issue.clj:1014` `done?`; `:1246` `settle-call` | Tests take precedence over detector; scalar agent lookup and closure stamp. Neither proves conjunction, multiple attempts, or accepted shared deliverables. |
| `src/seon/issue.clj:1259` `start!`; `:1278` `add-tx` | Atomic worker creation/resume on supplied connection; tests-or-detector admission. Authored ID currently minted inside `add-tx`; stable retry identity must be supplied once at request admission. |
| `resources/seon/schemas/seon.issue.edn:1` | Ref subjects/tests/detector, single agent, stored status. `my.plan.edn:1` and `my.plan.item.edn:1` provide an agent-owned tree, needs as values, query completion; preserve their missing-dependency safety while retiring the separate tree. |
| `src/seon/reconcile.cljc:254`, `:299`, `:418`, `:447` | Exact managed-entity diff; absent desired attributes retract; converged apply writes nothing; nonempty apply recomputes inside writer. Whole-row reconciliation would erase runtime task additions unless ownership is separated. |
| `config/default.edn:511`; `src/seon/config.clj:610`, `:653` | Initialization uses ordinary facts/reconciliation. `require-functions!` treats every nested qualified symbol as a function reference: unsuitable for task forms, test symbols, or TDD names. |
| `resources/seon/schemas/seon.schedule.edn:1`, `seon.schedule.task.edn:1`, `seon.schedule.fire.edn:1`; `src/seon/schedule.clj:322` | Schedule owns timing, fire identity/claim, and maintenance execution. Evolve its task reference; do not replace cron or fabricate agent turns for ticks. |
| `src/seon/test.clj:1621` `run` | One execution/recording request, captured commit, real isolated test bodies, positive proof and actual exit; exclusions/unfinished are not green. Task requirements must use this owner. |
| `src/seon/cluster/registry.clj:193` `branch!` | Datahike branch pointer, idempotent creation; not a fresh JVM or complete agent acquisition. |
| `src/seon/cluster/source.clj:736` `prepare-merge!`, `:786` `accept-merge!` | Combined branch and gate exist; prepare explicitly refuses additions, retractions, and empty deltas. Full new-function/schema/data delivery is not installed. |
| `src/seon/schema.clj:1311`, `:1714`, `:3728`, `:3755`, `:3799` | Registration stages an isolated overlay. `commit-registration-delta!` returns changed keys; its name is not evidence of a durable branch publication. |
| `src/seon/instrument.clj:272`, `:586`; `src/seon/call_preparation.clj:425` | Refusal paths both explain the contract; snapshot derives program facts. They own the reported duplicated refusal evidence and preparation cost investigations. |

Pinned dependency evidence: Malli `8725a8cbd9d595f4a970ce53a2eefdbe7211b96d`, `reference-code/malli/src/malli/core.cljc:2626` `validator` and `:2642` `explainer` retain pure checkers on compiled schemas; use these through `seon.schema/projection-validator` (`src/seon/schema.clj:3926`) and `/projection-explainer` (`:4012`). Compile when relevant schema content changes, validate per input, explain only refusal; cost follows that schema/value, not all contracts.

Datahike `c79cd03a44427ac1734d917c7484c3e529c77716`: `reference-code/datahike/src/datahike/db/transaction.cljc:1171` passes the mid-transaction database to transaction functions; `:1224` validates the final report. `versioning.cljc:212` branches shared indexes; Seon's first-party caller is `registry/branch!`. Identity upserts and serialized writer decisions are the seams, not a new task lock/retry registry. SCI pinned `fcbd8862800e638dc0f8f5521111f999279cbcd2`; reuse existing agent acquisition/test custody, not direct manipulation of shared SCI state.

## 1. Proposed data model

Only **task** and **attempt** need new root identities. Requirements, examples, proposed deliveries and decisions are owned components; existing errors, messages, schedules, test runs, measurements, blobs and program declarations remain their own entities. Task and attempt schemas declare `:seon.program/partition :seon.data`. Components inherit that ownership; nothing puts task metadata on subject declarations.

Notation: `Subject` is a function/test qualified symbol, namespace symbol (simple symbols allowed), schema qualified keyword, or stored-data identity `[identity-attribute value]`. Examples: `seon.error/explain-problem`, `seon.error`, `:my.note/about`, `[:seon.error/signature "…"]`. A symbol that ambiguously resolves to several declaration identities must use the explicit pair, e.g. `[:seon.test/sym some.ns/check]`. `Identity` is exactly that pair; its value satisfies the declared identity attribute, not a guessed string type. Ref-valued identities normalize recursively to target identities, never branch-local numeric entity IDs.

**Storage gate, UNVERIFIED:** these are logical value types, not a claim the current bridge stores the union natively. `src/seon/schema/datahike.clj:17` maps symbols natively; `:116` encodes heterogeneous unions as strings; `:132` supports fixed native tuples. A generic tuple containing `:seon.schema/value` does not prove native heterogeneous storage. General subject/requirement values depend on A2's existing native-value proof (comparator, equality, history, reconnect and retraction), or an owner-approved narrower typed representation. Do not silently turn subjects into strings/refs. This is a build prerequisite, not permission to add an encoder here.

All fields below are fully qualified. Optional means absent, never stored nil. A = authored request/declaration; P = produced at the authority. Collections are sets unless stated otherwise; examples' argument vectors preserve order. Source strings are exact authored Clojure source, not serialized symbol identities.

Required task fields: ID, producer, primary subject, title, problem, created-by. Required requirement fields: ID, test, description. Required example fields: function, arguments. Required attempt fields: ID, task, branch, base, task-digest and exactly one executor; an agent attempt also requires budget. All three delivery fields are required. Required decision fields: ID, by, inputs, value, reason; acceptance also requires accepted-basis, supersession requires replacements. All other fields are optional; omit empty sets. Additional state-specific admission, such as nonempty requirements before starting, is stated below.

### Task attributes

| Attribute | Value type; meaning; authority |
|---|---|
| `:seon.task/id` | Nonempty string, identity; P from detector identity or once-admitted authored request ID. |
| `:seon.task/producer` | Qualified symbol; A/P contracted producer, e.g. detector or `seon.task/create!`; it is a value. |
| `:seon.task/subject` | `Subject`; A/P primary subject used for detector identity. |
| `:seon.task/subjects` | Set of `Subject`; A supplementary subjects, not implicit deliverables. |
| `:seon.task/title` | Nonempty string; A/P short description, never identity. |
| `:seon.task/problem` | Nonempty string; A/P present claim, desired outcome and constraints. |
| `:seon.task/created-by` | Agent identity string; P authenticated author/producer's agent, retained after archival. |
| `:seon.task/perspective` | Namespace symbol; A optional starting perspective, not execution permission or exclusive ownership. |
| `:seon.task/priority` | Integer; A optional root ordering. No severity-to-scheduling policy inferred. |
| `:seon.task/parent` | Task ID string; A optional grouping relation, not component ownership. |
| `:seon.task/needs` | Set of task ID strings; A explicit must-precede obligations. Missing target remains visible and blocks. |
| `:seon.task/same-cause` | Set of task ID strings; A reviewed causal hypothesis; neither ordering nor completion. |
| `:seon.task/must-read` | Set of `Subject`; A required context references. No extraction from prose. |
| `:seon.task/requirements` | Set of requirement components below; A conjunction, task-owned and non-merging. |
| `:seon.task/delivers` | Set of `Subject`; A explicit permissible deliverable identities, including schema keys, namespaces, data and child tasks. |
| `:seon.task/guarantees` | Set of qualified deftest symbols; A explicit proposed permanent guarantees; root must accept their promotion. |
| `:seon.task/expected-subjects` | Set of `Subject`; A not-yet-defined targets covered by TDD requirements or explicit schema/data creation requirements. |
| `:seon.task/observations` | Set of `Identity`; P durable triggering/error/measurement/read observations, including historical citations; never a mutable live object. |
| `:seon.task/schedule` | Schedule ID string; A optional recurrence; existing schedule owner interprets timing. |
| `:seon.task/function` | Qualified symbol; A optional contracted mechanical action; absence permits agent execution. |

Transaction provenance supplies creation and modification history; no `updated-tx`, status, namespace roster, or task→single-agent field. No independent “issue kind”, “backlog kind”, or “plan kind”. Their views filter the same facts by obligations, parent, priority, and attempt evidence.

### Requirement and example components

| Attribute | Value type; meaning; authority |
|---|---|
| `:seon.task.requirement/id` | String; A stable local name, unique within task; evidence keys include task ID and requirement content digest. |
| `:seon.task.requirement/test` | Qualified symbol; A existing test/contracted test function, or named task-local test declaration. |
| `:seon.task.requirement/source` | String; A optional exact task-local test source, admitted by existing analyzer/SCI/test machinery. Absence means use the named program test. |
| `:seon.task.requirement/description` | String; A what this proves, including nonvacuity and fixture premises. |
| `:seon.task.requirement/examples` | Set of example components; A concrete calls to validate at admission, not prose. |
| `:seon.task.requirement/failing-basis` | Commit UUID; A/P optional captured reproduction basis for a repair; current failure must actually be observed before claiming red→green. |
| `:seon.task.example/function` | Qualified symbol; A function called by the example. |
| `:seon.task.example/arguments` | Vector of portable values; A the authored argument values. |
| `:seon.task.example/prepare` | Qualified symbol; A optional contracted fixture function `(database, arguments) → complete call arguments`, when a real database/schema/object must be constructed. No serialized compiled schemas. |

A requirement is an ordinary test body, not a new expression language. Examples, empty queries, properties, bounds, decisions and child completion are written with existing functions inside that body. A query requirement asserts both a valid query result and the expected emptiness; a property asserts positive trials and its premise census; a bound uses the measured operation and the existing test's declared bound. A root-decision requirement queries a decision component by actor, question/input digest and value; a browser requirement queries durable observation evidence plus the authorized review, not HTTP success.

Compile task-local requirement declarations only into the attempt/test branch through existing admission; select them explicitly through `seon.test/run`. A requirement's source/temporary test rows are excluded from the deliverable transfer even though the test runner needs callable rows. Existing program tests used as requirements already remain program; task ownership does not remove them. Deliberate promotion adds a chosen deftest symbol/source to `guarantees` and `delivers`, with normal declaration validation, reach, and combined proof. Merely passing never promotes a requirement.

Compute permanent contract/coverage obligations on the **mergeable program**, excluding task-local requirement declarations; then execute requirements against that exact program through the test owner's isolated context. A temporary requirement must not make an uncovered function appear covered after the temporary test is discarded. Existing surviving tests or explicitly promoted guarantees must satisfy the permanent gate. Requirement source, its digest and results remain task evidence, separately from the accepted program digest.

### Attempt attributes

| Attribute | Value type; meaning; authority |
|---|---|
| `:seon.task.attempt/id` | String, identity; P from admitted start request; recurring mechanical start derives it from task ID + nominal fire identity. |
| `:seon.task.attempt/task` | Task ID string; P required parent task identity. |
| `:seon.task.attempt/agent` | Agent ref; P optional actual executor, listened assignment using existing wake semantics; archive agent instead of deleting this evidence. |
| `:seon.task.attempt/function` | Qualified symbol; P optional mechanical executor; exactly one of agent/function. |
| `:seon.task.attempt/branch` | Branch keyword; P custody address, not immutable evidence identity. |
| `:seon.task.attempt/base` | Commit UUID; P immutable starting program/data basis. |
| `:seon.task.attempt/task-digest` | String; P digest of held task requirements, scope and relevant inputs, using existing content identity owner. |
| `:seon.task.attempt/observations` | Set of `Identity`; P triggering occurrences this attempt undertakes, distinguishing later recurrence. |
| `:seon.task.attempt/budget` | Positive integer; A admitted total ordinary turns, retained on resume and explicitly increased. |
| `:seon.task.attempt/runs` | Set of test-run identities; P durable executed/reused evidence owned by test authority, not copied green booleans. |
| `:seon.task.attempt/deliveries` | Set of delivery components below; P proposed concrete values. |
| `:seon.task.attempt/decisions` | Set of decision components below; P writer-authenticated review facts. |
| `:seon.task.attempt/exit` | `Identity`; P terminal execution observation from the existing execution owner. Timeout/cancellation alone cannot supply it. |
| `:seon.task.attempt/exhausted` | `Identity`; P budget observation and root-task/delivery evidence; it cannot erase live execution ownership. |

Three agents tackling one task create three attempts/branches. Each shares the objective, has its own budget, execution and evidence, and can fail independently. Resume retains attempt/agent/branch identity; a genuinely independent try gets a new attempt. Root reads all attempts; no winner field on the task.

### Delivery and decision components

| Attribute | Value type; meaning; authority |
|---|---|
| `:seon.task.delivery/subject` | `Subject`; P exact offered identity, must be within authored/accepted scope. |
| `:seon.task.delivery/commit` | Commit UUID; P immutable candidate containing the desired value or retraction. Retain until accepted/refused evidence survives unlink. |
| `:seon.task.delivery/digest` | String; P canonical desired content digest, including explicit absent-side representation for retraction. |
| `:seon.task.decision/id` | String; P admitted review request ID, idempotent. |
| `:seon.task.decision/by` | Agent identity string; P actual deciding actor, checked against root/delegated authority. |
| `:seon.task.decision/inputs` | String; P digest of exact task, requirements, observations, proposal subset, question/options and combined proof reviewed. |
| `:seon.task.decision/value` | Qualified keyword; A/P one of `:seon.task/accept`, `:seon.task/revise`, `:seon.task/reject`, `:seon.task/obsolete`, `:seon.task/supersede`. A decision task's substantive answer is an ordinary explicitly declared data delivery. |
| `:seon.task.decision/reason` | Nonempty string; A explanation, required also on acceptance of judgment-based work. |
| `:seon.task.decision/deliveries` | Set of `Subject`; A exact selected subset, never implied dependency closure. |
| `:seon.task.decision/replacements` | Set of task ID strings; A required nonempty replacement tasks for supersession. |
| `:seon.task.decision/accepted-basis` | Commit UUID; P destination value proving accepted installation; absent for nonaccepting decisions. |

Program delivery includes new/replaced/retracted functions, tests, namespace declarations and schema keys. Data delivery names `[attr value]`, with the value read from the retained proposal and admitted by its **data owner** at the destination; it is not wholesale branch-data merge. Child-task proposals pass `seon.task/create!`. Research findings use their declared data schema/blob owner. Root can select one proposal while discarding its exploratory definitions, messages, tasks, errors and requirements. Any newly necessary helper/schema/namespace is proposed for explicit scope expansion; a missing prerequisite refuses instead of silently widening the selection.

Avoid circular proof: a decision task's requirement validates the authorized substantive answer and its question/options digest; it must not require that task's own settled/acceptance predicate. Root records the answer as declared data, its requirement verifies that evidence, and acceptance then settles the task. Likewise, aggregate requirements cannot call their own completion through a parent/needs cycle.

### Identity and derived states

Detector task identity is `seon.id/id` of `[producer canonical-primary-subject]`: no title, timestamp, eid, current definition digest, or namespace agent. Different producers can raise tasks about one entity; repeated observations from one producer reuse its task. Authored tasks have once-admitted request IDs, allowing arbitrarily many tasks about the same subject. EDN supplies stable IDs. A changed detector claim is new requirement content on the same task, invalidating old proof; incompatible meanings require a new producer name. Do not rewrite authored prose on each sweep.

Below are **query definitions over one supplied database value**, not stored enum transitions. `current-proof(t,a)` means every current requirement has positive content/input-valid, terminated recorded evidence on the proposed/combined program; `accepted(t,a)` additionally joins an authorized decision covering that exact task digest, delivered subset and relevant observations, plus destination installation. Missing inputs/evidence are typed unknown. A failed query is never an empty successful relation.

| Derived predicate | Query meaning |
|---|---|
| raised | Task exists with admitted producer, claim and subject evidence. No requirement needed yet. |
| specified | Raised ∧ nonempty admitted requirements ∧ unambiguous scope/inputs ∧ links valid or explicitly expected/historical. A no-change or zero-child outcome still needs a requirement and explicit decision. |
| attempted | There exists an attempt whose task ID joins this task. “Active” additionally means admitted execution has no actual exit. |
| settled | There exists `accepted(t,a)` for current objective/requirements and all outstanding triggering observations. Candidate green alone fails this query. For no-code work, destination installation is the accepted reply/finding/decision fact. |
| blocked | Unsettled and an explicit `needs` target is missing/unsettled, a required input is unavailable, a requested root ruling is outstanding, or all runnable attempts are exhausted/unavailable. Return the blockers, not just true. |
| superseded | An authorized current supersede decision names existing replacement tasks and explains transferred obligations. Not equivalent to settled. |
| obsolete | An authorized current obsolete decision proves the premise no longer applies. A missing subject merely raises investigation until this decision exists. |

These predicates overlap: attempted remains true historically. A single display may choose obsolete → superseded → settled → blocked → attempted → specified → raised, while returning underlying facts. Later meaningful occurrences reopen the same detector task because no acceptance covers them; replay of the same occurrence does not. Past settlement remains historical evidence, not a reusable current boolean. A perpetual scheduled objective shows per-fire settlement and its next due fire, not permanent “done”.

Concrete proposed query shapes (UNVERIFIED until these attributes are installed): raised is `[:find [?id ...] :where [?t :seon.task/id ?id]]`; attempted adds `[?a :seon.task.attempt/task ?id]`. Missing-prerequisite blockers are `[:find ?id ?needed :where [?t :seon.task/id ?id] [?t :seon.task/needs ?needed] (not-join [?needed] [_ :seon.task/id ?needed])]`. The specified/settled/disposition queries join the requirement/attempt/decision relations above through the test owner's current-evidence checks; they must not replace that join with an existential green run or a new cached status datom.

`parent` groups; it does not mean all future descendants are completion obligations. A decomposition requirement accepts the explicit child set; a parent implementation requirement names which accepted children must settle. Empty decomposition is legitimate only with an accepted reason. `needs` alone establishes order. Call/reference reach can suggest needs, but shared subjects, duplicate reports and same-cause links do not. Validate parent/needs/supersession cycles and missing obligations at the writer; retracting a task must repair surviving obligations or refuse. No silent ref sweep can make work complete.

## 2. Producers and admission

**Proposed** `seon.task/create!` takes explicit connection + authored request; a pure writer function takes database + request and returns tx-data or throws its declared refusal. It evolves `issue/add-tx`, `subject-id` and `generate-subject-call`, not a parallel registry. Every function, detector and private helper has a complete named Malli contract. No task is launched just because it was created.

Admission order: validate the open task/component schemas with the carried projection; canonicalize subject values; resolve declared links by their identity attributes; verify author/provenance and request identity; admit requirements; then validate the complete final task/relations inside the writer. Same request ID with different content refuses. A detector batch is computed outside the writer on a captured database; the writer verifies its relevant basis/content or refuses stale evidence. Do not run a detector or tests while holding the writer, or mark missing batch members complete after a partial/failed scan.

A required link must resolve. A TDD exception names an expected subject and a requirement actually referring to it, with the intended function contract/schema/data shape; it is not a blanket allowance for typoed symbols. A missing historical subject is admitted only with an observation proving it existed at a retained basis, or an explicitly accepted investigation claim. The task points at that value even after deletion. Unknown legacy citations enter a triage task, not a fake program row. Validate schema keys and namespace symbols by their own populations; never `require-functions!` over arbitrary nested symbols.

For each example, acquire the named function's effective arity input schema from the same projection. Validate the **complete** argument vector, including legitimate declared supplied arguments; caller values win. A fixture's `prepare` runs in existing bounded test custody and its resulting arguments are validated by retained Malli validators. Admission retains the declaration/input digests; the writer checks them before accepting the task. If the future function is TDD-allowed, its explicitly proposed contract is the checked schema; later admission must prove correspondence. Invalid examples refuse before implementation starts, naming requirement, function, value path, expected shape and offending value. Do not serialize a database/compiled schema merely to make an example storable.

Detectors have the exact public shape `(database-value → vector-of-task-values)` and no clock/filesystem/network effects. A failed read returns/throws the detector's declared error, never `[]`. They derive over immutable facts; publication selects changed subjects and periodic schedule sweeps detect drift. Keep public population detection pure; subject-local requirements query the same predicate so settling one task does not rerun the whole census. Cache only content-valid derivations through existing owners, with missed/visited-work counters.

| Concrete proposed detector | Database predicate, identity subject, and positive exit |
|---|---|
| Open task whose subject is gone | Join unsettled tasks' value subjects to the relevant identity index; unresolved, not TDD-expected, previously observed subject → task about `[:seon.task/id affected-id]`. Motivated by deleted `seon.search/index-step`, `issue-audit/part-00.edn:438`. Exit: resolve/rewrite that subject with evidence or authorized obsolete/supersede disposition; absence alone never closes the original. |
| Cited code gone | Join explicit `must-read`/structured citation targets to current source/declaration facts and retained cited digest. Pathless deletion report at `part-00.edn:122`; deleted search citation at `:438`. Filesystem acquisition must first record facts through source owner. Exit: all required citations resolve at the reviewed basis or the task is explicitly dispositioned. Never scrape backticks from prose. |
| Fixed but open | Unsettled task has current complete requirement proof and an accepted matching delivery, but outstanding note/task disposition. `part-02.edn:226` keep-history claim is an example requiring verification, not a regex for “Applied”. Exit: acceptance/disposition facts agree with proof. Old notes lacking structured evidence need a one-time review task; title/status contradiction is not automatic success. |
| Function changed after its docstring | Current declaration/doc content differs from the pair last reviewed in a task attempt's decision inputs; missing review is unknown. `part-02.edn:1315` stale runtime prose and `:596` request-shape docs motivate it. History order is only a candidate warning: same doc text may still be correct. Exit: accepted review of current body/contract/doc tuple, possibly with unchanged doc; no prose parsing or touch-stamp. |
| Operation over its bound | Join a completed measurement's workload, phase, callable digest, actual duration/memory metric and declared bound; observed value exceeds bound. `part-02.edn:1650` includes the 600,001 ms backstop observation. Subject is operation plus the stored workload/bound identity, so incompatible workloads do not collapse. Exit: same-workload before/after evidence within the accepted bound, or an explicit root ruling changing it with reason. Missing samples/unfinished execution are separate unknown/failure tasks, not passing zero. |

The fixed-but-open detector is a consistency/triage detector, not a second settler. New writes should make acceptance and settlement agree atomically. For source notes it proposes disposition through root; it does not resurrect the deleted markdown importer.

EDN preload uses ordinary task values and stable IDs. Evolve `reconcile/plan` to reconcile **only the caller-declared authored attribute projection**, with final whole-entity validation; attempts/observations/decisions are outside that projection. Reapplication is no-write when unchanged; removal does not erase evidence or dependencies. Task retirement needs explicit disposition and later authorized retention policy. This is narrower than a task-specific reconciler. Replace config's all-symbol heuristic with declared link validation in the same slice.

Recurrence retains `seon.schedule` and its nominal-fire identity/claim. One fire creates one mechanical attempt; its function executes through the existing maintenance owner without an LLM. Migrate `seon.schedule.task`'s work declaration into task fields and retire that separate task family. Keep fire/maintenance result rows only where they prove timing, exit or external-effect facts that an attempt does not duplicate. Repeated claim is idempotent; task creation never implies paid execution. A failed run may produce a triage task under explicitly configured policy.

Error → triage uses the existing error signature/occurrence writer and ordinary delivery. Producer + `[:seon.error/signature signature]` gives the triage task; occurrences remain attached to the error and are pointed to from task/attempt observations. Its requirement demands a reasoned decision: agent mistake (explain/re-prompt), core fault (store/deliver and panic/record policy), product repair proposal, or existing same-cause task. Classification is declared triage output data, not a new global error discriminator. Recording an error without delivering the responsible task/wake is a failure. Budget exhaustion and “ask root” likewise create tasks with explicit question, evidence, options and decision requirement; they do not bypass the gate.

## 3. Context and a worked brief

A brief starts from the held task/attempt and read basis: claim; subject identities and present/missing state; exact requirements and their failing/unknown evidence; intended deliverables/guarantees; current contracts/schema forms; explicit must-reads; direct callers/references, schema consumers and relevant tests; observed errors/measurements; dependencies/root questions; custody, budget and actual callable next forms. Expand only justified edges, preserving whether each is a call, reference, declaration dependency, observation or obligation. Use existing AI/HTML pairs and render profiles; elisions name count/path/requery. Full gate selection is separate from prompt selection.

Current callable reads are `seon.db/db` (`src/seon/db.clj:2030`), `pull` (`:2453`), `q` (`:2231`), `seon.fn/tests-reaching` (`src/seon/fn.clj:1676`), and projection schema readers; `my.program/callers` (`src/my/program.clj:144`), `tests-reaching` (`:159`), `breaks` (`:276`), and `history` (`:357`) are existing composing owners, whose availability must be checked in the target SCI context. Do not print hypothetical `seon/schema` or `seon.task/describe`. Prompt visibility never limits callable authority.

**Worked brief — missing collection member index**, audit `part-00.edn:47`, original note `docs/seon/issues/a-collection-member-refusal-does-not-name-the-members-index.md:10`:

> Claim: a vector member refusal points at the container. Primary subject `seon.error/explain-problem`; supporting subject `seon.error/collection-member-problem`; key `:seon.error/path`; perspective `seon.error`.
> Requirement: under armed contracts, invalid ninth member yields path `[8]`, offending value `"bad"`, expected `:int`, and a fix locating index 8. Verify the example actually contains eight valid members before the invalid one. A separate set case must preserve its declared location semantics, not invent a sequence index.
> Proposed deliverables: those two functions; add only an explicitly accepted guarantee deftest if root chooses promotion. No blanket `seon.error` namespace delivery.
> Owner mechanism: `collection-member-problem` walks to the bad member and drops location (`src/seon/error.clj:792`); `explain-problem` retains supplied container path (`:811`). Malli structured paths and child schema are the input, not parsed error text. Direct live callers are `seon.instrument/violation` and `seon.error/refusal-data`.
> Current proof: reproduced at default basis 536872633, archived JVM; path `[]`, offending `"bad"`, expected `:int`, fix `"Supply an integer at []."`. Relevant full gate selection currently contains 1,374 tests: report that fact and query it separately; dumping it into this brief would hide the task. No test has been run or claimed green here.
> Next: use the target branch's admitted equivalent of the following verified JVM call; get schema forms through its carried projection; author the requirement before changing the producer. If branch submission cannot publish/arm it, return that named platform refusal and ask root through a task.

Exact read-only reproduction, MCP JVM mode, explicit root `/Users/sean/src/seon`, cluster `default`, timeout 5,000 ms:

```clojure
(let [database (seon.db/db (seon.cluster.boot/connection "default"))
      started (System/nanoTime)
      result (seon.error/explain-problem
              {:seon.error/problem
               {:schema (malli.core/schema [:vector :int])
                :value [0 1 2 3 4 5 6 7 "bad"]}
               :seon.error/path [] :seon.error/argument "values"})]
  {:basis (seon.db/basis-t database)
   :subject (seon.db/pull database
              [:seon.fn/sym :seon.fn/doc :seon.fn/arglists]
              [:seon.fn/sym 'seon.error/explain-problem])
   :identity-types
   (frequencies (map (comp :db/valueType val)
                    (filter (comp #{:db.unique/identity} :db/unique val)
                            (:schema database))))
   :result result :probe-ms (/ (- (System/nanoTime) started) 1000000.0)})
```

**VERIFIED live:** inner probe 0.446084 ms, MCP evaluation 2 ms; subject is a native symbol. A later read confirmed `(not (identical? @v (malli.instrument/-f->original @v)))` true for its Var: wrapped, not a fresh complete arming audit. Correct caller query: `(seon.db/q '[:find [?s ...] :in $ ?target :where [?caller :seon.fn/calls ?target] [?caller :seon.fn/sym ?s]] database 'seon.error/explain-problem)`; caller query plus gate count took 81 ms. `:seon.fn/calls` is native symbol-valued, not a ref.

The reproduction is a verified **JVM diagnostic**, not an advertised SCI instruction: the reported run could not call `malli.core` in SCI. T0/T6 must prove its branch-callable equivalent before showing it as an agent's next form. Until then the brief reports that exact unavailable operation. A source symbol or JVM Var alone cannot certify a usable agent opening.

Two exploratory reads were wrong and then corrected: guessed `:seon.fn/docstring` refused (actual key `:seon.fn/doc`); treating call targets as refs produced `ClassCastException`. Preserve these as researcher mistakes, not platform defects or passing probes. An earlier point-in-time JVM used-heap reading, `(let [r (Runtime/getRuntime)] (- (.totalMemory r) (.freeMemory r)))`, was 1,894,867,752 bytes; it is neither retained task memory nor per-call allocation. Allocation/retained-memory measurement of the new loop remains UNVERIFIED. The live runtime reported 35 historical over-second profiled functions; this design does not excuse or rerun those operations.

## 4. Coverage against the evidence

“PASS” below means the proposed model represents the case without a new family or runner. It is **UNVERIFIED implementation fit**, not executed acceptance. “FAIL today” names a demonstrated source/reported limitation; otherwise installation remains UNVERIFIED. Scenario references are to `task-scenarios-2026-09-24.md` at the cited lines.

| Scenario | Model verdict and required proof; present limit |
|---|---|
| 1 question (`:27`) | PASS: ordinary messages need no mandatory task; requested accountable work points at the message and requires an accepted answer. Reply existence alone fails quality. Installation UNVERIFIED. |
| 2 workout tracker (`:48`) | PASS: decomposition delivers accepted child tasks, including schema/data and new functions; explicit child completion governs parent. FAIL today: merge rejects additions. |
| 3 contract error (`:83`) | PASS: signature triage → decision → repair task with actual failure and requirement proof; bad caller need not cause a code fix. Full delivery/acceptance UNVERIFIED. |
| 4 doc/must-read staleness (`:115`) | PASS: content tuple review; equal doc text can be accepted, stale dependency makes review unknown. No transaction-touch freshness. Review facts UNVERIFIED. |
| 5 recurring maintenance (`:138`) | PASS: fire→mechanical attempt, no LLM, per-fire proof. Existing schedule seam VERIFIED source; unified task binding UNVERIFIED. |
| 6 EDN setup (`:165`) | PASS: stable IDs, authored-slice reconciliation, typed/TDD links. FAIL today: all-symbol function validation and whole-row reconcile. |
| 7 improve namespace (`:189`) | PASS: evidence-driven proposals or accepted no-work finding; focused references. No automatic broad edit mandate. Dogfood generalization UNVERIFIED. |
| 8 cross-namespace page (`:212`) | PASS: schema/render subjects, explicit scope and screenshot/review evidence. FAIL if only HTML/HTTP tests are offered. Browser integration UNVERIFIED. |
| 9 research finding (`:235`) | PASS: declared data delivery and provenance retained at destination before unlink. FAIL if scratch result handle is the sole evidence. Data acceptance UNVERIFIED. |
| 10 dependency bump (`:255`) | PASS: repository/pin data identity, fork test evidence, wrapper/must-read review. Pin delivery and program change are explicit; source-only checkout is insufficient. UNVERIFIED. |
| 11 root escape/review (`:272`) | PASS: ordinary decision task; revise/reject creates data refusal and reprompt; accept retains mandatory proof. UNVERIFIED. |
| 12 three agents (`:291`) | PASS: three attempts, separate custody/budgets; one accepted subset. FAIL today: scalar issue assignment. |
| 13 performance (`:307`) | PASS: workload/phase/bound and before/after data, including memory; missing samples refuse. End-to-end proof UNVERIFIED. |
| 14 render pair (`:315`) | PASS: existing detector adapted to task values, schema key and both render functions delivered; promote guarantee deliberately. FAIL today: general schema branch delivery unproven. |
| 15 owner decision (`:323`) | PASS: concrete options data + authorized answer tied to inputs; cannot infer semantics from green code. UNVERIFIED. |
| 16 merge conflict (`:331`) | PASS: root task points at retained B/C/H conflict value; repairs same intermediate branch, keeps disjoint changes, retests combined proposal. Full lifecycle UNVERIFIED. |

Ten representative audit checks (audit IDs below refer to `issue-audit/`):

| Audited issue | Model verdict; exact proof/disposition |
|---|---|
| Collection member index (`part-00.edn:47`) | PASS; example requirement `[8]`, producer fix, optional promoted guarantee. **VERIFIED live defect**, not yet repaired. |
| Pathless deleted source (`part-00.edn:122`) | PASS; file identity and captured source facts, publish/delete/pathless-publish requirement, surviving caller refusal. Live reproduction UNVERIFIED; db-only detector cannot inspect disk. |
| Deleted search predicate (`part-00.edn:438`) | PASS; historical symbol remains a value; subject-gone triage then root obsolete/supersede. Source-obsolete is not a fabricated green test. |
| Cohosted unbounded heap (`part-01.edn:2`) | PASS; decision first on actual memory guarantee, then authorized bounded two-cluster experiment. Isolation guarantee UNVERIFIED; task does not authorize destructive proof by itself. |
| Generated issue without tests (`part-01.edn:506`) | PASS; verify-only task checks detector-only start and neither-obligation refusal, then disposition; source claim already refuted by `create-tx`. No rewrite justified before proof. |
| Keep-history fixed but open (`part-02.edn:226`) | PASS; verify current explicit configuration/decision, accept no-code result, close historical claim. No regex “Applied” detector. |
| Lint identity collisions (`part-02.edn:263`) | PASS; two distinct findings at one owner/type/row/col, two identities; specific producer fix and guarantee. Same-cause link to lost findings does not impose sequence. |
| Namespace layout (`part-02.edn:662`) | PASS; wide/390px actual paint observation and authorized quality decision, linked to rendered content digest. Automated structural tests alone FAIL. |
| Lineage-keyed test evidence (`part-03.edn:275`) | PASS; property over equal/different content across branches, positive result survives unlink and unrelated lineage; no reset in this assignment. Installed fix UNVERIFIED. |
| Within-run schema refinement (`part-03.edn:731`) | PASS; root decision task precedes implementation, names no-data/no-dependent premise and exact target storage; an empty blocker query alone FAILS to decide policy. |

The audit's aggregate classes also expose two pitfalls: 61 large records need smaller task deliverables, not bigger openings; 169 unknown records need reproduction/decision requirements before prescribing a fix. A class task's property must enumerate positive members/premises; zero observations cannot satisfy it. Same-cause proposals remain hypotheses until reviewed.

## 5. Smallest build order: tasks on the existing owners

This is the proposed order to fold into B3/D1's owning plan, replacing contradicted sections rather than appending a second schedule. Prices are engineering estimates, not measured times. Split before implementation if any slice requires over roughly 100 added production lines; first seek an existing owner/dependency operation or deletion. The added attempt/components represent obligations absent today; the payoff is removal of the plan/task duplications, not permission for generic workflow machinery.

| Slice task / subject | Requirement | Deliverable, deletion, estimated work |
|---|---|---|
| T0 truthful branch evaluation / `seon.schema/register!`, MCP evaluation owner | Same schema declaration top-level and inside `do` either durably installs on the named branch and is callable/armed, or returns a named refusal; preserve defn-name metadata and actual event namespace; read after a new acquisition/test fork. For `:my.note/about`, first specify native symbol value shape and old-data policy, then prove ref→symbol on a live-store branch without reset. | Improve existing submission→registration delta→row admission→load/arm/record; remove false-success/nonsettling path, string round-trip metadata loss and invented context calls. Three bounded owner cuts, ~1–2 days; not one giant task. Schema type-change policy is D3 below. |
| T1 affordable, faithful refusal / `seon.instrument/boundary-refusal`, `seon.call-preparation/derive-snapshot` | Replay recorded 15 KB and 1.26 s specimens; preserve path/expected/offending/cause and full durable evidence, render once under profile; same warm refusal <1 s, measured allocation and retained heap, unchanged inputs cause no full snapshot derivation. | Evolve existing Malli explanation/value-renderer and content cache seams; delete duplicated schema-location/printer wrappers and per-call whole-program work. ~0.5–1 day after exact cause probe; no cap masking structural duplication. |
| T2 durable task values / `seon.issue/subject-id`, `seon.issue/add-tx`, task schemas | Native subjects survive target deletion/history/reconnect; two tasks can point at one entity; typed/TDD/historical links and example inputs validate; identical retries idempotent; stale detector basis refuses. | Fold A2's native-value proof into this prerequisite; evolve issue attrs/writer into task values and requirements. Delete ref-only citation coercion and status-as-truth. ~0.5 day task writer plus A2 dependency cost **UNVERIFIED**, not a new storage implementation. |
| T3 one objective, several attempts / `seon.issue/start!`, `seon.issue/settle-call`, `seon.plan/settle-call` | Three agents, one task, independent branches/budgets; only assigned work runs; resume same attempt; exit required before release; refused completion explains exact failed/unknown obligation and causes ordinary reprompt; ask-root task delivers. | Move assignment/budget/execution evidence to attempts through existing agent/turn/registry owners; replace tests-OR-detector with requirement conjunction; retire scalar issue-agent closure and plan-driven duplicate settlement. ~1 day, split writer/turn consumers as one loadable cut. |
| T4 requirements on actual code / `seon.test/run`, task requirement declarations | Task-local test can fail on captured base, pass on candidate, and fail on a mismatched candidate; no missing/zero-premise/unfinished evidence passes; full proof survives unlink. Requirement rows never enter ordinary program merge. | Adapt existing named-test request/recording to explicit task-local requirements and selected program tests; delete task-specific ad hoc check loops. ~0.5–1 day; use existing runner, no executor. |
| T5 root selects and accepts / `seon.cluster.source/prepare-merge!`, `accept-merge!` | Add function + schema + optional promoted deftest; reject missing scope/helpers, conflict and moved H; temporary requirements cannot count as permanent coverage; test exact combined selection; data/child-task delivery persists through its own writer; requirement/turn/error/private scratch omitted. No-code acceptance is valid. | Extend exact declaration transfer to additions/retractions/schema/ns, selected data-owner requests and explicit guarantees; remove replacement-only refusal and merge-all-candidate inference. ~1–2 days in bounded declaration/data cuts; full core contract/reach gate remains. |
| T6 task-centered opening / `seon.issue/opening`, `my.program/callers`, render pair | Worked brief reproduces defect using only advertised callable forms in actual SCI; contracts/current schema and two callers visible; large gate summarized with exact requery; refuse missing subject or unsupported publication explicitly. | Evolve existing opening/prompt/read owners to task reference selection; delete namespace tours, obsolete trial templates and fictional procedure text. ~0.5 day. Separate browser paint proof. |
| T7 producers and preload / `seon.issue/generate`, `seon.reconcile/plan`, `seon.schedule/fire-call`, error writer | Five detector fixtures incl. missing/failed scans; error repetition one task with new occurrence delivery; EDN apply twice no second write and no lost attempts; recurring fire one mechanical attempt, zero provider calls. | Convert existing detectors/config/recorder/schedule to common writer; declared attribute projection in reconciler; remove all-symbol manifest heuristic and separate schedule-work declaration. ~1 day in detector, config and schedule cuts. |
| T8 retire and demonstrate / `seon.issue`, `seon.plan`, `my.plan`, remaining callers | One authored repair, one triage, one recurring action and a schema/new-function task complete brief→attempt→refusal/repair→root selection→gate→accept→reload/arm; retained evidence and shared/candidate separation proved. Views show same tasks. | Mechanical rename issue→task with every caller/schema/render, retire note parser/indexer and per-agent plan tree/API; use parent/needs queries. ~0.5–1 day after previous behavior proofs. Do not automatically import issue markdown. |

T0–T1 are necessary platform repairs, not a request to solve every existing error before independent design work. T2–T5 make the data/acceptance loop real; T6 proves agents can use it; T7 broadens producers; T8 removes legacy paths with their final consumers. Rename only once, never run `seon.issue` and `seon.task` as two systems. Optional UI quality and broader detector inventory follow demonstrated use.

Costs to measure per installed slice: writer = changed task/components + bounded linked obligations; subject resolution = indexed identity lookups; readiness = reached needs/children, bounded with explicit unknown on exhaustion; brief = selected reference neighborhood; proof = actual required tests plus changed reach; acceptance = selected identities + their required closure validation. Detector sweep is necessarily O(scanned population), scheduled explicitly; ordinary changed-subject checks must follow the changed set. Target ordinary overhead <1 s, compare identical parent/change workloads, reject >20% or 50 ms regression; over-10-second proof needs owner authorization and an issue. Record wall time, allocation, retained heap, visited work, cache misses and actual exit separately.

Current checked-out sizes: `src/seon/issue.clj` 1,401 lines, `src/seon/plan.clj` 1,587, `src/my/plan.clj` 173, `src/seon/issue/opening.clj` 233. These are a deletion opportunity, not a promised net saving: count moved code/schema/tests at destination during implementation. Independent review must examine each concrete small diff and live evidence before the next cut. This assignment changes no production or test lines and runs no gate.

## 6. Owner decisions

Existing rulings are not reopened: subjects are values; requirements do not merge; guarantees need promotion; root accepts selected deliverables; every mandatory gate still applies. The older B3 scalar-agent/ref model (`lane-b3-errors-tasks-dials.md:213`) and D1's “union task tests into delivered tests” implications must be rewritten in their owning sections by their owner, not silently implemented from this research. Three genuinely open implementation choices follow; prices are estimates.

| Decision | Option, guarantee, cost and sacrifice |
|---|---|
| D1: native subject storage until A2 proof | **1. Typed native subjects for the first loop — recommended:** restrict admitted identity value types explicitly to the proven native family, using homogeneous native tuple attributes and a derived unified Subject view; refuse others by name. ~0.5 day, releases small task proof early, sacrifices full generality temporarily; publish exact temporary attrs before code. **2. Wait for A2 native heterogeneous proof:** keep the logical model above with no extra typed slots; task integration ~0.25 day after A2, A2 work unpriced until its measurements; sacrifices early loop availability. **3. Start symbol/schema-only tasks:** native symbol/keyword columns, refuse data subjects; ~0.25 day, simplest demo but cannot claim the general model or error triage delivered. Strings/refs as hidden substitutes are not an option. |
| D2: requirement source authoring | **1. Named task-local test source through existing admission — recommended:** exact callable identity and reuse of `seon.test/run`; ~0.5–1 day including exclusion/promotion proof, modest source boilerplate. **2. EDN expression requirements wrapped into named tests by the same analyzer:** concise task data and preserved forms; ~1 day, must prove metadata/source/fixture fidelity, more admission glue. **3. Existing program tests only initially:** ~0.25 day and smallest runner delta; sacrifices new-feature/TDD tasks until extension. None merges requirement tests implicitly. |
| D3: ref→value and other incompatible schema changes | **1. Refuse occupied incompatible changes until an explicit retirement/data-rewrite delivery is accepted — recommended:** final schema/data/referrer consistency, ~0.5–1 day over existing blocker/writer seams, more explicit work per change. **2. Add a new attribute, convert consumers, then retire old:** accretive intermediate state, ~1–2 days and temporary extra key, avoids in-place type conversion. **3. Owner explicitly discards the affected data in the same branch transaction:** ~0.5 day, proves new shape with no conversion, sacrifices those values and requires the exact discard set in the task. Never default-reset as schema proof. |

No owner answer is required to deliver this design. D1–D3 must be selected before dependent implementation, not resolved by an agent quietly broadening a storage/acceptance guarantee.

## Landing record

Deliverable only: this file. No default stop/reset/restart, branch creation, provider call, source change, test gate, push, or foreign-lane operation. Existing and concurrent edits outside this file are preserved. `bin/seon status` took 0.0294 s; fresh read-only JVM forms/evidence are above. Historical status ready time 7,552 ms and reported expensive profiling cells are not fresh boot measurements. Publication/reload clocks are not applicable to a documentation-only change; installed task behavior, browser paint, allocation and retained-memory costs remain UNVERIFIED.

Validation: the four audit EDN files parse and their 436 records reproduce every aggregate above. Standalone `seon.dev.markdown/validate-file` returned `{:valid? true :violations []}` (namespaced keys) in 0.151 s; explicit source paths/line bounds, linked research paths, 16 scenario rows, the 450-line ceiling and whitespace were checked. Production/test delta is 0/0. Automatic repository documentation lint reported 57 stale-citation issues, including historical Datahike/Malli pins in unrelated landing files; this is a foreign documentation boundary, not a pass and not changed here. The shared HEAD advanced during research; source anchors above describe the named inspected checkout, while live evidence names its separate archived basis.

The model represents all 16 scenarios and 10 chosen audit cases, subject to its explicit storage/admission/evidence gates. The existing platform fails several required operations today. The first proof should be one small real task with a callable brief, durable branch declaration, task-local requirement, explicit selected delivery and accepted combined evidence.
