---
type: issue
status: open
severity: high
created: 2026-09-24
tags: [issue, testing, fixture, error]
---

# Test fixture worlds inherit the live cluster's errors and messages

## Problem

`seon.test/run` acquires each member's branch off the live cluster's captured
commit, and `seon.test-support/with-database` branches off that member
(`test/seon/test_support.clj`, `with-branched-database`). The world therefore
carries the live cluster's operational data: its errors, fault messages and
agents, not only its program rows. Assertions that count over the whole
database see those rows too.

## Evidence (2026-09-24, default pid 70372, lane fix-test-worlds)

- Live default held `{:about 1, :errors 2, :agents ["root"]}`
  (`[:find ?m :where [?m :seon.message/about _]]`, `[:find ?e :where [?e :seon.error/id _]]`).
- `bin/test-check default --ns seon.error-test`, run 3851b614fe6b, once the
  fixture cluster was named correctly:
  - `a-prepared-message-keeps-its-id-when-the-transaction-repeats`
    (`test/seon/error_test.clj:893`): expected 1 message, got 2.
  - `an-unattributable-throwable-goes-to-the-escalation-owner` (`:864`) and
    `an-agent-this-cluster-does-not-have-is-no-attribution-at-all` (`:845`):
    `{"root" 1}` expected, got `{"root" 2}`.
  - `only-a-throwable-tells-the-attributed-agent`: `{"agent-3" 1}` expected,
    got `{"root" 1, "agent-3" 1}`.

## Wanted

Either the member branch starts from the program partition only (turns,
evaluations, messages and errors are data, never program: AGENTS.md "One JVM,
many realities"), or each such test establishes its before-state and asserts
the rows it produced. Owner of the branch point: `seon.test/run`
(`src/seon/test.clj`); owner of the assertions: `test/seon/error_test.clj`.
