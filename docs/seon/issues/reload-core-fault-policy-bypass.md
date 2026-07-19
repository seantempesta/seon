---
type: issue
status: open
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
