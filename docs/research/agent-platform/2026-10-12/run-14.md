---
title: "Run 14: the first unattended two-step chain: Prove :satisfied with 0 calls, Implement merged in 5 calls; publication stalled 3 min on a cacheless analysis and committed only after a cleanup write woke db<->flow"
type: research
status: complete (lane run14, 2026-10-13); chain completed; Seon git commit e3e185853; the subject is set back to :held on default
created: 2026-10-13
---

# Run 14: Run 13's chain, rerun unchanged on d7ccec1b6

**Result: accepted, with one caveat about publication.** One launch fact. The Prove step ended `:satisfied` 0.8 s
later with **0 model calls** (F22 fixed by `d7ccec1b6`). Implement forked main 37 ms after that. It fixed only the
map-member branch of `seon.error/message`, using `clojure.string/blank?`. Its proof and reaching tests went green,
and it ended `:done` and **merged on main 20.9 s after launch, in 5 model calls**. The publication's first try
threw a `:seon.fn/dependency-analysis-missing-error` (F28), which left `src/seon/error.clj` written but not
committed, with its lane claim held. It was retried only when my cleanup write (subject back to `:held`) woke
db<->flow 3 min 20 s later. Then it committed **`e3e185853`** (`src/seon/error.clj`, +4 −1), and both branches
(Implement's and the probe's) were deleted.

So this is the **first two-step chain to finish (Prove satisfied, then Implement merged and published)**, with 0
hand acts on the chain after launch. It was **not fully unattended**, though. Publication would have stayed stuck
until some write woke db<->flow, and the write that did was cleanup, not part of the run (F28, F29).

| Measure | Prove (`9008f9c3276a`) | Implement (`3c537ab3f8af`) |
|---|---|---|
| Assignment / agent / branch | `9d2026d3e4a7` / `attempt/9d2026d3e4a7` / `:attempt-9d2026d3e4a7` (the same ids as Runs 12 and 13, F26) | `37bd87f6430b` / `attempt/37bd87f6430b` / `:attempt-37bd87f6430b` |
| Model calls | **0** (no turn on its branch) | **5**: 3 `tool_calls`, 2 `stop` |
| Prompt / cached / completion tokens | 0 | 25,472 / 13,440 (53 %) / 1,256; call 1 3,806 prompt, call 5 6,099 |
| Replies that are tool-call markup or unknown tools | — | **0 / 5** |
| Prose-only "no form" refusals | — | 0 |
| `my.task/refine!` | 0 | **0** |
| `defn` | none | 1: `seon.error/message` (call 5) |
| Scope | — | **in scope**: only the `:seon.error/message` member branch changed (`(and (string? m) (not (str/blank? m)))`). The turn and `ex-message` branches are byte-identical (F17 did not recur), and the docstring was kept |
| End | `:satisfied`, outcome `:closed`, no problem task, branch kept (as designed) | `:done` → merged (`6acd348c-e23c-…`, 1 declaration) → published `e3e185853` → branch deleted |
| Launch → end | 0.84 s | 20.2 s (last call 19:26:57.9, end 19:27:08.8: 10.9 s of proofs and reaching tests after the `defn`) |

## Before launching

- Default pid 74941, booted 19:25:24 UTC from `data/source/d7ccec1b6c24…` (HEAD, which includes F22/F23). Health
  `:degraded` with the same single `:seon.cache/discipline-warning` on `seon.sci.eval/program-context` (46 % bytes,
  33 % hit, load 1,058 ms) as Runs 10 to 13. No open problems. Default was not stopped or reset by this lane.
