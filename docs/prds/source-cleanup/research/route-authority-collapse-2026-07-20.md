---
type: research
status: complete
tags: [research, web, database, architecture]
---

# Route-authority collapse — implementation-ready design (2026-07-20)

Owner ruling (2026-07-20): the database is where routing changes; collapse to
ONE truth system. Route datoms are the single authority; handlers are named by
SYMBOL in the datom and resolved at dispatch; the reitit router is a pure
derivation that re-derives on route-datom transactions through the existing
`seon.reactive` registration mechanism; the only static remainder is a fixed,
tested-closed bootstrap set (readiness + static assets) that must route before
a database session exists. Operator doors gate on launch-bound capabilities at
dispatch. The `/agent/{id}/feed` → `/agent/{id}/sse` rename is a seed-row edit
riding this collapse in stage 4, not the stage-2 freeze.

Issue: [[../../../seon/issues/static-routes-bypass-database-route-authority]].
Stage: source-cleanup stage 4, beside config reconciliation
([[../roadmap.md]] §Stage 4).

## Dependency ledger

| Dependency | Where read | What it establishes |
|---|---|---|
| reitit `106fc4c7` (0.9.1-96) submodule | `reference-code/reitit/modules/reitit-core/src/reitit/core.cljc:82-300` | router variants (`linear`/`lookup`/`trie`/`mixed`/`quarantine`); `r/router` auto-selects; router is an immutable value, swappable by rebuilding |
| reitit creation cost | `reference-code/reitit/perf-test/clj/reitit/router_creation_perf_test.clj:40-55` | 100 random routes: default router ~11 ms (2014 MacBook, JVM); linear 7 ms. ~30 routes is low-single-digit ms |
| Datahike symbol type | `src/seon/route.cljs:47` (`::handler :symbol` → `:db.type/symbol`) | handler symbols store natively; no string round-trip |
| `seon.eval/lookup-value` | `src/seon/eval.cljs:502-527` | the ONE late symbol→value resolution (render engine, data-browser renderer symbols, route handlers); never throws, nil on miss; sees `eval-str` redefinitions immediately |
| Data-browser symbol indirection | `docs/prds/source-cleanup/research/universal-data-browser-design-2026-07-20.md` (schema `:properties` render symbols → resolved through `seon.render`/`lookup-value`) | the just-adopted precedent this design reuses — no second resolution mechanism |
| `seon.reactive/observe!`/`unobserve!` | `src/seon/reactive.cljs:471-546` | one registration key, all-attributes interest, Datahike read-evidence demand, newest-pending-only, equality suppression, consumer notify |
| Route schema + seed | `src/seon/route.cljs:43-113` (`::pattern ::method ::name ::owner ::handler ::middleware`, `core-routes-tx`) | the authority to extend |
| Static supplement + router build | `src/seon/web/router.cljs:228-330` (`static-supplement`, `mw-registry`, `route-handler`, `build-ring-handler`, bespoke `attach!` listen/settle at 338-436) | what is deleted/replaced |
| Dispatch + handlers + predicates | `src/seon/web/serve.cljs` (`handle-readiness!` :99, `serve-static!` :154, POST handlers, `loopback-peer?` :1470, `same-origin?` :1587, `router/install!` :1624, readiness-only server :1756-1772) | the handlers to refactor to Ring shape; the injected predicates |
| Config reconciliation | `src/seon/client.cljs:1922-1954` (`apply-config!` → `state/reconcile!`, managed-identity `:seon.route/name`), `src/seon/config.cljs:87-89,1505-1518` (`:seon.config/route-spec` `:removes`, `resolve-routes`) | routes are already manifest-curated, provenance-scoped, retract-on-drop |
| Launch capability | `src/seon/client.cljs:243-283` (`default-launch-capability`, `claim-launch-capability!`, retained across hot reload) | the process-local capability the operator gate consults |
| Existing tests | `test/seon/web/router_test.cljs` (supplement/loopback/admission tests) | tests to migrate; the bootstrap-closure test's home |
| Architecture target | `docs/seon/architecture/ui.md` §"Routing is data" | already states the target: router = pure derivation of route datoms; `:compile` middleware reads route-data and vanishes when N/A |

## 1. Classification and migration table

Every current `static-supplement` row (`router.cljs:255-305`), classified per
the issue's acceptance criteria. "Datom" rows become `core-routes-tx` seed
rows upserted by `:seon.route/name`; new attributes are defined in §2.

