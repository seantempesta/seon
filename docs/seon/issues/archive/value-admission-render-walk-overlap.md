---
type: issue
status: resolved
severity: cleanup
tags: [issue, architecture, render, sci]
---

# Finish the protected-walk integration with the admitted render floor

## Problem

W3 deleted the independent value sampler and made `seon.sci.admit` the one
nested-data admission walk for both floor projections, but the context walk
erased floor provenance and `/data` still owned an uncapped raw HTML renderer.

## Owner

W1 owned `src/seon/render/walk.clj`; W3 owned the admitted floor and `/data`.

## Acceptance

The walk carries a source-derived floor fact, every dropped or failed value is
loud, and `/data` delegates to the merged admitted floor with the superseded
renderer and its pinning tests deleted.

## Resolution

W1 commit `071ca1e50` threads `:seon.render/would-fall-to-floor?`, derives
per-family concrete selectors and reverse selectors, and represents cap
elision as a flat error value. W3 leaves that protected mechanism untouched.

`seon.render.data` now owns only total cursor parsing and `get-in` selection.
The `/data` route preserves its entity/path/offset vocabulary and delegates the
opened value to `seon.render.block/data-panel`, the same admission-backed HTML
floor used by routed values and per-agent debug nodes. Its independent raw
entry realization, summaries, HTML, ids, and behavior-pinning tests are
deleted. The recurring web regression sends a 5 MiB string through `/data` and
asserts the response remains capped, loud, and drillable.
