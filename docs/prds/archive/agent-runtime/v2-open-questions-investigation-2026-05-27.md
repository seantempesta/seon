---
type: research
status: active
tags: [research, agent, database]
---

# V2 Open Questions — Investigation Report

**Date:** 2026-05-27
**Author:** investigation agent
**Status:** Pre-Wave-5 architectural sweep — pure research, no code changes

---

## TL;DR

- **The code-as-data replay loop is HALF-built.** `replay-program-graph!` (src/seon/client.cljs:464-506) re-evals `:seon.ns`/`:seon.fn`/`:seon.schema` entities from the DB in tx-id order. Detect-and-tee (`build-tee-entities`, src/seon/eval.cljs:648-698) populates those entities on every successful agent eval. **What's missing: substrate-source seeding.** On a fresh DB, there are no substrate entities for replay to find — the substrate code comes from `out/client/main.js` at process start, not from the DB. The "the agent sees the system build itself from the ground up" story doesn't actually happen today; only AGENT code participates in the build-itself loop.
- **The runtime atom (`seon.runtime`, src/seon/runtime.clj) is well-disciplined and largely persisted.** Two private atoms (`generated-ids` at L295, `registry-cache` at L330, `flow-handles` at L883) — all three are caches over DB facts (the first is a dedup set for generation; the other two persist via `register!` / `register-flow!` to `:seon.runtime`). Sean's intuition is correct: runtime state IS already "wire things up to predictable places + persist the wiring." Almost nothing belongs in datoms that isn't.
- **Pause/resume is genuinely simple under V2's one-fiber-per-Store model.** All the V0 transient state Sean asked about (warnings-bucket, tx-context, agent-id-als) is either per-eval (correctly transient) or already a key into `!instances`. Pause = stop the wake handler; resume = re-install it. The DB is always up to date because every meaningful state change is a transact. No suspend/resume gymnastics required.
- **For Integrant: ONE registry component, N session entries.** Each session as its own ig component multiplies lifecycle code and breaks the lazy-on-first-reference pattern. The existing `:seon.orchestrator/sessions` (system.clj:153) is already this shape; `:seon.server.session/registry` (Wave 2) follows it. Don't N-ify.
- **Multi-host topology: one host, N guests is fine for MVP and the right Tauri shape too.** Each Tauri install = one user = one host = one wasm guest = one session = one DB. Multi-host is an experiment-runner concern, deferred. The host-to-JVM connection is a UDS socket pair; the path lives in config (Wave 5).
- **CLI config + lifecycle is mostly tractable.** `bin/seon` (the supervisor) already does `start`/`stop`/`status`/`tail` for pod/cljs-watch/jvm. Session-level `create`/`destroy`/`pause`/`resume` belongs ONE LAYER UP from the registry — a thin `seon.session.cli` namespace that calls `seon.server.session/ensure-db!` + `seon.session/start-agent-session!`.
- **Test targets are NOT well-factored today.** `bin/test` runs the JVM kaocha suite (cold JVM ~2min). There is NO `bin/test-cljs` and no separate CLJS target. CLJS tests run via shadow-cljs preload (`src/seon/dev/test_preload.cljs`) inside the running V0 pod. **This is a real gap to fix before Wave 5.**
- **Shadow-cljs dev loop is alive.** `clj -M:cljs watch ...` rebuilds + auto-reloads inside the Node pod. Iteration is sub-second after the first compile. No wasm involved for V0 dev. **For V2 guest-cljs, the same pattern works** — the guest source compiles via shadow under Node first, then the same artifact gets wrapped with wasm-rquickjs only for production builds.
- **Top 3 things to address before Wave 5:** (1) decide whether substrate-source seeding is in or out of MVP scope — it changes what `seon.session/create!` does; (2) clarify the session vs registry vs Integrant split (the three-way namespace collision below); (3) draft the actual config-file shape so Wave 5+6 can target it.

---

## Q1. Code-as-data / serialize-then-replay model

### What V0 already does

**Detect-and-tee** (src/seon/eval.cljs:648-698, called from `record-eval!` at L700-…):
After every successful `cljs.js/eval-str`, snapshots the analyzer's `:defs` diff (via `seon.analyzer-info/defs-since`) and the schema-registry keyset diff (`seon.schema/current-keys`). For each new def, transacts a `{:seon.fn/sym ... :seon.fn/ns [:seon.ns/name <kw>] :seon.fn/source <source> :seon.fn/fn-var? ... :seon.fn/arglists ... :seon.fn/doc ... :seon.fn/private? ... :seon.fn/specced? ... :seon.fn/created-at ...}` map. Each new schema → `:seon.schema` entity. An `(ns …)` form → `:seon.ns` entity. All ride in the SAME tx as the `:seon.eval` entity (via `record-eval!`'s `:tee` arg).

