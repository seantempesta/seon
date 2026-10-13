---
title: "TASK-CHAIN: one task handoff, one proof selector, one successful merge"
type: spec
status: LOCKED design; implementation pending the acceptance proofs below
created: 2026-10-11
---

# TASK-CHAIN

Tasks, assignments, branches, test evidence and the listened `seon.cluster.agent/db<->flow` already exist.
Compose their owners over immutable data; delete the competing selectors and completion policies. **LOCKED means
these are the implementation decisions, not that the target is installed.** The owner's current rulings supersede
older designs, including the dated reviews retained below. Superseded decided text remains in Git.

## 1. Data

**Intake (root) → Survey → Define → Design → Prove → Implement → Land/Retrospective (root).** Every worker,
including root, has an ordinary task. A subject owns component `:seon.task/children`; sibling `:seon.task/needs`
refs impose order. Successors fork main after their needs merge. An agent writes its own branch; root alone
applies effects on main. Graphs and SCI contexts remain live resources in their existing registry; lifecycle
intent, assignments, ends, evidence and outcomes are database data.

The subject's `:seon.task/intent` is **one growing Markdown document**, revised in place: Problem, What exists,
Examples, Expected cost, Approach, Results. Steps carry `:seon.task/instructions`. `seon.task/effective` merges
schema-declared content down children, root first, step last; never along needs. `my.task/refine!` writes intent
and product to `seon.task/goal-owner`, instructions and copied proofs to the held step, preserving every other
declaration member. This routing expresses ownership; allowed members come from the schemas.
References remain derived: `seon.task/with-references` passes every string value the task holds, recursively,
to `seon.ref/names`; resolution uses `seon.ref/references` over those same strings. Symbols remain the stored
reference set; schema keywords and document paths resolve from text without changing that set's meaning.

Five templates are ordinary held task entries in `resources/seon/tasks/seon.step.edn`, named
`seon.step/survey`, `seon.step/define`, `seon.step/design`, `seon.step/prove`, `seon.step/implement`.
`seon.task/chain!` constructs the subject and **all** clone declarations, children and needs in memory, then
calls `seon.task/adopt-tasks-tx` once through the checked writer. Identity comes from `seon.task/task-handle`
and `seon.task/task-sym`. Only the subject initially has `:held`; clones inherit execution. One later
`:running` fact readies exactly Survey. Repeating the same construction changes nothing.

Clone instructions, step and the template's selected proof-symbol set as plain `:seon.task/proofs` data.
Root customizes the clone's instructions/proofs; removing a copied proof changes that set, while removing a
locally tagged proof changes its test tag. **`:seon.task/template` is the only new persisted attribute**:
an optional peer task ref, provenance only, never consulted for execution or proof selection; deleting a template
sweeps this optional ref, while history retains provenance. Template edits affect future clones only; changes to
a selected test's body still follow ordinary program identity. Reuse the existing proofs schema as an authored
optional set in declaration/entry/task; absent means no copied proofs. Extend the step enum with `:survey :define :design`. No notes, PRD, goals, examples, expected-cost, declares, exit or verdict attributes.

`:seon.test/task` is an explicit **test request input**, not a new stored lifecycle attribute: the held step's
handle, carried through request → member custody → `:seon.task/id` binding. Its effective declaration and
selected obligations come from the member's candidate database. Include their content and captured read evidence
in the existing member evidence identity; changing task input invalidates reuse, unrelated facts do not.
No test discovers its subject from the test agent's assignment; no handoff test recursively runs the test runner.

## 2. Rules: datum → reaction

| Datum → reaction | One policy owner (effect helpers do not decide again) |
|---|---|
| Subject declaration + ordered templates → one cloned population | `seon.task/chain!` |
| Inherited execution + needs + accepted assignment outcomes → ready leaves and derived subject completion | `seon.task/ready-rules`; `seon.task/ready`, `seon.task/done?` query these same rules |
| Ready leaves, open attempts, registry observations and existing capacity dial → assign/start/pause/resume/stop/cancel | `seon.cluster.agent/plan`; `seon.task/assign!` owns assignment writes for direct and reconciler entrances |
| First attempt, changed program/task inputs or stale selected evidence → run obligations and reaching tests, acquire candidate evidence | `seon.turn/test-step`; `seon.turn/prove-turn` delegates to it before any model call |
| Current candidate observation → working or proposed `:done` | `seon.context/step-status`, consuming the shared verdict |
| Unanswered work and remaining budget → next work | `seon.turn/next-agent-work` |
| Candidate proposal, disposition or spent budget → attempt end | `seon.turn/task-end` |
| End with no open evaluation → record end and close admission | `seon.turn/end-task-call`; `:done` proposes acceptance, never completes the task |
| End + accepted/refused merge + remaining work + relaunch dial + actual resource exit → merge/close/relaunch/publish/retire actions | `seon.cluster.agent/assignment-actions`, made the shared pure end-to-action function used by `next-actions`, `close!` and `failed-pass!` |
| Candidate, effective task, obligations and current evidence accepted → declarations, task refinements and `:merged` outcome in one writer transaction | `seon.cluster.source/accept-merge!`, for automatic merges **and** `my.root/merge!` through `seon.merge/merge!` |
| Accepted declarations awaiting publication → write-back, reindex and Git commit; then release branch after execution exits | `seon.merge/publish!`; existing publication facts feed `assignment-actions` |
| Recorded friction → root works its ordinary retrospective task | `seon.cluster.agent/plan`, as for other ready tasks; root uses the ordinary task writers |

**Success belongs to the accepted candidate.** `seon.merge/merge-tx` consumes the acceptance decision; it no
longer infers `:merged` from “end is not sent-back.” Task-only, empty-program-delta and manually requested pre-end
merges take the same acceptance path, with task inputs from the actual candidate. A no-op still proves its
obligations on main and records its successful assignment. Delete `:satisfied` throughout. A sent-back or other
unsuccessful end may transfer its authorized facts; it never writes successful completion. A later send-back
reopens its target through the existing ready rules and blocks successors until that target merges again.

Static candidate acquisition (three-way, proposed files, lint, contracts, references) and test acquisition
(current members, reach, product and baseline observations) feed **one** pure verdict. Branch preview and merge
use that predicate over their respective held candidates; a preview is not a promise about a future main.
`prepare-merge!` still tests the combined candidate. `accept-merge!` carries its task/program read basis into
the existing writer comparison, including unchanged dependencies the proofs read, so it cannot accept different
inputs from those tested. No whole-head timestamp, independent exit validator or refusal-class whitelist.

