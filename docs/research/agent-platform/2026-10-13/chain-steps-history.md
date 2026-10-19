---
title: "Chain step history: every specified step sequence for an agent task chain"
type: research
status: research, read-only; no code or doc changed
date: 2026-10-13
basis: "HEAD of refactor/agent-platform as checked out 2026-10-13; git history searched with --all; docs read at the paths and commits named"
---

# Chain step history

Question: where was the full series of software-development steps for a task chain specified, and what does the most recent owner-endorsed sequence look like? Only `:prove` and `:implement` exist in code today.

Legend. **[V]** = quoted from the file or commit message named, checked in this session. **[I]** = my inference. **[M]** = recorded only in the Claude memory directory (not in the repo).

## 1. Built today

- `resources/seon/schemas/seon.task.edn` line 9 [V]: `:step [:enum {:description "The step kind: Prove supplies new red product tests; Implement makes its proofs green. Absence reads as :implement at seon.task/obligations."} :prove :implement]`
- `docs/seon/architecture/vocabulary.md` line 89 [V]: "a step is an ordinary task with its own assignment, obligations and end, whose `:seon.task/step` is `:prove` or `:implement` (absent means `:implement`)".
- `:seon.task/send-back!` exists as the verb, with the end `:sent-back` [V, commit e42d548aa]. Old `:seon.task/steps`, `:seon.step/*`, `my.task/plan!`/`step!`/`done!` were deleted in e42d548aa on 2026-10-11 [V].
- Two-step chain Prove → Implement ran unattended in Run 15b, commit 492f20d29 and 1bcc6711b [V].

## 2. Sources, oldest first

### S1. Flat text steps (2026-09-28, deleted 2026-10-11)

- Commit c135d9993 [V]: "tasks carry ordered steps: :seon.task/steps components at the task home (position, text, status, done-tx)". No kind on a step.
- `docs/seon/specs/task-steps.md` [V]: "status: shelved by the owner (2026-10-08)"; "keep as flat ordered steps; the tree is not used".
- Deleted in e42d548aa 2026-10-11 [V]. Status: **superseded** by one task per step (children).

### S2. AGENTS.md design order for Seon developers (2026-10-10, commit fb98f1752)

Developer order, not an agent chain, but the owner's list matches it. [V] AGENTS.md "How we design" (current):
1. Trace the data flow. 2. Specify the interface before the body: "the contract over those schemas and its proofs ... The proofs are red until the body exists." 3. Write the simplest body. 4. Change the owner. 5. Measure before and after. 6. Prove the whole loop.
And AGENTS.md line 21 [V]: "Tasks chain naturally: model the data and specify the interface, then write the proofs, then implement the body."
Status: **law, in force** at HEAD.

### S3. TASK-CHAIN card draft (2026-10-11, commit a8bce9d10, root)

Eight stages, in order [V, from `git show a8bce9d10:docs/seon/schedule/cards/TASK-CHAIN.md`]:
0. **Mine** (a query, sometimes the owner): reproduction form, evidence values, the task kind.
1. **Survey**: search what exists; name the owner.
2. **Model** ("skipped when no schema is new"): register missing schemas, declare consumers.
3. **Prove**: an `are` table holding the reported input; a seeded property where a one-line law exists.
4. **Check proofs** (a separate, short agent): "do the proofs encode the intent ... red for the right reason, cheap?"
5. **Implement**: "the simplest body that turns the proofs green". May send back to Prove or Survey.
6. **Land** (automatic): "gate, merge, write-back, git commit."
7. **Review** (root, later the root agent): reads the end summary; the stage that caused friction is edited.
Done-check per stage type "expressed as data". Status: **superseded** by S4 and S5.

### S4. Fable review of the card (2026-10-11, commit b1fa25d4e)

§8.1.1 [V]: "data (Model) → specify by failing tests (Prove) → implement to green (Implement) → integrate under a gate (Land) → retrospect (Review)." Corrections [V]:
- "Model becomes **Design**: schemas *and* the contracted stub declarations" so Prove "compile[s] against real symbols".
- "One **Design** stage in pipeline A (survey + design); the split is pipeline B."
- Check "folded into Implement's first turn with a send-back right".
- "Rename Review to **Retrospective**."
- Land stays automatic on green; the review disagreement is recorded as a named disagreement.
Resulting sequence [V, §3 of the card after S4]: Mine, Design, Prove, Implement, Land, Retrospective. Status: **adopted into TASK-CHAIN**, later revised by S5.

