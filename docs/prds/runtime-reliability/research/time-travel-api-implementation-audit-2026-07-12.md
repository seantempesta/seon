---
type: research
status: completed
tags: [research, database, flow, agent]
---

# Time-travel API implementation audit

## Decision

The read-only correction is independent and safe to land now. A writable fork
or restore is not a small `datahike.api` wrapper in the current system. It is a
branch-qualified database attachment plus an external process transition. The
minimum correct implementation is roadmap Phase 9a–9e in order.

Do not expose the current `seon.server.registry/fork-db!` as the canonical API,
and do not add a second restore path around it. It copies a physical store with
`datahike.api/fork-database`; it cannot guarantee a coherent live cut or copy an
external secondary index. It also gives the target a different store identity,
which defeats Datahike's same-store copy-on-write branch model.

The correct primitives already exist upstream:

- `datahike.api/as-of` produces an immutable read value;
- `datahike.api/branch!` creates a durable same-store writable branch;
- `datahike.api/branch-as-db` and `commit-as-db` resolve immutable roots;
- `datahike.api/delete-branch!` releases a retained branch name; and
- `datahike.api/force-branch!` promotes a resolved database root.

Seon must coordinate those primitives, not reproduce their storage behavior.
Config remains an optional, explicitly supplied overlay. Absence of config
means preserve the selected database facts.

## What is safe now

`seon.db/as-of` already returns the ordinary Datahike immutable wrapper accepted
by query, pull, and entity. One reporting bug was independent: Datahike's
`AsOfDB.-max-tx` delegates to the origin head, while `AsOfDB.-time-point` is the
selected view. Reading `-max-tx` therefore mislabeled an old view as current.

The accompanying code change makes both the CLJS `seon.db/basis-t` API and JVM
wire read responses report the selected `AsOfDB` point. It deliberately leaves
`SinceDB` reporting its origin head; the since point is a lower bound, not the
latest state represented by that view. Behavioral tests prove values and
coordinates together.

This is still a lineage-local numeric coordinate. It does not claim that a bare
transaction id identifies a commit across branches.

## Current writable paths to remove

### Physical fork

`src/seon/server/registry.clj` currently owns all of the following as one
physical-copy path:

- `::fork-db!-request` accepts source name, target name, optional integer `at`,
  and a target path;
- `fork-verify!` opens the copied store and scans temporal EAVT;
- `fork-db!` calls `datahike.api/fork-database`, deletes a failed copy, and
  retries once; and
- `delete-db!` deletes the target's whole physical database.

`bin/seon cluster fork` calls that function through the wire-server REPL, then
copies the whole blob directory and boots the new physical cluster. This path
must be deleted in the same commit that the same-store fork transition becomes
usable. Keeping it as an “alternate” would preserve two incompatible meanings
of fork and two destruction protocols.

### No live restore

There is no current operation that safely changes the main `:db` branch root.
`cluster reset` stops processes and wipes storage; it is a fresh-state action,
not restore. Calling `force-branch!` while the current writer, readers, feeds,
or hosts remain live would strand every one of them on a stale root.

### Unqualified attachment state

The current runtime cannot represent main and simulation branches safely:

- `seon.server.store/config-for` derives store identity from logical db-name and
  always relies on Datahike's default `:db` branch;
- `seon.server.registry` stores `{db-name -> conn/backend/path}` without store
  identity or branch;
- agent routing stores `{agent-id -> db-name}`, so the same copied agent ids on
  two branches collide;
- filtered database handles, broadcast subscriptions, feed cursors, and replay
  watermarks are not branch/commit-qualified; and
- the pod derives attachment identity independently from its cluster directory.

A branch wrapper added before fixing these keys could write or render the wrong
lineage even if Datahike itself branched correctly.

## Upstream correctness gates

The pinned Datahike source has a historical-secondary-index bug in `branch!`.
It loads the requested stored commit, but the reduction preferentially branches
the connection's live secondary index. If head H changed after historical T,
the new branch can contain T's primary database plus H's secondary rows.

The upstream-shaped fix belongs in Datahike before Seon permits branching from a
commit id:

- reuse a live secondary index only when `from` is the exact branch attached to
  that connection at its current head;
- for a commit id or another branch, restore/branch every secondary index from
  the selected stored root's `secondary-index-keys`;
- reject missing commit records or effective `:commit-graph? false` before any
  mutation;
- make connection release await writer shutdown; and
- guard `force-branch!` with the expected current commit, then read back the new
  head.

This work must update the pinned dependency and matching `reference-code`
checkout together. A Seon-only special branch function would lose upstream
updates and create the duplicate path this refactor is intended to remove.

## Canonical data and function ownership

### Coordinate facts

Create `seon.db.coordinate` as the single owner of the coordinate schema and
pure projections. Every coordinate is a fully populated map:

```clojure
{:seon.db.coordinate/store-id  #uuid "..."
 :seon.db.coordinate/branch    :db
 :seon.db.coordinate/commit-id #uuid "..."
 :seon.db.coordinate/t         536870913}
```

The four values are facts about one immutable database root. The map stores no
derived state-machine status. Logical `:seon.server.registry/db-name` remains
routing data beside it, not part of database identity.

Only a full coordinate is canonical. `{branch, t}` is a convenience selector
that must walk retained ancestry and resolve to exactly one commit. Zero or
multiple matches return a namespaced error value. Responses and persisted
bookmarks always contain the resolved four facts.

### Database operations

Create one JVM `seon.db.version` namespace under the existing `seon.db` API
boundary. It is the only Seon namespace that calls Datahike branch/commit/force
operations. Its map-in/map-out functions are:

- `current-coordinate` — project the attached branch head;
- `resolve-coordinate` — validate store/branch/commit/t agreement and load the
  immutable root;
