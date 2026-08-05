---
type: prd
status: active
tags: [prd, storage, datahike, konserve, gc, maintenance]
---

# Exclusive sweep design — 2026-08-05

## Reading and ruling

I read the repository `AGENTS.md` in full; the P1 section of
[state-of-the-program-2026-08-05.md](state-of-the-program-2026-08-05.md);
[gc-correctness-cas-opus-2026-08-05.md](../research/gc-correctness-cas-opus-2026-08-05.md);
[storage-reclamation-correctness-2026-08-05.md](../research/storage-reclamation-correctness-2026-08-05.md);
[ranged-store-collection-can-delete-live-segments-via-branch-resurrection.md](../../../seon/issues/ranged-store-collection-can-delete-live-segments-via-branch-resurrection.md);
and the second ruling batch in [README.md](README.md), including **P1 STORAGE
RECLAMATION: EXCLUSIVE SWEEP**, in full before writing this design. I also read
the localized instructions and the current Datahike and Konserve owners named
below.

This document designs to the owner's 2026-08-05 ruling: a collection owns the
branch-creation exclusion from before its whitelist is computed through its
last physical delete. No fork or `branch!` begins in that interval.
`bin/seon start` waits or refuses loudly. The earlier preemptible-sweep design
is superseded.

## Dependency ledger

The selected sources are Datahike
`c15272730e74fb3f8bba91f6361c268492a99ba7` and Konserve
`89795ae1b769aafd47adf4168e2393d7b4721bc2`.

- Datahike's present per-store branch-roster mutex is a capacity-one
  core.async channel in
  `reference-code/datahike/src/datahike/versioning.cljc:174-203`.
  `branch!` does not take it until the final roster update, after it has read
  the source and written the new head (`:237-291`). `force-branch!` likewise
  publishes values and a head before that final update (`:323-444`).
- Datahike's collector reads the roster once, marks each branch, unions the
  closures, adds `:branches`, and passes the fixed whitelist to Konserve
  (`reference-code/datahike/src/datahike/gc.cljc:22-81,121-146`).
- The values-before-pointer safe point is process-local and store-ID scoped
  (`reference-code/datahike/src/datahike/gc_guard.cljc:47-74,85-114`).
  Ordinary commits write referenced values before the mutable head
  (`reference-code/datahike/src/datahike/writing.cljc:441-552`).
- Konserve snapshots the complete key-metadata collection up front, then lazily
  filters and partitions it into sequential batches of 1,000; each issued
  batch is fixed before deletion
  (`reference-code/konserve/src/konserve/gc.cljc:8-40`). Its
  current file backing deletes each key and normally syncs the store after
  each deletion (`reference-code/konserve/src/konserve/filestore.clj:336-351,
  893-904`); the apparent batch operation reaches that loop through
  `reference-code/konserve/src/konserve/impl/defaults.cljc:691-712`.
- Seon's process-lifetime flock is acquired before the store opens and held
  until every connection is released
  (`src/seon/cluster/store.clj:183-238,270-370`). It excludes another process,
  not concurrent operations inside the owning JVM.
- Seon's sole collector currently snapshots blob keys before calling Datahike
  and extends the whitelist by process-global `with-redefs`
  (`src/seon/cluster/registry.clj:286-325,327-359`). This is the seam the new
  explicit mark extension replaces.

## Recommended construction

Use one store-ID-scoped **reachability gate**, evolved from Datahike's existing
branch-roster mutex. It has publisher permits and one exclusive sweep permit:

- branch creation, `force-branch!` when it creates or republishes old
  reachability, explicit-old-parent `merge!`, and each blob write-through-root
  sequence are publishers;
- a sweep is exclusive against every publisher, from whitelist computation
  through the last Konserve deletion; and
- ordinary transactions whose only parent is the branch's prior head do not
  take the gate. Their values-before-pointer order and safe point already make
  them safe (`reference-code/datahike/src/datahike/writing.cljc:467-474,
  497-552`; `reference-code/datahike/src/datahike/gc.cljc:121-146`).

The gate is one mechanism, not a branch lock plus a blob lock. Its state needs
to distinguish a roster publisher, any number of blob publishers, a queued
sweep, and an active sweep. The existing roster update still has a serialized
critical section, but it lives inside the same gate. A queued sweep closes
admission to new publishers, lets admitted publishers drain, then receives the
exclusive permit; this prevents publisher traffic from starving a sweep.
Completion is event-driven through the gate's channel/promise notification,
with release in `finally`. There is no correctness timeout.