The turn acquires evidence once per changed input; context only reads it. Use existing recorded test/error/evaluation
facts and content-keyed `seon.cache` derivations, not agent verdict attributes. Missing/evicted acquisition is
unknown and the next test step acquires it before proposing an end. Refused post-end merges use the same end-to-action
rule and existing relaunch allowance, preserving end commit and merge base; an unchanged green relaunch takes zero
model calls. Caps/stalls with work use that rule too. Exhaustion remains visible to root. Remove taskless lifecycle
arms by supplying ordinary tasks at their creation entrances; remove parked/mailbox refusal counters as lifecycle
policy by recording the existing error end at its producer. Actual-exit ownership remains with Flow's resource owner.
Publication is a separate durable effect: a refused publication stays visible and retains its branch; `:merged`
readies successors on main, but does **not** claim disk/Git publication succeeded.

## 3. Shared functions and schemas

Names marked **new** are proposed interfaces, not live claims; new named value schemas add no persisted attributes.
The observation holds the prepared candidate, effective task, obligations, current test results, captured basis/read
evidence and static errors under their existing schema names; acquisition failures remain errors, never empty success.

| Owner | Contract vocabulary and composition |
|---|---|
| `seon.task/declaration` | `:seon.schema/projection` × task value → `:seon.task/declaration`; `malli.core/children`, `deref-all`, `type` derive members, cardinality and ref conversion. Normalize only declared sets; preserve vectors, tuples, order and duplicates. Constructors, refinement, pulls and write-back consume this derivation, including scalar template refs. |
| `seon.task/effective` | `:seon.db/database-value` × `:seon.task/handle` → `:seon.task/effective`; derive the content selector from that schema. It includes product, not structural children/needs or inherited clone proof sets. |
| `seon.task/obligations` | database × `:seon.task/sym` → `:seon.task/obligations`. Green proofs = copied set ∪ own tagged deftests; Implement additionally takes subject-tagged behavior proofs and effective product. Prove selects its effective product separately for designed-red evidence. Delete inference from every successor tag: it would misclassify green template proofs as red product. Convert existing Prove declarations in that cut. |
| `seon.test.runner/designed-red?` | `:seon.test/result` → boolean; the only predicate over executed failure reports, used by the verdict and template test. Missing execution/load failure is not designed red. |
| `seon.cluster.source/merge-verdict` (**new**) | named `:seon.source/merge-observation` → vector of `:seon.error/value`; compose extracted static and test predicates over acquired values. Positive complete evidence required; green obligations, Prove's nonempty designed-red product, reaching regressions, coverage, file equivalence, lint, contracts and dangling refs retain their existing owners. |
| `seon.cluster.agent/assignment-actions` | `:seon.flow.plan/held-assignment` with acquired work/relaunch/exit observations → `:seon.flow.plan/actions`; extend these existing value schemas, collapse the policies in `close!`, `failed-pass!`, `refused-merge-relaunch` and `relaunch-reason`. |

All proof consumers use `obligations`: assignment/admission, first-proof detection, staleness, execution, context,
merge and task-edge display. They may project its result, never query another proof set. Prove runs product first,
then handoff proofs that inspect those recorded results, then remaining reaching tests; Implement runs the same
product as required green tests. The runner's existing evidence key includes task input; no clone-specific runner.

Cost follows declaration members/strings, lineage depth, selected tests and changed candidate files. A chain is one
write proportional to clones plus edges. Candidate acquisition is not just validation: charge lint, baseline tests,
recording, merge and publication separately. Historical static-only measurements are not full-loop budgets.
Define records workload/proportionality and expected cost in intent; Prove expresses it with `seon.profile/measure`
deftests. Prove authors a cost deftest comparing its measured probe with `seon.db/stored-costs`, using the existing
`seon.batch/cost-diff` comparison and the same workload, evaluator, scope and captured baseline. A comparable
regression is a failing assertion; no resurrected COST-REG verdict/dial system. A missing or
incomparable sample is unknown, requiring measurement, never a green result. Performance claims need end-to-end
parent/change measurements; line estimates are information, not limits.

## 4. Template instructions and default deftests

The five defaults live in `test/seon/step_test.clj`, tagged to their matching template task. They read the explicit
task input and use the existing `seon.ref` CommonMark parser for document sections; extend that owner for strings,
not a new Markdown/EDN mini-language. Section checks prove presence, **not relevance or design quality**. Root
customizes with subject-specific deftests; agents exercise judgment in Survey/Design and Implement's send-back.

| Stage/template | Instructions summary → default proof and assertions |
|---|---|
| Intake (root task) | Reuse/file the subject, state the problem and instantiate the chain. Its construction proof is A3 below; no second Intake template. |
| Survey | Reproduce at the entrance; inspect libraries, history and consumers; put localization and observations in What exists, with inline references. `seon.step-test/survey-handoff`: Problem and What exists sections contain content; all cited references resolve on the candidate. Recorded repro is cited; an incident's exact input/expected/actual becomes a subject-specific regression, not printed-value parsing. |
| Define | Refine Problem, Examples and Expected cost: success, boundaries, workload and proportionality. `seon.step-test/define-handoff`: those sections contain content and references resolve. Prove turns their examples/costs into executable tests; this default does not pretend prose is an oracle. |
| Design | Trace data/interfaces, contracts, consumers and deletions in Approach; reuse existing interfaces, stub only genuinely new ones. `seon.step-test/design-handoff`: Approach contains content; cited interfaces resolve and function contracts compile in the candidate registry using `malli.core/schema` and the shared contract owner. Add step-tagged interface deftests for bounded samples and new stubs: invoke a stub and assert its declared unimplemented error, supplying green coverage for the Design merge. Prove later writes the red behavior product. Generators require a supported law. |
| Prove | Add exact/boundary cases to existing tests where possible; declare product on the subject, tag behavior tests to the subject/Implement; measure costs through the real entrance. `seon.step-test/prove-handoff`: product nonempty, every product symbol resolves to a deftest with current designed-red evidence, and product is a subset of Implement's selected obligations. It reads results, never runs them recursively. |
| Implement | Read intent and proofs, send back a contradiction with counterexample; change the producer, convert callers, delete replacements, turn product green. `seon.step-test/implement-handoff`: effective document references resolve. Behavior/cost proofs and candidate acceptance supply the substantive completion evidence; the default does not duplicate their judgment. |
| Land/Retrospective (root task) | Merge after each step; publish; inspect costs/friction and improve future templates. A2/A5 assert accepted/merged/written identity and positive Git evidence; retrospective follow-ups carry red proofs. No mandatory model call for an uneventful retrospective. |

