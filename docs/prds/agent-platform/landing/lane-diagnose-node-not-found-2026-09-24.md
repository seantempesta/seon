---
type: landing
status: diagnosed
lane: diagnose-node-not-found
created: 2026-09-24
issue: docs/seon/issues/a-committed-database-holds-its-fused-roots-only-softly.md
---

# Lane diagnose-node-not-found: landing (2026-09-24)

## Broken

- **Default's live database values are exposed right now.** The live `@conn` (and every
  flushed in-memory database value) keeps each fused index root in a `SoftReference`
  under an address that was never written. Under heap pressure, once the LRU also evicts
  the root, reads of that value fail with `Node not found in storage.` Only a new
  acquisition from the branch head (`stored->db`, which pins the root strongly) recovers.
  Issue: `docs/seon/issues/a-committed-database-holds-its-fused-roots-only-softly.md`.
- **No on-disk damage today.** All 23 roster heads read fully: every child of every fused
  root exists (19,556 `k/exists?` checks, 685 ms, all roots at level 1, so the check is
  complete).
- My reproduction logged one extra `Node not found` line in default's `seon.log` at
  2026-09-24T21:38:05Z (address `6ab597f5-8268…`). That was the probe, not an incident.

## Occurrences

| Time (Z) | Address (squuid time) | Running | Now on disk |
|---|---|---|---|
| 09-23 05:15:31 ×3 | 6ab35f8e-… (05:11:42) | turn-test / namespace-agent-loop tests | store nuked 05:36 |
| 09-23 05:29:56, 05:35:42 | 6ab361f1-9fef (05:21:53) | turn-backstop-test | nuked; existing issue: missing children |
| 09-23 test reports | 6ab36206, 6ab376ef, 6ab377b7 | `seon.db/transact-call` in tests | nuked |
| 09-24 18:07:30 | 6ab56616-49ae (18:04:06) | web-test / sci documentation-test, 1 min before pid 70372 heap OOM | absent. It is the fused `:avet` root of commit `6ab56616-12f0…` (cluster-web-test lineage) |
| 09-24 21:14:14 | 6ab59219-bbd0 (21:11:53) | turn-test generated-phase-failures, agent-test parallel turns | absent; no roster chain match |
| 09-24 21:19:42 ×5 | 6ab593d5-3eb4 (21:19:17) | loop-proof-test `running-fixture` turn-step (core fault panic) | absent; no roster chain match |

The 09-23 cases are covered by
`docs/seon/issues/published-head-references-never-written-index-nodes.md`: a swallowed
`Error` left commits with missing children on disk. That issue now points to the new one.

## Cause (confirmed for 18:07:30; the 21:1x pair matches the same signature)

1. With `:fuse-index-roots?`, `commit!` excludes the roots from the writes
   (`reference-code/datahike/src/datahike/writing.cljc` `fused-root-addresses`, and the
   `remove` at ~493).
2. The live flushed set keeps `_address` set to the root's address, with `_root` held in a
   `SoftReference` (store `:ref-type` SOFT, `:store-cache-size` 1000; observed live).
3. `PersistentSortedSet.root()` falls back to `IStorage.restore(_address)` when the
   reference is empty (javap 0.4.137).
4. `CachedStorage.restore` checks the LRU, then konserve (`persistent_set.cljc:432-444`),
   which leads to the miss.

Reproduced read-only (`tmp/diagnose-node-not-found/soft-root-clear.clj`):
- On a copy of default's live `:avet` set, an empty `SoftReference` plus a fresh storage
  raised `Node not found in storage.` for the live head's own root.
- With the live LRU, the same read passed.

Ruled out:
- **Collection (Datahike GC, konserve, `registry/retire-branch!`, maintenance).** No
  collection is logged, it is scheduled only on Sundays at 03:00 UTC, and tests collect
  their own directories. Nothing deleted these addresses, because they were never written.
- **Swallowed `Error`.** The superv.async fork has been pinned since `20d317e19`, and
  `-XX:+ExitOnOutOfMemoryError` is set. There was no writer shutdown or Error near the
  21:1x reads, and 18:07:30 comes before the first OOM.
- **Use-after-release of a materialized database.** `release-materialized-db` deletes no
  keys.

## Owner and smallest fix

- **Owner:** the Datahike fork, `reference-code/datahike/src/datahike/writing.cljc:142-160`.
- **Fix:** pin each flushed set's root with `di/-seed-root!`
  (`persistent_set.cljc:232-236`). That is about 6 lines, and it covers both `commit!` and
  `force-branch!`.
- **Regression:** in the fork's tests, as written in the issue.

## Probes and timings (read-only, JVM mode, default)

| Probe | Result | Time |
|---|---|---|
| `k/exists?` on the 3 addresses, plus roster read | all false; 23 branches | 8 ms |
| `head-check.clj` | 0 damaged heads | 685 ms |
| `root-match.clj` (3 branches × 400 commits) | no hits | 4.9 s |
| `root-match-all.clj` (20 branches, back to 18:00Z) | 18:07 address = fused avet root on 4 lineages | 8.8 s |
| `soft-root-clear.clj` | reproduced | under 20 ms |

The two root-match walks exceed one second. They are one-off diagnostics proportional to
the ~1,400 commit records they deserialize, not product paths.

## Heavy probe left for root (quiet window)

Attribute `6ab59219…` and `6ab593d5…`: scan the retired branch head and commit keys (whole
`k/keys`) for a record whose `:merkle-roots` contain them.

## Changes

- New issue: `docs/seon/issues/a-committed-database-holds-its-fused-roots-only-softly.md`.
- Cross-reference paragraph added to
  `docs/seon/issues/published-head-references-never-written-index-nodes.md`.
- This note, plus probe forms under `tmp/diagnose-node-not-found/` (the temporary PSS jar
  extraction was deleted).
- No source or test edits. No RESET NEEDED.
