---
type: issue
status: resolved
severity: blocker
tags: [issue, cljs, health]
---

# Reload faults bypass the database crash policy

## Problem

Shadow build/import failures correctly closed program admission and recorded a
core fault, but the notification callback did not carry the database
configuration. `seon.error/record!` therefore used its default `:gate` policy
even when the cluster selected `:crash`.

## Evidence

The atomic-import live proof emitted `SEON-CORE-FAULT` and left the pod alive
and unavailable. That made same-process repair possible, but contradicted the
database singleton's `:seon.config/on-core-error :crash` value and the fail-loud
development contract.

## Owner

`seon.client/shadow-build-notify!` acquires the immutable configuration once for
the failed publication and runs the existing admission/error transition inside
`seon.error/with-configuration`. There is no reload-specific policy or cache.

## Acceptance

- Focused proof shows a Shadow failure records under the acquired database
  configuration and never publishes or rearms work.
- Under `:crash`, a watched import failure persists its core-fault datom before
  the pod exits.
- The operator replaces only the pod, and a clean build starts ready.
- Under non-crash policy, the next complete build can still recover the same
  unavailable pod through the existing publication transition.

## Resolution

Commit `a45b8cb4` runs build and publication failures inside the immutable
database configuration acquired for that occurrence. Focused instrumentation
proof passes 11 tests/128 assertions.

The exact watched import proof then threw
`deterministic configured reload crash proof`. Shadow reported the import
failure, Seon persisted core-fault entity `5923`, logged the configured crash,
and the pod exited. The watcher and writer stayed alive. Removing the guarded
probe and running normal `bin/seon up` rebuilt current source and replaced only
the unexpectedly exited pod; the cluster returned ready.
