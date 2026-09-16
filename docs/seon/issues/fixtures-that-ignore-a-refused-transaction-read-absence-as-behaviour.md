---
type: issue
status: open
severity: friction
created: 2026-09-16
tags: [testing, fixture, class, absence-as-health]
---

# Fixtures that ignore a refused transaction read absence as behaviour

## Problem

`seon.db/transact!` returns a flat `:seon.error` value when write admission
refuses a row the current schema no longer admits (stricter admission since
`20d30a0bd`). Fixtures that call it without reading the answer seed nothing,
and their tests then fail several assertions away from the cause, or read
the empty result as a semantic change. Three independent triages hit the
same class on 2026-09-16: `seon.render.transcript-test` (seven survivors,
second pass), `seon.render.web-debug-test` (the panel fixture), and
`seon.config-test`/`seon.reconcile-test` (batch 36, refuted as a
regression by the config-apply-cost lane, `5e5aa6293`). Each triage added
its own local `transacted!` helper.

This is the project's named recurring class: a check that reads ABSENCE OF
SIGNAL as health.

## Fix shape (one choke point, one regression)

1. `seon.test-support` owns one fixture write helper that asserts the
   transaction report (`:db-after`) and fails the test with the refusal's
   diagnostic; the local helpers in the three namespaces are replaced by it.
2. A detector over the program graph: a test namespace whose functions call
   `seon.db/transact!` without consuming the result is a generated issue
   (the issue generator's detector contract), so the class cannot silently
   return.
3. One regression: a fixture write refused by admission fails the test at
   the write with the refusal named, never downstream.

## Status 2026-09-16: the choke point landed, the detector is the other half

`seon.test-support/transacted!` is now the one fixture write path
(`test/seon/test_support.clj:224`). It transacts through `seon.db/transact!`
with an explicit connection, refuses to return anything but a report carrying
`:db-after` and no `:seon.error/kind`, and throws an `ex-info` carrying the
flat error — so a refusal stops the fixture AT the write, naming write
admission's own diagnostic and the authored row at the refusal's path index.
(The refusal's `:seon.db/entity-form` is the entity SCHEMA it was read
against, not the row; naming it as the row was the first probe's finding.)
The local helpers and the write-and-discard seeds in
`seon.render.transcript-test`, `seon.render.web-debug-test`,
`seon.config-test` and `seon.reconcile-test` now call it; the regression is
`seon.test-support-test/a-refused-fixture-write-is-reported-at-the-write`
(`test/seon/test_support_test.clj:43`), which proves the seeding fixture never
resumes after a refused write. `seon.custody-stability-test` was examined and
left alone: its writes are SCI source strings evaluated in an agent context,
not fixture calls with a connection in hand, and it already asserts the
written message ids. The remaining half is item 2, the detector — a test
function calling `seon.db/transact!` without consuming the result should be a
generated issue under the issue generator's detector contract
([issue-generator-2026-09-16.md](../../prds/steward-platform/research/issue-generator-2026-09-16.md)).
Until that lands, the class can still return in a namespace nobody triages.

The choke point earned its place on the first run: two
`seon.render.web-debug-test` fixtures were writing a bare
`{:seon.cluster/name …}` row that write admission refuses (it requires
`:seon.cluster/config`), had been seeding nothing since the admission change,
and passed anyway. They now seed through `seon.test-support/seed-cluster!`
(`test/seon/render/web_debug_test.clj:508`, `:556`). Measurements and the
complete in-process tally are in
[fixture-write-helper-2026-09-16.md](../../prds/steward-platform/research/fixture-write-helper-2026-09-16.md).

Related: `write-admission-validated-partial-maps-against-every-schema`
(the admission change), the transcript second-pass landing note.

## Status 2026-09-17: the sweep is finished, the detector is the only half left

The class hit an eighth time after the choke point landed
(`seon.render-coverage-test` seeded a program row without
`:seon.schema.admission/source`, `ac95db78a`), because the first pass converted
only the namespaces its three triages had visited. The whole tree has now been
enumerated structurally — a rewrite-clj parse that propagates a
value-is-discarded flag, not a regex — and **588 discarding fixture writes in 96
files** now call `seon.test-support/transacted!`. `seon.effect-test`'s and
`seon.edit-test`'s own checked wrappers were folded into it, and
`seon.flow.kill-child` (the Flow process-death child JVM, the one instance of
the class outside a fixture) now names a refusal instead of leaving the parent
to read a missing datom.

**Baseline for the detector: outside the four files another lane holds, ZERO
raw discarding `seon.db/transact!` sites remain in `test/`.** The 16 that
remain are all in `test/seon/render_simplification_test.clj` (15) and
`test/seon/render_coverage_test.clj` (1). Seven raw `datahike.api/transact`
fixture writes are deliberate admission bypasses and throw on failure, so they
are not this class. Measurements, the classification rules the detector needs
(a textual rule reports 310 false positives), and the in-process tally are in
[fixture-write-sweep-2026-09-17.md](../../prds/steward-platform/research/fixture-write-sweep-2026-09-17.md).


## Batch 107 followup

The four `seon.db-test` fixture sightings in batch 107 now use complete cluster,
turn and evaluation creation through the canonical helpers/production transaction
constructors, with checked writes. The collection-bound attribute regression no
longer reads a refused bare cluster-identity creation as unchanged read evidence.
The diagnostic grammar's intentionally incomplete creation now uses a new
identity instead of expecting a valid partial update to an existing function to
fail. Details and the remaining lifecycle distinction are in the
[write-admission landing note](../../prds/steward-platform/research/write-admission-2026-09-17.md).
This closes those sightings, not the broader detector/fixture class.
