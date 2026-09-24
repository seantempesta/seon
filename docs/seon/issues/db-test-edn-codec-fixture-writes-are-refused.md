---
type: issue
status: resolved (fixture built wrong: with-codec-database now writes its schema rows, 9b5eefbb7)
severity: defect
tags: [issue, db, testing, codec, fixtures]
---

# `seon.db-test` EDN-codec fixture writes are refused, so decode tests prove nothing

`seon.db-test/edn-backed-reads-return-distinguishable-logical-values` and
`query-symbol-values-use-the-declared-codec-in-every-binding-shape` fail at their first
fixture write, before any read: Datahike refuses
`[:db/add … :seon.db-test/ai-declaration example.render/ai]` with
"value does not match schema definition. Must be conform to: string?". The symbol is
not encoded for the EDN-backed `:db.type/string` attribute on the write path under
`with-codec-database`. Every later assertion then reads an empty database.

Observed 2026-09-24 on default (committed archive `ce73846828a5`, pid 90471) with
`bin/test-check default --test seon.db-test/edn-backed-reads-return-distinguishable-logical-values`,
and on a `git archive` snapshot of HEAD `341305b4f` (root `tmp/fix-db-decode/snap`,
`bin/test-check --root … default --ns seon.db-test`: 19 reds, pass 424 / fail 49 / error 10).
Most of those 19 reds are fixture writes refused by other validators (missing
`:seon.program/definition-digest`, `creation-tx` expecting a string `:seon.agent/id`,
"A fixture agent needs exactly one cluster").

Consequence: nothing in the suite currently proves that EDN-encoded values decode on
`seon.db/q`, `pull` or `datoms`. Acceptance: the write encodes through the declared
codec, or the fixture writes through the canonical writer that does. Both tests then go
green without weakening their assertions.
