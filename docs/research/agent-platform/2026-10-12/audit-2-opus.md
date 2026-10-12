---
title: "Audit 2 (Opus), 2026-10-12 night: how we are doing, what to do better, and a focused plan §0"
type: research
status: complete; independent audit (lane audit2-opus) for the hybrid author and the owner, and root's working context after compaction (§8 is root's brief: state of every line of work, next actions in order, traps, rulings already made). Read-only except this file.
basis: HEAD aea2a1052 (git, documents, code read by symbol); default running archive b14dc2b3f (restarted 17:10 +07, `bin/seon status`), queried in JVM mode as root, reads only. Previous audit fable-audit.md at c2284b109 (13:14 +07). Times are +07 unless marked Z (UTC).
---

# Audit 2 (Opus), 2026-10-12 night

Sources read end to end: AGENTS.md; plan.md §0 (and the section headings after it); fable-audit.md;
hybrid-done-and-merge.md §1 and §6; hybrid-test-results.md §6 status column; hybrid-immutable-env.md status;
chain-experiment-2/3/4.md; chain-queue.md; chain-3-candidates.md; m4/m5/m6-notes.md; red-triage.md;
prebrief-red-owners.md; task-triage-1.md (head and counts); sentinel-baseline.md; open-faults-evening.md; the 106
commits `c2284b109..HEAD` (subjects, and the bodies of 6433c7593, b7be69770, 360e1f2ea, 7fea1f2e7, 9e23a93b7,
bc347d583, 3da1f6449, 4825cac93, fec3d7737); root's handoff note (as a view to verify). `central-round-head.md` does
not exist at HEAD (the round lane has not committed). Live numbers come from five JVM-mode queries on default and
from `data/clusters/default/logs/` (operator-start and operator-check records).

## 0. Five findings, ranked

1. **The goal's missing link has not moved: zero changes merged through the merge path, and the next blocker is
   not on the queue.** Since the previous audit, four chain experiments ran (1–4 today; 2, 3, 4 since c2284b109).
   Two agent-written changes reached `main`, both as git commits made by lanes (4825cac93 by Opus, fec3d7737 by
   Sonnet with three root fixes to the agent's code), both platform code (the value printer, the web server) that
   no branch can prove interpreted. `my.root/merge!` merged nothing; on default no assignment has a
   `:seon.task/outcome` (20 assignments, query below). What did move is real: M3, M5 and M6 built one merge engine
   keyed by the assignment (`seon.merge/merge!`), deleting `copy!`, `land-ends!`, `land-held!`, the marker and four
   schemas. But M6 had to add a rule that every branch holding an agent's merge request **waits for root**, because
   an automatic code merge cannot publish: default runs its committed archive, `:seon.config.fs/working-root` `"."`
   resolves to `data/source/<sha>`, and `write-back!` refuses it (m6-notes "The rule the code forced"). So the first
   automatic merge of code needs a decision on where an automatic merge writes files, then M9 and the end of the
   `requested?` hold. Root's queue puts M4 next (unmerged entities), a phase for a failure never observed in four
   chains.
2. **Three quarters of the hand acts are lifecycle, and the phases that remove them are queued last.** Hand acts
   after launch: chain 2 eight, chain 3 twenty-three, chain 4 seventeen (sixteen with the config act counted once).
   Counted from the experiments' own hand-act lists: relaunch by end rewrite (G6) 2 + 6 + 6, forks of a deleted
   predecessor branch (G7) 2 + 9 + 0, an end left on its branch until a hand pass (G8) 1 + 5 + 5. That is 36 of 48.
   Their owners are M12 (relaunch), M10 (fork main) and the branch's listen route (G8, no task; "only on
   hand-started attempts", which G6 and G7 force). The hybrid's order puts M4 → M7 → M8 → M9 before M10 and M12.
3. **The gate still judges against nothing, and the check volume doubled.** The operator wrote **202 check records
   since 13:22, 232 minutes of check wall time** (200 with `:total-ms`); every one has disposition `:client-failed`
   (the candidate is red), the median executed **2** tests, and every summary says **`reused 0`**. Before the
   previous audit the same directory held about 200 records over thirteen hours; since, 202 in four hours (14h 35,
   15h 53, 16h 66). The branch-REPL fixes (7fea1f2e7, 9e23a93b7) removed the two named acquisition causes, but only
   8 commit bodies since c2284b109 mention `--branch-evidence`. The previous audit's #2 (a stored red baseline,
   checks reporting transitions) was not executed: R10 (`bin/seon check` judges its reds against main) is filed and
   not started, no central round has run on any HEAD since aee027806 (06:08), and red-triage classified 25 of 37
   reds "E, unknown, needs a run".
4. **The derived lifecycle failed on its first live pass, and nobody has looked.** Default restarted onto b14dc2b3f
   at 10:10:08Z. At 10:10:29Z db<->flow's `:merge` of chain 4's attempt `4438ded31ef2` was refused by the writer
   (`seon.db/transact!` from `seon.merge/merge!`: "Complete component validation refused: unowned-entity", entity
   292095, a `:seon.error` fact) and the assignment ended `:error`; at 10:10:32Z acquisition of a pre-rename branch
   failed on row `[:seon.fn/sym seon.cluster.squash/attribute-tx]` ("database-effective-config requires
   configuration naming its cluster; this database has none"), now **20 occurrences** of
   `seon.sci.eval/acquisition-refusal`. Health is `:faulted` with **9 open faults**; branches grew from 49 (previous
   audit) to **71** (62 `agent-*`), agents to **112**, though 29 branches were retired at 14:10 (27701241d). No task
   names either new failure. Lane e7 saw the same cause on a nuked-and-started isolated root at HEAD (handoff note,
   unverified by me): base acquisition refused ten `seon.cluster.squash` declarations, then every agent form refused.
   HEAD's `src`, `resources`, `config`, `script` and `test` hold no `seon.cluster.squash` text (`rg`), so the stale
   declarations come from stored or cached state, not source.
5. **The cheapest lanes produced the most rework; the best output per hour came from Opus on the merge path,
   Sonnet on the root REPL, and Astra on reach.** Haiku's work selection failed three times: chain 3's subject
   (rank 2 in chain-3-candidates, a 185-line scanner) and chain 4's (chain-queue #1, "not platform", whose fixture
   cannot run interpreted) cost 174 model calls and 40 hand acts without a merge request; red-triage ranked four
   owners from a superseded round, and a second Haiku document (prebrief-red-owners) had to correct it;
   task-triage-1 left 114 of 150 tasks UNRESOLVED. Forty-two of the 106 commits are documentation only. Against
   that: M3, M5 (two slices), M6 (two slices) in about two hours of Opus, each deleting machinery; Sonnet's
   b7be69770 took root's `(doc my.root/close!)` from 10–11 s and 3.5–6.9 GB to 124 ms by selecting the tests the
   request changed instead of the branch's drift; Astra's R11 (6433c7593) took one test after one change from
   408 ms / 628 MB to 69 ms / 107 MB, measured end to end.

