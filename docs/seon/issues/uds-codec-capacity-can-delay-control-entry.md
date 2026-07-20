---
type: issue
status: open
severity: blocker
tags: [issue, database, flow]
---

# Keep database control entry independent of occupied handlers

## Problem

Unix-socket decode, connection opening, and database handler entry share one
bounded codec executor. Long handler entry can occupy every worker even when
the selector remains responsive, delaying a small control request behind
unrelated database work.

## Evidence

`codec-workers-currently-bound-control-entry` occupies the executor's exact
configured worker count with latched handlers. A fifth control request cannot
enter until one handler releases. Increasing the worker count only changes the
number of requests required to reproduce the same structural starvation.

## Owner

The database authority mesh owns the transport admission boundary. Decide from
measured request classes whether control needs a small decode/admission floor or
a fair codec dispatcher; do not add an unbounded emergency executor.

## Acceptance

- A control request enters while every heavy handler is held.
- Decode, open, input bytes, response slots, and output bytes remain bounded.
- Saturation cannot create threads or bypass per-session ordering.
- Shutdown proves every codec/control worker stopped.
