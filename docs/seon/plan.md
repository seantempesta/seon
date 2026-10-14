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

**Next restart batch** (root opens it when the in-flight code lanes have committed): (1) S1a phase 2, with reset; the
same commit renames db<->flow's decision `seon.cluster.agent/plan` and its `:seon.flow.plan/*` keys so "plan" names
only the task field; (2) the GC fork bump (Konserve b4b896f, Datahike 6d49110d; `gc-one-enumeration.patch`; retire
`seon.db/node-not-found-carries-a-store-id-not-the-store`); (3) the `io.github.nextjournal/markdown` removal
(`drop-nextjournal-markdown.patch`); (4) the retention dial applied. After the restart: the sentinel with repeated
samples; a stale-template check.

**Then, before S1b: the red sweep.** The bulk tier once on the restarted default; classify every red by the commit
that turned it red (stored runs, `bin/seon check --at`); fix broken and surviving reds, skip what TASK-CHAIN §6
deletes, file the rest. Known untracked reds: ten `seon.effect-test` vars, the `seon.edit-test` odd-map pair,
`my.edit-test/public-entries-declare-resolvable-io-capabilities`,
`seon.cluster.turn-test/a-model-request-is-help-own-exchanges-and-the-block-last`,
`seon.sci.supplied-database-test/own-record-reads-supply-the-same-database-and-agent-as-explicit-calls`,
`seon.help-test/source-finds-a-schema-registered-in-the-same-eval-request`,
`seon.test.accretion-test/source-harvest-keeps-refusal-render-contracts-coherent` (19 s over its bound).

**Beside the chain** (file-disjoint, never ahead of it): the errors cut E2 → E3 (restart batch) → E4a → E4b → E6
(`../research/agent-platform/2026-10-14/errors-cut-plan.md`); the reply fence (`seon.test-support/reply`'s catch,
130526a8b; task `seon.test-support/tests-read-the-evaluations-recorded-data-not-printed-text`), designed from the
two prototypes: two of three call kinds assert an emission's data, the rest read `seon.mcp/eval-clj`'s value through
a data-returning split.

**Models** (owner 2026-10-14, tailored by measured outcome). Haiku gathers and summarizes (digs, inventories, flake
runs, red triage) so root reads conclusions, not bulk; Sonnet does well-specified work and REPL prototypes (on the
same prototype brief Sonnet answered 3.5 of 4 questions at the REPL, Haiku 1); Opus takes the hard problems and
planning; Astra gives the independent second opinion and the hard performance work.

**Live runs** (owner 2026-10-14): the first live runs after S3a (Design, then Prove with Implement held) use a Claude
model, not DeepSeek, until the agent experience is right and each task's generated context is of high quality.
Prerequisite: `seon.ai` speaks OpenAI chat completions to `:seon.config.ai/endpoint`; prove Anthropic's
OpenAI-compatible endpoint (tool calls included) by configuration before S3a ends, and file what does not fit.

**Goals, in order** (owner, 2026-10-12): (1) chains run unattended, measured by hand acts after launch (target 0),
calls, tokens and errors by signature; (2) context useful by construction; (3) tasks improve as work proceeds; (4)
agents mine Seon for tasks, after goal 1 holds.

**Deferred, not on the path:** user surfaces; concurrency (two chains at once); performance follow-ups
(`my.program/search` allocation, publication ~1.3 s per namespace, the 2.3 s cold one-test check, check JVM runs of
160–190 s for one test).

## 1. The goal

Software that grows by accretion: open data under named schemas, stable names, every call checked and measured, built
by many agents each in its own branch and SCI context. Work is tasks that improve as they move through a chain of agents
(design, prove, implement, merge after review, retrospect), each agent working from the task as the previous one left
it and editing it for the next. Tasks are mined from the database (slow functions, guessed names, uncontracted
functions, red proofs) and retire themselves when their proofs are green on main. A core of about 10,000 lines where
ordinary work is sub-second.

## 2. The essentials

1. **An honest, short number in every reply.** Committed (FEEDBACK-LOOP stages 1–13, 16; like-for-like cold and warm
   cost per [symbol scope], b37a3ff72). Being cut to one line (§0, the concise performance line): an agent learns it wrote slow software in
   forty tokens, not five hundred.
2. **Tasks worked end to end by Seon agents through the REPL.** A model agent turns a designed task green; root has
   merged three; the automatic path has merged nothing yet (§0, the merge request). The chain of separate agents is the next
   shape (§0, the runtime and the chain experiment).
