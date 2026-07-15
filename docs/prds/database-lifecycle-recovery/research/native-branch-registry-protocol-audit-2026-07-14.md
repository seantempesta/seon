---
type: research
status: completed
tags: [research, database, flow]
---

# Native branch registry and protocol audit

## Decision

Native same-database branches fit the existing runtime without a second
database mechanism, but the cutover must change identity at every boundary in
one unit. A logical `database-name` remains a routing alias. The registry entry
behind it becomes one live connection to one stable
`{database-id, branch}` attachment and exposes its current complete head. Two
logical names may address different branches of the same physical database;
two logical names may never address the same attachment.

Branch creation and deletion belong to the existing JVM writer, registry, and
database protocol. They are supervisor-only protocol operations, not functions
exported through the agent-facing `seon.db` API. The pod still has exactly one
connection. A source pod and a forensic pod are separate processes attached to
separate branches, while one JVM database server owns both connections and all
writes.

The implementation is not ready to start as one blind edit. Three blockers
must be made explicit:

- Datahike can branch from a retained commit UUID, not an arbitrary temporal
  cut inside that commit. A request is branchable only when its coordinate's
  `t` equals the loaded commit's `:max-tx`; other cuts need a maintained
  Datahike branch-at-cut primitive or must fail as typed
  `cut-not-branchable` data.
- `datahike.api/delete-branch!` removes the branch from the durable roster but
  intentionally leaves the branch-head key readable until garbage collection.
  Every Seon open must therefore check `d/branches` before `d/connect`; a
  deleted branch must never be reopened merely because its old key remains.
- The pod has no non-autonomous boot mode. `seon.client/start-runtime!` always
  performs recovery, agent birth/resume, provider and brand synchronization,
  and ticker installation. A forensic branch must not start until one explicit
  read/UI-only boot path gates those effects.

## Dependency ledger

| Dependency or mechanism | Selected identity | Exact source and relevant behavior | Existing Seon use |
|---|---|---|---|
| Datahike | `org.replikativ/datahike` SHA `6f90b339768b1a02066dce3b6fcc93a200758fcc` in root and CLJS override | `reference-code/datahike/src/datahike/versioning.cljc`; `branch!` accepts a branch keyword or retained commit UUID, rejects commit branching when `:commit-graph? false`, branches primary and durable secondary roots from the same selected record, updates the branch head and roster, and returns only after writes complete. `delete-branch!` refuses `:db`, missing branches, and any active connection to the target branch. `branch-as-db` and `commit-as-db` load immutable roots. | `src/seon/db/coordinate.cljc`, `writer.clj`, `registry.clj`, and `replica.cljs` already use Datahike values and the selected commit identity. |
| Datahike connection ownership | same SHA | `reference-code/datahike/src/datahike/store.cljc` defines connection identity as `[store-id branch]`; `connector.cljc` shares identical opens, rejects config mismatch, and drains writer plus secondary handles before final release. Different branches of one store are distinct connections. | `seon.db.registry` currently stores one connection per logical database name but omits the attachment. |
| Datahike versioning tests | same SHA | `reference-code/datahike/test/datahike/test/versioning_test.cljc` proves branch roster, isolated writes, missing commit, commit-graph opt-out, active-connection deletion, and source survival. `stratum_vt_test.clj` proves historical primary and secondary roots match. | These are the dependency acceptance floor; Seon tests must prove routing and protocol behavior above them. |
| Konserve | `org.replikativ/konserve` SHA `df6818d43ea3363a808cd051c0d68917f1b987a9` | `reference-code/konserve/src/konserve/protocols.cljc` and `core.cljc`; per-key update is atomic, while multi-key atomicity is optional. Datahike writes immutable roots before a mutable branch head. A registry lock is not a cross-process storage CAS. | The JVM is the sole writer, so Seon can quiesce one connection and delegate storage mutation to Datahike. Seon must not write Konserve branch keys directly. |
| Canonical coordinate | current first-party source | `src/seon/db/coordinate.cljc`; one closed coordinate and attachment, with `at` naming a cut inside an immutable containing commit. | Protocol v2, replay, replica progress, transaction fences, turns, and errors already carry it. |
| Database protocol | current first-party source, version `2` | `src/seon/db/protocol.cljc`; all requests route by logical database name and complete coordinates cross transactions/replay. | Extend this one discriminated protocol. Do not add an admin envelope or another socket. |
| Registry/backend | current first-party source | `src/seon/db/registry.clj` and `backend.clj`; the registry is `{database-name -> entry}`, backend identity is currently derived from the logical name, and only `:db` is opened. | Strengthen these owners in place. The physical-copy `fork-database!` is the superseded path to delete. |
| Replica | current first-party source | `src/seon/db/replica.cljs`; writer-owned attachment config, branch-qualified progress, full-coordinate replay validation, and atomic attachment-generation replacement already exist. | It is branch-ready once ensure/open supplies the chosen attachment and logical route. |
| Operator | current first-party source | `script/seon/dev/cli.clj`; the current public cluster transition is only `cluster reset`. `bin/seon-server-call` is an arbitrary REPL bridge left from deleted fork/destroy commands. | Add typed branch operations through the normal operator and remove the obsolete arbitrary-expression helper when its remaining archive/eval consumers are retired separately. |

