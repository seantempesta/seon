---
type: issue
status: open
severity: friction
created: 2026-09-25
tags: [issue, db, render, performance, agent-platform]
---

# declares-program? is recomputed per rendered node

`seon.render/project-node*` (src/seon/render.clj:1196) calls `request-projection` for every
node, and `request-projection` calls `db/carried-projection` (render.clj:120).
`carried-projection` first evaluates `declares-program?` (src/seon/db.clj:1394-1398), which
reads `(dbi/-schema database)` and `(first (d/datoms database :avet :seon.schema/key))` on
every call, before any memoized tier is consulted.

Measured with 100 ms thread samples of one batched turn fixture (lane fix-turn-hang-opus,
pid 23282): 32 of 366 samples (about 3 s) were in `declares-program?` under
`carried-projection` ← `request-projection` ← `project-node*` ← `value-node*`.

Cost target: once per render request. Derive the projection once where the request is
built and hand it on (`:seon.schema/projection`, which `request-projection` already
prefers), or check the memoized tiers before `declares-program?`.
