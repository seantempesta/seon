---
type: research
status: proposed
tags: [storage, datahike, konserve, gc, correctness]
---

# Storage reclamation correctness in vendored Datahike — 2026-08-05

## Verdict

A compare-and-swap of the branch heads recorded by the mark does not make the
current two-phase collector correct: it neither covers a branch created from an
older commit nor remains valid during Konserve's multi-key, prefix-applied
sweep. The correct construction is a store-id-scoped, two-sided reachability
fence in vendored Datahike: operations that can publish an older previously
unreachable object and the complete mark-plus-sweep pass are mutually
exclusive, while ordinary Datahike commits remain concurrent under the
existing values-before-head ordering and safe-point cutoff
(`reference-code/datahike/src/datahike/writing.cljc:423-565`;
`reference-code/datahike/src/datahike/gc.cljc:121-146`).

**Invariant, in one sentence:** while reclamation owns the store's exclusive
reachability fence, the complete root set cannot acquire a reference to an
older object, and an object older than the captured safe point is removed only
when it is absent from the closure of that fixed root set.

This requires a small Datahike fork change, not acknowledged quiescence in
every cluster's Flow graph. Seon's process-root flock already makes Datahike's
one-JVM writer assumption true for the store
(`src/seon/cluster/store.clj:183-238,270-351`); the missing fence therefore
belongs beside Datahike's existing store-id-keyed GC guard, at the database
mechanism that knows which operations change reachability
(`reference-code/datahike/src/datahike/gc_guard.cljc:47-55`).

## Sources read end to end

I read the following current sources end to end, not by keyword extraction:

- Datahike `c15272730e74fb3f8bba91f6361c268492a99ba7`:
  `reference-code/datahike/src/datahike/gc.cljc`, `gc_guard.cljc`,
  `versioning.cljc`, `writing.cljc`, `writer.cljc`, `api.cljc`, and
  `api/specification.cljc`; also both current reclamation test namespaces,
  `test/datahike/test/gc_test.cljc` and
  `test/datahike/test/background_gc_test.cljc`.
- Konserve `89795ae1b769aafd47adf4168e2393d7b4721bc2`:
  `reference-code/konserve/src/konserve/gc.cljc`, `protocols.cljc`,
  `core.cljc`, `impl/storage_layout.cljc`, `impl/defaults.cljc`,
  `filestore.clj`, and `utils.cljc`.
- Seon: `src/seon/cluster/registry.clj`,
  `src/seon/cluster/store.clj`, and the directly implicated blob owner,
  `src/seon/blob.clj`.
- The active working edge in
  `docs/prds/sci-execution-runtime/plan/unsettled.md`, the previously landed
  independent analysis and probe report
  `docs/prds/sci-execution-runtime/research/gc-correctness-cas-opus-2026-08-05.md`,
  and its open issue
  `docs/seon/issues/ranged-store-collection-can-delete-live-segments-via-branch-resurrection.md`.

For the upstream-delta check I also read the complete store-reference design at
Datahike commit `11426b97`, the reclamation implementations at `11426b97` and
feature commit `501f23fb`, and the complete relevant changes at Datahike commit
`1ac36159` and Konserve commit `596b5ec`.

### Dependency ledger

| Boundary | Selected source | First-party seam |
| --- | --- | --- |
| Reachability mark and sweep cutoff | Datahike `c15272730e74fb3f8bba91f6361c268492a99ba7`, `gc.cljc:22-146` and `gc_guard.cljc:47-114` | `src/seon/cluster/registry.clj:327-359` |
| Branch roots and commit publication | same pin, `versioning.cljc:174-203,237-444` and `writing.cljc:321-565` | `src/seon/cluster/registry.clj:104-111,160-251` |
| Per-key writes and multi-key removal | Konserve `89795ae1b769aafd47adf4168e2393d7b4721bc2`, `protocols.cljc:4-39`, `core.cljc:279-318,435-475,512-565`, `gc.cljc:8-40` | Datahike's `sweep!` call at `gc.cljc:146` |
| One-JVM enforcement | Java file lock held for the store lifetime, `src/seon/cluster/store.clj:183-238,270-370` | process-root store owner |
| Blob values and references | Konserve binary values, `protocols.cljc:41-49`; Seon writes at `src/seon/blob.clj:103-116,138-149` | fact-derived whitelist at `src/seon/cluster/registry.clj:288-325,343-357` |

## 1. Present guarantees

### Public operation and arguments

