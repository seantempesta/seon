---
type: research
status: complete
tags: [database, research, decision, capability]
---

# Strict temporal coordinate seam

## Decision

Keep the existing coordinate. Do not add a temporal database handle, snapshot
ID, revision, lease, or second coordinate shape.

One portable coordinate already contains the necessary facts:

```clojure
{:seon.db.coordinate/database-id #uuid "54b5b7e7-51fb-3220-b079-81a81914d86f"
 :seon.db.coordinate/branch :db
 :seon.db.coordinate/commit-id #uuid "6a56b426-c836-5817-9f6b-20584f2e81d5"
 :seon.db.coordinate/t 536870913}

```

The commit UUID selects one immutable Datahike value. `t` selects a transaction
cut inside that value. Database ID and branch select the attached route through
which the commit is allowed to be read. The four facts together are sufficient;
commit UUID plus `t` alone is not a complete protocol identity because a store
and an attached branch still own availability, release, and authorization.

The smallest correct implementation is in Seon's existing coordinate resolver,
not a new Datahike operation. Resolve the containing raw `DB` once, prove that
its commit is reachable from the attached branch head, validate `t`, and use
`d/as-of` only when `t` is less than the containing value's maximum transaction.
The raw containing value remains the resource owner and is released after the
read. Datahike already supplies every required primitive.

This closes query, pull, pull-many, history-query, and native index-page cuts.
Do not silently claim temporal schema or temporal KNN semantics: Datahike's
`AsOfDB` delegates schema to its origin, and a secondary index may represent the
containing commit rather than the earlier `t`. Until those two capabilities are
made natively temporal, reject `schema` and KNN when `t` is below the containing
commit maximum.

## Dependency ledger

| Owner | Selected source | Exact dependency fact |
|---|---|---|
| Datahike | `reference-code/datahike` at `a53158582dd2d8ba12e8bfc0843125d246b573c6` | `commit-as-db`, `as-of`, `history`, native index access, commit parents, and materialized-value release are the selected primitives. |
| Datahike immutable values | `src/datahike/db.cljc:319-407,515-701` | Raw `DB` owns exact max transaction and cache identity. `HistoricalDB`, `AsOfDB`, and `SinceDB` are wrappers over an origin value; wrappers report the origin's max transaction. |
| Datahike temporal filtering | `src/datahike/db.cljc:142-152,567-631` | Numeric `as-of` is inclusive. A future transaction is not rejected by Datahike; it simply produces the head state. Seon must enforce the containing range. |
| Datahike versioning | `src/datahike/versioning.cljc:403-443` | `commit-as-db` loads any retained commit UUID. It preserves attached cache ownership but does not prove that the commit is reachable from the caller's branch. |
| Datahike batching | `src/datahike/writer.cljc:205-239` | A drained writer batch persists one final `commit-db` and gives that same committed value to every logical transaction callback. Several requests may therefore share a commit UUID. |
| Seon coordinate | `src/seon/db/coordinate.cljc:1-107` | The existing closed shape is database ID, branch, commit ID, and `t`; `at` already validates database and range and intentionally retains the containing commit. |
| Seon authority resolver | `src/seon/db/writer.clj:529-557` | `pinned-database` currently requires the request coordinate to equal `coordinate/resolved`, so it rejects a valid earlier `t`. |
| Seon history/branch proof | `src/seon/db/writer.clj:1168-1198,1290-1352` | Replay already has commit-ancestry traversal, exact coordinate reconstruction, and Datahike history composition. Reuse these mechanisms. |
| Protocol | `src/seon/db/protocol.cljc:221-240,464-590,768-835` | All reads already carry the same attachment and coordinate. Query and index page already carry the existing `history?` option. |
| First-party proof | `test/seon/db/coordinate_test.cljc:42-116`, `test/seon/db/writer_integration_test.clj:229-340` | Tests already prove lineage separation, containing-commit cuts, future-range rejection at construction, and retained historical commit reads. |

The Datahike paths above are relative to `reference-code/datahike`. No source
other than the selected maintained fork was used to infer behavior.

## What each existing fact means

### Head

A head coordinate is the current attached branch value and must satisfy:

```clojure
(= :seon.db.coordinate/t (max transaction of the selected commit))

```

`resolve-head` always returns that form. It is the only coordinate suitable as
an optimistic expected-head fence for a write. An earlier `t` is a read point,
not a claim that the branch head is still there.

### As-of

There is no public `as-of` handle in the remote API. A request whose `t` is
earlier than the selected commit maximum means: run this operation over
`(d/as-of containing-db t)` inside the JVM. The wrapper never crosses Transit.