### S5. The owner's Q13 answer on the first experiment (2026-10-11, `docs/research/agent-platform/2026-10-11/fable-replan.md` §8)

Options listed [V]: "(i) one mined task ... through Survey, Prove, Check-proofs, Implement, Land, each a separate DeepSeek agent; (ii) ... with Model added".
Owner answer [V]: "Astra's version: two agents, Design+Prove → Implement, on one small existing defect. Root launches the successor by hand once from the predecessor's program basis ... then the same handoff is automated at `seon.turn/end-task!`."
Status: **implemented as the two-step chain** (Runs 12 to 15b, 1bcc6711b, 492f20d29) [V].

### S6. Plan pass 2 (2026-10-11, commit 73a36c1fc) and the current plan

Commit message [V]: "TASK-CHAIN §1–§7 revised to the agreed design (Design, Prove, Implement with send-back, Land after review, Retrospective; stage proofs ...)".
Current `docs/seon/plan.md` §1 [V]: "work is tasks that improve as they move through a chain of agents (design, prove, implement, merge after review, retrospect)".
Current `docs/seon/plan.md` §3 [V]: "Mine (a query) → Design (survey, schemas, contracted stubs) → Prove → Implement (with a send-back right) → Merge (root's merge-request task) → Retrospective."
Current plan §4 [V]: "Instructions are data on the task (steps and problem tasks alike); no templates, no stage types."
Status: **the written agreed design**. Design is **not built** (see S9). Whether §3's wording predates 73a36c1fc is [I]; its commit message says it revised the plan to this design.

### S7. One task per step (2026-10-11, `docs/research/agent-platform/2026-10-11/task-graph-design.md`)

