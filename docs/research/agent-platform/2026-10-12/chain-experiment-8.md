---
title: "Chain experiment 8: with a 40-call cap the forgive task stalls at call 24 on two prose replies, no defn; the stale merge base (P3) is root-caused"
type: research
status: complete (lane chain8, 2026-10-12); no merge, no publication; the subject task is left :held on default
created: 2026-10-12
---

# Chain experiment 8

**Result: not merged.** This run used the same subject as chains 6 and 7, `seon.sci.forgive/forgiving-forgiven-text-changes-nothing`
(the fix goes in `seon.sci.forgive/scan`). Default ran archive **104e39fce**, restarted and reset, with DeepSeek flash
and the call cap raised to **40**.

- The attempt ended **`:stalled` after 24 model calls in 93 s** (13:36:40.9 to 13:38:13.8 UTC).
- It used 370.0k prompt tokens (289.3k cached, 78 %) and 10.3k completion tokens.
- **It never wrote a `defn`.** `scan` on the branch has main's digest (`c54e2f6c…`), so **0 of the 5 failing rows**
  pass. Chain 7 had reached 4 of 5.
- The stall came from two prose-only replies in a row (calls 23 and 24). In both, the model was planning to rewrite
  `src/seon/sci/forgive.clj` with `slurp`/`spit` instead of redefining `scan` at the REPL.
- **Hand acts after launch: 0.** Before launch there was 1: the `:running` transaction.
- db<->flow assigned the task in the launch's own pass, and copied the end to main 122 ms after the branch end.
- `:merge`, `:publish` and `:delete-branch` were **never reached** (the fifth chain in a row).
- No merge commit, no Seon git commit, and the branch was not deleted.

Authorities: AGENTS.md "The goal"; [chain experiment 7](chain-experiment-7.md) (method, P-frictions); [chain experiment
5](chain-experiment-5.md) (setup).

## Runtime and subject check (before running)

- **Default.** pid 25964, booted 13:34:23 UTC on 104e39fce and reset.
  - Health `:degraded`: 4 untested-hot warnings, plus a throwing warning on `seon.sci.eval/agent-require` (13,541
    calls, all of them throws). That warning already has tasks `59ba0f266270` and `e2242800e230`.
  - There was no attempt for the subject, and `seon.task/ready` over a `d/with` of `:running` answered
    `#{"90f6088b30d3"}`.
- **Red re-verified, interpreted.** As probe agent `chain8-probe` in SCI mode, `my.test/check` of the three proofs
  answered 3 tests, 3 pass, 6 fail, 0 error. These are chain 7's five table rows (`"a]a"`, `"(\\"`, `"1]2"`,
  `"(f a]b)"`, `"('"`) and the same property counterexample `"a]a"`.
- **Config.** `seon.config/apply!` set `:seon.config.ai/no-provider false` and `:seon.config.run/max-episode-runs 40`.
  Before the run they were `true` / `100`. `:seon.config.fs/working-root` = this checkout.

## P9: the 3-test proof check, measured once

| | Chain 6 | Chain 7 | **Chain 8** |
|---|---|---|---|
| `my.test/check` of the 3 forgive proofs, interpreted, probe branch | 1.14 s / 205 MB | 3.4 s / 4.19 GB | **1.32 s / 711 MB** (220,571 calls, 1,356 reads) |

- **The 3.4 s / 4.19 GB is gone** on this boot, but allocation is still 3.5× chain 6.
- **The rerun is answered from record.** A second identical check inside `seon.profile/measure` took 16.8 ms and
  16.7 MB, because the result is reused by reach digest. Its top self time is `seon.program/source-names`: 98 calls,
  11 of the 17 ms. So even the reused answer pays one source-name derivation per program function.
- **Cold-run self time.** The cold run's own per-function self time was not captured separately. JVM-wide since boot,
  with other lanes' work included, the leaders are:
  - `seon.test/bounded-result`: 46.6 s self over 7 calls;
  - `seon.schema/call-with-projection-state`: 24.4 s over 28 calls, max 11.1 s;
  - `seon.fn.analyzer/invoke-kondo`: 24.2 s.

  These cannot be attributed to the probe.

## Timeline (UTC)

