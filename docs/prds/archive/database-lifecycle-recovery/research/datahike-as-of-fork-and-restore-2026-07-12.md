---
type: research
status: completed
tags: [research, database, agent]
---

# Datahike `as-of`, writable branches, and live restore

## TL;DR

Seon should use exactly the three upstream same-store mechanisms Datahike already
has:

- `as-of` for immutable historical reads;
- `branch!` for a durable, writable, copy-on-write simulation branch; and
- `force-branch!` to promote an exact branch/commit onto `:db`, after creating an
  undo branch and quiescing every live `:db` writer/reader.

There should be no Seon snapshot format, transaction replay restore, physical
store-copy variant, or second restore implementation. Replace the current
`fork-database`-based `cluster fork` path with a branch-bound debug pod and put
all state-changing operations behind one supervisor-only, schema-validated
`seon.server.lifecycle/transition!` map-in/map-out API. `undo` is not another
implementation: it calls the same `:restore` action with the retained undo branch
as its target and creates a new undo point first.

Three findings are load-bearing:

1. A Datahike transaction id is not a globally unique coordinate after a branch
   diverges or `:db` is rewound. The experiments below produced two different
   commits at the same `t`. Operator plans and persisted restore facts therefore
   use `{store-id, branch, commit-id, t}`; `t` alone is only a convenient selector
   that must resolve uniquely before apply.
2. The current Datahike fork has a correctness bug when `branch!` starts from a
   historical commit and a versioned secondary index is live. It branches the
   historical primary DB but prefers the connection's current secondary index.
   The isolated Stratum proof produced one historical primary entity and two
   secondary rows. Same-store historical forks must not ship until the upstream
   `branch!` implementation branches secondary state from the selected stored
   commit.
3. Historical branch/restore requires an effective commit graph and the actual
   retained commit records, not merely Datahike's default value. `branch!` from a
   commit UUID explicitly throws when the store was created with
   `:commit-graph? false` (`versioning.cljc:111-118`). The live default store was
   observed with no literal config key, effective `true`, and a successful
   head-commit round trip, so requiring literal `true` would also be wrong. Plan
   and apply must inspect the connected store and prove every selected commit and
   ancestry edge can be loaded.

Live restore is intentionally more ceremonial than ordinary crash recovery. A
normal pod restart does not move a branch head: root reconstructs small transient
state, marks only a genuinely interrupted `:running` turn as an error, re-arms
hosts, and re-drives the still-open run behind its existing CAS fence. A live
restore changes the root seen by every main-branch participant, so it must close
admission, stop hosts/SSE, drain accepted writes, retain undo and target branches,
release and await every `:db` connection, force the root, reopen from the target's
own durable facts, rebuild runtime projections, and only then reopen work.

Preserving the selected target is the default. A config artifact is not a boot
prerequisite, and neither current core nor current config is silently applied.
The same lifecycle request may explicitly carry validated `:current-core` and/or
`:current-config` overlays; those use the ordinary exact root/boot and root/config
population contracts and are snapshotted into the confirmed intent so crash
recovery never rereads a changed or missing file. A later cold boot likewise uses
the database as-is unless an overlay input is explicitly supplied. No operation
claims to roll back JavaScript objects, timers, Promises, files, sockets, network
calls, emails, or other external effects, and no arbitrary eval source is
replayed.

## Ratified scope: one storage model, three views

| Need | Upstream primitive | Mutable? | Isolation | Runtime behavior |
|---|---|---:|---|---|
| Query/render database state at `T` | `datahike.api/as-of` | No | An immutable DB value over retained temporal indexes | Pass the DB value explicitly to query, pull, entity, or render; start nothing |
| Run a writable simulation from `T` | `datahike.versioning/branch!` from the resolved commit | Yes | A separate branch head/writer with copy-on-write primary and secondary state in the same store | Start a distinct branch-bound pod; source `:db` is untouched |
| Make a known point live | `branch!` for undo/target plus `force-branch!` on `:db` | Yes | Replaces the main branch head; retained branches preserve both sides | Quiesce, force, reconnect, reconstruct target state, apply only explicitly requested overlays, resume |

Same-store branches share a storage, garbage-collection, and physical-failure
domain. That is a property, not a hidden “isolation level.” It satisfies the
stated debugging and simulation requirements while keeping Datahike's structural
sharing. If a future requirement genuinely demands a different physical failure
domain, it needs its own evidence and decision; it must not survive today as a
silent second forking implementation.

The existing `datahike.api/fork-database` remains an experimental upstream API,
but Seon should not call it. It enumerates and copies every Konserve key, warns
that a live source can tear, and does not copy external secondary-index storage.
The current Seon wrapper's retry plus full temporal `:eavt` scan cannot prove
that an external Proximum/Scriptum/Stratum index matches the selected commit.

## Existing code and source map

### Seon already has the read side

| Surface | Exact source | What exists | Gap |
|---|---|---|---|
| Pure temporal DB values | `src/seon/db.cljs:1037-1115` | `history`, `as-of`, `since`, and `basis-t` | `basis-t` claims to mean the latest tx reflected by a view, but delegates to Datahike `-max-tx`; that is not the `AsOfDB` time point |
| Wire query/pull snapshots | `src/seon/server/wire.clj:205-209,280-287,559-565` | Optional `:seon.store.wire/basis-t` wraps the live DB with `d/as-of` | The reply derives basis from the wrapper instead of returning the resolved requested coordinate; the current test checks rows but not the response coordinate |
| Snapshot protocol tests | `test/seon/server/protocol_extensions_test.clj:184-217` | Older query and pull values are correct | Add coordinate assertions and branch/commit-aware tests |
| Frozen agent UI | `src/seon/web/datastar.cljs:648-677` | `?t=` renders a non-live `db/as-of` feed | Retain this as the one historical-render mechanism; do not build a simulator-specific renderer |

Datahike's `AsOfDB` stores `time-point` separately but delegates `-max-tx` to its
origin DB (`reference-code/datahike/src/datahike/db.cljc:539-603`, especially
lines 573 and 580). The isolated experiment observed target `T`, origin head `H`,
correct data at `T`, `-time-point = T`, and `-max-tx = H`. Seon must report the
view coordinate explicitly instead of interpreting origin max-tx as the view.

### Seon already has a physical fork path, and should retire it

| Surface | Exact source | Current behavior | Required disposition |
|---|---|---|---|
| Fork request/response schemas | `src/seon/server/registry.clj:71-89` | Integer `t`, fork name, optional target path | Replace with the unified transition request/response and full resolved coordinate |
| Verification | `src/seon/server/registry.clj:401-422` | Checks target max-tx and forces a full history scan | Delete; it cannot prove a coherent external secondary index or a live-copy cut |
| Physical copy wrapper | `src/seon/server/registry.clj:424-508` | Calls `d/fork-database`, retries a failed copy once | Delete from Seon; do not retain as an isolation option |
| Existing test | `test/seon/server/registry_test.clj:151-190` | Proves target identity differs and one value is queryable | Replace with same-store branch, independent-write, reconnect, secondary parity, feed routing, and safe release proofs |
| CLI | `bin/seon:1292-1370` | Creates a copied store and copies the whole blob directory | Rewrite `cluster fork` to create/register an upstream branch and a branch runtime descriptor; remove whole-store/blob copying |
| Destruction | `bin/seon:1372-1417` and `registry/delete-db!` at `src/seon/server/registry.clj:371-399` | Releases a registry conn and calls `delete-database` | A branch runtime must call `delete-branch!`; calling `delete-database` on its shared store would destroy the source |
| Reset | `bin/seon:1420-1496` | Stops processes and wipes the store | Keep fresh reset distinct; add historical restore through the one transition API |

The current CLI also says lifecycle operations have no confirmation prompt
(`bin/seon:1202-1206`). That is unacceptable for main-root promotion or deletion
of an undo branch.

### The current registry cannot represent a branch pod

`seon.server.registry` is currently an in-memory `{db-name -> entry}` atom. Its
entry has only conn/backend/path, and `create-entry!` derives a new store id from
the logical `db-name` with no branch (`src/seon/server/registry.clj:204-243`).
`ensure-db` accepts only name/backend/path (`src/seon/server/wire.clj:221-240`).
The pod independently derives its store path and id from `SEON_CLUSTER_DIR` and
always attaches the default branch (`src/seon/store/wire.cljs:87-153,
218-242`). A debug pod therefore cannot safely attach a second logical wire name
to a branch in the source store today.

Datahike itself supports exactly that topology. Its connection identity is
`[store-identity branch]` (`reference-code/datahike/src/datahike/connector.cljc:
230-248`), and a non-streaming pod reader rereads its configured branch root on
every dereference (`connector.cljc:69-79`). Source main and simulation branch are
different connection/writer identities in one store.

The registry must separate:

- the canonical logical/store/branch coordinate used by routing;
- the backend/path used to attach that store;
- the live connection.

One valid entry is:

```clojure
{:seon.server.registry/coordinate
 {:seon.db.coordinate/store-id #uuid "...source store identity..."
  :seon.db.coordinate/branch   :seon.simulation/debug-alpha}
 :seon.server.registry/db-name :debug-alpha
 :seon.server.registry/backend :file
 :seon.server.registry/path    "data/clusters/default/store"
 :seon.server.registry/conn    branch-conn}
```

