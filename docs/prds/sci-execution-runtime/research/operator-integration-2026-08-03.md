---
type: research
status: active
tags: [research, runtime, operator, boot]
---

# Operator integration into the Seon runtime

## Recommendation

Keep a tiny OS launcher, but move the reachable control plane into the
packaged JVM as ordinary functions at the owners that already perform each
operation. Do **not** adopt Integrant. The current boot sequence and
`core.async.flow` already own ordered construction, teardown, readiness, and
long-running lifecycle; Integrant would add a second dependency graph and a
second set of lifecycle verbs without deleting either owner.

The recommended **target**, dependent on the owner rulings at the end of this
report, is:

1. a thin launcher creates the JVM process, captures its output, records its
   exact `(pid, start-instant, generation)`, discovers its minimal control
   endpoint, and retains TERM/KILL plus offline destruction as recovery paths;
2. the persistent minimal JVM base binds one process-root control REPL,
   acquires the lifetime store `flock`
   before opening Datahike, opens the process-root store, and publishes base
   readiness; and
3. the reachable JVM starts, stops, forks, reforks, configures, publishes, and
   inspects clusters through Seon's existing owners. `bin/seon` becomes a
   client of those functions rather than a program that manufactures Clojure
   forms containing the real control logic.

The persistent root REPL and store hold through a zero-cluster state are target
changes, not current architecture: today every cluster owns its REPL and the
process root shares only the store holder and executors. This answers an
important ambiguity in “flock before JVM.” The current
lifetime fence is acquired **inside the process-root JVM but before Datahike
opens** (`src/seon/cluster/store.clj:270-351`), and sibling clusters reuse that
held store (`src/seon/cluster.clj:1472-1497`). The Babashka operator acquires
the same lock itself only for offline proof and destructive reset
(`script/seon/fresh_operator.clj:2324-2417`). Moving the lifetime fence to the
parent OS process would require transferring or proxying custody across the
process boundary and would make the store outlive or diverge from its real
owner. Keep the lifetime fence in the minimal JVM base; keep the offline
destruction fence outside.

## Owner question and shortest answer

The packaged jar can control itself after two resources stand: a reachable
REPL/control endpoint and the one flock-held process-root store. Everything
above that seam is already implemented as JVM functions. Most of
`fresh_operator.clj` is therefore transport, reconciliation, and fallback
around functions Seon already owns—not runtime semantics that Babashka must
keep.

The irreducible outside layer is small but real. A dead or wedged JVM cannot
spawn, identify, log, or kill itself; a process that has not yet acquired the
store cannot use database facts to decide whether it may open that store; and
whole-root deletion must be possible precisely when the database cannot be
opened. Those are bootstrap/supervision exceptions, not a competing runtime.

## Dependency ledger

| Dependency or mechanism | Selected revision or owner | Evidence used |
|---|---|---|
| Integrant | vendored `bcad6bcf35b62d3a32a453dc26b6d3a4d659dc01` (`1.0.1-2-gbcad6bc`) | `reference-code/integrant/src/integrant/core.cljc:182-222,351-548,650-702` |
| `core.async.flow` | `dc35f3e0d7bc2eef502e77982f48641f025c8051`, published as `1.10.874-alpha3` | `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj`; `flow/impl.clj`; `flow/spi.clj` |
| Datahike branch and writer lifecycle | maintained fork `0e8601d7f2f6` | `reference-code/datahike/src/datahike/versioning.cljc`; `writing.cljc`; `writer.cljc` |
| Process-root custody | hidden runtime namespace | `resources/seon/operator/runtime.clj:1-28` |
| Store and lifetime flock | `seon.cluster.store` | `src/seon/cluster/store.clj:183-239,270-351` |
| Branch lifecycle | `seon.cluster.registry` | `src/seon/cluster/registry.clj:92-111,160-284` |
| Cluster boot sequence and readiness | `seon.cluster` | `src/seon/cluster.clj:1472-1677,1697-1740,1780-1867` |
| Database-backed configuration | `seon.config` | `src/seon/config.clj:137-275`; `script/seon/fresh_operator.clj:1719-1751` |
| Source publication | `seon.cluster` and `seon.cluster.source` | `src/seon/cluster.clj:550-872`; `script/seon/fresh_operator.clj:1753-1932` |
| Packaged initialization | `seon.artifact` plus build-produced pages | `build.clj:57-100,154-178`; `src/seon/artifact.clj:45-100` |
| Current operator | `seon.fresh-operator` | `script/seon/fresh_operator.clj:28-2479`; `bin/seon:1-18` |

