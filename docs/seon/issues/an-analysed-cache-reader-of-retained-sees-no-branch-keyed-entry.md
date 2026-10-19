---
type: issue
status: open
severity: medium
created: 2026-10-20
tags: [issue, sci, branch-runtime, cache]
---

# An analysed reader of `seon.cache/retained` sees no branch-keyed entry

`seon.sci.runtime/keyed-entries` pairs the content key of `seon.cache/lookup`, `lookup-within`, `present` and `evict!`
with the runtime's code, but not `seon.cache/retained`, which answers every entry of a kind with its raw key. A changed
runtime's `seon.db/nearest-base` (analysed when its reach changes) therefore lists `::projection-alias` entries whose
keys are `[alias-key code]` vectors, which its `(map? k)` filter drops: the branch's own derived projections are never
candidates for its nearest base, so a branch derives from main's projections or from `{}`. Correctness holds (any base
derives the same projection); the cost is a derivation that could have replaced from a nearer base. Found by lane
runtime-fix4 while reading the census of cache keyers; not reproduced as a measured cost.
