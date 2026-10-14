# What fills seon.cache to ~508 MB

Scope: default JVM (read-only, JVM mode) and the check run 1ed06ad6cf8d (candidate c47077768f, lane db-caches, fbf611da5). Gathering only; no source edits.

## Measured on default (VERIFIED)

`(seon.cache/status)` read in JVM mode on cluster default: max 512,000,000 bytes, weighted 490,791,435 bytes (about 96%), 48 kinds. Per-kind entries, bytes, hit/miss/eviction (evictions 0 in every top kind, so the budget has not yet forced eviction):

| kind | entries | bytes | share | hit % | misses | load ms |
|---|---|---|---|---|---|---|
| seon.schema.edn/packaged-population | 62 | 90,247,360 | 18% | 99 | 64 | 2316 |
| seon.test.runner/reach-attribute | 68 | 89,717,792 | 18% | 81 | 80 | 1935 |
| seon.sci.eval/program-context | 35 | 85,181,031 | 17% | 75 | 35 | 7793 |
| seon.schema/projection | 5 | 53,521,430 | 10% | 95 | 5 | 2212 |
| seon.schema/declaration-projection | 5 | 38,585,330 | 7% | 99 | 5 | 1376 |

Next kinds are all under 7%: contracts-compile-test/schema-resources 32.9 MB (3 entries), reach-index-by-revision 22.0 MB (15), contracts-compile-test/read-source 13.4 MB (10), task/file-declarations 9.8 MB (419), cluster/file-derivation-key 6.6 MB (2313), reach-node 6.0 MB (3867), program/source-resolver-names 5.7 MB (7491).

## Producers and keys (VERIFIED by reading source)

1. `seon.schema.edn/packaged-population` (src/seon/schema/edn.clj, `packaged-population`). Key: the digest of the packaged resource population, which is itself looked up under `::packaged-digest` keyed by the resource URL string. Weight: 376 bytes per declared form. Measured: 62 entries, each with 3754 declared forms.
2. `seon.test.runner/reach-attribute` (src/seon/test/runner.clj, around line 904). Key: `[attribute shape revision]`. Weigher and derivation read but not measured.
3. `seon.sci.eval/program-context` (src/seon/sci/eval.clj, lookups near lines 3955 and 4007). Key: `base-key` for cluster bases, `program-key` for overlays. Weight: `context-weight` / `overlay-weight`, not read in detail. Measured: 34 entries, about 2.5 MB each.
4. `seon.schema/projection` (src/seon/schema.clj, around line 2429). Key: `[forms function-contracts options]`. Weight: 2850 bytes per form plus per-contract bytes. Measured: 6 entries of 3753 to 3760 forms, about 8.9 MB each.
5. `seon.schema/declaration-projection` (src/seon/schema.clj, around line 1291). Key: `[forms predicates]`. Weight: 2550 bytes per form. Measured: 6 entries, one of 112 forms and the rest about 3754 forms.

## Duplicates (VERIFIED for the count; mechanism PARTIAL)

Among the 62 packaged-population entries, the keys are distinct `file:` URLs of archives under `data/source/<sha>/resources/seon/schemas/`. There are 62 distinct map values by hash, but only 5 distinct `:seon.schema.edn/declared-forms` vectors. So roughly 57 entries repeat content the cache already holds under another key. The code comment says equal resources under different URLs share one instance, which the measurement contradicts. The mechanism (the URL-keyed `::packaged-digest` and what the digest covers) is partly read, not fully traced.

Projections: 6 projection entries and 6 declaration-projection entries of about 3754 forms each. Whether they are content duplicates was not checked.

## Weigher calibration

- packaged-population: weigh = 376 × forms + 256 entry overhead gives about 1.41 MB per entry. Measured about 1.46 MB per entry (90,247,360 / 62). Consistent (VERIFIED by arithmetic), real retained size UNVERIFIED.
- projection: weigh 2850 × 3754 ≈ 10.7 MB per entry against measured about 8.9 MB. Overestimates by about 20% (VERIFIED by arithmetic), real retained size UNVERIFIED.
- declaration-projection: weigh 2550 × 3754 ≈ 9.6 MB against measured about 7.7 MB (VERIFIED by arithmetic), real retained size UNVERIFIED.
- program-context, reach-attribute: weighers not read; UNVERIFIED.
- clj-memory-meter is only on the `:measure` alias (deps.edn:197-199), not on the default classpath, so no retained-size measurement was taken. UNMEASURED.

## Check JVM run 1ed06ad6cf8d (UNVERIFIED for cache contents)

- `data/clusters/default/logs/operator-check-20261014T140820.238.edn` records the run's test summary (122 executed, 818 pass, 9 fail, 5 red) and no cache data.
- `bin/seon` contains no cache dump on check. The check JVM's cache cannot be inspected after the run. The 508 MB figure is not reproduced here.
- The red tests in that run are about projection cache identity and misses (`seon.schema.projection-writer-test` lines 42 to 191; `seon.db-test` line 2873), which fits projections being rebuilt rather than shared. That is a hypothesis, not measured.

## Open items

- Why 62 population entries exist: trace `::packaged-digest` and what the digest covers across `data/source/<sha>` archives.
- Retained size of a projection and a program-context entry, measured with clj-memory-meter in the `:measure` alias.
- Whether check JVMs leave one population per archive sha, which would multiply the 1.4 MB entry per run.
