---
type: issue
status: open
severity: cleanup
tags: [issue, operator, deletion, testing]
---

# Delete operator helpers maintained only by private tests

## Problem

Four private operator helpers have no production reader. Tests reach into the
namespace to call them directly, manufacturing the only reason the dead
surfaces remain.

## Evidence

- `script/seon/fresh_operator.clj:1542-1552` defines
  `terminate-observed-process!`; only tests read it at
  `test/seon/dev/fresh_operator_test.clj:511,532`.
- `script/seon/fresh_operator.clj:2216-2223` defines
  `require-readable-process-records!`; only the test at `:496` reads it.
- `script/seon/fresh_operator.clj:2342-2344` defines
  `assert-store-flock-free!`; only tests at `:901,908,946` read it.
- `script/seon/fresh_operator.clj:2373-2384` defines
  `delete-cluster-root-no-follow!`; only the test at `:640` reads it.
- Clj-kondo reports all four as unused private Vars.

## Owner

The live operator command paths and their behavioral tests.

## Acceptance

Delete helpers with no command reader and replace direct private-Var tests with
tests of the surviving command seam. A reference census finds no test-only
private operator surface.
