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
