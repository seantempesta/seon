---
type: research
status: proposed
tags: [storage, operator, lifecycle]
---

# Store existence authority — 2026-08-04

## Verdict

Datahike and Konserve do not maintain a parent catalog that can answer which
file stores exist. Datahike requires a caller-supplied store configuration for
`database-exists?`, `create-database`, and `delete-database`; its only durable
roster is the Konserve key `:branches` inside the already-addressed store.
Konserve's file backend defines existence as “the supplied path exists” and
stores logical keys as hashed `.ksv` files, with no store manifest or parent
index. `reference-code/datahike/src/datahike/api/specification.cljc:204-277`,
`reference-code/datahike/src/datahike/writing.cljc:608-625`,
`reference-code/datahike/src/datahike/versioning.cljc:207-214`,
`reference-code/konserve/src/konserve/filestore.clj:207-240`,
`reference-code/konserve/src/konserve/impl/defaults.cljc:46-55`

Seon therefore needs one non-database bootstrap authority. The recommendation
is to promote the operator's existing durable, atomic EDN record mechanism
into a claim-first machine catalog outside every managed root. It is not a new
singleton registry file and not a second database: it is the existing
one-record-per-identity mechanism, under the existing operator lifecycle lock,
extended from process records to root, store, and cluster existence records.
The current record writer already performs temp-write, file sync, atomic move,
and parent-directory sync, and the operator already serializes lifecycle
transitions with a kernel file lock. `script/seon/dev/state.clj:28-63`,
`script/seon/fresh_operator.clj:210-220`

The ordering contract is the result: **publish the external root claim before
creating any target directory or child process**. The current implementation
does the reverse: `cluster/start!` creates directories before even reserving
the process-local cluster name, while a new-JVM launch publishes its process
record only after the child is running. `src/seon/cluster.clj:1949-1964`,
`script/seon/fresh_operator.clj:1842-1864`

This is a design report only. It makes no production change.

## Scope and sources read

I read the requested Datahike files end to end before designing the Seon side:

- `reference-code/datahike/src/datahike/store.cljc`;
- `reference-code/datahike/src/datahike/writing.cljc`;
- `reference-code/datahike/src/datahike/writer.cljc`;
- `reference-code/datahike/src/datahike/api.cljc`;
- `reference-code/datahike/src/datahike/api/specification.cljc`;
- `reference-code/datahike/src/datahike/config.cljc`; and
- `reference-code/datahike/src/datahike/versioning.cljc`.

I then read the relevant Konserve implementation end to end at the store,
filestore, storage-layout, default-store, metadata, key-enumeration, and memory
registry seams. I also read the requested Seon store, registry, operator,
launcher, process-record, advertisement, issue, and maintenance-design seams.
The requested path `script/seon/dev/fresh_operator.clj` does not exist; the
maintained launcher is `script/seon/fresh_operator.clj`.

### Dependency ledger

| Boundary | Selected source | Seon seam |
| --- | --- | --- |
| Store addressing and identity | Datahike `c15272730e74fb3f8bba91f6361c268492a99ba7`: `reference-code/datahike/src/datahike/store.cljc:35-71`, `reference-code/datahike/src/datahike/config.cljc:200-248` | `src/seon/cluster/store.clj:121-164` |
| Database creation, existence, and deletion | Datahike `c15272730e74fb3f8bba91f6361c268492a99ba7`: `reference-code/datahike/src/datahike/writing.cljc:603-731` | `src/seon/cluster/store.clj:241-351` |
| Branch roster and branch heads | Datahike `c15272730e74fb3f8bba91f6361c268492a99ba7`: `reference-code/datahike/src/datahike/versioning.cljc:207-321` | `src/seon/cluster/registry.clj:92-111`, `src/seon/cluster/registry.clj:175-284` |
| File-store existence and layout | Konserve `89795ae1b769aafd47adf4168e2393d7b4721bc2`: `reference-code/konserve/src/konserve/store.cljc:136-198`, `reference-code/konserve/src/konserve/filestore.clj:157-240` | `src/seon/cluster/store.clj:132-164` |
| Durable external records | Fresh tree `38f3cb84c2588dc2363d79dbf3fed3078e59d2d0`: `script/seon/dev/state.clj:28-63`, `script/seon/fresh_operator.clj:82-165` | `script/seon/fresh_operator.clj:210-220`, `src/seon/cluster/process.clj:12-49` |
| Maintenance consumer | [Scheduler mining and root maintenance design](docs/prds/sci-execution-runtime/research/scheduler-mining-and-gc-design-2026-08-04.md) | `docs/prds/sci-execution-runtime/research/scheduler-mining-and-gc-design-2026-08-04.md:365-425` |

