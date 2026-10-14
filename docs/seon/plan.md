---
title: The plan
type: plan
status: active
created: 2026-10-10
---

# The plan

One plan, revised in place. First written by lane fable-docs on 2026-10-10; rewritten by lane fable-replan-2 on
2026-10-11 from the owner's answers in [fable-replan](../research/agent-platform/2026-10-11/fable-replan.md) §8
(questions Q1–Q16), the [task verbs catalogue](../research/agent-platform/2026-10-11/task-verbs-catalogue.md), the
[owner decisions](../research/agent-platform/2026-10-11/owner-decisions.md) of the same morning and the TASK-CHAIN card's
two reviews (`schedule/cards/TASK-CHAIN.md` §8). Live facts were checked on default (archive c1230efc3, then
f51240f34) in JVM mode, read-only. Dated evidence lives in the research pages; this file holds direction and decisions.

## 0. Where we are, and the order (revised in place by root; last 2026-10-14 afternoon)

**Lifecycle authority: [TASK-CHAIN](schedule/cards/TASK-CHAIN.md), READY (56f31a105, 5e0774bd6, f7ce8d41f).** A chain
is a vector of plain task maps written by `seon.task/chain!` in one transaction on one branch, linked by
`:seon.task/next`, and worked by a new agent per task. It has one chain fact on main and one proof set per task
(`:seon.task/proofs`, no tags). One done rule (`seon.cluster.source/done-refusals`) applies to every task. A failure
bails to root, with no relaunch; the chain merges to main once. The MVP chain is Design → Prove → Implement, for bug
fixes and new features alike. Implementation follows TASK-CHAIN §7: S1 → S2 → S3a in order, then the three stage
lanes (S4b) in parallel with live runs. Everything older in this file and in `schedule/cards/` that describes copies,
children, `seon.task/effective`, obligations, tags or relaunch is superseded; git history keeps it.

**Landed since TASK-CHAIN was READY (2026-10-14).** S1d-1 `my.edit/text` (776a04249); S1d-3 help as Markdown
(f87629f9b), its references re-resolved by what they read (e552417e3); S1a rename prepared (4da46c8e7: script
`script/seon/rename_task_intent.clj`, 156 files, dry run clean); value defs as program (de1f48c23); errors-cut E1
(5a08c13af); retention: a current head no longer moves the cutoff (0691cb23c), the held-value rule recorded
(284ec61e5, TASK-CHAIN §2). Research: reply data (6ff790bb7; REPL prototypes 7b5ca80b9 Sonnet, 1b574d429 Haiku),
proof audit, red triage, flake hunt, lint inventory (notes under `../research/agent-platform/2026-10-14/`).