The containing commit is load and release identity. The selected `t` is read
semantics. Keeping those two facts in one coordinate is necessary because writer
batching can persist several transaction cuts in one commit, and transaction
responses already use earlier cuts inside a later containing value.

### History

History remains the existing `:seon.db.protocol/history? true` option. For a
strict earlier cut, the host composes history over the as-of value. The
executable probe below found identical bounded datoms for both wrapper orders,
but the existing writer already applies `d/history` to the resolved operation
value, so retain that one order:

```clojure
(d/history (d/as-of containing-db t))

```

This yields assertions and retractions through `t`; it does not mean “all
history in the containing commit.” Query and native index-page therefore use
the same selected cut.

### Branch

Branch is the attached route, not an assertion about the immutable commit
record's stored `:config :branch`.

This distinction is required by Datahike itself. A native branch created from a
commit UUID points its branch record at the same commit. Loading that UUID with
`commit-as-db` reconstructs the immutable record with its original branch,
while loading the branch head reconstructs the selected branch. The probe
observed the same UUID and `t` as:

```clojure
;; branch head
[:probe/b #uuid "6a58827b-2f55-5c41-aadf-62fa87603118" 536870913]

;; same commit loaded by UUID
[:db      #uuid "6a58827b-2f55-5c41-aadf-62fa87603118" 536870913]

```

Therefore a full equality check against `coordinate/resolved` is too strict,
but blindly overwriting branch is unsafe: `commit-as-db` can load an orphan or
a sibling-only commit from the same physical store. The authority must first
prove that the requested commit is an ancestor of the attached branch head,
then use the existing `coordinate/at` attachment projection. The replay path's
existing `ancestor-commit?` walk is the correct first implementation. It should
not be duplicated under a new name.

### Transaction responses

Do not assume one logical transaction equals one commit. Datahike's commit loop
drains ready reports, commits only the final database value, and substitutes
that same `commit-db` into every callback. Seon's current live response is
therefore deliberately:

- `coordinate`: the committed batch head returned by Datahike; and
- `previous-coordinate`: the request's `db-before` transaction selected inside
  that same containing batch commit.

That previous coordinate is the immediate production reason strict `t` must
work. It is not malformed merely because its `t` is below the commit maximum.

Recovery currently constructs the request transaction and its predecessor
inside the current containing head (`writer.clj:980-1005`). Consequently a
recovered coordinate may use a later containing commit while selecting the
same transaction cut. That is semantically valid on a proved lineage, but it is
not byte-identical response replay. Do not redesign this during the temporal
read cut. The later compact-transaction-response work must explicitly decide
whether the response promises the acknowledgement head, the request transaction
cut, or both existing facts (`coordinate` plus `transaction-id`). The temporal
resolver must not make that choice accidentally.

## Strict validation

Validation is ordered from cheapest and most local to storage/history work.
Every semantic rejection uses the existing
`:seon.db.protocol.error/stale-coordinate`; malformed wire shapes remain
`:seon.db.protocol.error/protocol`. No new error vocabulary is needed.

1. Protocol schema requires the existing closed four-field coordinate. Tighten
   `:seon.db.coordinate/t` from nonnegative to Datahike's transaction floor
   `536870912` (`datahike.constants/tx0`). `t = tx0` is the valid empty origin
   even though it has no `:db/txInstant` datom.
2. The live acquired route must exactly equal the request attachment. This is
   already checked before materialization.
3. Resolve the requested commit from that connection. Absence is stale.
4. Compare database ID and commit UUID. Do not compare the immutable record's
   stored branch directly for the native-fork reason above.
5. Prove the commit is reachable from the current attached branch head. A
   retained orphan, a commit on only a sibling branch, and a commit discarded
   by force-branch are stale even though the UUID still exists.
6. Require `tx0 <= t <= containing max-tx`. This rejects both an origin-underflow
   and Datahike's otherwise silent future-as-head behavior.
7. For `t > tx0`, require the exact transaction entity's
   `:db/txInstant` datom in the containing value. An exact EAVT seek is enough;
   every committed Datahike transaction owns that entity. This rejects a hole
   in imported or damaged history instead of silently treating it as “the most
   recent earlier transaction.”
8. Reconstruct the coordinate through `coordinate/at` and require exact equality
   with the request before executing work.

The transaction-existence check belongs in `coordinate/at`, because that is
already the one constructor used by live reports, replay, recovery, and
lifecycle code. The route reachability check belongs in `pinned-database`,
because only the authority has the attached connection and current branch head.

