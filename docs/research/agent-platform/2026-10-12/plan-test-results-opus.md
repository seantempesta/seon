---
title: "Plan (Opus): which tests are red, and which reds a change caused, as Seon data"
type: research
status: independent plan, design round over dig-test-results.md (fada01e55); lane plan-tr-opus, 2026-10-12
basis: HEAD 7fea1f2e7 (one commit past the dig, touching only `seon.sci.eval`). Code read by symbol: `seon.test.runner/result-at-reach`, `newest-results`, `latest-results`, `red-at-current-reach?`, `red-result?`, `reach-digests`; `seon.test/fork-point-red`, `change-reds`, `run`, `run-request`; `seon.turn/record-broken!`, `test-step`, `fork-commit`, `summary`; `seon.context/step-status`, `step-observation`, `base-results`, `designed-red?`, `step-section`, `main-database`; `seon.cluster.branch-evidence/test-evidence`, `verdict`, `branch-evidence`; `seon.cluster.source/red-test-verdicts`; `seon.operator/check!` (script); `bin/lane` evidence. Live probes on default through `mcp__seon__eval_clj` (§7).
---

# Plan: which tests are red, and which reds a change caused

## 0. The design in one paragraph

A test result is already a content-addressed fact: a member records the reach digest (and the evaluator) of what
it ran, so a result recorded anywhere is true wherever that reach digest occurs. "Did my change cause this red?"
is therefore one read: for each test red at the branch head, its status at the branch's **merge base** under the
reach digest it has **in the base** (not the head's: every test the change reaches has a different digest at the
head; probe P3 shows `:same-reach? false` for both reached tests), read with `seon.test.runner/result-at-reach` on
the branch and on main. That read already exists for proofs (M1, `seon.context/base-results`); this plan makes it
the one fork point for every reached red, makes the one existing fallback run (`seon.test/fork-point-red`) keep the
base result it computes instead of throwing it away, and has `branch-evidence`, `bin/seon check` and MCP read the
same function. Main gains results only from runs root already makes (merge acceptance's main side today, the merge
gate after M7, the restart batch's bulk tier if the owner wants totals). No baseline file, no accepted list, no
second classifier. Unknown stays unknown and is shown as such.

## 1. The data

**A run is `:seon.test.run/*`, unchanged.** No new entity. The schema already separates where a run is recorded
from what it tested: `:seon.test.run/branch` ("durable destination branch") and `:seon.test.run/tested-branch`
("branch of the immutable database tested, when different from the result destination"), and each member carries
`:seon.test.member/reach-digest` and `:seon.test.member/evaluator`. Probe P2: one run of two results on a branch is
64 datoms.

**The fork point becomes a status map.** `:seon.context/fork-point` is today `test → boolean` (true = red there),
and its producers disagree on what an unknown base means: `step-observation` defaults a judged test to
`(not (broken s))`, red there; `fork-point-red` merges `(zipmap present (repeat false))`, green there, so a base that
could not be run inside the step limit makes the test **broken**. Neither is "unknown". The new value is
`test → :seon.context/proof-status` at the base (`:green`, `:red`, `:pending`), the enum the head already uses;
absent means the test does not exist at the base (a new test). `step-status` derives four sets from head and fork
point, one rule each:

| Head | Base | Set | Meaning |
|---|---|---|---|
| `:red` | `:green` | `:seon.context/broken` (exists) | the change caused it |
| `:red` | `:red` | red-before (counted, not listed) | red before the change |
| `:red` | `:pending` | `:seon.context/unknown` (new) | nobody knows yet; never green, never the change's |
| `:red` | absent | `:seon.context/new-red` (exists) | a test the change added |

`:seon.agent/broken` and `:seon.agent/new-red` stay the step's record on the agent's row; `:seon.agent/unknown` joins
them (consumer: `step-observation`, which already reads the row).

**One read: `seon.test.runner/fork-point`** `(fork-point databases base tests evaluator) → {test status}`: the base
reach digests of `tests` present in `base` (`reach-digests` on the base commit's value, `seon.db/commit-database`),
then one `result-at-reach` per database in `databases` (the branch, and main read as root's home branch:
`seon.context/main-database` already does this read for costs), keeping members whose
`:seon.test.member/evaluator` equals `evaluator`, newest first. Present with no such result is `:pending`. It writes
nothing and runs nothing, so a render can call it.

**How main gets results.** Only from runs root already makes; whether more is wanted is owner question Q2.
1. Merge acceptance today: `seon.test/change-reds` asks `fork-point-red` with root's world as the execution, so
   once `fork-point-red` records on its execution's own connection (phase T2), main's side of every merge's reds
   is recorded on main, at main's reach digests. M7 replaces `change-reds` with the gate on the merge branch; that
   gate's run is recorded with destination main and tested-branch `merge/<assignment>`, the two attributes made for
   exactly this.
2. The restart batch's bulk tier (`script/seon/central_round.clj`), today on an isolated root and nuked, recorded
   instead as root's runs on default's main (T6, owner Q2).
3. Nothing else: no extra base runs on main (owner, m1-notes). An agent's base runs land on its own branch.

**Runs stay data (`:seon.program/partition :seon.data`).** The reach digest is the link to the program; nothing
has to merge for a result to stay true. AGENTS.md's "The program is entities: … test evidence keyed by exercised
definition digests" and the 2026-09-23 memory say evidence is program; the schema says data. Owner question Q1
prices both; this plan works under either and recommends data.

## 2. "My change caused this red" as one query

For agent A on branch B with merge base C (`seon.turn/fork-commit`, later `:seon.task/merge-base`), test T red at
B's head:

    (get (seon.test.runner/fork-point [B-db main-db] (seon.db/commit-database B-db C) [T] :interpreted) T)
    ;; :green → caused (broken), :red → red before, :pending → unknown, absent → new

Then:
- **Unknown resolves once.** The test step's `fork-point-red` runs only the `:pending` ones, on a branch forked at
  C (exists), and **records the run on the execution's own connection** (today the result is recorded on the child
  branch and lost with `release-context!`, so every later step reruns every unknown base: code read). The next step
  reads it at the same base digest. Cost is proportional to the reds the change reaches, once per (test, base
  digest), never per step and never the suite.
- **Unreached tests are never judged**: their digest equals the base's, so no request reruns them and they cannot be
  the change's.
- **Evaluator is part of the key.** Selection already reuses only runs of the requesting kind (schema
  `:seon.test.run/evaluator`), and 205 tests were red only interpreted (`interpreted-tier.md`). A compiled base never
  decides an interpreted head: it reads `:pending`. Owner Q3.
- **Over-bound or unfinished**: `:seon.test/result-unknown` (exists) maps to `:pending`, at head or base. Pending is
  never red and never green.
- **Flaky**: the newest execution at the key decides; no rerun loop (owner: rare edge cases get no machinery). A
  flaky test is its own defect with its own task. Owner Q4 offers the one-read alternative.
- **Designed reds** (`:seon.error/unimplemented`, tagged to an open task): on main they are red at the base, so an
  agent sees them as red-before, never broken; a Prove step's own product is new-red, judged by `designed-red?`
  (exists). No new class.
- **Deleted tests**: absent at the head, so never red there; the block names none.
- **Failure identity** is not compared: red at both is red-before even if it fails differently. The entry still
  shows the head's failing assertions.

## 3. How it renders, and who reads it

**Reply** (test step summary, `:seon.turn.loop/test-summary`): today `{:seon.turn.loop/reaching {:seon.test/passed?
false :seon.turn.loop/red [t]}}` with no origin (probe P1). Added: `:seon.turn.loop/broken` (exists, today only when a
task is held) and `:seon.turn.loop/unknown`, both vectors, absent when empty, and the red-before count
`:seon.turn.loop/red-before`. About 10 tokens plus about 15 per listed test.

**Current-state block**: `step-section` already lists `:seon.context/broken-tests` and `:seon.context/new-red-tests`
with failing assertions; it gains `:seon.context/unknown-tests` (symbol only) and shows for every agent, not only a
task holder. A clean step adds 0 tokens; 3 broken with one failing assertion each and 2 unknown is about 250 tokens.
The section carries its form `(my.test/reds)`: each red of the agent's last step with its head result, its base
status and the base run's id, the one place to dig in.

**The same answer for our lanes**, one function, three entrances:
- MCP `eval_clj`, sci mode as the lane's agent id: `(my.test/reds)`, or the block.
- `bin/lane commit --branch-evidence AGENT`: `test-evidence` sorts a reaching red by `fork-point` at the branch's
  merge base: `:red` → `:seon.source/red-before`, which proves (a positive fact, named in the trailer
  `Branch-evidence-red-before:`); `:green` → `:seon.source/broken` refuses; `:pending` → unproven, naming the test and
  the form. `--accept-red-before` and `:seon.source/accepted-red-before` are deleted: the stored base replaces the
  hand list. Today the head side is `red-at-current-reach?` on the cluster head, which holds zero runs, so
  red-before can never be found (probe P0) and the flag is the only way through.
- `bin/seon check`: its throwaway JVM's reds (compiled, a platform run) are judged over the live root's prepl, as
  `branch-evidence` is, by `fork-point` on main at HEAD with evaluator `:compiled`; a red at both is red-before and
  does not refuse; unknown refuses as today. That retires the task
  `seon.operator/bin-lane-commit-cannot-accept-a-test-already-red-on-head`.

## 4. What it deletes

| Deleted | Converted caller | Phase | Lines |
|---|---|---|---|
| boolean fork point and the two disagreeing defaults (`(not (broken s))`, `(repeat false)`) | `step-status`, `step-observation`, `record-broken!`, `fork-point-red`, `change-reds`, context_test rows | T1 | ≈ −6 |
| `seon.context/base-results` body (now a `fork-point` call) | `step-observation` | T1 | ≈ −10 |
| the lost base run in `fork-point-red` (recorded on the released child) | — | T2 | ±0 |
| `seon.test.runner/red-at-current-reach?` | `branch-evidence/test-evidence`; task must-read in `resources/seon/tasks/seon.turn.edn`; `docs/seon/schedule/cards/ROOT-AGENT.md` line naming it | T4 | −11 |
| `--accept-red-before`, `bin/lane evidence … -- TEST`, `:seon.source/accepted-red-before` (schema, `verdict`, `branch-evidence`) | `bin/lane`, `seon.source.edn`, `branch_evidence_test` (the "root's acceptance" testing block), `RF-2.md` and the ledger row | T4 | ≈ −25 |
| task `seon.operator/bin-lane-commit-cannot-accept-a-test-already-red-on-head` | `seon.operator.edn` | T5 | −5 |
| the central round's `OUT` text file (`record!` spit) | `central_round.clj` prints run ids; results are the runs | T6 | ≈ −4 |
| hand diffing of reds in commit messages and `tmp/` | the block, `my.test/reds`, the run on main | T3–T6 | — |

Added: `fork-point` ≈ 15, `:seon.context/unknown` ≈ 4, `my.test/reds` ≈ 12, check verdict ≈ 12, schema keys ≈ 6.
Net source about −10 lines; tests about +40 (rows and three deftests). `change-reds` is **not** deleted here: M7
deletes it with its gate; until then it gains main's recording for free (T2).

## 5. Phases (about fifteen minutes each; HEAD boots after each; red proofs are red on HEAD 7fea1f2e7)

| # | Slice, owned files | Red proof (data assertion) | Cost bound (`seon.profile/measure`) |
|---|---|---|---|
| T1 | **The fork point is a status map; unknown is its own set.** `seon.test.runner/fork-point` (new); `step-status` derives `:seon.context/unknown`; `step-observation` default and `base-results` read `fork-point`; `fork-point-red` returns statuses (no result → `:pending`); `record-broken!` and `change-reds` read `= :red`. Files: `src/seon/test/runner.clj`, `src/seon/context.clj`, `src/seon/test.clj`, `src/seon/turn.clj`, `resources/seon/schemas/seon.context.edn`, `test/seon/context_test.clj`, `test/seon/test/runner_test.clj` | `seon.context-test/each-step-kind-is-done-by-its-one-rule`: every row converted to statuses, plus row `{p green r red}` / `{p :green r :pending}` → `:working`, unknown `#{r}`, broken `#{}` (red today: boolean schema, no `:seon.context/unknown`). `seon.test.runner-test/the-fork-point-reads-a-base-result-at-the-base-digest` (new, beside `a-result-at-the-base-reach-digest-survives-later-runs`): a red at the head's digest and a green at the base digest → `{t :green}`; an interpreted-only base for a compiled ask → `{t :pending}` | `fork-point` for 30 tests warm < 5 ms (P3: 4.3 ms for 2 tests incl. both digest sets; P4: `result-at-reach` 0.44 ms for 30) |
| T2 | **The base run is kept where the next step reads it.** `fork-point-red` passes `:seon.test/recording-connection` = the execution's connection, `:seon.test.run/tested-branch` = the child; reads base via `fork-point` on `[execution-db main-db]`. Files: `src/seon/test.clj`, `test/seon/test_test.clj` | `seon.test-test/a-fork-point-run-is-read-at-the-next-step-without-running` (new): two calls for one red; the second answers `:red` with `(calls-of 'seon.test/run)` 0 (red today: the child's run is released, the second call runs again). Row in the existing `change-reds` long test (`test_test.clj` 362): after the call, `result-at-reach` on main holds the test at main's digest (red today: `{}`) | second call < 10 ms; first call one test run (today's cost) |
| T3 | **Every agent sees its reds' origin.** `record-broken!` judges reached reds with or without a held task and writes `:seon.agent/unknown`; summary gains `broken`, `unknown`, `red-before`; `step-section` gains `:seon.context/unknown-tests` for every agent; `my.test/reds`. Files: `src/seon/turn.clj`, `src/seon/context.clj`, `src/my/test.clj`, `resources/seon/schemas/{seon.agent,seon.turn.loop,seon.context}.edn`, `test/seon/context_test.clj` | `seon.context-test/broken-tests-show-with-their-failures` (exists, long) gains: an agent holding **no** task whose change turns a base-green test red has it in `:seon.context/broken-tests`, and `(my.test/reds)` maps it to `{:base :green}` (red today: probe P1's block shows only `:seon.context/turns`) | block read < 20 ms warm (P5: 14 ms today); test step overhead unchanged |
| T4 | **`--branch-evidence` reads the same answer; the accepted list goes.** `test-evidence` sorts by `fork-point` at the branch's merge base; `:seon.source/broken` refuses, red-before proves; delete `red-at-current-reach?`, `:seon.source/accepted-red-before`, `--accept-red-before`, `evidence -- TEST`. Files: `src/seon/cluster/branch_evidence.clj`, `src/seon/test/runner.clj`, `resources/seon/schemas/seon.source.edn`, `bin/lane`, `test/seon/cluster/branch_evidence_test.clj`, `resources/seon/tasks/seon.turn.edn`, the two cards and ledger row. Host-bound slice (`bin/`): `--test seon.cluster.branch-evidence-test` | `seon.cluster.branch-evidence-test/a-branch-proves-its-files-only-with-current-green-evidence`: "a test red on H too is red before" now asserts `(:seon.source/proven? answer)` **true** with no accepted key, and a new assertion: a test green at the base and red on the branch is in `:seon.source/broken` and refuses (red today: proven? false until accepted) | `branch-evidence` answer < 1 s end to end over the prepl (today 23–90 ms per read in the dig) |
| T5 | **`bin/seon check` judges its reds against main.** `seon.operator/check-verdict` (pure: the throwaway run's results and main's `fork-point` → refused?, red-before, unknown); `check!` asks the live root for the statuses. Files: `script/seon/operator.clj`, `test/seon/operator_test.clj`, `resources/seon/tasks/seon.operator.edn` | `seon.operator-test/a-check-red-main-holds-red-does-not-refuse` (new, table over `check-verdict`: `{t :red}` → not refused, red-before `[t]`; `{t :green}` → refused; `{t :pending}` → refused, unknown `[t]`; red today: no such function) | the extra prepl read < 200 ms |
| T6 | **(Owner Q2 = B or C) The bulk tier records on main.** `central_round.clj` runs as root on default with main as the recording connection; prints run ids, no `OUT` text. Files: `script/seon/central_round.clj`, `test/seon/central_round_test.clj` (new) | `seon.central-round-test/a-batch-records-its-results-on-main`: one batch of two fixture tests on a test world; `result-at-reach` on that world's main holds both at their digests (red today: the script writes text only) | storage per result measured and reported (P2 bound: ≤ 32 datoms/result, ≈ 92k per 2,872-test round) |

Order: T1 → T2 → T3; T4 and T5 after T2 (they read recorded bases); T6 when the owner answers Q2. File collisions
with the hybrid: `src/seon/context.clj` (M7, M14) and `src/seon/turn.clj` (M8, M10, M11) — root sequences T1 and T3
before M7 so M7's gate inherits `:seon.context/unknown`. T1–T3 are Opus lanes (the one path's judgment); T4, T5
platform slices; T6 Sonnet.

## 6. Owner questions

**Q1. Are runs program (merge) or data (never merge)?**
- **A. Data (recommended).** Root records on main the runs it makes itself; agents' runs stay on their branches. Cost:
  0 extra runs; main grows by root's runs only. Sacrifice: a sibling agent re-pays a base run another agent already
  paid on its own branch. Correct AGENTS.md "The program is entities" to say evidence is data keyed by digests.
- B. Program. Runs three-way merge (added immutable entities never conflict). Main inherits every agent's evidence
  free. Cost: one run per eval request lands on main (most superseded, no consumer), partition change, and the merge
  gate trusts agent evidence instead of running.
- C. Merge only the runs at the merged head's digests. Main gets only current evidence; costs a selection rule in
  `merge-tx` (≈ 20 lines) the hybrid does not have.

**Q2. Should main hold the whole suite's results?**
- A. No round: main fills from merge acceptance and gates (T2, M7); unreached tests stay unknown on main, which no
  agent's question needs. Cost 0. Sacrifice: no main totals for root.
- **B. Recommended: the restart batch's bulk tier, already run, recorded on default's main** instead of an isolated
  root (T6). Cost: no extra run; about 61 min of load on default during the batch (central round: 114 batches,
  2,094 s pass 1) and ≈ 92k datoms per round. Gives root's totals and a compiled base for `bin/seon check`.
- C. B, plus the round run interpreted as well, so agents' bases are free. Cost: a second ≈ 60-minute round per batch.

**Q3. Does the evaluator key the base?**
- **A. Yes (recommended):** equal `:seon.test.member/evaluator` or `:pending`. Correct for the 205 interpreted-only
  reds; agents pay one base run per unknown red, once.
- B. No: any evaluator decides. Free bases from a compiled round; wrongly "caused" for interpreted-only reds.

**Q4. Flaky tests.**
- **A. Newest execution decides (recommended);** the flake is its own task. Cost 0.
- B. Two executions at the same key that disagree read `:pending`. Cost: `newest-results` keeps the distinct outcomes
  per key (one more projection of the read it already makes, ≈ 5 lines).

## 7. Evidence (2026-10-12, default, `runtime_status` **faulted**: 2 open faults, 2 agent-error signatures, 27 errored

receipts, 5 untested-hot warnings, heap 1.03 GB of 4 GB; my agent `plan-tr-opus`, SCI on its own branch, retiring at commit)

- **P0** (jvm, root, read-only): main holds 0 runs and 0 members; 41 agent branches, 2 openable with runs
  (`:interpreted` members). The dig's 37/7/33 has drifted; the conclusion (main empty) holds.
- **P1** (sci, own branch): `(defn tr-f [] 1)`, deftests `tr-f-is-one` and `tr-f-is-two`; then `(defn tr-f [] 2)`.
  Reply `{:seon.turn.loop/reaching {:seon.test/passed? false :seon.turn.loop/red [scratch.trprobe/tr-f-is-one]}}`:
  no origin. `(seon.context/current-state)` → only `:seon.context/turns` (no task held, nothing judged).
- **P2**: the branch's run of 2 results = 64 datoms; main's `result-at-reach` at the branch's digests → `{}`.
- **P3** (the query, jvm read of the branch, base = its commit before the change, `6acc7f1c…`):
  `tr-f-is-one {:head :red :base :green :same-reach? false :class :caused}`, `tr-f-is-two {:head :green :base :red
  :same-reach? false}`; 4.32 ms, 4 MB, 28 reads end to end through `seon.profile/measure`.
- **P4** (main, 2,872 tests): `reach-digests` cold for the whole program 3.0 s and 5.0 GB (the failed first request:
  its `:seon.cost/alloc-mb` 5012), which reconciles the dig's three figures to this JVM; a random 30 cold 484 ms /
  840 MB, the same 30 again 2.76 ms; one test cold 79 ms / 142 MB; `result-at-reach` for 30 tests 0.44 ms.
- **Findings for root to file** (outside this assignment's files): (a) `reach-digests`' cache is keyed by the
  requested set, so a cached whole-program answer does not serve a 30-test subset (484 ms recomputed; see the filed
  `seon.test.runner/verified-recomputes-reach-digests-for-the-whole-program`); (b) the test step costs 541–555 ms and
  585–927 MB for two tests whose bodies took 24–29 ms (P1's `:step` and `:tests` costs), over 10× the work;
  (c) the open faults above.
