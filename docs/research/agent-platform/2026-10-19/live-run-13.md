---
title: "live run 13: the first designed Design -> Prove -> Implement chains, four chains on 4313acf79, 3 of 4 Designs and 2 of 3 Proves done, no Implement done, nothing merged"
type: evidence
status: "run complete 2026-10-19 in 3 min 22 s: 9 attempts, 215 calls, cached share 85%; Design 3 of 4 done (median 15 turns), Prove 2 of 3 done (16 and 16), Implement 0 of 2 (one correct send-back, one capped); no merge, so no merge delay and no filed entry to retire; no fault and no heap crossing during the run (one growth crossing 26 s after boot, before it); fixes 519c745fd (help binds task symbols with let), intake ce3b89669; six findings filed in this commit"
---

# Live run 13: the first live run of the designed chain

Lane run13 (Opus). Default JVM pid 55445 was restarted by root onto 4313acf79 at 10:31:36 UTC (ready 21.0 s). This
is the first live run of the designed flow: each filed task became a three-task chain, Design → Prove → Implement,
built by `seon.task/from-filed` and written by `seon.task/chain!` (which stops at the merge map since 7fa9ec012), with
root's final merge of the chain branch as the merge. It is the installed chain model, not the target `needs` graph
(task-system brief §0).

