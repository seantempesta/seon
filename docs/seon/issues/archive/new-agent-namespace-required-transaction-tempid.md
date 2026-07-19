---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, database]
---

# New agent namespace required transaction tempid

## Problem

The first live absent-namespace transaction used the new agent's
`:seon.agent/namespace` lookup ref to name the `:seon.ns/name` entity created in
the same transaction. Datahike resolves lookup refs against `db-before`, so it
rejected the unresolved ref with `:entity-id/missing`. Merely ordering the
namespace row before the agent row did not change lookup-ref resolution.

## Resolution

An absent namespace row now carries a transaction-local string tempid, and the
new agent's ref uses that same tempid. The transaction orders namespace, agent,
then initial message. Existing namespaces continue to use ordinary
`:seon.ns/name` lookup refs and their program declaration is never rewritten.

## Acceptance

- A new namespace and agent resolve through one shared transaction tempid.
- The agent identity precedes the initial message that references it.
- Existing namespace source and require edges remain unchanged.