Upgrade the existing `seon.server.store/config-for` function to accept explicit
store id and branch requirements; do not create `branch-config-for`. Its ordinary
defaults remain “derive store id from db-name and use `:db`.” A branch entry
passes the source id/path and its branch. The registry also maintains an inverse
runtime index from `[store-id branch]` to logical db-name and rejects both a
second logical label for that attachment and an idempotent `ensure-db` whose
requested attachment differs from the existing label. Without those guards,
Datahike returns the same JVM connection and Seon's fixed listener keys would
relabel/replace each other's broadcast and reactive listeners.

The wire `ensure-db` response returns the validated routing label plus canonical
coordinate the pod must use. Every later routed request echoes db-name beside its
`{store-id, branch}` attachment projection, and the server rejects any mismatch
before resolving an agent or calling an operation. This lets the CLJS pod stop independently reimplementing
JVM store identity derivation and prevents a stale/misconfigured debug pod from
turning a valid logical label into a main-branch write. For a branch runtime, the
supervisor transition creates and registers the branch before the pod starts;
pod boot looks up and verifies that entry rather than gaining a general ability
to create or point branch roots.

### Duplicate agent ids require branch-qualified routing

A branch intentionally contains the same `:seon.agent/id` values as its source.
The current runtime registry instead stores global `{agent-id -> db-name}` and
resolves `agent-id` before an explicitly supplied db-name
(`src/seon/server/registry.clj:527-609`; `src/seon/server/wire.clj:643-671`). A
debug branch registering `root` would overwrite or cross-route source `root`.

Change the runtime index to attachment-qualified membership
`[db-name agent-id]`. When both coordinate and agent id are present, resolve the
coordinate first and require that membership exists in that exact runtime;
neither field is allowed to override the other. Agent-id-only lookup may succeed
only when it is unique across registered logical DBs; otherwise return a typed
ambiguous error naming the required db-name. Pod writes already carry their
explicit logical db-name (`src/seon/store/wire.cljs:346-364`) and must continue
to do so, now with the rest of the attachment coordinate. Tests must prove that
the same agent id can write concurrently on source and branch without one datom
crossing branches.

### Routing leaks beyond the registry

The coordinate must be reused at every process-global key; fixing only
`!registry` leaves several cross-branch holes:

- `filtered-dbs` in `src/seon/server/wire.clj:195-203,613-641` is a global
  `{integer-handle -> db}` table. `q-filtered` ignores its resolved connection,
  so a handle minted on main can be consumed by a branch request. Store the
  attachment coordinate with the handle, require an exact request match, and
  drop all of that attachment's handles on release/root replacement.
- `!engines` in `src/seon/server/boot.clj:108-119` and in-process broadcast
  subscriptions in `src/seon/server/broadcast.clj:43-70` are keyed only by
  logical string. Key them by the stable coordinate projection and explicitly
  uninstall them on attachment release; a db-name may later be rebound only
  after its old state is gone.
- Raw and reactive broadcast frames carry only db-name plus numeric basis-t
  (`src/seon/server/wire.clj:310-327`; `src/seon/server/boot.clj:168-180`). They
  must carry the resolved commit coordinate. Socket demux checks the stable
  attachment fields; replay/dedup checks commit ancestry, not only `t`.
- The CLJS adapter stores only `db-name` and `last-applied-t`
  (`src/seon/store/wire.cljs:438-461`). Replace the cursor with the last resolved
  coordinate. If its commit is not an ancestor of the current head, return a
  typed reset, discard buffered frames, reread the new head, and repaint. Do not
  add a persisted generation counter.
- MCP runtime advertisements are populated from the basename of
  `SEON_CLUSTER_DIR` before the store is verified
  (`src/seon/client.cljs:251-257`; `src/seon/dev/runtime_id.cljc:61-96`), and
  `bin/mcp-server-cljs:46-51,295-303` repeats the basename rule. Keep the existing
  user-facing `<db-name>/<agent-id>` grammar, but advertise the server-validated
  coordinate after attach and match the qualifier against its logical db-name.
  There is no second agent-id syntax.
- The main Datastar feed groups by `[:agent id]`, `[:agent id :as-of t]`, or
  `[:roster]` (`src/seon/web/datastar.cljs:250-275,648-705,721-737`), while the
  debug SSE registry is `{agent-id -> conns}`
  (`src/seon/web/debug.cljs:45-68`). A pod currently owns one attachment, so its
  URL `/agent/{id}` remains locally unambiguous, but internal feed/view keys must
  prefix the stable coordinate and historical keys must use the resolved commit,
  not `t`. Root replacement closes and empties both registries before reopen.

These are projections of one coordinate, not new identity schemes. Logical
db-name remains the human route, `[store-id branch]` remains Datahike connection
identity, and commit id remains historical identity.

### Blob truth needs a read-only base and branch overlay

Forensic truth is the database plus the blob store, not the database alone
(`docs/seon/architecture/observability.md:12-15,37-74`). A same-store DB branch
with a new cluster directory would otherwise lose historical prompt/reply blobs.
Sharing one writable blob directory is also wrong: branch-only writes would
pollute source lifetime, branch destruction could delete source truth, and source
cleanup could strand branch refs.

Keep the one `my.blob` API, but upgrade its one-directory implementation
(`src/my/blob.cljs:121-145,204-265`) to a storage view:

- writes go to a branch-local content-addressed overlay;
- reads search the branch overlay and then the source blob directory as a
  read-only base;
- identical hashes are identical content, so fallback needs no remapping;
- branch destruction removes only its overlay after the branch is inaccessible;
- blob GC marks references across every retained DB branch before deleting base
  or overlay content; and
- before promoting a divergent branch to `:db`, the lifecycle copies/reflinks
  only target-referenced overlay blobs into the main base and verifies every
  target blob ref resolves. It never copies the whole blob tree.

Materializing immutable content before promotion is not “rolling back files.” An
undo may leave now-unreferenced content-addressed blobs, which later mark/sweep
can reclaim. Database restore still makes no claim about arbitrary filesystem or
external effects.

### Upstream Datahike and Konserve primitives

| Primitive | Exact source | Relevant property |
|---|---|---|
| Branch enumeration/history | `reference-code/datahike/src/datahike/versioning.cljc:68-96` | Branch names and reachable commits are durable store metadata |
| CoW branch creation | `versioning.cljc:98-144` | Copies a selected stored DB root and invokes native secondary-index branching |
| Branch deletion | `versioning.cljc:146-163` | Removes the name; data remains until GC and readers must release |
| Reset-hard | `versioning.cljc:165-215` | `force-branch!` writes a DB value to a branch and explicitly warns that existing connections are stale and must reconnect |
| Exact DB lookup | `versioning.cljc:217-253` | Commit and branch heads load as immutable DB values |
| Commit-graph opt-out | `versioning.cljc:111-118`; `writing.cljc:450-482`; `connector.cljc:171-219` | `:commit-graph? false` omits immutable commit records, is store-fixed/adopted on reconnect, and makes branch-from-commit unavailable |
| Physical fork | `versioning.cljc:271-440` | Experimental all-key copy; lines 301-305 document live-tear and external-secondary-index caveats; lines 423-437 show the copy loop and target force |
| Connection identity/release | `reference-code/datahike/src/datahike/connector.cljc:230-248,344-372` | Connections are keyed by store plus branch; release shuts the writer but currently ignores its completion value |
| Writer serialization | `reference-code/datahike/src/datahike/writer.cljc:15-34,43-168` | One queue/commit loop per branch connection; shutdown returns a completion channel |
| Commit write order | `reference-code/datahike/src/datahike/writing.cljc:391-498` | Immutable nodes/schema/commit precede the mutable branch-head write on non-multi-key stores |
| Branch-aware GC | `reference-code/datahike/src/datahike/gc.cljc:21-112` | Every enumerated branch head is a reachability root |
| Secondary versioning protocol | `reference-code/datahike/src/datahike/index/secondary.cljc:84-113` | Durable secondary state has flush, restore, branch, and mark operations |
| Proximum CoW | `reference-code/datahike/src-secondary/datahike/index/secondary/proximum.clj:121-187,307-320` | Live branch uses Proximum native branch; stored key-map path loads the chosen commit first |
| Stratum CoW | `reference-code/datahike/src-secondary/datahike/index/secondary/stratum.clj:545-574,1093-1102` | Live path forks its current dataset; stored key-map path loads the chosen dataset commit |

The integrated source pair is now pinned and vendored exactly:

- Datahike `30a154838ffcc270d32d03bfca7f6518c9ad9faf` in
  `reference-code/datahike`, including upstream through `0.8.1729` plus the
  branch/secondary, connection-lifecycle, force-branch, and fatal-writer fixes;
- Konserve `df6818d43ea3363a808cd051c0d68917f1b987a9` in
  `reference-code/konserve`, based on upstream `0.9.356` and retaining the
  shared CLJ/CLJS legacy one-byte metadata-header reader; and
- Konserve-sync `0.1.35` through Datahike's Kabel dependency, carrying ordered
  handshake walks and `:always-send-mutable?` so a branch head is never
  timestamp-deduplicated away.

Konserve publishes its real `0.9.356-seon.1` classpath resource, so Seon's fake
consumer version shim is deleted. A byte audit of the live default store found
6,280 Konserve blobs and zero legacy-header patterns; compatibility remains for
older stores and backups rather than being inferred unnecessary from one store.

