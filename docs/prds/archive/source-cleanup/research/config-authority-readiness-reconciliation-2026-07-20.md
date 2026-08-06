---
type: research
status: complete
tags: [research, database, architecture]
---

# Configuration authority readiness reconciliation (2026-07-20)

This read-only reconciliation compares [[../config-through-aero]] with HEAD.
It distinguishes mechanisms that are already maintained from dependency-ready
implementation units. Companion grounding lives in
[[aero-config-seam-2026-07-20]] and [[als-config-probe-2026-07-20]].

## Already landed

Do not rebuild these mechanisms:

- `seon.config/load-manifest-path` is the one Aero read and closed-manifest
  validation seam. `seon.client/apply-config!` reconciles the resolved
  singleton, routes, and skills as desired database data; a converged apply
  writes nothing. Omitting an explicit manifest preserves retained facts.
- Separate `#include` plus `#merge` overlays are established. Sparse
  `:seon.eval/home-requires` merge additively by namespace through
  `merge-home-requires` and `combine-agent-context`.
- `a850b343` absolutizes environment-supplied operator coordinates once in
  `script/seon/dev/config.clj`.
- `8b0796eb` installs high-natural database read ceilings through the Aero
  manifest, singleton attributes, pure accessors, and
  `seon.db/read-resource-options`. Query defaults are
  `100000000 / 1000000 / 3000000`; pull defaults are
  `25000000 / 1000000 / 3000000` for work, retained result nodes, and shallow
  result weight respectively. Explicit operation limits win. This closes
  declaration and fallback behavior, not live propagation.
- `904cf4ab` derives managed identity attributes from desired configuration
  rows. A future brand row does not need another hard-coded identity registry.
- `execution/runtime.cljs` already demonstrates the target operation idiom:
  the full decoded singleton enters `db/with-tx-context` with the pinned
  database value and also scopes `error/with-configuration`.
- `d784432e` attributes reads through the current agent and transaction
  ambient. Any ALS consolidation must preserve that behavior.

## Earliest unsettled contract

Every operation boundary acquires one immutable database value and enters
identity, provenance, and the full decoded configuration at that basis through
AsyncLocalStorage `.run`. Descendants inherit the value; the next operation
acquires anew. No live context is mutated.

HEAD does not satisfy this contract:

- `seon.db.internal` retains three `AsyncLocalStorage` instances and an
  `enter-tx-context!` implemented with `.enterWith`;
- `seon.db/install-configuration-context!` installs one boot snapshot, and
  `seon.client` still calls it during startup; and
- outside `execution/runtime.cljs`, operation boundaries do not enter
  `:seon.config/configuration` in their transaction context.

Consequently, transacted database query/pull ceilings and
`:seon.config/on-core-error` remain stale or fall back to code defaults on many
fibers. [[als-config-probe-2026-07-20]] falsifies refresh-in-place: an
`.enterWith` replacement does not update pre-existing fibers. Do not add a
committed-transaction refresh around `.enterWith`.

## Ordered implementation owners

### 1. Per-operation configuration spine

One coordinated owner takes `src/seon/db/internal.cljs` and
`src/seon/db.cljs`, then the operation boundaries in `src/seon/client.cljs`,
`src/seon/agent/turn.cljs`, `src/seon/agent/loop.cljs`,
`src/seon/web/serve.cljs`, and `src/seon/execution.cljs`.
`src/seon/execution/runtime.cljs` is the reference implementation, not a new
path.

Delete the boot installer and `enter-tx-context!` only after every boundary
enters a full configuration. Fold the separately scheduled three-ALS and
`with-tx-context` naming cleanup into this unit only if the same owner can
preserve `with-agent`, `without-agent`, read-evidence isolation, nested merge
semantics, and read attribution. Otherwise land configuration propagation
first and keep carrier consolidation as a separate source-atomic unit.

### 2. Launch descriptor and grants

One descriptor owner takes `src/seon/launch.cljc` and
`script/seon/dev/config.clj`, followed by the shell and web grant consumers and
`src/my/blob.cljs`.

The descriptor gains launcher-owned web and shell booleans. One portable
`env-grant?` preserves the current coercion: absent, blank, and `"0"` deny;
every other non-blank string grants. Operator and direct-launch fallback
descriptor construction both call it. Runtime grant checks read only the
descriptor. Direct launchers continue exporting environment values into that
fallback seam.

This owner also collapses HTTP port, port-file, process-directory, log, and
writer-port defaults to the launch authority. It must not absorb transport
edits merely because the socket is descriptor data.

### 3. UDS coordinate consumer

After the database/UDS owner hands off, `src/seon/db/transport/uds.cljs`
consumes `:seon.launch/request-socket-path` and deletes its environment read
and drifted `tmp/seon-cluster-default-db.sock` fallback. This is a narrow
consumer of the settled descriptor contract.

### 4. Manifest behavior facts

The config/render owner adds `:seon.config.render/strict?`, resolves it through
Aero, and threads the acquired singleton through render guards. Aero's CLJS
`#boolean` accepts `"true"`, not `"1"`; test and operator wrappers must export
`"true"` or `"false"`.

