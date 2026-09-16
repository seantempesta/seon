---
type: issue
status: open
severity: friction
tags: [issue, program-graph, schema, data-model, class/p2]
---

# `seon.program/shapes` mirrors the schema row maps by hand, and silently drops what it misses

`seon.program/shapes` (`src/seon/program.cljc:38`) lists, per identity
family, the attributes a program row owns. `seon.program/canonical-row`
(`src/seon/program.cljc:704`) then `select-keys`es every statically indexed
row down to that list before it becomes a file artifact, so an attribute the
list does not name is DISCARDED with no diagnostic. The same attribute set is
already declared, for the same families, in the schema row maps
(`:seon.fn/fn` in `resources/seon/schemas/seon.fn.edn`, `:seon.test/test` in
`resources/seon/schemas/seon.test.edn`). Two lists, one fact.

Measured drift, 2026-09-16: `:seon.fn/writes` and `:seon.fn/call-arities`
landed in commit `efaa45a68` with their schema declarations and their
analyzer derivations. `seon.fn/analysis-rows-by-file` emitted both correctly
(probed in `default`'s JVM), the runtime admission path carried both, and
`seon.fn/rows` returned rows with NEITHER, because `shapes` had not been
extended. Four `seon.fn-test` regressions were red for that reason alone, and
no publication would ever have stored the facts.

This is the recurring failure class from AGENTS.md: the strip is silent, so
the absence of a facet reads as "this declaration writes nothing" — absence
of signal as health.

Second hit, same day: `:seon.fn.file/root` (2026-09-16, `925ca19fe`) was
declared on `:seon.fn.file/file`, written by the indexer where it mints the
file row, and dropped by `canonical-row` with no diagnostic until the literal
list was extended too — verified live in `default`'s JVM before the edit. Twice
in one day the hand-maintained mirror silently stripped an owned attribute; the
first was `:seon.fn/writes` / `:seon.fn/call-arities` in `7cfe02790`.

Dissolution: derive `:seon.program/owned-attributes` from the identity
family's declared schema row map (the keys of `:seon.fn/fn`,
`:seon.test/test`, `:seon.ns/ns`, `:seon.fn.file/file`, `:seon.lint/lint`),
and delete the literal lists. Failing that, one checker that fails on drift
between `shapes` and those row maps. Until then, every attribute added to a
program row map must be added here in the same commit.
