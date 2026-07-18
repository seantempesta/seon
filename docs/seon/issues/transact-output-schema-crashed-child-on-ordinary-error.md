---
type: issue
status: open
tags: [database, agent, issue]
---

# Transact output schema crashed child on ordinary error

## Evidence

A live agent called `complete`, which transacts the run's terminal facts. The
writer returned an ordinary `:seon.error/message` map. `seon.db/transact!`
already documents and implements errors as values, but its Malli output named
only a successful transaction report. Instrumentation rejected the error map
as invalid output, recorded a core fault, and exited the execution child.

## Expected owner

The public `:seon.db/transact-response` schema is the union of Datahike's
transaction report data and Seon's ordinary database error data. Callers inspect
the returned value; instrumentation never turns a recoverable writer response
into a process crash.

## Acceptance

- Focused database facade tests cover a failed transaction under
  instrumentation and receive the original error map.
- A real agent can call `complete` without its execution child exiting.
