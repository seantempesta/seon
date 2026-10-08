---
title: "Card EN-4: A test body runs as one eval request with no rows of its own"
type: reference
status: active
created: 2026-10-08
---

# EN-4 A test body runs as one eval request with no rows of its own

Ledger row EN-4 (S6-02a, group E plus `test-request` in group B). Design: `redo/entrants.md` §3 "The test member is a
pass with no rows" and owner question 1 (no member rows; the value joins the request's one transaction), approved.
Lane: Sonnet. Cited at HEAD `e7f120672`.

## What exists
`seon.test/member-result` (`src/seon/test.clj:1998-2116`) calls `bounded-result` (`:216`) directly; the body's failure
texts are built with `render.value/core-text` (`:2036`, `:2105`). `seon.turn.batch/test-request`
(`src/seon/turn/batch.clj:970`) and `seon.turn.world/test-agent-world` (`src/seon/turn/world.clj:70`) have no caller
(`rg` confirms).

## Change
- `member-result` runs the body through the fold: `(seon.batch/evaluate-batch (turn.batch/test-request {:seon.batch/body #(bounded-result …same arguments…) :seon.batch/world member-world :seon.batch/show turn.batch/show}))`
  with `:seon.batch/settle` absent (no recording on the member branch) and `member-world`
  `{:seon.boot/cluster-name … :seon.db/connection (:seon.db/connection child) :seon.sci.eval/ctx ctx :seon.turn/id eval-request :seon.agent/id agent-id}`,
  `eval-request` minted `(seon.id/id)` before the body. The captured result is the entry's value; the result map
  gains `:seon.test.member/eval-request` (the id), `:seon.test.member/shown` (the entry's shown text, stored by SR-B's
  rule as the runner stores failure texts: `seon.blob/inline-or-stage`) and, when the batch has diagnostics, their
  shown text as `:seon.test.member/diagnostics`; these ride `commit-results!`'s one record transaction.
- `test-request`: delete `:seon.batch/settle settle` (`:985`); its docstring: "no recording of its own".
- DELETE `seon.turn.world/test-agent-world` and the world's `:seon.batch/settlement` member and
  `settlement-connection`'s fallback branch if no other world sets it (`rg -n 'seon.batch/settlement' src`).
- agent-loop.md §3.2's test-member row ("1 member pass") is corrected in the same commit to "none: its value rides
  the request's record".
- Schema `seon.test.member.edn`: `:eval-request [:string {:min 1}]`, `:shown [:string {:min 1}]`,
  `:shown-size [:int {:min 0}]`, `:shown-blob :seon.blob/digest`, `:diagnostics [:string {:min 1}]`, all optional on
  the member entity; the runner's `complete-members` (`src/seon/test/runner.clj:1534`) copies them from the result.

## Proof
`seon.test-test/a-member-body-runs-as-one-eval-request` (new): an MCP eval request on an agent with a red proof
(`(deftest red-proof (is (= 1 2)))`), checked with `(my.test/check {:seon.test/identities #{'…/red-proof}})`; the
member entity pulled from the recording branch has `:seon.test.member/eval-request` and `:seon.test.member/shown`; the
member branch (gone after release) wrote nothing: the store holds no call row `[:seon.turn/id <that eval request>]` on
any branch the agent can read.

## Files / commit
`src/seon/test.clj`, `src/seon/test/runner.clj`, `src/seon/turn/batch.clj` (`test-request` only), `src/seon/turn/world.clj`,
`resources/seon/schemas/seon.batch.edn`, `resources/seon/schemas/seon.test.member.edn`, `docs/seon/specs/agent-loop.md`,
`test/seon/test_test.clj`.
`bin/lane commit <lane> -m "EN-4: a test body runs through the fold with no rows of its own" --test seon.test-test --test seon.sci.documentation-test --test seon.test.runner-test src/seon/test.clj src/seon/test/runner.clj src/seon/turn/batch.clj src/seon/turn/world.clj resources/seon/schemas/seon.batch.edn resources/seon/schemas/seon.test.member.edn docs/seon/specs/agent-loop.md test/seon/test_test.clj`

## Cost
Expected: the fold around one entry (entrants §1: 0.30 ms / 0.36 MB without print) plus one print of the result map;
≤ 2 ms per member. Report the T probe (`seon.pass-probe/run-proof`, EN-0).

## Dependencies
EN-1. `src/seon/test/runner.clj` is led-storage/blob lanes' at times: check `bin/lane status`. Reset: none.
