---
type: issue
status: open
severity: friction
tags: [sci, contracts, acquisition, testing]
---

# SCI fork's three-argument arity passes nil to a required projection state

The public three-argument `seon.sci.eval/fork-cluster-ctx` delegates to its
four-argument arity with nil. The latter's Malli contract requires
`:seon.sci.eval/projection-state`. Under the canonical armed harness, the
delegation refuses before the body can derive the missing state.

Observed in the Stage 2 draft regression
`seon.test-test/resolution-follows-admitted-source-and-acquisition`, using
the canonical database and acquired fixture context. Source basis:
`19251d6469f0b87e7512f6dbd022dbb1a49db9ba`; log:
`tmp/test-system-stage2-resolution-second-fast.log`, lines 31–34:

```text
seon.sci.eval/fork-cluster-ctx refused supplied-projection-state at []: expected must hold one immutable replacement environment, got nil. Fix: must hold one immutable replacement environment
```

The owning source already uses a private constructor to avoid this same
nil delegation in `cluster-ctx`. Repair the fork's delegation at its owner,
preserve explicit supplied state, and prove both public arities under armed
contracts. Do not weaken the state predicate or make callers bypass the
three-argument API. The Stage 2 continuation stopped at a separately held
launcher file; this issue is not attributed to that foreign edit.

The pending regression and exact iteration are linked from
[the Stage 2 landing note](../../prds/steward-platform/research/test-system-stage2-2026-09-17.md).
