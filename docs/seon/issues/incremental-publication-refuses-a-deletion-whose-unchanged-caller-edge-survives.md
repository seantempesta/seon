---
type: issue
status: open
severity: blocking
created: 2026-09-22
tags: [issue, publication, program-graph, test-runner]
---

# Incremental publication refuses a deletion whose unchanged caller's edge survives

Seen 2026-09-22 20:45Z by lane projection-writer-producer. `bin/test --paths
src/seon/db.clj … -- seon.schema.projection-writer-test` failed in its
`published-base` phase: HEAD preparation (`seon.cluster/publication-base!`, run in
the default JVM, scratch branch `:building-source-51528-…`) refused inside
`seon.fn/index!` at `:seon.fn/population`:

```
Program deletion leaves surviving referrers:
[{:seon.program/subject seon.db/render-diff-ai, :seon.program/relation :seon.fn/calls,
  :seon.program/referrer #:seon.fn{:sym seon.render/invoke-selected}, :db/id 4903}]
```

`seon.db/render-diff-ai` left the program in `f1e55a824` (A2 c3). The incremental
population deletes it but keeps the stored `:seon.fn/calls` edge of the unchanged
caller `seon.render/invoke-selected`, so the deletion guard correctly refuses. A
from-zero boot of the same HEAD (`bfe3445f8`) plus this lane's patch published with
exit 0, so the program itself is consistent; only the incremental path is wrong.

Consequence: no fresh published test base can be prepared, and the only existing
base (`target/test-published-bases/d73e0a6c…`, 44 commits behind HEAD) predates
`8a069b5e4`'s partition declarations. Every canonical fixture then refuses
`:my.note/note` for a missing `:seon.program/partition`, at pure HEAD as well
(`bin/test-fast --paths test/seon/schema/projection_acquisition_test.clj --
seon.schema.projection-acquisition-test`). Armed fixture tests are blocked for every lane.

Wanted: a deletion's incremental population recomputes (or retracts in the same
transaction) the `:seon.fn/calls` edges of every caller that names the deleted
identity, including callers in unchanged files. Regression: publish a program,
delete a callee whose caller file is unchanged, publish incrementally; the
population commits and the caller's edge names no deleted identity.
Raw log: `tmp/projection-writer-producer/test-gate-1.log`.
