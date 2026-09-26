---
type: issue
status: open
severity: friction
tags: [issue, database, class/n14, wave/store-perf]
---

# Give storage GC the cutoff that makes it actually reclaim

## Re-measured 2026-09-26 16:00Z (lane inv-lifecycle-storage-opus, default pid 13262, HEAD 61d207c3e)

Collection now reclaims what is unlinked. Around one `bin/test-check default --ns seon.cluster.host-test`
request the store went 1.2 GB / 7,563 files → 1.6 GB / 10,502 files (request in flight, 186 roster branches) →
1.2 GB / 7,379 files, konserve keys 8,795 → 7,352, roster 186 → 99. A dry run afterwards found 1 candidate.

What it cannot reclaim is what nothing unlinks. 90 of the 99 roster branches were `agent-*` (48) and `host-test-*`
(42) branches last written 14:16–14:20Z by an earlier JVM, with no connection in this one: execution-owned branches
whose process died before `release-context!`/`retire-branch!` ran. Marked separately, 2,624 of 7,352 keys (36 %)
are reachable only from them. Nothing ever retires them, so every later mark walks them too.

The mark grows with branches × ancestry: the dry run took 6,550 ms at 99 branches (850 ms at the first
measurement). `datahike.gc/reachable-in-branch` (`reference-code/datahike/src/datahike/gc.cljc:22-81`) keeps its
`visited` set per branch, and the plain cutoff walks every branch's whole ancestry, so shared commits are marked
once per branch; `referenced-blobs` (1,724 ms) repeats a history query per branch.

The request's collection (`seon.maintenance/collect-released!`, handed to the launcher at `src/seon/test.clj:2485`)
returns its inventory to a `complete!` that discards it on success: no receipt records swept keys or mark time.

Retirement of dead-process branches needs a claim rule; root options are in
`docs/research/agent-platform/landing/inv-lifecycle-storage-opus-2026-09-26.md` §1.

## Re-diagnosed 2026-09-26 (lane fix-eval-schema-reds-opus)

At HEAD the growth is not a missing cutoff: **collection never runs.** Its one runtime caller
is the weekly `root/maintenance/compact` schedule, which refused because
`:seon.config.maintenance/collect?` was absent. Default had no collection receipts. Unlinked
test branches are unreachable and the plain collection reclaims them whole. On a scratch store,
10 retired member branches × 20 commits took the store from 8 to 218 files; the plain
(epoch-cutoff) collection swept 210 and left 8. A dry run on default found 971 of 2,363 keys
reclaimable (41 %), with an 850 ms mark. Ruling: the plain collection (`registry/plain-cutoff`,
no ancestry pruning) runs after each `seon.test/run` request releases its branches
(`seon.maintenance/collect-released!`), gated by the dial, which now defaults to true.
Retention pruning (a real `remove-before`) stays a separate policy, out of scope.


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

## Re-observed 2026-09-16 (orchestrator)

The development store reached 71.6 GB / 382,425 files, 93.3% `pss/leaf`
copy-on-write index nodes, ~67 GB unreachable, created 2026-09-08 and grown
through the one weekly `root/maintenance/compact` window since
(`docs/prds/context-generation/research/store-footprint-2026-09-16.md`,
commit `cfef57241`). A GC that never reclaims in practice reads absence of
signal as health. The per-minute blob-retention sweep that starved writers
is being removed (`blob-retention-sweep-starves-every-roster-writer.md`),
and the development store is reset at the next refork; the standing fix is
this issue: a real cutoff plus a cadence tied to an observed footprint
signal (the `bin/seon status --verbose` footprint), not a weekly cron.
