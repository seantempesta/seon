---
type: research
status: complete
tags: [database, research, decision, capability]
---

# Temporal KNN preflight seam

## Decision

Reject an unusable KNN coordinate at the beginning of the existing bounded
provider job, before `query-vec`. Do not validate on the selector thread, do not
open a historical database before the provider, and do not add another executor
or public coordinate.

The dependency-native seam is one Datahike operation over immutable stored
commit records:

```clojure
(d/attached-commit-info head-db requested-commit-id)
;; => {:datahike.commit/id requested-commit-id
;;     :datahike.commit/max-t max-t}
;; or nil when the commit is absent or is not reachable from head-db
```

`head-db` is one already-owned committed value from the live connection. The
operation captures no new database owner. It compares the head commit in
memory, walks raw `:datahike/parents` only when necessary, and returns a bounded
ordinary map. It never calls `stored->db`, never restores a secondary index, and
never returns the raw stored record.

Extend the existing `commit-as-db` options with `:attached? true`, backed by the
same private traversal. Query, pull, paging, replay, and final KNN
materialization can then ask Datahike to load a commit only when it is reachable
from the supplied committed value. This deletes Seon's Konserve traversal
instead of leaving two ancestry implementations.

The provider job retains only the returned ordinary facts and the commit UUID
while the embedding provider runs. The KNN phase then materializes the full
historical commit with Datahike's default secondary restoration and releases it
in the existing `finally`. Full historical KNN therefore keeps native Proximum
acceleration, while rejected work opens no HNSW owner, mmap generation, file
descriptor, or Datahike database value.

## Dependency ledger

This audit is against Seon `4549204ea6d50f9f89f2bd0478053dfe64ccc44e`,
Datahike `f0ee54c22d70a20de0279996f93aea98c6a9d1df`, and Proximum
`9846d3e79e1aee48474bc876d3d563d7137209c6`. Paths in the table are relative
to those checkouts.

| Owner | Exact source | Constraint |
|---|---|---|
| KNN admission | `src/seon/db/writer.clj:2993-3013` | A KNN request currently enters `:provider` with one reserved `:knn` position and 64 KiB of reserved vector/result capacity. This is the existing bounded semantic-search pipeline. |
| Provider phase | `src/seon/db/writer.clj:1725-1736` | `query-vec` runs immediately, then returns the existing `executor/continue-with :knn`. This is the first place that can avoid provider work without blocking the transport. |
| KNN phase | `src/seon/db/writer.clj:1738-1760` | Only this phase calls `pinned-database`; it therefore rejects an earlier `t` after provider work. Its `finally` already releases the containing materialization. |
| Exact pinning | `src/seon/db/writer.clj:531-603` | The current owner loads the requested commit, walks ancestry in Seon, validates exact `t`, derives `as-of`, and owns release. It is correct but too late for KNN and duplicates Datahike commit-graph knowledge. |
| Seon ancestry | `src/seon/db/writer.clj:1191-1220,1363-1368` | `retained-stored-commit`, `stored-ancestor?`, and `ancestor-commit?` read Datahike's private stored layout directly. Replay also uses the duplicate traversal. |
| Dispatcher classes | `src/seon/db/executor.clj:109-148,432-438,457-470` | Provider work has independent bounded admission and executes on virtual threads. Read/KNN CPU work uses fixed workers. Control and the selector are not involved in provider execution. |
| Continuation and cancellation | `src/seon/db/executor.clj:334-408,494-555` | One admitted job changes from provider to its reserved KNN class under the executor lock. Cancellation prevents the continuation, but code inside a running provider function needs an active-request check before starting a new external call. |
| Connection value | `reference-code/datahike/src/datahike/connector.cljc:38-97` | A connection wraps one atom. In the maintained streaming writer, dereference returns the current immutable committed value. Final release closes query generation, writer, secondary owners, and storage in order at lines 454-538. |
| Stored commit shape | `reference-code/datahike/src/datahike/writing.cljc:30-35,48-180` | Every immutable commit record already contains `:max-tx`, `:meta :datahike/commit-id`, `:meta :datahike/parents`, config, and detached primary/secondary roots. No new durable metadata is needed. |
| Durable publication | `reference-code/datahike/src/datahike/writing.cljc:450-495,497-515` | Parent IDs are stamped before the content-addressed record is written; pointed-to values precede the immutable commit and mutable branch head. A reachable committed record is immutable. |
| Datahike versioning | `reference-code/datahike/src/datahike/versioning.cljc:47-88,403-443` | `commit-as-db` extracts the owning store, restores a raw record with `stored->db`, and attaches exact cache identity. There is no metadata-only reachable-commit API today. |
| Materialization | `reference-code/datahike/src/datahike/writing.cljc:182-292` | `stored->db` restores secondary owners by default. `:secondary-indices? false` avoids them, but still constructs a database value and is unnecessary for the KNN rejection. |
| Proximum restoration | `reference-code/datahike/src-secondary/datahike/index/secondary/proximum.clj:97-103,137-165,330-354` | Historical restoration calls `proximum.writing/load-commit`; the bridge owns a closeable Proximum value and synchronous KNN search. This work must happen only in the KNN phase. |
| Proximum native load | `reference-code/proximum/src/proximum/writing.clj:184-230` | `load-commit` opens the exact durable vector snapshot and restores native state. It is not a metadata lookup. |
| Existing proof | `test/seon/db/writer_integration_test.clj:642-725` | The retained fixture proves two provider calls but one KNN call for a full historical commit plus an exact earlier cut. It is the shortest regression owner. |

