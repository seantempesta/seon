---
type: defect
status: open
severity: blocker
tags: [operator, indexing, tests]
---

# bin/seon init is red: indexed test reaches script-side seon.dev.markdown

`bin/seon init` exits 1 at HEAD (~17.9s in) on unresolved
`md/validate-repository-pins` at `test/seon/dev/markdown_test.clj:203`:
the implementation lives in `script/seon/dev/markdown.clj` (moved there
by `fd9777027`, operator-boundary intent), outside the indexed
`src/`+`test/` inputs. `bin/test`'s own program-graph build still
passes (observed green 2026-08-17), so the breakage is publication-
specific — but it blocks every task that must time or run full init
(the graph-enrichment lanes hit it first; schema-references stopped
honestly on it). Fix directions, owner to pick: (a) move the test to
the script side with its subject (removes it from the gate — an
absence-as-health cost that must be named, not slid); (b) move
`seon.dev.markdown` back into `src/` as first-party; (c) teach init's
analysis that script-side namespaces are name-only externals like
other non-indexed requires. (c) matches the existing name-only
external precedent and keeps the gate.
