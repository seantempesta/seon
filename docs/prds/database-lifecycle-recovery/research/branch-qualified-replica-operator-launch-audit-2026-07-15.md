---
type: research
status: completed
tags: [database, pod, flow, research]
---

# Branch-qualified replica and operator launch audit

## Result

The next lifecycle slice is one immutable, closed launch descriptor consumed by
the existing Babashka operator and the existing pod startup path. It separates
runtime identity from database routing, physical storage, and writer ownership.
The operator creates or adopts the native branch through the source writer,
publishes the descriptor atomically, and starts only a non-autonomous pod. The
pod uses the descriptor to open the exact target attachment, subscribe to the
source writer's sockets under the target logical route, configure its private
blob overlay, and advertise a distinct runtime identity.

No second writer, watcher, supervisor, replica, registry, blob API, or MCP
server is needed. The present operator cannot implement this safely because
`cluster` currently means five different things at once: runtime/MCP identity,
writer logical route, physical database directory, supervisor/writer
ownership, and writer REPL-port ownership. A branch launch must split those
values explicitly rather than manufacturing another `SEON_CLUSTER_DIR`.

The smallest implementation boundary ends after a branch pod can be created,
started non-autonomously, MCP-evaluated in CLJS and CLJ, restarted across a
writer restart, and released/deleted without touching its source database or
source blob archive. Restore, promotion, and undo remain later slices.

## Dependency ledger

| Dependency or mechanism | Selected version or SHA | Exact grounding source | Current Seon owner or proof |
|---|---|---|---|
| Datahike | `6f90b339768b1a02066dce3b6fcc93a200758fcc` | `reference-code/datahike/src/datahike/versioning.cljc:116-229`; `reference-code/datahike/src/datahike/api/impl.cljc:338-347`; `reference-code/datahike/src/datahike/connector.cljc:438-514`; `reference-code/datahike/test/datahike/test/versioning_test.cljc` | `seon.db.registry/create-branch!`, `ensure-database!`, `release-attachment!`, and `delete-branch!` use maintained native branch/open/release/delete behavior. |
| Konserve | `df6818d43ea3363a808cd051c0d68917f1b987a9` | `reference-code/konserve/src/konserve/filestore.clj`; `reference-code/konserve/src/konserve/impl/defaults.cljc`; `reference-code/konserve/src/konserve/node_filestore.cljs` | Datahike's one shared file store; branch pods read the same immutable nodes and never create a second writer. |
| Clojure / ClojureScript | `1.12.0` / `1.12.145` | `deps.edn`; ClojureScript source mirror at `reference-code/clojurescript` tag object `bd23d9a2475d822ea8dfd65deaa6732428b9ed25` | JVM writer and Node pod. |
| Shadow CLJS | `3.4.10`; source mirror `8236315af7426ba505aad6102dea1c4ccb1fe412` | `reference-code/shadow-cljs/src/main/shadow/cljs/devtools/api.clj`; `reference-code/shadow-cljs/src/main/shadow/cljs/devtools/server/runtime.clj` | One flavor-owned watcher; `seon.dev.mcp` enumerates and pins exact runtime ids instead of selecting latest. |
| Babashka | `1.12.212`; process source mirror `16a84e0af0da51b8c84e289970f6b7cc35b35d18` | `reference-code/babashka-process/src/babashka/process.cljc:96-171,367-507,678-710` | `bin/seon` delegates to `seon.dev.cli`; `seon.dev.process` owns process identity, start order, readiness, drain, and retained state. |
| Malli | `0.20.0`; source mirror `80138076960e7820523b4cb932c5b5d1936d4e7f` | `reference-code/malli/src/malli/core.cljc`; `reference-code/malli/docs` | Every descriptor and public boundary is closed and validated. |
| `superv.async` / `partial-cps` | `3e6ed755f83634c9e9bbb58707f9446420d32ce9` / `1e119b03ea908ad925b98f9ba0a26371c65441e3` | selected overrides in `deps.edn`; maintained sources in `reference-code/datahike` dependency checkout | Existing CLJS Datahike and `await` behavior only; neither becomes a lifecycle owner. |
| Typed writer protocol | protocol version `2`, current branch commits `f34b7bda` and `989b6ed3` | `src/seon/db/protocol.cljc`; `src/seon/db/transport/uds.clj`; `src/seon/db/writer.clj`; `src/seon/db/registry.clj` | Closed create/release/delete, exact attachment/head fences, branch-as-database equality, and observational reopen exist. |
| Non-autonomous runtime | commit `e0bd14b6`, after attachment-scoped projection `81129753` | `src/seon/client.cljs`; `src/seon/runtime/admission.cljs`; `test/seon/client_runtime_test.cljs` | One launch capability suppresses autonomous writes and one retryable stop closes every inverse. Combined gate: 38 tests/316 assertions. |
| Blob storage view | current branch | `src/my/blob.cljs`; `test/my/blob_test.cljs` | One process-local writable directory plus ordered read-only bases already exists. |

