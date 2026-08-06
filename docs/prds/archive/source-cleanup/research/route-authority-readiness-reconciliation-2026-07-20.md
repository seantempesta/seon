---
type: research
status: complete
tags: [research, web, database, architecture]
---

# Route-authority readiness reconciliation (2026-07-20)

This audit reconciles the Stage-4 route-authority design with current source
after Stage 3. The route collapse is dependency-ready before the broader
configuration/environment cleanup, but it has one hard pre-merge condition:
prove the rollback crossing before removing the static product routes.

## Current verdict

The earliest ordered boundary is an atomic source cut that:

- expands the route schema and seeded population;
- converts migrated `seon.web.serve` functions to public one-argument Ring
  handlers;
- projects admission and launch-capability data through structural middleware;
- reduces the static catalog to the exact bootstrap remainder;
- changes the three live stream routes and their consumers from `/feed` to
  `/sse`; and
- preserves late handler-symbol resolution at request dispatch.

The existing bespoke router listener can carry those new datoms temporarily.
A second bounded cut then replaces that listener/settle loop with one
`seon.reactive` registration. Keeping those cuts ordered makes the first
boundary behavior-preserving without mixing two lifecycle changes into its
version-crossing proof.

The current source still implements the pre-collapse design:

- `src/seon/route.cljs` seeds seven core routes and has no admission or
  capability attributes;
- `src/seon/web/router.cljs` retains product, lifecycle, debug, data, and
  operator routes in `static-supplement`;
- `src/seon/web/router.cljs` directly owns a `db/listen!` plus
  desired/accepted database-value settle loop;
- most migrated `src/seon/web/serve.cljs` handlers remain private two- or
  three-argument functions; and
- `/agent/{id}/feed`, `/agent/{id}/debug/feed`, and `/data/feed` remain live.

## Dependency and source ledger

| Dependency or mechanism | Selected revision and source | Established contract |
|---|---|---|
| Reitit | `106fc4c7` (`0.9.1-96`), `reference-code/reitit/modules/reitit-core/src/reitit/core.cljc:82-380` | A router is an immutable replaceable value. Default construction detects unresolved path and name conflicts and throws. |
| Reitit route resolution | `106fc4c7`, `reference-code/reitit/modules/reitit-core/src/reitit/impl.cljc:110-167` | Raw rows are expanded and meta-merged. Duplicate/conflicting paths enter the default conflict report; they are not a safe last-row-wins crossing. |
| Reitit Ring compilation | `106fc4c7`, `reference-code/reitit/modules/reitit-ring/src/reitit/ring.cljc:58-79,121-148` and `reference-code/reitit/modules/reitit-core/src/reitit/middleware.cljc` | Per-method route data reaches middleware `:compile`; middleware may disappear by returning no compiled middleware when its route-data key is absent. |
| Reitit creation cost | `106fc4c7`, `reference-code/reitit/perf-test/clj/reitit/router_creation_perf_test.clj:40-55` | One small route-set rebuild is cheap; no second cache or incremental route compiler is justified. |
| Route data | `src/seon/route.cljs:43-113` | `:seon.route/handler` is native symbol data, and `:seon.route/name` is the managed identity. |
| Late symbol lookup | `src/seon/eval.cljs` (`lookup-value`) | One symbol-to-function mechanism already serves render and route dispatch and observes redefinitions without a router rebuild. |
| Reactive scheduling | `src/seon/reactive.cljs:471-584` | `observe!`/`unobserve!` own one active computation, newest pending database value, equality suppression, interest replacement, and final-consumer release. A computation must return a `:seon.db/value` plus `:seon.db/read-evidence` envelope. |
| Read evidence | `src/seon/db.cljs` (`with-read-evidence`) and `src/seon/db/internal.cljs` (`run-with-read-evidence`) | Route computation must capture the actual route query. A bare vector returned from `compute` is a malformed reactive result, not a supported shortcut. |
| Manifest route curation | `src/seon/config.cljs:1506-1519` | `resolve-routes` removes seed rows by `:seon.route/name`; absence of an explicit manifest preserves existing database facts. |
| Declarative reconciliation | `src/seon/client.cljs` (`apply-config!`) | The desired route population reconciles through `seon.state/reconcile!` with managed identity `:seon.route/name`; converged application writes nothing. |
| Launch capability schema | `src/seon/client/schema.cljc` and `src/seon/launch.cljc` | The current closed capability has only `:seon.client/autonomous?`; every descriptor constructor and validator must understand the new optional operator-door grant. |
| Operator descriptor construction | `script/seon/dev/config.clj` (`load!` calling `launch/default-descriptor`) | The operator, rather than the pod or database, is the place that explicitly grants operator doors. |
| SSE reference idiom | `reference-code/datastar-clojure/src/dev/examples/tiny_gzip.clj` | The page shim and long-lived stream remain separate GET routes; this cut changes their route names, not the feed mechanism. |