## What the dependencies already record

### Datahike addresses one known store; it does not discover stores

Datahike's store identity is the caller's Konserve `:id`, and its process-local
connection identity is `[store-id branch]` for the self writer. Its
`physical-store-key` is the supplied store configuration with runtime options
removed; that value exists to share process resources for a known backing
location, not to enumerate locations. `reference-code/datahike/src/datahike/store.cljc:35-71`

`load-config` merges the passed `:store` map into the effective configuration,
and delegates the UUID requirement to Konserve. It does not consult a config
registry or discover a backend path. `reference-code/datahike/src/datahike/config.cljc:200-248`

The public API is generated from `api/specification.cljc`; its database
lifecycle block contains `database-exists?`, `create-database`,
`delete-database`, and `fork-database`, all addressed by configuration. There
is no `list-databases` or equivalent store-enumeration operation in the
complete specification. The only list operation is `branches`, and it requires
an existing connection. `reference-code/datahike/src/datahike/api.cljc:1-40`,
`reference-code/datahike/src/datahike/api/specification.cljc:204-277`,
`reference-code/datahike/src/datahike/api/specification.cljc:912-952`

`database-exists?` first calls Konserve `store-exists?` with the supplied store
configuration, then connects to that same store and tests for its `:db` key.
It answers whether one already-addressed configuration contains a Datahike
database; it cannot answer which configurations should be tried.
`reference-code/datahike/src/datahike/writing.cljc:608-625`

Database creation creates the supplied Konserve store, writes immutable data
and the initial commit, writes the mutable `:db` branch head, and publishes
`:branches #{:db}` last. Database deletion rejects active connections with the
same supplied store ID and delegates deletion of the supplied store to
Konserve. `reference-code/datahike/src/datahike/writing.cljc:630-715`,
`reference-code/datahike/src/datahike/writing.cljc:717-731`

The Datahike writer serializes committed transactions per connection and
delegates database creation/deletion to the writing implementation. It does
not add a store catalog. `reference-code/datahike/src/datahike/writer.cljc:231-299`,
`reference-code/datahike/src/datahike/writer.cljc:350-368`

### Datahike's `:branches` key is the exact in-store roster

`branches` reads the Konserve key `:branches` from the connected store.
Branch creation writes the new branch head first and adds its name to
`:branches` last; branch deletion removes only the name from `:branches`, with
the unreachable data retained until collection. This is an excellent
store-local existence fact and the correct reconciliation source once the
store is known. `reference-code/datahike/src/datahike/versioning.cljc:207-214`,
`reference-code/datahike/src/datahike/versioning.cljc:237-321`

Ordinary commits update an immutable commit record and the mutable head named
by the branch. They do not change the store roster. A database fork enumerates
and copies every Konserve key into a caller-addressed target store, and returns
the effective target configuration containing its new store identity; it still
requires both source and target configurations. `reference-code/datahike/src/datahike/writing.cljc:470-552`,
`reference-code/datahike/src/datahike/versioning.cljc:517-567`,
`reference-code/datahike/src/datahike/versioning.cljc:660-686`

Therefore Datahike already answers “which branches exist in this store?” but
cannot answer “which stores exist under this parent?” The `:branches` roster
survives every cluster branch's own datoms, but it does not survive deletion of
the containing Konserve store. `reference-code/datahike/src/datahike/versioning.cljc:207-214`,
`reference-code/datahike/src/datahike/writing.cljc:717-731`

### Konserve records a store's contents, not a catalog of stores

