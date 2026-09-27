# Boot "store → branch" 50.6 s — 2026-09-27

Lane `boot-branch-open-opus`. The live JVM was pid 89589, which booted at 18:34Z on b7e148de6. Every probe below ran on the default cluster through MCP `eval_clj` in JVM mode. All of them were read-only, except for one disposable scratch branch, which was retired afterward.

## Cause

The branch layer is not slow. The 50.6 s between `:seon.store/store` (672 ms) and `:seon.store/branch`
(51,251 ms) was the boot publication, which stood with no layer of its own. In
`stand-boot-layers!` (`src/seon/cluster/boot.clj:154`), the code between the store stamp and the branch stamp does this:
`changed-source-paths` → `cluster/refresh-source!` (the program publication, when inputs changed) →
`retire-unclaimed!` → `roster` → reachability permit → fork. The only proportional work is the
publication, and its cost follows the number of **changed inputs**:

| boot | commit | files changed vs. published head | store → branch |
|---|---|---|---|
| 18:00Z | 9e61340b2 | 10 | 9.0 s |
| 18:34Z | b7e148de6 | 275 of 825 (entity-vocabulary sweep 799cd62a7 and others; 1 deleted, 1 added) | 50.6 s |

Evidence that the gap is the publication: the boot's seal `:seon.source/built-at` was 18:35:08.528. The start began at about
18:34:17.5, because served (+54,319 ms) logged at 18:35:11.857. That places the seal at about +51.0 s, just before the branch stamp at
+51,251 ms. The next seal, at 18:35:24.883, is the post-boot adoption.

## Refuted candidates (warm, live store)

| candidate | measured |
|---|---|
| roster (`d/branches`) | 0.36 ms; 4 branches (`:db :current-src :design-1 :cluster-default`); boot retired 1 checkpoint |
| `retire-unclaimed!` claims (per-branch `branch-as-db` + 2 queries) | 2.2 / 6.5 / 4.8 / 24.1 ms |
| `changed-source-paths` (commit-as-db + file-digest query) | 123 ms |
| `ensure-dependency-analysis!` | 7.6 ms (all jars marked; config unchanged since 09-22) |
| Datahike connect / konserve key listing | not in the gap: the store layer stamps at 672 ms, and a branch open is milliseconds |
| captured heads (`capture-ms 7083`) | runs in `script/seon/operator.clj:710`, before the JVM; not part of ready-ms (it is part of total-ms 96,078) |

## The publication, reproduced (armed, warm)

The reproduction publishes the same 275-path change from the prior head `6ab959e2-…` (the 18:01 seal) onto a disposable branch,
`probe-boot-branch-<uuid>`, which was retired afterward. The kondo cache was a private copy, so the shared cache was not written.

| phase | ms |
|---|---|
| `seon.fn/analyze-entities` (275 paths → 6,731 entities) | 8,442 (11,340 first run) |
| `populate-source!` total | 12,571 |
| · contract projection (3,569 schemas, 1,308 fns) | 2,697 |
| · contract entities 5,342 | 904 |
| · reconciliation transaction (4,928 operations) | 8,345 |
| ·· `db/transact!` | 6,966, of which `validate-write-report` 3,029, `write-digest-error` 1,620, `write-owned-values-error` 790 |
| ·· `seon.test.accretion/generatable?` ×172 | 3,317 (19 ms each) |
| ·· `db/with-declarations` ×19,498 | 5,249 inclusive |
| ·· `fn/report-identities` ×2 | 2,663 |

The warm, armed total is about 21 s. Boot runs the same work **unarmed but on a cold JIT**, straight after class
loading, and took about 50 s. The remaining ~29 s is attributed to the cold JVM plus the `publish!` wrapper (scratch branch, issue and task
indexing, seal, `force-branch!`). This is not measured separately: boot's publication runs before arming, so the profiler
cannot see it.

## A second publication on every start whose analyzer inputs changed

Boot publishes with the *launched* analyzer digest `e10808d4…` (tx 536871072). The post-boot adoption
reads the loaded identity, computes `d09a929d…`, and republishes: `development-source-refresh!` 15,512 ms and
`full-source-refresh!` 12,720 ms (tx 536871074), with **0 file digests changed**. This is outside the branch layer,
but it is 15.5 s of the 96 s total. The owner is `seon.cluster/loaded-analyzer-digest` / `full-source-refresh!`
(`src/seon/cluster.clj:2567`, `:1971`), in a file this lane does not own.

## Fix landed (small, ours)

`src/seon/cluster/boot.clj`: the boot publication now stands as its own layer, `:seon.source/current` (the
published head boot proceeds from). It is stamped between `:seon.store/store` and `:seon.store/branch`, so the
timeline names the publication instead of charging it to the branch. `boot-layers` is now in true acquisition order, with
`:seon.source/commit-id` after `:seon.boot/cluster-connection`, where `stand-boot-layers!` assocs it. This change does not
make boot faster. It makes the next slow boot self-diagnosing.

REPL proof: the edited namespace was loaded under a scratch name. Its `boot-timeline` stamps
`[prepl-server 1, store 600, current 700, branch 800]`, and the instance validates against `:seon.boot/instance`.

## What would make start ≤ 10 s (owner decision)

Each option lists its guarantee, cost and trade-off.

1. **Recommended: make publication cost follow the change.** Cache `generatable?` by schema-form digest in
   `seon.cache` (3.3 s → ~0 on repeat). Stop computing `write-digest-error` and `write-owned-values-error` on a committed
   write (4.6 s of the 7 s transaction). Compute the contract projection only for the changed functions (2.7 s).
   Guarantee: same entities. Cost: about 1 day across `seon.db`, `seon.fn` and `seon.test.accretion`. Trade-off: none known.
   Expected: a 10-file boot goes from 9 s to about 2–3 s. A 275-file sweep stays at about 20–25 s cold.
2. **Serve before publishing.** The JVM already runs the committed archive, so boot could stand the branch and web on the
   prior head and publish afterward, as adoption does. Guarantee: ready ≤ 10 s regardless of the change. Cost: arming
   currently reads the *published* program (`cluster/arm-host-program!` at `boot.clj:77`), so arming must move
   after publication. That is cross-owner (boot, cluster, sci/eval), about 2 days. Trade-off: a window in which the loaded
   program is ahead of the published entities.
3. **Remove the double publication.** Boot publishes with the loaded analyzer digest, so the adoption is a
   no-op. Saves 15.5 s of total-ms on every analyzer-changing start. Cost: small, in `src/seon/cluster.clj`.

## Findings met on the way

- An `analyze-entities` contract refusal (a vector where a set belongs) stored a **7.9 MB** error blob
  (`389176522603…`). The offending value carries the whole request, captured sources included. This breaks
  "errors hold references, never copies" (owner: `seon.instrument` refusal construction).
- `seon.cluster/refresh-source!` in the live profile: ×3, total 85 s, max 28.4 s; `schema/call-with-projection` max 28.2 s.
  These are post-boot publications by other work, all over one second.

## Tests for root (after the next restart)

- `seon.cluster.boot-timeline-test`. It reads the live instance, so it needs a JVM booted on this commit. Until then
  the live instance has no `:seon.source/current` and the test fails.
- `seon.cluster.boot-test` (the readiness `missing-layers` assertions).
