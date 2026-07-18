---
type: issue
status: open
tags: [database, issue, pod, web]
---

# Pod database session capacity was smaller than real feed concurrency

## Evidence

A real agent run with five independent Datastar feeds filled the CLJS
database session's 16 pending-request entries. Reads have a 30-second deadline,
and correctly retain their request ID after timeout until the JVM finishes or
cancels the physical database work. While those entries remained owned, every
new database call failed immediately with
`:seon.db.transport.uds.failure/busy`.

The JVM UDS transport already bounds its codec worker queue at 256 entries.
The CLJS session limit of 16 was an unrelated hard-coded assumption and was too
small for the application's supported feed and agent concurrency.

## Expected owner

`seon.db.transport.uds` owns one bounded per-session pending-request map. Its
capacity permits ordinary concurrent feeds and agent work while still bounding
memory and preserving request identity until physical completion. It does not
discard timed-out request IDs or add retries.

## Acceptance

- The transport test fills the selected capacity with timed-out physical work
  and proves that one more request receives the existing busy error.
- A sustained real-agent run with five feeds does not exhaust session capacity.
- Pending requests retire after the JVM completes or cancels physical work;
  capacity does not leak across the recovery proof.
