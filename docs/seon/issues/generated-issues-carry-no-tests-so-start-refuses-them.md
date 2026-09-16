---
type: issue
status: open
severity: friction
tags: [issue, steward, schema, class/p1]
---

# A generated issue carries no tests, so `start!` refuses it

`seon.issue/generate` writes status, severity, the detector ref and the subject
ref, and never `:seon.issue/tests`. `seon.issue/start-tx` refuses an issue whose
tests are empty ("Starting an issue requires at least one test"), so none of the
63 issues the first generator run wrote on `default` (2026-09-16,
`seon.issue.detect/entity-map-without-pair` 32, `seon.issue.detect/public-without-doc` 31)
can seed a worker.

The generator deliberately mints no `:seon.test` row: a row for a deftest nobody
has written is a fabricated program fact, and `:seon.test/sym` is the identity
the runner selects on. The required test is stated in `:seon.issue/problem`
instead, as the acceptance a worker must write.

Two facts already hold, and the design decision is which one `start!` reads:
a generated issue's completion is decided by its DETECTOR (the subject leaves
the result and the run asserts `:seon.issue/resolved-tx`), not by a test set,
so `start!`'s nonempty-tests rule is a rule about authored issues only.

Acceptance: `seon.issue/start!` admits an issue whose completion is decided by
its detector — for example by accepting `:seon.issue/detector` as an
alternative done-query source — and refuses an issue that has neither tests nor
a detector. One regression starts a generated issue and one refuses an issue
with neither. Owner: the issue-settlement lane (it owns `start!`, the writer
guard and the settlement path, [issue-family-spec §6 P5/P6](../../prds/steward-platform/plan/issue-family-spec-2026-09-16.md)).
