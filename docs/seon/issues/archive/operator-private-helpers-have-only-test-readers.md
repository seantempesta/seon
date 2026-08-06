---
type: issue
status: resolved
severity: cleanup
tags: [issue, operator, deletion, testing]
---

# Delete operator helpers and tests with no live command reader

## Problem

Two private operator helpers had no reader at all, one had only a private test
reader, and three tests called private functions already deleted from
production. The private-Var tests were both a maintenance reason for dead code
and a red gate for absent code.

## Evidence

- `process-record-directory` and `record->boot-process` had zero callers.
- `require-readable-process-records!` had only one private-Var test reader.
- One test called the absent `legacy-operator-arguments?`, while two tests
  called the absent `terminate-observed-process!`.
- The command-level process-record, PID-reuse, down, reset, and cleanup proofs
  use surviving owners and remain in the suite.

## Owner

The live operator command paths and their behavioral tests.

## Acceptance

Delete helpers with no command reader and delete direct private-Var tests for
absent functions without restoring either legacy helper. Preserve the command
level process-cleanup proofs, and leave no test-only private operator surface.

## Resolution

Resolved by audit-finding-8 commit `b41728539`. The three
readerless production helpers, two test-only process launchers, and three
unresolved tests are gone. Exact searches find none of their symbols, while
the current command-level process cleanup proofs remain intact. Proof: the two
non-boot owner tests ran 5 assertions with zero failures and errors; boot-bound
proof remains assigned to the quiet-tree checkpoint.