Identity-attr upsert (`:seon.fn/sym`, `:seon.schema/key`, `:seon.ns/name`) means redefinitions replace in place; bitemporal history retains prior versions.

**Replay** (src/seon/client.cljs:464-506):
On boot, `replay-program-graph!` queries every `:seon.ns`/`:seon.fn`/`:seon.schema` entity, sorts by tx-id ascending, and re-evals each entity's `:source` in tx-id order via `cljs.js/eval-str`. Per-entry try/catch (`replay-one!` at L429-449); failures log `:seon.log :warn` entries but do NOT abort replay. Wrapped in `with-tx-context {:seon.db/origin :replay :seon.db/replay? true}` so replay-generated tee entries are distinguishable from live evals.

**Load order on start-agent!** (src/seon/client.cljs:535-630):
1. Open conn (or reuse `@!agent-conn`).
2. Set `db/*conn*` dynvar.
3. `(db/assert-preconditions!)` — schema sanity.
4. `(repl/ensure-bootstrap!)` — initialize cljs.js compile-state from the substrate's bundled `out/bootstrap/` analyzer caches.
5. Mint `agent-id`.
6. `(db/with-agent agent-id (fn ^:async boot-with-agent! [] …))`:
   - `(replay-program-graph! …)` — re-evals DB entities.
   - `(setup-agent-ns! …)` — creates `seon.agent.<id>` ns with a `(result <eval-id>)` accessor.
   - `(agent/boot! …)` — creates `:seon.agent` entity, installs kick listener.
   - `(web.serve/start!)`, handler/wake bootstrap, broadcast install, inspector install.

### What's missing for "the agent sees the system build itself from the ground up"

The substrate's OWN code (`seon.db`, `seon.eval`, `seon.agent`, `seon.client`, `seon.schema`, etc.) is **NOT in the DB on first boot**. It comes from the compiled `out/client/main.js` bundle. So `replay-program-graph!` on a fresh DB sees zero entries — there is nothing to "build up from the ground."

The substrate-seed mechanism described in `docs/seon/concepts/code-as-data-runtime.md` § "Substrate boot — source files as the substrate seed" — "First-boot helper walks `@!compile-state`'s `seon.*` namespaces, reads each source file from disk, slices defining forms by `:line`/`:column`, transacts `:seon.ns` + `:seon.fn` + `:seon.schema` entities" — is documented design but **not implemented**. No fn in `src/seon/*.cljs` matches "walk-substrate-source-and-seed-db". Grep for `seed-substrate`/`substrate-source`/`substrate-seed`/`first-boot` returns zero matches.

So today: agent evals replay correctly on resume. Substrate code does NOT replay; it's re-bound by importing the compiled JS module at process start.

### Concrete proposal for V2 sessions

There are two distinct moments to think about:

| Moment | What happens |
|---|---|
| `seon.session/create!` — first time a session DB is born | Open a fresh `:seon.session/<name>` DB. If substrate-seeding is in scope: read substrate source files (or use a build-time-bundled artifact), transact `:seon.ns`/`:seon.fn`/`:seon.schema` entities for every substrate def. Then any agent that subsequently boots against this session replays the substrate FIRST in tx-id order, then any agent-defined code. |
| `seon.session/start!` — every subsequent connect (including create-then-start) | Open the existing DB. Call `replay-program-graph!`. Whatever's in the DB (substrate-first, then agent additions) re-evals in tx-id order. |

**Recommendation for MVP scope: defer substrate-seeding.** Keep substrate in the compiled bundle for now. Reasons:
1. Substrate seeding requires source files to be readable at runtime — for V0 (Node) they are; for V2 (wasm guest), they'd need to be bundled as WASI preopens. Manageable but adds a build-system concern.
2. Sean's "every restart looks the same to the agent" outcome is **already true** for agent-defined code via the existing `replay-program-graph!`. The substrate hasn't been changing turn-to-turn — it's the floor.
3. Substrate seeding is a separable feature; it can land in a later wave without changing the session lifecycle wired now.

**What `seon.session/create!` does (MVP):** `(seon.server.session/ensure-db! {::db-name :seon.session/<name> ::backend :sqlite ::path "data/sessions/<name>/store.sqlite"})` — creates the konserve store dir, runs `d/create-database`, connects, registers in the atom registry, transacts a `{:seon.session/name … :seon.session/db-name … :seon.session/state :active}` entity into the master `:seon.runtime` DB. **Nothing else.** The session is empty datoms; no substrate, no agent.

**What `seon.session/start!` does (MVP):** Looks up (or `ensure-db!`s) the conn. Calls `start-agent!` for a fresh agent inside the session — which mints agent-id, runs `replay-program-graph!` (no-op on first boot, replays accumulated work on subsequent boots), wires the kick listener, returns the agent-id.

### What needs to be built (LOC estimate)

