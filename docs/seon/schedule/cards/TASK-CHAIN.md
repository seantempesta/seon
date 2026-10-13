---
title: "TASK-CHAIN: ordinary tasks, one proof selector, accepted merges"
type: spec
status: LOCKED target; implementation starts with red acceptance deftests
created: 2026-10-11
---

# TASK-CHAIN

Tasks, branches, deftests and the listened `seon.cluster.agent/db<->flow` already exist. Compose their owners;
delete duplicate decisions. **Target, not installed behavior; §§1–7 supersede the dated reviews below.**

**Owner rulings, 2026-10-13 (binding):**

> “What do we need to control and change and expand upon as the chain progresses... only what exists is useful
> and is easy for an agent to write up at each stage.” “Start from need, not from names.”
>
> “ONE prose parser for ALL prose everywhere” — “DO NOT RECREATE”.
>
> “Stages don't own sections; it's all data agents update.” “Proofs exist to advance the chain automatically
> and adapt to each task (root/agents add, change or drop deftests per task).”
>
> “Templates: everything predetermined for progressing through a stage lives on the template off the shelf”;
> “Templates are ordinary tasks in resources/seon/tasks/seon.step.edn.”
>
> “Markdown edits: Clojure functions that read/replace/append one section by span” — “only if a stage actually
> needs section edits; otherwise refine! of whole prose is enough.”
>
> “one proof selector (seon.task/obligations), one success authority (merge's accepted candidate, incl.
> my.root/merge!), one write builds a chain, schema-derived member lists, cost regressions are failing tests,
> deftests only as gates, NO GUARDS OR FENCES, shared small pure functions”.

## 1. Need → fields

**Intake (root) → Survey → Define → Design → Prove → Implement.** Intake/Survey is a conversation with the user. Root reviews and publishes accepted work.
Successors fork main after their needs merge.

| Need | Data and consumer |
|---|---|
| Grow the goal, observations, examples, approach and results | Subject `:seon.task/intent`, one Markdown document; `seon.task/effective` supplies it to every step. |
| Establish user approval | Proposed task answer-schema/answer data, consumed by `my.agent/answer!` and Survey’s deftest; an answer identifies its inbound message and the subject intent it approves. Existing `:seon.message/caused-by` is the reply-to ref consumed by inbound routing; no new message attribute. |
| Tell this step what to do | Copied `:seon.task/instructions`; agents fill gaps and revise through `my.task/refine!`. |
| Order, start, pause, stop | Existing `:seon.task/children`, `:seon.task/needs`, `:seon.task/execution`; readiness and Flow consume them. |
| Judge work | Deftests tagged `:seon/tasks`; `seon.task/obligations` selects them. Existing `:seon.task/product` names the behavior tests Prove supplies and Implement makes green. |
| Freeze selected defaults without copying test bodies | Existing value schema `:seon.task/proofs`, newly admitted as an optional declaration/entry/task member, consumed only through obligations. Its description is “the copied part of `seon.task/obligations`”; `seon.render.ns/task-ai` shows obligations, not this raw member. |
| Run a default against a clone | New `:seon.test/task` request input, consumed by the runner binding and member evidence identity; not a stored lifecycle attribute. |
| Index docstring citations | Existing `:seon.fn/references`: union source-observed names with docstring names from `seon.ref/names`; context, referrers and conservative test reach consume this one definition edge. |

No template ref or new step enum values. The answer schema/value are the sole newly needed task fields: prose and execution cannot carry a validated, message-linked answer; slice I2 names and admits them at their producer. Survey/Define/Design omit step; Prove/Implement declare `:prove`/`:implement`. Derive member lists from schemas; normalize declared sets only. `seon.task/effective` merges content down children, never needs; product is inherited,
copied proofs are local. `my.task/refine!` routes intent/product to `seon.task/goal-owner`, instructions to the held
step, preserving other members; first make `seon.task/create!` take its members from `seon.task/declaration` (its hand list drops product today), admit product in `:my.task/refine-request` and carry it in `:seon.task/effective`. No gating-proof route. Whole-prose replacement suffices: **no section-edit API** or stage-owned headings.

## 2. Rules and owners

- **Construct once.** New `seon.task/chain!` submits subject, five copies and needs together through
  `seon.task/adopt-tasks-tx`. Clone namespace/file are the subject's; name = template name + `-` + subject name;
  `seon.task/task-handle`/`seon.task/task-sym` derive identities. Copy instructions, step and selected defaults,
  never worked-example intent. Only the subject starts held; running readies Survey. Identical reconstruction
  has no delta; template edits affect future copies.
- **Select once.** `seon.task/obligations` defines absence once as copied proofs ∪ own tagged tests, never `:implement`; explicit Implement adds subject-tagged tests and effective product. Census existing chains and mark their Implement leaves explicitly. Prove runs product before green handoff proofs. Delete successor-tag inference.
  Admission, first-proof detection, staleness, runs, context, task edges, merge and error resolution use this value.
  `seon.task/assign!` and `seon.cluster.agent/write-attempt!` share `seon.task/assignment-read` and attempt writes.
- **Adapt without self-certification.** Root customizes gates before assignment; agents add/change/drop tests for
  later work. “Assigned target basis” = obligations(main head) ∪ (obligations(candidate) − obligations(merge base)); use the three held database values. Root revisions apply, branch removals do not; the base’s inherited gates are not candidate additions.
  Candidate refinements/new proofs still enter acceptance. Contradictions go back with a counterexample.
