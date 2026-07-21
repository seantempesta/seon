---
type: prd
status: draft
tags: [prd, agent, architecture]
---

# Per-Agent Runtime State via Dynvar-Held Atom

**Author:** research agent (PRD draft, no implementation)
**Date:** 2026-05-26 (revised 2026-05-27)
**Branch:** `feature/agent-runtime`
**Supersedes (partially):** the AsyncLocalStorage + ad-hoc dynvar arrangement documented at `src/seon/db.cljs:421-440` and the per-namespace `defonce !config` atoms scattered across the substrate.
**Status:** DRAFT — pending Phase 0 sign-off. Reduced open-question set in §11.

---

## Revision log

- **2026-05-27** — user decisions on Q1–Q4 reshaped the design. Single
  shared atom (`!instances`) replaced with **per-agent atom held in a
  `^:dynamic` Var** (`seon.agents/*ctx*`). Bespoke API (`set!`/`get!`/
  `persist!`) deleted in favor of standard `swap!`/`assoc-in`/`reset!`.
  ALS remains a substrate-internal implementation detail for
  await-survival but is no longer user-facing. Init protocol formalized
  as "walk codebase on cold boot, persist code-as-data, resume from DB
  after." Eval result stash stays on `globalThis` (Q1 confirmed).
- **2026-05-26** — initial draft (atom-keyed-by-agent-id, bespoke `set!`/`get!` API). Sections retained where still factual; rewritten where the new design supersedes them.

---

## TL;DR

V0 grew **two ALS instances** (`als-instance` for tx-context, `agent-id-als` for the active agent — `src/seon/db.cljs:481, 516`), **one ALS for analyzer warnings** (`warnings-als` — `src/seon/eval.cljs:205`), **two `^:dynamic` Vars** (`*conn*` — `src/seon/db.cljs:439`, `*log-file*` — `src/seon/log.cljs:178`), and **roughly a dozen `defonce !foo (atom …)`** sites. The result: "the runtime knows about the active agent" has at least four mechanical answers depending on which file you're in.

The locked design: **one well-known dynvar, `seon.agents/*ctx*`, holding a per-agent atom — exactly parallel to `seon.db/*conn*`.** The Var is the fixed name; the atom is the per-agent value; the substrate binds the Var around every entry into agent code. Users get one primitive (a Var holding an atom). They manipulate it with standard Clojure (`swap!`, `assoc-in`, `update-in`, `reset!`, `@`). No bespoke API, no shared registry, no per-agent dispatch.

ALS does not disappear — Node has no other way to make a `binding` survive `await` — but it **moves inside the substrate**. The user never sees ALS; the substrate uses it internally to keep `*ctx*` bound across async hops. Two-layer simplicity outside; one implementation detail inside.

Init protocol: on cold boot the substrate **walks its own source tree and persists `:seon.fn`/`:seon.ns`/`:seon.schema` entities** for every namespace it finds (`CLAUDE.md` "Code as data — the runtime IS the database"). On warm boot it resumes from those entities. The atom itself is purely volatile per-process state — nothing in it needs to survive a restart, because anything that matters lives in the DB.

---

## §1 Goals, non-goals, load-bearing constraints

### Goals

1. **One mechanism** for per-agent runtime state: a single dynvar pointing at a per-agent atom. New per-agent state is a new key in that atom's value, not a new defonce.
2. **Standard Clojure ops only.** `swap!`/`assoc-in`/`update-in`/`reset!`/`@`. No `get!`/`set!`/`persist!`.
3. **CLJ ↔ CLJS portable.** Atom + dynvar semantics are identical. JVM gets fiber-local-via-dynvar-binding for free; CLJS uses ALS internally to extend `binding` across `await`.
4. **Per-agent atoms, never shared.** N agents in one pod = N atoms. The Var binding selects which one is "this agent's atom" for the current call.
5. **Substrate-internal use only.** `*ctx*` is for runtime-specific special state (compile-state pointers, LLM closures, per-agent timeout overrides) — parallel to `seon.db/*conn*`. Persistent state belongs in the DB. Most code should never read or write `*ctx*`.
6. **Code-as-data init protocol.** Cold pod boot walks the seon codebase and persists code entities to the DB; warm boot resumes from those entities; per-agent atoms get reseeded from defaults + DB-persisted overrides at agent boot.

### Non-goals

1. **NOT removing `seon.db/*conn*`.** It's the mental model `*ctx*` follows. Both stay.
2. **NOT removing tx-context ALS (S1).** `with-tx-context` answers a different question — "what causality bundle does this tx belong to within ONE form's `await` chain". Lives one stack frame inside an agent fiber, not a candidate for `*ctx*`.
3. **NOT a security boundary.** Agent eval can `(swap! seon.agents/*ctx* …)` whatever it likes. Catches LLM hallucinations, not adversarial code. WASM containment is Phase 3 per `CLAUDE.md`.
4. **NOT moving the eval result stash off globalThis** (Q1 confirmed). Stash stays at `js/globalThis["__seon_results_<eval-id>"]` keyed by eval-id; `(result …)` accessor unchanged.

### Load-bearing constraints (from the user, locked)