Konserve requires a UUID `:id` in the caller's store configuration, but its
file backend passes only `path`, storage options, and filesystem into the
filestore. The UUID is not written as a file-store manifest by these seams.
`reference-code/konserve/src/konserve/store.cljc:45-75`,
`reference-code/konserve/src/konserve/store.cljc:271-307`,
`reference-code/konserve/src/konserve/filestore.clj:870-911`

For a file store, `store-exists?` is exactly a path-existence check. Creation
creates that directory, deletion removes its immediate contents and the base,
and key enumeration lists only files inside a supplied directory. There is no
parent-directory scan for stores. `reference-code/konserve/src/konserve/filestore.clj:157-219`,
`reference-code/konserve/src/konserve/filestore.clj:229-240`,
`reference-code/konserve/src/konserve/filestore.clj:287-320`

Each logical key is hashed to a UUID-named `.ksv` file. The blob header records
layout version, serializer, compressor, encryptor, and metadata length; the
per-value metadata records logical key, value representation, and last-write
time. Neither structure is a store identity or a parent catalog.
`reference-code/konserve/src/konserve/impl/defaults.cljc:46-55`,
`reference-code/konserve/src/konserve/impl/storage_layout.cljc:10-47`,
`reference-code/konserve/src/konserve/utils.cljc:14-24`

Konserve can reconstruct the logical keys *inside one opened store* by listing
its `.ksv` files and reading their embedded metadata. That is how Datahike's
fork copies keys; it does not discover sibling store directories.
`reference-code/konserve/src/konserve/impl/defaults.cljc:372-415`,
`reference-code/konserve/src/konserve/core.cljc:690-696`,
`reference-code/datahike/src/datahike/versioning.cljc:669-674`

The memory backend is the one backend with a registry, but it is only a
process-local atom mapping IDs to live memory stores. Creation adds an entry
and deletion removes it; process death loses the entire registry. It cannot be
the machine authority for persistent roots. `reference-code/konserve/src/konserve/memory.cljc:11-18`,
`reference-code/konserve/src/konserve/memory.cljc:175-219`,
`reference-code/konserve/src/konserve/store.cljc:236-266`

## Seon's current non-database records

| Record | Current location and coverage | Gap |
| --- | --- | --- |
| Store path and ID convention | The physical store is `<operator-root>/data/clusters/store`; Seon canonicalizes that path and derives the Konserve UUID from it. `script/seon/fresh_operator.clj:56-88`, `src/seon/cluster/store.clj:132-164` | A convention lets an operator address a store only after it already knows the operator root. It cannot enumerate roots, and a leftover directory does not say whether its creator is alive or whether it is ephemeral. |
| Process records | One EDN file per generation lives under `<operator-root>/data/clusters/processes`; it records generation, pid, start instant, root, log, and dependency-cache path. `script/seon/fresh_operator.clj:82-114`, `script/seon/fresh_operator.clj:144-165` | The record lives inside the root whose cluster data `reset` destroys, and a stopped process record is removed. It neither survives arbitrary root deletion nor records a durable root with no live process. |
| Advertisements | Each cluster writes `<operator-root>/data/clusters/<name>/prepl.edn` containing cluster name, transport, pid, and start instant; stop removes only the matching advertisement. `src/seon/cluster.clj:435-454`, `src/seon/cluster.clj:1979-1997`, `src/seon/cluster.clj:2179-2188` | The advertisement lives inside the protected target, is created after its directory, and is deliberately ephemeral. A stale or missing advertisement is evidence to reconcile, not proof that the root is garbage. |
| Exact process liveness | Seon identifies a process by `(pid, start-instant)` and compares both against `ProcessHandle`; a recycled pid is not live ownership. `src/seon/cluster/process.clj:1-7`, `src/seon/cluster/process.clj:30-49` | Exact identity answers whether a *known* owner is live. It cannot discover which paths that owner owns when no external claim connects them. |
| Lifetime store flock | The store owner holds `<canonical-store>.lock` from before existence/create through release; the in-process `held-flocks` atom prevents the same JVM from accidentally dropping its own OS lock. `src/seon/cluster/store.clj:121-130`, `src/seon/cluster/store.clj:183-238`, `src/seon/cluster/store.clj:270-351` | A flock is a live exclusion fence, not an inventory. The caller must know the lock path, the file carries no creator or durability policy, and an unheld leftover lock file says nothing about store existence. |
| In-JVM holders | `seon.operator.runtime` keeps process-local atoms for running instances, root-store holders, and held flocks; `seon.operator/clusters` derives this JVM's advertisements and store rosters from them. `resources/seon/operator/runtime.clj:1-15`, `src/seon/operator.clj:104-119` | These values disappear with the JVM and intentionally are not in any cluster program graph. They cannot protect a target after owner death. |
| Datahike branch roster | Seon maps a cluster name to `:cluster-<name>` and treats the connected store's branch roster as the store-local existence fact. `src/seon/cluster/registry.clj:92-111` | Reading it requires opening the already-known store. Retiring a branch removes the roster entry while its bytes remain until collection, so leftover bytes are not cluster existence. `src/seon/cluster/registry.clj:253-284` |
| Operator lifecycle lock | `<operator-root>/data/operator/lifecycle.lock` serializes commands under a kernel-owned file lock. `script/seon/fresh_operator.clj:210-220` | It is the right mechanism, but the current location is still selected from the target root and contains no root/store/cluster record. Separate `--root` invocations have separate locks and no shared catalog. |

