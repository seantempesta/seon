---
type: issue
status: open
severity: friction
tags: [issue, agent, runtime, testing]
---

# Keep disarm retryable after its loud backstop fires

## Problem

The provider-derived disarm backstop fires loudly, but the armed route has
already disappeared, so the failed stop is not retryable as the test contract
requires.

## Evidence

The bare 2026-08-05 gate emitted the expected core-fault line and then failed
`seon.cluster.agent-test/disarm-has-a-provider-derived-loud-backstop` at
`test/seon/cluster/agent_test.clj:870`:

```text
SEON CORE FAULT (agent stop backstop): Agent turn completion exceeded its provider-derived backstop.
expected: (some? (agent/armed routing agent-id))
  actual: (not (some? nil))
```

This exact var is the open D14 backstop in the
[session-curation findings ledger](../../prds/sci-execution-runtime/plan/curation-findings-ledger-2026-08-04.md),
which records the same nil route after the D13 fixture repair. The focused
pre-rename reproduction at `401fd300e` also failed identically.

## Owner

The `seon.cluster.agent` disarm transition and its armed-route lifecycle.

## Acceptance

When the loud last-resort backstop fires, disarm fails closed without dropping
the route needed to retry. A later successful stop removes the route exactly
once.
