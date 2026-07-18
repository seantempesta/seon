---
type: issue
status: resolved
severity: blocker
tags: [issue, database, agent]
---

# Serialized mutation queue rejected normal agent burst

## Problem

Sixteen simultaneous `POST /agents` requests admitted nine births and rejected
seven with “The database work queue is full, fenced, or stopped.” Datahike
correctly serializes writes for one database, so one mutation was running and
the executor's fixed per-database queue of eight was the limiting factor. This
was an admission budget, not CPU or memory exhaustion.

## Resolution

The mutation class now admits at least 64 queued requests per process and per
database. The existing queued-request-byte allowance remains the process-wide
memory bound, while the sole database writer continues to serialize each
database and distinct databases retain fair rotation.

## Evidence

- Before the change, the live 16-request burst produced exactly nine successes
  and seven queue-capacity failures.
- The focused executor gate passes 26 tests and 674 assertions.
- After a source-frozen rebuild, 32 simultaneous `POST /agents` requests
  returned 32 HTTP 200 responses with 32 distinct agent IDs in 8.25 seconds.
- The immediately preceding complete writer gate passed 218 tests and 1,813
  assertions; the executor change itself is covered by the focused gate and
  remains included in the next source-frozen complete checkpoint.
