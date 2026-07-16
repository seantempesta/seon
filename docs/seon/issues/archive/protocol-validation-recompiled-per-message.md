---
type: issue
status: completed
tags: [issue, database, flow]
---

# Protocol validation recompiled schemas per message

## Failure

`seon.db.protocol/valid-request?` and `valid-response?` called `m/validate` with
the registry keyword on every message. Malli documents that `validate` creates
the validator on every call. Under the asynchronous embedding saturation test,
a primary transaction had already committed but spent more than five seconds
in `malli.core/schema` while another response was validated concurrently.

This cost would multiply across every request and page on persistent Bun
sessions and showed up as avoidable CPU work rather than database contention.

## Resolution

The protocol now recursively resolves its fixed request, response, and writer
terminal schemas once and retains compiled validators and explainers. Protocol
validation still covers the same registered forms; ordinary messages no longer
walk and compile the registry graph.

## Proof

The queue-saturation transaction regression now passes: primary writes return
while the embedding provider is blocked and after the per-database embedding
queue is full. In one warmed JVM, 10,000 valid ping responses took 2.38 ms
through the retained validator versus 621.69 ms through repeated
`m/validate`, approximately 261 times less validation time in this probe.
