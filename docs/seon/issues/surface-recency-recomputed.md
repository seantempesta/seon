---
type: issue
status: open
severity: cleanup
tags: [issue, web, database]
---

# Surface recency may be recomputed globally

## Problem

Choosing the most recently deliberate surface may repeatedly scan or derive
recency for unchanged surfaces.

## Evidence

The archived dual-path audit's C29 row identifies the computation. Current UI
requirements sort context surfaces by recent change and choose focal content,
so this belongs to render-unit dependency/reuse rather than a standalone cache.

## Owner

The database query and render-unit dependency that select focal and sidebar
surfaces.

## Acceptance

Profiling identifies the actual query/derivation cost; changed datoms invalidate
only dependent surface units, and focus/sort remain database-derived without a
second recency registry or mutable atom.