The decisive Datahike constraints are source-derived. `branch!` publishes a new
branch in the same store; `delete-branch!` refuses an active connection; and
maintained release waits for the connection resources. Seon's registry adds
the missing application fences: a branch is open-only, its durable roster
entry must exist, the source physical database must already be registered in
that writer process, and the connected attachment must equal the requested
one. Therefore a writer restart must reopen the source route before reopening
any target branch route.

## Read-only operator observation

The current default descriptor was derived without starting or stopping any
process:

```clojure
{:seon.dev.config/process-dir
 "/Users/sean/src/seon/tmp/seon-operator"
 :seon.dev.config/cluster-name "default"
 :seon.dev.config/cluster-dir
 "/Users/sean/src/seon/data/clusters/default"
 :seon.dev.config/http-port-file
 "/Users/sean/src/seon/tmp/seon-port"
 :seon.dev.config/writer-repl-port-file
 "/Users/sean/src/seon/tmp/seon-writer-repl-port-default"
 :seon.dev.config/publish-socket
 "/Users/sean/src/seon/tmp/seon-cluster-default-pub.sock"
 :seon.dev.config/request-socket
 "/Users/sean/src/seon/tmp/seon-cluster-default-req.sock"
 :seon.dev.config/client-build-id "client"
 :seon.dev.config/artifact-flavor
 :seon.dev.artifact.flavor/default}

```

`seon.dev.process/specs` always derives watcher, writer, and pod together. The
writer route and physical path come from `cluster-name` and `cluster-dir`; the
pod receives that same environment. `seon.db.replica` again derives route and
path from `SEON_CLUSTER_DIR`. `my.blob` derives its default writable archive
from it. This is convergent for an ordinary autonomous cluster but cannot
describe a pod-only attachment to a branch in another process's writer.

`bin/acme` is already a semantic wrapper over the same operator and correctly
owns an independent artifact flavor, Shadow cache/build, writer, sockets,
database, web endpoint, and process directory. A branch sourced from ACME must
reuse the ACME watcher and writer. A default branch must reuse the default
watcher and writer. The implementation must consume flavor-owned descriptors;
it must not hard-code `bin/acme`, copy maintained dependency overrides into
`acme/deps.edn`, or let a branch watcher own the canonical `test` build.

## One launch descriptor

Create one shared `seon.launch` data contract. The operator atomically writes
it under the branch target's process directory and passes only its pathname as
`SEON_LAUNCH_DESCRIPTOR`. The normal default and ACME paths also derive the
same shape, so branch launch is a specialization of one mechanism rather than
a parallel startup path.

