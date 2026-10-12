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

## 0. Where we are, and the order (revised in place; last 2026-10-12 night at HEAD f2422dcd8; follows [hybrid-chains](../research/agent-platform/2026-10-12/hybrid-chains.md))

**Where we are.**
- Chains 5–9 ended `:capped` or `:stalled` with 0 hand acts after launch and nothing merged; five agent changes reached `main` only as lanes' git commits. Default holds 808 tasks, none with children or a step.
- Chain 9 shows the first failure is the interface, not the context: 32 of 40 replies were native tool-call markup for tools that do not exist (`seon.ai/request-body` declares no tools; `completion-text` reads only `content`). Round 4's cold reads of the context all answered CAN DO WITH SEARCHING.
- Three code facts stop a two-step chain whatever the model: a Prove product never merges (`seon.merge/needed?`), a successor forks a deleted branch (`seon.cluster.agent/fork-attempt!` throws), a capped step is terminal (`seon.task/ready`).
- One merge engine exists, `seon.merge/merge!` keyed by the assignment, with `seon.merge/publish!` as due work; facts merge automatically and a code merge waits for root's restart and a probe. `my.root/merge!` has merged nothing.
- Default runs 61b9534d7 (`:degraded`, 8 branches, 0 open recorded errors) and reboots with a recorded error on `install-row!`, which is foreign. The central round holds 341 red or error of 2,808.

**Done tonight.** A task is its name, an intent with inline references and optional needs: `:seon.task/references` is derived, must-read and evidence are deleted (8d14faeee); `:seon.task/kind` is retired (4fded61c0); a merged task is written back to its task file and the reload merges tasks three-way (7c87dfb8e, d50eb5cec); `my.task/refine!` rewrites the held task's intent on the agent's branch (7e2544e38); a referenced function resolves to its whole source and contract (ba4d6c34e); help is eight titled groups (8a3eae77a); a check's JVM binds an ephemeral web port (505fabdf8); the agent's merge verb and the merge request are deleted, so root's `db<->flow` merges a done branch with the proofs from the assignment's task (e8d0a41c9).

**The goals** (owner, 2026-10-12 night; briefed in [plan-chains-opus](../research/agent-platform/2026-10-12/plan-chains-opus.md)), in order:
1. **Chains run unattended**, measured by hand acts after the launch act (target 0), calls, tokens and errors by signature.
2. **Context useful by construction**: an agent finds what it needs in its first calls; the block names the chain, and a reference shows its referent whole.
3. **Tasks improve as work proceeds**: each step refines the task for the next, durably (branch facts, merge, task file).
4. **Agents mine Seon for tasks** (slow functions, guessed names, uncontracted functions, red proofs): not started; nothing serves it before goal 1 holds.

Decided and not re-asked: auto-merge when done, gate green and the writer's compare-and-swap passes; a step's product merges at its end; successors fork main after their needs merge; the branch is the proposal; a test result is reused at its key, red included; runs are data; performance is proven end to end on the same probe; dependency bumps and wide renames only in a restart batch; model roles per AGENTS.md §4; library and git words. The 10,000-line goal is de-emphasized.

**The order: hybrid phases H0–H7**, simplest first. Each is accepted by a real DeepSeek run (the chain-experiment method: cap 40, one launch act, nothing by hand; graded on hand acts after launch, calls before the first `defn`, tokens, errors by signature, and whether each step reached `:done` and `:merged`), after a Haiku cold read of the real first call (a NO stops the paid run). H0 and H1 merge first and are file-disjoint; H2–H4 are serial on `task.clj`, `context.clj`, `agent.clj` and `merge.clj`. Red proofs are written with each phase's task; one proof round and one restart per batch.

