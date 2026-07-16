---
type: issue
status: resolved
severity: blocker
tags: [issue, database, flow]
---

# Retain selector sessions through physical cleanup

## Problem

The first bounded Java NIO selector cut removed a closed socket from connection
admission before its asynchronous database cleanup completed. Reconnect churn
could therefore exceed the cleanup queue even though the number of open sockets
never exceeded its limit. A rejected cleanup only logged the failure and could
retain a database acquisition indefinitely.

Forced shutdown also allowed the selector to stop while a response encoder
still owned a response slot. Its later completion could remain in an undrained
command queue. Concurrent input release and reservation could lose an update in
the shared byte count, and the writer ignored negative transport-shutdown
evidence before releasing Datahike authority.

## Resolution

One connection admission now covers opening, open, closing, response encoding,
and database cleanup. The session leaves admission only after its response
slots and exact physical cleanup are both complete. Input reservation and
release share one atomic critical section. Shutdown stops producers before
draining their final selector commands, reports incomplete worker cleanup
honestly, and the writer retains its executor and databases unless selector,
codec, and cleanup workers all stopped.

Startup failure closes the server channel, selector, and both bounded worker
pools. No rejected-cleanup thread or silent ownership loss remains.

## Acceptance

- Disconnect bursts retain every closing session until its exact cleanup ends.
- A forced close during response encoding either drains the final slot and
  frame or reports that workers did not stop; a later close can finish safely.
- Input, output, response-slot, command, connection, and active-owner counts
  return to zero after complete shutdown.
- Writer shutdown never releases a database while transport-owned work remains.
- Focused selector, executor, and writer integration proof passes.