The low-level owner should be `datahike.gc-guard`, or a dependency-layer
namespace immediately below both `datahike.versioning` and `datahike.gc`.
Putting the primitive in `datahike.versioning` would introduce a require cycle
from GC; putting it only in Seon would leave direct Datahike `branch!` and
`force-branch!` unsafe. The existing `branches-locks` state moves into this
owner rather than surviving as a second mutex.

### Lock choice

#### 1. Widen the existing per-store branch lock — recommended

Guarantee: every API that can publish old reachability participates by store
ID, direct Datahike callers included. The sweep queues once, drains admitted
publishers, marks, and deletes while exclusive. Implementation risk is
moderate because the present non-reentrant channel cannot simply be acquired
outside `branch!`: its final `update-branches!` would reacquire and deadlock
(`reference-code/datahike/src/datahike/versioning.cljc:188-203,288-289`). The
implementation must expose an explicit permit/token or split the already-held
roster update from the acquiring wrapper.

Operationally, the new permit serializes the **entire** branch creation, which
is broader than today: currently only the final roster update is serialized
while source reads, secondary branching, and head writes can overlap
(`reference-code/datahike/src/datahike/versioning.cljc:174-203,264-289`). A
sweep blocks branch creation and blob publication for its full duration.
Ordinary commits continue.

#### 2. Reuse the process flock

Guarantee: none inside the live process. The flock proves that no other JVM
owns the physical store, but the process already holds it for the store's
entire lifetime (`src/seon/cluster/store.clj:183-238,270-370`). Reacquiring it
cannot distinguish a branch operation from collection, and closing a second
descriptor can even drop the process's lock (`:186-196`). This option is
rejected.

#### 3. Commit a coordination fact

Guarantee: durable visibility, but no atomic exclusion with Konserve's
multi-key deletion. A branch can observe the fact before it changes and still
publish after the collector changes it; the collector can do the symmetric
thing. Closing that gap needs the in-process gate anyway, while adding a
second recovery lifecycle. This option is rejected.

### Exact acquisition boundaries

The sweep owner performs this sequence:

1. queue the exclusive request, closing admission to new branch/old-parent/blob
   publishers;
2. wait for admitted publishers to release their permits;
3. capture the safe point and acquire the branch roster, every branch head,
   the retained commit closure, and the blob mark extension;
4. pass that one fixed whitelist to Konserve;
5. retain the exclusive permit across every batch and every individual file
   delete; and
6. publish the bounded receipt and release in `finally`.

`branch!` acquires before it reads `:branches` or its source key and holds
through the new head and roster publication
(`reference-code/datahike/src/datahike/versioning.cljc:264-289`).
`force-branch!` acquires before its current-head/parent reads and holds through
readback (`:361-441`). Branch deletion should use the same roster mode because
it mutates `:branches` (`:293-321`), even though deletion cannot resurrect a
closure. `merge!` with explicit parents also takes a publisher permit: it can
name an old commit absent from the initial mark
(`reference-code/datahike/src/datahike/writing.cljc:450-474`;
`reference-code/datahike/src/datahike/versioning.cljc:688-702`). A normal
prior-head commit remains outside.

Seon's cluster "fork" is the same-store `branch!` path and is covered above.
Datahike's separate cross-store fork reads a selected old source record and
then copies every source key (`reference-code/datahike/src/datahike/versioning.cljc:603-674`).
If that API remains supported, it takes a publisher/read permit on the source
store before resolving the fork point and holds it through the last source
read. Any target permit is acquired in stable store-ID order to avoid a
two-store deadlock. Seon must not introduce this cross-store operation merely
to implement cluster start.

`bin/seon start` takes a nonblocking start permit from the same gate after it
acquires the root store and before it tests the roster. It holds that permit
through branch open, whether the branch already exists or must be forked
(`src/seon/cluster.clj:1878-1890`). It passes the permit to nested
`ensure-cluster!`/`branch!`; nested acquisition is forbidden rather than made
implicitly reentrant.

### Start contention semantics

#### 1. Loud retryable refusal — recommended

If a sweep is queued or active, `bin/seon start NAME` returns immediately with
a flat, typed `:sweep-in-progress` refusal naming the store and the running
maintenance receipt. It writes no branch or roster state. If the REPL-first
boot has already installed a partial instance, this expected contention path
unwinds it rather than leaving the general failed-above-the-REPL partial state
described by `src/seon/cluster.clj:2076-2093` and
`script/seon/fresh_operator.clj:1977-1989`.

