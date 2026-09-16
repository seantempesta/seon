---
type: issue
status: open
severity: friction
created: 2026-09-16
tags: [publication, analysis, seon.fn, bounded-boundaries]
---

# Source analysis throws when a file changes between snapshot and span read

## Problem

`bin/seon init --dev default` after the 2026-09-16 21:00Z refork failed with
a raw `java.lang.StringIndexOutOfBoundsException: Range [109022, 114047) out
of bounds for length 114007` from `seon.fn/exact-source` (`src/seon/fn.clj:161`)
via `var-row` → `analysis-rows-by-file` → `build-manifest` →
`seon.cluster/stable-manifest` → `full-source-refresh!`
(`tmp/orchestrator/refork/refork-2026-09-16T2100Z.log`). A ~114 KB file
(`src/seon/fn.clj`, under a concurrent lane's edits) changed between the
analysis snapshot and the read that slices a declaration's span, so the
span ran past the new end of the text. The publication reports the cluster
threw, not what changed; the operator's "source changed during adoption"
retry did not cover it because the failure happened inside analysis, not at
the adoption compare.

## Fix shape

`exact-source` must read the same bytes the analyzer analyzed (hand the
snapshot's text through, never re-read the file), or refuse with the typed
`seon.cluster/source-changed-during-analysis` naming the file and the two
lengths so the operator's one retry applies. Regression: a file whose bytes
change between analysis and span extraction yields the typed refusal, never
an index exception.

Related: `one-lanes-intermediate-edit-refuses-adoption-for-every-lane`,
`concurrent-publications-serialize-past-the-hook-bound`.

## Update 2026-09-16 21:15Z

The gate session reports no lane was editing `src/seon/fn.clj` at the time
(its gate-set lane had committed `1d141d26a` and stopped before the refork).
If the retry reproduces the same range, the cause is not a concurrent edit
but a span computed from a different text than the one sliced — a cached
analysis (the analyzer's shared cache, or spans carried on program rows from
an earlier version) applied to the current file. That would make the
defect deterministic and a blocker for adoption, not a race.
