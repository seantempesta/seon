---
type: prd
status: draft
tags: [prd, agent, platform, architecture]
---

# Atom-Backed Runtime State for Agents

**Author:** research agent (PRD draft, no implementation)
**Date:** 2026-05-26
**Branch:** `feature/agent-runtime`
**Supersedes (partially):** the AsyncLocalStorage + ad-hoc dynvar arrangement documented at `src/seon/db.cljs:421-440` and the per-namespace `defonce !config` atoms scattered across the substrate.
**Status:** DRAFT — not approved. Open questions in §11 must be resolved before any phase starts.

---

## TL;DR

V0 grew **two ALS instances** (`als-instance` for tx-context, `agent-id-als` for the active agent — `src/seon/db.cljs:481, 516`), **one ALS for analyzer warnings** (`warnings-als` — `src/seon/eval.cljs:205`), **two `^:dynamic` Vars** (`*conn*` — `src/seon/db.cljs:439`, `*log-file*` — `src/seon/log.cljs:178`), and **roughly a dozen `defonce !foo (atom …)`** sites (process-global config / timeouts / lazy-init slots). The result is a substrate where "the runtime knows about the active agent" has at least four different mechanical answers depending on which file you're in. The sidecar overlay maintains that incoherence — it ships parallel `^:dynamic` Vars that DON'T survive `await` because wasm-rquickjs has no ALS (see `pod-host/sidecar-poc/bench/v0-port-survey.md:228+`). When V0 added the agent-id ALS (commit `5a82742`, 2026-05-24), the overlay drifted silently.

The user wants ONE atom — `seon.agents/!instances` — holding `{<agent-id> <instance-map>}`, with the substrate wiring each agent's slot at startup. Atoms transfer cleanly to CLJ (the sidecar JVM writer at `pod-host/sidecar-poc/jvm-writer/src/seon/sidecar/writer.clj:27` already uses one — `(defonce ^:private state (atom nil))`). The user is also clear that **agents must not be encouraged to use atoms themselves** because the substrate can't guarantee persistence/resume for atoms created in agent eval space.

This PRD argues: **collapse all per-agent runtime state into one substrate-owned atom**, with an explicit substrate API (`seon.agents/get`, `seon.agents/set!`) that the agent's eval calls. Atoms persist as DB-derived snapshots on every `set!`; resume reconstitutes the atom from DB facts. ALS stays exactly where it must — `with-agent` and `with-tx-context` continue to bind fiber-local identity, but **only because that identity is a KEY into `!instances`**, not because the identity carries the state itself. The agent's home-ns (`seon.agent.<id>`) becomes a **lookup view** over `!instances`, not a state container.

---

## §1 Goals, non-goals, load-bearing constraints

### Goals

1. **One mechanism** for per-agent runtime state across the entire substrate. New per-agent state is a key in the instance map, not a new defonce.
2. **CLJ ↔ CLJS portable.** Atom semantics are identical on both sides (`swap!`/`reset!`/deref). The sidecar JVM writer and the CLJS pod both run the same `!instances` shape.
3. **Inspectable.** `@seon.agents/!instances` from a REPL or inspector handler returns a fully-namespaced data structure validated by Malli. No opaque ALS stores hiding state.
4. **Resume-safe.** Pod restart reconstructs `!instances` from DB facts before any agent code runs. State the agent put there via `(seon.agents/set! …)` survives a cold boot.
5. **Agent eval space discouraged from minting its own atoms.** The substrate provides the obvious correct way to keep per-session state, so atoms in agent code become an anti-pattern with a documented reason.

### Non-goals

1. **NOT removing `seon.db/*conn*`.** The DB connection is a genuinely-singleton runtime artifact, not per-agent state. It stays a dynvar on the V0 single-process pod (one conn per pod). The sidecar already has a different posture (no in-guest conn; routes to JVM writer). Each is correct as-is.
2. **NOT removing fiber-local identity propagation.** `with-agent` / `current-agent-id` stay — they answer "who's running THIS turn", which is intrinsically fiber-scoped on a multi-agent pod. The atom answers "what state does agent X carry"; ALS answers "which agent is X for THIS callstack". These are different questions.
3. **NOT a security boundary.** The atom is open; agent eval can `(swap! seon.agents/!instances …)` directly. This catches LLM hallucinations and gives one obvious correct path, not adversarial isolation. WASM containment is Phase 3 (`CLAUDE.md` "Where we're going").
4. **NOT replacing `:seon.fn`/`:seon.ns`/`:seon.schema` program-graph entities.** Those ARE the persisted code. The atom holds runtime-volatile-by-default; the program graph holds code-as-data. (See `CLAUDE.md` "Code as data".)

### Load-bearing constraints (from the user's framing)

These are non-negotiable. Future agents reading this PRD MUST refuse changes that violate them.

| # | Constraint | Source |
|---|---|---|
| C1 | **Agent IDs are generated at boot** (`seon.client/start-agent!` calls `(db/new-id!)` at `client.cljs:565`). No pre-declared namespaces like `seon.agent.alice`. The atom must accept ids born at runtime. | User: "Agent IDs are generated, so we can't pre-define namespaces" |
| C2 | **One atom in a known location.** `seon.agents/!instances`. Not per-agent atoms, not per-namespace defonces. Discoverable by both REPL inspection and Malli schema. | User: "Use one system atom in a known location" |
| C3 | **Agents are wired in at substrate startup.** Substrate writes the initial instance slot before any agent code runs. The agent's eval surface NEVER initializes its own slot. | User: "with agents wired in at startup" |
| C4 | **Atoms must be CLJ-compat.** Same `(defonce !instances (atom …))` form runs on both sides. No CLJS-only or JVM-only primitive. | User: "Atoms transfer to CLJ too" |
| C5 | **Discourage agent-created atoms.** The substrate provides the obvious correct API so agents don't mint their own. | User: "I don't want to encourage atom use for agents — we can't guarantee they're persisted" |
| C6 | **Agent init is required to wire the slot.** No lazy-init in the read path. If an agent's slot isn't in `!instances`, it's a substrate bug, not an agent error. | User: "We need the full agent init to wire it up" |
| C7 | **The atom's Clojure form is itself persisted.** Either as a `:seon.ns` entity carrying `(ns seon.agents)` and `(defonce !instances …)`, or by being part of the substrate-source bootstrap that loads from program-graph entities on resume. Without this, restarting the pod against an existing DB tries to read state for an undefined var. | User: "We need to persist the atom code too somewhere or else it's not valid Clojure we're eval'ing" |

