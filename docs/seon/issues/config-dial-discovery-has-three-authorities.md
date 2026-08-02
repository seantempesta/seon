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
