# Projection entries in seon.cache: duplicates by content (2026-10-14)

Measurement only. No source was edited. Default cluster, MCP `eval_clj` in JVM mode, root agent, read-only.

## Source read

- `src/seon/db.clj` (`content-projection`, `speculative-projection`, `advanced-key`, `speculative-report-projection`,
  `hold-committed-projection!`, `alias!`, `resolved`, `nearest-base`, `projection-bytes`).
  - Canonical entries: `[::declaration-content builder (declaration-content-key db)]` in `::projection`, or in
    `::value-projection` when a speculative value derived it first.
  - Advanced entries: `[::advanced builder (projection-cache-key before) written-datoms]` in `::value-projection`.
  - Aliases (`::projection-alias`) hold only `[kind key]` and weigh 0. They are not counted below.
- `declaration-content-key` keys each declaration attribute by its datoms **and their `:tx`**, so the same declarations
  written in another transaction yield a different key (inference from the code, not yet measured).
- `src/seon/cache.clj`: `retained` enumerates completed entries of one kind (`[content-key value]`); `status` gives per-kind
  counters. `projection-bytes` = 2850 bytes times forms.
- `schema/projection` has no digest field of its own (only `:seon.schema.projection/forms`, registry, compiled, etc.), so
  content identity was computed as `hash` of the whole projection and then confirmed by `=` (see below).

## Forms run (trimmed)

Form 1, enumerate and hash (`retained` for both kinds, `(pbytes v)`, `(hash v)`): 44 ms enumerate, 13 + 22 entries.
The first attempt failed on my own reader error (`::seon.db/...` alias not resolvable in SCI); fixed.

Form 2, equality clustering: 36 entries, 27 equal-content classes, 5 classes with duplicates, 9 extra entries,
96,312,900 bytes in extra entries. Probe run took 21 ms.

Form 3, one probe error and an accidental 13 GB allocation: a `tree-seq` walk over every projection was added to test
for function values. This is a bug in my probe, not in the system (it took 3.5 s and triggered the `:slow` finding on
the reply). That form was discarded; no result from it is used.

Form 4 (final snapshot, same probe as Form 2 plus builder and head checks, cache status):

```
:entries 37           :builders-distinct 1          :builders-equal-in-dup-classes true
:dup-ck-heads {:declaration-content 11, :advanced 3}
:projection       {:entries 13, :entries-in-dup-classes 9, :bytes 139045800, :bytes-in-dup-classes-extra 53471700}
:value-projection {:entries 24, :entries-in-dup-classes 5,  :bytes 257115600, :bytes-in-dup-classes-extra 32130900}
:status {:max 512000000, :weighted 510537996}   ; ~99.7% of the byte budget
:kinds  {:seon.db/projection       {:hits 10 :misses 16 :evictions 2 :bytes 149742584 :hit-percent 38 :byte-percent 29}
         :seon.db/value-projection {:hits 2  :misses 30 :evictions 5 :bytes 267832300 :hit-percent 6  :byte-percent 52}}
```

## Numbers

Snapshots move between runs (the cache is live: 36, 37 entries during the probes), so the ranges below are the observed
span.

| | `::projection` | `::value-projection` |
|---|---|---|
| entries | 13 | 22 to 24 |
| distinct contents (by `=`) | about 8 | about 19 |
| entries in duplicate classes | 9 | 5 |
| estimated bytes held | 139 MB | 257 MB |
| estimated bytes held by duplicate extras | about 53 MB | about 32 MB |

- All kinds together: 36 to 37 entries, 27 distinct contents, 9 duplicate extras, about 96 MB of estimated bytes
  (about 25 percent of 385 MB held by the two kinds). The cache weighs 510 MB of its 512 MB budget.
- Hash-only grouping (Form 1) gave 35 distinct content keys for 36 entries. Equality (Form 2) gave 27 classes. The gap
  is the hash of a projection that changes across runs. Both views agree there are duplicates; the equality count is the
  reliable one.
- Duplicate shapes: 11 of 14 duplicate-class members are canonical (`::declaration-content` keys), 3 are advanced keys.
  Within `::value-projection`, 3 advanced and 2 canonical members are duplicates. Cross-kind duplicates: one content in
  both kinds at the snapshot in Form 1.
- Builder: exactly 1 distinct loaded builder across all entries, and every duplicate class shares it. A changed loaded
  program is not multiplying entries (no builder-digest fan-out observed).
- Key shape of the duplicates: canonical content keys whose projections are equal. Their content keys differ, since
  distinct entries have distinct `(hash content-key)`. The likely cause is the `:tx` in each declaration datom in
  `declaration-content-key` (the same declarations in another transaction give a new key but the same forms). This is an
  inference from the code; it needs a check that compares two such keys' datoms directly, which was not run.
- Forms per projection: 3752 to 3761 for every entry, so one projection of the same program costs about 10.7 MB
  estimated. Equal forms across entries mean each duplicate is a full copy of that program's projection.

## Limits

- Bytes are estimates from `projection-bytes` (2850 per form), not retained sizes. The true heap cost may differ.
- Equality is value equality of the whole projection map; it was not checked for identical objects with different
  identity. A projection holding a function object would be split by `hash` but matched by `=` only if it is the same
  object.
- Counts moved between runs; one snapshot (Form 4) is the reference, the others are its range.
- Not measured: whether the 3 advanced duplicates share their basis, and how many aliases point at each duplicate.
- A check that two `:tx`-differing declaration keys hold the same datoms apart from `:tx` was not run (it would need
  datom-level comparison; out of scope for a no-edit measurement).

## Answer to the question

1. Enumeration is possible with the existing `seon.cache/retained` and the private `#'seon.db/projection-bytes`. No code
   edit was needed.
2. Entries are 13 + 22 to 24, distinct contents about 8 and 19 (by `=`), duplicates hold about 96 MB estimated.
3. `(seon.cache/status)` shows 510 MB of 512 MB weighed; the projection kinds have hit rates of 38 and 6 percent.
4. Builder: 1 distinct builder across all entries, so no builder multiplication.
