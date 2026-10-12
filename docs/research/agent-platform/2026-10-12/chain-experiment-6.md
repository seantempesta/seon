---
title: "Chain experiment 6: a forgive fix stalls after 9 calls on two DSML replies; nothing reaches the merge pass"
type: research
status: complete (lane chain6, 2026-10-12); no merge, no publication; the subject task is left :held on default
created: 2026-10-12
---

# Chain experiment 6

**Result: not merged.** One single-Implement step ran on default (archive **a221ad569**, DeepSeek flash) on
`seon.sci.forgive/forgiving-forgiven-text-changes-nothing`. The attempt ended `:stalled` after 9 model calls in 39 s
(13:03:47 to 13:04:26 UTC). It used 89.5k prompt tokens (55.2k cached, 62 %) and 4.4k completion tokens. It never
wrote a `defn`. Its last two replies were DeepSeek's native tool-call markup (`<｜｜DSML｜｜ invoke …>`) and held no
Clojure form. `seon.sci.forgive` drops that markup at read, so both replies had no forms. `seon.turn/continuing-reply?`
gives one more call after a formless reply and none after a second, so the attempt stalled. **Hand acts after
assignment: 0** (chain 5: 0, chain 4: 17). db<->flow copied the end to main 75 ms after the branch end and wrote root's
problem task. Then it stopped, because `:stalled` is terminal. The M8a path (`:merge` → `:publish` → `:delete-branch`) was
**never reached**. This run neither proves nor refutes it. **No git commit by Seon**: `publish!` never ran.

Authorities: AGENTS.md "The goal"; [chain experiment 5](chain-experiment-5.md) (method); [chain experiment
4](chain-experiment-4.md); [M8a notes](m8a-notes.md).

## Runtime check (before running)

Default ran archive a221ad569. HEAD was 5618fddf6. Four commits between them touch `src`:
- **07404a3c0** (`seon.cluster.agent`): only the `:cancel` → `:close` path. `lifecycle-facts` derives `:cancelled` only
  for a cancelled task's attempt that holds no end.
- **73174f499** (`seon.db`): a refusal for a store that lacks the write-time-limit dial.
- **83e9c5df7** (`seon.test.runner`): working roots for fixture clusters.
- **5618fddf6**: task EDN only.

None of them changes `:merge`, `:publish`, `:delete-branch`, `seon.merge/merge!` or `seon.merge/publish!`, or what
`next-actions` derives for a `:done` end. So I judged that no restart was needed and ran the experiment.

## Subject selection

