---
type: issue
status: open
severity: friction
tags: [issue, web, database, flow, architecture]
---

# Thread one database value through debug and data feeds

## Problem

The debug and data feeds start `capture-reads` with one dereferenced replica
value but their initial-render thunks dereference the connection again. Those
separately reconstituted immutable values are not identical, so the observer
classifies reads made through the second value as foreign and non-replayable.
Every committed transaction can therefore conservatively rebuild an open
debug or data view even when none of its semantic results changed.

## Evidence

`seon.web.debug/debug-feed-definition` passes `@db/*conn*` to
`seon.web.datastar/render-observed`, while its zero-argument `render-debug`
calls `debug-projection`, whose implementation reads through the ambient
connection again. The same definition does not accept and thread the
transaction's `:seon.db/db` into the complete projection.

`seon.web.debug/data-feed-definition` has the same initial-render shape: it
passes one `@db/*conn*` value into `render-observed`, then calls
`data-browser-fragment` with a second `@db/*conn*`. Its change transition is
already correct because it threads the change's explicit `:seon.db/db` into
the thunk. The shared defect is therefore the initial feed-plan boundary, not
either page's database projections.

A read-only CLJS REPL probe against the live default cluster on 2026-07-14
inspected the one open root-debug subscription. It retained 657 observations:
129 queries, 382 lazy entities, 55 touched entities, 44 installed-schema reads,
20 pulls, 16 history reads, 6 basis reads, 4 reverse-index reads, and one index
read. All 657 carried `:seon.db/read-replayable? false`; none was replayable.
Earlier runtime profiles measured open debug renders between roughly 220 ms and
1,100 ms.

## Owner

The one reactive unit transition in `seon.web.view-unit`, with
`seon.web.debug` reduced to unit composition and presentation. The producer
must receive the transition's frozen database value explicitly; do not add a
debug-specific cache or another listener.

## Acceptance

- One immutable database value is threaded through debug/data plans and unit
  producers for a transition.
- Replayable query, pull, entity, schema, and index observations remain
  replayable; genuinely lazy, temporal, or unknown operations stay broad.
- An unrelated transaction invokes zero debug renderers, SCI bodies, Hiccup
  serialization, or token estimation for unchanged active units.
- A relevant transaction updates exactly the affected open debug unit, while
  closed units perform no reads or rendering.