The final fork gates were green: Datahike JVM lifecycle/Kabel/writer and
versioning/secondary/Stratum/GC suites, full Kabel integration, 102 CLJS tests
with 814 assertions, Java compilation, and idempotent generated source. An
independent combined versioning/lifecycle run added 135 tests and 681
assertions. Konserve's complete JVM and Node suites passed at 77 tests/1,293
assertions and 46 tests/331 assertions respectively.

Konserve's file store provides atomicity per key, not across several keys:

- `konserve.core/assoc` is a top-level key overwrite
  (`reference-code/konserve/src/konserve/core.cljc`);
- the default writer writes a `.new` blob, optionally syncs it, atomically moves
  it, then syncs the store
  (`reference-code/konserve/src/konserve/impl/defaults.cljc`);
- the file backend uses NIO `ATOMIC_MOVE`
  (`reference-code/konserve/src/konserve/filestore.clj`);
- multi-key operations exist only when the backing implements both multi-write
  and multi-read (`reference-code/konserve/src/konserve/impl/defaults.cljc`),
  which the file
  backing does not; and
- the backend guide promises ACID per key-value pair
  (`doc/backend.org:55-70`).

Consequently Datahike's immutable-first, branch-head-last order makes each visible
head point to complete immutable content, but `:branches`, commit records, and a
branch head are not one atomic multi-key transaction. The lifecycle must be able
to derive and repair a partially prepared transition; it cannot pretend the
whole ceremony is one storage transaction.

## Critical upstream fixes before implementation

### Add a commit-graph and retention preflight

Seon's `src/seon/server/store.clj:117-148` enables history but does not state
`:commit-graph?`; that normally means effective true. It is unsafe to infer an
arbitrary existing store's capability from this constructor, because the pinned
Datahike fork treats commit-graph layout as store-fixed and adopts the stored
value on reconnect (`connector.cljc:171-219`). The upstream regression test at
`reference-code/datahike/test/datahike/test/commit_graph_test.clj:73-114` proves
that branch-from-branch still works when false, branch-from-commit fails, and a
later explicit `true` conflicts with the stored layout.

Plan, then apply again behind the write gate, must prove all of the following:

- the connected DB has `:keep-history? true`;
- its effective `(get (:config db) :commit-graph? true)` is true;
- `commit-as-db` round-trips the current head, selected target, and every parent
  traversed while resolving the selector;
- each loaded DB reports the expected store id, commit id, and retained native
  schema/secondary key maps; and
- the target is reachable from the selected branch ancestry (or is the exact
  head of an explicitly named retained branch), with no missing parent record.

The live default store was read through the running wire-server during this
audit: its literal config value was absent, its effective value was true,
`:keep-history?` was true, branch was `:db`, and its current commit UUID loaded
back through `commit-as-db`. This is why the proof must test effective behavior:
literal-key presence is neither necessary nor sufficient.

A store with effective false or a hole in the required ancestry fails planning
with a typed capability/retention error before intent or maintenance. Do not
flip the flag with `:allow-unsafe-config`: future commits cannot reconstruct the
missing immutable records, and a partial ancestry would make an older restore
look safer than it is. Read-only `as-of` may remain available when temporal
history exists, but commit-addressed writable fork/restore is unavailable until
the data is deliberately migrated to a newly proven store.

### Fix historical secondary branching

Current `branch!` loads the selected historical `stored-db`, but when the source
connection has live secondary indices it prefers those indices
(`versioning.cljc:111-143`). For a commit UUID it sets `from-branch` to `:db` and
calls `-sec-branch` on the current live index. Stratum and Proximum ignore the
historical stored key-map on that path and fork their live dataset/index.

Fix `branch!` in place:

- use live indices only when `from` is exactly the connection's current branch;
- for a commit UUID or any other branch, invoke `sec/branch-from-key-map` on the
  `secondary-index-keys` stored in the selected DB;
- verify the new branch's primary commit coordinate and each secondary key-map
  derive from that same selected commit; and
- add regression tests for commit UUID and non-current branch sources across
  Stratum and Proximum, plus Scriptum when present.

This is not an optional optimization. Until it lands, a writable historical
simulation can answer a primary Datalog query at `T` and a secondary query from
the future.

### Make release completion awaitable

`datahike.connector/release` calls `w/shutdown` but discards the channel that
resolves after the writer exits (`connector.cljc:344-372`; `writer.cljc:15-34`).
Upgrade the existing release path so synchronous JVM release waits for writer
completion and async release returns an awaitable completion. Seon's root switch
must not infer that a returned `release` call drained work when the library has
not promised it.

### Add an expected-head guard to `force-branch!`

The current operation is unconditionally reset-hard. Extend its existing options
with an expected current commit id and make the final per-key branch-head update
fail if the head is no longer that commit. The Seon gate/drain is still required,
but this closes the last plan/apply race at the storage boundary. Return or allow
an immediate read-back of the newly forced commit coordinate instead of only
`nil`.

These are improvements to upstream primitives, not a Seon root format or another
versioning layer.

## Isolated experiments

All experiments ran with `clojure -M:fork-deps` or
`clojure -M:simd:fork-deps:test`, explicit disposable stores under `/tmp`, and
explicit non-production UUIDs. All created paths were removed. No default or
ACME store was opened.

### `as-of` data is right while max-tx is the origin head

The source held `alpha` at `T = 536870914` and changed it to `beta` at
`H = 536870915`:

```clojure
{:experiment :as-of
 :target-t 536870914
 :head-t 536870915
 :as-of-time-point 536870914
 :as-of-reported-max-tx 536870915
 :as-of-value "alpha"}
```

This proves the read behavior and the reporting trap independently.

### Same-store branch is writable, durable, and write-isolated

The branch started at `alpha`, main advanced to `beta`, and one branch-only value
was transacted. Main and fork both then displayed `t = 536870915`, despite being
different commits and states:

```clojure
{:experiment :same-store-writable-fork
 :branches #{:db :seon.simulation/proof}
 :main-t 536870915
 :fork-t 536870915
 :main-values #{"beta"}
 :fork-values #{"alpha" "same-store-only"}}

{:experiment :same-store-fork-reopen
 :fork-t 536870915
 :fork-values #{"alpha" "same-store-only"}}
```

The reconnect proof matters: this is a durable branch, not an in-memory `db/with`
value. The equal transaction ids prove why branch plus commit is required.

### Historical branch currently mixes primary and secondary time

A Stratum index was current with `alpha` and `beta`; the selected historical
commit contained only `alpha`. `branch!` from that commit produced:

```clojure
{:experiment :historical-secondary-branch
 :target-t 536870914
 :main-primary 2
 :main-secondary 2
 :fork-primary 1
 :fork-secondary 2
 :fork-secondary-rows [{:eid 100 :name "alpha"}
                        {:eid 101 :name "beta"}]}
```

That is a direct falsification of historical fork correctness in the pinned
Datahike commit and is the gating upstream fix above.

### A stale main connection can undo a root reset

After `force-branch!` moved stored `:db` from `beta` back to `alpha`, the still
live main connection saw `beta` while a fresh root read saw `alpha`. A transaction
through that stale writer then moved the stored root back to its old lineage and
added the late datom:

```clojure
{:experiment :stale-after-force
 :stale-connection-value "beta"
 :fresh-root-value "alpha"}

{:experiment :late-write-clobber
 :fresh-root-value "beta"
 :late-write-visible? true
 :fresh-root-t 536870916}
```

Stopping the agent loop without releasing the Datahike connection is therefore
not sufficient.

### Quiesced force and undo work with upstream branches

The corrected experiment created undo and target branches, released the main
connection, forced `:db` from the target branch, and reopened:

```clojure
{:experiment :quiesced-restore
 :value "alpha"
 :target-t 536870914
 :root-t 536870914
 :target-commit #uuid "6a539ce3-a125-561f-95e2-7dd77ea1d9be"
 :root-commit #uuid "6a539ce3-49d5-583b-afb0-ac0a91a08980"
 :root-parents #{:seon.restore/target-proof}}
```

`force-branch!` correctly created a new main commit identity for the same target
data and linked it to the target branch. A completion fact then used
`t = 536870915`, the same transaction id the pre-restore `beta` commit had used
on the other lineage. Forcing from the undo branch restored `beta`, and the
completion fact disappeared with the abandoned lineage:

```clojure
{:experiment :quiesced-undo
 :value "beta"
 :root-t 536870915
 :expected-original-head-t 536870915
 :restore-fact-visible? false
 :root-parents #{:seon.restore/undo-proof}}
```

This proves both upstream reset/undo mechanics and the need for a commit-aware
coordinate.

### Physical fork works only as an unnecessary fourth mechanism

A quiescent `fork-database` experiment produced a distinct store id and isolated
writes, matching the upstream test at
`reference-code/datahike/test/datahike/test/versioning_test.cljc:60-163`. That
does not answer a requirement same-store branches fail to answer. Its live-copy,
external-index, registry, blob-copy, and deletion complications are reasons to
remove the Seon call path, not reasons to preserve a second mode.

## Canonical coordinate and selector rules

Use one database coordinate map at registry, wire, feed, UI, plan, intent, and
result boundaries. The recognizable logical db-name is its routing label, not
part of database identity:

```clojure
{:seon.db.coordinate/store-id  #uuid "..."
 :seon.db.coordinate/branch    :db
 :seon.db.coordinate/commit-id #uuid "..."
 :seon.db.coordinate/t         536870914}
```

