---
type: spec
status: proposed
created: 2026-09-28
tags: [spec, task, plan]
---
# Task steps — tasks take over the plan

Owner ruling 2026-09-28: if tasks can provide a plan (ordered steps, persistent across compaction), drop `seon.plan`.
Verdict: YES, with one added attribute family on the task entity and four `my.task` functions. Tasks lack steps and an
agent-side attachment; they already survive compaction. Everything below is verified against source at HEAD 587412b85
unless marked UNVERIFIED.

## 1. What plans do today, and which behaviors anyone uses

`src/seon/plan.clj` (1512 lines, 83 defns), `src/my/plan.clj` (186), schemas `my.plan.edn` 305, `my.plan.item.edn` 102,
`seon.plan.edn` 20. Behaviors (from `docs/seon/specs/plan.md`, uncovered list there is authoritative for tests):

| Behavior | Callers outside plan.clj/my.plan | Verdict |
|---|---|---|
| Ordered step tree with position, parent/depth | none but renders | keep as flat ordered steps; the tree is not used |
| `needs` dependencies, ready/blocked, cycle refusal | `ready-subjects` has no caller in src | dead: delete |
| `done-query` auto-complete in `settle-call` | `turn.clj:2252,3480,3627` schedule the call; no shipped agent/task authors a done-query (`rg done-query src resources` hits only plan files) | dead in practice: delete |
| `about`/`subject` tokens | none | dead |
| Current step, `start!`/`complete!`, recent completions | `cluster/status.clj:112` (current step title), `render/transcript.clj:1774-1801` (step completed detail), `:2149-2162` (per-step turn cost) | keep the current-step idea only |
| `plan!` whole-document reconcile | none | dead |
| Objective | none but render | subsumed by the task's `:seon.task/intent` |
| Plan in context: `:seon.agent/plan` component with `seon.plan/render-plan-{ai,html,form}` (`seon.agent.edn:208-211`), created for every agent at `cluster/agent.clj:240`; the opening stores the `(seon.plan/plan ..)` read (`test/seon/loop_proof_test.clj:170`) | live | keep the function (agent sees its current step), replace the source |
| `bootstrap.clj:60` help line "Your plan is your instructions" | live text | rewrite around the task |
| `:my.plan.item/id` as `:my.note/about` target (`seon/note.clj:63-99`, `my.note.edn:32`) | live | retarget to a task handle (or delete the plan-step arm) |
| NOT plan behavior, hosted there: `run-issue-tests!` (plan.clj:676), `stale-issue-tests`, and the issue half of `settle-call` (plan.clj:749) | `turn.clj:3461,3583` and 3 tests | MOVE to `seon.issue` (which the header at `issue.clj:22` already says is a load-cycle boundary) |
| NOT plan behavior, borrowed names: `:my.plan/request` is the generic request schema of `my.note` (note.clj:20), `my.agent` (agent.clj:19,33,45), `seon.test.clj:1538`; `:my.plan/agent-not-found-error`, `:my.plan/missing-agent-id` are bootstrap's refusal (`bootstrap.clj:108,121-122`, `cluster/agent.clj:389`) | live | rename to owners (see 4) |

Exercised in production by whom: UNVERIFIED for live agents (no store read done in this lane); by callers in source only
the current-step reads above. Root should run `(seon.db/q '[:find (count ?s) :where [?s :my.plan.item/id]])` on
default before deleting; a count near zero confirms.

What tasks do today (`src/seon/task.clj` 266, `src/my/task.clj` 79, `seon.task.edn`): a task is a cluster entity
`{id ns name kind intent must-read evidence}` from `resources/seon/tasks/<ns>.edn` (56 files) or written by an agent
(`seon.task/create!`, `created-by`); `assign!`/`my.task/claim!` write an assignment row (task handle, agent, branch,
revision); `my.task/open` is the queue; proofs and merge tasks hang off it. `task-ai` (render/ns.clj:92) shows name,
kind, ns, intent, must-read. The revision an assignment binds is `seon.id/digest` over `:seon.task/declaration`'s owned
keys only (`issue.clj:548-554,605,637-644`), so an added attribute outside that schema never revises a task.

## 2. The gap

1. Ordered steps and step status: absent.
2. An agent-side attachment: the agent record has no task edge; the only link is the assignment row on the cluster
   (`seon.task/assignment`, task.clj:141). Context renders `:seon.agent/plan` because it is one hop from the agent.
3. Persistence across compaction: the task is durable (a datom set, not evaluation state). `compact-call`
   (turn.clj:2262) retracts only the agent's evaluations, and the next opening regenerates from the agent record. So a
   task already survives compaction and shows again IF an agent-attached render points at it. Today nothing in context
   shows an assigned task except the bootstrap message; the opening shows the plan (`loop_proof_test.clj:170`).
