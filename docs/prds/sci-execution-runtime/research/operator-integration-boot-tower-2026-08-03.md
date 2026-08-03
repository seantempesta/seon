---
type: research
status: complete
tags: [research, operator, runtime, boot]
---

# Operator integration: boot-tower seams (2026-08-03)

## Verdict

The surviving boot tower already is the lifecycle mechanism. Do not put a
component framework or a process-root scheduler beside it. Extract one small
process-control owner around the existing process-local custody, keep
`seon.cluster/start!` and its reverse unwind as the cluster mechanism, and make
the existing operations callable through data-shaped process-control functions.

The important qualification is that “callable” has two different execution
shapes:

- REPL and MCP callers may invoke the process-control functions directly because
  they are already outside agent evaluation and can receive an ordinary result;
- an agent evaluation must return a lifecycle request value (or make a request
  through `seon.effect`) which the run loop interprets only after evaluation
  returns. An agent-facing `stop!` or `refork!` that tears down its own graph,
  branch connection, or REPL from inside the evaluation would restore precisely
  the leaf-bound lifecycle semantics the fresh runtime deleted.

The standalone artifact is already close to the target: it packages program
initialization pages and boots the same `seon.cluster/start!`. It currently does
the two critical operations in the wrong ownership interval, however. It opens
the store, publishes pages, releases the store and flock, and only then calls
`start!` (`src/seon/artifact.clj:51-70,72-100`). That known gap is recorded in
[Hold one store ownership interval across artifact install and start](/docs/seon/issues/artifact-releases-the-fence-between-install-and-start.md).
The migration should make packaged page installation and cluster start use one
process-root store hold, not add another initialization path.

## Scope and sources read

I read the named authorities end to end before reaching this verdict:

- the repository instructions, including the tower at `AGENTS.md:219-281`;
- [the intended architecture](/docs/seon/architecture/architecture.md), whose
  process/cluster ownership boundary is at lines 30-54;
- [the active program roadmap](/docs/prds/sci-execution-runtime/plan/README.md) and
  [current unsettled ledger](/docs/prds/sci-execution-runtime/plan/unsettled.md);
- [the runtime-local instructions](/docs/prds/sci-execution-runtime/AGENTS.md); and
- [the transfer orientation](/docs/TRANSFER_PROMPT.md).

I then read the complete current owners in `src/seon/cluster.clj`,
`src/seon/cluster/store.clj`, `src/seon/cluster/registry.clj`,
`src/seon/cluster/source.clj`, `src/seon/cluster/process.clj`,
`src/seon/config.clj`, `src/seon/artifact.clj`,
`resources/seon/operator/runtime.clj`, and `build.clj`, plus the directly
relevant program-indexing and boot-test sections. The outer Babashka operator is
audited by the sibling operator lane; this report only follows its calls across
the JVM boundary where necessary.

## Dependency ledger

| Dependency or mechanism | Selected revision | Maintained source and constraint |
|---|---|---|
| Seon tree | `67e92bec93c7da4a5ef7a92a8bd9fa57d0d6d24f` | `src/seon/cluster.clj:1472-1677,1780-1867` is the existing ordered start/stop/refork mechanism. |
| Datahike fork | `0e8601d7f2f68c01070e13a95483bc82be04cabc` | `src/seon/cluster/store.clj:270-351` acquires the exclusive flock before any database existence check/open; `src/seon/cluster/registry.clj:160-284` is the one branch lifecycle owner. |
| core.async Flow | `dc35f3e0d7bc2eef502e77982f48641f025c8051` (`v1.10.874-alpha3`) | `src/seon/cluster.clj:1251-1469` uses Flow for the cluster plumbing graph and joins proc completion before releasing the branch connection. Flow owns runtime proc lifecycle, not process spawning or store custody. |
| Konserve fork | `737697d9205e5e8f0bc08a666e4c97dad55e9dbe` | Datahike's branch roster and store contents remain behind the one fenced store owner; a status implementation must not add a raw second reader. |
| SCI fork | `2db3358cba913b6fbbe49c7b5b34d7ac72715924` | A cluster's SCI context is derived only after branch facts and config stand (`src/seon/cluster.clj:1525-1530`), so it cannot own the process substrate which creates that context. |
| Process-root custody | current tree | `resources/seon/operator/runtime.clj:1-28` deliberately lives outside indexed `src`/`test`. It owns only running instances, held stores/flocks and the shared executors. This is the right minimal protected substrate. |
| Packaged initialization | current tree | `build.clj:57-100,154-172` derives and packages initialization pages; `src/seon/artifact.clj:45-70` installs those pages without requiring a source checkout. Runtime file indexing is explicitly checkout-only (`src/seon/fn.clj:19-21,33-48`). |