The earlier recommendation to adopt Integrant narrowly is recorded in
[docs/prds/sci-execution-runtime/research/integrant-boot-design-2026-07-26.md](integrant-boot-design-2026-07-26.md).
Its expected simplification depended on merging then-separate host, writer,
and web processes. That merge is complete by a different mechanism: the
current tower directly owns the store, branch connections, SCI context, Flow
graphs, and web server. ADR-009 explicitly records that the Integrant
component lifecycle was deleted and superseded by branch-per-cluster plus
agents-as-flows
([docs/seon/architecture/decisions/009-cluster-jvm-topology.md](../../../seon/architecture/decisions/009-cluster-jvm-topology.md)).

There is a live authority conflict: the older working-edge block still says
the conditional Integrant decision is “resolved” and “not reopened”
([docs/prds/sci-execution-runtime/plan/unsettled.md](../plan/unsettled.md#L2755-L2766)),
while the active ADR and current tree contain no Integrant lifecycle. This
report recommends that the owner explicitly supersede the conditional ruling;
it does not treat the research recommendation as authority to implement ahead
of that ruling. The complete dependency audit is
[docs/prds/sci-execution-runtime/research/operator-integration-integrant-2026-08-03.md](operator-integration-integrant-2026-08-03.md).
The two independent first-party boundary audits are
[docs/prds/sci-execution-runtime/research/operator-integration-operator-boundary-2026-08-03.md](operator-integration-operator-boundary-2026-08-03.md)
and
[docs/prds/sci-execution-runtime/research/operator-integration-boot-tower-2026-08-03.md](operator-integration-boot-tower-2026-08-03.md).

## What the operator actually owns today

### Outside-process supervision

`fresh_operator.clj` currently owns several things a managed JVM cannot do for
itself reliably:

- It launches a detached `clojure -M:dev` child, redirects stdout and stderr
  to a cluster log, closes inherited descriptors, and gives the child an
  adoption gate (`script/seon/fresh_operator.clj:32-49,240-258,1447-1481`).
- It records a supervisor generation and log path plus exact process identity,
  then verifies that an advertisement matches the same `(pid, start-instant)`
  (`script/seon/fresh_operator.clj:95-179,1483-1505,1709-1716`).
- It can observe `ProcessHandle`, reconcile stale records, and signal an exact
  recorded generation when the REPL is unreachable. Shared-JVM fallback
  refuses an implicit signal that would stop sibling clusters
  (`script/seon/fresh_operator.clj:1507-1541,2106-2132`).
- It holds the operator command lock across current CLI lifecycle mutations
  (`script/seon/fresh_operator.clj:195-205`). In the target that external lock
  remains only around OS/file mutations such as spawn, process-record repair,
  signals, and destructive reset. It cannot serialize REPL/MCP/agent calls;
  their concurrency fences belong at the existing JVM operation owners.
- It can prove the store flock free and destructively remove only the scoped
  cluster root without following symlinks, even when no database can open
  (`script/seon/fresh_operator.clj:2324-2417`).

These stay outside. A packaged deployment may replace Babashka and Python with
a small native shell, service manager, launchd/systemd unit, or Java launcher,
but the responsibility does not move into the process it supervises.

### Reachable-runtime orchestration

The script also owns operations whose actual semantics already live inside
the JVM:

- Starting the first cluster builds a form that requires `seon.cluster` and
  calls `seon.cluster/start!`; adding a sibling cluster sends almost the same
  form through an existing cluster's prepl
  (`script/seon/fresh_operator.clj:1400-1445,1622-1717`).
- Config apply reads an EDN map outside, then sends a form whose only semantic
  operation is `seon.config/apply!`
  (`script/seon/fresh_operator.clj:1719-1751`).
- Complete and incremental publication call `seon.cluster/refresh-source!`;
  fork/refork delegates to `seon.cluster.registry/ensure-cluster!`,
  `reset-cluster!`, or `seon.cluster/refork!`
  (`script/seon/fresh_operator.clj:1753-1848`).
- Graceful stop sends a form that calls `seon.cluster/stop!`; only an
  unreachable runtime needs the signal fallback
  (`script/seon/fresh_operator.clj:2070-2184`).
- Live status is assembled from runtime instance state, advertisements,
  process liveness, and the Datahike branch roster
  (`script/seon/fresh_operator.clj:659-1068,1934-2048`).

These move inside. The migration should eliminate code-generated forms and
make the transport submit one ordinary data request to a stable function.
The owning functions remain in `seon.cluster`, `seon.cluster.registry`,
`seon.cluster.source`, and `seon.config`; do not create a generic lifecycle
manager that wraps all four. One small protected process-control boundary may
resolve hidden process-root custody and delegate to those owners for REPL/MCP;
it routes requests but does not become a second lifecycle owner. Agent-facing
code returns data and never receives that hidden custody.

## The minimal base and the full runtime

The current tower already exposes the right cut, but `start!` executes both
halves in one call.

### Minimal base

The minimal base must publish its dependencies and readiness in this order:

1. Resolve only the closed bootstrap data: operator root, store path, log path,
   and control bind. No database-owned dial belongs here
   (`src/seon/cluster.clj:57-60,1594-1605`).
2. Bind one process-root control io-prepl and publish `(pid, start-instant)`
   plus its real bound port. The current per-cluster implementation
   deliberately leaves its REPL alive after a later-layer failure
   (`src/seon/cluster.clj:1607-1647`); the process-root split must preserve that
   guarantee without requiring an ordinary cluster to own the control plane.
3. Acquire the lifetime flock and open the process-root Datahike store. Store
   readiness is a readable main connection; the fence is held until the last
   cluster releases the root store (`src/seon/cluster/store.clj:270-351`;
   `src/seon/cluster.clj:1780-1810`).
4. Publish a base-ready value/event containing the control coordinate, exact
   process identity, canonical root/store identity, and whether the root store
   is readable. The launcher waits on that event or process exit; a clock is
   only a loud backstop.

This is the packaged jar's true entry point. It should not arm agents, start a
web endpoint, index a source tree, or infer selected clusters yet. An empty
process-root JVM is a valid controllable state and remains alive until explicit
`down`; stopping the last cluster no longer implies reaping the process.

Two current seams prove this split is not merely a rename:

- `start!` compiles the cluster manifest before binding its claimed
  REPL-first server (`src/seon/cluster.clj:1594-1622`). The base REPL must use
  bootstrap data only; database-backed cluster config belongs above base
  readiness.
- The standalone jar installs packaged pages under one store/flock hold,
  releases it, then reacquires the store in `start!`
  (`src/seon/artifact.clj:51-70,72-100`). Packaged installation and cluster
  start need one uninterrupted process-root store hold. The open issue is
  [docs/seon/issues/artifact-releases-the-fence-between-install-and-start.md](../../../seon/issues/artifact-releases-the-fence-between-install-and-start.md).

### Full cluster boot sequence

After the base is reachable, the surviving current order remains valid:

- capture the published `current-src` commit and ensure the branch;
- open the branch connection and refuse an incoherent program graph;
- accrete the schema population and settle interrupted runs;
- reconcile config and ensure cluster/root-agent facts;
- derive the one cluster SCI context;
- start the work launcher, cluster graph, and per-agent graphs; and
- serve the web UI and extend the advertisement with its URL.

That is exactly the order in `stack-tower!`
(`src/seon/cluster.clj:1472-1571`). It is already a layered system in which
each step returns or publishes the stood value. The migration should expose
this existing boundary, not translate its layers into component keys.

## What becomes runtime functions

| Operation | Runtime owner and result | Outside fallback that remains |
|---|---|---|
| Start/add cluster | `seon.cluster/start!` over one request map; return the complete or degraded instance/readiness | spawn the first process-root JVM when none is reachable |
| Stop cluster | `seon.cluster/stop!`, instance-addressed and reverse-tower | exact recorded TERM/KILL only when the JVM is unreachable; explicit `down` terminates the persistent root JVM |
| Status/readiness | `seon.cluster/readiness` plus a process-root derivation over hidden running instances and the branch roster | process records, advertisements, `ProcessHandle`, and offline roster when no runtime is reachable |
| Apply config | `seon.config/apply!`/`apply-compiled!`; accept a parsed sparse map, not a file path | CLI reads the selected file and sends its ordinary EDN value |
| Publish current source | `seon.cluster/refresh-source!` in development; packaged publication consumes the build's admitted source rows/pages | dependency-cache preparation and checkout watching remain development tooling |
| Fork dormant cluster | `seon.cluster.registry/ensure-cluster!` from an exact published commit | none after the base store is reachable |
| Refork cluster | `seon.cluster/refork!` for a live instance or `registry/reset-cluster!` for a dormant branch | none after the base store is reachable |
| Stop all reachable clusters | reduce `seon.cluster/stop!` over the process-root registry, preserving instance identity | process census and exact signal for unreachable JVMs |
| Whole-root reset | no in-runtime form: orderly down first, then the outside launcher holds the store flock while deleting scoped data | the whole operation remains outside and explicit `--force` |
| Open browser/log tail | not runtime semantics | remain OS-client conveniences |

Status must stay a derivation. Branch existence is already the Datahike roster
fact (`src/seon/cluster/registry.clj:104-111`); config and source publication
already commit their own facts. Do not store `running`, `ready`, `healthy`,
port reachability, or Flow ping snapshots as database state. Those change
with the process and are queried from the current instance, process identity,
advertisement, and Flow graph.

If automatic process restart must reconstruct only the clusters that were
selected before death, that selection needs one explicit durable posture fact;
the branch roster alone cannot distinguish a stopped cluster from one desired
to restart. This is a schema/design decision, not permission to store observed
liveness. The process-root store has no current database-backed selected-set
owner, so implementation should stop for an owner ruling before declaring the
attribute or choosing which branch owns it.

## REPL, MCP, CLI, and agent call paths

The same runtime functions can serve three callers without three mechanisms:

- **REPL/MCP:** call the owning function directly in the process-root JVM and
  return admitted ordinary data. The current io-prepl already projects values
  for MCP (`src/seon/cluster.clj:87-130`).
- **`bin/seon`:** discover one base/cluster endpoint, submit the same request,
  and format the returned data. When no endpoint is reachable, it may spawn,
  report offline state, or perform an explicitly destructive recovery.
- **Agents:** never expose an in-eval lifecycle side effect directly. The
  simplest current shape is an agent-callable pure function returning a
  structured lifecycle request that the run loop commits and the system-side
  owner interprets after the terminal transaction. `my.message` is precedent
  only for the pure returned-value boundary: its delivery rows are derived and
  committed in that terminal transaction, while post-commit lifecycle
  interpretation is a new unresolved owner. The tree explicitly records that
  `seon.effect` does not exist yet (`resources/seon/schema.edn:1532-1540`). If
  the owner later classifies an operation as a genuine capability request, it
  crosses the one `seon.effect` owner when that owner is built. Every function
  remains callable in the cluster program graph either way; only the
  side-effect is deferred out of the eval.

This last distinction is required by the stateless claim-native model. Calling
`seon.cluster/stop!` directly from an interpreted eval would mutate runtime
semantics from inside the eval and could remove the connection needed to
settle its own receipt. The request must commit before the system interprets
it, and self-stop/process-stop needs an explicit terminal acknowledgement
order. No per-agent grants or function allowlist may be introduced.

## Integrant evaluation against the current system

Integrant is a small, coherent library, but it solves a problem Seon no longer
has.

The vendored source provides:

- a dependency graph derived from `ref`/`refset` values and deterministic
  topological ordering
  (`reference-code/integrant/src/integrant/core.cljc:182-222`);
- synchronous construction through `init-key` and reverse-order teardown
  through `halt-key!`
  (`reference-code/integrant/src/integrant/core.cljc:430-504,650-666`); and
- suspend/resume hooks that may reuse previous implementations
  (`reference-code/integrant/src/integrant/core.cljc:506-526,677-702`).

It does not provide a readiness event, supervision, health checks, restart
policy, process identity, a store fence, or durable lifecycle facts. Its
system map is process memory. Build failure carries a partial system and
requires the caller to decide cleanup
(`reference-code/integrant/src/integrant/core.cljc:409-455`).

In present Seon, adopting it would duplicate concrete owners:

- Tower dependency order is explicit and publishes the partial instance after
  every stood layer (`src/seon/cluster.clj:1472-1571`).
- Reverse teardown is explicit and waits for Flow completion before releasing
  the branch connection (`src/seon/cluster.clj:1414-1470,1780-1841`).
- Long-running owners already use Flow's start/stop/pause/resume and proc
  lifecycle. Integrant's halt/suspend/resume would be a second vocabulary and
  a second control path.
- Durable truth already belongs in Datahike. An Integrant config/system map
  would either mirror database facts or become a non-queryable authority.
- Hot function replacement uses Vars; topology replacement rebuilds a Flow
  graph. Integrant resume-by-config comparison adds a third live-update model.

The earlier proposed Integrant graph also assumed nested per-cluster systems
to prevent a root `refset` edge from halting siblings. The current branch-local
instances and explicit `stop!` already make that isolation direct. Recreating
the same ownership in Integrant would be relocation, not simplification.

## Migration seams

### 1. Seal the base request and readiness values

Split process-root base boot from cluster boot without changing the current
layer order. The first implementation proof is a packaged JVM that publishes
REPL readiness, acquires the store flock, opens the store, and remains
diagnosable with zero cluster instances.

The recommended endpoint is one process-root control REPL, not “the first
cluster” as a permanent controller. Current `start!` creates a prepl and
advertisement per cluster, so this intentionally changes the boot contract.
MCP/control functions must select the cluster explicitly; degraded cluster
instances remain inspectable from the root endpoint. Whether per-cluster REPLs
remain as separate eval surfaces or are removed must be owner-ruled before the
transport changes—quietly retaining two lifecycle control planes is not an
acceptable default.

Do not start by moving script functions wholesale. `fresh_operator.clj`
mixes OS observations, formatting, transport, and semantic calls; porting its
shape would put the old operator inside the jar.

### 2. Add direct functions at existing owners

Replace `launch-form`, `add-form`, `config-apply-form`, `init-form`,
`stop-form`, and `stop-all-form` with request maps and direct function calls.
The protected in-JVM process-control boundary may resolve hidden process-root
custody from `resources/seon/operator/runtime.clj`, but that namespace and its
live handles must remain absent from every cluster program graph
(`resources/seon/operator/runtime.clj:1-7`). The ordinary source-level
agent-facing control functions only construct lifecycle request values.

### 3. Move live reconciliation inside, retain offline reconciliation outside

The current operator derives truth from advertisements, the process-local
registry, open branches, process records, and the roster. Once reachable, the
JVM can derive its own registry/branch/readiness view. The launcher still
reconciles files and exact process identity when the JVM cannot answer.

The two results should use one ordinary data schema, but they are not the same
evidence. Runtime status may say what graphs and connections stand; offline
status may only say what records, files, processes, and a flock-free roster
prove. Never fill absent runtime evidence with a success-shaped default.

### 4. Separate development publication from packaged initialization

`init --changed` is tied to a checkout, clj-kondo dependency cache, and
reloadable source-analysis namespaces
(`script/seon/fresh_operator.clj:1821-1902`). It is development tooling.
The packaged jar must consume the one admitted program/schema population
produced by the build; it must not expect `src/` and `test/` beside the jar or
reconstruct a second indexer. Both paths publish through the same
`current-src` owner and guarded branch-head move.

### 5. Make agent-initiated control a returned lifecycle value

Define the lifecycle request and receipt facts before exposing any runtime
mutation to SCI. The agent-facing function returns the request as data; the run
loop commits it, and only a new post-commit system-side consumer invokes the
runtime owner. That consumer cannot synchronously stop the turn proc and then
await the same proc's completion; the exact dispatch and acknowledgement owner
remains an owner decision.
In particular, self-stop and process-stop need an event order that lets the
current terminal transaction settle before teardown begins. A timeout cannot
stand in for that completion. Do not build `seon.effect` solely to wrap this
lifecycle shape; use it only if the eventual effect contract independently
classifies the operation as a genuine capability request.

### 6. Shrink the launcher last

After each live operation uses the runtime function, delete the corresponding
code-generated form and duplicated semantic validation from
`fresh_operator.clj`. Retain only parsing/formatting, discovery, spawn,
process records, log capture, exact signaling, offline status, browser/log
conveniences, the external lock around those OS/file mutations, and destructive
root reset.

### 7. Preserve the current fallback proofs

The migration is not complete until the following classes remain covered:

- first JVM spawn, exact record adoption, and advertisement identity match;
- two clusters in one JVM, one-cluster stop, last-cluster stop leaving the root
  control endpoint reachable, and explicit `down` ending the root JVM;
- reachable and unreachable config/fork/refork/status paths;
- stale advertisement and reused-pid refusal;
- held-flock refusal across processes;
- degraded boot with REPL still reachable;
- source publication that leaves existing cluster branches sovereign;
- TERM-resistant child escalation by exact recorded identity; and
- symlink-safe whole-root reset under the held offline flock.

The standing tests already name these classes in
`test/seon/dev/fresh_operator_test.clj`,
`test/seon/cluster/boot_test.clj`, and
`test/seon/cluster/store_test.clj`. Implementation should migrate the tests to
the surviving boundaries rather than preserve script internals.

## Options for the owner

### Option 1 — Thin launcher plus the existing tower, no Integrant (recommended)

**Guarantee.** The OS layer retains only responsibilities a dead JVM cannot
perform. Once the base is reachable, every control operation is an ordinary
Seon function over explicit data, durable decisions/receipts are database
facts, and runtime status is derived. The current boot sequence and Flow remain the
only lifecycle mechanisms.

**Cost and risk.** Medium. It requires a real base/cluster split, stable request
and result schemas, a packaged initialization input, and careful terminal
ordering for agent-initiated self-stop. Most operation bodies already exist.

**Operational trade-off.** There are deliberately two evidence levels:
in-runtime full status and outside degraded/offline status. The launcher still
exists, but it becomes small and boring. The root JVM may remain alive with no
clusters, so `down` rather than “stop the final cluster” owns process exit.

**Capability given up.** No generic component graph or config-diff resume.
Seon keeps direct, domain-named lifecycle and cheap Flow topology rebuilds.

### Option 2 — Integrant inside the minimal base

**Guarantee.** Integrant deterministically orders selected component init and
reverse halt, while the outside launcher still owns process supervision.

**Cost and risk.** High. Every boot layer needs an Integrant key/method and
reference graph; each long-running component still needs Flow; database facts
still own config and recovery; readiness and partial cleanup still need Seon
code. The highest risk is divergent teardown between Integrant, Flow, and the
current instance-addressed `stop!`.

**Operational trade-off.** A generic system map makes REPL inspection of
constructed handles convenient, but it creates process-memory topology beside
the database program graph and hidden runtime registry.

**Capability given up.** The one-mechanism rule and the current direct mapping
from a cluster instance to its branch, graphs, context, and web service. This
option is justified only if a concrete prototype deletes the tower and more
code than it adds; present source gives no such deletion path.

### Option 3 — Keep Babashka orchestration, expose runtime RPCs incrementally

**Guarantee.** Lowest near-term change risk. First replace code-generated
forms with direct request functions, but leave selection, reconciliation, and
workflow ordering in `fresh_operator.clj`.

**Cost and risk.** Low initially, high if treated as the destination. It leaves
control logic split between Babashka and the jar and does not satisfy the
owner's self-controlling packaged-runtime goal.

**Operational trade-off.** Existing offline behavior and tests move least.
The jar remains dependent on the script for composition and recovery policy.

**Capability given up.** Agents and MCP cannot use one authoritative control
plane; every new operator verb risks another generated-form wrapper. Use this
only as the first migration checkpoint toward Option 1.

## Owner rulings needed before implementation

Option 1 is the recommendation, but six semantics must be explicit before
production edits:

1. **Integrant authority:** explicitly supersede or retain the conditional
   2026-07-26 adoption ruling. The current tree and active ADR support
   non-adoption, but the working-edge text still says the old decision must not
   be reopened.
2. **Restart selection:** where the durable decision “this cluster should be
   reconstructed when the process root returns” lives. It must be a fact, not
   inferred from a stale advertisement or conflated with branch existence.
3. **Packaged source population:** confirm that the release artifact carries
   the admitted initialization/program rows consumed by `current-src`, while
   `init --changed` remains a development-only producer of the same shape.
4. **Self-stop acknowledgement:** confirm that an agent's control request and
   terminal receipt commit before stopping its cluster or process. The
   runtime must never remove the database connection needed to record the
   request's own outcome.
5. **Control coordinate:** confirm that the process-root REPL becomes the one
   lifecycle-control coordinate, and decide whether per-cluster REPLs survive
   only as selected eval surfaces or are deleted. The current architecture
   gives every cluster its own REPL; the self-controlling base requires an
   explicit superseding decision rather than an accidental second plane.
6. **Fence interpretation:** confirm whether “before JVM” literally means the
   parent must acquire the lifetime fence before process creation, or whether
   the minimal JVM acquiring it before any Datahike open is the required
   guarantee. Literal parent custody needs a proved descriptor handoff or a
   resident supervisor; acquire-release-reacquire has a race and is not a
   fence.

None of these rulings require Integrant. They decide facts and transaction
boundaries; the existing boot sequence and Flow can then execute them.