- **Accept once.** `seon.cluster.source/accept-merge!` judges the combined candidate for automatic and
  `my.root/merge!` paths, including task-only/no-op/pre-end. `seon.merge/merge-tx` consumes acceptance; only
  `:merged` completes a leaf. Delete `:satisfied`. Carry tested task/program/read basis, including the selected obligations and their inputs, into `seon.merge/merge-tx`’s writer comparison; declaration digests alone do not cover a moved task/tag basis. This is required. Converge `seon.cluster.source/prepare-merge!`’s automatic gate and `seon.cluster.source/accept-merge!` into the same `merge-verdict` path.
  Prove's deftest alone judges designed red; merge admits product reds by membership. Required green proofs,
  reaching regressions and static checks retain their owners; missing/stale execution is not success.
  `seon.context/step-status` is a read-only preview of the same `seon.cluster.source/merge-verdict` over the held branch candidate; `seon.turn/task-end` proposes `:done` when it returns no errors, never successful completion.
- **React once.** `seon.cluster.agent/plan` derives start/pause/stop from task facts. `seon.turn/test-step` and
  `seon.turn/prove-turn` acquire evidence before model work; context reads it. `seon.cluster.agent/assignment-actions`
  derives end actions from remaining work, allowance and actual exit. `seon.task/ready` excludes ended attempts
  without relaunch, deleting its terminal-end literal. `seon.merge/publish!` remains separate: refusal stays visible
  and retains the branch. Root reviews costs after `seon.task/done?`; no retrospective trigger or sixth stage.

## 3. Shared functions, one prose path

| Owner | Shared transformation / deletion |
|---|---|
| `seon.task/declaration`, `seon.task/effective` | Members and held lineage shared with goal-owner/context; use `malli.core/children`, `malli.core/deref-all`, `malli.core/type`. |
| `seon.task/obligations` | Remove caller tag queries and Prove selection. `seon.error/proof` uses it and `seon.test/green?`; retain current/completed evidence checks. |
| New `seon.cluster.source/merge-verdict` | Candidate/obligations/results/static findings → errors, shared by preview and merge; no exit validator. |
| `seon.cluster.agent/assignment-actions` | Assignment/observations → actions; delete policy from close!, failed-pass!, refused-merge-relaunch and ready; share held values. |
| `seon.ref/document`, `seon.ref/candidate`, `seon.ref/names`, `seon.ref/references` | One CommonMark parse/candidate rule for task prose, every function/test/namespace docstring, schema descriptions, documents and help. |

Index function/test/namespace docstrings with `seon.ref/names`; union their symbol citations into the existing `:seon.fn/references` (2,949 datoms in the narrow check), preserving source-observed names. **Decision: citations widen conservative test reach through the existing definition edge; no new attribute.** Context, `my.program` and test reach consume the same relation. Keywords/paths resolve at read, never enter this symbol set. Phase 2 measures selected test counts and end-to-end edit/retest time before/after on the same corpus; a materially bad measured widening returns to this decision, not a parallel edge added speculatively.
Delete authored must-read/see-also ingestion, its `seon.fn` metadata writes, both `seon.task/task-edges` must-read referrer rows, and the attributes on fn/test/ns/task schemas (zero stored edges; no migration).
Move `seon.sci.lookup/schema-documentation` and private `documentation-unavailable` to the ref side; convert `src/my/program.clj` too. **In that same commit** delete the ref→lookup require and every `docstring-parts` caller with the splitter, so fn/lookup/instrument/render.ns can depend on ref without a cycle.
Commit the sweep script’s own file/heading inventory: the check found 82 indexed docstrings but 92 colon-heading lines in 25 source/test/script files, not a fixed 87. Convert headings to `## `; wrap Example/Examples code in fenced code blocks under those headings. Summary = first sentence of the first paragraph’s source span; example extraction reads the fenced code’s source span, never rendered text. Keep `seon.ref/candidate` internal to names/references.
An unqualified backticked span is prose unless it names an existing stored/loaded/library namespace; `deps.edn` and Java classes gain no unresolved problems. Explicit qualified names/keywords and based paths retain unresolved diagnostics.
`seon.bootstrap/help-value` and every prose site use this parser; stage guidance comes only from instructions, deleting prove/implement help groups and context's Prove-only guidance.

**OWNER DECISION — “find and resolve all refs and symbols”.** A **reference** is any span of prose naming exactly one **entity**, unique by its identity attribute (Datahike’s word). References may occur anywhere in task text, any docstring, schema descriptions and docs. Backticks remain explicit; bare qualified symbols/keywords and `path#anchor` resolve only when they name an existing entity, otherwise they are ordinary prose, never problems. Explicit unresolved citations retain diagnostics. “Docstring refs can be anywhere in the docstring.” The ONE `seon.ref/candidate` rule handles both forms with three clauses: an entity’s own prose never cites itself (compare subject identity); a directory is not an entity (only files/anchors, never gitignored `tmp/` paths); Example/Examples code in docstrings is code, excluded like other fenced blocks—the heading sweep fences that code, a heading alone does not exclude prose. Opaque handles remain excluded. Identity lookup distinguishes kinds, never spelling heuristics.

The [narrow check](../../../research/agent-platform/2026-10-13/narrow-spec-3.md) scanned 4,470 function docstrings; these yielded 293 bare hits in about 114 docs. These clauses predict **132 remaining hits** (293 − 73 self − 85 example-code − 3 directory): 111 schema keys and 21 symbol hits (19 functions, one task, one library Var), before deduplication against the existing edge. The remaining document count was not measured; 114 is the pre-exclusion count. Recount namespace docs separately (16 raw hits in nine docs); do not call these hit counts measured reach growth.

