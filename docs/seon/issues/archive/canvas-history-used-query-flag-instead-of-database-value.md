---
type: issue
status: resolved
severity: blocker
tags: [issue, database, web, cljs]
---

# Canvas history used a query flag instead of a database value

## Problem

Canvas renderer selection put `:seon.db.protocol/history?` on an
`execute-many` query member. Datahike history is a database value, and the
closed database protocol correctly rejected the invented query option. A new
agent with an authored canvas therefore failed its complete web UI render.

## Evidence

The real `fresh-kiwis-lay` child returned Malli's `:malli.core/extra-key` at
`[:seon.db.protocol/members 0 :seon.db.protocol/history?]`. The resulting error
also carried Malli's host-owned schema object and was rejected by the execution
child's ordinary-data boundary.

## Owner

`seon.agent.ctx.canvas/acquire-canvas!` owns canvas renderer selection. It must
pass a Datahike history database value through `seon.db/execute-many`; the
database protocol must not grow a second history mechanism.

## Acceptance

- Canvas history selection associates `:history true` with the immutable
  database value.
- Query members contain only the existing query operation fields.
- Focused canvas tests prove the exact database values used by candidate and
  history acquisition.
- The real cross-namespace canvas renders and submits through the browser.

## Resolution

The owning code now sends `(assoc database :history true)` for its history
query and removes the invalid member flag. Focused proof passes 8 tests / 31
assertions. The browser interaction portion of acceptance remains the immediate
integrated proof before this resolution is considered graduated.
