---
type: issue
status: open
severity: friction
tags: [issue, database, flow]
---

# Share concurrent database session opening

## Problem

`seon.db/open-session!` is idempotent after one physical session is connected,
but two same-selection calls during the native handshake do not share that
work. The first call stores an owner placeholder; the second observes neither a
connected session nor a reusable opening result and reports a conflicting
process owner.

Normal cold start currently has one caller, so this does not invalidate the
direct-session cut. It is still the wrong resilience contract for concurrent
startup, hot reload, or a later supervisor retry.

## Evidence

- The initial `swap!` in `seon.db/open-session!` stores `::owner` and
  `::selection` before `uds/connect!` begins.
- The same-selection branch only reuses a state containing a connected
  `::session`; it cannot join the in-progress handshake.
- A different selection must remain a loud conflict because one Bun process
  owns exactly one database attachment.

The first correction now stores the complete opening Promise in the one session
state. A same-selection caller joins it; publication succeeds only while the
original owner still owns the state. Focused proof performs two concurrent
opens and observes one native connect, one capability/ensure/acquire sequence,
and equal ordinary results. The session gate passes 7 tests and 41 assertions.
Failure fanout, conflicting selection, and close-during-opening remain to close
this issue.

## Owner

The existing process-local session state in `seon.db`. The native transport
must remain unaware of database selection and acquisition.

## Acceptance

- Two or more concurrent calls with the same selection perform exactly one
  native connect, capability negotiation, ensure, and acquire sequence and
  receive the same ordinary result.
- A shared handshake failure settles every caller and leaves no session state,
  socket, interest handler, or rejected Promise behind.
- A conflicting selection still fails without disturbing the opening or live
  session.
- Physical close during opening cannot publish a connected-looking result.
