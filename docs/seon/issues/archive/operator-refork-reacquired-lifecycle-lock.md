---
type: issue
status: resolved
severity: blocker
tags: [issue, operator, lifecycle, testing]
---

# Keep forced refork on one lifecycle-lock acquisition

## Problem

`bin/seon init NAME --force` held the installation lifecycle lock and called
the public `seon.operator/refork!`. Refork composed public
`cleanup-cluster!`, which attempted to acquire the same non-reentrant file
lock. The forced refork wedged indefinitely and blocked `bin/seon status`
behind the held lock.

## Evidence

A live forced refork remained blocked for more than five minutes. A
virtual-thread-aware dump located the outer acquisition in the operator
command and the second acquisition in `seon.operator/cleanup-cluster!` at
`FileChannel.lock`.

After splitting the internal arms, the recurring real-process proof completed
`bin/seon init NAME --force`, started the replacement cluster, and read its
`:seon.boot/ready-ms`. The focused operator gate passed 18 tests / 95
assertions; the exact subprocess regression passed 1 test / 19 assertions.

## Owner

`src/seon/operator.clj` owns public lifecycle operations and their internal
under-lock composition. `script/seon/fresh_operator.clj` owns the command-level
lock interval. `resources/seon/operator/state.clj` owns the file lock and exact
external claims.

## Acceptance

- Public cleanup and refork operations each acquire the lifecycle lock once.
- A command already holding the lock calls only internal under-lock arms.
- Cleanup reads the addressed root claim rather than depending on unrelated
  installation claims.
- Forced refork completes on a real store and the replacement reaches READY.
- No public `:seon.operator/control-lock-held?` assertion exists.

## Resolution

Resolved by the commit containing this note. Cluster cleanup and refork now
have private under-lock arms. Public entry points acquire once; the forced-init
form resolves the private refork arm while its command lock is held. Refork
retains one operation-store interval across cleanup and branch creation, and
the addressed cleanup reads only its exact external root claim.
