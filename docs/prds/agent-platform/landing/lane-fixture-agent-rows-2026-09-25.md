---
type: landing
status: code-only (root runs the tests)
lane: fixture-agent-rows
created: 2026-09-25
census: docs/research/agent-platform/red-baseline-census-2026-09-25b.md (aab55ddd3), classes A and B
---

# Lane fixture-agent-rows — landing (2026-09-25)

What exists: `seon.cluster.agent/creation-tx` writes an agent, seeds its namespace and records custody
through `branch-call`, which asserts the branch the transacting database holds (agent.clj:186–224,
b2fcb5225). `seon.test-support/agent-tx` (test_support.clj:716) is the fixture entrance to it. The
fixtures below wrote agents by hand, either with no branch (class A, rule 1ada78050) or with the label
`(registry/cluster-branch "turn-test")` (class B). They now write agents through `support/agent-tx`
on the test's own connection. A test that needs `root` seeds its own; inherited agents are no longer
the fixture's agents (cluster/turn_test `agent-ids`, `agent-evaluations`).

## Files owned (named first)

test/seon/cluster/turn_test.clj, test/seon/turn_test.clj, test/my/agent_test.clj,
test/seon/agent_situation_test.clj, test/seon/ai_test.clj, test/seon/cluster/problem_routing_test.clj,
test/seon/contracts_fixture.clj, test/seon/error_write_timing_test.clj, test/seon/eval/drive_test.clj,
test/seon/fn_test.clj, test/seon/help_trial_test.clj, test/seon/loop_proof_test.clj,
test/seon/maintenance_schema_test.clj, test/seon/render/entity_pairs_test.clj,
test/seon/render/history_test.clj, test/seon/render/web_debug_test.clj, test/seon/rereads_test.clj,
test/seon/run6_stall_test.clj, test/seon/schema_usage_guard_test.clj, test/seon/turn_continue_test.clj,
test/seon/turn_loop_test.clj; tmp/fixture-agent-rows/ (survey.clj, convert.clj, class lists).

Files with both an A/B red and another red: I own these. **For update-retired-test-shapes:**
my/agent_test, ai_test, fn_test, loop_proof_test, entity_pairs_test, schema_usage_guard_test,
render/web_debug_test, turn_loop_test, turn_test, cluster/turn_test, error_write_timing_test. None of
them is in that lane's rewrite.clj target list, which I read at 19:35 local.

## Broken or not done (first)

- **Nothing was executed.** Load rule: lint only. clj-kondo reports 0 errors across the 21 files,
  with test_support.clj included for arity checks. Warnings match HEAD. I did not load any test
  namespace into default.
- **render/web_test.clj is not mine.** fix-src-singles-prompt-web-mcp-config holds it.
  `an-undeclared-incoming-reference-is-reachable-from-the-page` still writes
  `{:seon.agent/id "debug-trigger"}` at web_test.clj:2037. Fix: `(into (support/agent-tx @connection
  "debug-trigger") …)` or `cluster.agent/creation-tx`, the same pattern already used at web_test.clj:126.
- **The script cannot tell a creation row from an update row.** Both have the same shape,
  `{:seon.agent/id x :seon.agent/namespace ns}`. Converting an update would rerun `creation-tx` on an
  existing agent and orphan its plan, settings and runtime components. In this set I reverted two
  update rows by hand, both on agent-a in cluster/turn_test (mixed-plan at :503 and the a-red-form
  reassignment at :1492).
  - The dry run over the other 28 files that have branchless rows is at
    `tmp/fixture-agent-rows/files2.txt`. Most of those rows are updates, for example
    agent_namespace_test.clj:139 and :47 and cluster/agent_test.clj:447, so I **did not convert** them.
  - Creation-shaped candidates that are not red from this class today: my/test_test.clj:27,
    core_functions_test.clj:28, render/retained_test.clj:16, render/walk_test.clj:36 and
    cluster/resume_artifact_routing_test.clj:38/40 (the last is among the 18 unknowns). Convert
    one only when a red names it.
