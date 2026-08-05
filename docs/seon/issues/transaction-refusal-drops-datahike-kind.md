---
type: issue
status: open
severity: friction
tags: [issue, database, agent, testing]
---

# Preserve the Datahike refusal kind in the flat error value

## Problem

Assigning one namespace to a second agent is correctly rejected by Datahike,
but the returned flat error does not retain the rejection kind where the
consumer contract expects it.

## Evidence

The bare 2026-08-05 gate logged Datahike's exact
`{:error :transact/unique, :attribute :seon.cluster.agent/namespace}` refusal,
then failed
`seon.cluster.agent-namespace-test/one-namespace-cannot-be-assigned-to-two-agents`
at `test/seon/cluster/agent_namespace_test.clj:69`:

```text
expected: (= :transact/unique (:error (:seon.error/data refusal)))
  actual: (not (= :transact/unique nil))
```

The same var failed identically at pre-rename commit `401fd300e`. The older
cause-chain loss was repaired in
[[archive/transaction-refusal-loses-its-ex-data]]; this is current evidence
that the public flat-value projection still drops the recovered kind.

## Owner

The `seon.db/transact!` rejection classifier and flat error projection.

## Acceptance

The unique constraint remains enforced and its structured Datahike kind and
attribute survive in the returned flat error data. The regression asserts the
value without relying on duplicated writer log lines.