`gc-storage` has `[connection]` and `[connection time-point]` forms; its public
description is only that it removes old snapshots before the supplied time
point (`reference-code/datahike/src/datahike/api/specification.cljc:1142-1156`).
The API dispatches to the writer (`reference-code/datahike/src/datahike/writer.cljc:446-454`),
but the writer recognizes the returned channel as a background operation and
runs it concurrently with its serialized commit loop
(`reference-code/datahike/src/datahike/writer.cljc:184-199,231-299,301-310`).
Thus routing through the writer does not freeze heads.

### `remove-before` is retention policy; the safe point is correctness

The implementation has two different time points:

- `remove-before`, the public argument, controls the mark. A branch and its
  head commit are seeded unconditionally, but parents are followed only while
  the record's creation/update instant is newer than `remove-before`
  (`reference-code/datahike/src/datahike/gc.cljc:22-27,31-42,61-74`). The
  no-argument form uses epoch, retaining all reachable ancestry
  (`reference-code/datahike/src/datahike/gc.cljc:83-87,118-120`). This is a
  **policy knob**: narrowing the retained history deliberately makes more
  segments reclaimable.
- `cutoff` is internal and controls the sweep. It is the earlier of the pass's
  captured `now` and `gc-guard/safe-point`; only objects strictly older than it
  may be removed (`reference-code/datahike/src/datahike/gc.cljc:121-130,146`;
  `reference-code/konserve/src/konserve/gc.cljc:13-22`). This is a
  **correctness mechanism** for values written before their publishing
  pointer.

Datahike states this distinction directly: `remove-before` is mark-side and
makes collection remove more; the safe point is sweep-side and makes it remove
less (`reference-code/datahike/src/datahike/gc.cljc:89-106`). Calling the
public time point an "age safety cutoff" conflates these separate roles.

An additional age grace is correct only when the system enforces a maximum
delay between an object's write and publication of its reference; the upstream
store-reference design says exactly that for external uploads
(`reference-code/datahike@11426b97:doc/store-refs.md:79-100`). No finite age
grace protects an arbitrarily old commit that a new branch can publish, so it
cannot repair the branch-creation gap.

### What the safe point covers

Datahike's crash discipline is values first, mutable pointer last. `commit!`
opens the store-id guard before index materialization, writes pending nodes,
schema data, and the commit record before the branch head, then closes the
guard after the head has landed
(`reference-code/datahike/src/datahike/writing.cljc:423-449,475-552,564-565`).
For multi-key Konserve backends the sequence is ordered and the head is last;
for the fallback path the commit record is awaited before the head write is
issued (`reference-code/datahike/src/datahike/writing.cljc:497-552`). Konserve
promises ordered-prefix rather than universal all-or-nothing semantics for
multi-key writes, and documents why pointer-last makes any prefix safe
(`reference-code/konserve/src/konserve/protocols.cljc:19-39`;
`reference-code/konserve/src/konserve/core.cljc:435-475`).

The guard records each in-flight values-before-pointer sequence in one
process-local atom keyed by store ID. Its safe point is `now` when none is open
and the oldest sequence's start otherwise
(`reference-code/datahike/src/datahike/gc_guard.cljc:47-74,85-102`). `branch!`
and `force-branch!` also hold it across their writes
(`reference-code/datahike/src/datahike/versioning.cljc:246-291,355-444`).

That guarantee is exact for **newly written objects**. It is not a root-set
stability guarantee: `branch!` can read an already old commit and publish a new
branch pointing to that old closure
(`reference-code/datahike/src/datahike/versioning.cljc:254-289`). The guard's
cutoff does not make those old objects young again.

### What is atomic underneath

Konserve's `update` is an atomic read-modify-write of one key under that store
instance's per-key lock (`reference-code/konserve/src/konserve/protocols.cljc:4-12`;
`reference-code/konserve/src/konserve/core.cljc:279-318`). File publication of
one key uses an atomic move (`reference-code/konserve/src/konserve/filestore.clj:302-308`).
Neither fact makes the whole sweep atomic: `multi-dissoc` may expose an applied
prefix (`reference-code/konserve/src/konserve/core.cljc:528-565`), and the file
backend removes keys sequentially (`reference-code/konserve/src/konserve/filestore.clj:336-351`).
The collector first enumerates all candidates and then removes them in batches
(`reference-code/konserve/src/konserve/gc.cljc:8-40`).

## 2. Interleaving inventory

### Concurrent transaction advances a head mid-pass

