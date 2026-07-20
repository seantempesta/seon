---
type: issue
status: resolved
tags: [issue, cljs, pod, flow]
severity: friction
---

# Restart cannot reuse a stopped watcher's client manifest

## Evidence

On 2026-07-18 a normal `bin/seon restart` stopped all three managed processes,
then selected the content-verified manifest fast path. The replacement watcher
compiled every build but could not become ready against output published by
the stopped watcher because Shadow's development output includes
watcher-specific runtime data.

## Resolution

Commit `ea663cae` permits the manifest fast path only while the watcher that
published the verified output remains alive. A complete restart therefore uses
the existing frozen-source publication path; writer-only recovery continues to
retain its healthy watcher and pod. Focused operator proof passes 111 tests and
450 assertions.

The clean live repeat admitted new clean generations for watcher, writer, and
pod, recovered the pre-restart agent message exactly once, and returned the
agent to `:idle` with no current run.

## Acceptance

- Manifest reuse requires the managed publishing watcher to remain alive.
- Complete restart publishes the replacement watcher's completed output.
- Writer-only recovery retains healthy watcher and pod identities.
- Focused operator and live committed-work restart proofs pass.
