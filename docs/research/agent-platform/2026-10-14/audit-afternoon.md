---
title: "Audit, 2026-10-14 afternoon: how planning and execution went, and the order to the automatic chain"
type: research
status: dated audit (Fable, read-only; evidence is commit ids and today's notes); input to the compaction of plan.md section 0
---

# Audit, 2026-10-14 afternoon

Scope: today's 215 commits (08:00–15:40 local, `git log --since=2026-10-14`), plan.md §0, TASK-CHAIN §7, root's working
state (9fa2ba933) and today's notes. Co-authors: Opus 101, Sonnet 63, Haiku 29, Astra/Codex 6, Fable 3. Paths touched:
docs 244, resources 222, test 138, src 80. Plan §0 was edited 21 times. TASK-CHAIN went from READY (56f31a105, 10:45) to
S1 complete (6b6a825f9, 15:26) in 4 h 41 min, eight slices, about 35 min each when a slice was actually running.

## 1. What went well (keep doing)

- **Red proof first, and it is real.** The proof audit (3081f15fa) rebuilt ten commits' parents with only the new test
  file applied: 13 of 13 new deftests red at the parent, 0 green; every S1 slice commit names its red run id
  (c8bf4d82a, 831df5f72, d4566ddd2). The rule "no red run on HEAD, not proven" is holding.
- **Scripted sweeps.** S1a renamed 859 task entries plus src, tests, schemas and laws by syntax with rewrite-clj
  (243683f1d, EDN-read check equal, 0 lint errors, warning set identical to the parent); the flow-actions rename the
  same way (e6df7e213). Zero hand-fix rework afterwards.
- **Haiku pre-briefs catch stoppers before a lane starts.** Pre-brief S2 (86eb73e8c): none of the five S2 red proofs
  exists, `done-refusals` does not exist, the §6 tag counts do not reproduce, and it listed every file collision with
  live lanes. Pre-brief S1 (4a6ba813a) had the same effect on S1.
- **Design rounds are fast.** Dig → two plans → hybrid took 39 min for reference levels (207e7c213 → 411153f5c),
  35 min for the overlay (f76a6838d → 723bce2e1), 39 min for the truths (cb5549740 → 579d9a774). Each hybrid states
  a cost rule or decision the two plans missed (truths: key cost vs work saved; overlay: unbind before install).
- **REPL-first is measurably better where it works.** The trial (a283068f1): first form to 15 of 15 green in 14 min
  with 0.2–0.9 s per round versus at least 4 min of throwaway boot per round on the check path; the branch-evidence
  refusal was correct and cost 551 ms; nothing inside the JVM was lost. The eleven frictions became tasks (5fddc2bfd).
- **The live Design prototype.** Design as one ordinary task: 12 calls, 41 s, 0 hand acts, proof green on its
  branch (8c7901438); its gaps went into TASK-CHAIN §2.4, §2.6, §4 instead of a parallel ruling log.
- **Context sanity before grading agents on it** (58f130e03): verdict usable with five ranked defects by producer,
  two of them small and now S3a-2's proof rows (0b661ea81).
- **Refutation counted as progress.** 521a957dd, aa2d202f1, b2605deef, 8f9c604ce each closed a reported defect by
  reproducing it and finding it absent; 86beed937, 8e5738243, 45072ea86 retired tasks that were already fixed.
- **Gemini blind-tested before being trusted** (gemini/README, a581628e1): Flash-high 4 of 4 with known answers;
  its moot-task list then failed verification 24 of 25 (f0bcf6cb5) and nothing was retired on its word.

## 2. What cost the most, ranked by measured cost

1. **File-claim contention (hours).** The S1a rename claimed 166 paths from its dry run (4da46c8e7, 11:06) until its
   commit (243683f1d, 13:06): two hours in which every lane touching a task file, schema or src with `intent`
   queued (root's queue header: "Lanes queued until S1a releases its 166 paths"; items 9, 11). S1b was pre-briefed at
   11:41 (c0ac8ee6c) and landed at 13:50 (d4566ddd2). The same pattern at smaller scale all afternoon: 19 patch files
   under this directory are finished work waiting on a held file (s3d-3: 10 files proven red → green, waiting on
   three lanes; s1c-2 two patches; overlay phases 2–3 on `seon.test`; the task-proofs patch; two writeback-fix
   patches; b1-test-reuse; proj-content-key; reconcile-writer). Right now 10 lanes hold 53 paths, and the chain's
   critical files are held by REPL-path and cache lanes: `cluster/source.clj` and `my/program.clj` by writeback-fix
   (S2b-2, S3b-1 need them), `turn.clj` by verdict-honest (S2a, S3a-2), `test.clj` and `runner.clj` by b1-durable
   (S2b-1, S2a), `sci/eval.clj` by request-view (S2b-1's caller). Root also edited held files three times (root's
   report; not reconstructible from git).
   *Root cause:* a claim is held for the lane's whole life, including its design, probing and patch-writing time,
   while the ledger has no notion of "editing now". *Change:* every claim is a commit window, as the sweep rule
   already says (root-working-state "Lane rules", last bullet): claim when the edit begins, commit or leave a patch
   within the time box, release on stop; a lane that stops with a patch releases in the same report. Root launches
   a chain slice only when its files are free, and never launches a non-chain lane onto a chain file while S2–S3b
   are open.
2. **Check-slot queueing (70–227 s per check, 4–6 checks per lane).** Measured waits: 193 of 259 s, 185 of 236 s,
   119 of 179 s, 70 of 115 s (610573d64); 227 s wait in the collection flake hunt (b41d82c3a); a proof-audit run
   timed out waiting for a slot (3081f15fa). Slots went 2 → 4 (f008a4616) and the owner named the wait a defect,
   not a dial. *Root cause:* every check boots a throwaway JVM (default's own ready time 38,968 ms, repl-trial.md)
   and the bulk tier shares the slots (185 of 2,993 members in 600 s, 5036d2264). *Change:* host-bound commits
   only for what `gate-input?` says is host-bound (86942d3d0 landed this); ordinary function changes go
   REPL-first with `--branch-evidence` (no JVM); the bulk tier runs once, centrally, when no lane is committing;
   the fix is the filed task `seon.operator/a-check-waits-for-a-slot-longer-than-it-runs`.
3. **Load flakes against time bounds (one rerun each, several reclassifications).** 3 of 17 reds in the triage were
   load flakes (5036d2264); `redefined-callee-test` 17–19 s loaded vs 7 s alone against a 15 s bound, `a-steps-block-
   names-its-chain` 5.17 vs 5 s, `ready-answers-in-under-10-ms` 18.4 ms under concurrent checks (queue item 20);
   `eval-test/no-agent-work-writes-a-program-context` at `(< 0.5 0.5)` (item 22); the collection body 127 s against
   70 s under contention. *Root cause:* bounds are wall time measured on a quiet machine, run on a loaded one.
   *Change:* a timing assertion compares against the same probe on the parent in the same run (`seon.profile/measure`,
   as the perf rule already requires), or is a CPU-time bound; a red that passes alone is recorded as load, never
   re-triaged by hand a second time.
4. **Too many design rounds at once, ahead of implementation capacity.** Three hybrids between 13:14 and 14:58
   produced 26 phases (reflevels S3d-1a..6, overlay 1–5, truths 1–8 plus 16 catalogue rows); by 15:40 four had
   landed (S3d-1a, S3d-1b, overlay 1–3) and the rest sit as lanes holding chain files or as patches. The Gemini
   stream added two worktree pitches, two backlog clusterings and a handoff in the same hours. *Root cause:* the
   design pipeline is cheap (40 min) and implementation is not, so designs accumulate on the queue and on files.
   *Change:* one design round in flight per half day while the goal is the automatic chain; a hybrid is filed as
   tasks only when its first phase has a free owner and free files.
5. **Scope: defects and digs ahead of the chain.** Between the S1 pre-brief (10:50) and S1b-1 (13:50), 85 commits
   landed, two of them TASK-CHAIN slices (S1a, S3a-2); the rest: ten doc inventories and edits, eight Gemini
   integration commits, the errors cut, GC phase 1, sentinels, filing. Much of it was correct work (the owner's
   focus rules were written at 13:03, c31780f9d), but it consumed the Opus and Sonnet slots the chain needed.
   *Change:* the focus rule as written, enforced at launch: a non-chain lane starts only when a check slot and its
   files are free and no chain slice is ready.
6. **Haiku conclusions acted on before verification.** Root counts 4 of 6 Haiku causal claims wrong today; the
   visible ones: the interrupted-request fixture hypothesis refuted by Sonnet (521a957dd), the ns-unmap retraction
   not reproduced (aa2d202f1), the storage-loss claim not reproduced (b2605deef), the active-connections refusal 0
   of 8 (8f9c604ce); the REPL prototype answered 1 of 4 questions at the REPL against Sonnet's 3.5 (1b574d429,
   7b5ca80b9). *Change:* Haiku output carries VERIFIED/UNVERIFIED per row (as dig-repl-commit-path.md does) and a
   Haiku-filed defect gets a reproduction form before anyone designs for it.
7. **Index and message incidents.** 243683f1d carried another session's AGENTS.md edit (gemini/handoff.md §1);
   036e69da7 restored a law line lost to a clobber; two lanes once committed under one message file (lane rules).
   *Root cause:* a shared working tree and index with a dozen writers. *Change:* already in the rules (own message
   file per lane, no index operations); the structural fix is the owner's "files at merge, `bin/lane` retires".
8. **Lanes stopping on held files and leaving patches** is the symptom of 1; the cost is a second lane's context
   to apply each patch (s3d-3's patch is 10 files that a fresh lane must re-prove).

## 3. Scheduling: the order to an automatic Design → Prove → Implement chain

Target: S5 (one `chain!`, 0 hand acts, one merge). Needed for it, from TASK-CHAIN §7 and plan §0: S3a-1 (in flight),
S2a, S2b-1, S2b-2, S3a-2, S3a-3, S3b-1 (+S3b-2 in the same lane), S4a (in flight). Not needed for the first S5 run:
S2c-1/S2c-2 (tags are the installed proof mechanism until S2c; the sweep can run in a commit window any time, the
deletion after S5), S3c (bail; the first run is watched), S3d-2..6 (doc views; context is "usable", 58f130e03), S3e,
S3f-1/S3f-2 (needed for the Claude-Code-as-Seon-agent runs, after S5), the cache phases 2 and 4–8, S3d-3's patch,
reconcile-writer and boot-dup-analysis.

Critical path, with files (serial where a file repeats):

| Step | Slice | Files | Model | Waits for |
|---|---|---|---|---|
| 1 | writeback-fix, verdict-honest, b1-durable commit or leave patches and **release** | `my/program.clj`, `cluster/source.clj`; `turn.clj`; `test.clj`, `test/runner.clj` | in flight | 30-min time box from now |
| 2a | S2b-2 `done-refusals` | `cluster/source.clj`, `source_test.clj` | Opus | 1 |
| 2b | S2a one task input | `turn.clj`, `test/runner.clj`, `context.clj`, `task.clj`, `task_test.clj` | Opus | 1 |
| 2c | S2b-1 whole-branch selection | `test.clj`, `test_test.clj`; `sci/eval.clj` caller as a patch to request-view | Sonnet | 1 |
| 2d | S3a-1 root reads chains | `cluster/agent.clj` | Opus, in flight | — |
| 2e | S4a templates and helpers | `resources/seon/tasks/seon.step.*.edn`, `step.clj`, `test_support.clj` | Sonnet, in flight | — |
| 3a | S3a-2 turn authority and context | `turn.clj`, `context.clj`, `repl_test.clj` | Opus | 2b |
| 3b | S3b-1 + S3b-2 acceptance, publication, retirement | `merge.clj`, `cluster/source.clj`, `merge_test.clj` | Opus | 2a |
| 3c | S2c-1 tag sweep (commit window only) | task files | Sonnet | 2e committed |
| 4 | S3a-3 advance | `task.clj`, `cluster/agent.clj`, `db_flow_test.clj` | Opus | 2b, 2d, 3a |
| 5 | S4b-design, S4b-prove predicates (Implement has none) | `step/<stage>.clj`, `step/<stage>_test.clj` | Sonnet ×2 | 2e |
| 6 | S5 run 26, live DeepSeek | none (root REPL) | Opus run lane | 3b, 4, 5 |

Parallelism: waves 2a–2e are file-disjoint (five lanes), 3a–3c are disjoint from each other, 4 and 5 are disjoint. With
4 check slots and checks of 115–259 s under today's load, run **at most four host-bound lanes at once plus
REPL-first and docs lanes** (which need no slot); the chain lanes are host-bound (writer, turn loop, runner, agent
lifecycle), so while S2–S3b are open the perf and cache lanes run only as design, patches, or REPL-first ordinary
functions. Rough arithmetic: 2a–2e about 1.5 h, 3a–3c about 1.5 h, 4 about 1 h, 5 in parallel with 3–4, S5's run and
its first frictions 1–2 h: about 6 h if no slice is blocked by a held file, which is the one thing root controls.
The rough live run (live-chain-rough) continues meanwhile with hand acts counted; its frictions feed 3a and 4.

Defer today: the Argon third truths catalogue and its hybrid (queue item 15), speculative projections (16), the
S3d-3 patch until `db.clj`/`ref.clj`/`my/program.clj` are free, reconcile-writer and boot-dup after S5 unless a live
run shows the cost blocking, the namespace-agents retirement and the second restart batch (they need a restart, which
pauses every lane), the red sweep (bulk tier first, centrally, in a quiet hour).

## 4. Model use today

- **Haiku (29 commits).** Good: digs and pre-briefs (86eb73e8c, 4a6ba813a, 9e8d8251f, cb5549740), the proof audit,
  red triage, flake hunts, doc inventories. Bad: causal claims and REPL work (§2.6). Keep it on gathering with
  VERIFIED/UNVERIFIED rows; never a REPL prototype or a fix.
- **Sonnet (63).** Good: the two scripted renames, S1a, S1b-1/2, S1d-1/2/3, the doc edits from Haiku's inventories,
  the REPL prototype (3.5 of 4). Its stops were clean (patch and report). Give it every specified slice and the
  REPL-first ordinary changes; S2b-1, S2c-1, S4a, S4b are its.
- **Opus (101).** Good: S1c-1/2, S3a-2's small fixes, overlay phases 1–3, B2/B4, the atomic install (f9bc67d84), the
  lifecycle race (8b6473ed8). Cost: Opus lanes hold the most paths (writeback-fix 11) and the chain's critical
  files; four Opus lanes on perf and cache ran while S2 waited. Put Opus on the critical path (2a, 2b, 2d, 3a, 3b, 4,
  6) until S5 runs.
- **Astra (6).** Good: the measured cost corrections in the truths and overlay plans (key cost vs saved, 32-bit
  fingerprint, the supplied-base bypass), the test-step cost measurement (70933f422). One perf lane at a time, each
  proven by `seon.profile/measure` on the end-to-end probe; the test step's 2.6–7.3 GB per `defn` is the next, after
  b1-durable releases `test.clj`.
- **Fable (3).** The three hybrids, each in about 40 min with a decision the plans missed. The adjustment is on the
  pipeline, not the writing: one hybrid in flight at a time while implementation is the bottleneck.
- **Gemini (bin/agy-agent).** Flash-high: 4 of 4 blind root causes, F53 in 7 min; its retire list 24 of 25 wrong.
  Argon: right but slower, hit quota, once invented code excerpts (166d830d2). Use Flash to find a cause and to judge
  a proposal before it is built, with every quote reproduced; never to retire, file or implement; Argon only for a
  long hunt that no one else has time for.

## 5. The plan document after compaction

Stale in §0 now: "In flight" (lane-input-roots landed, 86942d3d0); "Next restart batch" and "Next restart (batch 1b)"
(both done: 243683f1d, 06bbba850); "Then, before S1b: the red sweep" (S1b is done); two "Landed" paragraphs that
overlap; the Models paragraph states Gemini twice; "Worktree experiment" is decided (no per-agent worktree, 7e1247ec4)
and belongs under "Files at merge"; "Backlog by root cause" verified one task and belongs in the research page;
"Working order" (15:05) and "Live chain today" (15:30) and "Focus rules" (13:03) say overlapping things in three
places. §0 holds 21 edits of today's history; history belongs in §4 and the dated notes.

Proposed §0 outline (one screen):
1. **State.** Default's commit; TASK-CHAIN: S1 and S3d-1 done (§7), the rough live chain running (lane, subject,
   hand acts so far); the lanes in flight by one link to the working-state note.
2. **The order to the automatic chain.** The table of §3 above: steps, files, model, waits-for; the deferred list in
   one line each with its reason.
3. **Beside the chain** (file-disjoint, only into a free slot and free files): REPL-path frictions by producer, the
   cache phases in order with their owners, the errors cut E2→E6, the perf leads by measured weight.
4. **Rules.** Claims are commit windows; four host-bound lanes at most; REPL-first for ordinary functions; a Haiku or
   Gemini claim is reproduced before it is a task; restarts pause and resume lanes; one design round in flight; a
   dependency or fork bump only inside a restart batch.
5. **Restart batches.** One list: the next batch's contents and its trigger.
6. **Owner rulings of today**, one line each, moved to §7 at the next compaction.

## 6. Recommendations for the owner

1. Put the four Opus slots on the critical path (S2b-2, S2a, S3a, S3b) and run perf and cache work as patches or
   REPL-first until S5 runs: the chain's files are held by non-chain lanes right now.
2. Make every claim a commit window and release on stop: the 19 patches and the two-hour S1a hold are the day's
   largest cost, larger than the slot queue.
3. One design round in flight at a time; defer the Argon catalogue and the second truths hybrid to tomorrow.
4. Fix the check wait at its owner (one booted check JVM serving named tests; bulk tier centrally in a quiet hour)
   rather than by slots; it taxes every host-bound commit by 70–227 s.
5. A Haiku or Gemini finding becomes a task only with a reproduction form; mark the rest UNVERIFIED and leave it.
