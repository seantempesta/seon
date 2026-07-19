---
type: issue
status: open
severity: blocker
tags: [issue, agent, cljs, health]
---

# Ticker core faults bypass the database crash policy

## Problem

The one periodic ticker caught every rejected watchdog or schedule operation,
logged `tick failed (timer continues)`, and resolved. It neither recorded the
fault nor applied `:seon.config/on-core-error`, allowing broken core machinery
to remain alive in development.

## Evidence

`seon.agent.loop/run-tick!` ended its Promise chain with a console-only catch.
The ticker was installed without the immutable database configuration already
acquired at startup or hot publication. A diagnostic failure exposed the
policy bypass even though the failure itself came from an unsupported direct
loader call.

## Owner

`seon.agent.loop/install-ticker!` owns the single interval and retains the
already-acquired configuration. `run-tick!` records an unexpected rejection as
one `:core` fault through `seon.error/record!`; `seon.error` remains the sole
policy and persistence owner.

## Acceptance

- Focused proof observes the exact retained configuration and one `:core`
  recording for a rejected watchdog operation.
- A live deterministic ticker failure under `:crash` persists its fault before
  the pod exits.
- The supervisor starts a clean replacement, the ticker does not repeat the
  same failure while alive, and normal schedule/watchdog behavior still works.
- `:gate` and `:log` retain their existing single-policy semantics without a
  ticker-specific switch or configuration read per tick.