`{store-id, branch}` is the stable attachment projection; adding the all-or-none
`{commit-id, t}` pair makes a resolved point. Register one schema with a predicate
enforcing that pair; do not define parallel “branch coordinate,” “feed
coordinate,” and “restore coordinate” maps. A routing envelope carries
`:seon.server.registry/db-name` beside this map. Pure consumers project the same
map according to their job:

| Consumer | Projection | Rule |
|---|---|---|
| Human/MCP route | routing `db-name` plus agent id | db-name is the recognizable logical label; bare agent id must be globally unambiguous |
| Datahike connection | `[store-id branch]` | Matches Datahike's actual connection identity |
| Registry uniqueness | route db-name and `[store-id branch]` | Each maps one-to-one while attached |
| Live wire/feed | route db-name plus attachment projection; each event carries all four | Reject attachment mismatch; dedupe/continue only along commit ancestry |
| Historical request/response/bookmark/cache | all four coordinate fields | Commit id is canonical; t is a display/selector aid |
| Local UI grouping | route db-name plus attachment projection and view key | Historical view key adds resolved commit id, never bare t |

Rules:

- `commit-id` is canonical; `t` is a display/query aid.
- A selector may provide `{branch, t}` for convenience. The plan walks only that
  branch's retained ancestry and must find exactly one matching commit. Zero or
  several matches is an error requiring a commit id.
- A selector may provide an existing simulation/undo branch; planning freezes
  its current commit id. Later writes to that branch make the apply token stale.
- A Date selector resolves to a concrete commit during plan and never remains a
  wall-clock predicate during apply.
- Source and target must carry the same store id. Cross-store promotion is not a
  supported action.
- A registry or wire request never trusts `db-name` to imply storage identity.
  It compares the supplied `{store-id, branch}` with the registered projection.
- A replay cursor is the last full resolved coordinate. If its attachment
  differs or its commit is not an ancestor of the current head, the only valid
  response is reset/full reread; numeric `since-t` replay is forbidden there.
- Every response and confirmation renders both `t` and an abbreviated commit;
  neither the CLI nor a user-facing API calls `t` globally unique.

The wire read API may continue accepting a plain basis-t for an immutable view,
but the server resolves it on the requested branch and its response echoes the
full view coordinate rather than calling `basis-t-of` on `AsOfDB`. A client that
wants a durable bookmark should use the returned commit id. Mechanical tests for
every projection appear below; no layer may re-derive store identity from a path
or logical name.

### Turn capture and bookmark migration

New turn capture needs a durable commit id in addition to legacy
`:seon.agent.turn/rendered-as-of`. Today `run-turn!` records only
`(db/basis-t db)` (`src/seon/agent/turn.cljs:74-80,564-609`), and that helper is
`dbi/-max-tx` (`src/seon/db.cljs:1096-1115`). Datahike's `AsOfDB` delegates
`-max-tx` to its origin while retaining the requested time point separately
(`reference-code/datahike/src/datahike/db.cljc:539-603`). The isolated proof
therefore rendered target `T` but reported origin head `H`. Even a correct `T`
is not unique after a branch/reset because different commits reuse it.
The running default store showed the same shape at adjacent commits: head t
`536871006`, selected/view time point `536871005`, view `-max-tx 536871006`, and
`datahike.versioning/commit-id` on the `AsOfDB` returned nil. Commit resolution
must therefore walk the retained graph; it cannot be read from the wrapper.

Persist the resolved coordinate on every new turn as queryable scalars:

```clojure
{:seon.agent.turn/rendered-store-id  store-id
 :seon.agent.turn/rendered-branch    branch
 :seon.agent.turn/rendered-commit-id commit-id
 :seon.agent.turn/rendered-as-of     t}
```

The existing t attr remains useful for ordered/range queries and backward
compatibility; the other three complete the canonical coordinate. Capture all
four from the one frozen DB value plus its already verified attachment before
opening the turn. For a live base DB, read its commit metadata. For an
`AsOfDB`/t request, resolve t through the named branch's retained commit graph
and fail on zero/multiple commits rather than copying the origin commit. Upgrade
the existing `seon.db/basis-t` contract to return the view time point for an
`AsOfDB`; do not add a competing basis helper.

Migration is bounded and honest:

- before enabling root reset, query turns missing the new attrs and walk the
  then-current branch ancestry for their stored t;
- backfill only a unique match, in one root/boot migration transaction with the
  normal user/process provenance;
- leave zero/multiple matches unmodified and return a typed ambiguous-coordinate
  result from structured re-render/fork helpers; the prompt blob remains the
  byte ground truth; and
- never infer an abandoned lineage from the turn creation transaction—the prompt
  was rendered before that transaction, which is why the coordinate exists.

Historical HTTP parameters may accept legacy `?t=` as a selector, but responses
canonicalize to all four fields and durable bookmarks/cache keys use commit id.
The same ambiguity rule applies to legacy `:seon.error/at`; a fork hint cannot
silently pick the first commit whose `t` matches.

## One state-changing lifecycle API

Keep pure `seon.db/as-of` separate because it changes no state. Every branch,
restore, and release mutation goes through one supervisor-only public function:

```clojure
(seon.server.lifecycle/transition!
  {:seon.server.lifecycle/action     :restore
   :seon.server.lifecycle/mode       :plan
   :seon.server.lifecycle/db-name    :default
   :seon.server.lifecycle/target
   {:seon.db.coordinate/branch :seon.simulation/debug-alpha
    :seon.db.coordinate/commit-id #uuid "..."}})
```

The one request schema contains:

```clojure
[:map
 [:seon.server.lifecycle/action
  [:enum :fork :restore :release]]
 [:seon.server.lifecycle/mode
  [:enum :plan :apply]]
 [:seon.server.lifecycle/db-name :seon.server.registry/db-name]
 [:seon.server.lifecycle/target
  {:optional true} :seon.db.coordinate/selector]
 [:seon.server.lifecycle/fork-name
  {:optional true} :seon.server.registry/db-name]
 [:seon.server.lifecycle/overlays
  {:optional true}
  [:set [:enum :seon.server.lifecycle.overlay/current-core
               :seon.server.lifecycle.overlay/current-config]]]
 [:seon.server.lifecycle/config-input
  {:optional true} :seon.config/input]
 [:seon.server.lifecycle/plan-token
  {:optional true} :string]
 [:seon.server.lifecycle/confirmation
  {:optional true} :string]]
```

Action rules:

- `:fork` creates one upstream same-store branch. Empty overlays are the default:
  it leaves the selected target unchanged and starts an explicitly debug-only
  runtime with autonomous hosts disabled. An explicitly requested overlay uses
  the same exact population compiler on that branch; it is not another
  lifecycle/versioning mode.
- `:restore` defaults to empty overlays and reconstructs from the selected
  target's own durable schema/program/config/domain facts. It may explicitly
  request current core and/or current config. Current config requires a supplied
  validated input; ambient `config/system.edn`, `SEON_CONFIG`, and current env are
  never implicit inputs.
- `:release` stops/releases a simulation or retained restore branch, removes its
  runtime descriptor/overlay, invokes upstream `delete-branch!`, and only later
  permits branch-aware GC. It never calls `delete-database` for a shared branch.
- Undo is `:restore` targeting a retained undo branch. There is no public
  `undo!`, no second root switch, and no reverse transaction replay.

A successful plan returns an error-as-value envelope containing full source and
target coordinates, the deterministic reserved branch names, requested overlay
set and input digests, commit-graph/retention proofs, plan token, and exact
confirmation phrase. The token is a digest of canonical plan data including
current main commit, target commit, fork/branch name, overlay desired-map digests,
and capability proofs; it is not persisted state and needs no new id generator.
Apply reruns resolution and the preflight behind the admission gate and rejects
if any covered coordinate moved.

```clojure
{:seon.db/ok? true
 :seon.server.lifecycle/action :restore
 :seon.server.lifecycle/mode :plan
 :seon.server.lifecycle/source source-coordinate
 :seon.server.lifecycle/target target-coordinate
 :seon.server.lifecycle/undo-branch
 :seon.restore.undo/<transition-id>
 :seon.server.lifecycle/target-branch
 :seon.restore.target/<transition-id>
 :seon.server.lifecycle/overlays #{}
 :seon.server.lifecycle/preflight
 {:seon.server.lifecycle.preflight/keep-history? true
  :seon.server.lifecycle.preflight/commit-graph? true
  :seon.server.lifecycle.preflight/commits-readable? true}
 :seon.server.lifecycle/plan-token "...canonical digest..."
 :seon.server.lifecycle/confirmation-text
 "restore default from <source commit> to <target commit>"}
```

Apply requires that token and exact phrase for `:restore`; `:release` of an undo
branch also requires typed confirmation. A non-destructive fork may use the plan
token without typed prose. The transition suffix comes from Seon's one shared id
function once the id work is ratified; lifecycle code must not invent another
random-id function.

Errors use the existing `{:seon.db/ok? false :seon.db/error ...}` envelope with
fully namespaced data. Branch names, request actions, and response modes are
control data, not persisted entity kinds.

## Durable supervisor attachment registry

`seon.server.registry` is only a process-local atom, and
`snapshot-registry`/`restore-registry!` at `src/seon/server/registry.clj:611-648`
is an in-memory test fixture, not database restore or durable supervisor state.
The existing supervisor likewise stores only transient process facts under
`tmp/proc/<name>/{pid,cmd,started-at,lock}` (`bin/seon:43,771-836`). A branch pod
needs a durable attachment descriptor, and an applying restore needs minimal
durable intent, outside the branch either operation may rewind.

