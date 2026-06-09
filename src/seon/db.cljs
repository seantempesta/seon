(ns seon.db
  "Database access. This is the only API you need to read, write, and
   react to the database. Everything in your runtime that touches the DB
   goes through this namespace.

   ## Your universe is one connection

   You have exactly ONE database. `seon.db/*conn*` is bound for you by
   the session-flow boundary before your code starts running; you never
   thread it through your own calls and you never open another conn.
   Every fn here defaults `:seon.db/conn` to `*conn*`, so call sites
   stay clean. Aliasing `seon.db` as `db` lets you write `::db/foo` for
   `:seon.db/foo`:

     (require '[seon.db :as db])

     (db/query    {::db/query '[:find ?n :where [_ ::name ?n]]})
     (db/transact! {::db/tx-data [{::name \"Alpha\" ::rank 1}]})

   That's how you talk to the world. Every map key in / out of seon.db
   is fully namespaced under `:seon.db/*`. The namespacing isn't
   cosmetic — it's what lets a single Datalog query connect the data
   in the database to the functions that operate on it.

   ## Register schemas before you transact

   `transact!` will refuse any tx that touches an attribute it doesn't
   recognize. This is deliberate: it means the DB never accumulates
   silent typos, and every attribute carries a Malli schema you and
   future code can introspect. Register first, transact second:

     (require '[seon.schema :as schema])
     (schema/register! ::name :string)
     (schema/register! ::rank :int)
     (schema/register! ::tags [:vector :keyword])

     (db/transact! {::db/tx-data [{::name \"Alpha\" ::rank 1 ::tags [:demo]}]})

   `:db/*` system attributes (`:db/ident`, `:db/valueType`, etc.) used
   to declare the datahike schema itself bypass the gate. Vector tuples
   (`[:db/add e a v]`, `[:db/retract e a v]`) flow through, but only
   their attribute is checked — entity-map values are what get full
   Malli validation. `transact!` never throws into your eval — every
   failure (invocation shape, unregistered attr, bad value, datahike
   commit explosion) comes back as `{::db/ok? false ::db/error …}` with
   `:seon.error/kind` tagged `:user-input` vs `:substrate-bug`.

   ## Reads are synchronous, writes return a channel

   `query`, `pull`, `entity` are sync — they resolve against the current
   db value (`@*conn*`), so you compose them in straight-line code:

     (let [eid (db/query {::db/query '[:find ?e .
                                       :in $ ?n
                                       :where [?e ::name ?n]]
                          ::db/args  [\"Alpha\"]})]
       (db/pull {::db/pull-pattern '[*]
                 ::db/ref          eid}))

   `transact!` is `^:async` and returns a Promise with an envelope:

     (let [{::db/keys [ok? tx-report error]}
           (await (db/transact! {::db/tx-data [...]}))]
       (if ok?
         (handle-success tx-report)
         (handle-failure error)))

   The envelope is the ONLY return shape — both validation failures
   and datahike-side commit failures land as `::db/ok? false` with
   `:seon.error/kind` (`:user-input` vs `:substrate-bug`) in the
   error's `:seon.error/data`. The agent eval boundary is never asked
   to catch a throw from this surface (spec-02 §2.5 safe-by-default).

   ## Reactions: register a listener with a key

   `listen!` installs a tx-listener under a key. Multiple keys coexist;
   they each receive every tx-report. To stop a listener, retract it by
   key — there's no \"unsubscribe from one channel of many\"; one
   listener, one key.

   Handler input is rich — every key fully namespaced, pick what you
   want, ignore the rest:

     :seon.db/tx-report   full raw datahike report (escape hatch)
     :seon.db/db          the post-commit db value, ready to query
     :seon.db/db-before   the pre-commit db value, for change-detection
     :seon.db/datoms      [{:seon.db/e :seon.db/a :seon.db/v
                            :seon.db/tx :seon.db/added?} ...]
     :seon.db/attr-index  {:my.ns/attr [datoms-touching-it ...]}
                          — pre-grouped (keys are user-domain attrs;
                          values are vectors of :seon.db/datom maps)

   The `:seon.db/db` key means handlers don't reach back to `*conn*`.
   You query the exact post-commit state of the tx that fired you — no
   stale-read races even when the listener queue is backed up.

   ## The canonical reaction: kick your own agent loop on a user message

   The default agent boot wires this listener. Read it as your own
   self-description — this is how you start running when the user
   addresses you, and how you stop running when you have nothing left
   to say:

     (require '[seon.db :as db] '[seon.schema :as schema])

     ;; ---- schemas: registered once at boot ----
     (schema/register! :seon.message/role
                       [:enum :user :assistant :system])
     (schema/register! :seon.message/content   :string)
     (schema/register! :seon.message/agent     :seon.db/ref)
     (schema/register! :seon.agent/state
                       [:enum :idle :running])

     ;; ---- the kick-the-loop reaction ----
     (defn kick-on-user-message
       \"Trigger handler. Fires on every tx. For each newly-added
        :seon.message/role datom whose value is :user, look at the
        agent that owns the message and decide whether to spawn a
        turn.\"
       [{::db/keys [db attr-index]}]
       (doseq [{::db/keys [e added?]} (:seon.message/role attr-index)
               :when added?
               :let [msg (db/entity {::db/db db ::db/ref e})]
               :when (= :user (:seon.message/role msg))
               :let [agent (:seon.message/agent msg)
                     state (:seon.agent/state agent)]]
         (case state
           ;; Loop already running — it will see the new message on its
           ;; next ctx-build and respond to it. Doing nothing here is
           ;; the idempotency guarantee: you can paste 10 user messages
           ;; in a row and exactly one loop runs, processing all of them.
           :running nil
           ;; Idle (or nil for a fresh agent). Flip the state and run
           ;; one turn. The loop owns its own continuation — once it
           ;; has the `:running` flag it'll keep ticking until it
           ;; decides to stop.
           (do
             (db/transact!
               {::db/tx-data [{:db/id (:db/id agent)
                               :seon.agent/state :running}]})
             (seon.agent/run-turn-once!
               (:seon.agent/id agent) (seon.agent/home-ns (:seon.agent/id agent))
               llm-fn compile-state))))

     (db/listen! {::db/key     ::user-message-trigger
                  ::db/handler kick-on-user-message})

   ### How the loop stops itself

   Each turn of `seon.agent/run-turn-once!` does:

     1. build ctx from the agent's messages (user + prior assistant
        + tool-call results),
     2. call the LLM,
     3. parse the response:
        - if it has tool calls → execute them, transact their results
          as `:seon.tool-call/*` entities, recurse for another turn
          (the tool-call results re-trigger ctx-build; the LLM gets
          its own outputs back),
        - if it's plain text with no tool calls → transact an
          `:seon.message/role :assistant` entity carrying the text,
          set `:seon.agent/state :idle`, return.

   The exit condition is what LLMs do most of the time anyway: when
   they have nothing useful left to do, they return prose. That prose
   becomes an `:assistant`-role message; the listener above ONLY fires
   on `:user`-role additions; the assistant's own message doesn't
   re-trigger the loop. So:

   - User types something → loop kicks → runs until LLM is done →
     halts. Idle.
   - User types again → loop kicks again. Idempotency means even if
     they paste fast, only one loop runs at a time.
   - The loop NEVER kicks itself by writing its own messages — that
     would be infinite recursion. The asymmetric trigger
     (`:user`-only) is the load-bearing piece.

   ### What if the LLM never stops calling tools?

   Bound the tick count. `run-agent-tick!` carries a `:tick` counter;
   when it crosses (say) 20 without producing a text response, the
   loop transacts a synthetic assistant message saying so, sets state
   `:idle`, and exits. The user can kick it again with a fresh
   prompt. Bounded recursion + the user as oracle on \"are we done?\"
   is the right shape for a system the user can supervise.

   ### Removing the reaction

   If you want to stop reacting to user messages (for instance, you
   want a session that the user can append to but you don't auto-
   respond to), just retract the listener:

     (db/unlisten! {::db/key ::user-message-trigger})

   The session's message log keeps growing; no loops spawn. To bring
   it back, register the handler again.

   ### Triggers as data (V0-B-5)

   `seon.trigger/register!` will let you persist this same reaction as
   an entity in the DB — `:seon.trigger/signature` + `:seon.trigger/
   handler-symbol` — so the agent and user can both see what's wired
   and retract any single one without touching the others. The Shape B
   dispatcher under the hood is one `seon.db/listen!` call with key
   `::seon.trigger/dispatcher`. Direct `db/listen!` (above) is the
   primitive; `seon.trigger` is the data-driven layer on top."
  (:require
    [datahike.api :as d]
    [malli.core :as m]
    [seon.error :as error]
    [seon.schema :as schema]))

;; ---------------------------------------------------------------------------
;; Schemas — registered at namespace load. seon.schema is global + atom-backed,
;; so re-loads idempotently overwrite the same keys.
;; ---------------------------------------------------------------------------

;; Every key in every map seon.db hands out — request, response, handler
;; input, decoded datom — is fully namespaced under `:seon.db/*`. This is
;; not stylistic: it's what lets the agent (or the user, or operator
;; tooling) join function specs to data with a single Datalog query. A
;; single-segment `:tx-data` key carries no information about which fn
;; owns it; `:seon.db/tx-data` does.

(schema/register! ::tx-data [:vector :any])
(schema/register! ::opts :map)
(schema/register! ::conn :any)
;; `::tx-meta` — the positional 3-arity convenience slot. datahike has NO
;; positional tx-meta arg (it rides inside the arg-map under `:tx-meta` —
;; see research/datahike-api-forms-2026-06-08.md §2); seon exposes a
;; 3-arity `(transact! conn tx-data tx-meta)` since seon already nests
;; tx-meta under `::opts {:tx-meta …}`. The slot is a plain map.
(schema/register! ::tx-meta :map)

(schema/register!
  ::transact-request
  [:map
   [::tx-data ::tx-data]
   [::opts    {:optional true} ::opts]
   [::conn    {:optional true} ::conn]])

;; ---------------------------------------------------------------------------
;; Envelope contract (locked 2026-05-26).
;;
;; `transact!` NEVER throws into the calling agent's eval context. Every
;; failure path — invocation-shape guard, unregistered-attr gate, Malli
;; value validation, datahike commit explosion — is caught at the boundary
;; and returned as data.
;;
;; The agent's eval surface generates malformed tx-data routinely (LLM
;; hallucinations, partial-typed maps, refs to entities that don't exist).
;; Throwing from `db/transact!` would crash the eval loop and leave the
;; agent unable to recover its own state. Returning an envelope keeps the
;; pod alive and lets downstream code (renderers, retry logic, the agent
;; itself) inspect the failure as just another map.
;;
;; The envelope distinguishes two error kinds via `:seon.error/data`:
;;   :user-input   — caller-fault: bad shape, unregistered attr, value
;;                    fails its Malli schema, invalid invocation. The agent
;;                    should fix its tx-data and retry.
;;   :substrate-bug — anything else (typically a throw from datahike's
;;                    internals). The pod recovered; the substrate didn't.
;;                    Worth surfacing to ops tooling, not retrying blindly.
;; ---------------------------------------------------------------------------

(schema/register!
  ::error
  [:map
   [:seon.error/message :string]
   [:seon.error/data    {:optional true} :map]
   [:seon.error/ex-data {:optional true} :map]
   [:seon.error/stack   {:optional true} :string]
   [:seon.error/cause   {:optional true} :map]
   [:seon.error/raw     {:optional true} :any]
   [:seon.error/truncated {:optional true} :boolean]])

(schema/register!
  ::transact-response
  [:or
   [:map
    [::ok?       [:= true]]
    [::tx-report :any]]
   [:map
    [::ok?       [:= false]]
    [::error     ::error]
    ;; When the substrate translated a cryptic datahike message into a
    ;; guiding one, the ORIGINAL message is preserved here verbatim.
    [::raw-error {:optional true} :string]]])

(schema/register!
  ::query-request
  [:map
   [::query :any]
   [::args  {:optional true} [:vector :any]]
   [::db    {:optional true} :any]
   [::conn  {:optional true} ::conn]])

(schema/register!
  ::pull-request
  [:map
   [::pull-pattern :any]
   [::ref          :any]
   [::db           {:optional true} :any]
   [::conn         {:optional true} ::conn]])

(schema/register!
  ::entity-request
  [:map
   [::ref  :any]
   [::db   {:optional true} :any]
   [::conn {:optional true} ::conn]])

;; ---------------------------------------------------------------------------
;; Positional read-op slot schemas (T15 — agent-facing positional db ops).
;;
;; The agent-facing read ops (`query`/`pull`/`entity`) keep their map-in
;; arity (the 179 internal callers route through it unchanged) AND gain a
;; datahike-shaped POSITIONAL arity. The positional form makes the db/conn
;; an EXPLICIT, required slot — no ambient `*conn*` fallback — so nothing
;; depends on the mutable root of `*conn*` (the smell this cures).
;;
;; `::db-val` is the ONE canonical "a datahike db value" shape (a record
;; that satisfies the map protocol). Every positional `:db` slot references
;; it — never inline `[:fn map?]` per op (shared-shape rule). Dispatch is
;; uniform across the ops: map-in iff the first arg is a map carrying that
;; op's request key (`::query`/`::pull-pattern`/`::ref`); a db value is
;; `map?`-true but carries NONE of those keys, so it never masquerades as a
;; request map. (Verified live 2026-06-08: `@conn` is `map?`-true with
;; `(contains? db :seon.db/query)` => false, etc.)
;; ---------------------------------------------------------------------------

;; NOTE on instrumentation: `m/-instrument` requires each `:=>` arity's
;; INPUT to be an inline `:cat`/`:catn` — it inspects the input schema's
;; type directly and does NOT deref a registered keyword reference (a
;; bare `[:=> ::query-positional …]` throws `:malli.core/invalid-input-schema`,
;; verified live 2026-06-08). So the positional `:catn` is authored INLINE
;; in each fn's `:function` schema below. The shared-shape rule still holds
;; via `::db-val`: the repeated "datahike db value" shape is registered ONCE
;; here and referenced (by keyword) in every positional `:db` slot — never
;; inlined as `[:fn map?]` per op.
(schema/register! ::db-val [:fn map?])

(schema/register!
  ::datom
  [:map
   [::e      :int]
   [::a      :keyword]
   [::v      :any]
   [::tx     :int]
   [::added? :boolean]])

(schema/register!
  ::handler-input
  [:map
   [::tx-report  :any]
   [::db         :any]
   [::db-before  :any]
   [::datoms     [:vector ::datom]]
   [::attr-index [:map-of :keyword [:vector ::datom]]]
   [::trigger    {:optional true} :any]])

(schema/register!
  ::listen-request
  [:map
   [::handler [:fn fn?]]
   [::key     {:optional true} :any]
   [::conn    {:optional true} ::conn]])

(schema/register!
  ::listen-response
  [:map [::key :any]])

(schema/register!
  ::unlisten-request
  [:map
   [::key  :any]
   [::conn {:optional true} ::conn]])

(schema/register!
  ::unlisten-response
  [:map [::ok? :boolean]])

;; ---------------------------------------------------------------------------
;; Tx-meta attrs (v1.md §2.3) — the causality bundle attached to every tx.
;;
;; These are registered HERE (not in seon.eval or seon.agent) for two reasons:
;;
;;   1. They live in the `:seon.db/*` namespace; seon.db owns it.
;;   2. The `transact!` auto-merge path below reads `current-tx-context` and
;;      writes these keys to `:tx-meta`. Datahike's `flush-tx-meta` rejects
;;      unregistered keys at write time — so they MUST be registered before
;;      any `(seon.db/transact! …)` call fires. Registering at namespace
;;      load (here) means by the time the agent boots, they're known.
;;
;; `assert-preconditions!` (below) double-checks this at boot so a
;; misconfigured registry fails loud instead of crashing the first tx.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; ID generation — single source of truth for both seon.agent (which used to
;; own it) and seon.eval (which used to keep a duplicate to dodge the
;; agent↔eval require cycle). Lives in seon.db because (a) every id exists
;; to identify a DB entity, (b) the id-shape schemas already live here, and
;; (c) seon.db is required by both agent and eval — no cycle.
;;
;; Shape (locked 2026-05-23): <3-letter-random>-<YYMMDDHHmm>, 14 chars.
;; Example: Kpx-2605232138.
;;
;; LLM reads wall-clock time directly from the body; random prefix gives
;; visual distinguishability for same-minute ids AND guarantees letter-
;; leading (so home-ns derivation works without special-casing).
;;
;; Design rationale, REPL-verified probes, and trade-offs:
;; docs/prds/agent-runtime/research/id-generator-design-2026-05-23.md.
;; ---------------------------------------------------------------------------

(def ^:private id-letters
  "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ")

(defn- id-pad-2 [n]
  (if (< n 10) (str "0" n) (str n)))

(defn- id-rand-letter []
  (nth id-letters (rand-int 52)))

(defn new-id!
  "Generate a fresh 14-char LLM-readable id with shape
   `<3-letter-random>-<YYMMDDHHmm>`. Examples: `Kpx-2605232138`,
   `Bcd-2605232138`, `Mng-2606010900`.

   Same-minute collision math: 3-char letter random = 140K slots →
   birthday risk only at ~370 entities/minute. Datahike's tx-id is
   the canonical creation order for sub-minute ordering; the id's
   time portion is for LLM readability + cross-pod stable reference
   frame, not for in-memory sort."
  []
  (let [d        (js/Date.)
        time-str (str (id-pad-2 (mod (.getFullYear d) 100))
                      (id-pad-2 (inc (.getMonth d)))  ; JS month 0-based
                      (id-pad-2 (.getDate d))
                      (id-pad-2 (.getHours d))
                      (id-pad-2 (.getMinutes d)))
        rand-str (str (id-rand-letter) (id-rand-letter) (id-rand-letter))]
    (str rand-str "-" time-str)))

(defn id->time-str
  "Extract the YYMMDDHHmm portion of an id for programmatic time
   sorting / comparison. Returns nil if the id doesn't match the
   expected shape."
  [id]
  (when (and (string? id) (= 14 (count id)) (= \- (nth id 3)))
    (subs id 4)))

;; Tx-meta id scalars — bare references to the canonical :seon.db/id
;; shape registered in seon.schema. One source of truth; bumping the
;; id length updates every constraint.
(schema/register! ::agent-id        :seon.db/id)
(schema/register! ::session-id      :seon.db/id)
(schema/register! ::turn-id         :seon.db/id)
(schema/register! ::eval-id         :seon.db/id)
(schema/register! ::origin          [:enum :user :agent :system :replay :substrate-seed :test-run])
(schema/register! ::replay?         :boolean)
(schema/register! ::resume-marker?  :boolean)

(def ^:private tx-meta-attrs
  "Set of attr keywords the tx-meta auto-merge writes. Used by
   `assert-preconditions!` to confirm registration. Update both lists
   together when adding new tx-meta attrs."
  #{::agent-id ::session-id ::turn-id ::eval-id
    ::origin ::replay? ::resume-marker?})

;; ---------------------------------------------------------------------------
;; The agent's universe. Bound by the session-flow boundary at session start;
;; nil outside a bound scope. Test/admin code may pass `:conn` explicitly per
;; the spec's escape-hatch posture.
;; ---------------------------------------------------------------------------

(def ^:dynamic *conn*
  "The runtime's datahike connection. Bound by the session-flow boundary at
   session start; never threaded through agent call sites. Reads default to
   `@*conn*` (a db value); writes route through this conn's writer (V0:
   local datahike-cljs; V1+: `:writer :kabel`, transparent to callers).

   For a CLJS user-pod runtime, this is the user's DB. All sessions for the
   same user share this conn — sessions are entities in it, not partitions
   of it."
  nil)

;; ---------------------------------------------------------------------------
;; *tx-context* — the causality bundle for every tx in an eval scope.
;;
;; v1.md §2.3 establishes the contract:
;;   - `eval-batch!` enters a `(with-tx-context {…} f)` scope around each
;;     form's per-form work.
;;   - `transact!` reads `(current-tx-context)` and deep-merges into
;;     `:tx-meta`. Explicit `opts.tx-meta` wins per-key.
;;
;; We DO NOT use a CLJS `^:dynamic` Var here, even though that's the
;; idiomatic Clojure spelling. CLJS `binding` macroexpands to
;; `(set! var :new)` + `(try … (finally (set! var orig)))` against ONE
;; global slot — it survives single-binder cases but silently clobbers
;; under overlapping awaits when two `^:async` fns each bind it (see
;; `research/impl-finding-tx-context-promise-2026-05-22.md` Probe 13).
;; v1 supports concurrent agents in one pod, so a fiber-local primitive
;; is required.
;;
;; `node:async_hooks/AsyncLocalStorage` IS fiber-local: V8 instruments
;; the async context propagation at the engine level so a `.run`-scoped
;; store survives across any `await`s (real timers, microtasks,
;; rejections, nested ^:async calls) AND does not interfere with
;; concurrent `.run`s in other fibers. Probe 14 verified this under the
;; same adversarial interleaving that broke Probe 13.
;;
;; The require is at top-level so a pod missing `node:async_hooks`
;; fails loudly at ns load instead of silently at first transact.
;; Phase 3 (WASM cutover) needs an ALS-equivalent in wasm-rquickjs;
;; tracked as a separate D13-WASM prerequisite.
;; ---------------------------------------------------------------------------

(defonce ^:private als-instance
  (let [AsyncLocalStorage (.-AsyncLocalStorage (js/require "node:async_hooks"))]
    (AsyncLocalStorage.)))

(defn current-tx-context
  "Return the active tx-context map, or nil if no `with-tx-context` is
   in scope. Reads from AsyncLocalStorage — fiber-local across awaits,
   safe under concurrent agents.

   Auto-merged into every `transact!` call's `:tx-meta`. Explicit
   `opts.tx-meta` keys on the call site win per-key."
  []
  (let [store (.getStore als-instance)]
    ;; Outside a `.run` scope the JS getStore returns undefined; CLJS
    ;; treats that as nil in `when`-position. Be explicit anyway —
    ;; callers downstream `merge` on the result.
    (when (some? store) store)))

;; ---------------------------------------------------------------------------
;; agent-id-als — fiber-local agent identity for the dynamic extent of an
;; agent's work. Distinct from `als-instance` (tx-context) so non-DB code
;; paths (inspectors, section fns, web handlers) can read the active
;; agent-id without depending on tx-context machinery.
;;
;; Same ALS substrate, same propagation guarantees (survives ^:async /
;; await boundaries; concurrent fibers see only their own scope). Wired
;; from `seon.client/start-agent!` so all downstream calls — boot,
;; replay, turn loops, listeners — see the agent's id via
;; `(seon.db/current-agent-id)`.
;;
;; The `transact!` auto-merge below stamps `:seon.db/agent-id` into
;; tx-meta when this ALS has a value AND the caller didn't already supply
;; one in `(:tx-meta opts)` OR in the tx-context scope. Audit P1 fix.
;; ---------------------------------------------------------------------------

(defonce ^:private agent-id-als
  (let [AsyncLocalStorage (.-AsyncLocalStorage (js/require "node:async_hooks"))]
    (AsyncLocalStorage.)))

(defn current-agent-id
  "Return the active agent-id (string), or nil if no `with-agent` is in
   scope. Reads from AsyncLocalStorage — fiber-local across awaits, safe
   under concurrent agents. The standard accessor for any code (inspector,
   section fn, web handler, REPL convenience) that needs to know whose
   universe it's running in."
  []
  (let [store (.getStore agent-id-als)]
    (when (some? store) store)))

(defn with-agent
  "Establish an agent-id scope for the dynamic extent of `f` (a 0-arg
   fn). Inside `f`, `(current-agent-id)` returns `agent-id`. Survives
   ^:async / await boundaries — Promises returned by `f` carry the
   scope through their continuations.

   Used by `seon.client/start-agent!` to wrap the full boot + run-loop
   pipeline so every downstream call site (listeners, eval-batch, web
   handlers, inspectors) can read the agent-id without threading it
   through every argument list.

   Nesting: the inner `agent-id` wins for the duration of the inner
   scope; the outer value restores on exit."
  [agent-id f]
  (.run agent-id-als agent-id f))

(defn with-tx-context
  "Establish a tx-context for the dynamic extent of `f` (a 0-arg fn).
   Nested calls MERGE: the new `ctx-map` is merged on top of any
   already-active context.

   Returns whatever `f` returns — including a Promise, in which case
   the context propagates across `await` points inside `f` and any
   `^:async` fn `f` calls.

     (seon.db/with-tx-context
       {:seon.db/origin :agent
        :seon.db/agent-id agent-id}
       (fn []
         (seon.db/transact! {:seon.db/tx-data [...]})))   ; auto-tagged

   The ctx-map's keys are typically the 7 `:seon.db/*` tx-meta attrs
   (see `tx-meta-attrs` above). Any registered scalar attr is allowed
   — `seon.db/transact!` will fail at write time if the key isn't
   registered in `seon.schema`.

   Implementation note: the merging behavior makes nested-scope
   tx-meta natural — e.g. `eval-batch!` opens a turn-scoped ctx;
   a sub-call that wants to add `:seon.db/origin :replay` for one
   form just wraps that form with the override, and turn-id /
   agent-id flow through unchanged."
  [ctx-map f]
  (let [current (current-tx-context)
        merged  (merge current ctx-map)]
    (.run als-instance merged f)))

;; ---------------------------------------------------------------------------
;; Validation gate — mirrors `seon.db/transact!` 60–135 on JVM byte-for-byte
;; so the eventual .cljc merge is mechanical. Any change here must mirror
;; into the JVM file (and vice versa) until convergence.
;; ---------------------------------------------------------------------------

(defn- system-attr?
  "True for `:db/*` system attributes that drive datahike's own schema
   layer and should not be validated against the seon Malli registry."
  [k]
  (and (keyword? k)
       (= "db" (namespace k))))

(defn- extract-tx-attrs
  "Walk tx-data and collect every attribute keyword that appears. Handles
   both entity-map forms (`{:attr v ...}`) and vector tuple forms
   (`[:db/add e a v]`, `[:db/retract e a v]`). Tempids and metadata not
   keyed by a true attribute are filtered out."
  [tx-data]
  (into #{}
        (mapcat (fn [datum]
                  (cond
                    (map? datum)
                    (keys datum)

                    (and (vector? datum) (>= (count datum) 3))
                    [(nth datum 2)]

                    :else nil)))
        tx-data))

(defn- validate-attrs!
  "Ensure every non-`:db/*` attribute appearing in `tx-data` is registered
   in `seon.schema`. Throws `ex-info` with `{:unregistered [...]}` listing
   the offenders. Caller is expected to register schemas first."
  [attrs]
  (let [domain-attrs (remove system-attr? attrs)
        unregistered (into [] (remove schema/registered?) domain-attrs)]
    (when (seq unregistered)
      (throw (ex-info (str "Unregistered attributes in transaction: "
                           (pr-str unregistered)
                           ". Register them with seon.schema/register! first.")
                      {::error            :seon.db/unregistered-attrs
                       ::unregistered     unregistered
                       :seon.error/kind   :user-input})))))

(defn- truncate-value
  "Truncate a value's `pr-str` representation to 100 chars for error
   messages — keeps error payloads readable when a malformed value is
   large (e.g. a stringified pull pattern)."
  [v]
  (let [s (pr-str v)]
    (if (> (count s) 100)
      (str (subs s 0 97) "...")
      s)))

;; Forward declares — bridge helpers used by validation live further
;; down in the file (kept grouped with the rest of the Malli→datahike
;; schema-translation code). The validation gate references them here
;; to detect ref-typed attrs that accept datahike's nested-map
;; shorthand. CLJS doesn't allow implicit forward references.
(declare ^:private resolve-malli-form)
(declare ^:private form-head)
(declare ^:private form-children)
(declare ^:private ensure-datahike-attrs!)

(defn- ref-attr-arity
  "If the attr's resolved Malli schema describes a ref slot, returns
   `:one` (single ref) or `:many` (container of refs). Returns `nil`
   if the schema isn't a ref slot.

   Arity matters because the validation gate's nested-map-shorthand
   path branches differently:

   - `:one` — value may be a single map (validate as nested entity),
     OR a single ref-shape (eid, lookup tuple). A lookup tuple is
     itself a 2-element vector — we MUST NOT iterate it as a
     container or we'd validate the keyword + string separately.
   - `:many` — value is a sequential of mixed (maps + refs); iterate
     and validate each child."
  [schema-form]
  (let [resolved (resolve-malli-form schema-form)
        head     (form-head resolved)]
    (cond
      (= resolved :seon.db/ref) :one

      (and (#{:vector :set :sequential} head)
           (when-let [child (first (form-children resolved))]
             (= :seon.db/ref (resolve-malli-form child))))
      :many

      :else nil)))

(declare validate-entity-values!)

(defn- validate-ref-child!
  "Validate one entry inside a ref-typed slot. A map is treated as
   datahike's nested-entity shorthand (recursively validated against
   the children's own per-attr schemas); anything else must satisfy
   `:seon.db/ref` (eid, lookup tuple, or temp-id keyword)."
  [parent-attr child]
  (cond
    (map? child)
    (validate-entity-values! child)

    (m/validate :seon.db/ref child)
    nil

    :else
    (throw (ex-info (str "Malli validation failed for " parent-attr
                         " child: expected map or :seon.db/ref, got "
                         (truncate-value child))
                    {::error              :seon.db/invalid-ref-child
                     ::attr               parent-attr
                     ::actual-value       child
                     ::malli-explanation  (m/explain :seon.db/ref child)
                     :seon.error/kind     :user-input}))))

(defn- validate-entity-values!
  "Validate each `[attr v]` pair in an entity map against its registered
   Malli schema. Skips system attrs and unregistered attrs (the latter
   should have been caught by `validate-attrs!`).

   Special-cases ref-typed attrs (see [[ref-attr-arity]]): accepts
   datahike's nested-map shorthand in place of explicit refs. A map
   value is recursively validated against the children's own per-attr
   schemas; the outer ref-type check is skipped because datahike will
   turn the map into an entity at write time.

   Arity matters: a single-ref slot whose value is a 2-element vector
   like `[:seon.agent/id \"seon\"]` is a LOOKUP REF, not a container
   of refs. We must dispatch on schema-declared arity, not on the
   value's `sequential?` shape, or we'd iterate the lookup tuple's
   keyword + string and validate them as separate refs."
  [entity]
  (doseq [[attr val] entity]
    (when (and (not (system-attr? attr))
               (schema/registered? attr))
      (let [schema-form (schema/schema-definition attr)
            arity       (ref-attr-arity schema-form)]
        (cond
          ;; Single-card ref slot.
          (= arity :one)
          (cond
            ;; Nested-map shorthand → recurse as entity.
            (map? val)
            (validate-entity-values! val)
            ;; Anything else (eid, lookup tuple, ident) → validate as ref.
            :else
            (when-not (m/validate :seon.db/ref val)
              (throw (ex-info (str "Malli validation failed for " attr
                                   ": expected :seon.db/ref (eid, lookup "
                                   "tuple, or nested entity map), got "
                                   (truncate-value val))
                              {::error             :seon.db/invalid-value
                               ::attr              attr
                               ::expected-schema   schema-form
                               ::actual-value      val
                               ::malli-explanation (m/explain :seon.db/ref val)
                               :seon.error/kind    :user-input}))))

          ;; Many-card ref slot — iterate children, each may be a
          ;; map (nested entity), eid, or lookup tuple.
          (= arity :many)
          (when (sequential? val)
            (doseq [child val]
              (validate-ref-child! attr child)))

          ;; Normal scalar / non-ref path — validate against the schema.
          :else
          (when-not (m/validate attr val)
            (throw (ex-info (str "Malli validation failed for " attr
                                 ": expected " (pr-str schema-form)
                                 ", got " (truncate-value val))
                            {::error              :seon.db/invalid-value
                             ::attr               attr
                             ::expected-schema    schema-form
                             ::actual-value       val
                             ::malli-explanation  (m/explain attr val)
                             :seon.error/kind     :user-input}))))))))

(defn- validate-values!
  "Walk tx-data and validate every entity map. Vector tuple forms
   (`[:db/add ...]`, `[:db/retract ...]`) carry only one attribute and
   are best validated through their declared Malli schema by the caller;
   this gate doesn't try to type-check vector tuples (the JVM impl makes
   the same call)."
  [tx-data]
  (doseq [datum tx-data]
    (when (map? datum)
      (validate-entity-values! datum))))

(defn- resolve-conn
  "Resolve a caller-supplied or default `*conn*`. Throws a clear error if
   neither is set — that almost always means `seon.db/*conn*` hasn't been
   bound at the session-flow boundary yet, or you're calling from outside
   a session scope."
  [conn]
  (or conn
      (throw (ex-info
               (str "seon.db: *conn* is unbound and no :seon.db/conn was "
                    "passed. Bind via session-flow setup, or pass "
                    "::db/conn explicitly.")
               {::error          :seon.db/no-conn
                :seon.error/kind :substrate-bug}))))

;; ---------------------------------------------------------------------------
;; Public write path
;; ---------------------------------------------------------------------------

(defn- conn?
  "A datahike conn is an `IDeref` that is NOT a map (verified live
   2026-06-08: `(map? conn)` => false, `(satisfies? IDeref conn)` =>
   true; a db VALUE is `map?`-true). Used by `normalize-transact-args` to
   tell a positional conn slot apart from a stray request map / db value."
  [x]
  (and (satisfies? IDeref x) (not (map? x))))

(defn- normalize-transact-args
  "Normalize `transact!`'s variadic args into the canonical map-in
   request map `{::tx-data … ::opts … ::conn …}` that the rest of the
   body and `assert-invocation-shape!` already understand. T15: the
   public surface accepts BOTH shapes.

   Dispatch (the chunk-1 finding — a db/conn value must never be mistaken
   for a request map): the FIRST arg decides.
     - a map containing `::tx-data`  -> map-in (passed through verbatim).
     - otherwise                     -> positional, first arg is the conn.
   A conn is `map?`-false and tx-data is a vector, so a positional first
   arg never collides with a `::tx-data`-bearing request map.

   Positional forms (mirror datahike `(d/transact! conn tx-data)`; seon
   adds a 3-arity tx-meta convenience since it nests tx-meta under
   `::opts {:tx-meta …}`):
     (transact! conn tx-data)          ==> {::conn c ::tx-data td}
     (transact! conn tx-data tx-meta)  ==> {::conn c ::tx-data td
                                            ::opts {:tx-meta tm}}

   Throws `:user-input` ex-info (caught upstream into an envelope, never
   into agent eval) for a malformed positional call — non-conn first arg,
   missing tx-data, or non-map tx-meta. A malformed map-in call is left
   to `assert-invocation-shape!`, which already produces a clear message."
  [args]
  (let [a0 (first args)]
    (cond
      ;; map-in: one request map carrying `::tx-data`. Pass through; the
      ;; existing guard validates the rest.
      (and (map? a0) (contains? a0 ::tx-data))
      a0

      ;; A lone map WITHOUT `::tx-data` is a malformed map-in call — let
      ;; the guard name the missing key / unqualified-key hint.
      (and (= 1 (count args)) (map? a0))
      a0

      ;; Positional: first arg must be a conn.
      (not (conn? a0))
      (throw (ex-info
               (str "seon.db/transact!: positional call expects a datahike "
                    "CONN as the first argument (an IDeref, not a map). Got: "
                    (truncate-value a0)
                    " — call `(transact! conn tx-data)` or `(transact! conn "
                    "tx-data tx-meta)`, or use the map-in shape "
                    "`{::db/tx-data […] ::db/conn conn}`.")
               {::error          :seon.db/invalid-invocation-shape
                ::actual-shape   (type a0)
                ::actual-value   a0
                :seon.error/kind :user-input}))

      :else
      (let [[conn tx-data tx-meta & extra] args]
        (when (seq extra)
          (throw (ex-info
                   (str "seon.db/transact!: positional call takes 2 or 3 "
                        "arguments `(conn tx-data [tx-meta])`. Got "
                        (count args) " arguments.")
                   {::error          :seon.db/invalid-invocation-shape
                    ::actual-value   (vec args)
                    :seon.error/kind :user-input})))
        (when (and (some? tx-meta) (not (map? tx-meta)))
          (throw (ex-info
                   (str "seon.db/transact!: positional tx-meta (3rd arg) "
                        "must be a map. Got: " (truncate-value tx-meta))
                   {::error          :seon.db/invalid-invocation-shape
                    ::actual-value   tx-meta
                    ::actual-shape   (type tx-meta)
                    :seon.error/kind :user-input})))
        (cond-> {::conn conn ::tx-data tx-data}
          (some? tx-meta) (assoc ::opts {:tx-meta tx-meta}))))))

(defn- assert-invocation-shape!
  "KI-1 guard. `transact!` is map-in / map-out — every key namespaced
   under `:seon.db/*`. Positional invocations are normalized to this map
   shape by `normalize-transact-args` BEFORE this guard runs; an
   unqualified-key map (`{:tx-data […]}`) or a map missing `::tx-data`
   silently destructured to nil/empty and used to crash deep inside
   datahike with cryptic errors. This precondition catches that at the
   boundary with a clear message.

   Run BEFORE destructuring so the error message can name the actual
   shape received."
  [arg]
  (cond
    (not (map? arg))
    (throw (ex-info
             (str "seon.db/transact! expects ONE map argument with "
                  "`:seon.db/tx-data`. Got: " (truncate-value arg)
                  " — did you call positionally? "
                  "Use {::db/tx-data […]} or {:seon.db/tx-data […]}.")
             {::error            :seon.db/invalid-invocation-shape
              ::actual-shape     (type arg)
              ::actual-value     arg
              :seon.error/kind   :user-input}))

    (not (contains? arg ::tx-data))
    (let [unqualified-tx-data (get arg :tx-data ::not-present)
          hint                (if (not= unqualified-tx-data ::not-present)
                                " — Hint: keys must be namespaced. Use `:seon.db/tx-data`, not bare `:tx-data`."
                                "")]
      (throw (ex-info
               (str "seon.db/transact!: missing `:seon.db/tx-data` key."
                    hint
                    " Got keys: " (pr-str (vec (keys arg))))
               {::error            :seon.db/invalid-invocation-shape
                ::missing          :seon.db/tx-data
                ::actual-keys      (vec (keys arg))
                :seon.error/kind   :user-input})))

    ;; tx-data must be a sequential collection. Strings, JS objects,
    ;; numbers, nil — anything non-sequential — is a caller fault
    ;; (LLM hallucination, wrong-shape eval). Catch it here at the
    ;; shape guard so it's classified `:user-input`. Without this
    ;; check, the value flows into `extract-tx-attrs`/`mapcat` which
    ;; throws an opaque "X is not ISeqable" → outer catch tags it
    ;; `:substrate-bug`. That misclassification was task-9b finding 2.
    (not (sequential? (::tx-data arg)))
    (throw (ex-info
             (str "seon.db/transact!: `:seon.db/tx-data` must be a "
                  "sequential collection (vector or seq) of entity "
                  "maps or [:db/add ...] tuples. Got: "
                  (truncate-value (::tx-data arg)))
             {::error            :seon.db/invalid-invocation-shape
              ::actual-value     (::tx-data arg)
              ::actual-shape     (type (::tx-data arg))
              :seon.error/kind   :user-input}))))

(defn- merge-tx-context-into-opts
  "Merge `(current-tx-context)` AND `(current-agent-id)` into
   `opts.:tx-meta`. Explicit `(:tx-meta opts)` keys win per-key; the
   tx-context fills the next layer; the agent-id ALS fills the last.

   Precedence (highest → lowest):
     1. explicit `:tx-meta` keys passed by the caller
     2. `(current-tx-context)` keys
     3. `(current-agent-id)` → `:seon.db/agent-id` (audit P1 — every
        agent-scoped tx is auto-tagged with the originating agent)

   Returns the (possibly-updated) opts, or nil if nothing to merge AND
   nothing was passed."
  [opts]
  (let [ctx       (current-tx-context)
        agent-id  (current-agent-id)
        als-meta  (cond-> {}
                    agent-id (assoc ::agent-id agent-id))
        merged    (merge als-meta ctx)]
    (cond
      (and (nil? opts) (empty? merged))  nil
      (empty? merged)                    opts
      :else                              (update (or opts {}) :tx-meta
                                                 #(merge merged %)))))

(defn- error-envelope
  "Build a `{::ok? false ::error <error-map>}` failure envelope from a
   thrown error. Ensures `:seon.error/data` carries a `:seon.error/kind`
   tag (defaulting to `:substrate-bug` when the throw didn't ship one).
   `:user-input` is reserved for caller-fault paths — invocation shape,
   unregistered attr, value Malli failure. Anything else (datahike
   internals, store I/O, schema bridge bug) defaults to `:substrate-bug`."
  [e]
  (let [emap (error/->map e)
        data (or (:seon.error/data emap) {})
        kind (:seon.error/kind data :substrate-bug)
        emap (assoc emap :seon.error/data (assoc data :seon.error/kind kind))]
    {::ok? false ::error emap}))

(defn- translate-cryptic-error
  "A4: rewrite the two known cryptic datahike commit errors inside a
   failure envelope into guiding, agent-actionable messages. The raw
   message is preserved verbatim under `:seon.db/raw-error`. Both are
   caller-fixable, so `:seon.error/kind` is retagged `:user-input`
   (datahike throws them from its internals, which the generic
   classifier would mislabel `:substrate-bug`). Non-matching envelopes
   pass through unchanged."
  [{::keys [error] :as envelope}]
  (let [msg  (:seon.error/message error)
        exd  (:seon.error/ex-data error)
        rewrite (fn [guiding]
                  (-> envelope
                      (assoc ::raw-error msg)
                      (assoc-in [::error :seon.error/message] guiding)
                      (assoc-in [::error :seon.error/data :seon.error/kind]
                                :user-input)))]
    (cond
      (not (string? msg))
      envelope

      ;; "Bad entity attribute :x at {...}, not defined in current schema"
      (re-find #"not defined in current schema" msg)
      (let [attr (:attribute exd)]
        (rewrite
          (str "attr " (pr-str attr) " is not installed in the database "
               "schema — register it with (seon.schema/register! "
               (pr-str attr) " <type>) BEFORE transacting. If you "
               "registered it earlier this turn and still see this "
               "error, report a substrate bug.")))

      ;; "Lookup ref attribute should be marked as :db/unique"
      (re-find #"Lookup ref attribute should be marked as :db/unique" msg)
      (rewrite
        (str "lookup-ref failed: the lookup-ref's target attr must be an "
             "identity attr — add {:seon.db/identity true} to its "
             "register! call, e.g. (seon.schema/register! :my.ns/id "
             "[:string {:seon.db/identity true}]). Alternatively the "
             "referenced entity doesn't exist yet — transact it first "
             "(or use a tempid in the same tx)."))

      :else envelope)))

(defn- commit-error-envelope
  "Failure envelope + cryptic-message translation. Every catch in the
   transact path routes through this so the agent always sees the
   guiding message (with the raw one preserved)."
  [e]
  (translate-cryptic-error (error-envelope e)))

(defn- ^:async transact!*
  "The map-in commit body. `transact!` normalizes its variadic args
   (map-in OR positional) into the canonical request map first, runs the
   invocation-shape guard, then delegates here. `arg` is the canonical
   `{::tx-data … ::opts … ::conn …}` map. Returns the `{::ok? …}`
   envelope; the datahike commit failure path is caught here, the
   pre-normalization failures are caught by `transact!`."
  [arg]
  (let [{::keys [tx-data opts conn] :or {conn *conn*}} arg
        c           (resolve-conn conn)
        attrs       (extract-tx-attrs tx-data)
        merged-opts (merge-tx-context-into-opts opts)]
    (validate-attrs! attrs)
    (validate-values! tx-data)
    ;; Install datahike schema for any registered attr not yet in the
    ;; conn (e.g. one the agent just `seon.schema/register!`'d at runtime).
    ;; Schema-before-data in its own tx; skips attrs already present. See
    ;; `ensure-datahike-attrs!` for the why.
    (await (ensure-datahike-attrs! c attrs))
    (try
      ;; Datahike-cljs `d/transact!` takes one arg-map combining
      ;; `:tx-data` + `:tx-meta` (see datahike.api.impl/transact! L29-41).
      ;; The previous shape `(d/transact! c tx-data opts)` passed opts
      ;; as a third arg that datahike silently ignored — so user tx-meta
      ;; NEVER reached the db before this fix. The single arg-map shape
      ;; is the only supported call path.
      (let [arg-map (merge {:tx-data tx-data} merged-opts)
            report  (await (d/transact! c arg-map))]
        {::ok? true ::tx-report report})
      (catch :default e
        ;; Datahike-side / async commit failure. Translation rewrites
        ;; the two known cryptic messages and retags them :user-input;
        ;; anything else past the gate stays :substrate-bug.
        (commit-error-envelope e)))))

(defn ^:async transact!
  "Commit tx-data to the agent's conn. Two call shapes (T15):

   - map-in / map-out (preferred for internal callers):
       (db/transact! {::db/tx-data [{::name \"A\"}]
                      ::db/opts {:tx-meta {…}}   ; optional
                      ::db/conn <conn>})          ; optional, defaults *conn*
   - positional, mirroring datahike `(d/transact! conn tx-data)` — conn
     FIRST and explicit (no ambient `*conn*`); seon adds a 3-arity tx-meta
     convenience:
       (db/transact! <conn> [{::name \"A\"}])
       (db/transact! <conn> [{::name \"A\"}] {:source :import})

   Both shapes return the SAME envelope (see below) and are `^:async`
   (await the returned Promise). Dispatch is unambiguous: a conn is
   `map?`-false, so a positional first arg never collides with a
   `::db/tx-data`-bearing request map.

   ## Safe-by-default — never throws into agent eval

   Every failure path returns an envelope; the agent's eval loop never
   sees a thrown exception from this surface. Validation failures,
   invocation-shape errors, and datahike commit explosions are all
   caught at the boundary and shipped back as data.

   Validates BEFORE reaching datahike:
     1. invocation shape is `{:seon.db/tx-data […] …}` (KI-1 guard).
     2. every non-`:db/*` attribute in tx-data is registered;
     3. every value in an entity-map form satisfies its attribute's
        registered Malli schema.

   ## Auto-merged tx-meta (v1.md §2.3)

   If a `(seon.db/with-tx-context {…} f)` scope is active when this
   call fires, the active context map is merged into `opts.:tx-meta`.
   Explicit `:tx-meta` keys on the call site win per-key. The merged
   bundle reaches datahike via `(d/transact! conn arg-map)`, landing
   every key as a datom on the tx entity (requires `:keep-history? true`
   on the conn — see `assert-preconditions!`).

   Returns a Promise resolving to one of:

     {:seon.db/ok? true  :seon.db/tx-report <datahike report>}      ; ok
     {:seon.db/ok? false :seon.db/error <seon.error/->map e>}       ; fail
     ;; + :seon.db/raw-error <original message string> when the
     ;; substrate translated a cryptic datahike error into a guiding one

   The error envelope's `:seon.error/data` map carries
   `:seon.error/kind`:

     :user-input    — caller-fault: bad invocation shape, unregistered
                      attr, value fails its Malli schema. Fix tx-data
                      and retry.
     :substrate-bug — datahike internals or other unexpected throw. The
                      pod is still alive; the substrate didn't deliver
                      the commit. Worth surfacing to ops, not retrying
                      blindly.

   Example:

     (require '[seon.db :as db] '[seon.schema :as schema])
     (schema/register! ::name :string)
     (schema/register! ::rank :int)
     (let [{::db/keys [ok? tx-report error]}
           (await (db/transact! {::db/tx-data [{::name \"Alpha\" ::rank 1}]}))]
       (if ok?
         (process tx-report)
         (handle error)))"
  ;; The `:function` schema documents BOTH arities as the discoverable
  ;; contract. NOTE (the T15 caveat): `^:async` fns are SKIPPED by
  ;; `seon.instrument/collect-registrations` (instrument! can't await a
  ;; Promise before output validation), so this schema is NOT runtime-
  ;; enforced by Malli today — the hand-rolled `normalize-transact-args`
  ;; + `assert-invocation-shape!` + envelope do the guarding. When the
  ;; async-aware instrument wrapper lands, this schema enforces with no
  ;; further change.
  {:malli/schema
   [:function
    [:=> [:cat ::transact-request] ::transact-response]
    [:=> [:catn [::conn ::conn] [::tx-data ::tx-data]] ::transact-response]
    [:=> [:catn [::conn ::conn] [::tx-data ::tx-data] [::tx-meta ::tx-meta]]
         ::transact-response]]}
  [& call-args]
  (try
    (let [arg (normalize-transact-args call-args)]
      (assert-invocation-shape! arg)
      ;; AWAIT is load-bearing (Run-5 / A4): `transact!*` is ^:async, so
      ;; a throw inside it (validate-attrs!, validate-values!,
      ;; ensure-datahike-attrs!) surfaces as a REJECTED Promise, not a
      ;; sync throw. Returning the un-awaited Promise let those
      ;; rejections sail past this catch and escape to the caller /
      ;; unhandledRejection net (live: pod.log:3660) — the agent's eval
      ;; captured nothing and the agent reported success on lost data.
      ;; With the await, EVERY failure path resolves to the envelope.
      (await (transact!* arg)))
    (catch :default e
      ;; Pre-commit failures (positional/invocation shape, unregistered
      ;; attr, bad value, unbound *conn*) + any rejection out of
      ;; transact!*. The throwing helpers tagged their ex-data with
      ;; `:seon.error/kind`; the envelope preserves it.
      (commit-error-envelope e))))

;; ---------------------------------------------------------------------------
;; Malli → datahike schema bridge.
;;
;; Datahike requires every attribute have a declared `:db/valueType` +
;; `:db/cardinality` before its first transact. seon.schema (Malli) is
;; our source of truth for attr shape; this bridge derives the datahike
;; declaration from the Malli registration so the two layers can't
;; drift.
;;
;; Currently handles the type surface v1 needs (string, keyword,
;; boolean, inst, int, uuid, enum, vector/set cardinality, ref,
;; component refs, identity attrs). Anything else throws — caller
;; decides whether to hand-write the entry or extend the bridge. Phase
;; 2.6 (`client.cljs` cleanup) expands coverage.
;; ---------------------------------------------------------------------------

(defn- form-properties
  "Extract the Malli properties map from a schema form, or nil. Returns
   the first map-typed child.

   Malli's canonical placement is index 1 (after the head):
   `[:string {:min 12} …]` → `{:min 12}`. But some authors put bridge
   markers after the child schema for readability:
   `[:vector :seon.db/ref {:seon.db/component true}]`. The bridge
   accepts either — there's at most one props map per schema form."
  [form]
  (when (vector? form)
    (some (fn [x] (when (map? x) x)) (rest form))))

(defn- form-children
  "The non-property children of a schema form. For
   `[:vector {:min 1} :int]` returns `[:int]`; for `[:vector :int]` also
   `[:int]`; for `:string` returns `[]`."
  [form]
  (if (vector? form)
    (let [body (rest form)
          body (if (and (seq body) (map? (first body))) (rest body) body)]
      (vec body))
    []))

(declare ^:private resolve-malli-form)

(defn- resolve-malli-form
  "Follow a Malli schema form through seon.schema-registered keyword
   indirections until it reaches a non-keyword form OR a keyword whose
   resolved schema is a built-in `IntoSchema` (not a raw Malli form).

   Malli built-ins like `:inst`/`:string`/`:int` ARE present in the
   registry (they have to be — Malli looks them up too), but their
   `schema-definition` returns an `IntoSchema` instance rather than a
   reducible Malli form (keyword or vector). The bridge maps the
   built-in heads directly via `form->datahike-value-type`; recursing
   into the IntoSchema would lose the head and break the mapping.

   So: only recurse when the resolved definition is itself a Malli
   form (keyword or vector). Anything else (IntoSchema, compiled
   schema) means we've hit a built-in — return the form unchanged.

   `:seon.db/ref` is special: even though it's registered, the bridge
   maps it directly to `:db.type/ref` rather than following its
   `[:or ...]` registration (which describes valid value shapes, not
   the underlying datahike type)."
  [form]
  (cond
    (= :seon.db/ref form)
    :seon.db/ref

    (and (keyword? form) (schema/registered? form))
    (let [def (schema/schema-definition form)]
      (if (or (keyword? def) (vector? def))
        (resolve-malli-form def)
        form))

    :else
    form))

(def ^:private malli-type->datahike-type
  "Mapping from Malli base types to datahike `:db.type/*` keywords.
   Lookup is by the *head* of a resolved Malli form (or the form
   itself when it's a bare keyword)."
  {:string  :db.type/string
   :int     :db.type/long
   :double  :db.type/double
   :float   :db.type/float
   :keyword :db.type/keyword
   :boolean :db.type/boolean
   :inst    :db.type/instant
   :uuid    :db.type/uuid
   :symbol  :db.type/symbol})

(def ^:private bridge-supported-types
  "Human-readable list of the attr types the Malli→datahike bridge can
   store. Surfaced in the ensure-datahike-attrs! error so an agent that
   registered an unstorable type sees exactly what IS storable."
  (str ":string :int :double :float :boolean :keyword :inst :uuid "
       ":symbol :seon.db/ref, [:enum :a :b], or a container "
       "[:vector|:set|:sequential <one of those>]"))

(defn- form-head
  "The head of a Malli form. For `[:string {:min 1}]` returns `:string`;
   for `:boolean` returns `:boolean`; for `[:enum :a :b]` returns
   `:enum`; for `:seon.db/ref` returns itself (special case)."
  [form]
  (cond
    (vector? form)  (first form)
    :else           form))

(defn- form->datahike-value-type
  "Given a resolved (no more keyword refs) Malli form, return the
   matching datahike `:db.type/*` keyword. Throws on unmappable
   shapes — caller extends the bridge or hand-writes the entry."
  [resolved-form]
  (let [head (form-head resolved-form)]
    (cond
      (= head :seon.db/ref)
      :db.type/ref

      (= head :enum)
      ;; All current v1 enum schemas hold keyword values
      ;; (`:user`/`:agent`/`:system`/etc). Verify before mapping so a
      ;; non-keyword enum (e.g. enum of strings) doesn't silently land
      ;; as :keyword. If we add string-enums later, branch here.
      (if (every? keyword? (form-children resolved-form))
        :db.type/keyword
        (throw (ex-info (str "Malli :enum with non-keyword values not "
                             "supported by the v1 bridge: "
                             (pr-str resolved-form))
                        {::error :seon.db/unbridgeable-malli-form
                         ::form resolved-form})))

      ;; [:and base extra-constraints] — bridge on the base.
      (= head :and)
      (form->datahike-value-type (resolve-malli-form (first (form-children resolved-form))))

      ;; [:or alt-1 alt-2] — bridge on the first alt. We don't have
      ;; mixed-type :or fields in v1; if one appears, fail loud.
      (= head :or)
      (let [child-types (set (map #(form->datahike-value-type (resolve-malli-form %))
                                  (form-children resolved-form)))]
        (if (= 1 (count child-types))
          (first child-types)
          (throw (ex-info (str "Malli :or with mixed datahike types not "
                               "supported: "
                               (pr-str resolved-form))
                          {::error :seon.db/unbridgeable-malli-form
                           ::form resolved-form
                           ::distinct-types child-types}))))

      :else
      (or (malli-type->datahike-type head)
          (throw (ex-info (str "Cannot map Malli type to datahike type: "
                               (pr-str resolved-form))
                          {::error :seon.db/unbridgeable-malli-form
                           ::form resolved-form
                           ::head head}))))))

(defn- form->cardinality
  "`:db.cardinality/many` if the form is a vector/set/sequential
   container; `:db.cardinality/one` otherwise. The CHILD is the value
   type — caller resolves it separately."
  [form]
  (if (and (vector? form)
           (#{:vector :sequential :set} (first form)))
    :db.cardinality/many
    :db.cardinality/one))

(defn- form->child-form
  "For container forms (`:vector`/`:set`/`:sequential`), the child
   value form. For scalar forms, returns the form unchanged."
  [form]
  (if (and (vector? form)
           (#{:vector :sequential :set} (first form)))
    (first (form-children form))
    form))

(defn malli->datahike-attr
  "Translate a single attr keyword from the seon.schema Malli registry
   into a datahike attribute declaration map (the shape datahike's
   bootstrap schema vector wants).

   Returns:
     {:db/ident       <attr-key>
      :db/valueType   <:db.type/*>
      :db/cardinality <:db.cardinality/one|many>
      :db/unique      <optional :db.unique/identity>
      :db/isComponent <optional true>}

   Properties on the Malli registration drive the optional fields:
     - `{:seon.db/identity true}` → `:db/unique :db.unique/identity`
     - `{:seon.db/component true}` → `:db/isComponent true`

   Throws on unregistered attrs or Malli forms the bridge can't map."
  [attr-key]
  (let [raw-form    (or (schema/schema-definition attr-key)
                        (throw (ex-info (str "Attr not registered in seon.schema: "
                                             (pr-str attr-key))
                                        {::error :seon.db/unregistered-attr
                                         ::attr attr-key})))
        props       (form-properties raw-form)
        outer-form  raw-form
        cardinality (form->cardinality (resolve-malli-form outer-form))
        ;; For vectors/sets, the child is the value form; for scalars,
        ;; same as outer.
        value-form  (-> outer-form
                        resolve-malli-form
                        form->child-form
                        resolve-malli-form)
        value-type  (form->datahike-value-type value-form)]
    (cond-> {:db/ident       attr-key
             :db/valueType   value-type
             :db/cardinality cardinality}
      (:seon.db/identity props)  (assoc :db/unique :db.unique/identity)
      (:seon.db/component props) (assoc :db/isComponent true))))

(defn malli->datahike-schema
  "Vector form of [[malli->datahike-attr]]. Pass a sequence of attr
   keywords; get a vector of datahike-ready attr declarations.

   The vector ordering preserves the input ordering — when a
   datahike conn boot-transacts the result, attrs land in the order
   given (matters for forward references between schema entities)."
  [attr-keys]
  (mapv malli->datahike-attr attr-keys))

(defn tx-meta-datahike-schema
  "The datahike schema entries for the 7 tx-meta attrs (v1.md §2.3).
   Built by running `tx-meta-attrs` through the bridge. Called by
   `seon.client/agent-bootstrap-schema` so the entries are derived
   from Malli, never hand-written."
  []
  (malli->datahike-schema (sort tx-meta-attrs)))

(defn- ^:async ensure-datahike-attrs!
  "Install the datahike attribute-declaration (`:db/valueType` +
   `:db/cardinality` + identity/component flags) for any attr in `attrs`
   that is registered in `seon.schema` but NOT yet present in the conn's
   live datahike schema.

   WHY this exists: datahike runs `:schema-flexibility :write`, so every
   attr must have a datahike schema datom BEFORE its first transact —
   otherwise `d/transact!` throws \"Bad entity attribute … not defined in
   current schema\". At boot, `seon.client/open-agent-conn!` installs the
   substrate's attrs from a fixed list. But when an AGENT registers a NEW
   attr at runtime via `seon.schema/register!`, only the Malli registry
   learns about it — the datahike conn does not. Without this step the
   agent's register→transact flow ALWAYS failed at the datahike layer,
   even after a correct `register!` (the second half of the Phase-1 demo
   gap). This closes the loop so `register!` truly is 'register the type,
   the system derives datahike storage' (CLAUDE.md).

   Reads the conn's current schema map (`(:schema @conn)` — keyed by both
   ident keywords and eids) to find which idents are missing, derives the
   datahike entries via the Malli→datahike bridge, and transacts them in
   their OWN tx (schema before data, like boot). `:db/*` system attrs and
   `:seon.db/ref` (no standalone valueType — refs are declared via the
   attrs that USE them) are skipped.

   FAIL-LOUD (Run-5 / A4): a bridge failure here means the attr was
   `register!`'d with a type datahike can't store. The old behavior
   (console.warn + skip) silently dropped the install, and the data tx
   then died on datahike's cryptic \"Bad entity attribute … not defined
   in current schema\". Now the whole transact fails with a legible
   `:user-input` error naming the attrs, their registered forms, and the
   supported type list — which `transact!`'s catch turns into the
   `{::ok? false}` envelope the agent can SEE and act on."
  [conn attrs]
  (let [installed  (:schema @conn)
        candidates (->> attrs
                        (remove system-attr?)
                        (remove #(= :seon.db/ref %))
                        (filter schema/registered?)
                        (remove #(contains? installed %))
                        distinct)
        {:keys [entries failures]}
        (reduce
          (fn [acc attr]
            (try
              (update acc :entries conj (malli->datahike-attr attr))
              (catch :default e
                (update acc :failures conj
                        {::attr   attr
                         ::schema (schema/schema-definition attr)
                         ::reason (or (.-message e) (str e))}))))
          {:entries [] :failures []}
          candidates)]
    (when (seq failures)
      (throw (ex-info
               (str "These attrs are registered in seon.schema but their "
                    "types cannot be stored in datahike: "
                    (pr-str (mapv (juxt ::attr ::schema) failures))
                    ". Supported attr types: " bridge-supported-types
                    ". Re-register each with a storable type (e.g. "
                    "(seon.schema/register! "
                    (pr-str (::attr (first failures)))
                    " :double)) and transact again.")
               {::error          :seon.db/unbridgeable-attrs
                ::failures       failures
                :seon.error/kind :user-input})))
    (when (seq entries)
      (await (d/transact! conn (vec entries))))))

(defn assert-preconditions!
  "Validate v1.md §7.1 boot preconditions. Throws ex-info on failure.

   Preconditions:
     1. Resolved conn is opened with `:keep-history? true`. Without
        history, tx-meta datoms don't persist (datahike drops them on
        compaction) — the causality bundle silently degrades.
     2. All tx-meta attrs in `tx-meta-attrs` are registered in
        `seon.schema`. Datahike's `flush-tx-meta` rejects unregistered
        keys at write time; the first tx after boot would crash.

   Called from `seon.client/start-agent!` before any agent work fires.
   Tests pass an explicit `:seon.db/conn` to verify against a fresh
   conn without touching `*conn*`."
  ([] (assert-preconditions! {}))
  ([{::keys [conn] :or {conn *conn*}}]
   (let [c (resolve-conn conn)]
     ;; datahike-cljs exposes the conn's config map at `(:config @conn)`.
     ;; There's no `d/get-config` on the CLJS side (it exists on JVM
     ;; only). Deref + key access is the supported path.
     (when-not (:keep-history? (:config @c))
       (throw (ex-info
                (str "seon.db: agent conn opened with `:keep-history? false`. "
                     "v1's tx-meta-as-history mechanic requires history "
                     "(see v1.md §7.1). Open the conn with "
                     "`:keep-history? true`.")
                {:kind          :seon.boot/precondition-failed
                 :failure       :keep-history-off
                 ::error        :seon.boot/precondition-failed})))
     (let [unregistered (into [] (remove schema/registered?) tx-meta-attrs)]
       (when (seq unregistered)
         (throw (ex-info
                  (str "seon.db: tx-meta attrs not registered: "
                       (pr-str unregistered) ". seon.db registers these at "
                       "namespace load — if this fires, the seon.schema "
                       "registry was likely cleared after seon.db loaded "
                       "(`schema/clear-all!` in a test, or stale REPL state).")
                  {:kind          :seon.boot/precondition-failed
                   :failure       :tx-meta-attrs-unregistered
                   :unregistered  unregistered
                   ::error        :seon.boot/precondition-failed})))))
   true))

;; ---------------------------------------------------------------------------
;; Public read path — synchronous over a db value (datahike-cljs is sync
;; once you have a db value; `@conn` is the cheap deref). When V1's kabel
;; replica lands, reads still resolve against the local replica and stay
;; synchronous; the API doesn't change. Each op exposes a map-in arity AND
;; a datahike-shaped positional arity (T15).
;; ---------------------------------------------------------------------------

;; Dispatch note: the map-in vs positional split is by ARITY. The 1-arg
;; arity is map-in (a request map); the 2+/3+ arities are the datahike-shaped
;; positional forms (a positional query/pull/entity always needs at least a
;; query/db + db/eid, so it never collapses to 1 arg). This is the same
;; "map-in iff first arg is a request map" guarantee the spec describes,
;; realized via Clojure's own multi-arity dispatch rather than a runtime
;; first-arg check — and it lets Malli instrument each arity independently
;; (named-slot errors on the positional `:catn`).

(defn query
  "Run a Datalog query. Two call shapes:

   - map-in (preferred for internal callers):
       (db/query {::db/query '[:find ?n :where [?e ::name ?n]]
                  ::db/db <db> | ::db/conn <conn>
                  ::db/args [...]})
   - positional, mirroring datahike `(d/q query db & inputs)` — query
     FIRST, the db is the first `:in $` input, extra `:in` bindings follow:
       (db/query '[:find ?n :where [?e ::name ?n]] <db>)
       (db/query '[:find ?n :in $ ?t :where …] <db> \"Alice\")

   The positional form's db slot is REQUIRED and explicit (no ambient
   `*conn*`). Both shapes return the same query result set."
  ;; PURE-VARIADIC body (verified against reference-code/malli): CLJS
  ;; `malli.instrument` replaces fn arities per CLJS-compiled accessor.
  ;; A MIXED fixed+variadic defn (`([req] [q db & inputs])`) compiles to a
  ;; fixed arity-2 accessor that malli's `-arity->schema` never maps, so a
  ;; 2-arg call slips past Malli (see instrument.cljs L62-75, L29-39 — the
  ;; `-replace-multi-arity` path). A single `[& args]` body is
  ;; `-pure-variadic?` (maxFixedArity 0, only the `…$variadic` accessor),
  ;; so it takes the clean `-replace-variadic-fn` path; the `:function`
  ;; schema's per-arity dispatch (core.cljc L2288) then validates by
  ;; arg-count: 1 -> map-in, 2 -> `(q db)`, 3+ -> `(q db & inputs)` — each
  ;; with named-slot errors. The body dispatches the same way.
  {:malli/schema
   [:function
    [:=> [:cat ::query-request] :any]
    [:=> [:catn [::query [:or [:vector :any] :map :string]]
                [::db    ::db-val]] :any]
    [:=> [:catn [::query [:or [:vector :any] :map :string]]
                [::db    ::db-val]
                [::inputs [:+ :any]]] :any]]}
  [& args]
  (if (= 1 (count args))
    ;; map-in: one request map
    (let [{::keys [query args db conn] :or {conn *conn* args []}} (first args)
          db (or db @(resolve-conn conn))]
      (apply d/q query db args))
    ;; positional: (query q db & inputs) — query first, db binds $
    (let [[q db & inputs] args]
      (apply d/q q db inputs))))

(defn pull
  "Pull an entity by ref using the given pull pattern. Sync. Two call
   shapes:

   - map-in:    (db/pull {::db/pull-pattern selector ::db/ref eid
                          ::db/db <db> | ::db/conn <conn>})
   - positional, mirroring datahike `(d/pull db selector eid)` — DB-first:
                (db/pull <db> selector eid)

   The positional db slot is REQUIRED and explicit. Returns the pulled map
   (or nil if `eid`/`ref` doesn't resolve)."
  {:malli/schema
   [:function
    [:=> [:cat ::pull-request] :any]
    [:=> [:catn [::db ::db-val] [::selector [:vector :any]] [::eid :any]] :any]]}
  ([req]
   (let [{::keys [pull-pattern ref db conn] :or {conn *conn*}} req
         db (or db @(resolve-conn conn))]
     (d/pull db pull-pattern ref)))
  ([db selector eid]
   (d/pull db selector eid)))

(defn entity
  "Look up an entity by eid or lookup-ref. Sync. Two call shapes:

   - map-in:    (db/entity {::db/ref eid ::db/db <db> | ::db/conn <conn>})
   - positional, mirroring datahike `(d/entity db eid)` — DB-first:
                (db/entity <db> eid)

   The positional db slot is REQUIRED and explicit. Returns a datahike
   entity (lazy map-like)."
  {:malli/schema
   [:function
    [:=> [:cat ::entity-request] :any]
    [:=> [:catn [::db ::db-val] [::eid :any]] :any]]}
  ([req]
   (let [{::keys [ref db conn] :or {conn *conn*}} req
         db (or db @(resolve-conn conn))]
     (d/entity db ref)))
  ([db eid]
   (d/entity db eid)))

;; ---------------------------------------------------------------------------
;; Listener machinery
;;
;; Datahike's native `listen!` stores callbacks in a per-conn atom keyed by
;; an opaque key. Same key replaces (idempotent), distinct keys coexist as
;; independent listeners that EACH receive every tx-report.
;;
;; We wrap user-supplied handlers with a transformer that pre-computes the
;; common shape — decoded datoms, attr-grouped index, pre-resolved :db /
;; :db-before — so handlers don't reach to `*conn*` and don't recompute
;; the same group-by N times. Handlers receive a single map argument with
;; everything they might want; they pick what they use and ignore the rest.
;;
;; The Shape B trigger dispatcher (`seon.trigger`, V0-B-5) is a single call
;; to `listen!` under a stable key; its handler reads `:seon.trigger/entity`
;; records from the DB and fans out by signature. The primitive here is
;; equally usable for ad-hoc reactions, debug taps, and ops tooling — agents
;; can call `listen!`/`unlisten!` directly when they don't need the trigger
;; registry's data-driven shape.
;; ---------------------------------------------------------------------------

(defn- datom->map
  "Decode a datahike Datom into a fully-namespaced plain map. We re-emit
   under `:seon.db/*` rather than passing datahike's positional Datom
   record through, so handler bodies destructure with the same
   namespaced shape the rest of seon.db uses."
  [datom]
  {::e      (:e datom)
   ::a      (:a datom)
   ::v      (:v datom)
   ::tx     (:tx datom)
   ::added? (boolean (:added datom))})

(defn- build-handler-input
  "Build the rich handler input map from a raw datahike tx-report. Called
   once per listener invocation. Cheap: a single mapv + group-by over
   :tx-data. Output keys are all `:seon.db/*` so handler code can
   destructure with `::db/keys [...]`."
  [raw-tx-report]
  (let [datoms (mapv datom->map (:tx-data raw-tx-report))]
    {::tx-report  raw-tx-report
     ::db         (:db-after raw-tx-report)
     ::db-before  (:db-before raw-tx-report)
     ::datoms     datoms
     ::attr-index (group-by ::a datoms)}))

(defn listen!
  "Install a tx-listener on the conn. Map-in / map-out. SAFE BY DEFAULT
   per spec-02 §2.5 — the handler's sync throws are caught, and if the
   handler returns a rejecting Promise, `.catch` swallows it. Neither
   takes down the pod. Errors are logged via `js/console.warn`.

   `::db/handler` is a fn-of-one-map. It receives:

     {:seon.db/tx-report   <raw datahike report — escape hatch>
      :seon.db/db          <:db-after, ready to query>
      :seon.db/db-before   <:db-before, for change-detection>
      :seon.db/datoms      [{:seon.db/e :seon.db/a :seon.db/v
                             :seon.db/tx :seon.db/added?} ...]
      :seon.db/attr-index  {:my.ns/attr [datoms-touching-it ...] ...}}

   The `:my.ns/attr` keys of `::db/attr-index` are user-domain attributes
   (whatever attr appeared in the tx); the values are vectors of
   `::db/datom` maps.

   If the handler returns a sync value, transact blocks until it returns
   (back-pressure preserved). If it returns a Promise, transact resolves
   immediately and the listener completes fire-and-forget. Use
   `listen-sync!` / `listen-async!` aliases below to make the intent
   explicit at the call site.

   If the caller doesn't supply `::db/key`, a fresh `random-uuid` is
   generated. Same key on subsequent calls replaces the prior handler.

   Returns `{:seon.db/key <key>}` — the key suitable for `unlisten!`."
  {:malli/schema [:=> [:cat ::listen-request] ::listen-response]}
  [{::keys [handler key conn] :or {conn *conn*}}]
  (let [c (resolve-conn conn)
        k (or key (random-uuid))]
    (d/listen c k
              (fn [raw-tx-report]
                (try
                  (let [input  (build-handler-input raw-tx-report)
                        result (handler input)]
                    (if (instance? js/Promise result)
                      (.catch result
                              (fn [err]
                                (js/console.warn "[seon.db/listen!" (pr-str k)
                                                 "] async-rejected:"
                                                 (error/->message err))))
                      result))
                  (catch :default e
                    (js/console.warn "[seon.db/listen!" (pr-str k) "] threw:"
                                     (error/->message e))
                    nil))))
    {::key k}))

(defn listen-sync!
  "Intent-revealing alias for [[listen!]]. Use when the handler is
   intentionally sync and should gate transactions via back-pressure."
  {:malli/schema [:=> [:cat ::listen-request] ::listen-response]}
  [request]
  (listen! request))

(defn listen-async!
  "Intent-revealing alias for [[listen!]]. Use when the handler returns
   a Promise (fire-and-forget — transact does not wait)."
  {:malli/schema [:=> [:cat ::listen-request] ::listen-response]}
  [request]
  (listen! request))

(defn unlisten!
  "Remove a listener by key. Returns `{:seon.db/ok? true}`. Idempotent —
   unknown keys are silently no-op (matches datahike upstream)."
  {:malli/schema [:=> [:cat ::unlisten-request] ::unlisten-response]}
  [{::keys [key conn] :or {conn *conn*}}]
  (let [c (resolve-conn conn)]
    (d/unlisten c key)
    {::ok? true}))