## 5. Acceptance deftests (implementation must start red)

These are **specified, not newly implemented or run by this docs slice**. Existing-source counterexamples are in
[the review](../../../research/agent-platform/2026-10-13/review-lifecycle-astra.md) and both narrow checks.
Each test carries `:seon/tasks` metadata for its phase task below; no phase is assigned until its red reproduces.

| ID / proposed deftest | Required counterexample and assertion |
|---|---|
| A1 `seon.task-test/one-selector-serves-every-proof-consumer` | On indexed branch analysis, census every proof-selection entrance and assert it reaches `obligations` with no independent task-tag query. Behavior table: template-only first attempt, customized copied set, subject proof inherited by Implement, Prove product distinct from green handoffs. Assert identical selected sets in run, display and merge; no checkout-source census. |
| A2 `seon.cluster.merge-test/only-an-accepted-candidate-completes-a-task` | Automatic and `my.root/merge!`, with/without an end, changed/task-only/no-op candidates: red or stale task proof cannot produce `:merged`; a valid candidate does, in the same transaction as refinement. Change main/task input after preview; acceptance uses the actual combined value. Include Prove red-product and unrelated reaching-red cases; no empty-delta bypass. |
| A3 `seon.task-test/one-write-clones-a-chain-and-one-running-readies-survey` | Count the construction transaction, pull all clones and edges; children have no explicit execution. One subject `:running` yields only Survey. Change template instructions/tags and customize a clone: existing selection stays fixed, next clone changes. Task-file round trip preserves it. |
| A4 `my.task-test/refine-preserves-every-declared-member` | Through create → refine → pull → write-back → re-ingest, product/copied proofs/template survive. A local schema extension with a vector preserves order/duplicates/empty vector; only declared sets normalize. Derive tested members from schema. References from every string reach the same resolver. |
| A5 `seon.cluster.db-flow-test/a-scripted-full-chain-completes-without-hand-acts` | Canonical isolated fixture, scripted replies injected through `:seon.turn.loop/complete`, zero paid calls or Var redefinitions. One construction and launch; real listens, five agents, SCI, test runner, merges, disposable checkout publication. Each successor reads the accumulated intent and predecessor's program on main; product red at Prove, green at Implement; every assignment merged, subject done, publication/Git observed and branches retired after actual exit. Record calls, timings/allocation and zero post-launch hand acts. Table variants cover send-back, capped continuation, moved-main refusal then zero-call green relaunch, publication refusal visible, and root/scheduled agent creation with tasks. |

## 6. Implementation phases and file ownership

**Status: all phases pending; no implementation acceptance claimed.** Serial where files overlap. Each row is a
phase task in `resources/seon/tasks/seon.task.edn`; tag the named acceptance deftests with that task symbol. A5 is the final full-loop proof; earlier action
variants are `seon.cluster.db-flow-test/ends-share-one-action-owner` and
`seon.cluster.db-flow-test/every-agent-start-has-a-task`, tagged to `seon.task/chain-actions`; they use ordinary
task declarations and do not wait on the later template constructor. Every phase must turn its own proof green.
Deletions and every caller/schema/test conversion commit together; no dual verdict writer between phases. Each
candidate must lint, publish/reload its changed schemas and boot through `bin/lane commit --test` before commit.

| Phase task / proof | Delete first; replacement and complete file groups |
|---|---|
| `seon.task/chain-declarations` / A4 | Delete constructor, effective-content, ref and file-member hand lists, blanket sequential→set normalization. Derive from schemas; preserve product and references; admit copied proofs, template ref and stage enum here so A4 round-trips the complete declaration. `src/seon/task.clj`, `src/my/task.clj`, `src/seon/ref.clj`, `src/seon/cluster/source.clj`; schemas `seon.task.edn`, `my.task.edn`, `seon.ref.edn`; tests `task_test.clj`, `ref_test.clj`, `my/task_test.clj`, `cluster/write_back_test.clj`. |
| `seon.task/chain-obligations` / A1 | Delete duplicate tag selectors and implicit successor-product selection with all consumers converted. Add explicit test input/evidence and use the admitted copied proofs. `src/seon/{task,turn,context,test}.clj`, `src/seon/test/runner.clj`, `src/seon/cluster/{agent,source}.clj`, `src/seon/merge.clj`; matching task/test/context/flow-plan schemas, `seon.test.member.edn`, schema parity; `test/seon/{task,test,context}_test.clj`; convert existing Prove entries in `resources/seon/tasks/` found by census. |
| `seon.task/chain-acceptance` / A2 | Delete `unmet`, context's designed-red/contracts/base-results copies, broken/new-red verdict production and readers, `:satisfied`, facts-only success and empty-delta refusal. Extract shared observation/verdict and route both merge entrances through acceptance. `src/seon/{context,turn,task,merge}.clj`, `src/seon/cluster/source.clj`, `src/seon/cluster/agent.clj`, `src/my/root.clj`; schemas `seon.{agent,context,task,task.attempt,turn.loop,source,render.walk}.edn`, parity; tests `context_test.clj`, `test_failure_facts_test.clj`, `cluster/{merge,db_flow}_test.clj`, `my/root_test.clj`; retire quoted deleted symbols in owning task files. |
| `seon.task/chain-actions` / A2, A5 variants | Delete `fixable-refusals`, duplicate relaunch decisions, taskless actions and local refusal-count lifecycle. Compose `assignment-actions`; root, outside and schedule creation supply tasks. `src/seon/{turn,cluster,mcp,schedule}.clj`, `src/seon/cluster/{agent,host}.clj`, `src/my/agent.clj`; their configuration/schema/default owners and `test/seon/cluster/{db_flow,agent,boot}_test.clj`, `test/seon/schedule_test.clj`, `test/my/agent_test.clj`, `test/seon/turn_continue_test.clj`. Census actual creation entrances before editing; no orphan root or taskless compatibility path. |
| `seon.task/chain-templates` / A3, A4 | Replace hand-built chain setup with one population writer and five cloned templates. `src/seon/task.clj`, `resources/seon/tasks/seon.step.edn`, `test/seon/{task,step}_test.clj`, `test/my/task_test.clj`, `src/seon/bootstrap.clj`, `docs/seon/architecture/vocabulary.md`. |
| `seon.task/chain-loop` / A5 | Delete superseded two-step-only fixture setup; use templates and canonical fixture. `test/seon/cluster/db_flow_test.clj`, shared `test/seon/test_support.clj` only for reusable setup, `test/seon/step_test.clj`, this card's phase status. Add measured cost regression cases through `seon.profile/measure` and existing stored-cost comparison owners; no separate cost gate. |

