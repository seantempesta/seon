---
type: issue
status: open
severity: friction
tags: [issue, render, testing, program-graph, symbols]
---

# `seon.render.value-test` fixtures still hand strings to symbol-typed attributes

Observed 2026-09-17 on `steward-platform` HEAD `767ff6d75`, running
`bin/test-fast --paths src/seon/render/value.clj -- seon.render.value-test`
(37 tests / 243 assertions / 36 failures / 9 errors). The tally is identical
with the HEAD test file and with the facet-declaration change applied, so this
predates both and is independent of the wrapper-enforcement work.

26 of the failing assertions are one class. The 2026-09-17 ruling stores every
symbol as `:db.type/symbol`; these fixtures still pass the string spelling, so
the writer refuses and the test then reads the refusal's absence as data:

```text
seon.db/transact! refused transaction data at [2 :seon.effect/owner 1]:
expected a namespaced symbol, got a string.
  Fix: Supply a namespaced symbol at [2 :seon.effect/owner 1].
seon.db/pull received "seon.db/q" for :seon.fn/sym, whose installed value
type is :db.type/symbol.
```

Affected tests, with the count of failing assertions:

| test | assertions |
|---|---|
| `background-poll-keeps-identity-while-payloads-grow` (`test/seon/render/value_test.clj:209`) | 12 failures, 2 errors |
| `explicit-structural-results-retain-attributes-through-real-evaluation` (`:101`) | 11 failures |
| `a-pulled-function-row-is-its-attributes-not-steering-prose` (`:170`) | 3 failures |
| `default-entity-map-renders-refs-as-installed-identities` (`:707`) | 1 failure |
| `an-explicit-pull-keeps-its-nested-shape-in-shown-text` (`:719`) | 2 failures |

The repair is the fixture's own: the lookup-ref and attribute values become
symbols, and each fixture write goes through `seon.test-support/transacted!`
(`test/seon/test_support.clj:296`) so a refusal is surfaced rather than read as
behaviour — the honest-fixture class AGENTS.md §5 already names. The first
assertion of each block already reads `(:db-after written)` off a refused
report, which is exactly the "absence of signal as health" failure class.