**Datahike structural segments: prevented in the supported one-JVM model.** If
the mark sees the old head, the old closure is marked and every node newly
written by the concurrent commit is at or newer than the safe point. If the
head changes between reading its commit ID and reading the branch record, the
mark retains the old commit ID and walks the new record, which is a safe
superset (`reference-code/datahike/src/datahike/gc.cljc:22-31`). The writer's
pointer-last ordering and the cutoff protect both cases
(`reference-code/datahike/src/datahike/writing.cljc:497-552`;
`reference-code/datahike/src/datahike/gc.cljc:121-146`). This is why ordinary
commits need not be stopped by the new fence.

**An old out-of-line object newly named by that transaction: not generally
prevented.** Seon's current blob key is not part of Datahike's native mark;
`collect!` computes a separate whitelist before starting Datahike's pass
(`src/seon/cluster/registry.clj:288-325,342-358`). If the mark omitted an old
blob and a concurrent transaction then names it, the structural commit is safe
but the blob is not. Correct blob publication must either hold the unreferenced
write guard from before the blob write through the referencing transaction or,
for a genuinely pre-existing unrooted object, participate in the reachability
fence.

### Branch created from an older commit between mark and sweep

**Not prevented.** The collector reads the branch roster once, marks its
closure, and then sweeps (`reference-code/datahike/src/datahike/gc.cljc:136-146`).
`branch!` may subsequently read an older commit, write the new head, and add the
branch to the roster last (`reference-code/datahike/src/datahike/versioning.cljc:254-289`).
Its guard protects the newly written head and secondary-index values but not
the older index nodes that the head makes reachable. Those nodes can be absent
from the completed mark and older than the sweep cutoff.

This gap is most readily exposed by ranged reclamation because
`remove-before` stops the parent walk
(`reference-code/datahike/src/datahike/gc.cljc:41-42,72`). Epoch retention
protects an old commit only when that commit remains in the ancestry of a root
seen by the pass; it does not protect an old commit belonging solely to a
retired branch or another currently unrooted closure.

The deterministic scratch-store probe was rerun in this lane at
`tmp/gc-resurrection-probe/store`, never under `data/`. It overwrote the same
400 entities across several commits so old persistent-index nodes became
genuinely unreachable, paused the real collector after mark and before sweep,
created a real branch from a middle commit, and resumed the real Konserve
sweep. Branch creation succeeded, the roster and head looked valid, and 23
keys were removed. The warm connection still read 400 datoms because its node
cache masked the missing segment; after releasing and reconnecting, reading
the new branch failed with `:node-not-found`. The failure matches the source
seam at `reference-code/datahike/src/datahike/gc.cljc:136-146` and the branch
publication at `reference-code/datahike/src/datahike/versioning.cljc:268-289`.

Seon exercises this operation routinely: its branch owner accepts a commit UUID
and cluster creation/refork uses the selected source commit
(`src/seon/cluster/registry.clj:160-198,200-251`).

### Blob written during the pass

**A blob whose Konserve write lands after the pass captured its cutoff is
spared in that pass**, because the sweep retains every key whose `last-write`
is greater than or equal to the cutoff
(`reference-code/konserve/src/konserve/gc.cljc:13-22`). This is temporary
protection, not publication atomicity.

**The complete write-then-reference sequence is not prevented today.** Seon
writes a content-addressed binary value with `k/bassoc`
(`src/seon/blob.clj:103-116,138-149`) and the caller later transacts its digest.
Neither the blob API nor `collect!` holds Datahike's unreferenced-write guard
across both operations, and the blob whitelist is captured before the Datahike
mark (`src/seon/cluster/registry.clj:321-325,342-358`). A blob written before
the cutoff, or an older previously unreferenced blob, can therefore be named
after the whitelist snapshot and removed by the pass. Datahike's upstream
store-reference contract identifies the same gap and requires one guard across
the object write and reference transaction
(`reference-code/datahike@11426b97:doc/store-refs.md:18-25,79-100`).

### Two overlapping passes

**Prevented through Seon's owner; not prevented by raw Datahike.** A
process-global monitor serializes every call through `registry/collect!`
(`src/seon/cluster/registry.clj:286,327-359`). Datahike itself deliberately
runs the reclamation channel in parallel, and its optional background loop can
overlap other direct calls
(`reference-code/datahike/src/datahike/writer.cljc:184-199,301-310`;
`reference-code/datahike/src/datahike/gc.cljc:148-192`). Two passes over a
stable root set only duplicate idempotent removals or retain a safe superset;
they do not by themselves introduce a new live object. Each pass nevertheless
retains the branch-creation and blob-publication gaps above.

