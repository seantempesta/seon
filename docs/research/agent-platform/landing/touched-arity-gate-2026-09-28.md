# Touched-edge arity gate — 2026-09-28

Task `0caf995ddc22`. The writer already holds a transaction report and immutable
before/after databases. Use its logical datoms to select written callers and
changed callees, then Datahike EAVT/AVET and bound Datalog queries to read only
their edges and bounds. Delete the whole-program base and all three cache kinds.

## Algorithm and contracts

`seon.db/report-arity-comparison` follows `:seon.fn/calls` backwards for changed
callees, including changed arity components. Written callers contribute their
final call-arities. Schema and call-preparation changes follow reverse schema
references to arity input references. Reads use the existing vector-aware
`attribute-holders` and `storage-q-in` owners; final verdict preparation and its
contract remain. Coverage now describes this write, not the whole program.

Cost is proportional to transaction datoms, reached schema references, affected
callers' edges and reached bounds, plus indexed seeks. It retains no program-size
base. The standalone whole-program diagnostic remains available. On the measured
live database, no function call-arity target was missing from its calls index.

`seon.cache/evict-kinds!` removes retired producers' entries using Caffeine's
existing concurrent map conditional removal, then cleans up its synchronous
view. This is one O(cache entries) retirement scan, not per-write invalidation.
Reloading the DB owner evicts the three deleted kinds. An in-flight derivation
still returned its value to its waiting caller but retained zero entries/bytes.

## Live-data evidence

Default was faulted (17 open faults), not healthy; its compiled code remained
unchanged. Scratch candidates used disposable branches and speculative reports.
The candidate outer function was unarmed; its installed helpers remained armed.
Full mismatch vectors were equal, not merely mismatch counts.

| Operation | Parent comparison / total ms | Candidate comparison / total ms | Edges before → after | Mismatches |
|---|---:|---:|---:|---:|
| Add wrong call to `seon.id/id` | 137.299 / 141.351 | 2.719 / 3.992 | 108131 → 3 | 1 |
| Change id/id bounds, breaking callers | 138.522 / 142.741 | 7.313 / 9.216 | 108130 → 112 | 112 |
| Unrelated doc write | 123.380 / 123.418 | 0.069 / 0.075 | 108130 → 0 | 0 |

At 1/10/100 added wrong calls, candidate edges were 3/12/102 and query calls
stayed at three; parent edges were 108131/108140/108230 and query calls five.
Candidate totals were 3.314/2.045/2.453 ms; parent 337.570/174.143/183.446 ms.
The first parent run was noisier; these are measurements, not a linear latency
prediction. Both returned exactly 1/10/100 mismatches.

Initial old-kind weights were 283666544 bytes (base), 175171368 (revision), zero
(commit); total cache 503495190 bytes. After the parent measurements, explicitly
retiring the three kinds and the owner's named stray health-family removed 11
entries: total 251700140 → 63814956 bytes, all four retained-kind weights zero.
Default still runs the old producer until root reloads it and can refill those
kinds. No persistent zero under old code is claimed. These are read-only gate
comparisons: zero durable commits and zero store bytes per comparison.

## Verification and landing boundary

Archive `ae2c09c1538885095404aad2972056d168d87311` plus the nine owned code,
schema and test files: lint src/script/test with cache false, 0 errors, 1659
warnings, 7.423 s. Contract compilation examined 500 files in 25.281 s and
reported only ten existing foreign script findings (docstring check-source,
check-file, format-findings, scan; markdown parse, validate, validate-file,
validate-repository-pins, format-violations, fix). Whole-repository contract
compilation is therefore not clean; this slice introduces no findings.

Drill `f92f54f4ebe7` passed: base fresh boot 232.650 s, move 59.131 s,
candidate fresh boot 196.935 s; total 612.342 s. These are still far above the
project's sub-second target; this gate cut does not claim to fix whole boot.
The drill also includes the pending transaction-report data renderer and queued
DB data-assertion patch described in the store-write landing note.

Foreign boundary: root's MCP deletion and other lanes' turn, profile, agent and
cache lookup changes are excluded. The cache file acquired foreign lookup edits
after this verified snapshot; only the eviction function belongs to this slice.
Test recording and attribute-refusal work remain separate pending slices.

Root runs focused tests centrally; none were run by this lane:

- `seon.db-test/a-report-compares-only-the-written-callers-edges`
- `seon.db-test/an-unrelated-report-reads-no-arity-edges`
- `seon.db-test/arity-components-and-callers-are-checked-in-the-final-state`
- `seon.db-test/a-first-population-is-judged-by-the-declarations-it-installs`
- `seon.cache-test/retiring-kinds-removes-only-their-entries`
- Transaction-report and data-assertion tests listed in the store-write note.

Exact forms, envelopes and cache observations are in
`tmp/astra-store-fixes-20260928/arity-*.{clj,json}`. The new work-count
regressions cover 1/10/100 inputs and unrelated writes; retained-kind regression
covers 1/10/100 entries per kind. The report shares two identity reads across
changed entities and tempids, with its own 1/10/100 regression.
