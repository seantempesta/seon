---
type: research
status: completed
tags: [research, database, flow, pod]
---

# Branch CLI, status, MCP, and first live gate audit

## Result

The retained native-branch transition is ready to become public, but the
consumer boundary is not a CLI-only change. One exact runtime identity must be
projected consistently through four existing owners:

- `seon.dev.branch` derives and retains the open/restart/close request;
- `seon.dev.process` derives current owned and external-process health;
- `seon.client/runtime-advertisement` publishes the selected runtime plus its
  immutable writer owner; and
- `seon.dev.mcp` selects that advertised runtime for CLJS and its advertised
  source writer for CLJ.

The shortest implementation slice adds `branch open`, `branch restart`,
`branch close`, and `branch status` to `bin/seon` while keeping
`branch/open!`/`close!` and `process/with-startup-ownership` as the only
lifecycle and inverse owners. The first live checkpoint is deliberately a
default-derived **branch-pod restart** while the source watcher and writer stay
up. Source-writer restart/crash recovery is the following roadmap boundary and
must not be claimed by this gate.

Two exact source gaps would otherwise make a plausible CLI lie:

1. `client/runtime-advertisement` still advertises
   `replica/database-name`, the basename of ambient `SEON_CLUSTER_DIR`, rather
   than the descriptor's `:seon.launch/runtime-cluster`. A default-derived
   branch would therefore advertise `default`, collide with the source pod,
   and be impossible to address as its retained runtime.
2. CLJ MCP still derives
   `tmp/seon-writer-repl-port-<requested-runtime-cluster>`. A branch named
   `default-proof` has no JVM writer there; its descriptor intentionally names
   the default writer's actual port file. CLJS discovery is already
   advertisement-driven, but CLJ discovery has not yet consumed that data.

## Exit measure

One public branch name deterministically selects one retained lifecycle file,
runtime cluster, target database route, Datahike branch keyword, pod process
directory, log directory, HTTP port file, and blob overlay. Repeating `open`
reconciles the same intent; `restart` replaces only that pod; `close` runs the
existing exact pod-absence → fresh-head release → native-delete inverse.
Structured status derives from the retained record and live process probes,
and never labels the immutable creation cut as the current head.

While source default remains live, both MCP runtimes then prove the same
branch:

- `eval_cljs` resolves `default-<name>/root`, writes a branch-only fact, and
  reads it after the branch pod has a different process lifetime;
- `eval_clj` with cluster `default-<name>` deliberately reaches the descriptor's
  `default` writer and resolves the target logical route; and
- after close, the target route, durable Datahike branch, process record,
  listener, lifecycle file, and branch-private paths are absent while the
  source does not contain the branch-only fact.

## Dependency ledger