- **Behaviour changes:**
  - Converted agents now get creation-tx's canonical namespace source,
    `(ns x (:require [my.message] [my.turn] [seon.db] [seon.bootstrap …] [clojure.test …]))`,
    wherever the fixture did not write its own namespace row first. Where the fixture wrote one first,
    `namespace-seed-call` keeps it. In cluster/turn_test the old digest-only `namespace-row` stays in
    front of each converted agent so its namespace bytes do not change.
  - Each converted agent also gets its plan, settings and runtime components and a steward assignment.
- The `settings` and `plan` fixture values now go through the owners. Settings use
  `seon.agent/settings!` (my/agent_test, ai_test). The plan and runtime components are written by their
  identity back-refs `:my.plan/agent` and `:seon.runtime/agent` (run6_stall_test, web_debug_test
  saved-history), so the canonical component is updated and none is orphaned.
- error_write_timing_test: I deleted the unused `{:seon.agent/id "error-write-request"}` returned by
  the test's transaction function. The test asserts only about the request object and the issue row.
- cluster/turn_test rows :1873 and :1987 still replace agent-a's settings component with a hand-built
  map. That is class C, and the file is mine, but the change is out of this class. The fix is
  `seon.agent/settings!`.
- No RESET NEEDED.

## Evidence

- Class membership comes from `(@#'seon.problems/failed-tests @(seon.cluster.boot/connection "default"))`
  (read-only, 329 ms). "missing :seon.agent/branch" matches **37** reds and "Isolation requires a
  fresh owned branch" matches **33** (census: 40 and 33). The lists are in
  `tmp/fixture-agent-rows/ab-symbols.edn`.