| Reference kind | Explicit syntax (bare forms follow the rule above) | Identity lookup | Declared AI display function |
|---|---|---|---|
| Function | Backticked qualified symbol | `:seon.fn/sym` | `seon.render.ns/function-ai` |
| Deftest | Backticked qualified symbol | `:seon.test/sym` | `seon.render.test/render-ai` |
| Task | Backticked qualified symbol | Unique `:seon.task/sym` → `:seon.task/id` | `seon.render.ns/task-ai` |
| Library Var | Backticked qualified symbol | `:seon.fn/sym`, resolved from the pinned dependency when not indexed | `seon.render.ns/function-ai` over the same declared function value |
| Schema key | Backticked qualified keyword | `:seon.schema/key` | `seon.render.schema/render-ai` |
| Attribute | Backticked qualified keyword | `:seon.schema/key` of its declaration | `seon.render.schema/render-ai` |
| Error type | Backticked qualified keyword | `:seon.schema/key` of the error variant, not an error occurrence | `seon.render.schema/render-ai` |
| Configuration dial | Backticked qualified keyword | `:seon.schema/key` of the dial declaration, not its current value | `seon.render.schema/render-ai` |
| Namespace | Backticked bare symbol, only when the namespace exists | `:seon.ns/name`, including loaded/library namespaces | `seon.render.ns/render-ai` |
| Document / section | Backticked relative `path#anchor` (anchor optional), or that target in a Markdown link | `:seon.ref/path` + optional `:seon.ref/anchor`; value identity, no DB identity attribute | New `seon.ref/render-doc-ai`, declared on existing `:seon.ref/resolved-doc` and `:seon.ref/section` |
| Reference-code document / section | Same path/link syntax, rooted at `reference-code/<project>/` | Path + optional anchor + `:seon.ref/pin` (project/revision); no DB identity attribute | Same `seon.ref/render-doc-ai` |

`seon.ref/resolve-name` only identifies the entity; delete its per-kind display branches. Display follows the entity’s declared render through the existing consumer dispatch (CLAUDE.md “Choose the render function per consumer”), then the value printer. Schema keys already have a declared render; docs/sections lack one, so move their existing display into the declared function above, with an HTML counterpart `seon.ref/render-doc-html`. No second display selector or entity registry.

Cost follows changed prose, members, lineage, selected tests/files; reuse content-valid acquisition. Commonality
A7/A10/A16's bases, latest-attempt/count and branch/three-way diffs are not equivalent; do not collapse them.

## 4. Off-the-shelf templates

Ordinary tasks in `resources/seon/tasks/seon.step.edn`, named survey/define/design/prove/implement, contain structured
Markdown instructions (work, examples, completion) and a worked-example intent. Their default deftests in
`test/seon/step_test.clj` are each tagged to one corresponding template. A run without explicit `:seon.test/task`
uses that single tag's task; a clone supplies its own task. The runner binds the selected task, never its test
agent's assignment; task identity plus effective task content/read evidence belongs in member/reuse identity, including inputless-template versus clone runs. Convert `seon.test/green?`, latest-results/red-at-current-reach?, `seon.error/proof`, context/head-entries and accept-merge!’s members together. Measure the stored-encoding change with `seon.profile/measure` on two clones sharing a default: initial runs, repeated unchanged runs, then one clone’s refinement; report end-to-end time/allocation, rerun/reuse counts and stored bytes against the parent. Require a measured win for the encoding change and prove no green crosses tasks; no performance win is claimed here. Each handoff is a pure helper over effective document, obligations and result values: inputless tests pass worked-example values, clone runs read current stored branch results; no fabricated stored entities or recursive test runs.

| Template | Instructions → default deftest (proposed) |
|---|---|
| Survey | Converse with the user; search owners, vocabulary, libraries and history; rewrite subject intent with references → `seon.step-test/survey-handoff`: Problem and What exists have content, references resolve, and the latest user answer approves this intent. |
| Define | Specify outcomes, boundary examples and cost workload → `seon.step-test/define-handoff`: previous content plus Examples and Expected cost: a backticked citation to a deftest using `seon.profile/measure` is a cost claim; a present section without one is no claim. |
| Design | Specify approach, interfaces/contracts and deletions; reuse existing interfaces → `seon.step-test/design-handoff`: cumulative content plus Approach; every function cited there resolves and its contract compiles against the candidate registry. New stub interface tests assert the instrumented input contract, not the temporary body. |
| Prove | Add exact/boundary cases, declare subject product, write cost assertions where claimed → `seon.step-test/prove-handoff`: nonempty product with current executed designed-red reports, using `seon.test.runner/designed-red?`. This is the sole designed-red judgment. |
| Implement | Check intent/proofs; change the producer and callers; turn behavior/cost tests green or send back → `seon.step-test/implement-handoff`: cumulative document/reference checks; selected behavior tests prove the result. |

Defaults share a data-checking helper but keep single-tag entries. Match required sections by `seon.ref/document` heading anchor at any level; prove content presence, not quality. Root adapts defaults per task.
A cost claim requires a `seon.profile/measure` deftest against comparable `seon.db/stored-costs` at the candidate's
merge base, using `seon.batch/cost-diff`: regression fails, missing/incomparable evidence requires measurement.

### Intake conversation and user surfaces