There is an additional current Seon hazard if any direct Datahike pass bypasses
the sole owner: `collect!` extends the mark by process-wide `with-redefs` of
`konserve.gc/sweep!` (`src/seon/cluster/registry.clj:343-357`). A concurrent
pass for a different store could receive the first store's blob keys instead
of its own. Current ownership avoids that overlap, but the fork should expose a
real mark-extension input and delete this global binding.

### Fork or refork mid-pass

**A same-store cluster fork/refork is not prevented.** It is the branch-from-old
commit case above; `reset-cluster!` explicitly retires and recreates the branch
from the selected source commit (`src/seon/cluster/registry.clj:224-251`).
Removing a branch during the pass is safe by itself because a mark that already
saw it merely retains a superset; creating or forcing a branch to an older
closure adds reachability and needs the fence.

**A cross-store `fork-database` has a separate documented copy race.** It
enumerates and copies Konserve keys one at a time
(`reference-code/datahike/src/datahike/versioning.cljc:669-683`) and explicitly
states that a concurrent source write can tear the copy; callers must quiesce
or verify (`reference-code/datahike/src/datahike/versioning.cljc:517-551`). A
source-store sweep can likewise remove a key between enumeration and copy.
That operation needs its own source read fence; a destination head comparison
alone cannot prove every referenced source segment was copied.

### Second JVM on the same store

**Prevented when all access goes through Seon's store owner; not prevented by
Datahike alone.** `open-store!` canonicalizes the path and acquires a
non-blocking exclusive file lock before checking or opening the database, then
holds it until Datahike has released successfully
(`src/seon/cluster/store.clj:112-164,183-238,270-370`). Datahike's GC guard is
explicitly process-local and says all writers must be in one JVM; a second
`:self` writer is not fenced and can lose commits even without reclamation
(`reference-code/datahike/src/datahike/gc_guard.cljc:36-41`;
`reference-code/datahike/src/datahike/gc.cljc:108-117`). Any tool that bypasses
`seon.cluster.store/open-store!` is outside the safe construction.

### Summary

| Interleaving | Existing result | Mechanism or gap |
| --- | --- | --- |
| Datahike transaction advances a branch head | safe for structural segments | pointer-last ordering plus safe-point cutoff |
| Transaction newly names an old blob | unsafe | current mark already captured the blob whitelist |
| Branch is created from an old commit | **unsafe** | new roster/head writes are guarded; old closure is not |
| Blob write lands after cutoff | spared for this pass | `last-write >= cutoff` |
| Blob write/reference sequence crosses the pass | **unsafe** | no guard across both operations |
| Two `registry/collect!` calls | serialized | `collect-monitor` |
| Direct/background Datahike passes | may overlap | no Datahike pass latch; global Seon mark extension is unsafe if bypassed |
| Cluster fork/refork from commit | **unsafe** | same as branch creation |
| Cross-store fork while source changes/reclaims | unsafe without source fence | key-by-key copy |
| Second Seon JVM using store owner | refused | process-root file lock |
| Second raw Datahike JVM | **unsafe** | process-local writers and GC guard |

## 3. The CAS question

### What is comparable and what exists

A branch head contains an ordinary UUID at
`[:meta :datahike/commit-id]`; `commit!` computes and stores it in the head
record (`reference-code/datahike/src/datahike/writing.cljc:363-382,475-482`).
Recording and comparing head values is therefore mechanically possible.

The nearest existing conditional update is `force-branch!`'s
`:expected-current-commit`. It checks before materialization and again inside
the single-key `k/update` that writes the head, then reads the head back
(`reference-code/datahike/src/datahike/versioning.cljc:323-370,415-441`). Its
own contract says the caller must already have exclusive write access because
Konserve does not expose a cross-operation compare-and-set against an
independent writer (`reference-code/datahike/src/datahike/versioning.cljc:327-335`).

Konserve's atomic operation is one-key `update`, not an atomic transaction
over a branch roster, every head, and a set of removals
(`reference-code/konserve/src/konserve/core.cljc:279-318`). Datahike's branch
roster uses an additional store-id-keyed in-process channel to serialize roster
updates (`reference-code/datahike/src/datahike/versioning.cljc:174-203`); that
is serialization, not a durable backend CAS.

### Why record-and-verify is insufficient

Checking that recorded heads are unchanged just before sweep has two gaps:

1. A newly created branch has no recorded old head to compare, so every
   recorded head can verify unchanged while the new branch publishes an older
   closure (`reference-code/datahike/src/datahike/versioning.cljc:237-291`).
2. Even if the complete roster and heads verify, another publication can occur
   immediately after verification while Konserve removes many independent keys.
   The file backend exposes a sequential applied prefix, not one atomic sweep
   (`reference-code/konserve/src/konserve/protocols.cljc:19-39`;
   `reference-code/konserve/src/konserve/filestore.clj:336-351`). Once a live
   key has been removed, a failed post-sweep verification cannot repair it.

A retry-on-change algorithm can safely retry **marking**, but it cannot retry
or roll back an already started destructive sweep. Therefore a verification
must also prevent every relevant publication until the final removal completes.

### Can a generation close the branch-creation gap?

Yes, but only if it becomes the same exclusion mechanism under another name.
A monotonic branch-set generation merely detects a branch created after the
snapshot. For correctness, reclamation must atomically change a store-wide
reachability state from generation `G` to `sweeping(G)`, every operation that
can add old reachability must refuse or wait while that state is held, and the
state must remain held through the last removal. Branch-set generation alone
is still incomplete: forced heads, explicit GC roots, and old blob references
also add reachability without necessarily adding a branch.

The current dependencies do not expose a durable multi-key CAS that could
implement such a state across JVMs. Under Seon's enforced one-JVM model, one
atomic state transition in the existing store-id-keyed process-local guard is
sufficient and simpler (`reference-code/datahike/src/datahike/gc_guard.cljc:47-74`;
`src/seon/cluster/store.clj:183-238`). CAS is appropriate for acquiring that
gate; it is not a substitute for holding the gate.

## 4. Correct-by-construction design

### Vendored Datahike change

Extend the current store-id entry in `datahike.gc-guard` from only in-flight
unreferenced writes to two coordinated states:

- the existing timestamped write tokens, which continue to define the safe
  point and let ordinary `commit!` proceed concurrently; and
- an exclusive reachability-publication/reclamation state acquired atomically
  on that same store-id entry.

The exact owners are:

1. `gc-storage!` acquires the reclamation side before reading `:branches` and
   holds it through `sweep!`, releasing in `finally`
   (`reference-code/datahike/src/datahike/gc.cljc:120-146`).
2. `branch!` and `force-branch!` acquire the publication side across their
   existing guarded scopes
   (`reference-code/datahike/src/datahike/versioning.cljc:246-291,355-444`).
   Creation of the initial database and any API that publishes an explicit old
   GC root must follow the same rule when applicable.
3. Ordinary `commit!` retains only the existing write token because its new
   graph is the old head closure plus values written within that token
   (`reference-code/datahike/src/datahike/writing.cljc:423-565`).
4. A conflict waits on an observable release or returns a typed retriable
   refusal; it never relies on a tuned sleep. No Flow graph has to quiesce,
   because the operator's branch publication is not owned by an agent graph
   and Datahike already owns the exact dependency.

If implemented as a generation, the state must cover **all reachability
publication**, not only the branch roster, and must remain in `sweeping` state
through the last Konserve removal. That is semantically the same two-sided
gate.

### Blob change

Replace Seon's separately derived, process-global sweep extension with
Datahike's first-class key-bearing value mark. Upstream commit `11426b97`
introduces `:db.type/store-ref`: an object is live when a retained datom names
it, and the mark collects those keys across branches/history
(`reference-code/datahike@11426b97:doc/store-refs.md:15-25,70-77`;
`reference-code/datahike@11426b97:src/datahike/gc.cljc:26-74,76-176`). Adopt
that mechanism rather than preserving `with-redefs`
(`src/seon/cluster/registry.clj:343-357`).

For local blobs, the write and the transaction that first names the digest
must share one `with-unreferenced-writes` scope, exactly as the upstream design
requires (`reference-code/datahike@11426b97:doc/store-refs.md:79-100`). A later
attempt to name a pre-existing but currently unrooted object must either take
the publication side of the reachability fence or first establish an explicit
root; it cannot rely on the object's age.

### Role of time points in the final invariant

The public `remove-before` remains necessary as the declared retention policy:
it defines which parent edges belong to the fixed root closure
(`reference-code/datahike/src/datahike/gc.cljc:41-42,72,103-106`). It is not a
safety hedge. The internal safe point remains necessary because a pass may run
alongside ordinary values-before-head commits and must not remove their newly
written unpublished values
(`reference-code/datahike/src/datahike/gc_guard.cljc:85-102`).