Use one validated descriptor per logical branch runtime, for example
`data/clusters/debug-alpha/runtime.edn`:

```clojure
{:seon.runtime/coordinate
 {:seon.db.coordinate/store-id #uuid "..."
  :seon.db.coordinate/branch   :seon.simulation/debug-alpha}
 :seon.runtime/db-name        :debug-alpha
 :seon.runtime/source-db-name :default
 :seon.runtime/store-path     "data/clusters/default/store"
 :seon.runtime/blob-write-dir "data/clusters/debug-alpha/blobs"
 :seon.runtime/blob-read-dirs ["data/clusters/debug-alpha/blobs"
                                "data/clusters/default/blobs"]}
```

This is host launch configuration pointing at an upstream Datahike branch, not a
snapshot of database contents and not another DB registry. Write it as a temp
file in the same directory, fsync the file, atomically rename it to `runtime.edn`,
then fsync the parent directory. Read and Malli-validate it before constructing a
process command; a missing/corrupt descriptor fails loud and never defaults to a
new store.

Once a confirmed restore moves from plan to apply, write exactly one intent file
under the source cluster but outside its Datahike and blob directories:

```text
data/clusters/<db-name>/lifecycle/restore-<R>.edn
```

For the supervisor's own cluster, derive the prefix from `SEON_CLUSTER_DIR`, the
same root whose `store/` child `bin/seon` currently starts and resets. The minimal
validated shape is:

```clojure
{:seon.db.restore/id            restore-id
 :seon.runtime/db-name         :default
 :seon.db.restore/source       source-coordinate
 :seon.db.restore/target       target-coordinate
 :seon.db.restore/undo-branch  :seon.restore.undo/<R>
 :seon.db.restore/target-branch :seon.restore.target/<R>
 :seon.db.restore/overlays      #{}
 :seon.db.restore/overlay-digests {}
 :seon.db.restore/overlay-inputs  {}
 :seon.server.lifecycle/plan-token "...canonical digest..."}
```

There is no phase, status, progress, rate, or result field. Presence says a
confirmed apply must be recovered; source/target/branch facts say what was
authorized. For an explicit overlay, `overlay-inputs` contains the already
compiled canonical desired maps, or content-addressed sibling files whose hashes
are in `overlay-digests`; it never contains only a mutable source path. Thus a
confirmed apply remains recoverable if the build, manifest, or environment
changes or disappears after confirmation. Empty overlays need no config artifact
at all. Write the file/payloads with the same temp-file, fsync, atomic-rename, and
parent-fsync protocol before closing admission. After the DB completion fact is
present, runtime reconstruction is live-proven, and admission has reopened,
delete the intent/payloads and fsync the lifecycle directory. The DB fact is the
durable audit record; the external intent is only the supervisor crash-recovery
input.

On supervisor boot, scan and validate lifecycle intents after the wire server
opens stores but before any main pod starts. Compare each intent with reserved
branches, main ancestry, and restore-fact presence to derive the recovery action.
A corrupt or ambiguous intent keeps the pod stopped. `cluster reset` and
`cluster destroy` must refuse an outstanding intent unless the operator
explicitly cancels/recovers it; wiping only `store/` while silently retaining or
discarding confirmed intent would make recovery dishonest.

Recovery bootstrap is deterministic:

1. `bin/seon` scans validated runtime descriptors when enumerating `pod-*` branch
   processes. A non-`:db` attachment derives debug-only/non-autonomous boot; no
   stored status boolean is needed.
2. The wire server starts its ordinary in-memory registry empty except for main.
3. A branch pod's boot ensure carries or looks up the descriptor coordinate; the
   registry verifies the upstream branch exists and opens `[store-id branch]`.
4. If the descriptor exists but the branch does not, startup refuses and offers
   descriptor cleanup. If the branch exists but descriptor creation never
   committed, it remains a harmless unattached branch visible to lifecycle list/
   release.

The intent file is not a second versioning mechanism. Upstream Datahike branches
and main ancestry remain the database decision record; the file is the minimal
supervisor instruction that survives rewinding the very branch that would
otherwise have held it.

## Live restore protocol

### Preparation and root switch

For source main coordinate `H`, target coordinate `T`, and transition id `R`, use
reserved branches:

```clojure
:seon.restore.undo/<R>
:seon.restore.target/<R>
```

The supervisor/root sequence is:

1. Plan against immutable source/target DB values. Run the commit-graph/retention,
   native-schema, canonical-schema/program, blob, and config-input preflights;
   build every requested overlay desired map before asking for confirmation.
2. Receive exact typed confirmation, then atomically persist the lifecycle intent
   and any content-addressed overlay payloads outside `store/` and `blobs/`.
3. Close write and lifecycle admission for the main logical DB. New requests get
   a typed maintenance response, not a hanging socket.
4. Stop the main pod's HTTP acceptance and SSE feeds; uninstall ticker, schedules,
   wake listeners, agent hosts, and the tx-feed adapter. Send a final reconnect/
   maintenance event where possible.
5. Gracefully stop the main pod and prove its process is gone so its local DIS
   reader and wire writer cannot issue late work. Other branches may stay live;
   they have different `[store-id branch]` identities.
6. Drain every already accepted main wire request. Because `d/transact` replies
   only after commit, zero in-flight requests is a writer fence for the routed
   surface. Also stop/await internal backfills or other writer users.
7. Re-read main, require its commit to equal plan source `H`, and rerun the
   commit-record/ancestry and overlay-digest proofs. Any mismatch leaves main
   untouched and fails the stale plan.
8. `branch!` exact `H` to the undo branch and exact `T` to the target branch;
   read both back and verify commit, `t`, schema, primary roots, and every
   secondary key-map. This depends on the historical-secondary fix.
9. Remove filtered DB handles, reactive engine/listener state, and every registry
   or ambient reference for main `:db`. Release all JVM `:db` connections and
   await writer shutdown. A connection to another branch may remain.
10. Connect the reserved target branch, materialize/verify its referenced blob
   overlay into main base, then call `force-branch! target-db :db
   #{target-branch}` with expected old commit `H`.
11. Read stored `:db` through a fresh connection and verify target datoms/t plus
    the new forced commit whose parent names the target branch. Never trust the
    old connection object.
12. Re-register/reopen main without transacting schema, seeds, config, AI, brand,
    or subscriptions, and reinstall one set of non-writing wire/reactive hooks.
    The
    request server's ambient fallback must dereference the current registry entry,
    not retain the conn captured at wire-server startup
    (`src/seon/server/wire.clj:679-715,763-801`).
13. Rebuild and atomically install the target's own canonical Malli/program
    projections. Then apply only the overlay desired maps frozen in the intent:
    current core as root/boot, current config as root/config. An empty set emits
    neither transaction and never reads an artifact.
14. Write one restore completion fact as the initiating human (or root until a
    human identity is available) through REPL process metadata.
15. Rebuild route/render projections, listeners, compile state, and runtime hosts;
    perform cheap root-owned run recovery below. Keep autonomous capabilities
    fenced until this succeeds.
16. Start HTTP/SSE with a complete repaint, start a fresh tx-feed adapter from
    the new head, then reopen admission. After live proofs pass, delete and
    directory-fsync the supervisor intent and overlay payloads.

The current feed watermark is only a transaction id
(`src/seon/store/wire.cljs:438-461,606-656`). After a rewind, old `H` may be
greater than new commits, so a surviving adapter would suppress the restored
lineage. Cold pod restart naturally resets it, but the upgraded adapter still
uses a full coordinate cursor so a reconnect can prove ancestry. A root change
returns reset/full repaint and clears the process-local cursor; do not add an
epoch fact or use ordinary `since H` replay across the change.

### Durable restore fact and provenance

No phase/status entity is stored. Once the root switch and any requested overlay
DB work are complete, one
entity records the factual transition:

```clojure
{:seon.db.restore/id             restore-id
 :seon.db.restore/db-name        :default
 :seon.db.restore/store-id       store-id
 :seon.db.restore/from-branch    :db
 :seon.db.restore/from-commit-id source-commit
 :seon.db.restore/from-t         source-t
 :seon.db.restore/to-branch      selected-branch
 :seon.db.restore/to-commit-id   selected-commit
 :seon.db.restore/to-t           selected-t
 :seon.db.restore/forced-commit-id forced-main-commit
 :seon.db.restore/undo-branch    undo-branch
 :seon.db.restore/target-branch  target-branch
 ;; Present only when that overlay actually committed:
 :seon.db.restore/core-overlay-digest   core-digest
 :seon.db.restore/config-overlay-digest config-digest}
```

Attribute presence identifies this fact; optional digest presence records an
overlay that actually committed, while absence records preservation. There is no
`kind`, operation, phase, progress, generation, or status attr. Its transaction
supplies `db/txInstant`, `:seon.db/user`, and `:seon.db/process`. The restore fact
is the requesting human/root plus REPL; protected code correction is root/boot;
configured subset correction is root/config. There is no transaction turn/eval
ref and no copied user/process scalar on domain entities.

The fact is written after optional overlays but before runtime admission. It
means the durable DB transition is complete, not that external effects have been
undone or every process object survived. Because this fact, and sometimes honest
CAS-fenced crash-recovery facts, are appended after the force, “preserve target”
means no current core/config/domain reconciliation; it does not mean byte-for-byte
identity with the historical head. Byte-stable observation remains `as-of` or an
unmodified debug branch.

### Crash-derived phases

Reserved branch names share `R`; branch/head ancestry plus completion-fact
presence derive recovery state:

| Observed durable facts | Derived meaning | Recovery |
|---|---|---|
| Intent exists; main at `H`; neither reserved branch enumerated | Confirmed apply not prepared | Keep main in maintenance; resume preparation or explicitly cancel intent |
| Main at `H`, undo exists, target absent | Preparation interrupted | Keep main; finish or release preparation |
| Main at `H`, undo and target exist | Prepared, not switched | Revalidate plan and finish or cancel |
| Main descends first through target branch, no restore fact `R` | Root switched; requested overlay/fact pending | Hold maintenance, use the frozen intent inputs to idempotently finish only requested overlays and write fact, or restore from undo |
| Current DB contains restore fact `R`; intent remains | Durable DB transition complete; supervisor reopen pending | Reconstruct runtime, reopen only after live proofs, then delete intent |
| Current DB contains restore fact `R`; intent absent | Transition and supervisor reopen completed | Normal cheap boot/recovery |
| Main matches neither planned source nor target ancestry | Unexpected divergence | Keep admission closed; never guess |

`branch!` writes the new branch head before adding its name to `:branches`, so a
crash can leave an unenumerated orphan root. That is safer than exposing a named
missing head, but Datahike should eventually add an idempotent branch metadata
repair. The main head is unchanged in that case.

Never auto-delete undo or target branches. Explicit release requires confirmation
and no live readers. Branch-aware GC may run only after deletion and reader
release; active undo/target/simulation branches intentionally pin their reachable
history. Datahike's online single-branch GC is not the tool for this topology;
full reachability GC already treats every enumerated branch as a root.

### Undo and redo

Undo calls the same `:restore` action with the prior undo branch as target. It
first creates a new undo branch from the current main head, which becomes the redo
point. The same confirmation, gate, drain, release, expected-head force, selected
overlay set, completion fact, and runtime reconstruction apply. Undo defaults to
preserving its target too; it does not silently apply whichever config happens to
exist on disk at undo time.
There is no special reverse-datom compiler.

## Cheap root-owned crash recovery after open

Ordinary restart and post-restore runtime reconstruction share one recovery
mechanism. They differ only in whether the root changed.

Current `start-agent!` calls `recover-crashed-runs!` before arming and that
function closes every open run as `:crashed`
(`src/seon/client.cljs:2592-2621`; `src/seon/agent/run.cljs:749-791`). Replace it
in place; do not add a second resume path.

Root/boot should:

1. Query current open runs and turns whose stored status is still `:running`.
2. Before any agent host is active, CAS-fence each recovery against the agent's
   current `:seon.agent/run` pointer and mark only the interrupted turn `:error`
   with an honest “runtime ended before completion; effects were not replayed”
   value. That is irreducible historical fact, not a derived status cache.
3. Rebuild safe declarations/compile state, home namespaces, wake listeners, and
   runtime hosts from the current DB. `init-agent!` already has the idempotent
   home-ns/trigger/host spine (`src/seon/client.cljs:2293-2349`).
4. Leave paused open runs paused. For each non-paused current open run, call the
   existing `drive-run!` after its loop input is installed
   (`src/seon/agent/loop.cljs:484-526,600-626`).
5. Let the first new work transaction pass the existing agent-run CAS fence
   (`src/seon/agent/run.cljs:251-265`; turn open at
   `src/seon/agent/turn.cljs:291-325`). If the run pointer moved or closed,
   skip/abort as a value.

Do not mint an agent when its DB identity exists. Do not close all open runs. Do
not replay the interrupted LLM call or arbitrary eval source. Do not claim a
pending Promise, shell command, web request, email, or other external effect was
rolled back. A new turn continues from the durable transcript and the explicit
interrupted-turn fact.

A simulation branch should start with autonomous wake/ticker/resume disabled by
its durable runtime descriptor. It may reconstruct recognizable agents for
inspection, but historical open work does not silently run against external
capabilities. The operator explicitly enables/drives simulation work inside the
branch process and its restricted capability surface.

## Config-free boot, restore, and optional overlays

### Current boot silently changes the selected database state

The current pod cannot preserve a restored target or boot independently of the
current checkout config. Every write/fallback below must be removed from the
ordinary populated-store open path, not merely skipped in the restore CLI:

| Current path | Source | Preservation/config-free failure |
|---|---|---|
| Supervisor default manifest | `bin/seon:176-187` | Always exports `SEON_CONFIG=config/system.edn`; “no config input” cannot be represented |
| Manifest loader | `src/seon/config.cljs:504-534` | Missing file becomes `{}`, conflating no overlay with an explicit empty/default config apply |
| Singleton read fallback | `src/seon/config.cljs:391-410,703-716`; `src/seon/db.cljs:1490-1528` | “No conn” and “attached DB has no singleton” both return nil, so an absent restored row reads today's manifest/env |
| Skills/context fallback | `src/seon/config.cljs:772-793,1308-1324`; `src/seon/agent/ctx.cljs:2279-2315`; `src/seon/eval.cljs:1505-1543` | Scans current disk and copies current context/home requires into future agents |
| Wire-server ensure hook | `src/seon/server/boot.clj:121-181` | Every opened conn transacts subscription schema before the pod starts |
| Pod connect | `src/seon/client.cljs:693-721` | Unconditionally retransacts the full compiled pod schema |
| Boot seed/reconcile | `src/seon/client.cljs:2422-2568` | Unconditionally writes current entity schemas/core/index and exact-reconciles routes, skills, and config from the manifest/disk |
| Program reconstruction | `src/seon/client.cljs:722-771,895-1017` | Treats compiled core rows as display-only and reloads only the agent layer, so a target whose protected core differs from the current bundle is not actually reconstructed |
| Roster/core cleanup | `src/seon/client.cljs:2596-2672` | Empty *armable* roster mints `root`, then current core ghost pruning mutates the target |
| Provider and branding | `src/seon/client.cljs:2746-2756`; `src/seon/ai.cljs:788-835`; `src/seon/web/debug.cljs:1041-1051`; `src/seon/web/brand.cljs:184-238` | Boot seeds AI env values and exact-syncs brand env values |
| Runtime provider fallback | `src/seon/ai.cljs:649-679`; `src/seon/client.cljs:2242-2257,2897-2903` | An absent restored AI row falls through to current provider/backend env; desired dials must use DB/defaults after attach while only secrets/endpoints explicitly classified as launch capabilities remain live env inputs |
| Pod attachment | `src/seon/store/wire.cljs:87-153,218-242` | Derives path/store id/default branch from `SEON_CLUSTER_DIR`; cannot consume a restored branch descriptor |

The active reactive hook also persists UI subscriptions
(`src/seon/server/reactive.clj:183-214`), although the integrated reliability
design retires persisted dependency/subscription state. Regardless of that
deletion, opening a connection must be read-only.

Config-free does not mean executable-free. A small compiled kernel is still
needed to locate/attach the store, decode Transit/Konserve, validate canonical
forms, and expose supervisor maintenance. Launch-only capabilities—store path,
socket paths, port, API credentials, filesystem/network grants—may still come
from a validated runtime descriptor or environment. They are not database
desired state, are never transacted implicitly, and their availability is not
claimed restored.

### Minimum durable contract for a writable reconstructed target

After the root switch, the database itself must contain enough compatible facts
for the minimal kernel to validate and reconstruct it:

1. Datahike store metadata reports the expected store id/branch, effective
   `:keep-history? true`, effective commit graph, and readable current/parent
   commit records.
2. Native Datahike schema contains the identity/ref capabilities needed to query
   root/human/process identities and normal transaction provenance. The target
   has resolvable root, human, and `boot`/`config`/`repl` process identities plus
   `:seon.db/user` and `:seon.db/process` before any post-restore write.
3. Full, untruncated canonical Malli forms keyed by `:seon.schema/key` agree with
   every installed native storage signature. The process-local registry is built
   atomically from these facts; it is not regenerated from current registration
   side effects.
4. Persisted namespace/function/test declarations needed by the target pass the
   strict safe-declaration loader. Arbitrary eval source, analyzer state,
   functions, Promises, and result vars are not reconstruction inputs.
5. Existing agent identities, run/turn/message facts, route/render facts, and
   referenced blobs validate. Run recovery may append only the honest CAS-fenced
   interruption facts described above.
6. Config facts are optional. A stored config singleton, AI row, routes, root
   context, and mint templates are used when present; their absence is a valid
   target state and never causes disk/env seeding.

If the target intentionally has no database route population, the compiled
kernel exposes only its fixed loopback maintenance/readiness surface; it does not
seed today's application routes to make the UI look healthy. Agent/API admission
opens only for surfaces the reconstructed target actually declares.

A target missing or violating the non-optional floor fails the plan before the
root moves. It remains available to `as-of` or a non-autonomous debug branch. An
operator may explicitly choose a validated current-core migration overlay, but
preserve-target never installs native schema or program facts behind the user's
back. This bounds “preserve” to states created after the reconstruction/provenance
floor exists; older history is not falsely advertised as writable-bootable.

This requires the canonical program-reconstruction refactor already ratified by
the reliability design. Merely skipping `boot-seed!` is insufficient: today's
bundle has already loaded current core and `replay-program-graph!` deliberately
excludes it. Until the minimal kernel can validate/load the target's safe
protected declarations—or prove them byte/semantic-compatible with its compiled
kernel—a differing target is inspection/fork-only. The restore planner must not
equate “the DB opened” with “the selected program can run.”

