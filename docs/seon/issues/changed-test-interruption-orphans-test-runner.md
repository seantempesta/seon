---
type: issue
status: open
severity: blocker
tags: [issue, cljs, flow]
---

# Interrupted changed-test hooks can orphan the CLJS runner pipeline

## Problem

Stopping the Babashka `test changed` parent does not reliably unwind its
`bin/test-cljs` shell, node, `tee`, and output-filter descendants. Reparented
children can keep the shared test lock or continue executing against the one
`out/test/test.js` artifact after their owning edit hook has ended.

## Evidence

During database-browser Slice B, stopping broad edit-hook parents `47814`,
`57853`, and `60863` left runner or pipeline descendants including `50354`,
`57867`, `57922`–`57924`, and `60919`/`60921`. Some retained
`tmp/test-cljs.lock`; others continued after the lock owner exited. The exact
gate had to remain frozen until each descendant was identified and stopped,
because a lock-free orphan can still replace or execute the shared bundle.

## Owner

The one changed-test command lifecycle in `seon.dev.cli` plus the existing
`bin/test-cljs` process bracket. Interruption must terminate and await the
complete child pipeline before releasing ownership; do not add another lock
or test runner.

## Acceptance

- Interrupt `test changed` during compile and during node execution; every
  descendant exits before the parent returns.
- The shared lock is removed only after the complete owned pipeline stops.
- No orphan can write or execute `out/test/test.js` after interruption.
- A concurrent exact runner either observes the live owner and fails closed or
  acquires a fully quiescent artifact boundary.

## Implementation evidence

Implemented on 2026-07-15 in the one `seon.dev.changed-test/run-command!`
lifecycle. Every child command now registers an owner-process shutdown hook,
captures a stable `ProcessHandle` tree, signals descendants before ancestors,
and awaits their absence. Timeout and thread-interruption paths retain the hook
until cleanup completes. If graceful termination does not converge, the owner
rescans every still-known handle for newly spawned descendants before forced
termination and reports cleanup failure if bounded absence cannot be proven.

Focused operator proof passed 15 tests and 37 assertions in
`tmp/test-changed/changed-operator-1784099964314-331ede03-3436-46f5-8ccc-ca50be91a746.log`.
One regression installs a TERM trap that spawns a new child during the grace
period and proves escalation includes it. Another starts a real nested
Babashka changed-test owner, Bash child, and Bash grandchild, sends the owner
SIGTERM, and proves its shutdown hook awaits both descendants before exit.

This guarantee covers catchable JVM shutdown and the runner's ordinary
timeout/interruption paths. SIGKILL cannot run a shutdown hook in any process;
after an external uncatchable kill, the existing PID-validated stale-lock
recovery remains the next-invocation repair boundary rather than a claim that
the killed owner performed cleanup.

The focused process-tree proof is not yet sufficient to close this issue. A
source-frozen verification window must still interrupt the real changed-test
owner once during CLJS compilation and once during Node execution, then prove
that the shared test lock is released only after every owned descendant has
exited and that no orphan continues to write or execute `out/test/test.js`.