An extra age floor belongs only to an external-object protocol with a stated,
enforced maximum upload-to-reference interval; the invariant is then "no
unreferenced external object is removed before that maximum publication
interval has elapsed" (`reference-code/datahike@11426b97:doc/store-refs.md:93-100`).
It is not part of the branch correctness argument.

### Upstream-delta note

The current pin diverged from upstream at `85c40aee`; these relevant changes
exist after that point:

- Datahike `11426b97` adds first-class store references and their GC mark. It
  should replace Seon's manual blob whitelist, but it does **not** freeze the
  root set: it still reads the roster once and then sweeps
  (`reference-code/datahike@11426b97:src/datahike/gc.cljc:178-251`).
- Datahike `1ac36159` plus Konserve `596b5ec` moves safe-point and `last-write`
  stamps onto one monotonic clock. The current pins use separate raw wall-clock
  reads (`reference-code/datahike/src/datahike/gc_guard.cljc:44-45`;
  `reference-code/konserve/src/konserve/utils.cljc:10-24`); the upstream change
  explains that a clock retreat can otherwise stamp a guarded live object
  before the safe point
  (`reference-code/datahike@1ac36159:src/datahike/gc_guard.cljc:48-54`). Adopt
  both sides together.
- Feature commit `501f23fb` adds persistent `:gc-roots` whose ancestry is not
  time-gated (`reference-code/datahike@501f23fb:src/datahike/gc.cljc:80-94,196-246`).
  Explicit roots are useful for pinning a commit **before** reclamation starts,
  but the implementation reads roots once before sweep
  (`reference-code/datahike@501f23fb:src/datahike/gc.cljc:248-342`); a root
  added after that read has the same race and must participate in the new gate.

The fork delta to maintain and offer upstream is therefore: adopt the
store-reference mark and monotonic-clock pair, then add a store-id-scoped
two-sided reachability fence around `branch!`/`force-branch!`/explicit-root
publication and the complete mark-plus-sweep. Record that this fence is
required whenever `remove-before` permits an old unrooted closure to be
reclaimed; the current safe point covers writes, not resurrection of old
objects.

## 5. Verification scenario in prose

Use one isolated repository-local scratch store with `:keep-history? false` and
a ranged `remove-before` equal to the pass start. Create a schema attribute,
write a stable set of several hundred entities, and update those same entities
through at least six commits; overwriting is load-bearing because append-only
data can leave every old persistent-index node structurally reachable and make
the scenario pass without exercising reclamation. Record the branch roster,
every branch head commit ID, the query result at each head, one middle commit
ID with its expected result, and the contents/digests of existing referenced
blobs.

Make the interleaving deterministic at a proper test hook after the complete
reachability mark and before the first removal. While the pass is paused there:

1. commit a transaction on the main branch that changes the indexed data;
2. request a new branch whose source is the recorded middle commit; and
3. write a distinct blob and publish its reference through the supported
   guarded store-reference path.

Under the recommended fence, the branch/root publication must wait or return a
typed retriable refusal until reclamation releases the gate; the ordinary
writer commit may complete concurrently, and the guarded blob sequence is
protected by the safe point. If the API chooses refusal, retry the branch
creation immediately after the pass completes. Do not accept timing as proof:
the test must observe the explicit after-mark/before-removal hook and the
publication operation's wait/refusal.

After the pass, release every connection and clear process-local caches by
opening fresh connections. Assert that every original branch still resolves
to its recorded or validly advanced head, the writer's new transaction is
present, the newly created branch opens from the requested old commit and
returns the recorded middle-commit result, and every previously referenced and
newly referenced blob returns exactly its original bytes. Cold reopen is
mandatory because the scratch probe showed a warm persistent-index cache can
continue serving nodes that the sweep already removed.

Finally run the same ranged pass again without concurrent publication, assert
that it completes and preserves all current heads/blobs, and include a negative
control proving reclamation is real: before the interleaving scenario, the
same ranged pass on an equivalent unrooted old closure must remove a non-zero
set and make an unrooted old commit unavailable. This prevents an
over-retaining implementation from passing the correctness test vacuously.

## Open issue

The data-loss finding is already recorded at
`docs/seon/issues/ranged-store-collection-can-delete-live-segments-via-branch-resurrection.md`.
This report refines its acceptance boundary by distinguishing a blob written
after the cutoff (spared for the current pass) from the unsafe unguarded
write/reference interval, and by requiring any generation-based alternative to
remain held through Konserve's non-atomic multi-key sweep.