### What `bin/seon status` currently reconciles

The foreign-process operator combines process records, discovered process
observations, advertisements, live registrations, live branch connections,
and—when safe—the persisted Datahike roster. Its source-observation reducer is
already a useful degradation model. `script/seon/fresh_operator.clj:887-965`,
`script/seon/fresh_operator.clj:996-1030`,
`script/seon/fresh_operator.clj:1150-1208`

The missing input is the root catalog. Advertisement discovery scans only the
selected root's conventional cluster directory, offline roster inspection
opens only the selected root's conventional store, and process reconciliation
filters observations to the selected canonical root. An ephemeral test root
that never passed through the operator is outside this graph.
`script/seon/fresh_operator.clj:501-524`,
`script/seon/fresh_operator.clj:707-722`,
`script/seon/fresh_operator.clj:803-839`

The process-observation fallback recognizes JVMs from command arguments and
derives a root from a system property, working directory, `/proc`, or `lsof`.
That is useful repair evidence for a missing process record, but it is the same
class of heuristic that must never authorize deletion. It cannot recover an
ephemeral/durable policy or the creator of an unclaimed root.
`script/seon/fresh_operator.clj:531-590`,
`script/seon/fresh_operator.clj:612-677`,
`script/seon/fresh_operator.clj:679-705`

The [shared bootstrap drive issue](docs/seon/issues/shared-bootstrap-drive-root-disappears-during-live-experiments.md)
demonstrates the gap rather than a theoretical edge: a disk sweep removed two
live Konserve roots and completed reports, after which their writers faulted
on missing `.new` files. Its acceptance boundary requires a claim outside the
protected directory and refuses deletion when ownership is absent or
ambiguous.
`docs/seon/issues/shared-bootstrap-drive-root-disappears-during-live-experiments.md:12-38`,
`docs/seon/issues/shared-bootstrap-drive-root-disappears-during-live-experiments.md:46-64`

### The two current creation windows

1. In a new JVM, `cluster/start!` creates the cluster and log directories
   before publishing an advertisement or opening the store.
   `src/seon/cluster.clj:1949-1964`, `src/seon/cluster.clj:1979-1997`
2. The parent operator launches that child and only then reads its
   `(pid,start-instant)` and writes the process record. A failure to publish the
   record terminates the child, but there remains a real interval in which the
   child and target directories exist without an external durable claim.
   `script/seon/fresh_operator.clj:1593-1617`,
   `script/seon/fresh_operator.clj:1842-1869`

A direct test or experiment that invokes `cluster/start!` without
`bin/seon` has an even larger gap: it can create directories, advertisements,
the store, and branches without ever creating an operator process record.
`src/seon/cluster.clj:1928-1946`, `src/seon/cluster.clj:1960-1997`

## The proposed one authority

### Location and ownership