- `branch!` — create one named same-store branch from a resolved commit;
- `delete-branch!` — delete only a non-main retained branch; and
- `force-branch!` — guarded main-root promotion after the caller has quiesced
  the attachment.

These functions do database work only. They do not start pods, stop agents,
copy blobs, read config, or record procedural progress.

Keep CLJS `seon.db/as-of` as the immediate read-only value API. Full-coordinate
resolution over retained commit records crosses the writer boundary and uses a
wire read operation backed by `seon.db.version`; the pod must not become a
second branch writer.

### Process transitions

Create one `seon.server.lifecycle` plan/apply service for actions `:fork`,
`:restore`, and `:release`. This namespace owns admission, drain, process
handles, attachment replacement, confirmation, and restart. It delegates every
database mutation to `seon.db.version`.

Planning is read-only. A plan freezes source and target coordinates, overlay
digests, and a confirmation token. Apply rejects a token when the source head no
longer equals the planned head. There is no generic “optimal diff” or replayed
transaction program.

## Exact implementation sequence

### 1. Immutable coordinates and reads

- Add the coordinate schemas and resolution errors.
- Extend wire query/pull/entity responses to echo a full coordinate.
- Store full coordinates for new turn/error capture and view cache keys.
- Migrate an old t-only fact only when the main lineage resolves it uniquely.
- Make reconnect compare attachment plus commit ancestry; a non-ancestor is a
  reset and fresh render, never numeric replay.

Mechanical gates:

- an as-of read causes no transaction and starts no writer;
- two branches may reuse the same `t` while producing different coordinates;
- an ambiguous t-only lookup fails as data; and
- caches never key a frozen view by bare `t`.

### 2. Correct the pinned Datahike primitives

- Land the historical-secondary branch fix in the pinned upstream-shaped code.
- Add primary plus each enabled secondary-index regression at T and H.
- Add commit-graph-disabled and missing-commit preflight tests.
- Add release/drain and stale-force tests.
- Update the dependency SHA and submodule pointer atomically.

No Seon fork/restore operation is enabled before these gates pass.

### 3. Branch-qualified attachments

- Extend `seon.server.store/config-for` to accept explicit store-id and branch;
  keep current derivation only as ordinary-main defaults.
- Replace registry entries with `{coordinate, db-name, backend, path, conn}` and
  enforce a bijection between logical db-name and `[store-id branch]`.
- Key agent membership by `[db-name agent-id]`; agent-only lookup succeeds only
  when unique.
- Qualify filtered handles, subscriptions, feeds, writer correlations, runtime
  advertisements, and host registries.
- Persist one atomic attachment descriptor containing only attachment/launch
  facts. It contains no config policy.
- Give branch blobs a read-only source base and branch-local writable overlay.

Mechanical gates:

- source and branch can both contain `root` and route independently;
- a request/frame with mismatched attachment facts is rejected;
- branch cold attach needs no config file; and
- deleting a branch overlay cannot delete source data.

### 4. Writable fork and release

Fork apply performs these transitions:

1. reject new source admission and drain already accepted writes;
2. recheck the planned source head;
3. call canonical `branch!` at the resolved target commit;
4. verify branch coordinate and primary/secondary query parity;
5. write/fsync/rename the attachment descriptor; and
6. start a non-autonomous branch runtime.

The branch runtime starts no ticker, wake trigger, agent host, schedule,
provider synchronization, or external-effect worker merely because history was
opened.

Release stops and drains that runtime, releases all attachment handles, deletes
the upstream branch name, and removes only its descriptor/blob overlay.

### 5. Quiesced restore and undo

Restore apply performs these transitions:

1. validate the target reconstruction floor and optional frozen overlays;
2. persist one confirmed external lifecycle intent;
3. close admission and fence active actions;
4. drain accepted writes;
5. stop agent hosts, feeds/listeners, readers, and the writer;
6. retain the old head as an undo branch and verify it;
7. materialize only target-referenced branch-overlay blobs;
8. guarded-force the target root onto main;
9. reconnect every main attachment from the promoted root;
10. rebuild safe runtime projections from database facts;
11. optionally apply explicitly supplied current-core/config overlays through
    the exact reconciler;
12. recover eligible runs, confirm the resulting coordinate, clear intent, and
    reopen admission.

Undo plans a normal restore targeting the retained undo coordinate. No database
transition claims to undo email, network, filesystem, timer, Promise, or other
external effects. No arbitrary eval is replayed.

Crash tests kill the supervisor after every numbered boundary. Recovery derives
the next safe action from the durable intent, actual branch heads, connection
absence, and reconciliation facts; it does not persist a checklist cursor.

## Deletion map

Delete, rather than deprecate, these superseded paths when their replacements
land:

- registry `::at`, `::fork-db!-*`, `fork-verify!`, and `fork-db!`;
- `bin/seon cluster fork` physical store and whole-blob copy implementation;
- any branch destruction that calls `delete-database` on a shared store;
- bare `{agent-id -> db-name}` routing;
- t-only historical cache/feed keys and replay decisions; and
- any direct Datahike versioning calls outside `seon.db.version`.

`cluster reset` remains a distinct destructive fresh-state operation. It does
not become an alias for restore.

## Why implementation stops at the read correction in this slice

Enabling a writable API before the upstream secondary-index fix and attachment
qualification would turn a known unsafe mechanism into a supported contract.
Implementing only `branch!` without branch-specific release would also make the
existing `delete-db!` capable of deleting the source store. Implementing only
`force-branch!` would leave active connections writing stale lineage state.

The safe boundary is therefore explicit: land the truthful read-coordinate fix
now, then implement Phase 9a–9e as the ordered replacement above. There is no
temporary fork API and no compatibility alias.
