---
type: issue
status: resolved
tags: [issue]
severity: cleanup
---

# `seon.dev.markdown/format-violations` truncation off-by-one

Flagged by the docstring-lint build agent (2026-07-01) while writing the
sibling `seon.dev.docstring/format-findings`.

## Problem

`seon.dev.markdown/format-violations` truncates output to a `max-length`, but
the elision suffix `"\n... (truncated)"` is 16 chars while the code subtracts
only 15 — so a truncated string can exceed `max-length` by one char.

## Fix

Compute the suffix length rather than hardcoding 15 (the new
`seon.dev.docstring/format-findings` already does this correctly — copy that
approach).

## Acceptance

- Truncated output is `<= max-length` for all inputs.
- A regression test in `seon.dev.markdown`'s test ns pins the boundary.

## Notes

Cosmetic (display truncation only) — no correctness impact on lint findings.
Out of scope for the docstring-lint task; captured here so it isn't dropped.
Related: [[compact-namespace-cards-spec]] (the docstring-lint work that
surfaced it).