### DB-owned config and future minting

Post-attach config lookup must distinguish `not-attached` from
`attached-but-absent`. Before attach, accessors use small fail-closed compiled
kernel defaults only. After attach, the injected reader returns the DB map or
`{}`; it never calls `load-manifest`. This fixes the current nil ambiguity at one
switchover seam.

Persist the general agent-context/mint template and root override as canonical
managed values on `[:seon.config/id "cluster"]` (the integrated config population
contract), rather than inventing a parallel template registry. A new agent mint
reads that DB template once, applies its explicit per-mint override, and commits
the complete initial agent/context/home-namespace facts atomically. If the
template is absent, it uses the schema-defined safe empty/default value. Existing
agents keep their own copied facts. `home-requires-for` follows the same order:
agent datom, stored DB template, safe compiled default—never a manifest read.

SOUL/AGENTS files, filesystem grants, API keys, sockets, and other live host
inputs remain external capabilities. A preserved restore may deliberately expose
them through the current host, but the UI and audit must not describe the prompt
as historically exact unless the captured prompt blob/volatile inputs prove it.

### Overlay semantics on restore and later cold boots

There is no stored “config mode.” Each invocation supplies an overlay set:

- empty set — reopen and reconstruct the stored target, with no core/config/AI/
  brand/route/context write;
- `:seon.server.lifecycle.overlay/current-core` — apply the frozen, validated
  current protected program/schema desired maps exactly as root/boot;
- `:seon.server.lifecycle.overlay/current-config` — apply a supplied config input
  through the ordinary exact config populations as root/config; and
- both — validate both candidates first, then commit core before config in the
  fixed order.

The CLI must make config presence explicit (`--config <path>` or an explicitly
present `SEON_CONFIG`), parse it before plan, and pass data—not an ambient default
path. `bin/seon` must stop exporting `config/system.edn` merely because the
variable is unset. The plan snapshots canonical desired maps/digests into its
durable intent. Apply and crash recovery consume only that snapshot.

The exact same rule governs a later cold boot. No supplied overlay means no
desired-state transaction; the values last committed to the DB continue to run.
Supplying current config later repairs only its declared subset and those facts
then survive following config-free boots. AI/brand env sync, route/skill scans,
full schema assertion, core index seed/prune, and root mint are not ordinary boot
hooks. Fresh empty-store genesis remains an explicit base case through the same
startup service; it is not inferred from an empty armable roster.

## Safety, confirmation, and failure model

### Admission and concurrency

The wire server currently accepts one thread per socket and allows several
clients concurrently (`src/seon/server/wire.clj:679-715`). Add a per-logical-DB
lifecycle gate and in-flight counter around every write/lifecycle operation.
Reads may receive a pinned maintenance snapshot or a typed maintenance error;
live UI/SSE should close rather than mix roots.

Branch creation from an immutable commit does not move main, but historical
secondary branching must be performed against a coherent selected commit after
the upstream fix. Briefly gate/drain source writes while capturing and verifying
the branch, especially while versioned secondary writers are present. Once the
branch exists, ordinary main and branch transactions run concurrently through
their distinct Datahike writer queues and branch-head keys.

### Failure boundaries

- Before `force-branch!`, main remains `H`. Preparation branches can be retained
  or released.
- A false commit graph, missing commit/parent record, incompatible target floor,
  or unavailable explicit overlay input fails during plan, before intent or
  maintenance. The same proof failing after drain makes the plan stale and still
  leaves main at `H`.
- During force on the file backend, immutable content/commit is written before
  the atomic main-head key. Recovery sees old or new main, not a partially
  addressable primary root; branch-list metadata can still need repair.
- After force and before completion fact, admission stays closed. Reserved target
  ancestry tells boot which transition to finish or undo.
- If a requested overlay fails, keep maintenance and undo. An empty overlay set
  has no current-config failure path. Never accept agent work on a partially
  applied requested overlay.
- If runtime reconstruction fails after the completion fact, the DB transition
  remains factual; the next boot retries transient reconstruction.
- A normal crash after reopen uses cheap root recovery and CAS fencing; it does
  not repeat the root switch.
- Branch pod failure cannot write main when every wire request names its logical
  branch route and duplicate agent ids are branch-qualified.
- Branch release cannot call `delete-database`; tests must prove the main store,
  main writer, and source blobs remain readable after branch descriptor/overlay
  deletion.

### Observability without stored progress

Emit timestamped structured logs for admission closed, drained, undo created,
target created, main released, root forced, each requested overlay delta (or the
empty overlay fact in the plan), restore fact committed, runtime reconstructed,
and admission reopened. Derive elapsed time and any throughput estimate from
those timestamps. Do not persist a rolling rate, progress counter, current
phase, or status entity. Durable transition state is already derivable from
intent, branch/head, and result-fact presence.

## Implementation and deletion map

### Upgrade pinned Datahike in place

- Fix `versioning/branch!` secondary selection for commit UUID/non-current branch
  sources and add Stratum/Proximum regression tests.
- Make connection release await writer shutdown.
- Add expected-current-commit guarding and useful read-back to
  `force-branch!` without inventing a new root operation.
- Keep the existing commit-graph opt-out behavior, but add direct tests that a
  historical lifecycle preflight distinguishes effective-default true, stored
  false, and a missing retained parent record.
- Add crash/idempotence tests around branch-head plus `:branches` metadata.

### `src/seon/server/store.clj`

- Extend the one `config-for` request with optional explicit store id and branch.
- Keep normal db-name-derived `:db` defaults and state `:commit-graph? true`
  explicitly for new Seon stores.
- Delete no existing config function and add no branch-specific duplicate.

### `src/seon/server/registry.clj`

- Expand entries/summaries to logical db-name plus the one canonical coordinate,
  path/backend, and conn.
- Add inverse `[store-id branch] -> db-name` uniqueness.
- Scope agent membership by logical db-name and make agent-only ambiguity an
  error; an explicit db-name/agent mismatch is also an error.
- Replace public `fork-db!` with private helpers under the one lifecycle service;
  delete fork schemas, `fork-verify!`, physical copy/retry, and misleading
  copy-while-live claims.
- Make removal branch-aware: release/delete branch versus delete whole database.
- Keep `restore-registry!` clearly test-only; it is unrelated to DB restore.

### `src/seon/server/wire.clj` and `src/seon/server/boot.clj`

- Add lifecycle admission/in-flight tracking per logical DB.
- Extend branch attachment/ensure with exact validated store coordinate and
  return it to the pod.
- Resolve explicit db-name with agent membership rather than letting duplicate
  agent id override it.
- Make ambient main conn lookup dynamic after root replacement.
- Qualify filtered DB handles, reactive engine state, and broadcast subscriptions
  by attachment projection; drop them during release and reinstall once on
  reopen.
- Include the full resolved coordinate in tx/replay events and replies. Replace
  numeric-only replay with commit-ancestry continuation or typed reset.
- Remove `seed-subscription-schema!` from the connection-open hook. Opening an
  existing branch must transact nothing.

### `src/seon/store/wire.cljs`

- Separate logical runtime name from store path/id/branch.
- Consume the server-validated coordinate or durable runtime descriptor; stop
  deriving branch store identity from the fork's directory name.
- Include branch in the local Datahike config and keep every write/replay request
  routed by logical db-name plus the verified attachment projection.
- Replace `last-applied-t` with a full coordinate cursor and add a real
  listen-adapter stop/reset path; a main root switch starts from the new
  coordinate, never the old numeric watermark.

### `src/my/blob.cljs`

- Upgrade the one blob store to branch-local write overlay plus ordered read
  bases.
- Keep all existing functions and hash identities; add no fork blob API.
- Add referenced-blob verification/materialization helpers for lifecycle
  promotion and branch-aware mark/sweep tests.

### `src/seon/config.cljs` and config consumers

- Make no config input distinct from an explicitly empty manifest; remove the
  implicit default-file read from runtime accessors.
- Make the DB reader distinguish not-attached from attached/absent and use
  fail-closed kernel defaults only before attach.
- Persist/read general and root mint templates through the existing config
  singleton population; remove manifest reads from agent mint and home-ns setup.
- Fold AI/brand/routes/context desired values into explicit config apply, or keep
  a value runtime-only; never transact them from an ordinary open hook.
- After attach, provider/model/policy dials resolve from DB then safe compiled
  defaults, not current config env. Keep credentials and explicitly classified
  transport endpoints as live capabilities rather than restored facts.
- Keep launch capabilities/secrets in validated runtime inputs without claiming
  they are restored database state.

### `src/seon/client.cljs`, agent runtime, and web runtime

- Add graceful cluster close: stop HTTP/SSE, ticker, listeners/feed, hosts, and
  release the cluster conn.
- Refactor the one startup service so opening a populated target transacts no
  schema/core/config/AI/brand/subscription data. Fresh genesis and supplied
  overlays are explicit inputs to that same service.
- Reconstruct the canonical Malli/program projections from the DB before loading
  safe declarations; fail maintenance on incompatibility instead of seeding.
- Replace close-all `recover-crashed-runs!` with root-owned interrupted-turn
  repair plus existing-run re-drive.
- Keep simulation agents non-autonomous until explicitly driven.
- Persist the full rendered coordinate on new turns and uniquely backfill legacy
  `rendered-as-of` rows before root-reset support is enabled.
- Prefix Datastar/debug feed keys with the attachment projection and use commit
  ids for historical keys/bookmarks. On reopen, rebuild router/render projections
  and send a complete view; do not reuse an SSE connection or cursor across
  roots.