## 1. Against the goal

### What moved since c2284b109

| Link | At the previous audit (13:22) | Now (HEAD aea2a1052, default b14dc2b3f) | Source |
|---|---|---|---|
| Chain experiments | 1 run, no merge request | 4 run; 2 reached a merge request or merge-ready code (chains 2, 4) | chain-experiment-2/3/4.md |
| Merges of agent code | 3 by hand (all before today) | 5 by hand: + 4825cac93 (chain 2), fec3d7737 (chain 4, with 3 root fixes); 0 through `my.root/merge!`; 0 automatic | git; default: no assignment has `:seon.task/outcome` |
| Merge engine | two shapes, two bases, marker, catches | one `seon.merge/merge!` keyed by the assignment, base advanced in main's writer; facts of non-requested ends merge automatically; requested code waits for root | 767551c7b, 1b09d8043, c74ef3437, 7c3077b9e, 3da1f6449 |
| Done | judged at the fork point by `(not broken)` | each proof's result at its base reach digest; `:satisfied` opens no turn | M1 (3fe23b2c0, 346645d56) |
| Lifecycle | `land-ends!`, catches | derived `next-actions`; one catch ends a failed action `:error` | M5; but see finding 4 |
| Test results | selection reran reds | a result is keyed by what the body executed and reused at its key, red included; incremental reach | R1 50ca41030, R2 474174b52, R11 6433c7593 |
| Request cost (warm `(+ 1 2)`) | 52–65 ms | 38.6 ms median, 6,473 calls, settle 27.4 ms (at e9bcf36d2) | sentinel-baseline.md |
| Root REPL on default | 10–11 s, 3.5–6.9 GB per request (test step ran the branch's drift) | 124 ms | b7be69770 |
| Health | `:faulted`, 7 acquisition refusals | `:faulted`, 9 open faults, acquisition refusal ×20, new merge refusal | `bin/seon status`, `seon.problems/open-faults` |
| Branches / agents on default | 49 / 33 | 71 (62 agent, 6 attempt) / 112 | `datahike.api/branches`, query |
| Size | 95,285 src / 105,806 test | 95,874 src / 106,812 test (+1,650 −1,019 src since c2284b109) | `wc -l`, `git diff --shortstat` |

### Per chain experiment

| | Chain 2 (c792b6aaf) | Chain 3 (98720b4eb) | Chain 4 (46a1c4f06) |
|---|---|---|---|
| Shape | Design+Prove → Implement | Design+Prove → Implement | Implement only |
| Attempts / DeepSeek-flash calls | 7 / 73 | 8 / 105 | 5 / 69 |
| Prompt tokens (cached) | 838k (611k) | 1,308k (951k) | 884k (661k) |
| Hand acts after launch | 8 | 23 | 17 (16) |
| of which G6 + G7 + G8 | 5 | 20 | 11 |
| Merge request | yes (b210caab20ba) | no | no |
| Merged | by an Opus lane as a git commit (4825cac93); compiled check needed, printer is platform | — | by a Sonnet lane (fec3d7737) from attempt 4's code plus 3 root fixes |
| Calls that did the work when nothing interfered | 14 + 2 | 15 + — | 3 |

The agents did the work cheaply when the platform let them (chain 2's Implement: 2 calls; chain 4's attempt 4:
3 calls). Every failure to reach a merge request was the platform or the subject: G6/G7/G8, H1 (instructions cut
at 1,024 characters by the printer, against AGENTS.md "an agent's own values are never clipped"), H2 (a step
proof reading tags reused red at an unchanged key), J1 (a schema the writer admitted wedged the branch for 4 min
with no end and no recorded error), J3–J5 (contract and schema admission refusals), J8 (a platform fixture).

### What only looked like progress

- **"Merged" agent work.** Both merges today were lanes' git commits of platform code, not the merge path; chain 4's
  commit carries root's own fixes (the shutdown in `finally`, the termination wait, the schema member). They prove
  the agents can write the change, not that Seon can merge it.
- **Restart batching.** The rule (64668ecae, 23bcc612e) stopped forced restarts: no dependency bump since 13:14.
  But default restarted **ten times between 13:15 and 17:10** (operator-start records: c2284b109, c792b6aaf,
  f8565bffa, 5640aef92, c1a327d35, 98720b4eb, e9bcf36d2, f7c730911, 46a1c4f06, b14dc2b3f; ready 5.9–32.7 s), one per
  26 minutes. Root chose them to put each fix live before the next chain; that is a reasonable reason, but it is
  the previous audit's 13-a-day pattern at a higher rate.
- **Pre-briefs far ahead.** Eight pre-briefs (about 1,500 lines) were written for M5–M14 and R4–R12 between 15:59
  and 17:40. M5 and M6 used theirs within the hour. M12–M14 and R4–R12 will start after several more commits to
  `agent.clj`, `turn.clj` and `test.clj`; their caller lists will be stale by then.
- **Triage without a run.** red-triage (25 of 37 unknown), task-triage-1 (114 of 150 unresolved) and chain-queue
  (nothing re-run) are static reads. They filled pages, not the schedule.

### Shortest credible path

**(a) The first automatic merge of one ordinary task.** Facts already merge automatically; code does not, for one
reason (publication) and one rule (the `requested?` hold that the reason forced). Path:

1. Owner decision Q1 (§7): where an automatic merge writes files when the JVM runs an archive.
2. M9, narrowed: `seon.merge/publish!` = `write-back!` + `commit!` against that root, derived as due work after
   `:seon.task/merge-commit` (proof `seon.cluster.squash/an-accepted-merge-is-published-by-the-next-pass`, filed).
3. "M8a": `seon.cluster.agent/next-actions` merges a branch holding the agent's merge request when its gate passes
   (delete `:seon.flow.plan/requested?` and the wait); the rest of M8 (deleting the verb and the request schema)
   follows later. Red proof: the filed `seon.task/a-done-branch-merges-with-no-merge-request-written-by-the-agent`,
   or a new row in `seon.cluster.db-flow-test/each-ended-assignment-derives-its-lifecycle-from-its-facts`.
4. One probe: a single Implement step on an ordinary, interpretable owner with an existing red proof, verified
   first by running the proof with `my.test/check` on a probe branch (an error rules it out; chain 4's lesson). The
   first attempt is started by db<->flow itself, which worked in all three chains, so G7 and G8 do not occur unless
   it caps. Expected hand acts: the config act only.

M7 is not on this path: a single Implement step has no designed reds, and `prepare-merge!` already gates the proofs
and reaching tests on a merge branch. At today's rate (M6: two slices in about 55 minutes) this is half a day.

**(b) An unattended chain** adds: M12 (relaunch; `seon.cluster.agent/a-capped-attempt-has-no-relaunch`), M10 (fork
main after the needs merge; deletes G7's cause and E6's octopus), G8 reproduced on an attempt db<->flow started and
fixed at the listen route, M7 (a Prove step's designed reds merge onto main so its Implement successor forks main
and reads them), H1 (the current-state block shows instructions whole), J1 (an admitted uncompilable schema reference
is refused at `seon.schema/register!`, and a read error on the turn path ends the attempt loudly). Then the six-step
tree. Roughly a day and a half after (a), serial, because M10, M12 and M13 all edit `src/seon/cluster/agent.clj`.

## 2. How we work

### Model roles and schedule today (AGENTS.md §4, adopted 16:09 in 4826578fa)

Commits since c2284b109 by co-author line: Sonnet 48, Opus 33, Haiku 17, Fable 2, none 6 (Astra lanes write none);
by hour 13h 19, 14h 27, 15h 22, 16h 28, 17h 11. Lane hours and tokens are not recorded anywhere I can read; the
judgments below are from commit content and the documents.

| Role | Delivered | Wasted or weak | Verdict |
|---|---|---|---|
| Opus | M3, M5 ×2, M6 ×2, branch REPL ×2, G1, E7 (measured: program-context kind 165.5 → 31.0 MB), chain 2/3/4 runs and their friction tables with red proofs (G1–G12, H1–H6, J1–J8), M4 stopped correctly before editing | M3 parked 15:23 for a "Datahike join bug" that was a fixture task handle with a leading digit (e9bcf36d2, 83acdfbf3); about 30 minutes plus a parked patch | Right role. The merge-path lanes are the highest-value work of the evening. |
| Sonnet | root-drift b7be69770 (11 s → 124 ms), step-skip 360e1f2ea, cache weights (5218b097e 41 MB, e70ed838d 163 MB of key-sized charges), unmap/unalias report once, task retirements, web-check (48bb5cfee) | none found | Right role; the root-REPL fix is the single biggest productivity gain for root today. |
| Haiku | dig-test-results (used by both plans), sentinel baseline, open-faults-evening, pre-briefs M5–M7 used immediately | chain picks 2 of 3 wrong; red-triage on a superseded round; task-triage 76 % unresolved; pre-briefs hours ahead of their phases | Role is right for gathering; wrong for picking. Its picks need a check that runs something. |
| Astra | R11 (6433c7593, end-to-end measured, no dependency change) | not visible in the record | Right brief this time (super-linear repeated work, end-to-end proof). |
| Fable | test-results hybrid | — | Two narrow checks on it (6d82fc948, def69a2cc), not four; the brief change worked. |

**The design round.** The test-results round ran dig 13:29 → plans 13:39/13:42 → hybrid 13:54 → checks 14:05 and
14:30 → tasks 14:34 → R1 green 14:40, R2 14:58. Seventy minutes to tasks, two phases done within thirty minutes
after: worth it. Its weak point is the same as done-and-merge's: the hybrid orders phases by dependency, not by what
the next probe will meet. Done-and-merge's order put M4 before M6 (m4-notes; the narrow check missed it) and keeps
the lifecycle phases late (finding 2). R3 was designed and then dropped when the owner counted 0 `defmulti` in
`src` (51a5e159e): AGENTS.md's "count before designing" rule was written after it.

**Gating.** The lane gate today is "the named Vars are green, the known reds are listed in the message". It works
only because lanes name Vars one at a time; 202 red check records with `reused 0` say every check reruns everything
it names in a fresh JVM and is judged against nothing.

### The most effective way from here (concrete)

- **One serial goal lane, Opus,** owning `src/seon/cluster/agent.clj`, `src/seon/merge.clj` and the `turn.clj`
  patches for the merge path, phase after phase in the order of §6 item 1–2. Each phase starts with the next
  phase's pre-brief only (Haiku, 15 minutes before), never three ahead.
- **One Sonnet lane on gating** (R10, central round per restart batch) and hygiene, file-disjoint from the goal lane.
- **One Astra lane** on the costs a chain step pays (first merge in a JVM 1,275 ms / 2.3 GB; fresh custody 81–89 ms;
  cold reach 1.8 s / 5.0 GB; the defn test step), each proven by `seon.profile/measure` on the same probe.
- **Haiku for digs, pre-briefs one phase ahead, flake bisects and the sentinel; never a subject pick or a triage
  verdict without running the reproduction.** A chain subject is admitted only after its proof is run on a probe
  branch and is red (not erroring) for its stated reason.
- **Root runs the probe** after each goal phase that changes what the chain meets, and restarts default only before a
  probe or at a batch: at most four restarts a day.
- Three editing lanes plus design lanes. Today's limit is not lane count but the two collision files.

## 3. The plan (plan.md §0)

### Stale or false claims in §0, with the correct value

| Claim in §0 | Correct value | Source |
|---|---|---|
| Header: "last 2026-10-12, verified at the code of HEAD 015673f47 and corrected for 786915ca3" | revised piecemeal through 59a1ffe1f; never re-verified as a whole since 015673f47 | git log of plan.md |
| "root has merged three such changes by hand" | five: + 4825cac93 and fec3d7737, both lane git commits, none through `my.root/merge!` | git |
| "The first chain experiment ran on 2026-10-12" | four ran; chain 2 reached a merge request | chain-experiment-1..4.md |
| "783 tasks in 94 files … 155 … 628 have no tagged proof … 25 stale" | 825 task entities on default; task-triage-1's static scan: 803 entries, 638 untagged (morning counts not re-measured) | query; task-triage-1.md |
| "Running now: lanes branch-repl, chain-2, cost-gb, dig-test-results, lane-batch" | all finished; at 17:40 running: round (central round on HEAD, uncommitted), filing (18 claimed paths) | handoff note; `bin/lane status` |
| Item 1: "an agent's own refusal does not mark the cluster `:faulted`" (as done by implication) | health `:faulted`; open faults include an agent's contract refusal (`my.agent/retire-branch!`, chain-2-probe); the task from 1d4a668df is unassigned | `bin/seon status`; open-faults-evening.md row 3 |
| Item 5: "cold reach digests (3.1 s / 6 GB)" | 1.8 s / 5.0 GB after R11 | 6433c7593 |
| Item 5: "the test step after a `defn` (0.6–1.9 GB)" | defn request 378.6 ms / 117.9 MB at e9bcf36d2; the 0.85–2.2 GB came from drift selection, removed by b7be69770 | sentinel-baseline.md; b7be69770 |
| Phase table M row: "M4 next … then M7" | correct as root's ruling, wrong for the goal (finding 2); M3 and M6 rows of the hybrid's §6 table still read "filed as" and its lead says "no other phase is started" | hybrid-done-and-merge.md §6 |
| R row: "R4–R12 filed, not started"; "R4 … next" | R11 done (6433c7593) | git |
| E4 row: "request 77–89 → 61–71 ms" | 38.6 ms median warm at e9bcf36d2 | sentinel-baseline.md |
| E7 row: "partial … `script/seon/program_context_weight.clj` does not exist" | done: bc347d583 (script exists; kind 165.5 → 31.0 MB); the row change sits unapplied in e7-plan-row.patch | bc347d583 |
| E3/E5: "after item 1" | item 1's named causes are fixed (7fea1f2e7, 9e23a93b7); E3/E5 are not on the goal path | — |
| E6: "partial; red proof a-successor-starts-from-its-predecessors-program-context" | superseded by M10 (successors fork main; the octopus goes) | hybrid-done-and-merge §1 "Forking and joins" |
| Cost findings, measured at 015673f47: "(+ 1 2) 82–113 ms, 7,035 calls"; items 1, 3 | 38.6 ms, 6,473 calls; item 1 fixed by E4, item 3 by 360e1f2ea; the recording transaction is the settle stage, 27.4 ms | sentinel-baseline.md |
| Cache ranking items 1–4 | 1 fixed (786915ca3, E7), 2 fixed (5218b097e), 3 fixed (a1ad947be), 4 fixed (74b86a38c); cache now 401.7 / 512 MB weighed | `bin/seon status` |
| "Findings outside any phase": `branch-connection` NPE; integers printed as entity references | the first retired as correct behavior (913e6a4f4); the second fixed (4825cac93); custody on a fresh agent is 81–89 ms, not 165–178 | git; sentinel |
| P3 row: "`seon.test/change-reds` still has a caller in the merge gate" | still true (`seon.cluster.source`, the `requiring-resolve` in the gate); M7 owns it | `rg change-reds src` |
| Older direction: "The chain experiment … Run once on 2026-10-12" | four runs | — |
| M-final row (hybrid): "Chain experiment 2 (P-final)" | chain experiment 2 ran; M-final is the first unattended chain | — |

### Is the order right?

No, in three places.

1. **Publication and the `requested?` hold before M4 and M7.** They are the only thing between a done Implement
   branch and an automatic code merge (§1).
2. **M12, M10 and the G8 route before M7/M8's remainder.** They are 36 of 48 hand acts.
3. **M4 waits for its first observed conflict.** Four chains, zero conflicts; AGENTS.md: "Never build protection
   against a failure that has not been observed." Root's prose conflict task stays until then.

Missing from §0 and more important than E3/E5/E6 and R4–R9: the first live lifecycle failures (finding 4); the red
baseline as stored data (R10 plus the central round per batch); fresh-install boot is not threatened by anything
I found today (every restart booted, 5.9–32.7 s), so it stays a standing check, not an item. The 628/638 untagged
tasks: do not triage them statically again; retire on contact (a lane that touches a task's owner retires or
proves it), and let the chain queue draw only from tasks whose proof was run.

Cut or park: E3, E5 (the program context is not what chains meet); R4, R7–R9, R12 until R10 and M7 exist; M11,
M15–M18 until the unattended chain; the src CN renames (already paused).

## 4. Issues to focus on

| # | Issue | Evidence | Owner to change |
|---|---|---|---|
| 1 | Automatic code merge has no valid publication root | m6-notes: `write-back!` refuses `data/source/<sha>`; `:seon.flow.plan/requested?` holds every requested branch for root | owner decision Q1; then `seon.merge` (M9) and `seon.cluster.agent/next-actions` |
| 2 | Relaunch, fork and end-copy need hands (G6, G7, G8) | 36 of 48 hand acts; open fault `seon.cluster.agent/fork-attempt!` ×6 with no task (open-faults-evening row 1) | `seon.task/ready` (M12), `seon.cluster.agent/write-attempt!`/`fork-attempt!` (M10), the branch's listen route |
| 3 | The first live merge refused | `seon.db/transact!` from `seon.merge/merge!`, unowned-entity, entity 292095 (a `:seon.error` fact), attempt `4438ded31ef2` now `:error`, at 10:10:29Z on b14dc2b3f | `seon.merge/merge-tx` (which coordinates it copies from a branch: an error occurrence created on the branch has no owner on main) |
| 4 | Pre-rename branches cannot be acquired | acquisition refusal ×20, row `seon.cluster.squash/attribute-tx`, cause "database-effective-config requires configuration naming its cluster; this database has none", branch `:cluster-default` | `seon.sci.eval/database-effective-config` (reads cluster config from the branch database instead of the evaluation's bindings) |
| 5 | Gates judged against nothing | 202 red check records since 13:22, `reused 0`, 232 min; R10 not started; no central round since aee027806 | `seon.operator/check-verdict` (R10), `script/seon/central_round.clj` per batch |
| 6 | Agents' own values clipped | H1: instructions of 2,473 characters shown cut at 1,024; the agent spent five calls recovering them | the value printer's string budget on the current-state block (`seon.context/a-held-steps-instructions-are-shown-whole-in-its-context`, filed) |
| 7 | A silent wedge | J1: an admitted bad schema reference made every declared read refuse; `next-agent-work` answered nil for 4 min with no end, no error | `seon.schema/register!` admission; the turn path's read error must end the attempt |
| 8 | Probe agents and branches never deleted | 71 branches (62 `agent-*`), 112 agents; AGENTS.md: a finished agent's branch is deleted | MCP agents have no assignment, so the lifecycle never sees them (M13's ROOT/AGENT declaration) |
| 9 | Subject selection without a run | chain 3 and 4 subjects misjudged; 174 calls, 40 hand acts, no merge request | the chain-queue procedure: run the proof on a probe branch first |
| 10 | A transitional fence | `requested?` (M6) and the two `kept?` conditions (M5) are holds beside the mechanism, each named with its removal phase | removed by M8a, M10; keep them named in the plan until gone |
| 11 | Stale lane claims and an uncommitted tree | `bin/lane status`: astra-one-system (since 633e25beb, 10-09), astra-review-merge-base (d9eb15c05, 10-10), root-report (dcfbee6c1, 10-09); 15 modified tracked files under `test/` and `resources/seon/tasks/`; an untracked `reference-code/http-kit` | root at the next batch: release dead claims; the filing lane commits or patches |
| 12 | Root's read-only JVM queries write a transaction and allocate ~100 MB | my five queries: 84–156 ms, 60,752–68,405 calls, 90–124 MB, `:transactions 1`, settle 26–77 ms each, against the sentinel's warm SCI request at 38.6 ms / 7.3 MB | the JVM-mode recording path (`seon.turn` recording for root's JVM runs); a finding for the cost lane, under 1 s so not urgent |
| 13 | Plan status lags the code | §3's table; the E7 row in an unapplied patch because plan.md was held by another lane | the commit that moves a phase updates the row (the standing rule); plan.md should not be held by a build lane |

Flaky or unclear: `seon.cluster.db-flow-test/a-prove-step-admitted-by-its-successor-opens-a-model-turn` (red in a
whole-namespace run, green alone 9 of 9: g1-proof-bisect.md; two tests share a test world, filed as
`seon.test/two-tests-never-share-a-test-world`); `seon.turn-test/an-eval-request-on-an-unchanged-program-forks-nothing`
(0.5 ms fork bound under contention, handoff note); `seon.render.web-test/a-reconnect-refuses-a-pass-derived-before-it-connected`
(21 s against 5 s, 48bb5cfee).

## 5. Risks to the next day

1. **The next chain meets finding 4 first.** Old attempt branches that cannot be acquired or merged will fault the
   first lifecycle passes again after every restart until their cause is fixed or they are deleted.
2. **The goal lane serializes on two files.** Every phase on the path edits `src/seon/cluster/agent.clj` or patches
   `src/seon/turn.clj`; a stall in one lane stops the goal. Mitigation: one lane owns the path; everything else
   stays off those files.
3. **The publication decision is not made, and the queue does not show it.** Without Q1, M8 and M9 cannot finish and
   the first automatic merge does not happen however many phases run.
4. **Restart churn plus red-by-default checks.** At one restart per 26 minutes and every check red, a new red can hide
   behind the listed old ones; the next regression of the kind f18ab95e3 was will pass a gate.
5. **Concurrent agent turns starve default.** Chain 4 recorded 30–150 GB allocated per minute and GC windows up to 78 s
   while other lanes ran agent turns; a chain and an Astra lane on default together can turn every probe number
   into noise.
6. **Research volume crowds the schedule.** 42 of 106 commits were documents; pre-briefs far ahead go stale; static
   triage produces "unknown". The worst stretch of today (chain 3: 23 hand acts on a subject that was too hard)
   came from a pick nobody ran.

## 6. Proposed plan §0

> ## 0. Where we are, and the order (revised in place; last 2026-10-12 night at HEAD aea2a1052, default b14dc2b3f)
>
> **Where we are.** Agents write correct small changes from Seon's context (chains 2 and 4: 2 and 3 calls when
> nothing interfered), but nothing has merged through the merge path: five agent changes reached `main` as lanes'
> git commits, the last two today (4825cac93, fec3d7737). One merge engine exists (`seon.merge/merge!`, keyed by the
> assignment); facts merge automatically, and code waits for root because an automatic merge has no valid
> publication root on an archive-run JVM (`m6-notes.md`). Chains 2–4 needed 8, 23 and 17 hand acts, 36 of 48 of
> them relaunches, forks and end copies. Default is `:faulted` (9 open faults; the first live lifecycle pass failed
> twice); every check is red and reuses nothing, because checks are judged against no stored baseline.
>
> **The order** (one serial goal lane on `seon.cluster.agent`/`seon.merge`/`seon.turn` patches; beside it one gating
> lane, one cost lane and design lanes on disjoint files; restarts only before a probe or at a batch, at most four a
> day):
>
> 1. **First automatic merge of one ordinary task** (Opus, goal lane). After the owner's publication decision:
>    M9 narrowed (publication as derived due work against that root; proof
>    `seon.cluster.squash/an-accepted-merge-is-published-by-the-next-pass`), then M8a (db<->flow merges a branch
>    holding the agent's merge request when its gate passes; `:seon.flow.plan/requested?` deleted; proof
>    `seon.task/a-done-branch-merges-with-no-merge-request-written-by-the-agent`). Probe: a single Implement step on an
>    interpretable owner whose proof was run red on a probe branch. Target: merged with zero hand acts beyond the
>    config act. Unblocks the goal's missing link.
> 2. **The lifecycle needs no hands** (Opus, goal lane, after 1). M12 (proof
>    `seon.cluster.agent/a-capped-attempt-has-no-relaunch`), M10 (proof
>    `seon.cluster.agent/a-join-forks-main-after-both-needs-merge`; supersedes E6), G8 reproduced on an attempt
>    db<->flow started and fixed at the listen route (task with its proof filed first). Target: a two-step chain with
>    at most two hand acts. Unblocks the unattended chain.
> 3. **The first live failures** (Sonnet, now, file-disjoint from 1): `seon.merge/merge!` refused unowned-entity on
>    attempt `4438ded31ef2`; acquisition of pre-rename branches (`seon.sci.eval/database-effective-config`, ×20);
>    `fork-attempt!`'s gone-predecessor fault (until M10 deletes it); an agent's contract refusal recorded as a cluster
>    fault. Each filed with a red proof from the recorded fact. Target: health leaves `:faulted`.
> 4. **Checks judged against main** (Sonnet, gating lane): R10 (proof
>    `seon.operator/a-check-red-main-holds-red-does-not-refuse`) and the central round stored per restart batch;
>    then R5 → R6. Target: a check refuses only on a transition; check records with `reused 0` fall.
> 5. **The probe in flight** (root; subject picked by Haiku and admitted only after its proof runs red on a probe
>    branch): one chain after each of items 1 and 2, graded on hand acts, calls and errors by signature.
> 6. **The Prove step's product merges** (Opus, goal lane, after 2): M7 (designed reds as data;
>    `change-reds` deleted), the rest of M8, H1 (instructions shown whole), J1 (an uncompilable schema reference is
>    refused at registration; a read error ends the attempt). Then the six-step tree unattended (M-final).
> 7. **Costs a chain step pays** (Astra): first merge in a JVM (1,275 ms / 2.3 GB), the defn test step, fresh custody
>    (81–89 ms), cold reach (1.8 s / 5.0 GB); each proven end to end by `seon.profile/measure` on the same probe.
> 8. **Hygiene at each batch** (Sonnet): delete finished agents' branches (71 now), release dead lane claims, update
>    this table in the commit that moves a phase. Paused: M4 (until a conflict is observed), M11, M13–M18, E3, E5,
>    R4, R7–R9, R12, the src CN renames.
>
> ### Phase status
>
> | Phase | Status | Evidence | Next |
> |---|---|---|---|
> | P1, P2, P4, P5, P6 | done | b09c02b29, 9d25711e7, 031125438, be32e7501, 015673f47 | |
> | P3, P7–P10 | absorbed into done-and-merge | P7 done in M5 (1b09d8043); `change-reds` still called by the merge gate (M7) | M7, M12, M13 |
> | U0–U7 | superseded by M1–M18 | | |
> | M1, M2, M3, M5, M6 | done | 3fe23b2c0, 346645d56; 3eca1a962; 767551c7b; 1b09d8043, c74ef3437; 7c3077b9e, 3da1f6449 | |
> | M9 (narrowed), M8a | not started | blocked on the publication decision | item 1 |
> | M12, M10 | filed | | item 2 |
> | M7, M8 rest | filed | | item 6 |
> | M4 | filed, paused | no conflict observed in four chains; the merge branch exists since M6 | when one is observed |
> | M11, M13–M18, M-final | filed | M13 needs a small design slice (what starts MCP and schedule agents) | after item 6 |
> | G1, G3, G4, G5, G9 | done | 6a687ac17, 9116692ea, 5640aef92, ea27cde53, 98720b4eb | |
> | R1, R2, R11 | done | 50ca41030, 474174b52, 6433c7593 | |
> | R3 | replaced by the no-key rule | 51a5e159e (0 `defmulti` in src) | |
> | R10, R5, R6 | filed | | item 4 |
> | R4, R7–R9, R12 | filed, paused | | after R10 and M7 |
> | E1, E2, E4, E7 | done | f424382cd; 172b23e7c; bc347d583 (165.5 → 31.0 MB) | |
> | E6 | superseded by M10 | | |
> | E3, E5 | paused | not on the chain's path | |
> | H1–H10, AW0–AW7 | see their documents | | |

## 7. Questions for the owner

**Q1. Where does an automatic merge write files while default runs its committed archive?**
- **A (recommended).** Configure `:seon.config.fs/working-root` to the checkout and let `seon.merge/publish!` commit
  only the merged paths with `git commit --only`, as `bin/lane commit` does, under a root-held lane claim on those
  paths. Cost: one dial and the claim; sacrifice: Seon's commits land in the shared tree while lanes work, so a merge
  touching a lane-held file must wait or refuse.
- B. Merge into Datahike only; root writes files for all merged entities at each restart batch. Cost: none now;
  sacrifice: files lag the database for hours, and a restart from HEAD before the write loses the merges.
- C. A private index and request ref (Astra's deferred §3), or a worktree. Cost: a design round and owner
  authorization for worktrees; sacrifice: a day before the first automatic merge.

**Q2. Order of the done-and-merge phases.**
- **A (recommended).** Publication and M8a first, then M12/M10/G8, then M7 and the rest; M4 waits for an observed
  conflict. Sacrifice: the hybrid's dependency order is revised and two phase tasks change their needs.
- B. The hybrid's order (M4 → M7 → M8 → M9 → M10 → M12). Sacrifice: the first automatic merge is about a day later,
  and chains keep needing 15–20 hand acts meanwhile.

**Q3. Restart cadence.**
- **A (recommended).** Only before a probe or at a scheduled batch, at most four a day. Sacrifice: a fix waits up to a
  few hours to go live on default.
- B. Today's practice, a restart per ready fix (ten in four hours). Sacrifice: lanes' evidence is on a moving default.

**Q4. The gate until R10 exists.**
- **A (recommended).** Keep "named Vars green, known reds listed", and make R10 the gating lane's first slice.
  Sacrifice: a day of the current honor system.
- B. Require the central round on HEAD at each batch and diff by hand. Cost: about 35 minutes of a JVM per batch.

**Q5. What Haiku may decide.**
- **A (recommended).** Haiku gathers, pre-briefs only the next phase, and proposes subjects; a subject is admitted only
  after its proof is run on a probe branch, and no triage verdict is recorded without a run. Sacrifice: fewer pages.
- B. As today. Sacrifice: today's three misjudged picks and corrections repeat.

**Q6. Probe agents created over MCP never get deleted (62 `agent-*` branches).**
- **A (recommended).** Every MCP agent other than root gets an assignment at creation (M13's ROOT/AGENT declaration),
  so the derived lifecycle deletes its branch when it ends. Sacrifice: M13's design slice moves earlier.
- B. Root deletes probe branches by hand at each batch. Sacrifice: a recurring chore and storage growth between
  batches.

## 8. Root's brief (for a fresh, compacted root context)

Verified at HEAD aea2a1052 and on default (b14dc2b3f) at about 17:45 +07 unless marked **unverified**. Default
runs b14dc2b3f; HEAD is 6433c7593, fec3d7737, 3da1f6449, bc347d583 and doc commits ahead of it.

### 8.1 Lines of work

| Line | Model, id (handoff note) | State | Evidence | What root does |
|---|---|---|---|---|
| m6 | Opus | **done** | 7c3077b9e (rename `seon.cluster.squash` → `seon.merge`), 3da1f6449 (one `seon.merge/merge!` keyed by `:seon.task/assignment`), 59a1ffe1f (status) | the hybrid's §6 M3 and M6 rows still read "filed as": fix in the next plan commit |
| m5 | Opus | **done** | 1b09d8043, c74ef3437 (`seon.cluster.agent/lifecycle-facts`, `next-actions`, one catch `failed-pass!`) | first live pass on default failed twice (§0 finding 4) |
| e7 | Opus | **done** | bc347d583 (`seon.sci.eval/context-weight`, `script/seon/program_context_weight.clj`); plan row in unapplied `e7-plan-row.patch` | apply the patch or this §6 table |
| reach-incr (R11) | Astra codex | **done** | 6433c7593 (end-to-end numbers in its body) | not live until restart; hybrid-test-results R11 row still "filed as" |
| chain-4 + chain4-merge + web-check | Opus, Sonnet | **done** | b14dc2b3f (experiment), fec3d7737 (merge by a lane), 48bb5cfee (whole `seon.render.web-test` compiled: no regression) | release the stale `web-check` claim (`bin/lane status`); file the three pre-existing web reds named in the handoff note |
| step-skip, root-drift | Sonnet | **done, live** | 360e1f2ea, 3ddcf3e67, b7be69770 | none |
| round (central round on HEAD) | Sonnet a6200e56d87c57f03 | **unknown**: no `central-round-head.md` at HEAD | `git ls-files` | ask its status before starting another round |
| filing | Sonnet afc8a6c6a0f4579d4 | **running**: holds 18 paths since bc347d583; the tree shows 15 modified tracked files under `test/` and `resources/seon/tasks/` | `bin/lane status`, `git status` | nothing commits those files until it releases |
| audit2-astra | Astra codex | running (independent; not read by me) | `bin/lane status` | then the Fable hybrid of the two audits, owner questions, plan §0 |
| stale claims | — | astra-one-system (since 10-09), astra-review-merge-base (10-10), root-report (10-09) | `bin/lane status` | release at the next batch |
| M4 | (lane m4 stopped) | **not started**; pre-brief aea2a1052 asks where `:seon.merge/unmerged` is written | `m4-notes.md`, `prebrief-m4.md` | park it (§3, Q2) |

### 8.2 Next, in order, and why

1. **Before any restart of default, reproduce lane e7's fresh-root finding** on an isolated root at HEAD: base
   acquisition refusing `seon.cluster.squash` declarations with "`seon.sci.eval/database-effective-config` requires
   configuration naming its cluster". Default shows the same signature (500ddeee…, ×20, last 10:10:32Z, branch
   `:cluster-default`, row `[:seon.fn/sym seon.cluster.squash/attribute-tx]`). **Unverified** whether default's agent
   SCI forms are refused now; check with one disposable non-root agent and retire its branch, never root SCI. If a
   fresh boot at HEAD refuses every agent form, a restart onto HEAD breaks every lane: that is a boot defect for an
   Opus lane first (owner `seon.sci.eval/database-effective-config` and whatever still holds the old declarations).
2. **File the two live lifecycle failures with red proofs** (Sonnet): `seon.merge/merge!` refused by
   `seon.db/transact!` "unowned-entity" on attempt `4438ded31ef2` (signature 75d68dbb…, entity 292095, a `:seon.error`
   occurrence fact from the branch); `seon.cluster.agent/fork-attempt!` "predecessor branch is gone" (db142341…, no task
   names it; M10 deletes its cause).
3. **Ask the owner Q1 and Q2** (or carry them into the hybrid of the two audits). Without Q1 no phase on the merge path
   can finish the first automatic merge.
4. **Launch the goal lane** (Opus) on §6 item 1 after Q1; the gating lane (Sonnet) on R10; the cost lane (Astra) on
   the first-merge cost; a Haiku pre-brief for the goal lane's next phase only.
5. **Restart default once**, after 1 holds and the commits ahead are proven, then run the sentinel
   (`seon.sentinel/run`, baseline `sentinel-baseline.md`: warm 38.6 ms, fresh 205–215 ms, defn 378.6 ms, all at
   e9bcf36d2) and `seon.cache/status` (401.7 / 512 MB before; E7 lowered the program-context charge, so expect the
   cache to fill with other kinds).
6. **Probe** after item 1 lands: a single Implement step on a subject whose proof `my.test/check` has run red (not
   erroring) on a probe branch.

### 8.3 Traps

- **plan.md §0 is stale in the nineteen places §3 lists**. Read the phase rows against `git log`, not the text.
- **"Merged by hand" is not the merge path.** 4825cac93 and fec3d7737 were lanes' git commits; `my.root/merge!` has
  merged nothing on default (no assignment has `:seon.task/outcome`).
- **Check records look like "every candidate is red".** 202 `operator-check` records since 13:22, all
  `:client-failed`, `reused 0`; passing checks seem to leave no record there (unverified), and the reds are mostly
  historical. Do not read them as regressions, and do not count them as check volume without saying so.
- **Old evidence numbers.** The cost table in plan.md (82–113 ms, 7,035 calls) is from 015673f47; the 0.6–1.9 GB
  defn test step was drift selection, gone since b7be69770; "cold reach 3.1 s / 6 GB" is 1.8 s / 5.0 GB since 6433c7593
  (not live on default yet).
- **red-triage.md's owner ranking is superseded** (prebrief-red-owners.md: three of four owners' tasks retired green at
  aee027806). Its 25 "E" rows are unknowns, not reds.
- **Chain subjects**: chain-queue.md #1 was platform (chain 4), #2 is stale (G9 done in 98720b4eb). Run the proof first.
- **Do not fork `:attempt-ee6cfaeb5c0b`** (its schema projection is broken: J1; handoff note). The other chain residue
  branches are disposable.
- **`requested?` and the two `kept?` conditions are transitional holds**, each with its removing phase (M8a, M10):
  name them as such; do not build on them.
- **Root's JVM queries record a transaction** (~100 ms, ~90 MB each); batch read-only probes into one form.
- **Root REPL SCI on default writes test runs to main**; Haiku and audits use JVM mode only.

### 8.4 Already ruled by the owner (do not re-ask)

- Auto-merge when the source is done, the gate is green on the merge result, nothing is unmerged and the writer's
  compare-and-swap passes; root looks only at unmerged coordinates (Q-a, hybrid-done-and-merge §4).
- Everything a step made (facts and code) merges at each step's end; successors fork main after their needs merge
  (Q-b, Q-c: C1); the branch is the proposal; the current-state block shows the derived diff (M14).
- Base result per proof only from existing runs: no extra proof run, no `admit-run` change, nothing stored; no run =
  unknown (M1, `m1-notes.md`).
- A test result is keyed by what the test ran and reused at that key, red included; a test runs only when its key has
  no result (hybrid-test-results, d598bad77); methods are not declarations: a test that ran a callable with no
  definition digest gets no reuse key (51a5e159e, 0 `defmulti` in `src`).
- Performance commits are proven by the targeted cost end to end; a measured regression or no gain is not committed
  (AGENTS.md, 64668ecae). Dependency and fork bumps and wide renames only in a restart batch (`bin/lane commit
  --restart-batch`, 23bcc612e).
- Model roles (AGENTS.md §4, 4826578fa): Fable only writes hybrids and audits; narrow-check failures go to Opus or
  Sonnet; Haiku never judges alone.
- Words: Clojure, Datahike, Malli, SCI, core.async.flow and git only; "assignment" is the one Seon word for the merge
  request's entity; never row, land, settle, world, custody, arm.
- Owner preference, repeated today: the simple general rule over modeling a rare case; count before designing.
- Open, default kept: Q-e publication order (Datahike first, publication as derived due work). Q1 here asks the part
  Q-e left open: which file root.

## Evidence and limits

- Default queries (JVM mode, root, reads): tasks 825, assignments 20 (none with `:seon.task/outcome`; ends:
  `:sent-back` 12, `:done` 4, `:capped` 2, `:error` 1, `:cancelled` 1), agents 112, tests 2,911, test runs 25,
  branches 71 (`agent` 62, `attempt` 6), cache 401,652,216 / 512,000,000 B; nine open faults read with their facts
  (the three quoted above in full). Each query was also recorded as a root run with one transaction (issue 12).
- Operator logs: `operator-start-20261012T*` (22 today, 10 since 13:15) and `operator-check-20261012T*` (404 today;
  202 since 13:22, all `:client-failed`). I found no record of a passing check in that directory, so the true check
  count is at least 202; a passing check may write elsewhere.
- Not measured: lane hours and tokens per lane (not recorded); a central round on HEAD (not run; the round lane has
  not committed); whether `seon.merge/merge!`'s refusal reproduces at HEAD (default runs b14dc2b3f, before M6 slice 2).
