---
type: research
status: complete
tags: [research, operator, runtime, process, database]
---

# Operator integration: the process boundary

## Verdict

Keep a very small external supervisor, but move cluster control into the
process-root JVM. The external program has four irreducible jobs: become or
spawn the first JVM, establish process-root custody before any database open,
capture the process log, and retain enough exact OS identity to stop or replace
an unreachable JVM. Destructive whole-root recovery also stays there because
it must work when the database and REPL do not.

Everything after that boundary already has an in-JVM owner. Source publication
is `seon.cluster/refresh-source!`; branch creation and replacement are
`seon.cluster.registry/ensure-cluster!` and `reset-cluster!`; cluster start and
stop are `seon.cluster/start!` and `stop!`; config reconciliation is
`seon.config/apply!`; readiness is `seon.cluster/readiness`. The Babashka
operator currently reaches those owners by generating source strings and
sending them through a cluster prepl. The migration should expose stable
ordinary functions over plain data and delete the generated forms. It should
not introduce a component registry or another lifecycle model.

The recommended target is one persistent process-root base: the external
supervisor starts the packaged jar, the base takes the lifetime store custody
and publishes one control REPL coordinate, and cluster instances are then
started, stopped, configured, forked, and inspected from inside that JVM. An
empty process-root JVM remains alive and controllable; `down`, not the last
cluster's `stop!`, ends it.

This audit read the root `AGENTS.md`,
[`docs/TRANSFER_PROMPT.md`](../../../TRANSFER_PROMPT.md),
[`docs/prds/sci-execution-runtime/AGENTS.md`](../AGENTS.md), the current working
edge and operator-related rulings in
[`docs/prds/sci-execution-runtime/plan/unsettled.md`](../plan/unsettled.md) and
[`docs/prds/sci-execution-runtime/plan/README.md`](../plan/README.md), and
[`docs/seon/architecture/architecture.md`](../../../seon/architecture/architecture.md)
before reaching this verdict. It then read `bin/seon`,
`script/seon/fresh_operator.clj`, and the relevant boot-tower, store, registry,
process, runtime-custody, and operator-test owners. This report changes no
production source.

## Dependency ledger

The source basis observed for the final audit was Seon
`67e92bec93c7da4a5ef7a92a8bd9fa57d0d6d24f`. The working tree was shared and
dirty; every pre-existing change was treated as protected. This report is the
only owned path.

- Datahike is the maintained local fork selected at
  `0e8601d7f2f68c01070e13a95483bc82be04cabc`. The relevant custody seams are
  `src/seon/cluster/store.clj:270-405` and
  `src/seon/cluster/registry.clj:92-280`; Datahike owns branch heads and the
  writer, while Seon owns the process-root file lock and branch-open refusal.
- `core.async`/Flow is selected at
  `dc35f3e0d7bc2eef502e77982f48641f025c8051` (`1.10.874-alpha3`). Flow owns
  cluster and agent graph lifecycle after the boot tower reaches that layer;
  it does not own OS process launch or the store fence.
- Konserve is selected at
  `737697d9205e5e8f0bc08a666e4c97dad55e9dbe`; it is reached through the
  Datahike store and branch owners, not through the command script.
- Malli is selected at
  `80138076960e7820523b4cb932c5b5d1936d4e7f` (`0.20.0`). Bootstrap and public
  function shapes are declared schemas; command parsing is not a second
  lifecycle contract.
- The first-party process boundary is `bin/seon:1-18` plus
  `script/seon/fresh_operator.clj`. The in-JVM owners are
  `resources/seon/operator/runtime.clj`, `src/seon/cluster.clj`,
  `src/seon/cluster/store.clj`, `src/seon/cluster/registry.clj`,
  `src/seon/cluster/process.clj`, and `src/seon/config.cljc`.
- Recurring behavioral evidence is in
  `test/seon/dev/fresh_operator_test.clj:397-1459`, especially exact process
  identity, isolated roots, no-follow reset, held-flock destruction, cold and
  live initialization, restart, and signal fallback.

## What `bin/seon` actually does

`bin/seon` itself is only a checkout-relative launcher. It canonicalizes the
source and optional operator roots and `exec`s Babashka with the repository
classpath (`bin/seon:4-18`). All control semantics live in the 2,510-line
`seon.fresh-operator` namespace.