Survey uses `my.program/search`, `docs/seon/architecture/vocabulary.md` and `seon.ref/references` to turn the request into referenced subject intent via `my.task/refine!`. It may ask with `my.message/send`, then `my.turn/wait`; the user is an agent—an address messages route to—and the reply is an inbound message fact that root’s `seon.cluster.agent/db<->flow` delivers through the existing agent-to-agent inbox/receive path to Survey’s own branch. A fact/wake on main alone is insufficient. Persist outbound `my.message/send` questions to the user’s address even without a model graph, so the chat can display the question’s message id; record `:seon.message/from` and reuse `:seon.message/caused-by` for the reply. No cross-branch effect belongs to the agent. `my.agent/answer!` validates and stores the reply’s answer as data. Survey’s gating deftest reads the latest answer from that user for the current subject intent: missing, negative, superseded or for an earlier intent stays red. Approval makes that proof green; only ordinary accepted merge advances the chain. Acknowledgement is not a separate exit.

Search gaps from `docs/research/agent-platform/2026-10-13/link-statement-experiment.md` become slice I0: vocabulary bridging, ranking by recorded use, schema-key results returning key + producer, owners above tests, and token cost per candidate before selection. No second search index or prose parser.

The user interface is specified separately in [USER-SURFACES](USER-SURFACES.md): an explicit chat session connects the ordinary user agent and the viewed agent, with a layout render, ordered cached slots and an optional canvas pin. That SPEC-ONLY card owns UI-1–UI-3, render-cache migration, sender attribution, message presentation and controls; none is scheduled in this agent-chain round. I1–I3 below retain the user-attributed REPL reply, root delivery and answer/approval contracts needed for intake; their correctness does not depend on completing the page layout. The session supersedes the proposed agent-level viewing/pin attributes.

## Interfaces

**Target contracts for the acceptance tests; register new names in their owning schema files before bodies.** Existing error unions remain at their entrances; examples use values acquired by the standard fixtures, not fabricated entities. New request/result schemas below are values, not lifecycle attributes. `main`, `candidate`, `base`, `connection`, `held`, `prepared`, `service` and message ids in examples are those held fixture values.

- **A1 — obligations.** Add arity `[:=> [:cat :seon.db/database-value :seon.db/database-value :seon.db/database-value :seon.task/sym] [:or :seon.task/obligations :seon.db/invalid-read-error :seon.db/pull-budget-error]]`, arguments `[main candidate base task]`. Preserve the two-argument arity as `(obligations db db db task)`. Make `:seon.task/step` optional in the existing result; apply §2’s set formula separately to `:seon.task/proofs` and `:seon.task/product`, with the candidate’s effective step. Example: `(seon.task/obligations main candidate base 'work/fix)` → proofs `#{'proof/g 'proof/h}` when main requires G and only H is new since base. Runner, context, render and `seon.error/proof` receive the assignment’s held main/candidate/base and call this arity (or consume that exact result); an unassigned template uses the two-argument arity. Merge passes target head, combined candidate and assignment merge base. No consumer re-queries tags.

- **A3 — chain construction.** `seon.task/chain!`: `[:=> [:cat :seon.db/connection :seon.task/declaration :seon.task/templates :seon.task/customizations] [:or :seon.db/transaction-report :seon.error/value]]`. New `:seon.task/templates = [:vector {:min 1} :seon.task/sym]` (ordered, distinct); `:seon.task/customizations = [:map-of :seon.task/sym :seon.task/customization]`, where new `:seon.task/customization = [:map [:seon.task/instructions {:optional true} :seon.task/instructions] [:seon.task/proofs {:optional true} :seon.task/proofs] [:seon.task/product {:optional true} :seon.task/product]]`, applied to that template’s copy before assignment. The owner derives handles, subject children and sequential needs; submits one population via `adopt-tasks-tx` in ONE writer transaction; returns its report (`:tx-data`, `:db-after`), not a second write or launcher. Example: `(seon.task/chain! connection {:seon.task/ns 'work :seon.task/name "fix" :seon.task/intent "Fix the observed failure."} '[seon.step/survey seon.step/define seon.step/design seon.step/prove seon.step/implement] {})`. Return data is the one population’s transaction; use task-handle/task-sym to find its subject and copies. Only the subject has execution `:held` initially.

- **A2 — verdict and writer basis.** `seon.cluster.source/merge-verdict`: `[:=> [:cat :seon.db/database-value :seon.db/database-value :seon.db/database-value :seon.source/prepared] :seon.source/refusals]`, arguments `[main candidate base prepared]`; new `:seon.source/refusals = [:vector :seon.error/value]`, empty only for acceptance. Extend prepared with required new `:seon.source/tested-task-basis`: `[:map [:seon.task/sym :seon.task/sym] [:seon.task/obligations :seon.task/obligations] [:seon.task/basis :seon.task/basis]]`; basis contains the selected task/ancestor declarations read for the run, obligations the selected sets. Existing prepared run id, expected declaration digests and the candidate’s stored run/read evidence supply other inputs. Example: `(seon.cluster.source/merge-verdict main candidate base prepared)` → `[]` for current green evidence. Test A2: prepare and judge; transact an ordinary task refinement or test-tag edit on the target through its owner; call `(seon.cluster.source/accept-merge! source store prepared)` with the unchanged prepared value. The writer re-reads its head, recomputes the same obligations/task basis and refuses changed proof inputs as `:seon.source/test-evidence-error`, without installing any candidate datoms. This is sequential data, no redefs, callback or race hook; unrelated writes remain valid. Replace `seon.merge/merge-tx`’s four/five-argument arities with the six-argument arity `[database assignment commit projection fork tested-task-basis]` returning existing `:seon.store/transaction-data`; convert all callers and their tests together to pass the tested basis; the writer never acquires or runs new proofs.

