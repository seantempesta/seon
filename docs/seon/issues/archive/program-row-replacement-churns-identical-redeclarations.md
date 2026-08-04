---
type: issue
status: resolved
severity: cleanup
tags: [issue, database, program-graph]
---

# Re-declaring an identical function retracts and rebuilds its component trees

## Evidence

Session-curation research probe, 2026-08-04
([session-curation-replay-mechanics-opus-2026-08-04.md](../../prds/sci-execution-runtime/research/session-curation-replay-mechanics-opus-2026-08-04.md)),
on scratch cluster `curation-opus`. One contracted `defn` was evaluated
twice from byte-identical source and each resulting program row applied
through the real seam `#'seon.cluster.run/program-row-tx`:

| | first install | second install |
|---|---|---|
| tx-data items | 1 | 4 |
| datoms committed | 42 | 69, of which **34 are retractions** |
| `:seon.fn/sym` rows | 1 | 1 |
| entity id | 14080 | 14080 (stable) |

The cause is `seon.program/changed-attributes` (`src/seon/program.cljc:424-444`),
which compares the PULLED entity against the DESIRED row:

```clojure
(program/changed-attributes existing row)
;; => [:seon.fn/ns :seon.fn/arities :seon.fn/ast]
;; existing :seon.fn/ns => #:db{:id 13991}
;; desired  :seon.fn/ns => [:seon.ns/name my.agents.root]
```

A lookup ref never equals a pulled `{:db/id …}` map, and pulled component
trees never equal the desired plain maps, so those three attributes report
"changed" on every redeclaration. `exact-replacement-tx`
(`src/seon/program.cljc:449-465`) then emits `:db.fn/retractAttribute` for
the component-owned attributes and re-asserts them.

## Why this is wrong

Identity is preserved and correctness is unaffected, but "the agent
re-evaluated the same `defn`" writes ~69 datoms and creates fresh
component entities every time. That inflates history, makes any datom-level
comparison of two branches lie about what changed, and makes an idempotent
operation look like a mutation to every consumer that watches the program
graph. Hot reload, curated replay, and any repeated `acquire!`-adjacent
path pay it.

## Expected behavior

`changed-attributes` normalizes both sides before comparison — resolve the
desired ref to the same shape the pull returns (or pull refs as lookup
refs), and compare component trees on their declared attributes rather than
on their pulled representation — so a byte-identical redeclaration produces
NO retraction and no re-assertion.

## Acceptance

A regression that applies the same program row twice through
`program-row-tx` and asserts the second transaction commits zero datoms for
`:seon.fn/ns`, `:seon.fn/arities`, and `:seon.fn/ast`, with the entity id
and every component identity unchanged. A companion case where the source
genuinely differs must still retract and rebuild exactly the changed
attributes.

## Closed 2026-08-04

`seon.cluster.run/program-row-tx` now compares canonical declared content
before constructing exact replacement transaction data. Reference attributes
are normalized to lookup refs and component entity identities are excluded as
database mechanics. An identical declaration therefore emits zero transaction
data, while a changed source still emits and commits the real replacement.

`seon.program-test` proves the correct `[database row]` argument order plus
both identical and changed cases: 13 tests and 61 assertions passed. The real
boot-tower gate additionally passed 28 tests and 137 assertions.
