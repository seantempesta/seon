---
type: issue
status: open
severity: friction
tags: [issue, operator, class/n7, wave/operator-process-identity]
---

# Operator down gives different process censuses for the same scratch root

Components cleanup, 2026-09-08, used the explicit root
`/Users/sean/src/seon/tmp/components-root`.

The main checkout's `bin/seon --root tmp/components-root down --force`
reported `records=0 unreadable=0` and `no recorded JVMs to stop`. The JVM
remained alive. Its advertisement identified PID 8533, start instant
`2026-09-08T23:16:10.344Z`, cluster `components`, PREPL 51722, HTTP 7809.

The creating checkout's `tmp/components-wt/bin/seon --root
/Users/sean/src/seon/tmp/components-root status` then reported that same
live JVM with generation `cb80f90a-b2a7-4a79-ad7c-126fe821e6d2`. Its
`down --force` reported `records=1`, stopped that exact recorded identity
through the operator's SIGTERM path, and removed the stale advertisement.
The process table confirmed PID 8533 was gone before deleting the root.
No JVM was killed by a command-line substring match.

The two invoking source checkouts were main at `8b48a7c47` plus unrelated
working edits, and `2531b2e70` plus the components files. This establishes
the census discrepancy; it does not establish whether source version or
source-checkout location is its cause. The operator owner should reproduce
that distinction and ensure an explicit operator root selects one process
record authority, independent of the invoking source checkout. An empty
census must not imply successful cleanup while that root advertises a live
recorded JVM.
