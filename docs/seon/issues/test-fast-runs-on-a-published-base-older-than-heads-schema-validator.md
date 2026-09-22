---
type: issue
status: open
severity: blocking
created: 2026-09-23
tags: [issue, testing, fixture]
---

# test-fast runs on a published base older than HEAD's schema validator

`bin/test-fast` picks the newest `target/test-published-bases` entry
(`seon.test.cache/newest-base`, `src/seon/test/cache.clj:566`) and only prints
how far it is behind (`overlay graph d73e0a6c... age= 46 commits behind HEAD`,
HEAD `bfe3445f8`, 2026-09-22). After `8a069b5e4` added
`assert-entity-partition!` (`src/seon/schema.clj:1349`), every canonical fixture
built on that base refuses with `:my.note/note` "must declare its partition",
and a booted scratch cluster refuses at `seon.cluster.boot/start!`
(`boot.clj:263`, "Store identity mismatch"). Same errors on the lane's change
(run `1909e03da02a`) and on the baseline with HEAD's own test file (run
`34fd8e2534c2`). Lanes `fault-no-history`, `projection-writer-producer` and
`oversight-owning-instance` each hit it and recorded it only in their landing
notes.

Wanted: an overlay older than a schema-validator or schema-resource commit
refuses by name ("base predates <commit>; run `bin/test --prepare-head-base`")
before it runs any test. Today the refusal comes from inside fixture setup,
where the failing test looks like a red. The orchestrator owns
`bin/test --prepare-head-base`.

Sighting 2026-09-22 20:51Z (lane three-way-comparison): the overlay was 52 commits
behind HEAD. Runs `8e0595c0b640`, `e6688bfdc5b3` and `e84538519dbc` of
`seon.program-test` all hit the same 11 fixture-setup errors on `:my.note/note`.
The block also stops the incremental schema proof the owner asked for on
2026-09-23: no canonical fixture branch can be opened to transact a declaration
change on.

Sighting 2026-09-22 20:42Z (lane publication-lock-deletion): run `e838074339ff`
at HEAD `a102a8403`, overlay `d73e0a6c` 44 commits behind; all five fixture
tests of `seon.cluster.source-test` / `seon.cluster.publication-lock-test`
refused on `:my.note/note`. The lane built its own base with
`cluster/publication-base!` inside a `git archive` snapshot and ran the named
namespaces under armed contracts without recording; see its landing note.

Sighting 2026-09-22 21:05Z (lane validator-single-pass): overlay `d73e0a6c…` 67 commits
behind HEAD `f31074521`; `bin/test-fast --paths src/seon/db.clj
test/seon/owned_value_test.clj -- seon.owned-value-test` (runs `5995c1bd98eb`,
`3a17ec42b5a4`) errored in fixture setup with the same `:my.note/note` partition refusal.

Sighting 2026-09-22 21:13Z and after `d8734f1e7` (lane three-way-comparison): with the
overlay 78–90 commits behind, `bin/test-fast --paths ... -- seon.program-test` now
refuses before running any test. The refusal is
`seon.test.runner/record-snapshot!` "The recording authority returned no admission
or result facts.", caused by "Test recording requires a published current-src"
(`src/seon/cluster/source.clj`). Three runs took 29.4–36.5 s each. Until the base
is prepared, focused proof needs an unrecorded JVM that calls
`seon.test.arm/initialize-contracts!` directly.