| Dependency or mechanism | Selected version or SHA | Exact grounding source | Existing Seon owner or proof |
|---|---|---|---|
| Datahike | `417649383c65e13f15ea41d394fb1ed742477965` | `reference-code/datahike/src/datahike/versioning.cljc:116-229`; `branch!` publishes a same-store branch and `delete-branch!` refuses an active connection | `seon.db.registry/create-branch!`, `release-attachment!`, and `delete-branch!`; typed writer protocol at commit `f34b7bda` |
| Konserve | `df6818d43ea3363a808cd051c0d68917f1b987a9` | `reference-code/konserve/src/konserve/filestore.clj`; `reference-code/konserve/src/konserve/impl/defaults.cljc` | One shared physical store; a branch owns no copied database and no second writer |
| Clojure / ClojureScript | `1.12.0` / `1.12.145` (`r1.12.145` commit `bd23d9a2475d822ea8dfd65deaa6732428b9ed25`) | `deps.edn`; `reference-code/clojurescript` | JVM writer and descriptor-driven Node pod |
| Malli | `0.20.0`; source mirror `80138076960e7820523b4cb932c5b5d1936d4e7f` | `reference-code/malli/src/malli/core.cljc` | Closed `seon.launch`, branch request/record, protocol, and public function schemas |
| Babashka / `babashka.process` | Babashka `1.12.212`; process `v0.6.25`, `16a84e0af0da51b8c84e289970f6b7cc35b35d18` | `reference-code/babashka-process/src/babashka/process.cljc:432-445`; process creation/shutdown-hook behavior | `bin/seon` → `seon.dev.cli`; `seon.dev.process` owns detached identity, readiness, status, and signal-safe reverse unwind |
| Shadow CLJS | selected fork `4e72595f57618f5c43388ad13d5136cd3bede566` | `reference-code/shadow-cljs/src/main/shadow/cljs/devtools/api.clj:218-231,397-407`; `repl-runtimes` enumerates live runtime ids and `nrepl-select` pins one | `seon.dev.mcp/probe-advertisements!`, `runtime-id/select-runtime`, and exact client-id session pinning |
| Transit CLJ | `1.0.333`, tag commit `12f50e4391208d36f910a39dd947cefabf77dc52` | `reference-code/transit-clj/src/cognitect/transit.clj`; selected in `deps.edn` | Existing synchronous `seon.db.transport.uds/call!`; no shell/JSON lifecycle client |
| Maintained CLJS async forks | `superv.async` `3e6ed755f83634c9e9bbb58707f9446420d32ce9`; `partial-cps` `1e119b03ea908ad925b98f9ba0a26371c65441e3` | `deps.edn`; maintained dependency sources reached through `reference-code/datahike` | Existing replica, `^:async`, and `await` behavior only; neither owns operator state |
| Immutable launch descriptor | current `seon.launch` | `src/seon/launch.cljc`; `test/seon/launch_test.cljs` | Separates runtime cluster, target route/attachment, source writer owner, pod process paths, and blob view |
| Retained branch lifecycle | `74bfa7e2`, exact adoption `e7bd160c`, signal-safe inverse `c95f8e03` | `script/seon/dev/branch.clj`; `script/seon/dev/process.clj`; `test/seon/dev/branch_test.clj` | One desired-state/phase record and one ordered inverse; branch-only signal gate is 4 tests/61 assertions and combined branch/CLI/process is 34/190 |

The dependency versions above are the currently selected coordinates in
`deps.edn`, not older SHAs quoted by pre-integration audits.

## Read-only probes

No live cluster or ACME process was changed. `bin/seon status --edn` reported
the default target down with watcher, writer, and pod absent. The published
artifact remained readable, so this is a runtime-state observation rather than
an artifact claim.

A pure Babashka descriptor probe then derived a default branch without opening
a socket or writing state:

```clojure
{:ambient-cluster "default"
 :runtime-cluster "default-proof"
 :database-route "default-proof"
 :branch :seon.branch/default-proof
 :writer-cluster "default"
 :writer-repl-port-file
 "/Users/sean/src/seon/tmp/seon-writer-repl-port-default"}
```

This is the decisive MCP falsifier. Runtime cluster, database route, Datahike
branch, and writer cluster are related but different coordinates. Deriving the
writer port from the runtime-cluster string manufactures a nonexistent writer.

## Current source inventory and exact gaps

### Retained lifecycle owner

`script/seon/dev/branch.clj` already validates the complete open request,
retains exact source/create/target/descriptor data, creates or adopts through
the typed UDS writer, starts only the pod, and closes through a freshly ensured
target head. It deliberately has no public name-to-request projection, record
inventory, read-only status function, or pod-only restart function. The CLI
therefore cannot safely construct its eight path/identity fields independently.

Add one closed public target request in this namespace. A validated user slug
and source configuration derive all internal names once. The source cluster
prefix is part of runtime identity so simultaneous default and ACME branches
with the same user slug cannot both advertise `proof/root`.

Recommended deterministic projection for slug `proof` from source `default`:

| Coordinate | Derived value |
|---|---|
| runtime cluster | `default-proof` |
| target database route | `default-proof` |
| Datahike branch | `:seon.branch/default-proof` |
| lifecycle record | `<source-process-dir>/branches/default-proof.edn` |
| pod process dir | `<source-process-dir>/branch-processes/default-proof` |
| log dir | `<source-log-dir>/branches/default-proof` |
| HTTP port / file | `0`; `<source-process-dir>/branch-ports/default-proof.port` |
| blob overlay | `<checkout>/data/branches/default-proof/blobs` |

