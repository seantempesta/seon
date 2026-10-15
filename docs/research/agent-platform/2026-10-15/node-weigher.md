# node-weigher: the node cache weighs real retained bytes (lane node-weigher, Sonnet, 2026-10-15)

Fork `seon/overlay-file-frontend` at `96000df8` (on `771a0c4b`; the first push `b19fa3c7` is replaced, see section 5), `datahike.index.node-cache/node-bytes`. Calibrated on an APFS clone of `data/store` (`cp -c -R` then `cp -c -R -n`, 65 s) in throwaway JVMs on Seon's classpath with the fork worktree first. Default never touched. Clone, worktree and JVMs removed.

## 1. Calibration (512 MB budget, scans of eavt, aevt, avet and history eavt)

Ground truth: a deep object-graph walk (JOL `VM.sizeOf` plus reflection; JOL works on JDK 26 with `-Djol.skipHotspotSAAttach=true`; `jcmd` self-attach hangs), skipping interned keywords, settings, storage, comparators and child nodes (a weak reference counts itself only). Check of the walk against the heap: full walk of 16,353 cached nodes 262.4 MB; heap after full GC with the cache held minus emptied 264.8 MB (0.9 % apart, the Caffeine entries). A first walk that skipped Symbols (fresh objects, not interned) was 14 % short; that is why it matters.

| node | sample | real / old weight | note |
|---|---|---|---|
| Leaf | 3,964 | 0.508 | old weigher over-weighs 2.0x; per-leaf real 44 to 4,500 B per datom |
| Branch (all level 1, 5-21 diff-buf slots each) | 36-76 | 0.53 | diff-buf slots are `{:present [datoms] :absent [datoms]}` maps |
| whole cache | 16,353 nodes | 0.51 (265 MB real for 512 MB weighed) | |

The old formula also built `node->map` (vector copies) on every insert.

## 2. The weigher

`node-bytes` = `1.05 * (200 + node)`, no map built, no child followed:
- Leaf: 32 + key array (16 + 4 * capacity, 8-aligned) + sum over keys.
- Datom: 40 + `:a` + `:v`. Value: Keyword and Boolean 0 (interned/shared), Long/Double/Integer 24, UUID 32, Date 24, String 40 + 8-aligned byte[] (1 B per char, 2 if any char is above 255, one early-exit scan), Symbol 32 + name string, plus ns and full-name strings when namespaced, vector 256 + 8n + elements, map 128 + 64 per entry + keys and values, other coll 128 + 64n, byte[] 16 + aligned, else 128. Constants measured with JOL (`GraphLayout`) on JDK 26 compressed oops.
- Branch: 96 + keys + n * 88 (UUID address, weak ref, array slots) + each diff-buf slot 56 + its diff value (private `NodeState.buf.slots` read through cached `Field`s).
- 200 B per entry (Caffeine) and a 1.05 margin on the whole.

Estimate / real on 5,924 sampled leaves (before the 1.05 margin): min 1.0009, p1 1.009, p10 1.03, median 1.31, p90 2.17, max 3.66. The tightest are big String leaves (exact); the loosest are Symbol leaves (3.5x) because the decoder shares symbol name strings, which a per-node estimate cannot see. Branch: min 1.05, median 1.26.

## 3. Proofs

- Fork test `datahike.test.node-cache-test/weighed-bytes-cover-real-retained-bytes-and-track-them`: 300 generated leaves per value kind (String, UTF-16 String, Symbol, Long, UUID, Date, vector), heap after four GCs with the nodes held vs before; asserts real <= weighed <= 3 x real. Measured weighed/real: string 1.06, wide-string 1.05, symbol 1.42, long 1.08, uuid 1.08, date 1.08, vector 1.87.
- Fork suite `clojure -M:test -m kaocha.runner clj-pss`: 971 tests, 5668 assertions, 1 failure: `optimistic-test/tx-report-happy-path-converges-to-conn`, the recorded timing flake (`datahike-asof-bump.md`); passes alone on the change.
- End to end on the clone, 512 MB budget, 3 rounds of full scans of eavt, aevt, avet and history eavt (9.0 M datoms):

| | nodes held | hits | misses (storage reads) | hit rate | live heap above base | weighed |
|---|---|---|---|---|---|---|
| old weigher | 16,714 | 57,306 | 64,195 | 0.472 | 254 MB | 512 MB |
| new weigher | 24,131 | 76,713 | 44,788 | 0.631 | 365 MB | 512 MB |

  Storage reads fall 30 %; 365 MB live is under the 512 MB budget (ratio 0.71). Wall time 5.0 s to 4.6 s. A workload that fits the cache (random point reads, 11k nodes) is unchanged (hit rate 0.993 both; weighed 328 to 228 MB).
- Calibration run with the new weigher: 24,344 nodes weighed 512 MB; real retained 377 MB.

## 4. Limits

- Dragon: heap use at a full cache is about 0.7 of budget on this data. A non-datom key (secondary index element, `ChunkEntry`) falls to the generic 128 B per value; unmeasured here. Strings: Latin-1 vs UTF-16 is exact; compact-string off or another JDK header layout is not covered. Shared symbol/string objects only make the estimate looser.
- One store, one scan mix, JDK 26. The 1.05 margin covers the 0.9 % gap and per-entry Caffeine overhead; `cache-entry-bytes` 200 against about 150 measured.
- Seon side: the deployed 512 MB dial now holds 1.45x the nodes at 1.4x the heap (254 to 365 MB); lower `:seon.config.db/store-cache-bytes` if the memory floor should stay where exp-a measured it.

## 5. Correction: the test measurement, not the weigher (96000df8)

Root's run on `771a0c4b` + `b19fa3c7` failed `:wide-string` 4.6 % under (real 12,860,232 vs weighed 12,290,245). Cause: the test measured real as live heap after the nodes minus live heap before building them, and in a full-suite JVM that delta picked up about 1.2 MB of other tests' garbage and caches settling. Isolated, the same measurement repeated 6 times gave real 11,651,664 to 11,675,480 for wide strings: weighed/real 1.053 to 1.055 every time (strings 1.058, symbols 1.425), so there is no under-count in `string-bytes` (UTF-16 strings, hash field and byte[] header are exact; the 1.05 margin is the only slack and was never consumed).

Fix: the test now measures exactly the held nodes, live heap with them held minus live heap right after releasing them (median of 5), so unrelated garbage cancels. Verified: the test 11 times in one JVM (10 test-vars runs plus the namespace run) with no failure, and the full suite on `771a0c4b` + the change: 973 tests, 6471 assertions, 0 failures. The weigher and its 1.05 margin are unchanged; the margin stays because the worst measured case across repeats is 1.053 for wide strings, which is the cost model plus margin, not a shortfall.