---

## §2 Current state — every per-agent / per-fiber state site

This is the inventory. Each row is a mechanism that today carries state that PROBABLY belongs in `!instances`. I'm being conservative — flagging things even when they might stay where they are.

| # | Site | File:line | What it stores | Lifetime | Mechanism | Who reads | Who writes |
|---|---|---|---|---|---|---|---|
| S1 | `als-instance` (tx-context) | `db.cljs:481-483` | Causality bundle: `:seon.db/{agent-id,session-id,turn-id,eval-id,origin,replay?,resume-marker?}` | Dynamic extent of `with-tx-context` body, survives `await` | Node `AsyncLocalStorage` | `current-tx-context` (`db.cljs:485`), `merge-tx-context-into-opts` (`db.cljs:839`) | `with-tx-context` (`db.cljs:546`) at `eval-batch!` per-form (`eval.cljs:854`), `run-turn!` (`agent.cljs:664`), `replay-program-graph!` (`client.cljs:474`) |
| S2 | `agent-id-als` | `db.cljs:516-518` | `<agent-id>` string only | Dynamic extent of `with-agent` body, survives `await` | Node `AsyncLocalStorage` (added in `5a82742`) | `current-agent-id` (`db.cljs:520`), `resolve-id` in agent.cljs:835, web handlers, every read API in `seon.agent` | `with-agent` (`db.cljs:530`) at `start-agent!`, `chat`, the `setTimeout`-driven kick handler (`agent.cljs:356`) |
| S3 | `warnings-als` | `eval.cljs:205-207` | Per-form analyzer warning bucket atom | Dynamic extent of `raw-eval`'s `cljs.js/eval-str` call, must survive `await` of the loader | Node `AsyncLocalStorage` | the root warning dispatcher (`eval.cljs:226`) | `raw-eval` wraps each cljs.js call in `.run` (`eval.cljs:412`) |
| S4 | `*conn*` | `db.cljs:439` | The single datahike connection for the pod | Process lifetime (set once at `start-agent!`) | `^:dynamic` Var, mutated via `set!` at root | Every `db/*` fn that resolves `:conn` with default `*conn*` | `start-agent!` `(set! db/*conn* conn)` (`client.cljs:547`) |
| S5 | `*log-file*` | `log.cljs:178` | Path string to the log file | Process lifetime; overridable in tests | `^:dynamic` Var (`"logs/pod-events.log"`) | `event-file-path` (`log.cljs:216`), readers + writers | `configure!` calls `(set! *log-file* …)` (`log.cljs:204`) |
| S6 | `seon.log/!config` | `log.cljs:186` | `{:seon.log/file-cap, :seon.log/keep}` rotation policy | Process lifetime | `defonce` atom | `rotate-if-needed!` (`log.cljs:236`) | `configure!` (`log.cljs:209`) |
| S7 | `seon.fs/!config` | `fs.cljs:183` | `{:seon.fs/allowed-roots, :seon.fs/read-only?}` | Process lifetime | `defonce` atom (env-bootstrapped) | every fs gate fn | `seon.fs/configure!` |
| S8 | `seon.eval/!timeout-ms` | `eval.cljs:66` | Per-form wall-clock timeout (default 10000) | Process lifetime; mutable from agent eval | `defonce` atom | `race-timeout`, `maybe-await-value` | `set-timeout-ms!`, `budget` (one-shot via `!next-budget-ms`) |
| S9 | `seon.eval/!next-budget-ms` | `eval.cljs:76` | Side-channel one-shot timeout override | Until next `maybe-await-value` consumes it | `defonce` atom | `maybe-await-value` (`eval.cljs:596`) | `(seon.eval/budget …)` (`eval.cljs:99`) |
| S10 | `seon.ai.deepseek/!timeout-ms` | `deepseek.cljs:59` | LLM HTTP timeout (default 60000) | Process lifetime | `defonce` atom | the deepseek HTTP caller | `set-timeout-ms!` |
| S11 | `seon.client/!state` | `client.cljs:108-111` | `{:boot-at, :reload-count, :heartbeat-id}` | Process lifetime | `defonce` atom | reload hooks, heartbeat | `before-reload`, `after-reload` |
| S12 | `seon.client/!agent-conn` | `client.cljs:226` | The single datahike conn (mirrors `db/*conn*`) | Process lifetime; idempotent on reload | `defonce` atom | `start-agent!` reuse logic (`client.cljs:543`) | `start-agent!` |
| S13 | `seon.repl/!compile-state` | `repl.cljs:76` | Outer atom holding the cljs.js compile-state atom | Process lifetime; rotated on `seon.eval` reload via `!init-version` | `defonce` atom-of-atom | `ensure-bootstrap!`, agent code via globalThis lookup | `ensure-bootstrap!` |
| S14 | `seon.repl/!init-version` | `repl.cljs:83` | Gensym stamp matching `seon.eval/init-version` | Process lifetime | `defonce` atom | bootstrap stale check | `ensure-bootstrap!` |
| S15 | `seon.repl/!conn` | `repl.cljs:85` | "Yet another mirror of the conn" | Process lifetime | `defonce` atom | dev-init paths | `dev-init!` |
| S16 | `seon.eval/!warning-dispatcher-version` | `eval.cljs:213` | Hot-reload version stamp for the warning dispatcher | Process lifetime | `defonce` atom | `install-warning-dispatcher!` | same |
| S17 | Per-eval `warnings` bucket atom | `eval.cljs:409` | `(atom [])` collecting analyzer warnings for ONE eval | The duration of one `cljs.js/eval-str` call | local `(let [warnings (atom [])] …)` then handed to `(.run warnings-als …)` | the dispatcher reading `(.getStore warnings-als)` | `swap! bucket conj` from the dispatcher |
| S18 | Per-agent home-ns vars/atoms (historic) | `eval.cljs:540-546` (docstring) | None today — explicitly deleted 2026-05-25 ("kill !session-id atom") | n/a | n/a | n/a (historic) | n/a (historic) |

**Why ALS was chosen for S1/S2/S3** — quoted from `src/seon/db.cljs:464-478` verbatim (the verbatim citation is reproduced as the §12 appendix):

> "We DO NOT use a CLJS `^:dynamic` Var here, even though that's the idiomatic Clojure spelling. CLJS `binding` macroexpands to `(set! var :new)` + `(try … (finally (set! var orig)))` against ONE global slot — it survives single-binder cases but silently clobbers under overlapping awaits when two `^:async` fns each bind it… v1 supports concurrent agents in one pod, so a fiber-local primitive is required."