The checked-out tree contains vendored Integrant, but the tower findings do not
create a missing dependency which Integrant would fill. The sibling Integrant
audit owns that library verdict.

## What exists today

### The minimal protected substrate is already present

`resources/seon/operator/runtime.clj` is intentionally on the JVM classpath but
outside `seon.fn/source-roots` (`src/seon/fn.clj:19-21`). It holds five
process-local artifacts only: the running-instance map, root-store holder,
held-flock table, delayed executor pair and executor accessor
(`resources/seon/operator/runtime.clj:1-28`). A load-only probe confirmed that
the namespace resolves from the resource classpath while the indexed roots
remain exactly `["src" "test"]`.

This is not missing database state. Connections, file locks, server sockets,
executors and live instance values are process custody and cannot be durable
facts. What is missing is a public data-shaped process-control boundary over
that custody, so the Babashka operator currently reaches private state with
`ns-resolve` (for example `script/seon/fresh_operator.clj:1385-1398,1719-1732,
1811-1819`).

### The cluster tower publishes incremental process-local readiness

`start!` establishes the cluster io-prepl and its advertisement before entering
the rest of the tower (`src/seon/cluster.clj:1594-1642`). `stack-tower!` then
adds each live resource to the registered instance as it stands:

| Boundary | Existing readiness value |
|---|---|
| REPL/process identity | advertisement with cluster, host, bound port, pid and start instant (`src/seon/cluster.clj:1615-1642`; identity implementation at `src/seon/cluster/process.clj:12-28`) |
| Process-root store | `:seon.store/store`, published immediately after the ref-counted acquire (`src/seon/cluster.clj:1480-1484`) |
| Cluster branch connection | `:seon.boot/cluster-connection` (`src/seon/cluster.clj:1485-1497`) |
| Recovery | `:seon.boot/recovered-runs` and operation count (`src/seon/cluster.clj:1508-1514`; values produced at lines 902-941) |
| Config | `:seon.boot/config-result`, after database reconcile and before any graph arms (`src/seon/cluster.clj:1515-1524`) |
| Shared SCI program | `:seon.sci.eval/ctx` (`src/seon/cluster.clj:1525-1530`) |
| Cluster work | `:seon.flow/work-launcher`, then graph/routing/view values from `arm-agents!` (`src/seon/cluster.clj:1539-1548`) |
| Human surface | served web value and URL-bearing advertisement, last (`src/seon/cluster.clj:1549-1571`) |
| Complete boot | `:seon.boot/ready-ms` (`src/seon/cluster.clj:1660-1666`) |

`readiness` already derives an ordinary status value from the registered
instance plus one immutable database value (`src/seon/cluster.clj:1697-1740`).
The MCP observation similarly derives health and Flow status from the instance
instead of keeping a second status flag (`src/seon/cluster.clj:204-217`). A
later-layer failure retains the exact degraded instance and live REPL
(`src/seon/cluster.clj:1667-1677`), with a recurring proof at
`test/seon/cluster/boot_test.clj:1096-1128`.

Not every pure transition has a named readiness value yet. Fork result,
program-coherence verification, schema accretion, cluster-row convergence and
root-agent creation are local bindings or `_` results
(`src/seon/cluster.clj:1485-1524`). That is acceptable for the current
synchronous caller, but a durable control receipt should name the selected
cluster, source commit, config digest and final disposition instead of asking a
future controller to infer success from adjacent fields.

### Most requested control operations already have one JVM owner

- source publication is `seon.cluster/refresh-source!`; it shares an already
  held root store by ref count and publishes `current-src` without touching
  existing sovereign clusters (`src/seon/cluster.clj:862-884`, proved at
  `test/seon/cluster/boot_test.clj:832-884`);
- branch creation/reset/retirement is solely
  `seon.cluster.registry/ensure-cluster!`, `reset-cluster!` and
  `retire-branch!` (`src/seon/cluster/registry.clj:200-284`);
- config compilation/reconcile/read are `seon.config/compile-manifest`,
  `apply!` and `effective` (`src/seon/config.clj:185-231,239-295`);
- status for a live instance is `seon.cluster/readiness`; and
- addressed teardown/refork are `seon.cluster/stop!` and `refork!`
  (`src/seon/cluster.clj:1780-1867`).

A load-only JVM probe resolved all seven relevant public cluster Vars:
`refresh-source!`, `start!`, `stop!`, `refork!`, `readiness`,
`read-advertisement` and `resolve-bootstrap`. The migration is therefore an API
and ownership consolidation, not a port of Babashka algorithms.

## Ownership after integration

### Must remain outside the cluster program graph

The following values and transitions precede or outlive any one cluster and
remain in the protected process substrate:

- pid/start-instant identity and the supervisor's generation/process record;
- the canonical process root, store path and lifetime flock;
- the main store connection and the branch lifecycle owner;
- the root executor pair;
- the running-instance registry and exact instance-addressed teardown fence;
- creation of the first REPL/control transport; and
- packaged initialization-page installation when no `current-src` exists.