Add one closed bootstrap coordinate: the **operator control root**. It is known
before any managed `--root` and is never derived from a managed target. For the
checkout operator, the natural default is the repository root already derived
from the loaded launcher source; its durable records live under
`<repository-root>/data/operator/claims/`. The existing `--root` remains the
managed root and never relocates this authority. `script/seon/fresh_operator.clj:28-36`,
`script/seon/fresh_operator.clj:56-68`

One Seon installation owns one operator control root. If several checkouts
must form one machine-wide administration domain, they must be launched with
the same closed control-root coordinate; silently scanning home directories or
the whole filesystem would recreate heuristic discovery. The selected control
root is therefore the explicit scope of “on this machine,” just as the current
selected operator root is the explicit scope of `status` and `reset`.
`script/seon/fresh_operator.clj:887-915`,
`script/seon/fresh_operator.clj:2520-2558`

Use the existing record shape mechanically:

- one durable EDN record per claim identity, written with
  `seon.dev.state/write-edn!` and removed/tombstoned only through
  `delete-edn!`; `script/seon/dev/state.clj:28-63`
- one control-root lifecycle lock around claim creation, adoption, release,
  destruction, and reconciliation; `script/seon/fresh_operator.clj:210-220`
- exact process records linked by generation, pid, and start instant rather
  than a stored liveness boolean; `script/seon/fresh_operator.clj:100-114`,
  `script/seon/fresh_operator.clj:167-207`
- root, store, and cluster identity expressed by attributes in open EDN maps,
  not a `:type` discriminator; and
- lifecycle facts such as requested, created, released, and destroyed instants,
  from which current existence is derived rather than stored as a mutable
  `alive?` or `exists?` flag.

This is an extension and relocation of the process-record authority, not a
second registry alongside it. Current per-root process records become records
in the control root, advertisements remain per-cluster transport evidence, and
the store flock remains the exclusion fence. `script/seon/fresh_operator.clj:82-165`,
`src/seon/cluster.clj:2230-2254`, `src/seon/cluster/store.clj:270-351`

### Minimum recorded facts

Each root claim must record:

- a stable claim identity and canonical root path;
- the creating operator installation and exact creating process identity;
- an explicit `reap-on-owner-exit` fact, which distinguishes ephemeral from
  durable without inferring policy from path or age;
- an optional parent claim for experiment/report trees;
- the canonical Konserve store path, backend, and store UUID when allocated;
- each declared cluster name and Datahike branch, with requested, published,
  retired, and destroyed lifecycle facts; and
- every exact process generation currently holding the root, plus release
  facts from clean teardown.

These values reuse identities the implementation already has: the root is
canonicalized at argument parsing, the store UUID is a pure function of the
canonical store path, cluster branches derive in one owner, and process
identity is `(pid,start-instant)` plus the operator generation.
`script/seon/fresh_operator.clj:56-68`,
`src/seon/cluster/store.clj:150-164`,
`src/seon/cluster/registry.clj:92-98`,
`script/seon/fresh_operator.clj:100-114`

The authority need not copy branch heads or commits. Datahike already owns
those inside the store; the external record needs only the store configuration
and branch identity needed to locate and reconcile them.
`reference-code/datahike/src/datahike/writing.cljc:489-552`,
`reference-code/datahike/src/datahike/versioning.cljc:207-214`

### Query without opening a database

Reading the control-root records alone answers:

- which managed roots have a create fact without a later destroy fact;
- which Konserve stores were allocated beneath each root;
- which clusters were published without a later retirement/destruction fact;
- which roots are ephemeral because `reap-on-owner-exit` was declared;
- who created each root and which exact process generations currently claim
  it; and
- which claimed processes are live, derived at read time from pid and start
  instant.

The last answer uses the existing exact `ProcessHandle` comparison; no clock,
pid-only probe, command grep, advertisement, or database open is required.
`script/seon/dev/state.clj:11-26`, `src/seon/cluster/process.clj:30-49`

### Claim-first lifecycle

1. Acquire the control-root lifecycle lock and durably publish a root intent
   containing canonical target path, creator identity, parent claim, and
   `reap-on-owner-exit` before making the target directory.