This is correct. The CLJS dynamic-var clobber problem is real (Probe 13 referenced in the source). What we propose below does NOT eliminate the need for fiber-local identity — it just narrows the role of ALS to **identity propagation only**, with state lookup going through the atom keyed by that identity.

### Categorization

| Kind | Sites | Disposition |
|---|---|---|
| **Substrate runtime singletons** | S4 (`*conn*`), S5 (`*log-file*`), S11 (`!state`), S13/S14/S15 (compile-state), S16 (dispatcher version), S12 (`!agent-conn` — duplicates S4) | **Stay where they are.** Not per-agent, not addressable by agent-id. Phase 1 of this PRD merely names them "substrate state" and documents the distinction. S12 and S15 are duplication candidates (see Phase 5). |
| **Per-fiber identity ALS** | S1, S2 | **Stay as ALS.** The fiber-local clobber argument from `db.cljs:464-478` is binding-correct. These shrink in **role** — S2 just answers "what's the key into `!instances`" — but the mechanism stays. |
| **Per-eval transient ALS** | S3, S17 | **Stay as ALS.** S3 is genuinely about the dynamic extent of ONE cljs.js call. There is no agent-id semantics to hang it on; the bucket lifetime is shorter than even one turn. This is correctly fiber-local context, NOT per-agent state. |
| **Process-wide config atoms** | S6 (log rotation), S7 (fs allowlist), S8/S9 (eval timeouts), S10 (deepseek timeout) | **Migrate to `!instances` with caveats** — these aren't per-agent today but the user model wants per-agent overrides (different agents may have different fs allowlists, timeout budgets). The "process-default" stays; the per-agent override layers on top. See §4 for the lookup chain. |
| **Per-agent per-session live state** | (Nothing in `!instances` today) | **The whole point of `!instances`.** Currently scattered across globalThis result-stash (`eval.cljs:512-527`, NOT migrated — see C7 below) and the agent's home-ns `(result …)` accessor. |

---

## §3 Why atoms — the case (atom vs ALS vs dynvar)