The OS launcher still owns process spawn, stdout/stderr capture, log-file
redirection, adoption of the exact child generation, and TERM/KILL fallback.
The JVM cannot create or reap itself reliably, and its last REPL cannot promise
a response after closing itself. The architecture's exceptional ordering is
explicit: the flock precedes Datahike open (`AGENTS.md:228-247`;
[architecture](/docs/seon/architecture/architecture.md#L30-L50)). The current
implementation acquires that fence inside the JVM immediately before Datahike
open (`src/seon/cluster/store.clj:270-351`). If the integration requires the
fence to stand before JVM launch, that acquisition and lifetime must move to the
outer launcher; either way, there must be one uninterrupted fence before any
store access and throughout every hosted cluster.

### Per cluster

Cluster name and branch, the branch connection, config facts, run recovery,
cluster/root-agent rows, SCI context, work launcher, agent graphs, render graph,
web server, io-prepl and advertisement remain per cluster. The current tower
and reverse unwind already enforce that boundary
(`src/seon/cluster.clj:1472-1571,1780-1842`). A sibling shares only the store
holder and executors; the existing proof is
`test/seon/cluster/boot_test.clj:1042-1094`.

### Database facts versus process-local results

Durable desired control and completed outcomes may be database facts once a
cluster database exists: requested operation identity, target cluster, selected
source commit/config digest, disposition and fault provenance. Do not store the
instance map, connection, flock, socket, executor or Flow object. Before any
cluster branch is open, the advertisement and exact process record remain the
honest filesystem/OS discovery facts; claiming that all process bootstrap state
is already database data would create a circular dependency on the unopened
database.

## Circularities and hazards to dissolve

1. **The artifact initializes before it has a REPL and drops the fence between
   phases.** `seon.ArtifactMain` loads `seon.artifact` first
   (`java/seon/ArtifactMain.java:11-22`); `seon.artifact/-main` installs packaged
   pages, releases the store, then calls `cluster/start!`
   (`src/seon/artifact.clj:72-100`). The target needs one root-store hold and an
   early control REPL, then page install and cluster tower inside that substrate.

2. **“REPL first” still compiles cluster config first.** `start!` calls
   `config/compile-manifest` at lines 1594-1604 before binding the server at
   lines 1615-1622, because the MCP projector receives result caps from the
   compiled effective config. A genuinely tiny control REPL must use only the
   closed bootstrap config until database config exists; cluster config
   compilation belongs above that readiness boundary.

3. **Namespace load is not minimal.** Requiring `seon.cluster` activates the
   packaged schema at `src/seon/cluster.clj:67-83` and loads the entire cluster
   closure. The protected `seon.operator.runtime` namespace is small, but it
   currently requires `seon.flow` to construct executors
   (`resources/seon/operator/runtime.clj:8-22`). A new control entry should not
   claim “second-zero REPL” unless a measurement proves its actual require
   closure and bind order.

4. **Source publication has two distinct producers.** Development
   `refresh-source!` reads a checkout and statically analyzes `src`/`test`
   (`src/seon/fn.clj:19-21,33-48`; `src/seon/cluster.clj:599-608,752-884`). A
   packaged jar correctly installs embedded initialization pages instead
   (`build.clj:57-119`; `src/seon/artifact.clj:45-70`). Keep both as inputs to
   one `source/publish!` owner; do not make the packaged runtime pretend it has a
   source checkout.

5. **Self-stop and self-refork close their caller's transport.** `stop!`
   releases graphs, branch connection, store hold and the addressed prepl in
   that order (`src/seon/cluster.clj:1780-1842`). `refork!` calls `stop!` before
   resetting the branch (`src/seon/cluster.clj:1844-1867`). They are safe from a
   sibling/control REPL, but a synchronous call through the target cluster's own
   prepl cannot require a terminal reply after the socket is closed. Agent
   execution is stricter: it must return a lifecycle request and let the outer
   run loop settle the eval before interpreting it.

6. **Offline status cannot be “inside the running system” when no system is
   running.** Live status belongs in the JVM and should derive from the protected
   instance registry plus database values. When all JVMs are down, the outer
   operator may report process records/advertisements or start the packaged JVM
   in a read-only control mode. It must not duplicate the Konserve roster. The
   current cold-reader cost and ownership problem are already recorded in
   [Give offline roster discovery a current read-only helper](/docs/seon/issues/give-offline-roster-discovery-a-current-read-only-helper.md).

## Migration seams

1. Give `seon.operator.runtime` one ordinary, public process-control namespace
   which accepts names/paths/manifests and hides instance/connection lookup. Its
   observation result should be ordinary data. Keep the atoms themselves
   protected and remove operator `ns-resolve` readers when their public
   replacements land.
2. Split process substrate readiness from cluster start: exact process identity,
   store hold and control REPL first; then packaged-page install if required;
   then call the existing cluster tower. Pass or ref-count the same held store so
   install and start have no fence gap.
3. Route `init`, named fork/refork, config apply and live status through those
   functions over the existing REPL/MCP transport. Their implementations should
   call `source/publish!`, `registry/*`, `config/*` and `cluster/*`; none should
   reimplement those owners.
4. Give agent-originated lifecycle changes an explicit result value interpreted
   after eval. Direct read-only status may return a value. Config/source/database
   mutations go through the existing effect boundary. Persist a receipt only
   when recovery or another process needs it.
5. Leave spawn, child adoption, log capture, exact-generation signals and
   process-exit observation in the thin outer launcher. Once the JVM reports
   ready, the outer process becomes transport and supervision, not a second
   implementation of cluster semantics.

## Options for the owner

### Option 1 — existing tower plus a protected process-control owner (recommended)

Keep the current tower and its instance map. Add a minimal root control entry
which holds the store once, opens an early control REPL, installs packaged pages
when needed and invokes the tower. Public request-map functions wrap the existing
source, registry, config, readiness and lifecycle owners. REPL/MCP call them
directly; agent lifecycle requests are interpreted after eval.

Guarantee: one lifecycle mechanism, one branch owner, one uninterrupted flock,
and an available control transport even when a cluster tower is degraded.
Cost/risk: moderate extraction around the current private root-store holder and
careful separation of agent-returned lifecycle values from direct operator
calls. Capability retained: multi-cluster control and degraded-instance repair
from the packaged JVM.

### Option 2 — make the first cluster's io-prepl the control plane

Fix the artifact's store-hold gap, then expose data-shaped wrappers over the
existing functions on the first cluster prepl. Additional clusters are added
through that prepl, with no separate process-root control server.

Guarantee: the smallest source diff and no new transport. Cost/risk: the
control plane is owned by an ordinary cluster; stopping/reforking it interrupts
control, the no-cluster state has no live control endpoint, and process-wide
status remains awkward. Capability given up: a stable process-root control
address independent of any cluster's lifecycle.

### Option 3 — durable desired-state reconciliation loop

Commit desired cluster/config/source rows and let a process-root controller
continuously reconcile them.

Guarantee: durable asynchronous requests and restart visibility. Cost/risk:
high. The controller must exist before the cluster database and Flow graphs it
would use, creating another scheduler/lifecycle mechanism plus a bootstrap
database circularity. Capability gained: queued control changes while a
particular cluster is down. This is not justified by the current owner question;
add durable receipts to option 1 only where recovery evidence requires them.

## Shortest falsifiers and acceptance proof

### Read-only probes performed

1. `bin/seon status` observed one live `default` cluster at PID `95639`, prepl
   `54028`, while the offline roster fallback correctly refused to contend for
   its flock. This confirms that live discovery and offline store inspection are
   distinct ownership paths; it also reproduced the separate open status issue
   [Stop reporting an MCP-proven live prepl as unreachable](/docs/seon/issues/status-reports-a-live-mcp-proven-prepl-unreachable.md).
2. A load-only `clojure -M:dev` probe required `seon.operator.runtime` and
   `seon.cluster`, resolved the seven control Vars named above, and confirmed
   that the protected runtime namespace is loadable while program indexing is
   limited to `["src" "test"]`. No cluster or database was mutated.

### Minimum recurring integration proof

Build the standalone jar, start it under an isolated operator root, and prove
the following through its advertised control transport:

1. the process owns one flock continuously from packaged page installation
   through default-cluster readiness; a competing opener never enters the gap;
2. request a sibling cluster, apply a sparse config, read the committed config
   and readiness, then stop only the sibling while the default cluster and
   process control endpoint remain live;
3. inject a tower failure above the REPL and prove status returns the exact
   degraded instance boundary and a later stop can unwind it;
4. refork a non-control cluster and prove its old fact is absent, its program
   commit equals the selected `current-src` commit, and its sibling basis is
   unchanged; and
5. from an agent eval, return a lifecycle request and prove no graph, branch,
   REPL or advertisement changes until the eval result is settled and the
   process-control owner interprets it.

The existing focused regressions already cover the core mechanics which this
proof should compose rather than duplicate: retryable addressed stop at
`test/seon/cluster/boot_test.clj:551-614`, orderly Flow completion before branch
release at lines 616-676, sovereign old programs at lines 736-782, live source
publication isolation at lines 832-884, config-before-arm at lines 902-946,
multi-cluster store/config isolation at lines 1042-1094, degraded REPL survival
at lines 1096-1128, and destructive refork at lines 1130-1165.
