---
type: issue
status: superseded
severity: blocker
tags: [issue, program-graph, indexing]
---

# Make function coverage independent and cardinality-preserving

## Resolution

The issue described the retired reader-built repository index. Static source
indexing now consumes clj-kondo analysis directly and does not use
`seon.sci.reader` as either the definition producer or its own coverage
oracle. `seon.fn/assert-one-row-per-identity!` refuses duplicate program
identities rather than collapsing them. Adding another parser or declaration
census would recreate the second mechanism this issue sought to remove.