This is preferable because a realistic first sweep lasts minutes, while the
operator treats 30 seconds without a prepl event as a bug backstop
(`script/seon/fresh_operator.clj:60-75,1410-1475`). A silent lock wait would
misreport correct exclusion as an operator failure.

#### 2. Event-driven wait with progress

`start` may enqueue and print explicit sweep-progress events until the gate
grants its permit. This preserves one command invocation and can refresh the
operator's event-silence backstop, but it couples maintenance progress to the
boot protocol and makes cancellation/partial-boot cleanup more complex.

#### 3. Silent wait

Rejected. It conflicts with the existing operator backstop and is not loud.

### Expected hold time on the 374 GiB store

The measured census is 151,017 files. A heads-only mark predicts 136,800
deletions and 357.36 GiB reclaimed, leaving 14,217 files and 17.08 GiB
(`docs/prds/sci-execution-runtime/research/disk-burn-forensics-2026-08-04.md:176-205`).
At Konserve's 1,000-key batch size, that is 137 sequential batches.

The only direct elapsed measurement in the reports is a complete pass that
swept 331 objects in 158.653–255.249 ms
(`docs/prds/sci-execution-runtime/research/gc-cost-2026-07-27.md:64-70`). A
linear projection is an illustrative obsolete-path floor of about 66–106
seconds, but that experiment predates the current pins and does not isolate or
measure the current per-file sync path. Current
source performs an existence check, delete, and normally one store sync for
each key inside a batch (`reference-code/konserve/src/konserve/filestore.clj:336-351,
893-904`). At an illustrative 1, 5, or 10 ms per deleted file, deletion alone
is about 2.3, 11.4, or 22.8 minutes, before mark time. Therefore the runbook
reserves **5–25 minutes of exclusivity**, with no correctness timeout; a
current-store dry run must replace that range with measured mark/enumeration
timings before the destructive pass. Immediate start refusal is designed for
this duration.

## Whitelist under exclusivity

For every branch in the locked roster, the native mark retains:

- the branch key and its current head commit ID
  (`reference-code/datahike/src/datahike/gc.cljc:22-27`);
- the head/retained commit records, schema metadata, the three current index
  closures, temporal index closures when history is enabled, and secondary
  index closures (`:31-71`); and
- parent commits only while their record is newer than `remove-before`
  (`:41-42,72-74`).

The union also retains the `:branches` discovery key (`:136-146`). Seon's mark
extension adds every direct blob digest found through declared schema
references on every live branch, including the retained logical history
appropriate to that branch. This extension is an explicit argument/callback
inside the Datahike mark while the gate is exclusive; it is not computed
before acquisition and not installed through process-global `with-redefs`.

Once exclusivity begins, a branch that completed earlier is in the roster and
fully published because roster publication is last. A branch that did not
complete cannot read its source or write its head until after the sweep. An
explicit-old-parent merge is governed by the same rule. Thus no in-flight
operation can publish an old closure outside the fixed whitelist. Concurrent
ordinary commits can only extend their prior head with newly written values;
the safe point retains those values until their pointer lands. This is the
precise reason ordinary transaction throughput need not be quiesced.

## The independent blob publication race

Branch exclusivity does not by itself make a blob write and its later
referencing transaction atomic. Both blob writers publish a content-addressed
Konserve key before the caller's datom commit, and skip the write when the key
already exists (`src/seon/blob.clj:103-116,138-149`). Konserve snapshots all
deletion candidates up front, then deletes by key without rechecking metadata
(`reference-code/konserve/src/konserve/gc.cljc:13-39`). Therefore even an
unconditional rewrite is insufficient after candidate selection:

> enumerate old orphan digest D → rewrite D → commit its reference → delete D
> from the stale candidate batch

The store-ID reachability gate closes this second race too. A blob publication
permit begins **before the content-addressed existence/reuse check**, not only
before the first physical write, and ends only after one transaction installs
every direct digest root (`src/seon/blob.clj:103-116,138-149`). Otherwise a
publisher can observe an old orphan, wait while a sweep deletes it, skip its
write based on the stale observation, and commit a dangling root. The sweep
waits for active publication permits, then excludes new permits through its
last delete.