## What the metadata operation should own

### One traversal, inside Datahike

Datahike should add one private traversal beside `commit-as-db` and expose the
small ordinary projection above. The traversal takes a committed `DB` or live
connection value as the descendant and a requested commit UUID as the possible
ancestor.

The algorithm is:

1. Read the supplied value's commit UUID and parents from memory.
2. If the requested UUID equals that commit, return its UUID and `max-tx`
   without a store read.
3. Otherwise walk the immutable parent DAG with a visited set.
4. Fetch each parent with one synchronous Konserve `get`; inspect only the raw
   record's commit UUID, parent UUIDs, and `max-tx`.
5. Return the two-field ordinary projection when the requested commit is
   reached; return nil when it is absent or unreachable.
6. Fail closed on a malformed or missing intermediate record. Never return a
   raw stored map, index root, store, connection, or database value.

The walk must support merge commits, so following only `(first parents)` is not
correct. The visited set also makes a malformed cycle bounded by the number of
records encountered. When `:commit-graph? false`, only the in-memory head can be
resolved; historical requests correctly remain unavailable.

The private traversal should also back:

```clojure
(d/commit-as-db head-db requested-commit-id {:attached? true})
```

In that form it may materialize the raw record already found by the walk rather
than reading it again. `:attached?` must reject a raw store argument because a
raw store has no selected descendant. Existing two-argument `commit-as-db` and
the `:secondary-indices?` option keep their current semantics for branch and
administrative tools that intentionally load detached or sibling commits.

This is preferable to adding Seon `commit-metadata`, `snapshot`, `lease`, or
proof-token concepts. `commit`, `parent`, `head`, `max-tx`, and attachment are
already Datahike terms.

### Why the stored transaction bound is sufficient for KNN

KNN supports the containing commit only. It does not support an `AsOfDB` at an
earlier transaction. Once reachability is proven:

- `requested-t == :datahike.commit/max-t` is the supported full commit;
- `requested-t < :datahike.commit/max-t` is the existing explicit unsupported
  temporal KNN error; and
- `requested-t > :datahike.commit/max-t` is stale.

No `:db/txInstant` scan is necessary merely to reject an operation that cannot
run at any earlier `t`. Authority-produced coordinates are exact already. A
forged non-transaction `t` below `max-tx` may therefore receive the same
protocol-error discriminator as an exact earlier `t`; it still cannot read any
data or invoke a provider. Paying for a primary-only database materialization
solely to distinguish those two rejected inputs would defeat the preflight.

If preserving the stale discriminator for a forged in-range gap becomes a hard
wire requirement, Datahike would need a small durable transaction-membership
summary or a primary-only index lookup. Neither is justified by the current
protocol, whose KNN contract has no temporal success case.

## Seon integration

### Provider entry

`execute-knn-provider!` should receive the writer runtime as well as the
dependency functions. Its first operation is a private coordinate check:

1. Recheck the captured connection, attachment, database ID, and generation
   scope exactly as `pinned-database` does.
2. Capture `head-db` once from the connection.
3. Call `d/attached-commit-info` with that immutable value and the requested
   commit UUID.
4. Map nil, wrong database, missing, sibling, or discarded commits to the
   existing stale-coordinate response.
5. Compare requested `t` with `max-tx` as above.
6. Retain only the ordinary commit-info map and the captured head commit UUID
   in the internal executor request. Do not retain `head-db`.
7. Recheck the writer's existing active-request cancellation bit before
   invoking `query-vec`.
8. Invoke the provider and return the existing `:knn` continuation.

No field crosses the database protocol. The provider result and the existing
request ID remain the only job data needed by the next phase.

The active-request check closes the useful cancellation window: a request
canceled while its old-commit metadata is being read does not then start a
remote provider call. Cancellation racing after that check has the same
semantics as cancellation racing the current provider call: the external call
may finish, but the executor refuses the KNN continuation and does not publish
hits.

### KNN entry and time-of-check/time-of-use