## Atomic ownership groups

### Boundary A — route authority and version crossing

These paths form one source/build boundary because newly seeded handler symbols
must never ship against the old handler arities:

- `src/seon/route.cljs`
- `src/seon/web/router.cljs`
- `src/seon/web/serve.cljs`
- `src/seon/client/schema.cljc`
- `src/seon/launch.cljc`
- `src/seon/client.cljs`
- `script/seon/dev/config.clj`
- `src/seon/web/datastar.cljs`
- `src/seon/web/debug.cljs`

The directly owned tests are:

- `test/seon/route_test.cljs`
- `test/seon/web/router_test.cljs`
- `test/seon/web/serve_test.cljs`
- `test/seon/web/datastar_test.cljs`
- `test/seon/launch_test.cljs`
- `test/seon/client_initialization_test.cljs`
- `test/seon/dev/config_test.clj`
- descriptor/branch/operator tests whose expected closed capability changes

Documentation and closure travel with the same boundary:

- `docs/seon/architecture/ui.md`
- `src/seon/web/AGENTS.md`
- `.agents/skills/datastar-web-ui/SKILL.md`
- `docs/seon/issues/static-routes-bypass-database-route-authority.md`

### Boundary B — reactive router ownership

After Boundary A is proven, `src/seon/web/router.cljs` and its focused tests
replace `reconcile-cache!`, `refresh-routes!`, `settle-routes!`, and the direct
listener lifecycle with one `seon.reactive` consumer.

The compute function must wrap the route query in `db/with-read-evidence` and
return the canonical projection as `:seon.db/value`. Notification accepts only
a valid projection, retains the last valid compiled router on failure, and
records the failure through the existing error boundary. `detach!` delegates
to `reactive/unobserve!`.

Reactive registration IDs already prevent an evaluation from a removed
registration publishing into a new registration. Re-observing the same
consumer on an existing registration replaces that consumer callback rather
than minting a new registration, so detach/attach and install/hot-reload storms
still require an explicit behavioral falsifier before deciding that no small
router generation fence is needed.

## Exact static remainder

After Boundary A, static routing is a tested-closed map of exactly four
pattern/method pairs:

| Pattern | Method | Reason |
|---|---|---|
| `/css/{*path}` | GET | Disk artifact needed independently of database route facts |
| `/js/{*path}` | GET | Disk artifact needed independently of database route facts |
| `/_seon/ready` | GET | Health/readiness must answer while database admission is unavailable |
| `/_seon/operator/config` | POST | Repair door for new-code/old-database crossing |

The configuration repair door retains same-origin, admission, kernel-loopback,
and launch-capability gates. The open issue's shorter “readiness + assets”
wording is stale; the later owner ruling and implementation design correctly
retain this fourth route. No product, lifecycle, debug, data-browser,
composition, or ordinary operator route remains literal static data.

## Capability requirements

`:seon.route/capability` names a launch-bound key. The first key is
`:seon.launch/operator-doors?`. It is optional route data and never a database
grant.

The capability must not be inferred from `:seon.client/autonomous?`:

- the `bin/seon` operator-built descriptor grants operator doors explicitly;
- operator-created branch and shared-writer cluster descriptors preserve the
  grant even when a branch is non-autonomous;
- embedded or downstream fallback descriptors omit the grant unless their own
  boundary explicitly supplies it; and
- the retained process capability remains stable across hot reload and is
  consulted at dispatch together with the kernel-derived loopback predicate.

Adding the key unconditionally inside `launch/default-descriptor` would grant
embedded fallback pods accidentally. Dropping it from non-autonomous branch
descriptors would break Inspect and lifecycle evidence doors. Therefore
`script/seon/dev/config.clj`, every descriptor derivation, the closed
capability schema, and their tests are one ownership group.

## Handler and middleware requirements

Every migrated route handler is one public Ring function accepting the request
map, reading the WHATWG Request from `:seon.http/request`, and reading path
parameters from `:path-params`. Public functions carry a real map-shaped Malli
boundary; the existing `::ring-request :any` should not be replicated across
the new handler surface.

Structural gates are derived during `projection->routes`:

- every POST receives same-origin middleware automatically;
- `:seon.route/admitted? true` installs the admission middleware;
- `:seon.route/capability` installs the combined launch-capability and
  loopback middleware; and
- an absent gate attribute compiles to no middleware.

