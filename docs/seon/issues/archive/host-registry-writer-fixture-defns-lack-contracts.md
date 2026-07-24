---
type: issue
status: resolved
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

## Resolution — 2026-07-23

Resolved by `fe4bfed0c`; the accepted focused gate recorded in `efdce2b67`
passed 40 tests / 247 assertions. The registry fixture's durable
`parity-double` definition carries its complete contract at
`test/seon/host_registry_writer_test.clj:488-490`, and the same test retains
the recording, cross-agent resolution, and replay assertions through
`test/seon/host_registry_writer_test.clj:519-638`. The sibling sweep repaired
four additional schema-less durable definitions in the same commit, leaving
zero matches in the computed fixture sweep.