### `src/seon/dev/runtime_id.cljc` and `bin/mcp-server-cljs`

- Keep the existing `<db-name>/<agent-id>` grammar and bare-id ambiguity rule.
- Advertise the server-validated coordinate after attachment rather than a
  pre-attach directory basename; include it in resolver cache validation and
  diagnostics.

### `bin/seon`

- Rewrite `cluster fork` to plan/apply same-store `branch!`, atomically write the
  validated runtime attachment descriptor, and start a branch-bound pod.
- Delete `cp -R` of store/blobs, target store path creation, physical fork RPC,
  and distinct-store language.
- Route branch destroy through lifecycle `:release`; never wipe the source store
  or base blobs.
- Add `cluster restore` plan/apply with exact typed confirmation. `cluster undo`
  may be CLI sugar that selects an undo branch but must call the same restore
  action.
- On supervisor boot, recover incomplete reserved restore transitions before
  starting the main pod.
- Stop exporting `config/system.edn` on an unset `SEON_CONFIG`. An explicit
  `--config`/env input is parsed into plan data; preserve-target needs neither.

### Documentation after implementation

Update `docs/seon/architecture/data-model.md`, `agent-runtime.md`,
`observability.md`, and the active runtime-reliability roadmap so the ideal and
we-are-here views agree. Remove older copy-fork recommendations rather than
leaving two supported stories. In particular, replace observability's
`rendered-as-of`-alone and “commit whose max-tx equals at” claims with the full
coordinate/legacy-ambiguity contract; `AsOfDB -max-tx` is the origin head.

## Required tests and live proofs

### Coordinate and routing proofs

- The canonical coordinate schema accepts `{store-id, branch}` and the complete
  four-field resolved map, but rejects commit-id without t or t without
  commit-id.
- Registry property tests generate logical names/store ids/branches and prove a
  bijection between db-name and `[store-id branch]` for live attachments.
  Re-ensure with an identical envelope is idempotent; either side differing is a
  typed conflict.
- `ensure-db` returns the actual connection's store id, branch, commit id, and t;
  the pod builds its local reader config from that response/descriptor and an
  immediate read-back matches all four.
- Source and branch both register agent `root`. Explicitly qualified reads and
  writes hit their own conn; bare `root` is ambiguous; a request combining source
  coordinate with branch membership fails instead of allowing either to win.
- A filtered handle minted on source is rejected from the branch and is gone
  after source release. Reactive/broadcast engine counts return to zero on
  release and one after reopen.
- Every tx/replay frame carries the exact resolved coordinate. A linear cursor
  continues once; a duplicate commit dedupes; a cursor from the abandoned
  lineage returns reset even when its t equals or exceeds the new head.
- MCP advertisements use the verified coordinate and still resolve
  `<db-name>/<agent-id>` through the one existing grammar. Datastar/debug view
  keys for the same agent on two attachments differ; two historical commits at
  the same t also differ.

### Temporal read proofs

- Query and pull at `T` return only `T` data and reply with the view coordinate,
  not origin `H`.
- `basis-t` of an `AsOfDB` returns its view time point, while coordinate
  resolution returns the selected commit rather than the origin commit.
- `as-of` does not move any branch head or execute code.
- Frozen Datastar feeds remain byte-stable after later source transactions.
- New turn capture persists all four rendered-coordinate scalars. A pre-reset
  legacy t uniquely backfills; a repeated/ambiguous t remains unmodified and its
  structured re-render returns a typed ambiguity while the prompt blob remains
  readable.

### Commit-graph preflight proofs

- A legacy/default store with no literal `:commit-graph?` key but readable head
  and ancestry records passes as effective true.
- A store created with `:commit-graph? false` and a store with a deliberately
  missing selected/parent record both fail plan before intent, admission gate, or
  branch creation. Passing `true` at reconnect is not treated as migration.
- Plan resolves and freezes every commit record; apply repeats the walk after
  drain. Pruning/moving a required record or head between them rejects the stale
  plan without moving main.

### Writable branch proofs

- Branch from an exact commit, connect with the same store id/path plus new
  branch, transact, release/reconnect, and prove main never sees the write.
- Run concurrent source/branch writes and prove each advances only its own head.
- Branch from a historical commit with Stratum and Proximum; primary query,
  secondary query, commit key-map, and reopen all agree at `T`. Pin the reproduced
  future-index failure as a regression.
- Register identical agent ids in source and debug branch. Explicit logical
  route writes correctly; agent-only lookup returns ambiguity.
- Broadcast/replay feeds deliver only events carrying the corresponding logical
  db-name and full resolved coordinate.
- Historical blob reads fall through to source base; new branch blobs land only
  in overlay; branch destruction leaves source blobs untouched.

### Restore/undo crash matrix

- Reproduce the stale-writer clobber as a negative control; prove the lifecycle
  cannot issue it after admission/drain/release.
- Race a last accepted write with apply. It either completes before captured `H`
  or is rejected after the gate; never commits on stale lineage.
- Move main after plan and prove apply rejects its stale token/expected head.
- Kill at every boundary: after undo branch, target branch, main release, force,
  each requested overlay delta, completion fact, and runtime reconstruction.
  Restart derives the phase and finishes/undoes from the frozen intent without
  rereading a file or guessing.
- Restore directly to historical `T` and to a divergent simulation branch.
- With empty overlays, prove target core/config/AI/brand/routes/agent/domain facts
  match the selected target except for explicit restore and CAS-fenced recovery
  facts. Repeat with current-core only, current-config only, and both; only the
  named exact populations may differ.
- Restore to the undo branch through the same action; prove a new redo point is
  retained.
- Prove old SSE/feed clients are closed, a fresh full view is correct, and no old
  watermark suppresses reused transaction ids.
- Prove every target blob ref resolves before admission; promotion copies only
  branch-overlay hashes the target references.

### Config-free cold-boot proofs

- Remove/rename `config/system.edn`, unset `SEON_CONFIG` and config-like env
  values, then restore and cold-boot a compatible populated target. The DB head
  advances only for required restore/recovery facts, not schema/config seeds, and
  stored config/routes/context still drive the runtime.
- Restore a compatible target with no config singleton/template. Post-attach
  accessors return safe defaults without touching disk; no singleton is created.
  An explicit later agent mint uses the safe DB-absent template behavior.
- Plan an explicit config overlay, then change/delete its source file and env
  before apply and at each crash boundary. Recovery uses the frozen canonical
  payload/digest and produces the confirmed result.
- A later plain restart after a config overlay performs no config transaction;
  an explicit different config apply exact-reconciles only its declared
  populations.
- AI/brand sync, subscription schema seed, full schema assertion, core seed/
  prune, skills scan, and root mint each have a negative test proving they do not
  run on populated preserve-target open.

### Root crash recovery proofs

- Kill a pod mid-turn. On restart, the dangling turn becomes an honest error,
  the open run remains current, transient hosts rebuild, and a new turn is driven
  behind the CAS fence.
- A paused open run remains paused.
- A stale/superseded run cannot commit after recovery.
- No stored eval source is executed and no missing Promise/runtime object is
  claimed restored.

### Release and GC proofs

- Stop/release every branch reader/writer, delete the branch, remove its runtime
  descriptor and overlay, then prove main DB and base blobs remain available.
- GC preserves every active simulation/undo/target branch and reclaims only
  unreachable content after explicit branch release.
- A descriptor without a branch and a branch without a descriptor both fail
  safe and are operator-repairable.

### Operator live proof checklist

Before declaring the feature complete, observe in a running disposable cluster:

```clojure
(datahike.versioning/branches main-conn)
(datahike.versioning/commit-id
  (datahike.versioning/branch-as-db main-conn :db))
(datahike.versioning/commit-id
  (datahike.versioning/branch-as-db main-conn simulation-branch))
(let [db @main-conn
      cid (datahike.versioning/commit-id db)]
  {:effective-commit-graph? (get (:config db) :commit-graph? true)
   :commit-roundtrip?
   (= cid (some-> (datahike.versioning/commit-as-db main-conn cid)
                  datahike.versioning/commit-id))})
```

Then query a branch-only datom from both connections, perform a confirmed restore,
restart the pod/wire attachment with `SEON_CONFIG` absent, query the restore fact
and preserved config/core/domain populations, inspect the recovered run/turn and
its full rendered coordinate, and finally restore from undo. Repeat once with an
explicit config overlay and prove only its exact population changes. The running
system—not only unit tests or source inspection—must show the intended
coordinates, commit round trips, and absence of cross-branch writes.

## Final decision

Use upstream same-store versioning and make it correct. `as-of` is the cheap
immutable simulation; `branch!` is the cheap writable simulation; undo plus
`force-branch!` is the deliberate live reset. One supervisor lifecycle wrapper
owns every mutation. Preserve-target is the default; current core/config repair
is an explicit, frozen overlay through the existing exact population contracts,
never a prerequisite or an ambient boot side effect.

Before implementation, gate on effective commit graph plus readable retained
records, fix historical secondary branching, and make writer release awaitable in
the pinned Datahike fork. Then plumb the canonical
`{store-id, branch, commit-id, t}` coordinate through routing, feeds, UI, turn
capture, and lifecycle; make populated-store open config-free/read-only; add blob
base/overlay, admission/drain, and durable runtime descriptors. Retire the
physical-copy fork path rather than carrying two versions of the same idea.
