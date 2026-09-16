---
type: issue
status: resolved
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

## Update 2026-09-16 21:20Z — it was a live edit after all

The 114,007-byte file was `test/seon/render/web_test.clj` (114,561 bytes
minutes later, dirty under the dir-elision lane's edits), not `fn.clj`. So
the class stands as filed: a file changing between the analysis snapshot
and the span read yields a raw index exception instead of the typed
source-changed refusal the operator retries on.

## Resolved 2026-09-16 (steward-platform)

Cause, confirmed live on pid 17352: `source-contexts` (`src/seon/fn.clj:138`)
captures each file's bytes once, and `analyzer/analyze` re-reads the same
paths itself (`src/seon/fn.clj:1622`, `src/seon/fn.clj:1526`). The analyzer's
rows and columns therefore describe a DIFFERENT read of the file than the text
they are sliced against, and an edit between the two reads left
`exact-source` calling `(nth line-starts (dec row))` past the captured text.
Reproduced against the loaded pre-fix definition with a file grown after its
capture: `java.lang.IndexOutOfBoundsException` carrying no message, exactly
the adoption failure logged in `tmp/orchestrator/fn-adopt2.log`.

Fix: the span read is total. `character-offset` and `exact-form-span` refuse a
row or offset the captured text cannot hold, through `span-refused!`
(`src/seon/fn.clj:146`), which names `:seon.fn.file/path`, the analysis span,
`:seon.error/diagnostic-offending`, `:seon.fn.file/captured-length`, and the
captured digest against the file's current digest, under
`:seon.error/kind :seon.fn/index-refused` with
`:seon.error/diagnostic-cause :seon.fn/source-changed-during-analysis`.
A span that still fits reads the captured source unchanged.

Regression: `seon.fn-test/a-span-past-the-captured-source-is-the-typed-refusal`.

Retry boundary (verified, not edited): the one adoption retry at
`src/seon/cluster.clj:2244` keys on `::source-changed-during-adoption` under
`[:seon.boot/offense :seon.error/diagnostic-cause]`, raised only by the
post-publication digest compare (`src/seon/cluster.clj:2169`). An
analysis-time refusal does not reach it. It does reach the incremental
catch at `src/seon/cluster.clj:1904`, which reads `:seon.fn/index-refused`
and falls back to a complete rebuild. So a changing file is now a named
refusal plus one full rebuild; making it retry adoption needs
`::source-changed-during-analysis` added to that retry predicate in
`seon.cluster`, which this lane did not own.