[V] "Every unit of agent work (Design, Prove, Implement, root's merge request, root's retrospective) is a task with its own assignment, proofs and end." Stage names as `:seon.task/stage` on the step: "a ref to a stage type, set once at creation". Chain table [V]: "Design; Prove `after #{Design}`; Implement `after #{Prove}`; merge request `after #{Implement}`; retrospective `after #{merge}`."
Status: **mostly adopted**: one task per step with `:seon.task/children` and `needs` is built. The mutable stage is **not built** [V, the doc's own table line 143: "not built: stage is set once; the basis lives on the predecessor's assignment; advance is derived"].

### S8. Owner decisions list (2026-10-11, `docs/research/agent-platform/2026-10-11/owner-decisions.md` items 5 and 6)

[V] "Build it generally and experiment with variations step by step, keeping the steps that make the task better at each stage." And: "A stage is done when its stage proofs ... are green. Every stage records an explicit decision (`:seon.task.stage/name`, `/decision`, `/reason`, `/keys`); 'nothing to do' is `:unchanged` with a reason, never an absence."
Status: the decision record was **dropped** with the stage entity [I, from task-graph-design line 143 and the later "no stage entity" rulings in S10].

### S9. Astra's `:design` kind (2026-10-12, `docs/research/agent-platform/2026-10-12/plan-chains-astra.md`)

Example EDN [V]: a subject `square-area` with children `design`, `prove`, `implement`, where the design step has `:seon.task/step :design` and the instruction "Specify the input/output schemas and contract ... Do not implement the body."
Done rules [V]:
- **Design** (new `:design`, introduced with C1): "Its nonempty tagged design proofs pass on the actual declarations/examples and were not already satisfied at assignment ... A proof merely checking that prose exists is insufficient." Scope: "interface design with executable acceptance."
- **Prove**: "the product is nonempty and is exactly the newly introduced designed-red proof definitions for its successor(s)."
- **Implement**: "Required proofs are current and green; no change breaks an existing green or introduces an unrelated red."
Status: **deferred** (see S10, D2).

### S10. The hybrid and the deferral (2026-10-12, `docs/research/agent-platform/2026-10-12/hybrid-chains.md`)

§7 D2 [V]: "No `:design` step; Prove's product is red tests, contracted stubs, registered schemas | 0 tasks carry a step; the target is two steps; a kind needs a done rule, help group and schema | Astra's interface-design kind with executable acceptance, added when a Prove run fails for want of it."
§6 [V]: "Do not add a `:design` step, a chain runner, a handoff store, a prompt store, a per-subject call allowance or ..."
`plan-chains-opus.md` line 279 [V]: "No `:design` step kind before a Prove run has failed for want of it (Q4). Count first: 0 tasks carry a step."
Status: **deferred**. The current `:prove` step does the design job: its product is red tests, stubs and schemas (hybrid-chains line 78 [V]).

### S11. Step-kind proposals (2026-10-12, `plan-step-semantics-sol.md`, `plan-step-semantics-opus.md`, `hybrid-step-semantics.md`)

- Sol [V, plan-step-semantics-sol.md line 28]: `:seon.task/step-kind` ∈ `:work`, `:design-prove`, `:implement`, `:merge`. Its `:merge` done rule [V]: "Reviewed identity = accepted identity = written identity; positive git-commit evidence and all merge-branch tests green."
- Opus [V, plan-step-semantics-opus.md line 29]: `:seon.task/stage` ∈ `#{:prove :implement}`.
- Hybrid [V, hybrid-step-semantics.md line 29 and 43]: `:seon.task/step` ∈ `#{:prove :implement}`, "the creator writes `:prove` on a Design+Prove step". Sol's `:merge` and `:work` were not adopted as step kinds; a merge is the root merge-request task [I from plan §3].
Status: `:prove`/`:implement` **built**; `:design`, `:work`, `:merge` **not built**.

### S12. Old plan steps as a historical claim (2026-10-12, `docs/research/agent-platform/2026-10-12/dig-step-semantics.md` line 71)

[V quote of the dig doc]: "Steps lived in plan (My, Design, Prove, Implement, Land); proof selection was stage-aware." [I, not checked]: I did not locate the deleted plan code that held those names. Treat as the dig's claim.

### S13. Owner instruction, intent versus instructions (2026-10-12 night) [M]

Memory `project_handoff_2026_10_12_night.md` line 104 records the owner: "bring back :seon.task/instructions — intent = description that ACCRETES down a chain (refine! expands); instructions = per-step text that OVERRIDES inherited instructions." Commit 3410f6ca8 [V] "Chain task data (H2): :seon.task/instructions back as a step's own work, seon.task/effective merges the goal down :children". Status: **built**.

### S14. Owner's templates note (2026-10-13 morning) [M]

Memory `project_step_templates_2026_10_13.md` [M, quoted]: "the tasks root hand-writes are really the first part of a chain ... the later parts are the software-development steps. Make the steps templates the previous data merges into, some fields overwriting (instructions), others accumulating (intent refinements, references)." And "In all cases we need the current step to update the task so it's ready for the next stage and we can use the proofs to know when that's been done too."
Proposed shape [M]: `resources/seon/tasks/seon.step.edn` with design, prove, implement templates; "every template carries a standard proof that reads the task data".
Conflict: this reverses the 10-11 and 10-12 rulings in S6 (plan §4, "no templates, no stage types") and TASK-CHAIN §7 ("never a template or a stage type"). No repo document with the templates was found (rg over `docs/research/agent-platform/2026-10-13/` found none). Status: **unconfirmed**; needs the owner to restate it in the repo.

### S15. Review before merge: open question (2026-10-13 morning report)

`docs/research/agent-platform/2026-10-13/morning-report.md` lines 54-57 [V]: "A review step before merge? ... Options: (a) rely on the new help lines (send back contradicting proofs; write laws) and measure; (b) a cheap reviewer model judges each Prove product against the intent before it merges; (c) root reviews Prove products by hand for now." Status: **open**, no decision recorded.

### S16. Refactor and cost tasks (not a step)

`docs/seon/specs/agent-loop.md` lines 63-64 [V]: "A cost or refactor task, whose value does not change, is proven by measured cost: its proof asserts one test-visible measure ... red before the fix." `.agents/skills/clojure-testing/SKILL.md` line 148 [V]: "A behavior-preserving refactor names ...". Status: **a task kind with a proof rule**, not a chain step. No source names "refactor" as a step.

## 3. Answers to the owner's list

| Owner term | Where it is specified | Status |
|---|---|---|
| model the data | S3 Model; S4 folds it into Design; AGENTS.md line 21 "model the data and specify the interface" | folded into Design; Design not built |
| specify the interface | S4 contracted stubs; S9 Astra `:design` | Design deferred (D2) |
| design | S4, S6, S9 | deferred |
| write proofs | Prove (S3, S6) | built as `:prove` |
| implement | Implement (S3 to S6) | built as `:implement` |
| review | S3 Review (root); S4 renamed Retrospective; S15 open question; plan §1 "merge after review" | Retrospective not built; review before merge undecided |
| refactor | S16 only, as a task proof kind | not a step anywhere |
| merge | S3 Land; S6 Merge (root's merge-request task); S11 Sol `:merge` | automatic merge built; review gate open |
| write-back | S3 Land: "gate, merge, write-back, git commit" [V]; no chain doc makes it its own step | machinery inside Merge, not a step [I] |
| something else | S3 Mine and Survey; S6 Retrospective; S12 Land | Mine and Retrospective not built |

## 4. Synthesis: the most recent owner-endorsed sequence

Owner-endorsed by written decision: the TASK-CHAIN card as revised to "the agreed design" (commit 73a36c1fc, S4 and S6), plus the owner's Q13 answer that the first experiment is two steps, Design+Prove then Implement (S5). The 10-13 templates note (S14) is newer but unconfirmed in the repo.

Sequence (plan.md §3 and TASK-CHAIN §3, with the done rule from TASK-CHAIN §3b and the built status):

1. **Mine** (optional; root). Product: reproduction form, expected and actual data, basis. Done: the reproduction shows expected differs from actual. Status: **not built** (root does it by hand).
2. **Design** (survey, schemas, contracted stubs, refined intent). Product: named schemas, `(fail! …)` stubs with complete contracts, refined intent naming them. Done: design proofs pass on the actual declarations and were not already satisfied; no unresolved reference; schema samples validate (S9, TASK-CHAIN §3b). Status: **deferred**; its job is currently inside Prove.
3. **Prove** (designed reds). Product: deftests tagged to the successor. Done: product is exactly the new designed reds; each red by an assertion or the unimplemented error (`:seon.error/unimplemented`, hybrid-step-semantics line 46). Status: **built** (`:prove`, H4 merges its product).
4. **Implement** (body and send-back). Product: body, callers converted. Done: required proofs green; no newly broken reached test (`:seon.task/proofs`). Send-back to Prove or Design with counterexample (`my.task/send-back!`). Status: **built**; send-back built, Design target not built.
5. **Merge** (root's merge-request task, automatic on green). Product: merged declarations, write-back, `:seon.task/git-commit`. Done: reviewed identity = accepted = written; the gate passes on the merge branch (S11, TASK-CHAIN §3b Land). Status: **built**, automatic merges running.
6. **Review before merge** (open). Options in S15. Status: **undecided**.
7. **Retrospective** (root). Product: end summary, follow-up tasks with red proofs, instruction edits. Done: follow-ups filed, no follow-up without a red proof. Status: **not built**.

Versus the current code: steps 1, 6 and 7 are not in the schema, and step 2 is folded into step 3. `seon.task.edn` line 9 has two values, and the TASK-CHAIN card asks for three more.

## 5. Conflicts to settle with the owner

- **Templates or data.** S6 and TASK-CHAIN §7 say no templates and no stage types. S14 [M] asks for step templates. Both cannot be the current design.
- **Review.** S4 says Land stays automatic; S6 says "Land after review" and plan §1 "merge after review"; S15 leaves it open.
- **Design as a step or inside Prove.** S9 and S11 want a `:design` kind; S10 (D2) defers it until a Prove run fails for want of it, and the owner has not yet asked for it.
- **Stage names.** "Model" (S3) became "Design" (S4); "Check proofs" (S3) was folded into Implement (S4); "Land" became Merge (S6) with the review question open.

## 6. Top 3 sources

1. `docs/seon/schedule/cards/TASK-CHAIN.md` (current §3, §3b, §8.1), with history a8bce9d10 → b1fa25d4e → 73a36c1fc (2026-10-11). The fullest step list, with done rules and the Fable review that set the agreed order.
2. `docs/research/agent-platform/2026-10-11/fable-replan.md` §8 Q13 (owner answer) with `docs/seon/plan.md` §1 and §3 (current). The owner's decision that the first chain is Design+Prove → Implement, and the written order.
3. `docs/research/agent-platform/2026-10-12/hybrid-chains.md` §7 D2 with `plan-chains-astra.md` (the `:design` table, C1). The deferred step kind, its done rule and why it is not built. Its memory counterpart is `project_step_templates_2026_10_13.md` (S14), which needs confirmation.

## 7. Not found

- No commit adds a step kind named Mine, Survey, Model, Check, Land or Retrospective to the schema. Those names appear only in docs (S3, S4).
- No repo document records the templates design of S14.
- `git log -S':review'` returned plan and review commits, none adding a step kind [V].
- I did not read the full `task-write-back.md` (cited by hybrid-chains basis) or the deleted plan code behind S12.