A separate brand subunit owns `config/acme.edn`, `src/seon/web/brand.cljs`,
the desired rows in `seon.client/apply-config!`, and CSS consumers. Brand
facts live only in the downstream overlay. Remove environment-to-row sync and
zero-argument CSS lookup after reconciliation and acquired-row consumption
are complete. `config/system.edn` carries no brand section.

### 5. Reactive live policy

After per-operation acquisition is coherent, the reactive owner takes
`src/seon/reactive.cljs`, `src/seon/web/datastar.cljs`, and the web
committed-delivery boundary. Prefer deleting the process-local policy atom and
deriving timing from configuration acquired with the delivered database value
if measurement shows that path is cheap. Otherwise, refreshing this ordinary
atom when the configuration entity changes is viable. That atom exception does
not revive ALS refresh-in-place.

### 6. Embedding gate semantics

An independent JVM/operator unit teaches the embedding owner that blank and
`"0"` mean disabled, then deletes duplicate translation scrubs in
`script/seon/dev/config.clj` and `bin/acme`. Keep this as a follow-on if the
embedding switch remains intentionally process-bootstrap-only.

## Verified open gaps

At HEAD:

- shell and web grants still read `SEON_SHELL` and `SEON_WEB` live;
- the launch descriptor has no grant fields or shared coercion;
- `render-strict?` remains a zero-argument environment read;
- brand environment sync and zero-argument CSS lookup remain, and client boot
  still invokes the sync;
- blob storage rereads `SEON_CLUSTER_DIR`, while UDS rereads its socket and
  declares another default;
- launch defaults remain duplicated between operator, fallback descriptor,
  and writer entry points;
- the embedding presence scrub is duplicated; and
- reactive timing remains an atom captured by boot `configure!`.

## Residual environment seams requiring rulings

These were not all named in the original migration table and must not silently
expand an implementation unit:

- `SEON_BIND` selects a boot OS listening resource and belongs in the launch
  descriptor if Stage 4 claims complete launch authority.
- `SEON_SOUL` and `SEON_SOUL_FILE` still initialize context constants even
  though the current manifest teaches generic explicit file blocks. Decide
  whether to delete legacy compatibility or model it as manifest data.
- autocomplete rereads `SEON_CLUSTER_DIR`; derive the cluster identity from
  the launch descriptor if that UI evidence remains useful.
- instrumentation recovery, provider secrets, provider key-variable names,
  artifact digests, filesystem host grants, and provider endpoints are
  process-bootstrap or secret seams. Classify each before changing it; they do
  not become database facts merely because an environment read exists.

## Coordination and migration hazards

- The per-operation spine and socket consumer overlap active database, UDS,
  and execution owners. Require an explicit commit or path handoff before
  editing. Do not parallelize different semantics in `seon.db.internal`.
- Stage 2 renames process terminology and persisted process keys across launch,
  client, restore, operator, and documentation paths. Finish configuration
  source units before its freeze or translate them inside that single frozen
  rename. Never straddle the rename with built artifacts.
- Adding required fields to the closed launch descriptor invalidates retained
  operator descriptors, restore intents, and packaged artifacts. Choose either
  an optional/default-deny transition or quiesce and invalidate every retained
  descriptor under the Stage-2 freeze. Default-denying an old direct-launch
  descriptor can silently break shell-dependent benchmarks.
- New singleton attributes are a low-risk schema evolution: older retained
  rows use accessor defaults. However, opening an existing database without an
  explicit manifest intentionally preserves its facts. Apply desired changes
  through the explicit idempotent config operation; never reset to hide drift.
- Brand reconciliation must stop the environment sync before desired-state
  reconciliation owns the same identity. Prove the disposition of existing
  brand datoms and their transaction provenance.
- ALS propagation has no database migration, but it is source-atomic. A mixed
  old/new process gives different policy behavior depending on which boundary
  opened the fiber. Freeze every input to its live checkpoint.

## Shortest falsifiers

### Per-operation configuration

Start with the focused database remote-contract test. Transact a singleton
whose query `max-results` is `3`, begin a new ordinary operation boundary, and
issue a query without an explicit limit. The authority request must carry `3`.
An explicit request limit must still win. Then run the focused turn, loop,
execution, client, and web suites. Live graduation transacts the ceiling and
observes the next operation change without restart. A final search finds no
`enterWith`, `install-configuration-context!`, or captured boot configuration
at operation owners.

### Launch grants and coordinates

Pure tests cover the complete coercion matrix and exact descriptor round trip.
Run launch, operator-config, shell, web, blob, and UDS focused suites. Then boot
the normal operator and one direct container launch. Run one shell-dependent
Inspect smoke; a uniform-zero result is the default-deny regression signature.

### Manifest behavior and brand

Config tests pin Aero boolean behavior. Render tests receive an explicit
singleton. Apply the ACME overlay twice and prove the second apply writes
nothing. Transact the brand row and observe the next request change title and
CSS without restart. Boot the ACME cluster from that overlay.

### Reactive policy

Run `seon.reactive-test` and `seon.web.datastar-test`. Live, transact a larger
settle interval and timestamp the next feed's coalescing window. It must widen
without restart.

### Final checkpoint

Under one source freeze, run the CLJS, writer, and operator gates; boot default
and ACME clusters; run the direct-launch grant smoke; and search for runtime
environment reads outside explicitly classified Aero, bootstrap, and secret
seams. Only then is the resulting HEAD ready for the Stage-2 rename freeze.
