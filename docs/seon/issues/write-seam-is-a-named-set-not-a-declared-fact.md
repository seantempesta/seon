---
type: issue
status: open
severity: cleanup
tags: [issue, program-graph, analyzer, class/p2]
---

# The database write seam is a named set in the indexer, not a declared fact

`seon.fn/write-seam-symbols` (`src/seon/fn.clj`) is the set of function
symbols whose call spans `:seon.fn/writes` joins keywords against. It holds
exactly one member today, `"seon.db/transact!"`, and it is a hand-maintained
mirror of a fact the program graph could declare: AGENTS.md §2.2 rules that
every exception must be a COMPUTED rule and §3 that no hand-maintained list
stands in for a query.

One member is cheap to keep honest, and the derivation was out of scope for
the lane that landed the attribute (`docs/prds/steward-platform/research/
analyzer-facets-2026-09-16.md` §2), but the shape is the defect class: a
second write entry point added anywhere else makes `:seon.fn/writes`
silently under-report, and an under-reporting set is precisely the check
that reads absence of signal as health.

**The fix.** Declare the seam where it lives — a marker in
`seon.db/transact!`'s own metadata, read by the analyzer from the
var-definition `:meta` it already requests — and derive the set from the
analysis instead of naming it in `seon.fn`. Then delete
`write-seam-symbols`. One regression: a fixture function marked as a seam
contributes its span's keywords, and removing the marker removes them.

Discovered 2026-09-16 by the analyzer lane whose note is
`analyzer-facets-2026-09-16.md`, while landing `:seon.fn/writes`.