| Piece | Status | LOC | Notes |
|---|---|---|---|
| `seon.session/create!` | NEW | ~30 | Thin wrapper over `seon.server.session/ensure-db!` + `:seon.session` entity transact. The atom-registry-side already exists (Wave 2). |
| `seon.session/start!` | NEW | ~50 | Calls `ensure-db!` (idempotent), invokes the agent boot path in the context of that session's conn. The CLJS-side boot path EXISTS at client.cljs:535-630 — needs adapting to take a conn arg instead of opening one. |
| `seon.session/stop!` | NEW | ~30 | Halt the wake handler, release agent-id-als, leave the conn open in the registry. |
| `seon.session/destroy!` | NEW | ~30 | Call `seon.server.session/remove-db!` + mark `:seon.session/state :archived` in `:seon.runtime`. Filesystem dir kept by default. |
| Substrate-source seeding | OUT-OF-SCOPE for MVP | ~150 | Later wave. Read `:seon.*` namespaces from `@!compile-state`, slice each def by `:line/:column`, transact entities. |
| **Total MVP code** | | **~140 LOC** | All on top of the EXISTING `seon.server.session` registry + `seon.session` entity. |

---

## Q2. Runtime atom contents

### Current shape (src/seon/runtime.clj, 997 LOC)

Three private `defonce` atoms in the entire ns:

| Line | Atom | Purpose | DB-backed? |
|---|---|---|---|
| 295 | `generated-ids` | Set of recently-generated IDs for dedup during a session | NO (transient — dedup window only) |
| 330 | `registry-cache` | `{namespace -> instance-map}` for fast read | YES — mirror of `:seon.runtime` namespace-instance entities, hydrated via `hydrate-cache!` at L572 |
| 883 | `flow-handles` | `{handle-id -> live-flow}` for non-serializable core.async flow handles | PARTIAL — handle IDs persist as `:seon.runtime.flow/*` entities (L885 `register-flow!`); the actual flow object is in-memory only |

### Persistence audit

What `seon.runtime` persists to the `:seon.runtime` DB (via `db/transact!`):

- Namespace-instance entities (`::namespace`, `::status`, `::location`, `::session-id`, `::nrepl-port`, `::component-key`, `::started-at`, `::stopped-at`) — `register!` at L376.
- Agent-run entities (`:seon.agent.run/*`) — `start-agent-run!` at L679.
- Flow-handle metadata — `register-flow!` at L885 persists `:seon.runtime.flow/*` attrs; the handle ITSELF (not serializable) stays in the atom.
- Flow snapshots — `snapshot-topology!` at L823.

What's in the atom only (genuinely volatile):

- The dedup ID set (`generated-ids`) — exists to avoid generating the same ID twice within ms of each other. Not state to preserve.
- The actual core.async `flow` Java object inside each `flow-handles` entry — not serializable; would have to be re-built from its config on resume.
- The `registry-cache` itself — entirely derivable from the DB via `hydrate-cache!`.

### Recommendations

**The runtime atom is already where Sean wants it to be.** The pattern is:

1. Persist everything serializable to the DB.
2. Keep a fast-read cache atom that mirrors what's in the DB.
3. Keep a separate handle atom for non-serializable runtime references (live flows, conns, etc.).
4. Provide `hydrate-cache!` to rebuild the atom from DB on boot.

This is EXACTLY the pattern proposed for `seon.server.session/registry` (Wave 2) and EXACTLY what the atom-state PRD (`atom-state-system-2026-05-26.md` §10) is trying to bring to the CLJS pod.

**Nothing in `seon.runtime` should move.** The audit's claim (`integration-anti-rewrite-audit-2026-05-26.md` §7) that `seon.runtime` is the prior art for "register a running thing at runtime" is correct — extend it (or copy its pattern), do not parallel it.

**One small smell:** `flow-handles` is keyed off `handle-id` strings rather than the more obvious `(namespace, component-key)` tuple — works fine, but readers have to look up the handle through an extra indirection. Not a blocker.

---

## Q3. Pause/resume

### Volatile state inventory (V0 pod)

(See `atom-state-system-2026-05-26.md` §2 for the canonical 18-row table. Summarized here from the per-state classification lens Sean asked for.)