| Phase | What | Acceptance run |
|---|---|---|
| H0 | The model's one native tool, `eval` `{"code": string}`, declared in `seon.ai/request-body`; its `tool_calls` (streamed fragments too) read as reply text into `seon.turn/planned-sources` | a single-step run: no markup replies, first `defn` within a few calls |
| H1 | Help tells the truth: Done differs by step, Prove tags the successor's name, automatic merge, one tool; only the current step's Done group shows | the same run: calls before the first `defn` and first-call tokens fall against chain 9 |
| H2 | Chain data: `:seon.task/instructions` returns per step, `seon.task/effective` is `clojure.core/merge` down `:children`, `:seon.task/execution` inherits so one fact launches, `refine!` writes intent on the subject and instructions on its own step | a two-step chain launches from one fact; each step shows its goal and its own work |
| H3 | `:seon.context/chain` in the block: the subject, the steps before with what each merged, the steps after | the Implement agent does not rediscover what Prove wrote |
| H4 | M7 (a Prove step's designed reds merge as its product; `seon.merge/needed?` and `seon.test/change-reds` leave) and M10 (successors fork main; `:seon.task.attempt/fork`, the predecessor clause and `kept?`'s pin leave in one commit): the two-step chain with zero hand acts | Prove → Implement on the fixture chain, both `:merged`, 0 hand acts |
| H5 | A capped or stalled step continues as a new assignment forking the ended commit and keeping the merge base, bounded by the `:seon.config.run/relaunches` dial; a monotone `:seon.task/attempts-made` | a chain whose first attempt caps finishes with no hand act |
| H6 | Context fits: a name resolved at top level is cited by name below, the printer never cuts inside an operation form, the token budget is raised only if a measured cut remains | first-call tokens and cuts against H1's run |
| H7 | The real chain on a mined subject, task file entry written back with the refinement (`seon.cluster.agent/the-real-chain-runs-with-no-nudges`, reshaped) | 0 hand acts, every step `:merged`, subject done |

Traps: `seon.cluster.agent/write-merge-base!` is the only merge-base writer on the chain path (deleting it makes every red "already red"); keep `seed-attempt!` (H5) and `attempt-rows-tx` (`assign!`); content merges down `:children` only, never along `:needs`; help teaches installed forms only.

**Beside the phases** (file-disjoint lanes, never ahead of them): restart-safe branches (recorded errors 1 and 7: `seon.test/clean-request-handle` and `seon.schema/register!`; proofs to write); tests judged once (R5–R7, R4, R10, R9 and the round-waste driver, committed 3c88f7a6a); costs a round pays (the fixture hour first, proof `seon.test-support-test/a-file-backed-fixture-costs-a-branch`, red); hygiene at each batch (retire subject-gone tasks, re-point stale references, release dead claims, delete probe branches). **Paused**: M4, M11, M14–M18, E3, E5, R12, the `src` CN renames, the printer duplicates.

### Phase status (every commit that moves a phase updates this table)

| Phase | Status | Evidence | Next |
|---|---|---|---|
| H0–H7 | planned; hybrid written, no phase started | hybrid-chains.md (f2422dcd8) | H0, H1 |
| P1, P2, P4–P6; M1–M3, M5, M6; G1, G3–G5, G9; R1, R2, R11; E1, E2, E4, E7 | done | b09c02b29, 9d25711e7, 031125438, be32e7501, 015673f47; 3fe23b2c0, 3eca1a962, 767551c7b, 1b09d8043, 7c3077b9e; 6a687ac17, 9116692ea, 5640aef92, ea27cde53, 98720b4eb; 50ca41030, 474174b52, 6433c7593; f424382cd, 172b23e7c, bc347d583, e1d2854cb | |
| M9, M8a, M8 rest (S5, publication signal, gate from the task, merge verb deleted) | done; live after root's restart | 05d3eb2ed, 2736c187d, ad20b764d, 662208736, 7a4fba11c, fa0b0b6cb, e8d0a41c9 | restart default |
| Task data: one-field tasks, kind retired, write-back, `refine!`, resolver, help | done | 8d14faeee, 4fded61c0, 7c87dfb8e, d50eb5cec, 7e2544e38, ba4d6c34e, 8a3eae77a | H1, H2 correct the help's stale lines |
| M7, M10 | in H4 | | H4 |
| M12, G6–G8 | in H5 (M12), H4 (M10 and G8 via the listen route) | chain 3 and 4 friction tables | H5 |
| M13, M16 | filed; not on the two-step path | | after H7 |
| M4 | parked | no conflict in four chains; `conflict-task!` stays until one is observed | when observed |
| M11, M14, M15, M17, M18, M-final | filed; M-final is H7 | | after H7 |
| Old item list (merge-and-publish first, lifecycle, probe, Prove merges, hygiene) | replaced by H0–H7 | | |
| Restart-safe branches; tests judged once; costs a round pays | beside the phases | `round-waste.md`: 3,230 ms → 26 ms for 25 unchanged tests | per lane |
| R3, E3, E5, E6, R12 | R3 replaced by the no-key rule (51a5e159e); E6 superseded by M10; E3, E5, R12 paused | | |
| Recorded errors before the reset (9) | 4 and 8 fixed (61b9534d7), 1 and 7 fixed (64138bf51), 2 → M10, 3 filed, 6 and 9 not reproduced | `db-clean/root-causes.md` | |

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
- Instructions are data on the task (steps and problem tasks alike); no templates, no stage types.
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