The lifecycle record stays outside the target process directory. If private
path removal fails after deleting part of that directory, exact retry evidence
must still exist. Existing `launch/branch-descriptor` path-overlap validation
remains the final guard before writer mutation.

`restart!` is composition, not a new state machine: validate the retained open
record, stop and prove only its target pod absent, then call the existing open
reconciliation. An interruption after stop leaves the exact branch record open
and retryable. It must never call `close!`, because close deletes the native
branch and blob overlay.

### CLI and status

`script/seon/dev/cli.clj` has no `branch` dispatch. Its normal `status` calls
`process/status` only for the source descriptor. `down` correctly owns only the
ordinary stack and must not silently close retained branches.

The minimal public surface is:

```text
bin/seon branch open <name>
bin/seon branch restart <name>
bin/seon branch close <name>
bin/seon branch status <name> [--edn]
```

`open` is create-or-exact-adopt plus pod reconcile. `close` is the existing
complete inverse, not separate public release/delete commands. Successful
commands print the runtime cluster, target route, Datahike branch, web endpoint,
and lifecycle phase from returned data. They never print or persist a guessed
head.

Ordinary `status --edn` should include a vector of retained branch status
projections so a forgotten branch is visible. That vector is obtained by
validating lifecycle records in the one configured branch-record directory; it
is not another registry. Human status prints one line per retained branch.
Successful close removes the lifecycle file, so the projection naturally
disappears.

`process/status` currently derives branch `all-ready?` from the one owned pod
only. It does not report or re-probe the pod spec's external watcher/writer
dependencies. A branch can consequently look ready after its source owner has
gone away. Generalize the existing status projection: report external
dependency identity/readiness and require it for target readiness. Reuse
`external-dependency-ready?`; do not add branch-specific pids or readiness
files.

Branch status should merge:

- validated retained desired-state/phase and immutable creation coordinate;
- the descriptor's target route/attachment, runtime, writer owner, and paths;
- generalized live owned/external process status; and
- web endpoint only when the exact pod record and readiness probe agree.

The descriptor coordinate is explicitly `coordinate-at-launch`. Current head
comes from the attached replica or writer registry during live proof, not from
the retained file.

### CLJS and CLJ MCP discovery

`seon.dev.mcp` already enumerates every flavor-owned Shadow port, every active
build, and every connected runtime, then calls
`seon.client/runtime-advertisement` in a session pinned to its exact Shadow
client id. `runtime-id/select-runtime` already rejects a bare `root` found in
several pods. Preserve that mechanism.

Change the runtime advertisement to take its cluster from
`replica/process-launch-descriptor`'s `:seon.launch/runtime`, not
`replica/database-name`. Merge the descriptor's writer cluster and
writer-REPL-port file into the same advertisement. Agent ids remain a database
projection; writer ownership remains immutable launch data. No filesystem scan,
MCP registry, or second membership atom is needed.

CLJ selection then resolves `cluster` as a **runtime cluster** against the same
advertisement inventory and reads its advertised writer port file. Several
advertisements for one runtime cluster fail as ambiguous. For this MCP server's
ordinary own cluster, the existing explicit environment override remains the
boot/down fallback; a non-own branch cluster with no live advertisement fails
instead of guessing `tmp/seon-writer-repl-port-<branch>`.

This preserves the useful semantic distinction:

- `eval_cljs(cluster=default-proof)` selects the default-proof pod;
- `eval_clj(cluster=default-proof)` selects the default-proof pod's declared
  writer owner, which is the default JVM process; and
- both can inspect the target logical route `default-proof`, but there is no
  branch-local JVM.

The default CLJS session remains pinned to this MCP server's ordinary own
cluster. A branch caller uses `agent_id="default-proof/root"` or an explicit
cluster-pinned session; Shadow's `:runtime-select :latest` must never become the
fallback.

## Ordered implementation slice

