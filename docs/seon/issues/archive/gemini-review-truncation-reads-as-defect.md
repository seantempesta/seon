---
type: issue
status: resolved
severity: minor
tags: [issue, tooling]
---

# Gemini review reports truncated file blocks as defects

## Problem

The hook review truncates each file block at `:max-code-length` (12k
chars). On files over the cap, Gemini reads the cut as a real defect
("function body is incomplete and will fail to compile") — a
false-positive class that costs reviewer trust.

## Evidence

2026-07-27: `tmp/reviews/20260727T112009` flagged `plan-tx` in
`src/seon/cluster/run.cljc` as "cut off mid-form" (the file compiles and
its suite runs); `tmp/reviews/20260727T113339` reported a "Syntax Error /
Truncated File" for `test/seon/cluster/run_test.clj` (loads clean, 6
tests discovered). Both files exceed 12k chars.

## Owner

`bin/seon-hook` review prompt construction: the truncation marker must
be unmissable — e.g. replace the tail with an explicit
`;; [REVIEW TRUNCATION — the file continues; do not report incompleteness
beyond this point]` line inside the code fence and instruct the reviewer
that truncated blocks are partial views. Alternatively raise the cap for
Clojure source.

## Acceptance

A review of a >12k-char file that compiles reports no
truncation/incompleteness finding; the review still covers the included
prefix.

## Resolution (2026-07-27)

`bin/seon-hook` now cuts every over-cap block with an in-band
`REVIEW TRUNCATION` marker written in that block's own comment syntax
(`;;` for Clojure, `<!-- -->` for Markdown), and the review instructions
state that a marked block is a partial view whose incompleteness must
never be reported. `:max-code-length` also rose from 12k to 40k, so an
ordinary Clojure source arrives WHOLE and the class cannot arise at all;
the batch budget moved to its own `:max-batch-length` dial instead of
silently reusing the per-file cap.

Live proof: a `PostToolUse` review of `src/seon/cluster/run.cljc`
(18,994 chars — the file the issue cites) produced
`tmp/reviews/20260727T120217.175Z.md` with zero matches for
`truncat|incomplete|cut off|unbalanced|syntax error`, while still
reporting real findings against the file. A second run under a 500-char
cap confirmed the marker renders in Clojure and Markdown blocks alike.
