---
type: issue
status: open
severity: friction
tags: [issue, render, context, runtime, notes]
---

# The runtime block's trigger prints a bare `#:db{:id}` and an empty notes read prints `nil`

Read on a reseeded `default` after `5af34fc38` (2026-09-09 20:55). The
runtime block pulls its trigger with `[*]`, so `:seon.message/from`
shows `#:db{:id 36216}` instead of the agent id; the selector should name
the trigger's fields (id, content, `{:seon.message/from [:seon.agent/id]}`).
The empty notes reverse pull prints `nil` where the inbox convention shows
`[]`; the comment says "an empty read", so the projection should show an
empty collection. Both are one-line selector/projection changes in the
block render functions.
