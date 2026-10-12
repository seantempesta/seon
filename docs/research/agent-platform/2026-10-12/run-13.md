---
title: "Run 13: the Prove step found its product already on main, could never end, and spent the 60-call cap; Implement never started"
type: research
status: complete (lane run13, 2026-10-13); chain not completed; the subject is set back to :held on default
created: 2026-10-13
---

# Run 13: the rerun of Run 12's two-step chain

**Result: not accepted. The chain stopped at its first step, for a new reason.** One launch fact and zero hand acts
after it. The Prove step was correct from call 3 on: it said "The product test already exists", confirmed it red by
design, and wrote nothing. The lifecycle has no end for that case, though. `seon.context/step-status` judges a
`:prove` step `:done` only when its new reds are exactly its product, and the product was already red at the fork
point (main holds Run 12's published table, `468d24ad2`). So the step stayed `:working`. The model kept announcing it
was done, 33 times in prose that `seon.turn` refused as "Your reply had no form". It hit the cap at call 60 and ended
`:capped` 118 s after launch. The Implement step never became ready. It never forked and never touched
`seon.error/message`. Nothing merged, nothing was published, and there is no new Seon git commit. F13's fix
(`304fd940d`) was not reached, but the pre-launch check shows it works (below).

The question the assignment asked: the Prove step **works again**. It does not end satisfied or done at once. The
rule says this on purpose: the table row "a product its fork point already held is not new" in
`seon.context-test/each-step-kind-is-done-by-its-one-rule` asserts `:working`. There is also no `:satisfied` for
`:prove`, unlike `:implement`. `seon.turn/prove-turn` skips the model only for `:satisfied` (F22).

| Measure | Prove (`9008f9c3276a`) | Implement (`3c537ab3f8af`) |
|---|---|---|
| Assignment / agent / branch | `9d2026d3e4a7` / `attempt/9d2026d3e4a7` / `:attempt-9d2026d3e4a7` (the same ids as Run 12; the reset reminted them) | none |
| Model calls | **60 (the cap)**: 7 `tool_calls`, 53 `stop` | 0 |
| Call that recognised the product | call 3 ("The product test already exists") | — |
| `defn` / `deftest` | none (correct: nothing to write) | — |
| Replies that are tool-call markup or unknown tools | **0 / 60** | — |
| Prose-only replies refused "Your reply had no form" | **33 / 60** | — |
| Other evaluation errors | 4 × `Unable to resolve symbol: seon.error/implement-a-blank-error-message-is-replaced` (the task symbol used as code) | — |
| `my.task/refine!` | **1**, at 19:09:03.967: rewrote its own step's instructions with text meant for the successor (F25) | — |
| Prompt / cached / completion tokens | 1,811,753 / 1,667,712 (92 %) / 14,363; call 1 3,220 prompt, call 60 52,253 | — |
| End | `:capped` at call 60, 19:10:33; problem task `7353c7c72c11` `:held` | never ready |
| Merge / Seon git commit / branch deleted | none / none / no (kept, as `:capped` ends keep it) | — |

Hand acts after the launch fact: **0**. Afterwards: config restored and the subject set back to `:held`. These are
cleanup and are not counted.

## Before launching

- Default pid 71740, booted 19:06:24 UTC from `data/source/8be1aba4fc63…` (HEAD, which includes `304fd940d`).
  Health `:degraded` with the same single `:seon.cache/discipline-warning` on `seon.sci.eval/program-context` (52 %
  of bytes, 44 % hit, load 1,195 ms) as Runs 10 to 12. No open problems. Default was not stopped or reset by this
  lane.
- Subject `db57ce884739` `:held`, children Prove `9008f9c3276a` (`:step :prove`) and Implement `3c537ab3f8af`
  (`:needs` Prove), no attempts. The reset dropped Run 12's attempt rows and outcomes. `seon.task/ready` answered `#{}`
  and over a `d/with` of `:running` `#{"9008f9c3276a"}`.
- Gap re-verified in JVM: `(#'seon.error/message {:seon.error/message "   "} nil)` returned `"   "`.
- **Reaching tests, interpreted, on probe branch `run13-probe`** (`:agent-092012acb66c`). `(my.program/tests-reaching
  'seon.error/message)` gates 10 tests in `seon.error-test`. The 9 not tagged to the Implement step ran with
  `(my.test/check {:seon.test/identities … :seon.test/rerun? true})`, run `605d5d060103`. Every member is
  `:interpreted` and **all 9 are green, 39 pass / 0 fail / 0 error**, including
  `a-contract-violations-fault-keeps-the-value-that-broke-it` at 5/0 (Run 12's blocker was 5/1). F13 is fixed. The
  request took 5,226 ms and allocated 4.35 GB, while the members' own run time sums to 1.17 s (F27). The tagged proof
  `a-blank-error-message-is-replaced-by-the-sentence-that-describes-the-source` ran red by design, 12 pass / 6 fail,
  interpreted. The probe branch was then retired (`:my.agent/retiring? true`).
- Config: `seon.config/apply!` with `{:seon.config.ai/no-provider false :seon.config.run/max-episode-runs 60}`.
  `seon.config/effective` confirmed both, model `deepseek-flash`.

## Timeline (UTC, 2026-10-12)

| At | Event |
|---|---|
| 19:08:35.6 | root transacts `[:db/add [:seon.task/id "db57ce884739"] :seon.task/execution :running]` (t 536871263, the only hand act) |
| 19:08:36.050 | Prove call 1: `(my.program/source 'seon.error)` `(my.program/source 'seon.error-test)` |
| :38.8 | call 3: "The product test already exists. Let me check its current status" |
| :40-:50 | calls 4-8: runs the test (`clojure.test/run-test-var`: fail 6, error 0), checks its tag and the must-read, first "My step `:prove` is complete" |
| 19:09:03.967 | `(my.task/refine! {:seon.task/instructions "Make the table green by changing seon.error/message: … (str/blank? ...) …"})`: `seon.task/adopt-tasks-tx` on its branch replaces the **Prove** step's instructions (t 536871328) |
| :04-19:10:33 | calls ~14-60: alternating `(seon.context/current-state)`, `assert`s on the tag and the `:red` status, and prose-only "My step `:prove` is complete" replies (33 refused as no-form). The state block answers `:seon.task/status :working` every time |
| 19:10:33 | call 60; attempt ends `:capped`; problem task `7353c7c72c11` ("ended capped. Nothing was merged to main; its branch attempt-9d2026d3e4a7 is kept") |
| 19:11:41 | still no Implement attempt; `seon.task/ready` `#{}`; Prove and Implement step tasks hold no execution or outcome |
| 19:12:28 | cleanup: config restored, subject `:held` |

## What the step saw

The Prove step's `(seon.context/current-state)` block (5,545 characters) held `:seon.context/step {:seon.task/step
:prove, :seon.task/status :working, :seon.context/product-tests [{… :proof-status :red, six :fail failures}]}`. It
held no reason why the step was still working: nothing said that the new reds were empty, or that the product was
already red at its fork point. The model read `:working` beside its own red product 13 times and concluded each time
that its work was done. That conclusion was correct. Success has no verb, so the model had no move left, and every
later call was waste.

The `:seon.task/instructions` the block shows from call ~14 on are the model's own `refine!` text, not the Prove
step's instructions in `resources/seon/tasks/seon.error.edn` (F25).

## Frictions

| # | Friction (observed) | Owner | Proposed red proof |
|---|---|---|---|
| **F22 (binding)** | A `:prove` step whose product is already on main, red by design, cannot end. `step-status` judges it `:working` because `new-red` (reds absent at the fork point) is empty, not equal to the product. `:prove` has no `:satisfied`, so `seon.turn/prove-turn` opens model turns until the cap. The step's need is never `:merged`, so its successor never forks. This happens whenever a reset or re-ingestion re-creates step facts while the files already carry the product, as here: Run 12's published test survived the reset but its outcome did not | `seon.context/step-status`; `seon.task/ready` (does a `:satisfied` need count as met, like `:merged`?) | In `seon.context-test/each-step-kind-is-done-by-its-one-rule`, change the row "a product its fork point already held is not new" (`[:prove #{} #{'tq.rule/s} {'tq.rule/s red} {'tq.rule/s true} …]`) to `:satisfied`: the product is nonempty, every product test is red by design at the head and was red at the fork point, the step's own proofs are green and nothing is broken. That row is red today. At the turn level: a Prove attempt whose product is on main makes 0 model calls (`seon.turn/prove-turn`) and its successor becomes ready |
| F23 | The step block says `:working` without the reason: `:seon.context/new-red` and `:seon.context/broken` are computed by `step-status` and dropped from the block, so an agent cannot see what keeps it from done | the step section of `seon.context/current-state` | the block of a `:working` `:prove` step whose product is at its fork point carries `:seon.context/new-red #{}` beside `:seon.context/product-tests` |
| F24 | 33 of 60 replies were prose only ("My step `:prove` is complete …"), each refused "Your reply had no form". These are not write refusals, so `:seon.config.agent/write-refusal-bound` (3) never parked the agent | root cause F22. A model with nothing left to do and no verb for it. Not a fence | none: it goes away with F22 |
| F25 (new) | `my.task/refine!` from the Prove step replaced **its own** step's instructions with instructions written for the Implement step ("Make the table green … `(str/blank? ...)` …"). Had the step merged, main's Prove task would read as an Implement task | `my.task/refine!` (it refines the held task; the docstring and the chain block do not say that a successor's instructions are out of reach) | none yet (observed once). Note: the text itself was good guidance for Implement, and it is the same `str/blank?` F17 asked for |
| F26 (run method) | The reset reminted the same assignment, agent and branch ids as Run 12 (`9d2026d3e4a7`, `:attempt-9d2026d3e4a7`), so two runs' evidence shares names. The run setup also missed that the product was already on main: Run 12 recommended checking reaching tests, but not that a chain's Prove product is absent at the fork point | run setup; id minting by `seon.task/assign!` (deterministic from the task) | none (observed) |
| F27 | The pre-launch `my.test/check` of 9 named tests took 5,226 ms and allocated 4.35 GB, against 1.17 s of member run time; the reply flagged it `:slow` and `:large-allocation` | `my.test/check` → `seon.test` runner (named policy, rerun) | a `seon.profile/measure` on `(my.test/check {:seon.test/identities <these 9> :seon.test/rerun? true})` on a probe branch: allocation under 1 GB, time under 2 s |
| F19 (again) | The proof failures in the block print `#object[java.util.regex.Pattern 0x37e6fefd]`, and a JVM reply printed a `java.time.Instant` as `#object[…]` | the value printer (general type improvement) | Run 11 F11's proposal, plus a Pattern printing as `#"\s*"` and an Instant as `#inst` |

The other grades could not be taken. Implement's scope (F17's two `ex-message` branches, the regex instead of
`clojure.string/blank?`), Implement's call count, its merge and publication of `src/seon/error.clj`, and its branch
deletion were never reached.

## What the next run changes

- Fix F22 at `seon.context/step-status`, and decide whether `:satisfied` meets a `:needs` edge in `seon.task/ready`
  (the M10 fork-after-merge rule reads `:merged`). Then rerun this chain unchanged: the product is on main, so the
  Prove step should be `:satisfied` with 0 calls and Implement should fork main at once. That is the cheapest
  possible test of F22 plus the full Implement path Run 12 could not merge.
- Alternatively, to measure Implement now without F22, choose a fresh subject whose Prove product does not exist yet.
  Its reaching tests must be green interpreted on main, checked as above.

## Residue on default (disposable)

- **Subject** `db57ce884739` set back to `:held` (cleanup); `seon.task/ready` answers `#{}`.
- **Prove** attempt `9d2026d3e4a7` `:capped`. `:attempt-9d2026d3e4a7` and `:base/attempt-9d2026d3e4a7` are kept, with
  the refined instructions on the branch only. Problem task `7353c7c72c11` `:held` on main.
- **Probe** branch `:agent-092012acb66c` (`run13-probe`) retiring; root deletes it once nothing on it is unmerged.
- **Config** restored to `no-provider true` and `max-episode-runs 100`, verified with `seon.config/effective`.
- **Seon git commits from this run:** none.
- Host Vars in `user` (`conn`, `pdb`, `all-calls`, `ts`, `evs`, `cs`, helpers `atts`, `atts2`, `turns`, `calls`,
  `bconn`) were left for reading.
