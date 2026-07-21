---
type: research
status: completed
tags: [database, archive, research]
---

# Branch-local blobs and forensic runtime audit

## Question and result

The database-lifecycle branch can add native writable historical branches only
after two adjacent gaps are closed in place:

1. `my.blob` must become one content-addressed storage view with one writable
   directory and ordered read-only bases; and
2. `seon.client/start-runtime!` must distinguish deterministic attachment from
   autonomous activation, so a historical attachment can reconstruct read
   surfaces without boot reconciliation, recovery, agent hosting, providers,
   schedules, or the ticker.

No new blob identity, database attribute, forked runtime, or second debug path
is required. The smallest safe implementation extends the current owners and
keeps the public `my.blob` functions unchanged. Native branch creation and
release remain root/supervisor operations, not agent protocol operations.

## Dependency ledger

| Dependency or mechanism | Selected version or SHA | Grounding source | Existing Seon owner or proof |
|---|---|---|---|
| Datahike | `6f90b339768b1a02066dce3b6fcc93a200758fcc` | `reference-code/datahike/src/datahike/versioning.cljc`; `test/datahike/test/versioning_test.cljc`; `test/datahike/test/nodejs_test.cljs` | `deps.edn` `:writer` and `:cljs`; `seon.db.backend`; `seon.db.registry`; `seon.db/at-coordinate` |
| Konserve | `df6818d43ea3363a808cd051c0d68917f1b987a9` | `reference-code/konserve/src/konserve/filestore.clj`; `impl/defaults.cljc`; `node_filestore.cljs` | Datahike's file store; no direct Seon blob dependency |
| Malli | `0.20.0` | `reference-code/malli` at `80138076960e7820523b4cb932c5b5d1936d4e7f` | `seon.schema`; public function schemas |
| Clojure / ClojureScript | `1.12.0` / `1.12.145` | `deps.edn` | writer and pod build bases |
| `superv.async` | `3e6ed755f83634c9e9bbb58707f9446420d32ce9` | maintained fork selected in `deps.edn` | Datahike CLJS async boundary; not a new lifecycle API |
| `partial-cps` | `1e119b03ea908ad925b98f9ba0a26371c65441e3` | maintained fork selected in `deps.edn` | CLJS `await`; not a lifecycle owner |
| Blob archive | repository source | `src/my/blob.cljs`; `test/my/blob_test.cljs` | one public `my.blob` API and `:my.blob/*` projection |
| Runtime hosting | repository source | `src/seon/client.cljs`; `src/seon/agent/runtime.cljs`; `src/seon/agent/loop.cljs`; `src/seon/web/serve.cljs`; `src/seon/db/replica.cljs` | one cluster startup, host, ticker, web, and replica lifecycle |
| Historical reproduction | repository source | `src/seon/agent/debug.cljs`; `docs/seon/architecture/observability.md` | `seon.db/at-coordinate` already supplies exact read-only values |

Datahike's maintained source is decisive. `branch!` accepts an existing branch
keyword or retained commit UUID, copies the selected stored root, branches
secondary indices from that same root, and rejects an absent commit or a store
with `:commit-graph? false`. `delete-branch!` refuses `:db` and refuses a branch
with an active connection. `force-branch!` requires external exclusive access,
supports `:expected-current-commit`, writes immutable leaves before the mutable
head, and verifies the stored head afterward. Existing connections become
stale and must be released and reconnected. `commit-as-db` and `branch-as-db`
load immutable roots from the same store.

Konserve's filestore supplies atomic replacement for its own `.new` blob
protocol (`Files/move` with `ATOMIC_MOVE`/`REPLACE_EXISTING` on the JVM and
rename in Node). This proves the write-leaves-then-publish-root idiom used by
Datahike. It does **not** make Seon's separate `my.blob` filesystem and a
Datahike transaction atomic; Seon must tolerate either side existing alone and
repair or reject admission from reachability evidence.

## Current source inventory and gaps

### Blob archive

`src/my/blob.cljs` has one process-local `!dir`, derived from
`SEON_CLUSTER_DIR/blobs`. `put!` writes the final pathname directly when absent
and then transacts the projection. `get` searches only that directory. `stat`
reads only the database projection and therefore can report `exists? true` when
the bytes are missing. Reads do not re-hash bytes. A successful file write
followed by a rejected projection transaction leaves an unreferenced file; a
committed projection with missing bytes remains a broken reference. The tests
prove single-directory round trip, idempotence, paging, concatenation, and
errors-as-values, but no overlay fallback, integrity, missing-byte admission,
materialization, or release behavior.