| At | Event |
|---|---|
| 13:36:39.510 | root writes `:seon.task/execution :running` (the only hand act) |
| 13:36:39.547 | db<->flow writes attempt `a82982ee0f9e`, 37 ms later. `write-attempt!` took 52 ms and `fork-attempt!` 49 ms. The assignment id is the same as chains 6 and 7 (P2) |
| 13:36:40.943 | call 1 (prompt 2,608), 1.43 s after launch |
| :40.9-:56.8 | calls 1-11: sources of `scan`, `step`, the three deftests, `forgiving-again-changes-nothing?` and `unrepair`; the current-state block; a probe of `forgive` on the 7 cases. **Calls 7-9 went to reading the intent in full** (call 9: "The value was truncated by the repl's display"), at last by printing `subs` ranges (P10) |
| :36:57.9 | call 12: an 8.7 KB analysis of `unrepair`'s semantics, prose only. It ends "Let me be systematic — read the whole file first" |
| 13:37:15-:27 | calls 13-19: `step`, `doc :seon.batch/span` and `:seon.batch/repair`, `forgive`; `slurp` of `src/seon/sci/forgive.clj` line ranges |
| :37:36.0 | call 20: "I wasted turns on prose. Let me act" |
| :37:37.4 | call 21: an 11 KB design; ends with `(dir my.program)` |
| :37:58.6 | call 22: "I have `my.program/write-branch!` … define a corrected `scan` in my namespace and write it back". It then reads file lines 100-145 and 144-270. It requires `seon.sci.forgive :as f` from its own namespace `my.attempt-a82982ee0f9e` and **never runs `in-ns`** |
| 13:38:08.7 | call 23: **prose only** ("I need to write the corrected `scan` back into the file. Let me edit the source file directly") → `no-forms` |
| 13:38:11.0 | call 24: **prose only** ("I should just edit the file directly with real forms … via `spit`") |
| 13:38:13.842 | `:stalled` on the branch (`seon.turn/continuing-reply?`: a second formless reply in a row), with 16 of 40 calls unused |
| 13:38:13.899 | problem task `attempt-ended-a82982ee0f9e` (`bfef7cedbd1e`) on main |
| 13:38:13.964 | `:stalled` end copied to main, **122 ms** after the branch end. No `revision-conflict` this time: the reset removed chain 7's problem task (P4 not met) |

Totals:
- 24 calls, all with `finish-reason "stop"`;
- 370,047 prompt / 289,280 cached / 10,295 completion tokens;
- 43 evaluations;
- no recorded error entity names the attempt agent.

Four replies were over the 4,096-byte inline threshold and are stored as blobs (8.7, 4.6, 11.1 and 5.0 KB). None of
them held a `defn`.

**db<->flow passes** (`seon.profile/cells`, JVM-wide since boot):

| Function | Calls | Max | Total |
|---|---|---|---|
| `seon.cluster.agent/plan-facts` | 41 | 48 ms | 311 ms |
| `seon.cluster.agent/lifecycle-facts` | 45 | **399 ms** | 1,457 ms |
| `seon.cluster.agent/next-actions` | 45 | 0 ms | — |
| `seon.cluster.agent/ended-problem!` | 5 | 68 ms | — |

Every pass is under a second. `ended-problem!` shows 4 throws, JVM-wide and not attributed: the only problem write for
this attempt succeeded, and no error was recorded.

## Hand acts

| | Chain 5 | Chain 6 | Chain 7 | **Chain 8 (104e39fce)** |
|---|---|---|---|---|
| Cap | 15 | 15 | 15 | **40** |
| Hand acts before assignment | 1 | 1 | 3 | **1** (`:running`) |
| Hand acts after assignment | 0 | 0 | 0 | **0** |
| Calls / time | 15 / 40 s | 9 | 15 / 128 s | **24 / 93 s** |
| Tokens prompt / cached / completion | 143k / 106k / 2.5k | 89k / 55k / 4.4k | 434k / 352k / 17.5k | **370k / 289k / 10.3k** |
| `defn` of the subject | no | no | yes, 4 of 5 rows | **no, 0 of 5** |
| End | `:capped` | `:stalled` (DSML ×2) | `:capped` | **`:stalled` (prose ×2)** |
| End copied to main | 41 ms | 75 ms | never (P4) | **122 ms** |
| Merge / publication / branch deleted | no / no / no | no / no / no | no / no / no | **no / no / no** |

**The raised cap was not the binding limit.** The stall rule fired with 16 calls left. A merge would have needed at
least two more hand acts:
1. a relaunch of the stalled task (M12);
2. a correction of the stored merge base (P3), or the merge diff would start at chain 7's fork point.

## Frictions

