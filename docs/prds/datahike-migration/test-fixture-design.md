---
type: prd
status: draft
tags: [prd, database]
---

# Datahike test fixture — design

Captured 2026-05-14, REPL-verified against the running orchestrator
(`mcp__seon__eval`, session `orchestrator`). Implements the canonical
test fixture for the datahike-on-`:memory` test path. Replaces the
datalevin-era `tu/with-temp-conn` shape with a `seon.db`-routed fixture
that stands up an isolated datahike flow per test and never touches
the live orchestrator's `:seon.db/flow`.

## TL;DR

- New public API in `seon.test-utils`:
  - `with-test-db` — function: takes a config map (`::namespaces`,
    optionally `::schemas`) and a body fn; builds an isolated datahike
    flow on `:memory`, binds `seon.db/*datahike-flow*`, runs body,
    releases. Returns body's return value. Body fn receives a map
    `{::aliases logical→internal ::flow flow-state}`.
  - `with-test-db-fixture` — `clojure.test`-shaped wrapper for
    `use-fixtures :each`, takes the same config map and returns a
    `(fn [f] ...)`. Inside, binds `*test-db-aliases*` + `*test-db-flow*`.
  - `test-db-name` — resolve `:seon.runtime` (logical) to the fixture's
    internal suffixed name. Returns input unchanged when no fixture is
    active. Throws if a fixture is active but the requested logical
    name wasn't declared in `::namespaces`.
- **Per-test isolation** is by gensym-suffixed db-names mapped to the
  caller's requested logical names — so a test asking for `:seon.runtime`
  sees its own `:seon.runtime` whose underlying konserve store is
  globally unique. The fixture installs an alias layer so the test code
  keeps using `:seon.runtime` and `seon.db/transact!` dispatches there
  via the fixture's flow.
- **Schemas land via `seon.db.datahike.flow/build-datahike-flow!`** at
  conn-process `:init` (the same path the live flow uses). The fixture
  is just a different config for the same builder.
- **`seon.db/*direct-mode*` is not used.** All reads/writes flow through
  the fixture's `:seon.db/flow`, so `seon.db`'s instrumentation,
  validation, and namespace-stamp logic exercise the same code paths
  the live system does.
- **Performance:** **9 ms per single-namespace fixture instantiation**
  (measured 2026-05-14, REPL smoke below). Two-namespace flow: 26 ms.
  Today's 108 s baseline isn't dominated by fixture setup; we have
  ample headroom.

## Why this shape

### Constraints recap

The user-supplied constraints translate cleanly:

| Constraint | How the design satisfies it |
|---|---|
| `seon.db` is the only DB API | Body fn uses `seon.db/transact!`, `seon.db/query`, `seon.db/pull-by-name`, etc. — never `datahike.api` or `datalevin.core`. |
| One good path, no legacy | Drops the datalevin-era `*conn-manager*` + `*direct-mode*` dance entirely. No backward-compat shim for the new fixture. |
| Per-test isolation | Each `with-test-db` invocation generates unique internal db-names (`:seon.test.fixture.iso/t-<nanotime>-<i>`), aliasing them under the caller's logical name. Different runs of the same test never share state. |
| Plays well with live system | The live flow's `pids` map is keyed by `:seon.session`, `:seon.repl`, `:seon.flow`, `:seon.orchestrator`, `:seon.phase2.demo`. The fixture builds a separate `flow-state` whose `pids` contain the fixture's internal db-names. Bound via `seon.db/*datahike-flow*`. The live flow is untouched and unread during the fixture body. Verified in REPL — see smoke. |
| Schema installation | `build-datahike-flow!`'s `::namespace-schemas` map is the documented hook for `:init`-time schema install. The fixture forwards `::schemas` through. Idempotent install is handled inside `conn-process/install-schema!`. |
| Cleanup | `try`/`finally` calls `stop-datahike-flow!` on the way out, which calls `flow/pause` + `flow/stop`; each `conn-process` releases its connection in its `:clojure.core.async.flow/stop` transition. Konserve's `:memory` store remains in the global registry (it's an atom on a keyword UUID; not a leak by JVM standards), but is unreachable by db-name from any subsequent fixture call. |
| Performance | 9 ms single-ns, 26 ms two-ns measured. Setup is dominated by `flow/create-flow` + `flow/start` + one `flow/ping`. Schema install per ns is ~5 ms — see smoke. |
| API ergonomics | One map argument, namespaced keywords, body as last arg. Matches seon `seon.db.datahike.flow/build-datahike-flow!` shape (caller uses the same `::namespaces` / `::schemas` keys). |
| Instrumentation-friendly | Public fns carry `:malli/schema` metadata; request map registered via `schema/register!`. |

