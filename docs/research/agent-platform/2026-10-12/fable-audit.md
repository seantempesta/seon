---
title: "Fable audit, 2026-10-12: how we are doing, and how to go faster without losing quality"
type: research
status: complete; written by lane fable-audit for the owner, read-only against HEAD c2284b109 and the running default (archive 203b9093d/c2284b109, jvm mode as root, queries only; nothing written, stopped or reset)
---

# Fable audit, 2026-10-12

The owner asked: are we still making the right planning and execution decisions, and what else can we do to plan ahead
and execute faster without sacrificing quality. Read end to end: AGENTS.md, plan.md §0, the 2026-10-11 replan, every
document dated today under `docs/research/agent-platform/2026-10-12/` (restart audits, the done-and-merge dig, both
plans, the hybrid, four narrow checks, the cost notes, program-context and M1 notes, the patch files), the 114 commits
since 00:00 and the bodies of the thirteen commits root named. Numbers below are from git, default's operator logs,
default's database and the documents; each names its source. Where I disagree with root I say so.

## 0. The five findings

1. **The day moved the request cost and a design; it did not move the goal.** The goal's one missing link (a change
   that merges without a hand; replan §1) is where it was yesterday: zero merges through the path today, the chain
   experiment ran once (07:41, four paid attempts, no merge request) and was not rerun. The standing rule "one probe
   always in flight" (plan §0) was not honored for the rest of the day. What moved: E4 (172b23e7c) took one warm
   `(+ 1 2)` from 82–113 ms to 52–65 ms on default; P3–P6 and the done-and-merge design (M1–M18) are real; the
   vocabulary sweeps are done. What only looked like progress: eight performance commits whose proofs were counters,
   with no end-to-end gain shown on the same probe and one reverted (f18ab95e3 → c2284b109); eight tasks filed at
   07:55 (U0–U7) and superseded by 11:25; four narrow-check rounds on one hybrid that still met two code-level
   contradictions in the first fifteen minutes of M1 (m1-notes §1).
2. **The lanes' REPL path is broken, so every lane proves in a throwaway JVM.** Default's health is `:faulted` on
   `seon.sci.eval/evaluate`'s acquisition refusal (7 occurrences) and agent `cost-record2` hit "Program row could not
   be installed" 16 times; cost-record, cost-acquire and cost-record2 each report that the edit hook cannot acquire the
   changed namespace on the lane's branch (`datahike.writer/write-error-log` unresolvable; `install-row!` "Unable to
   resolve symbol: profile/measure"). Result: **193 check JVMs today, 234 minutes of check wall time** (operator logs;
   39 in the 07:00 hour alone; 27–92 s each). The design says a lane `defn`s on its branch and the reaching tests run
   incrementally; today's lanes could not, so the cheapest proof was a JVM boot.
3. **Thirteen default restarts today (eight since 08:30), five of them forced by dependency changes** (sci bump
   08:01; Datahike bumps 10:38, 11:24, 11:59; the revert 13:14). Between a bump and the restart every lane's
   publication-based commit refuses `JVM RESTART NEEDED` (`seon.cluster.source`, line 203) and falls to the check JVM.
   Root counted four; the operator wrote thirteen start records (ready 7.8–25.5 s each).
4. **The red baseline has no owner and no stored shape, so gates are judged by eye.** Central round 3 (aee027806)
   left 229 red and 117 error of 2,832 tests; 172b23e7c reports 33 vs 34 reds across six core namespaces as its proof
   of "no new red"; every lane note today says "existing reds not declared green, the gate names individual Vars".
   `bin/lane commit --test NS` is unusable on a namespace with a historical red, so lanes name Vars one at a time and
   diff red lists by hand. This is the single biggest quality risk: a new red hides among 346 old ones.
5. **Performance work aimed at the wrong magnitude.** The plan ranked costs by milliseconds of one `(+ 1 2)` (a 20 ms
   prize in the recording transaction, floor 9 ms). What an agent actually pays is GB-class: a defn's test step
   allocates 0.6–1.9 GB and takes 1–2 s (chain-experiment-1 F10; narrow check §7 P5: 363–1,076 MB per eval request);
   a cold `reach-digests` 2.1 s / 4.0 GB; one fresh request 48 s / 42 GB (not reproduced). Those are 10–100× over
   what the work must do and none has a lane. Meanwhile three Datahike fork bumps chased a serialization share that
   JFR put at 71 of 107 commit samples and that, measured end to end, did not move (f18ab95e3 regressed and was
   reverted).

