---
type: research
status: active
tags: [storage, datahike, konserve, gc, correctness, operator]
---

# Store collection correctness: is CAS the answer? — 2026-08-05

## Verdict in two sentences

CAS on branch heads is the wrong tool: the hazard it would catch (a head
advancing mid-pass) is **already correct by construction** in the vendored
Datahike, and the hazard that actually deletes live data — a branch created
from an **older commit** between mark and sweep — is not a head advance at
all, so there is no prior value to compare against. The correct-by-construction
design is a **two-sided latch on one in-process atom** (a single `swap!`, which
is where CAS legitimately belongs): ordinary commits keep running freely under
the existing safe point, while **branch-set mutation and the sweep become
mutually exclusive**, and the `remove-before` cutoff stays as the *retention
policy* it already is — not as a hedge.

The sting: with Datahike's **default** `remove-before` of epoch, the
resurrection hole is closed by accident, because every ancestor commit stays
marked. **The cutoff Seon needs in order to reclaim the 357 GiB is precisely
the thing that opens the hole.** Seon's routine `bin/seon start <cluster>`
forks a branch from a published commit id — it is the exact operation that
triggers it.

## What I read end to end

Per the standing rule, these were read whole, not grepped:

- `reference-code/datahike/src/datahike/gc.cljc` (192 lines)
- `reference-code/datahike/src/datahike/gc_guard.cljc` (114 lines)
- `reference-code/datahike/src/datahike/online_gc.cljc` (252 lines)
- `reference-code/datahike/src/datahike/versioning.cljc` (712 lines)
- `reference-code/datahike/src/datahike/writing.cljc` (883 lines)
- `reference-code/datahike/src/datahike/writer.cljc` (454 lines)
- `reference-code/datahike/src/datahike/api.cljc` (89 lines)
- `reference-code/datahike/src/datahike/store.cljc` (104 lines)
- `reference-code/konserve/src/konserve/gc.cljc` (40 lines)
- `reference-code/konserve/src/konserve/core.cljc` (800 lines)
- `reference-code/konserve/src/konserve/impl/defaults.cljc` (798 lines)
- `src/seon/cluster/store.clj` (412 lines), `src/seon/cluster/registry.clj` (359 lines)
- `docs/prds/sci-execution-runtime/research/disk-burn-forensics-2026-08-04.md`
  and `scheduler-mining-and-gc-design-2026-08-04.md` (both whole)

Of `datahike/api/specification.cljc` (1207 lines) I read the header contract
(1-80) and the complete `gc-storage` entry (1142-1156) plus the helper section
(1178-1208); the intervening 1000 lines are unrelated operation entries.

### Dependency ledger

| Boundary | Pinned revision | Source |
| --- | --- | --- |
| Mark, sweep cutoff, safe point | Datahike `c15272730e74fb3f8bba91f6361c268492a99ba7` (`main`, ahead 1) | `gc.cljc`, `gc_guard.cljc` |
| Branch set, head writes | same | `versioning.cljc`, `writing.cljc` |
| Sweep, write/delete atomicity | Konserve (submodule at `reference-code/konserve`) | `gc.cljc`, `core.cljc`, `impl/defaults.cljc` |
| Our GC owner | fresh tree | `src/seon/cluster/registry.clj:327-359` |
| Our store fence | fresh tree | `src/seon/cluster/store.clj:197-239` |

