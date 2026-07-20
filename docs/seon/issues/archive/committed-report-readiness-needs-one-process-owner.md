---
type: issue
status: resolved
severity: blocker
tags: [issue, database, flow]
---

# Own committed-report readiness once per JVM authority

## Problem

Datahike's committed-report readiness queue is process-global. Starting one
consumer thread per writer runtime allowed one runtime to take another
runtime's source and lose its wake-up. Starting the thread before publishing
the complete runtime also exposed a nil runtime-reference race.

## Resolution

One process-wide readiness thread now routes each source through the existing
source-to-scope reverse index to its exact registered runtime. A source that
becomes ready during ownership publication is requeued without consuming a
report. The first runtime starts the thread, the final runtime interrupts and
joins it, and runtime publication precedes readiness registration.

Commit `2750c158` proves two concurrent runtimes route their sources to their
own executors, including a source ready before owner publication, and retain
zero runtime or thread state after final unregister. The integrated focused
gate passes 46 tests and 849 assertions.
