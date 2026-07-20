---
type: issue
status: resolved
severity: blocker
tags: [issue, architecture, database, agent]
---

# Remove replica event language from target architecture

## Resolution

The data-model and agent-runtime targets now distinguish durable transaction
history from live selective interests. Mutation request IDs remain transaction
response and receipt-recovery identities; live events carry the existing
interest request ID. Bun processes reread current truth at a coordinate and do
not correlate or replay transactions into a local replica.

## Original problem

The intended architecture already prohibited Bun Datahike replicas, but two
older passages still described mutation-event correlation with a replica and
called `since` replay the live event stream. Those statements contradicted the
authority mesh and could preserve the global feed under a different name.