`reference-code/datahike` and `reference-code/konserve` are clean and exactly
match the selected SHAs. ACME inherits the root dependency aliases and must not
override either fork.

## Current source truth

### Registry identity is still wrong for branches

`seon.db.registry` stores only connection, backend, and optional path. The
backend derives the database UUID from the logical database name, so a second
logical name necessarily creates a different physical identity. This directly
prevents two aliases from selecting branches of one store.

`ensure-database!` also creates the database when absent and connects the
default branch. That behavior is correct only for a main attachment. A
non-main attach must be open-only, must use an explicit database UUID and
branch, and must prove the branch is in Datahike's roster before connecting.

### The protocol is lineage-complete but has no lifecycle operations

Protocol version 2 carries full coordinates for transactions and replay, and
the replica rejects cross-branch frames. `ensure-database` still accepts only
logical name, backend, and optional path. It cannot request a pre-existing
attachment. There are no create, release, or delete branch operations.

The lifecycle operations must use the same protocol because transport is
already typed and local. They remain supervisor-only by omitting agent-facing
constructors/wrappers from `src/seon/db.cljs` and by exposing them only through
the operator boundary.

### Replica attachment switching is mostly built

`replica/database-config` already consumes a writer-owned attachment and puts
its branch into the local Datahike config. `attach!` replaces an attachment
generation atomically, disposes the old socket/correlations, and seeds progress
from the new branch's own coordinate. Replay pages require one attachment and
one frozen containing commit.

The missing part is stable routing configuration. `database-name` and the
physical path are derived from `SEON_CLUSTER_DIR`; a branch pod needs an
explicit logical route plus the source database path. These are process launch
inputs, not database facts or a second registry.

### The physical fork is already unreachable from the public operator

`registry/fork-database!`, `fork-verify!`, their schemas, and the file-fork test
still copy a whole Konserve store and mint a new database UUID. The current
Babashka operator exposes only `cluster reset`, so the old `cluster fork` prose
and `bin/seon-server-call` comments describe deleted commands. This is dead
production code, not a compatibility requirement.

## Target registry data

Keep one atom and one entry type:

```clojure
{::registry/database-name :default-forensic-abc
 ::coordinate/attachment
 {::coordinate/database-id #uuid "..."
  ::coordinate/branch :forensic/abc}
 ::coordinate/coordinate
 {::coordinate/database-id #uuid "..."
  ::coordinate/branch :forensic/abc
  ::coordinate/commit-id #uuid "..."
  ::coordinate/t 536870930}
 ::registry/conn <opaque>
 ::registry/backend :file
 ::registry/path "data/clusters/default/db"}

```

The current head is a projection refreshed from `d/db` at response time; it
must not become mutable duplicate truth. It may appear in returned summaries,
but the registry need store only the stable attachment and resources. Under the
registry lock, enforce:

- logical database name maps to exactly one attachment;
- attachment maps to exactly one logical database name;
- backend/path/database UUID agree for every entry sharing a physical store;
- `:db` can be created only through the ordinary main ensure;
- a non-main branch can only be attached when its roster membership exists;
- initialization completes before publishing the entry; and
- failed opening releases all resources and publishes nothing.