2. Create the target directory and update the same claim with its create fact.
3. Before launching a JVM, publish its generation intent; after launch, attach
   the exact pid and start instant using the existing process-record writer.
4. Before creating a Konserve store, record its configuration; after
   Datahike publishes `:branches`, attach the store-created fact.
5. Before creating a cluster branch, record the cluster/branch intent; after
   `branch!` publishes the roster entry, attach the branch-published fact.
6. On clean stop, await every claimed child, release process ownership, and
   retain the root/store/cluster creation facts. Destruction adds a durable
   destroy fact outside the target before or immediately after removing the
   target.

The Datahike-side publish points are already crash-ordered: database creation
publishes `:branches` last, and branch creation publishes the branch head
before adding the roster entry. The external record therefore needs only
intention-before-effect and confirmation-after-effect; a crash between them is
an explicit “reconcile” state rather than an invisible root.
`reference-code/datahike/src/datahike/writing.cljc:690-715`,
`reference-code/datahike/src/datahike/versioning.cljc:237-291`

No filesystem record can transact atomically with Konserve. The intentional
two-phase ordering accepts a stale intent but forbids an unclaimed target. A
stale intent is safe to reconcile; a live unclaimed target is the failure that
destroyed the experiment.
`docs/seon/issues/shared-bootstrap-drive-root-disappears-during-live-experiments.md:27-38`,
`docs/seon/issues/shared-bootstrap-drive-root-disappears-during-live-experiments.md:46-64`

### Reset and scheduled reaping are different dispositions

`bin/seon reset --force` remains explicit and unconditional for its selected
managed root: stop every exact recorded process, prove none remain, acquire the
store flock, delete every target under `data/clusters` without following
symlinks, then record the store/cluster destruction and republish/refork. It
does not ask whether individual children are garbage.
`script/seon/fresh_operator.clj:2417-2486`,
`script/seon/fresh_operator.clj:2494-2514`,
`script/seon/fresh_operator.clj:2520-2591`

The scheduled reaper is conservative. It may delete only a claim that declares
`reap-on-owner-exit` and whose exact owner is released or dead, after proving
that every claimed child is also released or dead. A durable claim, a live
identity, an unreadable record, a missing owner, or an ambiguous path is a
refusal. This directly satisfies the incident acceptance boundary and the
maintenance design's released/dead rule.
`docs/seon/issues/shared-bootstrap-drive-root-disappears-during-live-experiments.md:52-64`,
`docs/prds/sci-execution-runtime/research/scheduler-mining-and-gc-design-2026-08-04.md:408-425`

Both operations share the same containment and no-follow deletion function.
The difference is authorization: reset gets an explicit `--force` target after
process shutdown; scheduled reaping gets only the claim facts. The current
delete implementation already refuses the operator root itself and paths
outside it, and never follows a symlink during recursion.
`script/seon/fresh_operator.clj:2520-2558`

## Plug into the maintenance design

The [scheduler mining and root maintenance design](docs/prds/sci-execution-runtime/research/scheduler-mining-and-gc-design-2026-08-04.md)
assigns dead-root reaping, process census, footprint observation, log rotation,
and store GC to root, with the same public `seon.operator` functions used by
scheduled work and manual commands. This proposal supplies the missing
bootstrap input to `observe-footprint!`, `reap-dead-roots!`,
`census-processes!`, `cleanup-cluster!`, and `cleanup-root!`.
`docs/prds/sci-execution-runtime/research/scheduler-mining-and-gc-design-2026-08-04.md:26-33`,
`docs/prds/sci-execution-runtime/research/scheduler-mining-and-gc-design-2026-08-04.md:365-385`

The integration is:

1. `seon.operator` remains the one cleanup policy owner and returns flat error
   values through its existing boundary. `src/seon/operator.clj:10-36`
2. A protected runtime owner outside the cluster program graph holds the
   control-root coordinate and performs claim-file I/O, following the existing
   `seon.operator.runtime` placement for process-root custody.
   `resources/seon/operator/runtime.clj:1-15`
3. `script/seon/fresh_operator.clj` reads the claim catalog first, then
   reconciles its existing process records, advertisements, live JVM snapshots,
   and optional offline Datahike roster for each claimed root.
   `script/seon/fresh_operator.clj:887-965`,
   `script/seon/fresh_operator.clj:1150-1208`
