---
type: issue
status: open
severity: friction
tags: [issue, database, datahike]
---

# Give storage GC the cutoff that makes it actually reclaim

## Problem

Seon calls Datahike's storage GC in its weakest form. `d/gc-storage` with no
`remove-before` cutoff marks the complete commit ancestry of every extant
branch. It can reclaim any object outside those graphs—deleted-branch objects,
orphan nodes, or failed-write debris—but every intermediate commit's index
nodes remain reachable and stay on disk forever. Since every Seon commit
rewrites a root-to-leaf path per index, that retained ancestry is precisely the
growth the current call cannot reclaim.

The pin also ships a concurrent background collector that is safe against an
active in-process writer. It is **not directly adoptable by Seon**, however:
`seon.cluster.registry/collect!` extends Datahike's reachability mark with
schema-discovered content-addressed blob keys
(`src/seon/cluster/registry.clj:318-350`). Calling
`datahike.gc/start-background-gc!` directly would bypass that extension and can
sweep live Seon blobs. A periodic collector must remain behind Seon's one GC
owner and preserve the extended mark.

## Evidence

`src/seon/cluster/registry.clj:293` —
`(count @(d/gc-storage (:seon.store/connection store)))`, no cutoff argument.

`reference-code/datahike/doc/gc.md:20-24` highlights deleted-branch garbage.
The source is broader: the plain form marks every extant branch and its full
ancestry, then sweeps anything else; the cutoff form prunes ancestry before its
date (`reference-code/datahike/src/datahike/gc.cljc:83,119`).

`reference-code/datahike/src/datahike/gc.cljc:148,167-168` —
`start-background-gc!` with `:interval-ms` (default 300000) and
`:history-window-ms`, sweeping to the store's safe point across branches.

The retained reproduction
`docs/prds/sci-execution-runtime/research/scripts/store-options-before-after-2026-08-02.clj`
performed 80 replacements in a fresh private fused/diff/history-on store:

| collection | swept | objects after | bytes after |
|---|---:|---:|---:|
| before | — | 90 | 7,847,585 |
| plain `gc-storage` | **0** | 90 | 7,847,585 |
| cutoff at measurement time | **86** | 4 | 372,335 |

The cutoff reclaimed 7,475,250 bytes (95.3 %) and removed a sampled old commit
record. That is the intended effect, and also the safety cost: after cutoff a
caller can no longer branch from that pruned commit id. `remove-before` controls
which commit snapshots stay reachable; the independently computed safe point
controls which recently written objects may be swept while writers are active
(`reference-code/datahike/src/datahike/gc.cljc:83-146`).

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
  measured drop in on-disk object count; the retained script now proves
  90 → 4 objects while the plain form proves 90 → 90.
- The cutoff is derived from a stated retention position (and must agree
  with whatever `:keep-history?` decision lands), not a tuned constant.
- Whether collection stays operator-triggered or becomes the background
  collector is decided explicitly, with the safe-point interaction against a
  live writer proven rather than assumed. Either form preserves Seon's
  schema-derived blob reachability extension.
- The call site's docstring stops claiming reclamation it does not perform.

## Current disposition 2026-08-02

**The reclamation is proven; a safe default cutoff is not yet specified.** A
`Date.`/"now" cutoff is valid only for a private benchmark with no stale
database values or commit ids in flight. Production needs an explicit maximum
snapshot/commit-id retention contract. History-on does not itself require
keeping every old database snapshot forever—the current temporal indices carry
historical datoms—but Seon's exact-commit branch creation does require the
selected commit record to survive until the branch is published. No cutoff or
background schedule should land by guess.
