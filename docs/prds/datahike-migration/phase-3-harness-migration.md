---
type: prd
status: draft
tags: [prd, database, agent, flow, schema]
---
# PRD: Datahike Migration — Phase 3 (Agent Playground & Harness)

Phase 3 of the datahike migration. Phases 1–2 shipped (`db40ce2`, `9455f3f`): the datahike flow exists, `seon.db` dispatches per db-name, auto-stamp works. Phase 3 ports the REPL/agent harness subsystem onto datahike, gets agent JVMs working as REPL-driven playgrounds, and refactors the touched namespaces to match `docs/conventions.md`.

Related work, kept in scope as North Star:

- [agent-repl-interface/prd.md](../agent-repl-interface/prd.md) — composable renderers, REPL-only development, rendered ctx as LLM context. This phase builds the storage + harness substrate that PRD assumes.
- [datahike-migration/prd.md](./prd.md) and [decisions.md](./decisions.md) — overall migration; Decisions 2 (one DB per namespace), 3 (single writer), 7 (auto-stamp), 9 (state lives in flow).

---

## Scope

Migrate the REPL + agent harness subsystem off Datalevin and onto the datahike flow. The architecture is shaped by one design choice: **the agent JVM is the playground, not a forked database**. Each running agent is a separate JVM with its own atoms, namespace registry, and schema registry. Datahike has one shared session registry and the canonical per-namespace stores. Agents read and write canonical data through the existing cross-JVM flow relay. No per-agent stores, no dynamic conn-process spawn — the JVM isolation is the fork.

Five Seon concerns get datahike DBs:

| db-name | Owner | Purpose |
|---|---|---|
| `:seon.runtime` | `seon.runtime` | Instance + session registry, code graph refs |
| `:seon.session` | `seon.session` (new) | One row per agent: id, namespace, port, status, ctx checkpoint |
| `:seon.repl` | `seon.repl` | Form history (cross-JVM, optional) |
| `:seon.flow` | `seon.flow.trace` | Trace events |
| `:seon.orchestrator` | `seon.orchestrator.session` | Orchestrator's own state |

Four namespaces additionally migrate atoms → flow state because we're touching them anyway: `seon.flow.pool`, `seon.flow.status`, `seon.ns.routes`, `seon.render`.

**Out of scope for Phase 3:** domain namespaces (Phase 4), datalevin deletion (Phase 5), datahike named branches for clone-and-merge (Phase 4), per-agent stores, cross-process security filters.

---

## Goals

1. **Agent launch flow works end-to-end.** `(orchestrator/launch-agent! {::namespace 'seon.apps.demo})` returns a session id; `mcp__seon__eval :session_id "a5ba3e"` evaluates against that JVM's nREPL with `*ctx*` injected; checkpoint persists ctx to `:seon.session`; resume rehydrates a fresh JVM from the checkpoint.
2. **Agent JVM as playground.** Forked from main: own namespace registry, own schemas, own `*ctx*` atom. The agent can `(defn ...)`, `(schema/register! ...)`, redefine flow processes — all in their JVM, no effect on main. Persist promotion happens explicitly.
3. **Cross-namespace work via flows.** Agent's `(seon.code/find-references ...)` routes through the existing cross-JVM relay to main JVM's `seon.code` flow process; result returns. Agent's `(db/transact! :seon.runtime ...)` does the same, hitting main JVM's conn-process — single writer preserved (Decision 3).
4. **Read-share is free.** Whatever the user sees in the canonical DBs, the agent sees too — they're querying the same live state via flow. No replication, no sync.
5. **Refactor touched namespaces** to match conventions: map-in/map-out, `:malli/schema` on public fns, fully namespaced keywords, atoms migrated to flow state where appropriate.

---

## Architecture

### Agent JVM lifecycle

