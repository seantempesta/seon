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

## 0. Where we are, and the order (revised in place; last 2026-10-11 afternoon)

**Where we are.** A DeepSeek-flash agent turns a small ordinary task red → green from the context Seon generates (chain
runs 3–5, rounds 1–8: twelve agent runs, seven green). Root has merged three such changes by hand (d3de85d72,
3c4f94227, a4b7e6261). The automatic landing path (`seon.cluster.source/land!` from a green end) ran twice on real data
and landed nothing: both refusals compared an interpreted red with a compiled baseline (round 8, merge task
`57ad6cd33297`: two tests red the same way interpreted on unchanged main, gate run labelled `:compiled`; be337100e).
The night of 2026-10-10 added sixteen fences to that path for failures never observed; the owner's law of 2026-10-11
(AGENTS.md, NO GUARDS OR FENCES, c722d8d22) reverts them. A retention collection broke the REPL entrance because the
cluster context held a database value across it; fixed at the owner (f51240f34), with the general rule (hold a basis,
never a value) still to apply to `::loaded-database` and execution handles. The chain of separate agents per task
(the owner's design, card TASK-CHAIN) has never run: agents cannot edit their task at the REPL yet.

**Since the rewrite (2026-10-11 afternoon):** the fence revert landed (c53c9306e: src −593, test −503, resources
−107 lines) and was verified against a full compiled tier before and after (d9715071e, 1aad413c1:
[revert-verification](../research/agent-platform/2026-10-11/revert-verification.md)): 2,398 → 2,365 green; of 40 lost,
13 deleted with the machinery, 3 kept red as root-fix proofs, 22 bulk-run noise (green alone), **2 real regressions**
(filed 0256a98b5, being fixed in item 3). Default was restarted on HEAD and reset: 3 branches, 1 agent (root), 705
tasks from the task files, 0 merge requests. Round 8's merge request went with the reset.

**Build sequence (owner, 2026-10-11 evening; supersedes the order of items 5-7 below where they differ).** One focus,
at most two lanes on disjoint files, the start-a-flow benchmark rerun after each landing. Designs:
[hybrid-start-a-flow](../research/agent-platform/2026-10-11/hybrid-start-a-flow.md) (round 1),
[hybrid-agent-world](../research/agent-platform/2026-10-11/hybrid-agent-world.md) (round 2),
[r1-db-flow-design](../research/agent-platform/2026-10-11/r1-db-flow-design.md).
1. The combined cold-context regression (322 MB > 180 MB after H2+H9+H10).
2. Round 1 H3-H5: one program identity, no duplicate overlays, one fork per agent.
3. Round 2 P0-P1: the `:seon.cluster` partition and the pure cherry-pick.
4. S3 and R1 slices: the attempt as the launch write; renames; `plan`; `seon.flow/db<->flow`; `start-flow!` (H6-H7).
5. Round 2 P2/P3/P7 and IO effects inside each agent's own graph (proof: a whole task runs on its graph's proc threads;
   pausing the graph halts all of it): end copy-back and branch deletion, messages over channels (inbox 64, a dial).
6. The first chain experiment: a two-step tree set `:running` by hand.
7. W2: a test run is a task; members under `d/with`, in parallel under a dial.
Implementers: Sol 6.1 (medium) for specified phases, Opus for the one REPL path's semantics, Astra for hard performance,
Haiku for digs; design rounds are Haiku dig, Opus and Sol plans, Fable hybrid. Conflicts on copy-back keep main's value
and become root's problem task unless the entity schema declares a rule.

**The order.** Each item names its owner and what it unblocks; an item starts when its dependency has landed unless
marked "beside". Design is interactive: a lane implements an agreed design; when the owner is unreachable, bug fixes and
work already agreed here are pre-approved, and a new design question waits.

1. **Done: revert the night's fences** (c53c9306e; Q15). One batched revert of the sixteen fence
   commits since 2026-10-10 20:30, generated with `git revert --no-commit` in a throwaway shared clone under `tmp/`,
   applied to claimed files; `seon.turn/end-task!` rewritten to send the end message and close the agent in one
   transaction; the observed-failure tests kept as red proofs for root fixes; about −1,500 lines. Kept: the interpreter
   fixes, f51240f34, e71e60a47, `land!`, `land-end!`, the dial, typed refusals, `:seon.test.run/evaluator`. The four
   fences outside the night (`bin/lane`'s commit lock, archive and pin sealing, `:root-refused`, the `"root"` string
   check) each get a root-cause task. Unblocks 2.
