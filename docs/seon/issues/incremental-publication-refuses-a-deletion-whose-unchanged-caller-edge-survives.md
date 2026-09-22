---
type: issue
status: fixed-pending-default-reload
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

## Cause (verified 2026-09-22, lane deletion-caller-edge)

The surviving edge is not a literal call. `seon.render/invoke-selected` declares
`{:seon.fn/invokes #{:seon.render/ai :seon.render/html :seon.render/form}}`, and
`analysis-rows-by-file` derives its `:seon.fn/calls` from EVERY schema form naming a
producer under those attributes (`schema-targets`). `render-diff-ai` was named by
`{:seon.render/ai seon.db/render-diff-ai}` in `seon.db.diff.edn`; f1e55a824 removed
the declaration and the function, `render.clj` did not change, so the incremental
selection (`db.clj` + the two schema resources) never recomputed the caller.
Default (pid 51528) stores 112 edges on `invoke-selected`, including this one.

A second defect sat underneath: a partial analysis of `render.clj` alone kept only
16 of those 112 edges, because the first-party filter knew only symbols mentioned
in the analyzed batch. Any incremental edit of `render.clj` silently dropped 96
declared edges.

## Fix

`f31074521` (`src/seon/fn.clj`): `analyzed-files` reads the removed definitions'
stored `:seon.fn/calls`/`:seon.fn/references` referrers from AVET
(`removed-definition-caller-paths`) and re-analyzes their files in the same
population; `index!` reconciles every file whose rows are supplied
(`reconciled-paths`). `analyzed-artifacts` asks the database for invokers'
declared targets, so partial analysis keeps all 112 edges. The deletion guard is
unchanged. Regression: `test/seon/fn/incremental_deletion_test.clj`.

Evidence and the proof boundary: `docs/prds/agent-platform/landing/lane-deletion-caller-edge-2026-09-23.md`.
The shared base is prepared by the DEFAULT JVM's loaded `seon.fn`, so
`bin/test --prepare-head-base` keeps refusing until default reloads `seon.fn`.

## Remaining (same class, not fixed here)

- A schema change that ADDS a producer under an invoked attribute does not
  re-analyze the invokers (`invoke-selected`), so the new declared edge is missing
  until `render.clj` changes: under-reach for test selection, not a refusal. The
  recomputation event is "a form under an invoked attribute changed"; its callers
  are the definitions holding `:seon.fn/invokes` of that attribute.
- `seon.fn/source-rows` (agent-submitted forms) filters declared targets by the
  submitted batch's functions only, the same shape the analyzed-artifacts fix removed.

Sighting 2026-09-22 21:10Z (lane validator-single-pass): `bin/test --prepare-head-base` at
HEAD `4135c518a` refused with the same `seon.db/render-diff-ai` ←
`seon.render/invoke-selected` referrer (37 s, exit 1); default pid 51528 still runs code
older than `f31074521`. RESET NEEDED (or reload of `seon.fn`/`seon.cluster` in default).
