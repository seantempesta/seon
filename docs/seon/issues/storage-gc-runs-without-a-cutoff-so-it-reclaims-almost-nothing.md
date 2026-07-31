---
type: issue
status: open
severity: friction
tags: [issue, database, datahike]
---

# Give storage GC the cutoff that makes it actually reclaim

## Problem

Seon calls Datahike's storage GC in its weakest form. `d/gc-storage` with no
`remove-before` cutoff reclaims only the garbage left by **deleted branches**
— every intermediate commit's index nodes stay on disk forever. Since every
Seon commit rewrites a root-to-leaf path per index, that is precisely the
garbage that accumulates, and it is the garbage the current call cannot see.

The pin also ships a concurrent background collector that is safe against an
active in-process writer — exactly Seon's topology — and it is unused.

## Evidence

`src/seon/cluster/registry.clj:293` —
`(count @(d/gc-storage (:seon.store/connection store)))`, no cutoff argument.

`reference-code/datahike/doc/gc.md:20-24` states that the plain form
reclaims only deleted-branch garbage; actual eviction needs the cutoff form
at `reference-code/datahike/src/datahike/gc.cljc:83,119`.

`reference-code/datahike/src/datahike/gc.cljc:148,167-168` —
`start-background-gc!` with `:interval-ms` (default 300000) and
`:history-window-ms`, sweeping to the store's safe point across branches.

The docstring at the call site claims "the cost scales with total data and
the isolation is structural. Idempotent: a second pass over the same state
sweeps zero" — which is true of the call as written and hides that it is
sweeping almost nothing.

Full sweep:
`docs/prds/sci-execution-runtime/research/upstream-delta-sweep-2026-07-31.md`.

## Owner

`seon.cluster.registry` — the one GC call site.

## Acceptance

- A store that has taken many commits and then been collected shows a
  measured drop in on-disk object count; today's call shows none.
- The cutoff is derived from a stated retention position (and must agree
  with whatever `:keep-history?` decision lands), not a tuned constant.
- Whether collection stays operator-triggered or becomes the background
  collector is decided explicitly, with the safe-point interaction against a
  live writer proven rather than assumed.
- The call site's docstring stops claiming reclamation it does not perform.
