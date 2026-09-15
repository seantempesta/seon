---
type: issue
status: resolved
severity: friction
tags: [issue, render, sci, test, wave/verification-audit]
---

# Directory check reimplements the directory owner and reads the retired shown shape

## Problem

`directory-problem` recognizes only the literal operators `dir` and `clojure.core/dir`, duplicates the namespace/public-function query, and expects shown text to contain `:functions`. The new directory pair writes `:seon.repl/columns`, `:seon.repl/rows`, and `:seon.repl/schemas`. It therefore cannot compare that result; the regression manufactures the retired `{:functions []}` text. The duplicate query treats a missing private flag as public, whereas the actual directory query requires `:seon.fn/private? false`. With no recognized directory subjects it reports zero findings and zero unknown observations.

## Evidence

Audit-1, 2026-09-15; committed snapshot `0c70a1cb4b14a391935d47762580abe02c235cc4`. Line numbers below refer to that snapshot, not concurrent working-tree edits.

- `src/seon/render/transcript.clj:1765–1798`
- `src/seon/sci/eval.clj:1107–1179`
- `src/seon/repl.clj:21–30`
- `test/seon/render/web_debug_test.clj:223`

## Owner and deletion

Delete the duplicate public-function query and operator roster. Share the directory owner's declared semantics and identify observations from evaluation/read provenance. Make elided or unrecognized observations explicitly unavailable or not applicable.

Estimated change: 35–60 lines merged/replaced. Audit classes: 1, 3, 4. No production edits for this finding were made by the audit lane.

## Acceptance

Resolved by the A01 commit containing this note: the declared renderer and
saved namespace pull identify observations. The directory owner and its current
pair supply the comparison at the read basis; no public-function query or
column decoder is copied. Elided, missing and unrecognized observations are
unavailable. The real qualified SCI call and all three unknown cases pass.
Fast: 10 tests / 151 assertions; isolated: 10 / 155, zero failures/errors.
Responsive screenshots were inspected; hash and counts are in the landing note.

Exercise real SCI directory calls and the actual current pair, including a qualified/aliased call, an elided result, and no directory observations. Never maintain a third decoder for the column layout. Compare the observed membership through the owning data boundary; do not infer missing functions from presentation cuts.

See [the audit](../../prds/context-generation/research/audit-1-2026-09-15.md) for scope, change counts, and verification limits.
