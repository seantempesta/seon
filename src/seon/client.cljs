(ns seon.client
  "CLJS cluster host entry point.

   Responsibility: attach and cold-start the cluster runtime via
   [[start-runtime!]]. Warm agent birth belongs to [[seon.agent/start!]].

   How to run it:

     ;; Terminal 1 — the watcher (compiles + writes nREPL port file)
     clj -M:cljs watch client

     ;; Terminal 2 — the Bun host
     bun out/client/main.js

     ;; Editor / MCP — read `.shadow-cljs/nrepl.port`, then
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
    ["node:path" :as npath]
    [cljs.reader :as reader]
    [clojure.set :as set]
    [clojure.string :as str]
    ;; malli.core/form round-trips a fn's `:malli/schema` to the stable
    ;; `:seon.fn/spec` string in index-core! (the runtime-introspection
    ;; core indexer — coherent-bootstrap-indexing Step 2).
    [malli.core :as m]
    ;; Instrumentation publishes the validated database-derived projection
    ;; once at boot; accepted eval/hot-reload transitions publish exact deltas.
    [seon.instrument :as instrument]
    [seon.launch :as launch]
    [seon.client.schema]
    ;; Pull in the agent's required namespaces at compile time so all
    ;; schemas are registered before start-runtime! runs.
    [seon.agent :as agent]
    [seon.agent.home :as home]
    [seon.agent.runtime :as agent-runtime]
    ;; Lifecycle functions (wait/complete/terminate) — host-bundled so the agent
    ;; home ns can `:refer` them; required here so the build includes the ns.
    [seon.agent.lifecycle]
    ;; The agent loop + wake trigger: the client boot path ARMS the wake
    ;; trigger (seon.agent does NOT, to stay acyclic).
    [seon.agent.loop :as agent-loop]
    ;; The run lifecycle — the bootstrap turn-0 opens a run for its turn.
    [seon.agent.run :as agent-run]
    ;; Cron-as-data — required so its `:seon.agent.schedule/*` register! calls
    ;; run before `agent-bootstrap-attrs` installs them, and so the ticker's
    ;; `fire-due-schedules!` is in the build.
    [seon.agent.schedule]
    [seon.agent.ctx]
    [seon.ai :as ai]
    [seon.ai.dispatch :as ai.dispatch]
    [seon.db :as db]
    [seon.db.branch :as db.branch]
    [seon.db.id :as id]
    [seon.db.internal :as db.internal]
    [seon.db.process :as db.process]
    [seon.derive :as derive]
    [seon.error :as error]
    [seon.execution.host :as execution.host]
    [seon.log :as log]
    ;; Render protocol — A-2. Required here so the build includes it.
    ;; Symbol-lookup for render slots lives in seon.eval/lookup-value
    ;; (walks goog-global with cljs.core/munge); no boot-time wire-up
    ;; needed.
    [seon.render]
    ;; Canvas render namespace — required so the build includes it.
    [seon.render.canvas]
    ;; Root's SYSTEM VIEW — the `/` dashboard = root's canvas content.
    ;; Required so the build includes it and `system-view`'s symbol resolves
    ;; when the execution child materializes the root canvas.
    [seon.render.system]
    [seon.runtime.admission :as admission]
    [seon.runtime.lifecycle :as runtime.lifecycle]
    ;; Routing-as-data — the `:seon.route/*` schema + declarative core routes.
    ;; Required here so schema registration and the config builder are loaded.
    [seon.route :as route]
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
    ;; system-wide instruction singleton (my.kb.shared). Required here so their
    ;; registrations are present in the compiled initialization program.
    [my.kb]
    [my.kb.shared]
    ;; Pull-reference corpus — the `:my.skills/*` schema + scanner used by the
    ;; one declarative config reconciliation. These rows are available on
    ;; demand and are not standing context blocks.
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
    [my.blob :as blob]
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
    ;; Absent means no config reconciliation; present is loaded once.
    [seon.config :as config]
    ;; Holistic declarative-state reconciliation routes config, routes, and
    ;; skills through `seon.state/reconcile!`, including stale retractions.
    [seon.state :as state]
    ;; Local-machine capability surface — A-9. Required so the agent
    ;; can call (seon.agent.fs/read-file ...) from
    ;; bootstrap-CLJS eval.
    [seon.agent.fs]
    ;; Content search over allowed files — the exemplar npm-package
    ;; wrapper (@vscode/ripgrep). Required so the agent can call
    ;; (seon.agent.search/grep ...) from bootstrap-CLJS eval and so the
    ;; core-vars seed below can index it.
    [seon.agent.search]
    ;; Run real commands / Python — argv through the shared Bun subprocess
    ;; owner over the fs cwd gate,
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
    ;; One multiplexed authority session owns reads, writes, and interests.
    [seon.db.protocol :as db.protocol]
    [seon.db.restore :as db.restore]
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

(schema/register! ::runtime-phase
  [:enum :seon.client.runtime/starting
   :seon.client.runtime/running
   :seon.client.runtime/quiescing
   :seon.client.runtime/quiesced
   :seon.client.runtime/stopping
   :seon.client.runtime/cleanup-required])
(schema/register! ::blob-storage-view :my.blob/storage-view)
(schema/register! ::restore-completion-result ::db.restore/record-success)

(def default-launch-capability
  {::autonomous? true})

(defonce !state
  (atom {:boot-at      (.toISOString (js/Date.))
         :reload-count 0
         :heartbeat-id nil}))

(defn launch-capability
  "The closed process-local capability retained across hot reload."
  {:malli/schema [:=> [:cat] ::launch-capability]}
  []
  (or (::launch-capability @!state) default-launch-capability))

(defn autonomous-runtime?
  "True when this process may perform autonomous runtime effects."
  {:malli/schema [:=> [:cat] :boolean]}
  []
  (true? (::autonomous? (launch-capability))))

(defn- runtime-phase
  "Return the retained process-local launch phase, when present."
  []
  (::runtime-phase @!state))

(defn- claim-launch-capability!
  [capability]
  (let [retained (::launch-capability @!state)]
    (if (db/attached?)
      (cond
        (nil? retained)
        (throw
         (ex-info "The attached runtime has no retained launch capability."
                  {::launch-capability capability}))

        (not= capability retained)
        (throw
         (ex-info "The attached runtime has a different launch capability."
                  {::launch-capability capability
                   ::active-launch-capability retained})))
      (swap! !state assoc ::launch-capability capability))
    capability))

(defn- claim-blob-storage-view!
  [view]
  (when-not (schema/valid-candidate-value? ::blob-storage-view view)
    (throw
     (ex-info "The process cannot claim an invalid blob storage view."
              {::blob-storage-view view
               :seon.error/kind :core-bug})))
  (let [retained (::blob-storage-view @!state)]
    (cond
      (nil? retained)
      (do
        (reset! blob/!storage-view view)
        (swap! !state assoc ::blob-storage-view view)
        view)

      (= retained view)
      view

      :else
      (throw
       (ex-info "The process already claimed a different blob storage view."
                {::blob-storage-view view
                 ::active-blob-storage-view retained
                 :seon.error/kind :core-bug})))))

(defn runtime-advertisement
  "Project this pod's MCP addressable agents from its database interest.

   Runtime and writer process settings are immutable launch configuration. Agent
   membership is the last accepted result of the same nonterminated,
   born-agent query used by cold resume. One addressed database interest keeps
   that ordinary projection current without making this synchronous MCP probe
   perform I/O.
   Before the database session opens, the pod still advertises its runtime and
   writer owner with no agent ids so an ordinary cluster-pinned REPL can
   connect during boot."
  {:malli/schema
   [:=> [:cat]
    [:map
     [:seon.dev.runtime-id/cluster :string]
     [:seon.dev.runtime-id/ids [:vector :string]]
     [::launch/writer-cluster ::launch/writer-cluster]
     [::launch/writer-repl-port-file ::launch/writer-repl-port-file]]]}
  []
  (let [descriptor launch/process-launch-descriptor
        runtime (::launch/runtime descriptor)
        writer-owner (::launch/writer-owner descriptor)]
    (merge
     (runtime-id/advertisement
      #:seon.dev.runtime-id
       {:cluster (::launch/runtime-cluster runtime)
        :ids (if (db/attached?)
               (or (::resumable-agent-ids @!state) [])
               [])})
     (select-keys writer-owner
                  [::launch/writer-cluster
                   ::launch/writer-repl-port-file]))))

(defn- refresh-runtime-advertisement!
  [owner database]
  (if-not (identical? owner (::advertisement-owner @!state))
    (js/Promise.resolve false)
    (let [refresh
          (-> (agent/resumable-agent-ids! {::db/db database})
              (.then
               (fn [ids]
                 (when (:seon.error/message ids)
                   (throw
                    (ex-info "Runtime advertisement projection failed." ids)))
                 (if-not (identical? owner
                                     (::advertisement-owner @!state))
                   false
                   (-> (db/db)
                       (.then
                        (fn [latest]
                          (when (:seon.error/message latest)
                            (throw
                             (ex-info
                              "Runtime advertisement database read failed."
                              latest)))
                          (if (and
                               (identical?
                                owner (::advertisement-owner @!state))
                               (= database latest))
                            (do
                              (swap! !state assoc ::resumable-agent-ids ids)
                              true)
                            false))))))))]
      (swap! !state
             (fn [state]
               (if (identical? owner (::advertisement-owner state))
                 (assoc state ::advertisement-refresh refresh)
                 state)))
      refresh)))

(def ^:private runtime-agent-datom-patterns
  [{::db/a :seon.agent/id}
   {::db/a :seon.agent/terminated-at}
   {::db/a :seon.agent.runtime/wake?}
   {::db/a :seon.agent.run/paused-at}])

(def ^:private agent-ids-for-entities-query
  '[:find [?id ...]
    :in $ [?entity ...]
    :where [?entity :seon.agent/id ?id]])