| Property | `defonce !instances (atom …)` | `AsyncLocalStorage` | `^:dynamic` Var + `binding` |
|---|---|---|---|
| (a) Survives `await` | ✅ — process-global, no notion of fiber | ✅ — explicit V8 instrumentation | ❌ — CLJS macroexpands to `set!`+`finally`; second concurrent binder clobbers (cited from `db.cljs:464-478`) |
| (b) Spec-able with Malli | ✅ — `(schema/register! :seon.agents/instances [:map-of …])` and validate on every `swap!` via a watch | ❌ — the store is whatever you put there; no boundary to validate at | 🟡 — possible but the `binding` macro evaluates body BEFORE re-binding finalizers, so validation has to be on `set!` |
| (c) CLJ ↔ CLJS portable | ✅ — identical primitive on both sides (constraint C4) | ❌ — Node-specific, sidecar JVM writer would need a different impl; the sidecar wasm guest has only a partial shim (`pod-host/sidecar-poc/guest/sidecar-agent-build/src/builtin/async_hooks.js`) | 🟡 — works both sides but the CLJS clobber hazard is real on multi-agent |
| (d) Inspectability (REPL `@!instances`) | ✅ — `pr-str` shows the whole map | ❌ — `.getStore` is only meaningful FROM A FIBER; can't dump from outside | ✅ — `@#'seon.db/*conn*` works |
| (e) Snapshottable for resume | ✅ — single `swap!`/`deref`; freeze the map | ❌ — store is per-fiber and ephemeral, no global snapshot semantics | ✅ — single var |
| (f) Concurrency in V0 single-process Node | ✅ — `swap!` is atomic in JS (single-threaded event loop); two `swap!`s on the same key serialize correctly | ✅ — designed for it | ❌ (multi-agent), ✅ (single-agent) |
| (g) Future multi-process risk | 🟡 — Phase 2/3 worker_thread or wasm-rquickjs: each Store is a separate atom; needs explicit hand-off across the boundary | 🟡 — wasm-rquickjs has only a partial ALS shim (see `async_hooks.js:5-9`: "QuickJS `await` uses internal C-level perform_promise_then and bypasses JS-visible Promise.prototype.then, so await propagation is NOT possible"). ALS in V0 won't survive the WASM cutover. | ✅ — under wasm-rquickjs each Store is one fiber, so the clobber hazard disappears and dynvars become viable again |

**Headline:** atoms win on (a)(b)(c)(d)(e) — every property except multi-agent fiber-local identity, which we keep using ALS for. The agent-id and tx-context are answers to "which fiber"; the atom is the answer to "what's the state". They're different questions.

**Future multi-process risk note (g):** the user is correct that per-pod atoms don't generalize to multi-pod. The plan (per `CLAUDE.md` Phase 3) is one wasm Store per pod with the JVM writer owning truth. Under that topology each pod has its own `!instances` representing the agents IT owns; cross-pod coordination is via the DB (the canonical pattern already). The atom is a per-process projection, the DB is the source of truth.

---

## §4 The proposed atom shape — full schema

```clojure
(ns seon.agents
  (:require [seon.schema :as schema]
            [seon.db :as db]))

;; The atom. Defonce so reloads of THIS ns don't lose runtime state.
(defonce !instances (atom {}))

;; ---- shapes ----

(schema/register! ::id     :seon.db/id)       ; same shape as :seon.agent/id
(schema/register! ::state  [:enum :booting :idle :running :paused :stopped])
(schema/register! ::booted-at :inst)

;; Per-agent overrides for substrate-level config. Absent = use the substrate
;; default. THIS is how we migrate S6/S7/S8/S10 from process-globals to
;; per-agent without breaking the single-agent-still-works case.
(schema/register! ::eval-timeout-ms      :int)         ; overrides seon.eval/!timeout-ms for THIS agent
(schema/register! ::deepseek-timeout-ms  :int)         ; overrides seon.ai.deepseek/!timeout-ms
(schema/register! ::fs-allowed-roots     [:vector :string])
(schema/register! ::fs-read-only?        :boolean)
(schema/register! ::log-source           :keyword)

;; Volatile substrate-runtime keys — known to be lost on restart by design.
;; The compile-state IS volatile by nature (cljs.js analyzer atom). Storing
;; a pointer here means inspect/debug code can reach it without grovelling
;; through globalThis.
(schema/register! ::compile-state-ref :any)        ; opaque pointer, :volatile? true documented
(schema/register! ::llm-fn            :any)        ; ctx-string -> Promise<{:text "…"}>
(schema/register! ::home-ns           :symbol)     ; `(home-ns id)` — derivable but cached for speed

(schema/register! ::instance
  [:map
   [::id                       ::id]
   [::state                    ::state]
   [::booted-at                ::booted-at]
   [::eval-timeout-ms      {:optional true} ::eval-timeout-ms]
   [::deepseek-timeout-ms  {:optional true} ::deepseek-timeout-ms]
   [::fs-allowed-roots     {:optional true} ::fs-allowed-roots]
   [::fs-read-only?        {:optional true} ::fs-read-only?]
   [::compile-state-ref    {:optional true} ::compile-state-ref]
   [::llm-fn               {:optional true} ::llm-fn]
   [::home-ns              {:optional true} ::home-ns]])

(schema/register! :seon.agents/instances
  [:map-of ::id ::instance])

;; ---- API ----

(defn get-instance
  "Return the full instance map for `agent-id`, or nil. Map-in / map-out.
   Most callers want `get-state` or `get-key`; use `get-instance` only when
   you genuinely need the whole map (debugging, inspectors)."
  {:malli/schema [:=> [:cat [:map [::id ::id]]] [:maybe ::instance]]}
  [{::keys [id]}]
  (get @!instances id))

(defn get-key
  "Return one slot's value for `agent-id`, with optional substrate-level
   fallback chain. e.g. `(get-key {::id id ::key ::eval-timeout-ms
   ::default @seon.eval/!timeout-ms})` reads the per-agent override OR
   falls back to the substrate default."
  ...)

(defn ^:async set!
  "Write `(merge instance updates)` into `!instances[agent-id]` AND
   transact a :seon.agent.state/<key> projection for every NON-VOLATILE
   key. Volatile keys are atom-only (see ::volatile-keys below)."
  ...)

(defn set-volatile!
  "Like set! but atom-only. Returns immediately. For keys whose values
   don't survive serialization (fns, compile-state pointers, Promises)."
  ...)
```

### What's IN this atom

- The 14-char agent id keying it.
- Lifecycle state (`:booting`/`:idle`/`:running`/`:paused`/`:stopped`). **Mirrors** the `:seon.agent/state` DB attr; the atom is the fast-read cache, the DB is the truth.
- Per-agent overrides of substrate config (S6/S7/S8/S10).
- Volatile runtime pointers (compile-state ref, LLM fn closure) that genuinely cannot be serialized — flagged `::volatile? true` at the schema level.

### What's NOT in this atom

This is where the three-tier storage rule (`memory/project_seon_three_tier_storage.md`) is load-bearing. **Restated for the atom case:**

| Tier | Lives | Examples |
|---|---|---|
| **DB datoms** | `seon.db` — persistent, indexed, query-able, render-able | `:seon.agent/id`, `:seon.agent/state`, `:seon.session/at`, `:seon.eval/result-edn` (truncated), `:seon.eval/ns`, `:seon.message/content`. **Everything the renderer reads.** |
| **Blobs** | `seon.blob` (planned), on-disk content-addressed | `:seon.turn/prompt-blob`, `:seon.eval/result-blob` when full content matters for replay. |
| **`!instances` atom** (NEW — was globalThis) | `seon.agents/!instances` | Per-agent runtime config, lifecycle cache, volatile fn/state references. **Snapshot-able into DB on `set!` (default) or volatile-only (opt-in).** |
| **GlobalThis stash** | `js/globalThis["__seon_results_<eval-id>"]` (`eval.cljs:512`) | Raw eval values that can't pr-str cleanly (datahike DB tagged literals, Promises, fns). **STAYS — see §11 Q1.** |

The atom does NOT replace globalThis stash, the blob layer, or the DB. It replaces (a) the scattered process-global defonce atoms (S6-S10), (b) the implicit "active agent" portion of S2 that's not just identity, and (c) any future per-agent state the substrate needs to track. The DB datoms remain the source of truth; the atom is a fast-cache + volatile-pointer drawer.

### Concurrency in V0 single-process Node

`swap!` on a CLJS atom IS atomic because Node is single-threaded; the JS event loop guarantees that no two `swap!` calls interleave their compare-and-set. Two concurrent agents writing different keys is fine. Two concurrent operations writing the SAME key for the SAME agent (extremely unlikely — one agent runs one fiber at a time per the `run-agentic-loop!` design at `agent.cljs:711-740`) is fine because `swap!` retries.

**The genuine concern** is a write-then-read race across an `await`: agent A's eval calls `(set! …)` (which awaits the DB transact); during the await, the kick handler fires for the SAME agent and reads `@!instances`. Today's V0 prevents this with the `:running` state machine guard (`agent.cljs:345-352`). The atom respects the same guard — `set!`'s atom write happens before the await, so a concurrent read sees the new value.

---

## §5 Agent init protocol

Agent IDs are generated at `client.cljs:565` (`(db/new-id!)`). The substrate writes the initial slot. Walk:

1. `seon.client/start-agent!` mints `agent-id` (~line 565).
2. **NEW step before `replay-program-graph!`:** substrate writes the initial slot:
   ```clojure
   (seon.agents/set!
     {::id id
      ::state :booting
      ::booted-at (js/Date.)
      ::home-ns (agent/home-ns id)
      ::compile-state-ref compile-state
      ::llm-fn llm-fn})
   ```
3. `replay-program-graph!` proceeds (unchanged) — its txs auto-tag with the ALS-bound `agent-id` (this part of the world doesn't change).
4. `setup-agent-ns!` (`eval.cljs:529`) currently does very little; it stays a no-op or extends to install a substrate-provided fn (NOT an atom) into the home ns that the agent uses to read its slot. See §6 for the exact shape.
5. `agent/boot!` flips state to `:idle` via `set!`.
6. The user-message-handler kicks `run-agentic-loop!`; `with-turn-body!` flips to `:running` then back to `:idle` (today this is two DB transacts at `agent.cljs:579, 597` — the atom should also update so the kick handler's guard reads the latest value without a DB round-trip).

### Where the substrate-side API for read/write lives

`seon.agents/get-instance`, `seon.agents/get-key`, `seon.agents/set!`, `seon.agents/set-volatile!` — all take agent-id explicitly via map arg `{::id …}`. They're pure functions of `(@!instances, id)`. NOT bound by ALS.

### Does the agent's eval space see the atom directly, or only through a controlled API?

**Recommendation: only through a controlled API.** The agent's home ns gets ONE fn installed at setup time:

```clojure
;; in seon.agent.<id> after setup-agent-ns!:
(defn me
  "Return your own instance map from seon.agents/!instances."
  []
  (seon.agents/get-instance {:seon.agents/id (seon.db/current-agent-id)}))
```

The agent calls `(me)` to read. The agent writes (rarely) via `(seon.agents/set! {::id (seon.db/current-agent-id) ::eval-timeout-ms 30000})`. Direct access to `seon.agents/!instances` is *technically possible* (the agent can `@seon.agents/!instances` — CLJS atoms aren't private to a namespace), but the docstring on the var and the `set!` fn make the right way obvious. See §6 for the enforcement options.

**Critical answer to the user's question:** The substrate API takes `agent-id` explicitly (not from ALS) so it's testable, REPL-callable, and verifiable. The home-ns `(me)` accessor closes over `(current-agent-id)` for ergonomics. The atom itself is not closed over; it's a top-level var.

---

## §6 The "agents must not create atoms" rule — enforcement

The user is clear: agents minting their own atoms in eval space is a resume footgun. Today the `record-eval!` path (`eval.cljs:703`) IS the resume path — every `:seon.fn` / `:seon.ns` written via detect-and-tee replays on next boot. If an agent runs `(def !my-state (atom {}))`, the `:seon.fn` for `!my-state` is written, and replay re-runs the form on resume — giving a FRESH atom with empty state. The agent's previous state is silently lost. This is the bug.

### Options

**Option A — Lint via the analyzer-tee path.** When `build-tee-entities` (`eval.cljs:648`) sees a `(def !foo (atom …))` or `(defonce !foo (atom …))` form in the source, mark the resulting `:seon.fn` entity with `:seon.fn/warning :agent-atom`. The warnings-section (`agent.cljs:1052`) surfaces "agent X has 3 atoms in its code; resume will reset them — use `(seon.agents/set! …)`". Cost: extends the tee, easy to ship.

**Option B — Provide the obvious API.** `(seon.agents/set! {::id id ::my-key value})` is the discoverable verb. `seon.agents` is the FIRST result agents see when they look for "where do I keep state across turns" — the system-section's discovery cheat-sheet (`agent.cljs:983-1005`) gets a new line: `(seon.agents/set! {::id (seon.db/current-agent-id) ::your-key …}) ; persists across pod restarts`.

**Option C — Documentation only.** Add a paragraph to the agent-facing docstrings. Cheap, but agents don't read docs by default; they pattern-match on examples.

**Option D — Analyzer-tee rewrite.** Detect `(atom …)` forms inside `(def …)` heads and rewrite to `(seon.agents/set! …)`. **Rejected.** This is the worst kind of magic. The agent will write `(def !x (atom 0))` and silently get a different semantics — its `swap!` calls will hit the wrong API. The whole point of "code as data" is that the persisted form IS the executable form; rewriting violates that.

### Recommendation

**A + B together.** Lint flags the atom-in-agent-code pattern in the warnings section so the agent sees it in its own ctx on the next turn (reactive context; vanishes when the agent removes the atom). The substrate-provided `seon.agents/set!` is the obvious correct path that shows up in the discovery cheat-sheet. Don't enforce in the analyzer (no rewriting); make the wrong path visible and the right path easier.

C alone is too weak; D is too clever.

---

## §7 Persistence + resume

This is the hard part. The user wrote: "We need to persist the atom code too somewhere or else it's not valid Clojure we are eval'ing."

### What survives a pod restart

| Thing | Persistent? | Mechanism |
|---|---|---|
| `seon.agents` namespace itself | Yes (NEW) | The `(ns seon.agents …)` + `(defonce !instances …)` forms are part of the substrate's bootstrap-load — either bundled into `out/client/main.js` at compile time (current substrate-source pattern) OR persisted as a `:seon.ns` entity that replay re-evals. Per constraint C7, this must be deterministic. **See §7.3.** |
| The `!instances` atom's VALUE | No | Lives in pod memory only. Must be reconstructed. |
| `:seon.agent/*` DB datoms | Yes | Already so today. |
| `:seon.session/*`, `:seon.turn/*`, `:seon.message/*`, `:seon.eval/*`, `:seon.fn/*`, `:seon.ns/*`, `:seon.schema/*` | Yes | Already so. |
| The agent's home-ns vars/atoms | No (and shouldn't be) | The discouragement rule (§6) closes this. |

### The reconstruction rule

**On pod boot, AFTER `replay-program-graph!` re-evals the substrate source AND any agent-defined source, the substrate reconstructs `!instances` by querying the DB:**

```clojure
(defn rebuild-instances! [conn]
  (let [agents (db/query
                 {::db/db @conn
                  ::db/query
                  '[:find ?id ?state ?booted-at
                    :where
                    [?a :seon.agent/id ?id]
                    [?a :seon.agent/state ?state]
                    ...]})]
    (reset! !instances
            (into {} (for [[id state booted-at] agents]
                       [id #::{:id id :state state :booted-at booted-at
                               :home-ns (home-ns id)
                               ;; Volatile keys reseeded by per-agent
                               ;; init AFTER rebuild — compile-state,
                               ;; llm-fn, etc.
                               }])))))
```

Then the boot continues: `start-agent!` for each agent calls `(set-volatile! {::id ::compile-state-ref … ::llm-fn …})` to fill in the volatile pointers.

### The single API rule

**Recommendation:** ONE `set!` fn that takes a flag.

```clojure
(seon.agents/set! {::id id ::eval-timeout-ms 30000})           ;; persistent — also writes :seon.agent.config/eval-timeout-ms datom
(seon.agents/set! {::id id ::compile-state-ref cs
                   ::volatile? true})                           ;; atom-only
```

The `::volatile? true` flag means "do not transact a projection". Default is non-volatile (persistent). Each key in the instance schema has a marker in its `register!` properties indicating whether it CAN be persistent (`{::serializable? true}`) — keys like `::compile-state-ref` and `::llm-fn` are flagged `{::serializable? false}` so attempting to persist them fails with a clear "not serializable" error, not silently.

### Projection schema

For each persistable instance key, the substrate registers a corresponding DB attribute under `:seon.agent.config/*`. Example:

```clojure
(schema/register! :seon.agent.config/eval-timeout-ms :int)
(schema/register! :seon.agent.config/fs-read-only?   :boolean)
;; ... one-to-one with the instance keys that are :serializable? true
```

`set!` writes `{:seon.agent/id id, :seon.agent.config/eval-timeout-ms 30000}` to the DB AND updates the atom. `rebuild-instances!` reads them back. The reactive-context pattern from `CLAUDE.md` is preserved — the DB stays the source of truth, the atom is the fast path.

### Bootstrapping the `seon.agents` ns itself (C7)

Two options:

**Option 1: Substrate-bundled.** `seon.agents` lives in `src/seon/agents.cljs`, gets compiled into `out/client/main.js`, loads at pod boot before any `:seon.ns` replay. This is what happens to every other `seon.*` namespace today. The atom is ALREADY a top-level `defonce` so its definition is part of the compiled bundle.

**Option 2: As a `:seon.ns` entity in the DB.** The first time the substrate boots into a fresh DB, it writes `:seon.ns/source` entities for every substrate namespace including `seon.agents`. On second boot, `replay-program-graph!` re-evals them. This is the "code as data" extreme.

**Recommendation: Option 1 for now.** Option 2 is the eventual end state per `CLAUDE.md` "Code as data — the runtime IS the database", but applying it to `seon.agents` specifically before the rest of the substrate has migrated would invert the load order. `seon.agents` carries no agent-specific code; it's substrate. Until ALL substrate code lives in the DB (which is a separate, much larger migration), `seon.agents` belongs in the bundle. The atom value's persistence (via per-key projections) is what the user's constraint actually requires; the var's definition is a substrate concern, not an agent-state concern.

This satisfies C7: "valid Clojure we're eval'ing" because `seon.agents/set!`, `seon.agents/!instances`, etc. are resolvable substrate vars by the time any agent eval runs.

---

## §8 Refactor plan — every ALS / atom / dynvar site, the replacement

Walking §2 in order:

| # | Site | Verdict | Replacement |
|---|---|---|---|
| S1 | `als-instance` (tx-context) | **Keep as ALS.** Genuinely fiber-local — tx-meta merges into transact opts during one form's `await` chain. Not state, context. No change. |
| S2 | `agent-id-als` | **Keep as ALS.** The fiber-local identity propagation is correct and the V0 reasoning (`db.cljs:464-478`) holds. **Narrow its role**: it's now ONLY the lookup key into `!instances`, not a state store. Today it already only stores the id string. No code change; conceptual relabel. |
| S3 | `warnings-als` | **Keep as ALS.** Per-eval transient bucket; no agent-id semantics. No change. |
| S4 | `*conn*` | **Keep as dynvar.** One conn per pod; not per-agent. Optionally move the resolved conn into `!instances[id][::conn-ref]` for inspector ergonomics — pure caching, the dynvar stays authoritative. |
| S5 | `*log-file*` | **Keep as dynvar.** Single substrate path. |
| S6 | `seon.log/!config` | **Stays as a process-global atom for the SUBSTRATE default.** Per-agent override lives in `!instances[id][::log-config]` (new key). Readers consult per-agent first, substrate default second. Same pattern for S7/S8/S10. |
| S7 | `seon.fs/!config` | **Same as S6.** `:seon.fs/configure!` becomes the SUBSTRATE-default setter; per-agent override is `(set! {::id id ::fs-allowed-roots […] ::fs-read-only? false})`. |
| S8 | `seon.eval/!timeout-ms` | **Same.** `set-timeout-ms!` becomes the substrate-default setter. Per-agent override is `(set! {::id id ::eval-timeout-ms 30000})`. `race-timeout` reads per-agent first. |
| S9 | `seon.eval/!next-budget-ms` | **Move into the per-agent slot OR keep as substrate-global.** Today it's a side-channel one-shot. **Recommendation:** keep it where it is — it's per-FORM, not per-agent. Two concurrent agents shouldn't race on it (the form they each call has the budget set right before the await; the await happens-immediately-after), but if they DO, this becomes a bug we'd hit eventually. **Open question Q3 (see §11).** |
| S10 | `seon.ai.deepseek/!timeout-ms` | **Same as S8.** |
| S11 | `seon.client/!state` | **Keep as-is.** Boot metadata, no agent-id. |
| S12 | `seon.client/!agent-conn` | **DELETE.** Duplicates S4. The "is the conn already opened" check in `start-agent!` should read `db/*conn*` directly. (`client.cljs:543`.) |
| S13 | `seon.repl/!compile-state` | **Keep, with a cache key in `!instances`.** The atom-of-atom is a real thing because hot-reload rotates the inner atom. Optionally write `::compile-state-ref` into each instance so inspectors don't grovel through globalThis. |
| S14 | `seon.repl/!init-version` | **Keep.** Coupled to S13. |
| S15 | `seon.repl/!conn` | **DELETE.** Duplicates S4 and S12. Audit `dev-init!` usage. |
| S16 | `seon.eval/!warning-dispatcher-version` | **Keep.** Hot-reload bookkeeping. |
| S17 | Per-eval `warnings` bucket atom | **Keep.** Lifetime is one cljs.js call. |
| S18 | Per-agent home-ns vars/atoms (historic) | **NOTHING TO DO.** Already deleted 2026-05-25 — only the docstring at `eval.cljs:32-34` mentions them. The `(me)` fn (§5) is what we propose in their place. |

### Honest acknowledgement: nothing big moves

The single biggest finding of this audit: **almost nothing ACTUALLY moves to the atom.** The ALS uses are correct for what they do. The dynvars are correct. The "scattered defonce atoms" are mostly substrate-global config that has no per-agent semantics today.

What `!instances` adds is:

1. **A typed home for future per-agent overrides** (S6-S10's per-agent variants). Without this, the next per-agent feature would add ANOTHER defonce atom.
2. **A consolidated lifecycle store** so the kick handler's `:running` guard can read from one place without a DB round-trip. (Performance is a non-issue at V0 datom counts, but the architectural clarity matters.)
3. **A discouragement target.** Once `seon.agents/set!` is the obvious way to keep per-agent state, the "agents creating atoms" anti-pattern has a named alternative.
4. **A migration target for the globalThis result-stash (`eval.cljs:512-527`).** Today raw eval results live at `js/globalThis["__seon_results_<eval-id>"]`. This is a per-eval scratchpad and probably should NOT move to `!instances` (it's per-eval, not per-agent), but it's the closest analog to "per-agent volatile state". See §11 Q1.

---

## §9 CLJ / sidecar JVM compat

The sidecar JVM writer at `pod-host/sidecar-poc/jvm-writer/src/seon/sidecar/writer.clj:27` already uses `(defonce ^:private state (atom nil))`. Same primitive, same semantics — `swap!` on the JVM is CAS-based and thread-safe; on Node it's atomic-because-single-threaded. The schema validates the same way (Malli is CLJC at `seon.schema`).

The sidecar overlay (`pod-host/sidecar-poc/guest-cljs/src-overlay/seon/db.cljs`) currently uses `^:dynamic` Vars for the per-agent ALS (see overlay survey lines 228-273, the `5a82742` drift). Under wasm-rquickjs the dynvar approach works (one Store = one fiber); on V0 Node it does not. The atom is correct on BOTH:

- V0 Node: atom is process-global, ALS-bound `agent-id` is the key.
- Sidecar wasm-rquickjs: atom is per-Store, but each Store also IS one fiber, so ALS-bound `agent-id` is constant for the Store's lifetime.
- Sidecar JVM writer: a different process; doesn't own `!instances` directly — it owns the DB. If the writer needs per-agent state of its own (today: nothing), it gets its own atom.

**Confirm:** the proposed atom is byte-identical CLJ ↔ CLJS. The Malli schema is CLJC. The persistent-key projections route through `seon.db/transact!` which is already CLJ/CLJS-compatible via the bridge.

**Platform-specific gotchas:** 
- The volatile-key tags (`::compile-state-ref` storing a cljs.js analyzer atom) are CLJS-only. The JVM writer would never have those keys; harmless. 
- The `::booted-at` is a `:inst`, which is `java.util.Date` on JVM and `js/Date` on CLJS — already handled by `seon.schema` and the Malli bridge.

---

## §10 Migration phases

Each phase ships value standalone. Verify before starting the next.

### Phase 0 — Land the namespace + atom (no behavior change)

- Create `src/seon/agents.cljs` (and `agents.cljc` if portable shape allows — likely yes since `defonce (atom {})` and Malli schemas are pure).
- Register all `:seon.agents/*` schemas.
- Define `get-instance`, `get-key`, `set!`, `set-volatile!`, `rebuild-instances!` as no-op-correct functions.
- Wire `set!` to also transact `:seon.agent.config/*` projections for serializable keys.
- Add `seon.agents` to `seon.client`'s require list so it compiles into the bundle.
- **Verification:** `(seon.agents/get-instance {::id "fake"})` returns nil. `(seon.agents/set! {::id "fake" ::state :idle})` writes the atom AND a `:seon.agent.config/state` datom. Restart pod — `rebuild-instances!` reconstructs the map. No agent code changed.

### Phase 1 — Wire `start-agent!` to seed the slot

- In `start-agent!`, after `agent-id` is minted, call `(set-volatile! {::id agent-id ::state :booting ::booted-at … ::compile-state-ref … ::llm-fn …})` BEFORE `replay-program-graph!`.
- Hook the kick handler's `:running` check (`agent.cljs:345-352`) to read from `(get-key {::id id ::key ::state})` first, fall back to DB. (Optional this phase; the DB read still works.)
- **Verification:** Boot the V0 pod, send a chat, observe `@seon.agents/!instances` contains the agent's slot with `::state :idle` after boot. No regressions in the turn loop.

### Phase 2 — Migrate the discoverable agent-facing API

- Add `seon.agents/me` to the agent's home ns via `setup-agent-ns!` (`eval.cljs:529`).
- Add `(seon.agents/set! …)` and `(seon.agents/me)` to the system-section's discovery cheat-sheet (`agent.cljs:983-1005`).
- **Verification:** From the agent REPL, `(seon.agents/me)` returns the agent's instance map. Document in the agent-facing concepts file.

### Phase 3 — Move per-agent config overrides

- Add the per-agent override read chain to `seon.eval/race-timeout` (reads `(get-key {::id (current-agent-id) ::key ::eval-timeout-ms ::default @!timeout-ms})`).
- Same for `seon.ai.deepseek` HTTP timeout and `seon.fs` allowlist.
- **Verification:** An agent sets `(seon.agents/set! {::id (seon.db/current-agent-id) ::eval-timeout-ms 30000})`; subsequent slow form uses 30s timeout. Restart pod; the override persists via the `:seon.agent.config/eval-timeout-ms` datom.

### Phase 4 — Add the agent-atom warning

- Extend `build-tee-entities` (`eval.cljs:648`) to detect `(def !foo (atom …))` / `(defonce !foo (atom …))` and tag the `:seon.fn` entity with `:seon.fn/warning :agent-atom`.
- Extend `warnings-section` (`agent.cljs:1052`) with a new query that surfaces these.
- **Verification:** An agent runs `(def !foo (atom 0))`. Next turn's ctx contains "1 atom in your code; resume will reset — use (seon.agents/set! …) instead". Agent removes the atom and the warning vanishes.

### Phase 5 — Delete duplicate state slots (S12, S15)

- Delete `seon.client/!agent-conn`; replace usage with `(when (some? db/*conn*) db/*conn*)`.
- Audit `seon.repl/!conn`; delete if dev-init can route through `db/*conn*`.
- **Verification:** No regression; `(user/reload)` cycle still works.

### Phase 6 — Sidecar overlay sync (NOT in the V0 critical path)

- Update `pod-host/sidecar-poc/guest-cljs/src-overlay/` to ship its own `seon.agents` overlay (or share the V0 one if the bundle includes it).
- Resolve the AsyncLocalStorage drift documented at `bench/v0-port-survey.md:228+` by replacing the overlay's `^:dynamic` `*agent-id*` with a stamp-into-atom pattern (since wasm-rquickjs is one fiber per Store, the atom alone is sufficient — no fiber-local primitive needed).

Phases 0-5 are V0-pod-only. Phase 6 is sidecar-track work; it doesn't gate the V0 phases.

---

## §11 Risks + open questions

### Open questions

**Q1 — Result stash on globalThis (`eval.cljs:512-527`).** Today raw eval results land at `js/globalThis["__seon_results_<eval-id>"]`, with the agent's `(result id)` accessor reading them via `js/Reflect.get`. Should this migrate into `!instances[id][::results]`?
- **Pro:** consolidates "per-agent volatile" into one place; inspectability via `(get-instance …)`.
- **Con:** today it's keyed by eval-id (not agent-id), so multiple agents' results never collide. Moving to `!instances[id]` would still need the eval-id inner key, and `(result …)` ergonomics get worse (current: `(result :Kpx-2605232138)`; proposed: `(seon.agents/result :Kpx-2605232138)` which needs to walk `(current-agent-id)` first).
- **Recommendation:** **DON'T migrate.** Keep globalThis stash where it is. The atom is for per-agent state; eval results are per-eval-id state and the globalThis pattern works. Update the docstring on `seon.eval/stash-result-raw!` to point at `seon.agents` for the per-agent case.
- `@user` confirm or override?

**Q2 — `set!` synchrony.** The proposed `seon.agents/set!` is `^:async` because it transacts. But the atom write happens BEFORE the transact awaits, so callers that don't care about persistence can fire-and-forget. Should we ship TWO functions (`set!` sync atom-only, `persist!` async)?
- **Recommendation:** **One `set!` that always returns a Promise** but completes the atom write synchronously. Agent code calling `(seon.agents/set! …)` from inside a form reads back the new value immediately (it's in the atom); the awaited Promise resolves after the DB write lands. This matches the pattern in `seon.db/transact!`.
- `@user` confirm.

**Q3 — `!next-budget-ms` cross-agent leak.** Today `(seon.eval/budget 60000 …)` reset the one-shot atom; the next form's `maybe-await-value` consumes it. If two agents are interleaved (which shouldn't happen under `:running` state machine guards, but defensive), agent A's budget could leak to agent B. **Should the budget move into `!instances[id]::next-budget-ms`?**
- **Recommendation:** **Yes, eventually** — but it's a corner case and not Phase 1 critical. Note it for Phase 3.
- `@user` ack.

### Risks

**R1 — The atom and the DB drift.** `set!` writes both, but if the DB transact fails, the atom is already updated. **Mitigation:** roll back the atom on transact failure; document the error envelope shape `{::ok? false ::error … ::atom-reverted? true}`.

**R2 — Resume's `rebuild-instances!` runs against a stale DB if replay hasn't finished.** Order matters: `rebuild-instances!` must run AFTER `replay-program-graph!` so the queries see the latest `:seon.agent.config/*` projections. Document in `start-agent!`'s sequence.

**R3 — Multi-agent v1 stress.** The fiber-local clobber argument from `db.cljs:464-478` still applies to `with-agent` / `with-tx-context`. We are NOT removing ALS; we're using it as a lookup key into the atom. The risk reduces to "does the V0 pod ever actually run two agents concurrently?" — today it doesn't (single auto-boot agent), but the architecture supports it. The atom is fine; the ALS reasoning stays correct.

**R4 — Sidecar drift.** The overlay (`pod-host/sidecar-poc/guest-cljs/src-overlay/`) silently drifts every time V0 changes its state mechanism. Phase 6 of this migration would CLOSE the drift, but until it ships, the overlay continues to use whatever pattern was current at last sync. Phase 6 isn't blocking V0; it's a separate track.

**R5 — Atom-creation lint false positives.** Phase 4's warning fires on any `(atom …)` form in `def`/`defonce`. But what about `(defn f [] (atom …))` (an atom created inside a fn, used as a closure-local mutable cell)? That's fine and shouldn't warn. The lint must be precise — only flag top-level `def`/`defonce` whose RHS is `(atom …)` or `(swap!/reset!-touched cell)`. Get this wrong and the warning becomes noise.

### Things I couldn't resolve

- Whether the `seon.agents` namespace should be `.cljs` or `.cljc`. Likely `.cljc` since the atom + schemas are platform-portable; the volatile-key types like `::compile-state-ref` are `:any` which avoids the platform issue. **Tentative:** `.cljc`.
- Whether `set!` should fire a tx-listener on `!instances` updates so other parts of the system can react. Today the listener pattern is on `seon.db`, not on atoms. Probably YES (long-term), NO (Phase 0-5). Defer.

---

## §12 Appendix — V0 ALS rationale (verbatim citation)

From `src/seon/db.cljs:451-478` (the comment block above `als-instance`'s defonce). Preserved here because the user said: "we had specific reasons" and any future agent that wants to revisit this should be unambiguously aware of the original argument.

> ```
> ;; *tx-context* — the causality bundle for every tx in an eval scope.
> ;;
> ;; v1.md §2.3 establishes the contract:
> ;;   - `eval-batch!` enters a `(with-tx-context {…} f)` scope around each
> ;;     form's per-form work.
> ;;   - `transact!` reads `(current-tx-context)` and deep-merges into
> ;;     `:tx-meta`. Explicit `opts.tx-meta` wins per-key.
> ;;
> ;; We DO NOT use a CLJS `^:dynamic` Var here, even though that's the
> ;; idiomatic Clojure spelling. CLJS `binding` macroexpands to
> ;; `(set! var :new)` + `(try … (finally (set! var orig)))` against ONE
> ;; global slot — it survives single-binder cases but silently clobbers
> ;; under overlapping awaits when two `^:async` fns each bind it (see
> ;; `research/impl-finding-tx-context-promise-2026-05-22.md` Probe 13).
> ;; v1 supports concurrent agents in one pod, so a fiber-local primitive
> ;; is required.
> ;;
> ;; `node:async_hooks/AsyncLocalStorage` IS fiber-local: V8 instruments
> ;; the async context propagation at the engine level so a `.run`-scoped
> ;; store survives across any `await`s (real timers, microtasks,
> ;; rejections, nested ^:async calls) AND does not interfere with
> ;; concurrent `.run`s in other fibers. Probe 14 verified this under the
> ;; same adversarial interleaving that broke Probe 13.
> ;;
> ;; The require is at top-level so a pod missing `node:async_hooks`
> ;; fails loudly at ns load instead of silently at first transact.
> ;; Phase 3 (WASM cutover) needs an ALS-equivalent in wasm-rquickjs;
> ;; tracked as a separate D13-WASM prerequisite.
> ```

The agent-id ALS (added in commit `5a82742`, 2026-05-24) extends this reasoning to a SECOND ALS instance because non-DB code paths (inspectors, section fns, web handlers) also need fiber-local agent-id access without depending on tx-context. The same Probe 13/14 argument applies.

**This PRD does NOT remove either ALS.** It re-roles `agent-id-als` as the lookup key into `!instances`, where the actual per-agent state lives. The fiber-local primitive stays; what changes is what the substrate stores.

End of PRD.
