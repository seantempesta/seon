---
type: research
title: "live-pick: candidates for the next live DeepSeek chain run"
status: complete (lane live-pick, 2026-10-15, HEAD 9d33684f7; no REPL request on default, `bin/seon check` only)
---

# live-pick: the next live chain run

## Finding first: reds on HEAD are rare

Almost every filed task that names a proof test is already green: the patch behind
`seon.ref/an-unresolved-reference-offers-search-instead-of-creating` is applied, and
`seon.ref-test/an-unresolved-reference-says-what-failed-and-what-to-do` is **green** on HEAD (check run `1ee51f9eed15`,
3 pass; not a candidate). The same holds for `seon.print/a-path-form-reads-any-plain-key` and
`seon.render.block/a-surface-id-passes-only-ascii...` (fixed in source). Only one task file entry carries `:proofs`
(`seon.turn/the-fixture-model-requests-read-the-block-and-help-as-they-are-now`). So the ranking mixes one verified-red
existing deftest with two "new proof" tasks (the S5 route: `chain!` accepts a proof naming a test the branch has not
defined; the block shows it `:pending`, the done rule refuses until a current result exists,
`s5-remerge.md#step-1-root-setup-jvm-mode`). A pending proof is red by the done rule but cannot be shown red by
`bin/seon check` (the test does not exist), so those two are marked unchecked.

No lane holds any candidate file: `bin/lane status` shows only `s5-frictions` (1 path, `src/seon/error.clj`).

## Ranking

| # | Handle | Proof | Red evidence | Owner | Fit |
|---|---|---|---|---|---|
| 1 | `seon.ref/a-citation-ending-in-a-wildcard-is-not-reported-as-a-missing-name` | new row `seon.ref-test/a-citation-ending-in-a-wildcard-is-prose` | pending (unchecked); the bug is read from source | `seon.ref/span-candidate` | best |
| 2 | `seon.turn/the-fixture-model-requests-read-the-block-and-help-as-they-are-now` | `seon.cluster.turn-test/a-chat-model-request-carries-its-forms-as-assistant-messages` | RED, run `80687f8e0201` (1 fail of 13, test/seon/cluster/turn_test.clj:3126) | the test's own assertion, or `seon.context/current-state` | verified red, riskier |
| 3 | `seon.render.block/an-empty-block-name-answers-an-id-its-contract-refuses` | new row in `seon.render.block-test` | pending (unchecked) | `seon.render.block/surface-id` or `:seon.render.block/name` | fits, but holds a design choice |

### 1. Wildcard citation (recommended)

- **Bug.** A plan citing `` `:seon.render.unknown/*` `` (also `seon.task.attempt/*`, `seon.task.assignment/*`,
  `seon.schedule.task/*`, `seon.task.fire/*` in 8 task-file mentions) is read by `seon.ref/span-candidate` as a
  qualified keyword or symbol (`edamame/parse-string-all`, then `(or (qualified-symbol? form) (qualified-keyword? form))`)
  and resolved to an `:unresolved` problem. A trailing `*` names a family, not a name.
- **Done:** a span whose name part ends in `*` is read as prose (`span-candidate` answers nil), or answers a problem
  that names the wildcard; asserted by one `seon.ref-test` row on `seon.ref/references` over
  `` `:seon.render.unknown/*` `` and `` `seon.task.attempt/*` ``, which must answer no `:unresolved` entry.
- **Why it fits.** One private function (about 12 lines) in one namespace; the data flow is obvious
  (`seon.ref/references` -> `seon.ref/candidate` -> `seon.ref/span-candidate`); test rows beside it
  (`seon.ref-test/a-reference-with-no-path-is-a-missing-file`) show the shape. Ordinary code, SCI-definable on a branch.
  About 6 to 8 turns: read the block, define the test row (red), redefine `span-candidate`, read the green result.
- **Risk.** (a) The proof is pending, so the first turn must define the test; the plan names the exact name.
  (b) `span-candidate` is private with a contract: the redefinition must keep it. (c) The task wants "count first" how
  often plans cite one: the plan below already gives the count, so drop that step. (d) A model may choose "report a
  problem naming the wildcard" over "prose"; either passes the row as worded, so word the row on the absence of
  `:unresolved`.
- **Chain form** (root, JVM mode, as `s5-remerge.md` ran it):

