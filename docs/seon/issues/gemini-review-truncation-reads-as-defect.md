---
type: issue
status: open
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