| State | File:line | Lifetime | Classification |
|---|---|---|---|
| `als-instance` (tx-context) | db.cljs:481-483 | Per-eval | VOLATILE BY DESIGN — fiber-local. Resets on every form. Nothing to preserve. |
| `agent-id-als` | db.cljs:516-518 | Per-fiber-during-turn | DERIVED — id IS in the DB (`:seon.agent/id`); the ALS just carries it through awaits. On resume, re-bind from DB. |
| `warnings-als` | eval.cljs:205-207 | Per-eval-form | VOLATILE BY DESIGN — collects warnings during ONE cljs.js call. Discarded immediately. |
| `*conn*` dynvar | db.cljs:439 | Process | RECONSTRUCTABLE — re-opens against the same on-disk store. |
| `seon.eval/!timeout-ms`, `!next-budget-ms` | eval.cljs:66, 76 | Process | CONFIG — defaults are code; per-agent overrides will live in `!instances` (atom PRD). |
| `seon.fs/!config`, `seon.log/!config` | fs.cljs:183, log.cljs:186 | Process | CONFIG — same. |
| `seon.client/!state` | client.cljs:108-111 | Process | TRANSIENT — boot timestamp, reload counter. Not preserve-worthy. |
| `seon.client/!agent-conn` | client.cljs:226 | Process | DUPLICATE — mirrors `*conn*` (atom PRD §8 marks it for deletion). |
| `seon.repl/!compile-state`, `!init-version`, `!conn` | repl.cljs:76, 83, 85 | Process | BOOTSTRAP — `!compile-state` is genuinely volatile (the cljs.js analyzer atom). Rebuilt on every pod boot via `init-bootstrap!`. The other two are duplicates. |
| globalThis result-stash (`__seon_results_<eval-id>`) | eval.cljs:512-527 | Process | TRANSIENT — raw eval result values that can't pr-str cleanly. The DB has `:seon.eval/result-edn` (truncated text); the raw value is for in-session `(result <id>)` recall only. Lost on pod restart — acceptable per atom PRD §11 Q1. |
| Per-agent home-ns vars | (historic; eval.cljs:540-546 docstring) | Process | ALREADY DELETED 2026-05-25. |

### Per-state classification

| Movable to DB | Genuinely volatile (rebuilt on boot) | Bug |
|---|---|---|
| Per-agent config overrides (timeout, fs allowlist) — atom PRD's `:seon.agent.config/*` projections | `*conn*` (rebuilt by re-opening), `!compile-state` (rebuilt by `init-bootstrap!`), `agent-id-als` (re-bound in `with-agent`), tx-context/warnings ALS (per-eval) | `!agent-conn` + `!conn` (duplicate `*conn*`); ALS-vs-dynvar drift in overlay |

**No state that Sean cares about is "lost" by a pause.** The DB IS the substrate of truth.

### Resulting pause/resume spec

```
seon.session/pause! {::name <name>}
  1. Look up agent(s) bound to this session via `:seon.agent/session` ref.
  2. For each agent: transact `:seon.agent/state :paused`.
  3. The wake handler's :running guard already short-circuits on non-:idle state — kicks are no-ops while paused.
  4. The wake handler ITSELF stays installed; no Integrant halt needed.
  5. Return immediately.

seon.session/resume! {::name <name>}
  1. For each :paused agent: transact `:seon.agent/state :idle`.
  2. Synthesize a wake (write a `:seon.message/to` row with empty content
     or call the kick handler directly). Or just rely on the next user
     message to wake it.
  3. Return.

seon.session/stop! {::name <name>}
  1. Cancel any in-flight turn via the abort path (per-form eval timeout
     already exists at eval.cljs:66 — the agent's :running flag flips
     to :idle when run-turn! completes or aborts).
  2. Uninstall the wake handler (delete the kick listener — it's
     in-memory only).
  3. The session DB conn stays open in the registry; only the agent's
     in-process wake machinery is torn down.

seon.session/destroy! {::name <name>}
  1. seon.server.session/remove-db! — close conn, drop registry entry.
  2. Transact :seon.session/state :archived into :seon.runtime.
  3. Filesystem dir kept by default (configurable hard-delete flag).

```

**Pause/resume is trivial because the DB is always coherent.** Sean's intuition holds.

---

## Q4. Integrant lifecycle for sessions

### Proposal: ONE registry component, N session entries

`seon.server.session/registry` (the atom defined at src/seon/server/session.clj:83-85) is the entire session state for Path B. There should be exactly ONE Integrant component (`:seon.server.session/registry`) that owns this atom's lifecycle:

- `init-key` — `(reset! !registry {})` (already happens via `defonce`; the component just exposes it).
- `halt-key!` — `(doseq [{::keys [conn]} (vals @!registry)] (try (d/release conn) (catch _)))` then `(reset! !registry {})`.
- `suspend-key!` — return the registry snapshot. Conns are kept open (cheap; no socket teardown).
- `resume-key` — restore the snapshot OR (if config differs) halt + init.

Per-session is NOT an Integrant component. Sessions are runtime-managed atom entries with a corresponding DB entity. Reasons:

1. **Integrant requires static config at boot.** Sessions are minted at runtime (`ensure-db!` on first reference). An ig component per session would require dynamic ig-registration, which is exactly the pattern the flow spike rejected (`flow-runtime-update-spike-2026-05-26.md`).
2. **The existing `:seon.orchestrator/sessions` component (system.clj:153-178) is already this shape.** ONE component, manages N orchestrator sessions internally. `:seon.server.session/registry` follows it.
3. **Halt semantics are right: one atom-clear takes down all sessions.** Per-session ig would need ordered halts; this gets that for free.