The GC code is recent and OURS-adjacent: `gc_guard.cljc` arrived with
`83868775 fix(gc): sweep stops at the store's safe point` (#879) and
`85c40aee docs: state the writer model` (#880); the background collector with
`bb348d35` (#868). This is a live upstream area, not settled ancient code.

## 1. What Datahike's collection actually guarantees today

### The operation

`d/gc-storage` is declared at `api/specification.cljc:1142-1156` — arity
`[conn]` or `[conn time-point]`, `:stability :stable`,
`:referentially-transparent? false`, `:impl datahike.writer/gc-storage!`. Its
one documented sentence is: *"Invokes garbage collection on connection's store.
Removes old snapshots before given time point."* (`:1151`). It dispatches
through the writer (`writer.cljc:446-454`) as op `'gc-storage!`
(`writer.cljc:305`), and because that op returns a channel the writer runs it
**in the background, concurrently with transactions**
(`writer.cljc:184-199`) — collection is deliberately *not* serialized against
commits.

### The two bounds, and they are not the same bound

`gc-storage!` (`gc.cljc:83-146`) carries the real contract in its docstring
(`gc.cljc:84-117`), and it is explicit that there are two independent numbers:

- **`remove-before` is a MARK-side POLICY.** It controls how far back the
  commit graph stays reachable. `reachable-in-branch` follows a commit's
  `:datahike/parents` **only while** `in-range?` — the record's
  `updated-at`/`created-at` is newer than `remove-before` (`gc.cljc:41-42`,
  `:72`). It defaults to `Date. 0` = "beginning of time [no erasure]"
  (`gc.cljc:84-87`, `:118`). It makes GC collect **more**.
- **The SAFE POINT is a SWEEP-side SAFETY bound.** `cutoff` is
  `min(now, gc-guard/safe-point)` (`gc.cljc:122-129`) and is handed to
  `konserve.gc/sweep!` (`gc.cljc:146`), which deletes a key only when it is not
  whitelisted **and** its `last-write` is strictly older than that instant
  (`konserve/gc.cljc:13-22`; `last-write` is stamped by `meta-update`,
  `konserve/utils.cljc:23-24`). It makes GC collect **less**.

The docstring states the distinction itself at `gc.cljc:103-106`: *"Note the
safe point is NOT `remove-before`."*

So the answer to "what is `remove-before` really for" is: **policy only, never
safety.** It is the retention promise ("no old commit id or database value is
promised after this run"), which is exactly how the forensics report used it
(`disk-burn-forensics-2026-08-04.md:195-212`).

### What is always retained, unconditionally

`reachable-in-branch` seeds `#{branch head-cid}` before walking
(`gc.cljc:24-27`), then marks the head record's key, schema-meta key, the three
current index trees, the three temporal trees when `:keep-history?`, and
secondary index keys (`gc.cljc:43-71`). `:branches` itself is conj'd at
`gc.cljc:143`. So **every present branch head survives any cutoff** — the
docstring's "The branch heads will always be retained" (`gc.cljc:86`) is
accurate.

### What the safe point actually promises — and where the promise is false

`gc_guard.cljc:1-41` states the invariant: *"the instant before which every
written object is either reachable from a pointer, or garbage."* The mechanism
is a `store-id`-keyed atom of open write sequences (`gc_guard.cljc:51-55`),
opened by `writing!` (`:57-63`), closed by `done!` (`:65-74`), with
`safe-point` returning `now` when nothing is in flight and otherwise the START
of the oldest sequence (`:85-102`). `commit!` holds it (`writing.cljc:447-448`,
released at `:564-565`), as do `branch!` (`versioning.cljc:250-251, 290-291`),
`force-branch!` (`versioning.cljc:358-359, 443-444`), and `create-database`
(`writing.cljc:690-694, 714-715`).

That invariant is **true for objects the sequence WRITES and false for objects
the sequence makes REACHABLE.** Reachability is not monotone: `branch!` reads
an old commit record (`versioning.cljc:268`) and republishes it as a head
(`:287`), thereby making *previously written, currently unreachable* objects
reachable again. The safe point spares only what is written at or after the
sequence start; it says nothing about resurrected ancestors. This is the whole
defect, and it is one sentence in the guard's own docstring that is too strong.

### Scope: one JVM, no head fencing

The docstrings are unusually candid: *"ALL WRITERS FOR A DATABASE RUN IN ONE
JVM — they coordinate in memory, not through the store"* (`gc.cljc:108-117`,
`gc_guard.cljc:36-41`), and cross-process writers "can lose each other's
commits regardless of GC. See issue #878." A second process gets a `:self`
writer and **looks like a writer** — Datahike cannot warn you
(`gc.cljc:113-116`). The only warning emitted is when the writer backend is not
`:self` (`gc.cljc:131-134`), which is exactly backwards for the dangerous case.

`online-gc` is a separate, freed-address collector; it **disables itself
entirely when more than one branch exists** (`online_gc.cljc:143-158`,
`:176-181`, `:196-200`). Seon is always multi-branch, so it is a no-op for us
and is not part of this analysis beyond noting that the reachability collector
is our only option.

## 2. Every race that could delete a live segment

For each: whether existing code prevents it, and how.

### R1 — A concurrent transaction advances a head mid-pass. PREVENTED.

Two independent mechanisms, and they compose:

1. **Write ordering.** `commit!` writes every value the new head references and
   the mutable branch head **LAST** — as an ordered `multi-assoc` batch on
   multi-key backends (`writing.cljc:497-528`, head appended last at `:520`,
   with the reasoning spelled out at `:503-516`), or as an awaited sequence
   otherwise (`writing.cljc:530-552`, the commit record explicitly awaited
   before the head write is *issued*, `:542-549`). Konserve documents the same
   discipline at `konserve/core.cljc:435-475` ("Put the pointer LAST … A torn
   batch can never produce a dangling pointer"). Each individual key write is
   atomic via `-atomic-move` (`konserve/impl/defaults.cljc:112-114`).
   Consequence: **a head visible to the mark always has all its nodes on disk.**
2. **Safe point.** The new nodes are written at or after the guard token opened
   (`writing.cljc:447-448`), so `cutoff` ≤ that instant and the sweep spares
   them (`gc.cljc:122-129`).

Sub-case: the mark reads `head-cid` at `gc.cljc:24` and the head record at
`:31`; a flip in between yields a superset (old cid retained by the seed at
`:27`, new record walked). Harmless.

**This is the case a head CAS would guard, and it needs no guarding.**

### R2 — A branch is CREATED from an older commit between mark and sweep. NOT PREVENTED. This is the defect.

`branch!` holds the guard from before its read (`versioning.cljc:250-251`),
reads the source stored-db (`:268`), writes the new head record (`:287`), and
publishes `:branches` last (`:288-289`). `gc-storage!` captures its `cutoff`
once at the start (`gc.cljc:122-129`) and reads `:branches` once at `:136`. If
the collector has already passed both points, the new branch is invisible to
the mark, and every index node the resurrected commit references that is not
also reachable from a live head was written long before the cutoff — so the
sweep deletes it. The head then names deleted objects.

**Falsified live** (`research/scripts/gc-resurrection-race-2026-08-05.clj`,
own scratch store at `tmp/gc-resurrection-probe/store`, injected at the exact
`konserve.gc/sweep!` seam `gc.cljc:146` calls, so the real `d/branch!` runs
after the mark and before the sweep):

```text
RESURRECTION PROBE
{:source-commit-datoms-before-gc 400,
 :swept 23,
 :branch-during-mark-sweep-window :ok,
 :branches #{:db :resurrected},
 :resurrected-head-present? true,
 :resurrected-readable? 400,          ; <- looks FINE in this process
 :main-head-readable? 400}
:after-reconnect class clojure.lang.ExceptionInfo / Node not found in storage.
```

Two things matter here beyond "it breaks":

- **`branch!` returned `:ok`.** No refusal, no warning; `:branches` lists the
  branch; the head record is present.
- **The damage is invisible in the collecting process and surfaces only after
  reconnect**, because the persistent-set node cache still holds the deleted
  nodes. In Seon's shape that means a `bin/seon start` succeeds, the cluster
  works, and a *later boot* fails with `:node-not-found`. That is the worst
  available failure mode and it is why this cannot be left to "we'll notice".

Control (`research/scripts/gc-head-only-sweep-2026-08-05.clj`): the same
head-only collection with no interleaving sweeps 45 of 83 keys, deletes the old
commit *record*, keeps the head fully readable (2000 datoms), and a subsequent
`d/branch!` from that commit is **correctly refused** with
`:type :commit-not-found` (`versioning.cljc:269-275`). So the only unsafe
window is the mark→sweep interleaving — outside it, the API refuses honestly.

This is not a hypothetical operation for us. `seon.cluster.registry/branch!`
takes `::from` as a **commit UUID** by design (`registry.clj:160-198`),
`ensure-cluster!` forks from `:seon.source/commit-id` (`:200-222`), and
`reset-cluster!` is `retire-branch!` + `ensure-cluster!` (`:224-251`). Every
`bin/seon start <cluster>` is an R2 candidate.

### R3 — A blob is written during the pass. NOT PREVENTED.

`seon.blob/put!` writes the blob with `k/bassoc` (`src/seon/blob.clj:147-148`)
and `put-binary!` via `publish-binary!` (`:109-110`); the datom that references
the digest is transacted **later, by the caller** (six production call sites:
`src/seon/effect.clj:337`, `src/seon/cluster.clj:291`,
`src/seon/web/jvm.clj:167`, `src/seon/shell/jvm.clj:120`,
`src/seon/cluster/loop.clj:502,557,977`). Neither function takes
`gc-guard/writing!`. This is textbook values-then-pointer with **no guard**, so
the safe point does not spare the blob.

Worse on our side: `collect!` computes the blob whitelist *before* invoking the
collection (`registry.clj:343`, `:288-325`), so the vulnerable window is even
wider than the pass — it starts at `referenced-blobs` and ends when the sweep
finishes. A blob written before that read, whose datom commits during the pass,
is deleted while live. Same silent shape as R2: the datom survives, the bytes
do not.

### R4 — Two overlapping collections. PREVENTED for Seon's own; one smell.

`collect!` serializes on `collect-monitor` (`registry.clj:342`). Datahike's
`start-background-gc!` (`gc.cljc:148-192`) is not used anywhere in `src/`.
Overlap would in any case only over-retain (both whitelists are supersets),
never under-retain — so it is not a deletion race.

The smell: `collect!` extends the mark with `with-redefs` on
`konserve.gc/sweep!` (`registry.clj:349-357`). `with-redefs` alters the **root**
binding process-wide, not a thread-local. Any other collection running in this
JVM during that window silently receives *this* store's blob whitelist. Benign
today (over-retention only) and serialized by the monitor, but it is a global
mutation used as a local extension point, and the honest fix is a mark
extension argument rather than a redef. Recorded, not urgent.

### R5 — A fork/refork mid-pass. NOT PREVENTED (it is R2).

`reset-cluster!` (`registry.clj:224-251`) and the `--force` refork path are
`delete-branch!` + `branch!` from a commit id — R2 exactly. Note also that
`retire-branch!` removing a roster entry mid-pass is *safe in the other
direction*: it can only make the mark's whitelist a superset.

`versioning/fork-database` (`:517-686`) copies keys into a **different** store
and documents its own hazard: *"Copying from a store that is being written to
concurrently can tear — quiesce writers or verify the fork's head afterwards"*
(`:548-551`). Out of scope for one-store collection, but the same class.

### R6 — A second JVM on the store. PREVENTED BY US, not by Datahike.

Datahike has no store-level fence and says so (`gc.cljc:110-117`,
`gc_guard.cljc:36-41`, issue #878). Seon supplies it:
`seon.cluster.store/open-store!` acquires a non-blocking exclusive `flock` on
`<canonical-store-dir>.lock` **before** any existence check
(`store.clj:197-223`, `:270-351`), tracks this process's own holdings to work
around fcntl's close-drops-every-lock semantics (`store.clj:183-196`), and
`release-store!` keeps the fence when the Datahike release fails
(`store.clj:353-370`). This is the mechanism standing between us and the 40/40
scar, and it is also what makes an **in-process** latch a complete fence for
everything below.

One residual: `genesis-complete?` and `stored-main-keep-history?` open a bare
`filestore/connect-fs-store` on the same directory (`store.clj:248`, `:254`) —
read-only and inside our own flock, so not a writer, but worth knowing they
exist.

### Summary table

| Race | Prevented? | By what |
| --- | --- | --- |
| R1 head advances mid-pass | yes | write ordering (`writing.cljc:497-552`) + safe point (`gc.cljc:122-129`) |
| R2 branch created from old commit mid-pass | **NO** | — (falsified: `:node-not-found` after reconnect) |
| R3 blob written during pass | **NO** | `seon.blob` takes no guard (`blob.clj:109,148`) |
| R4 overlapping collections | yes (over-retain only) | `collect-monitor` (`registry.clj:342`); `with-redefs` smell |
| R5 fork/refork mid-pass | **NO** | identical to R2 (`registry.clj:224-251`) |
| R6 second JVM | yes, by Seon | process-root `flock` (`store.clj:197-223`) |

## 3. The CAS question, answered

### Is a branch head CAS-comparable? Yes.

A head record carries `[:meta :datahike/commit-id]`, a UUID stamped at
`writing.cljc:479-482`. It is an ordinary value; recording it at mark and
comparing at sweep is mechanically trivial.

### Is there an existing compare-and-set entry point? Only a weak one, and it disclaims itself.

`force-branch!` accepts `:expected-current-commit` (`versioning.cljc:327-335`)
and re-checks it **inside** the `k/update` function that writes the head
(`versioning.cljc:418-430`), then verifies by readback (`:434-441`). That is the
closest thing to a CAS in the tree. Its own docstring is explicit about what it
is not:

> *"The caller must hold exclusive write access to this store before forcing a
> branch. The expected-head check catches stale plans, but konserve does not
> provide a cross-operation compare-and-set against an independent writer."*
> (`versioning.cljc:331-335`)

That is accurate at the Konserve layer. `k/update`/`update-in` are
read-modify-write inside `go-locked` (`konserve/core.cljc:279-318`, lock at
`:295`), i.e. **in-process only**, and the lock registry lives on the store
record (`impl/defaults.cljc:795`) while separate connections to one physical
store hold **different** store instances (`gc_guard.cljc:47-52`). That is
precisely why Datahike needed a second, store-id-keyed serializer for the
roster — `update-branches!` (`versioning.cljc:174-203`) — and why our fork
carries "Serialize branch roster mutations by store" (`registry.clj:32-38`).
`:optimistic-lock-conflict` (`impl/defaults.cljc:298, 355-358`) is only a
*retry hook* for backends that can do conditional writes; nothing in Konserve
raises it. **Konserve has no CAS.**

### Would a head CAS make mark+sweep correct without quiescence? No — twice over.

1. **It solves a non-problem.** R1 is already correct by construction (§2). A
   record-then-verify on heads would either always pass or spuriously abort a
   pass that was never in danger. On a store under continuous write it never
   converges: heads change constantly, so every collection aborts. That is a
   livelock dressed as safety.
2. **It cannot see the actual hazard.** R2 is not a head advance. At mark time
   `:resurrected` did not exist, so there is **no prior value to compare**. A
   verify-unchanged over the recorded heads passes cleanly while the store is
   being corrupted. The probe above is exactly this scenario, and every
   pre-existing head was unchanged throughout it.

### Can a monotonic, CAS'd branch-set generation close the hole? Only by becoming an exclusion.

A generation counter on `:branches` **detects** R2 — but detection happens
*after* the sweep has already deleted. Detect-then-report is not safety; you
would learn that you corrupted the store. To be safe the check has to happen
*before* deletion and *hold* until deletion finishes — at which point it is no
longer a compare-and-set, it is a **latch**. And as a durable store-level
counter it would inherit exactly the cross-process CAS that Konserve does not
provide (`versioning.cljc:331-335`).

### Where CAS *is* the right primitive

One atomic `swap!` on a single in-process atom. Datahike's writer model says
all writers are in one JVM (`gc.cljc:108-117`); Seon's flock **enforces** that
(`store.clj:197-223`). So an in-process atom is a complete fence for this
store, and `gc_guard` already *is* that atom (`gc_guard.cljc:51`). It just
needs a second side.

## 4. Verdict — the design that is correct by construction

### The invariant, in one sentence

> An object older than the sweep cutoff is deleted only if it was unreachable
> at mark time **and** nothing that can make an *older* object reachable
> overlapped the pass — so commits, which only ever add reachability through
> *newly written* objects that the cutoff already spares, run freely, while
> branch-set mutations, which can make an *old* object reachable, are mutually
> exclusive with the sweep on one atomically-swapped in-process latch.

Two hazards, two mechanisms, neither substituting for the other:

| Hazard | Shape | Mechanism |
| --- | --- | --- |
| written, not yet reachable | commits, blob writes | **safe point** — bound the cutoff, never exclude (cheap, no coordination) |
| old and unreachable, about to become reachable | `branch!`, `force-branch!`, refork | **latch** — mutual exclusion with the sweep (rare, so exclusion is free) |

### The exact mechanism

Extend `datahike.gc-guard`'s existing `in-flight` atom
(`gc_guard.cljc:51-55`) from `{store-id {token start}}` to
`{store-id {:writes {token start} :sweeping token-or-nil}}`, and add one side:

1. **`writing!` / `done!` / `safe-point` keep their present meaning** for the
   `:writes` map. `commit!` (`writing.cljc:447-448`) and `create-database`
   (`writing.cljc:690-694`) are unchanged. Commits are never excluded.
2. **New `reachability-extending!`** — a `swap!` that succeeds only when
   `:sweeping` is nil, otherwise parks/retries. `branch!` and `force-branch!`
   take it *in addition to* `writing!`, at exactly the lines where they already
   open the guard (`versioning.cljc:250-251`, `:358-359`) — the scope is
   already correct; only the semantics need strengthening.
3. **New `sweeping!`** — a `swap!` that succeeds only when no
   reachability-extending sequence is open, otherwise **refuses with a value**
   (never a throw; GC is optional work and a refusal is an honest, retriable
   result). `gc-storage!` takes it around **both** the mark and the sweep
   (`gc.cljc:120-146`), releases it in a `finally`.

Both sides are one `swap!` on one atom, so there is no window between "check"
and "hold". A process that dies mid-sequence drops its entry and its objects
are genuine garbage — the same correct-by-construction crash story the guard
already has (`gc_guard.cljc:32-34`).

Cost: `branch!` is milliseconds and collection is minutes, so a cluster start
concurrent with a collection waits. That is acceptable and, unlike a retry
loop, it **converges** — the sweep is finite and the latch is not contended by
commits.

### Why the cutoff stays, stated as the invariant and not as a hedge

`remove-before` does not appear in the safety argument at all. It appears in the
definition of *reachable*: it is the point past which the parent walk stops
(`gc.cljc:41-42, 72`), i.e. **the retention promise we are choosing to make**
— "every present branch head and its indexes, and nothing older." The
forensics report already quantified that choice: 17.08 GiB retained, 357.36 GiB
(95.44%) reclaimed, versus 3.97 GiB (1.06%) at the epoch default
(`disk-burn-forensics-2026-08-04.md:195-198`). Under the latch, a head-only
cutoff is exactly as safe as the epoch default; without the latch it is
strictly less safe, because narrowing the retained ancestry is what makes
resurrection able to reach deleted objects. The cutoff is policy; the latch is
safety; conflating them is the mistake the earlier lane's "conservative cutoff"
option would have made.

### What this replaces

- **No acknowledged quiescence in `core.async.flow`.** The earlier proposal
  (`scheduler-mining-and-gc-design-2026-08-04.md:325-363`) asked every cluster
  graph to stop accepting kicks and publish a completion event, then re-read
  heads and restart the mark if any changed. That excludes the wrong thing
  (commits, which are safe) and does not exclude the right thing (a branch
  creation is not a graph episode — the operator can issue one from `bin/seon`
  while every graph is quiesced). Its step 4 head re-read is the head-CAS idea
  and inherits both flaws from §3. Graph quiescence remains legitimate for the
  *stated* purpose in that document — making before/after evidence stable — but
  it is not, and must not be described as, the safety mechanism.
- **No head compare-and-set, no durable generation counter, no second
  registry.** The one atom Datahike already has grows a second field.

### Exact fork changes (our Datahike is vendored and ours to fix)

| # | File | Change |
| --- | --- | --- |
| 1 | `reference-code/datahike/src/datahike/gc_guard.cljc:51-114` | two-sided latch as above; correct the docstring line at `:1-6` — the safe point covers *written* objects, not *resurrected* ones |
| 2 | `reference-code/datahike/src/datahike/versioning.cljc:250-251, 358-359` | take `reachability-extending!` alongside `writing!` |
| 3 | `reference-code/datahike/src/datahike/gc.cljc:120-146` | take `sweeping!` around mark+sweep; return a refusal value when a resurrection sequence is open; document that `remove-before` narrower than "all ancestry" requires the latch |
| 4 | `reference-code/datahike/src/datahike/index/persistent_set.cljc:439` | ugly-output defect: the `:node-not-found` error data embeds the **entire** `DefaultStore` record — every serializer, handler and lock atom — producing a several-thousand-character log line for a one-UUID fact (seen verbatim in the probe transcript). Report the address and the store **id**. |

**Upstream-delta note.** All four are in files upstream owns and all extend
work upstream did in the last weeks (#868 background collector, #879 safe
point, #880 writer model). The PR framing is: *"the safe point is necessary but
not sufficient — `branch!`/`force-branch!` make previously unreachable objects
reachable, which no write-time cutoff can cover, and a ranged
(`remove-before` ≠ epoch) collection is where that becomes deletion."* File it
with the probe as the reproducer. Until it is upstream, the change lives in our
fork and the fact is recorded here.

### Exact first-party changes

| # | File | Change |
| --- | --- | --- |
| 5 | `src/seon/blob.clj:103-116, 138-149` + its six call sites | R3: the blob write and the transaction that references its digest must be **one** `gc-guard/with-unreferenced-writes` sequence (`gc_guard.cljc:104-114`). This is the *written-not-yet-reachable* hazard, so the guard/cutoff is the right fix — **not** the latch. |
| 6 | `src/seon/cluster/registry.clj:343` | derive the blob whitelist **inside** the guarded mark, not before the call, so the whitelist and the mark share one basis |
| 7 | `src/seon/cluster/registry.clj:349-357` | replace the `with-redefs` root rebinding with a mark-extension argument (R4 smell) |
| 8 | `src/seon/cluster/registry.clj:327-359` | `collect!` already accepts `remove-before`; the operator must actually pass the head-only instant, per `disk-burn-forensics-2026-08-04.md:259-262` |

## 5. The falsifier

One recurring test, `test/seon/cluster/registry_gc_test.clj`, claimed by
`bin/test`. It must FAIL against today's code and pass after changes 1-6.

**Construction.** The interleaving must be deterministic, not raced. Inject at
`konserve.gc/sweep!` — the exact seam `gc.cljc:146` calls after the mark — so
the concurrent operations land provably *between* mark and sweep. (The probe
scripts do this; the test should use the same seam through the registry's
mark-extension argument once change 7 lands, so no `with-redefs` remains.)

```text
GIVEN  a scratch store (own dir, never data/) with :keep-history? false,
       a schema attribute, and >= 6 commits that OVERWRITE the same entities
       — overwrites matter: with append-only inserts every old node stays
       reachable from the head through structural sharing and the test is
       green for the wrong reason (observed: first probe run returned an
       intact 1200 datoms).
  AND  one blob written via seon.blob/put! whose referencing datom is
       transacted only inside the window (below).
  AND  recorded before the pass: every branch head's commit id, the datom
       count readable at each head, an OLD middle commit id C and its datom
       count, and the digest of the blob.

WHEN   a head-only collection runs (remove-before = now) and, between mark
       and sweep, ALL of:
         (a) a concurrent writer commits a new transaction on :db;
         (b) d/branch! creates :resurrected from the old commit C;
         (c) seon.blob/put! writes a new blob and its referencing datom is
             transacted.

THEN   1. every recorded branch head is still present in :branches and its
          commit id is unchanged (or advanced, for :db);
       2. AFTER releasing every connection and RECONNECTING — this is
          load-bearing; the persistent-set node cache masks the corruption
          in the collecting process, which is how the probe first read a
          healthy 400 while the store was already broken — each branch,
          INCLUDING :resurrected, queries without throwing and returns its
          recorded datom count;
       3. every recorded blob digest, and the one written during the window,
          is readable via seon.blob/get and matches its content;
       4. the concurrent commit from (a) is present at the :db head;
       5. a SECOND identical collection sweeps zero;
       6. if the collection instead REFUSES because (b) held the latch, that
          is a pass — assert the refusal is a flat value carrying the
          contending store id, and that a retry after (b) completes succeeds
          and still satisfies 1-5.

ASSERT ALSO (the negative control, so the test cannot pass vacuously):
       with the injection disabled, the same head-only collection sweeps a
       NON-ZERO number of keys and the old commit C's record is gone —
       otherwise the test proves nothing about a cutoff that reclaims.
```

Expected today: clause 2 fails on `:resurrected` with
`{:type :node-not-found}`, and clause 3 fails for the window blob.

Companion unit test for the latch itself (no store needed): `sweeping!`
refuses while `reachability-extending!` is open and vice versa; both are
released by `done!`; a dropped token (simulated abort) does not wedge either
side.

## Ugly output encountered

Reported per the standing order:

1. **`persistent_set.cljc:439` `:node-not-found`** serializes the whole
   `DefaultStore` — every custom read/write handler object, the lock atom, the
   write-hook atom — into one log line. The actionable facts are one UUID and
   one store id. Fix listed as change 4 above.
2. **`registry.clj:349-357`'s `with-redefs`** is not output, but it is the same
   category of surprise: a process-global mutation presented as a local
   extension.

## Files

- This report:
  [gc-correctness-cas-opus-2026-08-05.md](gc-correctness-cas-opus-2026-08-05.md)
- Control probe:
  [scripts/gc-head-only-sweep-2026-08-05.clj](scripts/gc-head-only-sweep-2026-08-05.clj)
- Falsifying probe:
  [scripts/gc-resurrection-race-2026-08-05.clj](scripts/gc-resurrection-race-2026-08-05.clj)

Both probes run with
`clojure -Sdeps '{:paths ["src" "<script dir>"]}' -M:dev -e "(require 'gc-cas-probe)(gc-cas-probe/run)"`
and create/delete only their own directory under `tmp/`. Neither touches
`data/`.
