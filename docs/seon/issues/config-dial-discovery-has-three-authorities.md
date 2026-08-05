---
type: issue
status: open
severity: friction
tags: [issue, config, schema, testing]
---

# Give config-dial discovery one explicit authority

## Problem

Config-dial membership and application semantics are maintained through a
namespace-prefix rule plus two test rosters. A new dial can be admitted because
of its spelling while the separate consumer tables drift.

## Evidence

`src/seon/schema/edn.clj:61-67` classifies every attribute whose namespace
starts with `seon.config.` as a dial, even without `:seon.config/dial true`.
A direct probe classified the nonexistent
`:seon.config.fake/not-a-dial` with definition `:string` as a dial.

`test/seon/config_application_test.clj:17-102` repeats the registered keys in
`application-ledger`, assigning each a mode and consumer symbol. Lines 104-128
repeat many of the same keys in `applied`. These lists test one another rather
than deriving admission and consumer evidence from the schema/program graph.

The rosters have already drifted on the current tree. Commits `be37aac87` and
`ebfaa4900` added the admitted and shipped
`:seon.config.eval.result/blob-threshold` and
`:seon.render.value/max-collection` dials. Their live readers are the loop's
blob-threshold query and the web/value page-size path, but neither key appears
in `application-ledger` or `applied`. A focused
`bin/test seon.config-application-test` run failed
`every-config-entry-has-an-honest-application-contract` with exactly those two
missing ledger keys. The derived schema/default dial sets themselves were
exactly 40 = 40. This is the predicted three-authority failure, not a separate
missing-consumer issue: both dials are real and consumed; only the copied test
authorities are stale.

The operator event-wait repair on 2026-08-05 supplied another direct example:
the registry-derived `seon.config-test` accepted
`:seon.config.operator/event-silence-backstop-ms` and passed all 15 tests and
73 assertions without a roster edit, while the copied `application-ledger` in
`test/seon/config_application_test.clj` has no row for it. That test file was
outside the operator lane's ownership; the right repair remains this issue's
derived application contract, not another hand-list entry.

## Owner

The schema form properties for config leaves, plus program-graph facts for
their actual consumers.

## Acceptance

- Every config dial declares its membership once in its leaf schema; spelling
  is never a classifier.
- Update semantics and consumer reachability are derived from the boot/runtime
  call graph or another single admitted fact, not test-only maps.
- A regression introduces a dial outside the existing namespace family and a
  similarly named nondial, proving both classifications without editing a
  roster.
- The application test first proves a nonempty admitted subject set, then
  exercises every derived consumer boundary.
