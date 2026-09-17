---
type: issue
status: open
severity: friction
created: 2026-09-17
tags: [issue, test, operator, liveness]
---

# Operator fast iterations end on unattributed TERM

Two Stage 2 foreground iterations ended with exit 143 before their declared
2,400-second bounds and without a final tally:

- `tmp/stage2-resolution-sixth-current-fast.log`, snapshot
  `tmp/test-runs/run.YLZTQO`, last test announcement at
  `2026-09-17T07:47:26.027532Z`: `dependency-tool-loads-selection`.
- `tmp/stage2-resolution-operator-fast.log`, snapshot
  `tmp/test-runs/run.4IethE`, last announcement at
  `2026-09-17T07:53:28.144635Z`:
  `live-init-reloads-schema-runtime-and-moved-predicate-owners-before-admission`.

Both launchers printed `interrupted by TERM; removing snapshot` and removed
their own snapshots. Neither log identifies the signal sender. These are
interrupted iterations, not passing evidence, and do not establish a cause in
another lane. No default operation was issued by this lane.

The second invocation was exactly:

```bash
timeout 2400 bin/test-fast --paths script/seon/fresh_operator.clj test/seon/dev/fresh_operator_test.clj dev_cache.clj bin/test src/seon/test/cache.clj src/seon/test/runner.clj -- seon.dev.fresh-operator-test > tmp/stage2-resolution-operator-fast.log 2>&1
```

Follow-up must identify the signal source or obtain a completed bounded run.
An interrupted test cannot establish its own result from silence.
