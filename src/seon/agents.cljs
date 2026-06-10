(ns seon.agents
  "Per-agent runtime state, held in one well-known dynvar — `*ctx*` —
   pointing at a per-agent atom. Mental model: exactly parallel to
   `seon.db/*conn*`. The Var is the fixed name; the atom is the
   per-agent value; the substrate binds the Var around every entry
   into agent code.

   ## What this is for

   Runtime-specific special state that mirrors how `*conn*` mirrors
   the pod-level datahike connection — pod-/agent-level runtime knobs,
   opaque-pointer pass-throughs, per-agent overrides of substrate
   defaults. Persistent state belongs in the DB (`seon.db`). Per-eval
   raw values belong in the globalThis stash. The atom held by `*ctx*`
   is purely volatile per-process state — nothing in it needs to
   survive a restart. See `docs/prds/agent-runtime/atom-state-system-2026-05-26.md` §4.

   Users manipulate the atom with standard Clojure:

     @*ctx*
     (:seon.agents/state @*ctx*)
     (swap! *ctx* assoc :seon.agents/state :running)
     (swap! *ctx* update :seon.agents/next-budget-ms (constantly 30000))

   No bespoke API (`get!`/`set!`/`persist!`). The Var IS the API.

   ## Most code should NOT touch *ctx*

   `*ctx*` is substrate-internal runtime state. Reach for it only for
   the same kinds of reasons code reaches for `*conn*`. Application data
   lives in the DB.

   ## Await-survival (CLJS-only concern)

   On Node, `binding` does not survive `await` (Probe 13 — see
   `src/seon/db.cljs` §`*tx-context*` and
   `research/impl-finding-tx-context-promise-2026-05-22.md`). The
   substrate uses Node's `AsyncLocalStorage` internally: `run-as-agent`
   stores the agent's atom in ALS (`.run`), and the sanctioned read
   path `ctx-or-throw` prefers the ALS store (`.getStore`) over the
   raw dynvar. Consequence for callers:

   - `(ctx-or-throw)` is correct EVERYWHERE — sync code, and any
     continuation after an `await` / `.then` inside `run-as-agent`'s
     dynamic extent. This is the one read path that honors the
     per-agent contract across async hops.
   - Raw `@*ctx*` deref is valid ONLY in the synchronous extent of
     `run-as-agent`'s body — the dynvar unwinds the moment the body
     returns its Promise (same mechanism as Probe 13).

   Users never see ALS — they call `ctx-or-throw`.

   On JVM (e.g. the sidecar writer), bare `binding` is sufficient —
   Clojure's Var thread-bindings convey across thread switches via
   `bound-fn`. A future `.cljc` port will reader-conditional out the
   ALS branch.

   ## Single-point-of-failure mitigation

   `run-as-agent` is the ONLY way the substrate should bind `*ctx*`
   to an agent's atom. If it is bypassed or wired wrong, one agent's
   `*ctx*` could resolve silently to another agent's atom. To make
   this loud rather than silent, `run-as-agent` asserts after binding
   that `(:seon.agents/id @*ctx*)` matches the caller-claimed agent
   id, throws and logs via `seon.log/error!` on mismatch. Substrate
   bugs should be obvious.

   ## Registry

   `start-agent!` / `stop-agent!` track per-agent atoms in a private
   registry (`!instances`). This is substrate-internal — users see
   only `*ctx*`. The registry exists so inspectors and the substrate
   eval-batch wiring can find an agent's atom by id."
  (:require
    [seon.log :as log]
    [seon.platform :as platform]
    [seon.schema :as schema]))

