---
type: issue
status: open
severity: friction
tags: [issue, database, ordering, class-kill]
---

# Make domain order come only from recorded order facts

## Problem

When ordering facts tie or disappear too early, selection falls through to
identifier strings or hash-map/set iteration. The result is deterministic only
by accident and becomes wrong at two digits or under a different collection
layout.

## Evidence

Four open issues recur on 2026-08-02 and 2026-08-04:
[[duplicate-identity-refusal-evidence-is-unordered]],
[[effect-feedback-orders-receipts-by-id]],
[[latest-closed-run-orders-by-id-string]], and
[[transcript-candidate-window-orders-receipts-and-comments-by-id]].

## Owner

The relevant database queries and their numeric/transaction order facts.

## Acceptance

- Every ordered selection carries its declared numeric, tuple, ordinal, or
  transaction order through the final limit/window operation.
- Identifier spelling and collection iteration are absent from tie-breaking.
- Generative tests cross 9→10 and permute input collection order while
  preserving the same selected result.
