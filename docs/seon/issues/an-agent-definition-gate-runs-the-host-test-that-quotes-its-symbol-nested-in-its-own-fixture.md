---
type: issue
status: open
severity: platform
created: 2026-09-25
tags: [issue, test, gate, accretion, agent-platform]
---

# An agent definition's gate runs the host test that quotes its symbol, nested in its own fixture

Observed on default 6ab6d18c (lane fix-turn-hang-opus) in
`seon.cluster.turn-test/a-batched-turn-commits-only-queryable-definition-facts`:

- **What happened in the turn.** Inside the test's fixture, the agent's contracted
  `(defn … extract-id …)` ran its install gate.
  - `seon.fn/gate-set db 'my.agents.agent-a/extract-id` answered
    `[seon.cluster.turn-test/a-batched-turn-commits-only-queryable-definition-facts]`,
    the host test itself, even though no function row exists for that symbol.
  - The host test's row carries `:seon.fn/references`, including
    `my.agents.agent-a/extract-id`, because its source quotes that symbol (`references-by-caller`,
    src/seon/fn.clj:313, counts quoted symbols as references).
  - The gate therefore ran the host test nested inside its own fixture. The nested run
    failed, the install was refused ("Gate: 1 tests · 0 passed · 1 failed"), and no
    definition facts were committed.
- **Why it is a class.** Any host test that names an agent function by a quoted literal,
  while the agent defines that function in the test's fixture, selects itself. The test
  can recurse up to its request bound.
- **Test-side mitigation.** Commit 403122fc2 and the sweep after it make turn_test build
  those symbols at runtime (10 sites). This does not fix the harness.
- **Owner-side fix.** The gate that runs inside a test member's fixture should never select
  that member, or any host test, for an agent-branch definition. Decide it in
  `seon.test.accretion` / `seon.fn/gate-set`, where the executing member (`seon.test/*member*`)
  is known. Regression: a host test that quotes a fixture agent's function is not selected
  by that function's gate.
