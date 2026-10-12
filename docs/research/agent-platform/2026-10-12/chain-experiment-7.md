---
title: "Chain experiment 7: the forgive rerun fixes 4 of 5 rows and caps at 15 calls; a hand relaunch re-mints chain 6's assignment and its end never reaches main"
type: research
status: complete (lane chain7, 2026-10-12); no merge, no publication; the subject task is left :held on default
created: 2026-10-12
---

# Chain experiment 7

**Result: not merged.** This was a controlled rerun of chain 6's subject on default (archive **aec253eb6**, DeepSeek
flash), the same task `seon.sci.forgive/forgiving-forgiven-text-changes-nothing`. The attempt ended `:capped` after
15 model calls in 128 s (13:27:05 to 13:29:13 UTC). It used 433.7k prompt tokens (352.4k cached, 81 %) and 17.5k
completion tokens. **Unlike chain 6, it wrote a `defn`.** Call 11 redefined `seon.sci.forgive/scan` on the branch, and
the redefinition fixes 4 of the 5 failing table rows (`"a]a"`, `"1]2"`, `"(f a]b)"`, `"('"`). The model was still
tracing the fifth (`"(\\"`) when the 15-call cap fired.

- **No DSML replies this run**, so the aec253eb6 message was never shown (see "The DSML message" below).
- **Hand acts after assignment: 0.** Before launch, 2 hand acts were needed to relaunch the task at all, because nothing
  relaunches a `:stalled` attempt (M12).
- That hand relaunch reused chain 6's assignment id. db<->flow's `:close` then failed with a recorded `revision-conflict`
  error, and the branch's `:capped` end **never reached main**.
- `:merge`, `:publish` and `:delete-branch` were **never reached** (the fourth chain in a row).
- **No git commit by Seon.**
- **N3 did not show up**: the namespace digest on the branch equals main's.

Authorities: AGENTS.md "The goal"; [chain experiment 6](chain-experiment-6.md) (method, subject); [chain experiment
5](chain-experiment-5.md) (setup); [M8a notes](m8a-notes.md).

## Runtime and subject check (before running)