An O(n) attachment uniqueness check over the small registry is preferable to a
second reverse registry. If measurement later requires an index, keep both maps
inside the same atom and update them in one pure swap.

## Exact schemas and functions

### `src/seon/db/backend.clj`

Extend the existing config request rather than add a branch backend:

- register/reuse `::coordinate/database-id` and `::coordinate/branch`;
- allow optional explicit database ID and branch;
- default to the existing deterministic ID and `:db` only when absent; and
- include `:branch` in the Datahike config.

`backend-facts` must return attachment plus optional path. A branch alias must
never derive its UUID from that alias.

### `src/seon/db/registry.clj`

Change `::entry` and summaries to include `::coordinate/attachment`. Replace
`create-entry!` with one internal open function whose request states whether
main creation is allowed.

Strengthen existing functions:

- `ensure-database!` accepts optional `::coordinate/attachment`, defaults to
  the deterministic main attachment, enforces the registry bijection, checks
  non-main roster membership, opens the exact config, validates the actual
  attachment, initializes, and publishes.
- `resolve-connection` returns connection, logical name, attachment, and
  current coordinate. Callers stop re-deriving attachment from a name.
- `list-databases` returns logical name, attachment, current coordinate,
  backend, and path.
- `release-database!` releases only that logical attachment and leaves the
  branch plus store durable.
- add `create-branch!` and `delete-branch!` as the sole native lifecycle
  operations described below.

Proposed create request:

```clojure
{::registry/database-name <source-route>
 ::registry/new-database-name <target-route>
 ::registry/source-coordinate <complete exact-commit coordinate>
 ::registry/expected-source-head <complete current head>
 ::registry/new-branch <non-:db keyword>
 ::registry/initialize-connection! <fixed writer initializer>}

```

Proposed success contains target logical name, attachment, and exact head.
Proposed delete request names source route plus target logical name/attachment
and its expected head. Delete returns released/deleted booleans and the source
head, never throws across the protocol.

### `src/seon/db/protocol.cljc`

Add discriminators without changing transports:

- `create-branch-operation`;
- `release-database-operation`; and
- `delete-branch-operation`.

Add closed request/response schemas using existing coordinate and attachment
shapes. Create requires source route, target route, source coordinate, expected
source head, and new branch. Release names the target route and expected
attachment. Delete names a live source route, target route/attachment, and
expected target head. Errors add discoverable discriminators for duplicate
route, duplicate attachment, invalid source coordinate, stale source head,
unbranchable cut, branch exists/missing, main-branch deletion, active branch,
and attachment mismatch.

Increment `current-version` only if these operations alter durable receipt
hash semantics. They do not participate in transaction receipts themselves;
do not add lifecycle receipts to domain transactions.

### `src/seon/db/writer.clj`

Add cases to `handle-request`, routed through the existing registry. The
writer supplies its one composed initializer. It must not expose a second
server or call Konserve directly.

Transaction, replay, and search handlers must consume the richer
`resolve-connection` result and validate that every request coordinate belongs
to that entry's attachment. Published events continue to carry logical route
plus full coordinates.

### `src/seon/db/replica.cljs`

Extend `ensure-database!` to accept optional requested attachment. Use the
writer response as the only identity authority. Add process launch values for
logical route and physical database path instead of deriving both from one
cluster directory basename.

No second replica state is needed. Existing generation replacement,
cross-branch frame rejection, frozen replay container, and correlation disposal
are the target mechanisms. On a stale/non-ancestor response after restore or
branch replacement, detach and reopen; never continue from numeric `t`.

### `src/seon/client.cljs` and operator process configuration

Add one explicit runtime mode value, for example
`:seon.runtime.mode/forensic-read-only`, to the existing boot request/process
configuration. It still opens the selected replica, reconstructs program data,
and starts the web UI. It must omit:

- crash recovery writes;
- root or initial-agent creation;
- agent resume/hosting and wake-trigger installation;
- ticker/schedule/watchdog installation;
- provider and brand synchronization that writes facts; and
- external-effect workers.

Do not implement this as `SEON_NO_AUTO_BOOT`; that skips the whole runtime and
does not provide a historical UI.

Add operator commands only after typed writer operations exist, for example:

```text
bin/seon branch create <source> <new-branch> --at <coordinate-edn> --name <route>
bin/seon branch open <route> --forensic --port <port>
bin/seon branch release <route>
bin/seon branch delete <route>

```

The exact CLI spelling is not architectural. Commands must use structured EDN
through the existing operator/writer boundary and never construct arbitrary
Clojure expressions.

## Creation transition

1. Resolve the source route and capture its current head.
2. Validate requested source coordinate shares the source attachment.
3. Load its containing commit with `d/commit-as-db`; reject missing/GC'd data.
4. Require requested `t == (:max-tx commit-db)`. Returning
   `cut-not-branchable` is correct until Datahike supports materializing an
   arbitrary cut.
5. Under `locking source-connection`, recheck the expected current head. This
   shares the same lock as Seon's transaction path, so accepted writes drain
   before branching and new writes wait.
6. Recheck target route and attachment uniqueness under the registry lock.
7. Call `d/branch! source-connection commit-id new-branch`. Do not call
   Konserve directly.
8. Read back with `d/branch-as-db`; verify commit ID, maximum `t`, database ID,
   and selected branch context.
9. Open a second Datahike connection using the same store UUID/path and the new
   branch. Run the fixed initializer appropriate to writable branches.
10. Verify the connected head and every enabled secondary implementation's
    dependency-owned parity test, then publish the registry entry.
11. Only after the registry response exists may the operator start a pod for
    that route/attachment.

If opening or initialization fails after `d/branch!`, release any target
connection and delete the unadvertised branch after proving no connection is
active. Never leave a half-published registry entry.

## Release and deletion transition

Release is non-destructive: stop the target pod, detach its feed, wait for the
connection writer and secondary handles to drain through `d/release`, then drop
the logical registry entry.

Deletion is explicit and non-main:

1. stop and prove absence of the target pod;
2. verify the target expected head and attachment;
3. release the registry entry completely;
4. resolve another live connection to the same physical database, normally
   the source main route;
5. call `d/delete-branch!`;
6. verify `new-branch` is absent from `d/branches`; and
7. keep all source connections, facts, blobs, and branch heads unchanged.

Datahike deliberately leaves the old head key accessible until GC. Seon's
non-main ensure must consult the roster before connect, making deletion
immediately authoritative at the Seon boundary without inventing storage
deletion.

## One-mechanism cutover and deletion plan

The native implementation and physical fork must not coexist after the unit:

1. Land branch-qualified backend, registry entries, ensure, routing, and tests.
2. Land typed create/release/delete operations and non-autonomous pod mode.
3. Switch forensic/debug hints and operator calls to complete-coordinate native
   branch operations.
4. Delete `registry/fork-database!`, `fork-verify!`, `::fork-database-name`,
   `::at`, `::forked?`, `::basis-t`, physical-fork response schemas, and the
   file-copy fork test in the same refactor.
5. Delete all calls to `datahike.api/fork-database` from Seon. The dependency
   may retain its general API; Seon no longer uses it.
6. Remove stale `cluster fork/destroy` prose. Retain whole-database reset and
   deletion only where the operator still owns an entire main database.
7. Retire `bin/seon-server-call` once the remaining archived readback/eval
   tooling has a typed replacement. It is not acceptable as the new branch
   operator transport.
8. Search for physical cloned database paths and blob-directory copies; remove
   them or transfer them to the later branch-local blob-overlay owner.

Git is the compatibility archive. Do not leave a hidden `fork-database-v2`, a
legacy request branch, or a migration flag.

## Failure matrix

| Failure | Required result | Mutation allowed |
|---|---|---|
| source route missing | typed not-found | none |
| source coordinate wrong database/branch | attachment mismatch | none |
| expected source head stale | stale-coordinate with current head | none |
| containing commit missing or commit graph disabled | missing-commit / unsupported-history | none |
| coordinate `t` is below containing commit maximum | cut-not-branchable | none |
| new branch is `:db` or malformed | protocol error | none |
| target route already exists | duplicate-route | none |
| target attachment already has another route | duplicate-attachment | none |
| Datahike branch roster already contains target | branch-exists | none |
| secondary preflight fails | dependency error; no branch roster/head publication | immutable orphan nodes may remain collectable |
| branch creation succeeds but target connect/init fails | release target, delete unpublished branch, return failure | no registry entry |
| target receives source-branch event | replica rejects frame and reconnects | no cursor advance |
| delete requested for `:db` | main-branch-protected | none |
| target pod/connection remains active | active-branch | none |
| delete removes roster but old head key remains | Seon open rejects absent roster | no source mutation |
| process dies after branch head creation but before registry publication | next create derives roster/head, verifies requested identity, and either adopts exact result or reports branch-exists | no physical copy |
| source advances after planning | recheck rejects stale expected head | none |