The projection is already correct: `:my.blob/hash` is the identity and the
token/media/time attributes are queryable metadata. Storage location is a
property of a runtime attachment, not of the blob entity, so no branch or path
attribute belongs on `:my.blob`.

The current `cluster reset` removes the database path but not the sibling blob
directory. This is a retention-policy mismatch: a fresh database can inherit
unreferenced bytes from the prior database lifetime. It is not evidence that
reset should delete blobs casually; reset must acquire an explicit whole-cluster
blob policy in the lifecycle slice, while branch release must never delete the
source base.

### Historical and forensic runtime

`seon.agent.debug/repro` now resolves the complete recorded coordinate through
`seon.db/at-coordinate` and deliberately omits writable branching. That is the
right present boundary.

`seon.client/start-runtime!` has no non-autonomous input. On cold start it
attaches, applies `boot-seed!`, performs crash recovery, creates root and the
initial agent, reconstructs the program graph, instruments it, resumes every
nonterminated agent, starts web, synchronizes AI and brand state, and installs
the ticker. `seon.agent.runtime/resume!` always creates the compiler home and
installs a wake trigger when the durable per-agent `wake?` defaults true.
`wake? false` gates only that listener; it does not gate the global ticker,
boot writes, recovery, providers, schedules, or web composition actions.

The necessary inverse primitives exist but are not composed into cluster
close: `agent.runtime/unhost!`, `agent.loop/uninstall-ticker!`,
`web.serve/stop!`, and `db.replica/detach!`. There is no single graceful
`stop-runtime!`. Hot reload also unconditionally rehosts runtimes and reinstalls
the ticker, so a launch mode must survive reload in process-local state or the
first reload would violate forensic isolation.

### Registry and protocol

`seon.db.registry` still models a logical database name as one backend/path/
connection and retains the physical `d/fork-database` wrapper. The current
Babashka operator exposes only `cluster reset`; there is no usable native
branch attach/release command. The typed writer protocol has ensure, transact,
replay, and KNN operations but no root-only lifecycle operations. This is
appropriate: branch creation, force, and deletion should be supervisor-to-
registry operations, not ordinary pod/agent requests. The registry needs a
branch-qualified attachment and inverse uniqueness before two pods can safely
share a store.

## Exact ownership

| Concern | One owner | Required change |
|---|---|---|
| Content hash, projection, agent reads/writes | `my.blob` | Replace one directory with one validated storage view; preserve public functions and hashes |
| Blob overlay allocation/deletion/materialization | database lifecycle service operated by the supervisor | Supply attachment directories, verify reachable refs, materialize before promotion, delete overlay only after release |
| Database branch root | `seon.db.registry` over maintained Datahike | Register `{database-id, branch}` uniquely; branch/connect/release/delete with upstream primitives |
| Launch capability | `seon.client` | One startup path with attachment/reconstruction and optional autonomous activation |
| Per-agent host | `seon.agent.runtime` | Host only when activation or an explicit forensic action requests it |
| Schedules and overdue work | `seon.agent.loop` | Install the one ticker only for autonomous activation |
| Read-only UI and exact reproduction | existing web/debug owners | Continue querying the attached immutable/branch value; do not invent a forensic renderer |
| Restore admission | lifecycle service | Verify branch coordinate, required blobs, program/schema reconstruction, and quiescence before forcing the live head |

The lifecycle code may call non-agent-facing helpers for storage verification,
but it must not create a second blob implementation. Path resolution, hashing,
and byte verification stay shared with `my.blob`; lifecycle orchestration stays
out of the agent's default `my.*` surface.

## Smallest in-place implementation

### 1. Make `my.blob` a storage view

Replace `!dir` with one process-local, validated value containing a writable
directory and an ordered vector of read-only base directories. For a normal
cluster the writable directory is the current blob directory and bases are
empty. For a branch pod the writable directory is its private overlay and the
source blob directory is its first base.

- `put!` computes the same hash, writes only to the writable directory, and
  uses temp-file-plus-atomic-rename publication rather than writing the final
  file directly.
- `get` resolves overlay first, then bases. Every found file is re-hashed before
  returning success; the same hash with different bytes is corruption, not
  shadowing.
- `stat` keeps its documented database-projection meaning. Lifecycle admission
  uses a separate internal reachable-byte verification, rather than changing
  `stat` to conflate projection and storage.
