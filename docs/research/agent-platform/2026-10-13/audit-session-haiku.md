---
title: "Audit of 2026-10-13 work: TASK-CHAIN progress, drift, code quality, process"
type: research
status: complete; research only, judged from git, files and root's notes; no SCI evals on default, no tests run, no code changed
basis: "git log a45f6a96b..6a421d96e (87 commits, 06:01-16:32 +0700; a45f6a96b is the last commit before today's window); TASK-CHAIN.md status line; plan.md section 0; night audit and 2026-10-13 research; resources/seon/tasks; memory project_handoff_2026_10_12_night.md (root's running notes, cited by the commit sha it names)"
---

# Audit of 2026-10-13 work

Evidence tags: **[V]** verified by a quoted command result or commit message; **[I]** inferred from the evidence named.
Tests were not run. "Green" and "red" below come from commit messages, TASK-CHAIN's phase-status line and test
metadata, not from a fresh run, so every test state is [V] only as a claim of a commit.

## Summary

- **TASK-CHAIN is partly built.** Green per its status line: A1, A2, A3, A5, A6, I0, plus A4 (phase 1) and phase 4 done. Red: A7 (full loop), I1, I2, I3 (Survey's approval) and the every-agent-start test (5b). No live DeepSeek chain has run on this spec. [V status line; commit list]
- **Chains ran unattended only twice today.** Of seven real chain runs (Runs 18-24, commits 06:47-09:09), Runs 19 and 20 were clean; Runs 18, 21, 22, 23 and 24 stalled on lifecycle defects. [V commit messages f15f6c20b, dbaf7ea46, d158237bd, 743aba13f, 391422b22, 5a3487d7e, 7454efe88]
- **The spec was locked at 10:10 and simplified twice** (40dd25678, 1415880d5, reviews bcf638361 and 79633359f, narrow check 30368cc00). Most of the day after 11:00 went to executing it. [V commit list]
- **Net growth, not deletion.** `src/` moved +2,314 / -1,349 (net +965) and `test/` +2,120 / -898 (net +1,222). Several phase commits report deletions (25973cd04 "net -115"), but the sum is growth. [V git diff --shortstat]
- **Restarts: at least 13 today** named with a commit in root's notes (handoff). [V handoff notes for 3b5585017, 487388073, 6299bcda4, 685ff94ad, 251d40805, e3515306a]
- **Unowned reds exist, contrary to plan.** plan.md §0 says "Reds on HEAD: 29, all owned by a task". My grep finds 4 host-test reds and 3 db-flow chain reds with no task file naming them. [V rg]
- **Patch hygiene is poor.** Of the 33 patches under `docs/research/agent-platform/2026-10-12/` and `2026-10-13/`, 3 apply as applied, 2 are unapplied and clean, 28 conflict. One of today's patches (`m13-held-task-opens-no-model-turn.patch`, 16:34) is unapplied and uncommitted. [V git apply --check]
- **Haiku claims were refuted, twice today (inventory of `operator.runtime`; chain-candidates "weak") and once overnight ("stale context risk"). Each was caught by root reading the evidence, not by a standing check. [V handoff notes for bc44d6587, the chain-candidates-3 paragraph and c05059e06; c05059e06 message]
- **Process breach to confirm:** root's notes say the 73968333e lane used `LANE_ADOPT`, which `bin/lane` reserves for root. The commit message does not say so. [V handoff note for 73968333e; bin/lane:22, :150; [I] that it was a breach until lane logs are read]
- **Lifecycle code is still accreting hand sets.** `src/seon/cluster/agent.clj` gained `#{:relaunch :close}` and `#{:attempt :start-flow :merge-base :merge}` literal sets today, and the new `end-kind` table in `task.clj` is still filtered with `#{:merge}`. [V grep of today's diff]

---

## 1. Where the plan is

### 1.1 TASK-CHAIN phases and acceptance tests

Acceptance tests exist in the tree (each located by `rg`). The "state" column is the status line's claim plus the commit
that wrote or moved it.

| Item | Test (file) | State | Commit (message excerpt) | Tag |
|---|---|---|---|---|
| Slice 0, red tests and phase tasks | all of the below | written and red at the time | 8a9fb77c5 "File the TASK-CHAIN phase tasks and their first red acceptance tests"; ab893212d "every acceptance test now written" | [V] |
| A1 one proof selector | `seon.task-test/one-selector-serves-every-proof-consumer` (test/seon/task_test.clj) | green | 941c494ec "One proof selector over main, candidate and merge base (TASK-CHAIN A1)" | [V] |
| Phase 3 remainder | `seon.task/chain-obligations` | open: successor-tag inference not yet deleted | 04964a3e4 "remaining item is deleting successor-tag product inference" | [V] |
| A2 one verdict | `seon.cluster.merge-test/only-an-accepted-candidate-completes-a-task` | green | 251d40805 "One merge verdict; prepare by task symbol ... (TASK-CHAIN phase 4, slice 4a)" | [V] |
| Phase 4 (4a, 4b, 4c) | | done | 25973cd04 (4b), 6548fa208 "chain-acceptance retires" | [V] |
| A3 templates and one-write chain | `seon.task-test/one-write-clones-a-chain-and-one-running-readies-survey` | green | a98fd86b9 "Five step templates and one-write seon.task/chain! (TASK-CHAIN phase 6, A3)" | [V] |
| A4 declarations from schema | (phase 1's own test) | done | 1d950f730 "Derive task declaration members from the schema"; handoff note for 1d950f730 "PHASE 1 DONE" | [V] |
| A5 one reference rule | `seon.ref-test/all-prose-uses-one-reference-rule` | green | 73968333e "One reference rule for all prose ... (TASK-CHAIN phase 2, A5)" | [V] |
| A5b declared render per kind | `seon.ref-test/each-reference-kind-uses-its-declared-render` | committed; green not stated | 47c49ea43 "Each reference carries the render its schema declares ... (phase 2, A5b)" | [I] |
| A6 one end-to-action owner | `seon.cluster.db-flow-test/ends-share-one-action-owner` | green | 595f8f7de "One end-to-action owner ... (phase 5, slice 5a, A6)" | [V] |
| 5b every agent start has a task | `seon.cluster.db-flow-test/every-agent-start-has-a-task` | red | status line: "Left: slice 5b ... (red)" | [V] |
| I0 intake search | `my.program-test/intake-search-finds-owners-with-candidate-costs` | green | e3515306a "my.program/search links a statement's words to owners ... (TASK-CHAIN I0)" | [V] |
| I1 user reply wakes the asker | `seon.render.web-test/a-user-reply-post-wakes-the-addressed-agent` | red (no commit claims green) | 5a97a217f "TASK-CHAIN acceptance reds: every agent start has a task, and a user reply wakes the asking agent" | [V] red at commit; [I] red now |
| I2 answer validated | `my.agent-test/an-answer-is-validated-and-keeps-its-message` | red (no commit claims green) | a558bfe58 "TASK-CHAIN acceptance reds A2, I2, I3" | [V] red at commit; [I] red now |
| I3 Survey waits for approval | `seon.step-test/survey-waits-for-the-latest-user-approval` | red | status line: "Left: Survey's approval clause (I3, after I1/I2)" | [V] |
| A7 scripted full chain | `seon.cluster.db-flow-test/a-scripted-full-chain-completes-without-hand-acts` (db_flow_test.clj:1483) | red; bound 120 s is unmeasured | 4c4c2a7bb "TASK-CHAIN acceptance red A7"; metadata `:seon.test/long-ms 120000`; spec §5: "needs owner authorization" | [V] |
| Phase 7 full loop | `seon.task/chain-loop` | not started; it is A7 | spec §6 table row 7 | [V] |
| Prerequisite `seon.fn/chain-namespace-source` | none | no commit found | spec §6 "Prerequisite" row | [I] |
| UI phases | none in §6 | deferred; spec and docs only | 5e5fbc2d7 "docs(ui): separate deferred user surfaces from task chains"; 72507d83b "Specify user-agent layout" | [V] |

The 120 s bound on A7 is an observed declared bound, not a measured duration. The spec requires slice 0 to measure A7 and
get owner authorization before it is a gate (spec §5: "A7 has no measured duration yet"). I found no commit that does
either. [V spec text; [I] no measurement commit]

### 1.2 What remains before A7, and before a live DeepSeek chain

Ordered by what A7 needs, from the spec and the status line:

1. I1 (reply delivery to the recipient's branch), then I2 (`my.agent/answer!`), then I3 (Survey's approval gate). Each is red. [V]
2. 5b, every agent start has a task. Red. [V]
3. Phase 3 remainder: delete successor-tag product inference, and the copied-proofs member. [V 04964a3e4]
4. The db-flow chain reds, which the chain-reds note classifies as chain blockers: `a-prove-steps-red-product-merges-into-main`, `an-ended-attempt-reaches-main-in-one-transaction-and-its-branch-is-deleted` (publication fault) and `a-merged-assignment-whose-graph-survived-a-crash-is-stopped-and-deleted`. The patch that converts them (`chain-reds-db-flow.patch`) has every added line already in HEAD, so it is applied in substance; the reds it left are still red, per the note. [V chain-reds.md; patch line check; [I] red now]
5. Owner authorization for A7's 120 s bound, and its measurement. [V spec §5]
6. Platform hazard still open: F55 (branch-loaded-by-name reads without commit identity, a fork bump for the next restart batch; filed in `resources/seon/tasks/seon.db.edn`). [V 6299bcda4; rg]
7. Plan §0 rule: implement and verify the scripted chain with no paid calls before another paid experiment. A live DeepSeek chain should wait for A7 green. [V plan.md §0]

### 1.3 Chain runs today (real DeepSeek unless noted)

| Run | Subject (commit) | Time | Result | Tag |
|---|---|---|---|---|
| 18 | surface-id (487388073) | 06:47 | STALLED in Prove; 23 min live-lock on one core; F53 | [V f15f6c20b] |
| 19 | surface-id rerun (6299bcda4) | 06:56 | clean, 21 s, 4+2 calls, 0 hand acts | [V dbaf7ea46] |
| 20 | token-boundary? (606110768) | 07:11 | clean, 25 s, 5+2 calls | [V d158237bd] |
| 21 | single-form? (1d538ec20) | 07:44 | STALLED; 3 attempts x 60 calls, 180 calls, all capped | [V 743aba13f] |
| 22 | single-form? rerun (d8b470c57) | 08:17 | Prove done; merge refused by scratch helper; dead end | [V 391422b22] |
| 23 | single-form? rerun (6e8b157ed) | 08:41 | STALLED 14 s after launch; relaunch to `:satisfied` with no work | [V 5a3487d7e] |
| 24 | single-form? rerun (371a754df) | 09:09 | Prove merged its tests; Implement stalled on a corrupt ns form | [V 7454efe88] |

## 2. Going off track?

### 2.1 Lanes stopping on undecided gaps

- Three filed subjects say "Not launched." in their commit messages: 487388073, 606110768 and 1d538ec20. They were then launched as Runs 18, 20 and 21, so this is a launch-order note, not a stall. [V commit messages f15f6c20b, d158237bd, 743aba13f]
- Chain 5's filing was blocked by an interpreted-red class: "Chain 5 not filed: source-path? ... fail the gate. Interpreted-red class blocks subjects — needs an owner" (handoff note after 1c68e9f5d). Interpreted reds were then split into 3 fixed and 2 filed (1c68e9f5d). [V handoff; V 1c68e9f5d]
- The A7 bound needs owner authorization and a measurement, neither done. [V spec §5]

### 2.2 Cascades

- **HEAD lint-red for 25 minutes after a chain merge.** Chain 4's merge 5f7fc3f7b published `block_test.clj` with a `pr-str` message, and every lane's `bin/lane commit` was refused until 1c68e9f5d. Fixed by 770733432 "the merge holds the lane commit's lint bar". [V 770733432 message]
- **Merged reds spread.** 25973cd04 "New red: prove-step-whose-product-main-holds (no :satisfied → merge refuses Prov...)" (handoff note for 25973cd04). [V]
- **Restart after each fix.** 13 restarts today (section 2.4). Restarts are batched by rule, but 4 of them fell between 14:00 and 16:30 (251d40805, 25973cd04, 685ff94ad, e3515306a). [V handoff; I on batching]
- **Boot time rose with changed inputs.** The boot sentinel found the 73968333e restart took 49.8 s total against 24 s for a 25-input restart, because `:seon.source/current` is 0.38 s per changed input. [V boot-sentinel.md table]

### 2.3 Patches left unapplied

Today's patches under `docs/research/agent-platform/2026-10-13/` (checked with `git apply --check -R`, which tests whether a
patch is already applied, then forward, which tests whether it applies cleanly; the working tree had uncommitted changes):

| Patch | Status | Evidence | Tag |
|---|---|---|---|
| armed-three-maintenance-tasks.patch | applied | reverse check passes | [V] |
| chain-reds-db-flow.patch | applied in substance | 34 of its added lines of 25+ characters present in HEAD's db_flow_test.clj; forward/reverse check conflicts since later commits touched the same lines | [V line check; [I] status] |
| task-chain-phase-tasks.patch | likely applied | 8a9fb77c5 "File the TASK-CHAIN phase tasks" | [I] |
| test-task-input-root.patch | likely applied | c5eb7b814 "the :seon.test/task test-run input" | [I] |
| chain-actions-draft.patch | likely superseded | 595f8f7de "One end-to-action owner" touches the same files | [I] |
| user-view.patch | unapplied | 1 of 5 added lines present in HEAD; UI work | [V line check] |
| m13-held-task-opens-no-model-turn.patch | **unapplied, uncommitted, clean** | forward check applies; created 16:34; touches no commit | [V git apply --check; mtime] |

2026-10-12 folder: 3 applied (k6-namespace-aliases x2, lane-accept-red-before-quote), 2 unapplied and clean
(cost-fresh-rehost-proof, quoted-reach-calls-only), 28 conflicting. The conflicting ones are mostly from the night and
likely superseded by later commits, but none is marked so. [V git apply --check; [I] superseded]

### 2.4 Restarts

At least 13 restarts today have a named commit in root's notes: 3b5585017 (06:13), 487388073 (06:21), 6299bcda4 (06:53),
606110768 (07:06), 1d538ec20 (07:21), d8b470c57 (07:54), 6e8b157ed (08:29), 73968333e (13:15), 1d950f730 (11:55),
251d40805 (14:02), 25973cd04 (14:32), 685ff94ad (15:14), e3515306a (16:16). [V handoff notes for the shas named in this list]
Two commit messages mention restarts directly. Many more are "Default restarted" without a hash. The true count is at least 13. [V]

### 2.5 Pre-existing reds, and whether they have an owner

| Red | Class | Owner (task file) | Tag |
|---|---|---|---|
| `seon.cluster.host-test/an-sci-send-from-the-cluster-supplies-its-handle-and-resumes-a-stopped-agent` | pre-existing | none | [V rg] |
| `seon.cluster.host-test/an-agent-creates-a-task-at-its-repl-and-it-is-a-cluster-task` | pre-existing | none | [V rg] |
| `seon.cluster.host-test/retirement-closes-admission-at-the-writer-and-races-submission` | pre-existing | none | [V rg] |
| `seon.cluster.host-test/a-message-to-an-admitted-agent-whose-branch-was-deleted-refuses-branch-absent` | pre-existing | none | [V rg] |
| `seon.cluster.host-test/a-message-to-a-stopped-admitted-agent-hosts-it-and-its-db<->flow-takes-it` | pre-existing | `seon.cluster.host.edn` | [V rg] |
| `seon.cluster.host-test/a-refused-deletion-is-its-forms-refusal-and-the-run-closes` | pre-existing | `seon.cluster.host.edn`, `seon.test.edn` | [V rg] |
| 2 reds in `seon.cluster.evaluate-source-test` | pre-existing | names not captured | [I] |
| `seon.cluster.db-flow-test/an-ended-attempt-reaches-main-in-one-transaction-and-its-branch-is-deleted` | chain red | none | [V rg] |
| `seon.cluster.db-flow-test/a-merged-assignment-whose-graph-survived-a-crash-is-stopped-and-deleted` | chain red | none | [V rg] |
| `seon.cluster.db-flow-test/a-prove-steps-red-product-merges-into-main` | chain red | none | [V rg] |
| `seon.test-census-test/no-test-redefines-a-var` | census | 3 task files | [V rg] |
| `seon.dev.hook-test` | flake, passes standalone | none | [V 3b5585017 message] |

Plan §0 says "29, all owned by a task since a05ba6655". The grep contradicts that for at least 9 of the named reds. [V]

## 3. Code quality

### 3.1 Net lines (base a45f6a96b, HEAD 6a421d96e)

| Tree | Files | Insertions | Deletions | Net | Tag |
|---|---|---|---|---|---|
| `src/` | 49 | +2,314 | -1,349 | +965 | [V git diff --shortstat] |
| `test/` | 53 | +2,120 | -898 | +1,222 | [V] |
| `resources/` | 47 | +344 | -147 | +197 | [V] |

Largest `src/` changes: `src/seon/cluster/source.clj` +335/-173, `src/seon/ref.clj` +325/-112, `src/my/program.clj`
+224/-64, `src/seon/task.clj` +195/-63, `src/seon/cluster/agent.clj` +161/-184 (net -23). [V git diff --numstat]

Commit-level deletions, claimed in messages: 25973cd04 "net -115", 941c494ec with cb7ec1180 "net -52", c05059e06 "-111". [V]

### 3.2 Hand lists, literal sets, special cases

44 added lines in `src/` contain `#{`. Those with constant members, which are the hand lists the plan says to delete:

- `src/seon/cluster/agent.clj`: `(#{:relaunch :close} kind)` and `(#{:attempt :start-flow :merge-base :merge} op)`. Both are end-kind policy, the thing the new `seon.task/end-kind` table is meant to own. [V]
- `src/seon/task.clj`: `(remove (comp #{:merge} end-kind))`. [V]
- `src/seon/ref.clj`: `#{"e.g." "i.e." "etc." "vs."}` and `#{"Example" "Examples"}`. Prose-side lists. [V]
- `src/seon/repl.clj`: `(some #{character} "()[]{}\";,`~@^'")`, a hand-written character set. [V]
- `src/seon/turn/batch.clj`: `(remove (into #{namespace-name 'clojure.core} requires))`. [V]

### 3.3 Atoms, volatiles, try

- New atoms in `src/`: 0. New `volatile!` in `src/seon/ref.clj` (lines near 1242, a paragraph accumulator). New `delay` in `src/seon/turn.clj` (`requiring-resolve` of `seon.context/task-done?`). Both are local; [I] acceptable, but CLAUDE.md asks that mutable cells be justified. [V grep]
- `try` forms added in `src/`: 7 (`agent.clj`, `registry.clj` with `finally`, `source.clj` x2, `context.clj`, `merge.clj`, `turn.clj`). None is a `catch` in `src/`. The one new `catch` is in a test file. [V grep]

### 3.4 file:line in source

`src/seon/cluster/source.clj` (comment): `;; commit (datahike writer.cljc:210).` The rule is no `file:line` in source, only in dated evidence. [V]

### 3.5 Contracts on new functions

66 new `defn` lines in `src/`. A heuristic (contract within 8 lines) flagged 6. I read each of the six at HEAD and all six carry `:malli/schema`: `seon.cluster.agent/assignment-actions`, `seon.cluster.source/gap-symbols`, `seon.cluster.source/merge-verdict`, `seon.cluster.source/results-verdict` (contract after a long docstring), `seon.task/assigned-obligations`, `seon.task/chain!`. So I confirmed 0 missing among flagged, and the heuristic has false negatives for long docstrings. Not exhaustive. [V]

### 3.6 Tests asserting printed text

- `test/seon/concurrency_independence_test.clj:616-617`: `(println "concurrency-independence timings" (pr-str timings))`. A test body printing a value. Not an assertion message, but it is printed output outside the value printer. [V]
- `test/seon/test_support.clj:1615-1617`: builds a question string with `(pr-str question-text)`. A fixture that builds source text, [I] acceptable.
- `test/seon/cluster/write_back_test.clj` generates a deftest containing `(pr-str :x)` as source text. A fixture, [I] acceptable.

## 4. Process

- **LANE_ADOPT.** `bin/lane` reserves `LANE_ADOPT=1` for root (`bin/lane:22`, `bin/lane:150`). Root's notes say the 73968333e lane used it ("Lane used LANE_ADOPT (root-only) — process", handoff note for 73968333e). The commit message does not mention it. [V] for the notes; [I] that this was an unauthorized use until the lane's log is read. The rule file says "root only". Also in the test suite (`test/seon/lane_test.clj:52, :120`), so tests use it legitimately. [V]
- **Lane time boxes.** The commits do not record elapsed time, so the 15-20 minute box cannot be checked directly. The clearest overruns are chain runs: Run 21 ran 180 model calls in three 60-call attempts (743aba13f), and Run 18 spun about 23 min on one core (f15f6c20b). [V]
- **Model calls.** Run 24's Implement spent "~25 spent looking for a way to see or edit the ns form" (7454efe88). A tool gap, not a time box. [V]
- **Haiku claims refuted, or not verified:**
  - "Haiku inventory WRONG on operator.runtime (lives in resources/seon/operator/runtime.clj)", handoff note for c05059e06, and commit c05059e06's message gives the same correction. [V]
  - "Haiku 'stale context risk' refuted", handoff note for bc44d6587 (overnight 10-12/13). [V]
  - "Haiku chain-candidates-3: weak", handoff note for the chain-candidates-3 paragraph. [V]
  - turn-reds-triage.md (Haiku, "inferred"), 33 slots, handoff note for 7f96b6cba (turn-reds-triage). Not verified by a run. [V note; [I] unverified]
  - Positive: lifecycle-commonality spot-checked, 3 of 3 claims ✓ (handoff note for 2d38bf197 (lifecycle-commonality)). [V]
- **Restart discipline.** Root's rule is one batch per restart. Today's 13 restarts with a named commit (2.4) are more than one batch per run. [I]
- **Boot sentinel.** `boot-sentinel.md` states it did not run `script/seon/sentinel.clj`, because that runs SCI on default. This is correct. [V]

## 5. Open findings: filed or unfiled

| Finding | Source | Filed? | Tag |
|---|---|---|---|
| `seon.test/host` closure walk, ~68 ms / 5,231 calls for a distance-1 test render | 41bcd24d8; handoff note for 41bcd24d8 "(unfiled)" | not found by name search | [V memory; [I] unfiled] |
| Task files written on one line, layout rule | 685ff94ad; handoff note for 685ff94ad "layout rule TODO" | no task found | [I] |
| `resources/seon/tasks/seon.instrument.edn` task `a-test-redefines-arming-jvm-wide` names the deleted `seon.dev.source-instrumentation-test`, which c05059e06 deleted | c05059e06; handoff note for c05059e06 "TODO retire task" | task still present, not retired | [V rg; the deletion is in c05059e06's message] |
| 4 host-test reds with no owner | section 2.5 | no | [V] |
| 3 db-flow chain reds with no owner, and the chain-reds patch not in HEAD as tasks | chain-reds.md | no | [V] |
| 2 `seon.cluster.evaluate-source-test` reds, names not captured | 3b5585017; handoff note for 3b5585017 | no | [I] |
| Flake `ready-answers-in-under-10-ms` (11.56 ms once) | 1d950f730; handoff note for 1d950f730 | test exists; no flake task | [I] |
| ~175 MB/call allocation for `my.program/search`, "follow-up" | e3515306a; handoff note for e3515306a | no task found by name | [I] |
| Boot sentinel recommendations: log phases inside `source/current`; zero-change restart; investigate 371a754df's 0.83 s/input; watch the 944 ms docstring pass | boot-sentinel.md | none; no commit | [V no commit] |
| Interpreted-red class "needs an owner" | handoff note after 1c68e9f5d; 1c68e9f5d | class owner not filed | [I] |
| F55 branch-loaded-by-name without commit identity | 6299bcda4 | filed: `resources/seon/tasks/seon.db.edn` | [V] |
| F57 stale compiled body on a fresh branch | plan §0 "needs a red proof" | fixed by ab8f218d4; tasks filed in 6a421d96e | [V] |
| F50, F51, F53, F60-F67 | night commits and today's | fixed; tasks filed where the message says so | [V] |
| `every-step-shows-the-file-findings-its-merge-would-refuse` | 6e8b157ed | filed in `resources/seon/tasks/seon.context.edn` | [V rg] |
| `retire-entry-schema` | 1d950f730 follow-up | filed in `resources/seon/tasks/seon.task.edn` | [V rg] |
| LANE_ADOPT use by a lane | handoff note for 73968333e | not filed | [I] |
| A7's 120 s bound, measurement and owner authorization | spec §5 | not done | [V] |

Filed today with a red proof, or converted to one: the turn reds (a05ba6655), the phase tasks (8a9fb77c5), the
chain-5 subject (1d538ec20), the chain-4 and chain-6 subjects (487388073, 606110768).

## Limits of this audit

- I did not run tests. The state of each acceptance test is what its commit message and the status line claim, plus whether the test exists. Run A1-A7 and I0-I3 on a JVM before relying on any of it. [V]
- The patch-status check used `git apply --check`, which needs a clean working tree. The working tree had uncommitted changes, so the forward and reverse results are approximate. The line-presence check is weaker still. [V]
- The contract check is a heuristic. The six hand-checked defns all had contracts, so the count of missing contracts is unknown, not zero. [V]
- The scope-drift counts are keyword matches on commit subjects, not measured effort. They are approximate. [I]