## Focused tests

### JVM

- Backend config: two logical names with explicit same UUID/path and different
  branches produce distinct Datahike connection IDs.
- Registry bijection: duplicate route and duplicate attachment reject without
  a second connection; failed initializer publishes nothing.
- Cold attach: non-main branch requires roster membership and validates actual
  UUID/branch; a deleted roster entry cannot reopen its stale head key.
- Native create at head and historical exact commit: source and target initial
  primary datoms match; writes diverge; source survives target deletion.
- Same numeric `t` on source and branch produces distinct complete heads.
- Missing commit, commit-graph opt-out, stale expected source head, non-max
  temporal cut, duplicate branch, secondary preflight failure, and `:db`
  deletion all return typed failures.
- Release waits for accepted writes and secondary handles; deletion fails while
  any target connection remains and passes after release.
- Writer integration routes transaction, replay, and search to two branches of
  the same UUID without cross-delivery.
- Transit preserves branch lifecycle requests/responses exactly.

Run through focused `bin/test-writer` namespaces first, then the full writer
gate.

### CLJS

- Ensure/open uses a requested writer-owned attachment and physical path.
- Two pods/connections with equal `t` but different branches start from their
  own cursor.
- Source frames cannot reach the branch generation; stale callbacks cannot
  mutate either attachment.
- Restore/non-ancestor commit response resets attachment rather than replaying
  numerically.
- Forensic mode starts replica plus web UI but leaves agents unhosted, ticker
  absent, schedules unfired, providers unsynchronized, and database coordinate
  unchanged.

Run focused `seon.db.replica-test`, boot/runtime tests, and browser feed tests,
then the complete pod gate.

### Operator and live default proof

1. Reset and boot default; capture its full head and representative primary and
   enabled-secondary query results.
2. Create a native branch at that exact commit and open a non-autonomous pod on
   a distinct HTTP/process coordinate.
3. Prove source and branch report the same database UUID, different branches,
   and the expected initial commit/`t`.
4. Prove primary plus every enabled secondary query matches at the fork point.
5. Write one isolated fact on each branch; prove neither leaks.
6. Run simultaneous gzip feed/replay, drop/reconnect each feed, and prove each
   cursor remains on its attachment.
7. Stop the forensic pod, release and delete its branch, then prove source
   facts, source head, source feed, and source blobs remain readable.
8. Attempt to reopen the deleted branch and observe roster-based typed failure.
9. Restart the default source config-free and prove no branch operation changed
   main initialization or config facts.

ACME coordination remains downstream of this complete default proof.

## Blockers and decisions for the implementation owner

- Decide whether branch creation intentionally supports only exact commit-head
  coordinates in this PRD. Supporting arbitrary `t` requires a maintained
  Datahike primitive; silently rounding to the containing commit is forbidden.
- Choose the process launch fields for logical route, physical database path,
  attachment, and runtime mode. They must be explicit artifact/operator inputs,
  not ambient database facts.
- Define the exact list of enabled secondary indexes at branch graduation.
  The maintained source proves persistent-set and Stratum behavior; Proximum
  needs its own selected-source branch parity and release proof.
- Define operator authorization for supervisor-only protocol operations before
  exposing them beyond loopback. Do not expose lifecycle constructors in the
  agent home namespace.
- Branch-local blobs are not solved here. Native database deletion must not
  delete a shared source blob directory; writable overlays remain a blocker for
  full forensic-branch graduation.
- The one JVM/multiple-pod supervisor topology is not implemented by the
  current single-stack Babashka operator. Process ownership, ports, readiness,
  and shutdown for a forensic pod must be added without launching a second
  writer against the same store.

Until these are resolved, it is safe to implement branch-qualified registry
identity and cold attachment tests, but not to claim writable forensic branch
graduation.
