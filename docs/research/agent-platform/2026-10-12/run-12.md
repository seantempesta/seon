---
title: "Run 12: the first two-step chain ran Prove → merge → publish → Implement → green in 34 s and 10 calls, then stalled for root on a reaching test already red on main"
type: research
status: complete (lane run12, 2026-10-13); Prove merged and published, Implement green on its branch but not merged; the subject is left :held on default
created: 2026-10-13
---

# Run 12: the first two-step chain (H4 acceptance)

**Result: not accepted, one step short.** One launch fact, zero hand acts after it. The Prove step designed its red
table in 8 calls, ended by itself, merged, published Seon git commit `468d24ad2` (`test/seon/error_test.clj`, +34) and
its branch was deleted. The Implement step forked main, read the merged Prove test in its `:seon.context/chain`, fixed
`seon.error/message` on call 2 and turned the proof green (18/0). Its merge was then refused with
`:seon.source/red-on-target-error`: the reaching test
`seon.error-test/a-contract-violations-fault-keeps-the-value-that-broke-it` is red under the interpreted evaluator on
main too (5 pass / 1 fail), and the merge rule hands that to root. The attempt ended `:error` at 18:51:49.8 UTC, 34 s
after launch. No Seon commit holds `src/seon/error.clj`.

The stall is not in the chain machinery. Every H4 mechanism worked (M7 designed reds merged, M10 the successor forked
main after its need merged, publish, branch deletion, chain context). It stopped at the gate's documented "a test
already red on main stops it for root" on a red that has been known since the 2026-10-11 interpreted census (class
`:U`) with no task (F13).

| Measure | Prove (`9008f9c3276a`) | Implement (`3c537ab3f8af`) |
|---|---|---|
| Assignment / agent / branch | `9d2026d3e4a7` / `attempt/9d2026d3e4a7` / `:attempt-9d2026d3e4a7` | `37bd87f6430b` / `attempt/37bd87f6430b` / `:attempt-37bd87f6430b` |
| Model calls | **8** (4 `tool_calls`, 4 `stop`) | **2** (2 `tool_calls`) |
| First `defn` / `deftest` | `deftest` on **call 8** (no stub needed) | `defn- message` on **call 2** |
| Replies that are tool-call markup or unknown tools | 0 / 8 | 0 / 2 |
| Evaluation errors | 1 (`my.program/search :text "doseq [["`, contract error on argument 0) | 0 |
| Prompt / cached / completion tokens | 61,486 / 42,752 (70 %) / 1,288 | 8,443 / 2,176 (26 %) / 589 |
| First-call prompt (provider) / estimate (system + user) | 3,165 / 2,163 + 1,208 | 3,956 / 2,011 + 2,332 |
| End | `:done` by itself (designed reds) | proofs green, then `:error` (merge refused) |
| `:seon.task/outcome` / merge commit / Seon git commit | `:merged` / `6acd2c36-aa69-…` / **`468d24ad2`** | none / none / none |
| Branch deleted | yes | no (open merge request) |
| `my.task/refine!` | never | never |

Hand acts: **0** after the launch fact (1 before: `:running` on the subject). Total 10 calls, 69,929 prompt tokens,
1,877 completion tokens.

## Runtime and subject check (before running)

- Default pid 68655, booted 18:48:36 UTC, source root `data/source/969555d6da16034e94c425de402962e5f6dae399` (HEAD).
  Health `:degraded` with the same single `:seon.cache/discipline-warning` on `seon.sci.eval/program-context` as Runs
  10 and 11 (52 % of bytes, 33 % hit, load 934 ms). No open problems. Default was not stopped or reset.
- Subject `db57ce884739` (db 508377) `:held`; children Prove `9008f9c3276a` (`:step :prove`) and Implement
  `3c537ab3f8af` (`:needs` Prove), no attempts. `seon.task/ready` answered `#{}`, and over a `d/with` of `:running`
  on the subject `#{"9008f9c3276a"}` (the Prove step only).
- `seon.task/effective` of each step answered the subject's intent plus the step's own instructions.
- Gap re-verified in JVM: `(#'seon.error/message {:seon.error/message "   "} nil)` returned `"   "`.
- Config: `seon.config/apply!` with `{:seon.config.ai/no-provider false :seon.config.run/max-episode-runs 60}`;
  `seon.config/effective` confirmed both, model `deepseek-flash`.

