---
type: issue
status: closed
severity: friction
tags: [issue, agent, runtime, testing]
---

# Supply the starting namespace in agent lifecycle fixtures

Lifecycle fixtures repeatedly attempted to start runs without the required
starting namespace, so the transition correctly refused and later assertions
observed derivative missing state.

Commit `3e0a38223` supplies each fixture's declared starting namespace. The
affected lifecycle vars now reach their intended assertions without a
`starting-namespace-missing` refusal; the complete `seon.cluster.agent-test`
namespace is part of W-A's integrated gate.
