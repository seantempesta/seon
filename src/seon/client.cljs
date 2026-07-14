(ns seon.client
  "V0 CLJS pod entry point. Long-running Node process; the V0 client.

   Responsibility: attach and cold-start the cluster runtime via
   [[start-runtime!]]. Warm agent birth belongs to [[seon.agent/start!]].

   How to run it:

     ;; Terminal 1 — the watcher (compiles + writes nREPL port file)
     clj -M:cljs watch client

     ;; Terminal 2 — the Node host
     node out/client/main.js

     ;; Editor / MCP — connect to nREPL on localhost:7889, then
     ;; pivot into the running CLJS runtime:
     (shadow.cljs.devtools.api/nrepl-select :client)

     ;; To cold-start with the deterministic stub:
     (seon.client/start-runtime!
       {:seon.client/llm-fn seon.ai.dispatch/stub})

     ;; Then message it (from defaults to the calling scope; the
     ;; HTTP /chat adapter stamps from = the user ref explicitly):
     (seon.agent/message!
       {:seon.agent.message/from    seon.agent/user-ref
        :seon.agent.message/to      [[:seon.agent/id \"<agent-id>\"]]
        :seon.agent.message/content \"hello\"})"
  (:require
    [cljs.reader :as reader]
    [clojure.set :as set]
    [clojure.string :as str]
    [datahike.api :as d]
    ;; konserve.node-filestore registers datahike's :file store backend
    ;; for Node (the agent conn persists to disk — see open-agent-conn!).
    ;; datahike.api conditionally js/requires it, but require it
    ;; explicitly so the :file backend is guaranteed registered in the
    ;; :client bundle regardless of that conditional's timing.
    [konserve.node-filestore]
    ;; malli.core/form round-trips a fn's `:malli/schema` to the stable
    ;; `:seon.fn/spec` string in index-core! (the runtime-introspection
    ;; core indexer — coherent-bootstrap-indexing Step 2).
    [malli.core :as m]
    ;; Instrumentation publishes the validated database-derived projection
    ;; once at boot; accepted eval/hot-reload transitions publish exact deltas.
    [seon.instrument :as instrument]
    ;; Pull in the agent's required namespaces at compile time so all
    ;; schemas are registered before start-runtime! runs.
    [seon.agent :as agent]
    [seon.agent.home :as home]
    ;; Lifecycle functions (wait/complete/terminate) — host-bundled so the agent
    ;; home ns can `:refer` them; required here so the build includes the ns.
    [seon.agent.lifecycle]
    ;; The agent loop + wake trigger: the client boot path ARMS the wake
    ;; trigger (seon.agent does NOT, to stay acyclic).
    [seon.agent.loop :as agent-loop]
    ;; The run lifecycle — the bootstrap turn-0 opens a run for its turn.
    [seon.agent.run]
    ;; Cron-as-data — required so its `:seon.agent.schedule/*` register! calls
    ;; run before `agent-bootstrap-attrs` installs them, and so the ticker's
    ;; `fire-due-schedules!` is in the build.
    [seon.agent.schedule]
    [seon.agent.ctx]
    [seon.ai :as ai]
    [seon.ai.dispatch :as ai.dispatch]
    [seon.db :as db]
    [seon.db.id :as id]
    [seon.db.process :as db.process]
    [seon.error :as error]
    [seon.eval :as seval]
    [seon.log :as log]
    ;; Render protocol — A-2. Required here so the build includes it.
    ;; Symbol-lookup for render slots lives in seon.eval/lookup-value
    ;; (walks goog-global with cljs.core/munge); no boot-time wire-up
    ;; needed.
    [seon.render]
    [seon.render.default]
    ;; Canvas render namespace — required so the build includes it.
    [seon.render.canvas]
    ;; Root's SYSTEM VIEW — the `/` dashboard = root's canvas content.
    ;; Required so the build includes it and `system-view`'s symbol resolves
    ;; via eval/lookup-value when render-agent-canvas renders root.
    [seon.render.system]
    ;; Routing-as-data — the `:seon.route/*` schema + the seeded core route
    ;; set; boot-seed! transacts (route/core-routes-tx). Required here so the
    ;; schema register! calls run and the build includes the seed builder.
    [seon.route :as route]
    ;; Iteration surface — owns the canonical `!compile-state`
    ;; defonce (in `seon.repl`). start-runtime! reads through
    ;; `seon.repl/ensure-bootstrap!` rather than holding a second
    ;; copy here. See compile-state-lifecycle research note.
    [seon.repl :as repl]
    ;; One-node source extraction (`form-source-at`) for the program-graph
    ;; source capture below — rewrite-clj parses EXACTLY one top-level form,
    ;; so char/regex/string-literal parens are balanced correctly (a raw
    ;; depth counter truncates such a form). Same parser `parse-forms` uses.
    [seon.repl.internal :as repl-internal]
    ;; REPL autocomplete (repl-autosuggest lane): the byte-exact situation
    ;; projection + the turn-mining exporter. Required so the build includes
    ;; it (curation attrs registered, export!/context callable + indexed).
    [seon.repl.autocomplete]
    [seon.runtime.recovery :as recovery]
    ;; Schemas-as-queryable-data (research file
    ;; schemas-as-queryable-data-2026-05-26.md). At boot,
    ;; start-runtime! decomposes every entity-shape :map schema into
    ;; a :seon.schema entity carrying its required-attrs / id-attr /
    ;; render symbols. Renderer kind-lookup queries these via
    ;; datalog instead of walking the in-memory *schemas atom.
    [seon.schema :as schema]
    [seon.schema.internal :as schema.internal]
    ;; Phase 2 — test capture as data. Required so the bundle
    ;; includes the runner; agent code reaches it from
    ;; bootstrap-CLJS eval via the analyzer's globalThis fallback
    ;; (seon.eval/truly-undeclared?).
    [seon.test.runner]
    ;; Pod HTTP+SSE server — A-5. Required here so the build includes
    ;; it; start-runtime! calls (web.serve/start!) at boot.
    [seon.web.serve :as web.serve]
    ;; Brand configuration is the only web-specific boot synchronization.
    ;; Debug/data feeds install lazily only while their routes are open.
    [seon.web.brand :as web.brand]
    ;; Default :seon.render/ai + :seon.render/html for :seon.agent.message
    ;; entities. Referenced by symbol from message tx data.
    [seon.handlers.message]
    ;; Renderers for :seon.eval / :seon.fn / :seon.schema / :seon.ns —
    ;; stamped at the write site (record-eval!, build-tee-entities) so
    ;; each persisted entity appears in the debug view's two panes via
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
    ;; Pull-reference corpus — the `:my.skills/*` schema + scanner whose rows
    ;; `boot-seed!` transacts (`my.skills/seed-skills-tx-data`). These rows are
    ;; available on demand and are not standing context blocks.
    ;; Required here so its register! calls run and the build includes it.
    [my.skills]
    ;; The canvas/canvas TOOLKIT — the aggregation (`my.data`) + static
    ;; (`my.ui`) + interactive (`my.canvas`) functions the agent composes its
    ;; canvas from. Required here so they BUILD + INDEX at boot (their
    ;; `:seon.fn` rows render full in the `:namespaces` block — the worked
    ;; examples, not `(no public fns indexed yet)`). They reference the
    ;; `:seon.items/*` envelope required above.
    [my.data]
    [my.ui]
    [my.canvas]
    ;; Content-addressed blob store — the disk tier (big text lives behind
    ;; a :my.blob/hash ref, never as datoms). Required so it builds +
    ;; indexes at boot and turn-capture/web-fetch can compose on it.
    [my.blob]
    ;; Program-graph introspection — (my.ns/functions {:my.ns/ns 'x})
    ;; lists a namespace's fns as the compact one-line cards. Required so
    ;; it builds + indexes at boot.
    [my.ns]
    ;; Inert foreign-code values — the `#code` heredoc literal's schemas
    ;; (`:seon.code/lang`/`::text`/`::block`). Required here so register!
    ;; runs before the reader/fs functions hand these maps around.
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
    ;; Context-block namespaces: each owns one block fn (+ optional HTML twin)
    ;; that the manifest may wire by SYMBOL — required here so the build includes them and
    ;; their munged symbols resolve via seon.eval/lookup-value at
    ;; render time (no require cycle: the section nses require seon.agent.ctx
    ;; for the shared read API, seon.agent.ctx names them only as symbols).
    [seon.agent.ctx.namespaces :as nss]
    [seon.agent.ctx.canvas]
    [seon.agent.ctx.warnings]
    [seon.agent.ctx.transcript]
    [seon.agent.ctx.subagents]
    [seon.agent.ctx.menu]
    ;; The :typeahead-steps block family (NOT seeded by default — installed
    ;; explicitly per agent / via a manifest overlay). Required so the build
    ;; includes it and its render-slot symbols resolve.
    [seon.agent.ctx.typeahead-steps]
    [seon.platform]
    ;; Phase B item 9 — shared read-side wrapper over the analyzer
    ;; state. Required here so the build includes it; item 10's
    ;; detect-and-tee in seon.eval/eval-batch! consumes it.
    [seon.analyzer-info :as analyzer-info]
    ;; Local reads plus the sole-writer transaction and feed attachment.
    [seon.db.protocol :as db.protocol]
    [seon.db.replica :as replica]
    ;; Use Shadow's own reload-source selection for build notifications;
    ;; this must stay identical to the files its Node client actually loaded.
    ;; Pure MCP runtime-addressing values. The pod advertisement below queries
    ;; its database on demand; there is no second hosted-agent registry.
    [seon.dev.runtime-id :as runtime-id])
  ;; Compile-time enumeration of the build's PUBLIC fns — `core-vars`
  ;; below IS this macro's whole-closure var vector: every public first-party
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

(defn runtime-advertisement
  "Project this pod's MCP addressable agents directly from its database.

   The cluster coordinate is immutable launch configuration. Agent membership
   is the same nonterminated, born-agent query used by cold resume. Before the
   database attaches, the pod still advertises its cluster with no agent ids so
   an ordinary cluster-pinned REPL can connect during boot."
  {:malli/schema
   [:=> [:cat]
    [:map
     [:seon.dev.runtime-id/cluster :string]
     [:seon.dev.runtime-id/ids [:vector :string]]]]}
  []
  (runtime-id/advertisement
   #:seon.dev.runtime-id
    {:cluster replica/database-name
     :ids (if (db/attached?)
            (agent/resumable-agent-ids {:seon.db/db @db/*conn*})
            [])}))

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

(declare rehost-agent-runtimes!)

(defn- instrumentation-summary
  "Drop per-function inventories from instrumentation status logs."
  [stats]
  (dissoc stats ::instrument/data ::instrument/accepted-syms))

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
  ;; Web feeds re-arm their own shared/lazy listeners from their namespace
  ;; reload hooks; there is no always-on debug listener here.
  (rehost-agent-runtimes!)
  ;; Re-arm the ONE ticker so a hot reload doesn't stack timers and the tick
  ;; body runs just-reloaded code (idempotent — clears the prior interval).
  (agent-loop/install-ticker!)
  (start-heartbeat!))

(defn- shadow-reloaded-namespaces
  "Namespace symbols Shadow's Node client loaded for one completed build.

   Keep this selection byte-for-byte equivalent to
   `shadow.cljs.devtools.client.node/handle-build-complete`: Node reloads a
   source when its resource id is in `:compiled` or its namespace is in
   `:always-load`, unless the namespace is in `:never-load`. The browser-only
   `filter-reload-sources` helper additionally consults its loaded-source
   registry and returns an empty set after Node has synchronously required the
   files, which previously left every replaced function uninstrumented."
  [message]
  (let [{:keys [sources compiled]} (:info message)
        {:keys [always-load never-load]} (:reload-info message)]
    (into #{}
          (comp
            (remove #(contains? never-load (:ns %)))
            (filter #(or (contains? compiled (:resource-id %))
                         (contains? always-load (:ns %))))
            (keep :ns))
          sources)))

(defn shadow-build-notify!
  "Apply exact instrumentation after Shadow loads changed namespaces."
  {:malli/schema [:=> [:catn [::message :any]] :boolean]}
  [message]
  (when (and (= :build-complete (:type message))
             (db/attached?))
    (let [namespace-syms (shadow-reloaded-namespaces message)
          stats
          (instrument/instrument-namespaces-from-db!
            {::instrument/db @db/*conn*
             ::instrument/namespace-syms namespace-syms})]
      (log/info-console!
        "seon.client"
        (str "reload: exact instrumentation "
             (pr-str (instrumentation-summary stats))))))
  true)

(defn ^:async mem-db
  "REPL convenience — open a fresh :memory datahike-cljs DB with optional
   schema. Returns a Promise resolving to a caller-owned conn atom; call
   `(datahike.api/release conn)` when finished."
  {:malli/schema [:function
                  [:=> [:cat] :any]
                  [:=> [:catn [::schema :any]] :any]]}
  ([] (mem-db []))
  ([schema]
   (let [cfg {:store              {:backend :memory
                                   :id (random-uuid)}
              :schema-flexibility :write
              ;; Match the agent connection's history posture (Phase 2.5).
              :keep-history?      true}]
     (await (d/create-database cfg))
     (let [conn (await (d/connect (id/allocation-connect-config cfg)))]
       (id/assert-allocation-writer! conn)
       (when (seq schema)
         (await (d/transact! conn schema)))
       conn))))

;; ---------------------------------------------------------------------------
;; Cluster runtime
;;
;; start-runtime! opens the cluster connection, bootstraps the Datahike schema,
;; binds seon.db/*conn* at the var root, and resumes the durable agents.
;; Repeated calls consult that one live attachment and return status without
;; repeating cold work; hot reload reconstructs only process-local runtimes.
;; ---------------------------------------------------------------------------

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
   ;; QUERY it on a fresh cluster before any spawn has lazily installed it.
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
   ;; Per-agent process reconstruction dials. Both are durable facts read by
   ;; seon.agent.runtime/home; absent values retain their defaults.
   :seon.agent.runtime/wake?
   :seon.eval/home-requires

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
   ;; subagents section + breaker query them on a fresh cluster.
   :seon.agent.run/result
   :seon.agent.run/result-ref
   :seon.agent.run/closed-at

   ;; --- Unexpected-exit recovery anchor (the affected agents/runs/turns are
   ;; derived by joining this anchor's transaction, never copied here) ---
   :seon.runtime.recovery/id
   :seon.runtime.recovery/reason
   :seon.runtime.recovery/detail

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
   ;; lazily installed them on a fresh cluster.
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
   :seon.schema/form
   :seon.schema/created-at
   :seon.db.id/generator

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
;; Cluster database connection.
;;
;; Reads use shared immutable Konserve files; writes use the remote Datahike
;; writer over UDS. There is no local-write fallback: boot fails loudly when
;; the authoritative writer is unavailable.
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

(declare index-schemas)

(defn- generator-policy-facts
  "Persistable identity-generator facts from the current schema declarations."
  []
  (into []
        (comp
          (filter #(contains? % :seon.db.id/generator))
          (map #(select-keys % [:seon.schema/key :seon.db.id/generator])))
        (index-schemas)))

(defn- ^:async install-runtime-schema!
  "Install the complete runtime schema, plus optional identity-generator facts,
   as root through the boot process. Provenance genesis must already exist."
  [conn generator-facts?]
  (await
    (db/with-tx-context
      {:seon.db/user [:seon.agent/id "root"]
       :seon.db/process (db.process/lookup-ref :seon.db.process/boot)}
      (fn ^:async install! []
        (let [schema-env
              (await (db/transact! {:seon.db/conn conn
                                    :seon.db/tx-data (pod-full-schema)}))]
          (when (false? (:seon.db/ok? schema-env))
            (throw (ex-info "Runtime schema installation failed." schema-env))))
        (when generator-facts?
          (let [policy-env
                (await (db/transact! {:seon.db/conn conn
                                      :seon.db/tx-data
                                      (generator-policy-facts)}))]
            (when (false? (:seon.db/ok? policy-env))
              (throw (ex-info "Generator-policy installation failed."
                              policy-env)))))))))

(defn ^:async open-agent-conn!
  "Open a FRESH ISOLATED `:memory` conn carrying the pod's full
   bootstrap schema. Test/diagnostic surface ONLY — the pod itself
   boots through its local replica via [[open-database-connection!]].
   Isolated-by-construction: tests that build agents on this conn can
   never touch the cluster database."
  {:malli/schema [:=> [:cat] :any]}
  []
  (let [cfg {:store              {:backend :memory
                                  :id      (random-uuid)}
             :schema-flexibility :write
             :keep-history?      true}]
    (await (d/create-database cfg))
    (let [conn (await (d/connect (id/allocation-connect-config cfg)))]
      (id/assert-allocation-writer! conn)
      ;; Establish the minimal root/process boundary first. Every subsequent
      ;; schema/policy write is an ordinary attributed transaction, including
      ;; on restarts of an existing store.
      (await (db/ensure-provenance! {:seon.db/conn conn}))
      ;; Isolated diagnostic databases carry the same authoritative identity
      ;; policies as a cold-started cluster.
      (await (install-runtime-schema! conn true))
      conn)))

(defn ^:async open-database-connection!
  "Open the pod's local database replica and attach its transaction feed.

   Order is load-bearing:
     1. `ping!` — FAIL LOUD if the database writer is down (no local
        fallback, no dual backend).
     2. `ensure-database!` — idempotently open the authoritative database and
        return its writer-owned identity.
     3. `d/connect` — reads go local from here; writes dispatch to the
        remote writer, explicitly routed to this database.
     4. provenance genesis/migration — ensure the minimal root/process attrs
        and refs before any ordinary transaction can be submitted.
     5. schema transact — the full Malli-derived attr schema goes OVER
        the protocol to the JVM writer as root/boot; `:db/ident` upserts make
        repeated installation safe. Exact no-write reconciliation is a
        later boot-state step, not an implied property of upsert.
     6. feed attachment — foreign writers' txs fire this conn's native
        listeners (wake triggers + web UI SSE)."
  {:malli/schema [:=> [:cat] :any]}
  []
  (await (replica/ping!))
  (let [opened (await (replica/ensure-database!))
        conn (await
              (d/connect
               (replica/database-config
                {::replica/database-id (::db.protocol/database-id opened)})))]
    (id/assert-allocation-writer! conn)
    (log/info-console! "seon.client/open-database-connection!"
                       (str "database " replica/database-name
                            ": " replica/default-database-path
                            " (writer: " replica/default-request-socket-path ")"))
    (await (db/ensure-provenance! {:seon.db/conn conn}))
    (await (install-runtime-schema! conn false))
    (await (replica/attach! {::replica/conn conn}))
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
;;   - `topo-sort-nses` over persisted `:seon.ns/require-edges`
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
;;   - Declaration loading runs as the owning agent through the REPL process.
;;     Its runtime-only `:seon.eval/replay?` flag suppresses eval tee writes;
;;     the flag never enters transaction metadata.
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
   Derives each ns's required set from persisted
   `:seon.ns/require-edges` (captured at tee from the analyzer, NOT
   re-parsed here — `seon.eval/persisted-require-targets`), INTERSECTED
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
                        (seval/persisted-require-targets db ns-kw)
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

(defn ^:private schema-forms-in-db
  "Canonical `{schema-key form}` read from one immutable database value."
  [db]
  (into {}
        (map (fn [[k form]] [k (reader/read-string form)]))
        (db/query '[:find ?k ?form
                    :where
                    [?s :seon.schema/key ?k]
                    [?s :seon.schema/form ?form]]
                  db)))

(defn ^:private function-contracts-in-db
  "Canonical `{qualified-symbol function-form}` from one database value."
  [db]
  (into {}
        (map (fn [[sym form]]
               [(symbol sym) (reader/read-string form)]))
        (db/query '[:find ?sym ?form
                    :where
                    [?function :seon.fn/sym ?sym]
                    [?function :seon.fn/spec ?form]]
                  db)))

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
     2. `topo-sort-nses` over persisted `:seon.ns/require-edges`
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
     - Cold path in start-runtime!, before per-agent setup.
     - REPL probe via the same-pod-session test pattern — see
       research/resume-findings-2026-05-23.md §'Same-pod-session test'."
  {:malli/schema [:=> [:catn [::args [:map [::conn :any]
                                            [::compile-state :any]
                                            [::agent-id :string]]]]
                  :any]}
  [{::keys [conn compile-state agent-id]}]
  (db/with-tx-context
    {:seon.db/user       [:seon.agent/id agent-id]
     :seon.db/process    (db.process/lookup-ref :seon.db.process/repl)
     :seon.eval/replay?  true}
    (fn ^:async run-replay! []
      (let [db       @conn
            ;; Schema facts are data, not replayable code. Validate and
            ;; activate the complete immutable projection before loading any
            ;; stored namespace/function source.
            _        (schema/activate-projection!
                       (schema/build-projection
                         (schema-forms-in-db db)
                         (function-contracts-in-db db)))
            agents   (agent-ns-set db)
            ;; Reconstitute each agent ns ONCE (frozen db value). A BLANK
            ;; source = nothing to replay: a member-less sourceless
            ;; keyword-namespace STUB (C30). `index-schemas` mints a
            ;; `:seon.ns/name` row per NAMESPACED schema key as a
            ;; `:seon.schema/ns` backref (`:seon.fn`, `:seon.ns`,
            ;; `:seon.error.malli`, …); those are NOT compiled nses, so
            ;; `agent-ns-set` (which subtracts only `core-ns-set`) can't
            ;; drop them, and `reconstitute-ns-source` yields "" (no
            ;; `:seon.ns/source` and no fn/test members). Schema membership
            ;; never makes a namespace replayable.
            ;; Eval'ing "" is a harmless no-op — skipping it (computed, no
            ;; name list) keeps replay-n honest. A REAL agent ns always
            ;; reconstitutes non-blank (source or a fn/test member), so
            ;; nothing real is skipped.
            src-of   (into {}
                           (map (fn [k] [k (seval/reconstitute-ns-source db k)]))
                           agents)
            order    (->> (topo-sort-nses (agent-ns-requires db agents))
                          (filterv (fn [k] (not (str/blank? (get src-of k))))))
            !n-fail  (volatile! 0)]
        ;; Whole-namespace, dependency-ordered load (the spine).
        (doseq [ns-kw order]
          (let [r (try
                    (let [src (get src-of ns-kw)]
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
        (let [total (count order)]
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
;; Transaction ordering at cold start (in start-runtime!):
;;   1. seed-core!    — user entity + my.kb.shared singleton
;;   2. index-core!   — :seon.ns + :seon.fn + canonical schema rows from REAL runtime
;;                           introspection (var meta + source file-read)
;;
;; Each transaction carries root/boot provenance refs, so audit queries can
;; isolate boot-managed datoms from agent-produced ones.
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

   Pure fn. Caller transacts via `db/transact!` as root through the boot
   database process."
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
;; surface auto-widens — `core-vars` is the `public-fn-vars` macro output, so
;; a new public first-party defn is seeded the moment the build loads it.
;; ---------------------------------------------------------------------------

(def ^:private core-vars
  "Every var indexed into the corpus at boot: the compile-time vector of
   EVERY public first-party fn across the build's whole require closure
   (`seon.indexing/public-fn-vars` — owner directive 'just index
   everything': all functions in the cljs package become `:seon.fn` rows,
   specced or not). No hand-curated inclusion list — the macro supplies the
   complete vector; a new public fn is indexed the moment it loads. Each var's
   spec/doc/source is read by [[var->fn-row]]; an unspecced fn simply omits
   `:seon.fn/spec` (honestly unspecced). Macro output is already
   sym-unique, so no dedup pass is needed."
  (public-fn-vars))

;; Downstream extra-core vars (task #36 — SEON_EXTRA_SRC; spec:
;; docs/prds/agent-runtime/research/extra-src-research-2026-06-12.md §d).
;; A downstream consumer's entry ns (named by SEON_EXTRA_PRELOAD, loaded
;; via the :devtools :preloads slot bin/seon --config-merges in) registers
;; its specced surface here:
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
   `seon.*` (the core's) and `my.*` (the human's database-replayed
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
                    "database-replayed corpus; SEON_EXTRA_SRC code must live "
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
   from the build closure and downstream extension vars — the SAME sources
   of truth the boot indexer writes from, so they can never drift. Used by
   [[agent-ns-set]] as the DB-layer load discriminator: a `:seon.ns/name`
   row whose ns is in this set is a COMPILED core ns (already in the
   bundle from module-load; indexed for DISPLAY only) and is EXCLUDED
   from the load — only the agent-authored DB layer loads. Re-evaling a
   core row's source — e.g. `(defn ^:async transact! …)` — would shadow
   the real compiled fn, so core is never loaded; only agent-authored
   corpus (in `my.agent.<id>` / agent domain nses) loads.

   A fn (not a def) because downstream extension vars load at runtime. It is
   NOT tx-meta and NOT a hand-typed ns list. Three computed sources union:
   [[compiled-first-party-ns-strs]] (the BUILD-DERIVED closure —
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
        (concat core-vars @!extra-core-vars)))

(defn- read-src-file
  "Read a core source file given a var-meta `:file` (classpath-relative,
   e.g. \"seon/db.cljs\" or \"seon/agent_context_test.cljs\"). Sources live
   under the deps.edn `:cljs` source roots (src, test, guest-cljs/src —
   probed in that order), resolved via `seon.platform/artifact-path`:
   CWD-relative when the pod runs from the repo root (seon's own usage),
   or under SEON_RUNTIME_ROOT when a downstream pod runs from its own
   runtime root. When SEON_EXTRA_SRC is set (task #36 — a downstream's
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
   running namespace object, so the compile-time var scan keeps enumerating it.
   Distinct from a genuinely-unreadable file (`txt` nil), which is a real
   error. A ghost is skipped from the boot var set with a single :warn."
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
        ;; Reified require edges (M4 persisted facts) for the SCI-
        ;; renderable surface: full-source nses are exactly where an
        ;; agent-authored-sym render fn can live (my.* + downstream), so
        ;; their alias/refer facts must be datoms, not text. Extracted
        ;; ONCE here at INDEX time from the real file's (ns …) form —
        ;; write-time extraction, never a render-time re-parse. Stub
        ;; nses (compiled seon.* — never SCI-rendered) skip the edges.
        edges (when full? (analyzer-info/require-edges-from-source src))]
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
        ;; (e.g. `[:fn canvas/valid-hiccup?]`) needs sci — which the
        ;; pod doesn't bundle — and `m/schema` THROWS. One bad form
        ;; must degrade ONE row (spec omitted, loud :warn), never kill
        ;; boot + the whole context-test family. Registered forms are
        ;; pure data by platform law (see seon.render.canvas); this
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
                    "Skipped from the indexed function vars.")})
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
   caller via [[core-program-tx]], which drops rows already present on the
   conn so a later `start-runtime!` against the same store seeds nothing.
   Keeping THIS fn conn-free preserves its role as a pure tx-data builder (the
   shape the index-core-test guards rely on).

   Fns whose source can't be read are OMITTED, not stubbed — the corpus stays
   honest. Returns the tx-data vector; caller transacts as root/boot."
  {:malli/schema [:=> [:cat] :any]}
  []
  (let [now     (js/Date.)
        ;; Downstream extra-core vars join the indexed vars after the
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

(defn index-schemas
  "Tx-data for a `:seon.schema` row per REGISTERED schema — every key in
   `seon.schema/registered-schemas`, attr-level and request/response shapes
   included, not just the entity `:map` shapes. `:seon.schema/form` is the full,
   canonical registered Malli form, so the complete shape of every attr is one
   entity-read away for the agent. It is never display-truncated.

   Pure tx-data builder; the boot reconcile in [[core-program-tx]] protects
   keys already present on the conn, so an agent's own
   `(seon.schema/register! …)` tee row (whose :source is the replayable
   call form) is NEVER overwritten by the boot index."
  {:malli/schema [:=> [:cat] :any]}
  []
  (let [now (js/Date.)]
    (into []
          (keep (fn [[k v]]
                  (when (keyword? k)
                    (let [form v
                          properties
                          (schema.internal/attr-form-properties form)
                          generator-present?
                          (contains? properties :seon.db.id/generator)
                          form-string (schema/form-string k)]
                      (cond-> {:seon.schema/key        k
                               :seon.schema/form       form-string
                               :seon.schema/created-at now}
                        generator-present?
                        (assoc :seon.db.id/generator
                               (:seon.db.id/generator properties))
                        (namespace k)
                        (assoc :seon.schema/ns
                               {:seon.ns/name (keyword (namespace k))}))))))
          (schema/registered-schemas))))

(defn ^:async core-program-tx
  "Return the exact core-program transaction for the current source.

   [[index-core!]] and [[index-schemas]] are evaluated once to form one desired
   compiled program graph. Against `conn`, this function derives
   the complete atomic delta: add missing declarations, replace drifted
   declaration fields, retract omitted optional fields/components, and remove
   boot-authored declarations absent from the desired graph. A fresh store
   receives the complete graph; a converged restart returns `[]`.

   Querying the conn's CURRENT identity set and emitting only the gap means a
   re-index never re-transacts a core row against the populated store —
   removing the re-seed interaction that the Run-3 findings traced to a
   malformed `:seon.fn/ns` value.

   DRIFT-HEALING is uniform across every compiled row: a stored row re-emits
   whenever ANY freshly-derived field differs from what the store holds —
   `:seon.ns/source`, schema source/generator policy, and for `:seon.fn` rows
   the WHOLE derived set (source, spec, doc, arglists,
   private?). Identity upsert re-asserts changed datoms in place; an optional
   fn spec or schema generator policy that DISAPPEARED is explicitly retracted
   (upsert cannot remove a datom). Replacements and removals are guarded by
   the CURRENT `:source` datom's transaction: only a declaration whose current
   source was written by the boot process is managed. An
   agent-authored row (detect-and-tee, runner) with a core sym is NEVER
   clobbered by the boot index.

   A boot-created agent home namespace is domain data, not compiled-program
   data. Home names are derived from the current `:seon.agent/id` facts and
   excluded from removal. Process provenance says HOW a fact arrived; desired
   identities plus domain connections say WHICH population owns it.

   The namespace/function/schema desired populations must be non-empty before
   any removal is compiled. Tests belong to the agent-authored program graph,
   not the compiled boot snapshot. Legacy boot-authored test rows are removed;
   agent-authored test rows are preserved. Returns a Promise of tx-data."
  {:malli/schema [:=> [:catn [::conn :any]] :any]}
  [conn]
  (let [all       (concat (index-core!)
                          (index-schemas))
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
        no-generator :seon.db.id.generator/absent
        schema-fields
        (fn [row]
          {:seon.schema/form (:seon.schema/form row)
           :seon.db.id/generator
           (get row :seon.db.id/generator no-generator)})
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
                               [?f :seon.fn/sym ?sym]
                               [?f :seon.fn/source _ ?tx]
                               [?tx :seon.db/process ?process]
                               [?process :seon.db.process/id :seon.db.process/boot]]
                             db))
        core-schema-keys
        (into #{} (map first)
              (d/q '[:find ?k
                     :where
                     [?s :seon.schema/key ?k]
                     [?s :seon.schema/form _ ?tx]
                     [?tx :seon.db/process ?process]
                     [?process :seon.db.process/id :seon.db.process/boot]]
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
        ;; Schema rows dedup on canonical form AND persisted generator policy. A
        ;; partial database missing the policy fact re-emits even when form is
        ;; unchanged; removing a policy emits an explicit retract below because
        ;; identity upsert cannot remove a card-one datom. Agent-authored rows
        ;; are protected by transaction provenance, not by overloading the form.
        schema-forms
        (into {} (d/q '[:find ?k ?form
                        :where
                        [?s :seon.schema/key ?k]
                        [?s :seon.schema/form ?form]]
                      db))
        schema-generators
        (into {} (d/q '[:find ?k ?generator
                        :where
                        [?s :seon.schema/key ?k]
                        [?s :seon.db.id/generator ?generator]]
                      db))
        have-schs
        (into {}
              (map (fn [[k form]]
                     [k {:seon.schema/form form
                         :seon.db.id/generator
                         (get schema-generators k no-generator)}]))
              schema-forms)
        desired-identities
        (into #{}
              (keep (fn [row]
                      (some (fn [a]
                              (when (contains? row a) [a (get row a)]))
                            [:seon.ns/name :seon.fn/sym :seon.schema/key])))
              all)
        desired-values
        (reduce (fn [m [a v]] (update m a (fnil conj #{}) v))
                {} desired-identities)
        _ (doseq [a [:seon.ns/name :seon.fn/sym :seon.schema/key]]
            (when-not (seq (get desired-values a))
              (throw (ex-info
                       (str "core program snapshot has no " a
                            " declarations; refusing removal")
                       {:seon.client/missing-program-population a}))))
        kept      (vec (remove
                         (fn [row]
                           (or (when-some [stored (get have-fns (:seon.fn/sym row))]
                                 (or (= stored (fn-fields row))
                                     (not (contains? core-syms (:seon.fn/sym row)))))
                               (and (contains? row :seon.ns/name)
                                    (= (get have-nses (:seon.ns/name row))
                                       (:seon.ns/source row)))
                               (when-some [stored (get have-schs (:seon.schema/key row))]
                                 (or (= stored (schema-fields row))
                                     (not (contains? core-schema-keys
                                                     (:seon.schema/key row)))))))
                         all))
        ;; A drifted fn row whose FRESH derivation is unspecced while the
        ;; stored row carries a spec: identity upsert re-asserts the other
        ;; fields but can't REMOVE a datom — retract the stale spec
        ;; explicitly so PRESENT ⇒ specced stays honest.
        field-retracts
        (into []
              (keep
                (fn [row]
                  (if-some [sym (:seon.fn/sym row)]
                    (let [stored-spec (get-in have-fns [sym :seon.fn/spec])]
                      (when (and (not (contains? row :seon.fn/spec))
                                 (seq stored-spec))
                        [:db/retract [:seon.fn/sym sym]
                         :seon.fn/spec stored-spec]))
                    (when-some [schema-key (:seon.schema/key row)]
                      (let [stored-generator
                            (get-in have-schs
                                    [schema-key :seon.db.id/generator])]
                        (when (and (not= no-generator stored-generator)
                                   (not (contains? row
                                                   :seon.db.id/generator)))
                          [:db/retract [:seon.schema/key schema-key]
                           :seon.db.id/generator stored-generator]))))))
              kept)
        ;; `:seon.ns/require-edges` COMPONENT rows can't ride the plain
        ;; identity upsert: cardinality-many component maps have no
        ;; identity of their own, so a drift re-emit (changed
        ;; :seon.ns/source) would DUPLICATE the edge rows. Strip the
        ;; edges off the kept ns rows and route them through the ONE
        ;; diff mechanism (seon.eval/ns-require-edges-tx — retractEntity
        ;; the old components, assert the new set, [] when unchanged).
        ;; Diffed over ALL fresh ns rows (not just the kept/drifted
        ;; ones), so code changes reconcile the complete desired edge
        ;; set on every boot — ~a pull per full-source ns, [] once
        ;; converged.
        edge-tx   (into []
                        (mapcat (fn [row]
                                  (when-some [edges (and (map? row)
                                                         (:seon.ns/name row)
                                                         (:seon.ns/require-edges row))]
                                    (seval/ns-require-edges-tx
                                      db (:seon.ns/name row) (set edges)))))
                        all)
        kept      (mapv #(dissoc % :seon.ns/require-edges) kept)
        agent-home-names
        (into #{}
              (map (fn [id] (keyword (str (home/home-ns id)))))
              (d/q '[:find [?id ...] :where [?a :seon.agent/id ?id]] db))
        boot-rows
        (d/q '[:find ?e ?identity-attr ?ident ?source
               :where
               (or-join [?e ?identity-attr ?ident ?source ?tx]
                 (and [?e :seon.ns/name ?ident]
                      [?e :seon.ns/source ?source ?tx]
                      [(ground :seon.ns/name) ?identity-attr])
                 (and [?e :seon.fn/sym ?ident]
                      [?e :seon.fn/source ?source ?tx]
                      [(ground :seon.fn/sym) ?identity-attr])
                 (and [?e :seon.schema/key ?ident]
                      [?e :seon.schema/form ?source ?tx]
                      [(ground :seon.schema/key) ?identity-attr])
                 (and [?e :seon.test/sym ?ident]
                      [?e :seon.test/source ?source ?tx]
                      [(ground :seon.test/sym) ?identity-attr]))
               [?tx :seon.db/process ?process]
               [?process :seon.db.process/id :seon.db.process/boot]]
             db)
        stale-eids
        (into #{}
              (comp
                (filter
                  (fn [[_ identity-attr ident _source]]
                    (and (or (= :seon.test/sym identity-attr)
                             (seq (get desired-values identity-attr)))
                         (not (contains? desired-identities
                                         [identity-attr ident]))
                         (not (and (= :seon.ns/name identity-attr)
                                   (contains? agent-home-names ident)))
                         )))
                (map first))
              boot-rows)
        stale-entities
        (mapv (fn [e] [:db.fn/retractEntity e]) (sort stale-eids))]
    (-> kept
        (into edge-tx)
        (into field-retracts)
        (into stale-entities))))

(schema/register! ::llm-fn        'fn?)
(schema/register! ::compile-state :any)
(defn- rehost-agent-runtimes!
  "Reconstruct every nonterminated agent after a code reload.

   This is process-local work only: one shared compile-state, then
   [[seon.agent/resume!]] per database-derived id. It never runs cluster seed,
   program replay, global instrumentation, or identity allocation."
  []
  (if (db/attached?)
    (let [conn db/*conn*]
      (-> (repl/ensure-bootstrap!)
          (.then
           (fn ^:async rehost! [compile-state]
             (let [ids (agent/resumable-agent-ids {:seon.db/db @conn})]
               (doseq [id ids]
                 (await (agent/resume!
                         {:seon.agent/id id
                          :seon.agent.runtime/compile-state compile-state})))
               (log/info-console! "seon.client"
                                  "reload: agent runtimes rehosted"
                                  {:seon.client/reinstalled ids})
               ids)))
          (.catch
           (fn [err]
             (log/error-console! "seon.client"
                                 "reload: agent runtime rehost FAILED"
                                 err)))))
    (js/Promise.resolve [])))

(schema/register! ::seeded? [:= true])
(schema/register! ::boot-seed-request  [:map [:seon.db/conn :seon.db/conn]])
(schema/register! ::boot-seed-response [:map [::seeded? ::seeded?]])

(defn ^:async boot-seed!
  "THE core boot seed — one code path for reconciling a database's managed
   startup facts, shared by `start-runtime!` and isolated runtime setup so
   callers cannot drift. The agent's identity is NOT seeded — SOUL.md /
   AGENTS.md are read LIVE as context sections every render
   (`seon.agent.ctx/file-block`), so every prompt gets the same identity with
   no seed step.

   Steps, in boot order. TWO provenance layers:

   BOOT-MANAGED (process `:seon.db.process/boot`) — two transactions, each
   with its own tx so the startup sequence remains observable:
          :core-seed  — `seed-core!` (user entity +
                             my.kb.shared instruction singleton).
          :core-index — `core-program-tx` (`:seon.ns` /
                             `:seon.fn` / `:seon.schema` rows). This is the
                             compiled desired graph: drift is repaired and
                             absent boot-authored declarations are retracted.
                             Agent-authored declarations are never swept.

   DECLARATIVE DESIRED SET (origin `:config`) — the routes
   (`route/core-routes-tx`, curated by the manifest) + the skills corpus
   (`my.skills/seed-skills-tx-data`, curated by the manifest) are the ONE
   managed declarative population, synced through
   `seon.state/reconcile!` (scope `#{:config}`). reconcile UPSERTS each
   desired row by its own `:db.unique/identity` (`:seon.route/name` /
   `:my.skills/name`) AND RETRACTS any managed row absent from the desired
   set — so dropping a route from the manifest, or a skill from disk,
   removes the stale datom (it can no longer persist across boots). The boot
   populations above are outside this config scope.

   Pins the root `db/*conn*` to `conn` for the duration, restoring in
   `finally`. ENVELOPE CONTRACT
   (A4): `db/transact!` never rejects, so every step checks the
   envelope and THROWS (surface-errors-loudly) — a silent partial seed
   is far worse than a crashed boot."
  {:malli/schema [:=> [:cat ::boot-seed-request] ::boot-seed-response]}
  [{conn :seon.db/conn}]
  ;; The seed runs outside any inherited agent scope and explicitly selects
  ;; root plus the boot/config process for each transaction.
  (await
    (db/without-agent
      (fn ^:async seed-unscoped! []
        (let [prev-conn db/*conn*]
          (set! db/*conn* conn)
          (try
            (let [index-tx (await (core-program-tx conn))
                  ;; The OPTIONAL loadout manifest, read ONCE and threaded to
                  ;; the route + skills steps below. Nil means preserve the
                  ;; database's config-managed subset; a map means explicitly
                  ;; reconcile that subset.
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
              ;; APPEND-ONLY root/boot core: introspection that is
              ;; not a desired set, never retracted.
              (await
                (db/with-tx-context
                  {:seon.db/user [:seon.agent/id "root"]
                   :seon.db/process
                   (db.process/lookup-ref :seon.db.process/boot)}
                  (fn ^:async seed! []
                    (check! :core-seed
                            (await (db/transact!
                                     {:seon.db/conn conn
                                      :seon.db/tx-data (seed-core!)})))
                    ;; No soul seed: the agent's identity is read LIVE from
                    ;; SOUL.md / AGENTS.md as context sections every render
                    ;; (seon.agent.ctx/file-block), never seeded into the database.
                    (check! :core-index
                            (await (db/transact!
                                     {:seon.db/conn conn
                                      :seon.db/tx-data index-tx}))))))
              ;; DECLARATIVE DESIRED SET (origin :config): the routes
              ;; (`:seon.route/*`, identity `:seon.route/name`), the scanned
              ;; skills corpus (`:my.skills/*`, identity `:my.skills/name`),
              ;; AND the `:seon.config` SINGLETON (identity `:seon.config/id`,
              ;; every cluster-config knob as a datom — config-db-migration
              ;; 2026-07-10) are ONE managed population synced through
              ;; reconcile! — exact add/change/attribute-remove/entity-remove,
              ;; and NO transaction on a converged Nth boot. A route dropped
              ;; from the manifest / a skill removed from disk is RETRACTED.
              ;; The singleton rides the
              ;; SAME config-process scope: folding it INTO the desired set is
              ;; what keeps it retract-PROTECTED. Identity-attr scope prevents
              ;; that process from sweeping unrelated populations it authored.
              ;; reconcile! never rejects; its error-value is checked + thrown
              ;; (surface-errors-loudly).
              (when manifest
                (let [singleton (config/resolve-config-singleton manifest)
                      desired (-> (vec (config/resolve-routes
                                       (route/core-routes-tx)
                                       manifest))
                                (into (my.skills/seed-skills-tx-data
                                        (config/skills-dir manifest)))
                                (conj singleton))
                      recon   (await
                              (db/with-tx-context
                                {:seon.db/user [:seon.agent/id "root"]
                                 :seon.db/process
                                 (db.process/lookup-ref :seon.db.process/config)}
                                (fn ^:async reconcile-declarative! []
                                  (state/reconcile!
                                    {:seon.state/desired desired
                                     :seon.db/managed-scope
                                     #{:seon.db.process/config}
                                     :seon.db/managed-identity-attrs
                                     #{:seon.route/name
                                       :my.skills/name
                                       :seon.config/id}
                                     :seon.db/conn conn}))))]
                (when (false? (:seon.state/ok? recon))
                  (throw (ex-info
                           (str "boot seed reconcile (routes+skills+config) failed: "
                                (:seon.state/error recon))
                           {:seon.client/seed-step :core-declarative
                            :seon.state/error      (:seon.state/error recon)})))))
              {::seeded? true})
            (finally
              (set! db/*conn* prev-conn))))))))

(schema/register! ::start-runtime-request
  [:map [::llm-fn {:optional true} ::llm-fn]])

(defn ^:async start-runtime!
  "Cold-start the cluster process exactly once.

   The cold transition attaches the database, reconciles boot-managed facts,
   reconstructs the compiler/program graph, instruments the accepted graph,
   performs crash recovery, resumes every nonterminated durable agent, and
   starts shared HTTP/debug/ticker machinery. Agent birth is not a mode of this
   function; warm callers use [[seon.agent/start!]]. A repeated call in the
   same process is a cheap status read and never re-enters cold work."
  {:malli/schema [:=> [:cat ::start-runtime-request] :any]}
  [{::keys [llm-fn]}]
  (if (db/attached?)
    (let [conn db/*conn*
          ids (agent/resumable-agent-ids {:seon.db/db @conn})
          primary (or (first (remove #{"root"} ids)) (first ids) "root")
          _ (await (replica/attach! {::replica/conn conn}))
          {:seon.web/keys [port port-file]} (await (web.serve/start!))]
      {:seon.agent/id primary
       :seon.client/resumed-ids ids
       :seon.client/created-ids []
       :seon.web/port port
       :seon.web/port-file port-file})
    (let [conn (await (open-database-connection!))
          _ (set! db/*conn* conn)
          _ (db/assert-preconditions! {:seon.db/conn conn})
          ;; Bootstrap compilation can overlap the independent writer seed.
          compile-promise (repl/ensure-bootstrap!)]
      (await (boot-seed! {:seon.db/conn conn}))
      (let [recovered
            (await
              (db/with-tx-context
                {:seon.db/user [:seon.agent/id "root"]
                 :seon.db/process
                 (db.process/lookup-ref :seon.db.process/boot)}
                (fn [] (recovery/recover! {}))))]
        (when (false? (:seon.db/ok? recovered))
          (throw (ex-info "start-runtime!: crash recovery failed" recovered)))
        (when (::recovery/repaired? recovered)
          (log/info-console! "seon.client/start-runtime!"
                             (str "crash recovery: restored "
                                  (count (::recovery/agent-ids recovered))
                                  " agent(s) to idle")
                             recovered)))
      (let [root-home [:seon.ns/name (keyword (str (home/home-ns "root")))]
            root-ready-before?
            (string? (:seon.ns/source (db/entity {:seon.db/ref root-home})))
            ;; Provenance genesis necessarily creates a bare root lookup
            ;; target before normal attributed writes are legal. Complete that
            ;; reserved stub through the ordinary atomic birth compiler; an
            ;; already-born root is an exact no-op and keeps its edits.
            root-result
            (await
              (db/with-tx-context
                {:seon.db/user [:seon.agent/id "root"]
                 :seon.db/process
                 (db.process/lookup-ref :seon.db.process/boot)}
                (fn [] (agent/create! {:seon.agent/id "root"}))))
            _ (when (false? (:seon.db/ok? root-result))
                (throw (ex-info "start-runtime!: root birth failed" root-result)))
            initial-result
            (await
              (db/with-tx-context
                {:seon.db/user [:seon.agent/id "root"]
                 :seon.db/process
                 (db.process/lookup-ref :seon.db.process/boot)}
                (fn [] (agent/ensure-initial-agent! {}))))
            _ (when (false? (:seon.db/ok? initial-result))
                (throw (ex-info "start-runtime!: initial agent birth failed"
                                initial-result)))
            initial-id (when (::agent/initial-created? initial-result)
                         (:seon.agent/id initial-result))
            created-ids (cond-> []
                          (not root-ready-before?) (conj "root")
                          initial-id (conj initial-id))
            compile-state (await compile-promise)
            resumable-ids (agent/resumable-agent-ids {:seon.db/db @conn})
            all-ids (->> (db/query
                           {:seon.db/db @conn
                            :seon.db/query
                            '[:find [?id ...]
                              :where [?a :seon.agent/id ?id]]})
                         sort vec)
            primary (or initial-id
                        (first (remove #{"root"} resumable-ids))
                        (first resumable-ids)
                        (first all-ids)
                        "root")]
        (let [replay-stats
              (await (replay-program-graph!
                       {::conn conn
                        ::compile-state compile-state
                        ::agent-id primary}))
              _ (log/info-console! "seon.client/start-runtime!"
                                   (str "replay: " (pr-str replay-stats)))
              instrument-stats
              (instrument/instrument-projection!
                (schema/current-projection))
              _ (log/info-console! "seon.client/start-runtime!"
                                   (str "instrumentation: "
                                        ;; The complete result carries Malli's
                                        ;; per-function `::instrument/data` and
                                        ;; every accepted symbol so callers can
                                        ;; inspect the exact reconstruction.
                                        ;; Printing that payload made one cold
                                        ;; boot log hundreds of tokens of
                                        ;; redundant program-graph detail. Boot
                                        ;; status needs the counts and the few
                                        ;; rejected rows; the database remains
                                        ;; the detailed source of truth.
                                        (pr-str
                                          (instrumentation-summary
                                            instrument-stats))))
              results
              (let [!results (volatile! [])]
                (doseq [id resumable-ids]
                  (vswap! !results conj
                          (await
                            (agent/resume!
                              (cond->
                                {:seon.agent/id id
                                 :seon.agent.runtime/compile-state compile-state}
                                (fn? llm-fn)
                                (assoc :seon.agent.runtime/llm-fn llm-fn))))))
                @!results)
              _ (when-let [failed
                           (some #(when (false?
                                          (:seon.agent.runtime/resumed? %)) %)
                                 results)]
                  (throw (ex-info "start-runtime!: agent resume failed" failed)))
              {:seon.agent/keys [id ns]}
              (or (some #(when (= primary (:seon.agent/id %)) %) results)
                  (first results))
              {:seon.web/keys [port port-file]} (await (web.serve/start!))]
          (await (ai/sync!))
          (await (web.brand/sync!))
          (agent-loop/install-ticker!)
          (log/info-console! "seon.client" "runtime started"
                             {:resumed resumable-ids
                              :created created-ids
                              :port port
                              :port-file port-file})
          {:seon.agent/id id
           :seon.agent/ns ns
           :seon.client/resumed-ids resumable-ids
           :seon.client/created-ids created-ids
           :seon.web/port port
           :seon.web/port-file port-file})))))

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
  ;; cold-store web UI render, 2026-06-09). Must run before start-runtime!
  ;; opens the store.
  (log/quiet-library-logs!)
  (install-process-safety-net!)
  (log/info-console! "seon.client" "-main boot" {:boot-at (:boot-at @!state)})
  ;; Malli instrumentation is installed from the validated PROGRAM projection
  ;; inside `start-runtime!`, after the core is indexed. The DB is the complete,
  ;; ordering-independent source of every fn + spec; later transitions publish
  ;; only their exact dependency delta.
  ;; A-5: auto-boot the V0 agent + HTTP server unless SEON_NO_AUTO_BOOT.
  ;; Cheap default for dev iteration — browser hits the loopback port,
  ;; no REPL needed. Disable for a compiler-only/dev-eval process.
  (when-not (config/no-auto-boot?)
    (let [llm-fn   (ai.dispatch/llm-fn)
          provider (ai/provider)
          key-set? (not (identical? ai.dispatch/stub
                                    (ai.dispatch/adapter)))]
      (if key-set?
        (log/info-console! "seon.client"
                           (str "using " (name provider) " LLM (API key set)"))
        ;; LOUD, grep-able marker (namespaces-milestone rung-1 trap, 2026-07-10): a real provider
        ;; configured with its key unset silently drove a whole trial on the
        ;; stub. ERROR level + the SEON-STUB-LLM token so harnesses can
        ;; refuse to dispatch against an accidental stub; boot still
        ;; proceeds — deterministic tests rely on the stub deliberately.
        (log/error-console! "seon.client"
                            (str "SEON-STUB-LLM: using stub LLM — provider "
                                 (name provider) " is configured but its API "
                                 "key env is unset; every agent turn will get "
                                 "canned replies. Export the key and restart "
                                 "(SEON_CONFIG must ride the restart) if this "
                                 "pod is meant to do real work.")))
      (-> (start-runtime! {::llm-fn llm-fn})
          (.then (fn [{:seon.agent/keys [id ns]
                       :seon.client/keys [resumed-ids created-ids]
                       :seon.web/keys [port port-file]}]
                   (log/info-console! "seon.client" "auto-boot ready"
                                       {:agent id :ns (str ns)
                                        :resumed resumed-ids
                                       :created created-ids
                                       :url (str "http://127.0.0.1:" port
                                                 "/agent/"
                                                 (js/encodeURIComponent id))
                                       :port-file port-file})))
          (.catch (fn [err]
                    ;; FAIL LOUD (2.2e): the pod is useless without its
                    ;; agent + cluster conn, and a half-up pod that looks
                    ;; healthy is worse than a dead one. Most common
                    ;; cause: the database server is unavailable (the error says
                    ;; exactly that). No local database fallback, by design.
                    (log/error-console! "seon.client"
                                        "auto-boot FAILED — exiting (no local fallback)"
                                        err)
                    (.exit js/process 1))))))
  (log/info-console! "seon.client" "nREPL :7889 — (shadow.cljs.devtools.api/nrepl-select :client)")
  (start-heartbeat!))