- Default: pid 18525, booted 13:20:13 UTC on aec253eb6 and **not reset** (chain 6's residue was present). Health was
  `:degraded`: the `seon.sci.eval/program-context` cache was at 33 % hits, and chain 6's two agent-error signatures were
  still open.
- **Red re-verified, interpreted.** I ran `my.test/check` on the three proofs (`…/a-stray-closer-keeps-tokens-apart`,
  `…/forgiving-forgiven-text-changes-nothing`, `…/the-repairs-account-for-every-change`) as probe agent `chain7-probe`
  in SCI mode. It answered: 3 tests, 3 pass, 6 fail, 0 error. These are the same five table rows and the same property
  counterexample `"a]a"` as chain 6.
- **The intent is still accurate.** aec253eb6 changed `scan` only in `prose!`: a prose line that is tool markup is now
  recorded as a `:seon.batch/dropped` repair. It left the stray-closer, quote-prefix and backslash paths alone, so the
  subject was kept.
- Cost finding: that 3-test check took **3.4 s and 4.19 GB allocated**. Chain 6 measured the same check at 1.14 s and
  205 MB, so allocation is about 20× higher (see P9).
- `src/seon/sci/forgive.clj` was clean and unclaimed.

## Relaunch (pre-assignment hand acts)

`seon.task/ready` excludes a task whose attempt ended `:stalled`. With `:running` written on its own, the task stays
unready: `(seon.task/ready (:db-after (d/with db [[:db/add … :running]])))` answered `#{}`.

1. **13:23:36.9.** One transaction: `[:db/retractEntity <chain 6's attempt>]` and `:seon.task/execution :running`.
   - 366 ms later db<->flow wrote a new attempt. It had the **same assignment `a82982ee0f9e`**, because
     `seon.task/next-attempt` derives the key as `(seon.id/id [handle (inc ended)])` and nothing had ended.
   - That attempt reused chain 6's agent and branch, and the branch head still held chain 6's `:stalled` end.
   - At 13:24:09 db<->flow copied `:stalled` back to main (P2).
2. **13:26:32.4.** I retired branches `:attempt-a82982ee0f9e` and `:base/attempt-a82982ee0f9e` with
   `seon.cluster.registry/retire-branch!` (no live context held them). Then I retracted the re-minted attempt.
   - db<->flow wrote the attempt again at 13:27:03.2, 30.8 s later (P5), as `a82982ee0f9e` once more.
   - This time it **forked a fresh branch from main** (base commit `6acce027-…`).
   - But the stored `:seon.task/merge-base` is chain 6's `6accdab1-…` (P3).

Config was applied as in chains 5 and 6 (`seon.config/apply!`: `:seon.config.ai/no-provider false`,
`:seon.config.run/max-episode-runs 15`). `:seon.config.fs/working-root` was this checkout.

## Timeline (UTC)

| Call | At | Prompt / cached / completion | What it did |
|---|---|---|---|
| 1 | 13:27:05 | 2,608 / 128 / 125 | `doc` of `scan`, then the source of `scan`, `step`, `forgive` and `segments` |
| 2 | :09 | 7,134 / 384 / 81 | the source of the three proof deftests |
| 3 | :12 | 7,871 / 4,992 / 362 | test helpers `unrepair` and `forgiving-again-changes-nothing?`; two `doc`s of keywords; 1 evaluated prose fragment (`(do (.append out …))` → "Unable to resolve symbol: out") |
| 4 | :16 | 9,264 / 5,632 / 256 | `(seon.test/run 'sym)`, refused (a symbol where `:seon.test/request` goes) |
| 5 | :20 | 10,476 / 7,040 / 79 | probed `forgive` on four failing rows |
| 6 | :23 | 10,905 / 8,320 / 4,297 | **prose only** → `no-forms` error. A correct diagnosis: a dropped closer must leave a space, recorded as `:dropped` and then `:inserted " "` so `unrepair` still restores the text, and a dangling `'` or `\` must become its own span |
| 7 | :48 | 17,417 / 8,704 / 4,551 | 32 forms: probes of `forgive` and `scan` on edge rows, `(in-ns 'seon.sci.forgive)`, and 7 evaluated prose fragments (`:else`, `closers-in-place!`, `(fn [] … marks …)` → unresolved locals) |
| 8 | 13:28:15 | 40,890 / 15,232 / 2,328 | a sketch `defn- scan` with `...` elisions → "let requires an even number of forms" (`...` is dropped as markup), plus 4 fragment errors |
| 9 | :29 | 43,539 / 38,656 / 115 | `ns-aliases`, `in-ns`, `refer-clojure` |
| 10 | :33 | 43,817 / 41,344 / 24 | **prose only** ("I'll write the corrected redefinition now…") → `no-forms` |
| 11 | :35 | 43,974 / 41,600 / 3,165 | "I need to send an actual form": **`defn- scan`, admitted** (44 ms). Its reply named pending test `seon.sci.forgive-test/valid-clojure-is-unchanged` |
| 12 | :50 | 47,293 / 41,728 / 127 | checked 12 rows: `"a]a"` → `"a a"` `[[0 1 1 1] [2 3 1 3]]`, `"1]2"`, `"(f a]b)"` → `"(f a b)"` and `"('"` → two spans all match; `"(\\"` is still `"(\\)"` `[[0 3 1 1]]` |
| 13-15 | :53-29:10 | 48k-51k | traced `"(\\"` through `token-end`; 1 unresolved local `t` |
| — | 29:13.968 | | `:capped` on the branch |
| — | 29:14.2 / .4 | | db<->flow `:close`: `seon.cluster.agent/ended-problem!` refused **twice**, `revision-conflict` (P4); recorded error `c560dcaa…`. **The end is not copied to main** (none at 13:31:20, 2 min later) |

Totals: 15 calls (all `finish-reason "stop"`), 433,676 prompt / 352,384 cached / 17,523 completion tokens, and 73
evaluations, of which 15 errored:
- 12 were prose fragments or unresolved locals;
- 2 were `no-forms`;
- 1 was the `seon.test/run` refusal.

## The DSML message (aec253eb6)

**Not exercised.** DeepSeek sent no tool-call markup in any of the 15 replies. Chain 6 had 2 of 9 replies in DSML and
stalled on them. The two formless replies here (calls 6 and 10) were plain prose and got the ordinary "Your reply had no
form" error. **Both times the next call sent forms**: call 7 sent probes, and call 11 opened with "I need to send an
actual form" and sent the defn. So this run cannot say whether the DSML wording changes behaviour. One more difference
from chain 6: a formless reply here was not followed by a second formless one, so `seon.turn/continuing-reply?` never
stalled the attempt.

## Hand acts

| | Chain 4 (46a1c4f06) | Chain 5 (a221ad569) | Chain 6 (a221ad569) | Chain 7 (aec253eb6) |
|---|---|---|---|---|
| Subject | web `start!`/`stop!` + schema | `runtime-duration` contract | `forgive`/`scan` | `forgive`/`scan` (same) |
| Hand acts before assignment | — | 1 (`:running`) | 1 (`:running`) | **3**: launch tx; retire 2 branches; retract re-minted attempt |
| Hand acts after assignment | 17 | 0 | 0 | **0** |
| Attempts / calls | 5 / 69 | 1 / 15 | 1 / 9 | 1 / 15 |
| Tokens prompt / cached / completion | 884k / 661k / 18.0k | 143k / 106k / 2.5k | 89k / 55k / 4.4k | 434k / 352k / 17.5k |
| `defn` of the subject | yes | no | no | **yes, 4 of 5 rows** |
| End | none `:done` | `:capped` | `:stalled` (DSML ×2) | `:capped` |
| End copied to main by db<->flow | 0 of 4 | 1 of 1 (41 ms) | 1 of 1 (75 ms) | **0 of 1** (P4) |
| Merge / publication / branch deleted | no / no / no | no / no / no | no / no / no | no / no / no |

A merge would have needed at least 1 more act: a relaunch from the capped branch. The model's state (4 of 5 rows) would
carry over only if the relaunch forks that branch's commit, which is M12's `:seon.task.attempt/relaunch-fork`.

## Frictions

| # | Friction (observed) | Owner | Proposed red proof |
|---|---|---|---|
| P1 | A `:stalled` (and `:capped`) task cannot be relaunched without editing facts by hand. Met for the fourth time in a row | `seon.task/ready`, `seon.cluster.agent/plan` | existing `seon.cluster.db-flow-test/a-capped-attempt-relaunches-only-by-roots-fact` (task `seon.cluster.agent/a-capped-attempt-has-no-relaunch`, M12) |
| P2 | An assignment's identity is `(seon.id/id [handle (inc ended)])`. Removing an ended attempt therefore re-mints the **same** assignment, agent and branch name. A surviving branch carries its old end back to main in 33 s. A launch is not a new identity | `seon.task/next-attempt`, `seon.cluster.agent/plan` (the `launches` key) | in M12's proof: a relaunch's assignment differs from every earlier assignment of the task, and its branch is not an earlier attempt's branch |
| P3 | The re-minted attempt was written with `:seon.task/merge-base` `6accdab1-…` (chain 6's fork point). Its branch actually forked at `6acce027-…`, which `seon.cluster.registry/base-commit-id` answers now. `seon.cluster.agent/write-attempt!` (via `seon.task/attempt-tx`) runs before `fork-attempt!`, so `base-commit-id` answered for a branch name whose branch had been retired. A stale base would make a merge diff take every main change since 13:03 as the agent's. Cause not isolated: it could be a cached base keyed by branch name, or a retained base ref | `seon.task/attempt-tx`, `seon.cluster.registry/base-commit-id` | **New**: after `retire-branch!` of `B`, `(registry/base-commit-id db B)` answers nil, and after a fresh fork of `B` it answers the new fork commit |
| P4 | `:close` for the re-minted assignment: `seon.cluster.agent/ended-problem!` → `seon.task/tasks-tx` refused `revision-conflict`, because problem task `attempt-ended-a82982ee0f9e` (`bfef7cedbd1e`) already says "ended stalled". It refused twice, recorded error signature `c560dcaa…`. The `:capped` end was then **never copied to main**: a failed problem write blocks the end copy, so the failure is not local. Root cause is P2. The end copy waiting on the problem write is the second defect | `seon.cluster.agent/ended-problem!`; the `:close` action in `seon.cluster.agent/next-actions` | **New**, in `seon.cluster.db-flow-test`'s lifecycle table: a branch end whose problem task already exists with another intent is copied to main |
| P5 | Relaunch latency. The first launch (retract + `:running`) was assigned in 366 ms; the retraction-only transaction was assigned 30.8 s later. During this window the JVM allocated about 3.7 GB/s (other lanes' tests and kondo: `seon.schema/call-with-projection-state` 108 s self over 30 calls, max 48 s; `seon.test/bounded-result` 102 s). Not isolated: a retraction may not match db<->flow's listen route, or the pass was queued | `seon.cluster.agent/db<->flow` (its wake) | none yet: measure the wake with an idle JVM first |
| P6 | Code the model quoted inside its prose, at column zero (`:else`, `(do (.append out …))`, `close-all!`, `(fn [] … marks …)`), was evaluated as forms: 12 errors in 73 evaluations, all "Unable to resolve symbol" on `scan`'s locals. A `defn` sketch with `...` lost its elisions as markup and failed as "let requires an even number of forms" | model reply shape; `seon.sci.forgive/scan` (`line!`, `code-line!`) | none. This follows the "forms at column zero" rule; it is noise, not a lost call |
| P7 | The cap fired with the fix 80 % done. The last row (`"(\\"`: a lone backslash read as a token, so the inserted `)` lands after it) needed a few more calls | `:seon.config.run/max-episode-runs` 15; M12 | M12's proof (relaunch forking the capped commit) |
| P8 | Root-side default filling. `(seon.config/effective db)` (1 of 2 arguments) prefixed a bound value and shifted the passed database to argument 1, refused as `cluster-name`. `(seon.cluster.agent/handle-store {…})` with a non-context map was filled with an "unavailable" error map, which then flowed into `seon.cluster.registry/branches` as the store | the instrumented wrapper's default filling (`seon.call-preparation`) | K7's class: a call missing a non-binding argument refuses by name. Partly addressed by 37d651f08 (landed during this run) |
| P9 | `my.test/check` of the 3 forgive proofs: 3.4 s and 4.19 GB allocated (chain 6: 1.14 s, 205 MB) | test check path; top self time `seon.schema/call-with-projection-state` | to the cost queue with the probe form `(my.test/check {:seon.test/identities #{'seon.sci.forgive-test/a-stray-closer-keeps-tokens-apart 'seon.sci.forgive-test/forgiving-forgiven-text-changes-nothing 'seon.sci.forgive-test/the-repairs-account-for-every-change}})` |

**N3 (the `ns` digest)** did not show up. Call 7 evaluated `(ns seon.sci.forgive)`, which had no `:require`, unlike
chain 6's `(ns … (:require [my.program …]))`. The branch's `:seon.program/definition-digest` for `seon.sci.forgive`
equals main's (`ffb3fe99…`). The only program change on the branch is `scan` (`56e7b4cb…` against main's `c54e2f6c…`).
Lane ns-digest's fix (8cc01ec66) landed during the run; default does not run it yet.

## What the next run changes

- M12 relaunch is now the only thing between this subject and its first `:done`. A relaunch that forks the capped
  commit would start from 4 of 5 rows green. Until M12 merges, a hand relaunch on a store that still holds the earlier
  attempt goes wrong (P2, P3, P4). A restart and reset before the run avoids that.
- Chain 7 never triggered the DSML message, so the comparison with chain 6 is still open.

## Residue on default (disposable)

- Task `90f6088b30d3` `:held`. Its attempt `a82982ee0f9e` holds no end on main; the `:capped` end is only on the branch.
- Branches `:attempt-a82982ee0f9e` (the partial `scan` fix, end commit `6acce0a9-…`) and `:base/attempt-a82982ee0f9e`.
- Problem task `bfef7cedbd1e` still reads "ended stalled". Recorded error `c560dcaa…` (`ended-problem!`,
  `revision-conflict`, 2 occurrences).
- Config restored to `no-provider true` / `max-episode-runs 100`, verified with `seon.config/effective`. Probe branch
  `:agent-a8e6b3f49fec` retiring (`:my.agent/retiring? true`).
- Nothing was published and Seon made no git commit.