### Suspend/resume support

Integrant supports it via `ig/suspend-key!` + `ig/resume-key`. The system.clj pattern (e.g. `:seon.dev/nrepl` at L136-143) is: suspend returns the live state; resume reuses if config is the same, else halts + reinits. Apply identically to `:seon.server.session/registry`. For `(user/reset)`, this means conns stay open across the reset — fast, no datahike re-open cost.

**For agent-level pause/resume** (a different concept — see Q3), no Integrant involvement. It's a DB transact + a wake-handler guard.

---

## Q5. Multi-host topology

### MVP shape (one host, N guests)

The V2 PoC's actual shape (`pod-host/sidecar-poc/SESSIONS.md` §3):
- ONE Rust host process.
- N wasm CLJS guests inside the host (each its own `wasmtime::Store`).
- Each guest bound to ONE session at instantiation via WASI env `SIDECAR_SESSION=<name>`.
- All guests in all sessions talk to the seon JVM's wire-server (post-Wave 4-6) via the host's UDS connection.

For MVP this is exactly right. There's nothing the agent-runtime wave plan needs to add to this — Wave 6a explicitly trims the host to "drop JvmSupervisor; connect to existing seon JVM."

### Scale-out path

When we need N hosts (e.g. one host per Tauri install, plus one experiment-runner host on a remote machine):

- The wire-server (Wave 5) listens on UDS sockets in `tmp/` per the architecture doc. UDS is local-only.
- For remote hosts: swap the UDS for TCP. The codec/transit/broadcast layers don't care about the transport. Existing TCP plumbing at `src/seon/flow/harness/channel.clj` (158 LOC) is the prior art.
- Each host's config carries the seon JVM endpoint (`{:seon.host/jvm-endpoint "unix:/tmp/seon-poc-req.sock"}` or `{:seon.host/jvm-endpoint "tcp://10.0.0.5:7900"}`).
- The session a guest connects to is still identified by `:seon.session/<name>`; the JVM's registry doesn't care which host the request came from.

**Scale-out is a one-day swap when needed.** Not a Wave-5-blocking concern.

### Discovery + connection management

For MVP (one host, one JVM, both local):
- The Rust host hardcodes (or reads from config) the JVM's UDS path.
- The JVM's `:seon.server/wire` Integrant component listens at a fixed path read from `resources/system.edn`.
- Default path: `tmp/seon-poc-req.sock` + `tmp/seon-poc-pub.sock` (already canonical per `pod-host/sidecar-poc/SESSIONS.md`).

For Tauri: the Tauri bundle includes BOTH binaries; the host is launched as a Tauri sidecar process; both processes know the path. No discovery protocol needed.

For experiments (later): config-driven (Q6).

---

## Q6. Config-driven launching

### EDN shape (concrete example)

```clojure
;; ~/.seon/config.edn or ./seon-experiment.edn
{:seon.host/jvm
 {:seon.host.jvm/endpoint "unix:tmp/seon-poc-req.sock"
  :seon.host.jvm/pub-endpoint "unix:tmp/seon-poc-pub.sock"
  ;; if endpoint isn't reachable, launch:
  :seon.host.jvm/launch
  {:seon.host.jvm.launch/cmd ["./bin/run"]
   :seon.host.jvm.launch/cwd "/Users/sean/src/seon"
   :seon.host.jvm.launch/wait-ms 10000}}

 :seon.host/sessions
 [{:seon.session/name :alice
   :seon.session/backend :sqlite
   :seon.session/path "data/sessions/alice/store.sqlite"
   :seon.session/agents
   [{:seon.agent/role :primary
     :seon.agent/llm :deepseek
     :seon.agent/auto-start? true}]}
  {:seon.session/name :bob
   :seon.session/backend :sqlite
   :seon.session/agents
   [{:seon.agent/role :primary
     :seon.agent/llm :stub
     :seon.agent/auto-start? false}]}]}

```

Two distinct use-cases:

| Use-case | Config shape | Lifecycle |
|---|---|---|
| **Tauri product** (single user) | `~/.seon/config.edn` with ONE session named after the user, plus the user's LLM API key reference | Launched by Tauri at app-start; lives for the app's lifetime; session DB persists across launches |
| **Experiment** (multi-session) | `seon-experiment-N.edn` with N sessions, N agents per session, role mix | Launched explicitly via CLI; runs for the experiment duration; sessions may be torn down at end |

### Commands

`bin/seon` already does process-level start/stop/status/tail for `pod`/`cljs-watch`/`jvm`. ADD a session-level layer:

```
bin/seon session create --name alice --backend sqlite
bin/seon session start  --name alice [--llm deepseek]
bin/seon session pause  --name alice
bin/seon session resume --name alice
bin/seon session stop   --name alice
bin/seon session destroy --name alice
bin/seon session list
bin/seon session status --name alice

```

Implementation: each subcommand is a thin shell wrapper that sends an HTTP request to the seon JVM's existing `seon.web.server` (port 8080) at a `/api/session/*` endpoint. The endpoint's handler calls `seon.session/<verb>!`. Alternative: an nREPL eval. The HTTP route is more portable (works without REPL connection).

### Experiment state persistence

Per Q4 + Q6, every session has a `:seon.session` entity in `:seon.runtime` with `::name`, `::db-name`, `::backend`, `::path`, `::state`. For experiments, ADD `:seon.experiment/*` entities in `:seon.runtime` that group sessions:

```clojure
{:seon.experiment/id "exp-2026-05-27-001"
 :seon.experiment/name "deepseek vs stub comparison"
 :seon.experiment/started-at #inst "..."
 :seon.experiment/sessions [[:seon.session/name :alice]
                            [:seon.session/name :bob]]
 :seon.experiment/state :running}

```

Experiments are first-class datoms in `:seon.runtime`, queryable from the existing inspector. Stop = transact `:seon.experiment/state :stopped`; cascade-stop the bound sessions.

### Tauri vs experiment difference

- **Tauri:** one session per user, named at app first-launch via `seon.db/new-id!`, saved in Tauri's config dir, reopened every relaunch. Wire endpoint = the bundled JVM's UDS sockets.
- **Experiment:** N sessions per experiment, named by the experiment config. Wire endpoint = local seon JVM, possibly with the host launching the JVM if it isn't running.

The session-lifecycle code is IDENTICAL in both. The difference lives in the config file and the launcher (Tauri vs `bin/seon experiment start`).

---

## Q7. Test targets

### Current state

- `bin/test` runs JVM kaocha (`clojure -M:test -m kaocha.runner ...`). Cold JVM ~2min, focused tests faster. Discourages no-arg invocation (prints REPL-first guide).
- **No `bin/test-cljs`.** CLJS test invocation today: load `src/seon/dev/test_preload.cljs` (5 LOC; requires every CLJS test ns) via shadow-cljs in the running pod; tests run inside the Node process.
- **No `bin/test-clj` either.** `bin/test` IS the JVM test runner.
- REPL-first: `(user/run-tests 'seon.foo-test)` (preferred), `(user/run-tests)` (full).

### Proposal

Restructure to:

| Target | Runs | Cold time | Use-case |
|---|---|---|---|
| `bin/test-clj [ns ...]` | JVM kaocha, focused or full | ~2min cold, seconds REPL | JVM-side tests (everything in `src/seon/*.clj{c}`, `test/seon/*.clj`) |
| `bin/test-cljs [ns ...]` | shadow-cljs node-test target | ~30s cold (shadow rebuilds quickly), seconds for re-runs | CLJS-pod tests (everything in `src/seon/*.cljs`, future `pod-host/guest/...`) |
| `bin/test` | runs both | ~3min cold | CI entry point; pre-commit |

Implementation:
- `bin/test-clj` = current `bin/test` renamed.
- `bin/test-cljs` = new ~30 LOC bash wrapper. Invokes `clj -M:cljs compile node-test && node out/node-test/main.js`. Add a `:node-test` build to `shadow-cljs.edn` (~10 LOC).
- `bin/test` = sequential call to both, with combined exit code.

**Pre-Wave-5 priority: shipping `bin/test-cljs` so the CLJS integration tests in Wave 4b+ have a non-REPL invocation path.** The MCP REPL has been flaky enough during prior waves that bin scripts are valuable insurance.

---

## Q8. Shadow-cljs dev story

### Current state

`shadow-cljs.edn` (not opened in this investigation but referenced everywhere) defines at least `:client` (the V0 pod) and `:cljs-sidecar`/`:v0-probe` (the overlay). Dev loop:

```bash
clj -M:cljs watch client          # recompile on file change
# In another terminal:
node out/client/main.js           # runs the pod; hot-reload triggers reset

```

The `user/reset` machinery in the pod re-evals changed files. Iteration time: sub-second after the first compile (shadow incremental).

### Proposed dev-loop time

Pre-Wave-5: no change. Sean wants this preserved.

Post-Wave-7 (sidecar):
- `clj -M:cljs watch guest` (the new build target for `pod-host/guest/`) recompiles on file change. Output goes to `pod-host/guest/build/sidecar_guest.cljs.js` or similar.
- For DEV iteration: run the guest output under Node (NOT under wasm-rquickjs) against the seon JVM's wire-server. Same sub-second iteration as today.
- For PRODUCTION builds: wrap the same artifact with wasm-rquickjs and load into the Rust host. This is a one-off `pod-host/build-guest` script invocation (already exists at `pod-host/sidecar-poc/build-sidecar-agent`).