## 1. Against the goal

**The goal** (AGENTS.md "The goal", plan §1): a self-improving loop run by Seon agents; tasks mined, chained, merged on
green; a core of about 10,000 lines where ordinary work is sub-second.

| Link | 2026-10-11 (replan) | Today, 13:30 |
|---|---|---|
| A model agent turns a designed task red → green from generated context | works for small functions | unchanged; chain-experiment-1: design and red proof in 5 calls, implementation to green in 3; the other 40 calls were spent on steps that could not end (F2, F3, F4) |
| The end reaches root | works | P6: a merge request only from a terminal step (015673f47) |
| Root merges by hand | 3 merges | still 3; none today |
| A green end merges itself | 0 | 0; the dial is false; the path is being redesigned (M1–M18, filed, M1 started) |
| A chain of separate agents | never run | ran once (chain-experiment-1), five hand nudges, no merge request; not rerun |
| Default stays up unattended | broken by collection | no collection incident today; health `:faulted` on acquisition refusals; 49 branches and 33 agents on default (the law says ended agents' branches are deleted; not done) |
| Request cost | 82–113 ms warm | 52–65 ms warm after E4 (cost-acquire, three successive requests 192/63/52 ms); fresh agent first request 170–330 ms; the recording transaction 32–45 ms against a 9 ms floor |
| Size | about 95k src lines | 95,285 src, 105,806 test (`db.clj` 6,373; `turn.clj` 5,897; `sci/eval.clj` 4,984); net today about flat; the hybrid promises −277 when M1–M18 are done |
| Tasks | 783, 155 proven | 796 on default, about 640 without a tagged proof |

**What actually moved today.** (a) E4 and 786915ca3: an unchanged program forks nothing; equal programs on two branches
share one program context; measured on default (fork 12.7 → 0.4 ms, request 77–89 → 61–71 ms, then 52–65). The one
performance change proven on the live probe, and it came from the Opus lane doing REPL-first work on its own branch.
(b) P3–P6 (7ddbb2c1e, 031125438, be32e7501, 015673f47): one rule judges a step at its fork point; broken tests show
with their failures; a merge request only from a terminal step. (c) The done-and-merge design round produced a hybrid
with the owner's decisions (auto-merge on green, one partition, successors fork main, the branch is the proposal), and
M1's first proof is committed (3fe23b2c0). (d) 53 of 114 commits were documentation: the vocabulary sweep over living
prose and three source renames (2ef43687f, de56c2570, d0ebba656; 7, 60 and 75 files).

**What only looked like progress.** The eight performance commits (§3). The U0–U7 tasks filed and superseded within
four hours. Commit messages that report regressions and still commit ("observed regression, no causal speedup
claimed" in 2eafd5a28, 60975016d, f18ab95e3). Four narrow checks that verified document consistency while the first
implementer found, at the code, that `seon.test/admit-run` refuses exactly the run M1 wants recorded and that a
hand-assigned task never gets the prove pass M1 depends on (m1-notes §1).

## 2. Are the planning decisions right?

**The order in plan §0** (program context → done-and-merge → recording and reads → words in src → findings →
numbering). Mostly right, two disagreements:

- Item 2 (done-and-merge) is right to come before more merge machinery is built on the old shape, and the owner's
  answers decide its hard questions. But it has again put a redesign ahead of a run. The replan's own lesson (§2.2:
  the night's path was built before it had ever merged once) applies: chain experiment 2 on today's HEAD, with root
  merging by hand, costs about an hour and nine model calls and would tell M1–M8 which edge cases are real. It was
  planned ("rerun the same subject", chain-experiment-1 "Next run") and did not happen. **The probe lane must be a
  standing lane, not an item.**
- Item 3 (recording, reads, configuration) ranked by `(+ 1 2)` milliseconds is the wrong ranking. Rank by what a
  chain step costs: the test step per defn (GB), fresh acquisition (three durable transactions before the first
  evaluation, cost-record2 "Acquisition"), reach digests cold (4 GB). The recording transaction's remaining 20 ms is
  below those by two orders of magnitude.
- Item 4 (words in src) was executed during build hours: de56c2570 touched 60 files and d0ebba656 75, and cost-acquire
  records foreign lint errors in `db_flow_test` from the rename while it worked. Renames are right; their timing
  should be the restart batch, when build lanes are idle.

**The design-round shape** (dig → Opus and Astra plans → Fable hybrid → narrow check). The shape works: the dig was
10,477 words and both plans cited it; the narrow checks found 9, 6, 4 and 3 failures, all real (false live claims,
deletions that strand callers, proofs that do not test their phase). Two defects in how it runs:

1. The check runs after the hybrid, so each round is hybrid → check → hybrid. Four rounds took 10:40–11:13 plus the
   hybrid revisions between; the planners had never been asked the three questions. Put the questions in the planner
   brief ("every live claim reproduced on default with its form; every deletion with its last caller; every phase a
   red proof that resolves"), and the hybrid inherits verified claims. One check after the hybrid, not four.
2. The check verifies the documents against the code; it does not run anything new. M1 met two contradictions
   (admission refuses the fork run; hand-assigned tasks have no base result) in its first slice. The cheaper check is
   a **proof spike**: before filing phases 2–18, the implementer writes phase 1's red proof and runs it on a candidate;
   the first fifteen minutes of implementation find what four document checks cannot. File phases lazily (the next
   three to five); the rest stay in the hybrid until the spike confirms the shape. Evidence that filing early wastes:
   U0–U7 filed 07:55, superseded 10:21–11:25; P7–P10 filed 08:09, absorbed 10:21.

**Model roles.** Right in the main; one correction.

- Opus for the one REPL path: right. Both proven moves today (E4, 786915ca3) and the only real design finding at the
  code (m1-notes) are Opus REPL-first work.
- Sonnet for specified slices and sweeps: right; the renames were clean (0 lint errors each).
- Haiku for digs: right; the done-and-merge dig is the best document of the day.
- Astra for hard performance: the role is right, the brief was wrong. Astra was pointed at serialization and
  configuration micro-costs with counter proofs and did exactly that: eight commits, each with a deterministic count
  that went down and a wall time that did not. AGENTS.md's Astra role is super-linear transformations and repeated
  work; today's GB-class repeats (the test step per defn, cold reach digests, the whole-program `loaded-digests` per
  branch commit) are that work and were left unassigned. Astra as second planner: its plan (7,321 words) supplied the
  "at least one proof red at the base" rule and the three distinct facts for Datahike/files/git; worth keeping.
- Fable for hybrid, tie-break and audit: right; but the hybrid author should not also be the one revising it four
  times against the same checker; see the brief change above.
- One focus with at most two build lanes: the rule held on paper; in practice today ran program-context (Opus),
  m1 (Opus), cost-record then cost-acquire then cost-record2 (Astra), cn-renames (Sonnet), merge-tasks, findings,
  root-docs, narrow-check, hybrid, dig: six to eight lanes editing, with `turn.clj` held by one and patched by three
  (cost-record-turn.patch, cn-renames-root.patch, M1's patch). The limit should be counted in files that collide, not
  lanes: `turn.clj` and `cluster/agent.clj` are the collision points of M1, M3, M5, M6, M8, M10–M13, M16, M17 (hybrid
  §6). Split those two files first (they are 5,897 and 2,330 lines), or sequence those phases one at a time.

## 3. Are the execution decisions right? Where the hours went

| Where lane-hours went today | Evidence | Verdict |
|---|---|---|
| Check JVMs: 193 runs, 234 min of wall | operator-check logs; 27–92 s each; cost-acquire: one check 84.9 s of which 27.9 s filling compiled classes and 24.4 s boot | Mostly avoidable: the lanes' branch REPL refused their namespaces (finding 2). About four lane-hours of waiting. |
| Restarts: 13, five forced by dependency changes | operator-start logs; `seon.cluster.source` line 203 refusal | Half avoidable: dependency bumps outside a batch. Each costs 8–25 s of boot plus every lane's fall-back to checks until it happens. |
| Design-round check loops: four narrow checks, five hybrid revisions | git log 10:21–11:25 | Two rounds' worth avoidable with the brief change (§2). About 45 min of Fable and Opus. |
| Rework: f18ab95e3 committed with its own regression, reverted 75 min later; U0–U7 and P7–P10 filed then superseded | commit bodies; plan §0 table | Avoidable by the performance proof rule (§4 #3) and lazy filing (§2). |
| Blocked claims: `bin/lane claim` refusing a file the lane just created | `bin/lane` line 145: a path that is dirty (`git status --porcelain`, which includes untracked `??`) and unclaimed refuses; `exists_or_new_under` admits a not-yet-created file, so the rule is "claim, then create", and every agent created first | Ten-minute fix: an untracked file nobody claims is claimable; only a tracked modification is "someone else's work". |
| Hand diffing of reds | every lane note; 172b23e7c "33 vs 34" | Avoidable with a stored baseline (§4 #2). |
| Patches for held files | cost-record-turn.patch, cn-renames-root.patch, findings-patches, merge-tasks-patches, chain-experiment-1-proofs.patch | A symptom of two files owning too much; root integrates by hand. |

**Rules and tools that should change.**

1. `bin/lane commit --branch-evidence` is the designed cheap path and nobody could use it today because the branch REPL
   refuses the changed namespaces. Fix the producer (acquisition refusal in `seon.sci.eval`, the alias resolution in
   `install-row!`), then the check JVM becomes the exception for host-bound slices only, as AGENTS.md says.
2. `bin/seon check` and `bin/lane commit --test` judge a namespace against nothing. They should judge against the
   stored result of the same tests at the candidate's merge base (the test system already keys results by reach
   digest; `script/seon/central_round.clj` already produces the per-commit record) and report transitions only: new
   red, new green, unchanged. Until then a namespace with one historical red cannot be named.
3. Performance proof rule: a performance commit's proof is a measured-cost assertion through `seon.profile/measure`
   on the end-to-end probe it claims to improve (the request, the step, the acquisition), before and after on a quiet
   candidate pair with the same store, in the commit body. A count (encoder calls, pulls, constructions) is a
   diagnostic, never the proof. A dependency bump without an end-to-end number is a patch in the research page, not a
   commit. Today's rule let three bumps commit on counts.
4. Restart batching: a dependency change commits only in a scheduled restart batch (every two hours, or at root's
   call), together with the renames that touch many files; between batches the fork change lives as a pushed fork
   branch plus candidate evidence. `bin/lane commit` can refuse `reference-code/*` and `deps.edn` paths outside the
   window.
5. `bin/lane claim`: untracked files are claimable (finding above).
6. The branch and agent chore the law names: 49 branches and 33 agents on default; delete the ended ones at each
   restart batch with a before/after byte count until M5 derives it.

## 4. How to go faster without losing quality (ranked)

| # | Change | Cost | What it gives | What it gives up |
|---|---|---|---|---|
| 1 | **Fix the lanes' REPL path** (`seon.sci.eval` acquisition refusal; `install-row!` alias resolution; the edit hook's PREPL bound), proven by a lane `defn`ing a changed test namespace on its branch and committing with `--branch-evidence`. Owner: the program-context lane (it holds `sci/eval.clj`), before E5. | About half a day of Opus | Removes most of 193 check JVMs/day; makes "REPL-first on a branch" true for every lane; health leaves `:faulted` | E5 slips half a day |
| 2 | **Red baseline owned and stored**: run the central round once per restart batch and store it; `bin/seon check` reports transitions against the merge base; a Sonnet triage lane walks the 346 historical reds (delete tests of deleted machinery, declare host-bound as data, file the rest with their cause) | 35 min of one JVM per round; one Sonnet day | Gates become honest; `--test NS` works again; a new red cannot hide | A Sonnet day off vocabulary and filing |
| 3 | **Performance proofs as end-to-end measured-cost assertions on the same probe; fork bumps need the number** (§3 rule 3); re-rank the performance queue by GB-class step costs: test step per defn (1–2 GB), cold `reach-digests` (4 GB), `loaded-digests` per branch commit, fresh acquisition's three transactions | A rule change; Astra's next brief | Every performance commit moves the agent's cost; no reverts | Fewer, slower commits from Astra |
| 4 | **Dependency bumps and wide renames only in a scheduled restart batch**; `bin/lane commit` refuses them outside it | Ten minutes in `bin/lane` | Restarts 13 → about 4 per day; lanes stop falling to check JVMs | A fork fix waits up to two hours |
| 5 | **Design round: questions in the brief, one check, proof spike before filing beyond phase 3**, phases filed lazily | None | Two to three fewer check rounds per design; fewer superseded tasks | Later phases live in the hybrid, not the task database, until confirmed |
| 6 | **A probe lane always in flight**: chain experiment 2 today on HEAD with the same subject and instructions, root merging by hand; graded against experiment 1 (calls, nudges, errors by signature); experiment 3 after M8 through auto-merge | One hour of root per run; cents of model spend | The M-series is built against observed edge cases; the loop's own numbers improve round over round | None |
| 7 | **`bin/lane claim` admits untracked files** | Ten minutes | Every lane's first friction gone | None |
| 8 | **Split `turn.clj` and `cluster/agent.clj` by responsibility** before M3–M13 (Sonnet, mechanical, one restart batch) | Half a Sonnet day | Phases that collide on two files can run in parallel | A large mechanical diff in one batch |
| 9 | **Task database prune** (Haiku): tasks with no proof, no resolving must-read, or whose subject is deleted; superseded phase tasks retired the day they are superseded | Half a Haiku day | The ranked schedule is readable; 796 → a few hundred | None |
| 10 | **Branch and agent cleanup at each batch** (49 branches, 33 agents) | Minutes | Health reads; storage and cache budget | None |

Design ahead now, in parallel, so implementers never wait (digs are cheap Haiku work and do not touch files lanes hold):

- **E5** (the recording stage derives the next program-context value instead of writing the base): already designed in hybrid-immutable-env; needs no dig,
  needs #1 first (same file).
- **`my.task/refine!`, `my.task/create!`, instructions on the step** (TASK-CHAIN's first build items; decided
  2026-10-11, still absent; chain-experiment-1 F8): a short dig of `my.task`, `seon.task/create!`'s upsert and
  `seon.context/held-task`; then one plan each (Opus, Astra), hybrid, spike. Unblocks the chain's handoff medium.
- **Guessed names answered at the failure** (Q7; 58 occurrences was the top agent error): dig `seon.error`'s suggestion
  path; a Sonnet slice.
- **The test step's cost** (F10): dig what the step does per defn (acquisition, reach, run, record) with
  `seon.profile/measure` on a fresh agent; the plan is Astra's.
- **The reach-digest cold cost** (2.1 s / 4 GB): same dig; one producer.

## 5. Plan-ahead queue, next two to three days

Lanes run in parallel on disjoint files; root sequences the two collision files. "Batch" is the scheduled restart.

**Today (remaining).**

| Lane | Model | Work | Depends on | Files |
|---|---|---|---|---|
| m1 | Opus | M1 with option A (admission of the fork run at the tested commit; `head-entries` reads by current reach digest), batch read of `result-at-reach` for all proofs | owner decision 1 | runner, test, context, turn (patch) |
| program-context | Opus | #1 the lanes' REPL path; then E5 | — | `sci/eval.clj`, `cluster/agent.clj` (patch) |
| probe | root | chain experiment 2 on HEAD, hand merge, graded against experiment 1 | nothing; a restart batch first so default runs P5/P6 and E4 | none |
| red-baseline | Sonnet | store the central round per batch; `bin/seon check` transitions; `bin/lane claim` untracked fix; `bin/lane commit` dependency window | owner decision 3 | `script/seon/central_round.clj`, `bin/lane`, `bin/seon` |
| perf | Astra | the test step per defn: measure, find the repeated work, fix at its owner; proof is a measured-cost assertion on "define one function on a fresh agent" | #1 for REPL-first; else candidate JVMs | `seon/test.clj`, `seon/test/runner.clj` after m1 releases |
| digs | Haiku | refine!/create!/instructions; guessed names; test-step and reach-digest cost | — | docs only |
| chores | Sonnet | branch and agent cleanup at the batch; task prune | — | none / task files at the batch |

**Day 2.** M2–M5 (M2, M4 Sonnet from the hybrid; M3, M5 Opus) after the M1 spike confirms the base-result mechanism;
E3 and E6 after E5; the refine!/create! design round (plans in the morning, hybrid at noon, spike in the afternoon);
central round after each batch; Astra on the next GB-class cost from the dig; chain experiment 3 if M5 is in.

**Day 3.** M6–M8 (Opus: the merge keyed by the assignment, the gate as the one rule, the verb deleted; auto-merge on);
M14 (the branch is the proposal) beside; the triage lane finishes; chain experiment 3 through auto-merge on a
restarted default, graded; M-final's proof red for the stated reason.

**Owner decisions, and when.**

1. **Now:** M1's contradiction (m1-notes §1): option A (the writer admits the fork run at the tested commit; two
   phases) or B (every assignment proves first; one phase, but a branch that changed a proof's closure before
   assignment has no base result). Recommend **A**: it is the design as written and the only one that gives every
   (test, base digest) a result whatever order the agent worked in.
2. **Now:** the performance proof rule (#3) and the dependency window (#4) as standing rules in plan §0; Astra's brief
   re-ranked to GB-class step costs. Recommend yes.
3. **Now:** the red baseline (#2): authorize a Sonnet lane to delete tests of deleted machinery and declare host-bound
   tests as data, with the central round stored per batch as the gate's baseline. Recommend yes.
4. **Today:** #1 before E5 in the program-context lane. Recommend yes: E5 is half a day either way and every lane
   waits on #1.
5. **Today:** pause src vocabulary sweeps (CN-5, CN-6, CN-9) until M8 is in, then do them in one batch. Recommend yes;
   the words a plan deletes are not renamed (plan §0 item 4 already says so), and the M-series deletes most of them.
6. **Tomorrow, after chain experiment 2:** whether M-final's chain (Design+Prove → Implement → auto-merge) is the
   right first automatic merge, or a single ordinary task of chain-run-5's class (replan §3.1) goes first. Recommend
   the single task first: it isolates the merge from the chain.
7. **Open from the hybrid, default stands:** Q-e publication order (Datahike first, publication as derived due work).

## 6. Root's list, verified and ranked

| Root's suspicion | Verified? | Rank |
|---|---|---|
| Performance lanes proving proxy counts, f18ab95e3 committed with its regression, 927dd3dbe no change on default | Yes. f18ab95e3's body: "Observed regression, not a causal speedup"; reverted c2284b109. 927dd3dbe: 192→153 ms in windows "contaminated" by checks; default never showed it. 2eafd5a28 and 60975016d report regressions in their own bodies. | 2 |
| Four default restarts, each blocking commits after a fork bump | Thirteen start records today, eight since 08:30; five forced by dependency changes; the refusal is `seon.cluster.source` line 203. | 3 |
| Slow checks on a red baseline; lanes diff reds by hand | Yes: 193 checks, 27–92 s; 229 red + 117 error at central round 3; 172b23e7c's "33 vs 34". The cause of the check volume is the broken branch REPL, not check speed. | 1 |
| The hybrid needed four narrow-check rounds | Yes (10:40, 10:58, 11:03, 11:13), and M1 still found two contradictions at the code. | 5 |
| Health `:faulted` for one branch's acquisition refusal, evidence cut at the size bound | `:faulted` confirmed: `seon.sci.eval/evaluate` 7 occurrences, `install-row!` alias error, `cost-record2` 16. The size cut I did not verify. | with 1 |
| 48 s / 42 GB fresh request; 2.1 s / 4 GB cold reach digests | Recorded (program-context-notes; narrow check §7 P5); 48 s not reproduced (cost-acquire). Both unassigned. | 4 |
| `bin/lane claim` refusing files a lane just created | Yes: `bin/lane` line 145 treats an untracked file as someone's uncommitted change. | 7 |
| 783 tasks, 628 unproven | 796 on default now; eight filed and superseded today. | 9 |
| ~95k source lines against 10k | 95,285 src; the owner ruled correctness and efficiency over size for now; no plan reduces it except the M-series' −277. | note |

Missing from root's list: the probe lane did not run after 07:41 (standing rule); renames of 60–75 files during build
hours; six to eight concurrent lanes against a stated two; `turn.clj` and `cluster/agent.clj` as the collision points of
eleven M phases; 49 branches and 33 agents on default; the chain experiment's "Next run" never scheduled.

## 7. Evidence and limits

Commits: `git log --since='2026-10-12 00:00'` (114; 53 touch no `src test resources reference-code bin script`
path); co-author lines: Opus 36, Sonnet 11, Fable 10, Haiku 9, the rest Astra/Sol lanes without a line. Operator:
`data/clusters/default/logs/operator-start-20261012T*.edn` (13) and `operator-check-20261012T*.edn` (200; 193 with a
`:total-ms`, 234 min). Default (jvm mode as root): tasks 796, branches 49, agents 33, tests 2,872, functions 6,527;
`runtime_status` `:faulted` as quoted. Sizes: `find src -name '*.clj*' | xargs cat | wc -l`. Working tree at the time
of writing: lane m1 holds thirteen paths with uncommitted edits (`turn.clj`, `context.clj`, `db.clj`, `runner.clj`,
schemas, tests); nothing of mine touches them. Not measured here: the health evidence size cut; a fresh central round
at HEAD; any lane's hours directly (hours are inferred from commit times and check logs).
