(ns seon.client
  "V0 CLJS pod entry point. Long-running Node process; the V0 client.

   Two responsibilities:

     1. Run the smoke test on boot — proves datahike-cljs is alive.
     2. Boot the V0 agent on demand via `start-agent!`.

   How to run it:

     ;; Terminal 1 — the watcher (compiles + writes nREPL port file)
     clj -M:cljs watch client

     ;; Terminal 2 — the Node host
     node out/client/main.js

     ;; Editor / MCP — connect to nREPL on localhost:7889, then
     ;; pivot into the running CLJS runtime:
     (shadow.cljs.devtools.api/nrepl-select :client)

     ;; To bring up the V0 agent (stub LLM):
     (cljs.core.async/go
       (cljs.core.async/<! (seon.client/start-agent-with-stub!)))

     ;; Then message it (from defaults to the calling scope; the
     ;; HTTP /chat adapter stamps from = the user ref explicitly):
     (seon.agent/message!
       {:seon.agent.message/from    seon.agent/user-ref
        :seon.agent.message/to      [[:seon.agent/id \"<agent-id>\"]]
        :seon.agent.message/content \"hello\"})"
  (:require
    [clojure.set :as set]
    [clojure.string :as str]
    [datahike.api :as d]
    ;; konserve.node-filestore registers datahike's :file store backend
    ;; for Node (the agent conn persists to disk — see open-agent-conn!).
    ;; datahike.api conditionally js/requires it, but require it
    ;; explicitly so the :file backend is guaranteed registered in the
    ;; :client bundle regardless of that conditional's timing.
    [konserve.node-filestore]
    ;; Phase A item 6 — bundle malli.instrument so Phase A item 7's
    ;; install! call resolves at runtime. Pulled in here (the :client
    ;; entry) rather than seon.repl/seon.eval so reload churn in those
    ;; namespaces doesn't drag instrumentation init into the hot path.
    [malli.instrument :as mi]
    ;; malli.core/form round-trips a fn's `:malli/schema` to the stable
    ;; `:seon.fn/spec` string in index-core! (the runtime-introspection
    ;; core indexer — coherent-bootstrap-indexing Step 2).
    [malli.core :as m]
    ;; Phase A item 7 — seon-native collector that walks the analyzer
    ;; at compile time for :malli/schema metadata and registers with
    ;; malli.core/-function-schemas*. Bridges the JVM/CLJS gap where
    ;; mi/collect! is JVM-only.
    [seon.instrument :as instrument]
    ;; Pull in the agent's required namespaces at compile time so all
    ;; schemas are registered before start-agent! runs.
    [seon.agent :as agent]
    [seon.ctx :as ctx]
    [seon.ai :as ai]
    [seon.ai.anthropic :as anthropic]
    [seon.ai.openai-compat :as openai]
    [seon.db :as db]
    [seon.eval :as seval]
    [seon.log :as log]
    ;; Render protocol — A-2. Required here so the build includes it.
    ;; Symbol-lookup for render slots lives in seon.eval/lookup-value
    ;; (walks goog-global with cljs.core/munge); no boot-time wire-up
    ;; needed.
    [seon.render]
    [seon.render.default]
    ;; The canonical creation-time tile wiring form (live-tiles U4) —
    ;; `creation-evals!` evals live-tile/wiring-source AS the new agent.
    [seon.render.live-tile :as live-tile]
    ;; Iteration surface — owns the canonical `!compile-state`
    ;; defonce (in `seon.repl`). start-agent! reads through
    ;; `seon.repl/ensure-bootstrap!` rather than holding a second
    ;; copy here. See compile-state-lifecycle research note.
    [seon.repl :as repl]
    ;; Schemas-as-queryable-data (research file
    ;; schemas-as-queryable-data-2026-05-26.md). At boot,
    ;; start-agent! decomposes every entity-shape :map schema into
    ;; a :seon.schema entity carrying its required-attrs / id-attr /
    ;; render symbols. Renderer kind-lookup queries these via
    ;; datalog instead of walking the in-memory *schemas atom.
    [seon.schema :as schema]
    ;; Phase 2 — test capture as data. Required so the bundle
    ;; includes the runner; agent code reaches it from
    ;; bootstrap-CLJS eval via the analyzer's globalThis fallback
    ;; (seon.eval/truly-undeclared?).
    [seon.test.runner]
    ;; Pod HTTP+SSE server — A-5. Required here so the build includes
    ;; it; start-agent! calls (web.serve/start!) at boot.
    [seon.web.serve :as web.serve]
    ;; Per-agent inspector UI (/agent/<id>) — installs its own
    ;; tx-listener that pushes morphs to that page's SSE stream.
    [seon.web.inspector]
    ;; Default :seon.render/ai + :seon.render/html for :seon.agent.message
    ;; entities. Referenced by symbol from message tx data.
    [seon.handlers.message]
    ;; Renderers for :seon.eval / :seon.fn / :seon.schema / :seon.ns —
    ;; stamped at the write site (record-eval!, build-tee-entities) so
    ;; each persisted entity appears in the inspector's two panes via
    ;; the core-wide `:seon.render/ai`-walking assembler.
    [seon.handlers.eval]
    [seon.handlers.fn]
    [seon.handlers.schema]
    [seon.handlers.ns]
    ;; The my.* scaffold — shared provenance shapes (my.kb) + the
    ;; system-wide instruction singleton (my.kb.system) that
    ;; `seed-core!` below transacts. Required here so their
    ;; register! calls run before the boot install of :my.kb/* attrs.
    [my.kb]
    [my.kb.system]
    ;; The store-resident system prompt (:my.soul rows — SOUL.md +
    ;; REPL mechanics). Required so its register! calls run before the
    ;; boot install of :my.soul/* attrs and so start-agent! can call
    ;; my.soul/seed-tx-data (seed-only-if-absent — a user's runtime
    ;; edit to the soul survives reboot).
    [my.soul]
    ;; Core handler registration — `wake-on-message`. Required so
    ;; start-agent! can call `handler/register!` + `wake/bootstrap-schema!`
    ;; at boot. Without this, the inspector header shows "0 handlers"
    ;; and the core has no wake-on-message responder beyond the
    ;; per-agent `install-user-trigger!` already wired by agent/boot!.
    [seon.handler :as h]
    [seon.handlers.wake :as wake]
    ;; Local-machine capability surface — A-9. Required so the agent
    ;; can call (seon.agent.fs/read-file ...) + (seon.platform/host) from
    ;; bootstrap-CLJS eval.
    [seon.agent.fs]
    ;; Content search over allowed files — the exemplar npm-package
    ;; wrapper (@vscode/ripgrep). Required so the agent can call
    ;; (seon.agent.search/grep ...) from bootstrap-CLJS eval and so the
    ;; core-vars seed below can index it.
    [seon.agent.search]
    ;; Work items (user→agent asks + agent notes-to-self) — required so
    ;; its register! calls run before the boot install of :seon.agent.todo/*.
    [seon.agent.todo]
    ;; The <turns> countdown section (core-default-ctx :turns) —
    ;; required so the build includes it; seon.ctx references it by
    ;; symbol only (late lookup-value resolution, no require cycle).
    [seon.agent.turns]
    ;; The per-section ctx namespaces (ctx-sections-split-2026-06-18):
    ;; each owns one section fn (+ html twin) that core-default-ctx
    ;; wires by SYMBOL — required here so the build includes them and
    ;; their munged symbols resolve via seon.eval/lookup-value at
    ;; render time (no require cycle: the section nses require seon.ctx
    ;; for the shared read API, seon.ctx names them only as symbols).
    [seon.ctx.namespaces]
    [seon.ctx.your-entity]
    [seon.ctx.live-tile]
    [seon.ctx.warnings]
    [seon.ctx.transcript]
    [seon.ctx.inventory]
    [seon.ctx.prompt]
    [seon.platform]
    ;; Phase B item 9 — shared read-side wrapper over the analyzer
    ;; state. Required here so the build includes it; item 10's
    ;; detect-and-tee in seon.eval/eval-batch! consumes it.
    [seon.analyzer-info]
    ;; THE FLIP (unit 2.2e): the cluster-store DIS-peer seam — the
    ;; :seon-wire PWriter, cluster connect config, and the foreign-tx
    ;; listen adapter. open-cluster-conn! below builds on it.
    [seon.store.wire :as store.wire]
    ;; MCP runtime-addressing probe (mcp-agent-id-unification PRD):
    ;; the pod `host!`s every agent id it resumes/mints/re-arms so
    ;; `mcp__seon_cljs__eval agent_id=<id>` pins THIS runtime.
    [seon.dev.runtime-id :as runtime-id])
  ;; Compile-time enumeration of the build's specced public fns —
  ;; `core-vars` below = curated unspecced base + this macro's
  ;; whole-closure roster (unit #23 fix b: index the WHOLE package
  ;; surface, never hand-list hundreds of vars).
  (:require-macros [seon.indexing :refer [specced-fn-vars]]))

;; ---------------------------------------------------------------------------
;; Process-lifetime state. `defonce` so reloads don't reset it.
;; ---------------------------------------------------------------------------

(defonce !state
  (atom {:boot-at      (.toISOString (js/Date.))
         :reload-count 0
         :heartbeat-id nil}))

(defn start-heartbeat!
  "Holds the Node event loop open with a minute-cadence heartbeat. The
   real V0 client will keep the loop alive via pending agent-loop work;
   for now this is the simplest 'process stays open' contract."
  []
  (let [id (js/setInterval
             (fn []
               (log/debug-console! "seon.client" "heartbeat"))
             60000)]
    (swap! !state assoc :heartbeat-id id)))

(defn stop-heartbeat! []
  (when-let [id (:heartbeat-id @!state)]
    (js/clearInterval id)
    (swap! !state assoc :heartbeat-id nil)))

(defn ^:dev/before-load before-reload []
  (log/info-console! "seon.client" "reloading…")
  (stop-heartbeat!))

(declare rearm-user-triggers!)

(defn ^:dev/after-load after-reload []
  (swap! !state update :reload-count inc)
  (log/info-console! "seon.client"
                     (str "reload #" (:reload-count @!state)
                          " — booted " (:boot-at @!state)))
  ;; Hot-reload hygiene: re-install the per-agent user-message
  ;; triggers so tx-listener closures run the just-reloaded code.
  ;; Async fire-and-forget — logs the re-armed ids / errors.
  ;; (seon.web.inspector re-arms its own ::inspector listener via its
  ;; own ^:dev/after-load — not duplicated here.)
  (rearm-user-triggers!)
  (start-heartbeat!))

;; ---------------------------------------------------------------------------
;; datahike-cljs smoke test — proves the core works end-to-end.
;;
;; This is the canonical 'is datahike-cljs alive?' check. Useful as:
;;   - boot-time verification (`-main` runs it),
;;   - REPL-callable health probe (`(datahike-smoke-test!)`),
;;   - reference for how to use datahike-cljs from agent code.
;; ---------------------------------------------------------------------------

(def ^:private smoke-schema
  [{:db/ident       :name
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/unique      :db.unique/identity
    :db/valueType   :db.type/string}
   {:db/ident       :rank
    :db/cardinality :db.cardinality/one
    :db/valueType   :db.type/long}])

(def ^:private smoke-seed
  [{:name "Alpha"    :rank 1}
   {:name "Seon"     :rank 2}
   {:name "Datahike" :rank 3}])

(def ^:private smoke-expected
  #{["Alpha" 1] ["Seon" 2] ["Datahike" 3]})

(defn ^:async datahike-smoke-test!
  "Create a fresh :memory datahike-cljs DB, transact schema + seed entities,
   query, compare to expected. Returns a Promise resolving to
   {:status :pass :datoms <n> :rows <set>} or
   {:status :fail :got <set> :expected <set>}.

   REPL usage:
     (.then (datahike-smoke-test!) println)"
  []
  (let [cfg {:store              {:backend :memory
                                  :id (random-uuid)}
             :schema-flexibility :write
             ;; Smoke + dev conns share the same history posture as the
             ;; agent conn so behavior parity holds across diagnostics
             ;; (Phase 2.5, 2026-05-22 — per Sean: storage overhead
             ;; trivial, consistency wins).
             :keep-history?      true}]
    (await (d/create-database cfg))
    (let [conn (await (d/connect cfg))
          _    (await (d/transact! conn smoke-schema))
          tx   (await (d/transact! conn smoke-seed))
          rows (d/q '[:find ?name ?rank
                      :where
                      [?e :name ?name]
                      [?e :rank ?rank]]
                    @conn)]
      (if (= rows smoke-expected)
        {:status :pass :datoms (count (:tx-data tx)) :rows rows}
        {:status :fail :got rows :expected smoke-expected}))))

(defn ^:async mem-db
  "REPL convenience — open a fresh :memory datahike-cljs DB with optional
   schema. Returns a Promise resolving to a conn atom."
  ([] (mem-db []))
  ([schema]
   (let [cfg {:store              {:backend :memory
                                   :id (random-uuid)}
              :schema-flexibility :write
              ;; Match agent + smoke conn history posture (Phase 2.5).
              :keep-history?      true}]
     (await (d/create-database cfg))
     (let [conn (await (d/connect cfg))]
       (when (seq schema)
         (await (d/transact! conn schema)))
       conn))))

;; ---------------------------------------------------------------------------
;; Agent boot
;;
;; The V0 agent runs against a long-lived :memory datahike conn distinct
;; from the smoke-test's ephemeral conn. start-agent! opens it, bootstraps
;; the datahike schema (idents needed for lookup-refs), binds seon.db/*conn*
;; at the var root, and hands off to seon.agent/boot!.
;;
;; Idempotent: re-calling start-agent! reuses the existing conn (stored in
;; !agent-conn) and overwrites the kick listener. Useful during dev hot-
;; reload where the watcher rebuilds client.cljs.
;; ---------------------------------------------------------------------------

(defonce !agent-conn (atom nil))

;; Datahike-side schema. Datahike requires every attribute have a
;; declared :db/valueType + :db/cardinality before first use — our
;; Phase 2.6 cleanup (2026-05-23): the datahike attribute schema is
;; now Malli-derived. Each entry below is a keyword reference into the
;; `seon.schema` Malli registry; `seon.db/malli->datahike-schema`
;; produces the datahike attr-declaration vector at boot time, reading
;; the registered Malli shape (type, cardinality, `:seon.db/identity`,
;; `:seon.db/component`).
;;
;; To add a new datahike attribute: register the Malli schema in the
;; owning namespace (`seon.agent`, `seon.log`, `seon.test.runner`, etc.)
;; with the usual marker props, then add the keyword to this vector.
;; No more hand-written `:db.type/*` entries.
;;
;; Order doesn't matter for forward refs — datahike's `:db.type/ref`
;; is loosely typed (no target-entity check); the Malli registry is
;; populated at ns-load time before this vector resolves.
(def agent-bootstrap-attrs
  "The full set of registered seon attr keywords whose datahike schema the
   agent conn needs. Public so tests can build an isolated `:memory` conn with
   the same schema the pod boots against (see index-core-test)."
  [;; --- Agent ---
   ;; :seon.agent/current-ns deleted 2026-05-23 — derived from the
   ;; latest successful eval's :seon.eval/ns. See
   ;; docs/seon/concepts/reactive-context.
   :seon.agent/id
   :seon.agent/state
   ;; lifecycle end-stamp (P3.5/#31) — absent = active; boot resumes
   ;; only agents WITHOUT it (see seon.agent/complete!).
   :seon.agent/completed-at
   :seon.agent/sessions
   :seon.agent/turns-cap
   :seon.agent/ctx

   ;; --- Render slots (A-6) — symbol-only at storage. ---
   :seon.render/ai
   :seon.render/html

   ;; --- Ctx section entities (seon.ctx; self-context spec 2026-06-10).
   ;; :seon.ctx/fn is DEAD — the one slot attr is :seon.render/ai
   ;; (above), string-or-symbol via the bridge's EDN-string encoding. ---
   :seon.ctx/name
   :seon.ctx/priority

   ;; --- Session (v1.md §2.1) ---
   ;; turns-since-inbound is DERIVED from the count of :seon.agent.turn
   ;; entities with :seon.agent.turn/at > the latest inbound message's :at.
   ;; See seon.agent/turns-since-inbound.
   :seon.agent.session/id
   :seon.agent.session/at
   :seon.agent.session/turns

   ;; --- Turn (v1.md §2.1) ---
   :seon.agent.turn/id
   :seon.agent.turn/at
   :seon.agent.turn/status
   ;; The full prompt is a logs/prompts/<agent>/<turn>.txt BLOB (three-
   ;; tier storage); the datoms are the char-count projection + the
   ;; file pointer. :seon.agent.turn/prompt-text RETIRED 2026-06-09 (was
   ;; silently cap-edn-truncated at 16,406 chars — useless evidence).
   :seon.agent.turn/prompt-chars
   :seon.agent.turn/prompt-file
   :seon.agent.turn/woken-by
   :seon.agent.turn/messages
   :seon.agent.turn/evals

   ;; --- Message (from/to refs since unit 1.5 — role/agent retired) ---
   :seon.agent.message/id
   :seon.agent.message/from
   :seon.agent.message/to
   :seon.agent.message/content
   :seon.agent.message/at
   :seon.agent.message/hops

   ;; --- User (ONE human entity, seeded at boot) ---
   :seon.user/id

   ;; --- Todo (work items — user→agent asks + agent notes-to-self;
   ;; --- installed at boot so a RESUMING agent can list-open before
   ;; --- any todo tx has lazily installed the attrs) ---
   :seon.agent.todo/id
   :seon.agent.todo/title
   :seon.agent.todo/description
   :seon.agent.todo/status
   :seon.agent.todo/created-at
   :seon.agent.todo/completed-at
   :seon.agent.todo/owner
   :seon.agent.todo/from

   ;; --- Eval ---
   ;; Evals are component-many on :seon.agent.turn/evals — no standalone
   ;; back-refs to agent / turn-n needed (reachable via the component
   ;; chain). Deleted 2026-05-23.
   :seon.eval/id
   :seon.eval/at
   :seon.eval/duration-ms
   :seon.eval/narration
   :seon.eval/source
   :seon.eval/ok?
   :seon.eval/result-edn
   ;; Captured println/prn output during the eval span (unit #23 fix f).
   :seon.eval/output
   :seon.eval/error
   :seon.eval/error-data
   ;; :seon.eval/ns — ending ns from cljs.js/eval-str's :ns, or
   ;; unchanged accumulator on parse/eval failure (v1.md:236).
   :seon.eval/ns

   ;; --- Program graph (v1.md §2.2) ---
   :seon.ns/name
   :seon.ns/source
   :seon.fn/sym
   :seon.fn/ns
   :seon.fn/source
   :seon.fn/fn-var?
   :seon.fn/arglists
   :seon.fn/doc
   :seon.fn/private?
   :seon.fn/spec
   :seon.fn/schema-error
   :seon.fn/created-at
   :seon.schema/key
   :seon.schema/ns
   :seon.schema/source
   :seon.schema/created-at
   ;; Meta-schema attrs (schemas-as-queryable-data, 2026-05-27). Every
   ;; entity-shape :map schema is decomposed at boot into a :seon.schema
   ;; entity carrying these. The renderer's kind-lookup queries them via
   ;; datalog instead of walking the in-memory *schemas atom.
   ;; :seon.schema/required-attrs is :db.cardinality/many keyword via
   ;; the [:vector :keyword] Malli bridge.
   :seon.schema/required-attrs
   :seon.schema/id-attr
   :seon.schema/render-fn
   :seon.schema/render-html-fn

   ;; --- Log (A-6) ---
   ;; REMOVED 2026-05-27 per storage discipline rule (Sean): logs are
   ;; transient data, do NOT persist as DB datoms. seon.log uses an
   ;; in-process ring buffer + logs/pod-events.log file destination.
   ;; The :seon.log/* attrs above (at/level/source/agent/message/stack/
   ;; dismissed-at) referenced un-registered :seon.log/dismissed-at and
   ;; broke pod boot; removing them resolves the boot error AND aligns
   ;; with the no-transient-data-in-DB rule.

   ;; --- my.kb (knowledge-base scaffold, 2026-06-10). ---
   ;; The shared provenance shapes (registered in my.kb) installed at
   ;; boot so agent-designed my.kb.<domain> schemas can reference them
   ;; before any kb tx lands, plus the my.kb.system system-wide
   ;; instruction singleton (empty entity seeded by seed-core!;
   ;; rows are appended at runtime by agents and the user).
   :my.kb/source-path
   :my.kb/source-line
   :my.kb/verified-at
   :my.kb/confidence
   :my.kb.system/id
   :my.kb.system/instructions
   :my.kb.system/text
   :my.kb.system/at
   ;; The store-resident system prompt (my.soul — seeded at boot
   ;; from SOUL.md + the REPL mechanics, seed-only-if-absent).
   :my.soul/id
   :my.soul/text
   :my.soul/priority

   ;; --- Test (Phase 2 — test capture as data) ---
   :seon.test/sym
   :seon.test/last-passed-at
   :seon.test/last-failed-at
   :seon.test/last-failure-summary
   :seon.test/last-run-id
   ;; Phase 4 (mvp-completion-plan 2026-05-27)
   :seon.test/source
   :seon.test/ns
   :seon.test/created-at])

;; ---------------------------------------------------------------------------
;; Cluster-store agent conn (unit 2.2e — THE FLIP, 2026-06-10).
;;
;; The pod no longer mints a per-run `data/seon-pod/<run-id>` store. It
;; connects to the SHARED cluster store (`data/clusters/default/store`)
;; as a DIS peer: reads are local sync konserve (lazy LRU node fetch),
;; writes route through the `:seon-wire` PWriter over the UDS wire to
;; the JVM wire-server — the SOLE writer. See `seon.store.wire`.
;;
;; Legacy per-run dirs under `data/seon-pod/` stay readable (the
;; konserve header sniff handles their 1-byte meta-size encoding) but
;; are never created again. No dual backend: if the wire-server is down
;; at boot, the pod FAILS LOUD — it never falls back to a local store.
;; ---------------------------------------------------------------------------

(defn- pod-full-schema
  "The pod's full datahike attribute schema: the agent bootstrap attrs
   plus the tx-meta attrs, both Malli-derived via
   `seon.db/malli->datahike-schema` (Phase 2.6 — no hand-written
   `:db.type/*` entries). Adding a new attr is a Malli `register!` in
   the owning ns plus a keyword line in `agent-bootstrap-attrs` above."
  []
  (into (db/malli->datahike-schema agent-bootstrap-attrs)
        (db/tx-meta-datahike-schema)))

(defn ^:async open-agent-conn!
  "Open a FRESH ISOLATED `:memory` conn carrying the pod's full
   bootstrap schema. Test/diagnostic surface ONLY — the pod itself
   boots on the shared cluster store via [[open-cluster-conn!]].
   Isolated-by-construction: tests that build agents on this conn can
   never touch the cluster store."
  []
  (let [cfg {:store              {:backend :memory
                                  :id      (random-uuid)}
             :schema-flexibility :write
             :keep-history?      true}]
    (await (d/create-database cfg))
    (let [conn (await (d/connect cfg))]
      (await (d/transact! conn (pod-full-schema)))
      conn)))

(defn ^:async open-cluster-conn!
  "Open the pod's DIS-peer conn on the shared cluster store and start
   the foreign-tx listen adapter.

   Order is load-bearing:
     1. `ping!` — FAIL LOUD if the wire-server is down (no local
        fallback, no dual backend).
     2. `d/connect` — reads go local from here; writes dispatch to the
        `:seon-wire` writer.
     3. schema transact — the full Malli-derived attr schema goes OVER
        THE WIRE to the JVM writer; idempotent `:db/ident` upserts, so
        re-booting against the populated store re-asserts no-ops.
     4. listen adapter — foreign writers' txs fire this conn's native
        listeners (user-message triggers + inspector SSE)."
  []
  (await (store.wire/ping!))
  (let [conn (await (d/connect (store.wire/cluster-config)))]
    (log/info-console! "seon.client/open-cluster-conn!"
                       (str "cluster store: " store.wire/default-store-path
                            " (writer: " store.wire/default-sock-path ")"))
    (await (d/transact! conn (pod-full-schema)))
    (await (store.wire/start-listen-adapter! {:seon.store.wire/conn conn}))
    conn))

;; ---------------------------------------------------------------------------
;; Resume — replay-program-graph! (the DB-is-the-running-system spine)
;;
;; Load the agent-authored DB LAYER (agent code + overrides) on top of the
;; compiled package after a pod restart, restoring the agent's namespaces /
;; vars / Malli registrations into the live compile-state + globalThis.
;;
;; The compiled package (kernel + core + third-party) is ALREADY in the
;; runtime from module-load — its rows are DISPLAY-only and are NOT loaded.
;; Only the agent DB layer loads, whole-namespace + dependency-ordered:
;;
;;   - `agent-ns-set` — every `:seon.ns/name` row minus `(core-ns-set)`.
;;   - `topo-sort-nses` over the STORED `:seon.ns/requires` (intersected
;;     with the agent-ns-set — intra-agent edges only; core deps load
;;     on-demand via the DB load-fn). A dep loads before its dependent.
;;   - For each ns in topo order, eval its reconstituted whole source
;;     (`seon.eval/reconstitute-ns-source`: the verbatim (ns … (:require …))
;;     form + every current :seon.fn/:seon.schema/:seon.test source). The
;;     ns form is the head, so the ns is created first by construction;
;;     cljs.js's load-fn (the DB branch of `seon.eval/guarded-load`)
;;     supplies any transitive agent require's source on demand, with
;;     cycle detection + load-once.
;;   - Per-ns try/catch: a failing ns logs a `:seon.log` :warn and the load
;;     CONTINUES to the next ns. NO 2-pass retry, NO per-fn fallback.
;;   - The replay-level (with-tx-context {:seon.db/origin :replay
;;     :seon.db/replay? true}) tags only the log-write transactions — no
;;     eval entities are written.
;; ---------------------------------------------------------------------------

(declare core-ns-set)

(defn ^:private agent-ns-set
  "The set of agent-authored namespace keywords in the DB layer — every
   `:seon.ns/name` row whose ns is NOT in `(core-ns-set)`. Core nses are
   COMPILED (present in the bundle, indexed for DISPLAY only); only the
   agent-authored DB layer is LOADED on boot. Read against the db value."
  [db]
  (let [all (into #{}
                  (map first)
                  (db/query '[:find ?n :where [?e :seon.ns/name ?n]] db))]
    (set/difference all (core-ns-set))))

(defn ^:private agent-ns-requires
  "Map of `agent-ns-kw → #{intra-agent require ns-kws}` for topo-sort.
   Reads the STORED `:seon.ns/requires` (the one unblocking fix —
   captured at tee from the analyzer, NOT re-parsed here) for each ns in
   `agent-nses`, INTERSECTED with `agent-nses` so only intra-agent edges
   order the load kick. Core/third-party deps are NOT edges here — they
   are satisfied on-demand by the compiled bundle via the DB load-fn
   (`seon.eval/guarded-load`) DURING each ns's eval. An agent ns with no
   stored requires (or only core deps) has an empty edge set."
  [db agent-nses]
  (into {}
        (map (fn [ns-kw]
               (let [reqs (into #{}
                                (map first)
                                (db/query '[:find ?r
                                            :in $ ?ns
                                            :where
                                            [?e :seon.ns/name ?ns]
                                            [?e :seon.ns/requires ?r]]
                                          db ns-kw))]
                 [ns-kw (set/intersection reqs agent-nses)])))
        agent-nses))

(defn ^:private topo-sort-nses
  "Dependency-ordered vector of the keys of `edges` (a `ns → #{dep-ns}`
   map) — a dep comes before its dependent (DFS post-order).
   Deterministic (sorted within each level). A require cycle is broken by
   the `visiting` guard (a back-edge to a node already on the DFS stack
   is skipped) so this always terminates; cljs.js then detects the actual
   circular dep and errors that ns during its per-ns eval (user
   directive: broken input just errors that ns and moves on)."
  [edges]
  (let [!order   (volatile! [])
        !seen    (volatile! #{})
        visit    (fn visit [ns-kw visiting]
                   (when-not (or (@!seen ns-kw) (visiting ns-kw))
                     (let [visiting' (conj visiting ns-kw)]
                       (doseq [dep (sort (get edges ns-kw))]
                         (visit dep visiting'))
                       (vswap! !seen conj ns-kw)
                       (vswap! !order conj ns-kw))))]
    (doseq [ns-kw (sort (keys edges))]
      (visit ns-kw #{}))
    @!order))

(defn ^:private standalone-schema-sources
  "The replayable `:seon.schema/source` strings for agent schema rows
   that own NO `:seon.ns` membership — single-segment ENTITY-schema keys
   (`:my.garden.watering`, a `:map {:seon.db/entity true}` registration)
   whose `(namespace k)` is nil, so the tee files them with no
   `:seon.schema/ns` link ([[seon.eval/reconstitute-ns-source]] joins
   schema members through that link, so these would otherwise never
   load). Only call-shaped sources (an agent's `(seon.schema/register!
   …)` tee) — boot-indexed shape literals are rebuilt from the registry.
   Returns each fully-qualified registration call; eval'd from
   `cljs.user` (the key carries its own namespace)."
  [db]
  (->> (db/query '[:find ?src
                   :where
                   [?s :seon.schema/key ?k]
                   [?s :seon.schema/source ?src]
                   (not [?s :seon.schema/ns _])]
                 db)
       (map first)
       (filter #(str/starts-with? (str/trim (str %)) "("))
       distinct
       vec))

(defn- error-chain-message
  "Human-readable message for a `seon.error/->map` map, composed from
   the WHOLE `:seon.error/cause` chain (deduped, joined with ` <- `).
   Fail-loud: cljs.js wraps analysis errors in ex-info layers whose
   top-level message is the literal string \"ERROR\" — the real defect
   (e.g. schema/register!'s legible invalid-schema explanation) lives
   one or two causes down. Surfacing only the top message produced
   warn lines like `replay of schema :seon.workout/date failed: ERROR`
   (live incident 2026-06-10)."
  [err-map]
  (->> (iterate :seon.error/cause err-map)
       (take-while some?)
       (map :seon.error/message)
       (remove str/blank?)
       (distinct)
       (str/join " <- ")))

(defn- error-chain-stack
  "Deepest available `:seon.error/stack` in the cause chain — the
   original throw site, not cljs.js's compile-loop trampoline."
  [err-map]
  (->> (iterate :seon.error/cause err-map)
       (take-while some?)
       (keep :seon.error/stack)
       (last)))

(defn ^:async ^:private log-replay-failure!
  [agent-id ns-kw {:keys [error stack]}]
  (await
    (log/warn! {:seon.log/source  ::log-replay-failure!
                :seon.log/agent   agent-id
                :seon.log/message (str "load of ns " (pr-str ns-kw)
                                       " failed: " error)
                :seon.log/stack   (or stack "")})))

(defn ^:private load-error->log
  "Normalize a load failure `err` (a `seon.error/->map` from
   `seon.eval/eval`'s `{:ok false :error …}`, or a raw caught JS error)
   into the `{:error <message-string> :stack <stack-string>}` shape
   `log-replay-failure!` expects. `:error` carries the full cause-chain
   message ([[error-chain-message]]); `:stack` the deepest cause's stack
   — chosen so a load-failure warn names the actual defect, not cljs.js's
   \"ERROR\" wrapper."
  [err]
  {:error (or (some-> err error-chain-message not-empty)
              (some-> err .-message)
              (str err))
   :stack (or (some-> err error-chain-stack)
              (some-> err .-stack)
              "")})

(defn ^:async replay-program-graph!
  "Load the DB layer (agent-authored code + overrides) on top of the
   compiled package — db-is-the-running-system PRD, the spine.

   The whole-namespace, dependency-ordered load (DELETES the old
   per-definition replay loop, the tx-order sort, the 2-pass retry, and
   `ensure-target-ns!`):

     1. `agent-ns-set` — every `:seon.ns/name` row minus `(core-ns-set)`.
        Core/third-party are COMPILED (in the bundle), indexed for
        DISPLAY only; only the agent DB layer is loaded.
     2. `topo-sort-nses` over the STORED `:seon.ns/requires` intersected
        with the agent-ns-set (intra-agent edges only — core deps load
        on-demand via the load-fn). A dep loads before its dependent.
     3. For each ns in topo order, `(seval/eval compile-state
        (seval/reconstitute-ns-source db ns-kw) {:ns 'cljs.user})`. The
        reconstituted source's head is the `(ns … (:require …))` form, so
        the ns is created first by construction (no `ensure-target-ns!`).
        cljs.js's load-fn (`guarded-load`'s DB branch) supplies any
        transitive agent require's source on demand, with cycle detection
        + load-once — we write no ordering beyond the topo kick.

   Per-ns try/catch: a failing ns (broken stored code, require cycle)
   logs a `:seon.log` :warn and the load CONTINUES to the next ns — no
   retry, no per-fn fallback (user directive: broken input just errors
   that ns and moves on).

   Returns a Promise of
     {:seon.client/replay-n-total <int>
      :seon.client/replay-n-ok    <int>
      :seon.client/replay-n-fail  <int>}.

   Call sites:
     - Boot path in start-agent!, before per-agent setup.
     - REPL probe via the same-pod-session test pattern — see
       research/resume-findings-2026-05-23.md §'Same-pod-session test'."
  [{:keys [conn compile-state agent-id]}]
  (db/with-tx-context
    {:seon.db/origin   :replay
     :seon.db/replay?  true
     :seon.db/agent-id agent-id}
    (fn ^:async run-replay! []
      (let [db       @conn
            agents   (agent-ns-set db)
            order    (topo-sort-nses (agent-ns-requires db agents))
            standalone (standalone-schema-sources db)
            !n-fail  (volatile! 0)]
        ;; Whole-namespace, dependency-ordered load (the spine).
        (doseq [ns-kw order]
          (let [r (try
                    (let [src (seval/reconstitute-ns-source db ns-kw)]
                      (await (seval/eval compile-state src {:ns 'cljs.user})))
                    (catch :default e
                      {:ok false :error e}))]
            (when-not (:ok r)
              (vswap! !n-fail inc)
              ;; Best-effort log; swallow log-write failure (a
              ;; double-fault must not abort the rest of the load).
              (try
                (await (log-replay-failure!
                         agent-id ns-kw (load-error->log (:error r))))
                (catch :default e
                  (log/error-console!
                    "seon.client/replay-program-graph!"
                    (str "log-replay-failure failed: " (.-message e))))))))
        ;; Standalone (ns-less) entity-schema rows — fully-qualified
        ;; register! calls evaled from cljs.user.
        (doseq [src standalone]
          (let [r (try (await (seval/eval compile-state src {:ns 'cljs.user}))
                       (catch :default e {:ok false :error e}))]
            (when-not (:ok r)
              (vswap! !n-fail inc)
              (try
                (await (log-replay-failure!
                         agent-id :standalone-schema (load-error->log (:error r))))
                (catch :default e
                  (log/error-console!
                    "seon.client/replay-program-graph!"
                    (str "log-replay-failure failed: " (.-message e))))))))
        (let [total (+ (count order) (count standalone))]
          {:seon.client/replay-n-total total
           :seon.client/replay-n-ok    (- total @!n-fail)
           :seon.client/replay-n-fail  @!n-fail})))))

;; ---------------------------------------------------------------------------
;; Core boot seed (P2, 2026-05-27)
;;
;; Per docs/prds/agent-runtime/mvp-completion-plan-2026-05-27.md §Phase 2
;; + research/repl-session-context-template-2026-05-26.md §5: the core
;; transacts the user entity + the my.kb.system instruction singleton plus an
;; introspection-indexed set of core fns at boot, BEFORE any agent turn —
;; so replay-from-tx-0 starts on a fully-seeded core, not mid-air.
;;
;; Tx-ordering at boot (in start-agent!):
;;   1. Entity-schema decomposition (schema/all-entity-schemas-tx-data)
;;      — already shipped, Item 4 commit 35035d8.
;;   2. seed-core!    — user entity + my.kb.system singleton
;;   3. index-core!   — :seon.ns + :seon.fn rows from REAL runtime
;;                           introspection (var meta + source file-read)
;;
;; Each transact carries `:seon.db/origin :core-seed` in tx-meta so
;; audit queries can isolate seed datoms from agent-produced ones.
;; ---------------------------------------------------------------------------

(defn seed-core!
  "Tx-data for THE user entity plus the EMPTY system-wide instruction
   singleton (`my.kb.system/seed-tx-data` — context-v4 §2.2 home 3;
   agents and the user APPEND rows at runtime, read back via
   `(my.kb.system/instructions)` in the creation-turn tutorial).

   The user row is the ONE `:seon.user/id` entity every
   `:seon.agent.message/from`/`to` user-ref resolves to (identity upsert,
   idempotent — same pattern as agent entities; one human for now).
   The instruction singleton identity-upserts on `:my.kb.system/id`
   carrying NO rows — re-running asserts zero new datoms and never
   clobbers runtime appends.

   Pure fn. Caller transacts via `db/transact!` with
   `:seon.db/origin :core-seed`."
  []
  (into [{:seon.user/id "user"}]
        (my.kb.system/seed-tx-data)))

;; ---------------------------------------------------------------------------
;; index-core! — runtime introspection of compiled core fns
;; (Step 2 of docs/prds/agent-runtime/coherent-bootstrap-indexing-2026-06-08.md)
;;
;; Replaces the old `core-fn-curated` / `synthesize-fn-source` / `seed-core-fns!`
;; hand-written table. Drives off a compile-time `#'`-LITERAL var-list (NOT
;; runtime symbols — in self-host CLJS `resolve` is a compile-time macro that
;; fails on runtime syms). For each var we read REAL data:
;;
;;   :seon.fn/spec      ← (some-> (:malli/schema (meta v)) m/schema m/form pr-str)
;;                        OMITTED when absent (honestly unspecced).
;;   :seon.fn/doc       ← (:doc (meta v))
;;   :seon.fn/source    ← READ the source FILE at the var's :file/:line and
;;                        paren-balance one form (cljs.repl/source-fn's
;;                        mechanism). :file/:line survive instrumentation.
;;   :seon.fn/arglists  ← parsed FROM that real source (the var-meta arglists
;;                        are mangled to ([arg]) for instrumented fns; the
;;                        source text carries the genuine arg vectors).
;;
;; This produces a `:seon.fn` row IDENTICAL in shape to a detect-and-tee row
;; (eval.cljs/build-tee-entities) — downstream readers never branch on origin.
;; The agent extends the indexed surface by transacting its own fns; grow
;; `core-vars` to widen the seeded set.
;; ---------------------------------------------------------------------------

(def ^:private curated-core-vars
  "Hand-curated `#'`-literal vars indexed REGARDLESS of `:malli/schema` —
   the honestly-unspecced core surface (`register!`, `current-agent-id`,
   the fs read fns) that the auto roster below can't see. MUST be
   `#'`-literals (self-host `resolve` is a compile-time macro)."
  [#'db/transact!
   #'db/query
   #'db/pull
   #'db/entity
   #'db/listen!
   #'db/current-agent-id
   #'schema/register!
   ;; Read surface on the user's machine (allowlist-gated, see seon.agent.fs).
   ;; Indexed so the functions catalog teaches the SEARCH→READ recipe.
   #'seon.agent.fs/read-file
   #'seon.agent.fs/list-dir
   #'seon.agent.fs/stat
   #'seon.agent.fs/walk-dir
   #'seon.agent.fs/home-dir
   #'seon.agent.search/grep
   #'seon.test.runner/run!])

(def ^:private core-vars
  "Every var indexed into the corpus at boot: the curated unspecced base
   PLUS the compile-time roster of every PUBLIC `:malli/schema`-carrying fn
   across the build's whole `seon.*` require closure
   (`seon.indexing/specced-fn-vars` — unit #23 fix b: 'all of the schemas,
   functions and tests in the cljs package should be present in the
   database'). Deduped by fully-qualified sym, curated entries first."
  (->> (into curated-core-vars (specced-fn-vars))
       (reduce (fn [[seen out] v]
                 (let [k (str (:ns (meta v)) "/" (:name (meta v)))]
                   (if (contains? seen k)
                     [seen out]
                     [(conj seen k) (conj out v)])))
               [#{} []])
       second))

;; Deftest vars the pod build loads, populated at load time by
;; `seon.dev.test-preload` (the ONE ns whose require closure contains the
;; test roster — a macro expanded HERE can't see nses compiled after this
;; file). Empty in builds without the preload (e.g. :node-test), where
;; tests index themselves via the runner's run-and-record path instead.
(defonce !indexed-test-vars (atom []))

;; Downstream extra-core vars (task #36 — SEON_EXTRA_SRC; spec:
;; docs/prds/agent-runtime/research/extra-src-research-2026-06-12.md §d).
;; A downstream consumer's entry ns (named by SEON_EXTRA_PRELOAD, loaded
;; via the :devtools :preloads slot bin/seon --config-merges in) registers
;; its specced surface here — the same precedent as `!indexed-test-vars`:
;;
;;   (reset! client/!extra-core-vars
;;           (filterv #(str/starts-with? (str (:ns (meta %))) "acme.")
;;                    (seon.indexing/specced-fn-vars)))
;;
;; The boot indexers consume it alongside `core-vars`: fn-rows +
;; FULL-SOURCE ns-rows in [[index-core!]], replay-skip membership in
;; [[core-ns-set]]. Empty in builds without a downstream preload.
(defonce !extra-core-vars (atom []))

(defn- extra-core-vars*
  "The registered extra vars MINUS any whose fully-qualified sym is
   already in `core-vars` — a downstream entry's `specced-fn-vars`
   expansion sees the seon surface its require closure pulls in, and
   those must dedup away silently (no duplicate rows, no reserved-prefix
   refusal) rather than double-index."
  []
  (let [have (into #{}
                   (map (fn [v] (str (:ns (meta v)) "/" (:name (meta v)))))
                   core-vars)]
    (into []
          (remove #(contains? have (str (:ns (meta %)) "/" (:name (meta %)))))
          @!extra-core-vars)))

(defn- reserved-extra-nses
  "The reserved-prefix violators among extra-var ns name strings:
   `seon.*` (the core's) and `my.*` (the human's store-replayed
   corpus — a COMPILED `my.*` ns would replay-skip what should be
   agent-authored rows). Sorted distinct vector; empty = all clear."
  [ns-strs]
  (->> ns-strs
       (filter (fn [s]
                 (some #(or (= s %) (str/starts-with? s (str % ".")))
                       ["seon" "my"])))
       distinct
       sort
       vec))

(defn- assert-extra-vars-unreserved!
  "LOUD structural refusal at boot-index time (extra-src research §e):
   throws, naming every offending ns, when the registered extra vars
   (post-dedup — see [[extra-core-vars*]]) provide reserved-prefix
   nses. Fires regardless of how the atom was populated."
  [vars]
  (let [bad (reserved-extra-nses (map #(str (:ns (meta %))) vars))]
    (when (seq bad)
      (throw (ex-info
               (str "extra-core registration provides RESERVED-prefix nses: "
                    (str/join ", " bad)
                    " — seon.* is the core's and my.* is the human's "
                    "store-replayed corpus; SEON_EXTRA_SRC code must live "
                    "under the downstream's own root prefix (e.g. acme.*)")
               {:seon.client/reserved-extra-nses bad})))))

(defn- extra-core-ns-strs
  "Ns name strings owned by the registered extra-core vars — these
   render FULL-SOURCE (the extra-src render-as-stubs gap closure: a
   downstream's nses are exactly the exemplar-grade surface it wants its
   agents reading whole)."
  []
  (into #{} (map #(str (:ns (meta %)))) @!extra-core-vars))

(def ^:private fn-less-compiled-roots
  "COMPILED core nses that own no indexed var (register! calls +
   ns-doc only) but DO own a boot-indexed full-source `:seon.ns` row —
   they must join [[core-ns-set]] (replay-skip: re-evaling their
   shipped source would re-run register! forms) and get an
   [[index-core!]] ns-row even though no fn-row names them."
  #{"my.kb" "my.soul"})

(defn core-ns-set
  "The set of namespace keywords owned by the COMPILED core, derived
   from `core-vars` + the preload's deftest vars — the SAME sources of
   truth the boot indexers write from, so they can never drift. Used by
   [[agent-ns-set]] as the DB-layer load discriminator: a `:seon.ns/name`
   row whose ns is in this set is a COMPILED core ns (already in the
   bundle from module-load; indexed for DISPLAY only) and is EXCLUDED
   from the load — only the agent-authored DB layer loads. Re-evaling a
   core row's source — e.g. `(defn ^:async transact! …)` — would shadow
   the real compiled fn, so core is never loaded; only agent-authored
   corpus (in `my.agent.<id>` / agent domain nses) loads.

   A fn (not a def) because `!indexed-test-vars` is populated by the
   preload AFTER this ns loads; robust by construction either way — it's
   NOT tx-meta and NOT a hand-typed ns list, it's the live var-meta `:ns`
   of the indexed vars. [[fn-less-compiled-roots]] joins explicitly
   because a fn-less compiled root (`my.kb`) owns an indexed full-source
   `:seon.ns` row without owning any var."
  []
  (into (into #{} (map keyword) fn-less-compiled-roots)
        (map #(keyword (str (:ns (meta %)))))
        (concat core-vars @!indexed-test-vars @!extra-core-vars)))

(defn- read-src-file
  "Read a core source file given a var-meta `:file` (classpath-relative,
   e.g. \"seon/db.cljs\" or \"seon/agent_context_test.cljs\"). Sources live
   under the deps.edn `:cljs` source roots (src, test, guest-cljs/src —
   probed in that order), resolved via `seon.platform/artifact-path`:
   CWD-relative when the pod runs from the repo root (seon's own usage),
   or under SEON_RUNTIME_ROOT when a downstream pod runs from its own
   world root. When SEON_EXTRA_SRC is set (task #36 — a downstream's
   compiled-in source root), `$SEON_EXTRA_SRC/src` and `/test` are probed
   AFTER the artifact roots, RAW (not via artifact-path — the extra root
   is not a seon-checkout artifact). Returns the file text, or nil if it
   can't be read."
  [file]
  (let [fs    (js/require "fs")
        extra (let [v (some-> (.. js/globalThis -process)
                              (.-env)
                              (aget "SEON_EXTRA_SRC"))]
                (when (and (string? v) (not= v ""))
                  [(str v "/src") (str v "/test")]))]
    (some (fn [root]
            (try
              (.readFileSync fs (str root "/" file) "utf8")
              (catch :default _ nil)))
          (concat (map seon.platform/artifact-path ["src" "test" "guest-cljs/src"])
                  extra))))

(defn- extract-form-at-line
  "Return the exact text of the top-level form beginning at `line-1based` in
   `txt` by paren-balancing (reader-free, so `::kw` / `#js` / reader-
   conditionals pass through verbatim). Tracks string + escape state so
   docstring parens don't unbalance. Returns nil if no `(` opens before EOF."
  [txt line-1based]
  (let [lines (vec (str/split-lines txt))
        start (str/join "\n" (subvec lines (dec line-1based)))
        n     (count start)]
    (loop [i 0 depth 0 in-str? false esc? false started? false]
      (if (>= i n)
        (when started? start)
        (let [c (nth start i)]
          (cond
            esc?                   (recur (inc i) depth in-str? false started?)
            (and in-str? (= c \\)) (recur (inc i) depth in-str? true  started?)
            in-str?                (recur (inc i) depth (not (= c \")) false started?)
            (= c \")               (recur (inc i) depth true false started?)
            (= c \()               (recur (inc i) (inc depth) in-str? false true)
            (= c \))               (let [d (dec depth)]
                                     (if (and started? (zero? d))
                                       (subs start 0 (inc i))
                                       (recur (inc i) d in-str? false started?)))
            :else                  (recur (inc i) depth in-str? false started?)))))))

(defn- ns-file-path
  "Classpath-relative source file for a namespace name string —
   `seon.agent.search` → `seon/agent/search.cljs`, `seon.agent.search-test` →
   `seon/agent/search_test.cljs` (munged like the compiler: dots → dirs,
   dashes → underscores). `read-src-file` probes the source roots
   (src, test, guest-cljs/src), so test siblings resolve too."
  [ns-sym-str]
  (str (-> ns-sym-str
           (str/replace "." "/")
           (str/replace "-" "_"))
       ".cljs"))

(defn- ns-row
  "Build the `:seon.ns` row for an owning ns name string.

   FULL-SOURCE nses (`seon.ctx/full-source-ns?` — all `my.*`, test
   siblings included) carry the REAL FULL FILE TEXT as
   `:seon.ns/source`: the boot indexer is the ONE file-reader; the
   `:namespaces` context section (and anything else downstream) renders
   that attr from the graph, never re-reading files. Safe because core
   rows are NOT loaded ([[agent-ns-set]] excludes any ns in `(core-ns-set)`
   from the DB-layer load). A
   full-source ns whose file can't be read falls back to the stub and
   logs fail-loud — the corpus stays honest.

   All OTHER core nses keep the minimal `(ns x)` stub — the
   `:namespaces` section compact-renders them from their indexed
   `:seon.fn`/`:seon.schema` member rows (API surface, bodies elided),
   and the stub keeps the no-replay invariant trivially cheap to reason
   about."
  [ns-sym-str]
  (let [stub (str "(ns " ns-sym-str ")")
        ;; Extra-core nses (downstream SEON_EXTRA_SRC code) are
        ;; full-source by rule, like my.* — closes the render-as-stubs
        ;; gap for the extra root.
        src  (if (or (ctx/full-source-ns? ns-sym-str)
                     (contains? (extra-core-ns-strs) ns-sym-str))
               (or (read-src-file (ns-file-path ns-sym-str))
                   (do (log/error-console!
                         "seon.client/ns-row"
                         (str "full-source ns " ns-sym-str " source file "
                              (pr-str (ns-file-path ns-sym-str))
                              " unreadable — falling back to the (ns x) stub"))
                       stub))
               stub)]
    {:seon.ns/name   (keyword ns-sym-str)
     :seon.ns/source src}))

(defn- arglists-from-source
  "Parse the pr-str-style arglists string (e.g. \"([{::keys [a b]}])\") from a
   `(defn …)` source text. Reader-free: an arg-vector is a `[..]` sitting
   either directly inside the defn list (paren-depth 1, brace-depth 0 —
   single-arity) or as the FIRST element of a list directly inside the defn
   (paren-depth 2 — each `([args] body)` arity of a multi-arity defn; the
   `fresh?` flag tracks \"just entered a depth-2 list, nothing but whitespace
   since\", so vectors elsewhere in arity bodies are never captured). This
   skips `{:malli/schema [...]}` metadata maps (brace-depth > 0). Collects
   every arg-vector across arities, wraps in parens. Tracks string, escape,
   `\\(` char-literal, and `;`-to-EOL comment state. Returns \"()\" if none
   found (caller treats that as no arglists)."
  [src]
  (let [n (count src)]
    (loop [i 0 pdepth 0 bdepth 0 in-str? false esc? false fresh? false vecs []]
      (if (>= i n)
        (str "(" (str/join " " vecs) ")")
        (let [c (nth src i)]
          (cond
            esc?                   (recur (inc i) pdepth bdepth in-str? false fresh? vecs)
            (and in-str? (= c \\)) (recur (inc i) pdepth bdepth in-str? true fresh? vecs)
            in-str?                (recur (inc i) pdepth bdepth (not (= c \")) false fresh? vecs)
            (= c \")               (recur (inc i) pdepth bdepth true false false vecs)
            (= c \\)               (recur (+ i 2) pdepth bdepth in-str? false false vecs)
            (= c \;)               (let [eol (loop [j i]
                                               (if (or (>= j n) (= (nth src j) \newline))
                                                 j (recur (inc j))))]
                                     (recur eol pdepth bdepth in-str? false fresh? vecs))
            (= c \()               (recur (inc i) (inc pdepth) bdepth in-str? false
                                          ;; entering a list DIRECTLY inside the
                                          ;; defn → candidate multi-arity body.
                                          (= (inc pdepth) 2) vecs)
            (= c \))               (recur (inc i) (dec pdepth) bdepth in-str? false false vecs)
            (= c \{)               (recur (inc i) pdepth (inc bdepth) in-str? false false vecs)
            (= c \})               (recur (inc i) pdepth (dec bdepth) in-str? false false vecs)
            (and (= c \[) (zero? bdepth)
                 (or (= pdepth 1)
                     (and (= pdepth 2) fresh?)))
            (let [vend (loop [j (inc i) vd 1 vs? false ve? false]
                         (if (>= j n) (dec j)
                             (let [vc (nth src j)]
                               (cond
                                 ve?                 (recur (inc j) vd vs? false)
                                 (and vs? (= vc \\)) (recur (inc j) vd vs? true)
                                 vs?                 (recur (inc j) vd (not (= vc \")) false)
                                 (= vc \")           (recur (inc j) vd true false)
                                 (= vc \\)           (recur (+ j 2) vd vs? false)
                                 (= vc \[)           (recur (inc j) (inc vd) vs? false)
                                 (= vc \])           (if (= vd 1) j (recur (inc j) (dec vd) vs? false))
                                 :else               (recur (inc j) vd vs? false)))))]
              (recur (inc vend) pdepth bdepth in-str? false false
                     (conj vecs (subs src i (inc vend)))))
            (or (= c \space) (= c \newline) (= c \tab) (= c \,) (= c \return))
            (recur (inc i) pdepth bdepth in-str? false fresh? vecs)
            :else (recur (inc i) pdepth bdepth in-str? false false vecs)))))))

(defn- expand-local-auto-kws
  "Display-expansion for arglists: rewrite namespace-LOCAL auto-resolved
   keywords (`::keys`, `::handler`) to their explicit form
   (`:seon.db/keys`) so an agent reading the arglist OUTSIDE the owning
   ns can't mis-resolve `::` against its own ns. Alias-qualified
   `::alias/x` is left untouched (the char class excludes `/` and the
   lookahead requires a delimiter right after the name, so `::db/key`
   never matches)."
  [arglists-str owning-ns-str]
  (str/replace arglists-str
               #"::([^\s,\[\]\{\}\(\)/]+)(?=[\s,\[\]\{\}\(\)]|$)"
               (str ":" owning-ns-str "/$1")))

(defn- var->fn-row
  "Build a `:seon.fn` row for a `#'`-literal core var from runtime
   introspection. Returns nil (and logs) when the source file can't be read or
   the form can't be extracted — NO `,,,` stub is ever persisted. `now` is the
   shared `:seon.fn/created-at` instant."
  [v now]
  (let [m       (meta v)
        sym     (str (:ns m) "/" (:name m))
        ns-kw   (keyword (str (:ns m)))
        ;; Per-var blast-radius guard (sci-not-available incident,
        ;; 2026-06-11): CLJS var meta is UNEVALUATED data, so a
        ;; `:malli/schema` form that embeds a symbol-referenced fn
        ;; (e.g. `[:fn live-tile/valid-hiccup?]`) needs sci — which the
        ;; pod doesn't bundle — and `m/schema` THROWS. One bad form
        ;; must degrade ONE row (spec omitted, loud :warn), never kill
        ;; boot + the whole context-test family. Registered forms are
        ;; pure data by platform law (see seon.render.live-tile); this
        ;; guard is the backstop for the metadata that isn't.
        spec    (when-some [ms (:malli/schema m)]
                  (try (-> ms m/schema m/form pr-str)
                       (catch :default e
                         (log/warn!
                           {:seon.log/source  ::var->fn-row
                            :seon.log/message
                            (str "spec for " sym " is not pure-data Malli "
                                 "(" (ex-message e) " — form " (pr-str ms)
                                 ") — row persisted WITHOUT :seon.fn/spec; "
                                 "fix the :malli/schema to reference "
                                 "registered schemas, not fn objects")})
                         nil)))
        txt     (read-src-file (:file m))
        src     (when txt (extract-form-at-line txt (:line m)))]
    (if (nil? src)
      (do (log/error-console!
            "seon.client/var->fn-row"
            (str "could not read real source for " sym
                 " (file " (pr-str (:file m)) " line " (:line m) ") — OMITTING"))
          nil)
      (cond-> {:seon.fn/sym        sym
               :seon.fn/ns         [:seon.ns/name ns-kw]
               :seon.fn/source     src
               :seon.fn/fn-var?    true
               :seon.fn/arglists   (expand-local-auto-kws
                                     (arglists-from-source src)
                                     (str (:ns m)))
               :seon.fn/doc        (or (:doc m) "")
               :seon.fn/private?   (boolean (:private m))
               :seon.fn/created-at now}
        ;; PRESENT ⇒ specced (exact contract in corpus); ABSENT ⇒ unspecced.
        (some? spec) (assoc :seon.fn/spec spec)))))

(defn index-core!
  "Tx-data for core `:seon.ns` + `:seon.fn` rows, built by REAL runtime
   introspection over `core-vars` (file-read at var-meta `:file`/`:line`
   + var meta for spec/doc). Replaces the old curated `seed-core-fns!`.

   Per owning ns, emits a `:seon.ns/name` + `:seon.ns/source` row (via
   [[ns-row]]) so the `[:seon.ns/name <kw>]` lookup-ref on `:seon.fn/ns`
   resolves. EXEMPLAR nses (context-focus-redesign root set) carry the
   REAL FULL FILE TEXT; all other core nses keep the minimal
   `(ns x)` stub. Both are load-safe: core rows are NOT loaded
   ([[agent-ns-set]] excludes any ns in `(core-ns-set)` from the
   DB-layer load).

   Always emits the FULL core row set — a function of `core-vars`
   + the registered `!extra-core-vars` + the on-disk source,
   independent of any conn. Re-seeding the same rows on a
   later boot is idempotent at the DB layer: every row upserts on its identity
   attr (`:seon.ns/name` / `:seon.fn/sym`). The lookup-ref `[:seon.ns/name <kw>]`
   is the only ref shape ever emitted for `:seon.fn/ns` (a single
   `:seon.db/ref`); it is never a bare keyword.

   Boot-time DEDUP (the \"fresh agent, same conn\" guard) is applied by the
   caller via [[core-index-tx]], which drops rows already present on the
   conn so a second/Nth `start-agent!` on the shared `*conn*` re-seeds nothing.
   Keeping THIS fn conn-free preserves its role as a pure tx-data builder (the
   shape the index-core-test guards rely on).

   Fns whose source can't be read are OMITTED, not stubbed — the corpus stays
   honest. Returns the tx-data vector; caller transacts under
   `:seon.db/origin :core-seed`."
  []
  (let [now     (js/Date.)
        ;; Downstream extra-core vars join the roster after the
        ;; sym-dedup against core-vars; the reserved-prefix guard
        ;; (seon.*/my.*) is the boot-index-time LOUD refusal — extra-src
        ;; research §e.
        extra   (extra-core-vars*)
        _       (assert-extra-vars-unreserved! extra)
        fn-rows (keep #(var->fn-row % now) (concat core-vars extra))
        ;; Fn-less compiled roots are unioned in explicitly: a root
        ;; with no public fns of its own (`my.kb` — register! calls +
        ;; ns-doc only) still needs its full-source `:seon.ns` row,
        ;; since the :namespaces section renders from exactly these
        ;; datoms.
        ns-syms (into (set fn-less-compiled-roots)
                      (map #(first (str/split (:seon.fn/sym %) #"/" 2)))
                      fn-rows)
        ns-rows (map ns-row (sort ns-syms))]
    (vec (concat ns-rows fn-rows))))

(def ^:private schema-source-cap
  "Char cap for the pr-str'd shape persisted as a boot-indexed
   `:seon.schema/source`. Registered forms are small (a type keyword or a
   short vector); the cap is a backstop against a pathological entity :map."
  1000)

(defn index-schemas
  "Tx-data for a `:seon.schema` row per REGISTERED schema — every key in
   `seon.schema/registered-schemas`, attr-level and request/response shapes
   included, not just the entity `:map` kinds (`all-entity-schemas-tx-data`
   covers those separately with id-attr/required-attrs; identity upsert on
   `:seon.schema/key` merges the two). `:seon.schema/source` is the
   registered Malli FORM (pr-str), so the full shape of every attr is one
   entity-read away for the agent.

   Pure tx-data builder; the boot dedup in [[core-index-tx]] drops
   keys already present on the conn, so an agent's own
   `(seon.schema/register! …)` tee row (whose :source is the replayable
   call form) is NEVER overwritten by the boot index."
  []
  (let [now (js/Date.)]
    (into []
          (keep (fn [[k v]]
                  (when (keyword? k)
                    (let [form (try (if (m/schema? v) (m/form v) v)
                                    (catch :default _ v))
                          s    (pr-str form)
                          src  (if (> (count s) schema-source-cap)
                                 (str (subs s 0 schema-source-cap) " …")
                                 s)]
                      (cond-> {:seon.schema/key        k
                               :seon.schema/source     src
                               :seon.schema/created-at now}
                        (namespace k)
                        (assoc :seon.schema/ns
                               {:seon.ns/name (keyword (namespace k))}))))))
          (schema/registered-schemas))))

(defn- var->test-row
  "Build a `:seon.test` row for a deftest `#'`-literal from runtime
   introspection (same mechanism as [[var->fn-row]]: real source file-read
   at the var's `:file`/`:line`). Returns nil (and logs) when the source
   can't be read — no stub is ever persisted."
  [v now]
  (let [m   (meta v)
        sym (str (:ns m) "/" (:name m))
        txt (read-src-file (:file m))
        src (when txt (extract-form-at-line txt (:line m)))]
    (if (nil? src)
      (do (log/error-console!
            "seon.client/var->test-row"
            (str "could not read real source for " sym
                 " (file " (pr-str (:file m)) " line " (:line m) ") — OMITTING"))
          nil)
      {:seon.test/sym        sym
       :seon.test/ns         [:seon.ns/name (keyword (str (:ns m)))]
       :seon.test/source     src
       :seon.test/created-at now})))

(defn index-tests
  "Tx-data for `:seon.test` + owning `:seon.ns` rows from deftest
   `#'`-literal vars (default: the preload-populated `!indexed-test-vars` —
   every deftest the pod build loads). Same shape the detect-and-tee path
   writes, so downstream readers never branch on origin. Pure tx-data
   builder; [[core-index-tx]] dedups against the conn."
  ([] (index-tests @!indexed-test-vars))
  ([vars]
   (let [now     (js/Date.)
         rows    (keep #(var->test-row % now) vars)
         ns-syms (into #{} (map #(first (str/split (:seon.test/sym %) #"/" 2)) rows))
         ns-rows (map ns-row (sort ns-syms))]
     (vec (concat ns-rows rows)))))

(defn ^:async core-index-tx
  "Boot-time core index tx-data: [[index-core!]] + [[index-schemas]]
   + [[index-tests]] filtered to the rows not yet present on `conn`. This is
   the idempotency guard for the
   \"fresh agent, same conn\" path — on the FIRST boot of a conn it returns the
   full set; on the SECOND and Nth boot (a second `start-agent!` on the shared
   `*conn*`, or a reconnect to a persistent store that already holds the
   core index) it returns ONLY rows whose `:seon.fn/sym` / `:seon.ns/name`
   identity is absent — typically `[]`.

   Querying the conn's CURRENT identity set and emitting only the gap means a
   re-index never re-transacts a core row against the populated store —
   removing the re-seed interaction that the Run-3 findings traced to a
   malformed `:seon.fn/ns` value. Returns a Promise of the tx-data vector."
  [conn]
  (let [all       (concat (index-core!)
                          (index-schemas)
                          (index-tests))
        db        (await (d/db conn))
        have-fns  (into #{} (map first) (d/q '[:find ?sym :where [?f :seon.fn/sym ?sym]] db))
        ;; ns rows dedup on name AND source: a `:seon.ns` row re-emits when
        ;; its stored `:seon.ns/source` differs from the freshly-built one —
        ;; this keeps the stored source tracking the build (e.g. a my.*
        ;; full-source ns whose file changed). Identity upsert on
        ;; `:seon.ns/name` means the re-emit lands as one `:seon.ns/source`
        ;; re-assertion, never a duplicate entity.
        have-nses (into {} (d/q '[:find ?nm ?src
                                  :where
                                  [?n :seon.ns/name ?nm]
                                  [?n :seon.ns/source ?src]]
                                db))
        ;; Schema rows dedup on key AND source (mirrors the ns-row rule
        ;; above): a boot-indexed `:seon.schema` row RE-EMITS when its
        ;; stored `:seon.schema/source` differs from the freshly-built
        ;; one — identity upsert on `:seon.schema/key` re-asserts the
        ;; source in place. This is what HEALS a pre-existing store
        ;; whose rows carry unreadable pre-pure-data-law sources (the
        ;; 2026-06-11 `[:fn #object[…]]` poison). An agent's own
        ;; `(seon.schema/register! …)` TEE row — a replayable `(…)`
        ;; call form, the same discriminator replay uses — is still
        ;; NEVER overwritten by the boot index.
        have-schs (into {} (d/q '[:find ?k ?src
                                  :where
                                  [?s :seon.schema/key ?k]
                                  [?s :seon.schema/source ?src]]
                                db))
        have-tsts (into #{} (map first) (d/q '[:find ?t :where [?e :seon.test/sym ?t]] db))]
    (vec (remove (fn [row]
                   (or (contains? have-fns  (:seon.fn/sym row))
                       (and (contains? row :seon.ns/name)
                            (= (get have-nses (:seon.ns/name row))
                               (:seon.ns/source row)))
                       (when-some [stored (get have-schs (:seon.schema/key row))]
                         (or (= stored (:seon.schema/source row))
                             (seval/registration-call-source? stored)))
                       (contains? have-tsts (:seon.test/sym row))))
                 all))))

(defn ^:async prune-core-ghosts!
  "Boot-index GC (open-issues 2026-06-11, agent-reported row 5): retract
   program-graph rows the boot indexers ONCE wrote but whose source
   ns/fn/test/schema no longer exists in the booting code. `core-index-tx`
   re-emits rows when source CHANGES (drift-healing) but never retracts —
   renames and deletions left ghosts that rendered into every context
   forever (live incident: the deleted `my.kb.instruction` ns kept
   injecting its dead teachings until manually retracted; the removed
   `:seon.render.chat/bubble` registration left a stale `:seon.schema` row).

   A stored row is a GHOST iff ALL of:

     1. CORE-CLAIMED — its `:source` datom's tx carries
        `:seon.db/origin :core-seed`, the persisted provenance claim
        every boot-index transact writes (and the origin-forge guard in
        `seon.db.internal` protects). Agent-authored rows (detect-and-tee,
        replay, runner) carry other origins and are NEVER candidates —
        even when their shape is identical and their ns is absent from
        this build.
     2. ABSENT FROM THIS BOOT — its ident (`:seon.ns/name` /
        `:seon.fn/sym` / `:seon.test/sym` / `:seon.schema/key`) is not in
        the freshly-built index set (the same pure builders
        `core-index-tx` transacts from). A rename prunes the old
        ident and keeps the new one.
     3. NOT an agent `(…)` registration call — `:seon.schema` rows keep
        replay's `registration-call-source?` discriminator (Step 4):
        a `(seon.schema/register! …)` tee row is agent corpus, never
        pruned, regardless of provenance.

   A kind whose freshly-built ident set is EMPTY is skipped entirely
   (degenerate-boot guard: an unreadable source tree must not mass-prune
   the store; `:test` is legitimately empty in builds without the preload).

   Runs BEFORE `replay-program-graph!` in `start-agent!` — load-bearing:
   a deleted ns falls OUT of `(core-ns-set)`, so its ghost rows would
   otherwise be misclassified as agent corpus and REPLAYED back into the
   live compile-state.

   Idempotent: pruned rows are gone, so the second boot finds zero
   candidates. Loud: one `:seon.log` info names every pruned row and why.
   Returns a Promise of `{:seon.client/pruned [[kind ident] …]}`."
  [conn]
  (let [idx    (index-core!)
        tsts   (index-tests)
        live   {:ns     (into #{} (keep :seon.ns/name) (concat idx tsts))
                :fn     (into #{} (keep :seon.fn/sym) idx)
                :test   (into #{} (keep :seon.test/sym) tsts)
                :schema (into #{} (keep :seon.schema/key) (index-schemas))}
        db     (await (d/db conn))
        rows   (d/q '[:find ?e ?ident ?source ?kind
                      :where
                      (or-join [?e ?ident ?source ?kind ?tx]
                        (and [?e :seon.ns/name   ?ident]
                             [?e :seon.ns/source ?source ?tx]
                             [(ground :ns) ?kind])
                        (and [?e :seon.fn/sym    ?ident]
                             [?e :seon.fn/source ?source ?tx]
                             [(ground :fn) ?kind])
                        (and [?e :seon.test/sym    ?ident]
                             [?e :seon.test/source ?source ?tx]
                             [(ground :test) ?kind])
                        (and [?e :seon.schema/key    ?ident]
                             [?e :seon.schema/source ?source ?tx]
                             [(ground :schema) ?kind]))
                      [?tx :seon.db/origin :core-seed]]
                    db)
        ghosts (->> rows
                    (keep (fn [[e ident source kind]]
                            (let [fresh (get live kind)]
                              (when (and (seq fresh)
                                         (not (contains? fresh ident))
                                         (not (and (= :schema kind)
                                                   (seval/registration-call-source? source))))
                                {:e e :kind kind :ident ident}))))
                    (sort-by (fn [{:keys [kind ident]}] [kind (str ident)]))
                    vec)]
    (when (seq ghosts)
      (await (log/info!
               {:seon.log/source  ::prune-core-ghosts!
                :seon.log/message
                (str "boot-index GC: pruned " (count ghosts)
                     " core ghost row(s) — core-seeded "
                     "program-graph rows whose source no longer exists "
                     "in the booting code: "
                     (str/join ", " (map (fn [{:keys [kind ident]}]
                                           (str (name kind) " " (pr-str ident)))
                                         ghosts)))}))
      (let [res (await (db/transact!
                         conn
                         (mapv (fn [{:keys [e]}] [:db/retractEntity e]) ghosts)
                         {:seon.db/origin :core-seed}))]
        ;; Boot maintenance stays fail-loud (same posture as the seed
        ;; transacts): a silent half-prune would leave the store lying.
        (when-not (:seon.db/ok? res)
          (throw (ex-info (str "boot-index GC retract failed: "
                               (get-in res [:seon.db/error :seon.error/message]))
                          {:seon.client/pruned (mapv (juxt :kind :ident) ghosts)
                           :seon.db/error      (:seon.db/error res)})))))
    {:seon.client/pruned (mapv (juxt :kind :ident) ghosts)}))

(defn- stub-llm
  "A fake LLM that demonstrates the REPL-as-harness response shape: a
   `;; narration` line then a real `seon.agent/reply!` form. The
   loop's reply-landed stop policy
   (`seon.agent/replied-since-inbound?`, #35) ends the wake after this
   turn — the old extra `:seon.agent/state :idle` transact taught a
   non-mechanism (the loop never read mid-turn state) and burned turns
   to the cap (#22). Returns a Promise of {:text \"...\"}."
  [ctx]
  (let [text (str
               ";; stub LLM here — the real one needs DEEPSEEK_API_KEY\n"
               ";; reply to whoever woke this turn — replying ends the wake\n"
               "(seon.agent/reply!\n"
               "  {:seon.agent.message/content "
               (pr-str (str "hello from the stub LLM — saw "
                            (count ctx) " chars of ctx"))
               "})\n")]
    (.then (.resolve js/Promise nil) (fn [_] {:text text}))))

(defn- current-llm-fn
  "The llm-fn for this pod process: the provider the `:seon.ai/config`
   row / SEON_AI_PROVIDER selects (`seon.ai/provider` — default
   :deepseek, C-20) when its API key is set, else the stub. Single
   selection point — `-main` and the hot-reload re-arm both call it.
   Rebuilt FRESH at each call (not cached) so a hot reload of the
   adapter ns takes effect on re-armed listeners; a registry of
   boot-time llm-fn closures would pin agents to pre-reload adapter
   code, defeating the re-arm."
  []
  (case (ai/provider)
    :anthropic     (if (.. js/process -env -ANTHROPIC_API_KEY)
                     (anthropic/agent-adapter)
                     stub-llm)
    ;; :openai-compat rides the SAME adapter as :deepseek (the wire
    ;; format is OpenAI's) — endpoint + key resolve per call from the
    ;; :seon.ai/config row / SEON_AI_* env (see seon.ai.openai-compat's
    ;; ns doc). :deepseek likewise uses the shared key resolution
    ;; (DEEPSEEK_API_KEY default, SEON_AI_API_KEY / api-key-env too).
    (if (openai/api-key-configured?)
      (openai/agent-adapter)
      stub-llm)))

(defn- live-agent-ids
  "Agent ids whose `:seon.agent/state` is `:idle` or `:running` AND
   that are not completed (`:seon.agent/completed-at` absent = active —
   see `seon.agent/complete!`) — the agents whose user-message triggers
   must exist. Derived from the DB at call time (reactive-context: no
   stored agent registry)."
  [db]
  (->> (db/query {:seon.db/query '[:find ?aid
                                   :where
                                   [?a :seon.agent/id ?aid]
                                   [?a :seon.agent/state ?state]
                                   [(contains? #{:idle :running} ?state)]
                                   (not [?a :seon.agent/completed-at _])]
                  :seon.db/db db})
       (mapv first)))

(defn resumable-agent-ids
  "Agent ids a booting pod RESUMES: every `:seon.agent/id` entity in db
   value `db` WITHOUT `:seon.agent/completed-at` (absent = active — see
   `seon.agent/complete!`). Sorted asc for deterministic boot logs.
   Empty = genuine first boot → `start-agent!` mints a fresh agent."
  {:malli/schema [:=> [:catn [:seon.db/db-val :seon.db/db-val]]
                  [:vector :seon.db/id]]}
  [db]
  (->> (db/query {:seon.db/query '[:find ?aid
                                   :where
                                   [?a :seon.agent/id ?aid]
                                   (not [?a :seon.agent/completed-at _])]
                  :seon.db/db db})
       (map first)
       sort
       vec))

(defn- rearm-user-triggers!
  "Hot-reload hygiene (work-plan 1.2): re-install the per-agent
   user-message trigger for every live agent so handler fixes take
   effect on hot reload. Without this, the registered tx-listener
   closures keep running pre-reload code (observed live 2026-06-09:
   the trigger-scoping fix in seon.agent/user-msg-for-agent? did not
   reach live agents until a manual install-user-trigger! per agent).

   Everything is derived, nothing stored: agent ids from the DB
   (`live-agent-ids`), compile-state from the idempotent
   `repl/ensure-bootstrap!`, llm-fn rebuilt via `current-llm-fn`.
   `agent/install-user-trigger!` is itself idempotent (unlistens the
   prior key), so re-running per reload is safe.

   Fire-and-forget from `after-reload` (which is sync): returns a
   Promise resolving to the re-armed id vector; errors are logged
   loudly, never swallowed. No-op (resolves []) before the first
   `start-agent!` (no conn yet)."
  []
  (if-let [conn @!agent-conn]
    (do
      ;; Re-assert the root *conn*. set! at boot survives a client.cljs
      ;; reload, but a reload that touches seon.db re-evaluates the
      ;; dynamic def and wipes the root — re-arming is the natural
      ;; place to restore it.
      (set! db/*conn* conn)
      (-> (repl/ensure-bootstrap!)
          (.then
            (fn [compile-state]
              (let [ids    (live-agent-ids @conn)
                    llm-fn (current-llm-fn)]
                (doseq [id ids]
                  ;; Re-host so a hot-reloaded pod stays MCP-addressable
                  ;; for every agent it re-arms (runtime-id is defonce'd,
                  ;; but re-hosting is idempotent and keeps the two
                  ;; rosters trivially in sync).
                  (runtime-id/host! id)
                  (agent/install-user-trigger!
                    {:seon.agent/id            id
                     :seon.agent/llm-fn        llm-fn
                     :seon.agent/compile-state compile-state}))
                (log/info-console! "seon.client"
                                   "reload: user-message triggers re-armed"
                                   {:seon.client/reinstalled ids})
                ids)))
          (.catch
            (fn [err]
              (log/error-console! "seon.client"
                                  "reload: user-message trigger re-arm FAILED"
                                  err)))))
    (js/Promise.resolve [])))

(defn- ^:async boot-one-agent!
  "The per-agent slice of the boot path: prime the agent's home
   namespace (atoms + accessors), create/refresh its entity + arm the
   kick listener (`agent/boot!`), and `runtime-id/host!` the id so
   `mcp__seon_cljs__eval agent_id=<id>` pins this pod. Runs inside its
   own `with-agent` scope so every transact carries the right agent-id.
   Returns boot!'s `{:seon.agent/id _ :seon.agent/ns _}`."
  [{:seon.agent/keys [id llm-fn compile-state purpose]}]
  (await
    (db/with-agent id
      (fn ^:async boot-agent! []
        (await (seval/setup-agent-ns! compile-state (agent/home-ns id) id))
        (let [res (await (agent/boot!
                           (cond->
                             {:seon.agent/id            id
                              :seon.agent/llm-fn        llm-fn
                              :seon.agent/compile-state compile-state}
                             (some? purpose)
                             (assoc :seon.agent/purpose purpose))))]
          ;; boot! propagates create!'s db error envelope (task #21 —
          ;; errors are values). A failed create means NO agent entity:
          ;; don't host the id for MCP addressing, hand the envelope up.
          (if (false? (:seon.db/ok? res))
            (do (log/error-console!
                  "seon.client/boot-one-agent!"
                  (str "agent/boot! FAILED for " id
                       " — no entity; propagating the error envelope")
                  res)
                res)
            (do (runtime-id/host! id)
                res)))))))

(schema/register! ::seeded? [:= true])
(schema/register! ::boot-seed-request  [:map [:seon.db/conn :seon.db/conn]])
(schema/register! ::boot-seed-response [:map [::seeded? ::seeded?]])

(defn ^:async boot-seed!
  "THE core boot seed — ONE code path for 'make this store the
   world a pod boots into', shared verbatim by `start-agent!` (live
   boot) and the gym's `seed-scenario-world!` (scratch worlds), so the
   two can never drift again (the gym hand-mirrored this sequence and
   drifted twice — most recently missing the `:soul-seed` step, so gym
   prompts lacked the `:my.soul` rows live prompts carry).

   Steps, in boot order:
     1. Core handler SCHEMA — `h/bootstrap-schema!` +
        `wake/bootstrap-schema!` (raw `:db/ident` attr rows; never
        counted as data).
     2. Under ONE `{:seon.db/origin :core-seed}` tx-context, five
        transacts (each its own tx so the core prefix stays a
        stable sequence of tx-times):
          :wake-handler    — the ONE `:wake/on-message` handler entity
                             (idempotent upsert; data-only — registering
                             arms no dispatcher). INSIDE the seed
                             context so its row carries `:core-seed`
                             provenance — a fresh world boots with
                             FACTS=0 (machinery rows are never the
                             cluster's data; demo-polish fix
                             2026-06-12).
          :entity-schemas  — `schema/all-entity-schemas-tx-data`.
          :core-seed  — `seed-core!` (user entity +
                             my.kb.system instruction singleton).
          :soul-seed       — `my.soul/seed-tx-data`, SEED-ONLY-IF-
                             ABSENT (a user's runtime soul edit is
                             never clobbered by reboot); skipped
                             entirely when every row already exists.
          :core-index — `core-index-tx` (`:seon.ns` /
                             `:seon.fn` / `:seon.schema` / `:seon.test`
                             rows, conn-deduped so an Nth boot on the
                             same store re-seeds nothing).

   Pins the root `db/*conn*` to `conn` for the duration (the handler
   bootstrap fns read it), restoring in `finally`. ENVELOPE CONTRACT
   (A4): `db/transact!` never rejects, so every step checks the
   envelope and THROWS (surface-errors-loudly) — a silent partial seed
   is far worse than a crashed boot."
  {:malli/schema [:=> [:cat ::boot-seed-request] ::boot-seed-response]}
  [{conn :seon.db/conn}]
  (let [prev-conn db/*conn*]
    (set! db/*conn* conn)
    (try
      (await (h/bootstrap-schema!))
      (await (wake/bootstrap-schema!))
      (let [index-tx (await (core-index-tx conn))
            check!   (fn [step {ok?   :seon.db/ok?
                                error :seon.db/error}]
                       (when-not ok?
                         (throw (ex-info
                                  (str "boot seed transact failed at "
                                       step ": "
                                       (:seon.error/message error))
                                  {:seon.client/seed-step step
                                   :seon.db/error error}))))]
        (await
          (db/with-tx-context
            {:seon.db/origin :core-seed}
            (fn ^:async seed! []
              (let [{registered? :seon.handler/registered?}
                    (await (h/register!
                             {:seon.handler/name      :wake/on-message
                              :seon.handler/match     {:seon.handler.match/attr
                                                       :seon.agent.message/to}
                              :seon.handler/fn        'seon.handlers.wake/wake-on-message
                              :seon.handler/on-origin #{:user :agent}}))]
                (when-not registered?
                  (throw (ex-info "boot seed transact failed at :wake-handler"
                                  {:seon.client/seed-step :wake-handler}))))
              (check! :entity-schemas
                      (await (db/transact!
                               {:seon.db/conn conn
                                :seon.db/tx-data
                                (schema/all-entity-schemas-tx-data)})))
              (check! :core-seed
                      (await (db/transact!
                               {:seon.db/conn conn
                                :seon.db/tx-data (seed-core!)})))
              (let [soul-tx (my.soul/seed-tx-data (await (d/db conn)))]
                (when (seq soul-tx)
                  (check! :soul-seed
                          (await (db/transact!
                                   {:seon.db/conn conn
                                    :seon.db/tx-data soul-tx})))))
              (check! :core-index
                      (await (db/transact!
                               {:seon.db/conn conn
                                :seon.db/tx-data index-tx})))))))
      {::seeded? true}
      (finally
        (set! db/*conn* prev-conn)))))

(def inventory-read-source
  "The creation-turn `store-inventory` eval (context-v4 §2.4/§3.1,
   V4-3): the catalogs died as sections; the inventory is a core
   fn the creation turn demonstrably RUNS — the fn's source is visible
   in the rendered `seon.db` namespace tag, the CALL is visible here,
   the RESULT is the value line, and re-running the fn is how you get
   fresh numbers. The `;;` comments ride as the eval's narration."
  (str
    ";; What's already in the shared store? Other agents stored knowledge\n"
    ";; here before me — checking BEFORE researching is how I avoid paying\n"
    ";; for answers that already exist. (An ordinary query; I re-run it\n"
    ";; whenever I need current numbers.)\n"
    "(seon.db/store-inventory)\n"))

(def instructions-read-source
  "The creation-turn READ of the system-wide instructions (context-v4
   PRD §3.1 tutorial register, V4-0): the fn's source is visible in
   the rendered `my.kb.system` namespace, the CALL is visible in the
   transcript, the RESULT is the value line — and re-running the fn is
   how the agent gets the current set. The `;;` comments ride as the
   eval's narration via `parse-forms`."
  (str
    ";; Next: the system-wide instructions — standing guidance for ALL\n"
    ";; agents in this cluster. Anyone (my human, another agent, me) can\n"
    ";; append a row; I re-read when I want the current set.\n"
    "(my.kb.system/instructions)\n"))

(defn ^:async creation-evals!
  "The startup eval block a NEWLY MINTED agent runs as its first
   logged act (live-tiles PRD 2026-06-11 §6 U4 + context-v4 §3.1):
   real evals, AS the agent, through the same `eval-batch!` path every
   turn uses — (1) the tile welcome wiring
   (`seon.render.live-tile/wiring-source`), whose tutorial `;;`
   comments ride as the eval's narration, and (2) the
   `(my.kb.system/instructions)` read ([[instructions-read-source]],
   V4-0) so the system-wide instruction rows land in the transcript as
   a demonstrated query. The wiring eval transacts
   `:seon.render.live-tile/content` onto the agent's own entity by
   lookup ref; the datom is durable, so a pod restart resumes the
   wiring with no re-seed. CREATION ONLY — resumed agents are never
   retro-wired (their unwired state falls back to welcome at render
   via `seon.render.live-tile/wired-content`).

   Opens the agent's first session + a creation turn so the eval
   lands where every eval lands (sessions → turns → evals) and the
   agent re-reads it in its own transcript. Returns the eval-batch!
   summary map; failures are logged LOUDLY but never abort the boot —
   a missing wiring datom only means the welcome fallback."
  [{:seon.agent/keys [id compile-state]}]
  (await
    (db/with-agent id
      (fn ^:async run-creation-evals! []
        (try
          (let [session    (await (agent/ensure-session! id))
                session-id (:seon.agent.session/id session)
                turn-id    (db/new-id!)
                source     (str (live-tile/wiring-source id)
                                "\n"
                                inventory-read-source
                                "\n"
                                instructions-read-source)
                batch
                (await
                  (db/with-tx-context
                    {:seon.db/agent-id   id
                     :seon.db/session-id session-id
                     :seon.db/turn-id    turn-id
                     :seon.db/origin     :system}
                    (fn []
                      (agent/with-turn!
                        {:seon.agent/id                    id
                         :seon.agent.session/id-of-session session-id
                         :seon.agent.turn/id-of-turn       turn-id
                         :seon.agent.turn/prompt-text      ""}
                        (fn ^:async creation-turn-body! []
                          (await (seval/eval-batch!
                                   compile-state
                                   (repl/parse-forms source)
                                   (agent/home-ns id)
                                   id turn-id)))))))]
            (when (pos? (or (:seon.eval/n-fail batch) 0))
              (log/error-console!
                "seon.client/creation-evals!"
                "creation wiring eval FAILED — tile falls back to welcome at render"
                {:seon.agent/id id :seon.eval/batch batch}))
            batch)
          (catch :default e
            (log/error-console!
              "seon.client/creation-evals!"
              "creation eval block THREW — tile falls back to welcome at render"
              e)
            {:seon.eval/ids [] :seon.eval/n-ok 0 :seon.eval/n-fail 1}))))))

(defn ^:async start-agent!
  "Bring up the pod's agents: open conn, init bootstrap-CLJS, then
   RESUME every active agent in the cluster store — the agent entities
   WITHOUT `:seon.agent/completed-at` (see `seon.agent/complete!`) —
   re-arming each one's user-message trigger. A fresh agent is minted
   ONLY when the store has zero resumable agents (genuine first boot)
   or on the explicit create path (`:mint? true` — POST /agents/new).
   Identity is durable: restarting the pod does NOT accumulate agents.

     :llm-fn — fn of ctx-string returning a Promise of {:text \"...\"}.
               Optional; defaults to stub-llm for verification without
               an API key. Pass (seon.ai.openai-compat/agent-adapter)
               for the real thing.
     :mint?  — force-mint a NEW agent even when resumable agents exist
               (they are already armed from boot). The /agents/new path.

   Returns a Promise resolving to
     {:seon.agent/id _ :seon.agent/ns _          ; the minted agent, or
                                                 ; the first resumed one
      :seon.client/resumed-ids [...]
      :seon.client/minted-ids  [...]
      :seon.web/port _ :seon.web/port-file _}.
   Subsequent (seon.agent/message! …) calls (or POST /chat) drive the
   loop via the kick listener."
  [& [{:keys [llm-fn mint? purpose] :or {llm-fn stub-llm}}]]
  (let [conn          (or @!agent-conn (await (open-cluster-conn!)))
        _             (reset! !agent-conn conn)
        ;; Bind the conn as the root *conn* so seon.db calls + the
        ;; kick listener resolve without per-call threading.
        _             (set! db/*conn* conn)
        ;; v1.md §7.1 boot preconditions. Throws cleanly if the conn
        ;; was opened without :keep-history? OR if the seon.db tx-meta
        ;; attrs aren't registered. Catches misconfigured schema
        ;; registries before the agent starts writing txs that would
        ;; silently lose their causality bundle.
        _             (db/assert-preconditions! {:seon.db/conn conn})
        ;; Bootstrap-CLJS init via the shared iteration-surface atom.
        ;; Version-stamped — a hot-reload of seon.eval rotates the
        ;; gensym so the next call rebuilds the state. Idempotent
        ;; while the core code is stable.
        compile-state (await (repl/ensure-bootstrap!))
        ;; RESUME, DON'T MINT (P3.5/#31): the store is the identity
        ;; source. Mint only on genuine first boot or explicit create.
        resumed-ids   (if mint? [] (resumable-agent-ids @conn))
        minted-ids    (if (empty? resumed-ids) [(db/new-id!)] [])
        agent-ids     (into resumed-ids minted-ids)
        ;; The PRIMARY agent (return-shape + shared-boot tx scope):
        ;; the minted one when minting, else the first resumed.
        primary       (first agent-ids)]
    (log/info-console! "seon.client/start-agent!" "agent roster"
                       {:seon.client/resumed resumed-ids
                        :seon.client/minted  minted-ids})
    (await
      (db/with-agent primary
        (fn ^:async boot-with-agent! []
          (let [;; Boot-index GC — MUST run before replay: a DELETED
                ;; core ns falls out of (core-ns-set), so its
                ;; ghost rows would be misclassified as agent corpus and
                ;; replayed back into the live compile-state (the
                ;; my.kb.instruction dead-teachings incident). Idempotent;
                ;; loud (one :seon.log info naming every pruned row).
                prune-stats   (await (prune-core-ghosts! conn))
                _             (log/info-console!
                                "seon.client/start-agent!"
                                (str "boot-index GC: "
                                     (count (:seon.client/pruned prune-stats))
                                     " ghost row(s) pruned"))
                ;; Load the agent-authored DB LAYER on top of the compiled
                ;; package: each agent ns's reconstituted whole source, in
                ;; dependency order. GLOBAL (not per-agent) — runs ONCE per
                ;; boot, before any per-agent setup. Core nses are EXCLUDED
                ;; ([[agent-ns-set]] drops `(core-ns-set)`) — they're
                ;; compiled (display-only rows). Idempotent against an
                ;; empty conn (genuine first boot) — returns
                ;; {…replay-n-total 0 …}.
                replay-stats  (await (replay-program-graph!
                                       {:conn          conn
                                        :compile-state compile-state
                                        :agent-id      primary}))
                _             (log/info-console!
                                "seon.client/start-agent!"
                                (str "replay: " (pr-str replay-stats)))
                ;; Per-agent boots — home ns + entity + kick listener +
                ;; MCP hosting, one at a time (boot transacts must not
                ;; interleave). The primary's result feeds the return map.
                results       (let [!acc (volatile! [])]
                                (doseq [aid agent-ids]
                                  (vswap! !acc conj
                                          (await (boot-one-agent!
                                                   (cond->
                                                     {:seon.agent/id            aid
                                                      :seon.agent/llm-fn        llm-fn
                                                      :seon.agent/compile-state compile-state}
                                                     ;; The stated purpose seeds
                                                     ;; ONLY a minted agent
                                                     ;; (create! never re-seeds
                                                     ;; an existing entity).
                                                     (some #{aid} minted-ids)
                                                     (assoc :seon.agent/purpose purpose)))))
                                  ;; U4: the startup eval block —
                                  ;; CREATION ONLY (a resumed agent is
                                  ;; never retro-wired; unwired falls
                                  ;; back to welcome at render).
                                  (when (some #{aid} minted-ids)
                                    (await (creation-evals!
                                             {:seon.agent/id            aid
                                              :seon.agent/compile-state compile-state}))))
                                @!acc)
                ;; Task #21: a boot-one-agent! that came back as an
                ;; error envelope means an agent with NO entity —
                ;; destructuring :seon.agent/id below would thread nil
                ;; through the whole boot. Same stance as boot-seed!'s
                ;; check!: a crashed boot beats a silent ghost roster.
                _             (when-let [bad (some #(when (false? (:seon.db/ok? %)) %)
                                                   results)]
                                (throw (ex-info
                                         (str "start-agent!: agent boot "
                                              "transact failed — refusing to "
                                              "boot the pod on a ghost roster")
                                         bad)))
                {:seon.agent/keys [id ns]}
                (first results)
                ;; Boot the pod's HTTP+SSE server (A-5). The browser hits
                ;; this for the dev iteration loop.
                {:seon.web/keys [port port-file]}
                (await (web.serve/start!))
                ;; THE core boot seed — handlers + the four seed
                ;; transacts, extracted to [[boot-seed!]] so the gym's
                ;; scenario worlds run the boot's OWN code path (one
                ;; mechanism — the hand-mirrored copy drifted twice).
                _ (await (boot-seed! {:seon.db/conn conn}))
                ;; C-18: sync the :seon.ai/config row to the SEON_AI_*
                ;; env vars (env owns the row across boots). Fire-and-
                ;; forget — sync! never rejects, logs its own failures.
                _ (ai/sync!)
                ;; Install the per-agent inspector tx-listener. Pushes
                ;; morphs for the agent-view inspector page (/agent/<id>).
                _ (seon.web.inspector/install!)]
            (log/info-console! "seon.client" "agents started"
                               {:resumed resumed-ids :minted minted-ids
                                :port port :port-file port-file})
            {:seon.agent/id           id
             :seon.agent/ns           ns
             :seon.client/resumed-ids resumed-ids
             :seon.client/minted-ids  minted-ids
             :seon.web/port           port
             :seon.web/port-file      port-file}))))))

(defn start-agent-with-stub!
  "Bring up the V0 agent with the canned stub LLM. Useful for verifying
   the full loop without a deepseek API key. Returns a channel."
  []
  (start-agent!))

(defn start-agent-with-deepseek!
  "Bring up the V0 agent against the real deepseek API. Requires
   DEEPSEEK_API_KEY in process.env. Returns a channel."
  []
  (start-agent! {:llm-fn (openai/agent-adapter)}))

;; Wire the UI's "new agent" affordance (POST /agents/new) to the ONE
;; boot path. serve.cljs can't require this ns (cycle: client → serve),
;; so the closure is injected at load time — re-runs on hot reload, so
;; the endpoint always calls current code. `current-llm-fn` is resolved
;; per CALL, matching -main's auto-boot selection. `:mint? true` — this
;; is THE explicit create path; without it a second start-agent! would
;; just re-resume the already-armed roster instead of minting.
(web.serve/set-create-agent-fn!
  (fn [& [{purpose :seon.agent/purpose}]]
    (start-agent! (cond-> {:llm-fn (current-llm-fn) :mint? true}
                    (some? purpose) (assoc :purpose purpose)))))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

(defn- install-process-safety-net!
  "Belt-and-suspenders: Node 15+ defaults to terminating the process on
   an unhandled Promise rejection. Anything in the pod (core, agent
   eval, HTTP handlers) that throws inside an async chain and isn't
   caught upstream brings the whole pod down by default — a tiny
   core bug becomes a denial-of-service.

   This handler converts unhandled rejections into logged warnings and
   keeps the process alive. The pod stays a 'mostly survives, surfaces
   the error in logs' system rather than a 'one bad transact kills
   everything' system. Per the user's reliability directive — operations
   should return data, not exit codes.

   Individual call sites should still `.catch` and convert to data
   shapes (`{:ok? false :error ...}`) where it matters; this is the
   floor, not the ceiling."
  []
  (.on js/process "unhandledRejection"
       (fn [reason _promise]
         (log/error-console! "seon.client" "unhandled promise rejection"
                             (or reason "<no reason>"))))
  (.on js/process "uncaughtException"
       (fn [err _origin]
         (log/error-console! "seon.client" "uncaught exception"
                             (or (.-message err) err)))))

(defn -main [& _args]
  ;; FIRST: gate datahike-cljs/konserve trace+debug (per-index-node
  ;; `:datahike/index-access` traces flooded pod.log to 813 MB on one
  ;; cold-store inspector render, 2026-06-09). Must run before the
  ;; smoke test / start-agent! open any store.
  (log/quiet-library-logs!)
  (install-process-safety-net!)
  (log/info-console! "seon.client" "-main boot" {:boot-at (:boot-at @!state)})
  ;; Phase A item 7+8 — install Malli instrumentation. Collects every
  ;; seon.* fn with :malli/schema metadata, registers with
  ;; -function-schemas*, and wraps each var with input+output
  ;; validation (Sean's decision #7). Runs after all seon.* nses have
  ;; loaded (CLJS module-load is eager and happens before -main fires)
  ;; and before any agent eval that would call them.
  (try
    (let [stats (instrument/install!)]
      (log/info-console! "seon.client" "instrumentation installed" stats))
    (catch :default e
      (log/error-console! "seon.client" "instrumentation install failed"
                          {:msg (.-message e)})))
  (-> (datahike-smoke-test!)
      (.then (fn [result]
               (case (:status result)
                 :pass (log/info-console! "seon.client" "datahike-cljs smoke test PASS"
                                          {:datoms (:datoms result)})
                 :fail (log/error-console! "seon.client" "datahike-cljs smoke test FAIL"
                                           {:got (:got result) :expected (:expected result)}))))
      (.catch (fn [err]
                (log/error-console! "seon.client" "datahike-cljs smoke test THREW" err))))
  ;; A-5: auto-boot the V0 agent + HTTP server unless SEON_NO_AUTO_BOOT.
  ;; Cheap default for dev iteration — browser hits the loopback port,
  ;; no REPL needed. Disable when running the bare smoke test alone.
  (when-not (.. js/process -env -SEON_NO_AUTO_BOOT)
    (let [llm-fn   (current-llm-fn)
          provider (ai/provider)
          key-set? (case provider
                     :anthropic (boolean (.. js/process -env -ANTHROPIC_API_KEY))
                     (openai/api-key-configured?))]
      (log/info-console! "seon.client"
                         (if key-set?
                           (str "using " (name provider) " LLM (API key set)")
                           (str "using stub LLM (" (name provider)
                                " selected but its API key is unset)")))
      (-> (start-agent! {:llm-fn llm-fn})
          (.then (fn [{:seon.agent/keys [id ns]
                       :seon.client/keys [resumed-ids minted-ids]
                       :seon.web/keys [port port-file]}]
                   (log/info-console! "seon.client" "auto-boot ready"
                                      {:agent id :ns (str ns)
                                       :resumed resumed-ids
                                       :minted minted-ids
                                       :url (str "http://127.0.0.1:" port)
                                       :port-file port-file})))
          (.catch (fn [err]
                    ;; FAIL LOUD (2.2e): the pod is useless without its
                    ;; agent + cluster conn, and a half-up pod that looks
                    ;; healthy is worse than a dead one. Most common
                    ;; cause: wire-server down (the error says exactly
                    ;; that). No local-store fallback, by design.
                    (log/error-console! "seon.client"
                                        "auto-boot FAILED — exiting (no local fallback)"
                                        err)
                    (.exit js/process 1))))))
  (log/info-console! "seon.client" "nREPL :7889 — (shadow.cljs.devtools.api/nrepl-select :client)")
  (start-heartbeat!))