| Candidate | Check | Verdict |
|---|---|---|
| **`seon.sci.forgive/forgiving-forgiven-text-changes-nothing`** → `seon.sci.forgive-test/a-stray-closer-keeps-tokens-apart`, `…/forgiving-forgiven-text-changes-nothing`, `…/the-repairs-account-for-every-change` | `my.test/check` on agent `chain6-probe`, SCI: **red, failing, not erroring**. 3 tests, 3 pass, 6 fail, 0 error, 1.14 s, 205 MB. The table test fails on 5 rows (`"a]a"` → `"aa"`, `"('"` and `"(\\"` spans), and the property fails at `"a]a"` | **picked**. A pure ordinary function (`:seon.fn/host-bound? false` for `scan` and `forgive`). The intent names `forgive`, `scan`, `step` and `segments` and states what must become true. The table test pins exact outputs, so there is no contract question. File `src/seon/sci/forgive.clj` clean and unclaimed |
| `seon.bootstrap/help-teaches-in-ns-first-and-no-probing` | the help lines already hold all three asserted facts | rejected (already fixed) |
| `seon.competency/slug-removes-edge-hyphens` | — | rejected: `comp.text` has no file in the checkout, so there is nothing to publish |
| `seon.problems/problems-prose-carries-no-date-or-file-line` | — | rejected: it also asks for a contract design (chain 5's K2 kind) |
| `seon.error/a-guessed-schema-key-…` | — | rejected: it touches the error path every reply uses, and the fix is larger |

The intent was **not refined** (0 hand acts). **This subject was not new.** Chain 3 ran the same task through five
Implement attempts, and the idempotence property stayed red at the end. I found that only after the run. The table rows
are exact, but the fix is real work in a 185-line imperative `scan`. This choice should have been weighed against that
history.

## Setup and launch (as chain 5)

- Config: `seon.config/apply!` with `:seon.config.ai/no-provider false` and `:seon.config.run/max-episode-runs 15`. The
  model is `deepseek-flash`. `:seon.config.fs/working-root` was `/Users/sean/src/seon`, so a publish would have committed
  into this checkout. The config was restored to `true`/`100` and verified with `seon.config/effective`.
- Before launch: `seon.cluster.agent/plan-facts` over `d/with` of the launch datom answered `:startable
  #{"90f6088b30d3"}`.
- Launch at 13:03:45.030: one root transaction, `[:db/add [:seon.task/id "90f6088b30d3"] :seon.task/execution
  :running]`. db<->flow assigned it by itself: assignment `a82982ee0f9e`, agent `attempt/a82982ee0f9e`, branch
  `:attempt-a82982ee0f9e`, merge base `6accdab1-92c4-…`.

## Timeline (UTC)

| Call | At | Prompt / cached / completion | What it did |
|---|---|---|---|
| 1 | 13:03:47 | 2,323 / 128 / 115 | `(ns seon.sci.forgive (:require [my.program :refer [source callers]]))` (see N3), then the source of `scan`, `step` and `forgive` |
| 2-3 | :49-:51 | 6.2k / 7.2k prompt | read `segments` and the two proof deftests, then probed `forgive` on the failing rows |
| 4-7 | :53-04:16 | 7.5k-12.8k prompt | a correct diagnosis, stated in prose: a dropped closer must leave a space, and a dangling `'`/`\` must stay outside the inserted closer. It read `markup-end` and `token-end` |
| 8 | 04:21 | 16,485 / 10,624 / 186 | a "Fix A" plan in prose, then DSML `invoke my.program/source` markup and **no form** → no-forms error |
| 9 | 04:24 | 17,059 / 14,336 / 150 | "My error — I sent prose without a form." It then sent DSML markup again and **no form** |
| — | 04:26.796 | | `:stalled` on the branch |
| — | 04:26.841 / .871 | | db<->flow `:close`: problem task `attempt-ended-a82982ee0f9e`, then the end on main |

Totals: 9 calls, all `finish-reason "stop"`; 89,483 prompt / 55,168 cached / 4,446 completion tokens. The code is
**none**: `scan` on the branch is byte-identical to main.

## Where it stalled, by derived action

- `:merge`, `:publish` and `:delete-branch` all need `merge?`, which requires the end in `merged-ends` (`#{:done
  :sent-back}`). `:stalled` is not in that set.
- `:close` fired as designed.
- No relaunch: `seon.task/ready` excludes a task whose attempt ended `:stalled`. Same as K3 in chain 5 (M12).

## Hand acts

**After assignment: 0.** Cleanup is not counted (as in chains 4 and 5): task → `:held`, config restored, probe branch
`:agent-fe77a3af25a1` retired (`:my.agent/retiring? true`).

A merge would have needed **at least 1** more: a relaunch of the stalled attempt. It might have needed more: chain 3
needed five Implement attempts on this subject and still did not turn the property green.

## Frictions

| # | Friction (observed) | Owner | Proposed red proof |
|---|---|---|---|
| N1 | The two DSML replies read as plain no-forms. `seon.turn/planned-sources` of call 9's text answers only "Your reply had no form; only comments/prose. Send a form." (verified in JVM mode). The markup that `seon.sci.forgive` dropped is not named, so the model's correction ("I sent prose") repeated the same markup. Met in chain 3 (F7) and in fan-outs 5 and 6. | `seon.turn/segmented-sources` (the no-forms error it builds); existing task `seon.ai/a-dsml-tool-call-reply-reads-as-prose` | That task's proof, as a `seon.turn-test` row: `(seon.turn/planned-sources dsml-text 'user 1048576)` for call 9's text answers a `:seon.cluster.reply/no-forms-error` whose data names the dropped tool markup (for example `:seon.cluster.reply/dropped-markup`). Red today: the error has only `:seon.error/type` and `:seon.error/message` |
| N2 | Two formless replies in a row are terminal (`:stalled`, no relaunch). A model with a correct diagnosis and 6 calls left lost the attempt to a reply-format slip. | `seon.turn/continuing-reply?`, `seon.task/ready` | existing `seon.cluster.agent/a-capped-attempt-has-no-relaunch` (M12): the same class for `:stalled` |
| N3 | Call 1 evaluated `(ns seon.sci.forgive (:require [my.program :refer [source callers]]))` on its branch (value nil). The branch's namespace entity now has a **different `:seon.program/definition-digest`** (`ad0b4bfa…` against main's `ffb3fe99…`). Its `:seon.ns/source`, `:seon.ns/requires` and alias values are byte-identical to main's; only the alias entities were re-created. So the digest no longer matches the stored source, and the `my.program` require it reflects is recorded nowhere. If the attempt had gone on to `:done`, this namespace "change" would have been part of the merge diff. | the namespace recording of an evaluated `ns` form (the writer of `:seon.ns/*` and the namespace's definition digest) | **New**: on an agent branch, evaluating an `ns` form for an existing file namespace that adds a `:refer` require either records that require in `:seon.ns/requires`/`:seon.ns/source` or leaves `:seon.program/definition-digest` equal to main's. Red today (digests measured above) |
| N4 | The subject repeated chain 3's. Neither the chain queue nor the task records that five attempts failed on it. | [chain queue](chain-queue.md) | none (process): the queue should carry each subject's attempt history |
| N5 | The problem task for a stalled attempt that changed nothing still says "Merge it with (my.root/merge! …)". | `seon.cluster.agent/ended-problem!` | chain 5's K4 proof, met again |