2. **Done: verification and a database reset** (d9715071e, 1aad413c1, 0256a98b5). One central test round on the reverted HEAD;
   schema retirement proven on a branch of the live store; then `bin/seon reset` (nothing in the store is sacred;
   the renames of item 5 need it anyway) and default restarted on HEAD. Unblocks 3–5.
3. **The round-8 interpreter bugs, then the first landing** (Opus, owners of `seon.sci.eval`, `seon.test`; Q3, Q11).
   The two refusing tests are red the same way interpreted on unchanged main, so they are interpreter bugs: a nil
   connection supplied on the interpreted path, the failure-facts reader mismatch; and the gate labels its run by agent
   id (`seon.test/run-request`), a bug: label each member by its actual resolution. Red proofs: the round-8 tests
   interpreted. With them, the revert's two regressions: `seon.turn/land-end!` passes a nil root branch
   (`seon.turn/a-landing-acquires-roots-world-when-root-has-no-branch-row`) and the root page misses a newly adopted
   commit (`seon.render.web/the-root-page-shows-a-newly-adopted-commit-after-the-fence-revert`); and the dial
   `:seon.config.root/land?` off. Round 8's merge request went with the reset, so the first landing is the round-8 task
   rerun by a fresh DeepSeek agent: its green end records a merge request, root works that task and merges. Unblocks 7.
