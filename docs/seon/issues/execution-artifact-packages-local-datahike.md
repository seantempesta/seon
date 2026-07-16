---
type: issue
status: open
severity: friction
tags: [issue, architecture, database, agent]
---

# Remove local Datahike ownership from execution children

## Problem

Every per-agent Bun child must perform database work through the remote
authority session without constructing or maintaining a Datahike connection,
immutable indexed database value, query cache, transaction replay, or full-feed
subscription. Duplicating any of that live state multiplies database memory and
maintenance work with the number of children, defeating modest-hardware
density and shared computation.

## Evidence

The `:execution` Shadow build starts at `seon.execution/-main`, and the child
already opens a direct authority session. The remaining local `seon.db`
implementation and replica/replay call sites make a second live database owner
possible. JavaScript package contents or bytes are not themselves a performance
failure: compiler reachability is useful only as a static guard against
accidentally restoring a live local mechanism. Runtime ownership and memory
scaling are the decisive evidence.

## Owner

The existing `seon.db` implementation and execution-child runtime boundary.
Preserve the one public `seon.db` function/schema interface while making its
authority session the only production implementation; do not create a second
database API or cache.

## Acceptance

- Runtime evidence proves no Bun child owns a Datahike connection, indexed
  database value, query cache, transaction replay, or full-feed subscription.
- Eight children issuing one identical coordinate-pinned read produce one JVM
  cache miss owner, seven joined callers, one physical read, and then a cache
  hit, with all retained in-flight state released afterward.
- Increasing database/index size changes authority memory but does not create a
  corresponding retained-memory slope in Bun children; retiring a child drops
  its process while a replacement can reuse the authority cache.
- Existing `seon.db` callers and ordinary protocol fixtures remain unchanged.
- Static Shadow reachability prevents an accidental local owner from returning,
  but package size is not a graduation or performance metric.
- The 1/4/16/32 density matrix uses the remote-only artifact when selecting the
  shipped child cap.
