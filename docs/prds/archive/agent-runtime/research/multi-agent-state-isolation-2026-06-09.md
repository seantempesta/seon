---
type: research
status: active
tags: [research, agent, database]
---

# Multi-agent state isolation — patterns, pod inventory, atom census (2026-06-09)

## TL;DR

- **The canonical answer to "N program instantiations, one runtime/store" in the Clojure world is exactly what 2.2b approved**: a single serialized writer + immutable db values as the read substrate (Datomic peer model). Datahike itself implements this in-process — every `transact!` is queued through a per-conn channel and applied by ONE loop; reads never block writes because they run against immutable snapshots. The 2.2b replica design is the same shape over a wire. Missing pieces vs the canonical pattern: **read-your-own-writes (basis-t handoff)**, gapless tx-feed ordering with reconnect catch-up, and server-side tempid resolution — detailed in Q1.
- **The pod's per-agent identity architecture is fundamentally sound** (ALS for fiber-local identity, per-batch `volatile!` for ns tracking, eval-id-keyed stash, per-agent listener keys). The real hazards are interleaving (check-then-act across `await`), not races. **Top 3 collision risks found**: (1) detect-and-tee snapshot-diff misattribution across awaits — agent B's defs/schemas can land in agent A's `:seon.fn`/`:seon.schema` tee rows; (2) the kick listener's read-state-then-schedule window can double-start a loop for one agent; (3) `seon.eval/!next-budget-ms` is a process-global one-shot side channel that a concurrently interleaved agent can consume or clobber.
- **Atom census: the CLJS side is already lean.** ~20 process-level holders; most are in the genuinely-stateful-allowed category (conn, compile-state, ALS instances, server/SSE handles, timers). The kill-list is short and specific: `!next-budget-ms` (move to ALS/tx-context), the `::state` duplicate in `seon.agents` atoms (derive from DB), the dead per-agent stash-prefix in `seon.agents`, the `seon.repl/!conn` second-conn atom (fold into one conn owner), and the two global timeout knobs (fold into one config map or `*ctx*`).
- **Where per-agent state should live** (one paragraph, see end of Q2): identity in ALS, persistent state in the DB keyed by agent ref, live values in the eval-id-keyed globalThis stash, and as close to ZERO per-agent runtime atoms as possible — the `seon.agents` `*ctx*`-atom layer should stay a thin escape hatch, not grow.
- **Flag for the pivot plan**: unit 2.3 as written ("per-agent db-name via the multi-DB registry") contradicts the shared-substrate-visibility principle (cross-agent section fns). Needs an explicit decision: shared DB with agent refs (current model, preserves visibility) vs db-per-agent (kills it). See Q1 §multi-tenant.

---

## Q1 — How the Clojure world handles "multiple instantiations, one runtime/store"

### 1.1 The Datomic/datahike peer model — and how datahike-cljs actually serializes writes

The pattern: **one logical writer, immutable database values for all reads**. Readers never coordinate with each other or with the writer; they hold a point-in-time value. Writers funnel through a single serialization point.

What datahike (the fork the pod runs, `seantempesta/datahike@01ba3f18`) actually does — read from source, not guessed:

- A conn is a `deftype Connection [wrapped-atom]` (`connector.cljc:25`), created as `(Connection. (atom db :meta {:listeners (atom {})}))` (`connector.cljc:84`). **The conn IS an atom**; `@conn` is a cheap deref to an immutable db value.
- `datahike.writer/transact!` (`writer.cljc:226`) does NOT swap the atom directly. It looks up the conn's writer and calls `-dispatch!`, which `put!`s `{:op 'transact! :args [...] :callback promise-chan}` onto a per-conn core.async **`transaction-queue`** (`writer.cljc:22-25`).
- `create-thread` (`writer.cljc:38+`) runs ONE `go-try` processing loop per conn: takes invocations off the queue one at a time, applies the write fn against the current db value, and pushes results to a `commit-queue`. A second loop batches commits, calls `w/commit!`, then `(reset! connection commit-db)` and delivers each callback.
- Listeners are stored in `(:listeners (meta conn))` — an atom of key→callback (`core.cljc:206-224`, same-key-replaces, distinct-keys-coexist). They are fired in `writer/transact!`'s go block AFTER the tx-report resolves (`writer.cljc:247`), per-tx, in dispatch order, with the post-commit report.

**Consequence for the pod**: two agents calling `seon.db/transact!` concurrently are serialized by the channel queue — there are no torn writes, no lost swaps, no need for any seon-side write lock. A listener that itself transacts simply enqueues; no reentrancy deadlock (queue-based, not lock-based). Note: core.async IS in the pod's datahike write path (the "no core.async in guest paths" rule applies to the wasm guest track, not the V0 pod).

### 1.2 Assessing 2.2b against the canonical peer pattern

The approved 2.2b shape (pivot plan, "Board status" + sign-off: writes/control over async UDS rpc; reads sync against a local replica materialized via `datahike.db/init-db` from wire tx events; `listen!` rides the tx feed) **is the Datomic peer model**. Things the canonical pattern has that the current scope does not name:

1. **Basis-t handoff / read-your-own-writes.** In Datomic, `transact` returns `db-after`, and `(d/sync conn t)` lets a peer wait until its local view reaches a basis. In 2.2b, if `transact!` resolves from the rpc ACK while the replica updates from the *separately-delivered* tx-feed event, a sync read immediately after an awaited `transact!` can see a STALE replica. This breaks straight-line agent code (`transact!` then `query` is the canonical agent flow, e.g. `seon.agent/start-session!` transacts then immediately `db/entity`s the new session — agent.cljs:647-658). Fix options: (a) the rpc response carries the tx's datoms + tx-id and the client applies them to the replica BEFORE resolving the `transact!` promise (dedupe when the feed event arrives); or (b) the envelope carries basis-t and reads await `(sync-to t)`. Option (a) matches `:streaming?` writer semantics datahike already models (`writer.cljc:17`).
2. **Gapless, monotonic tx ordering + reconnect catch-up.** Listener inputs promise consecutive `db-before`/`db-after` pairs (`seon.db/build-handler-input`, db.cljs:1666-1677). The replica must apply feed events strictly in tx-id order and, on reconnect, snapshot-then-replay from the last seen tx-id — a gap silently corrupts every "change-detection" handler. The planned snapshot wire op should take a `since-tx` arg, not only full-snapshot.
3. **Tempid/eid resolution is central.** Eids must be assigned by the JVM writer; the rpc response must return `:tempids` so nested-map upserts and `[:db/id ...]` follow-ups keep working. (`datahike.db/init-db` taking raw datoms with explicit e/tx — already noted in the plan — covers the replica side.)
4. **Memory model.** Datomic peers lazily fetch segments + LRU-cache; the 2.2b replica is a FULL in-memory copy. Fine at current datom counts; name the pivot trigger (replica > some datom count → move heavy reads server-side).
5. **Listener execution locus.** Today listeners fire inside datahike's writer go-block with back-pressure for sync handlers (transact blocks until sync handlers return — documented in `listen!`, db.cljs:1696-1699). On the wire, that back-pressure disappears: feed-driven listeners are inherently fire-and-forget relative to the writer. Audit any handler relying on sync back-pressure semantics (none found in `src/seon/*.cljs` today — the kick handler schedules via `setTimeout 0` anyway, agent.cljs:489-491).

### 1.3 nREPL session isolation — the closest prior art

nREPL is the longest-lived "N interactive actors, one runtime" design in the ecosystem. How it avoids collisions:

- **A session is a binding frame, stored as data.** Each session id maps to a persistent map of dynamic-var bindings (`*ns*`, `*1` `*2` `*3`, `*e`, `*out*`/`*err*`, plus user dynvars). Around every eval, the middleware re-establishes the session's frame (`with-bindings`), runs the form, then saves the (possibly mutated) frame back. State per actor = a map; the runtime primitive (thread-local Var frames) is only borrowed for the dynamic extent of one message.
- **Per-session serial execution.** Each session has its own queue/executor; two evals in one session never interleave. Cross-session evals run concurrently but collide only on shared globals (the same hazard class seon has).
- **The equivalent of seon's home-ns is the session's `*ns*` entry in the frame** — not a property of the runtime, just a value re-bound at each entry.

Seon already mirrors both halves, with the Node-appropriate primitive: `seon.db/with-agent` + ALS re-establishes the "frame" at every entry (boot, kick, turn — client.cljs:1114, agent.cljs:490, agent.cljs:795), and the per-agent loop is serial by construction (one `run-agentic-loop!` per agent, kick guard). The lesson seon should keep from nREPL: **per-actor mutable state lives in a data map re-attached at entry, not in process globals** — every process-global knob (`!timeout-ms`, `!next-budget-ms`) is a departure from this and shows up in the Q3 kill-list. The gap vs nREPL: evals entering from OUTSIDE the agent loop (MCP host eval, web handlers calling into eval) are not serialized per-agent.

### 1.4 Dynamic vars: CLJ conveyance vs what CLJS can actually do

- **CLJ**: `binding` pushes a thread-local frame; `future`/`agent`/`pmap` convey the frame via `binding-conveyor-fn`; `bound-fn` captures it explicitly. Bindings are genuinely per-thread — N actors on N threads do not collide.
- **CLJS**: there are no frames. `binding` macroexpands to `set!` of the var's ONE global root + `try/finally` restore. This is correct only for a fully synchronous dynamic extent. Where it breaks, precisely (Probe 13, `research/impl-finding-tx-context-promise-2026-05-22.md`, restated in db.cljs:498-512):
  - **Leak**: code in another fiber that runs while the binder is parked at an `await` sees the binder's value.
  - **Clobber/unwind**: with overlapping binders, the first binder's `finally` restores the pre-value over the second binder's live value; equivalently, a continuation resumed after another `binding` exited reads the wrong (already-restored) value. The live instance of this bug: test fixtures' `binding` of `*conn*` had unwound by the time `record-eval!`'s post-await retry ran — fixed by capturing the conn at sync entry (`(let [conn db/*conn*] …)`, eval.cljs:975, docstring 968-973).
- **Reliable alternatives, in preference order as used in the pod**:
  1. **ALS** (`AsyncLocalStorage`) — V8-level fiber-local; survives every await/microtask/timer-with-`.run`; concurrent `.run` scopes don't interfere (Probe 14). Used for tx-context (db.cljs:520), agent-id (db.cljs:555), warning buckets (eval.cljs:208), `*ctx*` reattachment (agents.cljs:177). Caveat: a bare `setTimeout` callback created OUTSIDE `.run` breaks the chain — which is why the kick handler re-enters `with-agent` inside its `setTimeout` (agent.cljs:486-491).
  2. **Explicit parameter threading** — `eval-batch!` passes `:ns` per `eval-str` call and folds `current-ns` in a batch-local `volatile!` (eval.cljs:1138, 1184-1199). No global "current ns" exists. This is the strongest pattern: no ambient state at all.
  3. **Closure capture at sync entry** — the `record-eval!` conn fix. Right whenever a value is stable for the whole operation.
  4. CLJS `binding` — acceptable ONLY for a synchronous extent (e.g. `run-as-agent` binds `*ctx*` then immediately asserts, agents.cljs:237-240, with ALS doing the across-await work).

### 1.5 Multi-tenant DB patterns — and a conflict inside the pivot plan

- **One-db-with-tenant-attr (current)**: every message/turn/session carries an agent ref (`:seon.message/agent`, tx-meta `:seon.db/agent-id` auto-stamped by `merge-tx-context-into-opts`, db.cljs:961-984). Cross-agent visibility is free: a section fn that doesn't filter by agent sees the whole substrate (the reactive-context principle). Cost: no hard isolation; a buggy/hostile agent can read or clobber another agent's rows (acceptable in the current trust model — the eval layer is explicitly not a security boundary).
- **Db-per-tenant**: the wire layer already has a multi-DB registry (agent-id→db-name), and pivot-plan unit 2.3 says "per-agent db-name via the existing multi-DB registry". **This contradicts the shared-substrate goal**: with one DB per agent, the cross-agent section fns, the shared program graph (`:seon.fn`/`:seon.schema` publish gate), and "agent A's failed eval shows up in agent B's render" all stop working unless a cross-db query layer is added (datahike has no cross-db joins on the CLJS read path). Recommendation: keep ONE shared substrate DB with agent refs as the default; reserve db-per-agent for genuinely partitioned workloads (separate users/clusters, per the clusters-platform track), and amend 2.3's wording to say which it means.
- Hybrid worth noting: Datomic shops commonly do one-db + tenant attr + **query-level scoping helpers** (every agent-facing read goes through a fn that injects the agent filter unless explicitly widened). Seon's render sections already do this socially (filter-by-agent is a choice per section); making the scoping explicit in section-fn signatures is enough — no mechanism needed.

### 1.6 The single-threaded event loop: real vs imagined hazards

The pod is Node: ONE thread, no data races, no torn reads, no need for locks/CAS/STM. The actual hazard class is **interleaving across await points**:

- **Check-then-act across await (async TOCTOU)** — read a value, await, act on the stale read. Instances found: kick listener state check (Q2 #4), tee snapshot diff (Q2 #1), `ensure-datahike-attrs!` schema-presence check then awaited install (benign: double-install of the same attr is idempotent, db.cljs:1453-1486).
- **One-shot global side channels** — `!next-budget-ms` (Q2 #3).
- **Listener reentrancy/ordering** — a listener that transacts enqueues a new tx whose listeners fire later; safe with datahike's queue, but handler authors must not assume their tx is committed before the listener returns (it isn't — `transact!` from inside a listener resolves later).
- **Shared registry last-write-wins** — `seon.schema/*schemas` is one global registry; two agents registering the same key with different shapes silently last-write-wins, while the datahike-installed schema keeps the FIRST shape (`ensure-datahike-attrs!` skips already-installed attrs) → registry/DB drift. The 1.2-reuse `check-parallel-attr` warn covers the sibling-attr case but not the same-key-redefinition case.
- **Mid-eval analyzer interleaving** — see Q2 #2; bounded because `cljs.js/eval-str` is synchronous per form unless a load goes async.

Imagined hazards to NOT spend effort on: locks, atomicity of `swap!` (always atomic — single thread), concurrent datahike write corruption (serialized by the writer queue, §1.1).

---

## Q2 — Per-agent runtime state inventory: where it lives, is it collision-safe?

Legend: PA = per-agent, PG = process-global, FL = fn-local.

| State holder | Owner | Scope | Collision vector across interleaved agent loops | Verdict |
|---|---|---|---|---|
| `agent-id-als` ALS instance | seon.db:555 | PG instance, PA values | None — `.run` scopes are fiber-isolated (Probe 14). Hazard only at scope-escapes (`setTimeout` outside `.run`), all known sites re-enter (`agent.cljs:490`) | SAFE — the keystone |
| `als-instance` (tx-context) | seon.db:520 | PG instance, per-fiber values | None; nested scopes merge by design (db.cljs:610-613) | SAFE |
| `*conn*` dynamic var | seon.db:478 | PG root, `set!` at boot (client.cljs:1093) | One conn today ⇒ benign. `binding` of it (tests) unwinds across await — the record-eval! bug class. Becomes load-bearing if multi-conn ever lands | SAFE-NOW; rule: capture at sync entry (eval.cljs:975) |
| `!agent-conn` | seon.client:248 | PG (one conn, all agents) | None — shared by design (sessions are entities, not partitions) | SAFE; replaced by remote-conn atom in 2.2b |
| Shared bootstrap compile-state `seon.repl/!compile-state` | repl.cljs:76, init eval.cljs:234 | PG — ONE cljs.js state for ALL agents | (a) **NS-switching is NOT a hazard**: there is no global current-ns; each `eval-str` call passes `:ns` explicitly and each batch folds `current-ns` in a batch-local `volatile!` (eval.cljs:1138,1184). Agent A yielding mid-batch cannot change agent B's ns or vice versa. (b) `eval-str` itself is synchronous per form (compile+eval on the main thread), so analyzer mutation doesn't interleave mid-form — EXCEPT when a form triggers an async `boot/load` require; a second agent's form starting during that window runs against partially-loaded analyzer state. (c) Cross-agent def VISIBILITY is by design (shared substrate) | MOSTLY SAFE; the sharp edge is (b) — low probability, and the real hazard it feeds is the tee diff (next row) |
| Detect-and-tee snapshots `defs-before`/`schemas-before` | eval.cljs:1181-1216 | FL per form, but diffed against PG state | **SHARPEST REAL HAZARD.** Snapshot → `(await eval)` → `(await maybe-await-value)` → diff (`build-tee-entities`). If agent B defines fns/schemas during A's awaits, B's defs appear in A's diff: wrong `:seon.fn`/`:seon.schema` rows attributed to A's eval-id/source, wrong auto-instrument targets (eval.cljs:1237), spurious auto-test runs (eval.cljs:1252). Same window for `schema/current-keys` | **FIX NEEDED** before real concurrent agents: filter `defs-since` to the form's own ending ns + agent home-ns lineage, or serialize eval-batches across agents with a simple async mutex, or diff immediately after the SYNC portion of eval |
| Per-agent home ns (`seon.agent.<id>`) | eval.cljs `setup-agent-ns!`:532 | PA (analyzer + globalThis JS namespace objects) | None — ns name embeds the agent id; `result` accessor reads globally-unique eval-id keys | SAFE |
| `(result id)` globalThis stash `__seon_results_<eval-id>` | eval.cljs:515-530, stash at 1205 | PG map, PA-disjoint keys (eval-ids are globally unique) | No collisions. Two smells: (1) **unbounded growth** — nothing ever deletes stash keys; long-lived pod leaks every eval's live value; (2) **prefix drift** — `seon.agents/default-stash-prefix` is `"__seon_results_<agent-id>_"` (agents.cljs:257) but eval.cljs writes `"__seon_results_<eval-id>"` with NO agent segment; the agents.cljs prefix is dead/wrong | SAFE for collisions; fix drift + add eviction (e.g. cap per agent, evict on turn close) |
| Listener registry | datahike `(:listeners (meta conn))`, wrapped by db.cljs:1679-1751 | PG atom, keyed | Keys are explicit: kick = `[::user-message-trigger id]` per agent (agent.cljs:504), inspector = `::inspector` singleton (inspector.cljs:470). Same-key-replaces is idempotent. Hazard is only forgetting the agent-id in a key — none found | SAFE |
| Kick listener check-then-act | agent.cljs:471-491 | — | Handler reads `:seon.agent/state`, then `setTimeout 0` schedules the loop; `:running` is only flipped later inside `with-turn!`'s open-tx (agent.cljs:714). Two user-message txs in quick succession → both listener firings can read non-`:running` before either loop's open-tx lands → **two concurrent loops for ONE agent** (duplicate turns, interleaved sessions) | **FIX NEEDED** (cheap): per-agent in-memory "loop-scheduled" latch checked/set synchronously in the handler, cleared when the loop exits — this is a legitimate runtime-artifact use, or do the state flip in the SAME tx pattern via a transaction-function-style guard |
| Turn/session open-state | DB (`:seon.agent/state`, `:seon.turn/status`, `:seon.session`) | PA entities | `ensure-session!` is check-then-act (agent.cljs:660-665) but only ever called inside the agent's own serial loop ⇒ safe. `with-turn!` flips state inside txs (serialized) | SAFE given one loop per agent (i.e., contingent on fixing the kick race) |
| Warning buckets | eval.cljs:208 ALS + per-eval atom at 412 | per-fiber | None — this is the model fix for exactly this hazard class (replaced a global `set!`) | SAFE — exemplary |
| `ana/*cljs-warning-handlers*` root | set once per init-version, eval.cljs:218-232 | PG | Single dispatcher reads per-fiber bucket; install is version-guarded | SAFE |
| `!timeout-ms` / `!next-budget-ms` | eval.cljs:69/79 | PG | `!timeout-ms`: a shared knob; one agent's `set-timeout-ms!` changes all agents — policy smell, not corruption. `!next-budget-ms`: **one-shot global side channel** — A's form sets it, A awaits, B's interleaved form's `maybe-await-value` consumes (or resets) it (eval.cljs:599-616) → A's slow op times out at default, or B gets A's 60s budget | **FIX NEEDED**: move the budget hint into the eval's tx-context ALS scope (it already exists per form) or `*ctx*` |
| `seon.agents/*ctx*` + `!instances` + per-agent atoms | agents.cljs:110/133/285 | PA atoms, PG registry | Identity assert makes wrong-binding loud (agents.cljs:182-208). Registry mutations are atomic. Collision-safe. BUT `::state` in the atom duplicates the DB's `:seon.agent/state` — two sources of truth | SAFE; derive `::state` from DB (Q3) |
| `seon.schema/*schemas` registry | schema.cljc:38 | PG | Cross-agent last-write-wins on same-key re-registration; drifts from the installed datahike schema (first-wins). No corruption, but silent semantic conflict | Add a registration-conflict warn (extend `check-parallel-attr` family); registry itself stays (it IS the runtime artifact Malli validates against) |
| Web/SSE: `!server`, `!sse-connections`, `!sse-by-agent`, `!pending` | serve.cljs:57/63, inspector.cljs:42/428 | PG handles, agent-keyed maps | Atomic swaps; `!pending` is per-agent coalescing timers — correct | SAFE — allowed runtime artifacts |
| Wire layer `!writer`/`!reader`/`!agent-id` | dev/wire_node.cljs:42-51, dev/node_agent.cljs:23 | PG | `!agent-id` defaults to `"wire"` and is a PROCESS-global identity in the transport — under multi-agent on the wire (2.3), per-op identity must come from `(db/current-agent-id)`/the op payload, not this atom | Flag for 2.2/2.3: per-op agent identity, not a transport-global atom |

### Where should per-agent state live? (the one-paragraph answer)

Per-agent state should live in exactly four places, in this priority order: (1) **identity and causality in ALS** (`with-agent` / `with-tx-context`) — never in a dynamic var root, never in a process atom; (2) **everything persistent in the shared DB**, keyed by the agent ref and auto-stamped via tx-meta — state like `:seon.agent/state`, sessions, turns, the program graph; derived counters (turn-index, turns-since-user) stay derived; (3) **live, non-serializable values in the globalThis stash keyed by globally-unique eval-id** (with eviction added); (4) **a minimal `*ctx*` atom only for genuine runtime artifacts that are per-agent and non-derivable** (a loop-scheduled latch, a per-agent budget override) — and that layer should be kept deliberately small, because every key added to it is a second source of truth waiting to drift. Process-global atoms are reserved for process artifacts (conn, compile-state, server handle, ALS instances, SSE registries); any process-global that an AGENT can write from inside eval (`!timeout-ms`, `!next-budget-ms`) is in the wrong place by definition under multi-agent.

---

## Q3 — Atom census (every atom / defonce / volatile / dynamic var, CLJS side)

Columns: D? = derivable from DB (reactive-context violation if yes), A? = allowed genuinely-stateful runtime artifact.

| Loc | Name | Holds | Scope | D? | A? | Collision risk | Recommendation |
|---|---|---|---|---|---|---|---|
| db.cljs:478 | `*conn*` (dyn) | datahike conn | PG root | no | yes (conn) | binding-unwind across await | KEEP; never `binding` it in async paths; capture at sync entry |
| db.cljs:520 | `als-instance` | tx-context ALS | PG | no | yes (ALS) | none | KEEP |
| db.cljs:555 | `agent-id-als` | agent-id ALS | PG | no | yes (ALS) | none | KEEP |
| client.cljs:122 | `!state` | boot-at, reload-count, heartbeat timer id | PG | no (process metadata) | yes (timer handle) | none | KEEP (small); reload-count is cosmetic |
| client.cljs:248 | `!agent-conn` | the agent conn | PG | no | yes (conn) | none | KEEP; becomes the 2.2b remote-conn atom; consider folding `seon.repl/!conn` into it |
| repl.cljs:76 | `!compile-state` | cljs.js compile-state | PG | no | yes (compile-state) | tee-diff window (Q2 #1) | KEEP; fix the diff attribution, not the atom |
| repl.cljs:83 | `!init-version` | reload stamp | PG | no | yes (cache-invalidation pair) | none | KEEP |
| repl.cljs:85 | `!conn` | SECOND dev conn (`:memory`, dev-init!) | PG | no | yes | none, but two conn owners in the codebase (`!conn` vs `!agent-conn`) is drift surface | **FOLD-INTO-ONE**: make `dev-init!` reuse `!agent-conn` when the pod is booted, else open + register through one owner |
| eval.cljs:69 | `!timeout-ms` | per-form timeout default | PG | no | borderline (policy knob) | one agent's `set-timeout-ms!` affects all agents | **FOLD**: move default into one substrate config map; per-agent override via `*ctx*` |
| eval.cljs:79 | `!next-budget-ms` | ONE-SHOT budget override | PG | no | no | **YES** — cross-agent consume/clobber across await (Q2) | **KILL** as a global: carry the hint in the per-form tx-context ALS scope (already opened per entry, eval.cljs:1151) or `*ctx*` |
| eval.cljs:108 | `timeout-sentinel` | identity marker | PG const | — | yes | none | KEEP |
| eval.cljs:179 | `init-version` (def) | reload gensym | PG | no | yes | none | KEEP |
| eval.cljs:208 | `warnings-als` | per-fiber warn bucket ALS | PG | no | yes | none | KEEP — model pattern |
| eval.cljs:216 | `!warning-dispatcher-version` | install guard | PG | no | yes | none | KEEP |
| eval.cljs:412 | `warnings` (atom) | per-eval bucket | FL via ALS | no | yes | none | KEEP |
| eval.cljs:1135-38 | `eids`/`n-ok`/`n-fail`/`current-ns` (volatile!) | batch fold | FL | — | — | none | KEEP — fn-local fold is the right tool |
| eval stash | `globalThis.__seon_results_<eval-id>` | live eval values | PG map | no (deliberately — values aren't serializable) | yes (three-tier rule) | none on keys; **unbounded growth**; prefix drift vs agents.cljs:257 | KEEP mechanism; ADD eviction; DELETE the dead `:seon.eval/results-stash-prefix` key in seon.agents |
| agents.cljs:110 | `!instances` | agent-id→atom registry | PG | partially (ids ARE in DB; atoms aren't) | yes (holds live atoms) | none (atomic swaps; start collision throws) | KEEP, but it shrinks as the `*ctx*` map shrinks |
| agents.cljs:133 | `*ctx*` (dyn) | per-agent atom | PG root, PA value | — | yes | none (ALS + identity assert) | KEEP |
| agents.cljs:177 | `substrate-ctx-als` | ALS | PG | no | yes | none | KEEP |
| agents.cljs:285 | per-agent atom (`seed-atom-value`) | id, booted-at, **state**, stash-prefix | PA | `::state` YES (duplicates `:seon.agent/state` in DB); stash-prefix is dead | partially | drift between atom-state and DB-state | **DERIVE-FROM-DB**: drop `::state`; **KILL** stash-prefix key; atom keeps only id + booted-at (or dies entirely if nothing else lands in it) |
| agent.cljs:1709-11 | `data-by-kw`/`seen`/`order` | render fold | FL | — | — | none | KEEP (or volatile!) |
| schema.cljc:38 | `*schemas` | Malli registry | PG | no — the DB `:seon.schema` rows are projections OF it | yes (the registry IS the validator's runtime artifact) | cross-agent same-key last-write-wins + drift vs installed datahike schema | KEEP; add same-key-conflict warn; long-term: DB becomes source on the CLJ central store, registry hydrates from it |
| schema.cljc:351 | `*schema-required-counts` | gen-test bookkeeping | PG | borderline | yes | none | KEEP (dev-only) |
| schema.cljc:42-135 | `_registry-init` etc. (defonce) | one-time type registrations | PG | — | yes (init guards) | none | KEEP |
| log.cljs:183 | `!config` | log file path/cap/keep | PG | no (host config) | yes | none | KEEP |
| fs.cljs:183 | `!config` | fs capability grants | PG | no | yes (capability config) | none today; becomes PER-AGENT when containment lands (one agent's `configure!` widens every agent's fs) | KEEP now; flag: per-agent grants must move into the capability layer, not a shared atom, before multi-agent + untrusted code |
| fs.cljs:440-41 | `!out`/`!truncated` | walk accumulators | FL | — | — | none | KEEP (could be volatile!) |
| deepseek.cljs:60 | `!timeout-ms` | HTTP timeout | PG | no | borderline | shared knob across agents | **FOLD** into the same one-config-map as eval timeout |
| serve.cljs:57 | `!server` | HTTP server handle | PG | no | yes | none | KEEP |
| serve.cljs:63 | `!sse-connections` | open SSE conns | PG | no | yes (socket handles) | none | KEEP |
| serve.cljs:177 | `chunks` | request-body fold | FL | — | — | none | KEEP |
| inspector.cljs:42 | `!sse-by-agent` | agent→SSE conns | PG | no | yes | none | KEEP |
| inspector.cljs:428 | `!pending` | per-agent coalesce timers | PG | no | yes (timers) | none | KEEP |
| test/runner.cljs:438 | `!builder` (volatile) | report fold | FL | — | — | none | KEEP |
| test/runner.cljs:528 | globalThis run-result stash | full run results | PG map | no | yes (three-tier) | unbounded growth (same as eval stash) | KEEP + same eviction story |
| ui/markdown.cljs:42-43 | `acc`/`last-end` | parse fold | FL | — | — | none | KEEP |
| dev/cbor.cljs:92 | `pos` | decode cursor | FL | — | — | none | KEEP |
| dev/wire_node.cljs:42-51 | `!writer`/`!reader`/`!agent-id` | UDS handles + wire identity | PG | no | yes (sockets) / **`!agent-id` no** | `!agent-id` is a transport-global identity — wrong under multi-agent | KEEP handles; **KILL** `!agent-id` as a global: per-op identity from ALS/payload (2.2 work) |
| dev/wire_node.cljs:73-76 | frame-parse atoms | FL per-connection parser | FL | — | — | none | KEEP |
| dev/node_agent.cljs:23 | `!agent-id` | dev-probe identity | PG | no | dev-only | same as above | dev-only; align when 2.2 lands |
| dev/wire_sync.cljs:49,180 | `!bridge`, `globalThis.__seon_client_runtime_db` | sync-bridge handle + WIT surface | PG | no | yes | none | KEEP |
| wasm_eval_smoke.cljs:59-60 | `!compile-state`/`!current-ns` | smoke-test state | PG (dev build only) | — | dev | note: a GLOBAL `!current-ns` atom is exactly the pattern eval-batch! correctly avoids — keep it quarantined to the smoke | KEEP (smoke-only) |
| code.cljc:289 | `reasons` (volatile) | fold | FL | — | — | none | KEEP |
| client.cljs:663-64 | `!n-ok`/`!n-fail` (volatile) | replay fold | FL | — | — | none | KEEP |

`warn.cljs` deserves explicit mention: **zero atoms, zero defonce, fully derived** — it is the reactive-context principle executed perfectly and the template for new surfaces.

### Prioritized kill-list (what to eliminate first, and what replaces each)

1. **`seon.eval/!next-budget-ms`** (eval.cljs:79) — the only holder with an active cross-agent corruption vector. Replace: carry the budget hint in the per-form ALS scope — `budget` writes into `(db/current-tx-context)`-adjacent fiber storage (or a dedicated tiny ALS), `maybe-await-value` reads it from the same fiber. Zero API change for agents.
2. **`::state` in the `seon.agents` per-agent atom** (agents.cljs:86,266) — duplicate of `:seon.agent/state` in the DB. Replace: derive; delete the key from `::ctx-value`. If nothing but id+booted-at remains, evaluate killing the atom layer's seed entirely (the registry can map id→`{:booted-at …}` plain data until a real runtime knob exists).
3. **`:seon.eval/results-stash-prefix` in seon.agents** (agents.cljs:92,257,267) — dead and DRIFTED (eval.cljs uses no agent segment). Replace: delete the key; the stash contract lives in seon.eval alone. (Separately: add stash eviction — not a kill, a leak fix.)
4. **`seon.repl/!conn`** (repl.cljs:85) — second conn owner. Replace: one conn owner; `dev-init!` reuses the pod conn or routes through the same open fn that sets `!agent-conn`.
5. **`seon.eval/!timeout-ms` + `seon.ai.deepseek/!timeout-ms`** (eval.cljs:69, deepseek.cljs:60) — two agent-writable global knobs. Replace: one substrate config map (same shape as `seon.fs/!config`) with per-agent override read through `*ctx*`; agents stop being able to silently change other agents' timeouts.
6. **`seon.dev.wire-node/!agent-id`** (wire_node.cljs:51) — transport-global identity. Replace during 2.2: agent-id per op from `(db/current-agent-id)` stamped into the wire payload.

Not on the kill-list, deliberately: `*schemas` (the registry is the live validator artifact; fix is a conflict-warn, and the CLJ central store later inverts the source-of-truth), `!compile-state` (allowed; fix the tee diff, not the atom), all conn/server/SSE/ALS/timer handles (the allowed category), and every fn-local atom/volatile (not shared state at all).

---

## Cross-cutting findings to act on (smell reports)

1. **Tee misattribution window** (eval.cljs:1181→1216) — fix before two agents eval concurrently for real. Cheapest correct fix: an async mutex serializing eval-batch entries across agents (evals are already serial per agent; cross-agent eval throughput is not a bottleneck today), OR scope `defs-since`/schema diff by the evaluating fiber.
2. **Kick double-schedule window** (agent.cljs:471-491 vs flip at 714) — add a synchronous per-agent scheduled-latch in the handler.
3. **Pivot-plan 2.3 wording conflict** — "per-agent db-name" vs shared-substrate visibility; needs an orchestrator decision (recommend shared DB + agent refs).
4. **2.2b read-your-own-writes** — make the transact rpc apply its own datoms to the replica before resolving (or basis-t sync). Without it, `transact!`-then-`query` agent code silently reads stale state — a worse-than-crash failure mode.
5. **globalThis stashes never evict** (eval results + test runs) — long-run pod memory leak; cap/evict per turn or session.
6. **Stash-prefix drift** between agents.cljs:257 and eval.cljs:515.
7. **Schema registry same-key conflict is silent** — add to the warn registry.