| # | Friction (observed) | Owner | Proposed red proof |
|---|---|---|---|
| P3 (root-caused) | Again the attempt was written with `:seon.task/merge-base` `6acce027-…`, which is chain 7's fork commit and still in the store. Its branch's base ref now answers `6acce267-…`. **The cause:** `seon.cluster.registry/base-commit-id` reads the raw konserve head record under `:base/<branch>`. `datahike.versioning/delete-branch!` removes only the branch's entry from `:branches`; the head record survives until GC, as its docstring says. The reset retired `:base/attempt-a82982ee0f9e`, but its record kept chain 7's commit. `seon.cluster.agent/write-attempt!` (via `seon.task/attempt-tx`) reads the base before `fork-attempt!` replaces it, so the deterministic branch name (P2) reads a deleted branch's leftover base. Every relaunch after a reset or retirement stores a stale merge base | `seon.cluster.registry/base-commit-id` (answers for a branch not in `d/branches`); the order of `seon.cluster.agent/write-attempt!` before `seon.cluster.agent/fork-attempt!` | **New**: after `seon.cluster.registry/retire-branch!` of `B`, `(seon.cluster.registry/base-commit-id db B)` answers nil; and an attempt written after a fresh fork of `B` stores the commit that fork made |
| P2 | Reset does not change identity: the assignment, agent, branch and problem-task ids (`a82982ee0f9e`, `bfef7cedbd1e`) are the same as chains 6 and 7, because `seon.task/next-attempt` derives them from `[handle (inc ended)]` | `seon.task/next-attempt` | M12's proof (chain 7 P2) |
| P10 | The task intent (about 780 characters) was not readable whole in the current-state block. The model spent calls 7-9 getting it: `(:seon.task/intent (seon.context/current-state))`, then `println` of `subs` ranges. AGENTS.md: "Agents' own values are never clipped" and "the right context is the product". Cause not isolated: the current-state render or the value printer's string budget | `seon.context/current-state` (its render), the value printer | **New**: in `seon.context-test`, the current-state block of an assigned task carries the whole `:seon.task/intent` string, with no elision, for an intent of at least 800 characters |
| P11 | The model never redefined `scan` in its namespace. It stayed in `my.attempt-a82982ee0f9e` with `seon.sci.forgive` aliased as `f`, and planned to edit the file with `slurp`/`spit`. Even after it found `my.program/write-branch!`, the plan was "define a corrected `scan` in my namespace and write it back". Chain 7's model ran `(in-ns 'seon.sci.forgive)` and wrote the `defn` there. Nothing in the attempt's context says that the work medium for a `:function` task is `in-ns` to the owner's namespace, then `defn` | `seon.context/current-state` (the work instructions for a function task); the task intent generator | **New**: the current-state block of a `:function` task whose `:seon.task/must-read` names `seon.sci.forgive/scan` offers the operation `(in-ns 'seon.sci.forgive)` as data, beside the proof check |
| P12 | Two formless replies in a row end the attempt (`seon.turn/continuing-reply?`) while 16 of 40 calls remain. The first `no-forms` error did not make the next reply carry a form; in chain 7 it did both times. Call 24's text ("I should just edit the file directly with real forms") shows the model read the error | `seon.turn/continuing-reply?` with the no-forms message | none yet. The behaviour is designed; it needs one owner ruling on whether a stall with budget left should take one more call |
| K4 (again) | The problem task of a `:stalled` attempt with no program change still says "Merge it with (my.root/merge! …)" | `seon.cluster.agent/ended-problem!` | chain 5 K4's proposal: a stalled or capped, unchanged row's problem intent names no `my.root/merge!` |
| P9 | The 3-test check costs 1.32 s and 711 MB cold (3.5× chain 6's allocation). The record-answered rerun spends 11 of 17 ms in `seon.program/source-names` (98 calls) | test check path; `seon.program/source-names` | to the cost queue with chain 7's probe form |
| P13 | `seon.cluster.agent/lifecycle-facts` peaked at 399 ms in one pass (45 calls, 1.46 s total, JVM-wide) | `seon.cluster.agent/lifecycle-facts` | none: under a second; watch it in the sentinel |

**P4 not met** (the reset removed the earlier problem task). **No DSML replies**, so the aec253eb6 message was again not
exercised.

## What the next run changes

- The cap was not what stopped this run, and chain 7's 4-of-5 state was gone after the reset. The next lever is the
  context:
  - P11: say where to `defn`;
  - P10: show the whole intent.

  With those, the model would not need to rediscover the medium. A run of the same subject right after both fixes
  would test that.
- P3 has to be fixed before any merge of a relaunched or post-reset attempt. Otherwise the merge diff takes main's
  changes since the stale base as the agent's.

## Residue on default (disposable)

- **Subject task.** `90f6088b30d3` is set back to `:held` (cleanup, not counted). Its attempt `a82982ee0f9e` ended
  `:stalled` on main and on the branch.
- **Branches.** `:attempt-a82982ee0f9e` (end commit `6acce2c5-…`, no program change) and `:base/attempt-a82982ee0f9e`
  are kept.
- **Problem task.** `bfef7cedbd1e` reads "ended stalled".
- **Config.** Restored to `no-provider true` and `max-episode-runs 100`, verified with `seon.config/effective`.
- **Probe branch.** `:agent-1bb8b76b7ac0` (chain8-probe) is retiring (`:my.agent/retiring? true`).
- Nothing was published, and Seon made no git commit.
