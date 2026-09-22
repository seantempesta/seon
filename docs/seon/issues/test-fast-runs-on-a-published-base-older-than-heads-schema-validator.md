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
