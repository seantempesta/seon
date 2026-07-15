---
type: issue
status: open
severity: friction
tags: [issue, flow, agent]
---

# Coalesce superseded changed-test hook requests

## Problem

Every edit-hook invocation starts a separate `bin/seon test changed` process.
The processes serialize behind the one `:changed-test` file lock, but they do
not coalesce paths or supersede an obsolete request. A burst of agent edits can
therefore retain many Babashka processes and run stale intermediate test
selections long after newer source has replaced the input they were meant to
check.

## Evidence

During the 2026-07-15 containment and pod-quiescence implementation, a read-only
process census found 27 `seon.dev.cli ... test changed --path ...` processes.
Most were reparented to PID 1, shared the Codex process group, slept behind the
lock, retained roughly 116–124 MiB RSS each, and ranged from seconds to more
than fifteen minutes old. Together they retained about 3 GiB while newer hook
requests continued to arrive. `tmp/test-changed/latest.report.edn` kept
advancing, proving the queue was making serialized progress rather than owning
27 independent useful test runs.

`seon.dev.changed-test/run-changed!` acquires the one `:changed-test` lock with
a timeout of `manifest-wait-ms + 3 * test-timeout-ms + 10000` (about fifteen
minutes), so every hook process waits independently. The current edit-hook
boundary has no one-slot pending request, path union, generation, or
supersession check.

A later 2026-07-15 ACME record-migration edit reproduced the user-visible
wedge. The patch bytes were already written, but its edit hook remained behind
the shared lock for more than two minutes while the process census showed many
older requests, including waits of 5–16 minutes. The top-level agent had to
terminate only its blocked hook cell and invoke the one focused process test
directly. This demonstrates that the backlog blocks patch completion itself,
not merely delayed advisory reporting.

## Owner

The existing changed-test hook/operator boundary. Keep the one selector, file
lock, report, and public `bin/seon test changed` operation; do not add another
test runner or daemon.

## Acceptance

- A rapid burst of edits retains at most one running test request and one
  bounded pending generation rather than one Babashka process per edit.
- The pending generation unions normalized changed paths and runs once against
  source no older than its published generation.
- An obsolete waiting request exits promptly without acquiring the expensive
  test lock or overwriting a newer report.
- Explicit foreground `bin/seon test changed` remains deterministic and does
  not silently discard the caller's requested paths.
- A process-level regression sends a burst larger than the current worker
  count, proves the bounded process/RSS envelope, and proves the final report
  covers the union belonging to the latest generation.