3. **Red proofs before any assignment.** `seon.task/assign!` runs the proofs on the agent's branch and refuses all-green
   or unobserved proofs (7ce008211, c95848065). 155 of 783 tasks carry a tagged proof (§0). Proof quality is the weak point:
   nearly every failed round traced to the proof or the task, not the implementer, so the chain gives proofs their own
   stage and the catalogue (TASK-CHAIN §4).
4. **Default runs HEAD.** Routine: `bin/seon start --head` per batch of commits; readiness 5.1–5.3 s on a quiet machine (`start --head` with schema transactions measured 32 s on 2026-10-11).
   The lag workarounds are not yet deleted (§0, chores).
5. **Correctness and efficiency over size, for now** (owner, 2026-10-10). Temporary growth is acceptable; Seon agents
   trim it later using the cost data; every change is judged by correctness and measured cost. Growth that is a fence
   is not acceptable (§0 standing rules).

## 3. The method: the chain of agents

The method is the TASK-CHAIN card (`schedule/cards/TASK-CHAIN.md`) on the runtime of
[runtime-follows-db](../research/agent-platform/2026-10-11/runtime-follows-db.md): Mine (a query) → Design (survey,
schemas, contracted stubs) → Prove → Implement (with a send-back right) → Merge (root's merge-request task) →
Retrospective. Each stage is a step task of one subject, worked by a fresh agent the reconciler launches when the
step's needs are done; the handoff is the subject task at main, edited by `my.task/refine!`; a step ends when its proofs
are green, the same rule as any task; its instructions are data on the step; every stage records an explicit decision.
Variations of the pipeline are trees and instructions, compared on the same subject family. The first experiment is two
steps, Design+Prove → Implement (§0, the chain experiment).

Every new mechanism is run by hand once before it is automated: the chain runs of 2026-10-10 found two to five
frictions each at minutes of cost, the cheapest learning of the weekend.

## 4. Done

The honest-measurement task (FEEDBACK-LOOP stages 1–13 and 16); the merge base by `t` (d9eb15c05, aa7ffb17f); the
byte-bounded node cache (12473ca94); the first links of the agent lifecycle (DYNAMIC-CONTEXT stages 1–3: the block
names the namespace, an agent closes at its end, one end message to root); non-root agents never load the shared JVM
(e361536a9); a function and its schema merge as one unit (ff61d0b29); the collection fix (f51240f34). History is in
git and the dated research pages.

## 5. Superseded

The 2026-10-10 step list and the 2026-10-10 evening dependency list (38fce69e3) are replaced by §0. Of that list,
items 1 (schema merge), 2 (target fence), 3 (exact merge), 4 (admission), 5 (idempotent end) were committed on 2026-10-10;
items 2, 3 and 5 are fences and are reverted (§4, Q15); item 6 (one interpreted proof path) becomes §0's interpreted census with the owner's rule that every mismatch is a bug; items 7 (data-first task) and 9–11 (rounds, ROOT-AGENT stages,
triage) wait behind the chain. Research corrections to pass 1 of the replan: `turn.clj` grew 5,622 → 5,888 overnight
and `source.clj` 1,488 → 2,136 (not from 441 and 1,156); the compiled check gate exists (`my.test/check-platform`,
32–45 s); the hand accept path already retires branches.

## 6. Deliberately deferred

- ROOT-AGENT stages 4–13 (grants, root tasks, `decline-merge!`, tick, triage, spend report): `my.root` holds root's
  tools first (§0, the vocabulary cut); the tick waits for three chain runs; triage waits for a judgment duty to occur three times.
  The card's two refuted claims (write-back conflicts vanish with Claude lanes; "a failure leaves nothing written") are
  corrected when it is next touched.
- DYNAMIC-CONTEXT stages 4–7 (shown declarations, working set, history by reference, reflection on a snapshot): after
  guessed names are answered at the failure (§0, guessed names) and only if a chain run shows prompt growth caps agents.
- Publication (a compiled check is 32–45 s measured) and boot's whole-program derivation: platform commits pay the existing check; per-file
  derivation inputs when the chain table shows the cost dominating.
- Merging through git objects or a dedicated checkout (fable-replan §4.3 fix A): the revert removes the fences the
  shared tree motivated; the root fix is committed only if a real collision is observed.
- COST-REG slices 2–3; growth tests and guards; embeddings (after lexical search
  is used and found wanting); the Datahike fork rebase; a stateful lifecycle test suite; a Flow-hosted writer (refused).
- The positive-wording pass over AGENTS.md's remaining negatives: root, in small commits.

## 7. Decisions, by owning topic (every owner ruling, deduped; superseded ones marked)

**Laws and design**
- 2026-10-10 Seon grows by accretion; the four properties and the design order in AGENTS.md are the laws.
- 2026-10-11 NO GUARDS OR FENCES, no exceptions (AGENTS.md c722d8d22); supersedes "every restriction names the failure
  it prevents". The night's sixteen fences are reverted in one batch (Q15); the three root causes they patched are
  committing in the shared tree, two evaluators compared, and database values held across transactions.
- 2026-10-11 Focus: improving the tasks, not more platform machinery; embedding search later.
- 2026-10-10 Correctness and efficiency over size for now; temporary growth acceptable; agents trim later.
- 2026-10-10 Datahike's writer stays as it is (go-loops, group commit); Seon does not batch; Flow procs run on
  platform threads, one per task; accounting closes at `seon.flow/var-process`; the reply is published after
  accounting; `seon.batch` is a misleading name for the eval-request fold (rename belongs to its owner).
- 2026-10-10 Stored data is described in Datahike's terms (entities, attributes, values, datoms).
- 2026-10-10 Merge bases are found by the fork's `t` and `as-of`, never by walking commits (committed d9eb15c05).
- 2026-10-10 Full history is kept with the one-hour retention window; `as-of`, `since` and `history` are unaffected.

**Tasks and the chain**
- 2026-10-10 Work is a chain of separate agents; run by hand first; nail a single task before child tasks (the
  deferral of child tasks ended 2026-10-11 with the task tree below).
- 2026-10-11 The chain is TASK-CHAIN: build it generally, experiment with variations, keep the stages worth their
  calls; agents may edit any subject field; every stage records an explicit decision, `:unchanged` with a reason, never
  an absence. Superseded the same day (runtime-follows-db rounds 1–4): stages are step tasks, not stages of one task;
  "stage proofs" are the step's own proofs; the first experiment's handoff is the reconciler's, not by hand.
- 2026-10-10 Tasks are entities upserted as the chain refines them, with history; author little, derive the rest;
  authored = name, intent, must-read; derived = proofs, interface, schemas, status. Task names are fully namespaced
  symbols. The database is the source of truth; files are lossless write-back, proven by a round trip.
- 2026-10-11 Auto-retire: a task whose proofs are green on main retires itself, derived, never written.
- 2026-10-11 Vocabulary (Q16): agents have `my.task/current`, `refine!`, `send-back!`; success has no verb; root's
  namespace is `my.root`; merge words are git's (`merge!`, `close!`, `:merged`/`:closed`); the queue
  and steps are deleted; home → main, refuted → a red proof, outcome → end, proposal → merge request, renamed now.
- 2026-10-08 A question task declares its answer's Malli schema; the answer is ordinary Datahike data.
- 2026-10-09 Budgets with four meters (calls, CPU, allocation, storage) estimated by the designer; completion is
  automatic when proofs are green.
- 2026-10-11 Guessed names: answer at the failure with the nearest real symbols and calling forms; learn guessed →
  resolved from the agent's next form; link it to the task; a name guessed twice becomes a mined task.

**Runtime, scheduling and the task tree** (runtime-follows-db, owner rounds 1–4, 2026-10-11)
- One path: an agent wakes and runs a task; everything uses it (steps, merge requests, problems, recurring attempts);
  the reconciler, the clock and the heartbeat are the procs that host it, not tasks. Agents never start agents.
- Progress is derived from facts written once (steps, needs, attempt, end); intent is stored on the task
  (`:held :running :paused :stopped :cancelled`, each one reconciler action in Flow's verbs) plus cluster
  `running/paused`; nothing writes "actual" to the database except the heartbeat's audit.
- Order is `needs` only, parallel by default, parents never run; no position; the plan is rendered from needs.
- Instructions are data on the task (steps and problem tasks alike). A stage is a template that is itself a task entry
  (`seon.step/survey`, `define`, `design`, `prove`, `implement` in `resources/seon/tasks/seon.step.edn`), cloned into each
  chain's steps by `seon.task/chain!` (instructions, exit contract and `:seon.task/template` ref copied), whose intent is the
  subject's refined goal, and whose tagged deftest is the step's handoff proof
  (owner, 2026-10-13; [hybrid-lifecycle](../research/agent-platform/2026-10-13/hybrid-lifecycle.md) §2). No stage type
  entity, no template language, no second merge rule.
- Problems are tasks assigned to root by identity, created where their facts already pass; nominal ends wake nobody;
  error signatures and cost regressions go to fresh agents. Merge requests are one problem class.
- One scheduling system: a recurring thing is a task with a cron; one clock proc writes firings; a function firing runs
  in the clock (maintenance, heartbeat); an attempt firing runs on the path; a firing to a live agent is a message
  wake; refused delivery is a problem task. `seon.schedule`'s per-agent proc goes.
- Flow owns process and computation; channels carry only ephemeral data; everything of consequence is a fact, so a
  crash and a resume to any commit are derivations. `start` resumes; `start --paused` for debugging.
- `max-running` is a dial, default 4; agents are to be lightweight: context MB and ms to start are measured targets.
- The cache of a branch's interpreted definitions (`seon.sci.eval/interpret-overlay!` per branch) is the first performance lane; Astra's
  0c3916000 already caches the plan.

**Proofs and tests**
- 2026-10-09 Proof gates scheduling; the rest is an unproven backlog.
- 2026-10-10 Proofs as written: an `are` table with the reported input plus one `is` on a count for a cost task; a
  seeded property only where a one-line law exists; COST-REG slices 2–3, guards and growth tests cut.
- 2026-10-11 How to write good proofs is not yet clear: keep a proof catalogue of good and bad examples with why
  (TASK-CHAIN §4).
- 2026-10-11 Interpreted must agree with compiled for every declaration that is not host-bound; every mismatch is a
  bug, never a verdict class; the gate compares directly; platform changes use the existing compiled check. Supersedes
  the 2026-10-10 "evidence readers accept the agent's evaluator plus compiled" and its tiers (reverted, Q15).
- 2026-10-10 Fixtures are data on the namespace entity (`:each`, `:once`), written by `use-fixtures` and file indexing.
- 2026-10-10 Only reliable, like-for-like cost data is surfaced; one cost entity per [symbol, scope], cold and warm.

**Merge**
- 2026-10-10 Auto-merge on green is the target; 2026-10-11 (Q8, round 3): until criteria for non-controversial merges
  are written, a merge request is a task assigned to root, rendered from the data attached to it; no review mechanism.
- 2026-10-11 The first merge through the path is the round-8 task, rerun by a fresh agent after its two interpreter
  bugs are fixed (Q11; the original merge request went with the reset). Interpreted/compiled mismatches at the gate are bugs (Q3).
- 2026-10-11 One close owner `seon.task/close!` decides a merge request and deletes its branches; the stale merge
  requests and ended agents' branches are cleaned by hand today (Q6).
- 2026-10-10 Commit the Datahike fork's commit-cost change (done, 5acc29e23).

**Measurement and cost**
- 2026-10-11 Concise performance feedback: one line of about forty tokens only when a changed definition got costlier
  than main; request cost as `ms / MB`; the teaching goes in the instructions (Q1). Supersedes the 2026-10-10 "compact
  cost diff always on" as to its size, not its existence.
- 2026-10-10 Numbers are live: each reply shows the request's own values against main's stored values; cost entities
  per function with values replaced in place, no history; schema cost measured once per digest at contract time.
- 2026-10-09 A cost regression against the last green run on main is a red test, not a health note.

**Data, documents and search**
- 2026-10-10 Doc sections are entities identified by path and anchor, with parsed references as facts; files are the
  write-back.
- 2026-10-10 Search: the fork's Lucene secondary index (`:scriptum`) first; vectors (`:proximum`) only after a chain
  run shows a lexical miss.

**Process and roles**
- 2026-10-10 Roles: Claude designs; DeepSeek-flash implements non-platform; Opus implements platform; root reviews and
  merges. 2026-10-11: design is interactive with the owner; lanes implement agreed designs (Q9).
- 2026-10-10 When the owner is not replying, root decides with the recommended option, proceeds and reports; 2026-10-11
  narrows it: pre-approved are bug fixes and work agreed in §0; new design waits.
- 2026-10-10 The whole loop is run by Seon agents eventually: a root agent through `my.root`, agents triage, no spend
  cap with a daily report (deferred behind the chain, §6).
- 2026-10-09 Auto-restart default onto HEAD per batch of commits; then delete the lag workarounds.
- 2026-10-09 Freeze new platform machinery until the REPL-only acceptance passes (host-bound half still open).
- 2026-10-10 Reflection: root reads the record first, then asks the snapshot; by hand.
- 2026-10-11 Re-plan in two passes: analysis and questions, then the plan from the answers (done; this file).

## 8. Open questions

None open. Q1–Q16 of the replan and the three round-3 questions are answered in
[fable-replan](../research/agent-platform/2026-10-11/fable-replan.md) §8 and folded above.