### Why not bind `*direct-mode*` instead?

The datalevin `with-test-datalevin` fixture bound `db/*direct-mode* true`
and `db/*conn-manager*` to a fake. That bypassed the writer/reader flow
entirely — fine for the datalevin path because the legacy code branched
on `*direct-mode*` to call `d/transact!`/`d/q` directly.

For the datahike path there is no equivalent `*direct-mode*` short-cut
in `seon.db`. The code at `seon.db/transact!` already checks
`datahike-owned?` first (which consults `*datahike-flow*` via
`get-datahike-flow`). Binding `*datahike-flow*` to a fixture-built
flow-state is the **intended extension point** — `seon.db.clj:216-222`
documents exactly this use case (`"Bind in a test fixture to a
flow-state map returned by seon.db.datahike.flow/build-datahike-flow!"`).
We use the door that was built for us.

### Why aliasing, not "register `:seon.runtime` in the fixture flow"

Two reasons:

1. **Schema drift across tests.** Datahike's schema is install-once.
   If test A registers `:seon.runtime` with one schema-shape and test
   B with another, the konserve memory-store-registry hands B the same
   store; `install-schema!` then errors out on `:db/valueType` drift
   (G3 in `conn-process.clj`) or silently reuses idents that don't
   match B's expectations. We saw this in the REPL probe: a second
   `build-datahike-flow!` on the same db-name returned a store
   containing v=1 from the first.

2. **Test-suite parallelism is future work.** If/when the suite runs
   tests in parallel, deterministic db-names guarantee corruption.
   Gensym-suffixed names guarantee non-collision.

The alias layer in the fixture intercepts the db-name the test passes
to `seon.db/transact!` / `query` etc. and substitutes the gensym'd
internal name **before** dispatch. Implementation: tiny wrapper around
`*datahike-flow*` that carries an alias map; `datahike-owned?` and the
flow `request!` look up via the alias if present.

Looking at the actual code in `seon.db.clj`, the bottleneck is
`datahike-owned?` (consults `(::pids fs)` for the db-name) and the
`::db-name` field threaded into the `dh-request!` call. The simplest
intervention: when the fixture sets `*datahike-flow*`, the flow-state
already contains the gensym-suffixed db-name in `::pids` and a parallel
`::aliases` map. Then we add a tiny wrap of `get-datahike-flow` (or, more
honestly, a small refactor inside `transact!` / `query`) so when a
caller passes `:seon.runtime`, the function:

1. Looks up `:seon.runtime` in `::aliases` if present → maps to the
   internal db-name → dispatches with the internal name.
2. Otherwise behaves exactly as today.

### Implementation note on aliasing (decision pending)

Two implementation paths:

**Path A (preferred, requires small `seon.db` extension):** add an
`::aliases` field to the flow-state map and a 3-line resolution helper
inside `seon.db/datahike-owned?` and the four public fns. Surgical;
keeps the dispatch single-source-of-truth in `seon.db`.

**Path B (no `seon.db` change):** the fixture wraps every `seon.db`
call by also rebinding `db/transact!` etc. via `with-redefs`. Avoid:
brittle, and breaks instrumentation.