- Tests set one storage view rather than mutating a bare directory atom. Public
  `put!`, `get`, `text`, `concat!`, and `stat` shapes do not change.

This slice can land before native branches because the normal one-directory
configuration is an exact specialization of the storage view.

### 2. Split attachment from autonomous activation inside one runtime

Extend the one `start-runtime!` request with a closed, process-local launch
capability supplied by the supervisor. Do not persist an entity `:type` or
derive authority from environment naming. The startup implementation remains
one service but has two explicit phases:

1. attach and validate the registry-selected branch, replica, compiler/program
   projection, instrumentation, and read-only web/debug surfaces; then
2. only when autonomy is enabled, reconcile explicit boot inputs, perform crash
   recovery, create genesis agents when appropriate, resume durable agents,
   synchronize providers/brand, and install the ticker.

A historical/forensic attachment runs phase 1 only. Hot reload consults the
same process-local capability and must not rehost or install a ticker when
autonomy is disabled. Add the corresponding one `stop-runtime!` that closes
web/SSE, uninstalls ticker and hosts, detaches replica/listeners, and releases
the connection in that order.

An explicit forensic action may later mint one agent on the writable branch and
drive it intentionally through existing agent primitives. It must not turn on
the ambient ticker or resume inherited agents. The existing `POST /agents/run`
composition door requires a focused audit before reuse because its normal path
delivers through wake/drive machinery; the admission rule is the behavior to
preserve, not the endpoint name.

### 3. Register native branch attachments

After the runtime split, replace the physical-copy registry operation with
Datahike `branch!` from the complete retained coordinate's containing commit.
The selected `t` remains a temporal cut inside that commit: if a writable
branch must begin at an earlier `t` than the containing commit head, the
lifecycle must materialize that exact as-of value deliberately and prove its
semantics; it must not silently treat the containing commit as the requested
cut. Register the resulting branch under a distinct logical cluster name but
the same database identity/path and explicit branch. Reject duplicate
`[database-id branch]` attachments and cross-attachment requests.

Release order is: close the branch runtime, drain/reject writes, release the
Datahike branch connection, call `delete-branch!`, then delete only the branch
overlay. Datahike itself rejects deletion while a connection is active.

### 4. Materialize blobs before promotion

Restore computes the set of `:my.blob/hash` values reachable in the target
database view. For each hash it verifies overlay then base. Overlay-only bytes
are copied or reflinked into a temporary base pathname, hash-verified, and
atomically renamed. Only after every referenced hash resolves may guarded
`force-branch!` replace `:db`. A crash before force leaves harmless immutable
base bytes; a crash after force leaves cleanup work but not missing live bytes.

Blob garbage collection is a later blob-lifecycle slice. Until it exists,
base and retained overlays remain append-only. Its mark roots must include every
retained branch and every retained historical coordinate promised by the
forensic retention policy, not only current branch heads.

## Schema and protocol implications

- Keep the four existing `:my.blob/*` attributes unchanged. Do not store paths,
  overlay ids, branches, or source directories on blob entities.
- The storage view and autonomy grant are validated process launch inputs.
  Secrets and filesystem capabilities are not reconstructed database facts.
- Extend registry entries and ensure/attachment responses with the already
  canonical attachment projection. Every transaction/replay path continues to
  validate it.
- Do not expose branch create/delete/force as agent protocol operations. A
  typed root/supervisor lifecycle interface invokes registry functions out of
  band and returns structured errors.
- Branch names are Datahike keywords and complete coordinates stay
  `{database-id, branch, commit-id, t}`. A logical cluster label is routing,
  never database identity.
- Runtime autonomy is a closed launch capability, not a durable agent status.
  Persist only durable domain facts produced by an explicit forensic action.

## Failure and retention matrix

