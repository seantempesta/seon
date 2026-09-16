---
type: issue
status: resolved
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

## Resolved 2026-09-16 (`8795db4ac`)

The literal lists are deleted. An identity attribute declares
`:seon.program/row-schema` (its entity map) and
`:seon.program/source-attribute`; that entity map's own entries are the
attributes the static indexer owns, minus every entry declaring another
`:seon.program/written-by`. `:seon.program/projected-properties` selects the
schema family's open row, and every absence refuses rather than yielding an
empty owned set.

The two genuine exclusions are now declared facts, not omissions:
`seon.test.runner/record-tx` owns the thirteen run-outcome attributes on
`:seon.test/test`, `seon.turn/relation-assertions` owns
`:seon.test/pending-subject`, and `seon.cluster.agent/steward-call` owns
`:seon.ns/steward`. `:seon.fn/writes` was probed as the source of that fact
and FALSIFIED — it records none of them, because they are assembled in
helpers away from the `seon.db/transact!` span.

The class regressions are
`seon.program-test/declaring-an-attribute-on-a-program-row-schema-is-sufficient`,
`seon.program-test/every-program-row-attribute-is-owned-or-names-another-writer`,
`seon.program-test/program-identity-attributes-are-exactly-the-declared-row-schemas`
and `seon.fn-test/the-indexer-emits-no-attribute-the-program-row-schema-drops`.
Evidence and the measured re-index numbers:
[program-shapes-dissolution-2026-09-16](../../prds/steward-platform/research/program-shapes-dissolution-2026-09-16.md).