The metadata check is an optimization, not a lifetime owner. At KNN entry:

- recheck connection membership, attachment, database ID, and generation;
- compare the live head commit UUID with the head UUID observed by preflight;
- if the head changed, rerun the Datahike attached reachability check;
- if it did not change, the immutable parent graph remains the proof, but still
  treat a missing `commit-as-db` result as stale; and
- materialize the requested full commit with secondary indices enabled, run
  KNN, and release it in the existing `finally`.

An ordinary writer advance normally preserves ancestry, but rechecking on a
changed head keeps the rule correct for merges, administrative restoration, and
future branch operations. A force move requires Datahike connections to be
released and reopened; Seon's generation scope already fences that transition.
The commit record itself is immutable. Correct GC cannot remove a record still
reachable from the unchanged attached head, and a corrupt or concurrently
removed record still fails at `commit-as-db` before KNN.

The preflight result does not authorize database access after a generation
change. It only avoids repeating an ancestry walk when the exact captured head
is unchanged.

### Query, pull, paging, and replay reuse

Change `pinned-database` to use `commit-as-db {:attached? true}` instead of
loading any UUID and then calling Seon's `ancestor-commit?`. The special case
for the identical live head stays allocation-free. Exact `coordinate/at`
validation and raw materialization lifetime stay in Seon.

Replay can likewise load its watermark and cursor relative to the selected
committed descendant with `:attached? true`. Once every call site uses the
dependency owner, delete `retained-stored-commit`, `stored-ancestor?`, the
forward declaration, and `ancestor-commit?` from `writer.clj`. This removes
Seon's dependence on Datahike's private Konserve record layout and gives KNN,
ordinary reads, and replay one DAG implementation.

## Queue and capacity placement

Keep the initial job in `:provider` and keep its reserved `:knn` capacity.

This is the smallest correct placement:

- the selector remains limited to frame ownership and admission;
- lifecycle and cancellation do not wait behind a store walk;
- exact database reads do not lose fixed CPU workers to historical metadata
  I/O;
- blocking Konserve I/O runs on the provider virtual-thread executor;
- the provider class's existing global and per-database bounds also bound cold
  ancestry walks; and
- the KNN reservation still guarantees that provider work is not started when
  no bounded downstream native-search position exists.

The preflight briefly consumes a provider permit before it consumes an external
provider connection. That is intentional backpressure: otherwise many cold,
invalid historical requests could create unbounded metadata I/O ahead of the
provider limit. The head path performs no store I/O, while an old valid or
sibling commit is `O(depth)` in the current commit DAG.

Do not add a `:metadata`, `:preflight`, or second read executor class before
measurement. If cold histories demonstrably delay valid provider calls, the
next optimization is a generation-scoped bounded positive reachability cache
inside Datahike, not another Seon scheduler. Cache identity would be the
existing connection/generation plus descendant and ancestor commit UUIDs, and
final generation release would clear it. That additional state is not needed
for the first cut.

## Errors and resilience

The public behavior should remain:

| Condition | Response | Provider | Proximum |
|---|---|---:|---:|
| Full head commit | success or provider/KNN failure | once | once |
| Full reachable historical commit | success or provider/KNN failure | once | once, native historical owner |
| Exact earlier `t` in a reachable commit | existing protocol error: semantic search unavailable at an earlier transaction cut | never | never |
| Future `t` | stale coordinate | never | never |
| Missing commit UUID | stale coordinate | never | never |
| Commit retained only by a sibling branch | stale coordinate | never | never |
| Commit discarded by force move and reconnect | stale coordinate | never | never |
| Wrong database ID, branch attachment, or generation | stale coordinate | never | never |
| Cancellation while queued | existing canceled outcome | never | never |
| Cancellation during metadata walk | existing canceled outcome after bounded walk | never after the active-request recheck | never |
| Cancellation during provider | provider may finish; continuation is rejected | at most once | never |
| Release during provider | generation drain waits for the public job; no historical DB is held | at most once | never before a valid continuation |

Raw ancestry failures must fail closed. They must not be translated into
"not temporal" or permit a best-effort KNN. Provider exceptions and native KNN
exceptions continue through the existing executor completion and canonical
protocol error path.

## Rejected alternatives

### Keep the current late rejection

Correct results, but provider latency, quota, and provider queue capacity are
wasted for every known-invalid request. This is the issue being fixed.

### Resolve `pinned-database` before provider

This proves everything but restores Proximum through `stored->db`, then retains
its mmap/HNSW owner across remote latency. It increases file descriptors,
mapped memory lifetime, release latency, and historical-owner contention.

### Materialize primary-only before provider

`commit-as-db {:secondary-indices? false}` avoids Proximum, but still constructs
primary DB/index wrappers and retains or repeats more work than the raw stored
bound requires. It is useful only if exact in-range transaction membership must
be distinguished for a rejected operation.

