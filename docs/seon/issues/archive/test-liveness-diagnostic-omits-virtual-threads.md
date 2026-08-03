---
type: issue
status: resolved
severity: blocker
tags: [issue, testing, runtime, flow]
---

# Retain virtual threads in test liveness diagnostics

## Problem

The `bin/test` liveness backstop called `ThreadMXBean.dumpAllThreads`, which
omits virtual threads, while labeling the result a full JVM thread dump. A
diagnosis consequently inferred that no Flow turn existed even though the turn
was active on a virtual thread.

## Evidence

The retained MXBean diagnostic showed only the main thread waiting in
`disarm!`. A subsequent `jcmd Thread.dump_to_file -format=json` against the
same failure class included a virtual thread in `turn-step` → `call-turn` →
`refused!` → `db/transact!`, disproving the missing-completion attribution.

## Owner

`seon.test.runner` owns the suite liveness diagnostic retained by `bin/test`.

## Acceptance

- Backstop firing retains a JVM dump that includes virtual threads and reports
  its path beside the ordinary diagnostic log.
- The MXBean output is labeled only as a platform-thread supplement.
- A recurring test parks a named virtual thread and finds both its name and
  virtual marker in the retained dump.
- Failure of the foreign diagnostic process is reported rather than hiding or
  delaying the original liveness incident indefinitely.

## Resolution

The containing path-limited repair invokes the current JDK's `jcmd` as a
foreign diagnostic process and retains `Thread.dump_to_file -format=json`
under `tmp/test-liveness`. Its ten-second last-resort bound applies only to
that external process. The existing MXBean deadlock and monitor detail remains
as an explicitly platform-thread-only supplement. The regression creates a
named virtual thread, obtains the production dump, and verifies the JSON
contains that thread and `"virtual": true`.