## Against chains 4 and 5

| | Chain 4 (46a1c4f06) | Chain 5 (a221ad569) | Chain 6 (a221ad569) |
|---|---|---|---|
| Subject | web `start!`/`stop!` + schema | `runtime-duration` contract | `forgive`/`scan` idempotence |
| Attempts / calls | 5 / 69 | 1 / 15 | 1 / 9 |
| Tokens prompt / cached / completion | 884k / 661k / 18.0k | 143k / 106k / 2.5k | 89k / 55k / 4.4k |
| End | none `:done` | `:capped` | `:stalled` (two DSML replies) |
| Hand acts after launch | 17 | 0 | **0** |
| End copied to main by db<->flow | 0 of 4 | 1 of 1 (41 ms) | 1 of 1 (75 ms) |
| Merge / publication / branch deleted | no / no / no | no / no / no | no / no / no |

## What the next run changes

- Clear N1 (`seon.ai/a-dsml-tool-call-reply-reads-as-prose`) or M12 relaunch first. The last three chains all ended on
  a terminal end with no relaunch (capped, stalled, stalled), so M8a's first live run is waiting on one of these.
- Pick a subject whose fix is a few lines and that has no earlier failed attempts. `scan` is neither.

## Residue on default (disposable)

- Task `90f6088b30d3` `:held`, with one `:stalled` attempt.
- Problem task `attempt-ended-a82982ee0f9e` (`bfef7cedbd1e`).
- Branches `:attempt-a82982ee0f9e` and `:base/attempt-a82982ee0f9e`, holding only the re-recorded `seon.sci.forgive`
  namespace (N3).
- Probe branch retiring. Nothing was published; no Seon git commit.