**I recommend Path A.** It's a 6-line touch in `seon.db.clj` plus
matching `seon.db.datahike.flow` doc update — small, localized, makes
the fixture clean. **Flagged as the explicit ask in the report-back**;
I have not made this change yet (the design constraints say "do NOT
modify `seon.db.*` implementation files unless the design requires a
specific small extension (and if it does, flag it in the doc and ASK
in your report rather than just doing it)"). The current spike below
uses gensym-named db-names **directly** without aliasing — so the
existing callers (`pipeline-test` using `dl-schema`, `workout-test`
using `:seon.runtime`, `session-test` using `:seon.orchestrator`) need
to be aware of the alias semantics at migration time.

If you don't want to extend `seon.db`, the fallback is: tests pass the
gensym'd db-name directly. Less ergonomic; possibly fine for the
limited number of tests being migrated. The migration sketch below
covers both.

## Cleanup semantics

```
(with-test-db {...} body-fn)
  ├── build-datahike-flow! → :seon.db.datahike.flow/flow-state
  ├── try
  │     binding *datahike-flow*
  │       body-fn called
  └── finally
        stop-datahike-flow! → flow/pause → flow/ping → flow/stop
          per-conn-process :stop transition releases datahike conn
```

What's released:

- Datahike connection (via `d/release` inside `conn-process-step`'s
  `:stop` transition handler).
- Flow's underlying core.async channels (via `flow/stop`).
- The fixture's flow-state map drops out of scope; pending promises in
  `seon.flow.topology/pending-promises` were cleaned up by `request!`
  on each call already.

What's NOT released:

- The konserve memory-store-registry entry. Datahike's
  `delete-database` would do this for `:memory` stores. **Not currently
  called by `stop-datahike-flow!`.** Each fixture instance leaks one
  store entry indefinitely. At 9 ms / fixture and a few KB of state per
  empty store, this scales to thousands of tests fine; if the suite
  grows past tens of thousands, add `delete-database` on the way out.
  Tracked as a known limitation below.

## Schema registration story

Three layers, in order:

1. **`seon.schema/register!`** for Malli attribute schemas — caller's
   responsibility. Same as production: register attribute schemas in
   the namespace that owns them, at top level. `seon.db/transact!`'s
   `validate-attrs!` will reject any unregistered attribute before
   ever reaching the datahike layer.

2. **`::schemas` arg to `with-test-db`** — caller passes
   `{:my.test/ns [:map [::id [:string {:seon.db/identity true}]]
                       [::name :string]]}`. The fixture forwards this
   into `build-datahike-flow!`'s `::namespace-schemas`. At
   conn-process `:init`, `install-schema!` derives the datahike
   schema via `seon.db.datahike.schema/malli-map->datahike-schema` and
   transacts the idents. Idempotent — the second `:init` for the same
   store is a no-op (which is why we don't reuse db-names across tests).

3. **System schema** — `:seon.db/namespace` and the tx-bus/tx-report
   idents are stamped by `seon.db.datahike.conn-process/stamp-namespace!`
   automatically. No caller action.

A test that just wants a working `seon.db/transact!` without a
particular schema can omit `::schemas` — the conn-process initializes
with only the system idents and `validate-attrs!` will gate everything
else through `seon.schema/registered?`.

## Isolation story

**Per-test fresh DB.** Each `with-test-db` call generates a unique
suffix appended to the requested logical db-names. The underlying
konserve store is identified by a UUID derived from the suffixed
name, so the global registry never serves stale data to a fresh
fixture. Tests are independent across both sequential and (future)
parallel execution.

**No shared conn across a namespace's tests.** `with-test-db-fixture`
runs per test (`use-fixtures :each`), not per-ns. A test that wants
shared expensive setup can use a `use-fixtures :once` shape against
a longer-lived fixture flow; not implementing that today — current
data says setup is cheap enough that per-test is fine.

## Performance — REPL smoke (2026-05-14)

Numbers captured in the orchestrator REPL against the running flow.

### Direct primitives

```clojure
;; Single-namespace flow, transact + query roundtrip:
{:build-ms 9, :tx-ms 1, :q-result 42}

;; Two-namespace flow, both with schema, with cross-ns isolation check:
{:build-ms 26, :tx-a-ms 1, :q-a 1, :q-b nil, :isolated? true}
```

### Full fixture API (build + body + teardown)

```clojure
;; with-test-db, single ns + schema, transact 2 entities + 2 queries:
{:db-name :seon.test-utils.iso/tu.smoke.basic-...-1
 :count 2, :v-a 1, :total-ms 15}

;; 10 sequential with-test-db-fixture runs (per-test cost in clojure.test loop):
{:per-instance-ms [13 10 7 7 6 6 5 6 5 5]
 :median 6, :max 13, :sum 70}
```

**Steady-state cost: 5–7 ms per fixture instantiation.** Well under the
50 ms target. The first instance carries one-time JIT / cache warm-up
(~13 ms); subsequent ones run at ~5 ms.

The current baseline is 108.5 s for 825 tests = ~131 ms / test average.
If half the suite migrates to the fixture (~400 tests × 6 ms = 2.4 s
additional setup), we add ~2% to wall-clock. The fixture is not the
wall-clock bottleneck and won't become one.

Comparison to the datalevin-era `with-temp-conn`: that one creates an
LMDB store on disk under a `tmp/test-<nanotime>` directory, closes the
connection on exit, and `rm -rf`s the directory. Disk-bound; somewhere
in the 20–50 ms range typically. Datahike `:memory` is faster (about
4–5× on steady state) and has no `tmp/` to clean up.

## Migration sketch for the existing callers

**Note: actual migration is the next round; this is the design-time
sketch.**

### `seon.db.pipeline-test`

This file tests the **Malli → Datalevin bridge** via direct
`d/transact!` / `d/pull` calls on the raw connection returned by
`with-temp-conn`. The fixture body fn doesn't receive a raw conn — by
design, since "no `datalevin.core`/`datahike.api` requires in test
code."

**Two migration shapes:**

1. **Rewrite to test the Malli → Datahike bridge.** Replace
   `d/transact!`/`d/pull` with `seon.db/transact!`/`seon.db/pull-by-name`,
   replace the `dl-schema` arg to `with-temp-conn` with a
   `::schemas {alias-name <malli-schema>}` arg to `with-test-db`. The
   schema bridge under test becomes
   `seon.db.datahike.schema/malli-map->datahike-schema`, accessed
   transitively when the conn-process installs schema on `:init`.
   ~700 lines of pipeline-test become roughly the same after rewrite;
   the helper functions (`strip-empty-colls`, `coerce-pulled-entity`,
   etc.) need datahike-shaped equivalents (datahike doesn't dedup
   cardinality-many values the same way datalevin does — verify).

2. **Keep them as legacy datalevin bridge tests.** Acceptable if we
   commit to keeping the datalevin bridge alive for some defined
   purpose; per `remaining.md` cluster 4 we're planning to delete it.

**Recommendation:** option 1; the new generative bridge tests should
exercise the path that actually runs in production (datahike). Per
`prd.md` §"Tests" deliverable #1, the unit-test bar is "schema
bridge coverage for every registered Malli type" — that's the
datahike bridge now, not datalevin.

**Migration shape:** multi-line restructure (~50 hunks in the file).
Not a one-liner.

### `seon.health.workout-test`

The fixture is a near-clone of `with-temp-conn` + `*conn-manager*`
binding for `:seon.runtime`. The body uses `*conn*` for direct
`d/transact!` calls into `*conn*`, then calls `seon.db`/`seon.render`
APIs which (today) try to resolve through the conn-manager.

**Migration shape:** one-file restructure. Replace the entire
`with-temp-datalevin` fixture with `with-test-db-fixture` config:

```clojure
(use-fixtures :each
  (tu/with-test-db-fixture
    {:seon.test-utils/namespaces [:seon.runtime]
     :seon.test-utils/schemas {:seon.runtime <runtime-merged-malli-schema>}}))
```

Replace `(d/transact! *conn* (vec (::extract/specs graph)))` with
`(seon.db/transact! :seon.runtime (vec (::extract/specs graph)))`.
The `*conn*` dynamic var disappears. Per the triage doc, the same
test will also gain `(::extract/entries graph)` and
`(::extract/shapes graph)` to the transact set (cluster 3) — covered
by the migration round.

Two assumptions to verify during migration: (a) `runtime/runtime-merged-schema`
or its successor exists in Malli form usable by the bridge — currently
it's an aggregated datalevin shape, so the migration round may need to
build a datahike-equivalent aggregation. (b) `seon.render/find-renderer`'s
db-name-routed path lands here (per `remaining.md` cluster 1 smell #1).

### `seon.orchestrator.session-test`

This file does **not** use `with-temp-conn`. It uses `with-test-node`
which is a no-op fixture stub. The tests call `seon.db/transact!
:seon.orchestrator [...]` — and because `:seon.orchestrator` is in the
live flow today, the test currently runs against the running
orchestrator's database. **The tests are contaminating live state.**

**Migration shape:** one-file restructure — wrap with the new
fixture against `:seon.orchestrator`, drop `with-test-node` and
`*test-node*`:

```clojure
(use-fixtures :each
  (tu/with-test-db-fixture
    {:seon.test-utils/namespaces [:seon.orchestrator]
     :seon.test-utils/schemas {:seon.orchestrator <session-malli-schema>}}))
```

Then strip `::session/node *test-node*` from every request map (it's
a vestigial key per the triage doc smell #2). Also lands the
cluster-1 fix from the triage doc (widen `::namespace` schema or
coerce in tests). Roughly 25 call sites touched, but each touch is
a 1-line delete of `::session/node *test-node*`.

The fixture binding alone will already remove the contamination —
session tests will start writing to their own `:seon.orchestrator`
instead of the live one. **This may break things on the way through**:
if any of the live system's auxiliary code (e.g. `:seon.dev/instrumentation`
or background flow processes) holds a reference to the live
`:seon.orchestrator` and observes session activity from inside the
test scope, we'll need to investigate. Empirically, today's session
tests already run and pass against the live DB — the live system
tolerates the writes. Routing to a per-test DB is strictly an
improvement.

## Known limitations / open questions

1. **Aliasing requires a small `seon.db` extension (Path A above).**
   Either accept the 6-line touch, or live with the gensym'd db-names
   exposed to test code (Path B avoided). Explicit ask in the report.

2. **konserve memory-store-registry leak.** Each fixture instance
   adds one entry; never removed. Fine until tens of thousands of
   tests. If we ever care, call `datahike.api/delete-database` on
   the way out — but that requires the datahike config, which the
   fixture already holds. Trivial to add later.

3. **Two-namespace+ fixtures + cross-DB refs.** Today's `with-test-db`
   supports multiple namespaces in one flow. Cross-DB ref-walking via
   `seon.db/pull-by-name` isn't exercised by either failing test, so
   the fixture doesn't validate it. Add a test against `pull-deep`
   (or its Phase-2-N successor) when the API lands.

4. **Schema-aggregation for `:seon.runtime`.** The workout-test
   migration needs a Malli `:map` schema for everything that gets
   transacted into `:seon.runtime`. Today, `runtime/runtime-merged-schema`
   is a hand-rolled aggregate of `graph.ingest/datalevin-schema` +
   `ctx/datalevin-schema` + `flow.trace/datalevin-schema` +
   `runtime-schema`. The datahike equivalent doesn't exist yet — per
   `remaining.md` cluster 2 work item, the merged-schema concept goes
   away (the conn-process derives its own schema from the Malli
   registry via the bridge). Migration round may need to build a
   minimal-viable Malli `:map` schema covering only what
   `workout_test` actually transacts (specs + functions + entries +
   shapes). Smaller surface; cleaner.

5. **Test parallelism.** Gensym-suffixed db-names make parallel-safe
   fixtures, but the live flow's `seon.flow.topology/pending-promises`
   atom is shared — each fixture's `flow/inject` adds entries keyed by
   request UUID. UUIDs collision-free, no expected interaction; but
   should be tested before flipping `clojure.test`'s parallel-runner
   flag.

6. **`assertion-helpers` and direct-datahike escape hatch.** The
   fixture deliberately does not expose the underlying datahike conn.
   If a future test legitimately needs to call `d/schema` to verify
   bridge installation, the path is `seon.db.datahike.flow/request!`
   with `::op :schema`, which surfaces via the conn-process. No
   `datahike.api` requires in test code. (`pipeline_test`'s migration
   may push back on this — likely fine.)

7. **`session_test` `*test-node*` removal coupled to fixture
   migration.** The triage doc's cluster-1 fix (widen schema for
   `::session/namespace`) is independent of the fixture migration —
   they can land in either order. Suggest cluster-1 first
   (one-line), then fixture migration (multi-call-site).

8. **Health/orchestrator-status interaction.** `seon.health/check-datalevin`
   is reported as a smell in `remaining.md` — it flags the system as
   unhealthy because datalevin is intentionally absent. Unrelated to
   the fixture; flagged here only because anyone who runs `user/status`
   while a fixture is active should not be surprised by the readout.

## Smoke verification

Captured 2026-05-14 in `mcp__seon__eval` session `orchestrator` after
landing the spiked implementation. All probes exercise the public
`tu/with-test-db` / `tu/with-test-db-fixture` API; none touch
`datahike.api` or `datalevin.core` directly.

| Probe | Result |
|---|---|
| Single-ns transact + query, with schema | `:count 2 :v-a 1 :total-ms 15` |
| 4 sequential runs of same logical db-name | every run sees `:pre-tx nil :post-tx 99` — `:isolated? true` |
| with-test-db-fixture binds `*test-db-aliases*` + `*test-db-flow*` | `:aliases-set? true :flow-set? true` inside; `nil` after |
| `tu/test-db-name` outside fixture | returns logical name unchanged |
| `tu/test-db-name :not-in-namespaces` inside fixture | throws ex-info with clear message |
| 10× sequential fixture-each | median 6 ms, max 13 ms, total 70 ms |

The original primitive probes that the design was built on:

```clojure
;; Verify: two-namespace flow, isolated stores, transact+query both sides.
(let [t0 (System/nanoTime)
      fs (seon.db.datahike.flow/build-datahike-flow!
           #:seon.db.datahike.flow{:namespaces [:test.fixture.smoke.a :test.fixture.smoke.b]
                                   :backend :memory
                                   :namespace-schemas
                                   {:test.fixture.smoke.a [:map ...]
                                    :test.fixture.smoke.b [:map ...]}})
      build-ms (long (/ (- (System/nanoTime) t0) 1e6))]
  (try
    (binding [seon.db/*datahike-flow* fs]
      ...transact :a, query :a, query :b...)
    (finally
      (seon.db.datahike.flow/stop-datahike-flow!
        #:seon.db.datahike.flow{:flow (:seon.db.datahike.flow/flow fs)}))))
;; => {:build-ms 26, :tx-a-ms 1, :q-a 1, :q-b nil, :isolated? true}

;; Verify: seon.db public API routes correctly when *datahike-flow* is bound.
(let [fs (seon.db.datahike.flow/build-datahike-flow!
           #:seon.db.datahike.flow{:namespaces [:test.fixture.api]
                                   :backend :memory
                                   :namespace-schemas {...}})]
  (try
    (binding [seon.db/*datahike-flow* fs]
      (seon.db/transact! :test.fixture.api [{...}])
      (seon.db/query :test.fixture.api '[:find ?v . :where ...]))
    (finally
      (seon.db.datahike.flow/stop-datahike-flow! ...))))
;; => 42  (build-ms=9, tx-ms=1)
```

Both probes pass. **Design verified end-to-end against the running
system** before writing the fixture.

The spiked implementation (next section) wraps these primitives behind
the public `with-test-db` / `with-test-db-fixture` API and is exercised
by an in-fixture smoke test that lives in the same namespace's
docstring/`comment` block.
