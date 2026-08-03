---
type: issue
status: resolved
severity: correctness
tags: [issue, schema, error]
---

# Derive the error class census from distinct classes, not occurrences

## Problem

The error catalog estimated approximately 118 target classes by subtracting
53 blame occurrences as though every occurrence were a distinct class. The
source contains only two blame literals, and their sites resolve to 45 real
class declarations.

## Evidence

An independent construction-site pass found 163 distinct current kinds,
including omitted `:my.fs/invalid-glob`, two indirect SCI-kernel classes, and
one cluster-store failure. Applying the ruled merges, splits, AI replacements,
and blame-site replacements yields exactly 225 catalog-scope class schemas.
The grouped audit's SCI/outcome subtotal is 21, not its initially reported 20.

## Owner

The census and its arithmetic are owned by
`docs/prds/sci-execution-runtime/research/error-catalog-2026-08-03.md`.

## Acceptance

The catalog records the source-derived count and the missing classes, and the
declaration gate queries the complete class-schema population rather than
pinning the obsolete estimate.

## Resolution

Resolved in the error-wave slice-1 documentation correction. The catalog now
records 225 target classes and names the corrected census inputs.
