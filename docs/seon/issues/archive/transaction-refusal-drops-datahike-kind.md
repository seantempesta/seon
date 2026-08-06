---
type: issue
status: resolved
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

## Resolution

Resolved by `00a5f4400`. `seon.db/rejected-value` now merges Datahike's
deepest non-empty `ex-data` with the derived unique-conflict facts instead of
replacing the dependency map. The focused database and namespace-assignment
gate passed 24 tests and 125 assertions with zero failures and zero errors.

The changed-test selector completed its fresh-operator group, then encountered
an unrelated in-flight schema-group failure at snapshot `910679222` and was
stopped before its source-churn fallback could run the full suite. That foreign
boundary does not alter the focused proof for this issue.
