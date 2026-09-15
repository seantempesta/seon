---
type: issue
status: open
severity: friction
tags: [issue, test, schema, wave/test-fixture]
---

# Test program rows omit required admission provenance

## Problem

Effect and search fixtures transact incomplete program declarations and
ignore the refusal. Later assertions observe missing capability handlers,
missing completion events, or nil database inputs instead of the setup
error. Increasing an event timeout cannot fix this.

## Evidence

The September 15 pre-WIP snapshot `38c49a1db` reports effect 29 failures /
4 errors and search 1 failure / 1 error. The exact setup data from
`test/seon/effect_test.clj` (`install-capability!`, `install-arm-probe!`)
and `test/seon/search_test.clj:132` lacks
`:seon.schema.admission/source`. Non-writing live validation returns
`:seon.db/invalid-write` at that member. The effect then returns
`:seon.effect/undeclared-owner`; search passes the refusal to
`apply-report!`, which queries nil `:db-before`.

The declaration has required source since `06f4ebc4b8`; `26ec13420`
(September 9) admitted authored transaction validation. See
[the measurements](../../prds/context-generation/research/bisect-today-reds-2026-09-15.md).

## Owner

The fixture declaration producers in `test/seon/effect_test.clj` and
`test/seon/search_test.clj`. Keep canonical database population and armed
contracts; do not remove legitimate declaration requirements.

## Acceptance

Install complete declarations and verify transaction success before
dispatch or index advancement. The existing handler, completion, and
incremental-search assertions must pass on the armed canonical fixture.
Search's separate literal field-set expectation must derive from declared
facts instead of preserving removed attributes.