1. In `script/seon/dev/branch.clj`, add the closed slug/target projection,
   validated record inventory/status projection, and pod-only `restart!` by
   composing the existing stop/open owners. Keep all lifecycle mutation in
   `open!`/`close!` and their current helpers.
2. In `script/seon/dev/process.clj`, make structured status project and gate on
   already-declared external dependencies. Preserve the existing process
   record, readiness probes, and ownership-conflict rules.
3. In `script/seon/dev/cli.clj`, parse only the four public branch commands,
   call the branch owner, and include retained branch projections in ordinary
   status. Add no low-level create/release/delete escape hatch.
4. In `src/seon/client.cljs`, advertise descriptor runtime and writer owner.
   Keep `seon.dev.runtime-id` as the pure agent-id selection rule.
5. In `script/seon/dev/mcp.clj`, use the same advertisement inventory for CLJ
   writer-owner selection while retaining current session restart semantics,
   exact Shadow pinning, and ambiguity rejection.
6. Run focused operator and CLJS gates under a source freeze. Only after they
   pass, run the first live default branch checkpoint below. ACME remains
   untouched in this slice.

Expected production owners are exactly:

- `script/seon/dev/branch.clj`;
- `script/seon/dev/process.clj`;
- `script/seon/dev/cli.clj`;
- `src/seon/client.cljs`; and
- `script/seon/dev/mcp.clj`.

Expected focused tests are:

- `test/seon/dev/branch_test.clj`;
- `test/seon/dev/process_test.clj`;
- `test/seon/dev/cli_test.clj`;
- `test/seon/dev/mcp_test.clj`;
- `test/seon/agent_lifecycle_test.cljs`; and
- `test/seon/dev/runtime_id_test.cljs` only if the pure candidate contract must
  name the writer fields. Extra candidate keys already pass through selection,
  so prefer leaving its resolution grammar unchanged.

## Failure matrix

| Failure cut | Required observation | Forbidden result |
|---|---|---|
| invalid/blank/path-like branch name | reject before lifecycle path or writer request | path traversal, partially retained intent, coerced keyword |
| derived private path overlaps source database/blob base | existing descriptor invariant rejects before create | branch mutation followed by path cleanup guess |
| source artifact, watcher, or writer unavailable | open fails before branch mutation | start a watcher/writer for the branch or publish pod intent |
| retained name resolves to different request/descriptor | exact consistency rejection | overwrite or fork a second record for the same name |
| branch pod spawn/readiness interrupted | `c95f8e03` inverse drains owned pod and deletes only invocation-created branch | orphan, delete adopted branch, or claim converged pod |
| branch restart interrupted after pod stop | retained open record and native branch remain; later open resumes | native delete, source process stop, or loss of lifecycle evidence |
| source watcher/writer becomes unavailable after open | branch status becomes degraded and identifies the external dependency | ready based only on branch HTTP 200 |
| branch pod crashes | record/branch/overlay remain, process status is dead/degraded | implicit delete or record cleanup |
| CLJS branch cluster advertised as source cluster | focused test fails; no live gate | ambiguous `default/root` accepted as branch proof |
| two runtime advertisements claim one cluster | MCP returns explicit ambiguity | last/latest runtime selection |
| branch CLJ writer port derived by naming convention | focused test expects advertised source port and fails | file lookup at `...-default-proof` |
| advertised writer port changes after writer restart | default CLJ session reconnects; named session returns lost-state data | reuse old socket/session or imply database loss |
| branch runtime absent | branch-qualified CLJ/CLJS selection fails clearly | guess an ordinary writer or another pod |
| close cannot prove pod absence | retained record/branch remain and no destructive writer request occurs | release/delete after uncertain process inverse |
| close release/delete/private cleanup fails | exact record remains at first unproved inverse | report closed or remove evidence |

## Focused acceptance

Run the operator owners together so the public projection, process health, and
MCP mapping cannot pass in isolation:

```bash
bin/seon test operator \
  seon.dev.branch-test \
  seon.dev.process-test \
  seon.dev.cli-test \
  seon.dev.mcp-test
```

