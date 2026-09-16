---
type: issue
status: open
severity: blocker
created: 2026-09-17
tags: [testing, platform-tier, store, root-resolution, destructive]
---

# A platform-tier test wiped the checkout's store

## Problem

At 10:17Z on 2026-09-17 the development root's `data/store` (3.6 GB, live
default cluster on pid 17352) became empty: publications refused with "no
branch or commit `:db` to branch from", `bin/seon status --verbose` reported
"the current-src branch is absent", and after a stop/start the store
directory was recreated from nothing (28 KB, files dated at boot). No
orchestrator or lane command reset, destroyed or collected the main root in
that window. The gate ledger shows batch 61 A (the PLATFORM tier, a cold
`bin/test` in its own run root) running
`seon.cluster.registry-test/non-temporal-collection-marks-current-blob-references`
at 10:18:11Z and `seon.cluster.registry-test/reset-returns-a-cluster-to-source-state`
at 10:18:12Z (`tmp/orchestrator/gate-results/batch-61/platform.log:468-472`).
Hypothesis under investigation (read-only lane): one of those fixtures
resolves its store/root to the checkout (`"."`, `user.dir`, or a nil
property falling to cwd) instead of its isolated operator root, so a
platform gate can reset the developer's store — the same class as the
`workers/` exhaust fixed in `86b4c8ff4`.

Evidence copy of the emptied store: `tmp/orchestrator/refork/store-wiped-2026-09-17T1100Z/`.
Recovery: `bin/seon reset --force` + start + reseed (data disposable by
ruling); the day's recorded test results were lost.

## Fix shape

1. The fixture derives its root from the isolated operator root it was
   handed, never from the process's cwd; a nil root refuses, never defaults.
2. A regression plants a sentinel store under a fake checkout `data/` and
   asserts a reset/collect drill never touches it (recursive deletion never
   follows symlinks; plant a symlinked sentinel).
3. The platform tier declares no destructive drill: destructive tests are
   `:seon.test/long` or run only under an explicit isolated root, and the
   tier's selection checker fails when one is declared `:seon.test/platform`.

Until (1)–(3) land: no gate runs the platform tier or `seon.cluster.registry-test`.

## Verdict 2026-09-17 11:40Z (peer research `fbd9c0cd9`)

The store was DELETED AND RE-CREATED FROM GENESIS, not collected: the
evidence copy's `:branches` is exactly `#{:db}` (only `d/create-database`
writes that). GC and `registry_test`'s own store fixture are cleared. Window
10:17:35Z (healthy, 91,443-row population logged) → ~10:50Z; the platform
tier began 10:18:11Z inside it. The two paths with exactly this shape are
`(io/file <root> "data" "store")` with a nil/relative root —
`src/seon/operator.clj:277/:294` (cleanup deletes clusters/store/lock/
blob-staging) and `src/seon/cluster/store.clj:281` (`create-store!` deletes
then creates) — with root fallbacks at `cluster.clj:792` and
`test_support.clj:112` (`bin/test-fast` sets no operator root): the
`86b4c8ff4` class. Exact caller not established (nothing logs a deletion;
the evidence copy lost mtimes — copy with `cp -Rp` next time). Fix lane
(peer, Opus): a nil/relative/"."/checkout root is unconstructable at both
owners (typed refusal before any delete; the owner's deliberate `reset
--force` keeps its explicit path); a log line naming root/paths/bytes/caller
before every delete; the two fallbacks removed; the symlinked-sentinel class
regression. Gates stay held until it lands. Full page:
`docs/prds/context-generation/research/store-wipe-2026-09-17.md`.
