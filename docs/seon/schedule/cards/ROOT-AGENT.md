---
title: "ROOT-AGENT: the self-improvement loop run by Seon agents"
type: card
status: proposed
created: 2026-10-10
---

# ROOT-AGENT

What exists: every link of the loop is a function a Claude orchestrator calls by hand through MCP as agent `"root"`.
`seon.turn/end-task!` already records a green agent's proposal (`my.program/proposal!`), sends `"root"` one message
carrying `:seon.task/end` and `:seon.task/merge`, and closes the agent; `seon.context/ended` derives the view at render
and offers `(my.program/prepare-merge! …)`; `seon.cluster.source/accept-merge!` holds the 2026-10-10 gate (every run
member green, program unchanged since the run, every added function reached and contracted); `write-back!` writes files
and commits nothing; `bin/lane commit` (bash) commits; `seon.operator/move-to-head!` (an outside Babashka process)
restarts; `seon.schedule` fires root-owned maintenance Vars on cron; a launch is `seon.task/assign!` plus one wake,
composed only in `test/seon/competency.clj`; usage lives on attempt entities (`:seon.ai.usage/*`) and prices on model
entities (`:seon.ai.model/*-usd-per-mtok`). The Clojure approach: the duties that need no judgment become functions at
the seam that already sees their data (the green merge in the end settlement, launching and the spend report in the
schedule proc), and the duties that need judgment become **root tasks**: ordinary task entities whose proofs are system
invariants (red while the duty is undone) and whose `:seon.task/grants` is the data that opens host evaluation and
merge decisions to the fresh DeepSeek agent that holds them. No second permission system, no root model loop, no new
store. Owner rulings (2026-10-10): auto-merge on green; a DeepSeek root agent handles root's requests, platform work
included, through root-specific tasks and a root system context; agents triage the backlog; no spend cap, daily
tokens and cost.

Cost today (plan §0, loop round 1): prepare 4–6 s / 1–3 GB per one-function merge, accept 1.0 s / 1.4 GB, write-back
0.2 s, commit by `bin/lane` about 2 s, restart readiness 5–9 s. The loop's per-landing cost is those numbers; this card
moves who runs them, not what they cost (merge cost is filed).

## 1. Root's role and context

