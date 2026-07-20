---
type: issue
status: resolved
tags:
  - bun
  - process
  - testing
severity: friction
tags: [issue]
---

# Bun-only pod was launched with Node

## Failure

The canonical pod transport uses Bun-native APIs, but the operator and test
runners still defaulted to Node. A normal `bin/seon up` therefore compiled the
pod successfully and then stopped at runtime with `ReferenceError: Bun is not
defined`.

## Resolution

The one `SEON_JS_RUNTIME` selection now defaults to `bun` for the supervised
pod and changed-test runner, and `bin/test-cljs` uses the same environment
selection. An explicit Node override remains available for compatibility
probes; it is not a second application runtime.

## Evidence

- `bin/seon test operator seon.dev.process-test seon.dev.changed-test-test`:
  71 tests, 320 assertions, zero failures.
- `bin/test-cljs --test=my.plan-test`: 21 tests, 70 assertions under Bun.
- `bin/test-cljs --test=seon.state-test`: 13 tests, 45 assertions under Bun.
