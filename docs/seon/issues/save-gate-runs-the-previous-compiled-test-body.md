---
type: issue
status: open
severity: blocking
created: 2026-09-24
tags: [issue, publication, save-gate, test, adoption]
---

# The save gate runs the previous compiled test body

## Problem

On default (pid 70372, from the checkout, hook publication on), a changed
`deftest` body is gated by the body that was loaded before the edit. A red
test can therefore never be repaired through publication: the gate stays red
on the old body and refuses, and the old body stays loaded.

Observed 2026-09-24 17:10–17:14Z on
`seon.cluster.boot-test/status-answers-faulted-health-and-lists-the-open-fault`
(lane fix-status-render):

- The file replaced the assertion
  `(= [(:seon.error/signature receipt)] (mapv :seon.error/signature ...))`
  with `(some #{(:seon.error/signature receipt)} (map :seon.error/signature ...))`.
- Three consecutive saves (runs 1bab41ddc196, bebdcb0b8207, fac395a4fd45) and
  a `bin/test-check default --test ...` (run f01a1c2eaa18) all failed at
  `boot_test.clj:520` quoting the replaced `(= [...] (mapv ...))` form. Each
  answered "refused by the save gate; default unchanged".
- Default's `:seon.test/source` row for that test lacks `some #{` (jvm
  eval, 17:12Z).
- Earlier in the same session, the gate ran the edit *before* each saved one,
  one version behind. For example, the save that removed a diagnostic
  `(is false (pr-str ...))` wrapper reported that wrapper's output.

Suspected, not proven: the reaching run executes the JVM's compiled test Var
and not the candidate's rows. `host-bound-declarations` (`src/seon/cluster.clj`) considers
only `:seon.fn/sym` identities, so a changed test is gated on the candidate
while the only executable body is the stale compiled one.

After another lane's save took the host-bound path (`adopt-then-test!`, 17:13Z),
which reloads the changed files, the same test ran its current body green
(run 8a7b2438cacf, 4 pass). This is consistent with the loaded Var being the
only executable body.

## Wanted

The gate runs the changed test body it is admitting: interpreted from the
candidate rows, or classified host-bound and adopted first, like a changed
function (`adopt-then-test!`). A test repaired in its file becomes green in
one save. Owner: `seon.cluster/save-gate!` / `candidate-gate!` and the test
runner's body selection.