(def ^:private agent-ids-for-runs-query
  '[:find [?id ...]
    :in $ [?run ...]
    :where
    [?agent :seon.agent/run ?run]
    [?agent :seon.agent/id ?id]])

(defn- event-entity-ids
  [tx-data attributes]
  (into []
        (comp (filter (fn [[_ attribute]] (contains? attributes attribute)))
              (map first)
              (distinct))
        tx-data))

(defn- ^:async reconcile-agent-runtimes!
  "Apply committed agent lifecycle facts to the pod's process-local runtimes."
  [{database :db-after tx-data :tx-data}]
  (when database
    (let [agent-entities
          (event-entity-ids
           tx-data
           #{:seon.agent/id :seon.agent/terminated-at
             :seon.agent.runtime/wake?})
          run-entities
          (event-entity-ids tx-data #{:seon.agent.run/paused-at})
          direct-ids
          (if (seq agent-entities)
            (await
             (db/query
              {::db/db database
               ::db/query agent-ids-for-entities-query
               ::db/args [agent-entities]}))
            [])
          run-ids
          (if (seq run-entities)
            (await
             (db/query
              {::db/db database
               ::db/query agent-ids-for-runs-query
               ::db/args [run-entities]}))
            [])
          failure
          (some #(when (:seon.error/message %) %) [direct-ids run-ids])]
      (if failure
        (throw (ex-info "Agent runtime projection failed." failure))
        (let [ids (into [] (comp cat (distinct)) [direct-ids run-ids])]
          (doseq [id ids]
            (await (agent-runtime/resume! {:seon.agent/id id})))
          ids)))))

(defn- runtime-advertisement-event!
  [owner event]
  (when-let [database (:db-after event)]
    (-> (js/Promise.all
         #js [(refresh-runtime-advertisement! owner database)
              (reconcile-agent-runtimes! event)])
        (.then (fn [results] (aget results 0)))
        (.catch
         (fn [error]
           (log/error-console! "seon.client"
                               "runtime agent refresh failed" error))))))

(defn- ^:async attach-runtime-advertisement-owner!
  [owner]
  (if-not (identical? owner (::advertisement-owner @!state))
    false
    (let [listening-key (atom nil)]
      (try
        (let [interest-key
              (await
               (db/listen!
                {::db/key ::runtime-advertisement
                 ::db/datom-patterns runtime-agent-datom-patterns
                 ::db/handler #(runtime-advertisement-event! owner %)}))]
          (when (:seon.error/message interest-key)
            (throw (ex-info "Runtime advertisement interest failed."
                            interest-key)))
          (reset! listening-key interest-key)
          (if-not (identical? owner (::advertisement-owner @!state))
            (do (await (db/unlisten! interest-key)) false)
            (do
              (swap! !state assoc ::advertisement-interest-key interest-key)
              (let [database (await (db/db))]
                (when (:seon.error/message database)
                  (throw (ex-info "Runtime advertisement database read failed."
                                  database)))
                ;; An event may win the race while the latest database read is
                ;; pending. In that case its db-after already owns the refresh.
                (if-let [refresh (::advertisement-refresh @!state)]
                  (await refresh)
                  (await (refresh-runtime-advertisement! owner database))))
              (swap! !state dissoc ::advertisement-attaching)
              interest-key)))
        (catch :default exception
          (when @listening-key
            (await (db/unlisten! @listening-key)))
          (swap! !state
                 (fn [state]
                   (if (identical? owner (::advertisement-owner state))
                     (dissoc state
                             ::advertisement-owner
                             ::advertisement-interest-key
                             ::advertisement-refresh
                             ::advertisement-attaching
                             ::resumable-agent-ids)
                     state)))
          (throw exception))))))

(defn- attach-runtime-advertisement!
  []
  (let [{::keys [advertisement-interest-key advertisement-attaching]} @!state]
    (cond
      advertisement-interest-key (js/Promise.resolve advertisement-interest-key)
      advertisement-attaching advertisement-attaching
      :else
      (let [owner (js-obj)
            attaching
            (-> (js/Promise.resolve nil)
                (.then (fn [_] (attach-runtime-advertisement-owner! owner))))]
        (swap! !state assoc
               ::advertisement-owner owner
               ::advertisement-attaching attaching)
        attaching))))

(defn- ^:async acquire-resumable-agent-ids!
  []
  (await (attach-runtime-advertisement!))
  (or (::resumable-agent-ids @!state) []))

(defn- recovery-result!
  [result]
  (if (:seon.error/message result)
    (throw (ex-info "start-runtime!: crash recovery failed" result))
    result))

(defn- ^:async detach-runtime-advertisement!
  []
  (let [interest-key (::advertisement-interest-key @!state)]
    (swap! !state dissoc
           ::advertisement-owner
           ::advertisement-interest-key
           ::advertisement-refresh
           ::advertisement-attaching
           ::resumable-agent-ids)
    (if interest-key
      (await (db/unlisten! interest-key))
      true)))

(defn start-heartbeat!
  "Holds the Bun event loop open with a minute-cadence heartbeat. The
   cluster host will keep the loop alive via pending agent-loop work;
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

(declare open-database-session! rehost-agent-runtimes!)

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
  ;; Publication and rehosting belong to shadow-build-notify!, which sees
  ;; build-start/failure/complete even when this namespace itself did not
  ;; reload. An after-load hook cannot be the admission owner.
  nil)

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

(defn- ^:async acquire-configuration!
  "Acquire and decode the retained config singleton at one database value."
  []
  (let [database (await (db/db))]
    (when (:seon.error/message database)
      (throw (ex-info "Config database acquisition failed." database)))
    (let [stored
          (await
           (db/entity database
                      [:seon.config/id config/cluster-config-id]))]
      (when (:seon.error/message stored)
        (throw (ex-info "Config singleton acquisition failed." stored)))
      (when-not stored
        (throw
         (ex-info "The attached database has no config singleton."
                  {:seon.config/id config/cluster-config-id
                   :seon.error/kind :core-bug})))
      (db/decode-edn-values stored))))

(defn shadow-build-notify!
  "Close admission on build start and publish only a complete build."
  {:malli/schema [:=> [:catn [::message :any]] :boolean]}
  [message]
  (when (and (= :seon.client.runtime/running (runtime-phase))
             (nil? (::launch/restore-startup
                    launch/process-launch-descriptor)))
    (case (:type message)
      :build-start
      (admission/begin-publication!)

      :build-failure
      (-> (acquire-configuration!)
          (.then
           (fn [configuration]
             (error/with-configuration
              configuration
              #(admission/mark-unavailable!
                {:seon.error/raw
                 (ex-info
                  "Shadow build failed while runtime publication was closed"
                  {:seon.client/build-message message})
                 ::admission/reason "Shadow build failed"}))))
          (.catch
           (fn [configuration-error]
             (admission/mark-unavailable!
              {:seon.error/raw
               (ex-info
                "Shadow failure could not acquire its database configuration"
                {:seon.client/build-message message}
                configuration-error)
               ::admission/reason
               "Shadow build and configuration acquisition failed"}))))

      :build-complete
      (-> (acquire-configuration!)
          (.then
           (fn [configuration]
             (error/with-configuration
              configuration
              #(-> (open-database-session!
                    {::initialize? true
                     ::configuration configuration})
                   (.then (fn [_] (admission/publish-committed!)))
                   (.then
                    (fn ^:async publish! [publication]
                      (log/info-console!
                       "seon.client"
                       (str "reload: committed publication "
                            (pr-str
                             (instrumentation-summary
                              (::admission/instrumentation publication)))))
                      (when (::admission/published? publication)
                        ;; Reinstall listeners/ticker only after the reloaded
                        ;; program is one verified generation. Web feeds re-arm
                        ;; lazily.
                        (if (autonomous-runtime?)
                          (do
                            (await (rehost-agent-runtimes!))
                            (agent-loop/install-ticker! configuration))
                          (do
                            (agent-loop/uninstall-ticker!)
                            (agent-runtime/unhost-all!)))
                        (start-heartbeat!))))
                   (.catch
                    (fn [publication-error]
                      (admission/mark-unavailable!
                       {:seon.error/raw publication-error
                        ::admission/reason
                        "Committed reload initialization or publication failed"})))))))
          (.catch
           (fn [configuration-error]
             (admission/mark-unavailable!
              {:seon.error/raw configuration-error
               ::admission/reason
               "Reload configuration acquisition failed"}))))

      nil))
  true)

;; ---------------------------------------------------------------------------
;; Cluster runtime
;;
;; start-runtime! opens the authority session, installs the database schema,
;; and resumes the durable agents.
;; Repeated calls consult that one open database session and return status without
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
  (into
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
   ;; seeds (the database-owned run policy, with an optional agent override)
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
   ;; Transcript eval rows may opt out of the ordinary authored-content cap.
   ;; This is read during the first prompt, so it must exist before lazy
   ;; namespace loading can install the rest of seon.render's schema.
   :seon.render/full?

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
   :seon.agent.turn/rendered-tx
   :seon.agent.turn/evals
   :seon.agent.turn/llm-attempts
   :seon.ai.attempt/ordinal
   :seon.ai.attempt/provider
   :seon.ai.attempt/adapter
   :seon.ai.attempt/requested-model
   :seon.ai.attempt/temperature
   :seon.ai.attempt/max-tokens
   :seon.ai.attempt/thinking
   :seon.ai.attempt/endpoint
   :seon.ai.attempt/adapter-timeout-ms
   :seon.ai.attempt/outer-timeout-ms
   :seon.ai.attempt/stream?
   :seon.ai.attempt/extra-body-digest
   :seon.ai.attempt/dg-backend
   :seon.ai.attempt/api-key-env
   :seon.ai.attempt/credential-class
   :seon.ai.attempt/outcome
   :seon.ai.attempt/error-status
   :seon.ai.attempt/response-model
   :seon.ai.attempt/system-fingerprint
   :seon.ai.attempt/request-id
   :seon.ai.attempt/evidence-error

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
   :seon.fn/agent-facing?
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
   ;; instruction singleton (empty entity admitted during database open;
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
   :seon.agent.testrun/message]
   db.restore/completion-attrs))

;; ---------------------------------------------------------------------------
;; Cluster database authority session.
;; ---------------------------------------------------------------------------

(declare database-initialization)

(schema/register! ::initialize? :boolean)
(schema/register! ::configuration :seon.config/singleton)
(schema/register! ::open-database-session-request
  [:map {:closed true}
   [::initialize? ::initialize?]
   [::configuration {:optional true} ::configuration]])

(defn- session-selection
  "Project one validated launch descriptor into a database session request."
  [descriptor initialization]
  (let [writer-owner (::launch/writer-owner descriptor)
        database (::launch/database descriptor)
        branch-head (::db.branch/head database)]
    (cond-> {::db/socket-path (::launch/request-socket-path writer-owner)
             ::db/database-name (::db.protocol/database-name database)
             ::db/backend (::db.protocol/backend database)}
      (::db.protocol/database-path database)
      (assoc ::db/database-path (::db.protocol/database-path database))
      initialization
      (assoc ::db/initialization initialization)
      branch-head
      (assoc ::db/connection-id (db.branch/connection-id branch-head)))))

(defn ^:async open-database-session!
  "Open this process's one persistent database authority session.

   When writes are enabled, the same open request carries the complete compiled
   program and required initial entities. The authority owns provenance genesis,
   native schema declaration, exact program reconciliation, and publication of
   the admitted immutable database value.

   The result is ordinary namespaced data. Bun retains no local Datahike
   connection, index, replay cursor, or publisher socket."
  {:malli/schema
   [:=> [:cat ::open-database-session-request] :any]}
  [{::keys [initialize? configuration]}]
  (let [descriptor launch/process-launch-descriptor
        writer-owner (::launch/writer-owner descriptor)
        database (::launch/database descriptor)
        initialization
        (when initialize?
          (when-not configuration
            (throw
             (ex-info "Database initialization requires explicit config data."
                      {:seon.error/kind :core-bug})))
          (database-initialization descriptor configuration))
        opened (await (db/open-session!
                       (session-selection descriptor initialization)))]
    (log/info-console! "seon.client/open-database-session!"
                       (str "database "
                            (::db.protocol/database-name database)
                            ": "
                            (::db.protocol/database-path database)
                            " (writer: "
                            (::launch/request-socket-path writer-owner) ")"))
    opened))

;; ---------------------------------------------------------------------------
;; Required initial database entities.
;; ---------------------------------------------------------------------------

(defn- initial-data
  "Return config, user, and the empty system-wide instruction singleton.

   The database authority admits these identities with the compiled program
   before publishing the first usable database value.

   The EMPTY system-wide instruction
   singleton (`my.kb.shared/seed-tx-data`); agents and the user APPEND
   rows at runtime, read back via `(my.kb.shared/instructions)` in the
   bootstrap turn.

   The user row is the ONE `:seon.user/id` entity every
   `:seon.agent.message/from`/`to` user-ref resolves to (identity upsert,
   idempotent — same pattern as agent entities; one human for now).
   The instruction singleton identity-upserts on `:my.kb.shared/id`
   carrying NO rows — re-running asserts zero new datoms and never
   clobbers runtime appends.

  Reopening the database never clobbers runtime appends."
  [configuration]
  (db.internal/encode-edn-slot-values
   (into [configuration {:seon.user/id "user"}]
         (my.kb.shared/seed-tx-data))))

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
;; FULL-SOURCE ns-rows in [[index-core!]]. Empty in builds without a
;; downstream preload.
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
   compiled ns gets an [[index-core!]] ns-row BY CONSTRUCTION — whether or not
   any fn-row names it (a register!-only root has no indexed var but is
   still compiled, and its name-set membership is now a build fact, not
   a literal)."
  (into #{} (first-party-ns-strs)))

(defn- load-program-sources
  "Load and verify the admitted build's resource-name to source-string map."
  []
  (let [path (seon.platform/env-val "SEON_PROGRAM_SOURCE_PATH")
        expected (seon.platform/env-val "SEON_PROGRAM_SOURCE_DIGEST")]
    (when-not (and path expected (re-matches #"[0-9a-f]{64}" expected))
      (throw
       (ex-info "The admitted program-source artifact identity is absent."
                {:seon.dev.artifact/program-source-path path
                 :seon.dev.artifact/program-source-digest expected})))
    (let [text (.readFileSync (js/require "fs") path "utf8")
          actual (-> (js/require "crypto")
                     (.createHash "sha256")
                     (.update text "utf8")
                     (.digest "hex"))
          value (reader/read-string text)
          sources (:seon.dev.artifact/program-sources value)]
      (when-not (= expected actual)
        (throw
         (ex-info "The admitted program-source artifact digest changed."
                  {:seon.dev.artifact/program-source-path path
                   :seon.dev.artifact/expected-digest expected
                   :seon.dev.artifact/actual-digest actual})))
      (when-not (and (map? sources)
                     (every? (fn [[resource-name source]]
                               (and (string? resource-name) (string? source)))
                             sources))
        (throw
         (ex-info "The admitted program-source artifact value is invalid."
                  {:seon.dev.artifact/program-source-path path})))
      sources)))

(defonce ^:private program-sources
  (delay (load-program-sources)))

(defn- read-src-file
  "Return source by the compiler's classpath-relative resource name."
  [resource-name]
  (get @program-sources resource-name))

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
   `.cljc` are checked so portable `.cljc` nses (e.g. `seon.schema`) resolve
   to their real compiled resource, not the stub."
  [ns-sym-str]
  (let [base (-> ns-sym-str
                 (str/replace "." "/")
                 (str/replace "-" "_"))]
    [(str base ".cljs") (str base ".cljc")]))

(defn- read-ns-source
  "Return full source for the first compiled namespace resource candidate."
  [ns-sym-str]
  (some read-src-file (ns-file-paths ns-sym-str)))

(defn- ns-row
  "Build the `:seon.ns` row for an owning ns name string.

   FULL-SOURCE nses (`seon.agent.ctx.namespaces/full-source-ns?` — all `my.*`, test
   siblings included) carry the REAL FULL FILE TEXT as
   `:seon.ns/source`: the boot indexer is the one program-source reader; the
   `:namespaces` context section (and anything else downstream) renders
   that attr from the graph, never re-reading files. A
   full-source ns whose file can't be read falls back to the stub and
   logs fail-loud — the corpus stays honest.

   All OTHER core nses keep the minimal `(ns x)` stub — the
   `:namespaces` section no longer renders these (only the curated full
   set is shown); the stub keeps the `:seon.ns/name` row + lookup-ref
   target for indexed members and the on-demand `render-namespace` path,
   and keeps the no-replay invariant trivially cheap to reason about."
  [configuration extra-sources ns-sym-str]
  (let [stub  (str "(ns " ns-sym-str ")")
        ;; Extra-core nses (downstream SEON_EXTRA_SRC code) are
        ;; full-source by rule, like my.* — closes the render-as-stubs
        ;; gap for the extra root.
        full? (or (nss/full-source-ns? configuration ns-sym-str)
                  (contains? (extra-core-ns-strs) ns-sym-str)
                  (contains? extra-sources ns-sym-str))
        src   (if full?
                (or (get extra-sources ns-sym-str)
                    (read-ns-source ns-sym-str)
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
   either FIRST directly inside the defn list (paren-depth 1, brace-depth 0 —
   single-arity) or as the FIRST element of each list directly inside the defn
   (paren-depth 2 — each `([args] body)` arity of a multi-arity defn; the
   `fresh?` flag tracks \"just entered a depth-2 list, nothing but whitespace
   since\", so vectors elsewhere in arity bodies are never captured). This
   skips `{:malli/schema [...]}` metadata maps (brace-depth > 0). Candidate
   vectors retain their parenthesis depth until the scan finishes: the first
   depth-1 vector makes the definition single-arity and excludes later
   vector-valued body data; otherwise all depth-2 arity vectors survive.
   Wraps the selected vectors in parens. Tracks string, escape, `\\(`
   char-literal, and `;`-to-EOL comment state. Returns \"()\" if none found
   (caller treats that as no arglists)."
  [src]
  (let [n (count src)]
    (loop [i 0 pdepth 0 bdepth 0 in-str? false esc? false fresh? false vecs []]
      (if (>= i n)
        (let [single (some (fn [[depth source]]
                             (when (= 1 depth) source))
                           vecs)
              selected (if single [single] (map second vecs))]
          (str "(" (str/join " " selected) ")"))
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
                     (conj vecs [pdepth (subs src i (inc vend))])))
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
        (true? (:seon.fn/agent-facing? m))
        (assoc :seon.fn/agent-facing? true)
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
;; SEON_EXTRA_SRC is set we select its compiled `*.cljs` resources from the
;; admitted program-source map, index every ns
;; (full-source row) and every public `(defn …)`/`(defn- …)` (a `:seon.fn`
;; row, specced AND unspecced). Scoped to the extra root ONLY — seon's own
;; core stays slim (var-derived, specced-only). The reserved-prefix guard
;; (`seon.*`/`my.*`) still applies via the registered-var path; scanned nses
;; that hit a reserved prefix are dropped here (a downstream root never owns
;; them, and a stray match must not forge a core row).

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

(defn- extra-src-ns->source
  "Map of downstream namespace names to admitted compiled source strings."
  []
  (if (config/extra-src)
    (reduce-kv
     (fn [sources resource-name source]
       (let [ns-name (when (str/ends-with? resource-name ".cljs")
                       (ns-name-from-source source))]
         (if (and ns-name (empty? (reserved-extra-nses [ns-name])))
           (assoc sources ns-name source)
           sources)))
     {}
     @program-sources)
    {}))

(defn- extra-fn-rows
  "`:seon.fn` rows for EVERY public `(defn …)`/`(defn- …)` across the
   downstream SEON_EXTRA_SRC surface — specced AND unspecced. The whole-
   surface counterpart to the specced-only var-derived rows: this is what
   makes an unspecced helper (`acme.helpers/format-count`) and an
   unspecced-only ns's fns (`acme.notes/*`) appear as indexed members in the
   namespace render. `now` is the shared `:seon.fn/created-at` instant.

   Each ns's already-acquired source is parsed by [[defn-rows-from-source]].
   Specced downstream fns ALSO get a var-derived row (with the real
   `:seon.fn/spec`) — [[index-core!]] dedups by sym, keeping the var-derived
   row in front so the spec is preserved."
  [extra-sources now]
  (into []
        (mapcat (fn [[ns-str source]]
                  (defn-rows-from-source ns-str source now)))
        extra-sources))

(defn- warn-if-extra-src-unregistered!
  "Observability for an incomplete consumer package registration.

   When `SEON_EXTRA_SRC` is set but `extra-core-vars*` is empty, the
   consumer wired the source root onto the classpath but their
   SEON_EXTRA_PRELOAD entry ns never ran the `(reset! !extra-core-vars …)` —
   so source-derived rows still index but exact runtime var metadata and Malli
   specs do not. Emit one actionable warning naming SEON_EXTRA_SRC and the
   registration form. Observability only; does not change indexing."
  [extra]
  (when-let [src (config/extra-src)]
    (when (empty? extra)
      (log/warn!
        {:seon.log/source  ::index-core!
         :seon.log/message
         (str "SEON_EXTRA_SRC=" src " is set but NO extra-core vars are "
              "registered — source-derived rows will index, but exact runtime "
              "var metadata and Malli specs will be absent. "
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
   introspection over `core-vars` (program-source lookup at var-meta `:file`,
   then extraction at `:line`
   + var meta for spec/doc). Replaces the old curated `seed-core-fns!`.

   Per owning ns, emits a `:seon.ns/name` + `:seon.ns/source` row (via
   [[ns-row]]) so the `[:seon.ns/name <kw>]` lookup-ref on `:seon.fn/ns`
   resolves. EXEMPLAR nses (context-focus-redesign root set) carry the
   REAL FULL FILE TEXT; all other core nses keep the minimal `(ns x)` stub.

   Always emits the FULL core row set — a function of `core-vars`
   + the registered `!extra-core-vars` + the admitted program sources,
   independent of any conn. Re-seeding the same rows on a
   later boot is idempotent at the DB layer: every row upserts on its identity
   attr (`:seon.ns/name` / `:seon.fn/sym`). The lookup-ref `[:seon.ns/name <kw>]`
   is the only ref shape ever emitted for `:seon.fn/ns` (a single
   `:seon.db/ref`); it is never a bare keyword.

   The authority reconciles this complete desired population against one
   immutable database value during session open. Keeping this function
   connection-free preserves one pure compiled-program builder.

   Fns whose source can't be read are OMITTED, not stubbed — the corpus stays
   honest. Returns the tx-data vector; caller transacts as root/boot."
  {:malli/schema [:=> [:cat :seon.config/singleton] :any]}
  [configuration]
  (let [now           (js/Date.)
        extra-sources (extra-src-ns->source)
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
                       (extra-fn-rows extra-sources now))
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
        ns-syms (into (into compiled-first-party-ns-strs (keys extra-sources))
                      (map #(first (str/split (:seon.fn/sym %) #"/" 2)))
                      fn-rows)
        ns-rows (map #(ns-row configuration extra-sources %) (sort ns-syms))]
    (vec (concat ns-rows fn-rows))))

(defn index-schemas
  "Tx-data for a `:seon.schema` row per REGISTERED schema — every key in
   `seon.schema/registered-schemas`, attr-level and request/response shapes
   included, not just the entity `:map` shapes. `:seon.schema/form` is the full,
   canonical registered Malli form, so the complete shape of every attr is one
   entity-read away for the agent. It is never display-truncated.

   Pure program-data builder. Authority reconciliation never overwrites an
   agent's own `(seon.schema/register! …)` tee row, whose source is the
   replayable call form."
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

(def ^:private compiled-program-wall-clock-attrs
  #{:seon.fn/created-at :seon.schema/created-at :seon.test/created-at})

(def ^:private compiled-program-identity-attrs
  [:seon.ns/name :seon.fn/sym :seon.schema/key :seon.test/sym])

(defn- compiled-program-sort-key
  [row]
  (some (fn [[position attribute]]
          (when (contains? row attribute)
            [position (pr-str (get row attribute))]))
        (map-indexed vector compiled-program-identity-attrs)))

(defn- database-initialization
  "Build the complete deterministic database initialization value."
  [descriptor configuration]
  (let [artifact-digest
        (get-in descriptor [::launch/runtime ::launch/execution-digest])
        program
        (->> (concat (index-core! configuration) (index-schemas))
             (map #(apply dissoc % compiled-program-wall-clock-attrs))
             (sort-by compiled-program-sort-key)
             vec)
        _
        (admission/committed-projection
         {::admission/schema-rows
          (into []
                (keep (fn [row]
                        (when-let [key (:seon.schema/key row)]
                          [key (:seon.schema/form row)])))
                program)
          ::admission/function-contract-rows
          (into []
                (keep (fn [row]
                        (when-let [form (:seon.fn/spec row)]
                          [(:seon.fn/sym row) form])))
                program)})]
    (when-not artifact-digest
      (throw
       (ex-info "The launch has no compiled execution artifact digest."
                {:seon.error/kind :core-bug
                 ::launch/runtime (::launch/runtime descriptor)})))
    {:seon.execution/artifact-digest artifact-digest
     :seon.db/attributes agent-bootstrap-attrs
     :seon.db/program program
     :seon.db/initial-data (vec (initial-data configuration))}))

(schema/register! ::llm-fn        'fn?)
(defn- rehost-agent-runtimes!
  "Reconstruct every nonterminated agent after a code reload.

   This is process-local work only: [[seon.agent.runtime/resume!]] per
   database-derived id. Each agent's execution child reconstructs its accepted
   program lazily; the pod owns no compiler or program replay."
  []
  (if (db/attached?)
    (-> (acquire-resumable-agent-ids!)
        (.then
         (fn ^:async rehost! [ids]
           (doseq [id ids]
             (await (agent-runtime/resume! {:seon.agent/id id})))
           (log/info-console! "seon.client"
                              "reload: agent runtimes rehosted"
                              {:seon.client/reinstalled ids})
           ids))
          (.catch
           (fn [err]
             (log/error-console! "seon.client"
                                 "reload: agent runtime rehost FAILED"
                                 err))))
    (js/Promise.resolve [])))

(schema/register! ::apply-config-request
  [:map {:closed true}
   [:seon.config/manifest :seon.config/manifest]
   [::configuration {:optional true} ::configuration]])

(defn ^:async apply-config!
  "Reconcile one resolved manifest into the config-managed database subset.

   This is the single declarative operation used by cold boot and the live
   development operator. Routes, skills, and the flattened config singleton
   land through one provenance-scoped `seon.state/reconcile!`; a converged
   apply submits no transaction. The manifest is already resolved data, never
   ambient process state."
  {:malli/schema [:=> [:cat ::apply-config-request]
                  :seon.state/reconcile-response]}
  [{manifest :seon.config/manifest configuration ::configuration}]
  (let [singleton    (or configuration
                         (config/resolve-config-singleton manifest))
        desired      (-> (vec (config/resolve-routes
                                (route/core-routes-tx)
                                manifest))
                         (into (my.skills/seed-skills-tx-data
                                 (config/skills-dir manifest)))
                         (conj singleton))]
    (await
      (db/without-agent
        (fn ^:async apply-unscoped! []
          (db/with-tx-context
            {:seon.db/user [:seon.agent/id "root"]
             :seon.db/process
             (db.process/lookup-ref :seon.db.process/config)}
            (fn ^:async reconcile-declarative! []
              (state/reconcile!
                {:seon.state/desired desired
                 :seon.db/managed-scope
                 #{:seon.db.process/boot :seon.db.process/config}
                 :seon.db/managed-identity-attrs
                 #{:seon.route/name :my.skills/name :seon.config/id}}))))))))

(schema/register! ::start-runtime-request
  [:map {:closed true}
   [::llm-fn {:optional true} ::llm-fn]
   [::launch-capability {:optional true} ::launch-capability]])
(schema/register! ::resumed-ids [:vector :seon.agent/id])
(schema/register! ::created-ids [:vector :seon.agent/id])
(schema/register! ::start-runtime-response
  [:map {:closed true}
   [:seon.agent/id :seon.agent/id]
   [:seon.agent/ns {:optional true} :symbol]
   [::autonomous? ::autonomous?]
   [::resumed-ids ::resumed-ids]
   [::created-ids ::created-ids]
   [:seon.web/port :int]
   [:seon.web/port-file :string]])

(declare process-generation)

(defn- validate-restore-launch!
  [descriptor capability]
  (when-let [startup (::launch/restore-startup descriptor)]
    (let [expected-generation
          (get-in startup
                  [:seon.dev.restore/startup-identity
                   :seon.dev.restore/consumer-generations
                   :seon.dev.process/pod])
          actual-generation (process-generation)]
      (when-not (false? (::autonomous? capability))
        (throw
          (ex-info "Restore startup requires a nonautonomous capability."
                   {:seon.error/kind :core-bug})))
      (when-not (= (str expected-generation) actual-generation)
        (throw
          (ex-info "Restore startup crossed its expected pod generation."
                   {:seon.client/expected-process-generation
                    expected-generation
                    :seon.client/actual-process-generation actual-generation
                    :seon.error/kind :core-bug})))
      startup)))

(defn- ^:async validate-restore-completion!
  "Prove retained completion evidence against the current authority head."
  [claim result]
  (let [completion (::db.restore/completion result)
        branch-head (::db.restore/completion-branch-head result)
        acquired
        (when (and (true? (::db.restore/ok? result))
                   (= claim (dissoc completion ::db.restore/id))
                   (schema/valid-candidate-value?
                    ::db.branch/head branch-head))
          (await
           (db.restore/acquire-completion!
            {::db.restore/plan-digest (::db.restore/plan-digest claim)})))
        readiness
        (when (and acquired (not (:seon.error/message acquired)))
          (db.restore/readiness
            {::db.restore/completion completion
             ::db.restore/current-completion
             (::db.restore/completion acquired)
             ::db.restore/completion-branch-head branch-head
             ::db.restore/current-branch-head
             (db.branch/head-from-database-value
              (::db.restore/current-db acquired))
             ::db.restore/publication-rows
             (::db.restore/publication-rows acquired)
             :seon.runtime.admission/state (admission/state)}))]
    (when-not (true? (::db.restore/ready? readiness))
      (throw
        (ex-info "start-runtime!: restore completion failed"
                 {:seon.client/restore-claim claim
                  :seon.client/restore-completion-result result
                  :seon.client/restore-readiness readiness
                  :seon.error/kind :core-bug})))
    result))

(defn- ^:async validate-restore-database!
  [descriptor startup claim]
  (when startup
    (let [acquired
          (await
           (db.restore/acquire-completion!
            {::db.restore/plan-digest (::db.restore/plan-digest claim)}))
          _ (when (:seon.error/message acquired)
              (throw
              (ex-info "Restore startup database acquisition failed."
                        {:seon.db/error acquired
                         :seon.error/kind :core-bug})))
          actual (db.branch/head-from-database-value
                  (::db.restore/current-db acquired))
          expected (get-in descriptor
                           [::launch/database ::db.branch/head])
          forced (get-in startup
                         [:seon.db.restore-admin/result
                          :seon.db.restore-admin/forced-main-branch-head])
          missing-schema
          (into []
                (remove #(contains? (::db.restore/installed-schema acquired) %))
                db.restore/completion-attrs)]
      (when-not (and (= expected forced actual)
                     (= :db (::db.branch/name actual)))
        (throw
          (ex-info "Restore startup attached another main database point."
                   {:seon.client/expected-restore-branch-head expected
                    :seon.client/forced-restore-branch-head forced
                    :seon.client/actual-restore-branch-head actual
                    :seon.error/kind :core-bug})))
      (when (seq missing-schema)
        (throw
          (ex-info "The restored database lacks completion schema."
                   {:seon.client/missing-restore-schema missing-schema
                    :seon.error/kind :core-bug}))))))

(defn- ^:async open-startup-session!
  "Open startup from selected desired config or retained database config."
  [startup? selected-configuration]
  (if (some? selected-configuration)
    (open-database-session!
     {::initialize? true
      ::configuration selected-configuration})
    (let [opened (await (open-database-session! {::initialize? false}))]
      (if startup?
        (let [retained-configuration (await (acquire-configuration!))]
          (open-database-session!
           {::initialize? true
            ::configuration retained-configuration}))
        opened))))

(defn- selected-startup-configuration
  "Resolve one explicitly selected startup manifest exactly once."
  [selected-manifest]
  (when (some? selected-manifest)
    (config/resolve-config-singleton selected-manifest)))

(defn- initial-agent-failure?
  [result]
  (or (string? (:seon.error/message result))
      (false? (:seon.db/ok? result))))

(defn- ^:async start-runtime-impl!
  "Cold-start the cluster process or refresh attached read-surface readiness.

   The cold transition attaches the database, reconciles boot-managed facts,
   publishes instrumentation from the accepted graph, performs crash recovery,
   resumes every nonterminated durable agent, and
   starts shared HTTP/debug/ticker machinery. Agent birth is not a mode of this
   function; warm callers use [[seon.agent/start!]]. A repeated attached call
   validates the retained capability, reads resumable ids, and idempotently
   reattaches the web surface. It never re-enters replay, publication,
   boot writes, or agent hosting."
  {:malli/schema [:=> [:cat ::start-runtime-request] :any]}
  [{::keys [llm-fn launch-capability]}]
  (let [capability (or launch-capability default-launch-capability)
        _ (claim-launch-capability! capability)
        autonomous? (true? (::autonomous? capability))
        descriptor (launch/validate-descriptor
                     launch/process-launch-descriptor)
        _ (execution.host/configure!
           {::execution.host/launch-descriptor descriptor
            ::execution.host/javascript-runtime js/process.execPath})
        attached? (db/attached?)
        restore-startup
        (validate-restore-launch! descriptor capability)
        startup? (and (not attached?) autonomous? (nil? restore-startup))
        selected-manifest (when startup? (config/load-manifest))
        selected-configuration
        (selected-startup-configuration selected-manifest)
        restore-completion-claim
        (when restore-startup
          (db.restore/completion-from-launch
            {::launch/descriptor descriptor}))]
    (if attached?
      (let [restore-completion-result
            (when restore-startup
              (await
               (validate-restore-completion!
                restore-completion-claim
                (::restore-completion-result @!state))))
            available-ids (await (acquire-resumable-agent-ids!))
            configuration (await (acquire-configuration!))
            resumed-ids (if autonomous? available-ids [])
            primary (or (first (remove #{"root"} available-ids))
                        (first available-ids)
                        "root")
            {:seon.web/keys [port port-file]}
            (await
              (web.serve/start!
                (if restore-startup
                  {::web.serve/readiness-only? true
                   ::web.serve/configuration configuration
                   ::web.serve/restore-completion-result
                   restore-completion-result}
                  {::web.serve/configuration configuration})))]
        {:seon.agent/id primary
         ::autonomous? autonomous?
         :seon.client/resumed-ids resumed-ids
         :seon.client/created-ids []
         :seon.web/port port
         :seon.web/port-file port-file})
      (let [_session-open
            (await (open-startup-session! startup? selected-configuration))
            _ (await
               (validate-restore-database!
                descriptor restore-startup restore-completion-claim))]
        (when (some? selected-manifest)
          (let [reconciled
                (await (apply-config!
                        {:seon.config/manifest selected-manifest
                         ::configuration selected-configuration}))]
            (when (or (string? (:seon.error/message reconciled))
                      (false? (:seon.state/ok? reconciled)))
              (throw
               (ex-info "Startup config reconciliation failed."
                        {:seon.error/kind :core-bug
                         :seon.state/error
                         (or (:seon.state/error reconciled)
                             (:seon.error/message reconciled))})))))
        (when autonomous?
          (let [recovered
                (await
                 (db/with-tx-context
                  {:seon.db/user [:seon.agent/id "root"]
                   :seon.db/process
                   (db.process/lookup-ref :seon.db.process/boot)}
                  (fn [] (recovery/recover! {}))))]
            (recovery-result! recovered)
            (when (::recovery/repaired? recovered)
              (log/info-console!
               "seon.client/start-runtime!"
               (str "crash recovery: restored "
                    (count (::recovery/agent-ids recovered))
                    " agent(s) to idle")
               recovered))))
        (let [configuration (or selected-configuration
                                (await (acquire-configuration!)))
              initial-result
              (when (and autonomous? (nil? restore-startup))
                (await
                 (db/with-tx-context
                  {:seon.db/user [:seon.agent/id "root"]
                   :seon.db/process
                   (db.process/lookup-ref :seon.db.process/boot)}
                  (fn [] (agent/ensure-initial-agent! {})))))
              _ (when (and autonomous?
                           (nil? restore-startup)
                           (initial-agent-failure? initial-result))
                  (throw (ex-info "start-runtime!: initial agent birth failed"
                                  initial-result)))
              initial-id (when (and autonomous?
                                    (nil? restore-startup)
                                    (::agent/initial-created? initial-result))
                           (:seon.agent/id initial-result))
              created-ids (cond-> []
                            (::agent/root-created? initial-result)
                            (conj "root")
                            initial-id (conj initial-id))
              available-ids (await (acquire-resumable-agent-ids!))
              resumable-ids (if autonomous? available-ids [])
              primary (or initial-id
                          (first (remove #{"root"} available-ids))
                          (first available-ids)
                          "root")]
          (when-not (admission/begin-publication!)
            (throw
             (ex-info "start-runtime!: program publication is already closed"
                      (admission/state))))
          (let [preparation
                (when restore-startup
                  (await
                   (admission/prepare-committed!
                    {::admission/record-failures? false})))
                _ (when (and restore-startup
                             (not (::admission/prepared? preparation)))
                    (throw
                      (ex-info
                        "start-runtime!: restore program preparation failed"
                        preparation)))
                restore-db (when restore-startup (await (db/db)))
                completion-result
                (when restore-startup
                  (await
                    (db/with-tx-context
                      {:seon.db/user [:seon.agent/id "root"]
                       :seon.db/process
                       (db.process/lookup-ref :seon.db.process/boot)}
                      (fn []
                        (db.restore/record!
                         {::db.restore/completion-claim
                          restore-completion-claim
                          ::db.restore/expected-db restore-db})))))
                completion-result
                (when restore-startup
                  (await
                   (validate-restore-completion!
                    restore-completion-claim completion-result)))
                _ (when restore-startup
                    (swap! !state assoc
                           ::restore-completion-result completion-result))
                publication (when-not restore-startup
                              (await (admission/publish-committed!)))
                _ (when (and (nil? restore-startup)
                             (not (::admission/published? publication)))
                    (throw
                     (ex-info
                      "start-runtime!: committed program reconstruction failed"
                      publication)))
                instrument-stats
                (::admission/instrumentation
                 (or preparation publication))
                _ (log/info-console!
                   "seon.client/start-runtime!"
                   (str "instrumentation: "
                        (pr-str
                         (instrumentation-summary instrument-stats))))
                results
                (if autonomous?
                  (let [!results (volatile! [])]
                    (doseq [id resumable-ids]
                      (vswap! !results conj
                              (await
                               (agent-runtime/resume!
                                (cond->
                                  {:seon.agent/id id}
                                  (fn? llm-fn)
                                  (assoc :seon.agent.runtime/llm-fn llm-fn))))))
                    @!results)
                  [])
                _ (when-let [failed
                             (some #(when (false?
                                            (:seon.agent.runtime/resumed? %))
                                      %)
                                   results)]
                    (throw (ex-info "start-runtime!: agent resume failed"
                                    failed)))
                hosted (or (some #(when (= primary (:seon.agent/id %)) %) results)
                           (first results))
                {:seon.web/keys [port port-file]}
                (await
                 (if restore-startup
                   (web.serve/start!
                     {::web.serve/readiness-only? true
                      ::web.serve/configuration configuration
                      ::web.serve/restore-completion-result completion-result})
                   (web.serve/start!
                    {::web.serve/configuration configuration})))]
            (when autonomous?
              (await (ai/sync!))
              (await (web.brand/sync!))
              (agent-loop/install-ticker! configuration))
            (log/info-console! "seon.client" "runtime started"
                               {:autonomous? autonomous?
                                :resumed resumable-ids
                                :created created-ids
                                :port port
                                :port-file port-file})
            (cond-> {:seon.agent/id primary
                     ::autonomous? autonomous?
                     :seon.client/resumed-ids resumable-ids
                     :seon.client/created-ids created-ids
                     :seon.web/port port
                     :seon.web/port-file port-file}
              (:seon.agent/ns hosted)
              (assoc :seon.agent/ns (:seon.agent/ns hosted)))))))))

(defn ^:async start-runtime!
  "Launch one runtime or refresh its proven running status.

   The retained phase is the process-local serialization fence: both cold and
   attached launches claim `starting` before their first await, so a concurrent
   launch or stop cannot publish a second transition."
  {:malli/schema [:=> [:cat ::start-runtime-request]
                  ::start-runtime-response]}
  [request]
  (let [attached-before? (db/attached?)
        phase (runtime-phase)]
    (if (or (and attached-before?
                 (= :seon.client.runtime/running phase))
            (and (not attached-before?) (nil? phase)))
      (do
        (swap! !state assoc ::runtime-phase :seon.client.runtime/starting)
        (try
          (let [result (await (start-runtime-impl! request))]
            (when-not (db/attached?)
              (throw
               (ex-info
                "The runtime lost its database session during launch."
                {::runtime-phase :seon.client.runtime/starting})))
            (swap! !state assoc ::runtime-phase :seon.client.runtime/running)
            result)
          (catch :default error
            (swap! !state
                   (fn [state]
                     (-> state
                         (assoc ::runtime-phase
                                :seon.client.runtime/cleanup-required)
                         (dissoc ::cleanup-requires-connection?
                                 ::restore-completion-result))))
            (throw error))))
      (throw
       (ex-info
        "The runtime launch state requires cleanup before it can start again."
        {::runtime-phase (or phase :seon.client.runtime/cleanup-required)
         ::database-attached? attached-before?})))))

(schema/register! ::stopped? :boolean)
(schema/register! ::stop-error :string)
(schema/register! ::stop-runtime-response
  [:or
   [:map {:closed true}
    [::stopped? [:= true]]
    [::agent-runtime/unhosted-ids ::agent-runtime/unhosted-ids]]
   [:map {:closed true}
    [::stopped? [:= false]]
    [::stop-error ::stop-error]]])

(defn- next-quiescence-observation!
  "Yield once before re-reading durable run and turn facts."
  []
  (js/Promise. (fn [resolve _reject] (js/setTimeout resolve 10))))

(defn- quiescence-deadline
  "Deadline for the existing bounded-turn lifecycle to finish settling."
  []
  (+ (.now js/Date) (config/turn-timeout-ms)))

(defn- ^:async settled-turns!
  "Partition observed turn ids by terminal status at one database value."
  [database turn-ids]
  (let [turn-ids (vec (sort turn-ids))
        rows
        (if (seq turn-ids)
          (await
           (db/pull-many
            {::db/db database
             ::db/pull-pattern
             [:seon.agent.turn/id :seon.agent.turn/status]
             ::db/refs
             (mapv (fn [turn-id]
                     [:seon.agent.turn/id turn-id])
                   turn-ids)}))
          [])]
    (when (:seon.error/message rows)
      (throw (ex-info "Terminal turn acquisition failed."
                      {:seon.error/ex-data rows
                       :seon.error/kind :core-bug})))
    (when-not (= (count turn-ids) (count rows))
      (throw
       (ex-info "Terminal turn acquisition lost input alignment."
                {:seon.agent.turn/ids turn-ids
                 :seon.agent.turn/values rows
                 ::db/db database
                 :seon.error/kind :core-bug})))
    (reduce
     (fn [result [turn-id row]]
       (case (:seon.agent.turn/status row)
         :done (update result ::completed-turn-ids conj turn-id)
         :error (update result ::errored-turn-ids conj turn-id)
         (throw
          (ex-info "A drained turn has no terminal durable status."
                   {:seon.agent.turn/id turn-id
                    :seon.agent.turn/value row
                    ::db/db database}))))
     {::completed-turn-ids [] ::errored-turn-ids []}
     (map vector turn-ids rows))))

(defn ^:async ^:private close-quiescent-runs!
  "Close the supplied pointer-owned runs and retain their results."
  [current-runs]
  (await
   (loop [remaining current-runs
          results []]
     (if-let [{run-id :seon.agent.run/id} (first remaining)]
       (let [result
             (await
              (agent-run/close-run!
               {:seon.agent.run/id run-id
                :seon.agent.run/closed-reason :quiesced}))]
         (recur (next remaining) (conj results [run-id result])))
       results))))

(defn ^:async ^:private drain-agent-work!
  "Close idle current runs and wait for every running turn bracket."
  [deadline]
  (await
   (loop [quiesced-run-ids #{}
          observed-turn-ids #{}]
     (let [work (await (agent-run/quiescence-work!))
           _ (when (:seon.error/message work)
               (throw
                (ex-info "Planned quiesce could not acquire current work."
                         {:seon.error/ex-data work
                          :seon.error/kind :core-bug})))
           current-runs (::agent-run/current-runs work)
           running-turns (::agent-run/running-turns work)
           running-run-ids (into #{} (map :seon.agent.run/id) running-turns)
           closable (remove #(contains? running-run-ids
                                        (:seon.agent.run/id %))
                            current-runs)
           close-results (await (close-quiescent-runs! closable))
           refreshed (await (agent-run/quiescence-work!))
           _ (when (:seon.error/message refreshed)
               (throw
                (ex-info "Planned quiesce could not refresh current work."
                         {:seon.error/ex-data refreshed
                          :seon.error/kind :core-bug})))
           remaining-run-ids
           (into #{}
                 (map :seon.agent.run/id)
                 (::agent-run/current-runs refreshed))
           failed-owned
           (->> close-results
                (keep (fn [[run-id result]]
                        (when (and (:seon.error/message result)
                                   (contains? remaining-run-ids run-id))
                          run-id)))
                vec)
           quiesced-run-ids
           (into quiesced-run-ids
                 (keep (fn [[run-id result]]
                         (when-not (:seon.error/message result) run-id)))
                 close-results)
           observed-turn-ids
           (into observed-turn-ids
                 (map :seon.agent.turn/id)
                 running-turns)]
       (when (seq failed-owned)
         (throw
          (ex-info "Planned quiesce could not close current runs."
                   {::quiesced-run-ids failed-owned})))
       (if (and (empty? (::agent-run/current-runs refreshed))
                (empty? (::agent-run/running-turns refreshed)))
         (merge
          {::quiesced-run-ids (vec (sort quiesced-run-ids))}
          (await
           (settled-turns!
            (::db/db refreshed)
            observed-turn-ids)))
         (if (<= deadline (.now js/Date))
           (throw
            (ex-info "Planned quiesce timed out waiting for durable work."
                     {::agent-run/quiescence-work refreshed}))
           (do
             (await (next-quiescence-observation!))
             (recur quiesced-run-ids observed-turn-ids))))))))

(defn- merge-quiesce-progress
  "Union retry-safe lifecycle evidence accumulated by completed inverses."
  [left right]
  {::quiesced-run-ids
   (vec (sort (set/union
               (set (::quiesced-run-ids left))
               (set (::quiesced-run-ids right)))))
   ::completed-turn-ids
   (vec (sort (set/union
               (set (::completed-turn-ids left))
               (set (::completed-turn-ids right)))))
   ::errored-turn-ids
   (vec (sort (set/union
               (set (::errored-turn-ids left))
               (set (::errored-turn-ids right)))))
   ::agent-runtime/unhosted-ids
   (vec (sort (set/union
               (set (::agent-runtime/unhosted-ids left))
               (set (::agent-runtime/unhosted-ids right)))))})

(defn- process-generation []
  (some-> js/process .-env (aget "SEON_PROCESS_GENERATION")))

(defn ^:async ^:private drain-runtime-owners!
  "Drain every runtime owner below the optional HTTP listener inverse."
  [capability]
  (agent-loop/uninstall-ticker!)
  (let [{wake-ids ::agent-loop/uninstalled-ids}
        (agent-loop/uninstall-all-wake-triggers!)
        _ (swap! !state update ::quiesce-progress
                 merge-quiesce-progress
                 {::agent-runtime/unhosted-ids wake-ids})
        {::keys [quiesced-run-ids completed-turn-ids errored-turn-ids]}
        (if (true? (::autonomous? capability))
          (await (drain-agent-work! (quiescence-deadline)))
          {::quiesced-run-ids []
           ::completed-turn-ids []
           ::errored-turn-ids []})
        _ (swap! !state update ::quiesce-progress
                 merge-quiesce-progress
                 {::quiesced-run-ids quiesced-run-ids
                  ::completed-turn-ids completed-turn-ids
                  ::errored-turn-ids errored-turn-ids})
        {host-ids ::agent-runtime/unhosted-ids}
        (agent-runtime/unhost-all!)
        _ (swap! !state update ::quiesce-progress
                 merge-quiesce-progress
                 {::agent-runtime/unhosted-ids host-ids})
        _ (await (execution.host/stop!))
        _ (await (detach-runtime-advertisement!))]
    (let [progress (::quiesce-progress @!state)]
      (let [detached (admission/detach!)]
        (when (false? (::admission/detached? detached))
          (throw (ex-info "Runtime projection detach failed." detached))))
      (let [generation (process-generation)]
        (db/close-session!)
        (swap! !state dissoc
               ::launch-capability
               ::cleanup-requires-connection?
               ::quiesce-progress)
        (cond->
         (assoc progress ::quiesced? true)
          generation
          (assoc ::runtime.lifecycle/process-generation
                 generation))))))

(defn- quiesce-failure
  [message]
  (let [generation (process-generation)]
    (cond-> {::quiesced? false ::quiesce-error message}
      generation
      (assoc ::runtime.lifecycle/process-generation generation))))

(defn ^:async quiesce-runtime!
  "Drain this pod and return its durable lifecycle evidence.

   The first caller closes executable admission synchronously. Overlapping
   calls fail closed through the retained lifecycle phase; a completed call
   returns the same typed result. Failures retain the session, launch
   capability, and cleanup-required occurrence for an explicit retry. The web
   server remains alive so the operator can receive the complete EDN result."
  {:malli/schema [:=> [:cat] ::runtime.lifecycle/quiesce-response]}
  []
  (let [state @!state
        phase (::runtime-phase state)
        completed (::quiesce-result state)
        attached? (db/attached?)
        capability (::launch-capability state)
        retry? (and (= :seon.client.runtime/cleanup-required phase)
                    (::quiesce-started? state))]
    (cond
      (and (= :seon.client.runtime/quiesced phase) completed)
      completed

      (= :seon.client.runtime/quiescing phase)
      (quiesce-failure "A runtime quiesce transition is already in progress.")

      (not (or (= :seon.client.runtime/running phase) retry?))
      (quiesce-failure "The runtime is not in a quiesceable lifecycle phase.")

      (or (not attached?) (nil? capability))
      (do
        (swap! !state assoc
               ::runtime-phase :seon.client.runtime/cleanup-required
               ::cleanup-requires-connection? true)
        (quiesce-failure
         "The runtime has no retained session and launch capability."))

      :else
      (do
        (swap! !state assoc
               ::runtime-phase :seon.client.runtime/quiescing)
        (if (and (not retry?)
                 (not (admission/begin-quiesce!)))
          (do
            (swap! !state
                   (fn [state]
                     (-> state
                         (assoc ::runtime-phase
                                :seon.client.runtime/running)
                         (dissoc ::quiesce-started?))))
            (quiesce-failure
             "Executable admission could not begin the planned quiesce."))
          (try
            (swap! !state assoc ::quiesce-started? true)
            (let [result
                  (await (drain-runtime-owners! capability))]
              (swap! !state
                     (fn [state]
                       (-> state
                           (assoc ::runtime-phase
                                  :seon.client.runtime/quiesced
                                  ::quiesce-result result)
                           (dissoc ::quiesce-started?))))
              result)
            (catch :default error
              (swap! !state assoc
                     ::runtime-phase :seon.client.runtime/cleanup-required
                     ::quiesce-started? true)
              (quiesce-failure (or (.-message error) (str error))))))))))

(defn ^:async stop-runtime!
  "Stop every runtime owner and close the database authority session.

   A wholly absent runtime is already stopped. Every other transition is
   serialized by `stopping`; web/SSE, ticker/hosts, interests, executable
   admission/projection, and session close are one ordered inverse. Failures
   retain the capability, session, and `cleanup-required` phase so the same
   idempotent owners can be retried from the beginning."
  {:malli/schema [:=> [:cat] ::stop-runtime-response]}
  []
  (let [state @!state
        phase (::runtime-phase state)
        capability (::launch-capability state)
        attached? (db/attached?)
        wholly-absent? (and (nil? phase) (nil? capability) (not attached?))
        quiesce-result (::quiesce-result state)
        transition-active? (contains? #{:seon.client.runtime/starting
                                        :seon.client.runtime/quiescing
                                        :seon.client.runtime/stopping}
                                      phase)
        connection-required? (or (= :seon.client.runtime/running phase)
                                 (::cleanup-requires-connection? state))]
    (cond
      wholly-absent?
      {::stopped? true
       ::agent-runtime/unhosted-ids []}

      transition-active?
      {::stopped? false
       ::stop-error "A runtime lifecycle transition is already in progress."}

      (= :seon.client.runtime/quiesced phase)
      (try
        (await (web.serve/stop!))
        (swap! !state dissoc
               ::runtime-phase
               ::quiesce-result
               ::quiesce-started?
               ::cleanup-requires-connection?
               ::restore-completion-result)
        {::stopped? true
         ::agent-runtime/unhosted-ids
         (or (::agent-runtime/unhosted-ids quiesce-result) [])}
        (catch :default error
          (swap! !state assoc
                 ::runtime-phase :seon.client.runtime/cleanup-required)
          {::stopped? false
           ::stop-error (or (.-message error) (str error))}))

      (and connection-required?
           (not attached?))
      (do
        (swap! !state assoc
               ::runtime-phase :seon.client.runtime/cleanup-required
               ::cleanup-requires-connection? true)
        {::stopped? false
         ::stop-error
         "The running runtime has no open database session."})

      :else
      (do
        (swap! !state assoc ::runtime-phase :seon.client.runtime/stopping)
        (try
          (when (admission/available?)
            (admission/begin-quiesce!))
          (await (web.serve/stop!))
          (let [drained
                (if attached?
                  (await (drain-runtime-owners! capability))
                  {::agent-runtime/unhosted-ids []})]
            (swap! !state dissoc
                   ::launch-capability
                   ::runtime-phase
                   ::cleanup-requires-connection?
                   ::quiesce-started?
                   ::quiesce-result
                   ::restore-completion-result)
            {::stopped? true
             ::agent-runtime/unhosted-ids
             (::agent-runtime/unhosted-ids drained)})
          (catch :default error
            (swap! !state assoc
                   ::runtime-phase :seon.client.runtime/cleanup-required)
            {::stopped? false
             ::stop-error (or (.-message error) (str error))}))))))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

(defonce ^:private !orig-shadow-node-eval
  ;; Dev-eval CALLER scope (C50): `js/SHADOW_NODE_EVAL` is the ONE conduit
  ;; every dev/MCP REPL-submitted form enters the pod through — both Shadow
  ;; nREPL routes (`do-invoke`'s node-eval and `IEvalJS -js-eval`) funnel
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
  (log/configure!
   {:seon.log/file
    (.join npath
           (get-in launch/process-launch-descriptor
                   [::launch/process ::launch/log-dir])
           "pod-events.log")})
  (claim-blob-storage-view!
   (::launch/blob-storage-view launch/process-launch-descriptor))
  (install-process-safety-net!)
  (log/info-console! "seon.client" "-main boot" {:boot-at (:boot-at @!state)})
  ;; Malli instrumentation is installed from the validated PROGRAM projection
  ;; inside `start-runtime!`, after the core is indexed. The DB is the complete,
  ;; ordering-independent source of every fn + spec; later transitions publish
  ;; only their exact dependency delta.
  ;; Auto-start the cluster host unless SEON_NO_AUTO_BOOT.
  ;; Cheap default for dev iteration — browser hits the loopback port,
  ;; no REPL needed. Disable for a compiler-only/dev-eval process.
  (when-not (config/no-auto-boot?)
    (let [llm-fn (ai.dispatch/llm-fn)]
      (-> (start-runtime!
           {::llm-fn llm-fn
            ::launch-capability
            (get-in launch/process-launch-descriptor
                    [::launch/runtime :seon.client/launch-capability])})
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
  (log/info-console!
   "seon.client" "runtime entry started"
   {:seon.launch/client-build-id
    (get-in launch/process-launch-descriptor
            [::launch/runtime ::launch/client-build-id])})
  (start-heartbeat!))
