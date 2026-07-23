---
type: issue
status: open
tags: [issue, agent, test]
severity: friction
---

# Host registry writer fixture defns lack contracts

## Evidence

`bin/test-writer seon.host-registry-writer-test` currently reports 15 failures.
The first causal failure is the fixture definition
`my.agent.parity-agent/parity-double`: strict durable-function admission
rejects it because its `defn` has no parseable `:malli/schema`. Later assertions
then observe the missing function, failed calls, and failed replay.

The new capability-inventory regression in the same namespace passes when run
alone, so these failures do not falsify capability enumeration.

## Expected owner

The host registry writer fixture should define schema-complete durable
functions through the same admission contract as production agent code. It
must not disable strict admission or restore an uncontracted fixture path.

## Acceptance

- Every durable fixture `defn` supplies a complete parseable function schema.
- `bin/test-writer seon.host-registry-writer-test` is green through initial
  recording, cross-agent require, and restart replay.
