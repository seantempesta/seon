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