## Timeline (UTC, 2026-10-12)

| At | Event |
|---|---|
| 18:51:15.643 | root transacts `[:db/add [:seon.task/id "db57ce884739"] :seon.task/execution :running]` (t 536871259, the only hand act) |
| :15.849 | Prove call 1: `(in-ns 'seon.error)` `(my.program/source 'seon.error/message)` `(my.program/source 'seon.error-test)` |
| :17.6-:24.1 | calls 2-6: sources of three neighbouring tests; `(my.program/search :seon.task/intent)`; `(in-ns 'seon.error-test)`; five probes of `#'seon.error/message` (blank, empty, padded, blank with a transition, nil with an ex-info). Call 3 re-reads `seon.error-test` it read on call 1 |
| :25.5 | call 7: `(my.program/search :text "doseq [[")`, refused (argument 0 must be a database value) |
| :26.9 | **call 8: the `deftest`**, six rows, tagged `seon.error/implement-a-blank-error-message-is-replaced`. Branch test run: 12 pass / 6 fail (designed red) |
| by :36 | Prove ends `:done`, merges (`6acd2c36-aa69-…`, 1 merged declaration), publishes git `468d24ad2` "Merge seon.error/prove-… from agent attempt/9d2026d3e4a7", branch deleted |
| :36.422 | Implement assignment forked main (merge base `6acd2c37-b3b2-…`); call 1: `(in-ns 'seon.error)` `(my.program/source 'seon.error/message)` |
| :38.080 | **Implement call 2: `defn- message`** with a `non-blank` local. Proof run: **18 / 0** (t 536871280) |
| :48.5 | the target-side check runs the reaching test on main, interpreted: `a-contract-violations-fault-keeps-the-value-that-broke-it` 5 / 1 |
| :49.814 | merge refused `:seon.source/red-on-target-error` "Every test the merge leaves red is already red on the target; root decides." Attempt `:error`, reason the same sentence. One root-to-root message (printed EDN) |

## The products

