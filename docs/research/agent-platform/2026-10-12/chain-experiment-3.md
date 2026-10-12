---
title: "Chain experiment 3: Design+Prove → Implement on 98720b4eb stops short of a merge request"
type: research
status: complete (lane chain-3, 2026-10-12); no merge request; both steps left :held on default
created: 2026-10-12
---

# Chain experiment 3

**Result.** The third Design+Prove → Implement chain ran on default (archive **98720b4eb**, DeepSeek flash, the shipped
`:seon.config.ai/model`) on the subject `seon.sci.forgive/forgiving-forgiven-text-changes-nothing` and **did not reach a
merge request**. Design+Prove produced the right product on its third attempt (the three subject deftests tagged with the
Implement step, bodies unchanged), but it could never end by itself: its step proof's red result was reused at an
unchanged key after the tags it reads were written (H2, new). Five Implement attempts followed; the last three each ended
with two of the three proofs green and `seon.sci.forgive-test/forgiving-forgiven-text-changes-nothing` (the seeded
idempotence property) red, each finding a deeper part of the cause. The run stopped after 8 attempts and 105 model calls
(1.31M prompt tokens, 951k cached, 65k completion), about 3× experiment 2's tokens, with **23 hand acts after launch**
(experiment 2: 8). Today's fixes held where they were reached: step instructions reached the agent (G5, but cut, H1), a
send-back reason reached the next attempt and changed its work (G4, twice), a function's callers' tests were found (G9).
Still open and met again: G6 (six relaunches by end rewrite), G7 (four Implement attempts could not fork; each needed a fork
rewrite and a hand db<->flow pass), G8 (every hand-started attempt's end stayed on its branch until a hand pass).

Authorities: AGENTS.md "The goal", [TASK-CHAIN](../../../seon/schedule/cards/TASK-CHAIN.md) §2-§3b,
[chain experiment 2](chain-experiment-2.md) (G1-G12), [chain experiment 1](chain-experiment-1.md) (F1-F11),
[chain-3 candidates](chain-3-candidates.md) (rank 2), [hybrid-test-results](hybrid-test-results.md) §2 (what a result key
holds).

## For root: no merge request

| | |
|---|---|
| Merge request | **none**: no Implement end ran green through `seon.turn/end-task!`; `my.root/merge!` was not called |
| Furthest branch | `:attempt-64abda4eb8aa` (Implement #5, end `:capped`, forked from Implement #4's end `6acc9a0a-9457-5c30-8544-676f65ba02e7`). It redefines `seon.sci.forgive/scan` (a stray closer is replaced by a space, recorded as `:seon.batch/dropped` then `:seon.batch/inserted`; an inserted closer goes before a dangling quote or backslash) and `seon.sci.forgive/forgive`, and carries the three retagged deftests. Proofs there: `a-stray-closer-keeps-tokens-apart` green, `the-repairs-account-for-every-change` green, `forgiving-forgiven-text-changes-nothing` red. It also holds agent probes (`seon.sci.forgive/marks-probe`, `probe`, `probe2`-`probe4`, `fix-probe` in `seon.sci.forgive-test`). **Not for merging.** |
| The open counterexample | Implement #5's last finding: `"(\\\n("` forgives to `"(\\)\n()"`, and forgiving that again reads `\)` as a character literal, so the spans change. Implement #4 had found the shape of the fix (the returned spans must equal `forgive` of the returned text, and the repairs must be exactly what turns the agent's text into it). |
| A design gap the Implement step found | Replacing a stray closer with a space shifts every later repair offset: `seon.sci.forgive-test/an-unbalanced-text-names-each-repair` expects the inserted `")"` of `"(a ]\n  b\n(c)"` at 7, which becomes 8. The subject's intent requires the change, so that existing test asserts the old behavior; the Prove step should have updated it (experiment 1's lesson, met again). |
| Reproduction on main (JVM mode, 1 ms) | `(seon.sci.forgive/forgive "a]a" :code)` → text `"aa"`, spans `[[0 1 1 1] [1 2 1 2]]`; forgiving `"aa"` → spans `[[0 2 1 1]]`. `"('"` → `"()'"` spans `[[0 2 1 1]]`; `"(\\"` → `"(\\)"`. |
| Proofs on HEAD | verified before launch on a probe branch: `a-stray-closer-keeps-tokens-apart` red (5 of 7 rows), `forgiving-forgiven-text-changes-nothing` red (shrunk to `"a]a"`), **`the-repairs-account-for-every-change` green** (a companion law, not red as the brief said) |

## Setup

- **Runtime.** Default PID 53385 runs archive 98720b4eb (restarted 08:00:48). Health `:faulted` before the run (4 open
  faults, 53 errored receipts), cache 335/512 MB, heap 1.6/4 GB. Never stopped or reset.
- **Tree** (root, JVM mode): `seon.task/create!` of `seon.sci.forgive/idempotence-design-prove` (`:step :prove`) and
  `seon.sci.forgive/idempotence-implement` (`:needs` the first), one transaction adding both as the subject's
  `:seon.task/children` and their `:seon.task/instructions` (instructions only; G5 is merged, so nothing was copied into
  the intent).
- **Step proof from the start** (G1 avoided as experiment 2 learned): `chain3.proofs/design-prove-tagged-the-implement-proofs`,
  defined by root's SCI on main, the same body as `chain2.proofs/design-prove-wrote-the-implement-proof`:
  `(is (seq (:seon.task/proofs (seon.task/task-edges (seon.db/db) 'seon.sci.forgive/idempotence-implement))))`. Dry run
  first on a probe branch: retagging one deftest made `task-edges` answer it in the next eval request.
- **Config** `seon.config/apply!`: `:seon.config.ai/no-provider false`, `:seon.config.run/max-episode-runs 15` (the request
  needs `:seon.boot/cluster-name`; the first call refused without it). Restored to `true` and `100` after the run
  (verified with `seon.config/effective`).

## Timeline (main transaction instants, UTC)

| Time | Event | Measured |
|---|---|---|
| 08:05:32.147 | attempt `a373f0b297d8` of design-prove; first form 08:05:32.875 | attempt → first form 0.73 s |
| 08:06:02.278 | `:capped`: calls 10-15 spent recovering its own instructions, cut at 1,024 characters (H1) | 15 calls, 30 s |
| 08:07:29.152 | **hand acts 2-3**: instructions rewritten to 878 characters; end `:capped` → `:sent-back` (G6) | next attempt +39 ms |
| 08:07:43-48 | attempt `285fdccc2fa6` retags the three deftests, but first deletes four rows of `a-stray-closer-keeps-tokens-apart` and changes `"('"`'s expected spans to today's output | |
| 08:08:09.484 | `:capped`: its step proof stayed red (`(not (seq #{}))`) though `task-edges` answered the three proofs (H2) | 15 calls, 40 s |
| 08:09:49.774 | **hand act 4**: end → `:sent-back` with target and a reason (keep every row; stop once tagged) | +47 ms |
| 08:10:46.132 | attempt `02110a533e26` `:capped`: the right product (three deftests tagged, bodies byte-identical to the files), the step proof still red at its reused key | 15 calls, 56 s |
| 08:11:10.893 | **hand act 5**: end `:capped` → `:done` (experiment 1's nudge 3 precedent) | |
| 08:11:10.964 | Implement attempt `4dafd020b92c`, forked from `02110a533e26`'s end `6acc9606…` with its proofs there, started by db<->flow itself | end → successor 71 ms; first form +1.9 s |
| 08:11:18.224 | `:stalled`: two DeepSeek DSML tool-call replies (F7) | 3 calls, 7 s |
| 08:11:47.172 | **hand act 6**: end → `:sent-back` (G6); attempt `ba9e1ea7d55b` written; `fork-attempt!` throws "The attempt's predecessor branch is gone": `:attempt-02110a533e26` was deleted after the first successor started (G7) | |
| 08:13:23-58 | **hand acts 7-8**: fork rewritten to `4dafd020b92c`'s end; a hand db<->flow pass starts it | pass 367 ms |
| 08:15:27 | `ba9e1ea7d55b` `:stalled` on its branch (two prose replies) after finding the offset conflict with `an-unbalanced-text-names-each-repair`; evaluated a fragment of `scan`'s body twice (`Unable to resolve symbol: out`, H3) | 12 calls, 88 s |
| 08:21:51 | **hand act 9**: a hand pass copies that end to main, 6 min 24 s later (G8) | 86 ms |
| 08:22:09 | **hand act 10**: end → `:sent-back`, reason: its own finding, and evaluate one whole `defn-` | |
| 08:22:30-49 | **hand acts 11-13**: a pass throws (G7, attempt `22cb267a82b7` written with the gone fork), fork rewritten, a pass starts it | |
| 08:25:33 | `22cb267a82b7` `:capped` on its branch: `scan` redefined whole, two proofs green; 11 errors from body fragments and the prose labels `(A)`-`(E)` read as forms | 15 calls, 163 s |
| 08:26:33-42 | **hand acts 14-18**: land pass (G8), `:sent-back` with reason, pass throws (G7), fork rewritten to `22cb`'s end, pass starts `0ba034cb32ea` | |
| 08:28:17 | `0ba034cb32ea` `:capped`: `forgive` re-scan tried; property green, then `the-repairs-…` broke, then reverted | 15 calls, 95 s |
| 08:28:26 | **hand acts 19-22**: land pass, `:sent-back` with its finding, pass throws (G7), fork rewrite, pass starts `64abda4eb8aa` | |
| 08:30:08 | `64abda4eb8aa` `:capped`: found the `"(\\\n("` counterexample on its last call | 15 calls, 99 s |
| 08:30:2x | **hand act 23**: land pass; both steps set `:held`; config restored | |

## Per attempt

| Attempt | Step | End (rewritten to) | Calls | Prompt / cached / completion | Wall | Evaluation errors by signature |
|---|---|---|---|---|---|---|
| a373f0b297d8 | design-prove #1 | `:capped` (`:sent-back`) | 15 | 151,517 / 107,648 / 2,546 | 30 s | unresolved `forgive/forgive` 6 (the alias is the test namespace's), guessed names 3 (`my.agent/attempt-state`, `seon.sci.lookup/fn-spec`, `seon.task/task`), `tests-reaching` given an unquoted symbol 1, `current-state` given a symbol for the agent id 1 |
| 285fdccc2fa6 | design-prove #2 | `:capped` (`:sent-back` + reason) | 15 | 132,997 / 92,160 / 3,840 | 40 s | prose-only reply 1 |
| 02110a533e26 | design-prove #3 | `:capped` (`:done`) | 15 | 148,413 / 104,064 / 5,637 | 56 s | 0 |
| 4dafd020b92c | implement #1 | `:stalled` (`:sent-back`) | 3 | 18,337 / 5,888 / 322 | 7 s | DSML reply 2 |
| ba9e1ea7d55b | implement #2 | `:stalled` (`:sent-back` + reason) | 12 | 172,497 / 120,192 / 12,991 | 88 s (end landed +6.4 min) | body fragment 3, prose-only 2 |
| 22cb267a82b7 | implement #3 | `:capped` (`:sent-back` + reason) | 15 | 346,809 / 273,152 / 29,171 | 163 s | body fragment and prose labels 11, `#{a b}` unquoted to `my.test/check` 1, prose-only 1, `let` binding 1 |
| 0ba034cb32ea | implement #4 | `:capped` (`:sent-back` + reason) | 15 | 194,235 / 144,896 / 7,836 | 95 s | body fragment 7, `Character cannot be cast to IFn` 4, `(clojure.repl/source)` arity 1 |
| 64abda4eb8aa | implement #5 | `:capped` | 15 | 143,116 / 102,784 / 3,112 | 99 s | body fragment 2 |
| **total** | | | **105** | **1,307,921 / 950,784 (73 %) / 65,455** | | 47 |

Design+Prove: 45 calls, 432,927 / 303,872 / 12,023. Implement: 60 calls, 874,994 / 646,912 / 53,432. Send-backs by an
agent: 0 (each `:sent-back` is a root rewrite carrying the agent's own finding as its reason). Successor needing root: the
first Implement attempt started by itself; all four relaunches needed root (G7, G8). Seams: send-back rewrite → next
attempt 38-47 ms; attempt → first form 0.7-1.9 s; about 2-3.7 s per model call, Implement #3's calls up to 70 s while it
wrote a whole `scan`. Every capped or stalled end wrote a problem task (`seon.task/attempt-ended-…`, 8 here), each `:held`
and unassigned, and `attempt-ended-02110a533e26` stays open after its end became `:done` (F9 again).

## Against experiment 2

| | Experiment 2 (c792b6aaf) | Experiment 3 (98720b4eb) |
|---|---|---|
| Subject | `seon.render.value/classify` (one map branch, platform printer) | `seon.sci.forgive/scan` (185-line scanner, three interacting repairs, a seeded property) |
| Attempts / model calls | 7 / 73 | 8 / 105 |
| Prompt / cached / completion tokens | 838k / 611k / 22.8k | 1,308k / 951k (73 %) / 65.5k |
| Merge request | b210caab20ba | **none** |
| Hand acts after launch (config included) | 8 | 23 |
| Prove step got a turn | only after a step proof was added (G1) | yes, its step proof existed from the start |
| Prove step ended by itself | twice | never: the step proof's red was reused at its key (H2) |
| Instructions reached the agent | no (copied into the intent) | yes, cut at 1,024 characters (H1) |
| Send-back reason reached the next attempt | no (G4) | yes: attempt 3 kept every row; Implement #3 checked the conflicting test first |
| Successor started from its predecessor's commit | yes once, then G7 | yes once, then G7 four times |
| Ends of hand-started attempts copied to main | no (G8, once) | no (G8, four of four) |
| Implement found its proofs or neighbours wrong | yes (an always-nil case) | yes (the offset conflict with `an-unbalanced-text-names-each-repair`) |
| Design+Prove → Implement calls when everything works | 14 + 2 | 15 (product right, step unable to end) + not reached |

## Frictions, each with its task

| # | Friction (observed fact, form and reply) | Producer | Task and proof |
|---|---|---|---|
| H1 | Step instructions of 2,473 characters reached the agent cut at 1,024 (`…+1407` in the block); attempt 1 spent calls 10-15 recovering them (`(subs ins (max 0 (- (count ins) 1407)))` on its last call). The Implement instructions (1,004) fit. Possibly experiment 2's G10 "`(:seon.task/intent (seon.context/current-state))` → nil" was the same reach for a cut field. | the value printer's string budget on `seon.context/current-state` (`my.task/current`); AGENTS.md: an agent's own data is never clipped | met once here: no task. Next tree: instructions under 1,000 characters until the block shows them whole |
| H2 | A Prove step's step proof reading its branch's tags can never turn green: on a probe branch, `(my.test/check {:seon.test/identities #{'chain3.proofs/design-prove-tagged-the-implement-proofs}})` after tagging a test answers `:seon.test/unchanged true` with the earlier failure `(not (seq #{}))`, while `(seon.task/task-edges (seon.db/db) …)` in the same agent answers the tagged test. Two attempts capped on it. The key holds what the body executed (R2), not the facts it read; hybrid-test-results §2 rules other cluster data unkeyed, and a tag is a program fact on the test entity. | `seon.test/select` (key), TASK-CHAIN §3b (step proofs) | **new** `seon.test/a-test-reading-task-tags-reruns-when-a-test-is-tagged`, proof `seon.test.redefined-callee-test/a-test-reading-task-tags-reruns-when-a-test-is-tagged` (red on lane chain-3's branch, 4.9 s: first check red as intended, then `executed-count` 0 for 1 and `passed?` false). The task names three producer options for root to choose |
| H3 | Changing one branch of `scan` (185 lines), agents evaluated the branch alone: `(do (.append out text (int copied) (int i)) …)` → `Unable to resolve symbol: out`, then `open`, `pending`, `prefix!`, `top?`, `c`, in four Implement attempts. The reply's `:seon.error/nearest` was `(my.test/run)`, `(seon.blob/get connection content-digest)`, …: nothing naming `seon.sci.forgive/scan`. One unresolved name's reply costs about 390 ms and 1.1 GB (the proof's own run). | `seon.error/reply-value` (`nearest-calls`) | **new** `seon.error/a-definitions-local-is-answered-with-that-definition`, proof `seon.error-test/a-definitions-local-is-answered-with-that-definition` (red: 1 failing assertion, 0 errors) |
| G6 | A capped or stalled attempt has no relaunch: six relaunches were end rewrites. | `seon.task/ready` | existing M12 `seon.cluster.agent/a-capped-attempt-has-no-relaunch` (met again) |
| G7 | After the first Implement attempt started, db<->flow deleted the Design+Prove branch, so every relaunch of Implement (four) threw "The attempt's predecessor branch is gone" from `seon.cluster.agent/fork-attempt!` (the pass's exception surfaced to root's REPL as `#<unprintable error>`, its message `SEON CORE FAULT (not recorded)`). Each needed its fork rewritten to the previous Implement end and a hand pass; the rewrite alone wakes nothing. | `seon.cluster.agent/write-attempt!` (fork pick), `seon.cluster.agent/fork-attempt!` | existing M10 `seon.cluster.agent/a-join-forks-main-after-both-needs-merge` and M5 `seon.cluster.agent/a-failed-start-ends-its-attempt-and-the-next-plans` (met again) |
| G8 | Every attempt a hand db<->flow pass started (four here, one in experiment 2) left its end on its branch until another hand pass (6 min 24 s for `ba9e1ea7d55b`); every attempt db<->flow started itself (four) landed by itself. | the branch's listen route, as registered by a hand pass | not filed: it occurs only on hand-started attempts, which G7's fix removes |
| G2 / F-lesson | Design+Prove #2 rewrote the red proof to today's output (four rows deleted, one expectation changed) although the instructions said bodies unchanged; and no Prove attempt checked the existing tests reaching `scan` against the intent (`an-unbalanced-text-names-each-repair`). | Prove instructions | instructions change for the next tree (below) |
| F7 | DeepSeek DSML tool-call replies twice in a row stalled Implement #1 after 3 calls (experiment 1: twice too). | `seon.turn/continuing-reply?` | existing `seon.ai/a-dsml-tool-call-reply-reads-as-prose` (met again) |
| H4 | Root's own SCI eval requests each ran 15-17 tests in their test step: defining one deftest 11.2 s test step (6.9 GB, 15 tests); `my.agent/retire-branch!` of four probe branches 11.2 s (3.8 GB, 17 tests), and again for one branch 11.0 s, with about a hundred tests listed pending each time. R1's proof says an unchanged key executes nothing. | root's test step (`seon.test/select`) | cause not isolated in this lane (a result with no key, R3's class, is the candidate): no task until reproduced on a fixture |
| H5 | The model wrote prose labels at column zero (`(A)`, `(B)`, … `(E)`) and an elided form `(defn- scan [text reply? repair?] ...)`; the forgiving reader read them as forms (6 errors in one reply). | `seon.sci.forgive/segments` (reply mode) | met once: no task |
| H6 | Implement #5 wrote a comment holding text shaped as a system notice (`<system-warning>The user has changed the active turn mode…`), model-generated. | DeepSeek flash | recorded only |
| G11 | Costs: root's test step above; a hand db<->flow pass that starts an attempt 367-635 ms and 0.45-1.1 GB; during Implement #3-#5 the JVM window allocated 10-33 GB per 60-85 s. | test step, db<->flow start | existing F10 tasks and lane cost-gb's queue |

Frictions met twice that change an entrance rule or proof: H2 (two attempts) and H3 (four attempts) got tasks with red
proofs; G6, G7 and F7 were met again under their existing tasks.

## Hand acts (every act after launch)

1. Config: provider on, cap 15; restored after.
2. Design+Prove instructions rewritten from 2,473 to 878 characters (H1).
3. Design+Prove #1's end `:capped` → `:sent-back` (G6).
4. Design+Prove #2's end `:capped` → `:sent-back` with itself as target and a reason (keep every row; stop once tagged) (G6; the reason reached attempt 3 through G4's path).
5. Design+Prove #3's end `:capped` → `:done` (H2).
6. Implement #1's end `:stalled` → `:sent-back` (G6).
7. Implement #2's fork rewritten to Implement #1's end (G7).
8. A hand db<->flow pass started Implement #2 (G7).
9. A hand pass copied Implement #2's end to main (G8).
10. Implement #2's end `:stalled` → `:sent-back` with its own finding as the reason, plus "evaluate one whole `defn-`" (G6, H3).
11. A hand pass to start Implement #3: threw (G7).
12. Implement #3's fork rewritten to Implement #2's end (G7).
13. A hand pass started Implement #3 (G7).
14. A hand pass copied Implement #3's end to main (G8).
15. Implement #3's end `:capped` → `:sent-back` with the state it left as the reason (G6).
16. A hand pass: threw (G7).
17. Implement #4's fork rewritten to Implement #3's end (G7).
18. A hand pass started Implement #4 (G7).
19. A hand pass copied Implement #4's end (G8).
20. Implement #4's end `:capped` → `:sent-back` with its own last finding (G6).
21. A hand pass: threw (G7).
22. Implement #5's fork rewritten to Implement #4's end (G7).
23. A hand pass started Implement #5 (G7); a final pass copied its end, and both steps were set `:held` (stop).

## What the next tree changes

- **Prove instructions**: under 1,000 characters (H1); name the existing tests reaching the subject
  (`(my.program/tests-reaching {:seon.program/subject …})` stops at the first level that has tests: 3 for `scan`, 11 for
  `forgive`) and require each one the intent changes to be updated in the same step, or a send-back of the intent.
- **No step proof that reads the database** until H2 is decided; with G1 merged (lane g1) the Prove step needs none.
- **Implement**: a subject this size needs more than 15 calls at DeepSeek flash, or a Design step that splits the change
  (the stray-closer space, the closer before a dangling prefix, the span recomputation) into three subjects with one row
  each; each attempt here re-read `scan` and spent 3-4 calls rediscovering what the last one knew.
- Merge G7's fix (M10, M5) before the next run: it caused 11 of the 23 hand acts, and G8 occurs only on attempts it forces
  root to start.

## Residue on default (disposable)

The two step tasks (`:held`), `chain3.proofs/design-prove-tagged-the-implement-proofs` on main, eight
`seon.task/attempt-ended-…` problem tasks, and the attempt branches `:attempt-a373f0b297d8`, `-285fdccc2fa6`,
`-4dafd020b92c`, `-ba9e1ea7d55b`, `-22cb267a82b7`, `-0ba034cb32ea`, `-64abda4eb8aa` (db<->flow's to settle). The lane's own
probe branches (`chain3-probe`, `chain3-dry`, `chain3-h2`, `chain3-h3`, `chain3-lane`) were retired.