- Subject `db57ce884739` `:held`; children Prove `9008f9c3276a` (`:step :prove`) and Implement `3c537ab3f8af`
  (`:needs` Prove); no attempts; `seon.task/ready` `#{}`. Gap re-verified in JVM: `(#'seon.error/message
  {:seon.error/message "   "} nil)` → `"   "`. The Prove product (Run 12's table, published `468d24ad2`) is on main.
- **Reaching tests, interpreted, on probe branch `run14-probe`** (`:agent-886ae684ded8`). `(my.program/tests-reaching
  'seon.error/message)` gates 10 tests in `seon.error-test`. The 9 not tagged to Implement ran through
  `(my.test/check {:seon.test/identities <9> :seon.test/rerun? true})`, run `08d094e748cd`. All 9 have
  `:seon.test.member/evaluator :interpreted` and are **green, 39 pass / 0 fail / 0 error**
  (`a-contract-violations-fault-keeps-the-value-that-broke-it` 5/0). The request took 5,282 ms and 4.76 GB against
  1.20 s of member run time, flagged `:slow` and `:large-allocation` (F27 again, unchanged).
- Config: `seon.config/apply!` with `{:seon.config.ai/no-provider false :seon.config.run/max-episode-runs 60}`.
  `seon.config/effective` confirmed both, model `deepseek-flash`.

## Timeline (UTC, 2026-10-12, from transaction instants)

| At | Event |
|---|---|
| 19:26:48.536 | root transacts `[:db/add [:seon.task/id "db57ce884739"] :seon.task/execution :running]` (t 536871264, the only hand act on the chain) |
| :48.577 | Prove attempt `9d2026d3e4a7` written |
| :49.379 | Prove ends `:satisfied`, outcome `:closed` (0 model calls: the fork-point run of its product decided it) |
| :49.416 | Implement attempt `37bd87f6430b` written: `seon.task/ready` saw the `:satisfied` need as met |
| :49.985 | Implement call 1: `(in-ns 'seon.error)` `(my.program/source 'seon.error/message)` `(ns-aliases *ns*)` |
| :51.5 / :54.2 / :55.8 | calls 2-4: `my.program/callers`, `(require '[clojure.string :as str])`, `my.program/search` for "blank", `(str/blank? nil)`, `"   "` and `" x "` probed |
| :57.918 | call 5: `(defn- message …)` with the map member guarded by `(and (string? m) (not (str/blank? m)))` |
| 19:27:08.773 | Implement ends `:done` (proof `a-blank-error-message-is-replaced-…` green, reaching tests green) |
| :09.402 | merged on main as `6acd348c-e23c-55bd-b4ec-0615f5a8263c` (gate green: no red on target) |
| :10.770 | `seon.merge/publish!` → `seon.merge/indexed!` → `seon.fn/index!` → `seon.fn/host-bound-callers` throws `:seon.fn/dependency-analysis-missing-error` ("clj-kondo holds no analysis of … malli.core, sci.core, datahike.db …"). `src/seon/error.clj` is already written on disk, uncommitted, and claimed by lane `merge-37bd87f6430b`. No `:seon.task/refusal` was recorded, because the failure was thrown, not returned. One recorded error, notified |
| 19:27:10-19:30:29 | nothing: no retry. My read-only JVM probes (each records an evaluation transaction on main) did not wake db<->flow |
| 19:30:29.9 | cleanup: config restored; subject set `:held` (t 536871292) |
| 19:30:30.847 | db<->flow pass retries `:publish`: git commit `e3e185853`, `:seon.task/git-commit` written; claim released |
| :30.866 / :30.886 | probe agent and Implement agent admission closed; `:attempt-37bd87f6430b` and `:agent-886ae684ded8` deleted |

## What Implement saw

Its `seon.context/current-state` block held `:seon.context/chain {:subject seon.error/a-blank-…, :before
[{:seon.task/sym seon.error/prove-a-blank-error-message-is-replaced, :seon.task/step :prove}]}`. It also held the
step section `{:seon.task/step :implement … :seon.context/broken #{} :seon.context/new-red #{}}` (F23's new keys).
Its proof was the Prove product with its full source under `:seon.context/proofs`. So the product was visible, as a
proof. The chain entry for Prove carries no outcome, though: `seon.context/step-before` adds `:seon.task/outcome
:merged` and `:seon.context/merged` only for a merge, and says nothing for a `:satisfied` end (F29b). Call 2's
reasoning names the scope rule directly: "The instructions say 'the other branches are unchanged.' So only the map
member branch changes. Let me keep `not-empty` for those."

## Seon git commits from this run

- **`e3e185853`** "Merge seon.error/implement-a-blank-error-message-is-replaced from agent attempt/37bd87f6430b:
  merged on main as 6acd348c-e23c-55bd-b4ec-0615f5a8263c" (trailers `Written: src/seon/error.clj`, `Assignment:
  37bd87f6430b`, `Merge-commit: 6acd348c-…`).

  ```
  src/seon/error.clj | 5 ++++-
  1 file changed, 4 insertions(+), 1 deletion(-)
  ```

The Prove step made no commit (it was `:satisfied`). Its product is Run 12's `468d24ad2` (`test/seon/error_test.clj`,
+34). The compiled `seon.error/message` in default's JVM still answers `"   "` until root's next `start --head`
reloads `e3e185853`.

## Grades

- Hand acts after launch: **0 on the chain**. One cleanup write (the subject back to `:held`) happened to trigger
  the stuck publication. Without it, `e3e185853` would not exist yet.
- Calls per step: Prove **0**, Implement **5** (Run 12's Implement: 2; Run 13's Prove: 60).
- Tool-markup replies: **0 / 5**. `refine!`: **0** (F25 did not recur). Scope: **clean** (F17 did not recur).
- Cost: 25.5 k prompt tokens for the whole chain (Run 13: 1.81 M).

## Frictions

| # | Friction (observed) | Owner | Proposed red proof |
|---|---|---|---|
| **F28 (binding)** | Publication of a `src` file threw `:seon.fn/dependency-analysis-missing-error`. The JVM runs from `data/source/<sha>`, so `seon.fn.analyzer/checkout-source?` judges the working checkout's files (`/Users/sean/src/seon/src/seon/error.clj`) not "the checkout's own declared source", and `seon.fn.analyzer/analysis-cache-root` answers **nil** for them. Measured: `(analysis-cache-root ["/Users/sean/src/seon/src/seon/error.clj"] nil)` → `nil`, but for the same file under `user.dir` → `".clj-kondo/.cache/dependencies/3b5f1e12b6ad"`, and `(seon.fn.analyzer/cached-namespace 'malli.core)` is present. The analysis runs without the dependency cache, so `malli.core`, `sci.core` and the rest read as unanalyzed and `seon.fn/unresolved-macro-refusal` throws. Run 12's published test file escaped it, presumably because its body calls nothing host-bound in those namespaces. The retry succeeded; unverified hypothesis: the first pass already committed the file's reindex before failing in the caller-findings stage of `seon.fn/index!`, so the retry had no caller paths to re-analyze. If so, those caller findings were never refreshed | `seon.fn.analyzer/checkout-source?` / `seon.fn.analyzer/analysis-cache-root` (the publication root `:seon.config.fs/working-root` is the program's checkout even when the JVM runs from an archive) | `seon.merge/publish!` of a merged change to a `src` declaration that calls `malli.core/validator`, published into a working root other than the JVM's `user.dir`, answers `:seon.git/commit` on its first pass with no recorded error |
| F29 | A thrown publication failure left the publication due but not retried. db<->flow derived no new pass for 3 min 20 s, and evaluation recordings on main did not wake it; only a task write did. `publish!` documents refusals as returned and recorded (`:seon.task/refusal`), but this one escaped as an exception. No refusal was recorded, the claim `merge-37bd87f6430b` stayed held, and `src/seon/error.clj` stayed dirty in the shared tree, which would have blocked any lane claiming it | `seon.merge/publish!` (a thrown analysis failure is not converted into its recorded refusal); `seon.cluster.agent/failed-pass!` (it ends `:merge`/`:merge-base` failures, but a `:publish` failure only records the fault, and nothing re-wakes it) | after a `:publish` whose `seon.fn/index!` throws, the assignment holds `:seon.task/refusal` and the lane claim is released (the files are as the write-back left them) |
| F29b | The Implement block's `:seon.context/chain` shows the Prove step with no outcome when it ended `:satisfied` (only `:merged` is rendered) | `seon.context/step-before` | the chain of a step whose need ended `:satisfied` carries `:seon.task/outcome :satisfied` for that need |
| F30 | The recorded error from F28 stays open (`seon.problems/open-faults`, 1 occurrence) after the retry succeeded. Its producer is unchanged, so by the rule it stays open, but no task for it was filed or named | root (file the task for F28) | none beyond F28's |
| F31 | Implement's branch was deleted after publication, and with it its 5 turns, their model attempts and usage. Main's agent entity holds no `:seon.agent/end-calls` / `end-prompt-tokens` (`seon.context/end-summary-tx` did not reach main for a `:done` → merged end). The step's cost does not outlive its branch: this run's numbers were read before deletion | `seon.context/end-summary-tx` and the merge of the end facts (where the summary is lost was not traced) | after a chain step merges and its branch is deleted, main's agent entity holds `:seon.agent/end-calls 5` and its token sums |
| F27 (again) | `my.test/check` of 9 named tests: 5,282 ms, 4.76 GB, against 1.20 s of member run time | `my.test/check` → `seon.test` runner | as Run 13 |
| F26 (again) | The reset reminted the Prove ids of Runs 12 and 13 (`9d2026d3e4a7`) | `seon.task/assign!` id minting | none (observed) |

## Residue on default (disposable)

- **Subject** `db57ce884739` set back to `:held` (cleanup); `seon.task/done?` true; `seon.task/ready` `#{}`.
- **Prove** attempt `9d2026d3e4a7` `:satisfied`/`:closed`; its branch `:attempt-9d2026d3e4a7` is kept (as `d7ccec1b6`
  designs for `:satisfied`).
- **Implement** attempt `37bd87f6430b` `:done`/`:merged`, `:seon.task/git-commit "e3e185853…"`; branch deleted.
- **Probe** branch `:agent-886ae684ded8` (`run14-probe`) retired and deleted.
- **Config** restored to `no-provider true` and `max-episode-runs 100`, verified with `seon.config/effective`.
- **Recorded error** `4db113a6…` (`seon.fn/unresolved-macro-refusal`) open.
- Host Vars in `user` (`conn`, `pdb`, `subj`, `atts`, `bdb`, `ai-atts`, `st`, `turns`, `blk`, `ti`, `txof`, …) were left
  for reading. **RESTART NEEDED** for the compiled `seon.error/message` to carry `e3e185853` (root's batch).