4. A current-step view in context: absent; `task-ai` shows no steps.
5. Custody: task writes go to the task home (`task.clj:written`, `home-connection`), root's branch, not the agent's.
   Step writes on every advance would land on the cluster head from every worker: UNVERIFIED cost, but one committed
   write per step advance, same as a plan write today, plus a home-branch hop. See open question 1.

## 3. Smallest design

Data (`resources/seon/schemas/seon.task.edn`, exact changes):

- `:seon.task/steps` `[:set {:seon.db/component true :description "A task's ordered steps; owned by the task, outside its declaration so no revision changes."} :seon.task/step]` on `:seon.task/task` (optional). Not added to `:declaration`, `:entry`, `:file`, `:basis`.
- `:seon.task/step` `[:map {:seon.db/attributes true :seon.program/partition :seon.data} [:seon.step/position [:int {:min 0}]] [:seon.step/text [:string {:min 1}]] [:seon.step/status [:enum :open :current :done]] [:seon.step/done-tx {:optional true} :seon.db/tx]]`. Position is the order (a component set keyed by position, as plan items were; an ordered vector via `seon.db/cell-vectors` is the alternative: UNVERIFIED, probe before choosing). `done-tx` is the completing transaction; its `txInstant` is the time (plan's `completed-tx` idea).
- `:seon.step/*` keys are new; search the registry (`rg ":step/"` none at HEAD: UNVERIFIED for the store).
- Each agent's task edge: use the assignment. `my.task/mine` reads the task via `seon.task/assignment` (one query, task.clj:141); no `:seon.agent/task` attribute. A task created by the agent (`create!`, `created-by`) is assigned to itself by `claim!`.
- Invariant: at most one `:current`; steps are done exactly once; a task with no steps has no current step.

Functions (all in `src/my/task.clj`, thin over `seon.task` writers; each with a complete Malli contract, private helpers too;
each total, RETURNS a named error alternative: `:seon.task/refused-error` with `:seon.task/refused` values
`:unknown-step :not-my-task :no-current-step`, added to `:seon.task/refused`):

- `(my.task/plan! {:seon.task/steps [text ...]})` -> the task with steps: replaces my assigned task's steps (positions from vector order, first `:current`, others `:open`) in ONE transaction; refuses `:unassigned` when I hold no task. Replaces `plan!`, `add!`, `update!`, `objective!`.
- `(my.task/step! {:seon.step/text ..})` -> appends one open step (`current` if none is). Replaces `add!`.
- `(my.task/done!)` -> marks the current step done with `done-tx` and makes the next open step current (decided inside the transaction with `:db.fn/call`, so two calls never double-complete; a repeat is a no-op). Replaces `complete!`/`start!`. Refuses `:no-current-step`.
- `(my.task/steps)` -> the ordered steps `[{position text status}]`. Replaces `plan`/`steps`/`current`/`ready`/`blocked`.

Contract for the writer helper (in `seon.task`): `task-steps-call` `[:=> [:cat :seon.db/database-value :seon.task/handle :seon.task/step-op] :seon.db/tx-data]`, a transaction function like `plan/settle-call`, run at the writer.

Render (`render/ns.clj`): `task-ai` adds `:seon.task/current-step` (the text) and `:seon.task/progress "2/5"` and, at distance zero, the remaining steps as text; `task-html` lists steps with status. Offered operations: `(my.task/done!)` when a current step exists, `(my.task/plan! ..)` when steps are empty. Agent-side attachment: `seon.agent.edn`'s `:seon.agent/plan` render pair is replaced by a render of the agent's assigned task; the opening's stored read becomes `(my.task/steps)` (form face `render/ns/task-form` today returns `seon.issue/task-edges`; change it to the read of the steps). Content changes after compaction: nothing, the read is regenerated at the opening from durable facts.

Cost: one indexed query for the assignment plus one pull of at most N steps; writes one transaction per call, under the 100 ms run bound (UNVERIFIED; time on a branch of the live store).

The simplest alternative (rejected): keep `seon.plan` and add nothing. It is 1512 + 186 lines for capabilities nobody
calls. Second alternative (rejected): steps as an attribute of the agent (`:seon.agent/steps`): it is the plan again,
detached from the work queue the owner wants unified.

## 4. What gets deleted when this lands

| Item | Lines |
|---|---|
| `src/seon/plan.clj` minus moved issue code (about 130 lines move to `seon.issue`) | -1382 |
| `src/my/plan.clj` | -186 |
| `resources/seon/schemas/my.plan.edn` (keep the two error schemas by renaming into `seon.bootstrap`/`seon.task`; about 40 lines survive) | -265 |
| `my.plan.item.edn`, `seon.plan.edn` | -122 |
| Renders on `:seon.agent/plan`, `plan` attribute at `seon.agent.edn:38,60,208-211` | -10 |
| transcript plan reads (`render/transcript.clj:1774-1801` completions detail, `:2149-2162` per-step cost, `:2214-2223` text) | -30 |
| `cluster/agent.clj:240` plan seed; `cluster/status.clj:112` pull | -2 (status reads the task's current step instead: +1) |
| `bootstrap.clj:60` help line | rewritten, net 0 |
| Tests: `test/my/plan_test.clj` 614, `plan_api_test.clj` 136, `test/seon/contracts_plan_test.clj` 97, `plan_completion_test.clj` 72, `plan_test.clj` 45 | -964 |
| Plan refs in tests (`repl_parity`, `rereads`, `data_shapes`, `run6_stall`, `help_trial`, `transaction_result`, `render_simplification`, `issue_test`, `issue_settlement_test`, `loop_proof_test`, `agent_test`, `program_read_test`, `render_source_test`, `repl_grammar_test`): edit in place | about -100 |
| `docs/seon/specs/plan.md`, `my.plan` entries in `resources/seon/tasks/*.edn` naming plan symbols (`seon.test`, `seon.bootstrap`, `seon.error`, `seon.render.value`, `seon.fn`, `my.program`) | -103, tasks retargeted |
| Total deleted | about -3200 |

## 5. Callers to convert

- `turn.clj:24` require; `:2252`, `:3480`, `:3627` `[:db.fn/call #'plan/settle-call ..]` -> `#'issue/settle-call` (only the issue half survives); `:3461`, `:3583` `plan/run-issue-tests!` -> `issue/run-issue-tests!`; `:3346,:3357` `:seon.plan/refusal` -> `:seon.issue/refusal`.
- `note.clj:63,74,77,96,98`, `my.note.edn:32`: about-target `:my.plan.item/id` -> `:seon.task/id`; `note.clj:20` `:my.plan/request` -> `:my.note/request` (own schema); `agent.clj:19,33,45` -> `:my.agent/request`; `test.clj:1538`.
- `bootstrap.clj:108,121-122`, `cluster/agent.clj:389`: `:my.plan/agent-not-found-error`, `:my.plan/missing-agent-id`, `:my.plan/refused-member` -> `:seon.bootstrap/*` names.
- `cluster/agent.clj:240` (seed), `cluster/status.clj:112`, `render/transcript.clj:1774,2149-2162`, `seon.agent.edn:38,60,208-211`, `seon.db.edn:10` (`:my.plan/error` in a union).
- `my/program.clj:930,992`: doc examples naming `seon.plan`.
- Tests listed in section 4.

## 6. Line estimate and open questions

Added (about): schema `seon.task.edn` +25 (steps, step, refusal enum members), `seon.task` +60 (`task-steps-call`, `assigned-task`), `my.task` +70 (four functions with docstrings, contracts), `render/ns.clj` +25, moved issue code 0 net, tests +120 (one data test per function and per error alternative, compaction survival, assignment revision unchanged by a step write). Total about +300.
Deleted about -3200. Net about -2900 lines; plan.clj's 1512 lines were 83 functions for what is now 6.

Open questions for root:

1. Custody: steps live on the task at the task home (root's branch); should a worker's step write go home, or should steps be an agent-branch component of the assignment row and merge as data? Home is simplest and matches `written`; it puts N writes/turn on the cluster head (UNVERIFIED cost; measure).
2. Are there live plans with data worth keeping? DB data is disposable (memory ruling), so default: none migrated.
3. An agent with no task (root, an outside MCP agent): does it `create!` + `claim!` a self task first, or is `my.task/plan!` allowed to create an anonymous task under a derived handle? Recommend the first (no invented entity kind).
4. `create!` needs ns/name/kind/intent and `kind :function|:namespace|:schema|:merge`; a general "work" task has no fitting kind. Add `:work` to `:seon.task/kind`? That adds a value to the handle aspect (`seon.issue/task-handle`).
5. Probe before build (UNVERIFIED): component-set position ordering and cell-vector alternative; that `render/ns/task-ai` is reached from the agent's opening; cost of the assignment query under 50 ms on the live store; that no stored `:my.plan.item/id` reference exists elsewhere (`:my.note/about` rows).
6. Order: moving issue code and renaming the shared error/request schemas is a separate landing, first, and independently green.