An exact transaction check can use the native EAVT index rather than a Datalog
scan:

```clojure
(or (= t datahike.constants/tx0)
    (seq (d/datoms containing-db :eavt t :db/txInstant)))

```

The retained proof should also force a missing in-range transaction using an
imported or deliberately altered fixture; ordinary Datahike writer commits are
contiguous, so merely performing normal transactions cannot create the
negative case.

## Smallest implementation seam

No Datahike production change is required for the first strict cut.

1. Strengthen `coordinate/at` to reject a nonexistent transaction, with `tx0`
   as the explicit origin exception. Tighten the registered `t` lower bound.
2. Refactor `pinned-database` to keep two host-local values together:
   the raw containing value that owns materialized resources and the operation
   value, which is either that raw value or `d/as-of` over it.
3. Reuse the existing ancestry walk before allowing a commit to be projected
   onto the active branch attachment.
4. Execute all execute-many members over the one operation value. Release only
   the raw containing value after every member completes or cancels.
5. Keep current raw values on Datahike's cache/single-flight path. Earlier
   temporal wrappers remain uncached exactly as the settled Datahike law says.
6. Reject temporal `schema` and KNN explicitly until their native implementations
   can prove `t` semantics. Do not return current schema or current secondary
   results labeled with an earlier coordinate.

The raw owner distinction is necessary for resilience. Datahike's
`release-materialized-db` currently calls `release-db` on its argument.
`AsOfDB` is a wrapper whose materialized-secondary ownership fields live on the
origin, so releasing only the wrapper is a no-op. Retaining and releasing the
raw containing value avoids a secondary-index resource leak without changing
Datahike's public API.

A later Datahike improvement could make `release-materialized-db` recursively
release a temporal wrapper's origin. That would be a useful defensive library
fix, but it is not required to settle or expose the protocol seam.

## Protocol fixtures

The same request shape covers head and strict temporal reads.

```clojure
{:seon.db.protocol/operation :seon.db.protocol.operation/query
 :seon.db.protocol/request-id "query/as-of-1"
 :seon.db.protocol/database-name "default"
 :seon.db.protocol/attachment
 {:seon.db.coordinate/database-id #uuid "54b5b7e7-51fb-3220-b079-81a81914d86f"
  :seon.db.coordinate/branch :db}
 :seon.db.protocol/coordinate
 {:seon.db.coordinate/database-id #uuid "54b5b7e7-51fb-3220-b079-81a81914d86f"
  :seon.db.coordinate/branch :db
  :seon.db.coordinate/commit-id #uuid "6a56b426-c836-5817-9f6b-20584f2e81d5"
  :seon.db.coordinate/t 536870913}
 :seon.db.protocol/query-form
 [:find '?value :where ['?entity :example/value '?value]]
 :seon.db.protocol/arguments []}

```

History changes only the existing option:

```clojure
(assoc request :seon.db.protocol/history? true)

```

The native index-page uses the identical coordinate and option, and its cursor
must repeat that coordinate exactly. A temporal page cursor cannot resume at a
head coordinate even when the commit UUID is equal.

A semantic failure remains the existing ordinary response:

```clojure
{:seon.db.protocol/success? false
 :seon.db.protocol/request-id "query/as-of-1"
 :seon.db.protocol/error-kind :seon.db.protocol.error/stale-coordinate
 :seon.db.protocol/error "The requested database coordinate is unavailable."
 :seon.db.protocol/expected-coordinate requested-coordinate
 :seon.db.protocol/current-coordinate current-head-coordinate}

```

Required fixture mutations are:

- same commit and its maximum `t`: raw head path;
- same commit and an earlier real `t`: as-of path;
- `t = tx0`: empty-origin path;
- `t > containing max`: stale without executing the query;
- in-range absent transaction: stale without falling back to an earlier state;
- retained but non-ancestor commit: stale;
- sibling-only commit in the same store: stale;
- shared native fork-point commit whose stored config says the source branch:
  accepted through the target branch only after reachability proof; and
- wrong database ID or unattached branch: stale before work admission.

## Executable probes

The probes used only an in-memory Datahike database and released/deleted it.
They did not start Seon lifecycle processes or modify source.

### Numeric temporal behavior

Three successive values had transaction IDs
`[536870912 536870913 536870914]`. Query results were:

| Value | Result |
|---|---|
| head | `#{[1]}` |
| as-of `tx0` | `#{}` |
| as-of schema transaction | `#{}` |
| as-of data transaction | `#{[1]}` |
| as-of future `t = head + 1` | `#{[1]}` |