### External process custody

- Root-scoped process records contain generation, PID, start instant, root,
  and log path. They are validated, durably replaced, read, and cleared at
  `script/seon/fresh_operator.clj:82-159`; the exact `(pid, start-instant)`
  match is rechecked through `ProcessHandle` before signaling at `:179-193`.
- One kernel file lock serializes state-changing operator commands at
  `:195-205`; `-main` applies it to start, config, init, status, stop, down,
  and reset at `:2475-2502`.
- `start-child-jvm!` is the single child construction seam. It selects the
  repository classpath, injects the operator root, passes the selected
  environment, redirects stderr to stdout, and launches either directly or
  through the detached Python handoff (`:38-50,235-258`). `launch!` creates the
  log, process generation, and adoption gate; `record-launched-process!`
  publishes exact identity before acknowledging adoption (`:1447-1505`).
- The parent observes either readiness or process exit, then verifies that the
  completed advertisement names the same recorded process generation
  (`:1563-1612,1684-1717`). This is the real external readiness boundary.
- Exact-process shutdown sends TERM, observes exit, rematches identity before
  KILL, and refuses a surviving generation (`:1507-1540`). `down` prints the
  complete record census before acting, attempts graceful prepl stop, signals
  the exact remaining process, clears records, and only then proves the store
  can be opened offline (`:2186-2322`).
- Whole-root reset holds the store lock across the destructive callback and
  uses a no-follow, under-root walk before republishing and reforking default
  (`:2324-2421`). The recurring symlink sentinel and held-lock tests are
  `test/seon/dev/fresh_operator_test.clj:625-647,892-963`.

### Discovery and reconciliation

The operator currently derives a cluster row from four observations:
root-scoped advertisement files, exact process records, the JVM's private
`running-instances`, and open/persisted Datahike branches
(`script/seon/fresh_operator.clj:422-1137`). It generates a JVM snapshot form
that resolves private Vars and reads Datahike connection internals at
`:659-730`; when no reachable JVM supplies the roster, it starts a second JVM
solely to open the store and print the roster (`:732-768`).

`derive-cluster-truth` joins those observations and names drift at
`:896-1067`. `reconciled-truth!` repairs stale or missing advertisements and
re-derives until converged (`:1228-1301`). `status!` then prints the derived
rows, roster source, process records, and orphan processes (`:1934-2048`).

The mechanism is valuable, but its placement is not. A live process already
owns the process-local instances, store, branches, and exact readiness data.
Making an external script reconstruct them through private Vars duplicates the
runtime's own view and has already diverged: the open issue
[`docs/seon/issues/status-reports-a-live-mcp-proven-prepl-unreachable.md`](../../../seon/issues/status-reports-a-live-mcp-proven-prepl-unreachable.md)
records `status` calling the same prepl unreachable while MCP successfully
evaluated through it.

### In-JVM operations wrapped by the script

- `start!` either sends `add-form` to a reachable JVM or launches a new JVM
  with `launch-form` (`script/seon/fresh_operator.clj:1400-1445,1622-1717`).
  Both generated forms ultimately call `seon.cluster/start!`.
- `config apply` reads a sparse EDN map outside, then generates a form that
  finds the private instance connection and calls `seon.config/apply!`
  (`:386-399,1719-1751`).
- `init` generates a form around `seon.cluster/refresh-source!`,
  `seon.cluster.registry/ensure-cluster!`, `reset-cluster!`, or
  `seon.cluster/refork!` (`:1753-1848`). It prefers a live JVM but otherwise
  starts a temporary initialization JVM (`:1850-1932`).
- Graceful `stop` generates a form around `seon.cluster/stop!`; only prepl
  failure reaches the external signal fallback (`:2070-2184`).
- `open` and `logs` are desktop/shell conveniences over already-derived URL
  and log paths (`:2050-2068,2423-2443`).

The script therefore does not own the semantics of config application,
publication, branch lifecycle, cluster start, or graceful stop. It owns text
generation and transport to their real owners.

## What must remain outside a managed JVM

### First and last process transitions

