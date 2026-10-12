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

## 0. Where we are, and the order (revised in place; last 2026-10-12 night at HEAD 61b9534d7, default 61b9534d7)

**Where we are.** A DeepSeek-flash agent writes a correct small change from Seon's context in 2–3 calls when the platform lets it (chains 2 and 4), but nothing has merged through the merge path: five agent changes reached `main` as lanes' git commits (d3de85d72, 3c4f94227, a4b7e6261, 4825cac93, fec3d7737); `my.root/merge!` has merged nothing and default holds 0 assignments. One merge engine exists, `seon.merge/merge!` keyed by the assignment (M1–M3, M5, M6 done); facts merge automatically, and code waits for root because an archive-run JVM has no publication root (`:seon.config.fs/working-root "."`, `m6-notes.md`) and `seon.cluster.agent/next-actions` holds requested branches. Chains 2–4 needed 8, 23 and 17 hand acts, 36 of 48 of them relaunches, forks of deleted predecessors and ends left on branches (M12, M10, the listen route). Default was reset on 7ef4967db and runs 61b9534d7: 8 branches, 0 open recorded errors, `:degraded`. The central round's raw results hold 341 red or error of 2,808 (the same set as aee027806's 346), spanning three commits because the driver rebuilt after four unanswered requests; the driver also forced reruns, so no result was reused (fix uncommitted, lane round-waste). Tasks: 804 on default, 802 in files at e700c5ea4, 173 with a tagged deftest, 629 without; of the phases below, only `seon.test-support-test/a-file-backed-fixture-costs-a-branch` has its red deftest written.

**The goal of this stretch** (owner, 2026-10-12 night): live Seon agents mining tasks from Seon and completing them unattended, measured by hand acts per chain; good code accreted and the code it replaces deleted; the 10,000-line goal de-emphasized. Decided and not re-asked: auto-merge when done, gate green, nothing unmerged and the writer's compare-and-swap passes; everything a step made merges at its end; successors fork main; the branch is the proposal; a test result is reused at its key, red included, evaluator in the key, unknown is not green; runs are data, never program; no batch tier on main; performance proven end to end on the same probe; dependency bumps and wide renames only in a restart batch; model roles per AGENTS.md §4; library and git words.

**The order** (one serial Opus goal lane owns `src/seon/cluster/agent.clj`, `src/seon/merge.clj`, `src/seon/cluster/source.clj` and `src/seon/turn.clj` patches, phase after phase; a Haiku pre-brief for the next phase only; everything else file-disjoint; one proof round and one restart per batch, before a probe, at most four a day):