**Decision: `"root"` stays what it is; the model works root tasks as fresh agents.** The agent `"root"` (branch
`:cluster-default`, `seon.cluster/root-agent-id`, seeded by `seon.cluster/seed-root-agent!`) remains the task home
(`seon.task/home` finds tasks through root's `:seon.agent/branch`), the recipient of every end message
(`seon.turn/end-message`), the owner of the maintenance portfolio (`seon.schedule/root-maintenance-portfolio`) and
the orchestrator's MCP identity (`seon.mcp/eval-clj` defaults the agent to `"root"`). It never runs a model turn.
Why not a root model loop: the lifecycle rule (one agent, one task, the agent ends when the task ends,
`:seon.agent/admission-closed-tx`) would close root; `seon.task/assign!` refuses a task whose proofs are green, so a
standing duty has no assignable shape; a model turn per inbox message would pay for every green merge that needs no
judgment; and work on the cluster's own branch has no branch to review or roll back.

**A root task** is a task entity in `resources/seon/tasks/seon.root.edn`, ingested like any other, with two
properties:

- Its **proof is a system invariant**: a deftest in `test/seon/root_test.clj` that reads the database and is red
  while the duty is undone, green when it is done. `assign!` therefore assigns it exactly when there is work (red) and
  refuses it when there is none (`:proofs-green`); `seon.context/task-done?` ends the agent when the invariant holds.
  Examples: `seon.root-test/no-merge-task-stays-undecided` (red while a merge task has a stored refusal and no
  `:seon.task/outcome`), `seon.root-test/default-runs-the-landed-head` (red while a host-bound landing is committed and
  default's archive is older), `seon.root-test/every-task-of-an-assigned-triage-carries-a-triage` (reads the held
  triage task's `:seon.task/must-read`).
- Its **grants are data**: `:seon.task/grants`, a set from the enum `:seon.grant/host-evaluation`,
  `:seon.grant/merge-decision`, `:seon.grant/restart`. One reader, `seon.task/grants` `[db agent-id]`, answers the set
  from the agent's assignment (`seon.task/agent-task`). The one existing check moves from the id to the data:
  `seon.turn.batch/jvm-evaluate` refuses unless `(or (= "root" agent-id) (contains? (task/grants db agent-id)
  :seon.grant/host-evaluation))`, with the same refusal text naming the grant; `my.program/accept-merge!` and the new
  `my.program/decline-merge!` require `:seon.grant/merge-decision` the same way. Nothing else is gated: `my.shell/run!`
  is bounded by filesystem policy and the `seon.config.shell` dials today and stays so (no observed failure names a
  reason to restrict it); `my.test/check-platform` is any agent's. A grant is read at the call, so a task revised to
  drop a grant drops it at the holder's next form.

**The root system context** is not a new render. It is the task's `:seon.task/must-read` reference to one doc section,
`docs/seon/architecture/root.md#the-root-role`, resolved into the block by the same resolver every reference uses
(`seon.ref/references`; doc sections as entities, plan Q1). The section states, in the agent's terms: what root is (the
cluster's own branch, the task home, the one that merges and restarts); its duties by task (decide undecided merges,
land platform changes through the throwaway check, keep default on HEAD, triage); its powers and the exact operations
(`(my.program/prepare-merge! {:seon.task/merge h})`, `(my.program/accept-merge! …)`, `(my.program/decline-merge!
{:seon.task/merge h :seon.task/reason "…"})`, `(my.test/check-platform {})`, JVM forms when granted, `(my.shell/run!
{:my.shell/argv ["bin/seon" "status"]})`); and its limits: one batched restart through `seon.root/restart!`, never
`reset`, `nuke`, `stop` or `down`; never edit another agent's branch; platform edits only as `defn` on its own branch
then `my.program/write-branch!` and `check-platform`; a design that proves wrong is reported with options to `"root"`
(`my.message/send`), never patched around; every finding filed as a task with a red proof. The block's existing
sections (task, proofs, working set, namespace) carry the rest.

## 2. Auto-merge on green

**Where:** the settlement that observes the end. `seon.turn/end-task!` already acquires the agent's context and
records the proposal on `:done`; it gains one call, `seon.cluster.source/land!`, between the proposal and the message,
and the message carries the result. No listener, no proc, no root turn: the data (branch, proofs, run) is in hand
there, and the end is the consequence of the evaluation that turned the proofs green.

`seon.cluster.source/land!` `[execution proposal root]` → `:seon.source/landed` or the first refusal, never throws:

1. `seon.cluster.source/prepare-merge!` on `seon.turn.world/merge-world` (a fresh branch of head, the proofs and
   reaching tests run through the save gate, the run recorded).
2. `seon.cluster.source/accept-merge!`: the 2026-10-10 gate unchanged (every member of the run green by
   `seon.test/green-members`, `seon.test.runner/program-written-since?` false, every added or replaced function reached
   and contracted, head digests equal `:seon.source/expected-digests`); on success `seon.task/decide!` `:accepted` and
   branch retirement, as today.
3. `seon.cluster.source/write-back!` into `:seon.fn/root` (the cluster dial; `seon.fs/checkout-root?`).
4. `seon.cluster.source/commit!` (§3).

The result value `:seon.source/landed` holds `:seon.source/commit-id`, `:seon.source/written`, `:seon.git/commit`
and the step timings; `end-task!` puts it on the end message as `:seon.source/landing`, and `seon.context/ended`
shows it in root's block. The merge task is decided in the same call, so `my.program/proposals` lists nothing green.

**On a red gate** (`:seon.source/red-test-error`, or `:seon.source/red-on-target-error` "root decides"): `land!`
stores the refusal on the merge task as `:seon.task/refusal` (the error map's shown text and ids, through
`seon.blob/inline-or-stage`, with `:seon.source/red-tests` as data), leaves `:seon.task/outcome` absent, and the end
message carries it. The invariant `no-merge-task-stays-undecided` is now red, so the next tick (§5) launches a fresh
agent on the root task `seon.root/decide-undecided-merges` with `:seon.grant/merge-decision`. Its operations: read
the refusal and the red tests; for `:red` (the change broke them) `my.program/decline-merge!` with a reason, which
also creates the follow-up task `(seon.task/create! {… :seon.task/basis <task> :seon.task/must-read [the red tests
…]})` for the next round; for `:red-on-target` (already red on main, not caused by the change) decide by reading the
reaching test's history and accept or decline. AGENTS.md says a test already red on main stops the merge for root; the
root agent is root here, and this card does not auto-accept it (question 2, §9). Both decisions are durable
(`seon.task/decide!`) before the agent is answered.

**On a write-back conflict** (`:seon.source/write-back-error` `:conflict`, `:seon.program/conflict-digests`: a file on
disk changed under a declaration since it was analyzed): the merge is already on main (the database is the truth), the
refusal is stored on the merge task as `:seon.task/write-back`, and the tick retries `write-back!` then `commit!` while
the conflict lasts, giving the task to `decide-undecided-merges` after `:seon.config.root/write-back-retry-ms`. This
conflict exists only while Claude lanes edit files outside the REPL; when every edit enters through the one path it
cannot occur, and the retry is deleted with the lanes (§7).

## 3. Git

Options: (a) the root agent runs `bin/lane commit --branch-evidence` through `my.shell/run!`; (b) an in-JVM commit
owner; (c) the database stays the truth and git is written in batches at restart.

**Recommendation: (b), one commit per landing, by the owner that wrote the files.** `seon.cluster.source/commit!`
`[root written acceptance]` runs `git commit --only <written paths> -m <message>` through `babashka.process` (already
a dependency, the owner `seon.shell.jvm` uses it) with `:seon.fn/root` as cwd, and returns `:seon.git/commit` (the
sha) or `:seon.git/commit-error`. The message is derived from the acceptance: the task symbol, the merge task, the
agent, `:seon.test.run/id`, `:seon.source/commit-id`, and the same trailers `bin/lane` writes (`Branch-evidence-runs`)
so the history reads alike. Its proof is the acceptance it carries: the gate is exactly what `--branch-evidence`
re-derives for Claude lanes (digests declared by the branch, every reaching test green), and `write-back!`'s analyzer
reindex is the lint. Why not (a): `accept-merge!` retires the agent's branch, so `--branch-evidence AGENT` has no branch
to ask, and the lane ledger (`tmp/orchestrator/lanes`) is a Claude-lane concept. Why not (c): a batch loses the
one-merge-one-commit trail that classifies a later red by the commit that changed its behavior, and plan §7's rule is
that disk is a lossless write-back of the database, which a per-landing commit keeps true at every instant. A
host-bound landing (§4) commits the same way with the platform check's run as its evidence
(`seon.test.platform/record!`), the counterpart of `bin/lane commit --test`. `bin/lane commit` keeps serving Claude
lanes until none commits, then is deleted; `commit!` names it as what it replaces.

## 4. Restart and adoption

**Ordinary (interpretable) code needs no restart.** A merged definition is an entity on main; every agent's SCI world
reacquires from its branch head at each eval request and interprets the definitions that differ from the compiled
program (`seon.sci.eval/interpret-affected!`, `:seon.fn/host-bound? false`). Compiled Vars catch up at the next
restart. No reload path is added: `seon.cluster/development-source-refresh!` reloads from the sealed archive and is
not a live-update mechanism (explorer finding, unproven as such).

**Host-bound (platform) landings become live by one batched restart.** `seon.root/restart!` runs `bin/seon start
--head` as a detached child (`babashka.process/process`, no wait, output discarded; not through `my.shell/run!`, which
waits and reaps descendants). `seon.operator/move-to-head!` does the rest outside the JVM as today: prepares HEAD's
archive and classes first, stops the root by exact identity, launches the replacement on the same store, has
`default` adopt, and keeps the previous commit when the replacement never becomes ready. The rule, decided by the tick
from data: restart when a host-bound landing is committed since default's archive (`seon.root-test/default-runs-the-
landed-head` red) and no agent has an open turn (`seon.turn/next-agent-work` situation `:open` for none), or when
the oldest pending landing is older than `:seon.config.root/restart-after-ms` (default 30 min) regardless. One
restart serves every landing since the last. Never a crash: the operator's rollback is the safety net, and the
request itself is a durable fact (`:seon.root/restart-requested-tx` on root's cluster entity) so the restarted JVM's
first tick sees it was asked and reports the outcome to `"root"`.

## 5. Launching agents and rounds

**The src owner.** `seon.root/launch!` `[cluster task-sym settings]` is the three existing calls in order:
`seon.cluster.agent/creation-tx` with a minted id (`seon.id`), `seon.cluster.registry/fork!` of its branch,
`seon.agent/settings!` (model, `:seon.config.ai/no-provider false`, `:seon.config.run/max-episode-runs`), then
`seon.task/assign!` (which runs the proofs in the agent's world and refuses green) and one wake through
`seon.eval.drive/inbound!` ("Work your task."). `seon.competency/start-run!` becomes a call to it and its test-side
composition (`seon.test-support/own-branch-agent!` plus `outside-wake-on-branch!`) is deleted from the launch path.

**The tick.** `seon.root/tick!` is a root maintenance task in `seon.schedule/root-maintenance-portfolio`
(`root/maintenance/tick`, every `:seon.config.root/tick-ms`, default 10 min; `:seon.wake/opens-turn? false`, so a
firing never costs a model call by itself). It is a function of the database and does, in order:

1. Root tasks: for each task in `seon.root.edn` whose proofs are red and that no live agent holds, `launch!`.
2. Ordinary tasks: up to `:seon.config.root/concurrent-agents` (default 3, a 16 GB machine beside the owner's work)
   minus the live agents, pick tasks with red proofs (`seon.context/proofs` red on main), not held, not ended by an
   agent in the last `:seon.config.root/retry-after-ms`, ordered by the index's rank (the owner's ranked schedule)
   and then by the owner's size (fewest reaching tests first); `launch!` each.
3. Retry pending write-backs (§2), request a restart when the rule holds (§4).

Each firing is a **round**: the launched agents carry `:seon.agent/round` (a ref to the `seon.schedule.fire` entity),
so a round's grade is derived, never stored. `seon.competency/grade` and `table` move to `seon.root` (the test
namespace requires them); `table` takes two rounds and shows each cell beside the previous round's (green share, calls
to green, errors by signature, tokens), and only a better table counts. A tick that launches nothing is a healthy
observation, recorded as the firing.

**The daily spend report** is derived at the firing from stored usage and never stored twice: `seon.ai/spend` `[db
from to]` sums each attempt's `:seon.ai.usage/prompt-tokens`, `cached-tokens` and `completion-tokens` by
`:seon.ai/model`, multiplied by that model entity's `:seon.ai.model/input-usd-per-mtok`,
`cached-input-usd-per-mtok` and `output-usd-per-mtok` and the DeepSeek window factor in force at the attempt's time
(`:seon.ai.model/deepseek-*`), and returns `:seon.ai/spend` (`:from :to :by-model {model {:prompt :cached :completion
:usd}} :usd`). `seon.maintenance/report-spend!` (schedule entry `root/maintenance/spend`, 00:05 UTC) sends `"root"`
one message whose content is that value; the web and the MCP block render it through the value's declared render. A
message a day is the report; the owner reads it in root's inbox.

## 6. Triage as a task kind

A triage is a root task generated by the tick when untriaged tasks exist: `seon.task/create!` of
`seon.root/triage-<fire-id>` with `:seon.task/must-read` naming `:seon.config.root/triage-batch` (default 10) task
symbols that carry no `:seon.task/triage`, oldest first, and the root role section. Its proof is the invariant
`every-task-of-an-assigned-triage-carries-a-triage`, red at assignment. The agent (DeepSeek flash, no grants) works
each task: reproduce the reported input at the REPL; then one of three outcomes, written by `my.task/triage!`:

- `:retired` with `:seon.task/triage-evidence` naming a covering deftest: **accepted automatically by the green
  rule** when that test is green on main at its current reach (`seon.test.runner/red-at-current-reach?` false) and
  names the task in `:seon/tasks`; otherwise the writer refuses `:uncovered` with the fix text. Tagging the test is an
  edit on the agent's branch that lands by §2 like any other, so the retirement is accepted when its merge lands.
- `:live` with a red proof added (a row in an existing table or a new deftest, red on main; the writer checks
  `seon.context/proofs` shows one red).
- `:unproven` with a reason, when the agent could neither reproduce nor refute; the task leaves the schedule.

Root reviews nothing: the evidence rule is the review, the same one that accepts merges. The outcomes are attributes
on the task entity at the home (`:seon.task/triage`, `:seon.task/triage-evidence`, `:seon.task/triage-reason`), with
history; the task file is written back by `seon.task/write-back!` (plan Q2 (a): the same path functions use), and a
retired task's entry is removed from its file in that write-back, which is the commit that retires it.

## 7. What the Claude orchestrator still does

The residue, all through the same identity `"root"` over MCP, with no other channel:

- **Owner conversation and design**: cards, rulings, the open questions below, the plan's §0. The orchestrator
  writes tasks when the owner decides something new; the tick launches them.
- **Emergencies**: default cannot boot after the operator's rollback, store corruption, a dependency or fork bump (a
  dependency change lands last in a batch and is host-bound in a way no throwaway check proves), a stalled tick
  (`seon.schedule`'s error settlement sends `"root"` the message; the orchestrator reads root's inbox).
- **The first stages of this card**, until the loop lands itself: stages 1–3 land by Claude lanes; from stage 7 the
  tick launches the rest as ordinary tasks with red proofs.

Hand-off: nothing to hand. The orchestrator's inbox is root's inbox; the end messages, spend reports, restart
outcomes and refusals it read by hand are the same messages the functions above now act on first. When the
orchestrator is away, nothing waits for it. Claude lanes and `bin/lane` are deleted when a round lands with no
Claude commit, together with the write-back retry (§2).

## 8. Stages, about 15 minutes each, in order

Each names its red proof (asserting on data, in the owning test namespace) and its files. Lanes: A holds
`src/seon/cluster/source.clj` and `test/seon/cluster/merge_test.clj`; B holds `src/seon/turn.clj` and
`test/seon/turn_continue_test.clj`; C holds `src/seon/task.clj`, `src/my/task.clj`, `src/my/program.clj`,
`src/seon/turn/batch.clj`, `resources/seon/schemas/seon.task.edn`, `test/seon/task_ingestion_test.clj`; D holds
`src/seon/root.clj`, `test/seon/root_test.clj`, `src/seon/schedule.clj`, `resources/seon/tasks/seon.root.edn`,
`docs/seon/architecture/root.md`, `test/seon/competency.clj`, `test/seon/competency_grade_test.clj`; E holds
`src/seon/ai.clj`, `src/seon/maintenance.clj`, `test/seon/ai_test.clj`. The loop runs crudely after stages 1–3
(agents launched by hand land without root); the tick after 7 makes it run alone.

1. **`land!` is one call** (lane A). `seon.cluster.source/land!`: prepare, accept, write-back, returning the first
   refusal as data. Proof `seon.cluster.merge-test/a-green-request-lands-on-main-and-disk-in-one-call` (fixture
   proposal; asserts main holds the digest, the file holds the span, the value is `:seon.source/landed`; a red
   reaching test yields the gate's refusal and no outcome).
2. **The green end lands** (lane B, after 1). `seon.turn/end-task!` calls `land!` on `:done`; the message carries
   `:seon.source/landing`; `seon.context/ended` shows it; a refusal is stored as `:seon.task/refusal` on the merge
   task (schema in `seon.task.edn`, lane C's file: one patch to C). Proof
   `seon.turn-continue-test/a-green-end-lands-its-merge-without-root` (fixture agent goes green; main has the change,
   the end message's `:seon.source/landing` names the commit; a second agent whose reaching test is red leaves an
   undecided merge task carrying the refusal).
3. **One git commit per landing** (lane A). `seon.cluster.source/commit!` through `babashka.process`; `land!` calls
   it after write-back; message and trailers from the acceptance. Proof
   `seon.cluster.merge-test/a-landed-merge-is-one-git-commit-of-exactly-its-paths` (a disposable `git init` root
   under the test's own tmp; asserts the commit touches exactly the written paths and its message names the run).
4. **Grants are task data** (lane C). `:seon.task/grants` and the `:seon.grant` enum in `seon.task.edn`;
   `seon.task/grants` reader; `seon.turn.batch/jvm-evaluate` and `my.program/accept-merge!` read it; ingestion
   accepts it. Proof `seon.task-ingestion-test/a-task-grants-host-evaluation-to-its-holder` (an agent holding a
   granted task evaluates a JVM form; one holding an ungranted task is refused naming the grant; `"root"` is unchanged).
5. **Root tasks and the role section** (lane D). `resources/seon/tasks/seon.root.edn` (`decide-undecided-merges`,
   `land-platform-change`, `keep-default-on-head`, with grants and the must-read reference),
   `docs/seon/architecture/root.md#the-root-role`, and the invariant proofs in `test/seon/root_test.clj`
   (`no-merge-task-stays-undecided`, `default-runs-the-landed-head`), red where violated today. Proof: ingestion of
   the file resolves every reference (`seon.task-ingestion-test`'s existing table gains the rows).
6. **`my.program/decline-merge!`** (lane C). `seon.task/decide!` `:declined` with `:seon.task/reason`, creating the
   follow-up task from the refusal's red tests with `:seon.task/basis`; requires `:seon.grant/merge-decision`. Proof
   `seon.task-ingestion-test/a-declined-merge-leaves-a-follow-up-task-naming-its-red-tests`.
7. **`launch!` and `tick!`** (lane D, after 5). `seon.root/launch!` (the three calls); `seon.root/tick!` steps 1–2
   with the dials `:seon.config.root/tick-ms`, `concurrent-agents`, `retry-after-ms` in
   `resources/seon/schemas/seon.config.root.edn` and `config/default.edn`; the schedule entry;
   `seon.competency/start-run!` calls `launch!`. Proofs `seon.root-test/launch-creates-assigns-and-wakes-one-agent`
   and `seon.root-test/a-tick-launches-at-most-the-dial-on-red-unheld-tasks` (fixture provider; asserts the launched
   set as data, no provider call).
8. **Rounds and the table** (lane D). `:seon.agent/round` (schema `seon.agent.edn`, a patch to its owner);
   `seon.competency/grade` and `table` move to `seon.root`; `table` compares two rounds. Proof
   `seon.competency-grade-test/a-round-table-compares-with-the-previous-round`.
9. **The restart rule** (lane D). `seon.root/restart!` (detached `bin/seon start --head`), `:seon.root/restart-
   requested-tx`, `:seon.config.root/restart-after-ms`; tick step 3. Proof
   `seon.root-test/a-restart-is-requested-only-for-a-pending-host-bound-landing-with-no-open-turn` (the effect
   substituted as a value; asserts the decision data in both arms).
10. **The spend report** (lane E). `seon.ai/spend` and `:seon.ai/spend` schema; `seon.maintenance/report-spend!`;
    the schedule entry (one line in `seon.schedule`, a patch to D). Proof
    `seon.ai-test/a-days-spend-is-tokens-times-the-model-price-by-model` (fixture attempts across two models and one
    off-peak window; asserts the by-model map and the total).
11. **Triage outcomes at the writer** (lane C). `:seon.task/triage`, `triage-evidence`, `triage-reason`;
    `my.task/triage!` with the green rule for `:retired`. Proof
    `seon.task-ingestion-test/a-retirement-needs-a-green-covering-test-on-main` (retired with a green tagged test is
    written; with a red or untagged one refused `:uncovered`; `:live` needs one red proof).
12. **Task-file write-back** (lane C, after 11). `seon.task/write-back!` writes `resources/seon/tasks/<ns>.edn` from
    the home's entities through `seon.cluster.source/write-back!`'s path (plan Q2 (a)); a retired task's entry is
    removed. Proof `seon.task-ingestion-test/a-task-file-round-trips-through-the-database` (ingest, change a triage,
    write back, re-ingest: equal entities; the retired entry gone). This may exceed fifteen minutes; split at the
    retire case if so.
13. **Triage batches** (lane D, after 11). The tick creates and launches `seon.root/triage-<fire>` tasks; the
    invariant `every-task-of-an-assigned-triage-carries-a-triage`. Proof
    `seon.root-test/a-tick-batches-untriaged-tasks-into-one-triage-task-of-the-dial`.
14. **Deletion** (root, after a round lands with no Claude commit): the write-back retry, `bin/lane commit`'s
    branch-evidence path and the lane ledger, `seon.test-support/own-branch-agent!`'s launch composition. Proof: the
    tests that reached them are deleted with them; `seon.root-test`'s invariants stay.

Stages 1 and 3 share lane A; 2 follows 1; 4, 6, 11, 12 are lane C in order; 5, 7, 8, 9, 13 are lane D in order; 10
is lane E and independent. Three lanes run at once from the start (A, C, D), B after A's first stage.

## 9. The simplest alternative considered, and the questions

**The alternative:** make `"root"` a model agent on `:cluster-default` with a standing task "serve root's requests"
and let its turn loop answer every inbox message. It is the least new code (a task file and a settings write), and it
was rejected: the standing task has no red proof, so `assign!` refuses it and `task-done?` never ends it; its end
would close root's admission and the task home with it; every green merge would cost a model call to do what a
function does in one; and its edits would land on main with no branch to review. The second alternative, a Flow proc
listening on `:seon.task/merge` messages with `d/listen`, is a second wake router beside the one `seon.turn` already
owns; the end settlement sees the same data a step earlier at no extra machinery.

Questions, each with options and the recommendation. Marked **answered** where this card proceeds on its
recommendation (owner 2026-10-10: decide with the recommended option, proceed and report).

1. **Who is root?** (a) functions at the seams plus fresh granted agents on root tasks (recommended); (b) `"root"`
   runs a model loop. **Answered: (a)**, §1.
2. **A merge whose only reds are already red on main** (`:red-on-target`). (a) the root agent decides, reading the
   test's history (recommended: AGENTS.md says it stops for root, and one round of decisions will show whether a rule
   exists); (b) auto-accept, since the change broke nothing. **Answered: (a)**; revisit after the first round with the
   decisions as data.
3. **Git.** (a) one commit per landing by `seon.cluster.source/commit!` (recommended); (b) `bin/lane commit` through
   the shell; (c) batches at restart. **Answered: (a)**, §3.
4. **Restart cadence.** (a) on quiescence after a host-bound landing, at most every 30 min otherwise (recommended);
   (b) only on quiescence; (c) a fixed interval. **Answered: (a)**, §4.
5. **Triage retirements.** (a) auto-accepted by the green rule on a covering test (recommended); (b) the root agent
   reviews each. **Answered: (a)**, §6.
6. **The model for root tasks.** (a) deepseek-flash, as for every agent (recommended: the first round measures it;
   the task's settings are an overlay, so one task can name `deepseek-v4-pro` without new machinery); (b) pro for
   merge decisions and platform work from the start. **Answered: (a)**; the owner may flip one task's settings.
7. **Shell for ordinary agents.** (a) leave `my.shell/run!` ungated, as today (recommended: no failure observed names
   the restriction); (b) gate it behind a grant. **Answered: (a)**; the root role section tells root agents what the
   shell is for.
8. **Concurrency.** `:seon.config.root/concurrent-agents` default 3 on a 16 GB machine beside the owner's work
   (recommended, from loop round 1's three agents and the 3.2 GB prepare); raise after the round table shows headroom.
   **Answered: 3**; a dial, changed by `bin/seon config apply`.

Open for the owner (not blocking; the card proceeds without them): whether the orchestrator's residue (§7) should
also receive the daily spend message by another channel than root's inbox, and whether a round's table should be
sent to `"root"` as a message at each tick or read on demand (`(seon.root/table <round> <previous>)`; recommended
on demand, since a tick every ten minutes would send 144 messages a day).
