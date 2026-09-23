---
type: landing
status: closed without a source change (the fix is 03bd7cfc9); residual filed
lane: loaded-follows-adoption (README §4 1.2b; realities §2 row 13; M9 class)
---

# Loaded program follows adoption

## Finding

The blocker note (created 15:24 -0600) predates 03bd7cfc9 (M9, 19:33 -0600),
which already does what it asks: `loaded-source` (`src/seon/sci/eval.clj:862`)
reads the adopting cluster's live `:seon.source/commit-id` through
`::loaded-connection` (boot passes it, `src/seon/cluster/boot.clj:79`; forks
inherit it), and `loaded-program-current?` (`:2514`) makes a moved record stale
for `acquired-database?`. Cost: O(1) per acquisition — one attribute-revision
compare when the record is unmoved, one pull plus one `commit-as-db` when it moved.
No program scan. The adopted commit is exact: adoption records only after reload,
verification and arming succeed, and unchanged namespaces were loaded by induction;
namespaces the JVM never requires (test helpers) bind `:unavailable`, not refused.
No per-namespace loaded identity is needed.

The live sightings came from a different defect: adoption writes rows before its
record (0.5–23 s gaps measured on default), filed as
`docs/seon/issues/development-adoption-writes-program-rows-seconds-before-its-record.md`
with the owner-side reorder (cluster.clj, held by save-gate). No eval.clj change
and no new regression: the existing
`seon.sci.branch-execution-test/an-adoption-record-moves-the-loaded-program-every-acquisition-compares-against`
asserts this acceptance (record moves → loaded names it, adopted host-bound row
compiled, 0 refused, isolated branch at the head agrees); a copy would be a
second regression for one behavior class.

## Evidence (default pid 90963)

| operation | wall | result |
|---|---|---|
| overridden-set probe (`loaded-program` of the live record vs live rows, `function-digests` both) | 49 ms | 0 of 4763 rows differ; loaded 6ab35809 |
| `bin/test-check … --test seon.db-test/a-merge-shares-the-write-fence-and-records-immutable-lineage` | 25.0 s | run 1cffaddc318a, pass 6, no "no reuse" line |
| `bin/test-check … --ns seon.render.transcript-test` | 126.8 s | ran (not check-unavailable); 2 reds + 1 bound overrun in the test's own assertions, 5 pending, 1 long excluded — the transcript lane's |
| `bin/test-check … --test seon.sci.branch-execution-test/an-adoption-record-moves-…` | 24.9 s | run a1835d5827a6, pass 12 |
| isolated acquisition on a fresh `cluster-ctx*` (no connection, `::loaded-connection` = default's) after another lane's adoption | ctx 10 ms, acquire 52 ms, unchanged `acquired-database?` 0.07 ms | loaded = record = published 6ab35966; 0 interpreted; 0 refused |
| history query, rows-to-record gaps | 21 ms | 4331, 11122, 548, 2516, 9006, 518, 23225, 11427, 1572, 1445, 1591 ms |

Over one second: the three test checks. A one-test in-process check at ~25 s is the
open defect `docs/seon/issues/a-one-test-in-process-test-check-takes-twenty-to-fifty-seconds.md`
(not this lane's work); the transcript namespace's 126.8 s is that plus its own
12.7 s member and a 66.7 s long member excluded by bound. Parent-vs-self hot-path
timing: not applicable, no source changed.

## Changed paths

- docs/seon/issues/development-adoption-leaves-the-loaded-program-at-the-boot-commit.md (closed)
- docs/seon/issues/development-adoption-writes-program-rows-seconds-before-its-record.md (new)
- this note

Net src 0, test 0. RESET NEEDED: no.
