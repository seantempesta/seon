---
title: "Task verbs: catalogue and a smaller vocabulary for TASK-CHAIN (proposal for owner discussion)"
type: research
status: proposal for owner discussion
created: 2026-10-11
basis: HEAD fee847013; feeds TASK-CHAIN §8 and fable-replan §8 Q4/Q5 (root namespace)
---

# Task verbs: catalogue and proposal

What exists: task design and progression is spread over 9 namespaces (`seon.task`, `my.task`, `seon.turn`,
`seon.context`, `my.program`, `seon.cluster.source`, `my.agent`, `my.turn`/`my.message`, test `seon.competency`), with
about 26 verbs naming 12 concepts. Approach: list every function, count callers, map each word to AGENTS.md's words
table and `docs/seon/architecture/vocabulary.md`, then cut to the set the chain (Design+Prove → Implement → Land, owner
Q13) needs.

**Method.** Callers are cross-namespace call sites from a clj-kondo analysis of `src test script` at fee847013
(`s/t` = src/test sites; self = calls inside the defining namespace). Kondo misses `seon.turn`'s
`@(requiring-resolve …)` delays (`context-task-done?`, `program-proposal!`, `source-land!`, …) and
`seon.cluster.source`'s calls into the `seon.task` ingest functions; those are named as "via". "Root used" counts root's
evaluations on default's main mentioning the verb (944 evaluations since the last reset, JVM probe 74 ms); agent
branches were not counted. **R** = removed or rewritten by the Q15 fence revert (the 16 fence commits are 5261c17eb
14b8ea164 17138ccad 860220add 25979a693 4ff7249cd 2cc872997 0794ffc8b 36890687e 2a84e933a 67ff477d5 9d4884373 ab18c3ffc
7ab559153 fbc7454ec f540268a1); R items are excluded from the proposals.

## 1. Catalogue

### 1.1 Authoring and ingestion (platform)

