---
type: issue
status: open
severity: friction
tags: [issue, test, dependency]
---

# Dependency resolution can fail in Maven model validation

## Problem

Classpath acquisition failed before tests loaded; no first-party test verdict
was produced. An identical retry passed the classpath phase.

## Evidence

2026-09-09, `tmp/turn-rename-final-gate.log`: `HashMap$Node` cannot be cast
to `HashMap$TreeNode`, from `HashMap.put`, `HashSet.add`, and Maven's
`DefaultModelValidator.validateId`, under `clojure.tools.deps/expand-deps`.
The runner reported `dependency cache freshness check failed`. The next
invocation acquired the same dependency cache successfully. Concurrent
resolution is a hypothesis, not a confirmed cause.

## Owner

Dependency-cache/classpath acquisition and the tools.deps/Maven seam.

## Acceptance

Reproduce the validator failure under bounded classpath acquisition, identify
its shared mutable state, and verify repeated acquisition without corrupting
that state or hiding a failed classpath behind a test-success claim.