```clojure
(def conn (seon.cluster.boot/connection "default"))
(def inst (get @seon.operator.runtime/running-instances "default"))
(def plan
  "## Problem

A plan citing `:seon.render.unknown/*` or `seon.task.attempt/*` makes `seon.ref/references` answer an `:unresolved`
problem for the wildcard: `seon.ref/span-candidate` reads any span that is one qualified keyword or symbol as a name,
and a trailing `*` names a family of names, not a name. Eight task-file mentions cite one.

## Done when

`seon.ref-test/a-citation-ending-in-a-wildcard-is-prose` (you define it in `seon.ref-test`, beside
`seon.ref-test/a-reference-with-no-path-is-a-missing-file`) asserts that `seon.ref/references` over the text
`` `:seon.render.unknown/*` and `seon.task.attempt/*` `` answers no entry with `:seon.ref/problem-kind :unresolved`
(read the answer by key, not by position), and is green. Change `seon.ref/span-candidate` (private; keep its
`:malli/schema`) so a span whose name part ends in `*` is prose. Every other `seon.ref-test` row stays green.")
(seon.task/chain! {:seon.db/connection conn :seon.store/store (:seon.store/store inst)}
                  [(merge (seon.task/template (datahike.api/db conn) 'seon.step.implement/template)
                          {:seon.task/ns 'seon.ref
                           :seon.task/name "a-citation-ending-in-a-wildcard-is-prose"
                           :seon.task/plan plan
                           :seon.task/proofs #{'seon.ref-test/a-citation-ending-in-a-wildcard-is-prose}})])
```

`:seon.task/model` is omitted, so the cluster's `:seon.config.ai/model` (the product model) works it. Root turns
`:seon.config.ai/no-provider` off for that one branch only. The name differs from the filed task's
(`a-citation-ending-in-a-wildcard-is-not-reported-as-a-missing-name`); root retires the filed entry at the merge
(TASK-CHAIN 2.6), or reuse the filed name to have the merge delete it.

### 2. Chat model request test (verified red)

- **Proof and evidence.** `seon.cluster.turn-test/a-chat-model-request-carries-its-forms-as-assistant-messages`,
  `bin/seon check --at HEAD --include-long --test ...`: **red**, run `80687f8e0201`, 12 pass, 1 fail. The failing
  assertion is `(str/starts-with? block "#:seon.context{:task")`: the current-state block now starts with
  `#:seon.context{:proofs`. The reply also shows the step `:working` with `:seon.source/uncontracted-error` on the
  fixture function `tq.cx12/area`, the cause the filed plan names. The sibling
  `a-model-request-is-help-own-exchanges-and-the-block-last` was not checked (one candidate per check).
- **Owner.** The test's expectation (a retired assumption: read the block by its keys) or `seon.context/current-state`
  (a surviving owner): the filed plan says decide per assertion.
- **Why it fits / risk.** It is a real red with a clear failing line. Against it: the test body is a 60 s `:seon.test/long`
  fixture in a 3,100-line namespace that boots a fixture provider, so every proof run is slow (a capped 12-turn run gets
  few proof cycles), the fix is a judgment call between test and producer, and redefining a deftest in
  `seon.cluster.turn-test` on a branch is the heaviest REPL form of the three. Use it only if root wants a verified red;
  otherwise rank below 1.
- **Chain form:**

```clojure
(seon.task/chain! {:seon.db/connection conn :seon.store/store (:seon.store/store inst)}
                  [(merge (seon.task/template (datahike.api/db conn) 'seon.step.implement/template)
                          {:seon.task/ns 'seon.turn
                           :seon.task/name "the-chat-model-request-reads-the-block-as-it-is-now"
                           :seon.task/plan
                           "## Problem

`seon.cluster.turn-test/a-chat-model-request-carries-its-forms-as-assistant-messages` is red at test/seon/cluster/turn_test.clj
line 3126: it expects the last user message's block part to start with `#:seon.context{:task`, but
`seon.context/current-state` now starts `#:seon.context{:proofs` (check run 80687f8e0201). The step also stays `:working`:
the fixture function `tq.cx12/area` is uncontracted (`:seon.source/uncontracted-error`).

## Done when

That test is green. Decide the assertion: the block is a map read by its keys, so assert on `:seon.context/proofs`
being its first section or on the section the test is about, not on a printed prefix. Read `seon.context/current-state`
and `seon.cluster.turn-test/model-requests!`."
                           :seon.task/proofs #{'seon.cluster.turn-test/a-chat-model-request-carries-its-forms-as-assistant-messages}})])
```

### 3. Empty block name (unchecked, holds a decision)

- **Bug.** `(seon.render.block/surface-id (keyword ""))` answers `"surface-"` (8 characters); its output schema
  `:seon.render/surface-id` is `[:string {:min 9}]` (resources/seon/schemas/seon.render.edn), so the instrumented call
  refuses its own output. Found by `clojure.test.check` shrinking to `:`.
- **Done:** either `:seon.render.block/name` refuses an empty name, or `:seon.render/surface-id` accepts `"surface-"`,
  with a row in `seon.render.block-test` (the existing `stable-surface-addresses-are-injective` generator test is the
  place). The `AGENTS.md` rule "fix at the producer" says tighten the input: the name type owns the promise.
- **Fit / risk.** `seon.render.block/surface-id` is ordinary and small, but the fix may sit in a schema resource
  (`resources/seon/schemas/`), which the done rule and the merge treat as platform-adjacent (schema publication), and
  a model may pick the loosening. Rank last. Chain form is the same shape as 1 with ns `seon.render.block`, name
  `an-empty-block-name-is-not-a-block-name` and proof `#{'seon.render.block-test/an-empty-block-name-is-not-a-block-name}`.

## Rejected (checked or read)

- `seon.ref/an-unresolved-reference-offers-search-instead-of-creating`: green on HEAD (run `1ee51f9eed15`).
- `seon.flow/a-panicked-proc-stop-test-runs-over-its-bound`, `seon.test.runner` timeout and reuse tasks,
  `seon.sci.eval/the-loaded-commit-is-materialized-on-every-call`, `seon.profile` C14 and cost entities: platform
  (flow, runner, evaluator, instrumentation) per the lane rule.
- `seon.cluster.merge-test/a-chain-merges-once-and-closes-every-attempt` (red per `seon.merge/a-chain-merges-once-is-red-on-head`):
  merge machinery, root's.
- `seon.task/a-tasks-model-reads-back-as-the-keyword-it-was-written-as`: now reproduced by `s5-remerge.md` (finding 1) but
  the producer is the merge, not ordinary code.
- Old task-file entries whose plan says "Done when that test is green" (2026-10-08/09) are stale: the surveyed ones are already fixed.
