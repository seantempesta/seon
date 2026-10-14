---
title: "Doc inventory: skills, help, my.* docstrings"
type: research
status: done
created: 2026-10-14
---

# Doc inventory: skills, help, my.* docstrings (lane inv-skills)

Read-only pass against TASK-CHAIN (READY 2026-10-14, `docs/seon/schedule/cards/TASK-CHAIN.md`) and plan §0 (4e70ff8c7).
Each stale claim is tagged **stale-target** (describes the TASK-CHAIN target wrongly) or **installed-until-S<n>**
(accurate today; TASK-CHAIN will change it). Code claims were checked with rg against `src/`, `test/`, `script/`.

## Totals

- Files in scope: 23 (22 Markdown under `.agents/skills/`, plus `resources/seon/help.md`), plus the `src/my/` docstrings.
- Verdicts: live and correct 18 · needs edits 5 (`repl/SKILL.md`, `clojure-testing/SKILL.md`,
  `data-oriented-clojure/SKILL.md`, `datastar-web-ui/references/design-principles.md`, `resources/seon/help.md`) ·
  superseded whole 0 · done historical 0.
- Docstrings with stale claims: `src/my/task.clj` (4 sites), `src/my/root.clj` (5 sites). Tag metadata on four `my.*` vars (below).
- Depth: `help.md`, `repl/SKILL.md` (agent-branch section), `clojure-testing/SKILL.md` (§2.11–2.12),
  `data-oriented-clojure/SKILL.md` (§9.3–9.4, §13, its operational checks), TASK-CHAIN and plan §0 read in full.
  The other 18 files were searched for every superseded term (relaunch, children, needs, effective, obligations, goal-owner,
  product, intent, refine!, `:seon/tasks`, step, Survey, Define, chain) and every relative link resolved; none of them
  describes task lifecycle, so their verdicts rest on the grep, not a line-by-line read.

## Brief correction: send-back! is live

The brief lists `my.task/send-back!` as superseded. TASK-CHAIN keeps it: §2.7 (bail: `:sent-back` with its reason) and
§4 Implement's instructions use it. Do not delete it. Its docstring is live (see below).

## Installed-but-superseded terms, by owner

These are installed today; each goes away in the slice shown. No skill or docstring cites `relaunch`, `allowance`,
`children`, `needs`, `effective`, `obligations`, `goal-owner` or `:seon.task/product` as a claim (verified by rg).

| Installed form | Where it is installed | Converted in |
|---|---|---|
| `:seon.task/intent` | `src/seon/context.clj:584`, `src/my/task.clj:48` | S1a rename (script `script/seon/rename_task_intent.clj`) |
| `my.task/refine!` | `src/my/task.clj:45` | S1d-2 → `my.task/update!` (TASK-CHAIN §3a) |
| `:seon/tasks` tags | `src/my/agent.clj:200`, `src/my/background.clj:67`, `src/my/edit.clj:3`, `src/my/program.clj:278` | S2c-2 → `:seon.task/proofs` field (§1, §6) |
| `seon.task/assign!` (root launches) | `src/seon/task.clj:881` | S1c-2 `chain!` / S3a-1 (§2.1–2.2) |
| merge request per assignment, `merge!`, `merge-requests`, `ended` | `src/my/root.clj:61–140` | S3b-1 (§2.6: a chain merges once from its branch) |
| `seon.context/chain` as the subject's steps | `src/seon/context.clj:235–254` | S3a-2 (`next` path) and S3e |
| `seon.task/product` | `src/my/task.clj:50,73` | §6 deletion, with its last reader |
| `seon.contracts/task` (generated tasks) | `test/seon/step_test.clj`, `data-oriented-clojure/SKILL.md:361` | §6 Tags deletion (decision needed, see below) |

## Per file