| # | Constraint |
|---|---|
| C1 | Agent IDs are generated at boot (`client.cljs:565`). Per-agent atom is created at runtime, not at compile time. |
| C2 | **One fixed symbol — `seon.agents/*ctx*` — a `^:dynamic` Var.** The Var is the well-known location; its value is the per-agent atom. Mental model exactly parallel to `seon.db/*conn*`. |
| C3 | **Per-agent atom, never shared.** Multiple agents in one pod = multiple atoms. The dynvar binding selects this agent's atom. |
| C4 | **No bespoke API.** Standard Clojure: `swap!`, `assoc-in`, `update-in`, `reset!`, `@`. No `set!`, `get!`, `persist!`. |
| C5 | **ALS dies as a user surface.** May remain a substrate-internal detail (Node ALS to make `binding` survive `await`); users never see it. |
| C6 | **Most code shouldn't touch `*ctx*`.** It's runtime-specific special state. Persistent state goes in the DB. Substrate-internal use only; agent code should reach for it only for the same kind of reasons code reaches for `*conn*`. |
| C7 | **Init protocol is walk-and-persist.** Cold boot: substrate walks its own source, transacts `:seon.fn`/`:seon.ns`/`:seon.schema` entities for everything. Warm boot: resume from those entities. Per `CLAUDE.md` "Code as data". |
| C8 | **CLJ-compat.** Same primitive both sides. JVM `binding` already conveys across thread switches in Clojure; the CLJS extension is the ALS wrapper. Sidecar JVM writer uses bare `binding`. |

---

## §2 Current state — every per-agent / per-fiber state site

This inventory is unchanged from the prior draft; it documents what exists today regardless of design. Most of these stay where they are. The §8 refactor plan walks each one and assigns a verdict under the locked design.

| # | Site | File:line | What it stores | Lifetime | Mechanism |
|---|---|---|---|---|---|
| S1 | `als-instance` (tx-context) | `db.cljs:481-483` | Causality bundle: `:seon.db/{agent-id,session-id,turn-id,eval-id,origin,replay?,resume-marker?}` | Dynamic extent of `with-tx-context` body, survives `await` | Node ALS |
| S2 | `agent-id-als` | `db.cljs:516-518` | `<agent-id>` string | Dynamic extent of `with-agent` body, survives `await` | Node ALS |
| S3 | `warnings-als` | `eval.cljs:205-207` | Per-form analyzer warning bucket | One `cljs.js/eval-str` call | Node ALS |
| S4 | `*conn*` | `db.cljs:439` | Single datahike connection | Process lifetime | `^:dynamic` Var |
| S5 | `*log-file*` | `log.cljs:178` | Log file path | Process lifetime | `^:dynamic` Var |
| S6 | `seon.log/!config` | `log.cljs:186` | Rotation policy | Process lifetime | `defonce` atom |
| S7 | `seon.fs/!config` | `fs.cljs:183` | FS allowlist | Process lifetime | `defonce` atom |
| S8 | `seon.eval/!timeout-ms` | `eval.cljs:66` | Per-form timeout | Process lifetime | `defonce` atom |
| S9 | `seon.eval/!next-budget-ms` | `eval.cljs:76` | One-shot timeout override | Until consumed | `defonce` atom |
| S10 | `seon.ai.deepseek/!timeout-ms` | `deepseek.cljs:59` | LLM HTTP timeout | Process lifetime | `defonce` atom |
| S11 | `seon.client/!state` | `client.cljs:108-111` | Boot metadata | Process lifetime | `defonce` atom |
| S12 | `seon.client/!agent-conn` | `client.cljs:226` | Mirror of `*conn*` | Process lifetime | `defonce` atom — DELETE candidate |
| S13 | `seon.repl/!compile-state` | `repl.cljs:76` | cljs.js compile-state atom-of-atom | Process lifetime; rotated on `seon.eval` reload | `defonce` atom |
| S14 | `seon.repl/!init-version` | `repl.cljs:83` | Compile-state version stamp | Process lifetime | `defonce` atom |
| S15 | `seon.repl/!conn` | `repl.cljs:85` | Another conn mirror | Process lifetime | `defonce` atom — DELETE candidate |
| S16 | `seon.eval/!warning-dispatcher-version` | `eval.cljs:213` | Hot-reload version | Process lifetime | `defonce` atom |
| S17 | Per-eval `warnings` bucket | `eval.cljs:409` | `(atom [])` | One cljs.js call | local atom |
| S18 | Per-agent home-ns vars/atoms | `eval.cljs:540-546` (docstring) | None today — deleted 2026-05-25 | n/a | n/a |

**Why ALS was chosen for S1/S2/S3** — quoted from `src/seon/db.cljs:464-478` (full verbatim citation in §12):

> "We DO NOT use a CLJS `^:dynamic` Var here ... CLJS `binding` macroexpands to `(set! var :new)` + `(try … (finally (set! var orig)))` against ONE global slot — it survives single-binder cases but silently clobbers under overlapping awaits when two `^:async` fns each bind it ... v1 supports concurrent agents in one pod, so a fiber-local primitive is required."

This argument **still holds**, and explains exactly why the new design needs ALS internally to make `*ctx*` bindings survive `await`. The argument is not against dynvars — it's against naked dynvars without ALS extension on CLJS. The locked design pairs them.

---

## §3 Why a Var-holding-an-atom — the case (and where ALS lives)