Required assertions include:

- one name derives stable, source-prefixed identities and non-overlapping
  paths; malformed names have no effects;
- open/reopen/restart/close call only the existing lifecycle owners and
  restart never sends release/delete;
- ordinary status inventories retained records; branch status distinguishes
  creation coordinate from live process health;
- external watcher/writer loss degrades branch status;
- a branch advertisement uses descriptor runtime cluster and writer owner;
- CLJS resolves duplicate `root` ids only when cluster-qualified;
- CLJ `cluster=default-proof` reads the advertised default writer port file;
  duplicate/missing advertisements fail without filename fallback; and
- a changed writer port self-heals the default CLJ session while invalidating a
  named session as already specified.

The CLJS selection is:

```bash
bin/test-cljs \
  --test=seon.agent-lifecycle-test/resume-reconstructs-process-state-without-writing-database-state \
  --test=seon.dev.runtime-id-test
```

If the runner cannot combine a namespace and var selector in one invocation,
run the two selectors serially against one source-frozen artifact; never start
overlapping CLJS suites.

## First live default branch proof

This checkpoint starts only after source-editing lanes pause and the default
cluster is ready from one current artifact digest. Use a unique slug such as
`proof-<short-id>`; below, `proof` denotes that suffix and `default-proof` its
derived runtime/route.

1. Record source evidence before mutation:

   ```bash
   bin/seon status --edn
   ```

   Through MCP, record default CLJ and CLJS complete coordinates and prove the
   unique claim is absent from the source.

2. Open the retained branch and inspect public status:

   ```bash
   bin/seon branch open proof
   bin/seon branch status proof --edn
   bin/seon status --edn
   ```

   Assert one target pod is alive, source watcher/writer are external and
   ready, the source PIDs are unchanged, the web endpoint is ready, the target
   attachment is the source database id plus `:seon.branch/default-proof`, and
   status labels the retained point as the creation coordinate.

3. Prove both MCP routes. `eval_cljs` with
   `agent_id="default-proof/root"` must return the branch head and must not be
   ambiguous with `default/root`. `eval_clj` with
   `cluster="default-proof"` must evaluate in the default writer and
   `seon.db.registry/resolve-connection` must return the `default-proof` target
   route and matching attachment.

4. Write one branch-only fact through the pod, not through the JVM registry.
   Use the already-schema'd `my.kb/remember` boundary with a unique claim and
   `seon.db/with-agent "root"`. Because direct Shadow eval returns before a
   Promise settles, retain its result in a temporary process-local atom and
   poll that atom in a later MCP call. Require a successful database envelope,
   then query the claim and record the advanced complete branch coordinate.
   The temporary atom is diagnostic only; the claim and coordinate are the
   proof.

5. Restart only the branch pod:

   ```bash
   bin/seon branch restart proof
   bin/seon branch status proof --edn
   ```

   Assert the branch pod PID/start identity changed, source watcher/writer PIDs
   did not, the same cluster-qualified CLJS MCP address re-resolved a new Shadow
   client id, the branch-only claim remained queryable, and the complete branch
   head remained at or after the committed write. Also re-run branch-qualified
   CLJ MCP to prove its writer-owner mapping survived the pod lifetime change.

6. Close through the public inverse:

   ```bash
   bin/seon branch close proof
   bin/seon status --edn
   ```

   Assert the lifecycle file, target pod record/listener/port, target route,
   durable `:seon.branch/default-proof` roster entry, blob overlay, process
   directory, and log directory are absent. Cluster-qualified branch MCP must
   no longer resolve. Default CLJ/CLJS MCP remain live, and the source query
   still does not contain the unique branch claim.

7. Retain exact commands, PIDs, source and branch coordinates, transaction
   envelope, claim query, MCP routing results, status maps, roster/route
   absence, and artifact digest in the PRD roadmap before releasing the source
   freeze.

This proves public branch lifecycle, pod-only restart, database isolation, and
both MCP discovery paths. It does **not** prove source writer restart, crash
recovery, restore/undo, ACME parity, or promotion; those remain ordered later
gates.