The observed namespace-source defect remains a prerequisite producer fix, not a lifecycle exception:
`seon.fn/runtime-require-specs` feeds `seon.sci.eval/namespace-context-row`, replacing `declared-source` append;
`my.program/source` shows the resulting namespace form. Files: `src/seon/{fn,sci/eval}.clj`, `src/my/program.clj`,
`src/seon/bootstrap.clj`, their task files and `test/seon/cluster/write_back_test.clj`, `test/my/program_test.clj`.
Tag `seon.cluster.write-back-test/repl-requires-never-corrupt-the-written-ns-form` to
`seon.task/chain-namespace-source`: replay cross-request require history and clean `ns` repair through file proof.
Do not teach the repair before this red passes. This slice may precede the serial phases; it adds no lifecycle rule.

## 7. Narrow-check questions and evidence limits

Before filing each phase, verify on the installed system or its disposable candidate:

1. Does the schema-derived round trip preserve scalar template refs, copied sets and vectors, including refine of
   an existing product? Does one population write omit child `:held` without a repair transaction?
2. Does the selector census cover admission, first proof, stale detection, task-edge/context displays and both
   merge entrances? Can the member read explicit task input before assignment, and does changed intent invalidate reuse?
3. Does the candidate include task refinements **before** tests run? Are all read dependencies compared in the
   writer, including a task-only/no-op merge and a root request with no end? Can any other writer produce `:merged`?
4. Can default proofs inspect current product evidence without recursion? Can the same test body run for two
   clones without borrowing the other's green? Does a template proof-membership edit leave old clones unchanged?
5. Does the action census include direct assignment, root/MCP/schedule creation, close/cancel/error and actual exit?
   Does every declared post-end refusal reach the new attempt/root with no class list? Publication refusal is not success.
6. Does A5 run through real listen wakes rather than manually advancing phases? Does the injected completion
   remain zero paid calls on all variants? What are full acquisition/test/merge/publication costs, not just validator CPU?

Source and JVM-read evidence underpinning the decisions: [review](../../../research/agent-platform/2026-10-13/review-lifecycle-astra.md),
[hybrid](../../../research/agent-platform/2026-10-13/hybrid-lifecycle.md),
[first narrow check](../../../research/agent-platform/2026-10-13/narrow-lifecycle.md),
[second narrow check](../../../research/agent-platform/2026-10-13/narrow-lifecycle-2.md),
[reference inventory](../../../research/agent-platform/2026-10-13/reference-reading-list.md),
[cost history](../../../research/agent-platform/2026-10-13/cost-estimates-history.md),
[stage history](../../../research/agent-platform/2026-10-13/chain-steps-history.md),
[lifecycle dig](../../../research/agent-platform/2026-10-13/dig-step-lifecycle.md).
The latest owner rulings settle those documents' open questions about templates, stage order, cost proofs and exit attributes.

[Commonality inventory](../../../research/agent-platform/2026-10-13/lifecycle-commonality.md), source-checked:
A1/A2/A3/A5/A6/A17 collapse into the owners above. Do not add its proposed parallel `proof-syms`, `judged-tests`
or `end-kind` policies. An enum supplies allowed values, not reactions. `seon.test/green?` supplies count judgment
(expose the existing function); completed/terminated evidence and current reach remain separate facts. A7's merge-base
fallbacks, A10's latest-attempt/count and A16's branch-delta/three-way comparison are **not equivalent** and are not
collapsed by assertion. Keep the merge's basis owner; share held assignment/task values across callers instead of
re-pulling handles. `seon.task/effective`/`goal-owner` and context share one lineage acquisition, while readiness
keeps the existing Datalog rules. No new generic accessor family.

Dependency seams checked locally: Malli `malli.core/children`, `malli.core/deref-all`, `malli.core/type` and
`malli.core/validator`; Datahike `datahike.versioning/merge!` and `datahike.writing/merge-writer!` retain the
serialized writer and final-report validation; `sci.core/fork` isolates definitions; Flow stop requests stop,
not actual exit. Reuse Seon's owners of those seams. Cheap live JVM reads reproduced the missing constructor/stages,
reference derivation and existing proof-set schema; they do not prove the new design. Default's reported faults
and unrelated working-tree changes are foreign to this documentation slice; no restart or root SCI is needed.


## 8. Reviews

(Fable, then Astra, append their improvements below; root and the owner discuss.)

### 8.1 Fable (2026-10-11)

Verified against HEAD be10632f3 by symbol: `seon.turn/step` (the pass runs `test-step`, then, when a model pass closed
its turn, `end-task!`), `seon.turn/task-end` (`:done` is `seon.context/task-done?`: every tagged proof green on the
branch and no reached red), `seon.turn/end-task!` (`seon.context/outcome-tx` at the task home → `end-message` to root → `end-task-call` closes admission),
`seon.task/assign!` (runs the proofs in the agent's own world first; refuses `:proofs-green`, `:proofs-unobserved`,
`:no-proofs`), `seon.task/task-edges` (proofs = deftests tagged with the task symbol), `seon.task/refuted` (proofs red at a
reach no green member shares), `seon.task/resolution` (closedness derived: faults, signal, outcome; nothing stored),
`seon.cluster.message/send!` (writes on the recipient's home branch), `seon.cluster.agent/graph-definition` (one graph
per agent: mailbox → turn, plus schedule), `clojure.core.async.flow/impl` (`report-chan` and `error-chan` are
`sliding-buffer 100`: lossy by design, so nothing durable may depend on them).

#### 8.1.1 Is this correct software engineering?

