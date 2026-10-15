---
title: "overlay-mem: the +128 MB per test world"
type: research
status: fixed in forks; Seon slice committed; submodule bump pending (root)
created: 2026-10-15
---

# overlay-mem: the +128 MB per test world (lane overlay-mem, Opus, 2026-10-15)

Follows [exp-a](exp-a-memory-floor.md). Everything ran in throwaway JVMs: a synthetic file store in the fork, then an
APFS clone of `data/store` (`cp -c -R` + `cp -c -R -n`, 8 min 18 s for 400,945 files, 2.3 GB) opened under its
original store id, from a `git archive` of Seon HEAD with the forks linked in. Default was never touched.

## Holder

`konserve.tiered/TieredStore`'s `-get-in` and `-multi-get` under `:read-policy :frontend-first` copy every value read
from the backend into the frontend (`konserve.tiered/warm!`, fired asynchronously). A `:datahike/overlay` store
(`datahike.store`, `ks/-connect-store :datahike/overlay`) is that tiered store, with a `konserve.memory/MemoryStore`
frontend registered under the overlay uuid. So every index node an overlay read was decoded once, put in its own node
cache, and also kept in the frontend's `:state` atom until `delete-database`. The node cache is bounded but the
frontend is not, which is why capping the cache at 16 MB (exp-a b1) changed nothing. Measured in the fork: 4 overlays
that scan 80k datoms hold **315 frontend keys each** (every node they read) and **5 node caches**.

The copy is not something the overlay needs. Overlay semantics are: writes go to the frontend, and a key the frontend
lacks reads from the backend. The copy suits konserve's other `:frontend-only` user, an LMDB cache over S3.

A second cause: `datahike.store/physical-store-key` keeps the whole overlay store map, random `:overlay` included, so
`datahike.connections/reserve-connection-opening!` gave every overlay its own node cache (512 MB weighed in Seon).

A third cause, on the Seon side: `seon.cluster.store/release-store!` on an overlay released only the `:db` connection.
A branch connection still open made `d/delete-database` throw `:database-has-active-connections`. That stranded the
overlay with its connection and its own 512 MB node cache. Reproduced in a red test.

## Fixes

| where | commit | change |
|---|---|---|
| konserve fork | `24be381` on branch `seon/overlay-no-populate` (pushed; based on `b4b896f`, separate from konserve-read's `seon/tiered-range-reads`) | `connect-tiered-store` takes `:populate-frontend? false`: a backend read is returned uncopied. Default unchanged. |
| datahike fork | `1758dfb1` on `seon/overlay-file-frontend` (pushed, on top of omb-gc's `00c58326`; identical tree change proven as `59c4c919` on `593a8276`) | the overlay connects with `:populate-frontend? false`; new `datahike.store/node-store-key` (an overlay's is its backend's); `reserve-connection-opening!` shares the node cache by node-store key and keeps write hooks keyed by physical store; `deps.edn` pins konserve `24be381` |
| Seon | this commit, `src/seon/cluster/store.clj` | `release-store!` on an overlay releases every connection whose id carries its overlay marker (`datahike.connector/release conn true`, projection metadata dropped), then deletes; nothing catches |

Sharing the cache is safe because node addresses are squuids (or content hashes). An overlay reads the backend's node
at the same address, and the nodes it writes get fresh addresses. One limit: online GC's freelist recycling
(`:online-gc {:enabled? true}`, single branch only) could reuse an address inside an overlay and put different content
under it in the shared cache. Seon does not enable online GC.

The `seon.test` failure path (`.addSuppressed` around `release-store!`) needs no change: the release no longer throws
on open branches, and any other release failure still travels with the thrown failure.

## Proofs (red first)

- konserve `konserve.tiered-populate-test/reads-populate-the-frontend-only-when-asked`: red (frontend held
  `#{:inherited :other :local}`), then green. Full konserve suite: 94 tests, 1,413 assertions, 0 failures.
- datahike `datahike.test.overlay-test/overlays-read-through-their-backend-without-copies` (one cache for the store id;
  frontends empty after scans; four scanning overlays retain less than half of one base scan, after a forced GC; an
  overlay write stays in that overlay). Red at `593a8276`: 5 caches, 315 keys per frontend, +30.1 MB against a
  3.7 MB bound. Green: +0.05 MB. Full `clj-pss`: 968 tests, 5,623 assertions, 0 failures, 2 errors in
  `background-gc-test`. Both errors are "File store already exists" at the fixed temp path `dh-bgc-branch`, and the
  same error occurs at `593a8276`.
- Seon `seon.cluster.store-test/releasing-an-overlay-ends-its-open-branch-connections`: red against HEAD's
  `release-store!` (the delete throws and the `:open-member` connection survives with its own cache), then green.

## End to end (clone of data/store, 1,440,270 eavt+aevt datoms on `:cluster-default`)

A world is `seon.cluster.store/open-overlay!` + `d/branch!` + `acquire-branch!` + one transaction + an eavt and aevt
scan. Live heap is measured after three `System/gc`, `-Xmx5g`.

| worlds open | before: live MB (growth) | caches | after: live MB (growth) | caches |
|---|---|---|---|---|
| 0 | 337 | 1 | 337 | 1 |
| 1 | 632 (+295) | 2 | 339 (+2) | 1 |
| 4 | 1,400 (+1,061) | 5 | 338 (+1) | 1 |
| released | delete throws `:database-has-active-connections` | | 337, 1 cache, 1 connection | 1 |

That is +265 MB per world before and about 0 after. The morning OOM (4 worlds open) matches the before column.

## For root

- Bump the Seon submodules: datahike to `1758dfb1` and konserve to `24be381`. Konserve's `24be381` and
  konserve-read's work are on separate branches and need a merge before both can be pinned. The datahike fork's
  `deps.edn` pins `24be381`.
- The Seon slice in this commit is correct with or without the bump. The memory win needs the bump.
- The full `clj-pss` suite ran on `593a8276` plus this change, not on `00c58326` plus it.