| Symbol | What it does | s/t | Who | Word |
|---|---|---|---|---|
| `seon.task/create!` | upsert a runtime task from its declaration, refusing a stale `:seon.task/basis` | 1/29 | root (4), `my.task` | create |
| `seon.task/index-tasks!` | reconcile task EDN files through the writer against the read basis | via source/9 | publication | index |
| `seon.task/tasks-tx`, `adopt-tasks-tx`, `adopt-tasks-call` | file → stored-task delta; adoption into a development db | self, 0/4 | publication, `seon.cluster` | adopt |
| `seon.task/task-files`, `task-basis`, `task-revision`, `task-handle`, `task-sym` | read files; basis; revision digest; identity | via/2–9 | platform | — |
| `seon.task/task-findings`, `candidate-dangling` | tags naming no task | via/2–4 | publication | dangling |
| `seon.task/open-tasks` | task symbols a definition still lists ("listing is openness") | 0/3 | publication | open |
| `seon.task/task-edges` | subjects, proofs (deftests tagged), must-read referrers | 3/2 | context, turn, program | edges |
| `seon.task/home`, `home-connection` | the connection tasks live on (root's branch) | 9/1, 7/1 | everywhere | **home** |
| `seon.contracts/task` | the generated `contract-<fn>` task declaration | 0/– | root | task |

### 1.2 Assignment, queue and steps

| Symbol | What it does | s/t | Who | Word |
|---|---|---|---|---|
| `seon.task/assign!` | run the task's proofs in the agent's world, assign only if one is red | 2/32 | root (8), competency, `claim!` | assign |
| `seon.task/assignment-read` | what assigning reads at home (revision, proofs via `task-edges`) | self/1 | `assign!` | — |
| `seon.task/assignment` | what agent+branch is assigned (proofs, ns, key) | 1/2 | `my.program/proposal!` | assignment |
| `seon.task/agent-task` | handle of the task an agent holds (latest assignment) | 10/4 | turn, context, render | (holds) |
| `seon.context/held-task` (P) | the held task's declaration | self | `task-done?`, current-state | held |
| `my.task/held`, `own-task` (P) | held handle; else mint+assign a `:work` task | self | `my.task` | held, own |
| `my.task/open` | unassigned tasks answering faults or a signal | 0/3 | agents (help) | open |
| `my.task/claim!` | self-assign an open task through `assign!` | 0/2 | agents | claim |
| `my.task/plan!`, `step!`, `done!`, `steps` | replace/append/advance/read the held task's steps | 0/6,2,6,1 | agents (1 root eval) | plan, step, **done** |
| `seon.task/steps!`, `steps-call`, `steps-view`, `task-steps`, `step-summary` | step writer and reads | 0–2/0–1 | `my.task`, status, render | step |
| `seon.task/advance-assignments-tx` | move assignments to the declaration's revision | self | `create!` | advance |

### 1.3 Proofs, completion and closure

| Symbol | What it does | s/t | Who | Word |
|---|---|---|---|---|
| `seon.context/proofs` | proofs section (status per proof) for task sym on a branch | self 4/2 | current-state, `task-done?` | proofs |
| `seon.context/task-done?` | held task has proofs, all green, no reached red | via turn | `seon.turn/task-end` | **done** |
| `seon.turn/task-proofs` (P) | held task's proofs at home ∪ branch tags (execution selection) | self | `test-step` | proofs |
| `seon.turn/run-proofs`, `run-reaching`, `test-step` (P) | run proofs, then reaching tests, each pass | self | `seon.turn/step` | step |
| `seon.task/refuted` (P) | proofs red at a reach no green member shares ("red first") | self | `propose!` | **refuted** |
| `seon.task/resolution`, `task-resolution`, `resolution-selector` | why a task is closed: faults resolved, signal gone, merge outcome | 0–1/1–2 | `my.task/open`, queue test | closed-by |
| `seon.context/outcome-tx`, `branch-outcome`, `recorded-outcome` | agent's end summary (calls, tokens, proof counts) at home | via turn | `end-task!` | **outcome** |
| `seon.context/ended` | what an end message stands for (task, agent, end, merge) | 0/4 | root's block | ended |
| `seon.turn/reached-verdicts`, `record-reached!`, `merge-target`, `statuses-at-current-reach` | verdict tiers | self | turn | verdict — **R** |

### 1.4 Turn ends (`seon.turn`)

| Symbol | What it does | s/t | Who | Word |
|---|---|---|---|---|
| `seon.turn/task-end` | derive `:done` `:given-back` `:capped` `:stalled` on the branch | 0/3 | `end-task!` | end |
| `seon.turn/end-task!` (P) | propose on green, resume/land, write outcome, send end message, close admission | self/3 | `seon.turn/step` | end |
| `seon.turn/end-task-call` | close the agent's admission once | self 2 | `end-task!` | end |
| `seon.turn/end-message` (P) | the one end message to root (id = digest; f540268a1 **R**) | self/1 | `end-task!` | end |
| `seon.turn/end-proposal` (P) | proposal this end already recorded | self | `end-task!` | proposal |
| `seon.turn/land-end!`, `landing-tx` (P) | land a green end under `:seon.config.root/land?`; record result on merge task | self | `end-task!` | land |
| `seon.turn/unfinished-landings`, `landing-exception` (P) | resume stopped landings; catch-all translation | self | `end-task!` | **R** |
| `seon.turn/close-tx`, `close-call`, `open?` | a *turn's* open/close | 5/14 | turn | close, open |

### 1.5 Merge request and landing

| Symbol | What it does | s/t | Who | Word |
|---|---|---|---|---|
| `my.program/proposal!` (internal) | run proofs on branch, record merge task + proposal (`seon.task/propose!`) | via turn | `end-task!` | **proposal** |
| `my.program/propose-merge!` | same plus a message to root | 0/0 | agents (1 root eval) | propose |
| `seon.task/propose!` | write merge task `merge-<key>` and proposal at home | 1/0 | `proposal!` | propose |
| `seon.task/one-open-proposal-tx` (P) | `:proposal-open` refusal at the writer | self | `propose!` | **R** |
| `my.program/proposals` | open merge requests on my cluster | 0/4 | root (13) | proposals |
| `my.program/prepare-merge!` → `seon.cluster.source/prepare-merge!` | gate the proposal on a fresh merge branch | 0/1, 1/13 | root (5), `land!` | prepare |
| `my.program/accept-merge!` → `seon.cluster.source/accept-merge!` | merge tested program; `decide! :accepted`; retire tested and source branches | 0/0, 1/16 | root (5), `land!` | accept, retire |
| `my.program/write-back!` → `seon.cluster.source/write-back!` | write accepted declarations into files | 0/0, 2/12 | root (4), `land!` | write-back |
| `seon.cluster.source/land!`, `land-held!` (P) | prepare + accept + write-back + git commit in one call | via turn/12 | root (13), `land-end!` | **land** |
| `seon.cluster.source/run-on-target!`, `merge-task-accepted-tx`, `installed-tx`, `landed-commit`, `recorded-landing`, `recorded-accepted`, `write-and-commit!`, `blob-digest`, `uncommitted-paths`, `target-program-held-tx`, `program-changes`, `landing-refusal-reason`, `awaits-decision?`, `stuck-landings`, `blocking-landing`, `landing-paths`, `transient-refusals` | landing fences | self | land | **R** |
| `seon.task/decide!` | write root's outcome `:accepted`/`:declined` on a merge task | 1/1 | root (1), `accept-merge!` | **decide** |
| `my.agent/retire-branch!` | close an agent and remove its branch from the roster | 0/5 | root (33), `accept-merge!` | **retire** |
| `seon.cluster.source/retire-scratch!` (P) | delete a scratch branch | self | source | retire |

### 1.6 Launching, grading, and messages that carry progression

| Symbol | What it does | s/t | Who | Word |
|---|---|---|---|---|
| `seon.competency/launch!` (test) | run cells × runs in parallel, await, grade, retire | –/1 | root (1) | launch |
| `seon.competency/start-run!` (P, test) | `own-branch-agent!` (forks main) → `assign!` → outside wake | self | root by hand (16) | start-run |
| `seon.competency/run-state`, `await-runs` (P) | poll runs to a terminal state | self | `launch!` | run |
| `seon.competency/grade`, `table`, `per-run` | grade one run from its branch; one row per cell | –/2 | root | grade |
| `my.message/send` → `seon.cluster.message/send!` | send a message (end messages, replies) | 0/4, 6/3 | agents, `end-task!` | send |
| `my.message/decline` → `seon.cluster.message/decline` | decline an *assignment* to its sender | 0/0, 0/10 | agents | **decline** |
| `my.turn/wait` | pause until an outside wake | 0/0 | agents | wait |
| `my.turn/complete` | completion reply; makes `task-end` `:given-back` | 0/0 | agents | **complete** |
| `my.agent/done` | end my session (= `my.turn/wait` "Session complete.") | 0/0 | agents | **done** |

Not existing, but named as targets: `my.task/complete!` and `my.agent/answer!` (AGENTS.md), `my.task/refine!` and
`my.task/send-back!` (TASK-CHAIN §6, §8.1.4), `seon.task/current` (the top guessed name, 58 occurrences, §6b).

## 2. Analysis

### 2.1 One concept, several verbs

| Concept | Verbs today | Count |
|---|---|---|
| finishing | `my.task/done!` (a *step*), `my.agent/done` (a session), `seon.context/task-done?` (proofs green), `task-end :done`, `my.turn/complete` (gives back), `end-task!`, `end-task-call`, `close-tx` (a turn) | 8 |
| asking to merge | `proposal!`, `propose-merge!`, `propose!`, `proposals`, `:seon.task/proposal`, "merge task" | 6 |
| deciding a merge | `accept-merge!`, `decide!`, `land!`, `land-held!`, `land-end!`, `:declined` | 6 |
| getting a task | `assign!`, `claim!`, `own-task` (mint), `assignment`, `assignment-read`, `agent-task`, `held-task`, `held` | 8 |
| which proofs count | `task-edges` `:proofs`, `seon.turn/task-proofs`, `seon.context/proofs`, `assignment-read`, `assignment` | 5 selections (Astra: one stage-aware selection) |
| removing a branch | `retire-branch!` (my.agent and registry), `retire-scratch!`, accept-merge's detached retire | 3 |
| "outcome" | `:seon.task/outcome` (root's merge decision) and `seon.context/outcome-tx`/`:seon.agent/outcome-*` (an agent's end summary) | 2 meanings |
| "decline" | `my.message/decline` (an agent refuses an assignment) and `decide! :declined` (root refuses a merge) | 2 meanings |
| "step" | task steps, `seon.turn/step` (one pass), `test-step`, two `step-limit-ms` (seon.task P, seon.turn) | 3 meanings |
| "open" | `my.task/open` (unassigned queue), `open-tasks` (tag listed), `seon.turn/open?` (turn), open proposal | 4 meanings |

### 2.2 Words that conflict with the agreed words

| Word in code | Agreed word (source) | Where |
|---|---|---|
| home, task home | **main** ("home" retired, vocabulary §merge) | `seon.task/home`, `home-connection`, every docstring |
| proposal, propose | **merge request** ("proposal, candidate" retired) | `proposal!`, `propose-merge!`, `propose!`, `proposals`, `:seon.task/proposal*` |
| tested branch, candidate | **merge branch** | `accept-merge!`, `land!` docstrings |
| retire a branch | **delete branch** ("retire" is for agents only) | `my.agent/retire-branch!`, `retire-scratch!` |
| done (a step) | a task **completes** when its proofs are green (vocabulary "proof") | `my.task/done!` |
| complete (give back) | AGENTS.md: `my.task/complete!` *gives back*; vocabulary: *completes* = green | `my.turn/complete`; the target name itself conflicts |
| refuted | red first (owner ruling: "proven only red first") | `seon.task/refuted`, `:seon.task/refuted` |
| land, landing | not in either table (git: merge, then write-back, commit) | `land!`, `land-end!`, `:seon.task/landed*` |
| decide, outcome `:accepted/:declined` | git/GitLab: a merge request is **merged** or **closed** | `decide!`, `:seon.task/outcome` |
| steps, plan | **shelved** 2026-10-08 ("tasks replace plan/steps; nothing new uses them") | all of 1.2's step rows |

### 2.3 Verbs with no live producer or caller

- `my.task/open` and `my.task/claim!` read tasks carrying `:seon.task/faults` or `:seon.task/signal`; **nothing writes
  either attribute** (only `seon.task`/`my.task` mention them). The queue is always empty; `resolution`'s first two
  clauses only ever run for the merge clause.
- `my.task/plan!`, `step!`, `done!`, `steps`, and `seon.task/steps!` and its reads: shelved, test-only; `:work` kind
  exists only for `own-task`'s minting.
- `my.program/propose-merge!`, `my.program/write-back!`, `my.agent/done`, `my.message/decline`: no src callers; ends
  use `proposal!`, `land!` writes back, `done` duplicates `my.turn/wait`.
- `seon.task/decide!`: one src caller (`accept-merge!`); root declined by hand once.

### 2.4 The lifecycle as data

| State | Stored where | Written or derived by |
|---|---|---|
| declared | `resources/seon/tasks/<ns>.edn` (718 entries: 656 `:function`, 57 `:namespace`, 5 `:schema`) → task entity at main | `index-tasks!` (publication) |
| created | task entity + `:seon.task/created-by` at main | `create!` |
| open | not stored: authored = a definition lists its tag (`open-tasks`); fault/signal = `resolution` nil (no producer) | derived, two rules |
| assigned | assignment entity (`:seon.task/assignment` key, `:assigned`, `:agent`, `:revision`, `:branch`) at main; red proof run on the agent's branch | `assign!` |
| working | the agent's turns and test runs on its branch | turn |
| ended | **derived** `task-end` on the branch; then `:seon.agent/admission-closed-tx` (branch), `:seon.agent/outcome-*` (main), end message `:seon.task/end` | `end-task!` (three writes, not one transaction) |
| merge requested | merge task (kind `:merge`, `merge-<key>`) + proposal entity (`:proposes`, `:commit`, `:proved`, `:refuted`, `:run-id`, `:program`) at main | `propose!` |
| merged / refused | `:seon.task/outcome :accepted`, `:landed`, `:landed-git`; `:refusal`, `:refused-tests` (+ R: `:accepted-tx`, `:installed`, `:refusal-reason`, `:blocked-by`, `:verdicts`, `:refused-run`) | `land!` + `landing-tx`, `decide!` |
| closed (merge) | `:seon.task/outcome` | `decide!` → `resolution` `:closed-by :seon.task/outcome` |
| closed (work task) | **nothing**: proofs green on main never close an authored task unless write-back drops its tag | gap; task `seon.task/open-tasks-whose-proofs-are-green-are-never-retired` |
| agent/branch gone | roster removal | `retire-branch!` by hand (33 root evals), `accept-merge!` |

Two openness rules, one stored outcome attribute used only for merge tasks, and the work task's success never written
or derived at main: the chain needs exactly the missing one (green on main) and a stage field.

## 3. Proposal: the smallest vocabulary for the chain

Three faces, each with one reason to change. Agents speak about **their task**; root speaks about **merge requests and
launches**; the platform owns the writes. Contracts are sketches over existing schema names where they exist.

### 3.1 Agent side, `my.task` (3 verbs; plus `my.message/send`, `my.turn/wait` unchanged)

| Verb | Contract sketch | Replaces |
|---|---|---|
| `my.task/current` | `[:=> [:cat :seon.task/agent-request] [:or :seon.task/held-view :seon.task/refused-error]]`: declaration, stage, the stage's proofs with status and failing data, decisions, offered operations | `my.task/steps`, `seon.context/held-task` as a read, the guessed `seon.task/current` |
| `my.task/refine!` | `[:=> [:cat [:map :seon.task/basis [:seon.task/note string?] …any of intent, must-read, evidence, :seon.task/decisions]] [:or :seon.task/declaration :seon.task/conflict-error]]`; `create!`'s upsert at main, agent as provenance | the missing handoff medium (§6 item 1); the stage decision is a field it writes, no separate verb |
| `my.task/give-back!` | `[:=> [:cat [:map [:seon.task/reason string?] [:seon.task/target {:optional true} :seon.task.stage/name] [:seon.task/counterexample {:optional true} any?]]] [:or :my.turn/wait :seon.error/value]]`; writes the `:send-back` decision, ends the turn; `task-end` derives `:given-back` | `my.turn/complete` (as give-back), Fable's `send-back!`, AGENTS' `my.task/complete!`, `my.message/decline`, `my.agent/done` |

Success has no verb: proofs green is the end (`task-end :done`), as AGENTS.md rules.

### 3.2 Root side, new namespace `my.root` (instructions + required tools)

| Holds | Contract sketch / source |
|---|---|
| `my.root/instructions` | data rendered first in root's current-state block: the laws root applies (review before merge, Q8), the chain order, the hand procedures (decline, delete ended branches with a receipt, Q6) |
| `my.root/launch!` | `[:=> [:cat [:map :seon.task/sym [:seon.agent/settings map?] [:seon.task/basis-commit {:optional true} :seon.db/commit-id]]] [:or :seon.task/assignment-view :seon.error/value]]`; fresh agent on a branch at the predecessor's commit (Astra), `assign!`, first wake. From `seon.competency/start-run!` minus test-support; `launch!`/`grade`/`table` stay the bench and call it |
| `my.root/merge-requests` | open merge requests (from `my.program/proposals`) |
| `my.root/review` | `[:=> [:cat :seon.task/merge] :seon.source/diff]`: the merge request's diff since its merge base, plus the gate verdict (`seon.cluster.source/prepare-merge!`), for Q8's review |
| `my.root/merge!` | `seon.cluster.source/land!` with root's world: gate, `merge!`, write-back, git commit; then `seon.task/close!` `:merged` |
| `my.root/close!` | decline a merge request with a reason: `seon.task/close!` `:closed` |
| `my.root/ended` | end messages in root's inbox as `seon.context/ended` values (task, agent, end, merge request) |
| requires | `seon.task :as task` (`create!`, `index-tasks!`, `assign!`, `resolution`), `seon.contracts` (`task`), `my.program` reads (`search`, `callers`, `source`) |

### 3.3 Platform, `seon.task` (writes and the one selection)

| Function | Contract sketch | Note |
|---|---|---|
| `create!`, `index-tasks!`, `assign!`, `task-edges`, `agent-task` | as today | `assign!` gains the stage-aware selection and the basis commit |
| `seon.task/proofs` (new) | `[:=> [:cat :seon.db/database-value :seon.task/handle] [:map [:seon.task/red [:set :symbol]] [:seon.task/green [:set :symbol]]]]` | Astra's one stage-aware selection: Prove wants proofs red, Implement green. Used by `assign!`, `seon.turn/test-step`, `seon.context/proofs`, `task-done?`; deletes `seon.turn/task-proofs` and the selection in `assignment-read` |
| `seon.task/request-merge!` | rename of `propose!` | vocabulary: merge request |
| `seon.task/close!` (new) | `[:=> [:cat :seon.db/connection :seon.task/merge [:enum :merged :closed] [:maybe string?]] [:or :seon.task/task :seon.error/value]]`: outcome, delete the merge branch and the source branch (closing its agent) | Q6's one close owner, called by `land!`/`accept-merge!` and `my.root/close!`; replaces `decide!` and the detached retire in `accept-merge!` |
| `seon.task/resolution` | + `:closed-by :seon.task/proofs` when every proof is green on main | §8.1.3 / Astra: closure for work tasks |
| `seon.turn/end-task!`, `task-end`, `land-end!` | stay in `seon.turn`; `end-task!` becomes message + admission close in one transaction (Q15) and gains the stage advance (Q13 phase 2) | |

## 4. Old → new

| Old | New | Action | Reason |
|---|---|---|---|
| `my.task/open`, `my.task/claim!` | — | delete | no producer of fault/signal tasks; chain assigns by launch |
| `my.task/plan!`, `step!`, `done!`, `steps`; `seon.task/steps!`, `steps-call`, `steps-view`, `task-steps`, `step-summary`, `:seon.task/steps`, `:work` kind | — | delete | shelved 2026-10-08; "done" collides with completion |
| `my.task/steps` (read), `seon.context/held-task` (as public read) | `my.task/current` | merge into | one read of my task; the guessed name |
| — | `my.task/refine!` | new | handoff medium (TASK-CHAIN §6.1) |
| `my.turn/complete` (give-back use), `my.agent/done`, `my.message/decline`, `send-back!` (proposed) | `my.task/give-back!` | merge into | four ways to stop without green; one, with a target and data |
| `my.turn/complete` (requester reply) | `my.message/send` + `my.turn/wait` | merge into | AGENTS.md: a reply is send then wait |
| `seon.context/task-done?`, `seon.turn/task-proofs`, proof selection in `assignment-read` | `seon.task/proofs` | merge into | one stage-aware selection (Astra) |
| `seon.task/propose!`; `my.program/proposal!` | `seon.task/request-merge!`; `my.program/request-merge!` (internal) | rename | "proposal" retired |
| `my.program/propose-merge!` | — | delete | no caller; ends request the merge |
| `my.program/proposals` | `my.root/merge-requests` | move+rename | root's read |
| `my.program/prepare-merge!`, `accept-merge!`, `write-back!` | `my.root/review`, `my.root/merge!` | merge into | root reviews then merges; the steps stay in `seon.cluster.source` |
| `seon.cluster.source/land!` | keep; root calls it as `my.root/merge!` | rename at root | "land" is not an agreed word; git says merge |
| `seon.task/decide!` | `seon.task/close!` | merge into | one close owner (Q6); outcome `:merged`/`:closed` |
| `my.agent/retire-branch!` (for branches) | `seon.task/close!`; by hand `my.agent/delete-branch!` | rename | "retire" is for agents; branches are deleted |
| `seon.competency/start-run!` | `my.root/launch!` | move to src | owner Q5 |
| `seon.competency/launch!`, `grade`, `table` | keep (test bench) | keep | bench composes `my.root/launch!` |
| `seon.task/home`, `home-connection` | `seon.task/main`, `main-connection` | rename | "home" retired |
| `seon.task/refuted`, `:seon.task/refuted` | `red-first`, `:seon.task/red-first` | rename | owner ruling's words |
| `seon.context/outcome-tx`, `:seon.agent/outcome-*` | `end-summary-tx`, `:seon.agent/end-*` | rename | "outcome" is root's merge decision |
| `seon.turn/end-task!`, `task-end`, `end-task-call`, `end-message`, `land-end!` | keep | keep | the one transition point (§8.1.3) |
| `create!`, `index-tasks!`, `assign!`, `task-edges`, `agent-task`, `resolution`, ingest functions | keep | keep | |
| every **R** row | — | excluded | the revert lane owns them |

Net: agent task verbs 6 → 3, root's 9 scattered tools → 7 in one namespace, three proof selections → 1, two close paths
→ 1; about 450 lines of steps/queue code and schema retire (`my.task` 194 lines, the step half of `seon.task`).

## 5. Decisions for the owner

1. **Give-back word.** One `my.task/give-back!` (reason, optional target stage, counterexample) covering send-back,
   give-back, assignment decline and session end, *or* AGENTS.md's `my.task/complete!`. Recommend `give-back!`:
   "complete" means proofs green everywhere else, and the end value is already `:given-back`.
2. **Delete the queue and steps.** `my.task/open`/`claim!` (no producer) and all steps (shelved) go, with
   `resolution`'s fault/signal clauses kept only if the errors cut will write `:seon.task/faults`. Recommend delete all
   now; the errors cut re-adds its clause with its producer.
3. **Root namespace name and scope.** `my.root` (supplied world like every `my.*`; recommended) or `seon.root`; holding
   instructions + `launch!`, `merge-requests`, `review`, `merge!`, `close!`, `ended`, with `my.program`'s merge
   wrappers deleted.
4. **Merge words.** Outcome `:merged`/`:closed` and verbs `request-merge!`/`merge!`/`close!` (git and the vocabulary), or
   keep `:accepted`/`:declined` and "land". Recommend git words; the rename is mechanical and one commit.
5. **When to rename.** `home` → `main`, `refuted` → `red-first`, `outcome-*` → `end-*` now (one scripted sweep before
   the chain build) or after the first two-agent experiment. Recommend now for the three task verbs and the root
   namespace (they are new surface agents learn), the attribute renames after the experiment.

**Not verified:** agent-branch usage of `my.*` verbs (only main's 944 evaluations counted); kondo misses delayed
`requiring-resolve` calls, so "via" rows were read by hand; the line estimate in §4 is from file sizes, not a diff.
