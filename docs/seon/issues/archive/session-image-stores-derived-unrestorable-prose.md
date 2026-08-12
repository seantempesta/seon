---
type: issue
status: superseded
severity: friction
tags: [issue, sci, database, errors]
---

# Keep session-image refusal evidence as facts, not derived prose

The deleted session-image path stored a derived English replay refusal while
discarding the observations that produced it. The 2026-08-05 owner ruling
superseded that mechanism: the agent's defs' restore ladder deliberately stores
one honest `:seon.def/unrestorable-reason` only after both pure source and a
store-faithful value are unavailable.

Commits `c124ffe56` and `f908a5939` delete the old write/restore path and its
tests. The replacement does not claim replay provenance it cannot preserve;
it states the exact persisted loss reason during each fresh turn fork.