4. `reap-dead-roots!` selects only explicitly ephemeral, exact-owner-dead
   claims and calls the one no-follow deletion owner; it records reclaimed and
   refused paths for the maintenance result.
   `docs/prds/sci-execution-runtime/research/scheduler-mining-and-gc-design-2026-08-04.md:258-277`,
   `docs/prds/sci-execution-runtime/research/scheduler-mining-and-gc-design-2026-08-04.md:408-425`
5. `reset!` replaces its script-local recursive deletion with
   `seon.operator/cleanup-root!` as the maintenance design already requires,
   but retains explicit force, exact-process shutdown, and the store-flock
   interval.
   `docs/prds/sci-execution-runtime/research/scheduler-mining-and-gc-design-2026-08-04.md:387-406`

The root agent's database still records maintenance schedules and results.
Those datoms explain when maintenance ran and what it reclaimed; they are not
the bootstrap authority that decides whether a root exists. The claim catalog
must remain readable when every cluster database in the target is absent.
`docs/prds/sci-execution-runtime/research/scheduler-mining-and-gc-design-2026-08-04.md:228-277`

## Reconciliation and self-healing

The current operator's “records plus advertisements plus live observation plus
optional roster” behavior should be extended, not replaced.
`script/seon/fresh_operator.clj:887-965`,
`script/seon/fresh_operator.clj:1150-1208`

| Condition | Required behavior |
| --- | --- |
| Claim says owner is live | Recompute exact liveness from pid and start instant. A recycled pid is dead; no stored `alive?` fact is trusted. `src/seon/cluster/process.clj:30-49` |
| Dead process record, target still present | Keep the root claim. If it is explicitly ephemeral and all child claims are dead/released, the reaper may remove it; if durable, report it as dormant. |
| Claim intent exists, target absent | Record creation failure/absence. A dead ephemeral intent is reapable metadata; a durable intent remains inspectable. No target was ever unclaimed. |
| Claim intent exists, target/branch was created before confirmation | Reconcile the path and, when the store can safely open, Datahike's `:branches` roster; attach the missing confirmation instead of inventing another identity. `src/seon/cluster/store.clj:270-351`, `src/seon/cluster/registry.clj:104-111` |
| Advertisement exists | Validate its exact process identity and use it as transport/readiness evidence. It may repair a process record, but it cannot create root ownership or reap policy. `src/seon/cluster.clj:2230-2254` |
| Process observation finds a matching Seon JVM | Use its explicit generation/root process properties to repair a missing process record. Command/cwd matching alone never creates a root claim or authorizes deletion. `script/seon/fresh_operator.clj:645-705` |
| Unclaimed directory is found | Report it as unknown and refuse scheduled deletion. Adoption must be explicit because creator and ephemeral/durable policy cannot be derived from path, age, or contents. `docs/seon/issues/shared-bootstrap-drive-root-disappears-during-live-experiments.md:46-64` |
| One claim file is unreadable | Status reports the exact file error and destructive scheduled maintenance refuses, matching the current process-record behavior. `script/seon/fresh_operator.clj:116-142`, `script/seon/fresh_operator.clj:2390-2436` |

### If the authority is lost

Loss of the control-root catalog is loss of provenance, not proof that managed
targets are garbage. Scheduled reaping must fail closed. It may report
candidate paths found from an explicitly selected root, advertisements,
process observations, and a safely opened Datahike roster, but it cannot infer
creator or reap policy and therefore cannot delete those candidates.
`script/seon/fresh_operator.clj:501-524`,
`script/seon/fresh_operator.clj:653-722`,
`script/seon/fresh_operator.clj:803-839`

Explicit `bin/seon reset --force ROOT` can still recover because the user names
the target and authorizes unconditional destruction. It must retain exact
process shutdown, the store flock, canonical containment, and no-follow
deletion; after deletion it can create a new control record for the new store
and default cluster, but it must not fabricate historical creator or policy
facts for what was lost. `script/seon/fresh_operator.clj:2390-2591`