- **A6 — assignment actions.** Public `seon.cluster.agent/assignment-actions`: `[:=> [:cat :seon.flow.plan/held-assignment] :seon.flow.plan/actions]`. Extend held-assignment with required new `:seon.flow.plan/allowance-left [:int {:min 0}]`, `:seon.flow.plan/exited? :boolean`, `:seon.flow.plan/work-left? :boolean`, `:seon.flow.plan/refused? :boolean`; optional existing `:seon.task.attempt/target` and `:seon.task.attempt/reason`. These are acquired observations, not stored state flags: allowance is remaining configured relaunches; exited means actual graph exit, not cancellation. Retain existing ops `:stop :merge-base :merge :publish :close :delete-branch :problem`; extend `:seon.flow.plan/op` by **`:relaunch :reopen`**, whose actions carry the existing assignment/task/branch/reason and target respectively. Example: `(seon.cluster.agent/assignment-actions (assoc held :seon.flow.plan/allowance-left 0 :seon.flow.plan/exited? true :seon.flow.plan/work-left? true :seon.flow.plan/refused? true))` → close/problem actions, no relaunch/delete. An ended graph not exited yields stop only; after exit, cap/stall/refused-done with work and allowance yields relaunch; sent-back yields reopen for its target, never sender relaunch; accepted/published work can retire. Extend action/class schemas with target/reason and `:unfinished-task` for the problem; existing root effects execute the ops.

- **I0 — search.** `my.program/search`: `[:=> [:cat :seon.db/database-value :my.program/search-query] :my.program/search-result]`. New `:my.program/search-query = [:or :string :keyword [:map [:text [:or :string :keyword]] [:limit {:optional true} [:int {:min 1}]]]]`; new `:my.program/search-result = [:map [:count [:int {:min 0}]] [:matches [:vector :my.program/search-match]] [:more {:optional true} :seon.render/read-form]]`. New `:my.program/search-match` is an open `:map` preserving optional `:sym :qualified-symbol`, `:line :int`, `:snippet :string`; it adds required `:my.program/candidate-tokens [:int {:min 0}]` (AI-rendered candidate through the value printer, same profile before expansion), optional `:seon.schema/key :seon.schema/key`, and `:my.program/producer :seon.program/declaration-identity`, required on schema-key hits. Example: `(my.program/search main :seon.message/id)` → matches including that schema key, its declaring producer identity and candidate-tokens, not merely its users. Vocabulary hits use their referenced owner identity; ranking uses recorded use then stable identity for ties, owners before tests.

- **I1 — addressed reply.** User and recipient are ordinary `:seon.agent/id` values, e.g. `"sean"` and `"survey"`. `POST /agent/survey/message` adapts the reply into an eval request for `"sean"` on their checked-out branch; it does not call the direct `inbound` message writer. Extend existing `:my.message/send-request`/`:my.message/message` with optional request-only `:my.message/caused-by :seon.message/id`, resolved to the existing stored ref. Example: `(my.message/send {:my.message/to "survey" :my.message/content "Approve" :my.message/caused-by question-id})`. The existing REPL records Sean’s evaluation; root delivery records sender, question ref and the message on Survey’s branch. Assert the user-attributed evaluation and recipient inbox/wake, not merely HTTP success. Sending to Sean uses the same durable agent-message path without requiring a model graph.

- **I2/I3 — answer.** New task fields: `:seon.task/answer-schema :seon.schema/key` and `:seon.task/answer` (a component ref to new `:seon.task/answer-value`). Define answer-value as `[:map [:seon.answer/message :seon.db/ref] [:seon.task/intent :seon.task/intent] [:seon.answer/value :seon.schema/value]]`; dynamically validate value by the task’s answer-schema. Survey uses new `:seon.task/approval = [:map [:seon.task/approved? :boolean]]`. Add new `:my.agent/answer-request = [:map [:seon.db/connection :seon.db/connection] [:seon.task/sym :seon.task/sym] [:my.message/id :my.message/id] [:seon.answer/value :seon.schema/value]]`; `my.agent/answer!` has `[:=> [:cat :my.agent/answer-request] [:or :seon.task/answer-value :seon.error/value]]`. Example: `(my.agent/answer! {:seon.db/connection connection :seon.task/sym 'work/fix :my.message/id reply-id :seon.answer/value {:seon.task/approved? true}})`. The owner derives sender/question from I1’s delivered reply, and intent from the outbound question’s snapshot (reuse `:seon.task/intent` on that message, captured when Survey asks); callers cannot supply this basis. Store the latest reply’s answer by message transaction order, not answer-write order; do not interpret “Approve” inside a generic writer. The agent supplies the structured answer; Survey’s task-specific deftest reads it and its source reply, requiring the expected user, question, current intent and latest approving answer. Wrong-schema input returns existing `:seon.schema/validation-refusal` and writes nothing. No approval boolean is a lifecycle exit.