A JVM cannot create the first JVM, redirect its output before it exists, or
reliably replace itself after it becomes unreachable. The external supervisor
must retain:

- canonical operator-root selection;
- first-process spawn or `exec`;
- environment/credential injection without storing credentials as datoms;
- stdout/stderr capture and rotation at the process-root level;
- exact process identity and generation records independent of database
  readability;
- readiness-versus-exit observation; and
- TERM/KILL fallback against an exact rematched generation.

The durable database may record the running process identity for provenance
and queries after opening, but those facts cannot authorize an OS signal when
the database is unavailable. The external process record is the recovery
authority, not a duplicate runtime status field.

### The store fence before managed boot

The store fence is exceptional because it must precede the database whose
facts would otherwise coordinate ownership. Today the child JVM acquires and
holds the lifetime file lock before Datahike open in
`src/seon/cluster/store.clj:197-225,270-351`; the external script takes the
same lock only for destructive reset (`script/seon/fresh_operator.clj:2324-2403`).

If the target requires the lock to be acquired before the managed JVM starts,
that is a real migration seam, not a function move. The implementation must
prove one of these handoffs on every supported platform:

- the launcher acquires the lock and `exec`s the JVM while preserving the
  exact locked descriptor;
- a resident supervisor holds the lock for the child's whole lifetime; or
- the owner relaxes “before JVM” to “the minimal JVM's first action before any
  Datahike open,” retaining the current `open-store!` fence.

The current `FileLock` cannot simply be acquired by the parent, released, and
assumed transferred. A release-then-child-lock sequence has a race; double
locking the same path causes the child to refuse. This requires a small
process-level falsifier before production design. Regardless of the chosen
handoff, Datahike never opens before the lifetime fence stands.

### Destructive recovery without runtime cooperation

`reset --force` must work when the store cannot open, source/config has
changed incompatibly, or the JVM is dead. Exact-process down, held-flock proof,
no-follow deletion, and creation of a fresh minimal JVM therefore remain
external. The republish and refork steps after deletion move back inside the
new minimal JVM; only the part that cannot trust the old JVM/database remains
outside.

### Human shell conveniences

Opening a browser and tailing a file are platform shell operations. They may
remain thin external conveniences. They must consume the same runtime-derived
URL and process log path rather than becoming lifecycle authorities.

## What moves into the process-root JVM

### One persistent process-root base

The existing protected namespace already places process-global custody outside
every cluster program graph: `resources/seon/operator/runtime.clj:1-28` owns
`running-instances`, `root-store-holder`, held flocks, and the shared executor
pair. `seon.cluster` imports those Vars at `src/seon/cluster.clj:41-42`.

Promote that base from incidental holders to the explicit process-root
lifetime:

1. open one root control REPL and publish a root advertisement containing the
   process identity and control coordinate;
2. acquire the process-root store once under the lifetime fence;
3. retain the store and root executors until process shutdown, not until the
   last cluster stops; and
4. call the existing cluster tower for any requested branch.

Today `stack-tower!` acquires the store as its first cluster layer and
`stop!` releases the last holder (`src/seon/cluster.clj:1472-1497,1780-1810`).
The process-root base changes that ownership interval. Cluster instances then
own only their branch connection and layers above it. `stop!` closes a branch
instance; external `down` closes the process root and its lifetime store.

This also deletes `stop-empty-jvm!` (`script/seon/fresh_operator.clj:2134-2148`):
an empty JVM is the intended controllable base, not garbage to reap.

### Stable functions, not generated prepl source

The runtime functions already compose in the required order:

- `seon.cluster/start!` opens the REPL first and publishes every partial tower
  value before the next layer (`src/seon/cluster.clj:1573-1677`);
- `stack-tower!` opens/forks/connects, checks program coherence, reconciles
  schema/config, recovers runs, installs the cluster SCI context, arms flows,
  serves HTTP, and enriches the advertisement (`:1472-1571`);
- `seon.cluster/refresh-source!` serializes complete or incremental
  `current-src` publication (`:610-884`);
- `seon.cluster/refork!` holds the root store across stop and the registry's
  single reset owner (`:1844-1867`); and
- `seon.cluster/readiness` derives an ordinary status value from the instance
  and one database value (`:1697-1740`).

