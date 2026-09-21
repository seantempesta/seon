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

## Update 2026-09-17 — the retry boundary is closed

`seon.cluster` now derives the publication phase from the failure's declared
source-change cause (`source-change-phases` / `source-change-phase`, next to
`refused!`), and `retrying-source-change` is the one retry both seams take:
an analysis-time `:seon.fn/source-changed-during-analysis` and the
adoption-time `:seon.cluster/source-changed-during-adoption` retry exactly once
and converge when the second read is stable. A refusal that survives the retry
names `:seon.source/change-phase`. The incremental catch falls back to a
complete rebuild only for an `:seon.fn/index-refused` that is NOT a source
change, so a moving tree is no longer a rebuild reason.

Regression: `seon.cluster-test/a-source-change-during-analysis-takes-the-one-publication-retry`
(drives the real `seon.fn` span read for the refusal it asserts on).
Landing note: [adoption-retry-on-analysis-refusal-2026-09-17.md](../../prds/steward-platform/research/adoption-retry-on-analysis-refusal-2026-09-17.md).

Follow-up, not in that slice: the two snapshot-before/after compares
(`src/seon/cluster.clj:1860`, `:1953`) say "retry" in their messages but carry
no `:seon.error/diagnostic-cause`, so they still hard-fail the publication.
Giving them the declared cause would fold them into the same single retry and
changes the full-refresh path's behaviour; it needs its own slice.

## Class dissolved 2026-09-16 (steward-platform)

The refusal above was the backstop; the class was the DOUBLE READ. One
analysis read each file twice — `source-contexts` captured the text spans
are sliced from, and clj-kondo re-read the same paths to produce rows and
columns — so an edit landing between the two reads produced offsets for
text the capture no longer matched.

`seon.fn.analyzer/analyze` now takes `::seon.fn.analyzer/sources`, a map of
canonical path to the captured text, writes each to one private mirror under
`tmp/analysis-mirror/<analysis>/<absolute source path>`, lints the mirror,
and deletes it. There is exactly ONE read of the live file per analysis, and
clj-kondo's rows describe the captured bytes by construction.
`seon.fn/build-artifact` and `seon.fn/build-manifest` pass their captured
text; `::paths` remains for whole directories, the synthesized stdin buffer,
and callers that own no capture.

`analyzed-source-path` (`src/seon/fn/analyzer.clj`) reads the source path
back out of a mirror path, and every rule that asks where an analyzed file
came from asks it: the emitted `::filename` on each row and finding, the
cache-ownership rule (`checkout-source?`), and the cache-obsolescence scan.
So a mirrored analysis answers exactly as a direct one did — same filenames
downstream, same shared clj-kondo cache.

Measured on pid 17352, 2026-09-16:

- `src/seon/fn.clj` (103,386 bytes), single-file analysis: 495 ms before,
  452–524 ms after — no meaningful change.
- `build-manifest` over `src` + `test` (336 files, 7.3 MB): 5,711 ms before,
  5,164 ms after; identical finding tally, including the single
  `unresolved-var`.
- The class probe: `src/seon/fn.clj` captured, then grown on disk by a
  concurrent write, then analyzed. All 108 declarations slice exactly out of
  the captured text, no refusal, every row named the real path, and the
  appended declaration was correctly absent from the analysis.

**Deliberate departure from the filed fix shape.** The shape suggested
keeping `:cache false` for a private analysis root (the b50f4ddc7 rule).
Measured, that loses signal: cache-off drops one `unresolved-var` finding
over the full tree and 60 of 1,412 external arity annotations on
`src/seon/fn.clj` alone — invalid-arity and unresolved-var are blocking
finding classes, so a silent cache-off would be this project's
absence-of-signal defect in a new place. b50f4ddc7's rule is about a DECOY
under a first-party namespace name; a mirror is not a decoy — it carries the
checkout file's own captured bytes — so `checkout-source?` resolves the
mirror to its source and the cache stays on, proven: mirrored analysis
yields 1,412 arity annotations, exactly the direct cache-on figure.

In-process proof (`seon.test/run` on pid 17352, test namespaces reloaded
through `seon.test`'s own loader):
`seon.fn-test/a-file-changed-after-capture-analyzes-to-the-captured-spans`
6/0/0 (new class regression),
`seon.fn-test/a-span-past-the-captured-source-is-the-typed-refusal` 17/0/0,
and all seven `seon.fn.analyzer-test` tests green, including
`canonical-analysis-rejects-obsolete-cache-authorities` and
`complete-roots-and-individual-files-have-parity`.

Boundary: the `tmp/analysis-mirror` parent is created under the checkout and
its per-analysis child is deleted in a `finally`; a killed JVM leaves one
child directory for ordinary `tmp/` hygiene.