| Property | `seon.agents/*ctx*` (Var holding per-agent atom) | Shared `!instances` atom (rejected) | Bare ALS as user surface (rejected) |
|---|---|---|---|
| Standard Clojure manipulation | ✅ — `(swap! *ctx* assoc-in [:k] v)`, `@*ctx*` | ✅ | ❌ — `(.run als …)` / `(.getStore als)` are Node-specific verbs |
| One named location | ✅ — fixed symbol `seon.agents/*ctx*` | ✅ | 🟡 — Node-only object, no Clojure naming convention |
| Per-agent isolation | ✅ — each agent has its OWN atom; binding selects | ❌ — single map keyed by id; one bad `reset!` wipes all agents | ✅ — `.run` scope is fiber-local |
| Survives `await` (CLJS) | ✅ — substrate-internal ALS wrapper restores binding | n/a (no binding) | ✅ |
| Survives across threads (JVM sidecar) | ✅ — Clojure `binding` conveys naturally | ✅ | 🟡 — needs different primitive (`InheritableThreadLocal`-equivalent) |
| Spec-able with Malli | ✅ — atom watch validates against `::ctx-value` | ✅ | ❌ |
| Inspectable | ✅ — `@*ctx*` from REPL within the binding scope; substrate inspector can enumerate all per-agent atoms | ✅ | ❌ — `.getStore` only meaningful from inside `.run` |
| Mental model | Same as `seon.db/*conn*` | New pattern, agent-keyed registry | New pattern, fiber-stack primitive |
| Substrate complexity | One Var + one wrapper fn | One atom + dispatch helpers | Pervasive `.run`/`.getStore` plumbing |

**Headline:** the dynvar-held-atom pattern matches `seon.db/*conn*` exactly (which the user singled out as the canonical good example). It gives users one primitive — a Var they can `@` and an atom they can `swap!` — with no new vocabulary. The per-agent isolation comes for free from `binding`. The await-survival problem is solved exactly once, inside the substrate's wrapper, and never resurfaces.

### Where ALS lives in this design

ALS is not gone. It is **substrate-internal plumbing** that exists for exactly one reason: on Node, `binding` does not survive `await`. The substrate wraps every entry into agent code (`eval-batch!`, callback dispatch, timer fires, message handlers) in a function that uses Node's `AsyncLocalStorage.run(...)` so the `binding` is reattached on every async hop. The agent never calls `.run`, never sees the ALS object, never imports `node:async_hooks`. From the agent's side: `*ctx*` is just bound, period.

On JVM (sidecar writer), this wrapper is a no-op — Clojure `binding` already conveys across thread switches via the framework. Same `*ctx*` symbol; different (or absent) plumbing.

### Future multi-process risk

Per-pod atoms don't generalize across pods. The plan (`CLAUDE.md` Phase 3) is one wasm Store per pod with the JVM writer owning truth. Each pod owns the atoms for the agents IT hosts; cross-pod coordination is via the DB. Same architectural pattern V0 already commits to.

---

## §4 The atom shape — `*ctx*` and its value

