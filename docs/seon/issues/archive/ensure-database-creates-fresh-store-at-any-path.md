---
type: issue
status: resolved
severity: blocker
tags: [issue, database, architecture]
---

# ensure-database silently creates a fresh store at any requested path

## Problem

`:seon.db.protocol.operation/ensure-database` with an existing database
NAME but a wrong `::protocol/database-path` silently initializes a brand
new empty store at that path and registers it under the name — there is
no "name exists elsewhere" or "store absent, refusing to create without
explicit initialization intent" guard.

Observed live (2026-07-20, U1 host lane): with the pod drained the
writer had released "default"; an ensure naming
`data/clusters/default/store` (the real store is
`data/clusters/default/db`) created an 11-file empty store at that path
and answered with a fresh `t 536870913` head registered as "default".
Any client that then resolved "default" saw an empty database. Recovery
was benign only because the wrong registration released when the ensuring
connection closed and the junk directory was deleted by hand; the real
store was never touched.

## Expected owner

`seon.db.writer`'s ensure path (`src/seon/db/writer.clj`). The request
schema already carries an optional `:seon.db/initialization` value —
creation could be gated on its presence, making a bare ensure an
open-existing-only operation that fails loudly when the store at the
path is absent or empty.

## Acceptance

- A bare ensure (no `:seon.db/initialization`) of a path with no
  existing store returns a not-found/absent error instead of creating.
- An ensure whose path disagrees with an already-known store location
  for the same name fails loudly.
- Writer tests cover both refusals.

## Notes

The U1 host mitigates on its side: `seon.host.context` sends ensure only
with explicitly configured backend/path (never guessed), and only after
a not-found head resolution. That narrows the host's exposure but does
not remove the writer-side hazard for other clients.

## Resolution

Resolved on 2026-07-23 by `d0a73db8e`. The writer now derives explicit
file-database creation authority only from its startup path or a supplied
initialization page. The registry's one open/create choke point calls
Datahike's `database-exists?` first and returns
`:seon.db.protocol.error/not-found` before creating a parent directory or
store when that authority is absent. The existing logical-route validation
continues to reject a known database name whose backend path changes.

Recurring proof lives in
`seon.db.writer-initialization-test/bare-file-ensure-refuses-an-absent-store`
and
`seon.db.writer-initialization-test/bare-file-ensure-refuses-a-known-name-at-another-path`.
The focused writer-initialization gate passed 14 tests / 83 assertions and
the registry lifecycle gate passed 23 tests / 141 assertions; the captured
run is `tmp/orchestrator/ensurepath-gate.log`.
