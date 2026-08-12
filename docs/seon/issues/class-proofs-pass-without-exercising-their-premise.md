---
type: issue
status: open
severity: friction
tags: [issue, testing, class-kill]
---

# Make proofs unable to pass without exercising their premise

## Problem

Tests and live drives can derive no subjects, construct a weaker fixture than
production, omit the failing generator branch, or declare success before the
semantic exit. The assertion can then be green while the claimed mechanism was
never exercised.

## Evidence

Seven open issues recur from 2026-07-31 through 2026-08-10:
[[ai-transport-taxonomy-test-can-run-zero-assertions]],
[[bootstrap-o4-stops-before-causal-delegation-settles]],
[[context-mvp-drive-can-false-green-after-cross-agent-delivery]],
[[output-sink-query-excludes-operator-and-mcp-scripts]],
[[oversight-fleet-test-pins-a-stale-proc-roster]],
[[public-contract-census-can-pass-with-no-subjects]], and
[[render-wave-properties-cannot-produce-their-failing-cases]].

The recent archive repeats the class on 2026-08-07, 2026-08-10, and
2026-08-11 in [[archive/flow-generators-reuse-one-mutable-sample]],
[[archive/blob-economics-test-used-a-now-windowed-string]],
[[archive/reader-policy-test-used-refused-sci-init-options]],
[[archive/concurrency-plans-open-unplanned-follow-up-runs]],
[[archive/activation-closure-fixtures-lag-lookup-ref-prerequisites]], and
[[archive/generative-loop-fixture-commits-no-run-facts]].

## Owner

`bin/test`, fixture constructors, and the program-graph subject discovery used
by each recurring proof.

## Acceptance

- Proofs obtain subjects through the same constructor/query as production and
  refuse an empty or incomplete subject set before semantic assertions run.
- Every property carries one retained counterexample that demonstrably makes
  it fail, and every generator creates fresh honest values.
- A live drive closes only on the requested durable semantic exit, not handoff
  or an intermediate transition.