There was one root act: an SCI request as root at 10:43:40 UTC, four `chain!` calls, chain config
`{:seon.config.ai/no-provider :seon.config/absent :seon.config.run/max-episode-runs 40}`, model `deepseek-flash` and
`:seon.config.agent/max-running` 4 (main's values), task names suffixed `-run-13`. The form is `tmp/run13/start.clj`.
The request cost 1,638 ms and 1,398 MB (evaluation 1,490 ms, 643 MB, 75,514 calls) for four chains: the start cost
filed as `seon.task/starting-three-chains-costs-a-gigabyte` holds.

Hand acts on the agents: 0. Everything else was JVM-mode reads on default (the watch form of the brief §3.3, the
grades, the evaluations, the problem tasks, the memory pools), plus three SCI requests as this lane's own agent
`run13` on its own branch (the attempted `seon.ref` fix, then its revert). All four chains started at once. The last
attempt ended at 10:47:02 UTC. Scratch output, one file per attempt with every form and its shown value, is in
`tmp/run13/`.

## Broken things first

1. **A Prove step whose next proof had never run is red at its start and stays red, because the handoff's red result
   is reused after the proof runs.** The pull chain's Prove attempt fc99cdd864e6 capped at 40 calls with
   `seon.step.prove-test/handoff` red, while `seon.step.prove/handoff` refuses nothing on that branch (JVM probe after
   the bail, through `seon.test.runner/task-input`: `[]`).
   - The handoff ran once, in the start request (test run a3123caf5551, 10:44:01), when Implement's proof
     `seon.data-shapes-test/listen-patterns-retain-optional-entity-and-logical-value` had no result on the branch.
     The block showed it `:pending`.
   - The agent ran that proof at 10:44:10 (run 101c6a5f22ea: red, `designed-red?` true). In the remaining 38 calls
     no further run of the handoff was recorded, and the done rule at the end judged the handoff by run a3123caf5551.
   - The typed chain's Prove met the same start. Its agent ran the proof (call 12) and then the handoff by name with
     `my.test/check` (call 13), and was done at call 16. The history chain's Prove wrote a new deftest, a program change
     that a reused result cannot survive, and was done at 16 (inferred, not traced).
   - Cause as far as verified: a task-reading result is keyed by its program content and its task's revision. The
     handoff also reads the next task's proofs and their results, which are outside that key. Filed:
     `seon.test/a-prove-handoff-red-at-its-start-is-reused-after-its-next-proofs-run`. The fix is either the key or
     the start request running the next proofs first; it is a design decision for the test runner's owner.
2. **No chain merged.** Implement was reached twice and never done (one correct send-back, one cap), so the merge, its
   delay and the write-back placement fix (4313acf79) were not exercised live.
3. **Help taught every agent to `def` its task symbols on the shared chain branch.** "Write what you learn" said
   "Bind both once: (def task ...) and (def next-task ...)". Six of nine attempts did, in whatever namespace they
   were in (`user`, `my.attempt-*`, `my.program-test`). Each def is a declaration on the chain branch: the pull Prove
   unmapped the pull Design's `my.attempt-7ad1d758ddbf/task` at call 2, the history Prove unmapped its own two defs
   from `my.program-test`, and the typed Design unmapped `user/task` twice. **Fixed** in 519c745fd: help binds both
   in a `let`. Proof: a row in
   `seon.bootstrap-test/help-and-every-step-template-describe-one-chain-branch-and-plan-updates`, green in check
   d1e12f8e83be. Default needs a restart to read it.
4. **Design's `:references` check traps a plan whose problem is a name that does not exist.** The program Design
   (6f8dd19d0c5b) cited the attribute keys the problem is about, unregistered on purpose. The problem message said
   only that they resolve to nothing. From call 26 it rewrote the plan four times, read `seon.ref/candidate` and
   `seon.ref/references`, then registered both keys on its branch with `seon.schema/register!`, unregistered them,
   and capped at 40.
   - `seon.ref` already reads a two-backtick span as a literal (JVM probe: no reference; one backtick: one
     `:unresolved` problem). Nothing tells the agent so.
   - I tried the producer fix (the `:unresolved` message names the form). It cannot be committed: the message is a
     private def read only by compiled `seon.ref/unresolved`, so the branch redefinition is not reached (observed),
     and `bin/lane commit` refuses the file both by branch evidence (unreached) and by `--test` alone (an ordinary
     file). Reverted, and filed as `seon.ref/an-unresolved-problem-never-says-how-to-mention-a-missing-name` with the
     lane gap, which is the class `seon.cluster.branch-evidence/a-function-only-platform-tests-reach-reads-as-unreached`.
   - My intake removed the backticks from these keys in the Problem (ce3b89669), but the Design agent put them back
     in its own sections.
5. **Single forms of 1.4 to 4.3 s and 1.8 to 3.7 GB read `my.task/current` mid-attempt**, against 69 ms and 90 MB at
   an attempt's start. Each came after the agent had defined a deftest or run a check on its branch. Filed:
   `my.task/reading-the-current-task-mid-attempt-costs-gigabytes`, with the exact forms.
6. **Three HEAD reds had no task** (screen run d1935909e716). All three are red at 739604872, the parent of the reach
   rewrite fb9f1ca65 (check 1b3265299e7e), so that rewrite did not cause them. Filed, each with its red test as proof:
   - `my.program/a-function-called-through-its-callers-is-reached-is-red-on-head`;
   - `seon.db/a-namespace-entity-change-keeps-its-declarations-digest-on-head`;
   - `seon.db/two-fixtures-of-one-commit-branch-off-different-commits-on-head`.
7. **Two lookups of a missing name took 1.7 and 3.3 s with almost no allocation**:
   `(my.program/source 'seon.turn.loop/settle-request)` 1,738 ms and 7 MB (typed Implement, form 60), and
   `(my.program/source 'seon.program/set-attributes)` 3,291 ms and 7 MB (program Design, form 42). Time without
   allocation reads as waiting, while four agents ran. Unreproduced and unfiled; the next run should time one alone.

## Preconditions and intake (§6.0, §6.1)

- `seon.step.prove-test/handoff` and `seon.step.design-test/handoff` were green at HEAD 4313acf79 in the screen JVM.
  `max-running` read 4 on main.
- Screen: one check JVM, run d1935909e716, 224 tests in 82 s, 8 red. It held both handoffs, the four proofs,
  `seon.program-test`, `seon.data-shapes-test`, `my.program-test`, `seon.schema.datahike-test` and `seon.db-test`.
- References: `seon.ref/references` found three unresolved names in the two program plans (a backticked shell
  command and two test-local attribute keys). Fixed and committed in ce3b89669. Default had not ingested that text,
  so the start form passed both plans inline.

| chain | task | kind | proofs at HEAD | other reds in reach |
|---|---|---|---|---|
| `34c574b0445f` program | `seon.program/a-declared-attribute-without-a-registered-schema-is-refused` (run 12 retry) | Design must find the producer | both error, "The attribute has no registered schema." | typed's proof and `indexed-and-evaluated-...`, named in the plan |
| `8d1dbda3c222` typed | `seon.program/typed-cross-namespace-deletion-leaves-function-and-test` (run 12 retry) | Design must find the producer | red at `program_test.clj:1209` | program's two proofs and `indexed-and-evaluated-...`, named in the plan |
| `6097a5caea6f` pull | `seon.db/a-cardinality-many-pull-answers-a-set-where-a-test-reads-a-vector` | filed red proof, a retired assumption | red: a set where the test compares a vector | none |
| `16572b11dd32` history | `my.program/history-takes-a-symbol-subject` | new behavior, no proof | none (Prove must write it) | `my.program-test/a-function-called-through-its-callers-...` (unrelated reach), not named |

## Summary

Wall is the grade's wall time. Tokens are prompt / cached / completion. Plan versions are the task's `:seon.task/plan`
assertions in branch history (creation or advance included). The first write is the grade's first call holding a
definition.

| chain | step | end | calls | wall | tokens p / c / out | cached | forms (in-ns) | first write | plan updates (versions) | eval ms / MB |
|---|---|---|---|---|---|---|---|---|---|---|
| pull | Design | **done at 9** | 9 | 26.7 s | 99,171 / 68,480 / 1,841 | 69% | 25 (0) | call 1 | 1 (2) | 2,944 / 803 |
| pull | Prove | **capped**, handoff red (Broken 1) | 40 | 99.2 s | 818,405 / 743,936 / 7,204 | 91% | 51 (3) | none | 0 (1) | 3,788 / 4,118 |
| history | Design | **done at 15** | 15 | 40.7 s | 201,273 / 150,272 / 2,607 | 75% | 28 (1) | call 11 | 1 (2) | 4,895 / 4,206 |
| history | Prove | **done at 16**, one proof onto Implement | 16 | 55.9 s | 285,334 / 232,320 / 3,302 | 81% | 46 (3) | call 4 | 0 (1) | 3,505 / 4,544 |
| history | Implement | **sent back at 13**, correctly | 13 | 45.2 s | 188,212 / 144,768 / 3,832 | 77% | 31 (8) | call 3 | 0 (1) | 3,160 / 3,559 |
| program | Design | **capped**, `:references` (Broken 4) | 40 | 103.2 s | 877,797 / 729,728 / 6,044 | 83% | 68 (1) | call 35 | 3 (4) | 7,212 / 1,768 |
| typed | Design | **done at 26** | 26 | 64.8 s | 489,575 / 392,576 / 3,588 | 80% | 46 (4) | call 3 | 2 (3) | not recorded |
| typed | Prove | **done at 16**, nothing written | 16 | 39.3 s | 248,570 / 205,312 / 2,352 | 83% | 25 (2) | none | 0 (1) | 3,116 / 895 |
| typed | Implement | **capped**, proof red | 40 | 106.0 s | 1,247,292 / 1,123,712 / 9,460 | 90% | 104 (41) | call 28 | 0 (1) | 5,216 / 1,335 |

Totals:
- 215 calls and 4,455,629 prompt tokens, of which 3,791,104 were cached (**85%**);
- 40,230 completion tokens;
- 424 forms, 63 of them `in-ns` (14.9%; 7.0% without typed Implement, which made 41);
- 40 wasted calls by the grade's rule;
- 581 s of summed wall, in 3 min 22 s of elapsed time with four agents at once.

The typed Design's evaluations carry no duration or allocation (its forms ran in `user`), so the dump shows none.

## Watch (§6.3)

- **Advances worked every time.** Each Design done was followed by Prove running on the same branch, a new attempt,
  within the pass: pull 10:43:55 → Prove, history 10:44:09 → Prove, typed 10:44:33 → Prove. Each Prove done was
  followed by Implement (history, typed). The plan flowed: each next task holds one plan version, the advance's.
- **Prove's write into Implement's proofs.** History: Implement went from no proofs to
  `#{my.program-test/a-history-subject-may-be-a-bare-symbol-or-keyword}`, through the template's `update!` form.
  Pull: the agent wrote Implement's existing set back unchanged (`constantly`). Typed: no write; the filed proof
  sufficed.
- **Plan versions.** Design updated its plan in all four chains (1, 1, 2 and 3 updates). No Prove or Implement
  updated a plan, though 31d6b7c0e asks every step to correct it.
- **Bails.** Four problem tasks on main, each `seon.task/unfinished-task-<key>` with its verdict: program Design
  (capped), pull Prove (capped), history Implement (sent back), typed Implement (capped).
- **Merge and merge delay:** none; nothing reached a final done.
- **Health and heap.** No error was recorded between 10:43 and 10:48. G1 old gen peaked at 1,268 MB of 4,096 in this
  process. The open fault `seon.cluster.boot/heap-crossed!` (17 occurrences) was last written at 10:32:02, 26 s after
  boot, at level `:growth`, before the run. Health stays `:faulted` on open faults older than the run.

## Bails, classified (§6.5)

| chain | step | end | what happened (read from the forms) | class |
|---|---|---|---|---|
| program | Design | capped | Read the producer path (`seon.program/shapes-in`, `derived-shape`, `seon.schema.datahike/attribute-declaration`) by call 20 and wrote What exists and Approach. Then `:references` refused the unregistered keys its sections cited; calls 26 to 40 went to that (Broken 4) | **context wrong** (the problem message); help's `def` added two calls |
| pull | Prove | capped | Its handoff was red from the start request and never re-run (Broken 1); it read `seon.step.prove/handoff`, `seon.test.runner/current-results` and `seon.test/task` for 30 calls without finding why | **context and mechanism wrong** (filed) |
| history | Implement | sent back at 13 | Prove's new row asserted `(my.program/history key)` for a keyword the fixture never declares. Both forms refuse identically except `:seon.error/at`, so the row could not turn green. Implement said exactly that | **task wrong**: Prove's proof was red for the wrong reason. Prove's `:not-designed-red` counts any failing assertion, so its handoff accepted it. The send-back is right |
| typed | Implement | capped | The Approach named `seon.turn/row-tx`. The agent read the settle path for 27 calls, redefined `seon.turn/receipt-settle-call` with its body unchanged at call 28, then ran fixture probes in `my.attempt-1` (41 `in-ns`). The verdict also holds `:one-file` for `user` (its `in-ns 'user` and `require`) | **model behavior**, plus the filed class `seon.cluster.source/an-agents-repl-scratch-namespace-never-blocks-its-landing` |

**Design passed its handoff with a wrong Approach once.** The pull plan said the vector's order is the retired
assumption: assert set membership in the test. The pull Design wrote "The fix belongs to the pull producer, not to
the test" and named `seon.db/pull` as the function to change. Its handoff passed: Problem unchanged, producer cited,
data modeled. This is S5 run 4's finding 3 again. A table row in `seon.step.design-test/handoff` cannot see a reversed
decision, so it is recorded here and not filed.

## The metric

`seon.mine/report` over main's 23 grades (`my.root/turns-report`'s body, read in JVM mode). This is the first run
whose grades carry `:seon.grade/step` (739604872). Runs 10 to 12 grade as `:other`.