| Event | Required result |
|---|---|
| Branch reads a source-era blob | Overlay miss falls through to read-only base; hash verifies |
| Branch writes a new blob | Bytes land only in overlay; projection commits only on branch |
| Same hash exists in overlay and base | Both must hash to the name; content is identical, never precedence-dependent |
| File publish succeeds, projection transaction fails | Unreferenced overlay file is safe and later sweepable; return an error value |
| Projection commits but bytes are absent/corrupt | Admission/restore fails closed with the hash and searched locations |
| Overlay file exists without projection | It is unreachable orphan data; never copied merely because it exists |
| Branch release succeeds | Source base remains untouched; overlay deletion occurs only after runtime and Datahike connection release |
| Process dies during overlay write | Final hash path is absent or complete; temp file is reclaimable |
| Process dies during materialization | Guarded promotion has not run; rerun verifies and completes idempotently |
| Process dies after force and before cleanup | Live base already contains every referenced byte; cleanup is resumable |
| Source and branch write concurrently | Separate branch heads and overlay directories prevent cross-lifetime writes |
| Historical pod boots | No seed/config/AI/brand/recovery writes, root birth, agent resume, wake trigger, ticker, schedule, or external effect |
| Historical pod hot reloads | It remains non-autonomous; no rehost or ticker installation |
| User explicitly starts forensic work | Only the requested branch-local agent is created/hosted/driven; inherited agents remain dormant |
| Delete branch while connected | Reject; Datahike requires release first |
| Force live head with stale expectation | Reject through `:expected-current-commit`; do not admit the runtime |
| Reset a whole cluster | Apply an explicit whole-cluster blob retention/deletion policy; never reuse branch-release semantics |
| Retention expires | Mark all promised branch/historical roots before sweeping; never infer liveness from directory age |

## Tests

### Focused CLJS blob tests

- normal storage view preserves every current `my.blob-test` assertion;
- overlay write never touches the base;
- overlay miss reads a base blob;
- corrupt overlay/base bytes return an error value naming the hash;
- temp-file publication leaves no partial final path under injected failure;
- projection rejection leaves an honest, unreachable orphan;
- reachable-byte verification reports every missing hash;
- materialization copies only target-referenced overlay hashes and is
  idempotent.

### Writer and registry tests

- branch from retained commit preserves database id and gets a distinct branch;
- branch writes do not move `:db`;
- historical secondary-index queries match the selected root;
- duplicate attachment and wrong attachment are rejected;
- release precedes delete, active deletion is rejected, and source remains;
- guarded force rejects a stale head and reconnect sees the promoted head;
- physical `fork-database!` and its new-identity assertions are deleted.

### Runtime tests

- non-autonomous startup records zero transactions and installs no hosts/ticker;
- read-only web/debug and exact `repro` still render from the selected branch;
- hot reload preserves non-autonomy;
- explicit one-agent forensic action does not resume sibling/inherited agents;
- graceful stop removes web, ticker, wakes, replica listeners, and connection;
- ordinary autonomous boot remains behaviorally unchanged.

### Destructive composition tests

- source and forensic pods run simultaneously against separate branches and
  blob write locations;
- branch-only prompt/reply blobs survive branch restart;
- promotion fails before force when one referenced blob is missing;
- successful promotion materializes branch-only bytes, reconnects `:db`, and
  exact turn/error reproduction reads them;
- undo restores the prior root without deleting newly materialized immutable
  bytes;
- branch destroy removes only its overlay and never the source base.

## Live proof and graduation evidence

Use the repository operator and MCP REPL after the focused gates:

1. Capture the source head coordinate and a known prompt/reply blob hash.
2. Create a native branch from that complete point and start its pod with
   autonomy disabled.
3. Through cluster-qualified CLJ and CLJS eval, prove the same database id,
   distinct branch, expected commit/t, readable source blob, no head advance,
   no hosted agents, and no ticker.
4. Explicitly run one forensic action, write a branch-only blob, and prove the
   source branch and source blob directory did not change.
5. Restart the branch pod and prove both inherited and overlay blobs resolve
   while inherited agents remain dormant.
6. Stop and release the branch; prove `delete-branch!` succeeds, the overlay is
   removed, and source `:db` plus its blob bytes remain readable.
7. In a separate destructive restore trial, inject a missing referenced blob
   and prove promotion refuses before `force-branch!`; repair it, promote with
   the expected-head guard, reconnect, and read the exact blob and database
   facts from the new live root.

Graduation requires the recorded coordinates, branch roster, transaction
counts, process-handle state, searched blob paths, and before/after hashes—not
only green tests or process logs.

## Dependency order

1. Land the backward-compatible blob storage view and integrity tests.
2. Land non-autonomous startup, hot-reload preservation, and graceful stop.
3. Extend registry attachment identity and delete the physical-copy fork.
4. Add native branch create/attach/release plus simultaneous source/branch
   proof.
5. Add referenced-blob verification/materialization to guarded restore.
6. Design retention/GC only after branch and historical retention roots are
   explicit.

This order lets the first two slices be reviewed independently and prevents a
native branch pod from ever starting with shared writable blobs or accidental
autonomy.
