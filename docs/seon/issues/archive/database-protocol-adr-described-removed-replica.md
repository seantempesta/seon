---
type: issue
status: closed
severity: high
tags: [issue, architecture, database]
---

# Database protocol ADR described the removed replica target

## Problem

Active ADR-008 still described local pod reads, committed transaction frame
broadcast, and replay into a CLJS Datahike replica. The always-current
architecture and UI documents instead define one indexed database authority,
direct Bun clients, selective interests, and no client replica or transaction
feed. The contradictory active decision could cause a future implementation to
restore a superseded mechanism.

## Evidence

`docs/seon/architecture/architecture.md` requires coordinate-addressed reads
against the authority and explicitly excludes a Bun Datahike replica.
`docs/seon/architecture/ui.md` requires one selective committed-interest path
and explicitly excludes a global transaction broadcast. ADR-008 stated the
opposite in both its context and decision.

## Resolution

ADR-008 now defines the protocol at the settled authority seam: ordinary data,
exact coordinates, persistent multiplexed sessions, authority-owned shared
Datahike computation, selective connection-owned interests, and durable
mutation receipts. It explicitly excludes the replica, broadcast/replay,
broker, and duplicate cache/listener paths. The architecture grep now leaves no
active decision that authorizes the superseded replica target.