| window | step | attempts | green | median / p90 turns to green | median plan updates | median first write |
|---|---|---|---|---|---|---|
| ab89888ef (run 10) | other | 4 | 0 | none | | |
| 5e8426095 (run 11) | other | 4 | 2 | 23 / 32 | | |
| e19694162 (run 12) | other | 6 | 2 | 21 / 32 | | |
| 4313acf79 (run 13) | **design** | 4 | 3 | **15 / 26** | 1 | 3 |
| 4313acf79 (run 13) | **prove** | 3 | 2 | **16 / 16** | 0 | 4 |
| 4313acf79 (run 13) | **implement** | 2 | 0 | none | 0 | 3 |
| whole | | 23 | 9 | 21 / 32 | | |

Top waste producers over all grades: `seon.context/current-state` 74 forms (redundant `in-ns`),
`my.program/render-source-ai` 55 (re-reads), `seon.render.value/show` 14. Run 13's typed Implement alone made 41
`in-ns` forms.

## Runs 10 to 13

| measure | run 10 (ab89888ef) | run 11 (5e8426095) | run 12 (e19694162) | run 13 (4313acf79) |
|---|---|---|---|---|
| shape | 4 Implement-only | 4 Implement-only | 6 Implement-only | **4 Design → Prove → Implement** |
| done / merged | 0 of 4 | 2 of 4 done, 1 merged | 2 of 6 done, 2 merged | Design 3 of 4, Prove 2 of 3, Implement 0 of 2; 0 merged |
| turns to green | none | 23, 32 | 21, 32 | Design 9, 15, 26; Prove 16, 16 |
| calls per attempt | 40 | 34 | 35 | 24 (9 to 40) |
| cached share | 90% | 87% | 87% | 85% |
| `in-ns` share of forms | 7.3% | 5.0% | 14.4% | 14.9% (7.0% without typed Implement) |
| first write | form 16, 53, 45, never | form 31, 55, 25, 50 | form 6, 22, 56, 57, never, none | call 1 to 35; median 3 (Design and Implement), 4 (Prove) |
| plan updates | n/a | n/a | 1 in 6 chains | Design 1 to 3 in all 4; Prove and Implement 0 |
| largest form | 164 ms | 226 ms | 5,266 ms | 4,266 ms, 3,682 MB (`my.task/update!` of a plan, Broken 5) |
| heap (old gen peak) | 812 MB | 798 MB | 1,997 MB | 1,268 MB |