`route-query`, canonical projection sorting, route-data projection, and the
compiled cache key must all carry the two new attributes. Omitting either from
one stage can leave a previously compiled ungated handler live after a datom
change.

Handler symbols continue to resolve per request. A route whose symbol is
unresolved returns 500, records one core fault, and self-heals on the next
request after the symbol resolves. No separate handler registry or stored
warning is introduced.

## SSE crossing

Boundary A changes these route identities and patterns together:

- `:seon.route/agent-sse` → `/agent/{id}/sse`
- `:seon.route/agent-debug-sse` → `/agent/{id}/debug/sse`
- `:seon.route/data-sse` → `/data/sse`

The route seeds, Datastar page opener strings, debug/data page strings, tests,
architecture, localized web instructions, and Datastar skill must change in
one cut. Explicit reconciliation retracts the old managed route identities.
Already open `/feed` clients require page reload/reconnect after that crossing;
the live proof must not treat a stale opener as a feed implementation failure.

## Duplicate-path rollback hazard

Rollback is the highest-risk contract. Vendored Reitit does not safely prefer
the old static row or the new datom row when their paths overlap. Default
router construction reports unresolved duplicate/conflicting paths and throws.
Old code opening a database that already contains the migrated rows can
therefore fail in `router/attach!` before the HTTP configuration repair door is
available.

The existing prose “run old-code config apply before relying on the old
router” is insufficient unless startup order proves how that apply occurs.
The required rollback proof is:

1. select the old manifest before old-code startup;
2. prove old-code declarative reconciliation runs before router attachment;
3. prove it retracts every new route identity from the managed population;
4. prove the old router then builds without duplicate paths; and
5. prove readiness and the old static product routes respond.

If explicit-manifest startup does not guarantee that order, Boundary A is not
safe to merge until an offline/operator database repair can retract the new
rows before the old router builds.

Forward crossing remains repairable: new code over an old config-free database
starts with the four bootstrap routes, migrated product paths initially fall
through, and the bootstrap configuration door applies an explicitly selected
manifest to install the new rows. A second apply must be a no-op.

## Shortest source falsifiers

- Exact bootstrap equality contains only the four declared pattern/method
  pairs.
- A source scan finds no second literal product-route catalog outside
  `core-routes-tx`.
- Every POST, including an added route fixture, receives same-origin
  middleware without a per-row literal.
- `admitted?` absent performs no admission check; true refuses unavailable
  admission before handler invocation.
- A capability route returns 403 unless both the retained named capability and
  kernel-loopback predicate pass.
- A missing handler symbol returns 500, records one core fault, and resolves on
  the next request after the function appears.
- A route-datom transaction changes dispatch without `router/install!`.
- An unrelated transaction performs zero route computation and router rebuild.
- Detach during an in-flight query cannot publish, and rapid
  detach/attach/install cycles cannot restore a stale projection.
- Manifest `:removes` retracts a migrated row and dispatch changes accordingly.
- A crossing fixture pins Reitit's duplicate-path build failure.
- `/agent/{id}/sse`, `/agent/{id}/debug/sse`, and `/data/sse` are the only
  maintained stream paths.

## Shortest live falsifiers

- **Forward:** boot new code on an old database without a selected manifest;
  reach `/_seon/operator/config`; explicitly apply the manifest; exercise a
  migrated product route; apply again and observe no transaction.
- **Rollback:** boot old code with the old manifest explicitly selected; prove
  reconciliation precedes route attachment; reach readiness and an old static
  product route with no Reitit conflict.
- Exercise `/chat`, `/stop`, `/data`, `/agents/run`, configuration apply, and
  one operator evidence/control door, preserving their status and response
  contracts.
- Use server-side clients to receive `datastar-patch-elements` from all three
  renamed SSE paths; reload an old page rather than expecting its stale
  `/feed` opener to migrate in place.
- Compare `seon.reactive/measurements` before and after an unrelated
  transaction: no route evaluation or notification changes.
- Stop the web/runtime owner and prove no router reactive consumer, timer, or
  database interest remains.

## Deferred Stage-4 configuration work

This route boundary does not absorb the remaining configuration cleanup:

- collapsing duplicate port, port-file, and cluster-directory defaults;
- migrating `SEON_WEB`, `SEON_SHELL`, `SEON_RENDER_STRICT`, brand, blob, and
  UDS environment gates into database facts or launch data;
- proving already-created independent async fibers observe an ambient
  configuration refresh;
- deduplicating downstream embedding environment scrubbing; and
- absolutizing configuration directory coordinates.

Those changes follow the route cut and share its final clean-boot,
configuration-idempotence, and downstream-cluster integration checkpoint.
They do not widen the route cut's source ownership or postpone its rollback
proof.
