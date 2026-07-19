---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, database]
---

# New agent namespace ref preceded namespace entity

## Problem

The first live absent-namespace transaction placed the new agent's
`:seon.agent/namespace` lookup ref before the `:seon.ns/name` entity it named.
Datahike correctly rejected the unresolved lookup ref with
`:entity-id/missing`. Generated home namespaces had masked the ordering error
because their program entities already existed.

## Resolution

When the namespace is absent, the one birth transaction now orders namespace
entity, agent entity, then initial message. An existing namespace still emits
only the agent and message rows and never overwrites its program declaration.

## Acceptance

- A new namespace lookup ref resolves within the birth transaction.
- The agent identity precedes the initial message that references it.
- Existing namespace source and require edges remain unchanged.