- **Prove's table** (published in `468d24ad2`): six rows of `(#'seon.error/message source failure)` → expected
  answer: whitespace-only and empty members fall to the unclassified sentence, a blank member lets the transition
  sentence and the root cause answer, a padded real message and a real message over the turn branches are returned
  unchanged. Each row asserts `string?`, not blank, and `=`. It was designed from the five probes on calls 4-5, so
  every expected value is what the current branches would answer once the member is ignored.
- **Implement's fix** (on `:attempt-37bd87f6430b` only): a `non-blank` local `(when (and (string? s) (not
  (re-matches #"\s*" s))) s)` replaces `not-empty` for the message member, and also for both `ex-message` reads. The
  instructions said "the other branches are unchanged" (F17).

## First-call contexts (copies for the Haiku review)

- `docs/research/agent-platform/2026-10-12/run-12/prove-first-call.md`
- `docs/research/agent-platform/2026-10-12/run-12/implement-first-call.md`

Both were rebuilt after the fact with `seon.context/model-context` at the branch commit of the first call's
`:seon.ai.attempt/basis-t` (found by `datahike.versioning/branch-history`; the Prove branch was already deleted but its
commits were still in the history), laid out by `seon.ai/messages` `:chat`. The Implement block holds
`:seon.context/chain` with the Prove step `:seon.task/outcome :merged` and its merged test under
`:seon.context/merged`, the red proof with six `:seon.context/proof-failures`, and `:seon.context/start` `(in-ns
'seon.error)`. The model read only `seon.error/message`'s source before its fix: nothing was rediscovered (chain 8: 22
calls before the first defn).

## Frictions

| # | Friction (observed) | Owner | Proposed red proof |
|---|---|---|---|
| **F13 (binding)** | The change reaches `seon.error-test/a-contract-violations-fault-keeps-the-value-that-broke-it`, which is red interpreted on main: its first assertion `(= 1 (:seon.instrument/registered (instrument/apply! {:seon.instrument/changed-identities #{[:seon.fn/sym 'seon.error-test/wide-return-narrow-violation]}})))` answers 0 under SCI, because the test arms a Var the interpreter does not hold as a compiled Var. It has been red that way since the 2026-10-11 interpreted census (`interpreted-census-after-205.edn`, class `:U`) with no task, so the merge stalled for root | the test (it instruments a compiled Var, so its host-bound status should come from its declaration, `seon.fn/host-bound?`, and the gate should run it compiled); and run setup: the chain subject was filed without checking the tests reaching `seon.error/message` on main | the test itself under `my.test/check` on an agent branch, interpreted: red today (5/1), green once it runs where its Var is armed. File as a task in `resources/seon/tasks/seon.error.edn` |
| F14 (new) | The refusal names `:seon.source/refused-test-run "b0289b2daef1"` on `:test.run/branch :merge-e6ce87ea99e2`, and both are gone (the merge branch was deleted). The red test list survives only inside the printed `:seon.error/data-edn` | `seon.cluster.source/red-test-refusal`, the error recording | the recorded `:seon.source/red-on-target-error` holds `:seon.source/red-tests` as data that `d/pull` returns, and its run id resolves on main |
| F15 (new) | `(my.root/merge-requests)` lists the `:error` request with `merge!` and `close!` offers but not why it waits: no red test, no verdict, no refusal reference | `my.root/merge-requests` | an entry for a red-on-target refusal carries the red test symbols and their `:seon.source/verdict` |
| F16 (again) | The only notification is a root-to-root message whose content is the error map printed as EDN, and it does not name the red test. No problem task was written for the `:error` end (Run 11's `:capped` end wrote one) | `seon.error/commit-call`; `seon.cluster.agent/ended-problem!` | existing task `seon.error/error-notifications-to-root-are-printed-maps` |
| F17 (new) | Implement changed the two `ex-message` reads as well, against "the other branches are unchanged", and used a production regex (`re-matches #"\s*"`) where `clojure.string/blank?` is the core function. The Prove table did not pin those branches, so this is a review item for root's merge, not a red | the model; the step's instructions | none (observed) |
| F18 (probe side) | Rebuilding a first-call context at a historical basis with `d/as-of` fails: `seon.turn/fork-commit` → `seon.cluster.registry/base-commit-id` refuses a nil branch, because an as-of database carries no branch config. It works on the commit database from `datahike.versioning/branch-history`. That rebuild cost 954 ms (Implement) and the two together 1.27 s and about 1.5 GB allocated | `seon.context/model-context` (cost); the run method (capture while live) | a `seon.profile/measure` on `seon.context/model-context` for the Implement step's first block, under 1 s |
| F19 (again, F11's class) | Proof failures show the regex as `#object[java.util.regex.Pattern 0x7ed734a8]`, and the probe printed a `TxReport` as `#object[datahike.db.TxReport …]` | the value printer (a general type improvement) | Run 11 F11's proposal, plus a Pattern printing as `#"\s*"` |
| F20 (new, minor) | Prove call 7 guessed `(my.program/search :text "doseq [[")`; the refusal named argument 0 as a database value, which the model never passes | `my.program/search` arglist and docstring | none (observed, 1 of 10) |
| F21 (new, minor) | Prove call 3 re-read `(my.program/source 'seon.error-test)` that call 1 had already read (the namespace summary resent) | the model | none (observed) |

## What the next run changes

- Fix F13 (or choose a subject whose reaching tests are green interpreted on main), then rerun the same chain. Nothing
  else stopped it.
- The run method should check before filing a chain that every test reaching the subject's functions is green on
  main under the evaluator the gate uses: `(my.program/tests-reaching 'seon.error/message)` plus their recorded
  results.
- Capture the first-call context while the step runs, or keep using `datahike.versioning/branch-history`. A step can
  finish in about 10 s, faster than a poll.

## Residue on default (disposable)

- **Subject** `db57ce884739` set back to `:held` (cleanup, not counted); `seon.task/ready` answers `#{}`.
- **Prove** `9d2026d3e4a7` `:merged`; the table test is on main and in `test/seon/error_test.clj` at `468d24ad2`.
- **Implement** `37bd87f6430b` `:error`, an open merge request in `my.root/merge-requests`. `:attempt-37bd87f6430b`
  and `:base/attempt-37bd87f6430b` are kept and hold the green fix. It was not merged: that would be a hand act.
- **Error occurrence** 509737 (`:seon.source/red-on-target-error`) and message 509738 on main.
- **Config** restored to `no-provider true` and `max-episode-runs 100`, verified with `seon.config/effective`.
- **Seon git commits:** `468d24ad2` (Prove's publication) only.
- JVM `d/connect` to `:attempt-37bd87f6430b` and host Vars in `user` (`conn`, `ic`, `ihall`, the captures) were left
  open for reading.