- **A7 — full-loop fixture.** New fixture value `:seon.task/chain-input = [:tuple :seon.task/declaration :seon.task/templates :seon.task/customizations]` holds the A3 subject declaration, ordered five-template vector and `{}` customizations above; pair it with the existing scripted `:seon.turn.loop/complete` binding; no new runner schema/API. Call chain! once, then one ordinary subject execution write to `:running`; the script supplies Survey’s user reply and each stage’s work. Assert five accepted merges, done subject, publication and retired branches with no test-body writes after running except the scripted replies. Expect over 10 s for real SCI worlds, five merges and publication; existing two-world tests have a 120 s declared bound. A7 has no measured duration yet: slice 0 records its `seon.profile/measure` time/allocation and owner-authorized bound before using it as a gate, with no fabricated cost pass.

## 5. Acceptance deftests — red first

**Specified, not implemented/run here.** Slice 0 reproduces reds; each tags exactly one phase. Extend existing
tests where they cover the class.

| ID / proposed deftest | Concrete counterexample → assertion |
|---|---|
| A1 `seon.task-test/one-selector-serves-every-proof-consumer` | Template-only first attempt through direct/reconciler admission; two clones with different content; inputless default → run/display/merge/error resolution select the same obligations, evidence never crosses tasks. Main has gate G: candidate removes G/adds H → select G and H; root removes G on main → select H. Census analysed `:seon.task/tasks`: only obligations selects proofs; allow task writers, `seon.fn/task-links`, `seon.task/candidate-dangling`, `seon.task/open-tasks` and `seon.task/task-findings` for writing/dangling/triage; allow `seon.task/task-edges` for tagged function/namespace SUBJECTS and `seon.cluster.agent/render-identity-html` for displaying namespace task tags, never proof selection; convert error/proof too. |
| A2 `seon.cluster.merge-test/only-an-accepted-candidate-completes-a-task` | Automatic/manual × program/task-only/no-op × ended/pre-end; red/stale gate or a task/tag input moved between verdict and writer comparison → no merged outcome; accepted candidate → refinement and outcome together. Prove product red passes only with its handoff green; reaching regression fails. |
| A3 `seon.task-test/one-write-clones-a-chain-and-one-running-readies-survey` | Count construction transaction; pull all five children/needs; run subject → only Survey ready. Repeated identical construction changes nothing; template instruction/tag edits affect next copy only; root-customized selection survives write-back. |
| A4 `my.task-test/refine-preserves-every-declared-member` | Create/refine/pull/write-back/re-ingest accepts product through `my.task/refine!` and preserves product/copied proofs; effective task includes product; derive tested members from schema. |
| A5 `seon.ref-test/all-prose-uses-one-reference-rule` | Same citation in each prose site above, including namespace name → same resolved identity and stored docstring edges; explicit qualified unresolved citations remain problems; `my.agent` resolves, `deps.edn`/Java classes remain prose; an anchor-only link without a document is a problem. Existing bare qualified symbols/keywords and path#anchor resolve identically to explicit forms; unknown bare tokens are prose without problems; code blocks excluded. Self-citations, directories and fenced example code produce no reference edges; ordinary prose under headings still resolves. Zero colon-heading docstrings or metadata/splitter duplicates. |
| A5b `seon.ref-test/each-reference-kind-uses-its-declared-render` | One table test, one row per kind above: prose snippet → resolved identity → render actually selected by the existing dispatch. Exercise citations mid-docstring as well as other prose sites; existing bare forms yield the same identity/render; unknown bare forms yield no reference or problem. Library values use the function schema; document/section rows cover both explicit path forms and the new declared render. |
| I0 `my.program-test/intake-search-finds-owners-with-candidate-costs` | The experiment’s three requests return vocabulary-linked owners; recorded-use ranking is deterministic, a schema-key hit includes its key and producer, owners precede tests, each candidate reports token cost before expansion. |
| I1 `seon.render.web-test/a-user-reply-post-wakes-the-addressed-agent` | `my.message/send` to the user persists a displayable question; live POST through its rendered reply form records `:seon.message/from` and `:seon.message/caused-by` → root delivery → `my.message/inbox` and unanswered wake on the recipient’s held branch, not just main. No manual copy/direct fixture transaction substitutes for POST; another agent is not woken. |
| I2 `my.agent-test/an-answer-is-validated-and-keeps-its-message` | Depends on I1’s recorded sender. Wrong-schema answer returns the declared error without storing it; valid answer retains user-message identity and subject intent basis; a later reply supersedes it. |
| I3 `seon.step-test/survey-waits-for-the-latest-user-approval` | No answer, rejection, older approval followed by rejection, another user/question, or changed intent → red; latest matching user approval → green Survey proof, then accepted merge readies Define. Send/wait alone never completes Survey. |
| A6 `seon.cluster.db-flow-test/ends-share-one-action-owner` | Sent-back sender, refused done, cap/stall and spent allowance → one action derivation; sender does not restart without relaunch, target reopens, exhausted work stays visible, retirement waits for exit. Separate `seon.cluster.db-flow-test/every-agent-start-has-a-task` covers root/scheduled creation. |
| A7 `seon.cluster.db-flow-test/a-scripted-full-chain-completes-without-hand-acts` | One launch, scripted replies through `:seon.turn.loop/complete`, real SCI/listens/tests/five merges/disposable publication → cumulative intent, red then green product, done subject, Git evidence and retired branches; after the subject’s running fact the test body writes only scripted completion replies; zero paid calls. |

