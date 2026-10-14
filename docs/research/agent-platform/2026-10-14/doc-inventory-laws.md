---
title: "Doc inventory: laws and architecture (inv-laws)"
type: research
status: done
---

# Doc inventory: laws and architecture (lane inv-laws)

Date: 2026-10-14. Authority: [TASK-CHAIN](../../../seon/schedule/cards/TASK-CHAIN.md) (READY; section 6 as of 466b1e207),
[plan §0](../../../seon/plan.md) (4e70ff8c7). Read-only except this note. Every code claim checked by `rg` against `src/`,
`test/` and `resources/`. Installed evidence uses file:line here because this is dated evidence.

Kinds: **stale-target** = the claim describes the TASK-CHAIN target wrongly. **installed until S<n>** = the claim
accurately describes installed code that TASK-CHAIN changes in slice S<n>; it is not stale, and it converts with that slice.

## Totals

- Files: 5 in scope. Verdicts: live and correct 0; needs edits 5; superseded whole 0; done historical 0.
- Stale or incomplete claims: CLAUDE/AGENTS 31, vocabulary 19, README 4, data-modeling-guide 6, contracts-guide 5 (65 rows).
- Path:N citations in living docs (convention defect, not lifecycle): data-modeling-guide 27, vocabulary 1.
- Dead links: none. The data-modeling-guide PRD links and vocabulary `../../research/` links resolve (checked from each file's directory).

## Notes for root (decisions and discrepancies)

1. **CLAUDE.md is a symlink to AGENTS.md** (70 KB). Edits belong in `AGENTS.md`; `CLAUDE.md` follows. vocabulary.md links to `AGENTS.md`.
2. **`my.task/send-back!` is not superseded.** The brief lists it among TASK-CHAIN §6 deletions, but §2.7 and §4 keep it as the bail verb (required reason, the chain goes to root, no earlier-stage target). Rows below treat it as kept with a changed target.
3. **Help is already Markdown.** `resources/seon/help.md` exists and `seon.bootstrap/help-value` reads it (`src/seon/bootstrap.clj:40-44`). The §6 "fixed help strings" deletion is done. vocabulary has no `help` row; add one.
4. **`:seon.task/proofs` is installed** as a schema member beside obligations (`resources/seon/schemas/seon.task.edn:26,40`). `seon.task/chain!` exists (`src/seon/task.clj:739`) in the copies form (templates, customizations, children): installed until S1c-2.
5. **CLAUDE contradicts TASK-CHAIN on completion at start.** CLAUDE says `seon.task/assign!` "refuses a task ... whose proofs are all green". TASK-CHAIN §2.2 says "All green at the start ends the task with no model call." Root must rule; TASK-CHAIN is the lifecycle authority. `seon.task/assign!` exists (`src/seon/task.clj:881`); TASK-CHAIN does not name it.
6. **Not addressed by TASK-CHAIN (owner decision needed):** question tasks and `my.agent/answer!` (CLAUDE; not installed, `src` has 0 hits); namespace agents and D1–D2 trigger-to-task routing (vocabulary; data-modeling-guide §8); session and slot (USER-SURFACES); cron firing and heartbeat (vocabulary); aggregate completion (data-modeling-guide §8).
7. **Word collisions.** `plan` is the task field (TASK-CHAIN §1) and the db<->flow decision (`seon.cluster.agent/plan`, `src/seon/cluster/agent.clj:1659`); the decision's rename is in plan §0 restart batch (1). `intent` names `:seon.task/execution` in vocabulary, which is a second, wrong use of the retired word.
8. **Out of scope, not read:** `docs/seon/specs/task-steps.md` (its subject is the children/step design TASK-CHAIN deletes; likely superseded whole; needs its own pass).

## 1. CLAUDE.md (= AGENTS.md)

Verdict: **needs edits**. Live and correct: "Keep the task-chain design locked" (l.34), "Work is tasks, and tasks chain" (l.20–23), the errors paragraph's per-eval-request ordering (l.489–491), `merge!`'s members except the one noted in row 31.

| # | Anchor | Claim (quoted) | TASK-CHAIN says instead | Kind |
|---|---|---|---|---|
| 1 | Merge is a root task (l.298) | "a done branch is merged by root's `db<->flow`, whose gate takes the proofs ... (`seon.task/obligations`)" | One merge of the chain branch at its end, §2.6; proofs are the `proofs` field, §1; obligations deleted, §6 | stale-target; `seon.task/obligations` installed until S2c-2 |
| 2 | Merge is a root task (l.301–302) | "the merge request is the assignment" | The terminal attempt is the source of the one merge, §2.6 | stale-target; `my.root/merge-requests` installed until S3b-1 |
| 3 | Merge is a root task (l.306–307) | "root reads the diff before it merges until criteria for automatic merges are written" | §2.6 has no per-diff review or auto-merge; root's acceptance is the one merge | unaddressed; owner decision |
| 4 | Write-back (l.311–312) | "a merged task is written into its task file" | Chain tasks are runtime; the final merge never writes them into `resources/seon/tasks/*.edn`, §1 | stale-target |
| 5 | Agents are compartments (l.323–324) | "A non-root agent holds its own branch and its own SCI fork, and nothing else" | Agents share the chain branch serially; a new agent id per task; no branch per task, §2.2 | stale-target; per-task fork installed until S3a-3 |
| 6 | Agents are compartments (l.326–327) | "task refinements, children, its end, sent messages ... reach main only through its one merge request" | Children deleted, §1/§6; refinements are `my.task/update!` on the branch, §3a; one merge, §2.6 | stale-target; children installed until S3e |
| 7 | Agents are compartments (l.328–330) | "auto-merges the rule-trusted parts it owns as they change" | No per-step merge; the chain merges once, §2.6; per-step `:merge` deleted, §6 | stale-target; installed until S3b-1 |
| 8 | Agents transact facts; root reacts (l.333) | "writes its end, refinements, outgoing messages and merge request as facts on its own branch" | Refinements are `update!`, §3a; merge is once, §2.6 | stale-target |
| 9 | Agents are compartments (l.265–267, section "Clusters and branches") | "A finished agent's branch is deleted once its task is done or given back and its merge request is closed" | The chain branch is retired after publication, §2.6; a bail keeps the branch, §2.7 | stale-target; installed until S3b-2 |
| 10 | An agent's context (l.288–290) | "its task selects the branch it checks out: the live cluster head or its own branch" | A chain's agents all check out the chain branch, §2.2 | stale-target (minor) |
| 11 | Identities are minted (l.358) | "(a task's handle by ingestion)" | The one task writer derives identity from final values, §1 "One writer for tasks" | stale-target (minor) |
| 12 | Merge is a root task (l.308–310) | "every test on the merge branch is green (the proofs, the tests reaching the change ...)" | Members are the last task's proofs, every test defined or changed on the branch, and every reaching test, §2.6 | incomplete |
| 13 | The right context is the product (l.412–413) | "References everywhere (task intents, Markdown docs, docstrings)" | `plan` replaces `intent`, §1 | installed until S1a (`:seon.task/intent`, 9 src files) |
| 14 | Documentation is data (l.403–405) | "A definition or test names its tasks with `:seon/tasks` metadata" | Tags deleted; the `proofs` field, §1 and §6 | stale-target; tags installed until S2c-2 (24 src files) |
| 15 | Write tasks as data (l.417–420) | "`:seon.task/intent` (what must become true, ...) and optional `:seon.task/needs`" | `plan` (§1); `needs` deleted (§1, §6) | intent: installed until S1a; needs: stale-target, installed until S3e |
| 16 | Write tasks as data (l.420–424) | "A chain is a subject with step `:seon.task/children` ... `seon.task/effective`" | No subject task; a chain is linked tasks via `next`, §0 and §2.1; children, step, effective deleted, §1 and §6 | stale-target; installed until S3e |
| 17 | Write tasks as data (l.424–426) | "Its proofs are the deftests carrying `ns/name` in `:seon/tasks`, never prose" | The `proofs` field, §1; tags deleted, §6 | stale-target; installed until S2c-2 |
| 18 | Write tasks as data (l.417) | "A task lives in `resources/seon/tasks/<ns>.edn`" | Declared tasks do; chain tasks are runtime data, §1 | incomplete (add the runtime case) |
| 19 | Write tasks as data (l.433) | "Write the intent typed: the fully qualified symbols and keywords" | `plan` replaces `intent`, §1 | installed until S1a |
| 20 | Derive work and completion (l.487–489) | "A task names its success tests (deftests carrying it in `:seon/tasks`) before it is assigned or launched" | The `proofs` field, §1; start rule, §2.2 | stale-target; installed until S2c-2 |
| 21 | Derive work and completion (l.489–490) | "`seon.task/assign!` refuses a task with no proofs or whose proofs are all green" | TASK-CHAIN has no `assign!`; all green at start ends the task, §2.2; an empty set never finishes, §2.4 (conflict, note 5) | stale-target; `assign!` installed until S3a-1 |
| 22 | Derive work and completion (l.491–492) | "all green completes the task, the only success" | Done is the done rule, §2.4: own proofs, no stray red, no main-green regression, no contract gap | stale-target |
| 23 | Derive work and completion (l.493–494) | "a question task declares its answer's Malli schema and `my.agent/answer!` stores the validated answer" | Not in TASK-CHAIN; not installed | unaddressed; owner decision |
| 24 | Derive work and completion (l.494–496) | "`my.task/send-back!`, with a required reason, an optional target stage ... back to the stage that owns the mistake" | Bail to root with reason; no stage target; a send-back never reruns an earlier task, §2.7; kept verb, note 2 | stale-target; installed until S3c-1 |
| 25 | Derive work and completion (l.496) | "an agent's other task verbs are `my.task/current` and `my.task/refine!`" | `my.task/update!` replaces `refine!`, §3a; `current` kept | refine!: installed until S1d-2 (`src/my/task.clj:45`) |
| 26 | Derive work and completion (l.498–499) | "preserve task and agent identity on loud, resumable budget exhaustion" | A resumed task's next attempt has a new attempt and agent identity, §1; `my.root/run!` starts it, §2.7 | stale-target |
| 27 | Work as a Seon agent (l.625–626) | "Root files each slice as a task with a red tagged proof ... and assigns it" | The `proofs` field, §1; `chain!` creates it, §2.1 | stale-target; installed until S1c-2 and S2c-2 |
| 28 | Design by independent plans (l.637–639) | "a subject task (what we know, what to look into, the questions)" | No subject task, §0; the round is a chain of tasks, §2.1 and §4 | stale-target; installed until S3e |
| 29 | Design by independent plans (l.651–652) | "Root runs this shape by hand until the scheduler runs it as a task tree." | "A single task is a chain of one" (§0 intro); no task tree | stale-target |
| 30 | Plan before implementing (l.632–635) | "Design decisions are made up front in implementation cards" | Correct English; the word `plan` is the task field, §1 (note 7) | live; word note only |
| 31 | Merge is a root task (l.308–310) | "a test already red on main stops it for root" | §2.6: a test already red on main is a refusal at merge; consistent | live |

Row 31 is a check, not a finding. Kept so root sees the merge paragraph was read whole.

## 2. vocabulary.md

Verdict: **needs edits**. Live and correct: the standing-order paragraph, the library table (rows reading Clojure, Datahike, Malli, SCI, edamame, Caffeine, konserve), `reference` (cites TASK-CHAIN §3), `reaching test`, `wake`, `answered t`, `turn`, `render function`, `current-state block`, `write-back`.

| # | Anchor (term) | Claim (quoted) | TASK-CHAIN says instead | Kind |
|---|---|---|---|---|
| 1 | `task` (Seon's words) | "an intent, linked facts, optional agent; a merge request is a task" | `plan` (§1); a merge is not a task (§2.6) | stale-target; `intent` installed until S1a |
| 2 | `merge request` (git words) | "an assignment, as the thing root merges" | One merge of the chain branch and its terminal attempt, §2.6 | stale-target; installed until S3b-1 |
| 3 | `end copy` | "one transaction copying a successful attempt's cluster facts onto main" | No copies, no per-step merges, §0 intro; one acceptance, §2.6; `chain-copy` deleted, §6 | stale-target; installed until S3b-1 |
| 4 | `:seon.cluster` | "facts copied onto main at an attempt's successful end" | Final acceptance only, §2.6; the schema partition itself stays | stale-target (the copy clause only) |
| 5 | `proof` | "a deftest naming a task in `:seon/tasks`; an Implement step requires its proofs green" | One proof set per task in the `proofs` field, §1; done rule, §2.4 | stale-target; installed until S2c-2 |
| 6 | `subject, step` | "a subject task owns its step tasks as components (`:seon.task/children`)" | No subject, step or children, §0, §1 deleted list, §6 | stale-target; installed until S3e |
| 7 | `product` | "the deftests it declares by symbol (`:seon.task/product`)" | The `proofs` field replaces it, §1 deleted list, §6 | stale-target; `seon.task/product` installed (5 src files) until S2c-2 |
| 8 | `obligations` | "`seon.task/obligations` selects the step kind" | Deleted, §6; a task's proofs are its field, §1 | stale-target; installed until S2c-2 |
| 9 | `needs` | "`:seon.task/needs`: the tasks a task runs after" | Deleted, §1 and §6; `next` links the chain | stale-target; installed until S3e |
| 10 | `references` | "its optional step, children and needs"; "(ingestion, `seon.task/create!`, `my.task/refine!`)" | Derived from `plan` and `instructions` by the one writer, §1; `update!` replaces `refine!`, §3a | stale-target; `refine!` installed until S1d-2 |
| 11 | `instructions` | "A step's effective task (`seon.task/effective`) is `clojure.core/merge` of the intent and instructions" | Owned member, never merged; `effective` deleted, §1 and §6 | stale-target; `effective` installed until S3e |
| 12 | `ready` | "the derived set of tasks that should be running now: leaves, not done, needs done" | Start rule, §2.2; needs clauses deleted, §6 | stale-target; `seon.task/ready` installed until S3a-1 |
| 13 | `attempt` | "ended once by its end fact" | Outcome on the attempt row: `:done`, `:merged` or `:closed`, §1 | incomplete (outcome exists installed; `:done` lands in S3a-3) |
| 14 | `intent` (row names `:seon.task/execution`) | "`:seon.task/execution`, the stored permission for an attempt" | `intent` is retired for `plan` (S1a); the member is `execution` (§1) | stale-target (term collision); installed until S1a |
| 15 | `plan` | "the pure decision of db<->flow (`seon.cluster.agent/plan`)" | The task field is `plan` (§1); this decision is renamed in plan §0 restart batch (1) | installed until S1a phase 2 (restart batch) |
| 16 | `problem task` | "its intent says what to do ... assigned to root or a fresh agent" | Root writes the problem task on main; its field is `plan`, §2.7 | stale-target (minor); installed until S3c-1 |
| 17 | `run` | "a budget of turns counted from a triggering message or task assignment" | Budget is per attempt, `seon.turn/turns-left`, §2.7 | stale-target (minor); installed until S3c-1 |
| 18 | `[TARGET] task, seon.task` | retired words "`my.task`, task template"; "A "template" is the render pair and units" | `my.task/update!`, `current`, `send-back!` keep the namespace (§3a); a template is an ordinary task map, §1 | stale-target |
| 19 | `[TARGET] task, seon.task` (trigger clause) | "A trigger resolves to a task identity at the writer" | Not addressed by TASK-CHAIN (D1–D2, 2026-09-19) | unaddressed; owner decision |

Also in vocabulary, not stale but needing work:
- `seon.id/id` row cites `src/seon/id.clj:246-261` (a `path:N` in a living doc; convention defect). Replace with the symbol.
- `[TARGET] namespace agent` ("a trigger maps to a TASK (D2)"), `session`, `slot`, `clock`, `firing`, `heartbeat`: unaddressed by TASK-CHAIN (note 6).
- No `help` row (note 3).

## 3. README.md (architecture index)

Verdict: **needs edits** (short). Links all resolve.

| # | Anchor | Claim (quoted) | TASK-CHAIN says instead | Kind |
|---|---|---|---|---|
| 1 | Index intro (l.26–27) | "The current specifications include agent execution, merge and errors" | TASK-CHAIN is the lifecycle authority (plan §0; its header); add it to the list | stale-target (incomplete) |
| 2 | Intro (l.12–13) | "the [ledger] owns delivery order and proofs" | Slice order is TASK-CHAIN §7; plan §0 owns the schedule | stale-target |
| 3 | Table, Function contracts row | "contract tasks, their proof cases and findings" | Proof is the `proofs` field, §1 and §6 | installed until S2c-2 (contracts-guide §6) |
| 4 | Intro (l.28–31) | "The older agent-platform plan supplies historical research" | plan.md is `status: active` and holds the schedule (§0); "older agent-platform plan" is ambiguous | needs clarification from root |

## 4. data-modeling-guide.md

Verdict: **needs edits**. Live and correct: §1 identity, §4 components, §5 positive observation, §6 collections, §7 pulled shapes, §9 declaration table (all 1,258-attribute probe evidence aside). The PRD links resolve (`docs/prds/...` from this directory).

| # | Anchor | Claim (quoted) | TASK-CHAIN says instead | Kind |
|---|---|---|---|---|
| 1 | §2 Ending a lifecycle | "a resolved task remains addressable through its closing fact" | The attempt's outcome, §1 (`:merged` installed; `:done` lands S3a-3) | incomplete; installed until S3a-3 |
| 2 | §3 after the table | "B3's target task `parent`/`needs` relations apply that rule" | `next` links tasks, §1; no cycle can be written, §2.1; `needs` deleted, §6 | stale-target |
| 3 | §8 Errors, tasks | "A plan step is a task related by parent, needs and position" | `next` (§1); no parent or needs (§0 and §6) | stale-target |
| 4 | §8 Errors, tasks | "aggregate completion requires a nonempty complete child set" | Children deleted (§1, §6); aggregate completion is not in TASK-CHAIN (note 6) | stale-target |
| 5 | §8 Errors, tasks | "Namespace agents express responsibility, not assignment or identity." | Not addressed by TASK-CHAIN | unaddressed |
| 6 | §8 and §9 `path:N` citations (27 lines; grounding lines throughout) | "`reference-code/datahike/src/datahike/db/transaction.cljc:641-715`" and the like | Living docs cite code by symbol (CLAUDE, "Documentation is data") | convention defect, not lifecycle |

Open question 4 names `:seon.task/base` and `:seon.task/proposed`. `src/seon/task.clj:307` still writes `:seon.task/base`; TASK-CHAIN does not mention it. Kept, unverified against TASK-CHAIN.

## 5. contracts-guide.md

Verdict: **needs edits**. Live and correct: §1 completeness, §2 cost rules, §3 schema search, §4 refused values (except the intent word), §5 `seon.contracts/task` (generator survives, §6 of TASK-CHAIN, commit 466b1e207), §6 cost and merge acceptance. Every symbol it names exists in `src/`.

| # | Anchor | Claim (quoted) | TASK-CHAIN says instead | Kind |
|---|---|---|---|---|
| 1 | §4 "A refused real value" | "citing the contracted function in its intent" | `plan` replaces `intent`, §1; `:seon.task/references` kept | installed until S1a |
| 2 | §5 Generating a contract task | "a short typed intent citing the function" | `plan` (§1); the generator survives and writes `proofs`, §6 | installed until S1a; generator output: installed until S2c-2 |
| 3 | §6 Proof | "a one-line deftest tagged with the task (`^{:seon/tasks #{'ns/contract-name}}`)" | The generator writes the `proofs` field; tags deleted, §6 (466b1e207) | stale-target; installed until S2c-2 (`test/seon/contract_coverage_test.clj`) |
| 4 | §6 Cases are temporary | "Root deletes a case when its task merges or retires" | The chain merges once, §2.6; the case belongs to the chain's last task | stale-target (minor) |
| 5 | §5 `seon.contracts/task` | "turns any uncontracted function into an assignable task declaration" | Survives (§6); "assignable" requires a `proofs` field (§1), which the generator must set | incomplete; follows row 3 |

## Verification notes

- `rg -F` counts (src/test/resources): `seon.task/obligations` 6/6/5; `seon.task/effective` 4/3/3; `refine!` 1 in src (`src/my/task.clj:45`); `send-back!` 3/8/8; `assign!` 1 src (`src/seon/task.clj:881`); `chain!` 1 src (`src/seon/task.clj:739`, copies form); `:seon/tasks` 24/97/14; `:seon.task/intent` 9/41/11; `:seon.task/proofs` 6/7/5 (schema installed); `:seon.task/product` 5/6/6; `:seon.task/children` 3/4/6; `:seon.task/needs` 3/4/3.
- Not installed: `my.task/update!`, `seon.task/template` (defn), `seon.task/next`, `:seon.task/plan` member, `done-refusals`, `seon.task/advance-tx`, `my.agent/answer!`, `seon.step.design`.
- Installed: `my.edit/text` (`src/my/edit.clj:89`, S1d-1 landed), help Markdown (note 3), `seon.task/outcome` on attempts (`src/seon/task.clj:1034`).
