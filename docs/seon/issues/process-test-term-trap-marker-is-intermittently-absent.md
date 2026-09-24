---
type: issue
status: open
severity: friction
tags: [issue, process, test, flake]
---

# The well-behaved-tree process test intermittently finds no TERM-trap marker

## Problem

`seon.cluster.process-test/a-well-behaved-tree-exits-on-term-without-force`
(`test/seon/cluster/process_test.clj`) asserts that the grandchild shell ran its
TERM trap, which writes `term` into a temporary directory. After
`terminate-subprocess!` returns true (every captured identity exited), the marker
is sometimes absent.

## Evidence (2026-09-24, lane fix-nil-process-agent)

`git archive` snapshot of HEAD 7c761e0c9, `clojure -M:test`,
`(clojure.test/run-tests 'seon.cluster.process-test)`:

- HEAD's `process.clj`: 1 of 2 runs failed this assertion.
- HEAD plus the fix-nil-process-agent change: 1 of 3 runs failed it.

The failure is `expected: (= "term\n" (when (.exists marker) (slurp marker)))`,
`actual: (not (= "term\n" nil))`. The other assertions in that test (reaped,
no survivors, root exit 143, elapsed < 2000 ms) passed in every run.

## Unknown

Which member of the tree (`sh -c "cd … && sh -c '…' & wait"`: root, a subshell
for the backgrounded list, the trapping shell, the rotating `sleep 0.1`) is
captured when `start-tree` sees three members, and whether the trapping shell is
reparented out of the root's tree before the owner recaptures it. Probe with the
captured identities printed on a failing run before changing the owner or the test.
