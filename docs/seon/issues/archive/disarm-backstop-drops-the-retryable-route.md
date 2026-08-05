---
type: issue
status: resolved
severity: friction
tags: [issue, agent, runtime, testing]
---

# Keep disarm retryable after its loud backstop fires

## Problem

The provider-derived disarm backstop fired loudly after the armed route had
already disappeared, so the failed stop was not retryable.

## Evidence

The 2026-08-05 gate emitted the expected core-fault line and then failed
`seon.cluster.agent-test/disarm-has-a-provider-derived-loud-backstop` because
`agent/armed` returned nil after the backstop.

The cause was teardown order in `seon.cluster.agent/disarm!`: it removed both
routing indexes and closed the mailbox before awaiting the turn-completion
event. The backstop therefore threw only after destroying the retry handle.

## Owner

The `seon.cluster.agent` disarm transition and its armed-route lifecycle.

## Resolution

Disarm now requests Flow stop and awaits the existing observable turn
completion before destructive cleanup. If the loud provider-derived backstop
fires, both routing indexes and the channels remain intact, so the same disarm
request can be retried. After completion, the transition removes both indexes
before closing the mailbox and completion channels; a repeated disarm is an
idempotent nil result.

The focused recurring vars passed together in one JVM, including the expected
loud backstop, the successful retry, both routing-index removals, channel
closure, and repeated-disarm idempotency. The complete namespace gate was
started but fixture loading was blocked by concurrent W-A work in protected
`src/seon/sci/eval.clj` (`changed-session-defs` unresolved at line 1722); no
disarm assertion failed.