Internal publishers should wait eventfully for the sweep rather than turn a
successfully computed effect into a false error. Remote work, process output
capture, response streaming, hashing, and temporary-file staging stay outside
the permit. A completed result then acquires immediately before physical
Konserve publication and holds through the root transaction. This requires
splitting the current `put-binary!` staging and publication phases: shell and
web currently publish while consuming child/HTTP streams
(`src/seon/shell/jvm.clj:114-120`; `src/seon/web/jvm.clj:158-171`). An
alternative is a loud retryable refusal, which bounds latency but requires
every caller to retain and retry its computed result. Silent waiting and
correctness timeouts remain forbidden.

### Blob guard options

#### 1. Two-sided publication/sweep permit — recommended

Guarantee: new blobs and reused old orphan keys cannot be swept between write
and direct-root commit. A crash before the transaction leaves an honest orphan
for a later pass; a crash after it leaves a rooted blob. Process death also
stops the only legal collector and drops in-memory permits, matching the guard's
existing crash model (`reference-code/datahike/src/datahike/gc_guard.cljc:32-40`).

The six durable production write expressions
(`src/seon/effect.clj:337`; `src/seon/shell/jvm.clj:120`;
`src/seon/web/jvm.clj:167`; `src/seon/cluster/loop.clj:502,557,977`) collapse
into three logical publication transactions, not six unrelated leaf guards:
effect settlement owns its own result blob plus shell/web content blobs;
terminal evaluation owns result and session-definition blobs; and AI-attempt
settlement owns reasoning. Each completion callback acquires after its remote
or compute result is complete, publishes every staged blob, and commits all
direct roots in the one settlement transaction before releasing.

#### 2. Durable pre-write intent fact

Guarantee only when combined with sweep exclusion. An intent can make a new
digest queryable before physical publication, but an intent committed after
the fixed whitelist is still missed, and an old digest may already be in a
Konserve deletion batch. Crashes also leave intents needing attributed cleanup.
It adds transactions and a second lifecycle without removing the gate, so it
is not recommended.

#### 3. Quiesce the writer

Guarantee only if expanded to stop all blob producers as well as Datahike
transactions. Blob writes bypass the writer, and pausing transactions alone
can strand a physical blob while preventing its root from committing. Full
writer/application quiescence blocks ordinary commits for 5–25 minutes and is
not recommended.

Computing unreachable objects at both sweep-start and sweep-end bases is not
an additional viable option. A reference can commit after the end snapshot but
before a later batch deletes its key. It also cannot discover references
hidden in opaque serialized data. A final exclusion boundary is still
required, making the two snapshots redundant for correctness.

### Direct roots are a prerequisite

The current collector discovers digest attributes by exact equality with the
serialized schema form `:seon.blob/digest`
(`src/seon/cluster/registry.clj:288-317`). That is not semantic schema
discovery and already misses production shapes. Make `:seon.blob/digest` the
one concrete digest shape and have every persisted digest-root attribute
reference it; the collector resolves the schema reference graph rather than
comparing authored EDN. This reverses the current alias direction, where
`:seon.cluster.eval/result-blob` owns the concrete expression and
`:seon.blob/digest` points to it
(`resources/seon/schemas/seon.cluster.eval.edn:55`;
`resources/seon/schemas/seon.blob.edn:1-3`). The accepted digest values do not
change. A direct, queryable digest datom must exist for every blob before its
publication permit releases. A digest nested inside
`:seon.effect/result-edn` is opaque and is not a root
(`src/seon/effect.clj:325-361`). Tests must install the production schemas,
not a hand-authored serialized-form row.

There is a seventh physical writer outside the six durable publication sites:
MCP projection writes an artifact and returns its digest without committing a
datom, yet later retrieval promises that digest
(`src/seon/cluster.clj:280-302,328-350`). Its lifetime needs an owner ruling:

#### 1. Durable MCP artifact fact — recommended

Commit an identified artifact row with a direct **no-history** digest before
returning retrievability. Root maintenance owns explicit retraction of those
rows; no unjustified expiry clock is introduced. No-history is load-bearing:
the current collector queries the history database
(`src/seon/cluster/registry.clj:301-317`), while Datahike retains historical
datoms unless the attribute declares `:db/noHistory`
(`reference-code/datahike/src/datahike/db/transaction.cljc:439-446,538-546`).
This preserves current retrieval and makes retention queryable and
reclaimable, at the cost of one small transaction per stored MCP artifact.

#### 2. No durable MCP retrieval

Do not publish the blob. Return the bounded inline/windowed value plus an
explicit refusal for later retrieval. This is simpler and avoids durable
growth, but gives up the current retrieval promise.

