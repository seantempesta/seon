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
    ;; Instrumentation. `instrument-from-db!` (called in start-agent! after
    ;; the core is indexed) reads the program graph to wrap every fn; the
    ;; eval path instruments newly-defined fns inline.
    [seon.instrument :as instrument]
    ;; Pull in the agent's required namespaces at compile time so all
    ;; schemas are registered before start-agent! runs.
    [seon.agent :as agent]
    ;; Lifecycle verbs (wait/complete/terminate) — host-bundled so the agent
    ;; home ns can `:refer` them; required here so the build includes the ns.
    [seon.agent.lifecycle]
    ;; The agent loop + wake trigger: the client boot path ARMS the wake
    ;; trigger (seon.agent does NOT, to stay acyclic).
    [seon.agent.loop :as agent-loop]
    ;; The run lifecycle — the bootstrap turn-0 opens a run for its turn.
    [seon.agent.run :as run]
    ;; Cron-as-data — required so its `:seon.agent.schedule/*` register! calls
    ;; run before `agent-bootstrap-attrs` installs them, and so the ticker's
    ;; `fire-due-schedules!` is in the build.
    [seon.agent.schedule]
    [seon.agent.ctx]
    [seon.ai :as ai]
    [seon.ai.tokens :as tokens]
    [seon.ai.anthropic :as anthropic]
    [seon.ai.openai-compat :as openai]
    [seon.ai.diffusiongemma :as diffusiongemma]
    [seon.db :as db]
    [seon.error :as error]
    [seon.eval :as seval]
    [seon.log :as log]
    ;; Render protocol — A-2. Required here so the build includes it.
    ;; Symbol-lookup for render slots lives in seon.eval/lookup-value
    ;; (walks goog-global with cljs.core/munge); no boot-time wire-up
    ;; needed.
    [seon.render]
    [seon.render.default]
    ;; Live-tile render namespace — required so the build includes it.
    [seon.render.live-tile]
    ;; Root's SYSTEM VIEW — the `/` dashboard = root's live-tile content.
    ;; Required so the build includes it and `system-view`'s symbol resolves
    ;; via eval/lookup-value when render-agent-tile renders root.
    [seon.render.system]
    ;; Routing-as-data — the `:seon.route/*` schema + the seeded core route
    ;; set; boot-seed! transacts (route/core-routes-tx). Required here so the
    ;; schema register! calls run and the build includes the seed builder.
    [seon.route :as route]
    ;; Iteration surface — owns the canonical `!compile-state`
    ;; defonce (in `seon.repl`). start-agent! reads through
    ;; `seon.repl/ensure-bootstrap!` rather than holding a second
    ;; copy here. See compile-state-lifecycle research note.
    [seon.repl :as repl]
    ;; One-node source extraction (`form-source-at`) for the program-graph
    ;; source capture below — rewrite-clj parses EXACTLY one top-level form,
    ;; so char/regex/string-literal parens are balanced correctly (a raw
    ;; depth counter truncates such a form). Same parser `parse-forms` uses.
    [seon.repl.internal :as repl-internal]
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
    ;; Operator dev tools (/data + /agent/<id>/debug) — installs its own
    ;; tx-listener that pushes morphs to those pages' SSE streams.
    [seon.web.debug]
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
    ;; Shared envelope shapes — the `:seon.result/ok?` discriminator and
    ;; the `:seon.items/*` self-describing-collection envelope. Required
    ;; here (before the my.* scaffold) so their register! calls run before
    ;; any consumer (`my.data` et al.) registers shapes that reference them.
    [seon.items]
    ;; The my.* scaffold — shared provenance shapes (my.kb) + the
    ;; system-wide instruction singleton (my.kb.shared) that
    ;; `seed-core!` below transacts. Required here so their
    ;; register! calls run before the boot install of :my.kb/* attrs.
    [my.kb]
    [my.kb.shared]
    ;; Loadable skills — the `:my.skills/*` schema + the corpus scanner
    ;; `boot-seed!` transacts (`my.skills/seed-skills-tx-data`) and the
    ;; catalog/skill-block render fns `seon.config/default-ctx-blocks` wires by symbol.
    ;; Required here so its register! calls run and the build includes it.
    [my.skills]
    ;; The live-tile/canvas TOOLKIT — the aggregation (`my.data`) + static
    ;; (`my.ui`) + interactive (`my.tile`) verbs the agent composes its
    ;; canvas from. Required here so they BUILD + INDEX at boot (their
    ;; `:seon.fn` rows render full in the `:namespaces` block — the worked
    ;; examples, not `(no public fns indexed yet)`). They reference the
    ;; `:seon.items/*` envelope required above.
    [my.data]
    [my.ui]
    [my.tile]
    ;; Content-addressed blob store — the disk tier (big text lives behind
    ;; a :my.blob/hash ref, never as datoms). Required so it builds +
    ;; indexes at boot and turn-capture/web-fetch can compose on it.
    [my.blob]
    ;; Inert foreign-code values — the `#code` heredoc literal's schemas
    ;; (`:seon.code/lang`/`::text`/`::block`). Required here so register!
    ;; runs before the reader/fs verbs hand these maps around.
    [seon.code]
    ;; Config-driven context — the OPTIONAL manifest (`config/system.edn`)
    ;; that shapes the agent-context, routes, and global render bounds.
    ;; Absent → byte-identical to a no-config boot. `boot-seed!` loads it
    ;; ONCE and threads it to the route + skill-corpus seed steps.
    [seon.config :as config]
    ;; Holistic declarative-state reconcile — `boot-seed!` routes its
    ;; desired-set steps (routes + skills) through `seon.state/reconcile!`
    ;; so a manifest that DROPS a route/skill retracts the stale datom.
    [seon.state :as state]
    ;; Local-machine capability surface — A-9. Required so the agent
    ;; can call (seon.agent.fs/read-file ...) + (seon.platform/host) from
    ;; bootstrap-CLJS eval.
    [seon.agent.fs]
    ;; Content search over allowed files — the exemplar npm-package
    ;; wrapper (@vscode/ripgrep). Required so the agent can call
    ;; (seon.agent.search/grep ...) from bootstrap-CLJS eval and so the
    ;; core-vars seed below can index it.
    [seon.agent.search]
    ;; Run real commands / Python — argv `execFile` over the fs cwd gate,
    ;; default-deny behind the SEON_SHELL host grant. Required so the
    ;; agent can call (seon.agent.shell/run ...) / (seon.agent.shell/py-run
    ;; ...) from bootstrap-CLJS eval and so the core seed indexes it.
    [seon.agent.shell]
    ;; Fetch + extract web pages — undici transport, readability→markdown,
    ;; blob-stored, SSRF-gated behind the SEON_WEB host grant. Required so
    ;; the agent can call (seon.agent.web/fetch ...) from bootstrap-CLJS
    ;; eval and so the core seed indexes it.
    [seon.agent.web]
    ;; Work items (user→agent asks + agent notes-to-self) — required so
    ;; its register! calls run before the boot install of :my.plan/*.
    [my.plan]
    ;; The per-block ctx namespaces (ctx-sections-split-2026-06-18):
    ;; each owns one block fn (+ html twin) that seon.config/default-ctx-blocks
    ;; wires by SYMBOL — required here so the build includes them and
    ;; their munged symbols resolve via seon.eval/lookup-value at
    ;; render time (no require cycle: the section nses require seon.agent.ctx
    ;; for the shared read API, seon.agent.ctx names them only as symbols).
    [seon.agent.ctx.namespaces :as nss]
    [seon.agent.ctx.live-tile]
    [seon.agent.ctx.warnings]
    [seon.agent.ctx.transcript]
    [seon.agent.ctx.inventory]
    [seon.agent.ctx.findings]
    [seon.agent.ctx.relevant]
    [seon.agent.ctx.jobs]
    [seon.agent.ctx.testrun]
    [seon.agent.ctx.subagents]
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
  ;; Compile-time enumeration of the build's PUBLIC fns — `core-vars`
  ;; below IS this macro's whole-closure roster: every public first-party
  ;; fn the build loads, specced or not (owner directive — 'just index
  ;; everything'; never hand-list vars).
  (:require-macros [seon.indexing :refer [public-fn-vars first-party-ns-strs]]))

;; ---------------------------------------------------------------------------
;; Process-lifetime state. `defonce` so reloads don't reset it.
;; ---------------------------------------------------------------------------

(defonce !state
  (atom {:boot-at      (.toISOString (js/Date.))
         :reload-count 0
         :heartbeat-id nil}))

;; C27: advertise this pod's CLUSTER to the MCP resolver. The probe
;; `(seon.dev.runtime-id/advertisement)` cluster-qualifies every hosted
;; id, so `agent_id "default/root"` pins THIS pod deterministically and a
;; bare "root" across several pods fails loud instead of mis-pinning.
;; Top level (not -main) so a hot reload arms an already-running pod;
;; idempotent. `cluster-name` is the ONE derivation (registry C15).
(runtime-id/cluster! store.wire/cluster-name)

(defn start-heartbeat!
  "Holds the Node event loop open with a minute-cadence heartbeat. The
   real V0 client will keep the loop alive via pending agent-loop work;
   for now this is the simplest 'process stays open' contract."
  {:malli/schema [:=> [:cat] :any]}
  []
  (let [id (js/setInterval
             (fn []
               (log/debug-console! "seon.client" "heartbeat"))
             60000)]
    (swap! !state assoc :heartbeat-id id)))

(defn stop-heartbeat!
  {:malli/schema [:=> [:cat] :any]}
  []
  (when-let [id (:heartbeat-id @!state)]
    (js/clearInterval id)
    (swap! !state assoc :heartbeat-id nil)))

(defn ^:dev/before-load before-reload
  {:malli/schema [:=> [:cat] :any]}
  []
  (log/info-console! "seon.client" "reloading…")
  (stop-heartbeat!))

(declare rearm-wake-triggers!)
(declare register-arm-hook!)

(defn ^:dev/after-load after-reload
  {:malli/schema [:=> [:cat] :any]}
  []
  (swap! !state update :reload-count inc)
  (log/info-console! "seon.client"
                     (str "reload #" (:reload-count @!state)
                          " — booted " (:boot-at @!state)))
  ;; Hot-reload hygiene: re-install the per-agent wake trigger so
  ;; tx-listener closures run the just-reloaded code.
  ;; Async fire-and-forget — logs the re-armed ids / errors.
  ;; (seon.web.debug re-arms its own ::debug listener via its
  ;; own ^:dev/after-load — not duplicated here.)
  (rearm-wake-triggers!)
  ;; Re-point the spawn ARM hook (#30) at the just-reloaded init-agent! so a
  ;; live pod arms freshly-spawned children with current code (boot registers
  ;; it once; a hot reload doesn't re-run boot).
  (register-arm-hook!)
  ;; Re-arm the ONE ticker so a hot reload doesn't stack timers and the tick
  ;; body runs just-reloaded code (idempotent — clears the prior interval).
  (agent-loop/install-ticker!)
  ;; Re-instrument from the program graph (C46): shadow just re-eval'd the
  ;; changed nses AND their dependents, replacing malli-wrapped vars with
  ;; fresh UNWRAPPED fns; without this, coverage stays degraded until the
  ;; next `start-agent!`. `instrument-from-db!` is idempotent
  ;; (`:skip-instrumented? true`; simple fns re-wrap from recorded
  ;; originals) — ~450ms, a dev-reload-only cost. Guarded: a reload can
  ;; only fire post-boot, but the conn check keeps a pre-boot edge safe.
  (when-let [conn db/*conn*]
    (let [stats (instrument/instrument-from-db! @conn)]
      (log/info-console! "seon.client"
                         (str "reload: re-instrumented " (pr-str stats)))))
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
  {:malli/schema [:=> [:cat] :any]}
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
  {:malli/schema [:function
                  [:=> [:cat] :any]
                  [:=> [:catn [::schema :any]] :any]]}
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
;; !agent-conn) and re-arms the wake trigger. Useful during dev hot-
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
  [;; --- Agent (state is DERIVED — no stored :seon.agent/state) ---
   ;; :seon.agent/current-ns deleted 2026-05-23 — derived from the
   ;; latest successful eval's :seon.eval/ns. See
   ;; docs/seon/concepts/reactive-context.
   :seon.agent/id
   ;; Subagent → parent ref (spawn tree). Boot-installed so the depth-cap walk
   ;; (`seon.agent/spawn-depth`) and the subagents/orphaned-agents sections can
   ;; QUERY it on a fresh world before any spawn has lazily installed it.
   :seon.agent/parent
   ;; Derived-state primitives: the CURRENT run pointer (fencing token +
   ;; spine of derived state) and the terminate marker. Plus the run-bound
   ;; seeds (the :seon.agent.run/default-turn-limit config datom, default 20)
   ;; and the self-managed cron vector. The agent's section overrides.
   :seon.agent/run
   :seon.agent/terminated-at
   :seon.agent/default-turn-limit
   :seon.agent/default-deadline-ms
   :seon.agent/schedules
   :seon.agent/ctx

   ;; --- Schedule (seon.agent.schedule — the cron maps an agent owns via
   ;; :seon.agent/schedules; the ticker's fire-due-schedules! reads these) ---
   :seon.agent.schedule/id
   :seon.agent.schedule/cron
   :seon.agent.schedule/fn
   :seon.agent.schedule/timezone
   :seon.agent.schedule/concurrency-policy

   ;; --- Render slots (A-6) — symbol-only at storage. ---
   :seon.render/ai
   :seon.render/html

   ;; --- Ctx section entities (seon.agent.ctx) — the one slot attr is
   ;; :seon.render/ai (above), string-or-symbol via the bridge's
   ;; EDN-string encoding. ---
   :seon.agent.ctx/name
   :seon.agent.ctx/priority
   ;; per-agent live-DB render override (cardinality-many ns-name keywords) —
   ;; the namespaces section unions these onto the curated full set at render
   ;; time (seon.agent.ctx.namespaces/db-render-set). Boot-installed so an
   ;; agent can pin/unpin a ns into its always-on view by transact/retract.
   :seon.agent.ctx/render-namespaces

   ;; --- Run (seon.agent.run — the wake-episode grouping; turns point UP
   ;; to it via :seon.agent.turn/run, it points UP to the agent) ---
   :seon.agent.run/id
   :seon.agent.run/agent
   :seon.agent.run/started-at
   :seon.agent.run/trigger
   :seon.agent.run/cause
   :seon.agent.run/turn-limit
   :seon.agent.run/deadline
   :seon.agent.run/last-beat-at
   :seon.agent.run/paused-at
   :seon.agent.run/remaining-ms
   :seon.agent.run/status
   :seon.agent.run/closed-reason
   ;; Durable return value (Piece 1) + the close instant the Piece 2d breaker
   ;; windows over (`:db/txInstant` can't be backdated) — boot-installed so the
   ;; subagents section + breaker query them on a fresh world.
   :seon.agent.run/result
   :seon.agent.run/result-ref
   :seon.agent.run/closed-at

   ;; --- Turn (a standalone entity that points UP to its run) ---
   :seon.agent.turn/id
   :seon.agent.turn/at
   :seon.agent.turn/status
   ;; The full prompt lives in the blob store via the turn's
   ;; :seon.agent.turn/prompt-blob ref (three-tier storage); the datom is
   ;; the char-count projection (display converts to tokens).
   ;; :seon.agent.turn/prompt-text RETIRED 2026-06-09 (silently truncated —
   ;; useless evidence); :seon.agent.turn/prompt-file RETIRED 2026-07-02
   ;; with the seon.debug file tree (C17 — blob capture subsumes it).
   :seon.agent.turn/prompt-chars
   ;; The run this turn belongs to (its derived current-turn = count turns
   ;; with this run).
   :seon.agent.turn/run
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

   ;; --- Plan (the per-agent planning graph — user→agent asks + the agent's
   ;; --- own steps; installed at boot so a RESUMING agent can list-open and
   ;; --- the plan block can pull before any step tx has lazily installed
   ;; --- the attrs) ---
   :my.plan/id
   :my.plan/title
   :my.plan/description
   :my.plan/status
   :my.plan/created-at
   :my.plan/completed-at
   :my.plan/agent
   :my.plan/from
   ;; Back-ref to the inbound :human message an address-step tracks
   ;; (auto-minted in message!; the render half links id+age+title).
   :my.plan/message
   ;; The plan TREE edge (parent, plain ref) + the dependency DAG edges
   ;; (needs, plain cardinality-many ref). Boot-installed so the derived
   ;; work-queue rules (next/blocked/ready) query them before any tx has
   ;; lazily installed them on a fresh world.
   :my.plan/parent
   :my.plan/needs
   ;; The goal/expect/pace narrative attrs — boot-installed so the windowed
   ;; plan render can read them on any node from turn one.
   :my.plan/goal
   :my.plan/expect
   :my.plan/pace

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
   ;; before any kb tx lands, plus the my.kb.shared system-wide
   ;; instruction singleton (empty entity seeded by seed-core!;
   ;; rows are appended at runtime by agents and the user).
   :my.kb/source-path
   :my.kb/source-line
   :my.kb/verified-at
   :my.kb/confidence
   :my.kb.shared/id
   :my.kb.shared/instructions
   :my.kb.shared/text
   :my.kb.shared/at
   ;; (No identity attrs — the agent's identity is read LIVE from
   ;; SOUL.md / AGENTS.md every turn as file-sections, never stored. See
   ;; seon.agent.ctx/file-block.)

   ;; --- Test (Phase 2 — test capture as data) ---
   :seon.test/sym
   :seon.test/last-passed-at
   :seon.test/last-failed-at
   :seon.test/last-failure-summary
   :seon.test/last-run-id
   ;; Phase 4 (mvp-completion-plan 2026-05-27)
   :seon.test/source
   :seon.test/ns
   :seon.test/created-at

   ;; --- Testrun (seon.agent.testrun — parsed pytest results projected as
   ;; data; the :test-failures section reads the LATEST run per agent).
   ;; Boot-installed so the section can pull on turn one. ::failures is the
   ;; cardinality-many component ref onto failure entities. ---
   :seon.agent.testrun/framework
   :seon.agent.testrun/passed
   :seon.agent.testrun/failed
   :seon.agent.testrun/errors
   :seon.agent.testrun/agent
   :seon.agent.testrun/failures
   :seon.agent.testrun/test-name
   :seon.agent.testrun/path
   :seon.agent.testrun/line
   :seon.agent.testrun/message])

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
  {:malli/schema [:=> [:cat] :any]}
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
     2. `ensure-cluster-db!` — register/create this cluster's db on the
        wire-server (idempotent), so a freshly created cluster's store
        exists before the peer attaches.
     3. `d/connect` — reads go local from here; writes dispatch to the
        `:seon-wire` writer (db-name-routed to this cluster).
     4. schema transact — the full Malli-derived attr schema goes OVER
        THE WIRE to the JVM writer; idempotent `:db/ident` upserts, so
        re-booting against the populated store re-asserts no-ops.
     5. listen adapter — foreign writers' txs fire this conn's native
        listeners (wake triggers + inspector SSE)."
  {:malli/schema [:=> [:cat] :any]}
  []
  (await (store.wire/ping!))
  (await (store.wire/ensure-cluster-db!))
  (let [conn (await (d/connect (store.wire/cluster-config)))]
    (log/info-console! "seon.client/open-cluster-conn!"
                       (str "cluster " store.wire/cluster-name
                            ": " store.wire/default-store-path
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
;;   - `topo-sort-nses` over the STORED `:seon.ns/require-edges`
;;     (targets intersected with the agent-ns-set — intra-agent edges
;;     only; core deps load on-demand via the DB load-fn). A dep loads
;;     before its dependent.
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

;; Forward decls: the SEON_EXTRA_SRC scanning helpers are defined far below
;; (alongside `config/extra-src`, after the form/arglist parsers they reuse), but
;; `core-ns-set`/`ns-row`/`index-core!` reference them. Fn-body references
;; resolve at call time; the declare silences the compile-time :undeclared-var
;; warning.
(declare extra-src-ns-strs extra-fn-rows extra-src-ns->file)

(defn ^:private agent-ns-set
  "The set of agent-authored namespace keywords in the DB layer — every
   `:seon.ns/name` row whose ns is NOT in `(core-ns-set)`. Core nses are
   COMPILED (present in the bundle, indexed for DISPLAY only); only the
   agent-authored DB layer is LOADED on boot. Read against the db value."
  {:malli/schema [:=> [:catn [::db :any]] :any]}
  [db]
  (let [all (into #{}
                  (map first)
                  (db/query '[:find ?n :where [?e :seon.ns/name ?n]] db))]
    (set/difference all (core-ns-set))))

(defn ^:private agent-ns-requires
  "Map of `agent-ns-kw → #{intra-agent require ns-kws}` for topo-sort.
   Derives each ns's required set from the STORED
   `:seon.ns/require-edges` (captured at tee from the analyzer, NOT
   re-parsed here — `seon.eval/stored-require-targets`), INTERSECTED
   with `agent-nses` so only intra-agent edges order the load kick.
   Core/third-party deps are NOT edges here — they are satisfied
   on-demand by the compiled bundle via the DB load-fn
   (`seon.eval/guarded-load`) DURING each ns's eval. An agent ns with no
   stored edges (or only core deps) has an empty edge set."
  {:malli/schema [:=> [:catn [::db :any] [::agent-nses :any]] :any]}
  [db agent-nses]
  (into {}
        (map (fn [ns-kw]
               [ns-kw (set/intersection
                        (seval/stored-require-targets db ns-kw)
                        agent-nses)]))
        agent-nses))

(defn ^:private topo-sort-nses
  "Dependency-ordered vector of the keys of `edges` (a `ns → #{dep-ns}`
   map) — a dep comes before its dependent (DFS post-order).
   Deterministic (sorted within each level). A require cycle is broken by
   the `visiting` guard (a back-edge to a node already on the DFS stack
   is skipped) so this always terminates; cljs.js then detects the actual
   circular dep and errors that ns during its per-ns eval (user
   directive: broken input just errors that ns and moves on)."
  {:malli/schema [:=> [:catn [::edges :any]] :any]}
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
  {:malli/schema [:=> [:catn [::db :any]] :any]}
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
  {:malli/schema [:=> [:catn [::agent-id :any] [::ns-kw :any] [::err-map :any]] :any]}
  [agent-id ns-kw {:seon.error/keys [message stack]}]
  (await
    (log/warn! {:seon.log/source  ::log-replay-failure!
                :seon.log/agent   agent-id
                :seon.log/message (str "load of ns " (pr-str ns-kw)
                                       " failed: " message)
                :seon.log/stack   (or stack "")})))

(defn ^:private load-error->log
  "Normalize a load failure `err` (a `seon.error/->map` from
   `seon.eval/eval`'s `{:seon.eval/ok? false :seon/error …}`, or a raw
   caught JS error)
   into the `{:seon.error/message <string> :seon.error/stack <string>}`
   shape `log-replay-failure!` expects (the :seon/error vocabulary — a
   projection, not a full `->map`). `:seon.error/message` carries the
   full cause-chain message ([[error-chain-message]]);
   `:seon.error/stack` the deepest cause's stack
   — chosen so a load-failure warn names the actual defect, not cljs.js's
   \"ERROR\" wrapper."
  {:malli/schema [:=> [:catn [::err :any]] :any]}
  [err]
  {:seon.error/message (or (some-> err error-chain-message not-empty)
                           (some-> err .-message)
                           (str err))
   :seon.error/stack   (or (some-> err error-chain-stack)
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
     2. `topo-sort-nses` over the STORED `:seon.ns/require-edges`
        targets intersected with the agent-ns-set (intra-agent edges
        only — core deps load on-demand via the load-fn). A dep loads
        before its dependent.
     3. For each ns in topo order, `(seval/eval compile-state
        (seval/reconstitute-ns-source db ns-kw)
        {:seon.eval/starting-ns 'cljs.user})`. The
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
  {:malli/schema [:=> [:catn [::args [:map [::conn :any]
                                            [::compile-state :any]
                                            [::agent-id :string]]]]
                  :any]}
  [{::keys [conn compile-state agent-id]}]
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
                      (await (seval/eval compile-state src
                                         {:seon.eval/starting-ns 'cljs.user})))
                    (catch :default e
                      ;; bulk-load replay machinery (reconstitute-ns-source +
                      ;; eval) throwing is NOT the normal broken-agent-ns path
                      ;; (seval/eval returns ok?=false, never throws) — a throw
                      ;; here is OUR machinery (:core); the row still degrades
                      ;; to ok?=false and log-replay-failure! runs below.
                      (when-not (error/recorded? e)
                        (error/record! {:seon.error/raw e :seon.error/fault :core}))
                      {:seon.eval/ok? false :seon/error e}))]
            (when-not (:seon.eval/ok? r)
              (vswap! !n-fail inc)
              ;; Best-effort log; swallow log-write failure (a
              ;; double-fault must not abort the rest of the load).
              (try
                (await (log-replay-failure!
                         agent-id ns-kw (load-error->log (:seon/error r))))
                (catch :default e
                  ;; double-fault: OUR replay-failure LOG write itself
                  ;; throwing is a core defect (:core); the load continues
                  ;; (a double-fault must not abort the rest of the replay).
                  (error/record! {:seon.error/raw e :seon.error/fault :core}))))))
        ;; Standalone (ns-less) entity-schema rows — fully-qualified
        ;; register! calls evaled from cljs.user.
        (doseq [src standalone]
          (let [r (try (await (seval/eval compile-state src
                                    {:seon.eval/starting-ns 'cljs.user}))
                       ;; bulk-load machinery throwing (not the normal
                       ;; ok?=false path) is OUR defect (:core); the row still
                       ;; degrades and log-replay-failure! runs below.
                       (catch :default e
                         (when-not (error/recorded? e)
                           (error/record! {:seon.error/raw e :seon.error/fault :core}))
                         {:seon.eval/ok? false :seon/error e}))]
            (when-not (:seon.eval/ok? r)
              (vswap! !n-fail inc)
              (try
                (await (log-replay-failure!
                         agent-id :standalone-schema (load-error->log (:seon/error r))))
                (catch :default e
                  ;; double-fault: OUR log write itself throwing is a core
                  ;; defect (:core); the load continues.
                  (error/record! {:seon.error/raw e :seon.error/fault :core}))))))
        (let [total (+ (count order) (count standalone))]
          {:seon.client/replay-n-total total
           :seon.client/replay-n-ok    (- total @!n-fail)
           :seon.client/replay-n-fail  @!n-fail})))))

;; ---------------------------------------------------------------------------
;; Core boot seed (P2, 2026-05-27)
;;
;; Per docs/prds/agent-runtime/mvp-completion-plan-2026-05-27.md §Phase 2
;; + research/repl-session-context-template-2026-05-26.md §5: the core
;; transacts the user entity + the my.kb.shared instruction singleton plus an
;; introspection-indexed set of core fns at boot, BEFORE any agent turn —
;; so replay-from-tx-0 starts on a fully-seeded core, not mid-air.
;;
;; Tx-ordering at boot (in start-agent!):
;;   1. Entity-schema decomposition (schema/all-entity-schemas-tx-data)
;;      — already shipped, Item 4 commit 35035d8.
;;   2. seed-core!    — user entity + my.kb.shared singleton
;;   3. index-core!   — :seon.ns + :seon.fn rows from REAL runtime
;;                           introspection (var meta + source file-read)
;;
;; Each transact carries `:seon.db/origin :core-seed` in tx-meta so
;; audit queries can isolate seed datoms from agent-produced ones.
;; ---------------------------------------------------------------------------

(defn seed-core!
  "Tx-data for THE user entity plus the EMPTY system-wide instruction
   singleton (`my.kb.shared/seed-tx-data`); agents and the user APPEND
   rows at runtime, read back via `(my.kb.shared/instructions)` in the
   bootstrap turn.

   The user row is the ONE `:seon.user/id` entity every
   `:seon.agent.message/from`/`to` user-ref resolves to (identity upsert,
   idempotent — same pattern as agent entities; one human for now).
   The instruction singleton identity-upserts on `:my.kb.shared/id`
   carrying NO rows — re-running asserts zero new datoms and never
   clobbers runtime appends.

   Pure fn. Caller transacts via `db/transact!` with
   `:seon.db/origin :core-seed`."
  {:malli/schema [:=> [:cat] :any]}
  []
  (into [{:seon.user/id "user"}]
        (my.kb.shared/seed-tx-data)))

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
;; The agent extends the indexed surface by transacting its own fns; the core
;; surface auto-widens — `core-vars` is the `public-fn-vars` macro roster, so
;; a new public first-party defn is seeded the moment the build loads it.
;; ---------------------------------------------------------------------------

(def ^:private core-vars
  "Every var indexed into the corpus at boot: the compile-time roster of
   EVERY public first-party fn across the build's whole require closure
   (`seon.indexing/public-fn-vars` — owner directive 'just index
   everything': all functions in the cljs package become `:seon.fn` rows,
   specced or not). No hand-curated inclusion list — the macro IS the
   roster; a new public fn is indexed the moment it loads. Each var's
   spec/doc/source is read by [[var->fn-row]]; an unspecced fn simply omits
   `:seon.fn/spec` (honestly unspecced). Macro output is already
   sym-unique, so no dedup pass is needed."
  (public-fn-vars))

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
;;                    (seon.indexing/public-fn-vars)))
;;
;; The boot indexers consume it alongside `core-vars`: fn-rows +
;; FULL-SOURCE ns-rows in [[index-core!]], replay-skip membership in
;; [[core-ns-set]]. Empty in builds without a downstream preload.
(defonce !extra-core-vars (atom []))

(defn- extra-core-vars*
  "The registered extra vars MINUS any whose fully-qualified sym is
   already in `core-vars` — a downstream entry's `public-fn-vars`
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

(def ^:private compiled-first-party-ns-strs
  "BUILD-DERIVED set of every first-party ns name string compiled into
   this bundle (`seon.indexing/first-party-ns-strs` over this ns's
   compile-time require closure). The computed replacement for the
   hand-maintained `fn-less-compiled-roots #{\"my.kb\"}` exception: a
   compiled ns joins [[core-ns-set]] (replay-skip: re-evaling its
   shipped source would shadow compiled fns / re-run register! forms)
   and gets an [[index-core!]] ns-row BY CONSTRUCTION — whether or not
   any fn-row names it (a register!-only root has no indexed var but is
   still compiled, and its name-set membership is now a build fact, not
   a literal)."
  (into #{} (first-party-ns-strs)))

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
   NOT tx-meta and NOT a hand-typed ns list. Three computed sources
   union: [[compiled-first-party-ns-strs]] (the BUILD-DERIVED closure —
   covers every compiled ns including fn-less register!-only roots),
   the runtime-scanned extra-src nses, and the live var-meta `:ns` of
   the indexed vars (covers test/preload/extra vars whose nses sit
   OUTSIDE this ns's compile-time require closure)."
  {:malli/schema [:=> [:cat] :any]}
  []
  (into (into (into #{} (map keyword) compiled-first-party-ns-strs)
              ;; Whole-downstream-surface (SEON_EXTRA_SRC): every scanned
              ;; downstream ns is COMPILED-into-the-bundle core (display-only,
              ;; replay-skip) — including an unspecced-only ns (`acme.notes`)
              ;; that owns no registered var. Without this its full-source
              ;; `:seon.ns` row would be mistaken for agent-authored and
              ;; re-eval'd on load.
              (map keyword) (extra-src-ns-strs))
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
        extra (when-let [v (config/extra-src)]
                [(str v "/src") (str v "/test")])]
    (some (fn [root]
            (try
              (.readFileSync fs (str root "/" file) "utf8")
              ;; probe: `some` over candidate roots — a miss at one root is
              ;; the EXPECTED signal to try the next (the file lives under
              ;; exactly one); nil drives the fallthrough, not a defect.
              (catch :default _ nil)))
          (concat (map seon.platform/artifact-path ["src" "test" "guest-cljs/src"])
                  extra))))

(defn- extract-form-at-line
  "Return the exact text of the top-level form beginning at `line-1based` in
   `txt`. Delegates to `seon.repl.internal/form-source-at` — rewrite-clj's
   one-node parse, so char/regex/string-literal parens are balanced
   correctly (a `)` inside `\\)` or `#\"…)…\"` no longer truncates the form).
   Any leading indentation on the target line is dropped (a `defn` nested in
   a `#?(:cljs …)` reader conditional still yields source starting at
   `(defn`)."
  [txt line-1based]
  (let [lines (vec (str/split-lines txt))
        idx   (dec line-1based)]
    (when (and (nat-int? idx) (< idx (count lines)))
      (repl-internal/form-source-at (str/join "\n" (subvec lines idx)) 0))))

(defn- extract-form-at-index
  "Return the exact text of the top-level form beginning at char `idx` in
   `txt` (`idx` must point AT a `(`). Delegates to
   `seon.repl.internal/form-source-at` (see [[extract-form-at-line]])."
  [txt idx]
  (when (and (nat-int? idx) (< idx (count txt)))
    (repl-internal/form-source-at txt idx)))

(defn- ghost-var?
  "True when var-meta `txt`/`line` is the signature of a GHOST var: the file
   is readable but `:line` points past its end (or is non-positive). That
   happens when a `defn` is deleted from a hot-reloaded ns — shadow recompiles
   the file but leaves the old var (carrying its stale `:line`) bound in the
   running namespace object, so the compile-time roster keeps enumerating it.
   Distinct from a genuinely-unreadable file (`txt` nil), which is a real
   error. A ghost is pruned from the boot roster with a single :warn."
  [txt line]
  (and (some? txt)
       (or (not (pos-int? line))
           (>= (dec line) (count (str/split-lines txt))))))

(defn- ns-file-paths
  "Classpath-relative source file CANDIDATES for a namespace name string,
   most-specific extension first — `seon.agent.search` →
   [\"seon/agent/search.cljs\" \"seon/agent/search.cljc\"],
   `seon.agent.search-test` → [\"seon/agent/search_test.cljs\" …] (munged
   like the compiler: dots → dirs, dashes → underscores). Both `.cljs` and
   `.cljc` are probed so portable `.cljc` nses (e.g. `seon.schema`) resolve
   to their REAL source, not the stub. `read-src-file` probes the source
   roots (src, test, guest-cljs/src) per candidate, so test siblings and
   `.cljc` nses both resolve."
  [ns-sym-str]
  (let [base (-> ns-sym-str
                 (str/replace "." "/")
                 (str/replace "-" "_"))]
    [(str base ".cljs") (str base ".cljc")]))

(defn- read-ns-source
  "Read the REAL full source for a full-source ns name string, probing the
   `.cljs` then `.cljc` candidate ([[ns-file-paths]]). Returns the file
   text or nil if no candidate is readable under any source root."
  [ns-sym-str]
  (some read-src-file (ns-file-paths ns-sym-str)))

(defn- ns-row
  "Build the `:seon.ns` row for an owning ns name string.

   FULL-SOURCE nses (`seon.agent.ctx.namespaces/full-source-ns?` — all `my.*`, test
   siblings included) carry the REAL FULL FILE TEXT as
   `:seon.ns/source`: the boot indexer is the ONE file-reader; the
   `:namespaces` context section (and anything else downstream) renders
   that attr from the graph, never re-reading files. Safe because core
   rows are NOT loaded ([[agent-ns-set]] excludes any ns in `(core-ns-set)`
   from the DB-layer load). A
   full-source ns whose file can't be read falls back to the stub and
   logs fail-loud — the corpus stays honest.

   All OTHER core nses keep the minimal `(ns x)` stub — the
   `:namespaces` section no longer renders these (only the curated full
   set is shown); the stub keeps the `:seon.ns/name` row + lookup-ref
   target for indexed members and the on-demand `render-namespace` path,
   and keeps the no-replay invariant trivially cheap to reason about."
  [ns-sym-str]
  (let [stub  (str "(ns " ns-sym-str ")")
        ;; Extra-core nses (downstream SEON_EXTRA_SRC code) are
        ;; full-source by rule, like my.* — closes the render-as-stubs
        ;; gap for the extra root.
        full? (or (nss/full-source-ns? ns-sym-str)
                  (contains? (extra-core-ns-strs) ns-sym-str)
                  (contains? (extra-src-ns-strs) ns-sym-str))
        src   (if full?
                (or (read-ns-source ns-sym-str)
                    (do (log/error-console!
                          "seon.client/ns-row"
                          (str "full-source ns " ns-sym-str " source file "
                               (pr-str (ns-file-paths ns-sym-str))
                               " unreadable — falling back to the (ns x) stub"))
                        stub))
                stub)
        ;; Reified require edges (M4 structural store) for the SCI-
        ;; renderable surface: full-source nses are exactly where an
        ;; agent-authored-sym render fn can live (my.* + downstream), so
        ;; their alias/refer facts must be datoms, not text. Extracted
        ;; ONCE here at INDEX time from the real file's (ns …) form —
        ;; write-time extraction, never a render-time re-parse. Stub
        ;; nses (compiled seon.* — never SCI-rendered) skip the edges.
        edges (when full? (seval/require-edges-from-source src))]
    (cond-> {:seon.ns/name   (keyword ns-sym-str)
             :seon.ns/source src}
      (seq edges) (assoc :seon.ns/require-edges (vec edges)))))

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
                  ;; probe: an m/schema throw here is the KNOWN, accepted
                  ;; degradation the docstring above names — a core var whose
                  ;; `:malli/schema` embeds a fn ref (needs sci, unbundled).
                  ;; It ALREADY becomes data (a loud, actionable :warn + the
                  ;; row persists without :seon.fn/spec); gating boot on it
                  ;; would red the pod for an expected-and-surfaced condition.
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
    (cond
      (nil? src)
      (do (if (ghost-var? txt (:line m))
            (log/warn!
              {:seon.log/source  ::var->fn-row
               :seon.log/message
               (str "ghost var " sym " — `:line` " (:line m) " points past "
                    "file " (pr-str (:file m)) " (a defn deleted from a "
                    "hot-reloaded ns; shadow left the stale var bound). "
                    "Pruned from the roster.")})
            (log/error-console!
              "seon.client/var->fn-row"
              (str "could not read real source for " sym
                   " (file " (pr-str (:file m)) " line " (:line m) ") — OMITTING")))
          nil)

      ;; Not a plain `(defn …)`: a `defrecord`/`deftype`-generated factory
      ;; fn (`->X` / `map->X`) or other synthesized fn-var whose `:line`
      ;; points at the type form, not a hand-written function. OMIT — the
      ;; corpus stays "real defns only" (the no-stub-source-anywhere
      ;; invariant). Expected (every record yields factories), so silent.
      (not (str/starts-with? src "(defn"))
      nil

      :else
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


;; --- WHOLE-DOWNSTREAM-SURFACE INDEXING (SEON_EXTRA_SRC) ---------------------
;;
;; The var-derived `:seon.fn` rows above come from the SPECCED vars a consumer
;; registered into `!extra-core-vars`. That leaves two gaps for a third party's
;; OWN code: (a) an UNSPECCED public fn gets no `:seon.fn` row (so the
;; namespace renderer — which lists member rows — never shows it), and
;; (b) a downstream ns owning ZERO specced fns gets no `:seon.ns` row at all
;; (silently invisible to context + retrieval). A third party wants its WHOLE
;; surface readable by its agents, not just the specced slice. So when
;; SEON_EXTRA_SRC is set we scan that root's `*.cljs` files, index every ns
;; (full-source row) and every public `(defn …)`/`(defn- …)` (a `:seon.fn`
;; row, specced AND unspecced). Scoped to the extra root ONLY — seon's own
;; core stays slim (var-derived, specced-only). The reserved-prefix guard
;; (`seon.*`/`my.*`) still applies via the registered-var path; scanned nses
;; that hit a reserved prefix are dropped here (a downstream root never owns
;; them, and a stray match must not forge a core row).

(defn- list-cljs-files
  "Recursively collect `*.cljs` file paths under `root` (a directory). Returns
   a vector of absolute-ish paths (root-prefixed). `[]` when `root` is missing
   or unreadable — never throws."
  [root]
  (let [fs   (js/require "fs")
        path (js/require "path")]
    (letfn [(walk [dir acc]
              (let [ents (try (.readdirSync fs dir #js {:withFileTypes true})
                              ;; probe: a missing / unreadable dir is expected
                              ;; (SEON_EXTRA_SRC may be unset or partial) — an
                              ;; empty listing is the answer, not a defect.
                              (catch :default _ #js []))]
                (reduce
                  (fn [a ent]
                    (let [full (.join path dir (.-name ent))]
                      (cond
                        (.isDirectory ent) (walk full a)
                        (and (.isFile ent)
                             (str/ends-with? (.-name ent) ".cljs")) (conj a full)
                        :else a)))
                  acc
                  (array-seq ents))))]
      (walk root []))))

(defn- ns-name-from-source
  "The ns NAME string parsed from a `.cljs` file's `(ns <name> …)` form, or
   nil. Reader-free: skips leading whitespace/`;`-comments, requires the first
   form to open `(ns `, then reads the symbol token. Robust to a shebang-free
   leading docstring-bearing ns."
  [txt]
  (when (string? txt)
    (when-let [m (re-find #"\(\s*ns\s+([A-Za-z0-9.*+!_?<>=$%&-]+)" txt)]
      (second m))))

(defn- sym-char?
  "True when `c` may appear inside a CLJS symbol token (a defn name). Excludes
   whitespace, delimiters, `^` (metadata marker), `\"`, `;`, `@`, `'`."
  [c]
  (not (or (= c \space) (= c \newline) (= c \tab) (= c \return) (= c \,)
           (= c \() (= c \)) (= c \[) (= c \]) (= c \{) (= c \})
           (= c \") (= c \^) (= c \;) (= c \@) (= c \'))))

(defn- dnd-skip-ws
  "Index of the first non-whitespace, non-`;`-comment char in `form` at/after
   `i` (`n` = (count form))."
  [form n i]
  (loop [i i]
    (cond
      (>= i n) i
      (let [c (nth form i)] (or (= c \space) (= c \newline) (= c \tab)
                                (= c \return) (= c \,))) (recur (inc i))
      (= \; (nth form i)) (recur (loop [j i] (if (or (>= j n) (= (nth form j) \newline)) j (recur (inc j)))))
      :else i)))

(defn- dnd-read-token
  "Index just past the symbol token starting at `i` in `form`."
  [form n i]
  (loop [j i] (if (or (>= j n) (not (sym-char? (nth form j)))) j (recur (inc j)))))

(defn- dnd-skip-meta
  "Index just past ONE leading metadata token (`^:kw`, `^Type`, or `^{…}`) at
   `i`, or `i` unchanged when `i` is not a `^`."
  [form n i]
  (if (and (< i n) (= \^ (nth form i)))
    (if (and (< (inc i) n) (= \{ (nth form (inc i))))
      (loop [j (+ i 2) d 1]
        (cond (>= j n) j
              (= \{ (nth form j)) (recur (inc j) (inc d))
              (= \} (nth form j)) (if (= d 1) (inc j) (recur (inc j) (dec d)))
              :else (recur (inc j) d)))
      (dnd-read-token form n (inc i)))
    i))

(defn- defn-name+doc
  "Parse `{:name <string-or-nil> :doc <string-or-nil>}` from a `(defn …)` /
   `(defn- …)` form text. Reader-free token scan: consume `(`, the
   `defn`/`defn-` head, SKIP any leading metadata (`^:async`, `^{…}`), read the
   NAME symbol token, then capture an immediately-following docstring (the
   `\"…\"` before the arg-vector). Robust to multi-meta and to no docstring."
  [form]
  (let [n  (count form)
        i  (dnd-skip-ws form n 1)                 ; past '('
        i  (dnd-read-token form n i)              ; past defn / defn-
        i  (loop [i (dnd-skip-ws form n i)]        ; skip every leading meta
             (let [i' (dnd-skip-meta form n i)]
               (if (= i' i) i (recur (dnd-skip-ws form n i')))))
        ne (dnd-read-token form n i)
        nm (when (> ne i) (subs form i ne))
        i2 (dnd-skip-ws form n ne)
        doc (when (and (< i2 n) (= \" (nth form i2)))
              (loop [j (inc i2) esc? false sb ""]
                (cond (>= j n) sb
                      esc? (recur (inc j) false (str sb (nth form j)))
                      (= \\ (nth form j)) (recur (inc j) true sb)
                      (= \" (nth form j)) sb
                      :else (recur (inc j) false (str sb (nth form j))))))]
    {:name nm :doc doc}))

(defn- defn-rows-from-source
  "Build a `:seon.fn` row for EVERY top-level `(defn …)`/`(defn- …)` in
   `txt` (the full file text of `ns-sym-str`). Reader-free, paren-balanced
   (reusing [[extract-form-at-line]] semantics inline): finds each top-level
   form that opens with `(defn`/`(defn-`, grabs its exact text, and reads the
   fn name + docstring + privacy off the source. SPECCED fns (those whose
   source carries `:malli/schema`) and UNSPECCED ones alike get a row — the
   point is the WHOLE downstream surface, so `:seon.fn/spec` is simply omitted
   when no schema literal is present (mirrors [[var->fn-row]]'s present⇒specced
   / absent⇒unspecced convention).

   `:seon.fn/spec` is NOT extracted here (the registered-var path owns the
   pure-data Malli form via `m/schema`/`m/form`; a downstream specced fn ALSO
   has a var-derived row, which dedups in front of this one in [[index-core!]]
   and carries the real spec). Returns a vector of rows (possibly empty)."
  [ns-sym-str txt now]
  (let [n     (count txt)
        ns-kw (keyword ns-sym-str)]
    (loop [i 0 rows []]
      (if (>= i n)
        rows
        ;; Only top-level forms (column 0 paren) are candidates. Find the next
        ;; '(' that begins a top-level form: track string/comment so a '(' in a
        ;; docstring or comment is skipped.
        (let [c (nth txt i)]
          (cond
            (= c \;) (let [eol (loop [j i] (if (or (>= j n) (= (nth txt j) \newline)) j (recur (inc j))))]
                       (recur eol rows))
            (= c \") (let [send (loop [j (inc i) esc? false]
                                  (cond (>= j n) j
                                        esc?     (recur (inc j) false)
                                        (= (nth txt j) \\) (recur (inc j) true)
                                        (= (nth txt j) \") (inc j)
                                        :else    (recur (inc j) false)))]
                       (recur send rows))
            (= c \()
            (let [form (extract-form-at-index txt i)]
              (if (and form
                       (re-find #"^\(\s*defn-?[\s\(]" form))
                (let [{:keys [name doc]} (defn-name+doc form)
                      nm   name
                      priv (boolean (re-find #"^\(\s*defn-[\s\(]" form))
                      row  (when (and nm (not (str/blank? nm)))
                             {:seon.fn/sym        (str ns-sym-str "/" nm)
                              :seon.fn/ns         [:seon.ns/name ns-kw]
                              :seon.fn/source     form
                              :seon.fn/fn-var?    true
                              :seon.fn/arglists   (expand-local-auto-kws
                                                    (arglists-from-source form)
                                                    ns-sym-str)
                              :seon.fn/doc        (or doc "")
                              :seon.fn/private?   priv
                              :seon.fn/created-at now})]
                  (recur (+ i (count form)) (if row (conj rows row) rows)))
                ;; Not a defn — skip the whole balanced form.
                (recur (+ i (if form (count form) 1)) rows)))
            :else (recur (inc i) rows)))))))

(defn- extra-src-ns->file
  "Map of `{ns-name-string file-path}` for every `*.cljs` under the
   SEON_EXTRA_SRC `/src` + `/test` roots whose `(ns …)` parses AND whose ns is
   NOT reserved (`seon.*`/`my.*` — a downstream root never owns those, and a
   stray match must not forge a core row). `{}` when SEON_EXTRA_SRC is unset.
   THIS is the authoritative downstream ns set — independent of which fns are
   specced, so an unspecced-only ns (`acme.notes`) is included."
  []
  (if-let [root (config/extra-src)]
    (let [fs    (js/require "fs")
          files (mapcat list-cljs-files [(str root "/src") (str root "/test")])]
      (reduce
        (fn [m file]
          (let [txt (try (.readFileSync fs file "utf8")
                         ;; the file was JUST enumerated by list-cljs-files —
                         ;; a read failure now is anomalous (:core), and a
                         ;; silent skip would hide a downstream ns from the
                         ;; index; the reduce still degrades (nil txt skips).
                         (catch :default e
                           (error/record! {:seon.error/raw e :seon.error/fault :core})
                           nil))
                ns  (some-> txt ns-name-from-source)]
            (if (and ns (empty? (reserved-extra-nses [ns])))
              (assoc m ns file)
              m)))
        {}
        files))
    {}))

(defn- extra-src-ns-strs
  "The downstream ns NAME strings discovered by scanning SEON_EXTRA_SRC (see
   [[extra-src-ns->file]]). A set; `#{}` when SEON_EXTRA_SRC is unset. Used to
   (a) give EVERY downstream ns a full-source `:seon.ns` row, (b) replay-skip
   them in [[core-ns-set]], and (c) drive [[extra-fn-rows]]."
  []
  (into #{} (keys (extra-src-ns->file))))

(defn- extra-fn-rows
  "`:seon.fn` rows for EVERY public `(defn …)`/`(defn- …)` across the
   downstream SEON_EXTRA_SRC surface — specced AND unspecced. The whole-
   surface counterpart to the specced-only var-derived rows: this is what
   makes an unspecced helper (`acme.helpers/format-count`) and an
   unspecced-only ns's fns (`acme.notes/*`) appear as indexed members in the
   namespace render. `now` is the shared `:seon.fn/created-at` instant.

   Each ns's file is read once; its defns parsed by [[defn-rows-from-source]].
   Specced downstream fns ALSO get a var-derived row (with the real
   `:seon.fn/spec`) — [[index-core!]] dedups by sym, keeping the var-derived
   row in front so the spec is preserved."
  [now]
  (let [fs (js/require "fs")]
    (into []
          (mapcat (fn [[ns-str file]]
                    (let [txt (try (.readFileSync fs file "utf8")
                                   ;; enumerated file failing to read now is
                                   ;; anomalous (:core); a silent skip hides a
                                   ;; downstream ns's fns from the index.
                                   (catch :default e
                                     (error/record! {:seon.error/raw e :seon.error/fault :core})
                                     nil))]
                      (if txt
                        (defn-rows-from-source ns-str txt now)
                        []))))
          (extra-src-ns->file))))

(defn- warn-if-extra-src-unregistered!
  "Observability for BUG B (silent total invisibility of a consumer's product
   source): when `SEON_EXTRA_SRC` is set but `extra-core-vars*` is empty, the
   consumer wired the source root onto the classpath but their
   SEON_EXTRA_PRELOAD entry ns never ran the `(reset! !extra-core-vars …)` —
   so ZERO downstream rows index, with no error. Emit ONE loud, actionable
   `:seon.log` warn naming SEON_EXTRA_SRC + the exact one-liner the entry ns
   must run. Observability only — does NOT change indexing. Returns nil."
  [extra]
  (when-let [src (config/extra-src)]
    (when (empty? extra)
      (log/warn!
        {:seon.log/source  ::index-core!
         :seon.log/message
         (str "SEON_EXTRA_SRC=" src " is set but NO extra-core vars are "
              "registered — your consumer's product source will NOT be indexed "
              "(invisible to agent context + retrieval, with no further error). "
              "Your SEON_EXTRA_PRELOAD entry ns must run, at load time:\n"
              "  (reset! seon.client/!extra-core-vars\n"
              "          (filterv #(clojure.string/starts-with? "
              "(str (:ns (meta %))) \"<prefix>.\")\n"
              "                   (public-fn-vars)))\n"
              "where <prefix> is your source root prefix (e.g. \"acme\"), and "
              "the entry ns needs "
              "(:require-macros [seon.indexing :refer [public-fn-vars]]) "
              "so the macro expands in YOUR ns (whose require closure sees your "
              "vars — a seon-side helper could not).")})))
  nil)

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
  {:malli/schema [:=> [:cat] :any]}
  []
  (let [now     (js/Date.)
        ;; Downstream extra-core vars join the roster after the
        ;; sym-dedup against core-vars; the reserved-prefix guard
        ;; (seon.*/my.*) is the boot-index-time LOUD refusal — extra-src
        ;; research §e.
        extra   (extra-core-vars*)
        _       (assert-extra-vars-unreserved! extra)
        _       (warn-if-extra-src-unregistered! extra)
        var-rows (keep #(var->fn-row % now) (concat core-vars extra))
        ;; WHOLE-DOWNSTREAM-SURFACE (SEON_EXTRA_SRC): every public defn across
        ;; the scanned downstream root — specced AND unspecced. Var-derived
        ;; rows go FIRST so a specced downstream fn keeps its real
        ;; `:seon.fn/spec`; the source-parsed row for the same sym dedups away.
        have-syms (into #{} (map :seon.fn/sym) var-rows)
        fn-rows  (into (vec var-rows)
                       (remove #(contains? have-syms (:seon.fn/sym %)))
                       (extra-fn-rows now))
        ;; EVERY compiled first-party ns gets a `:seon.ns` row — the
        ;; BUILD-DERIVED closure set, so a root with no public fns of its
        ;; own (a register!-calls-only ns) still gets its row by
        ;; construction (the :namespaces section + `:seon.fn/ns`
        ;; lookup-refs render from exactly these datoms; no hand
        ;; exception list). The scanned downstream nses join the same
        ;; way — so an unspecced-ONLY downstream ns (`acme.notes`) still
        ;; gets its row even though no fn-row's sym names it (it does
        ;; now, via extra-fn-rows — but the union is belt-and-suspenders,
        ;; and covers a downstream ns with literally zero defns).
        ns-syms (into (into compiled-first-party-ns-strs (extra-src-ns-strs))
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
  {:malli/schema [:=> [:cat] :any]}
  []
  (let [now (js/Date.)]
    (into []
          (keep (fn [[k v]]
                  (when (keyword? k)
                    (let [form (try (if (m/schema? v) (m/form v) v)
                                    ;; probe: a registered value whose m/form
                                    ;; can't be computed falls back to the raw
                                    ;; value — the source is still captured
                                    ;; (pr-str below); expected graceful
                                    ;; degradation, not a defect.
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
      (do (if (ghost-var? txt (:line m))
            (log/warn!
              {:seon.log/source  ::var->test-row
               :seon.log/message
               (str "ghost test var " sym " — `:line` " (:line m) " points "
                    "past file " (pr-str (:file m)) " (a deftest deleted from "
                    "a hot-reloaded ns; shadow left the stale var bound). "
                    "Pruned from the roster.")})
            (log/error-console!
              "seon.client/var->test-row"
              (str "could not read real source for " sym
                   " (file " (pr-str (:file m)) " line " (:line m) ") — OMITTING")))
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
  {:malli/schema [:function
                  [:=> [:cat] :any]
                  [:=> [:catn [::vars :any]] :any]]}
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
   identity is absent or whose stored fields have DRIFTED from the freshly
   derived ones — typically `[]`.

   Querying the conn's CURRENT identity set and emitting only the gap means a
   re-index never re-transacts a core row against the populated store —
   removing the re-seed interaction that the Run-3 findings traced to a
   malformed `:seon.fn/ns` value.

   DRIFT-HEALING is uniform across every row kind: a stored row re-emits
   whenever ANY freshly-derived field differs from what the store holds —
   `:seon.ns/source`, `:seon.schema/source`, `:seon.test/source`, and for
   `:seon.fn` rows the WHOLE derived set (source, spec, doc, arglists,
   private?). Identity upsert re-asserts the changed datoms in place; a
   spec that DISAPPEARED from the live var meta is explicitly retracted
   (upsert can't remove a datom). Fn/test re-emits are provenance-guarded
   the same way [[prune-core-ghosts!]] is: only rows whose `:source`
   datom's tx carries `:seon.db/origin :core-seed` are overwritten — an
   agent-authored row (detect-and-tee, runner) with a core sym is NEVER
   clobbered by the boot index. Returns a Promise of the tx-data vector."
  {:malli/schema [:=> [:catn [::conn :any]] :any]}
  [conn]
  (let [all       (concat (index-core!)
                          (index-schemas)
                          (index-tests))
        db        (await (d/db conn))
        ;; Fn rows dedup on sym AND every derived field. Sym-only dedup was
        ;; the stale-spec bug (live incident 2026-07-02: seon.agent.shell's
        ;; rows kept a first-index :seon.shell/* spec forever because the
        ;; changed row was dropped whenever the sym already existed). The
        ;; "" sentinel marks an absent :seon.fn/spec — a real pr-str'd
        ;; Malli form is never empty.
        fn-fields (fn [row]
                    {:seon.fn/source   (:seon.fn/source row)
                     :seon.fn/spec     (get row :seon.fn/spec "")
                     :seon.fn/doc      (:seon.fn/doc row)
                     :seon.fn/arglists (:seon.fn/arglists row)
                     :seon.fn/private? (:seon.fn/private? row)})
        have-fns  (into {}
                        (map (fn [[sym src spec doc args priv]]
                               [sym {:seon.fn/source   src
                                     :seon.fn/spec     spec
                                     :seon.fn/doc      doc
                                     :seon.fn/arglists args
                                     :seon.fn/private? priv}]))
                        (d/q '[:find ?sym ?src ?spec ?doc ?args ?priv
                               :where
                               [?f :seon.fn/sym ?sym]
                               [?f :seon.fn/source ?src]
                               [(get-else $ ?f :seon.fn/spec "") ?spec]
                               [(get-else $ ?f :seon.fn/doc "") ?doc]
                               [(get-else $ ?f :seon.fn/arglists "") ?args]
                               [(get-else $ ?f :seon.fn/private? false) ?priv]]
                             db))
        ;; The core-claimed syms — rows whose :source datom's tx carries the
        ;; boot-index provenance. Only these may be drift-overwritten.
        core-syms (into #{} (map first)
                        (d/q '[:find ?sym
                               :where
                               (or-join [?sym ?tx]
                                 (and [?f :seon.fn/sym ?sym]
                                      [?f :seon.fn/source _ ?tx])
                                 (and [?f :seon.test/sym ?sym]
                                      [?f :seon.test/source _ ?tx]))
                               [?tx :seon.db/origin :core-seed]]
                             db))
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
        ;; Test rows dedup on sym AND source (same drift rule as ns rows).
        have-tsts (into {} (d/q '[:find ?t ?src
                                  :where
                                  [?e :seon.test/sym ?t]
                                  [?e :seon.test/source ?src]]
                                db))
        kept      (vec (remove
                         (fn [row]
                           (or (when-some [stored (get have-fns (:seon.fn/sym row))]
                                 (or (= stored (fn-fields row))
                                     (not (contains? core-syms (:seon.fn/sym row)))))
                               (and (contains? row :seon.ns/name)
                                    (= (get have-nses (:seon.ns/name row))
                                       (:seon.ns/source row)))
                               (when-some [stored (get have-schs (:seon.schema/key row))]
                                 (or (= stored (:seon.schema/source row))
                                     (seval/registration-call-source? stored)))
                               (when-some [stored (get have-tsts (:seon.test/sym row))]
                                 (or (= stored (:seon.test/source row))
                                     (not (contains? core-syms (:seon.test/sym row)))))))
                         all))
        ;; A drifted fn row whose FRESH derivation is unspecced while the
        ;; stored row carries a spec: identity upsert re-asserts the other
        ;; fields but can't REMOVE a datom — retract the stale spec
        ;; explicitly so PRESENT ⇒ specced stays honest.
        retracts  (into []
                        (keep (fn [row]
                                (when-some [sym (:seon.fn/sym row)]
                                  (let [stored-spec (get-in have-fns [sym :seon.fn/spec])]
                                    (when (and (not (contains? row :seon.fn/spec))
                                               (seq stored-spec))
                                      [:db/retract [:seon.fn/sym sym]
                                       :seon.fn/spec stored-spec])))))
                        kept)
        ;; `:seon.ns/require-edges` COMPONENT rows can't ride the plain
        ;; identity upsert: cardinality-many component maps have no
        ;; identity of their own, so a drift re-emit (changed
        ;; :seon.ns/source) would DUPLICATE the edge rows. Strip the
        ;; edges off the kept ns rows and route them through the ONE
        ;; diff mechanism (seon.eval/ns-require-edges-tx — retractEntity
        ;; the old components, assert the new set, [] when unchanged).
        ;; Diffed over ALL fresh ns rows (not just the kept/drifted
        ;; ones), so a pre-structural store BACKFILLS its compiled
        ;; full-source nses on the next boot — ~a pull per full-source
        ;; ns, [] each once converged.
        edge-tx   (into []
                        (mapcat (fn [row]
                                  (when-some [edges (and (map? row)
                                                         (:seon.ns/name row)
                                                         (:seon.ns/require-edges row))]
                                    (seval/ns-require-edges-tx
                                      db (:seon.ns/name row) (set edges)))))
                        all)
        kept      (mapv #(dissoc % :seon.ns/require-edges) kept)]
    (-> kept (into edge-tx) (into retracts))))

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
        `:seon.db/origin :core-seed`, the provenance the boot-index
        transacts land under (boundary-stamped from the unscoped
        `:core-seed` tx-context — unforgeable from inside an agent
        scope). Agent-authored rows (detect-and-tee,
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
  {:malli/schema [:=> [:catn [::conn :any]] :any]}
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
                                {::e e ::ghost-kind kind ::ident ident}))))
                    (sort-by (fn [{::keys [ghost-kind ident]}] [ghost-kind (str ident)]))
                    vec)]
    (when (seq ghosts)
      (await (log/info!
               {:seon.log/source  ::prune-core-ghosts!
                :seon.log/message
                (str "boot-index GC: pruned " (count ghosts)
                     " core ghost row(s) — core-seeded "
                     "program-graph rows whose source no longer exists "
                     "in the booting code: "
                     (str/join ", " (map (fn [{::keys [ghost-kind ident]}]
                                           (str (name ghost-kind) " " (pr-str ident)))
                                         ghosts)))}))
      ;; `:core-seed` writer → runs OUTSIDE any (inherited) agent scope,
      ;; same writer posture as boot-seed!: the transact boundary stamps
      ;; the origin from the ambient scope, and a managed origin is only
      ;; reachable outside an agent scope.
      (let [res (await (db/without-agent
                         (fn []
                           (db/with-tx-context
                             {:seon.db/origin :core-seed}
                             (fn []
                               (db/transact!
                                 conn
                                 (mapv (fn [{::keys [e]}] [:db/retractEntity e])
                                       ghosts)))))))]
        ;; Boot maintenance stays fail-loud (same posture as the seed
        ;; transacts): a silent half-prune would leave the store lying.
        (when-not (:seon.db/ok? res)
          (throw (ex-info (str "boot-index GC retract failed: "
                               (get-in res [:seon.db/error :seon.error/message]))
                          {:seon.client/pruned (mapv (juxt ::ghost-kind ::ident) ghosts)
                           :seon.db/error      (:seon.db/error res)})))))
    {:seon.client/pruned (mapv (juxt ::ghost-kind ::ident) ghosts)}))

(defn- stub-llm
  "A fake LLM that demonstrates the REPL-as-harness response shape: a
   `;; narration` line then a real `(message/user …)` form — the verb
   that says something to the human. The FSM halt policy ends the wake
   when no actionable forms remain. Returns a Promise of {:text \"...\"}."
  [ctx]
  (let [text (str
               ";; stub LLM here — the real one needs DEEPSEEK_API_KEY\n"
               ";; say hello to your human via the message/user verb\n"
               "(message/user\n"
               "  "
               (pr-str (str "hello from the stub LLM — saw "
                            (tokens/estimate ctx) " tokens of ctx"))
               ")\n")]
    (.then (.resolve js/Promise nil) (fn [_] {:text text}))))

(defn- select-adapter
  "Pick the llm-fn for the CURRENT effective provider — the adapter whose
   wire path matches `(seon.ai/provider)`, gated on its API key (else the
   stub). Read PER CALL (not cached) so it reflects the effective config at
   the moment of the call: inside an agent turn scope ([[seon.agent.turn/run-turn!]]
   wraps the call in `db/with-agent id`), `(seon.ai/provider)` resolves that
   agent's `:seon.ai/agent-provider` overlay — so an agent whose provider
   differs from the cluster default routes to ITS adapter. `:inherit` (the
   default) and no-override agents resolve the global provider = byte-parity.
   Outside an agent scope (boot smoke, gym render) it is the global provider."
  []
  (case (ai/provider)
    :anthropic     (if (config/anthropic-api-key)
                     (anthropic/agent-adapter)
                     stub-llm)
    ;; DiffusionGemma — ONE provider, two backends (seon.ai/dg-backend,
    ;; env SEON_DG_BACKEND). :control = the transformers RunPod worker
    ;; (the per-step seam); :vllm = an OpenAI-compatible serving endpoint
    ;; (falls through to the openai adapter, like :openai-compat). Each
    ;; backend gates on its own key, else the stub.
    :diffusiongemma (case (ai/dg-backend)
                      :control (if (diffusiongemma/api-configured?)
                                 (diffusiongemma/agent-adapter)
                                 stub-llm)
                      (if (openai/api-key-configured?)
                        (openai/agent-adapter)
                        stub-llm))
    ;; :openai-compat rides the SAME adapter as :deepseek (the wire
    ;; format is OpenAI's) — endpoint + key resolve per call from the
    ;; :seon.ai/config row / SEON_AI_* env (see seon.ai.openai-compat's
    ;; ns doc). :deepseek likewise uses the shared key resolution
    ;; (DEEPSEEK_API_KEY default, SEON_AI_API_KEY / api-key-env too).
    (if (openai/api-key-configured?)
      (openai/agent-adapter)
      stub-llm)))

(defn- current-llm-fn
  "The llm-fn for this pod process: a DISPATCHING closure that selects the
   provider adapter PER CALL via [[select-adapter]]. Single selection point —
   `-main`, the hot-reload re-arm, and the HTTP endpoints all call this.
   Because the adapter is chosen at CALL time (not at re-arm), a per-agent
   `:seon.ai/agent-provider` overlay routes that agent's turn to a different
   provider than the cluster default (the call runs inside
   [[seon.agent.turn/run-turn!]]'s `db/with-agent id` scope, so
   `(seon.ai/provider)` resolves the effective per-agent provider). Choosing
   per call also means a hot reload of an adapter ns takes effect on re-armed
   listeners; a boot-time-baked closure would pin agents to pre-reload
   adapter code. Adapter construction is a trivial closure ((agent-adapter)
   returns (fn [ctx] …)); the real config resolves inside it via
   `seon.ai/current`."
  []
  (fn [ctx-text] ((select-adapter) ctx-text)))

;; `seon.agent/armable-agent-ids` (state ≠ :terminated) is the single
;; source of truth for "this agent can still be woken".

(schema/register! ::mint?         :boolean)
(schema/register! ::llm-fn        fn?)
(schema/register! ::compile-state :any)
;; Config-driven agent-init — arm the message wake trigger at init
;; (agent-level, default true). Seeded onto the agent by seed-default-ctx! and
;; READ here by [[wake-armed?]] to gate the trigger install.
(schema/register! ::wake? [:boolean {:default true}])

(defn- wake-armed?
  "Whether agent `id` should have its message wake trigger installed — its
   `:seon.client/wake?` datom (default true = today's unconditional arm). A
   guarded read: nil db / never-installed attr / non-bool → true (byte-parity).
   `wake? false` (config or a live transact) means the agent does NOT auto-wake
   on a message — a human-driven or externally-stepped agent."
  [id]
  (let [db (some-> db/*conn* deref)]
    (if (and db id (contains? (db/installed-schema db) ::wake?))
      (let [v (:seon.client/wake?
                (db/entity {:seon.db/db db :seon.db/ref [:seon.agent/id id]}))]
        (if (boolean? v) v true))
      true)))
(schema/register! ::init-agent-request
  [:map
   [:seon.agent/id      :seon.agent/id]
   [::mint?             {:optional true} ::mint?]          ; default false = re-arm existing
   [:seon.agent/purpose {:optional true} :seon.agent/purpose] ; mint-only (create! gates on fresh?)
   [::llm-fn            {:optional true} ::llm-fn]          ; omitted → (current-llm-fn)
   [::compile-state     {:optional true} ::compile-state]]) ; omitted → (repl/ensure-bootstrap!)

;; success = boot!'s {:seon.agent/id _ :seon.agent/ns _} (mint?) OR {:seon.agent/id id} (re-arm);
;; a FAILED create (mint?) returns the db error envelope as-is (errors are values).
(schema/register! ::init-agent-response
  [:or [:map [:seon.agent/id :seon.agent/id]] :seon.db/transact-response])

(defn ^:async init-agent!
  "THE ONE way an agent comes to life or re-arms IN-PROCESS. Deterministic, no
   turn-0 ceremony. Steps (fixed order):
     1. resolve cs = compile-state | (ensure-bootstrap!), llm = llm-fn | (current-llm-fn)
     2. setup-agent-ns!  — wire the home ns requires (the reflexive-verb wiring)
     3. (mint?) agent/boot! — create the entity (+ purpose); on FAILURE do NOT
        arm/host, return the error envelope (no ghost agent, task #21 stance)
     4. install-wake-trigger! {id llm cs}
     5. runtime-id/host! id
   Runs in a (db/with-agent id …) scope so every transact carries the id.
   Ready = :idle, zero runs, fully wired, wakeable.

   `mint? true` creates the entity (the `/agents/new` + first-boot path);
   `mint? false` (default) re-arms an EXISTING entity — the hot-reload
   (`rearm-wake-triggers!`) + spawn (`!arm-child-fn`) path. `setup-agent-ns!`
   and `install-wake-trigger!` are idempotent (the trigger unlistens its prior
   key), so re-running per hot reload is safe. llm-fn is re-derived fresh via
   `current-llm-fn` when omitted so arming never wires a stale adapter."
  {:malli/schema [:=> [:cat ::init-agent-request] ::init-agent-response]}
  [{:seon.agent/keys [id purpose] ::keys [mint? llm-fn compile-state]}]
  (await
    (db/with-agent id
      (fn ^:async init! []
        (let [cs  (or compile-state (await (repl/ensure-bootstrap!)))
              llm (or llm-fn (current-llm-fn))]
          ;; 2. wire the home ns FIRST (both mint + re-arm need it before
          ;;    anything that assumes the ns exists).
          (await (seval/setup-agent-ns! cs (agent/home-ns id) id))
          ;; 3. MINT ONLY: create the entity. On a FAILED create the db error
          ;;    envelope propagates as-is (task #21): NO entity → do NOT arm a
          ;;    trigger or host the id (no ghost on the roster).
          (let [boot-res (when mint?
                           (await (agent/boot!
                                    (cond-> {:seon.agent/id id}
                                      (some? purpose)
                                      (assoc :seon.agent/purpose purpose)))))]
            (if (and mint? (false? (:seon.db/ok? boot-res)))
              (do (log/error-console!
                    "seon.client/init-agent!"
                    (str "agent/boot! FAILED for " id
                         " — no entity; propagating the error envelope")
                    boot-res)
                  boot-res)
              (do
                ;; 4. arm the wake trigger (MUST be last-of-wiring — install
                ;;    unlistens any prior key) — GATED on the agent's
                ;;    `:seon.client/wake?` datom (default true = today). A
                ;;    `wake? false` agent is wired + hosted but does NOT
                ;;    auto-wake on a message (human-/externally-driven).
                ;;    5. host the id for MCP (independent of wake).
                (when (wake-armed? id)
                  (agent-loop/install-wake-trigger!
                    {:seon.agent/id            id
                     :seon.agent/llm-fn        llm
                     :seon.agent/compile-state cs}))
                (runtime-id/host! id)
                (if mint? boot-res {:seon.agent/id id})))))))))

(defn- register-arm-hook!
  "Point `seon.agent/start!`'s spawn ARM injection (`!arm-child-fn`) at
   `init-agent!` (#30). Called at boot AND on every hot reload, so the running
   pod arms each freshly-spawned child IN-PROCESS with live code — without it,
   `start!` mints an UNARMED child and a message to it strands. Idempotent
   (resets the defonce'd atom). The closure resolves `init-agent!` at call time,
   so it always runs the latest code. The child ENTITY already exists (`start!`/
   `create!` made it), so this re-arms (`mint? false`), never mints."
  {:malli/schema [:=> [:cat] :any]}
  []
  (reset! agent/!arm-child-fn
          (fn ^:async arm-child! [child-id]
            (init-agent! {:seon.agent/id child-id ::mint? false}))))

(defn- rearm-wake-triggers!
  "Hot-reload hygiene: re-install the per-agent wake trigger for every
   armable agent so handler fixes take effect on hot reload. Without
   this, the registered tx-listener closures keep running pre-reload
   code.

   Everything is derived, nothing stored: agent ids from the DB
   (`seon.agent/armable-agent-ids`), compile-state from the idempotent
   `repl/ensure-bootstrap!`, llm-fn rebuilt via `current-llm-fn`.
   `agent-loop/install-wake-trigger!` is itself idempotent (unlistens the
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
            (fn ^:async rearm! [compile-state]
              (let [ids (agent/armable-agent-ids {:seon.db/db @conn})]
                (doseq [id ids]
                  ;; One init mechanism: `init-agent!` (re-arm, mint? false)
                  ;; replays the home-ns wiring into the (possibly freshly
                  ;; rebuilt) compile-state BEFORE installing the trigger —
                  ;; killing the install-timing race — re-derives llm-fn, and
                  ;; re-hosts the id (MCP). Pass the already-bootstrapped state
                  ;; so it isn't re-derived per id.
                  (await (init-agent! {:seon.agent/id       id
                                       ::mint?              false
                                       ::compile-state      compile-state})))
                (log/info-console! "seon.client"
                                   "reload: wake triggers re-armed"
                                   {:seon.client/reinstalled ids})
                ids)))
          (.catch
            (fn [err]
              (log/error-console! "seon.client"
                                  "reload: wake trigger re-arm FAILED"
                                  err)))))
    (js/Promise.resolve [])))

(schema/register! ::seeded? [:= true])
(schema/register! ::boot-seed-request  [:map [:seon.db/conn :seon.db/conn]])
(schema/register! ::boot-seed-response [:map [::seeded? ::seeded?]])

(defn ^:async boot-seed!
  "THE core boot seed — ONE code path for 'make this store the
   world a pod boots into', shared verbatim by `start-agent!` (live
   boot) and the gym's `seed-scenario-world!` (scratch worlds), so the
   two can never drift again (the gym hand-mirrored this sequence and
   drifted twice). The agent's identity is NOT seeded — SOUL.md /
   AGENTS.md are read LIVE as context sections every render
   (`seon.agent.ctx/file-block`), so gym and live prompts get the same
   identity with no seed step.

   Steps, in boot order. TWO provenance layers:

   APPEND-ONLY (origin `:core-seed`) — introspection, never a desired
   set, never retracted (three transacts, each its own tx so the core
   prefix stays a stable sequence of tx-times):
          :entity-schemas  — `schema/all-entity-schemas-tx-data`.
          :core-seed  — `seed-core!` (user entity +
                             my.kb.shared instruction singleton).
          :core-index — `core-index-tx` (`:seon.ns` /
                             `:seon.fn` / `:seon.schema` / `:seon.test`
                             rows, conn-deduped so an Nth boot on the
                             same store re-seeds nothing).

   DECLARATIVE DESIRED SET (origin `:config`) — the routes
   (`route/core-routes-tx`, curated by the manifest) + the skills corpus
   (`my.skills/seed-skills-tx-data`, curated by the manifest) are the ONE
   managed declarative population, synced through
   `seon.state/reconcile!` (scope `#{:config}`). reconcile UPSERTS each
   desired row by its own `:db.unique/identity` (`:seon.route/name` /
   `:my.skills/name`) AND RETRACTS any managed row absent from the desired
   set — so dropping a route from the manifest, or a skill from disk,
   removes the stale datom (it can no longer persist across boots). The
   `:core-seed` introspection above is NOT in this scope and is never
   touched.

   Pins the root `db/*conn*` to `conn` for the duration, restoring in
   `finally`. ENVELOPE CONTRACT
   (A4): `db/transact!` never rejects, so every step checks the
   envelope and THROWS (surface-errors-loudly) — a silent partial seed
   is far worse than a crashed boot."
  {:malli/schema [:=> [:cat ::boot-seed-request] ::boot-seed-response]}
  [{conn :seon.db/conn}]
  ;; WRITER-ENFORCED scope: the whole seed runs OUTSIDE any agent scope
  ;; (`db/without-agent`, ALS exit). Its txs establish managed origins
  ;; (`:core-seed` / `:config`) via `with-tx-context`, and the transact
  ;; boundary stamps `:seon.db/origin` from that ambient scope. Under an
  ;; INHERITED agent scope — e.g. a call reached from an HTTP handler
  ;; (the boot registers the server inside the primary agent's
  ;; `with-agent`, so every request handler inherits that scope) — the
  ;; stamp would override the managed origins to `:agent` and the seed
  ;; rows would lose their core provenance.
  (await
    (db/without-agent
      (fn ^:async seed-unscoped! []
        (let [prev-conn db/*conn*]
          (set! db/*conn* conn)
          (try
            (let [index-tx (await (core-index-tx conn))
                  ;; The OPTIONAL loadout manifest, read ONCE and threaded to
                  ;; the route + skills steps below ({} when config/system.edn
                  ;; is absent ⇒ every resolve-* is the identity ⇒
                  ;; byte-identical seed).
                  manifest (config/load-manifest)
                  check!   (fn [step {ok?   :seon.db/ok?
                                      error :seon.db/error}]
                             (when-not ok?
                               (throw (ex-info
                                        (str "boot seed transact failed at "
                                             step ": "
                                             (:seon.error/message error))
                                        {:seon.client/seed-step step
                                         :seon.db/error error}))))]
              ;; APPEND-ONLY core (origin :core-seed): introspection that is
              ;; not a desired set, never retracted.
              (await
                (db/with-tx-context
                  {:seon.db/origin :core-seed}
                  (fn ^:async seed! []
                    (check! :entity-schemas
                            (await (db/transact!
                                     {:seon.db/conn conn
                                      :seon.db/tx-data
                                      (schema/all-entity-schemas-tx-data)})))
                    (check! :core-seed
                            (await (db/transact!
                                     {:seon.db/conn conn
                                      :seon.db/tx-data (seed-core!)})))
                    ;; No soul seed: the agent's identity is read LIVE from
                    ;; SOUL.md / AGENTS.md as context sections every render
                    ;; (seon.agent.ctx/file-block), never seeded into the store.
                    (check! :core-index
                            (await (db/transact!
                                     {:seon.db/conn conn
                                      :seon.db/tx-data index-tx}))))))
              ;; DECLARATIVE DESIRED SET (origin :config): the routes
              ;; (`:seon.route/*`, identity `:seon.route/name`) + the scanned
              ;; skills corpus (`:my.skills/*`, identity `:my.skills/name`),
              ;; curated by the manifest, are ONE managed population synced
              ;; through reconcile! — upsert-by-identity (idempotent on an Nth
              ;; boot) AND retract-stale, so a route dropped from the manifest
              ;; or a skill removed from disk is RETRACTED (it cannot
              ;; persist). Scope `#{:config}` excludes the :core-seed
              ;; introspection above. reconcile! never rejects; its
              ;; error-value is checked + thrown (surface-errors-loudly).
              (let [desired (into (vec (config/resolve-routes
                                         (route/core-routes-tx)
                                         manifest))
                                  ;; the scanned skill corpus is the desired
                                  ;; set as-is (no include/exclude curation —
                                  ;; the env-dir scan IS the corpus; always-on
                                  ;; bodies are the agent-context's
                                  ;; :my.skills/load presence-set).
                                  (my.skills/seed-skills-tx-data))
                    recon   (await
                              (db/with-tx-context
                                {:seon.db/origin :config}
                                (fn ^:async reconcile-declarative! []
                                  (state/reconcile!
                                    {:seon.state/desired    desired
                                     :seon.db/managed-scope #{:config}
                                     :seon.db/conn          conn}))))]
                (when (false? (:seon.state/ok? recon))
                  (throw (ex-info
                           (str "boot seed reconcile (routes+skills) failed: "
                                (:seon.state/error recon))
                           {:seon.client/seed-step :core-declarative
                            :seon.state/error      (:seon.state/error recon)})))))
            {::seeded? true}
            (finally
              (set! db/*conn* prev-conn))))))))

(defn ^:async start-agent!
  "Bring up the pod's agents: open conn, init bootstrap-CLJS, then
   RESUME every armable agent in the cluster store — the agents whose
   DERIVED state is `:idle` (not terminated, no open run — `armable-agent-ids`)
   — arming each one's wake trigger. A fresh agent is minted ONLY when
   the store has zero armable agents (genuine first boot) or on the
   explicit create path (`:mint? true` — POST /agents/new).
   Identity is durable: restarting the pod does NOT accumulate agents.

     :llm-fn — fn of ctx-string returning a Promise of {:text \"...\"}.
               Optional; defaults to stub-llm for verification without
               an API key. Pass (seon.ai.openai-compat/agent-adapter)
               for the real thing.
     :mint?  — force-mint a NEW agent even when armable agents exist
               (they are already armed from boot). The /agents/new path.

   Returns a Promise resolving to
     {:seon.agent/id _ :seon.agent/ns _          ; the minted agent, or
                                                 ; the first resumed one
      :seon.client/resumed-ids [...]
      :seon.client/minted-ids  [...]
      :seon.web/port _ :seon.web/port-file _}.
   Subsequent (seon.agent/message! …) calls (or POST /chat) drive the
   loop via the wake trigger."
  {:malli/schema [:=> [:cat [:* :any]] :any]}
  [& [{:keys [llm-fn mint? purpose] :or {llm-fn stub-llm}}]]
  (let [existing-conn @!agent-conn
        conn          (or existing-conn (await (open-cluster-conn!)))
        _             (reset! !agent-conn conn)
        ;; Bind the conn as the root *conn* so seon.db calls + the
        ;; wake trigger resolve without per-call threading.
        _             (set! db/*conn* conn)
        ;; v1.md §7.1 boot preconditions. Throws cleanly if the conn
        ;; was opened without :keep-history? OR if the seon.db tx-meta
        ;; attrs aren't registered. Catches misconfigured schema
        ;; registries before the agent starts writing txs that would
        ;; silently lose their causality bundle.
        _             (db/assert-preconditions! {:seon.db/conn conn})
        ;; CRASH RECOVERY (Gemini #4): close any run orphaned by a prior pod
        ;; crash — an :open run + :seon.agent/run pointer with no loop
        ;; driving it (derived :running, so unwakeable). MUST run BEFORE
        ;; armable-agent-ids below: a recovered agent then reads :idle and
        ;; JOINS the resume roster (else it's derived :running → never armed
        ;; → permanent deadlock). Gated on GENUINE BOOT (nil existing-conn):
        ;; NEVER on a /agents/new mint in a LIVE process (that would close
        ;; currently-RUNNING agents' open runs). Idempotent + a no-op when
        ;; no runs are open.
        _             (when (nil? existing-conn)
                        (let [{closed :seon.agent.run/closed}
                              (await (run/recover-crashed-runs!))]
                          (when (seq closed)
                            (log/info-console!
                              "seon.client/start-agent!"
                              (str "crash recovery: closed " (count closed)
                                   " orphaned run(s) :crashed")
                              {:seon.agent.run/closed closed}))))
        ;; Bootstrap-CLJS init via the shared iteration-surface atom.
        ;; Version-stamped — a hot-reload of seon.eval rotates the
        ;; gensym so the next call rebuilds the state. Idempotent
        ;; while the core code is stable.
        compile-state (await (repl/ensure-bootstrap!))
        ;; RESUME, DON'T MINT (P3.5/#31): the store is the identity
        ;; source. Mint only on genuine first boot or explicit create.
        resumed-ids   (if mint? [] (agent/armable-agent-ids {:seon.db/db @conn}))
        ;; First boot mints the orchestrator-root ("root", no parent — the
        ;; spawn-recursion base case); `mint?` (`/agents/new`) mints a normal
        ;; 14-char child id instead.
        minted-ids    (cond
                        mint?                [(db/new-id!)]
                        (empty? resumed-ids) ["root"]
                        :else                [])
        agent-ids     (into resumed-ids minted-ids)
        ;; The PRIMARY agent (return-shape + shared-boot tx scope):
        ;; the minted one when minting, else the first resumed.
        primary       (first agent-ids)]
    (log/info-console! "seon.client/start-agent!" "agent roster"
                       {:seon.client/resumed resumed-ids
                        :seon.client/minted  minted-ids})
    ;; THE core boot seed — handlers + the four seed transacts, extracted
    ;; to [[boot-seed!]] so scratch worlds (the gym) run the
    ;; boot's OWN code path (one mechanism — the hand-mirrored copy
    ;; drifted twice). MUST run BEFORE the per-agent init: seeding first
    ;; means the user entity + core schema exist before any agent wakes
    ;; and a message resolves against them. Runs OUTSIDE the `with-agent`
    ;; scope below: its txs land under the managed origins (`:core-seed`
    ;; / `:config`), which the transact boundary only stamps outside an
    ;; agent scope. boot-seed! pins its own *conn* and tx-context.
    (await (boot-seed! {:seon.db/conn conn}))
    ;; Boot-index GC — MUST run before replay: a DELETED core ns falls
    ;; out of (core-ns-set), so its ghost rows would be misclassified as
    ;; agent corpus and replayed back into the live compile-state (the
    ;; my.kb.instruction dead-teachings incident). Idempotent; loud (one
    ;; :seon.log info naming every pruned row). Also a `:core-seed`
    ;; writer → outside agent scope, same as the seed. Safe to run right
    ;; after the seed: the freshly-seeded `:core-seed` rows are all in
    ;; prune's freshly-built `fresh` set (same pure builders), so prune
    ;; never treats them as ghosts.
    (let [prune-stats (await (prune-core-ghosts! conn))]
      (log/info-console!
        "seon.client/start-agent!"
        (str "boot-index GC: "
             (count (:seon.client/pruned prune-stats))
             " ghost row(s) pruned")))
    (await
      (db/with-agent primary
        (fn ^:async boot-with-agent! []
          (let [;; Load the agent-authored DB LAYER on top of the compiled
                ;; package: each agent ns's reconstituted whole source, in
                ;; dependency order. GLOBAL (not per-agent) — runs ONCE per
                ;; boot, before any per-agent setup. Core nses are EXCLUDED
                ;; ([[agent-ns-set]] drops `(core-ns-set)`) — they're
                ;; compiled (display-only rows). Idempotent against an
                ;; empty conn (genuine first boot) — returns
                ;; {…replay-n-total 0 …}.
                replay-stats  (await (replay-program-graph!
                                       {::conn          conn
                                        ::compile-state compile-state
                                        ::agent-id      primary}))
                _             (log/info-console!
                                "seon.client/start-agent!"
                                (str "replay: " (pr-str replay-stats)))
                ;; Install Malli instrumentation from the PROGRAM GRAPH —
                ;; the DB now holds every core fn (index-core! via boot-seed!)
                ;; plus every replayed agent fn, each with its `:seon.fn/spec`.
                ;; This is the complete, ordering-independent source (issue
                ;; instrumentation-collect-clean-build-empty). Agent fns were
                ;; already wrapped inline by the eval-tee during replay; this
                ;; adds the compiled core fns. IDEMPOTENT on a later pass (a
                ;; POST /agents/new re-runs it): detection sees through
                ;; malli's per-var wrapper record, so re-instrumentation
                ;; re-wraps from originals instead of mis-detecting async
                ;; (the retired once-per-process gate's wedge class).
                instr-stats   (instrument/instrument-from-db! (await (d/db conn)))
                _             (log/info-console!
                                "seon.client/start-agent!"
                                (str "instrumentation: " (pr-str instr-stats)))
                ;; Per-agent init — home ns + (mint only) entity + wake
                ;; trigger + MCP hosting via the ONE `init-agent!`, one at a
                ;; time (init transacts must not interleave). No turn 0: a
                ;; freshly-minted agent is `:idle` with zero runs the moment
                ;; its entity + trigger exist, wakeable on the first message.
                ;; The primary's result feeds the return map.
                results       (let [!acc (volatile! [])]
                                (doseq [aid agent-ids]
                                  (vswap! !acc conj
                                          (await (init-agent!
                                                   (cond->
                                                     {:seon.agent/id       aid
                                                      ::mint?              (boolean (some #{aid} minted-ids))
                                                      ::compile-state      compile-state}
                                                     ;; ::llm-fn is optional (init-agent!
                                                     ;; falls back to current-llm-fn) — only
                                                     ;; pass a REAL fn, never nil (fn? schema).
                                                     (fn? llm-fn)
                                                     (assoc ::llm-fn llm-fn)
                                                     ;; The stated purpose seeds
                                                     ;; ONLY a minted agent
                                                     ;; (create! never re-seeds
                                                     ;; an existing entity).
                                                     (some #{aid} minted-ids)
                                                     (assoc :seon.agent/purpose purpose))))))
                                @!acc)
                ;; Task #21: an init-agent! that came back as an
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
                ;; C-18: sync the :seon.ai/config row to the SEON_AI_*
                ;; env vars (env owns the row across boots). Fire-and-
                ;; forget — sync! never rejects, logs its own failures.
                _ (ai/sync!)
                ;; Install the dev-tools tx-listener. Pushes morphs for
                ;; the /data browser + the per-agent /agent/<id>/debug page.
                _ (seon.web.debug/install!)
                ;; The ONE ticker — the only active machinery (deadline
                ;; watchdog + schedule firing). Single instance + idempotent;
                ;; re-armed on hot reload (after-reload above).
                _ (agent-loop/install-ticker!)
                ;; Register the spawn ARM hook (#30): `seon.agent/start!` arms
                ;; the child it mints by invoking this (injection — seon.agent
                ;; can't require seon.client). So a parent that spawns then
                ;; messages a child IN ONE TURN actually wakes it, no
                ;; out-of-band re-arm sweep. Re-registered on hot reload via
                ;; `after-reload`.
                _ (register-arm-hook!)]
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
  {:malli/schema [:=> [:cat] :any]}
  []
  (start-agent!))

(defn start-agent-with-deepseek!
  "Bring up the V0 agent against the real deepseek API. Requires
   DEEPSEEK_API_KEY in process.env. Returns a channel."
  {:malli/schema [:=> [:cat] :any]}
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

;; POST /agents/run (the one-shot composition door) mints its per-task agent
;; via init-agent! — THE per-agent wiring (create! + wake trigger) against the
;; pod's ONE cluster conn. serve.cljs can't require this ns (cycle), so the
;; mint closure is injected; same seam + hot-reload behavior as
;; set-create-agent-fn! above.
(web.serve/set-mint-agent-fn!
  (fn [id] (init-agent! {:seon.agent/id id ::mint? true})))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

(defonce ^:private !orig-shadow-node-eval
  ;; Dev-eval CALLER scope (C50): `js/SHADOW_NODE_EVAL` is the ONE conduit
  ;; every dev/MCP REPL-submitted form enters the pod through — both nREPL
  ;; :7889 routes (`do-invoke`'s node-eval and `IEvalJS -js-eval`) funnel
  ;; into this one global (reference-code/shadow-cljs .../client/node.cljs
  ;; :11-13,:97-108); hot reload uses SHADOW_IMPORT, never this. Patched
  ;; ONCE per process (defonce) to run each eval inside
  ;; `seon.error/dev-eval!`, so an input-contract violation a dev probe
  ;; provokes on a core fn classifies `:agent` (recorded, pod stays up)
  ;; while a genuine internal `:core` bug still escalates per the dial.
  ;; Absent global (e.g. the :node-test build) → no-op.
  (when (exists? js/SHADOW_NODE_EVAL)
    (let [orig js/SHADOW_NODE_EVAL]
      (set! js/SHADOW_NODE_EVAL
            (fn [code source-map-json]
              (error/dev-eval! (fn [] (orig code source-map-json)))))
      orig)))

(defn- install-process-safety-net!
  "Belt-and-suspenders: Node 15+ defaults to terminating the process on
   an unhandled Promise rejection. Anything in the pod (core, agent
   eval, HTTP handlers) that throws inside an async chain and isn't
   caught upstream brings the whole pod down by default — a tiny
   core bug becomes a denial-of-service.

   This is THE NET (error-blame-strict-gate, RULED 2026-07-04): anything
   escaping every catch becomes a fault-tagged DATOM via
   `seon.error/record!` with zero per-site work — nothing can silently
   vanish even before the catch-site sweep lands. The fault is coarse
   `:core` refined by the ONE classifier (`instrument/wrapper-fault` —
   content wins: an agent-diagnostic or dev-eval-scoped input violation
   reads `:agent` and never escalates). Under the dial's `:gate`/`:log`
   the pod keeps running (never-crash doctrine); under `:crash` a `:core`
   record! persists the datom first, then exits loudly.

   Individual call sites should still `.catch` and convert to data
   shapes (`{:ok? false :error ...}`) where it matters; this is the
   floor, not the ceiling."
  []
  (.on js/process "unhandledRejection"
       (fn [reason _promise]
         (log/error-console! "seon.client" "unhandled promise rejection"
                             (or reason "<no reason>"))
         (when-not (error/recorded? reason)
           (error/record! {:seon.error/raw   reason
                           :seon.error/fault (instrument/wrapper-fault reason :core)}))))
  (.on js/process "uncaughtException"
       (fn [err _origin]
         (log/error-console! "seon.client" "uncaught exception"
                             (or (.-message err) err))
         (when-not (error/recorded? err)
           (error/record! {:seon.error/raw   err
                           :seon.error/fault (instrument/wrapper-fault err :core)})))))

(defn -main
  {:malli/schema [:=> [:cat [:* :any]] :any]}
  [& _args]
  ;; FIRST: gate datahike-cljs/konserve trace+debug (per-index-node
  ;; `:datahike/index-access` traces flooded pod.log to 813 MB on one
  ;; cold-store inspector render, 2026-06-09). Must run before the
  ;; smoke test / start-agent! open any store.
  (log/quiet-library-logs!)
  (install-process-safety-net!)
  (log/info-console! "seon.client" "-main boot" {:boot-at (:boot-at @!state)})
  ;; Malli instrumentation is installed from the PROGRAM GRAPH inside
  ;; `start-agent!` (`instrument/instrument-from-db!`), AFTER the core is
  ;; indexed — the DB is the complete, ordering-independent source of every
  ;; fn + spec (issue instrumentation-collect-clean-build-empty). The
  ;; eval-tee path instruments newly-defined fns inline between boots.
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
  (when-not (config/no-auto-boot?)
    (let [llm-fn   (current-llm-fn)
          provider (ai/provider)
          key-set? (case provider
                     :anthropic (boolean (config/anthropic-api-key))
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