| Path | Verdict | Notes |
|---|---|---|
| `resources/seon/help.md` | needs edits | Items 1–9 below. Its reply keys and references were checked against src. |
| `.agents/skills/repl/SKILL.md` | needs edits | Item R1–R4 below. Lines 1–120 and the agent-branch section read; line 120 onward read fully. |
| `.agents/skills/clojure-testing/SKILL.md` | needs edits | Item T1–T2 below. |
| `.agents/skills/data-oriented-clojure/SKILL.md` | needs edits | Item D1–D2 below. |
| `.agents/skills/datastar-web-ui/references/design-principles.md` | needs edits (minor) | Line 186 "(agent, namespace, turn, plan step)": word drift; "plan step" is the step vocabulary TASK-CHAIN §6 retires. Change to "task". stale-target wording, no code claim. |
| `.agents/skills/data-modeling/SKILL.md` | live and correct | Its "target" and "installed" labels are explicit; no task claim. |
| `.agents/skills/datahike/SKILL.md` | live and correct | grep only; no task claim. |
| `.agents/skills/datahike/references/querying.md` | live and correct | grep only. |
| `.agents/skills/datahike/references/fork-maintenance.md` | live and correct | grep only. |
| `.agents/skills/datastar-web-ui/SKILL.md` | live and correct | grep only. |
| `.agents/skills/llm-providers/SKILL.md` | live and correct | grep only. |
| `.agents/skills/seon-context-config/SKILL.md` | live and correct | grep only. |
| `.agents/skills/seon-flow-architecture/SKILL.md` | live and correct | Agent context ownership section is labelled "Installed" and "[TARGET]" correctly. |
| `.agents/skills/seon-flow-architecture/references/wakes-and-faults.md` | live; installed-until errors cut | `seon.fault/fault!` is installed (`src/seon/fault.clj`); the errors cut retires `fault` (CLAUDE.md errors). Not a TASK-CHAIN item. |
| `.agents/skills/seon-flow-architecture/references/agent-graphs.md`, `decisions.md`, `degraded-start.md`, `render-delivery.md`, `workloads-and-scheduling.md` | live and correct | grep only. |
| `.agents/skills/ui-canvas/SKILL.md` | live and correct | grep only. |
| `.agents/skills/codex-lanes/SKILL.md` | live and correct | "relaunch" here is lane relaunch, unrelated to task relaunch. |
| `.agents/skills/clojurescript/SKILL.md` | live and correct | Historical by design; grep only. |
| `.agents/skills/data-oriented-clojure/references/program-state.md` | live and correct | Cited PRD exists. |

### help.md (`resources/seon/help.md`), item by item

Its section "Your task" and "How work proceeds" teach the copies-era model. Claim: quoted under 25 words.

1. "Your task" · `:seon.context/task holds :seon.task/intent, the goal every step of your chain shares` ·
   TASK-CHAIN §1 (`:seon.task/plan` is the flowing description; no goal shared by steps) · installed-until-S1a (key) and stale-target (the "goal every step shares" wording).