```clojure
{:seon.launch/runtime
 {:seon.launch/runtime-cluster "experiment-<id>"
  :seon.launch/artifact-flavor :seon.dev.artifact.flavor/default
  :seon.launch/client-build-id "client"
  :seon.client/launch-capability {:seon.client/autonomous? false}}

 :seon.launch/database
 {:seon.db.protocol/database-name "experiment-<id>"
  :seon.db.coordinate/attachment
  {:seon.db.coordinate/database-id #uuid "..."
   :seon.db.coordinate/branch :experiment-<id>}
  :seon.db.coordinate/coordinate
  {:seon.db.coordinate/database-id #uuid "..."
   :seon.db.coordinate/branch :experiment-<id>
   :seon.db.coordinate/commit-id #uuid "..."
   :seon.db.coordinate/t 536870912}
  :seon.db.protocol/backend :file
  :seon.db.protocol/database-path ".../data/clusters/default/db"}

 :seon.launch/writer-owner
 {:seon.launch/writer-cluster "default"
  :seon.launch/request-socket "...default-req.sock"
  :seon.launch/publish-socket "...default-pub.sock"
  :seon.launch/writer-repl-port-file
  ".../seon-writer-repl-port-default"}

 :seon.launch/process
 {:seon.dev.config/process-dir ".../tmp/branches/experiment-<id>"
  :seon.dev.config/log-dir ".../logs/branches/experiment-<id>"
  :seon.dev.config/http-port 0
  :seon.dev.config/http-port-file ".../http.port"}

 :seon.launch/blobs
 {:my.blob/writable-dir ".../branches/experiment-<id>/blobs"
  :my.blob/read-only-dirs [".../data/clusters/default/blobs"]}}

```

The example is a shape, not an instruction to duplicate schemas. Shared
coordinate, protocol, capability, storage-view, artifact, and process shapes
are referenced from their existing owners. `seon.launch` owns only the closed
composition and the relationship invariants:

- runtime cluster is a unique MCP/process/web identity, not a database id;
- database route is the writer-returned target logical name;
- attachment and coordinate must agree, and the ensure response must equal
  both plus backend and path before the pod connects locally;
- writer-owner endpoints are the source cluster's existing endpoints;
- artifact flavor/build must equal the writer owner's cluster flavor;
- branch launch requires `autonomous? false`;
- branch writable blobs and process paths are target-private; and
- no target-private path may equal or contain the source database or blob base.

The descriptor is immutable launch input, not a durable database authority.
The native Datahike branch and committed database facts remain truth. Retained
operator state may keep the descriptor for exact cleanup/restart, but runtime
code must not mutate it or infer missing keys from naming conventions.

## Ownership and deletion map

| Resource | Owner while branch pod is live | Release or deletion rule |
|---|---|---|
| Source Datahike database and `:db` connection | source writer cluster | Never stopped or deleted by branch close. |
| Native target branch and target logical route | source writer registry | Stop pod first; exact release target attachment/head; exact delete through the source route; prove roster absence. |
| Request/publish sockets and writer CLJ REPL port | source writer cluster | Shared read-only coordinates in branch descriptor; branch operator never removes them. |
| Shadow watcher/cache/build output | source artifact-flavor operator | Reused by branch pod; branch close never stops watcher or deletes output. |
| Branch pod process, web port file, advertisement, log/process state | branch target operator | Stop and prove absent before writer release; retain failure evidence when absence is unproved. |
| Local CLJS Datahike connection/feed/listeners | branch pod runtime | `stop-runtime!` detaches and awaits release before process exit. |
| Branch blob writable overlay | branch target operator | Delete only after pod absence and successful native branch deletion; retain on any uncertain cleanup. |
| Source blob bases | source cluster | Read-only to branch; never materialized into or removed by branch close. |
| Launch descriptor | branch target operator | Retain through failures and restart; archive/remove only after every owned inverse is proved. |

Normal `down` remains stack ownership. Branch `down` is pod ownership. This is
one process graph with a selected subset and external dependencies, not a
second supervisor: the branch pod spec depends on observed readiness of the
source watcher/writer but does not own their process ids.

## Replica, feed, MCP, and blob consumption