Keep moved-main relaunch, send-back and publication-refusal scenarios as separate full-loop deftests, not A7 rows.
A7 is expected to exceed ten seconds: existing two-world chain tests declare `:seon.test/long-ms 120000`; five real merges, SCI worlds and disposable publication make this integration work rather than an ordinary read. That 120 s is an observed declared bound, not a measured A7 duration. Slice 0 measures A7 end to end (including `seon.cluster.source/prepare-merge!`), records time/allocation and the long-test issue, and obtains owner authorization for its measured bound before running it as a phase gate.

## 6. Phases

**All pending; serial (shared owners).** Update status each commit; convert deletions and last callers together.
**Phase status (slice 0, partial, 2026-10-13):** committed red by design, each tagged to its `seon.task.edn` phase task. In 8a9fb77c5 (run 12796102f51d): A5 `seon.ref-test/all-prose-uses-one-reference-rule` (bare forms and `my.agent` resolve nothing, an anchor-only link gives no problem, docstring citations are missing from `:seon.fn/references`, 67 colon-heading docstrings) and A5b `seon.ref-test/each-reference-kind-uses-its-declared-render` (all 13 kinds select `seon.render.value/render-ai`). In 6a7ba4e3d (run 07cae68c7cba, 4 executed, 4,674 ms): A1 `seon.task-test/one-selector-serves-every-proof-consumer` (`seon.task/obligations` has no three-basis arity, so its arity is refused; the census has one reader left, `seon.error/proof`), A3 `seon.task-test/one-write-clones-a-chain-and-one-running-readies-survey` (`seon.task/chain!` does not exist), A6 `seon.cluster.db-flow-test/ends-share-one-action-owner` (there are no `:relaunch`/`:reopen` ops and no `:unfinished-task` problem; an unexited graph closes and a retired branch is deleted before exit) and I0 `my.program-test/intake-search-finds-owners-with-candidate-costs` (no schema-key or producer hit, no candidate tokens, tests before owners, vocabulary owners not first). In 5a97a217f: `seon.cluster.db-flow-test/every-agent-start-has-a-task` (run 95a8f4b69368, 4,208 ms; root ruling: a graph starts only from an assignment). On HEAD root, as the owner of the maintenance schedules, is started with no assignment, and chain-world's teardown then errors on that started agent. Also I1 `seon.render.web-test/a-user-reply-post-wakes-the-addressed-agent` (run dde7e4cf3491, 4,120 ms): `my.message/send` to the graphless user answers `:seon.message/undeliverable` and persists no question, the reply POST answers 422, and nothing reaches survey's branch. A4 is phase 1's own test (1d950f730). Not written yet: A2, A7, I2 and I3. I2 and I3 need I1's reply fixture shared from `seon.test-support`, because the web service fixture is private to `seon.render.web-test`.

| Slice | Work / proof / owners |
|---|---|
| Prerequisite · `seon.fn/chain-namespace-source` | Independent producer fix before shared-file phases: `seon.fn/runtime-require-specs` → `seon.sci.eval/namespace-context-row`, replacing declared-source append; proposed proof `seon.cluster.write-back-test/repl-requires-never-corrupt-the-written-ns-form` replays cross-request requires and clean ns repair through file proof. Files: `src/seon/fn.clj`, `src/seon/sci/eval.clj`, `src/my/program.clj`, `src/seon/bootstrap.clj`, `test/seon/cluster/write_back_test.clj`, `test/my/program_test.clj`, its task file. |
| 0 | Write red acceptance tests and file phase tasks in `resources/seon/tasks/seon.task.edn`, tagged one-to-one. Record exact failures and bounds; do not launch a phase with an already-green proof. |
| 1 · `seon.task/chain-declarations` | First derive create!’s members from declaration; then product request/effective flow / A4 / `src/seon/task.clj`, `src/my/task.clj`, `src/seon/cluster/source.clj`, `resources/seon/schemas/my.task.edn`, `resources/seon/schemas/seon.task.edn`, `test/my/task_test.clj`, `test/seon/task_test.clj`, `test/seon/cluster/write_back_test.clj`. |
| 2 · `seon.task/chain-prose` | One prose path, union into existing definition edges, measured reach and declared displays; atomic ref→lookup deletion and sweep / A5, A5b / `src/seon/ref.clj`, `src/seon/fn.clj`, `src/my/program.clj`, `src/seon/sci/lookup.clj`, `src/seon/instrument.clj`, `src/seon/render/ns.clj`, `src/seon/context.clj`, `src/seon/bootstrap.clj`, `src/seon/task.clj`, their schemas/tests and the sweep’s inventoried source files. |
| 3 · `seon.task/chain-obligations` | Three-basis obligations and measured task-aware member identity / A1 / task, turn, context, test/runner, error, cluster.agent, bootstrap, `src/seon/render/ns.clj`, `src/seon/merge.clj`, schemas/tests. Convert `seon.turn/task-proofs`, `seon.context/proofs`, render.ns’s task form and BOTH `seon.error/proof` tag queries; remove task-edges’ proofs member, keep subjects/referrers. Convert selectors in `test/seon/cluster/db_flow_test.clj`, `test/seon/test/redefined_callee_test.clj`, `test/seon/task_ingestion_test.clj` with consumer tests. Delete successor inference and stage help together. |
| 4 · `seon.task/chain-acceptance` | Converge prepare-merge!’s gate and accept-merge! through one verdict; compare tested obligations/task basis inside the writer / A2 / `src/seon/cluster/source.clj`, `src/seon/merge.clj`, `src/my/root.clj`, `src/seon/turn.clj`, `src/seon/context.clj`, `src/seon/bootstrap.clj`, `src/seon/task.clj`, `src/seon/cluster/agent.clj`, schemas/tests and `resources/seon/tasks/seon.context.edn`. Delete unmet, task-finished?, context’s designed-red/contracts/base-results copies, broken/new-red production/readers, satisfied, facts-only success and empty-delta refusal together. |
| 5 · `seon.task/chain-actions` | One end-to-action owner, every entrant has a task / A6 and separate creation proof / cluster.agent, task/ready, turn, my.root and tests. Remove parked/mailbox refusal counters as lifecycle policy; errors are facts at their producer. |
| I0 · `seon.task/chain-intake-search` | Search gaps / I0 / `my.program/search`, its existing search/render owners and tests, vocabulary; measure token cost and search cost on the recorded workloads. |
| I1 · `seon.task/chain-intake-replies` | Deliver persisted questions to user addresses and inbound replies to the recipient’s branch through root db<->flow; record sender and existing caused-by, no new reply-to attribute / I1 / message owner/schemas, cluster.agent delivery, web form/inbound, routing and web/Flow tests. |
| I2 · `seon.task/chain-intake-answer` | Depends on I1’s sender/delivery; missing `my.agent/answer!` and task-declared answer schema/value / I2 / my.agent, task/message schemas and writers/tests; reuse ordinary data and validation, preserve message and intent basis. |
| I3 · `seon.task/chain-intake-approval` | Latest-answer Survey gate / I3 / step template/default proof and Flow tests; depends on I1/I2 and existing obligations/acceptance. Slice 6 includes this Survey default. |
| 6 · `seon.task/chain-templates` | Templates and one-write constructor / A3 / task, step tasks/default tests and write-back tests. |
| 7 · `seon.task/chain-loop` | Full loop / A7 and separate scenario proofs / db-flow tests; root reviews measured costs and actual publication. |