The authority itself should use individual atomic records rather than one
large mutable file. A torn or corrupt record then blocks only its named claim,
while the control-root lifecycle lock prevents concurrent lifecycle writes
from racing. This behavior is already provided by `write-edn!` and the
operator lock. `script/seon/dev/state.clj:40-63`,
`script/seon/fresh_operator.clj:210-220`

## Honest trade-offs

- This introduces one closed bootstrap coordinate and makes it a machine-local
  single point of administrative truth. If it is unavailable, scheduled
  deletion stops; that is safer than reconstructing ownership heuristically.
- Claim files and Konserve cannot share a transaction. Intention-before-effect
  creates safe stale intents after crashes, while confirmation-after-effect
  plus roster reconciliation repairs them. It deliberately prefers visible
  ambiguity over an invisible live target.
- Every root/store/cluster creation path, including tests and direct REPL
  calls, must go through claim-first lifecycle ownership. Leaving
  `cluster/start!` able to create an unclaimed root would preserve the original
  failure. `src/seon/cluster.clj:1928-1997`
- Advertisements and flocks remain indispensable but narrower: advertisements
  locate live services, and flocks exclude concurrent store owners. Neither is
  promoted into an existence registry. `src/seon/cluster.clj:2230-2254`,
  `src/seon/cluster/store.clj:270-351`
- A durable tombstone/history increases small-file count. The alternative—
  deleting the only evidence when a target is destroyed—cannot answer who
  created it or distinguish completed destruction from never-recorded loss.
- One control root defines one administrative domain. Multiple independent
  installations on a machine remain independent unless explicitly configured
  to share that bootstrap coordinate; global filesystem scanning is rejected
  because it cannot recover ownership semantics.

## Implementation boundary and falsifiers

No implementation should begin until the owner rules on the operator control
root's closed bootstrap location. Once ruled, the smallest coherent slice is:

1. relocate/generalize process records under the control root;
2. make root claim publication precede directory creation and JVM launch;
3. attach store and branch confirmations at the existing Datahike publish
   points;
4. make `status` enumerate claim records before reconciling current evidence;
5. route scheduled reaping and forced reset through the same cleanup owner;
   and
6. delete heuristic deletion authority—retain process scans only as repair
   evidence.

The design is falsified unless all of these pass:

- a process dies after claim publication but before root creation, and the
  dead intent remains queryable and safely reapable;
- a process dies after root/store/branch creation but before confirmation, and
  reconciliation repairs the same claim;
- a live ephemeral experiment loses its advertisement and remains undeletable
  because its exact owner claim is live;
- a pid is recycled and the old claim derives dead;
- an unclaimed directory is reported and refused, never age-reaped;
- a scheduled reaper removes a dead ephemeral root but never a dormant durable
  root;
- `bin/seon reset --force` deletes every selected cluster/store/log target
  after exact process shutdown regardless of ephemeral policy;
- a symlinked sentinel outside the target survives; and
- listing roots, stores, clusters, ownership, durability policy, and exact
  liveness performs no Datahike connection or database read.

These falsifiers extend the incident's recurring multi-drive acceptance proof
and the maintenance design's manual/scheduled cleanup boundary.
`docs/seon/issues/shared-bootstrap-drive-root-disappears-during-live-experiments.md:52-64`,
`docs/prds/sci-execution-runtime/research/scheduler-mining-and-gc-design-2026-08-04.md:427-449`

## Ugly output

I exercised `bin/seon status`. Its fixed `%-22s` cluster column becomes jagged
for long cluster names: PID text immediately follows names such as
`expected-refusal-log-0804` and `fix-agent-config-fork-0804`, making the table
hard to scan. The fixed-width formatter is at
`script/seon/fresh_operator.clj:2172-2196`.

The same run reported `11/11 clusters alive` and then `roster unreadable`
because a live recorded JVM's prepl was unreachable and the offline reader
correctly refused to contend for its flock. The refusal is safe, but the word
“unreadable” is visually alarm-shaped despite being an expected degraded
source choice; the derivation and rendering are at
`script/seon/fresh_operator.clj:1162-1198` and
`script/seon/fresh_operator.clj:2197-2204`.
