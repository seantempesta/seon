---
type: research
status: active
tags: [research, database, architecture]
---

# Connection acquisition and database lifetime

## Question

How can several persistent Bun connections share one Datahike connection while
duplicate acquire/close callbacks, crashes, and final release remain exact?

## Dependency ledger

- Datahike `d7ac886f333ed65b9205b5e3515897caafd4e33a`:
  `connections.cljc`, `connector.cljc`, and `connector_release_test.clj`.
- Konserve: `core.cljc` acquire/release transformations.
- Seon: `seon.db.registry`, `seon.db.writer`, and the registry/writer tests.

## Source result

Datahike already owns the resource count. Every matching `d/connect` atomically
adds one reference and returns the identical connection. Every ordinary
`d/release` removes one reference; only the final release closes query-cache and
committed-report admission, drains the writer, closes secondary indexes, and
releases the store. A reconnect cannot join a generation whose final release is
in progress.

Seon therefore must not add a second numeric count. The registry retains only
the exact set of live JVM socket connections that acquired each database so an
acquire and a close are idempotent. The socket object itself is the internal
identity; no session ID, token, or lease enters Transit or database data.

Administrative ensure/release and Bun connection acquire/close are distinct.
The first Bun connection may take ownership of the reference established by
startup ensure; later distinct connections call `d/connect` once. An
administrative ensure while connections are live adds its own Datahike
reference. Each exact membership removal calls `d/release` once. When neither
an administrative reference nor a live socket connection remains, the entry is
claimed for final release before draining, then removed only after Datahike
release succeeds.

Final release cannot hold the registry-wide lock while writer drain or store
close runs. The entry first becomes unavailable to resolution/acquisition; the
exact database scope drains outside the global lock; then Datahike performs its
final release. An acquire during that interval receives a retryable failure and
reopens into a new Datahike generation after completion. Unrelated databases
remain independently operable.

## Required proof

- duplicate acquire/close changes the Datahike count once;
- closing connection A cannot fence a query owned by sibling B;
- administrative release preserves live Bun connections;
- final close versus reacquire cannot resurrect the closing generation;
- a late close from the old socket cannot affect a reopened database;
- final close waits for admitted mutation/read cleanup;
- release failure remains cleanup-required; and
- one crashed connection releases every acquired database once without one
  failure skipping the remaining databases.