### Validate synchronously in `start-knn-request!`

This puts Konserve I/O and an `O(depth)` graph walk on the request/selector
handoff. A cold database could delay ping, cancel, close, and unrelated session
progress. It violates the control-plane resilience goal.

### Add another process or executor

The existing provider class already supplies bounded blocking-I/O capacity and
the KNN reservation. Another owner adds queueing, shutdown, metrics, and
cancellation state without creating more useful parallelism.

### Trust `max-tx` or branch names without ancestry

Transaction IDs are only ordered within a lineage. A sibling commit can have
an equal or larger `max-tx`, and a source commit loaded through a native fork
may retain its source branch in immutable config. Reachability from the attached
head, not stored branch equality or numeric comparison, is the required proof.

### Keep the ancestry algorithm in Seon

It works today but binds Seon to `[:meta :datahike/parents]`, raw Konserve
records, and Datahike's missing-record behavior. It also prevents
`commit-as-db` from reusing the raw record found by the walk. Datahike owns the
commit graph and should expose the smallest ordinary answer.

## Shortest executable falsifier

Strengthen the existing
`semantic-search-transitions-from-provider-to-coordinate-pinned-knn` fixture in
`test/seon/db/writer_integration_test.clj:642-725` rather than creating another
harness.

The minimum assertion is:

1. Count `query-vec` and KNN calls.
2. Run one KNN at a full reachable historical commit; expect one provider and
   one KNN call.
3. Run one KNN at `::protocol/previous-coordinate` from the next transaction;
   expect the existing protocol error while both counters remain `1`.

That fails deterministically today because `query-vec` reaches `2`. It does not
need provider timing, a large vector index, or a benchmark.

Extend the same fixture or the existing branch lifecycle fixture with random
missing, sibling-only, and force-discarded commit coordinates. For each, assert
stale-coordinate and unchanged provider/KNN counters. A latch around the
metadata reader plus request cancellation proves cancellation before provider;
a latch inside `query-vec` preserves the existing cancellation-during-provider
truth.

## Implementation order

1. Add Datahike's private raw reachable-commit traversal and focused PSS/HHT
   tests for head, depth, merge parent, sibling, missing intermediate,
   `:commit-graph? false`, and malformed cycles.
2. Expose `attached-commit-info` as an ordinary-data versioning query and add
   `commit-as-db {:attached? true}` using the same traversal result.
3. Prove `attached-commit-info` creates no DB/secondary owner and that attached
   `commit-as-db` still restores and releases historical Proximum.
4. Move KNN coordinate validation to the start of the provider function, add
   the active-request cancellation recheck, and carry only commit facts/head
   UUID into the KNN continuation.
5. Revalidate on changed head, then retain the existing full materialization,
   native KNN, and `finally` release.
6. Replace `pinned-database` and replay ancestry with Datahike's attached
   materialization option; delete Seon's raw-store traversal.
7. Run the focused writer integration, branch lifecycle, executor
   cancellation, replay, and Datahike versioning/secondary gates under one
   source freeze.

## Performance and resource proof

Record these before and after; a correctness-only counter is not enough to
graduate an optimization:

- provider calls and provider wall time for 1/32/256 rejected exact earlier
  requests;
- head preflight latency and allocations, which should require zero store
  reads;
- cold and warm reachable/sibling preflight at depths 1, 16, 256, and 4,096,
  including Konserve reads and allocated bytes;
- added latency for a successful full historical KNN versus the current path;
- Proximum load calls, open owners, file descriptors, mapped bytes, and release
  time while the provider is blocked;
- provider queue age for a healthy database while another database submits
  cold invalid history, proving the existing fair per-database bound;
- cancellation during queued, metadata, provider, and KNN phases, with zero
  late KNN after metadata/provider cancellation;
- branch advance during provider and force-discard plus reconnect, proving the
  KNN recheck; and
- retained objects after completion: no DB, stored record, connection,
  Proximum owner, Future, callback, or active request.

The expected resource result is stronger than merely saving one provider call:
rejected temporal KNN owns no native secondary resources at all, while a valid
historical KNN opens them for only the native-search phase.

## Tradeoffs requiring Sean's involvement

The first cut has no consequential product choice: it preserves full-commit
KNN, rejects the already-unsupported temporal operation earlier, and removes a
duplicate dependency implementation.

Two later choices should remain measured and explicit:

- whether very deep/cold histories justify a small generation-scoped Datahike
  reachability cache; and
- whether temporal KNN should eventually become a real capability by teaching
  the secondary owner exact `t` semantics, rather than remaining an unsupported
  operation.

Neither choice should delay this preflight. The recommended seam leaves both
open without another protocol field or Seon cache.
