---
type: issue
status: open
severity: friction
tags: [issue, blob, test, class/n10, wave/blob-staging]
---

# Preserve the interrupted blob staging artifact until it can be observed

## Problem

The interrupted-write proof expects one oversized staging file after the input
stalls, but observes no staging files. Its next assertion then dereferences the
missing file and adds a secondary `NullPointerException` that obscures the
first mismatch.

## Evidence

The bare 2026-08-05 gate failed
`seon.blob-test/interrupted-staging-leaves-the-store-unchanged` at
`test/seon/blob_test.clj:155-156`:

```text
expected: (= 1 (count staging-files))
  actual: (not (= 1 0))
expected: (> (.length (first staging-files)) binary-threshold)
  actual: NullPointerException ... (first staging-files) is null
```

A focused reproduction of the same var at pre-rename commit `401fd300e`
produced the same zero-file assertion and the same follow-on NPE. This is
pre-existing behavior, not rename fallout.

## Owner

The file-backed staging lifecycle in `seon.blob` and its interrupted-write
test fixture.

## Acceptance

An interrupted oversized write leaves the store unchanged while the staging
lifecycle is observable deterministically. The regression makes one primary
assertion per state and never calls `.length` on absent evidence.
