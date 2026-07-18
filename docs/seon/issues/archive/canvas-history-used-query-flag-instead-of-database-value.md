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

The same live proof exposed a second part of the root cause: selection dropped
the chosen renderer's existing `:seon.fn/read-attrs`, so the one Datastar feed
could render the new value on demand but did not rerender for its transaction.

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
- The selected renderer's `:seon.fn/read-attrs` become the surface and feed
  dependencies, so its transaction rerenders the already-open feed.

## Resolution

Commit `842d335f` sends `(assoc database :history true)` for the history query
and removes the invalid member flag. Commit `a8e845c6` preserves the selected
renderer's `:seon.fn/read-attrs` through canvas acquisition and surface
materialization. Focused canvas proof passes 9 tests / 33 assertions and
execution-runtime proof passes 13 tests / 71 assertions.

After a complete clean watcher/writer/pod restart, Chrome rendered the
cross-namespace `my.interaction.view/view`, submitted
`reactive-1784413316743` through `my.interaction.actions/save!`, and the same
open feed morphed to `Saved: reactive-1784413316743` with no console errors.
The database retained the exact value and no reload or second feed was used.