- REPL probe (JVM, read-only; `datahike.api/with` on default's value, nothing committed; 5.2 ms). I
  applied `(into [ns-row] cat [(creation-tx …) [message]])`. Result: agent `:seon.agent/branch` is
  `:cluster-default`, which is the value's own `[:config :branch]`. One namespace entity exists, the
  fixture's row, because the seed call is skipped. The steward is the agent. So order is preserved,
  and custody is the connection's own.
- Script: `bb tmp/fixture-agent-rows/convert.clj FILE…` rewrote 16 vectors in one pass. Log:
  `tmp/fixture-agent-rows/convert-run.txt`. I fixed the rest by hand: problem_routing's `into`, drive's
  `:tx-data` map, fn_test's `into`, maintenance's `into`, web_debug thinking and saved-history, run6,
  my/agent, ai, error_write, and cluster/turn_test's five `agent-row` sites. A second pass removed
  labelled branches from test/seon/turn_test.clj (11 sites).
- Net test lines: +168 / −186 (−18). No src change.

## Root runs (serially, in a quiet window)

Adopt the committed paths, then run one request per namespace:

```
bin/test-check default --ns seon.cluster.turn-test
bin/test-check default --ns seon.turn-test
bin/test-check default --ns my.agent-test
bin/test-check default --ns seon.agent-situation-test
bin/test-check default --ns seon.ai-test
bin/test-check default --ns seon.cluster.problem-routing-test
bin/test-check default --ns seon.contracts-install-test
bin/test-check default --ns seon.contracts-plan-test
bin/test-check default --ns seon.error-write-timing-test
bin/test-check default --ns seon.eval.drive-test
bin/test-check default --ns seon.fn-test
bin/test-check default --ns seon.help-trial-test
bin/test-check default --ns seon.loop-proof-test
bin/test-check default --ns seon.maintenance-schema-test
bin/test-check default --ns seon.render.entity-pairs-test
bin/test-check default --ns seon.render.history-test
bin/test-check default --ns seon.render.web-debug-test
bin/test-check default --ns seon.rereads-test
bin/test-check default --ns seon.rereads-panel-test
bin/test-check default --ns seon.run6-stall-test
bin/test-check default --ns seon.schema-usage-guard-test
bin/test-check default --ns seon.turn-continue-test
bin/test-check default --ns seon.turn-loop-test
```

Converted tests expected to leave classes A and B (36 A and 33 B; the web_test A red stays):
- A:
  - my.agent-test/settings-are-one-owned-component-with-a-derived-render-pair
  - seon.agent-situation-test/situation-is-the-live-derived-control-surface
  - seon.ai-test/agent-overlay-reads-only-derived-per-agent-attributes
  - seon.cluster.problem-routing-test: an-author-owned-red-form-remains-unsettled-without-self-assignment,
    historical-reds-are-outside-the-live-attempt-chain,
    parse-time-owner-wins-and-absence-falls-back-to-the-author,
    stored-evaluations-derive-routing-and-settlement
  - seon.cluster.turn-test/a-red-form-routes-to-its-namespace-owner-and-the-fold-continues
  - seon.contracts-install-test/function-literal-contract-is-refused-before-printing-or-installing
  - seon.contracts-plan-test: installed-contract-refusal-names-the-run5-failing-coordinate,
    refusal-grammar-survives-real-evaluation
  - seon.error-write-timing-test/transaction-functions-receive-their-request-object-unchanged
  - seon.eval.drive-test/transcript-projects-the-evaluation-time-limit
  - seon.fn-test: settled-agent-form-has-static-index-edge-parity,
    settled-form-records-calls-across-every-program-namespace
  - seon.help-trial-test/preflight-requires-the-generated-opening-and-unpolluted-fixture
  - seon.loop-proof-test/running-fixture-settles-its-seeded-wake
  - seon.maintenance-schema-test/root-owned-portfolio-initializes-as-queryable-schedule-facts
  - seon.render.entity-pairs-test/namespace-page-renders-one-full-namespace-and-linked-references
  - seon.render.history-test: a-neighbour-the-walk-reached-renders-by-its-own-shape,
    form-is-the-third-output-of-the-existing-selection-chain
  - seon.render.web-debug-test: outline-origin-survives-the-issues-deletion,
    the-agent-page-shows-a-comment-as-thinking-and-a-result-through-its-pair,
    the-debug-outline-is-one-line-per-turn-and-one-line-per-unit,
    the-outline-toggle-serves-the-exact-stored-shown-text,
    the-outlines-show-everything-is-the-composed-prompt,
    turn-details-use-the-loop-opening-and-exact-segments
  - seon.rereads-panel-test/problems-count-every-silent-refresh-from-history
  - seon.rereads-test: failed-evaluations-are-not-promoted-and-documentation-follows-its-evidence,
    stale-read-with-equal-value-refreshes-evidence-without-emitting
  - seon.run6-stall-test/an-open-session-provider-refusal-is-the-first-problem
  - seon.schema-usage-guard-test/one-decision-path-answers-every-schema-form-change
  - seon.turn-continue-test: provider-progress-has-its-own-bound, read-results-arrive-before-done
  - seon.turn-loop-test/a-clean-last-form-without-a-disposition-is-loud-terminal-evidence
  - seon.turn-test/a-refused-turn-write-is-bounded-and-commits-exactly-one-fault
- B: the 32 `seon.cluster.turn-test` symbols in `tmp/fixture-agent-rows/ab-symbols.edn` :B, plus
  `seon.turn-test/virtual-turns-use-the-proc-and-compaction-is-agent-scoped`.
- Also touched while not A/B red:
  - render.web-debug-test/saved-history-preserves-shown-text-with-numeric-lookups
  - cluster.turn-test/mixed-plan-publishes-only-the-contracted-function
  - the label-branch tests in seon.turn-test: compaction-refuses-an-open-turn-at-the-writer,
    open-run-tx-call-emits-only-while-its-run-is-open, and the batch, generated-agent, replay,
    moving-agent, macro-caller, receipts, recovery and juno fixtures
  - loop-proof-test/virtual-loop-end-to-end

These tests can surface their next cause, for example classes C, I or K. That is expected: the next
red is the true one, not this class.

## Verification limits

Code only. No test request, no load and no timing of any test body. The single probe ran 5.2 ms, on
an uncommitted `d/with`.