Until that ruling lands, an MCP artifact must not claim durable retrievability
and must not close a publication permit as though a root existed.

## Strengthened falsifier

The retained reconnect falsifier pauses at entry to `konserve.gc/sweep!`, after
Datahike's mark but before Konserve has enumerated or issued its first batch.
That proves the mark/branch race but misses the stronger case where the batch
already contains a node the new branch needs. Konserve turns key enumeration
into batches at `reference-code/konserve/src/konserve/gc.cljc:13-30`; the file
backing begins touching their physical keys at
`reference-code/konserve/src/konserve/filestore.clj:336-349`. Enumeration order
is not stable because the default implementation accumulates keys in a set
(`reference-code/konserve/src/konserve/impl/defaults.cljc:379-415`).

The implementation adds an injected test-only
`batch-issued/before-first-delete` callback carrying the batch's physical
store-key strings. There is no such hook today: the call goes directly from
`reference-code/konserve/src/konserve/impl/defaults.cljc:703-707` to the file
loop at `reference-code/konserve/src/konserve/filestore.clj:336-349`. The
recurring falsifier must:

1. create a scratch store and overwrite the same entity population through at
   least six commits, recording an older commit C and a unique closure key K
   that heads-only mark excludes; append-only data is not a valid fixture;
2. use that callback to inspect issued batches, compare their encoded keys with
   `(key->store-key K)`, and pause **after** a batch containing K is fixed but
   **before** its first existence check/delete. The backing receives encoded
   `.ksv` strings, not logical K
   (`reference-code/konserve/src/konserve/impl/defaults.cljc:46-47,691-707`);
3. while paused, invoke a real branch from C. Under the recommended start/API
   policies, `bin/seon start` refuses immediately and direct `branch!` remains
   pending without reading C; neither publishes a head or roster row;
4. release the batch and finish collection, then let the direct branch request
   continue. Because it was not admitted before the sweep and C was excluded,
   it must refuse honestly with `:commit-not-found` and publish no head or
   roster entry;
5. release all connections and stores, reconnect cold, and read every
   published branch and head; and
6. prove the test exercised reclamation with a nonzero first pass and a zero
   second pass over unchanged state.

The complementary ordering admits the branch before the sweep request, pauses
it before roster publication, starts collection, and proves collection waits;
after the branch publishes, the sweep's whitelist includes it and cold
reconnect reads it. This proves both sides of the gate.

The blob variant prewrites orphan digest D, pauses after a batch containing D
is fixed, attempts to publish identical content and its direct-root transaction,
and proves publication cannot overlap the batch. After release, publication
commits and cold retrieval succeeds. A crash/refusal case proves an uncommitted
blob is later collectable, while a committed direct root survives.

The complementary blob ordering admits publication first, writes D, pauses
before its direct-root transaction, and then queues collection. Collection
must not enumerate or delete while the permit remains open; after the root
transaction commits and releases, collection proceeds and a cold read of D
succeeds. This proves the permit spans the root transaction rather than ending
after `put!`.

## One-time 357 GiB reclaim runbook

The manual and scheduled entry point is one future `seon.operator/collect!`
owner. A proposed `bin/seon collect` command calls that owner in the flock-holding
JVM; it never opens the database from a sidecar. The destructive heads-only
choice is explicit, because it invalidates old commit IDs while retaining every
present branch head and the logical temporal indices already included by
`:keep-history?`.

1. Land the gate, direct blob roots, semantic blob discovery, and both
   batch-contained reconnect falsifiers. Prove them through the recurring
   `bin/test` surface before touching the real store.
2. From the sole operator root, run `bin/seon collect --retain heads --dry-run`.
   The dry run acquires the same exclusive gate and applies the same retention
   policy and mark algorithm without deleting. It does not promise identical
   later batch partitions: enumeration order is set-derived and the execute
   pass recomputes current state. Record branch names and head commit IDs,
   retained/candidate file counts, mark duration, and projected duration.
   Candidate bytes require an explicit FileStore inventory using physical file
   sizes; Konserve key metadata contains only key/type/write time and sweep
   returns keys (`reference-code/konserve/src/konserve/utils.cljc:14-24`;
   `reference-code/konserve/src/konserve/gc.cljc:23-40`). Reconcile against,
   but do not assume, the old 27-branch, 136,800-file, 357.36 GiB estimate.
3. Establish an operational quiet window for legible evidence, not safety.
   Keep the flock-holding JVM up; do not launch a second store opener. Announce
   that `start` will receive a retryable refusal while the sweep runs.