;; ============================================================
;; Schemas — shape of the value an agent's atom holds.
;;
;; Decision: CLOSED map. Phase 0 is additive; we know exactly which
;; keys are valid today. New per-agent runtime knobs require adding
;; a `register!` here, which is the right friction (cf. CLAUDE.md
;; "no :any, no [:maybe X]", every key fully typed). Future keys
;; bump the schema in place — no parallel "open" variant.
;;
;; Note on key namespace: keys are `:seon.agents/*` (plural — this
;; namespace's name). They are distinct from `:seon.agent/*` (singular)
;; which lives on the DB entity (`seon.agent.cljs`). The atom keys
;; are runtime overrides and convenience caches; the DB entity is
;; the persistent identity / source of truth.
;; ============================================================

(schema/register! ::id        :string)
(schema/register! ::booted-at :inst)
(schema/register! ::state     [:enum :booting :running :paused :stopped])

;; Prefix for per-agent eval-result entries in the globalThis stash
;; (`js/globalThis["__seon_results_<eval-id>"]`). Held here for
;; inspectability; the `(result …)` accessor in agent code computes
;; from `(:seon.agents/id @*ctx*)` directly.
(schema/register! :seon.eval/results-stash-prefix :string)

(schema/register! ::ctx-value
  [:map {:closed true}
   [::id                              ::id]
   [::booted-at                       ::booted-at]
   [::state                           ::state]
   [:seon.eval/results-stash-prefix   :seon.eval/results-stash-prefix]])

;; ============================================================
;; Registry — substrate-internal mapping of agent-id -> atom.
;;
;; Per the PRD, the user-facing surface is JUST `*ctx*`. The
;; registry exists so `eval-batch!` / inspectors / kick handlers
;; can locate an agent's atom by id without threading it through
;; every callsite. Do NOT add user-facing lookup fns over this.
;; ============================================================

(defonce ^:private !instances (atom {}))

(defn lookup
  "Substrate-internal. Return the atom for `agent-id`, or nil if no
   agent with that id is currently registered (i.e. not between
   `start-agent!` and `stop-agent!`). Users should not reach for this
   — bind `*ctx*` via `run-as-agent` and use the standard Clojure
   ops instead."
  {:malli/schema [:=> [:cat :string] [:maybe :any]]}
  [agent-id]
  (get @!instances agent-id))

(defn registered-ids
  "Substrate-internal. Return the set of agent-ids with live atoms.
   Used by inspectors."
  {:malli/schema [:=> [:cat] [:set :string]]}
  []
  (set (keys @!instances)))

;; ============================================================
;; The Var — fixed symbol, per-agent value via binding.
;; ============================================================

(def ^:dynamic *ctx*
  "Current agent's per-runtime state atom. The substrate binds this to
   a fresh atom at agent boot inside `run-as-agent`, which ALSO stores
   the same atom in Node ALS so the per-agent binding survives `await`
   — but ONLY when read through `ctx-or-throw`. A raw deref of this
   var is valid solely in the synchronous extent of `run-as-agent`'s
   body; the dynvar unwinds when the body returns its Promise (see ns
   docstring §Await-survival). After any `await`, read via
   `(ctx-or-throw)`.

   Inside agent code, resolves to the agent's OWN atom — never
   another agent's. Mental model: parallel to `seon.db/*conn*`.

   This is runtime-specific special state, NOT app data. Persistent
   state belongs in the DB. Most code should never read or write this
   var. Use it for the same kinds of reasons you'd reach for `*conn*`:
   pod-level runtime knobs, opaque-pointer pass-throughs, per-agent
   overrides of substrate defaults.

   Standard Clojure ops only: `swap!`, `assoc-in`, `update-in`,
   `reset!`, `@`. No `get!`/`set!`/`persist!` bespoke API."
  nil)

;; ============================================================
;; ALS — substrate-internal plumbing for await-survival on Node.
;;
;; The ONE place ALS is mentioned. Agents never see this. JVM ports
;; (e.g. the sidecar writer) will reader-conditional this branch out;
;; bare `binding` is sufficient there.
;;
;; Write side: `run-as-agent` stores the agent's atom via `.run`.
;; Read side: `ctx-or-throw` prefers `.getStore` over the dynvar —
;; that is what makes the binding genuinely survive await (V8
;; propagates the ALS context through Promise continuations; the
;; dynvar alone unwinds at the first await — Probe 13/14, see
;; seon.db §als-instance for the verified pattern).
;; ============================================================

(defonce ^:private substrate-ctx-als
  (when (= :node (platform/host))
    (let [AsyncLocalStorage (.-AsyncLocalStorage (js/require "node:async_hooks"))]
      (AsyncLocalStorage.))))

(defn- als-ctx
  "Substrate-internal read of the ALS store: the current agent's atom
   when called anywhere inside a `run-as-agent` dynamic extent
   (including post-await continuations), else nil. Normalizes JS
   undefined (outside any `.run` scope) to nil."
  []
  (when (some? substrate-ctx-als)
    (let [store (.getStore substrate-ctx-als)]
      (when (some? store) store))))

(defn ctx-or-throw
  "Return the current agent's ctx atom, or throw if no agent scope is
   active. THE sanctioned read path — prefers the ALS store (fiber-
   local, survives `await`) and falls back to the dynvar for sync
   callers (e.g. JVM ports where bare `binding` suffices, or direct
   `binding` in tests). Use at any read site that should hard-fail
   when called outside `run-as-agent`; silent nil-reads are rejected
   here so substrate bugs surface immediately. Returns the atom
   (not the value — caller derefs)."
  {:malli/schema [:=> [:cat] :any]}
  []
  (let [c (or (als-ctx) *ctx*)]
    (when (nil? c)
      (throw (ex-info
               (str "seon.agents/*ctx* is not bound. "
                    "Every entry into agent code must go through "
                    "seon.agents/run-as-agent. Caller is likely missing "
                    "the wrapper.")
               {:seon.agents/error :ctx-unbound})))
    c))

(defn- assert-identity!
  "Verify that the atom currently visible through `*ctx*` actually
   belongs to the claimed agent. Loud failure on mismatch — throws
   AND logs via `seon.log/error!`. Substrate-internal; agents do not
   invoke this."
  [claimed-id]
  (let [actual-id (try (:seon.agents/id @(ctx-or-throw)) (catch :default _ nil))]
    (when-not (= claimed-id actual-id)
      (log/error! {:seon.log/source ::identity-mismatch
                   :seon.log/agent  (or claimed-id "<nil>")
                   :seon.log/message
                   (str "run-as-agent identity assert failed: "
                        "claimed " (pr-str claimed-id)
                        " but *ctx* atom carries "
                        (pr-str actual-id))
                   :seon.log/data {:seon.agents/claimed-id claimed-id
                                   :seon.agents/atom-id    actual-id}})
      (throw (ex-info
               (str "seon.agents/run-as-agent identity assert failed — "
                    "claimed agent " (pr-str claimed-id)
                    " but the bound *ctx* atom carries id "
                    (pr-str actual-id)
                    ". This indicates substrate wiring is broken; "
                    "two agents may be racing for the same atom slot.")
               {:seon.agents/error      :identity-mismatch
                :seon.agents/claimed-id claimed-id
                :seon.agents/atom-id    actual-id})))))

(defn run-as-agent
  "Substrate-internal. Run `body-fn` (0-arg) with the agent scope
   established two ways: `*ctx*` bound to `agent-atom` for the sync
   extent, AND the same atom stored in ALS (`.run`) so reads through
   `ctx-or-throw` survive every `await` continuation. On JVM, bare
   `binding` is sufficient (the ALS branch is a no-op when
   `substrate-ctx-als` is nil) and `ctx-or-throw` falls back to the
   dynvar.

   After binding, asserts that the atom's `:seon.agents/id` matches
   the caller's `:seon.agents/id` — see ns docstring §SPOF.

   Map-in shape:
     {:seon.agents/id      <claimed agent id>
      :seon.agents/atom    <atom holding ::ctx-value>
      :seon.agents/body-fn <0-arg fn>}

   Returns whatever `body-fn` returns (often a Promise — that's fine;
   ALS ensures `(ctx-or-throw)` resolves to this agent's atom across
   all of its continuations, while the raw dynvar unwinds when the
   body returns).

   Agents NEVER call this. Substrate calls it around every entry into
   agent code: eval-batch!, kick handlers, message handlers,
   replay-program-graph!."
  {:malli/schema
   [:=> [:cat [:map
               [:seon.agents/id      ::id]
               [:seon.agents/atom    :any]
               [:seon.agents/body-fn fn?]]]
    :any]}
  [{id :seon.agents/id, agent-atom :seon.agents/atom, body-fn :seon.agents/body-fn}]
  (let [run-body (fn []
                   (binding [*ctx* agent-atom]
                     (assert-identity! id)
                     (body-fn)))]
    (if substrate-ctx-als
      ;; Node — ALS keeps `binding` alive across await.
      (.run substrate-ctx-als agent-atom run-body)
      ;; JVM / no-ALS fallback — bare binding. Clojure's thread-bindings
      ;; convey via bound-fn at the framework layer.
      (run-body))))

;; ============================================================
;; start-agent! / stop-agent! — substrate facade.
;;
;; These create / destroy the per-agent atom + track it in the
;; registry. Wired into eval-batch in Phase 1; for now they let
;; tests exercise the binding contract directly.
;; ============================================================

(defn- default-stash-prefix [id]
  (str "__seon_results_" id "_"))

(defn- seed-atom-value
  "Build the initial map for an agent's atom. All `::ctx-value` keys
   are populated; the map is CLOSED so missing keys throw at the
   validation boundary."
  [id]
  {::id                            id
   ::booted-at                     (js/Date.)
   ::state                         :booting
   :seon.eval/results-stash-prefix (default-stash-prefix id)})

(defn start-agent!
  "Substrate-internal. Create a fresh atom for `:seon.agents/id`,
   register it in `!instances`, return the atom.

   Map-in:
     {:seon.agents/id <14-char id>}

   The returned atom can be passed to `run-as-agent` to bind `*ctx*`
   to it. The state starts as `:booting`; flip to `:running` via
   `(swap! the-atom assoc :seon.agents/state :running)` once boot
   work completes (Phase 1 will wire that).

   Throws if `agent-id` is already registered — concurrent starts
   should be a substrate bug, not a silent overwrite."
  {:malli/schema [:=> [:cat [:map [:seon.agents/id ::id]]] :any]}
  [{:seon.agents/keys [id]}]
  (let [a (atom (seed-atom-value id))]
    (swap! !instances
           (fn [m]
             (when (contains? m id)
               (throw (ex-info
                        (str "seon.agents/start-agent! id collision — "
                             "agent " (pr-str id) " already registered. "
                             "Call stop-agent! first or pick a new id.")
                        {:seon.agents/error :id-collision
                         :seon.agents/id    id})))
             (assoc m id a)))
    a))

(defn stop-agent!
  "Substrate-internal. Remove the atom for `agent-id` from the
   registry. Returns the atom that was removed (so the caller can
   inspect final state), or nil if no such agent was registered."
  {:malli/schema [:=> [:cat [:map [:seon.agents/id ::id]]] [:maybe :any]]}
  [{:seon.agents/keys [id]}]
  (let [a (get @!instances id)]
    (swap! !instances dissoc id)
    a))