## 7. Narrow-check questions

Can every red reproduce without root SCI/paid calls? Does inputless Prove pass without a permanently red main
fixture? Do main’s gates survive candidate tag deletion while root’s revisions apply? Does writer comparison cover proof
inputs? Are sent-back/refused-done readiness correct? Can the prose cut remove both duplicates without a ref/lookup
load cycle? Does each phase boot? Walk the dropped-message subject; measure time/allocation and hand acts.

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

### 8.3 Rewrite evidence (2026-10-13)

Inputs: [Opus's 13 findings](../../../research/agent-platform/2026-10-13/review-spec-opus.md) at bcf638361,
[prose references](../../../research/agent-platform/2026-10-13/prose-references.md),
[Markdown sections](../../../research/agent-platform/2026-10-13/markdown-sections.md),
[commonality](../../../research/agent-platform/2026-10-13/lifecycle-commonality.md) and
[Astra's lifecycle review](../../../research/agent-platform/2026-10-13/review-lifecycle-astra.md).
The old decided text is in 40dd25678; §§8.1–8.2 above are preserved verbatim, not current instructions.

Source at bcf638361 confirms the metadata reader/consumer, docstring splitter, duplicate proof queries,
hard-coded help and reconciler admission gap. No source definition authors must-read/see-also metadata.
The inspected owners match default's 371a754df archive. Malli pin dd13ff8113 supplies children/deref-all/type
and its cached validator; no new schema walker is needed.

One MCP JVM read (root/default, explicit connection; no root SCI or reload) returned: obligations,
assignment-read, write-attempt!, document, names and docstring-parts exist; chain! does not; installed steps
are `[:prove]`. `(seon.ref/names "Read `my.agent` and `seon.task/obligations`.")` returned only the qualified
function. Evaluation: 3 ms; request: 39.8 ms, 0.9 MB attributed allocation (entrance 7.9 MB), no reply errors.
This verifies existing seams/gaps, not the proposed chain.

Foreign boundary: both status entrances reported default faulted (two open faults, 17 errored receipts,
three agent-error signatures; no missing boot layers). Default and other lanes' files were left untouched.
Full `clj-kondo --lint src script test --cache false --parallel`: 0 errors, 1,645 warnings, 5,530 ms.
Documentation-only change; source/test delta zero. Commit-gate foundation tests and their recorded results
are in the commit evidence; they do not establish the proposed acceptance deftests.

The candidate checks have an unresolved storage/fixture interaction; no source fix is claimed:

| Selection / basis | Run | Result / test time |
|---|---|---|
| Three tests / first candidate | dd0997c91730 | Reference test passed; readiness errored at `seon.db/sink-digests` (missing node); effective-task returned empty data; 8,717 ms. |
| Task pair / HEAD | dc3a5e3bc8d7 | 11 passes, zero failures/errors; 8,156 ms (31,894 ms total). |
| Three tests / exact candidate | 2975c2e1e605 | Same two task reds; reference passed; 8,627 ms. |
| Three tests / HEAD | fb0d414727e8 | 13 passes, zero failures/errors; 8,587 ms (26,981 ms total). |
| Task pair / exact candidate | 1f43b2084c3d | 11 passes, zero failures/errors; 8,191 ms (28,773 ms total). |

The task pair is `seon.task-test/a-running-subject-readies-its-first-step-only` and
`seon.task-test/a-steps-effective-task-is-its-subjects-merged-with-its-own`; the reference test is
`seon.ref-test/one-text-yields-names-paths-and-line-citations-by-the-same-rules`. Final commit checks the task
pair separately with `--at-candidate`; the reference test's positive candidate evidence is above. The combined
candidate failure remains a foreign verification limit, not a claimed pass or a diagnosed root cause.
