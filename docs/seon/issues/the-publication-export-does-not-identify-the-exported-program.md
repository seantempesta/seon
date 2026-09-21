---
type: issue
status: open
severity: blocker
tags: [issue, publication, test-system, wave/publication-velocity, class/tools]
---

# The publication export does not identify the exported program

## Observation — 2026-09-21 ~22:40 UTC, cold gate `tmp/orchestrator/gate-1a-six-2026-09-21b.log`

With default stopped (fresh-JVM path), the gate's base publication reached
"SOURCE publication export" and refused at `seon.cluster/refused!`
(`cluster.clj:668`): "The publication artifact does not identify the
exported program." — preceded by `WARN seon.db/projection-fallback
caller= seon.db/q missing-projection`. The child's full report is at the
path printed in the log (`clojure-9206633710720735857.edn`). `bin/seon
reset` publishes the same tree successfully, so the refusal is specific to
the gate's base path through the common publisher (`fa1ff1dbe`,
`08ce441a6` memoized artifacts). Owner: the publication-dissolution lane
at resume; its landing never ran a gate because every fast run was blocked
by the write-schema defect fixed in `2d0e9b17e`.

## Repair — publication lane, morning resume

`4b0347f20` acquires the exported commit with the existing
`seon.cluster.source/database` owner, which carries that commit's schema
projection. The previous raw `commit-as-db` value lacked it. The digest is
read once, checked for a read refusal, and reused for artifact comparison
and exported provenance. No raw-query bypass or alternate publisher was
added. `seon.cluster.publication-export-test` publishes without a caller's
projection, reopens the exported store, and compares its actual digest,
commit ID, and basis transaction to the emitted artifact/provenance.

Proof is pending: the first selected-path run included the parked foreign
schema-acquisition hunks in `cluster.clj` and refused at population with
`:seon.db.process/id` uninstalled. The fix was then committed independently
of those hunks. The HEAD-only retry loaded and armed successfully but
executed zero tests because another process held `data/store.lock` during
snapshot admission. Status stays open until the regression runs.

The dedicated export retry also executed zero tests: newly committed
`:seon.call-preparation/ambiguous-call-error` requires an unstorable nested
vector member. See `a-call-preparation-facet-requires-unstorable-candidates.md`.
The export identity comparison has not been exercised after the fix; do
not count successful namespace loading as export proof.


## Complete export inventory check — 2026-09-21

Fresh preparation reached the exported-input comparison and refused a valid
source/test inventory. Raw evidence:
[the retained preparation log](../../prds/agent-platform/landing/fresh-start-test-base.log).
The exported `target/test-published-bases/1727c14e2d978e6e1bf31478b4ef808fcdc08e7a75b3030b728c15462d277ab2/base/build/current-src.edn`
contains graph files as well as non-graph inputs. `ensure-base!` compared that
complete inventory with an expected map filtered by `input-roots`, which
intentionally excludes `graph-roots` for test-widening decisions. The refusal
reported no changed files and every exported src/test file as removed.

The repair adds `publication-inputs`: derive graph roots and non-graph input
roots once, then include only the graph files accepted by the publisher's
existing `source-file?` classifier alongside all declared non-graph inputs.
The classifier moves from `seon.cluster.source` to its existing cache dependency;
both the snapshot and export verification call that same predicate.
`input-roots`, `widening-path?` and `test-input-digest` retain their existing
non-graph semantics. The canonical cache regression checks matching complete
inventory, real source/test changes and deletions, excluded documentation,
and unchanged widening digest for graph/document edits.

No gate ran in this bounded repair. With no prepared baseline, a dirty
`--paths` invocation recursively runs HEAD-only preparation first
(`bin/test:828-839`), so it cannot bootstrap this uncommitted fix. The root
must first land the cache/test slice, then run `bin/test seon.test-cache-test`
against that HEAD: the ordinary named gate prepares its own base and executes
the namespace. Alternatively prepare the new HEAD explicitly with
`bin/test --prepare-head-base`, then run the named gate. Status stays open
until the exported comparison and canonical regression complete.


The root's first pure-owner verification passed 4 tests / 26 assertions but
rejected the actual export with 19 changed paths and no removals. These were
nonindexed text, Python and CJS fixtures under `test/`; treating every graph-root
file as published source was too broad. The revised regression includes `.clj`,
`.cljc` and `.edn` indexed inputs plus `.txt`, `.clj.txt`, `.py` and `.cjs`
fixtures that stay outside this export comparison. Neither implementation selects
only keys already present in the export: missing/changed indexed source still
refuses, so incomplete publication cannot validate itself.

Separate invalidation gap, deferred from this bootstrap repair: `test-input-digest`
filters non-graph roots, while `source-inputs` includes every graph-root file.
A nonindexed fixture under `src`/`test` is therefore hashed in the full gate
inventory but absent from the external-input signature and has no indexed
program declaration for reach selection. The export must not invent a published
row to hide this distinction. The test-selection owner must prove how fixture-only
changes invalidate affected green results (or widen conservatively) before claiming
complete incremental coverage. This repair preserves current widening semantics;
it does not claim to close that separate gap.

The bootstrap repair passed the production Babashka cache owner tests: 4 tests,
26 assertions, zero failures/errors. Against the retained real export, all 702
expected input digests matched; deliberate source modification/test deletion
were detected. A fresh JVM required cache, source, cluster, reply and SCI owners
successfully. This tooling slice must land before the isolated gate: its automatic
HEAD-base preparation cannot overlay the broken HEAD cache owner. Canonical gate
verification follows that bootstrap commit; the broader issue remains open.
