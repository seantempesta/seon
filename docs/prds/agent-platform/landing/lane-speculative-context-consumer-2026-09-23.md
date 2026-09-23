---
type: evidence
status: commit 1 landed (projection memo on Datahike's speculative revision context, value-tier leak, base derivation); commit 2 (arity report snapshot) below
---
# Lane speculative-context-consumer (schedule #24q consumer, 24ac item 7), 2026-09-23

Owner paths: `src/seon/db.clj`, `src/seon/render.clj`, `test/seon/db_test.clj`,
`test/seon/schema/projection_writer_test.clj`, `test/seon/render_cache_test.clj`, this note.

## Commit 1: the projection memo reads Datahike's revision context

Seam: `datahike.db/speculative-cache-context`
(`reference-code/datahike/src/datahike/db.cljc:444`, fork `0c01b5fe`). A `with` value and a
transaction function's argument keep their basis's connection, generation and every untouched
attribute revision; each written attribute gets a fresh uuid; `:committed? false`, no commit id.

`seon.db/carried-projection`:

- **Committed** value: unchanged (revision key, then `[::commit id]`, then content).
- **Speculative** value (`:datahike.cache/connection-id` present, not committed): its revision
  key equals its basis's exactly when it wrote no declaration attribute, so it *reads* the
  durable tier (`cache/has?` then `lookup-or-miss`) and never inserts there. Otherwise its key
  names this value's declaration content alone and memoizes it in the bounded value tier.
- **As-of** view of a committed origin: value tier keyed `[::as-of commit-id time-point]`.
- **Detached** value (no context): the content key.

Value-tier leak (audit `docs/research/agent-platform/cache-invalidation-audit-2026-09-23.md`
item 7): the weak `ValueKey` equality died with its referent, so core.cache's LRU could never
evict the key a hit re-inserted. The tier is now keyed by plain data (the speculative revision
key, the as-of point); `ValueKey`, `value-key` and the public `projection-value-key?` are
deleted (only caller was `value-key`'s contract). Measured on the parent: 47 entries against a
bound of 4, and an evicted projection never collected.

Content key: its armed output contract validated every datom (148 ms per key on default, audit
item 7). It is now `[:vector [:tuple :qualified-keyword [:fn clojure.core/vector?] [:fn clojure.core/vector?]]]`,
O(attributes). A new named predicate (`seon.db/declaration-content-key?`) was tried first and
refused at adoption: `seon.schema/canonical-definition` requires the predicate to be an
admitted callable of the *current* projection, so a predicate and its first use cannot land in
one publication (finding 3 below).

Base derivation (orchestrator, from #24r `1625fb9bc`): a content miss calls
`schema/load-projection database (nearest-base database)`. `nearest-base` picks the realized
durable revision-key entry sharing the most declaration-attribute revisions with the value's
context: the basis for a speculative value, the previous commit for a committed one; `{}` when
none is derived. Known limit: `docs/seon/issues/projection-replacement-generations-retain-replaced-compiled-schemas.md`
(each replacement generation retains ~190 KB until a whole build) is now reachable from the
committed-declaration path, as that issue predicted.

Readers reviewed:

- `index-evidence-current` (db.clj): a speculative value of the read's connection and
  generation now takes the exact index check against its own history (sound: its uncommitted
  datoms sit above the read's basis). It never counts as committed evidence:
  `dependency-revision` gives a value without committed identity no revision. Docstring only.
- `render/branch-scope` (render.clj): a speculative custody value now derives in its basis's
  branch cache instead of `{}`; every entry there is validated by read evidence, which a
  speculative value never proves (`same-committed-database?` false). Regression assertions in
  `render_cache_test`: a `with` value of the parent's head has the parent's scope, of the
  fork's head the fork's scope. Docstring only in render.clj.
- Stale comments (`core.cljc:136` clears the context) corrected in db.clj.

Retired assumptions fixed: two tests asserted `(nil? (:cache-context speculative))`; they now
assert no committed identity and `:committed? false`. The `load-projection` redefs in
`db_test`/`projection_writer_test` took one arity; since `1625fb9bc` the 1-arity calls the
2-arity through the Var, so they threw `ArityException` (red on HEAD, both files). They now
count the 2-arity.

Regressions (`test/seon/schema/projection_writer_test.clj`):

- `a-speculative-value-reuses-its-basis-projection-unless-it-writes-a-declaration`: a `with`
  value and a `:db.fn/call` argument after an unrelated write are `identical?` to the head's
  projection with zero content keys; a declaration write misses once and derives by
  replacement from the head's projection (`identical?` base).
- `the-value-tier-honours-its-bound-and-releases-evicted-projections`: five speculative
  declaration values; tier size <= 4; the first projection's weak reference clears after GC.

## Proof (scratch root `tmp/scc-root`, source `tmp/scc-src` = `git archive HEAD` + my files)

Datahike on this root is the fork `0c01b5fe` (loaded from `reference-code/datahike`). The
snapshot's `.clj-kondo/.cache` is a copy re-linted from the snapshot: a linked cache carried
the main tree's uncommitted `src/seon/test/cache.clj` and refused adoption (finding 4).
Parent = HEAD's `db.clj` loaded into the same JVM with `load-file`; mine likewise; both
therefore unarmed for `seon.db`, everything else armed.

Test requests (`seon.test/run :named`, 10 members: projection_writer_test, render_cache_test,
`db-test/an-in-transaction-declaration-derives-its-population-once`,
`db-test/equal-declaration-content-shares-one-projection-across-values`):

| tree | run | result |
|---|---|---|
| parent `db.clj` | `eb653c648667` | 76 pass / 10 fail: speculative regression fails :144 (content keys read), :150, :152 (no base); value-tier regression :178 `(<= 47 4)`, :186 not collected; five members over 5 s (6.6-21.2 s) |
| mine | `9cbabfaf118b` | 80 pass / 0 fail, 40,211 ms |
| mine, armed after restart (previous test text) | `d12c7cd9fa85` | 81 pass; two members over 5 s (as-of 5.3 s, pre-existing: parent 5.6-5.8 s; value tier 5.7 s, fixed by writing declaration datoms directly instead of `turn/row-tx`, ~400 ms each) |

Hot path, same JVM, alternating loads (median ms; content-key computations in brackets):

| probe | parent | mine |
|---|---|---|
| lookup: `with` unrelated write + `carried-projection` (n=30) | 44.7 / 22.8 [30] | 3.7 / 2.5 [0] |
| config apply, new cluster row (n=8, then n=16) | 1,073 / 1,150; 1,547 / 1,452 [1 per op] | 972 / 1,129; 1,215 / 1,636 [0] |
| fixture seed `seed-cluster!` (n=8, then n=16) | 1,084 / 1,020; 1,705 / 1,739 [1 per op] | 1,149 / 1,237; 1,391 / 1,703 [0] |
| declaration transact (`row-tx`, n=8) | 1,831 / 1,877 [15] | 891 / 742 [14] |

The config-apply and seed rows drift upward across the session in both trees (same op, later
run slower); pairwise order alternates and no tree is consistently slower. Earlier variant
probe (unarmed copies, n=10): config apply 1,290 -> 1,232, seed 1,467 -> 1,446. Armed
`config/apply!` (real `seon.db`): 2.0 s, 0 content keys.

Direct: `(carried-projection with-value)` 90.6 ms parent (content key, unarmed store of the
full program) -> 0.092 ms mine; `identical?` to the head's projection. Base derivation of a
speculative declaration value: 46-60 ms (full build 322 ms per #24r).

Contracts: `seon.contracts-compile-test/check` over the five files against the snapshot's
packaged projection: no findings (1,370 ms).

## TIMINGS (over 1 s)

| operation | wall | justification |
|---|---|---|
| scratch first boot `start scc` | 147.1 s (ready 106.6 s) | **defect >10 s**: from-zero boot, class `docs/seon/issues/from-zero-boot-takes-minutes.md`; dependency-class cache miss (`:no-matching-cache`) |
| resume `start scc` | 132.6 s (ready 92.7 s); 51.8 s (ready 14.6 s) | **defect >10 s**, same class; cold class compile (cache miss) |
| adoption `init --dev --changed` x3 | 3.9 s (refused), 48.9 s (refused), 37.5 s | **defect >10 s**: `full-source-refresh!` 21.8 s + `fn/index!` 21.2 s; adoption cost class |
| `seon.test/run` 10 members | 40-80 s | **defect >10 s**: ~4 s per member, fixture acquisition + armed writer (`turn/row-tx` ~400 ms per declaration); parent 80 s |
| config apply / fixture seed | 1.0-1.7 s | writer-side reconcile queries and owned-value validation over the whole config row (sampled `write-owned-values-error` db.clj:3376/3408, `seon.db/q` under `reconcile/plan-transaction-data`); unchanged by this lane; **needs its own issue** |
| declaration transact | 0.74-1.9 s | `turn/row-tx` analysis + validation; mine halves it |
| contracts check | 1.37 s | one packaged-projection build (whole population) |
| probe scripts | 27-65 s | sum of the rows above |

## Findings outside my paths

1. `seon.test/run` panics the cluster on a test body's `ArityException` (the scratch cluster
   refused every later request, including `status`, until restart) instead of recording a red.
2. `seon.test.runner/program-digest` builds the projection of `(as-of db seal-basis)`: after an
   adoption that deletes a predicate Var used in a contract, every request refuses
   ("Predicate seon.db/projection-value-key? has no admitted callable") until the seal moves.
3. A new named predicate cannot be used in a contract in the same publication
   (`canonical-definition` output check against the current projection).
4. Linking `.clj-kondo/.cache` into an archive snapshot imports the main tree's uncommitted
   analysis and refuses adoption (`Unresolved var: cache/source-inputs`).
5. `src/seon/sci/eval.clj` in the main tree was unreadable (mismatched brackets at :982-1109)
   during this lane: another lane's in-progress edit, not touched.
6. `seon.call-preparation/snapshot` still keys on committed identity; with the fork, speculative
   values carry revisions, so `report-snapshot`'s before/after workaround could become the
   memo's own revision tier (call_preparation.clj owner).

Item 9 of the audit (committed tier by commit id): already present — a revision miss on a new
branch tries `[::commit commit-id]`, which names one value in every connection
(`equal-declaration-content-shares-one-projection-across-values`, green).

RESET NEEDED: no. Default was never touched.