The chain is the discipline in the right order: reproduce (Mine) → localize and read the data flow (Survey) → model the
data (Model) → specify by failing tests (Prove) → implement to green (Implement) → integrate under a gate (Land) →
retrospect (Review). It is red-green TDD with the red written by a different agent than the green, design-by-contract
in Model, and the reproduce → localize → patch → validate shape the assignment names (I did not verify outside
sources; the comparison is to the shape as given). Four corrections:

| Finding | Change |
|---|---|
| **The interface is nobody's stage.** Prove needs the function names, arities and contracts to exist as red stubs; Model only covers schemas. Design-by-contract puts the contract before the tests. | Model becomes **Design**: schemas *and* the contracted stub declarations (`defn` with its `:malli/schema`, body `(fail! ...)`), so Prove's deftests compile against real symbols. Its `:unchanged` decision covers "no new schema, no new symbol". |
| **Survey and Model separate costs a model call to say `:unchanged`** on most tasks (this weekend's tasks added no schema). | One **Design** stage in pipeline A (survey + design); the split is pipeline B. Stage types stay separate entities so the data can say which paid. |
| **Check as its own agent duplicates Prove's stage proof.** Everything mechanical in Check (tagged, red on main for the stated reason, reads the program, cheap) is Prove's proof below; what remains ("encodes the intent") is judgment. | Fold Check into Implement's first turn with a send-back right (§8.1.4); measure Prove→Implement send-backs. A Check agent is pipeline B. |
| **Review is a retrospective, not code review.** Established practice reviews code before merge; here Land auto-lands on green and no one reads the diff first. That is a deliberate choice (immutable store, root's `decide!` can decline later), but name it. | Rename Review to **Retrospective**; keep root's `seon.task/decide!` as the post-landing code review of record, with the agent's diff value in root's block. |

Nothing is mis-ordered. Mine is correctly a query most of the time; its reproduction form on the task is Survey's input.

#### 8.1.2 One mechanically checkable stage proof per stage

A stage proof is an ordinary deftest that reads **the task the running agent holds** from its world
(`seon.context/held-task`, exactly as `task-done?` does), so it is parameterized by custody, not by argument. Each is
owned by a stage type entity and tagged by **stage type**, not task; `seon.turn/task-proofs` unions the task's own
tagged proofs with the current stage's proofs (one `or` in one function; no new runner). Then `task-done?`,
`task-end`, `assign!`'s refusals and the automatic test step are the stage machinery, unchanged.

| Stage | Reads | Red when | Green by |
|---|---|---|---|
| **Mine** | the task row; `:seon.task/evidence` ∋ a reproduction form; `seon.test/run` of that form on main | the form answers the expected (good) value on main, or an open task has the same subject symbol and intent digest | record the form that shows the bad value; name the subject |
| **Survey / Design** | `:seon.task/must-read` resolved on the branch (`[?e :seon.fn/sym s]`, `:seon.ns/name`, `:seon.test/sym`); the intent's backticked symbols resolved the same way; the decision component; for `:added` keys, `malli.core/schema` compiles and `m/validate` holds on each `:seon.task.stage/sample` | any reference unresolved; intent names no existing symbol; no decision; an added key that does not compile or whose samples fail; a declared stub symbol without a complete contract | prune and add references; rewrite the intent in typed symbols; register schemas and contracted stubs; write the decision with `:unchanged` + reason when nothing is new |
| **Prove** | the deftests tagged with the task (`task-edges`); `seon.task/refuted` on the branch at the head program; each proof's run result (`:seon.test/elapsed-ms`); the proof's var usages (`clojure.core/slurp`, `clojure.java.io/*`) ; the decision | no tagged proof; a proof green at the head program (`assign!` would refuse `:proofs-green`); a proof whose failure data is not the stated class (an `:error` where a `:fail` is stated); elapsed over the dial `:seon.config.test/step-time-limit-ms`; a proof reading files; no decision on "tests asserting the old behavior" | tag proofs; make each fail with the stated data; keep them program-reading and cheap; record `:changed #{old tests}` or `:unchanged` + reason |
| **Implement** | today's `task-done?`; the stage decision | any proof red, a reached test red on branch and not on main, or no decision | the body; the decision `:changed #{syms}` (or `:send-back` with target and reason: the proof or intent is wrong, with the data) |
| **Land** | the merge task (`:seon.task/landed-git`, `:seon.task/refusal`, `:seon.task/refused-tests`) | neither landed nor refused with a routed target | root's `my.root/merge!`; a refusal writes the decision `:send-back` to Implement (red reached test) or Prove (refused proof) |
| **Retrospective** | `seon.task/resolution` on the home; the task's open follow-ups; the stage type's template revision | the task's proofs are green on main but it is still open (no retirement); friction recorded with no template change and no `:none` decision | retire (derived, §8.1.3); file follow-ups as tasks with red proofs; edit the template or decide `:none` |

Judgment stays in two places only, both recorded as data and judged downstream: Survey's choice of references (scored
by Implement's calls and send-backs) and Check-in-Implement's "the proofs encode the intent" (scored by whether the
landed change was declined by `decide!`). No stage proof calls a model.

#### 8.1.3 Advancement with no machinery: the end already does it

Today's facts: an agent's proofs run inside its own pass; a green end runs `end-task!`, which proposes, lands under a
dial, writes the outcome at the task home, sends root one message and closes the agent. That *is* a stage transition
for the last stage. Make it the transition for every stage:

1. **The task carries its stage** (`:seon.task/stage`, a ref to the stage type entity) and its decisions
   (`:seon.task/decisions`, cardinality-many component of the §3b map plus `:seon.task.stage/target` for send-backs).
   The stage type entity holds `:seon.task.stage/proofs` (test symbols), `:seon.task.stage/next`,
   `:seon.task.stage/template`. Pipelines are a vector of stage ids on a pipeline entity; the task refs its pipeline.
2. **`seon.turn/task-proofs`** returns the task's tagged proofs ∪ the current stage's proofs. Nothing else changes:
   `task-done?` is "stage done", `task-end` answers `:done`, the block shows the red stage proof with its data.
3. **`seon.turn/end-task!` gains one branch, `advance-end!`**: for a `:done` end whose task has a
   next stage (or a `:send-back` decision with a target), in the same transaction as `outcome-tx` it sets
   `:seon.task/stage` and calls the one launch path the chain runs already (`seon.competency/start-run!`: a fresh agent,
   its branch, `seon.task/assign!`, which runs the new stage's proofs red first). The merge runs only when the
   stage is Implement. Under a dial `:seon.config.chain/advance?`.
4. **The next agent's first wake is its assignment**, as today. Its context is `seon.context/current-state` rendering
   the stage template from the stage entity (derived at render, never stored: the card's `:seon.task/instructions`
   field goes), then the task as the previous stage left it.
5. **Root observes, it does not drive.** The end message it already receives carries the task; its block derives the
   stage column. Root acts only on a typed refusal that routes nowhere or a send-back count over
   `:seon.config.chain/max-send-backs`.

In Flow terms the edge between stages is **not a channel**: each stage is a fresh agent graph
(`seon.cluster.agent/graph-definition`), the handoff is the task entity at the task home, and the event is the
recording transaction of the previous agent's last pass. No pipeline proc, no stage proc, no supervisor, no poller, no
`::flow/report` consumer (lossy), no `ping` loop, no `pause`/`resume`, no done-check registry, no second state machine.
**What disappears from the card**: §2's done-check functions and `:seon.task/instructions`; §6 item 3's "transition"
as a mechanism (it is three lines in `end-task!`); the Check stage of §3 for pipeline A.

**Task edits land at the task home.** `my.task/refine!` is `seon.task/create!`'s upsert with `:seon.task/basis`,
written through `seon.task/home-connection` with the agent as transaction provenance, exactly as
`seon.cluster.message/send!` writes on the recipient's home branch: the handoff must be visible to a fresh agent on a
fresh branch, so it cannot live on the editor's branch. A conflicting basis refuses with current and proposed, as today.

**Auto-retire is derived, not written.** `seon.task/resolution` gains a fourth clause: `:seon.task/closed-by
:seon.task/proofs` when every tagged proof's latest member on the home is green and the task was ever assigned
(`assign!` refuses an already-green task, so "ever assigned" rules out the never-red). The `:seon/tasks` tag removal
from definitions is part of the landing's write-back, not a separate sweep.

#### 8.1.4 Answers to §7

| Question | Recommendation |
|---|---|
| Check a separate agent? | No, in pipeline A: Prove's stage proof carries the mechanical half; Implement's first turn may `send-back` to Prove with data. Pipeline B adds the agent; the send-back rate decides. |
| Survey and Model separate? | One Design stage (references, flow, schemas, contracted stubs) in A; split in B. |
| May Implement send back itself? | Yes: `my.task/send-back!` writes the decision with target and reason; the stage proof then holds and `advance-end!` routes to the target. Root is involved only over the send-back cap. |
| Which done-checks need judgment? | None: every stage proof above is a data predicate. Judgment is in agents' decisions and is scored by downstream data (send-backs, declines, calls). |
| How much law excerpt in the stable prefix? | Only the laws the stage's template cites (Prove cites "Proof and performance" and the catalogue; Design cites "Data and contracts"), never the whole file; measure cache hits per stage in the competency table. |
| How are variations assigned? | Deterministically by task id parity (A on even digests, B on odd), per round; no randomness, fair across families once the set is larger than a few tasks. |

#### 8.1.5 Making the first experiment smaller

Pipeline A for one mined task, three DeepSeek agents: **Design → Prove → Implement**, Mine done by root's query, Land
automatic, Retrospective by root. Build only: (1) `my.task/refine!` at the home; (2) `:seon.task/stage`,
`:seon.task/decisions` and the stage type entity with three stage proofs (three deftests); (3) the `or` in
`seon.turn/task-proofs`; (4) `advance-end!` in `end-task!` under its dial; (5) the template rendered in
`current-state`. Not yet: the pipeline entity (A is the only one; a vector in the stage entities' `next` suffices),
measurement columns, the catalogue page (seed it with the three proofs the stages produce), §6b steps 3–4. Expected
cost of the whole experiment: one task's three stage proofs plus one `end-task!` branch; the transition itself adds no
model call.

**Not verified:** that `seon.competency/start-run!` is callable from inside `end-task!`'s proc thread without the
acquisition fences root's REPL path avoids (it runs today from root's JVM REPL); and the var-usage attribute by which a
proof's file reads are detected, which Prove's proof needs and I did not confirm by symbol.

### 8.2 Astra (2026-10-11)

**Keep the engineering sequence; revise the proof selection and handoff before building advancement.** A task at its
home carries intent and evidence references; schemas, stubs and tests live on the working program branch. Both must
reach the next agent. The smallest composition is stage-selected ordinary tests and the existing task/agent owners.
It replaces generic done-checks, stored instructions and a separate Check agent; it needs no stage supervisor.

#### Engineering coverage and agreement

| Topic | Verdict on Fable | Place and concrete correction |
|---|---|---|
| Mine | Agree: normally a query | Record the smallest reproducible input, expected and actual data, execution mode, program basis and relevant configuration. Reproduce through the affected entrance; distinguish a product defect from a broken fixture. Match existing subjects and evidence, not just an intent digest. New features need an acceptance example, not a fabricated incident. |
| Design = Survey + Model + interface | Agree | Trace producer → transforms → storage → consumers; verify localization by an owner probe. Reuse a library seam before designing. Specify arities, named input/output schemas, error behavior, compatibility, replacement/deletion and caller conversion. Stubs are for new meanings; do not replace a working existing body merely to manufacture red. |
| Prove | Agree on its place, strengthen its meaning | Exact reported inputs plus boundary/negative cases; a property only for a stated law; deterministic work counts for a cost claim. Include integration/lifecycle behavior when that is the defect. Existing preservation tests may already be green; require a relevant new red, not every test red. |
| Implement, including send-back | Agree | Read the tests first; change the producer, update all callers and docs, remove what the change replaces. Measure the same real workload before/after, with like evaluators; interpreted allocation versus compiled allocation is not a regression. These are implementation work and proof/review evidence, not extra stages. |
| Land automatic | Disagree with review only afterward | Root's merge decision owns review of the actual diff, including special cases, lost behavior and unfinished deletions. Keep the required independent reviews there; an existing review call can do this. Mechanical merge/write-back/commit needs no model call. `seon.task/decide!` only writes an outcome: declining an accepted change does **not** undo it. |
| Retrospective | Agree on rename; avoid a mandatory call | Derive green-on-main closure and summarize costs mechanically. Use root's existing review for specific friction and follow-ups; change a template only when evidence supports it. Rollback belongs to Land's integration plan: retain the accepted/base identities and use the existing merge/write-back path to revert, with dependent changes considered. |

Reproduction, reference resolution, contract compilation, recorded test comparison and transaction observation do not
need a model. Localization, interface tradeoffs, proof relevance and diff review do. A model's decision recorded as
data is inspectable judgment, not a mechanical proof that the judgment is correct. Immutable history aids rollback;
it does not make external effects reversible or replace pre-merge review.

#### Stage proofs: concrete assertions, without pretending judgment is decidable

The following are **proposed deftest bodies**, not installed APIs. Locals denote projections from a captured task
revision, candidate program and recorded observations; names below introduce no production attributes. Bind those
inputs explicitly in the ordinary test world. Each equality reports the offending data; missing observations remain
unknown. Run reproduction, schema sample evaluation and tests through their owners first, then inspect their records;
calling `seon.test/run` from a stage predicate would make it effectful and risk recursive test execution.

| Stage | Concrete assertions inside its deftest | What red means; limitation of Fable's proof |
|---|---|---|
| Mine | `(is (= expected-input observed-input))` `(is (= expected-main-basis observed-basis))` `(is (= reported-actual observed-actual))` `(is (not= expected reported-actual))` | Missing/mismatched reproduction data names what to rerun. Bind a nonempty case set first. A duplicate lookup chooses the existing task; it is not a second refusal mechanism. Feature work compares an acceptance example with the current missing behavior. |
| Design | `(is (= #{} unresolved-refs))` `(is (= required-interface compiled-interface))` `(doseq [[schema sample expected-valid?] samples] (is (= expected-valid? (malli.core/validate schema sample))))` `(is (= #{} unaccounted-consumers))` | Compile with `malli.core/schema` in the candidate registry, including changed schemas, and expose compile errors. Compare actual producer/consumer contracts with the proposed interface. Consumer evidence records conversion or preservation, including deletion and docs. Resolving a bag of references proves neither relevance nor that anybody read them; render the same references in the next agent's context and assess their usefulness there. |
| Prove | `(is (= required-cases observed-cases))` `(is (= expected-failures observed-baseline-failures))` `(is (seq observed-baseline-failures))` `(is (= #{} unexpected-errors))` | Case identity includes entrance, input, expected/actual data and proof digest; red must name the behavior, not merely `:fail`. Baseline is the captured pre-change program **with the candidate tests**, plus declared stubs for new symbols. A known stub error can be explicit; an unrelated compile error cannot count. Preserve already-green companion tests. `seon.task/refuted` supplies historical red reach pairs, not this oracle. |
| Implement | `(is (seq required-proofs))` `(is (= required-proofs current-green-proofs))` `(is (= #{} newly-broken-reaching-tests))` `(is (= #{} remaining-replacements))` | Current means the tested definitions and task inputs still match. Assert the task's work-count formula when applicable; keep before/after CPU/allocation/storage observations alongside it. Test evidence plus diff review establishes the deletion/caller work; a self-authored `:changed` list alone does not. A send-back is an evidenced handoff, never green implementation. |
| Land | `(is (= reviewed-program accepted-program written-program))` `(is (= accepted-git recorded-git))` `(is (= required-proofs current-main-green-proofs))` | These are observed program identities and positive commit/test evidence, not optional keys whose simultaneous absence passes. A refusal is an unsuccessful attempt routed to the responsible work; it must not satisfy successful Land. New integration failures need localization, not automatic classification as a bad proof. |
| Retrospective | `(is (= observed-friction (set (keys dispositions))))` `(is (= #{} follow-ups-without-red-proofs))` | Every observed friction has an explicit follow-up, template change or reasoned no-change decision. Empty friction is explicit `:none`. Derive retirement separately from current main proofs; no test should demand a written retirement flag that the design deliberately removes. |

Every row also reads the stage's explicit decision required by §3b. Use a schema for its required fields, rather than
duplicating shape assertions. A decision of `:unchanged` needs the observed interface/diff that supports it, not just
a nonempty reason. Report subject, expected and actual data through `seon.context/proofs`; current failure claims are
stored shown text, so exact machine comparisons may require a named observation value, not parsing that text.
Do not add a file-I/O blacklist: it misses indirect reads and mistakes fixture reads for the defect. The proof must
observe the branch program through its owner; demonstrate that changing that program changes its result.
Finite tests cannot exclude every special-cased implementation or irrelevant reference; no extra verdict tier fixes
that. Broaden examples by behavior class, use independent laws where applicable, and read the diff before merging.

**Catalogue seed** (adapt the research examples; their added runtime guards conflict with c722d8d22 and are not adopted):

| Kind | Example assertion | Why |
|---|---|---|
| Good: example + law | For `seon.sci.forgive/forgive`, assert token separation for `"a]a"`, `"1]2"`, empty and nested inputs; seeded bounded idempotence and repair round-trip | Finds more than the reported token; round-trip detects silently unrecorded edits. Fix token production, not a post-result guard. |
| Good: read cost + answer | For `seon.fn/gate-set`, assert equality with the independent full-walk model and `(is (<= reads (+ 80 (/ callers 2))))` on leaf/hub fixtures | Preserves semantics and charges only relevant callers; add unrelated transitive nodes to distinguish whole-program work. The numerical budget comes from the research measurement, not a universal policy. |
| Good: writer behavior + storage | Publish one declaration edit through the real owner; assert exactly that declaration changed, pull it back, and `(is (<= datoms (+ (* 40 changed-count) 10)))` | Exercise real publication and deletion/caller cases, at several file positions/sizes. The research's pure selector alone does not prove storage cost. |
| Bad: arbitrary red | `(is false)` or a failing equality with constants | Tagged, fast and red, but no behavior can turn it green. Failure type alone admits it. |
| Bad: single input as specification | Only the reported slug input, then accept a constant answer | A special case passes while nearby titles fail. Test empty, already-normalized and varied titles against the promised behavior. |
| Bad: proxy for success | Nonempty references, a `:pass` note, two assertion events, or a census using `slurp` of checkout source | Measures paperwork or the wrong program. None proves the branch's intended behavior; the loop rounds already demonstrated the file-census failure. |

#### Advancement: one owner, but not one union and three lines

Verified at HEAD b1fa25d4e; the named production sources equal default's f51240f34 archive. Core.async pin
dc35f3e0d7 and Malli pin dd13ff8113 were read locally. Read-only JVM probes on default returned `:io` for
`seon.turn/step`; root held no task, had no proofs, `task-done?` false and `task-end` nil (3 ms evaluation,
55.5 ms request, 1.2 MB evaluation allocation). Symbol inspection took 2 ms, 70.8 ms request, 0.3 MB;
`seon.competency` was absent. An initial `find-var` probe errored on that absent namespace (112.5 ms request,
0.2 MB); the corrected namespace-aware inspection succeeded. Health was `:unknown` with untested-hot warnings;
these are source/read observations, not an end-to-end launch proof.

| Verified seam | Consequence |
|---|---|
| `seon.turn/task-proofs` selects execution; `seon.context/task-done?` independently calls `seon.context/proofs`; `seon.task/assignment-read` independently calls `seon.task/task-edges` | A union changed in the turn does not change admission or completion. Even a union everywhere is wrong: Prove succeeds when implementation proofs are **red**. Select the current stage's obligations once in the task owner and share that selection with assignment, test execution, context and completion. Only Implement requires the task's behavior proofs green. |
| `seon.task/assign!` runs proofs before writing the assignment; test members have their own execution worlds; `seon.context/held-task` reads the live home and pulls only four declaration fields | A generic test cannot discover its candidate task/stage by asking which task its test agent holds. Supply the task revision/stage as test input before assignment; include that input in evidence validity. A new task revision invalidates only dependent observations, preventing old Design decisions from completing a send-back. |
| `seon.task/create!` selects six declaration fields; `seon.task/assignment-read` reads proofs at home | The proposed refine face is not merely an alias: new decisions must be admitted and retained by the owner. Branch-only proofs remain invisible at assignment: existing task `seon.task/assign-cannot-see-a-red-proof-the-gate-will-not-land`. |
| `seon.turn/step` records, runs tests, then calls `seon.turn/end-task!`; the latter proposes, lands, writes home outcome, sends a message and closes branch admission through separate writes | The last evaluation's recording transaction is not an atomic stage transition today. A launch performs effects and cannot run inside a transaction-data function. Do not describe home writes and branch writes as one transaction. |
| `seon.competency/start-run!` calls `seon.test-support/own-branch-agent!`, `seon.task/assign!`, then `seon.test-support/outside-wake-on-branch!` | This private test helper forks from main, submits and waits for a setup MCP evaluation, and explicitly sends a wake: assignment alone is not today's first wake. Forking main loses the previous stage's unmerged stubs/tests. Carry the predecessor's commit as the next branch basis, referenced by the home task. |

**Proc-thread answer:** `clojure.core.async.flow/process` and `clojure.core.async.flow.impl/proc` run an `:io`
transform on its proc thread and permit blocking; `seon.cluster.agent/graph-definition` gives each agent its own
mailbox/turn/schedule graph. Waiting for a *different* new agent's setup has no inherent same-proc dependency.
The helper's writes use explicit custody via `seon.test-support/transacted!`, `seon.task/written` and the wake helper;
there is no source basis for inventing an acquisition exemption. However, the helper is not loaded on default and
its exact nested launch has not been exercised here. Reuse the production acquisition/assignment owners with explicit
home and predecessor basis; make the bench call that composition. Prove it once through a real turn with injected
provider replies, including successor visibility and actual predecessor exit, before claiming it installed.

`clojure.core.async.flow.impl/create-flow` gives report/error channels sliding buffers of 100. They are observations,
not durable handoffs; Flow stop is not a join. Keep one transition at `seon.turn/end-task!`: commit the stage result,
next assignment's identity/input and predecessor program basis at the task home, then let the existing agent
lifecycle acquire and wake that assignment after the commit. Identity comes from that assignment, not a new random
launch on repeated observation. Boot derives outstanding assignments from those same facts; it does not resume a
partially executed launch. Make that existing lifecycle own acquisition and retirement, including actual exit.
This is a proposed owner change, not a currently atomic composition. Its cost is proportional to the task's changed
facts and next proof set, plus the existing branch acquisition; no whole-program scan or per-stage monitoring.

| Remove | Design that makes it unnecessary |
|---|---|
| Send-back cap and a root watcher for it | Return counterexample, affected obligation and target as the task's ordinary given-back outcome; route to its owning stage. Charge all successor calls to the same existing task budget, so fresh agents do not replenish it. Exhaustion remains the normal resumable task end, not a second send-back state machine. |
| Advance dial | An assigned chain has its next work; execute it. Experiments choose a finite stage sequence as task data. Use existing task launch/budget controls for execution, not another boolean that can leave completed work stranded. |
| “Ever assigned” auto-retirement condition | `seon.task/resolution` derives closure from a nonempty set of current green behavior proofs on main, including tasks satisfied by another change. Old green or absent evidence is unknown. Do not remove the proof links while closure still derives from them. Existing owner: `seon.task/open-tasks-whose-proofs-are-green-are-never-retired`. |
| Repeated stage/proc state | One current stage in the task, one predecessor program basis, and ordinary assignment facts; no parallel running/done flags, polling, report-channel consumer or refusal tier. Send-back changes those inputs; it never masquerades as successful Implement. |

#### Smallest experiment and top changes

Use one small existing defect with settled policy and an existing symbol, like the successful slug/search runs.
**Two agents: Design+Prove → Implement.** Root mines it and performs the existing diff review/landing and retrospective.
First rehearse one explicit handoff through existing owners with injected replies: the successor sees the predecessor's
test and stub, the Design+Prove obligation is green while the behavior proof is red, then Implement makes it green.
Root can launch this one successor explicitly; that tests task quality without first implementing automatic chaining.
Then make that same handoff automatic and run the two-model experiment. Do not yet add pipeline entities, send-back
automation, automatic retirement, template-learning or measurement columns. Keep ordinary cost evidence; compare
paired tasks at the same starting program when comparing pipelines—task-id parity does not balance a small sample.

Top three changes: **(1)** one stage-aware proof selection with explicit task inputs, **(2)** predecessor-program
handoff through the task/agent owners, **(3)** pre-merge diff review and behavior-based proofs, deleting the three fences
above. This lane changes only this review; no new runtime task or code was written. The branch-proof admission and
retirement gaps above already have task identities; new chain-specific gaps are requirements for this unbuilt card.