4. Run `bin/seon collect --retain heads --execute`. The owner queues the
   exclusive gate, drains publishers, recomputes the whitelist, and streams one
   bounded progress event per 1,000-key batch. It holds exclusivity until the
   last delete and releases in `finally`; no elapsed-time timeout aborts it.
5. Record a bounded maintenance receipt: starting/ending basis transactions,
   branch/head identities, retention boundary, reachable/candidate/deleted
   counts, whole-store apparent/allocated bytes before and after, batch-count
   progress, duration, result, process, and error if any. The FileStore
   inventory owns candidate byte estimates; generic Konserve GC does not
   fabricate them. Store a detailed deletion manifest out of line if needed.
   Never render the returned set of roughly 136,800 UUIDs as the normal result.
6. With state still quiet, run the identical pass again and require zero
   deletions. Compare measured disk use/free space with the receipt.
7. Use `bin/seon down` so the supervisor closes every connection and releases
   its own flock, then start a new process and cold-open every roster branch.
   Query every current head and every direct referenced blob. Cached warm reads
   do not count. Only this cold proof closes the reclaim.

If the destructive pass fails after part of a batch, Konserve has no rollback:
its file backing deletes sequentially, but the local applied-prefix map is lost
when the operation throws
(`reference-code/konserve/src/konserve/filestore.clj:336-351`;
`reference-code/konserve/src/konserve/impl/defaults.cljc:703-712`). Before
releasing the gate, the operator rechecks the issued batch's physical keys and
records the derived applied members plus the failure; if recheck itself fails,
the receipt says the prefix is unknown. The operator reruns the same idempotent
mark/sweep after diagnosis. It never restores an old whitelist or resumes from
a remembered batch.

## Recurring turn-free maintenance

Recurring collection uses exactly the same `seon.operator/collect!` function
and gate. A root-owned schedule task already names its function as a database
reference (`src/seon/schedule.clj:122-142`). Under ruling R8, a due maintenance
fire invokes the mechanical owner on the schedule proc's `:io` workload,
without committing an agent message or opening a model turn. The current
message-producing `fire-call` (`src/seon/schedule.clj:155-222`) is therefore a
known seam for the ruled generic maintenance executor, not a reason to create
a GC-specific scheduler.

Each fire and collection result is an ordinary durable receipt. Success stays
turn-free; an error commits the receipt and sends one ordinary message to wake
root for judgment. Root's initial forms query a concise green/red projection
of these receipts. The schedule declaration, function fact, receipts, and
render producer make the whole portfolio queryable; no maintenance task hand
list is introduced.

Recurring collection may overlap ordinary prior-head commits. It refuses new
starts and waits for blob publishers exactly as the manual entry point does.
Its second-pass-zero property is asserted only on an unchanged fixture; live
ordinary commits can create honest new garbage between passes.

## Graduation evidence

- Datahike's one store-ID gate covers `branch!`, creating/republishing
  `force-branch!`, explicit-old-parent merge, blob publication, and collection;
  no preemptible or CAS-shaped path survives.
- `bin/seon start` returns a typed retryable refusal with no partial cluster
  during an active or queued sweep.
- The collector computes branch, retained-commit, index, and direct-blob
  reachability only after exclusive acquisition and holds the permit through
  the last physical delete.
- Batch-contained branch and reused-orphan-blob falsifiers pass after a fully
  cold reconnect and their negative controls delete nonzero data.
- Production schemas, not hand-authored rows, prove every durable blob
  publication has a direct queryable root.
- Manual and scheduled collection call the same owner and commit bounded
  receipts; scheduled success opens no run or model turn and failure wakes
  root once.
- The real reclaim records a nonzero first pass, zero unchanged second pass,
  measured bytes reclaimed, and successful cold reads of every branch head and
  referenced blob.

## Output defects and adjacent findings

The current collector returns the full deleted-key set from Konserve
(`reference-code/konserve/src/konserve/gc.cljc:23-40`); on the measured store
that is roughly 136,800 UUIDs. Rendering that set is ugly, noisy output. The
operator and maintenance surfaces should return counts/bytes plus an out-of-line
manifest reference.

The existing cold failure face embeds the entire Konserve store object in a
`:node-not-found` error (`reference-code/datahike/src/datahike/index/persistent_set.cljc:432-444`),
which made the reproducer output cryptic and enormous. That rendering defect is
adjacent to, not part of, the exclusion mechanism.