2. "Your task" · `:seon.context/chain names the subject, the goal's owner` · TASK-CHAIN §1 (no subject task), §3a (chain = tasks before and after through `next`) · installed-until-S3a-2 (`context.clj:235` walks children); the subject wording is stale-target.
3. "Your task" · `the steps before you, each with what its merge moved under :seon.context/merged` · TASK-CHAIN §2.6 (a chain merges once) · stale-target.
4. "Your task" · `A step after you starts from main once you merge.` · TASK-CHAIN §2.2 and §2.5 (the next task starts on the same chain branch) · stale-target.
5. "How work proceeds" step 1 · `(my.program/search :seon.task/intent)` · installed-until-S1a.
6. "How work proceeds" step 3 · `refused when your step is written` · "step" vocabulary, TASK-CHAIN §6 · stale-target wording.
7. "Write what you learn" · `(my.task/refine! {:seon.task/intent …})` and `(my.task/refine! {:seon.task/instructions …})`, "rewrites the goal for every step of your chain" · TASK-CHAIN §3a (`my.task/update!`, §1 flowing plan) · installed-until-S1d-2 (refine!) and S1a (intent); "goal for every step" is stale-target.
8. "When you cannot" · `record it with refine!` · installed-until-S1d-2.
9. "How work proceeds" · `When your step is done, everything on your branch merges onto main by itself … Then the step after you starts from main. There is no done call and no merge call.` · TASK-CHAIN §2.4 (the turn proposes `:done`, root re-judges), §2.5 (advance on the same branch), §2.6 (one merge at the chain's end) · installed-until-S3a-3 and S3b-1; "step" and "merges … by itself" are stale-target.
10. "Each reply is data" · `:ms and :alloc-mb` and bare `:value :error :findings :result` · installed keys are `:seon.repl/ms`, `:seon.repl/alloc-mb`, `:seon.repl/value` … (`src/seon/repl.clj:85–100`). The printed reply is `#:seon.repl{…}`, so the bare names read as shorthand. Owner decision: name the keys in full.
11. "Your task" · `(my.task/send-back! …)` example text "the intent asks for the new one" · installed-until-S1a (string only).

Live and correct: "When you cannot" (`my.task/send-back!`, `src/my/task.clj:103`, kept by TASK-CHAIN §2.7); "Data" (`seon.schema/register!`, `my.program/ns-unmap!`, `my.program/source`, `my.program/search`, `seon.render/operations`, `:seon.context/start`, `:seon.context/proof-status`, `:seon.context/must-reads`, `:seon.context/send-backs` all exist in src); the reference syntax (`src/seon/ref.clj`). `(doc …)` and `(dir …)` come from `clojure.repl` (`src/seon/bootstrap.clj:179,184`). The help is installed as a file (`src/seon/bootstrap.clj:40`, `help-value`), so S1d-3 has landed.

### repl/SKILL.md

- R1. §"An agent works on its own branch", item 5: "Root assigns the task (`seon.task/assign!`)" · TASK-CHAIN §2.1–2.2 (`chain!` creates; root starts an agent per task) · installed-until-S3a-1; the target verb is `seon.task/chain!`.
- R2. Item 5: "the agent writes a deftest naming it by symbol (`^{:seon/tasks #{'ns/task-name}}`)" · TASK-CHAIN §1, §6 (tags deleted; proofs are the task's `proofs` field) · installed-until-S2c-2; the sentence's model is stale-target.
- R3. Item 5: "A merge request is the assignment … `my.root/merge!` … closes `:merged`" · TASK-CHAIN §2.6 (a chain merges once from its branch, found by its chain fact), §6 (per-step merge deleted) · installed-until-S3b-1.
- R4. Item 5: "Only the proofs gate acceptance" · TASK-CHAIN §2.4 (done rule over every task) and §2.6 (the last task's proofs gate the final merge) · installed-until-S2b-2; wording stale-target.
- Last paragraph: "Root assigns the worker its task on that branch with `seon.task/assign!`" · same as R1.
- Live: `my.agent/create-branch!`, `my.agent/branches`, `my.agent/retire-branch!` (`src/my/agent.clj`), `seon.cluster.registry/retire-branch!` (`src/seon/cluster/registry.clj:495`), `seon.cluster.agent/submit-source!` (`src/seon/cluster/agent.clj:695`), `seon.test/run` and `seon.turn/settle!` (grep only, not verified line by line).

### clojure-testing/SKILL.md

- T1. §2.12 · "`(deftest ^{:seon/tasks #{'ns/task-name}} …)`: the deftests naming a task are its success tests" · installed-until-S2c-2. Target: the task's `:seon.task/proofs` field (TASK-CHAIN §1, §6).
- T2. §2.12 · "TARGET (agent-loop.md §1.7, §3.5) … a refactor task needs one proof that fails before it (how such tasks name that proof is an open owner question)" · TASK-CHAIN §4 Prove (the next task's proofs are red by design, enforced by `:not-designed-red`) and §2.4 (`:stray-red`). The target now lives in TASK-CHAIN; the open owner question is answered there. stale-target.
- Also §2 intro line 38 "A task names its success tests before it is assigned (§2.12)" · installed-until-S3a-1; the concept stays.
- Live: §2.11 (`test/my/examples_test.clj` removed) and "proofs already green end the attempt … (`seon.turn/prove-turn`)" (`src/seon/task.clj:889`, `src/seon/turn.clj:2222`), matching TASK-CHAIN §2.3.

### data-oriented-clojure/SKILL.md

- D1. §9.4 "Link a task with `:seon/tasks` metadata" · "Today the set holds task handles … TARGET (lane task-tags): it holds `ns/task-name` symbols." · TASK-CHAIN §0 owner ruling and §6: tags are deleted entirely; the target is the `:seon.task/proofs` field, not a tag set. stale-target (the TARGET sentence); the "today" half is installed-until-S2c-2. Replace §9.4 with the proofs field.
- D2. Line 361 "uncontracted functions are back-filled as generated tasks (`seon.contracts/task`)" · TASK-CHAIN §6 Tags lists "generated-task consumers (`seon.contracts/task`)" among deletions · installed-until-S2c-2; the owner must decide whether the back-fill generator survives (it is named for deletion in §6, but contracts still need a backlog path). Raise as a question, not an edit.
- Live: §9.3 and §13.1 (task references `ns/task-name`), §9.2 (no dates), and the operational check "record every finding … as a task (`seon.task/create!`)" (`src/seon/task.clj` has `create!`; TASK-CHAIN §1 keeps it, dropping only its separate name check).

## my.* docstrings (public, `src/my/`)

Quoted under 25 words each. Words searched: task, chain, proof, refine!, send-back!, merge, help, intent, step.

**src/my/task.clj**

1. Ns docstring, lines 2–5 · "its goal or my step's instructions rewritten as the work clarifies them (`refine!`)" · stale-target (TASK-CHAIN §3a: `my.task/update!`); installed-until-S1d-2. Also "my step" (§6) and "Success has no verb: my task ends when its proofs are green" (TASK-CHAIN §2.4: the turn proposes `:done`; root judges).
2. `current` docstring, lines 22–27 · "for a Prove step, the product I owe" · stale-target; `:seon.task/product` is deleted (§6). "my step as judged" is stale-target (§2.4: the block shows the task's own done refusals).
3. `refine!` docstring, lines 46–53 · "Rewrite my chain's goal or my step's own work on my branch; the merge carries it to main." · stale-target. The whole function is deleted in S1d-2 (§3a, §6: "refine! and its goal-owner routing are deleted"). Its routing text (`seon.task/goal-owner`, `seon.task/effective`, `:seon.task/product`) is stale-target as well. Its examples (lines 59–60) use `:seon.task/intent`, installed-until-S1a.
4. `send-back!` docstring, lines 104–112 · "Give my task back without green proofs." · **live** (TASK-CHAIN §2.7). Its example (line 117) says "the intent asks for the new one", installed-until-S1a.

**src/my/root.clj**

5. Ns docstring, lines 2–3 · "the open merge requests, merging one, closing one without merging" · installed-until-S3b-1; stale-target as the model (TASK-CHAIN §2.6: a chain merges once).
6. Laws, line 21 · "each step is its own task with its own proofs" · the wording "step" is retired (TASK-CHAIN §6, §2): say "each task in the chain has its own proofs". stale-target wording.
7. Laws, line 22 · "A merge request is an assignment … Merge one with (my.root/merge! {:seon.task/assignment key})" · installed-until-S3b-1; TASK-CHAIN §2.6 keeps `merge!` but its source is the chain branch found by the chain fact, not an assignment row.
8. Laws, line 24 · "(my.root/ended) shows each with its proofs, calls and merge request" · installed-until-S3b-1 (`merge request` → chain attempt, §2.8).
9. `merge-requests` docstring, lines 62–64 · "every assignment with no outcome yet (`seon.task/close!`, `seon.merge/merge-tx`)" · installed-until-S3b-1; the target enumerates chain attempts on their branches (§2.8).
10. `ended` docstring, lines 138–140 · "its proofs, the agent's calls and errors, and its merge request" · installed-until-S3b-1.

Not stale: `merge!` lines 94–100 "gated by the proofs and the tests reaching the change" matches §2.6's gate; the publication wording becomes stale only in S3b-2.

**Tag metadata on public `my.*` vars (not docstrings, listed for the S2c-2 sweep)**: `src/my/agent.clj:200` (`retire-branch!`), `src/my/background.clj:67`, `src/my/edit.clj:3` (ns), `src/my/program.clj:278`. Installed-until-S2c-2.

Not checked in depth: `src/my/message.clj:62` uses "intent" for a message field, which is a different concept from a task's intent; no action unless the owner wants the word retired everywhere (CLAUDE.md "Words").

## Owner decisions needed

1. `seon.contracts/task` generated-task back-fill (D2): keep as a backlog path, or delete with the tags per TASK-CHAIN §6?
2. Reply key names in `help.md` (item 10): bare `:value` or full `:seon.repl/value`?
3. Confirm `my.task/send-back!` stays (brief said superseded; TASK-CHAIN keeps it).