1. **The first automatic merge of one ordinary task** (Opus, goal lane; publication decided by root, owner deferring, 2026-10-12: `:seon.config.fs/working-root` names the checkout and `seon.merge/publish!` commits only the merged paths with `git commit --only`, as `bin/lane commit` does, under a root-held claim; a merge touching a lane-held path waits as due work). M9 narrowed: `seon.merge/publish!` as derived due work against that root; then M8a: `next-actions` merges a branch holding the agent's merge request when `prepare-merge!`'s gate passes and `:seon.flow.plan/requested?` is deleted with its clauses. M9 is live before M8a enables the merge (the hybrid's task text saying M9 after M8 is corrected here). Red proofs, written first: `seon.cluster.merge-test/an-accepted-merge-is-published-by-the-next-pass` (M9), `seon.cluster.merge-test/a-requested-green-end-merges-and-publishes-with-no-hand-act` (M8a; `seon.task-test/a-done-branch-merges-with-no-merge-request-written-by-the-agent` stays M8's). Target: one Implement step on an interpretable owner merges and publishes with zero hand acts beyond the config act. Unblocks: the goal's missing link. Deletes: the `requested?` hold and root's notice task.
2. **The lifecycle needs no hands** (Opus, goal lane, after 1). M12 (relaunch through `seon.task/ready`), M10 (fork main after the needs merge; supersedes E6), G8 reproduced on a hand-started attempt and fixed at the branch's listen route, M13 (one starter; owner 2026-10-12: an entrant without a task is an agent with no assignment, and `next-actions` derives `:delete-branch` when its end is written on its branch and the branch holds no unmerged program change, the assignment's rule; a small Opus design slice in the agent-loop spec first). Red proofs, written first: `a-capped-attempt-has-no-relaunch`, `a-join-forks-main-after-both-needs-merge`, `a-held-task-opens-no-model-turn`. Target: a two-step chain with at most two hand acts. Deletes: `fork-attempt!`'s predecessor clause, `seed-attempt!`, `attempt-rows-tx`, `:seon.task.attempt/fork`, `agents-to-start`, `host/start!`'s graph start, the two `kept?` conditions.
3. **Restart-safe branches** (Opus, beside 1, files `seon/test.clj` and `seon/schema.clj`). Recorded error 1: `seon.test/clean-request-handle` builds the branch's test execution from the JVM's loaded program, not the branch's fork-point record (proof `seon.test-test/a-branch-test-world-runs-the-jvm-loaded-program-not-its-fork-point-record`, to write); J1/recorded error 7: `seon.schema/register!` refuses a member naming an uncompilable branch predicate (proof `seon.schema-test/a-schema-naming-a-branch-predicate-breaks-the-test-step`, to write). Target: a `start --head` that renames a contracted function leaves open agent branches working; an admitted schema never wedges a branch's test step. Unblocks: restarts with live agents open.
4. **The probe** (root; model calls paid only here). One single-Implement chain after item 1 is live, another after item 2; the subject is admitted only after `my.test/check` runs its proof red (not erroring) interpreted on a probe branch; graded on hand acts, calls and errors by signature against chain 4 (16 hand acts). Cadence (owner, 2026-10-12): run a chain whenever it can teach us something that improves the speed and focus of the work; runs are cheap.
5. **Tests judged once** (Sonnet gating lane; Opus for `seon.test/select` and runner semantics). Commit lane round-waste's driver fix; R5 → R6 → R7 (one red/unknown judgment, base runs kept), R4, R10 (`seon.operator/check-verdict`; proof `a-check-red-main-holds-red-does-not-refuse`, to write), R9; the central round once per restart batch on one pinned revision with its store retained, reads reused. Target: a check refuses only on a transition; an unchanged 25-test request costs selection (26 ms), not bodies (3.2 s); `reused 0` disappears from check records. Deletes: the boolean fork point, `--accept-red-before`, the scratch recovery driver.
6. **The Prove step's product merges; done has no fact** (Opus, goal lane, after 2): M7 (designed reds as data; `change-reds` deleted with `accept-merge!`'s call), the rest of M8 (`request-merge!` ×2, `end-merge-request`, the request schema; the dead chain `land-end!`, `land-ended!`, `landing-tx`, `root-execution`, `land!`, `:seon.config.root/land?` is deleted), H1 (instructions shown whole). Then the six-step tree unattended (M-final, `seon.cluster.agent/the-real-chain-runs-with-no-nudges`).
7. **Costs a chain step or a round pays** (Astra, file-disjoint, one at a time; the fixture hour first, owner 2026-10-12): the fixture hour (28 tests over 20 GB, 58% of the round's 1,826 GB; proof `a-file-backed-fixture-costs-a-branch`, red), the first merge in a JVM (1,275 ms / 2.3 GB), the defn request's test step (109 ms of 460 ms), the warm request's unexplained 1.34× since e9bcf36d2. Each proven by `seon.profile/measure` on the same end-to-end probe before and after; a regression is not committed.
8. **Hygiene at each batch** (Sonnet): retire the four subject-gone tasks (not M9's), re-point the five stale must-reads, release dead claims (`astra-one-system`, `astra-review-merge-base`, `root-report`, `web-check`), delete probe branches, collect the store once, update this table in the commit that moves a phase. **Paused**: M4 (until a conflict is observed), M11, M14–M18, E3, E5, R12, the `src` CN renames (one batch, with M18), the printer duplicates (`seon.render.value/bounded-text`), the `settle-batch!` retries.

### Phase status (every commit that moves a phase updates this table)

| Phase | Status | Evidence | Next |
|---|---|---|---|
| P1, P2, P4, P5, P6 | done | b09c02b29, 9d25711e7, 031125438, be32e7501, 015673f47 | |
| P3, P7–P10, P-final | absorbed into done-and-merge | P7 done in M5 (1b09d8043); `seon.test/change-reds` still called by `accept-merge!` (item 6) | M7, M12, M13, M-final |
| U0–U7 | superseded by M1–M18 | | |
| M1, M2, M3, M5, M6 | done | 3fe23b2c0, 346645d56; 3eca1a962; 767551c7b; 1b09d8043, c74ef3437; 7c3077b9e, 3da1f6449 (`m6-notes.md`; a requested branch waits for root until item 1) | |
| M9 (narrowed), M8a | done, live after root's restart | 05d3eb2ed (M9: `seon.merge/publish!` as due work, proof red→green, publishing pass 260 ms / 175 MB); M8a in the commit after it (`requested?` deleted; requested ends merge through the gate, then publish) | restart default; probe one Implement chain (item 4) |
| M12, M10, M13 | filed; deftests absent; M13's entrant end decided (item 2) | | item 2 |
| M7, M8 rest | M8 rest started: S5 done (2736c187d, `leaf-done` reads `:merged`); the dead chain (`land-ended!`, `land-end!`, `landing-tx`, `root-execution`, `land!`, `:seon.config.root/land?`) is deleted (ad20b764d); publication's signal decided at the authority (root's option 1): `seon.merge/merge!` writes `:seon.task/merged-declarations` in the merge's transaction and `publication-due?` reads it, so a facts-only merge is never due; the gate's proofs from the assignment and the verb's deletion not started (`m8-rest-notes.md` (c)); M7 not started | 2736c187d, ad20b764d, the (b) commit; `m8-rest-notes.md`, `m8-s5-db-flow-test.patch` | (c): the gate takes the task's proofs, then delete the verb |
| M4 | paused | no conflict in four chains; `conflict-task!` stays until one is observed (`prebrief-m4.md` holds the design question for then) | when observed |
| M11, M14–M18, M-final | filed | | after item 6 |
| G1, G3, G4, G5, G9 | done | 6a687ac17, 9116692ea, 5640aef92, ea27cde53, 98720b4eb | |
| G6, G7, G8 | open | chain 3 and 4 friction tables; owners M12, M10, the listen route | item 2 |
| R1, R2, R11 | done | 50ca41030, 474174b52, 6433c7593 (live since 7ef4967db) | |
| R3 | replaced by the no-key rule | 51a5e159e (0 `defmulti` in src); no implementation yet | when an agent uses one |
| round-waste (driver reuse) | committed 3c88f7a6a; lane still running | round-waste.md: 3,230 ms → 26 ms for 25 unchanged tests | item 5 |
| R5, R6, R7, R4, R10, R9 | filed; R10's deftest absent | | item 5 |
| R8 | filed | after M7 | item 6 |
| R12 | paused | | |
| E1, E2, E4, E7 | done | f424382cd; 172b23e7c; bc347d583 (program-context kind 29.8 MB live after reset), e1d2854cb (script contracts) | E4's private-atom proof open |
| E6 | superseded by M10 | | |
| E3, E5 | paused | not on the chain's path | |
| Recorded errors before the reset (9) | 4 and 8 fixed (61b9534d7), 1 and 7 fixed (64138bf51), 2 → M10, 3 filed, 6 and 9 not reproduced | `db-clean/root-causes.md`, `faults-before-reset.edn` | item 3 |
| H1–H10, AW0–AW7 | see their documents | | |

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
  namespace is `my.root`; merge words are git's (`request-merge!`, `merge!`, `close!`, `:merged`/`:closed`); the queue
  and steps are deleted; home → main, refuted → red-first, outcome → end, proposal → merge request, renamed now.
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
