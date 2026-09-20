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
