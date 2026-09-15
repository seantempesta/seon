---
type: issue
status: resolved
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
[the measurements](../../../prds/context-generation/research/bisect-today-reds-2026-09-15.md).

## Owner

The fixture declaration producers in `test/seon/effect_test.clj` and
`test/seon/search_test.clj`. Keep canonical database population and armed
contracts; do not remove legitimate declaration requirements.

## Acceptance

The same fixture class also omitted turn agents/opening transactions and
the config row's `:seon.config/applied-manifest-digest`. A live non-writing
validation probe isolated that config refusal on September 15. The repair
uses `config/compile-manifest`'s desired row, supplies complete turn data,
and makes fixture transaction refusal immediate. No admission requirement
is removed. The new declaration regression reads both installed owners and
their schema provenance back from the canonical database.

Install complete declarations and verify transaction success before
dispatch or index advancement. The existing handler, completion, and
incremental-search assertions must pass on the armed canonical fixture.
Search's separate literal field-set expectation must derive from declared
facts instead of preserving removed attributes.

## Verification, September 15

The HEAD-plus-two-fixture-files isolated gate passed **19 tests / 102
assertions, 0 failures / 0 errors** at 19:58Z, with one worker. It exercised
real effect handlers, detached completion, SCI evaluation identity, and
incremental search. The schema-change regression uses an attribute-level
`:db/add` transaction, not an incomplete authored declaration map.