| Route | Method | Class | Seed row (`:seon.route/*`) | Notes |
|---|---|---|---|---|
| `/css/{*path}` | GET | **bootstrap-static** | — | disk artifact serving; needed by pre-admission/readiness-only pages; zero database dependency; the derived router does not exist pre-database |
| `/js/{*path}` | GET | **bootstrap-static** | — | same |
| `/_seon/ready` | GET | **bootstrap-static** | — | must answer 503 before a database session exists; the readiness-only server (`serve.cljs:1756-1772`) already dispatches `handle-readiness!` directly, bypassing the router entirely |
| `/data` | GET | **datom** (debug product) | `{::pattern "/data" ::method :get ::name ::data ::handler 'seon.web.debug/data-page!}` | handler already Ring-shaped |
| `/data/feed` | GET | **datom** (debug product) | `{::pattern "/data/sse" ::method :get ::name ::data-sse ::handler 'seon.web.debug/data-feed!}` | rides the §6 `/sse` rename |
| `/chat` | POST | **datom** (product) | `{::pattern "/chat" ::method :post ::name ::chat ::handler 'seon.web.serve/handle-chat! ::admitted? true}` | |
| `/stop` | POST | **datom** (lifecycle) | `{::pattern "/stop" ::method :post ::name ::stop ::handler 'seon.web.serve/handle-stop!}` | deliberately NOT admitted — stop must work when admission is unavailable (today's `post-handler`, not `admitted-post-handler`) |
| `/resume` | POST | **datom** (lifecycle) | `{… ::name ::resume ::handler 'seon.web.serve/handle-resume! ::admitted? true}` | |
| `/clear` | POST | **datom** (lifecycle) | `{… ::name ::clear ::handler 'seon.web.serve/handle-clear! ::admitted? true}` | |
| `/log` | POST | **datom** (product) | `{… ::name ::log ::handler 'seon.web.serve/handle-log! ::admitted? true}` | |
| `/agents/run` | POST | **datom** (product/composition door) | `{… ::name ::agents-run ::handler 'seon.web.serve/handle-agent-run! ::admitted? true}` | downstream clusters may drop it via manifest `:removes` |
| `/agent/{id}/complete` | POST | **datom** (lifecycle) | `{::pattern "/agent/{id}/complete" ::method :post ::name ::agent-complete ::handler 'seon.web.serve/handle-complete-agent!}` | not admitted today; handler reads id from `:path-params` after refactor |
| `/_seon/operator/config` | POST | **capability datom** | `{… ::name ::operator-config ::handler 'seon.web.serve/handle-config-apply! ::admitted? true ::capability :seon.launch/operator-doors?}` | today same-origin+admitted but NOT loopback; the capability gate adds loopback-peer — a deliberate tightening (the only caller is `bin/seon config apply` on loopback). Flagged in §Risks |
| `/_seon/operator/quiesce` | POST | **capability datom** | `{… ::name ::operator-quiesce ::handler 'seon.web.serve/handle-operator-quiesce! ::capability :seon.launch/operator-doors?}` | unadmitted by design (test pins it) |
| `/_seon/operator/blobs` | POST | **capability datom** | `{… ::name ::operator-blobs ::handler 'seon.web.serve/handle-operator-blobs! ::capability :seon.launch/operator-doors?}` | |
| `/_seon/operator/processes` | GET | **capability datom** | `{… ::method :get ::name ::operator-processes ::handler 'seon.web.serve/handle-operator-processes! ::capability :seon.launch/operator-doors?}` | |
| `/_seon/operator/product-evidence` | POST | **capability datom** | `{… ::name ::product-evidence ::handler 'seon.web.serve/handle-product-evidence! ::capability :seon.launch/operator-doors?}` | |

Already-seeded core rows (`route.cljs:98-113`) are unchanged except: the
explicit `::middleware ::same-origin` entries on `::agents-create` and
`::agent-call` are dropped (same-origin becomes structural, §2), and the two
feed rows take the §6 rename.

## 2. Schema — minimal extensions, and two structural gates

`::handler :symbol` already exists. Two new attributes, both optional in the
`::route` entity map, registered in `seon.route` beside the others:

```clojure
(schema/register! ::admitted? :boolean)   ; dispatch refuses 503 before executable admission
(schema/register! ::capability :keyword)  ; launch-capability key required at dispatch (operator door)

```

`::route` gains `[::admitted? {:optional true} ::admitted?]` and
`[::capability {:optional true} ::capability]`. Absent = no gate (no stored
nil, no `[:maybe]`).

Two gates become **computed structural rules** instead of per-row middleware
literals (no hand-maintained lists):

- **Same-origin on every POST.** Today's router docstring already states the
  invariant ("a reitit middleware on every state-changing POST route");
  `same-origin?` allows absent-Origin callers (`serve.cljs:1592-1596`), so
  applying it to all POSTs including operator doors is behavior-preserving and
  secure-by-construction — an agent-added POST route can never forget it.
  `projection->routes` adds `same-origin-mw` to every `:post` entry; the
  per-row `::middleware ::same-origin` literals disappear from the seed.
- **`::admitted?`/`::capability` project as route-data**, consumed by two
  reitit **`:compile` middleware** that vanish when the key is absent
  (the ui.md §routes idiom): `admitted-mw` returns 503 with the
  `admission/unavailable` message (replacing `admitted-post-handler`);
  `operator-door-mw` requires BOTH the loopback peer predicate AND the named
  key truthy in the retained launch capability, refusing 403 otherwise
  (replacing the `loopback-peer` literal rows). `mw-registry` keeps the
  keyword→middleware resolution as the one registry.

`::middleware` itself stays a single optional keyword for genuinely bespoke
per-route middleware (none needed by the migrated set); its cardinality is not
changed, so no Datahike cardinality migration rides this collapse.

**Launch capability key.** `::capability` values name keys in the retained
launch capability map (`client.cljs:243-283`). This design introduces ONE:
`:seon.launch/operator-doors?`, asserted `true` by the `bin/seon`-built launch
descriptor and absent for embedded/downstream pods. It is process-local and
launch-bound — never a database fact, so it can never become an agent-writable
grant (the issue's acceptance line). Because `seon.web.serve` cannot require
`seon.client` (client requires serve), the retained-capability owner moves to
the leaf `seon.launch` (client already requires it as `launch`); serve's
injected `:seon.web.router/capability?` predicate reads it there, exactly like
the existing `same-origin?`/`loopback-peer?` injections through
`router/install!`.

## 3. Handler-symbol resolution at dispatch

Reuse the ONE existing mechanism, unchanged: `router/route-handler`
(`router.cljs:169-181`) already wraps every db-projected handler symbol and
resolves it per-request via `seon.eval/lookup-value` — the same late binding
the render engine uses for `:seon.render/html` symbols and the data-browser
design uses for schema-property renderer symbols. The collapse extends its
coverage to every migrated row; nothing new is built.

- **Handler shape.** Every migrated serve handler is refactored to the
  Ring shape the core handlers already use: one public `(defn handle-x! [r]
  …)` taking the Ring request map, reading its WHATWG Request from
  `:seon.http/request` and path params from `:path-params` (e.g.
  `handle-complete-agent!` reads `(get-in r [:path-params :id])`). The
  vestigial `res` parameter (already ignored — Bun handlers return Response
  values) is dropped. Handlers become public because they are now the
  addressable, program-graph-indexed route surface; the `post-handler` /
  `admitted-post-handler` adapter wrappers are deleted.
- **Resolution failure = errors-as-values.** Route exists (the datom is
  asserted) but the symbol resolves to nil → this is a core misconfiguration,
  not a client error: **500**, never 404 (404/302-home remains the no-match
  path). In addition to today's console line, the miss records one fault datom
  via `seon.error/record!` so the existing warnings render derivation can
  surface "route ::name names unresolved symbol X" from current facts — no
  stored warning, self-heals when the symbol appears.
- **Hot reload.** Because resolution is per-request, a Shadow hot reload or an
  agent `eval-str` redefine takes effect on the next request with no router
  rebuild and no re-transact. `install!` continues to rebuild the compiled
  handler on reload only because the injected bootstrap/predicate fns change
  identity; route facts are untouched.
- **Build inclusion.** `router.cljs` already carries build-inclusion-only
  requires for `seon.web.datastar` and `seon.web.reactive.call`. The migrated
  handlers live in `seon.web.serve` and `seon.web.debug`, both already in the
  build (serve requires router; router requires debug). No new requires; the
  serve→router direction stays acyclic because router names serve fns only as
  SYMBOLS in data, resolved late — symbol indirection is exactly what breaks
  the would-be require cycle.

## 4. Derivation — route datoms → reitit router via `seon.reactive`

Replace `attach!`'s bespoke `db/listen!` + desired/accepted settle loop
(`router.cljs:338-436`) with one `seon.reactive` registration — the owner's
named mechanism, which already provides everything the bespoke loop
reimplements (one active computation, newest-pending-only, equality
suppression, writer-interest ownership):

```clojure
(reactive/observe!
  {:seon.reactive/key ::routes
   :seon.reactive/consumer-key ::router
   :seon.reactive/compute (fn ^:async [db] (route-projection (await (db/query {… ::db/db db}))))
   :seon.reactive/notify (fn [projection] (accept-projection! projection))})

```

- `compute` runs the existing `route-query` + `route-projection`
  canonicalization against the delivered immutable database value; Datahike
  read evidence scopes the interest, so transactions not touching
  `:seon.route/*` never demand recomputation, and `seon.reactive`'s equality
  suppression means `notify` fires only when the canonical projection actually
  changed.
- `accept-projection!` stores the projection and rebuilds the compiled
  ring-handler with the current injected config — the existing `cache-key`
  equality stays as the config-side half of the check. `detach!` becomes
  `reactive/unobserve!`. The `::desired-db`/`::accepted-db`/`settle-routes!`
  machinery is deleted (~100 lines): `seon.reactive` owns exactly that
  scheduling.
- **Compile cost.** Grounded in
  `reference-code/reitit/perf-test/clj/reitit/router_creation_perf_test.clj`:
  100 random routes compile in ~11 ms with default conflict resolution on a
  2014 JVM laptop. The post-collapse table is ~24 rows (~17 patterns); scaled,
  a rebuild is low-single-digit milliseconds, and it fires only when the route
  SET changes — boot seed, explicit `config apply`, an agent adding an
  `/agent/{id}/app/{x}` row. Handler redefines never rebuild (late symbols).
  Per-transaction re-derivation is therefore cheap; **no additional
  memoization is needed** beyond the existing projection/config cache-key.
  If a future measurement contradicts this, the fix is `seon.reactive`'s
  `settle-ms`, not a second cache.

## 5. Bootstrap set — fixed, tested closed

After migration `static-supplement` shrinks to a pure `bootstrap-routes` fn
producing exactly:

```clojure
[["/css/{*path}"  {:get …}]
 ["/js/{*path}"   {:get …}]
 ["/_seon/ready"  {:get …}]]

```

Closure test (in `test/seon/web/router_test.cljs`):

```clojure
(deftest bootstrap-set-is-closed
  (is (= {"/css/{*path}" #{:get}
          "/js/{*path}"  #{:get}
          "/_seon/ready" #{:get}}
         (into {} (map (fn [[pattern data]] [pattern (set (keys data))]))
               (#'router/bootstrap-routes fixture-config)))))

```

Exact map equality over pattern AND method set — adding, renaming, or
method-widening any static row fails the test, so the bootstrap set can never
silently grow back into a second product catalog. A companion assertion greps
nothing: `db->routes` remains the only other route source feeding
`build-ring-handler` (the issue's "no second literal product-route catalog"
scan is satisfied structurally — there is no third `into`).

Justification for each member: readiness must answer 503 before any database
session exists (the readiness-only server path bypasses the router entirely,
and the derived router's first value requires a database); static assets are
disk build artifacts required by every page including pre-admission ones and
have zero database dependency.

## 6. The `/feed` → `/sse` rename (seed-row edit, stage 4)

Riding the same collapse commit series, per the owner ruling — NOT the
stage-2 rename freeze:

- Seed-row pattern edits: `::agent-feed` → `"/agent/{id}/sse"`,
  `::agent-debug-feed` → `"/agent/{id}/debug/sse"`, new `::data-sse` →
  `"/data/sse"` (`:seon.route/name` keywords rename with them; identity-upsert
  plus reconcile retracts the old names).
- Consumer string edits: the shim `data-init` opener URLs in
  `seon.web.datastar` (`/agent/root/feed`, `/agent/{id}/feed`) and
  `seon.web.debug`, verification tooling, `datastar-web-ui` skill, ui.md, and
  tests.
- Because routes are config-manifest-seeded, the rename lands on the same
  boundary every route change already requires: `bin/seon cluster reset` or
  `bin/seon config apply` per cluster (documented in `src/seon/web/AGENTS.md`
  route-truth note).

## 7. Sequencing with config reconciliation (stage 4)

How the paths interact today, confirmed in source: `client/apply-config!`
(`client.cljs:1922-1954`) builds the desired set as
`(config/resolve-routes (route/core-routes-tx) manifest)` — the manifest's
`:seon.config/routes` `:removes` drops rows by `:seon.route/name`
(`config.cljs:87-89,1505-1518`) — and reconciles through ONE provenance-scoped
`state/reconcile!` with managed identity `:seon.route/name`, so removed rows
retract and converged applies write nothing.

The collapse **strengthens this path without duplicating it**: growing
`core-routes-tx` with the migrated rows automatically makes every product,
lifecycle, debug, and operator route manifest-curatable (a downstream cluster
drops `/agents/run` with one `:removes` entry) and reconciliation-managed.
Operator rows are seeded unconditionally — their conditionality is the
dispatch-time capability gate, never seed-time presence, so config apply needs
no launch-state input and stays a pure manifest function. Ordering within
stage 4: land the route collapse first (it is self-contained and
behavior-preserving), then the config env-gate migration; both share the
stage-4 gate (`bin/seon up` from clean checkout, config-apply idempotence
proof).

## Ordered implementation steps (owned files per step)

1. **Schema + seed** — add `::admitted?`/`::capability` registrations, extend
   `::route`, grow `core-routes-tx` with the §1 rows, drop the per-row
   `::same-origin` literals, apply the §6 pattern renames.
   Owns: `src/seon/route.cljs`.
2. **Serve handler refactor** — refactor the migrated handlers to public
   one-arg Ring shape; delete `post-handler`/`admitted-post-handler` usage
   sites' assumptions; move the retained launch capability owner to
   `seon.launch` with a leaf accessor; extend the launch descriptor with
   `:seon.launch/operator-doors?`.
   Owns: `src/seon/web/serve.cljs`, `src/seon/launch.cljc`,
   `src/seon/client.cljs` (capability claim delegation), `bin/seon` descriptor
   if it constructs the capability.
3. **Router projection + gates** — structural same-origin-on-POST; the two
   `:compile` middleware (`admitted`, `operator-door`) reading route-data;
   shrink `static-supplement` to `bootstrap-routes`; delete the deleted rows;
   extend `install!` config with `:seon.web.router/capability?`.
   Owns: `src/seon/web/router.cljs`.
4. **Reactive derivation swap** — replace `attach!`/`detach!` internals with
   `reactive/observe!`/`unobserve!`; delete the settle loop.
   Owns: `src/seon/web/router.cljs`.
5. **Tests** — bootstrap-closure test (§5); migrate the existing supplement/
   loopback/admission tests to datom-projection form; add: capability-gate
   refusal (403 without `:seon.launch/operator-doors?`), unresolved-symbol
   fault datom + 500, live router re-derivation on a route transaction,
   reverse-routing by the new names, manifest `:removes` retraction of a
   migrated row.
   Owns: `test/seon/web/router_test.cljs`, `test/seon/web/serve_test.cljs`.
6. **Docs + verification** — update ui.md §routes (supplement paragraph),
   `src/seon/web/AGENTS.md` route truth, `datastar-web-ui` skill route list,
   `route.cljs`/`router.cljs` docstrings; live proof on the default cluster:
   `bin/seon cluster reset default`, drive `/chat`, `/stop`, `/data`, an
   operator door, and the renamed `/sse` feeds; close the issue with evidence.
   Owns: docs listed, `docs/seon/issues/static-routes-bypass-database-route-authority.md`.

Each step is one path-limited commit; steps 1-3 must land within one build
checkpoint (the seeded symbols and the refactored handler shapes must ship
together, or seeded rows would resolve to two-arg handlers). A cluster reset
or config apply is required for the new rows — coordinate the freeze per
CLAUDE.md checkpoint rules.

## Risks and open questions

- **Two-arity window (highest risk).** A cluster whose database already holds
  the OLD seed rows running NEW code (or vice versa) crosses handler-shape
  changes only for the pre-existing core rows — those handlers keep their
  Ring shape, so the window is limited to the NEW rows, which old databases
  simply don't have (their static supplement still serves them only if old
  code runs). Mitigation: land steps 1-3 atomically per checkpoint; the
  operator boundary is the normal `config apply`/reset.
- **`/_seon/operator/config` tightening.** Adding loopback-peer to the config
  door is a deliberate behavior change (today same-origin+admitted only). The
  only known caller is `bin/seon config apply` on loopback. If a remote
  config-apply consumer exists downstream, the row's `::capability` can be
  dropped by that cluster's manifest — surfaced for owner confirmation.
- **Same-origin-on-all-POST.** Behavior-preserving for every current row
  (operator doors gain a check that passes for header-less callers), but it
  is a structural rule replacing explicit per-row middleware; ui.md should
  state it so agents adding POST routes know the gate is automatic.
- **`admission/unavailable` message coupling.** The `admitted` middleware
  reuses the existing envelope text; keep asserting behavior not strings in
  tests.
- **`observe!` all-attributes interest.** `seon.reactive` installs an
  all-attributes interest scoped by read evidence; confirm in the live proof
  that unrelated transactions do not demand route recomputation (measure via
  `reactive/measurements`).
- **Open: capability key granularity.** One `:seon.launch/operator-doors?`
  gates all five operator rows. If per-door granularity is ever needed, the
  mechanism already supports it (each row names its own key); do not
  pre-split now.
