---
type: issue
status: open
tags: [database, issue]
---

# Turn debug treated a database error as an entity id

## Evidence

During a sustained real-agent run on 2026-07-17, the database session reached
its fixed in-flight request limit. `seon.agent.debug/turn` received the ordinary
`:seon.error/message` map from its entity query, treated the truthy map as a
Datahike entity id, and passed it to `seon.db/pull`. Malli then threw from the
final `/agents/run` evidence read and repeated debug reads amplified the fault.

## Expected owner

`seon.agent.debug/turn` preserves database errors as its documented
`::ok? false` response and never uses an error value as a pull ref. The UDS
request-capacity cause is investigated separately; this adapter must remain
correct under every ordinary database error.

## Acceptance

- A focused regression proves that a failed entity query does not call pull.
- The real `/agents/run` final evidence path returns structured error data when
  database capacity is unavailable and never throws a Malli input error.