`seon.db.replica/database-config` is already branch-capable when given the
writer-returned attachment. Replace the namespace-load derivations of route,
database path, and sockets with the validated launch descriptor. Its
`ensure-database!` request carries target route, exact attachment, backend, and
path. It rejects any response whose route, complete coordinate attachment,
backend, or path differs before `d/connect`. Feed filtering continues to use
the target route while transport connects to the writer-owner publish socket.

`seon.client/_main` reads the descriptor once, installs its storage view, and
passes the retained capability to the existing `start-runtime!`. There is no
second branch bootstrap. The storage view is claimed before any blob read; a
conflicting hot reload fails rather than switching archives in a live process.

CLJS MCP already probes every live Shadow runtime, pins exact runtime ids, and
rejects ambiguous cluster advertisements. It should continue advertising the
descriptor's unique runtime cluster. CLJ MCP currently derives
`tmp/seon-writer-repl-port-<runtime-cluster>`, which is wrong for a branch pod.
Its runtime inventory must associate runtime cluster with the descriptor's
writer-owner cluster and port file. `eval_clj` for a branch runtime therefore
targets the source writer intentionally; it does not imply a branch-local JVM.

## Failure and restart matrix

| Transition | Required result | Fail-closed behavior |
|---|---|---|
| Create at exact source coordinate | Writer returns target route, attachment, coordinate, backend, path; operator validates and publishes descriptor | Any mismatch deletes only a newly created unpublished branch through the existing writer cleanup; no pod starts. |
| Pod launch after create | Exact ensure opens target route observationally; local replica attaches same branch; blob overlay/base and non-autonomous capability are retained | Stop partial pod, exact-release target route, exact-delete target branch. Retain descriptor/overlay/logs if any inverse is unproved. |
| Branch pod restart, writer alive | Reuse descriptor; ensure returns the same attachment and current target head; no autonomous write occurs | Moved/missing attachment, route conflict, or path mismatch prevents local connect. |
| Source writer restart | Ordinary source route reopens first. Branch operator then ensures target route with exact attachment/path and proves its retained expected head before pod attach | Missing durable branch, unavailable source route, or moved head prevents branch pod restart. Never create a main database under the target route. |
| Source watcher restart | Branch pod reconnects only to the same flavor/build; MCP re-probes its new runtime id by advertisement | Wrong flavor/build or absent owner watcher leaves branch target degraded, not silently attached to another build. |
| Operator interrupted during reconcile | Every child started by the interrupted transition is unwound, or the command explicitly reports retained ownership and performs the same unwind before returning | Never leave a newly started watcher/writer/pod adopted by PID 1 merely because the parent Babashka process received SIGINT. A later `down` being able to reap it is recovery, not successful interruption cleanup. |
| Pod crash | Source writer retains target route/branch; operator reports pod dead and keeps descriptor/overlay | No lifecycle delete occurs until an explicit close retries ordered teardown. |
| Writer unavailable while pod lives | Reads may continue from immutable local value; writes and replay fail through existing transport errors; autonomy remains disabled | Do not start another writer. Restart recovery reopens source then target route. |
| Explicit branch close | Stop pod; prove process/endpoints absent; release target exact attachment/head; delete exact branch; prove roster absence; remove target-only files | Stop at first unproved inverse and retain all remaining identity/evidence. |
| Source head advances | Branch remains on its independent target head; source changes do not alter its attachment | Close/delete fences target head and source route, never a remembered bare `t`. |
| ACME-derived branch | Reuse ACME flavor, `acme-client`, ACME writer sockets/port, and ACME source blob base | Never touch default watcher/writer or start ACME's `test` build. |

## Smallest path-bounded implementation slice

Implement and review this boundary before restart/crash recovery expands:

1. Add the closed shared composition and invariants in
   `src/seon/launch.cljc`, with pure default-descriptor derivation and tests.