**In flight.** `bin/lane` asks `seon.test.cache/input-roots` instead of a hand list of documentation paths (lane
lane-input-roots; then root commits the owner's experimental `src-clm/`). Retention off by dial,
`:seon.config.maintenance/retention?` false (owner 2026-10-14: keep main's history to measure true growth and optimize
the producers; deleted branches are still reclaimed; the window code stays). Documentation cleanup against
TASK-CHAIN (Haiku inventories → Sonnet edits).

**Next restart batch** (root opens it when the in-flight code lanes have committed): (1) S1a phase 2, with reset; db<->flow's decision is renamed first (`seon.cluster.agent/launch-actions`, `:seon.flow.actions/*`) so "plan" names
only the task field; (2) the GC fork bump (Konserve b4b896f, Datahike 6d49110d; `gc-one-enumeration.patch`; retire
`seon.db/node-not-found-carries-a-store-id-not-the-store`); (3) the `io.github.nextjournal/markdown` removal
(`drop-nextjournal-markdown.patch`); (4) the retention dial applied. After the restart: the sentinel with repeated
samples; a stale-template check.

**Restart batch after that:** retire namespace agents (owner 2026-10-14: a task supersedes them; a namespace agent
is a task about a namespace): delete `steward-call`, `:seon.ns/steward`, `:seon.error/of-steward`, `:my.agent/steward`,
their readers and the unowned-namespace problem family in one slice with every caller converted
(`../research/agent-platform/2026-10-14/namespace-agents-inventory.md`, b80a0fc67); `:seon.agent/namespace` (the
REPL's current namespace) stays. S3d-1 (doc views) runs beside S1b after the first batch (e962f5a11).

**Before the red sweep: the bulk tier must finish.** At 46992f75b it hit the check's 600 s bound with 185 of 2,993
members run (`../research/agent-platform/2026-10-14/red-triage.md`, 5036d2264). Opus (test runner) or Astra
(performance): measure where the bulk tier's time goes and make it finish, before reds are classified. Known so far: 14
deterministic reds (10 untracked, listed there), 3 load flakes in `seon.blob-*`; `bin/seon check` refuses a whole run
when one named namespace has no eligible tests; `seon.repl-parity-test`'s macro-generated deftests may be untested.

**Then, before S1b: the red sweep.** The bulk tier once on the restarted default; classify every red by the commit
that turned it red (stored runs, `bin/seon check --at`); fix broken and surviving reds, skip what TASK-CHAIN §6
deletes, file the rest. Known untracked reds: ten `seon.effect-test` vars, the `seon.edit-test` odd-map pair,
`my.edit-test/public-entries-declare-resolvable-io-capabilities`,
`seon.cluster.turn-test/a-model-request-is-help-own-exchanges-and-the-block-last`,
`seon.sci.supplied-database-test/own-record-reads-supply-the-same-database-and-agent-as-explicit-calls`,
`seon.help-test/source-finds-a-schema-registered-in-the-same-eval-request`,
`seon.test.accretion-test/source-harvest-keeps-refusal-render-contracts-coherent` (19 s over its bound); eight reds identical at HEAD from 86942d3d0 (three `seon.cluster.publication-inputs-test` errors on 64-character gitlink pins, three `seon.cluster.source-test` fails, `seon.test-cache-test/a-documentation-edit-never-widens-a-gate`, `seon.test.selection-test/a-proof-request-runs-only-its-proofs`); the collection test body at ~70 s (flake-collection-test.md); 157 real dead-code lint warnings (lint-warnings.md) as a Sonnet sweep. To file: a new test file calling a missing var makes the check's throwaway JVM refuse to boot, so the red names no test (proof-audit.md, 3081f15fa; f87629f9b's red).

**Beside the chain** (file-disjoint, never ahead of it): the errors cut E2 → E3 (restart batch) → E4a → E4b → E6
(`../research/agent-platform/2026-10-14/errors-cut-plan.md`); the reply fence (`seon.test-support/reply`'s catch,
130526a8b; task `seon.test-support/tests-read-the-evaluations-recorded-data-not-printed-text`), designed from the
two prototypes: two of three call kinds assert an emission's data, the rest read `seon.mcp/eval-clj`'s value through
a data-returning split.

**Models** (owner 2026-10-14, tailored by measured outcome). Haiku gathers and summarizes (digs, inventories, flake
runs, red triage) so root reads conclusions, not bulk; Sonnet does well-specified work and REPL prototypes (on the
same prototype brief Sonnet answered 3.5 of 4 questions at the REPL, Haiku 1); Opus takes the hard problems and
planning; Astra gives the independent second opinion and the hard performance work.

**Live runs** (owner 2026-10-14): no paid Claude API calls. Two kinds of run after S3a (Design, then Prove with
Implement held): (1) DeepSeek product agents, the measured live run; (2) Claude Code agents acting as Seon agents
through the MCP servers (`eval_clj` as their own agent on the chain branch, reading their current-state block and
working by `defn`, as CLAUDE.md's outside agents do), which judge the agent experience and whether each task's
generated context is complete and correct before the DeepSeek runs are graded on it.

**Goals, in order** (owner, 2026-10-12): (1) chains run unattended, measured by hand acts after launch (target 0),
calls, tokens and errors by signature; (2) context useful by construction; (3) tasks improve as work proceeds; (4)
agents mine Seon for tasks, after goal 1 holds.

**Deferred, not on the path:** user surfaces; concurrency (two chains at once); performance follow-ups
(`my.program/search` allocation, publication ~1.3 s per namespace, the 2.3 s cold one-test check, check JVM runs of
160–190 s for one test).

## 1. The goal

Software that grows by accretion: open data under named schemas, stable names, every call checked and measured, built by
many agents each in its own branch and SCI context. Work is a chain of tasks ([TASK-CHAIN](schedule/cards/TASK-CHAIN.md)):
Design, then Prove, then Implement, each a new agent working the task as the previous one left it, all on one branch
that root merges to main once. Tasks are mined from the database (slow functions, guessed names, uncontracted functions,
red proofs), and root closes a filed task whose proofs turn green on main (goal 4; not designed yet). A core of about
10,000 lines where ordinary work is sub-second.

## 2. The essentials

1. **An honest, short number in every reply.** Committed (FEEDBACK-LOOP stages 1–13, 16; like-for-like cold and warm cost
   per [symbol scope], b37a3ff72). Being cut to one line: an agent learns it wrote slow software in forty tokens, not five
   hundred.
2. **Tasks worked end to end by Seon agents through the REPL.** A model agent has turned a designed task green and root
   has merged three by hand. The chain of separate agents on one branch is the next shape (TASK-CHAIN §2, slices §7).
3. **Proofs before work, a done rule after.** A task's proofs are a field, `:seon.task/proofs`; Prove writes the next
   task's, red by design, and one rule, `seon.cluster.source/done-refusals`, ends every task (TASK-CHAIN §2.4). A task
   whose proofs are all green at its start ends at once. Installed until S2c/S3a: tagged deftests and
   `seon.task/assign!`'s refusal. Proof quality is the weak point: nearly every failed round traced to the proof or the
   task, not the implementer, which is why Prove is its own stage (TASK-CHAIN §4).
4. **Default runs HEAD.** Routine: `bin/seon start --head` per batch of commits; readiness 5–6 s on a quiet machine.
5. **Correctness and efficiency over size, for now** (owner, 2026-10-10). Temporary growth is acceptable; Seon agents
   trim it later using the cost data; every change is judged by correctness and measured cost. Growth that is a fence is
   not acceptable.

## 3. The method: a chain of agents

The method is TASK-CHAIN. A chain is a vector of plain task maps that `seon.task/chain!` writes in one transaction on one
branch, linked by `:seon.task/next` and started from a template per stage (`seon.step.design/template`, `prove`,
`implement`). Root's listener starts a new agent per task; the agent works its block at the REPL, edits the plan and the
next task's proofs with `my.task/update!`, and ends when `done-refusals` is empty. The finished task's plan flows to the
next. Any end that is not done bails to root, with no relaunch; a finished chain merges to main once. The MVP chain is
Design → Prove → Implement, for bug fixes and new features alike; specialized chains are other templates on the same
mechanism. Installed until S3a: copies, children and per-step merges.

Every new mechanism is run by hand once before it is automated: each early chain run found two to five frictions at
minutes of cost, the cheapest learning there is. Live runs and their order are §0.

## 4. Done

The honest-measurement task (FEEDBACK-LOOP stages 1–13 and 16); the merge base by `t` (d9eb15c05, aa7ffb17f); the
byte-bounded node cache (12473ca94); the first links of the agent lifecycle (DYNAMIC-CONTEXT stages 1–3: the block names
the namespace, an agent closes at its end, one end message to root); non-root agents never load the shared JVM
(e361536a9); a function and its schema merge as one unit (ff61d0b29); the collection fix (f51240f34). History is in git
and the dated research pages.

## 5. Superseded

Everything that describes copies, subjects, children, `needs`, `seon.task/effective`, obligations, `:seon/tasks` tags,
`refine!` or relaunch is replaced by TASK-CHAIN (§6 there lists the deletions). Git history keeps it.

## 6. Deliberately deferred

- ROOT-AGENT stages 4–13 (grants, root tasks, `decline-merge!`, tick, triage, spend report): `my.root` holds root's tools
  first; the tick waits for three chain runs; triage waits for a judgment duty to occur three times.
- DYNAMIC-CONTEXT stages 4–7 (shown declarations, working set, history by reference, reflection on a snapshot): only if a
  chain run shows prompt growth caps agents.
- Publication (a compiled check is 32–45 s measured) and boot's whole-program derivation: platform commits pay the
  existing check; per-file derivation inputs when the chain runs show the cost dominating.
- Merging through git objects or a dedicated checkout: committed only if a real collision is observed.
- COST-REG slices 2–3; growth tests and guards; embeddings (after lexical search is used and found wanting); the Datahike
  fork rebase; a stateful lifecycle test suite; a Flow-hosted writer (refused).
- The positive-wording pass over AGENTS.md's remaining negatives: root, in small commits.

## 7. Decisions, by owning topic (every owner ruling, deduped; superseded ones marked)

**Laws and design**
- 2026-10-10 Seon grows by accretion; the four properties and the design order in AGENTS.md are the laws.
- 2026-10-11 NO GUARDS OR FENCES, no exceptions (AGENTS.md); supersedes "every restriction names the failure it prevents".
- 2026-10-11 Focus: improving the tasks, not more platform machinery; embedding search later.
- 2026-10-10 Correctness and efficiency over size for now; temporary growth acceptable; agents trim later.
- 2026-10-10 Datahike's writer stays as it is (go-loops, group commit); Seon does not batch; Flow procs run on platform
  threads, one per task; accounting closes at `seon.flow/var-process`; the reply is published after accounting.
- 2026-10-10 Stored data is described in Datahike's terms (entities, attributes, values, datoms).
- 2026-10-10 Merge bases are found by the fork's `t` and `as-of`, never by walking commits (committed d9eb15c05).
- 2026-10-10 Full history is kept; `as-of`, `since` and `history` are unaffected. (Retention is off by dial, §0.)

**Tasks and the chain**
- 2026-10-10 Work is a chain of separate agents; run by hand first.
- 2026-10-11 The chain is TASK-CHAIN; build it generally, experiment with variations, keep the stages worth their calls.
  Its "every stage records an explicit decision" and the subject/step shape are superseded by the 2026-10-13/14 rulings
  in TASK-CHAIN's header (stages are linked tasks on one branch; a stage's check is its handoff predicate, §4).
- 2026-10-10 Tasks are entities with history; author little, derive the rest; task names are fully namespaced symbols;
  the database is the source of truth and files are lossless write-back, proven by a round trip. Superseded as to
  members (authored = name, intent, must-read; derived = proofs): authored = `plan`, `instructions`, `proofs`; derived =
  `references` (TASK-CHAIN §1).
- 2026-10-11 Auto-retire: root closes a filed task whose proofs turn green on main; not designed yet (goal 4).
- 2026-10-11 Vocabulary (Q16): agents have `my.task/current`, `update!` (was `refine!`, TASK-CHAIN §3a) and `send-back!`
  (stays, §2.7); success has no verb; root's namespace is `my.root`; merge words are git's (`merge!`, `close!`,
  `:merged`/`:closed`); home → main, refuted → a red proof, outcome → end, proposal → merge request.
- 2026-10-08 A question task declares its answer's Malli schema; the answer is ordinary Datahike data.
- 2026-10-09 Cost has four meters (calls, CPU, allocation, storage); an agent's budget is its turns
  (`seon.turn/turns-left`, TASK-CHAIN §2.7) and completion is the done rule.
- 2026-10-11 Guessed names: answer at the failure with the nearest real symbols and calling forms; learn guessed →
  resolved from the agent's next form; link it to the task; a name guessed twice becomes a mined task.

**Runtime and scheduling**
- One path: an agent wakes and runs a task; everything uses it (chain tasks, problems, recurring attempts); the
  reconciler, the clock and the heartbeat are the procs that host it, not tasks. Agents never start agents.
- Progress is derived from facts written once (chain tasks and their `next` links, attempts, outcomes); intent is stored
  on the task (`:held :running :paused :stopped :cancelled`) plus cluster `running/paused`; nothing writes "actual" to
  the database except the heartbeat's audit.
- Order on a chain is `:seon.task/next` (TASK-CHAIN §2.1). Superseded: "order is `needs` only, parents never run"; `needs`
  and `children` are deleted (§6 there).
- Instructions are data on the task. A stage is a template: an ordinary held task (`seon.step.design/template`, `prove`,
  `implement`, one file each) merged into a chain's task maps by its creator, with its checks a contracted handoff
  predicate and one deftest (TASK-CHAIN §1, §4). No stage type entity, no template language, no second merge rule.
- Problems are tasks assigned to root by identity, created where their facts already pass; nominal ends wake nobody;
  error signatures and cost regressions go to fresh agents. A bail is one problem class (TASK-CHAIN §2.7).
- One scheduling system: a recurring thing is a task with a cron; one clock proc writes firings; a function firing runs in
  the clock (maintenance, heartbeat); an attempt firing runs on the path; a firing to a live agent is a message wake;
  refused delivery is a problem task. `seon.schedule`'s per-agent proc goes.
- Flow owns process and computation; channels carry only ephemeral data; everything of consequence is a fact, so a crash
  and a resume to any commit are derivations. `start` resumes; `start --paused` for debugging.
- `max-running` is a dial, default 4; agents are to be lightweight: context MB and ms to start are measured targets.
- The cache of a branch's interpreted definitions (`seon.sci.eval/interpret-overlay!` per branch) is the first
  performance lane.

**Proofs and tests**
- 2026-10-09 A task's proofs are its definition of done and a task without proofs never finishes (TASK-CHAIN §2.4); the
  rest is an unproven backlog.
- 2026-10-10 Proofs as written: an `are` table with the reported input plus one `is` on a count for a cost task; a seeded
  property only where a one-line law exists; COST-REG slices 2–3, guards and growth tests cut.
- 2026-10-11 How to write good proofs: the Prove template's instructions are the catalogue (TASK-CHAIN §4), revised by
  live runs.
- 2026-10-11 Interpreted must agree with compiled for every declaration that is not host-bound; every mismatch is a bug,
  never a verdict class; platform changes use the existing compiled check.
- 2026-10-10 Fixtures are data on the namespace entity (`:each`, `:once`), written by `use-fixtures` and file indexing.
- 2026-10-10 Only reliable, like-for-like cost data is surfaced; one cost entity per [symbol, scope], cold and warm.

**Merge**
- 2026-10-10 Auto-merge on green is the target. Until criteria for non-controversial merges are written, root reads the
  diff before it merges (AGENTS.md). Superseded 2026-10-11 (Q8) "a merge request is a task assigned to root": root merges a
  chain branch once (TASK-CHAIN §2.6); `my.root/merge-requests` is installed until S3b.
- 2026-10-11 One close owner, `seon.task/close!`, decides and deletes branches; `my.root/close!` calls it.

**Measurement and cost**
- 2026-10-11 Concise performance feedback: one line of about forty tokens only when a changed definition got costlier than
  main; request cost as `ms / MB`; the teaching goes in the instructions (Q1).
- 2026-10-10 Numbers are live: each reply shows the request's own values against main's stored values; cost entities per
  function with values replaced in place, no history; schema cost measured once per digest at contract time.
- 2026-10-09 A cost regression against the last green run on main is a red test, not a health note.

**Data, documents and search**
- 2026-10-10 Doc sections are entities identified by path and anchor, with parsed references as facts; files are the
  write-back.
- 2026-10-10 Search: the fork's Lucene secondary index (`:scriptum`) first; vectors (`:proximum`) only after a chain run
  shows a lexical miss.

**Process and roles**
- Roles: AGENTS.md "Pick the model by the work" and §0 Models. Superseded: 2026-10-10 "DeepSeek-flash implements
  non-platform; Opus implements platform". 2026-10-11: design is interactive with the owner; lanes implement agreed
  designs (Q9).
- 2026-10-10 When the owner is not replying, root decides with the recommended option, proceeds and reports; 2026-10-11
  narrows it: pre-approved are bug fixes and work agreed in §0; new design waits.
- 2026-10-10 The whole loop is run by Seon agents eventually: a root agent through `my.root`, agents triage, no spend cap
  with a daily report (deferred behind the chain, §6).
- 2026-10-09 Auto-restart default onto HEAD per batch of commits; then delete the lag workarounds.
- 2026-10-09 Freeze new platform machinery until the REPL-only acceptance passes: superseded by TASK-CHAIN being READY
  (owner 2026-10-14).
- 2026-10-10 Reflection: root reads the record first, then asks the snapshot; by hand.

## 8. Open questions

None open.