The CLI and MCP should call these named functions with ordinary request maps.
The migration deletes `jvm-snapshot-form`, `launch-form`, `add-form`,
`config-apply-form`, `init-form`, `stop-form`, and `stop-all-form`. It also
deletes external access to the private `running-instances` Var. If a required
observation cannot be returned by a public function over ordinary data, the
missing function/fact is the defect.

### Publication and branch operations

Bare `init` and `init --changed` already execute publication inside a live
store-owning JVM; only the no-JVM fallback creates a temporary source process
(`script/seon/fresh_operator.clj:1873-1910`). With the persistent root base,
that fallback disappears.

For a packaged jar, developer source publication and artifact initialization
must stay distinct. `refresh-source!` analyzes the checkout and is appropriate
for source development. A packaged artifact instead publishes its embedded,
pre-analyzed initialization rows through the same `current-src` branch owner;
it must not grow a hidden checkout/clj-kondo dependency. Both paths terminate
at the one guarded `seon.cluster.source/publish!` mechanism.

Named fork and refork become direct calls to the registry/cluster owners in
the root JVM. Branch existence remains the Datahike roster fact
(`src/seon/cluster/registry.clj:104-111`); no separate cluster-exists table or
status boolean is added.

### Config apply

The CLI may still read a file because a path is an external input, but it
passes the resulting plain EDN map to `seon.config/apply!`. The database row is
runtime truth. REPL/MCP callers pass the map directly; agents may transact the
declared facts or enter the eventual guarded capability request. No runtime
function reads ambient config files or environment variables.

### Status

Split status at the actual authority boundary:

- the root JVM returns ordinary process identity, root readiness, branch
  roster, registered cluster instances, cluster readiness, and Flow
  observations from its held store and runtime values; and
- the external supervisor overlays only facts the JVM cannot report when it is
  unreachable: exact process-record state, log path, and whether the control
  coordinate answered.

There is no offline-roster subprocess while a root JVM exists. When no JVM
exists, `status` may report the external process record alone or start the
packaged jar in its minimal control mode to acquire the fence and query the
roster. It must never reconstruct live cluster state from command substrings.

## Migration seams and risks

### One REPL coordinate or two

The proposed base requires one process-root control REPL before any cluster is
started. Current `seon.cluster/start!` creates a separate prepl and
advertisement for every cluster (`src/seon/cluster.clj:1607-1642`). Keeping
both indefinitely creates two control mechanisms.

Recommendation: make the root REPL the sole JVM-control coordinate and make
cluster selection an explicit argument to MCP/control functions. Cluster web
URLs and readiness are returned by root status. Delete per-cluster prepl files
after MCP and `bin/seon` use the root advertisement. This intentionally changes
the current tower contract and needs an owner ruling plus architecture update;
quietly retaining both is worse.

### Agent invocation is not the first slice

JVM REPL and MCP may call lifecycle functions directly because they are
system-side operator surfaces. An agent evaluation must not directly perform
runtime lifecycle semantics from inside SCI: the standing effect law requires
either a pure value the run loop interprets or a genuine request through the
one guarded effect boundary. `seon.effect` is not built. Therefore the first
migration makes the functions callable through the JVM REPL/MCP. Agent
callability follows only when the guarded request/receipt owner exists; it does
not justify binding process/store handles or `seon.cluster/start!` directly
into SCI.

### Process-wide instrumentation

The script currently applies instrumentation after cluster start and refreshes
it from an arbitrary live anchor because instrumentation mutates process-global
Vars while its dials are cluster facts
(`script/seon/fresh_operator.clj:1363-1445`; `src/seon/cluster.clj:1531-1538`).
Moving control into the JVM does not solve that mismatch. The process-root base
needs one explicit instrumentation policy derived from a declared authority;
it must not choose the first cluster and call that process truth.

### Advertisement migration

Advertisements are bootstrap discovery, not durable domain truth. Keep one
small root advertisement on the filesystem until the external supervisor and
MCP can find the control REPL. Move cluster inventory and URLs behind the root
status function. During migration, do not let root and per-cluster
advertisements both claim authority; readers switch in one wave, then the old
files and reconciliation branches are deleted.

### Existing operator defects are evidence, not migration requirements