The last row is the decisive falsifier: Datahike intentionally treats a future
numeric cut as head. Strict protocol validation cannot be delegated to
`d/as-of`.

`d/history (d/as-of db t)` and `d/as-of (d/history db) t` returned the same
four datoms through the selected schema transaction in the probe. The first
form matches the existing Seon composition and should remain canonical.

### Branch identity

Creating `:probe/b` from a commit UUID produced a branch head and a UUID-loaded
value with the same commit and maximum transaction but different stored branch
fields, as shown above. This falsifies both naive full-coordinate equality and
naive branch replacement. Reachability plus expected attachment is required.

### Transaction existence

An exact EAVT seek at a committed transaction returned its one
`:db/txInstant` datom. A future transaction returned none. The empty origin
`tx0` also returned none, proving why it needs the explicit exception rather
than weakening the existence rule for every `t`.

## Throughput, memory, and resilience implications

- Head reads remain unchanged: one connection dereference, no wrapper, and the
  existing Datahike cache/single-flight identity.
- An earlier cut adds one small wrapper and native temporal filtering. It does
  not copy indexes or materialize a replica.
- The transaction-existence test is an exact EAVT seek, not a history scan.
- Historical commit resolution already loads one immutable commit. Reachability
  may walk commit parents; this is required to prevent sibling/orphan reads.
  Optimize it only after measurement, and then beside Datahike's immutable
  commit graph rather than in a Seon snapshot cache.
- Temporal wrappers deliberately do not populate the settled completed query
  cache. This avoids incorrect cross-cut hits and preserves release ownership.
  If repeated historical workloads later justify reuse, extend Datahike's one
  cache identity with the selected `t`; do not add a Seon cache.
- One execute-many request creates one as-of wrapper and shares it across all
  members. It must not create one wrapper or load one commit per member.
- Cancellation and disconnect release the raw containing value once after all
  member work stops. Releasing a wrapper alone can leak restored secondary
  resources.
- Rejecting temporal schema/KNN is safer than returning fast but mislabeled
  current results. Their eventual support belongs in the dependency owners,
  where temporal schema and secondary-index semantics can be proved directly.

## Tradeoffs and rejected alternatives

### Resolve the exact commit whose maximum equals `t`

Rejected as the general protocol rule. Writer batching intentionally puts
several transaction cuts in one durable commit, and current transaction/replay
coordinates intentionally retain that containing commit. Searching for a
one-transaction commit would fail for valid batched cuts and duplicate the
commit graph model.

### Accept any in-range `t`

Rejected. It silently rounds a damaged/imported history hole down to an earlier
state, so a coordinate can claim a transaction that never happened.

### Compare the stored commit branch directly

Rejected. Native branching from a commit UUID preserves the immutable commit's
original config while the branch record supplies the target branch.

### Replace the branch field without ancestry proof

Rejected. A connection can load any retained UUID in its physical store,
including sibling-only and orphaned commits.

### Add remote `as-of`, `history`, or database-value objects

Rejected. They export host ownership, complicate release and cancellation, and
duplicate facts already present in the coordinate and `history?` option.

### Patch Datahike first

Rejected for the initial cut. Database name, attachment, protocol errors, and
branch-route authorization are Seon concerns. Datahike already provides the
immutable value and temporal operation. A defensive wrapper-release patch is
useful later, but no new Datahike capability is required.

## Acceptance evidence

The strict seam is complete only when focused fixtures prove all of the
following in one source-frozen checkpoint:

1. head query/pull/index behavior and cache evidence are unchanged;
2. one earlier real `t` drives query, pull, pull-many, history query, forward
   index page, reverse index page, and execute-many over exactly one containing
   materialization;
3. current and temporal query results cannot share an incorrect completed-cache
   hit;
4. future, origin-underflow, nonexistent, sibling-only, orphaned, wrong-store,
   and wrong-branch coordinates fail before user query computation;
5. a shared native fork-point commit succeeds on the target route without
   permitting an unrelated sibling commit;
6. an exact temporal cursor resumes only under the identical coordinate,
   direction, index, history option, and Datahike-verified prefix;
7. cancellation/disconnect during a temporal execute-many request releases the
   raw materialized value exactly once and leaves no restored secondary index;
8. temporal schema and KNN return an explicit unsupported/protocol failure until
   native `t` semantics exist; and
9. every success echoes the requested coordinate exactly and returns only eager
   ordinary wire data.

This is the optimal seam because it makes the existing coordinate truthful,
uses Datahike's immutable and temporal machinery directly, preserves one
resource owner, and adds no public concept.