2. Extend `seon.dev.config`, `seon.dev.process`, and `seon.dev.cli` so the one
   operator can atomically publish a descriptor, select a pod-only owned process
   subset, call the existing typed lifecycle protocol through the existing CLJ
   UDS transport, and expose structured status. Add only the Transit/protocol
   dependencies needed by that reused transport to `bb.edn`.
3. Change `seon.db.replica` to consume route, attachment, backend, path, and
   writer sockets from the descriptor and validate the exact ensure response.
   Preserve `database-config`, feed, replay, and writer implementations.
4. Change `seon.client` bootstrap to install descriptor capability and the
   existing `my.blob` storage view before calling the one `start-runtime!`.
5. Extend `seon.dev.mcp` discovery so a runtime advertisement resolves both its
   CLJS watcher/build and its writer-owner port file. Keep ambiguity rejection.
6. Add focused operator, writer/protocol, replica, client, blob, runtime-id,
   and MCP tests. Then run one default-derived and one ACME-derived branch proof
   without restarting or resetting the source clusters during iteration.

The read-only audit also received direct interruption evidence from the active
operator lane. During `bin/seon restart`, source changed after the artifact
plan; the watcher compiled `client` and `test`, and readiness correctly remained
false because the current-client digest no longer matched. Ctrl-C ended the
parent Babashka process with exit 130 but left recorded watcher PID `68712`
alive under PPID 1. A later `bin/seon down` reaped it cleanly. This is tracked
at [[operator-interruption-can-orphan-managed-process]]. It is a prerequisite
for destructive lifecycle graduation and for branch create/launch rollback,
but it does not expand the descriptor/replica first sub-slice.

Exact anticipated paths are `src/seon/launch.cljc`, `src/seon/client.cljs`,
`src/seon/db/replica.cljs`, `src/my/blob.cljs`, `script/seon/dev/config.clj`,
`script/seon/dev/process.clj`, `script/seon/dev/cli.clj`,
`script/seon/dev/mcp.clj`, `bb.edn`, and their existing focused test
namespaces. `bin/seon` and `bin/acme` remain thin wrappers. Do not add a
`bin/branch`, copy a writer jar, or create a second process manager.

The first implementation sub-slice should stop after descriptor derivation,
operator validation/publication, and exact replica consumption under tests. It
must not yet add branch create/delete CLI commands. That proves the
cross-runtime data contract before lifecycle mutation is exposed. The next
sub-slice adds the typed operator transition and the complete failure matrix.

## Acceptance evidence

- Pure descriptor tests reject crossed route/attachment, source/target path
  overlap, autonomous branch launch, flavor/build mismatch, and missing writer
  ownership.
- Focused replica proof sends exact target ensure data, connects the returned
  branch, filters feed events by target route, and rejects every mismatched
  response without connection publication.
- Focused client proof installs the descriptor's blob view and capability once,
  reconstructs with no coordinate change, and closes every local inverse.
- Operator proof starts only the branch pod and never owns/stops the source
  watcher or writer. Status distinguishes owned processes from external
  dependencies.
- A SIGINT at every start-order boundary unwinds only processes newly started
  by that transition and leaves no recorded live process reparented to PID 1.
- MCP proof addresses duplicate agent ids by runtime cluster in CLJS and maps
  branch-qualified CLJ evaluation to the exact writer-owner dynamic port.
- A live default branch and ACME branch run simultaneously, each reads inherited
  source facts/blobs, accepts isolated intentional writes, survives pod restart,
  and leaves both source heads unchanged.
- A writer restart reopens source then target route from the retained descriptor
  with the same attachment/path. A missing or moved branch refuses startup.
- Close proves pod absence, exact release/delete, roster absence, and deletion
  of only target-private overlay/process files. Injected failure at each inverse
  retains enough descriptor and evidence for retry.

Only after this matrix is green should the roadmap proceed to quiesced clean
restart/crash policy, promotion materialization, restore/undo, and ordered
multi-form process-failure proof.
