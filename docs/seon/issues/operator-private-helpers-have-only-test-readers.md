---
type: issue
status: open
severity: cleanup
tags: [issue, operator, deletion, testing]
---

# Delete operator helpers and tests with no live command reader

## Problem

Two private operator helpers have no reader at all, one has only a private test
reader, and three tests call private functions that have already been deleted.
The private-Var tests are now both a maintenance reason for dead code and a
red gate for absent code.

## Evidence

- `script/seon/fresh_operator.clj:131-133` defines
  `process-record-directory`; exact search finds no caller.
- `script/seon/fresh_operator.clj:202-206` defines `record->boot-process`;
  exact search finds no caller.
- `script/seon/fresh_operator.clj:2370-2378` defines
  `require-readable-process-records!`; its only caller is the private-Var
  assertion at `test/seon/dev/fresh_operator_test.clj:629`.
- `test/seon/dev/fresh_operator_test.clj:527-541` calls deleted
  `legacy-operator-arguments?`; direct `ns-resolve` returned `nil` and the
  focused test produced six errors.
- `test/seon/dev/fresh_operator_test.clj:635-674` calls deleted
  `terminate-observed-process!` in two more tests; exact source search finds no
  definition.
- Clj-kondo independently reports the three surviving private Vars as unused.

## Owner

The live operator command paths and their behavioral tests.

## Acceptance

Delete helpers with no command reader and delete or replace direct private-Var
tests with tests of the surviving command seam. Do not restore either deleted
legacy helper. A reference census finds no test-only private operator surface,
and the focused operator suite has no unresolved private target.
