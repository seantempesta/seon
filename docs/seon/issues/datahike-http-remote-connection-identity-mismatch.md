---
type: issue
status: open
severity: friction
tags: [issue, database, flow]
---

# Align Datahike HTTP remote connection identity

## Problem

Datahike's legacy HTTP writer transport serializes a server-side database
connection identity that does not match the client-side remote connection
registry key. Remote writer tests therefore cannot resolve the returned
database through the active connection registry.

Seon does not use this transport: its JVM writer and CLJS replica communicate
through `seon.db.protocol` and the typed local writer boundary. The defect does
not block Seon's dependency cutover or runtime admission, but it remains a
real maintained-Datahike failure.

## Evidence

The server serializes the local database identity as `[store branch]`, while
the HTTP client registers and looks up the remote connection as
`[store branch :datahike-server]`. The focused Datahike HTTP writer suite fails
three cases at that mismatch. The adjacent strict lifecycle, secondary, and
Node gates remain green, so this is isolated from active-connection deletion
and guarded secondary force.

## Owner

Datahike's HTTP client/writer connection serialization and registry identity
boundary in `datahike.http.client`, `datahike.http.writer`, and
`datahike.store/connection-id`.

## Acceptance

- One canonical remote connection identity is used for registration,
  serialization, dereference, release, and deletion.
- A focused HTTP writer round trip creates, transacts, dereferences, releases,
  and deletes without an identity lookup miss.
- Local `[store branch]` connections remain distinct from remote
  `[store branch :datahike-server]` connections where both coexist.
- The complete Datahike HTTP writer suite passes without weakening strict
  active-connection deletion.