```
1. orchestrator/launch-agent! {::namespace 'seon.apps.demo}
     ↓
2. claim a JVM from seon.flow.pool (existing)
3. write registry row to :seon.session via main JVM's flow:
     {:seon.session/agent       "a5ba3e"          ; runtime/generate-id
      :seon.session/namespace   'seon.apps.demo
      :seon.session/port        7900
      :seon.session/pid         12345
      :seon.session/started-at  #inst "..."
      :seon.session/status      :running}
     ↓
4. inject *ctx* atom + bind nREPL session to namespace
5. return {:session-id "a5ba3e", :nrepl-port 7900}
     ↓
6. agent works freely: (swap! *ctx* ...), (defn ...), (schema/register! ...)
     - mcp__seon__eval :session_id "a5ba3e" routes here
     - cross-namespace calls go via flow relay to main JVM
     - canonical DB reads + writes also via flow relay (single writer)
     ↓
7. (orchestrator/checkpoint! "a5ba3e")
     - serialize @*ctx* (Nippy / pr-str)
     - transact {:seon.session/agent "a5ba3e", :seon.session/ctx <blob>} to :seon.session
     ↓
8. (orchestrator/stop-agent! "a5ba3e")
     - status → :stopped
     - JVM returned to pool (or terminated; configurable)
     - checkpoint preserved for postmortem
     ↓
9. (orchestrator/resume-agent! "a5ba3e")
     - claim fresh JVM, rebind ctx atom from checkpoint blob
     - status → :running, port/pid updated
```

### Schemas