**Net: dev iteration stays sub-second. WASM is opt-in for end-to-end production smokes.** This matches Sean's "We still want to support the shadow build for easy testing outside of the wasm container."

---

## Q9. Tauri integration shape

### Single-user vs multi-user

Tauri ALWAYS = single-user. One Tauri install = one user = one session conn to the JVM. The Tauri bundle includes:

- The Tauri Rust app shell (UI in webview).
- The seon JVM (or a slim JVM-with-app-classpath).
- The Rust host binary (for wasm guest execution).
- The wasm guest artifact.

Launch sequence:
1. User opens the app.
2. Tauri Rust launches the seon JVM as a sidecar process; waits for `tmp/seon-poc-req.sock` to be live.
3. Tauri Rust launches the Rust host as a sidecar process; passes the JVM endpoint and the guest .wasm path.
4. The Rust host instantiates ONE wasm guest with `SIDECAR_SESSION=<user-id>` (read from Tauri's config dir; minted on first run).
5. The guest connects through the host through the JVM's wire-server → registry → datahike conn for `:seon.session/<user-id>`.
6. Tauri's webview connects to the JVM's `seon.web.server` (port 8080 by default) — that's the user-facing UI.

### Save/resume guarantees

- Every `seon.db/transact!` from the guest is synchronous from the guest's perspective (it awaits the wire response). The JVM's datahike commits via konserve-sqlite which fsyncs per-commit.
- Pod restart: seon JVM relaunched on next Tauri start; same `:seon.session/<user-id>` DB reopened from disk; `replay-program-graph!` re-evals agent code; user sees the same state.
- App force-quit: in-flight transacts that didn't ack are lost; everything acknowledged is durable.

**The save/resume model is "every transact is the save." No explicit save step.** This is the durability story Sean has been targeting.

### Open concerns

- **JVM startup time inside Tauri.** Cold JVM is ~10s today (per `bin/test` notes). A user opening the app and waiting 10s before they can type is bad UX. Mitigations: precomputed AOT classes, jlink trimmed JRE, OR a "splash screen while JVM warms" UI pattern. Out-of-MVP-scope but flagged.
- **Wasm guest in the Tauri bundle:** the .wasm artifact is ~5-20MB; needs to be in the Tauri sidecar bundle. Tauri supports sidecar binaries; verify the wasm-rquickjs runtime is one of the bundled binaries, not loaded dynamically.
- **Code signing / notarization:** for macOS distribution, both the JVM and the Rust binaries need to be signed under the same Developer ID. Not a code concern but a release-engineering one.
- **Auto-update of substrate code:** Tauri's auto-updater can ship a new app version; this brings a new compiled JS bundle. Substrate code is bundled, not in the user's DB → the update is invisible to the user's data. Agent-defined code (in the DB) is preserved. This is the cleanest split possible.

---

## Synthesis — what to build, in order

Aligns with Waves 4b–7 in `execution-waves-2026-05-26.md`. Each item carries its file path + LOC estimate + gate criterion.

| # | Item | Files | LOC | Gate |
|---|---|---|---|---|
| **0** | **Resolve the three-way session-namespace tangle.** Today: `seon.session` (472 LOC, the orchestrator pool-based session), `seon.orchestrator.session` (609 LOC, richer schema, has `::db-name`), `seon.server.session` (203 LOC, the new atom registry). Wave 3 plans to rename `orchestrator/session.clj → session.clj`, but this collides head-on with the existing `seon.session`. Re-read Wave 3 spec; PICK ONE consolidation path; document in the wave header. | src/seon/session.clj, src/seon/orchestrator/session.clj, src/seon/server/session.clj | 0 (decision-only) | Single owner doc for each session concept |
| **1** | Wave 4b verification (deferred from Wave 4a) — verify wire-server bytes roundtrip. | (verification only) | 0 | UDS smoke works |
| **2** | Wave 5a — `:seon.server.session/registry` + `:seon.server/wire` Integrant components. ONE registry component, N session entries (Q4). Suspend/resume by snapshotting the registry atom; conns kept open across `(user/reset)`. | src/seon/system.clj, resources/system.edn | ~80 | `(user/reset)` 10× clean; `:seon.server/wire :ok` |
| **3** | `seon.session/{create!,start!,stop!,destroy!,pause!,resume!}` API (Q3 spec). Thin wrappers over `seon.server.session/ensure-db!` + `:seon.session` entity transacts + agent-boot path. | src/seon/session.clj (or wherever item 0 lands) | ~140 | Round-trip test: create alice, start agent, transact, stop, restart, agent's prior state visible |
| **4** | Wave 6a — Rust host trim. Drop `JvmSupervisor`; connect to seon JVM's wire-server. | pod-host/sidecar-poc/rust-host/src/*.rs | ~3h subtractive | Cargo build clean |
| **5** | Wave 6b — Phase D smoke against seon JVM. | (verification only) | 0 | 0 errors, 0 out-of-order, p50 in single-digit ms |
| **6** | `bin/test-cljs` + restructure `bin/test`/`bin/test-clj` (Q7). | bin/test, bin/test-clj (new), bin/test-cljs (new), shadow-cljs.edn | ~50 | Each target runs targeted-and-full modes |
| **7** | Config file shape + `bin/seon session ...` CLI subcommands (Q6). Thin wrappers; HTTP request to seon.web.server endpoints. | bin/seon, src/seon/web/handlers/session.clj | ~120 | `bin/seon session create --name x` round-trips |
| **8** | Wave 7 cleanup — delete `pod-host/sidecar-poc/jvm-writer/`, update docs. | (deletion only) | 0 | Final commit clean |
| **9** | DEFERRED — substrate-source seeding (Q1). | src/seon/substrate.cljs (new) | ~150 | Optional; needed only when "agent sees substrate build" is in scope |
| **10** | DEFERRED — atom-state ALS replacement (atom PRD). | src/seon/agents.cljs (new) | ~150 | Optional; needed when V0 single-process drops in favor of multi-Store wasm |
| **11** | DEFERRED — Tauri packaging (Phase 8). | pod-host/tauri/* | TBD | Not blocking MVP |

**Total Wave-5-blocking LOC: ~390** (items 2, 3, 6, 7). Items 0 and 1 are decisions/verification with no code.

---

## Top 3 things to address before Wave 5

1. **Resolve the three-way `seon.session*` namespace collision (item 0 above).** Wave 3's "rename orchestrator/session → session" was written before `seon.server.session` (Wave 2) shipped. Now there are three namespaces named "session" doing related-but-distinct things. Until this is sorted, every subsequent wave touching session code will pick the wrong one. **The cleanest split** based on what each currently owns:
   - `seon.server.session` = pure registry (atom of `db-name → {conn,backend,path}`). KEEP.
   - `seon.session` = session ENTITY schema + lifecycle API (`create!`/`start!`/`stop!`/`destroy!`/`pause!`/`resume!`). REPLACE current contents with the orchestrator/session.clj content + the new lifecycle verbs.
   - `seon.orchestrator.session` = DELETE; its content merged into `seon.session`.

2. **Decide MVP scope for substrate-source seeding (Q1).** The current `replay-program-graph!` works correctly but starts from empty on a fresh DB. If "the agent sees the system build itself up" is in MVP scope, item 9 above needs to land before Wave 7. If it's deferred (recommended), document explicitly that V2 session boot replays AGENT-DEFINED code only and the substrate comes from the compiled bundle.

3. **Ship `bin/test-cljs` before Wave 5 verification work begins (Q7).** The MCP REPL wedged twice during Waves 1a and 2a per the wave-status table. CLJS integration tests in the upcoming waves need a non-REPL invocation path. ~50 LOC of bash + shadow-cljs config. Trivial but unblocking.

---

## Open questions remaining (honest list)

I didn't have time to fully investigate:

- **Whether `seon.server.session/registry` should snapshot to `:seon.runtime` on each `ensure-db!`/`remove-db!`** as the existing `seon.runtime` patterns do (`register-flow!` at runtime.clj:885). The Wave 2 implementation does NOT currently transact a `:seon.session` entity into `:seon.runtime` — only the atom mutates. The integration-architecture doc §5 says it SHOULD (`record-session-entity! db-name entry`), but `src/seon/server/session.clj:119-145` `ensure-db!` doesn't have that call yet. **This is a gap to close in Wave 5 or in item 3.**
- **Exact shape of `seon.session/start!`'s agent-boot path under Path B.** The CLJS-side `start-agent!` at client.cljs:535-630 has all the right shape but assumes (a) `db/*conn*` is a CLJS datahike conn and (b) the agent runs in-process. Under V2, the agent's conn is the wire-server-relayed JVM conn, and the agent runs in a wasm guest. The atom PRD §10 Phase 1 hints at the seam (`set-volatile! ::compile-state-ref` at boot) but doesn't spec the full new boot path. **Worth a dedicated session-boot design doc before item 3 worker starts.**
- **Whether the host should auto-launch the seon JVM when not present** (Q6 config sketches it; not investigated whether `bin/seon` has the right machinery). The supervisor IS idempotent per CLAUDE.md Process Architecture but I didn't trace whether the Tauri Rust side can invoke it.
- **The `seon.flow.harness.channel` TCP wire-server pattern.** Anti-rewrite audit §4 flags `seon.db.relay` (339 LOC) as the closest existing wire-server analog. I confirmed it exists but didn't read it in depth. If Path B ever needs to swap from UDS to TCP, this is the prior art.