```clojure
(ns seon.agents
  (:require [seon.schema :as schema]
            [seon.db :as db]))

;; THE fixed symbol. Always seon.agents/*ctx*. Binding selects per-agent.
(def ^:dynamic *ctx*
  "Current agent's per-runtime state atom. The substrate binds this to
   a fresh atom at agent boot, then re-binds it via Node ALS internally
   around every entry into agent code so the binding survives `await`.

   Inside agent code, dereferences to the agent's OWN atom — never
   another agent's. Mental model: parallel to `seon.db/*conn*`.

   This is runtime-specific special state, NOT app data. Persistent
   state belongs in the DB. Most code should never read or write this
   var. Use it for the same kind of reasons you'd reach for `*conn*`:
   pod-level runtime knobs, opaque-pointer pass-throughs, per-agent
   overrides of substrate defaults."
  nil)

;; ---- shape of the atom's value ----

(schema/register! ::id     :seon.db/id)
(schema/register! ::state  [:enum :booting :idle :running :paused :stopped])
(schema/register! ::booted-at :inst)

;; Per-agent overrides of substrate defaults (migrated from S6-S10 process-globals).
;; Absent = use substrate default.
(schema/register! ::eval-timeout-ms      :int)
(schema/register! ::deepseek-timeout-ms  :int)
(schema/register! ::fs-allowed-roots     [:vector :string])
(schema/register! ::fs-read-only?        :boolean)
(schema/register! ::next-budget-ms       :int)        ; was seon.eval/!next-budget-ms

;; Volatile runtime pointers — by-design unserializable.
(schema/register! ::compile-state-ref :any)
(schema/register! ::llm-fn            :any)
(schema/register! ::log-file          :string)
(schema/register! ::eval-results-stash-prefix :string)   ; globalThis key prefix (see Q-name in §11)

(schema/register! ::ctx-value
  [:map
   [::id                       ::id]
   [::state                    ::state]
   [::booted-at                ::booted-at]
   [::eval-timeout-ms      {:optional true} ::eval-timeout-ms]
   [::deepseek-timeout-ms  {:optional true} ::deepseek-timeout-ms]
   [::fs-allowed-roots     {:optional true} ::fs-allowed-roots]
   [::fs-read-only?        {:optional true} ::fs-read-only?]
   [::next-budget-ms       {:optional true} ::next-budget-ms]
   [::compile-state-ref    {:optional true} ::compile-state-ref]
   [::llm-fn               {:optional true} ::llm-fn]
   [::log-file             {:optional true} ::log-file]
   [::eval-results-stash-prefix {:optional true} ::eval-results-stash-prefix]])

```

### Usage — standard Clojure only

```clojure
;; Inside agent code (or substrate code), with *ctx* bound:

@*ctx*                                                ;; whole value
(:seon.agents/state @*ctx*)                           ;; one key
(get-in @*ctx* [:seon.agents/fs-allowed-roots])

(swap! *ctx* assoc :seon.agents/eval-timeout-ms 30000)
(swap! *ctx* update :seon.agents/state (constantly :running))
(swap! *ctx* assoc-in [:seon.agents/fs-allowed-roots] ["/tmp/agent-x"])

;; Substrate-internal lookups follow the same pattern, e.g. inside
;; `seon.eval/race-timeout`:
(or (:seon.agents/eval-timeout-ms @*ctx*)
    @seon.eval/!timeout-ms)

```

No `get!`, no `set!`, no `persist!`. The Var IS the API. The atom IS the API.

### What's IN the atom

- The 14-char agent id (`::id`) — convenience copy for inspectors; the actual identity comes from the binding scope, not the atom contents.
- Lifecycle state (`::state`) — read cache; DB is authoritative for queries.
- Per-agent overrides of substrate defaults (S6/S7/S8/S9/S10).
- Volatile runtime pointers (compile-state, LLM fn, log file path) that can't serialize.

### What's NOT in the atom

The three-tier rule (`memory/project_seon_three_tier_storage.md`):

| Tier | Lives | Examples |
|---|---|---|
| **DB datoms** | `seon.db` — persistent, query-able, renderable | `:seon.agent/id`, `:seon.agent/state`, `:seon.session/*`, `:seon.eval/result-edn`, `:seon.fn/*`, `:seon.ns/*`. **Everything the renderer reads. Everything that needs to survive a restart.** |
| **Blobs** | `seon.blob` (planned), content-addressed | Full eval results, prompts. |
| **Per-agent atom (`*ctx*`'s value)** | `seon.agents/*ctx*` binding | Per-agent runtime config, lifecycle cache, volatile fn/state pointers. **Volatile by design — nothing here needs to survive a restart.** |
| **globalThis stash** | `js/globalThis["__seon_results_<eval-id>"]` | Raw eval values that can't pr-str cleanly (datahike DB literals, Promises, fns). **Unchanged; per-eval-id keyed.** |

### Concurrency

Two concerns:

1. **Two agents writing concurrently.** Each has its own atom. The Var binding scopes each `swap!` to that agent's atom. No interleaving.
2. **One agent's two awaiting fibers.** Today the `:running` state-machine guard in `agent.cljs:345-352` already prevents a single agent from running two turns concurrently. Within ONE fiber, `swap!` on a CLJS atom is trivially serial (Node single-threaded). Within ONE agent across multiple awaits to the same atom — also fine; `swap!` retries.

The ALS wrapper guarantees that every async continuation re-enters the same `binding` scope, so `*ctx*` always resolves to this agent's atom, regardless of how many `await`s have happened.

---

## §5 Init protocol — walk-and-persist on cold boot

Two distinct phases, distinct triggers:

### Phase A — Pod boot (once per process)

```
1. Bundle loads (substrate compiled into out/client/main.js).
2. seon.db/*conn* binds to the on-disk LMDB store (existing behavior).
3. NEW: If the DB is empty (no :seon.ns entities yet), the substrate
   walks its own source tree under src/seon/ and transacts:
     - :seon.ns entities (one per namespace, with source string)
     - :seon.fn entities (one per defn)
     - :seon.schema entities (one per (schema/register! …) call)
   This INCLUDES seon.agents itself.
4. If the DB is non-empty, skip the walk — substrate code came from
   the bundle, the DB already has the prior generation's entities.
   (Reconciliation between bundle and DB is a separate concern; see §11.)
5. Pod is ready to accept agent-creation requests.

```

Sketch:

```clojure
(defn boot-pod! [conn]
  (when (empty-db? conn)
    (let [forms (walk-source-tree "src/seon/")]      ; existing analyzer-tee infra
      (doseq [{:keys [ns-sym source fns schemas]} forms]
        (db/transact! conn
          (concat [{:seon.ns/sym ns-sym :seon.ns/source source}]
                  (for [f fns] {:seon.fn/sym (:sym f) :seon.fn/source (:source f) ...})
                  (for [s schemas] {:seon.schema/sym (:sym s) :seon.schema/form (:form s) ...})))))))

```

### Phase B — Agent boot (per agent, possibly many per pod)

```
1. start-agent! mints agent-id via (db/new-id!) — client.cljs:565.
2. NEW: substrate creates a fresh atom for this agent:
     (let [agent-atom (atom #::{:id agent-id
                                :state :booting
                                :booted-at (js/Date.)
                                :compile-state-ref compile-state
                                :llm-fn llm-fn
                                :eval-results-stash-prefix (str "__seon_results_" agent-id "_")})]
       ...)
3. From here on, EVERY entry into this agent's code is wrapped in
   (run-as-agent agent-atom (fn [] ...)). That includes:
     - eval-batch! invocations
     - run-turn! callbacks
     - setTimeout-driven kick handlers
     - message handlers from the loopback HTTP/SSE server
     - any future timer or async callback the substrate dispatches
4. Inside the wrapper, *ctx* is bound to agent-atom. Standard Clojure
   ops work as expected; the ALS plumbing keeps the binding alive
   across await.
5. replay-program-graph! runs INSIDE the wrapper, so any agent code
   that reads *ctx* during replay sees the agent's own atom.
6. agent/boot! flips state to :idle via (swap! *ctx* assoc :state :idle).
   No DB sync from the atom — the DB tracks :seon.agent/state via the
   existing transact, independently.

```

Sketch of the wrapper (substrate-internal, never user-facing):

```clojure
(defonce ^:private substrate-ctx-als
  (when (exists? js/globalThis.AsyncLocalStorage)
    (new (.-AsyncLocalStorage (js/require "node:async_hooks")))))

(defn run-as-agent
  "Substrate-internal. Wraps fn `f` so all code (including across await)
   inside it sees `*ctx*` bound to this agent's atom. Implementation:
   Node ALS restores the binding after each async hop. Users never call
   this — substrate calls it around every entry into agent code."
  [agent-atom f]
  (.run substrate-ctx-als agent-atom
    (fn []
      (binding [*ctx* (.getStore substrate-ctx-als)]
        (f)))))

```

JVM sidecar equivalent (no ALS needed):

```clojure
(defn run-as-agent [agent-atom f]
  (binding [*ctx* agent-atom]
    (f)))

```

The wrapper's call sites live in `seon.client/start-agent!`, `seon.eval/eval-batch!`'s outer envelope, the kick handler in `seon.agent`, and any handler in `seon.server` that dispatches into agent code. See §8 for the exact list.

### What does NOT happen on resume

The atom is **purely volatile**. There is no `rebuild-instances!`. There is no "reconstruct the atom from DB facts". When an agent reboots after a pod restart, it gets a fresh atom seeded with the same defaults plus any per-agent overrides persisted as `:seon.agent.config/*` datoms (see §7).

---

## §6 Enforcement — most code should ignore `*ctx*`

Two enforcement targets:

### Target A — Agents don't reach into `*ctx*` casually

`*ctx*` is substrate-internal. Agents who want persistent data write to the DB. Agents who want per-turn scratch space stash into globalThis (already the pattern, Q1 confirmed). `*ctx*` is for runtime-specific knobs that mirror `*conn*` — and the user has been clear that's a narrow category.

Surface this via the warnings section in the agent's ctx. If the analyzer-tee (`eval.cljs:648`) sees agent code referencing `seon.agents/*ctx*` directly (deref, swap!, alter-var-root), the resulting `:seon.fn` entity gets tagged `:seon.fn/warning :ctx-access`, and `warnings-section` (`agent.cljs:1052`) renders "Agent X reads/writes `seon.agents/*ctx*` directly. This is substrate-internal runtime state. Use the DB for persistent data."

The warning is **reactive context** — vanishes when the agent stops touching `*ctx*`. No persistent flag, no acknowledgement.

### Target B — Agents don't mint their own atoms

Existing concern, unchanged from prior draft. If an agent runs `(defonce !my-state (atom {}))`, replay on next boot re-runs the form and resets the atom. The agent's state is silently lost. Same lint extension as Target A — `build-tee-entities` flags `(def !foo (atom …))` / `(defonce !foo (atom …))` in agent code with `:seon.fn/warning :agent-atom`. Warning text directs them to **the DB**, not to `*ctx*`. (Because `*ctx*` is substrate-internal — see Target A.)

### What this is NOT

Not a sandbox. Not a runtime guard. The lint is reactive context: a section function that queries the DB for the warning tag and renders if present. CLJS doesn't allow private vars across `cljs.js` eval boundaries, so the agent CAN reach in. The point is to make the wrong path visible and the right path discoverable. Per `CLAUDE.md`, this is exactly the substrate's posture toward agent code: catch hallucinations, not adversaries.

---

## §7 Persistence + resume — the atom is volatile

**Claim under examination:** nothing in `*ctx*` needs to persist. Everything that matters lives in the DB. The atom is a fast-path runtime cache; reconstruction on restart is naturally empty (or empty-with-defaults).

### What survives a pod restart

| Thing | Persistent? | Mechanism |
|---|---|---|
| `seon.agents` namespace definition | Yes | Either bundled in `out/client/main.js` (today) or as a `:seon.ns` entity (after cold-boot walker runs). |
| The Var `seon.agents/*ctx*` | Yes (as a def) | Bundled or DB-persisted alongside the ns. |
| The per-agent atom's VALUE | **No — by design.** | New atom created at agent boot, seeded from defaults + DB-persisted overrides. |
| `:seon.agent/*` DB datoms | Yes | Unchanged. |
| `:seon.session/*`, `:seon.turn/*`, `:seon.message/*`, `:seon.eval/*`, `:seon.fn/*`, `:seon.ns/*`, `:seon.schema/*` | Yes | Unchanged. |
| Per-agent config overrides | Yes (when explicitly persisted) | `:seon.agent.config/eval-timeout-ms` etc. datoms — agent writes them via `db/transact!`, not via `*ctx*`. |
| Volatile runtime pointers (`::compile-state-ref`, `::llm-fn`, etc.) | No | Re-seeded at agent boot from substrate-provided values. |

### The two-step setting pattern

When an agent (or operator) wants a config override that survives restart, the pattern is **two explicit steps**:

```clojure
;; Step 1: persist to DB (survives restart, the source of truth)
(db/transact! [{:seon.agent/id (current-agent-id)
                :seon.agent.config/eval-timeout-ms 30000}])

;; Step 2: update the runtime cache (current process)
(swap! *ctx* assoc :seon.agents/eval-timeout-ms 30000)

```

This is honest about what the two storage tiers are. Hiding both behind a single `set!` (the rejected approach) blurs the line.

Where this matters: at agent boot, the substrate populates the atom by querying `:seon.agent.config/*` datoms:

```clojure
(defn seed-atom [agent-id substrate-defaults]
  (let [overrides (db/pull-by-name :seon.agent/id agent-id [:seon.agent.config/eval-timeout-ms ...])]
    (atom (merge substrate-defaults
                 #::{:id agent-id :state :booting :booted-at (js/Date.)}
                 (translate-keys overrides)))))

```

One direction: **DB → atom at boot**, never atom → DB silently. The agent writes the DB datom when they want persistence; they write the atom when they want runtime effect. Usually both, expressed as two ops.

### Defense of "all volatile"

This design rejects auto-persistence specifically because it preserves the three-tier rule's clarity. If `(swap! *ctx* assoc :seon.agents/foo …)` silently transacted, then `*ctx*` would be a second-class DB. It isn't. It's runtime state. The DB is the DB. Two motions for two purposes.

Alternative considered: a marker map (`{:persist? true}`) wrapping persisted writes. **Rejected** because it reintroduces the bespoke API the user just deleted. Standard Clojure ops only; persistence is an explicit `db/transact!`.

---

## §8 Refactor plan — every site, with the locked-in verdict

Walking §2 in order. Verdicts updated under the locked design.

| # | Site | Verdict |
|---|---|---|
| S1 | `als-instance` (tx-context) | **Keep.** Sub-form fiber-scoped causality bundle, not per-agent state. Unrelated to `*ctx*`. |
| S2 | `agent-id-als` | **Keep, but role narrows.** Today carries the active agent id; under the locked design that role is partly subsumed by the `*ctx*` binding (the agent atom carries `::id`). However, ALS is still needed to PROPAGATE the id across `await` so non-DB code paths (inspectors, web handlers) can identify the agent. Effectively this ALS instance becomes the implementation detail of `run-as-agent`'s binding survival — it's the same ALS, just renamed in spirit. |
| S3 | `warnings-als` | **Keep.** Per-eval transient; unrelated. |
| S4 | `*conn*` | **Keep.** Pod-level singleton. This is the canonical example `*ctx*` follows. |
| S5 | `*log-file*` | **Move into `*ctx*`.** Per-agent log file is a legit per-agent runtime knob (multiple agents can have separate logs). `(swap! *ctx* assoc :seon.agents/log-file …)`; reader of the log path consults `(or (:seon.agents/log-file @*ctx*) "logs/pod-events.log")`. |
| S6 | `seon.log/!config` | **Keep as substrate default.** Per-agent override layers via `*ctx*`. |
| S7 | `seon.fs/!config` | **Keep as substrate default.** Per-agent override via `*ctx*` (`::fs-allowed-roots`, `::fs-read-only?`). |
| S8 | `seon.eval/!timeout-ms` | **Keep as substrate default.** Per-agent override via `(:seon.agents/eval-timeout-ms @*ctx*)`. |
| S9 | `seon.eval/!next-budget-ms` | **Move into `*ctx*`** as `::next-budget-ms`. Today it's a side-channel one-shot that can leak across agents; per-agent atom eliminates the leak. |
| S10 | `seon.ai.deepseek/!timeout-ms` | **Keep as substrate default.** Per-agent override via `*ctx*`. |
| S11 | `seon.client/!state` | **Keep.** Pod-level boot metadata, not per-agent. |
| S12 | `seon.client/!agent-conn` | **DELETE.** Duplicates `*conn*`. |
| S13 | `seon.repl/!compile-state` | **Move pointer into `*ctx*`** as `::compile-state-ref`. The atom-of-atom hot-reload mechanism stays in `seon.repl`; each agent's atom holds a reference for inspector ergonomics. |
| S14 | `seon.repl/!init-version` | **Keep.** Coupled to S13's hot-reload bookkeeping; not per-agent. |
| S15 | `seon.repl/!conn` | **DELETE.** Duplicates `*conn*`. |
| S16 | `seon.eval/!warning-dispatcher-version` | **Keep.** Hot-reload bookkeeping. |
| S17 | Per-eval warnings bucket | **Keep.** One-cljs.js-call lifetime. |
| S18 | Historic home-ns atoms | n/a — already deleted. |

### The substrate-internal ALS wrapper

Already sketched in §5. Recapping the call sites that need wrapping:

| Entry point | File:line | Currently wrapped? |
|---|---|---|
| `eval-batch!` per-form work | `eval.cljs:854` | Already in `(with-tx-context …)`; add `run-as-agent` outside it |
| `run-turn!` body | `agent.cljs:664` | Same |
| `replay-program-graph!` per-form replay | `client.cljs:474` | Same |
| Kick handler (setTimeout) | `agent.cljs:356` | Currently `with-agent` (ALS-only); replace with `run-as-agent` |
| Message handler from loopback HTTP/SSE | `server.cljs` (TBD line) | Currently `with-agent`; replace |
| `start-agent!` post-init | `client.cljs:547+` | New — wraps the rest of the start sequence |

The wrapper is idempotent (re-running `(run-as-agent same-atom f)` inside an existing `*ctx*` binding to the same atom is a no-op observationally), so nested calls in the dispatch chain are safe.

### What actually moves

Honest accounting: **5 things move into `*ctx*`** — S5 (log-file), S9 (next-budget), S13 (compile-state pointer for inspection), and the per-agent override layer on S6/S7/S8/S10 (substrate defaults stay; per-agent layer is new). **2 things delete** (S12, S15). Everything else stays.

The bulk of the value is architectural: future per-agent state has ONE place to go, and that place uses standard Clojure ops.

---

## §9 CLJ / sidecar JVM compat

The sidecar JVM writer (`pod-host/sidecar-poc/jvm-writer/src/seon/sidecar/writer.clj:27`) already uses `(defonce ^:private state (atom nil))`. Standard atom semantics, identical to CLJS.

For `*ctx*` on JVM: bare `binding` works. No ALS shim needed.

```clojure
;; JVM sidecar — pod-host/sidecar-poc/jvm-writer/...
(defn run-as-agent [agent-atom f]
  (binding [*ctx* agent-atom]
    (f)))

```

Clojure's `binding` on JVM conveys via Var thread-bindings, which propagate naturally across `core.async/go`-driven thread switches when used with `bound-fn`. The sidecar's flow topology already handles this correctly.

The sidecar overlay (`pod-host/sidecar-poc/guest-cljs/src-overlay/seon/db.cljs`) drifted in commit `5a82742` (per `bench/v0-port-survey.md:228+`) because it still uses naked `^:dynamic` Vars without ALS extension. Under wasm-rquickjs each Store is one fiber, so dynvar clobber doesn't apply — but the overlay should still align on the `*ctx*` symbol for portability. Phase 6 below.

**Confirm:** `seon.agents/*ctx*` and `::ctx-value` are CLJC-portable. Schema is CLJC. Volatile keys (`::compile-state-ref`, `::llm-fn`) have type `:any`; JVM-side agents simply don't use them.

---

## §10 Migration phases

### Phase 0 — Land the namespace + Var + wrapper (no behavior change)

- Create `src/seon/agents.cljc` with `*ctx*` Var, `::ctx-value` schema, `run-as-agent` wrapper.
- Substrate-internal ALS instance in `src/seon/agents.cljs` (CLJS-only via reader conditional).
- Register all `:seon.agents/*` schemas.
- Wire `seon.agents` into `seon.client`'s require list so it compiles into the bundle.
- **Verification:**
  - `(binding [seon.agents/*ctx* (atom {:k 1})] @seon.agents/*ctx*)` returns `{:k 1}`.
  - **Cross-await binding-survival test** (CLJS): `(run-as-agent (atom {:k 1}) (fn [] (-> (js/Promise.resolve nil) (.then (fn [_] @*ctx*)))))` resolves to `{:k 1}`, NOT nil.
  - On JVM with the bare-binding variant: same test using `(future …)` resolves correctly.
  - No call sites changed yet; nothing in the substrate references `*ctx*`.

### Phase 1 — Wire `start-agent!` to create the per-agent atom + invoke wrapper

- In `start-agent!` (`client.cljs:547+`), after `agent-id` is minted: create per-agent atom seeded from `seed-atom` (substrate defaults + DB overrides).
- Wrap the remainder of `start-agent!` in `(run-as-agent agent-atom (fn [] …))`.
- `replay-program-graph!` now runs inside the binding scope.
- **Verification:** Boot V0 pod, send a chat. Inside any agent-eval form `@seon.agents/*ctx*` returns the agent's map (including `::id`, `::state`, `::booted-at`). Restart pod; second boot's `*ctx*` is freshly seeded — no stale state leaks.

### Phase 2 — Migrate ONE call site (S5: `*log-file*`)

- `seon.log/event-file-path` reads `(or (:seon.agents/log-file @*ctx*) @!log-file)` first.
- Set up an integration test where agent A has `log-file` override and agent B doesn't; verify each writes to the right file.
- **Verification:** Test passes. No regression on the default path.

### Phase 3 — Migrate remaining per-agent overrides (S6/S7/S8/S9/S10 reader-side)

- `seon.eval/race-timeout`, `seon.ai.deepseek` HTTP timeout, `seon.fs` gate fns read `*ctx*` first.
- Move S9 (`!next-budget-ms`) entirely into `::next-budget-ms`; delete the global atom.
- **Verification:** Per-agent override of each config works. Restart pod; persisted `:seon.agent.config/*` datoms re-seed the atom at agent boot.

### Phase 4 — Wrap remaining entry points

- Kick handler (`agent.cljs:356`), message handler (server), any other setTimeout/setInterval/HTTP-callback entry into agent code gets `run-as-agent` wrapping.
- Replace existing `with-agent` calls in those paths with `run-as-agent`.
- **Verification:** Cross-await `*ctx*` access works in every dispatch path. Inspector at `/agent` route shows the atom's value for the selected agent.

### Phase 5 — Add the lint warnings (Target A + Target B from §6)

- Extend `build-tee-entities` to detect `(def !foo (atom …))`, `(defonce !foo (atom …))`, AND `seon.agents/*ctx*` references in agent code.
- Extend `warnings-section` to render them.
- **Verification:** Agent runs `(def !foo (atom 0))` — next turn ctx shows the warning. Removes the form — warning vanishes. Same for `*ctx*` access.

### Phase 6 — Codebase walker for cold-boot persistence

- Implement `walk-source-tree` over `src/seon/` invoking the analyzer-tee to produce `:seon.ns`/`:seon.fn`/`:seon.schema` entities.
- Wire into `boot-pod!` as the `(when (empty-db? conn) …)` branch.
- **Verification:** Wipe the LMDB store. Boot the pod. Query the DB — every substrate namespace has a `:seon.ns` entity. Reboot — walker skips. Boot a fresh agent — sees the substrate code from the DB OR the bundle (both should agree).
- Reconciliation between bundle and DB stays out of scope; it's a separate concern listed in §11.

### Phase 7 — Sidecar overlay sync + delete unused ALS shims

- Sidecar overlay (`pod-host/sidecar-poc/guest-cljs/src-overlay/`) gets a matching `*ctx*` Var (bare-binding variant, no ALS — wasm-rquickjs is one fiber per Store).
- Delete `seon.client/!agent-conn` (S12), `seon.repl/!conn` (S15).
- If S2 (`agent-id-als`) is now fully subsumed by `*ctx*` carrying `::id`, retire S2 — but only after all non-DB code paths have migrated to reading `(:seon.agents/id @*ctx*)`. Stage carefully.
- **Verification:** No regressions; sidecar smoke tests still pass.

Each phase ships standalone. Phase 0's cross-await test is the single most important gate — if `binding` doesn't survive `await` under the substrate's ALS wrapper, the whole design fails and we revisit.

---

## §11 Open questions

Most prior Q-list is now answered. Remaining items:

**Q-name — Name of the dynvar.** Proposed: `seon.agents/*ctx*`. Alternatives: `*instance*`, `*runtime*`, `*self*`. `*ctx*` reads cleanly alongside `*conn*` and `*log-file*` (the other dynvars in the codebase). **Tentative:** `*ctx*`. `@user` confirm or override before Phase 0 ships.

**Q-stash — Stash prefix in `*ctx*`?** `::eval-results-stash-prefix` is included in the schema (§4) but agent code accessing eval results today does so via `(result eval-id)` which doesn't need the prefix. Should the prefix be a key in `*ctx*` (inspectability) or a derived expression (`(str "__seon_results_" agent-id "_" eval-id)`)? **Tentative:** include in `*ctx*` for inspectability; the `(result …)` macro computes from `(:seon.agents/id @*ctx*)` either way. `@user` low-stakes choice.

**Q-walk — Reconciliation between bundled substrate code and DB-persisted `:seon.ns` entities on warm boot.** Cold boot walks. Warm boot skips. But if `out/client/main.js` is recompiled with new substrate code between boots, the DB has stale `:seon.ns` source. Three options: (a) cold-walk on every boot, transact only diffs; (b) bundle source content-hash, walk only when hash changes; (c) treat the DB as substrate ground truth, ignore the bundle. The user's framing favored "all code is data, store in DB" — leaning toward (c) long-term, (b) short-term. **Out of scope for this PRD**; flag for a separate code-as-data PRD.

**Q-retire-S2 — Can `agent-id-als` retire fully once `*ctx*` carries `::id`?** Probably yes after Phase 4. But the ALS instance used internally by `run-as-agent` IS effectively the same ALS — just relabeled and accessed only via `.getStore` inside the wrapper. Decide during Phase 7 audit.

### Closed (from prior draft)

- ~~Q1 (result stash location)~~ — Stays on globalThis.
- ~~Q2 (set! shape)~~ — No bespoke API; standard Clojure only.
- ~~Q3 (`!next-budget-ms` leak)~~ — Moves into `*ctx*` as `::next-budget-ms`.
- ~~Q4 (scope)~~ — Per-agent atom held in dynvar; ALS retreats to substrate-internal.

### Residual risks

**R1 — ALS wrapper bug = silent cross-agent leak.** If `run-as-agent` is ever called without going through ALS (or with the wrong atom), one agent's `*ctx*` resolves to another's atom. Phase 0's cross-await test must be paired with a multi-agent interleaving test. **Mitigation:** `run-as-agent` asserts `(identical? agent-atom (.getStore substrate-ctx-als))` post-binding; throws if mismatched.

**R2 — Bundle vs DB substrate drift** (Q-walk above). Not blocking this PRD but blocks Phase 6 production-readiness.

**R3 — JVM thread-bindings need `bound-fn` for async hand-offs.** If the sidecar JVM writer ever dispatches work via `(future …)` or core.async without `bound-fn`, the `*ctx*` binding is lost. **Mitigation:** all sidecar substrate-internal dispatch goes through helpers that apply `bound-fn` automatically. Verify in Phase 7.

---

## §12 Appendix — V0 ALS rationale (verbatim citation)

From `src/seon/db.cljs:451-478`. Preserved because the locked design RELIES on this argument — ALS doesn't go away, it moves inside the substrate to make `binding` survive `await` exactly as the original V0 reasoning concluded.

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

**This PRD's design takes Probe 13's clobber problem seriously by pairing the dynvar with an ALS wrapper.** The wrapper is exactly the missing piece — `.run` re-binds `*ctx*` after each async hop, so the agent-facing surface stays "just a Var holding an atom" while the substrate handles the await-survival problem invisibly.

End of PRD.