Each owning namespace registers its entities. Conn-processes derive datahike schemas from Malli at `:init` and idempotently install (`install-schema!` already does this in Phase 1). When a namespace registers a new attr mid-session via `schema/register!`, the next `seon.db/transact!` for that DB triggers re-install of any newly-discovered attrs — the diff is the agent's new attrs not yet in the conn-process's installed set. (Phase 1's `install-schema!` already handles this on each call; we extend it to detect new registrations between transacts.)

Concrete:

```clojure
;; src/seon/session.clj — new ns
(schema/register! ::agent       [:string {:seon.db/identity true}])     ; session id
(schema/register! ::namespace   :symbol)
(schema/register! ::port        [:int {:min 1 :max 65535}])
(schema/register! ::pid         :int)
(schema/register! ::started-at  :inst)
(schema/register! ::status      [:enum :starting :running :idle :stopping :stopped :crashed :merged])
(schema/register! ::ctx         :string)   ; Nippy blob, pr-str fallback

(schema/register-entity-schema!
  "seon.session/agent"
  [:map
   [::agent ::agent]
   [::namespace ::namespace]
   [::port {:optional true} ::port]
   [::pid {:optional true} ::pid]
   [::started-at ::started-at]
   [::status ::status]
   [::ctx {:optional true} ::ctx]])

;; src/seon/flow/trace.clj — new schema registration
(schema/register-entity-schema!
  "seon.flow/trace"
  [:map
   [::id :uuid]
   [::at :inst]
   [::type [:enum :request :reply :error :event]]
   [::from-ns :string]
   [::to-ns {:optional true} :string]
   [::fn {:optional true} :string]
   [::duration-ms {:optional true} :int]
   [::status {:optional true} :keyword]])
```

Schemas for `:seon.runtime`, `:seon.repl`, `:seon.orchestrator` are similar; full list lives in the per-namespace files.

### State migration: atoms → flow state

Per the atoms-to-flow policy (touch-time only), four namespaces migrate. Resource handles (channels, schedulers, nREPL server objects) stay process-local — they aren't application state.

| Namespace | Today | Phase 3 |
|---|---|---|
| `seon.flow.pool` | `all-jvms` atom | Registry process holding `{:jvms {port -> entry}}` in flow state. JVM acquisitions/releases also persist to `:seon.session`. |
| `seon.flow.status` | `*prev-counts`, `*errors`, `*error-drains` defonces | Single telemetry process holding all three in state; error drain becomes one input port. |
| `seon.ns.routes` | `namespace-handlers` defonce | Cache process subscribed to tx-bus; cache invalidates on graph updates. |
| `seon.render` | `resolution-cache` defonce | Migrated to whichever conn-process owns render dispatch (or its own cache process). |
| `seon.orchestrator.session` | `session-registry` atom + `agent-pool` ref | Sessions write to `:seon.orchestrator` and `:seon.session`. In-memory registry becomes a read-through cache in flow state. |
| `seon.ctx` | `registry` atom (instance-id → entry with `::atom`/`::scheduler`/`::clients`) | The atom and scheduler are resource handles → stay process-local. Ctx values checkpoint as a blob to `:seon.session/ctx`. |

### Cross-JVM data access

The cross-JVM relay already exists in `topology.clj/start-cross-ns-relay!`. Phase 3 wires `seon.db` to use it from agent JVMs:

- In an agent JVM, `seon.db.datahike-owned?` returns false (no local datahike flow).
- The dispatch falls through to a new branch: route the request through the relay to main JVM, deliver result. From the agent's perspective, it's a regular `seon.db/transact!`/`query`/`pull-by-name` call.
- Single writer preserved: only main JVM's conn-process writes to disk.

Public function calls between namespaces use the same primitive: `(seon.code/find-references {...})` from an agent JVM is intercepted by the harness (existing pattern), routed to main JVM's `seon.code` flow process (which has its DB conn injected), result back over the relay.

### Public-fn DI: db conn injection

The harness already routes incoming requests to a namespace's public fns. Phase 3 adds dependency injection at the harness boundary:

```clojure
;; In seon.code:
(defn find-references
  {:malli/schema [:=> [:cat ::find-request] ::find-response]
   :seon.harness/needs [:seon.runtime]}
  [{::keys [query] :seon.runtime/keys [db]}]
  (d/q '[:find ... :where ...] db ...))
```

The harness reads `:seon.harness/needs`, pre-resolves each db-name via the running flow's conn-process, merges into the request map under reserved keys (`:seon.runtime/db`, etc.) before invoking the fn. The fn's signature shows the dependency directly. Test code can pass an explicit `:seon.runtime/db` value to bypass.

### Rendered dynamic context

Out of Phase 3's strict scope, but flagged here as the next thing this enables:

- `seon.render` already discovers functions via `:seon.render/html` in output schema. We add `:seon.render/ai` as a peer.
- A new primitive `(ctx/render-for {::session-id "a5ba3e" ::format :ai})` pulls the session row's ctx blob, walks its top-level keys, finds the most-specific `:seon.render/ai` renderer for each, composes XML output (per `agent-repl-interface/prd.md` "Example Output").
- Phase 3 ships the storage + harness substrate. The renderer functions for REPL/agent shapes (`schemas-ai`, `functions-ai`, `history-ai`) are written as part of the demo to validate the model. Broader render coverage is Phase 4 work.

---

## Demo Target (must pass before Phase 3 closes)

End-to-end script that runs in the live system:

```clojure
;; 1. Launch
(def s (orchestrator/launch-agent! {::namespace 'seon.apps.demo}))
;; => {:session-id "a5ba3e", :nrepl-port 7900}

;; 2. Eval through mcp__seon__eval :session_id "a5ba3e"
;;    Inside the agent JVM:
(swap! *ctx* assoc :seon.apps.demo/scratch 42)
(defn double-scratch [] (* 2 (:seon.apps.demo/scratch @*ctx*)))
(double-scratch) ;; => 84

;; 3. Cross-namespace call from agent JVM
(seon.code/find-references {::query "double-scratch"})
;; => routes via flow relay to main JVM, returns data

;; 4. Checkpoint
(orchestrator/checkpoint! "a5ba3e")
;; => writes ctx blob to :seon.session

;; 5. From orchestrator JVM, see the session
(db/pull-by-name :seon.session '[*] [:seon.session/agent "a5ba3e"])
;; => {:seon.session/agent "a5ba3e",
;;     :seon.session/namespace 'seon.apps.demo,
;;     :seon.session/status :running,
;;     :seon.session/ctx "<nippy blob>"}

;; 6. Stop
(orchestrator/stop-agent! "a5ba3e")
;; => :status :stopped, JVM released

;; 7. Resume from checkpoint
(orchestrator/resume-agent! "a5ba3e")
;; => claims fresh JVM, restores @*ctx* from blob
@*ctx* ;; => {:seon.apps.demo/scratch 42}
```

If this runs end-to-end against a fresh JVM, Phase 3 is done.

---

## Constraints

- **No call site breaks.** `seon.db/transact!`/`query`/`pull-by-name` public signatures stay. `seon.flow.pool`, `seon.orchestrator.session` internal implementation can change; their external callers don't.
- **Single writer per store** (Decision 3). Enforced by Phase 1's flow-build guard. Agent JVMs never open local conns.
- **Tests use real datahike.** `:memory` for unit tests, tmp `:file` dirs for integration tests. No mocking.
- **Datalevin stays untouched.** Domain namespaces still configured against datalevin in `system.edn`; they don't boot in dev (Datalevin is dead) but their config sits unchanged. Phase 4 cleans up.
- **No new defonce atoms** in touched namespaces. Existing ones either migrate or justify as resource handles.
- **Auto-stamp `:seon.db/namespace`** stays on for all datahike-routed transacts (Decision 7, Phase 2 work).

---

## Success Criteria

1. **Demo target passes** end-to-end against a fresh JVM. Steps 1–7 above all green.
2. **Lifecycle matrix passes for all five new DBs:** fresh-bootstrap, write, `(user/reset)`, read-back intact; crash recovery via SIGTERM mid-tx; double-writer guard fails fast.
3. **Refactor gates met** in touched namespaces: map-in/map-out, `:malli/schema` on public fns, namespaced keys, no new app atoms.
4. **No regressions:** baseline before Phase 3 is 4030 pass / 1 fail / 2 errors. Post-Phase 3: same or better. New tests add to passing total.
5. **Cross-JVM read-share works:** an agent JVM querying `:seon.code` sees identical results to the orchestrator JVM querying the same db-name.
6. **Cross-JVM write works:** an agent's `(db/transact! :seon.runtime ...)` lands in the canonical store via the main JVM's conn-process. Verified by query from orchestrator after.
7. **Schema-on-the-fly works:** agent `(schema/register! ::new-attr ...)` followed by `(db/transact! :seon.session [{::new-attr ...}])` succeeds without manual schema-install. The conn-process detects the new attr and installs idempotently.
8. **`(user/status)` reflects flow-state migration:** pool details, status counters, orchestrator sessions all readable via flow state inspection — no deref of private atoms.

---

## Deliverables

### Code

- [ ] Schema registrations + `register-entity-schema!` calls for the five DBs (in their owning namespaces)
- [ ] `resources/system.edn` — `:seon.db/flow` namespaces list expanded to `[:seon.runtime :seon.session :seon.repl :seon.flow :seon.orchestrator :seon.phase2.demo]`
- [ ] `src/seon/session.clj` — new ns owning `:seon.session` schemas + agent registry helpers
- [ ] `src/seon/orchestrator/launch.clj` (or extend `seon.orchestrator.session`) — `launch-agent!`, `checkpoint!`, `stop-agent!`, `resume-agent!` public API
- [ ] `seon.flow.pool` — registry migrated from atom to flow state
- [ ] `seon.flow.status` — atoms migrated to flow state
- [ ] `seon.flow.trace` — entity schema registered, writes via `seon.db/transact!`
- [ ] `seon.ns.routes` — cache migrated to flow state
- [ ] `seon.render` — resolution-cache migrated
- [ ] `seon.orchestrator.session` — registry migrated; writes via `seon.db/transact!`
- [ ] `seon.ctx` — debounced checkpoint to `:seon.session/ctx`; ctx atom and scheduler stay process-local
- [ ] `seon.db` — agent-JVM dispatch branch: when no local datahike flow, route ops through the cross-JVM flow relay
- [ ] `seon.flow.harness` — DI for `:seon.harness/needs` declared on public fns; pre-resolve and inject db values
- [ ] Update `seon.db.datahike.conn-process/install-schema!` to (a) detect new attrs registered between transacts and install them, (b) compare `:db/unique` drift (Phase 2 deferred), (c) thread `db-name` into the log line (Phase 2 cosmetic)

### Tests

- [ ] `test/seon/orchestrator/launch_test.clj` — full demo target script as integration test
- [ ] Per-DB lifecycle tests for the five new DBs
- [ ] Schema-on-the-fly: register a new attr mid-session, transact, query — succeeds without manual install
- [ ] Cross-JVM transact integration test: agent JVM transacts to `:seon.runtime`, orchestrator JVM queries, matches
- [ ] Convention check: a meta-test that greps the touched namespaces for missing `:malli/schema` on public fns, non-namespaced keys in registered schemas, and new defonce atoms

### Documentation

- [ ] `docs/seon/components/session.md` — new
- [ ] `docs/seon/components/agent-launch.md` — new; the demo flow as canonical reference
- [ ] `docs/seon/concepts/jvm-as-playground.md` — explains the fork-via-JVM model
- [ ] `docs/conventions.md` — add a "Cross-JVM data access" section
- [ ] ADR pointing at this PRD

---

## Phasing

Single feature branch (`feature/datahike-migration`). One commit per logical step:

1. Schemas + `system.edn` wiring (no behavior change)
2. `seon.flow.trace` migrated
3. `seon.session` ns + `:seon.session` registry plumbing
4. `seon.orchestrator.session` migrated
5. `seon.flow.pool` atoms → flow state
6. `seon.flow.status` atoms → flow state
7. `seon.ns.routes` + `seon.render` atoms → flow state
8. `seon.ctx` checkpoint path
9. `seon.db` agent-JVM dispatch branch
10. `seon.flow.harness` DI for `:seon.harness/needs`
11. `orchestrator/launch.clj` API + integration test (demo target)
12. Convention meta-test + docs

Each step has its own test gate. Verifier runs after each substantive commit.

---

## Open Questions

- **Ctx storage shape.** Phase 3 stores ctx as a single Nippy blob on the session row. Alternative: per-key, one row per (session, key) pair, queryable via datalog. Per-key is more powerful but the agent's ctx isn't typically queried from outside the agent JVM in interesting ways. Default: blob; revisit if usage demands queryable history.
- **Should `seon.repl` ship in Phase 3 or wait?** It's "form history if we want it" — useful but not blocking the demo. Default: defer to Phase 4 unless the demo target needs it.
- **Where does `launch-agent!` live?** Currently `seon.orchestrator.session` does most of this; `seon.orchestrator.launch` would be cleaner but adds a namespace. Default: extend the existing one, extract later if it bloats.
- **Branches for clone-and-merge** are explicitly Phase 4. Confirmed by the user: "named branches right we have git." We get it for free at the FS level for Phase 3 (file backend + git), so the workflow "checkout an old data sha, restart, see past state" already works without datahike branches.

---

## Out of Scope (Phase 4+)

- Domain namespace migration (`:seon.health.*`, `:seon.trading.*`, `:seon.ai.*`)
- Datalevin deletion
- Datahike named branches and the `:seon.db/branch` request key
- Per-agent stores (FS clones)
- Cross-process security filters (`d/filter` grants)
- Token-aware truncation in AI render output
- Full coverage of `:seon.render/ai` renderers across the codebase
- Renaming `:seon.runtime/agent-id` to a more general identity-attr scheme