The current script classifies processes from command substrings, has unbounded
subprocess waits, and retains private helpers solely for tests. Those classes
are already recorded in:

- [`docs/seon/issues/operator-classifies-processes-by-command-substrings.md`](../../../seon/issues/operator-classifies-processes-by-command-substrings.md)
- [`docs/seon/issues/operator-subprocesses-have-unbounded-read-and-wait-paths.md`](../../../seon/issues/operator-subprocesses-have-unbounded-read-and-wait-paths.md)
- [`docs/seon/issues/operator-private-helpers-have-only-test-readers.md`](../../../seon/issues/operator-private-helpers-have-only-test-readers.md)

Do not port these shapes into the jar. Exact records replace command scanning;
named functions replace generated source; the persistent root base removes
offline and initialization child JVMs; command-level tests move to the public
external/runtime seam rather than private Vars.

## Options for the owner

### Option 1 — thin external supervisor plus persistent root base (recommended)

Guarantee: one externally supervised JVM holds one store and one control REPL;
all cluster lifecycle, config, source publication, branch operations, and live
status execute through the existing in-JVM owners. Only spawn/log/signal,
pre-database fence custody, and destructive recovery remain outside.

Cost and risk: changes the store ownership interval, deletes per-cluster REPL
discovery, requires a proven lock handoff, and needs a packaged initialization
entry distinct from checkout analysis. These are bounded migration seams, not
new runtime concepts.

Operational trade-off: the empty JVM stays alive and consumes the minimal
store/executor/REPL footprint. In return, every later operation is immediate,
root status has direct truth, and the jar genuinely controls its own clusters.

Capability given up: none material. Agent-initiated lifecycle waits for the
guarded effect owner rather than exposing an unsafe direct SCI call.

### Option 2 — keep per-cluster REPLs beneath a root control REPL

Guarantee: current MCP and advertisement routing can migrate incrementally;
the root base still owns the store and invokes cluster functions.

Cost and risk: two permanent control/discovery mechanisms, two advertisement
levels, and ambiguity about which REPL owns status or degraded recovery. Every
consumer must select between root and cluster coordinates.

Operational trade-off: lower short-term conversion cost, continuing
reconciliation complexity forever.

Capability given up: the strong invariant that one process has one control
coordinate and one authoritative runtime observation. Not recommended.

### Option 3 — retain Babashka orchestration and only replace generated forms

Guarantee: smallest production diff. The script continues to spawn temporary
JVMs, reconstruct status, and terminate the empty JVM, but calls new named
functions instead of generated source.

Cost and risk: preserves the 2,510-line second control system, offline roster
JVM, filesystem reconciliation, and packaged-jar dependence on a checkout-side
script. It fixes transport hygiene without meeting the self-controlling jar
goal.

Operational trade-off: familiar commands and tests; continued slow and brittle
cross-process paths.

Capability given up: in-runtime control by database facts/functions and a
minimal self-hosting base. This is a useful emergency staging commit only, not
an acceptable end state.

## Recommended migration order

1. Seal the root-base contract: one control coordinate, one lifetime store
   holder, one root process identity, explicit readiness, and an empty-running
   state. Prove the lock handoff before production edits.
2. Add stable ordinary-data runtime calls at the existing owners and switch
   MCP to them. No new component registry or lifecycle service.
3. Move cold publication/fork/config/start/status/stop onto the root base;
   delete generated forms, private-Var snapshots, offline-roster JVM, and
   initialization JVM in the same wave.
4. Switch discovery from per-cluster prepl files to the root advertisement;
   delete the old reconciliation readers after every consumer moves.
5. Reduce `bin/seon` to root selection, external custody, and presentation.
   Keep `down`, signal fallback, log access, and destructive reset outside.
6. Add agent-requested lifecycle only through the settled guarded effect
   mechanism, with durable request identity and flat failures; do not block the
   JVM-control migration on that later boundary.

The integrated proof should cross the real failure boundaries: packaged jar
from an empty root, root REPL reachable before cluster boot, current source or
embedded initialization publication, two clusters started and inspected from
inside the same JVM, one stopped without affecting the other, zero-cluster
root still reachable, exact-process `down`, restart with the same branch
facts, and forced reset while the old database cannot be opened.
