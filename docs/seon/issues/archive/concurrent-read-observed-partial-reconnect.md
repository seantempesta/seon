---
type: issue
status: resolved
severity: blocker
tags: [issue, database, cljs]
---

# Concurrent read observed partial reconnect

## Problem

After a physical writer session closed, the reconnect owner published the new
connected socket before capability negotiation, database acquisition, and
listener restoration completed. A concurrent ordinary read treated that
partial state as active and attempted `resolve-head` with a nil database name.
The pod and writer stayed alive, but agent work could not read the database.

## Resolution

The existing session owner now reports a session as active only after its
database name has been installed by the single completed reconnect transition.
Concurrent reads observe the existing opening promise and share its complete
result.

## Evidence

The focused remote-contract regression holds reconnect capability negotiation
open, starts a second database read, and proves both reads return the same
database value through one reconnect. Live model-drive evidence is recorded in
the database-authority roadmap.