What improved:
- The designed chain runs live. `from-filed` → `chain!` worked as one form. Advances, plan flow and Prove's write into
  Implement's proofs happened as TASK-CHAIN §2 describes, with no hand act.
- Design did its job in 9 to 26 calls three times out of four. Every Design wrote What exists and Approach citing
  the producer, and the history Approach named the function its Implement then judged correctly; whether the typed
  Approach's `seon.turn/row-tx` is the producer is still unproven.
- The one Implement send-back named the exact defect in the proof it was handed.

What did not:
- No Implement was done, so nothing merged.
- One Prove of three was blocked by the system, not by the model (Broken 1).
- Only Design updates the plan.

## Fixes and filings

- ce3b89669: intake plan fixes for the two `seon.program` tasks.
- 519c745fd: help binds the task symbols with `let` (Broken 3).
- This commit files:
  - `seon.test/a-prove-handoff-red-at-its-start-is-reused-after-its-next-proofs-run` (Broken 1);
  - `seon.ref/an-unresolved-problem-never-says-how-to-mention-a-missing-name` (Broken 4);
  - `my.task/reading-the-current-task-mid-attempt-costs-gigabytes` (Broken 5);
  - `my.program/a-function-called-through-its-callers-is-reached-is-red-on-head`,
    `seon.db/a-namespace-entity-change-keeps-its-declarations-digest-on-head` and
    `seon.db/two-fixtures-of-one-commit-branch-off-different-commits-on-head` (Broken 6).
- No chain merged, so no merge review (§6.4) and no filed entry to retire.

## Next run

- Restart default onto 519c745fd or later, so agents read the `let` help.
- Fix Broken 1 before the next run with a Prove step whose next proof has never run. Until then, a Prove start with a
  `:pending` next proof needs the handoff run by name.
- Kept branches to rerun from: `:chain-34c574b0445f` (program; the Design's sections minus the backticked keys),
  `:chain-6097a5caea6f` (pull; its Design Approach reverses the plan's decision, so start a new chain),
  `:chain-16572b11dd32` (history; Prove's keyword row must declare its key, or drop it) and `:chain-8d1dbda3c222`
  (typed; Design and Prove done, Implement capped).
- Time `my.program/source` of a missing name alone (Broken 7).