4. **One close owner** (Sonnet, with item 5's sweep; Q6). `seon.task/close!` (the renamed `decide!`) closes a merge
   request (`:merged`, or `:closed` with a reason) and deletes its merge branch and the source branch, closing the
   agent; `my.root/merge!` and root's decline both end in it; an ended agent with no open merge request deletes its
   branch at its close. The by-hand cleanup is moot: the reset removed the stale merge requests and branches.
5. **The vocabulary cut, all renames now** (Sonnet, scripted sweep; Q16, catalogue §3–§4). Agent side:
   `my.task/current`, `my.task/refine!`, `my.task/send-back!`, `my.task/create!` (steps with needs and instructions);
   success has no verb. Delete the queue (`my.task/open`, `claim!`), the old step components (`plan!`, `step!`,
   `done!`, `seon.task/steps!`, `:seon.step/*` and the old `:seon.task/steps` schema; child tasks take the new name `:seon.task/children` in
   item 6 then takes over) and `seon.task/resolution`'s fault and signal clauses. Root's namespace `my.root`:
   `instructions`, `merge-requests`, `merge!` (over `land!`), `close!`, `ended`; no `launch!` (the reconciler launches);
   `seon.task/request-merge!` → `request-merge!`, `decide!` → `close!`, outcomes `:merged`/`:closed`. Stored attributes with
   their functions: home → main, refuted → red-first, outcome → end, proposal → merge request. After 2. Unblocks 6.
6. **The runtime follows the database** ([runtime-follows-db](../research/agent-platform/2026-10-11/runtime-follows-db.md),
   settled by the owner in four rounds, 2026-10-11; its §10 is the build order, 22 cards of about fifteen minutes each
   with a red proof, filed as tasks in `resources/seon/tasks/`). One path: an agent wakes and runs a task. A subject
   task owns step tasks (`:seon.task/children`), siblings run in parallel unless one `:seon.task/needs` another, a parent
   never runs; each step carries its own `:seon.task/instructions` as data; the frontier ("everything that should be
   running") is two reads (`seon.task/frontier`, design-probes P1); intents `:seon.task/execution` ∈ `#{:held :running :paused :stopped
   :cancelled}` plus cluster `running/paused`, each one action of the reconciler (`seon.cluster.agent/armer-step`
   generalized), the only thing that starts graphs, under the dial `:seon.config.agent/max-running` (default 4; per-agent
   cost in context MB and ms to start is a measured target of the path); the attempt row is the launch's write and the
   red-first proofs run in the agent's first pass; one clock proc fires cron tasks (a function firing for maintenance and
   the heartbeat, an attempt otherwise); problems are tasks assigned to root (fresh agents for error signatures and cost
   regressions), nominal ends wake nobody; the plan is rendered from needs, never stored; `start --paused` for
   debugging; the interpreted-overlay cache is the first performance lane. The probes ran (design-probes.md): the frontier is
   two reads, P2/P3 retired into R1/R4/D3, a `seon.db/q` stack-overflow bug filed (B1). Order: S1 after item 5, then
   S2–S3, R1–R4, C1–C3, Q1–Q3, D1–D4 as §10 says. Unblocks 7.
7. **The first chain experiment** (root; Q13, TASK-CHAIN §5) after R1 and S3: one small existing defect with settled
   policy and an existing symbol; root creates a two-step tree, Design+Prove → Implement; the reconciler launches both in
   turn, the successor from the predecessor attempt's commit; root works the merge request and the retrospective.
   Measured per step: calls, tokens, errors by signature, send-backs, the path's seams, whether the successor needed root.
   Then the six-step tree runs unattended. Unblocks the loop as the owner described it.
8. **The merge request is a task assigned to root** (Q8, round 3). No review mechanism: the merge request carries its
   data (the diff since the merge base, every reached test with its result, the proofs, the intent and decisions, the
   merge instructions), root's context renders it like any agent's task, and root's work ends in `my.root/merge!` or
   `close!`. Building it is making that data complete and its render good. The dial stays off until criteria for
   non-controversial merges are written from what root's merges find. Beside 3–7.
9. **Guessed names are answered at the failure** (Sonnet, `seon.error` owner; Q7, TASK-CHAIN §6b). The refusal names
   the nearest real symbols across public declarations and `my.*` (name parts, not only spelling distance in the guessed
   namespace) with their calling forms; tool markup stripped at read; the agent's next successful form records guessed →
   resolved and links it to the task; a name guessed by two agents becomes a mined task. Measured by occurrences per
   round; a help line only if the count does not fall. Beside 6.
10. **The concise performance line** (Sonnet, `seon.batch` owner; Q1). One line of at most about 40 tokens, only when a
    definition the agent changed got costlier than main (`slower: seon.foo/bar 3.1× main (alloc 40→124 MB, 12k
    calls)`); request cost as `ms / MB` on one line; `seon.batch/cost-diff`'s 500 tokens go. The teaching moves into
    the Implement instructions ("ask what the work is proportional to"); tasks where cost matters carry a cost proof.
    Beside 6.
11. **The interpreted census as a bug list** (Opus, beside everything; Q3). Every interpreted-only red of a declaration
    that is not host-bound is a bug at its owner: the 110 remaining classes largest first; the ~50 law-breaking tests
    (`with-redefs`, `alter-var-root`, `intern`) rewritten under `seon.test-census-test/no-test-redefines-a-var`;
    host-bound cases declared as data. Platform changes are checked by `my.test/check-platform` (32–45 s measured).
12. **Cost that follows changed inputs** (owner-file lane, beside). `install-row!` 7.38× main, `arm-first-loads!`
    14.8×, a ten-read query at 650 MB, a test run at 0.5–4.6 GB, `land!` 5–11 GB: acquisition that does not reuse the
    program-only base by identity (tasks filed: `seon.sci.eval/install-row-is-twice-as-slow-after-the-stale-var-fix`,
    `seon.test/arm-first-loads-costs-many-times-its-main-baseline`,
    `seon.task/one-test-run-at-the-repl-allocates-over-a-gigabyte`,
    `seon.context/one-agents-current-state-costs-hundreds-of-megabytes`). Hold a basis, never a value
    (`seon.sci.eval/a-context-holds-the-basis-it-acquired-not-the-database-value`,
    `seon.db/a-failed-projection-derivation-stays-cached-under-its-commit`).
13. **Chores beside, never ahead**: the two reds on clean HEAD (`seon.config-test/the-default-document-has-one-canonical-
    complete-location`, `seon.db-test/equal-declaration-content-shares-one-projection-across-values`); FEEDBACK-LOOP
    stages 14, 15, 17; the lag workarounds; one `:seon.source/landing` value per attempt so landing state is one read;
    the `tmp/` dispositions.

**Standing rules** (deduped; each names its reason):

- **No guards or fences** (AGENTS.md, c722d8d22). A change that adds a check, lock, refusal class, retry, verdict tier
  or recovery path beside a mechanism is refused in review; the design that makes the failure impossible replaces it.
  A fence found in code is a defect with a root-cause task.
- **Design is interactive.** The owner decides designs in conversation; lanes implement agreed designs; bug fixes and
  work agreed in this list are pre-approved when the owner is unreachable; a new design question waits for the owner.
- **Merge requests are root's tasks** until criteria for automatic merges are written (Q8).
- **One probe always in flight.** One lane always serves the loop (a landing, a chain run); the head of the list is
  work with a named owner. Rounds are runs of the chain; a run is graded against the previous one before the next.
- **A round's tasks are small ordinary owner functions picked by reach** until the chain runs; the instrument's own
  defects are lane work, never round tasks.
- **Progress is credited at completed guarantees**: a landed primitive is "available"; a path-only commit is not branch
  evidence; proof-only green is not done; an interpreted green is feedback, not the gate's verdict.
- **A finding is filed the same day with a red tagged proof** (a row in an existing table where one fits) or it is
  invisible; a friction met twice changes an entrance rule or an existing proof in the same beat.
- **Errors are judged by their producer**: the same signature across agents is a Seon defect; every checkable AGENTS.md
  law has a test.
- **Evidence outlives chat**: one dated evidence page per day under `docs/research/agent-platform/<date>/`; cards and
  this plan are revised in place; `tmp/` holds nothing that matters and is never swept by age or name.
- **Lanes**: claim before editing; delete with `rm`, never `git rm`; leave nothing staged; a dependency bump lands last
  in a batch, then one restart; a closing lane leaves one committed evidence link and a disposition per scratch root.

**Merges, host-bound tests and the first task (owner, 2026-10-11, round 3):**

- **The merge request is a task assigned to root; no new mechanism.** `:seon.config.root/land? false`. A green end
  records the merge request; everything root needs to judge it is attached to that task as data (the diff, every reached test with its result,
  the proofs, the task's intent and decisions, the merge instructions) and root's context renders it through render
  functions and the value printer. Root works it as any agent works its task; `my.root/merge!` or `close!` ends it; there is
  no review function, flag or `:reviewed` fact. Missing data in that rendering is a defect of the render or the task, fixed at its producer.
- **Host-bound tests are derived.** Whether a test can run interpreted is decided by the same analysis that decides it
  for functions (what the body references, from SCI's and clj-kondo's reading of it), never authored.
- **The experiment's first task** is a mined open task with a reproduction form and an existing owner symbol, picked by
  reach.

## 1. The goal

Software that grows by accretion: open data under named schemas, stable names, every call checked and measured, built
by many agents each in its own branch and SCI world. Work is tasks that improve as they move through a chain of agents
(design, prove, implement, merge after review, retrospect), each agent working from the task as the previous one left
it and editing it for the next. Tasks are mined from the database (slow functions, guessed names, uncontracted
functions, red proofs) and retire themselves when their proofs are green on main. A core of about 10,000 lines where
ordinary work is sub-second.

## 2. The essentials

1. **An honest, short number in every reply.** Landed (FEEDBACK-LOOP stages 1–13, 16; like-for-like cold and warm
   cost per [symbol scope], b37a3ff72). Being cut to one line (§0 item 10): an agent learns it wrote slow software in
   forty tokens, not five hundred.
2. **Tasks worked end to end by Seon agents through the REPL.** A model agent turns a designed task green; root has
   merged three; the automatic path has landed nothing yet (§0 items 3, 8). The chain of separate agents is the next
   shape (§0 items 6–7).
3. **Red proofs before any assignment.** `seon.task/assign!` runs the proofs in the agent's world and refuses all-green
   or unobserved proofs (7ce008211, c95848065). 119 of 722 tasks carry a tagged proof. Proof quality is the weak point:
   nearly every failed round traced to the proof or the task, not the implementer, so the chain gives proofs their own
   stage and the catalogue (TASK-CHAIN §4).
4. **Default runs HEAD.** Routine: `bin/seon start --head` per landing batch; readiness 5.1–5.3 s on a quiet machine (`start --head` with adoption measured 32 s on 2026-10-11).
   The lag workarounds are not yet deleted (§0 item 13).
5. **Correctness and efficiency over size, for now** (owner, 2026-10-10). Temporary growth is acceptable; Seon agents
   trim it later using the cost data; every change is judged by correctness and measured cost. Growth that is a fence
   is not acceptable (§0 standing rules).

## 3. The method: the chain of agents

The method is the TASK-CHAIN card (`schedule/cards/TASK-CHAIN.md`) on the runtime of
[runtime-follows-db](../research/agent-platform/2026-10-11/runtime-follows-db.md): Mine (a query) → Design (survey,
schemas, contracted stubs) → Prove → Implement (with a send-back right) → Land (root's merge-request task) →
Retrospective. Each stage is a step task of one subject, worked by a fresh agent the reconciler launches when the
step's needs are done; the handoff is the subject task at main, edited by `my.task/refine!`; a step ends when its proofs
are green, the same rule as any task; its instructions are data on the step; every stage records an explicit decision.
Variations of the pipeline are trees and instructions, compared on the same subject family. The first experiment is two
steps, Design+Prove → Implement (§0 item 7).

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
items 1 (schema merge), 2 (target fence), 3 (exact landing), 4 (admission), 5 (idempotent end) landed on 2026-10-10;
items 2, 3 and 5 are fences and are reverted by §0 item 1 (Q15); item 6 (one interpreted proof path) becomes §0 item
11 with the owner's rule that every mismatch is a bug; items 7 (data-first task) and 9–11 (rounds, ROOT-AGENT stages,
triage) wait behind the chain. Research corrections to pass 1 of the replan: `turn.clj` grew 5,622 → 5,888 overnight
and `source.clj` 1,488 → 2,136 (not from 441 and 1,156); the compiled check gate exists (`my.test/check-platform`,
32–45 s); the hand accept path already retires branches.

## 6. Deliberately deferred

- ROOT-AGENT stages 4–13 (grants, root tasks, `decline-merge!`, tick, triage, spend report): `my.root` holds root's
  tools first (§0 item 5); the tick waits for three chain runs; triage waits for a judgment duty to occur three times.
  The card's two refuted claims (write-back conflicts vanish with Claude lanes; "a failure leaves nothing written") are
  corrected when it is next touched.
- DYNAMIC-CONTEXT stages 4–7 (shown declarations, working set, history by reference, reflection on a snapshot): after
  guessed names are answered at the failure (§0 item 9) and only if a chain run shows prompt growth caps agents.
- Publication (a compiled check is 32–45 s measured) and boot's whole-program derivation: platform landings pay the existing check; per-file
  derivation inputs when the chain table shows the cost dominating.
- Landing through git objects or a dedicated checkout (fable-replan §4.3 fix A): the revert removes the fences the
  shared tree motivated; the root fix lands only if a real collision is observed.
- COST-REG slices 2–3; growth tests and guards; embeddings (after lexical search
  is used and found wanting); the Datahike fork rebase; a stateful lifecycle test suite; a Flow-hosted writer (refused).
- The positive-wording pass over AGENTS.md's remaining negatives: root, in small commits.

## 7. Decisions, by owning topic (every owner ruling, deduped; superseded ones marked)

**Laws and design**
- 2026-10-10 Seon grows by accretion; the four properties and the design order in AGENTS.md are the laws.
- 2026-10-11 NO GUARDS OR FENCES, no exceptions (AGENTS.md c722d8d22); supersedes "every restriction names the failure
  it prevents". The night's sixteen fences are reverted in one batch (Q15); the three root causes they patched are
  landing in the shared tree, two evaluators compared, and database values held across transactions.
- 2026-10-11 Focus: improving the tasks, not more platform machinery; embedding search later.
- 2026-10-10 Correctness and efficiency over size for now; temporary growth acceptable; agents trim later.
- 2026-10-10 Datahike's writer stays as it is (go-loops, group commit); Seon does not batch; Flow procs run on
  platform threads, one per task; accounting closes at `seon.flow/var-process`; the reply is published after
  accounting; `seon.batch` is a misleading name for the eval-request fold (rename belongs to its owner).
- 2026-10-10 Stored data is described in Datahike's terms (entities, attributes, values, datoms).
- 2026-10-10 Merge bases are found by the fork's `t` and `as-of`, never by walking commits (landed d9eb15c05).
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
- The interpreted-overlay cache (`seon.sci.eval/interpret-overlay!` per branch) is the first performance lane; Astra's
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

**Merge and landing**
- 2026-10-10 Auto-merge on green is the target; 2026-10-11 (Q8, round 3): until criteria for non-controversial merges
  are written, a merge request is a task assigned to root, rendered from the data attached to it; no review mechanism.
- 2026-10-11 The first landing through the path is the round-8 task, rerun by a fresh agent after its two interpreter
  bugs are fixed (Q11; the original merge request went with the reset). Interpreted/compiled mismatches at the gate are bugs (Q3).
- 2026-10-11 One close owner `seon.task/close!` decides a merge request and deletes its branches; the stale merge
  requests and ended agents' branches are cleaned by hand today (Q6).
- 2026-10-10 Land the Datahike fork's commit-cost change (done, 5acc29e23).

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
- 2026-10-09 Auto-restart default onto HEAD per landing batch; then delete the lag workarounds.
- 2026-10-09 Freeze new platform machinery until the REPL-only acceptance passes (host-bound half still open).
- 2026-10-10 Reflection: root reads the record first, then asks the snapshot; by hand.
- 2026-10-11 Re-plan in two passes: analysis and questions, then the plan from the answers (done; this file).

## 8. Open questions

None open. Q1–Q16 of the replan and the three round-3 questions are answered in
[fable-replan](../research/agent-platform/2026-10-11/fable-replan.md) §8 and folded above.
