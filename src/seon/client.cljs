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

     ;; Then chat with it:
     (seon.agent/chat \"seon\" \"hello\")"
  (:require
    [clojure.string :as str]
    [datahike.api :as d]
    ;; Phase A item 6 — bundle malli.instrument so Phase A item 7's
    ;; install! call resolves at runtime. Pulled in here (the :client
    ;; entry) rather than seon.repl/seon.eval so reload churn in those
    ;; namespaces doesn't drag instrumentation init into the hot path.
    [malli.instrument :as mi]
    ;; malli.core/form round-trips a fn's `:malli/schema` to the stable
    ;; `:seon.fn/spec` string in index-substrate! (the runtime-introspection
    ;; substrate indexer — coherent-bootstrap-indexing Step 2).
    [malli.core :as m]
    ;; Phase A item 7 — seon-native collector that walks the analyzer
    ;; at compile time for :malli/schema metadata and registers with
    ;; malli.core/-function-schemas*. Bridges the JVM/CLJS gap where
    ;; mi/collect! is JVM-only.
    [seon.instrument :as instrument]
    ;; Pull in the agent's required namespaces at compile time so all
    ;; schemas are registered before start-agent! runs.
    [seon.agent :as agent]
    [seon.ai.deepseek :as deepseek]
    [seon.db :as db]
    [seon.eval :as seval]
    [seon.log :as log]
    ;; Render protocol — A-2. Required here so the build includes it.
    ;; Symbol-lookup for render slots lives in seon.eval/lookup-value
    ;; (walks goog-global with cljs.core/munge); no boot-time wire-up
    ;; needed.
    [seon.render]
    [seon.render.default]
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
    ;; Default :seon.render/ai + :seon.render/html for :seon.message
    ;; entities. Referenced by symbol from message tx data.
    [seon.handlers.message]
    ;; Renderers for :seon.eval / :seon.fn / :seon.schema / :seon.ns —
    ;; stamped at the write site (record-eval!, build-tee-entities) so
    ;; each persisted entity appears in the inspector's two panes via
    ;; the substrate-wide `:seon.render/ai`-walking assembler.
    [seon.handlers.eval]
    [seon.handlers.fn]
    [seon.handlers.schema]
    [seon.handlers.ns]
    ;; P2 substrate seed — renderers + entity-shape schemas for the
    ;; sticky preamble entities (`:seon.system-prompt`, `:seon.conventions`).
    ;; `seed-substrate!` below transacts the actual rows.
    [seon.handlers.system-prompt]
    ;; Substrate handler registration — `wake-on-message`. Required so
    ;; start-agent! can call `handler/register!` + `wake/bootstrap-schema!`
    ;; at boot. Without this, the inspector header shows "0 handlers"
    ;; and the substrate has no wake-on-message responder beyond the
    ;; per-agent `install-user-trigger!` already wired by agent/boot!.
    [seon.handler :as h]
    [seon.handlers.wake :as wake]
    ;; Local-machine capability surface — A-9. Required so the agent
    ;; can call (seon.fs/read-file ...) + (seon.platform/host) from
    ;; bootstrap-CLJS eval.
    [seon.fs]
    [seon.platform]
    ;; Phase B item 9 — shared read-side wrapper over the analyzer
    ;; state. Required here so the build includes it; item 10's
    ;; detect-and-tee in seon.eval/eval-batch! consumes it.
    [seon.analyzer-info]))

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

(defn ^:dev/after-load after-reload []
  (swap! !state update :reload-count inc)
  (log/info-console! "seon.client"
                     (str "reload #" (:reload-count @!state)
                          " — booted " (:boot-at @!state)))
  (start-heartbeat!))

;; ---------------------------------------------------------------------------
;; datahike-cljs smoke test — proves the substrate works end-to-end.
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
(def ^:private agent-bootstrap-attrs
  [;; --- Agent ---
   ;; :seon.agent/current-ns deleted 2026-05-23 — derived from the
   ;; latest successful eval's :seon.eval/ns. See
   ;; docs/seon/concepts/reactive-context.
   :seon.agent/id
   :seon.agent/state
   :seon.agent/sessions
   :seon.agent/turns-cap
   :seon.agent/ctx

   ;; --- Render slots (A-6) — symbol-only at storage. ---
   :seon.render/ai
   :seon.render/html

   ;; --- Ctx section entities (v1.md §5) ---
   :seon.ctx/name
   :seon.ctx/priority
   :seon.ctx/fn

   ;; --- Session (v1.md §2.1) ---
   ;; :seon.session/turns-since-user deleted 2026-05-23 — derived from
   ;; the count of :seon.turn entities with :seon.turn/at > the latest
   ;; :seon.message/role :user's :at. See seon.agent/turns-since-user.
   :seon.session/id
   :seon.session/at
   :seon.session/turns

   ;; --- Turn (v1.md §2.1) ---
   :seon.turn/id
   :seon.turn/at
   :seon.turn/status
   :seon.turn/prompt-text
   :seon.turn/messages
   :seon.turn/evals

   ;; --- Message ---
   :seon.message/id
   :seon.message/role
   :seon.message/content
   :seon.message/agent
   :seon.message/at

   ;; --- Eval ---
   ;; Evals are component-many on :seon.turn/evals — no standalone
   ;; back-refs to agent / turn-n needed (reachable via the component
   ;; chain). Deleted 2026-05-23.
   :seon.eval/id
   :seon.eval/at
   :seon.eval/duration-ms
   :seon.eval/narration
   :seon.eval/source
   :seon.eval/ok?
   :seon.eval/result-edn
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

   ;; --- Sticky preamble (P2 substrate seed, 2026-05-27). ---
   ;; `:seon.sticky/*` already declared (registered in seon.render).
   ;; The two preamble kinds carry id + content; the renderer pins them
   ;; to the front of every context via :seon.sticky/position :prefix.
   :seon.sticky/position
   :seon.sticky/order
   :seon.sticky/id
   :seon.system-prompt/id
   :seon.system-prompt/content
   :seon.conventions/id
   :seon.conventions/content

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

(defn ^:async open-agent-conn! []
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             ;; v1.md §7.1 precondition. The tx-meta-as-history mechanic
             ;; (every tx carries the seon.db/* causality bundle as
             ;; persisted datoms on the tx entity) is silent-no-op unless
             ;; history is on. `seon.db/assert-preconditions!` (called
             ;; from `start-agent!` below) re-asserts this at runtime.
             :keep-history? true}]
    (await (d/create-database cfg))
    (let [conn (await (d/connect cfg))
          ;; Phase 2.6 (2026-05-23) — the agent schema AND the tx-meta
          ;; schema both flow through `seon.db/malli->datahike-schema`,
          ;; reading the shared `seon.schema` Malli registry. Adding a
          ;; new attr is a Malli `register!` in the owning ns plus a
          ;; keyword line in `agent-bootstrap-attrs` above. No more
          ;; hand-written `:db.type/*` entries.
          full-schema (into (db/malli->datahike-schema agent-bootstrap-attrs)
                            (db/tx-meta-datahike-schema))]
      (await (d/transact! conn full-schema))
      conn)))

;; ---------------------------------------------------------------------------
;; Resume — replay-program-graph!
;;
;; v1.md §7.4. Re-eval every :seon.ns / :seon.fn / :seon.schema entity's
;; persisted :source in tx-id order after pod restart, restoring the
;; agent's vars / namespaces / Malli registrations into the live
;; compile-state + globalThis.
;;
;; Design derived from research/resume-findings-2026-05-23.md:
;;
;;   - Query against `@conn`, NOT `(d/history db)`. Single-card identity
;;     attrs are last-write-wins; the current db gives us the latest
;;     :source per entity. Tx-id bound is the LATEST upsert tx — that's
;;     the correct order (replaying the current code shape, not the
;;     original creation order). Live-probe Q2 in the findings file.
;;   - Bypass `eval-batch!`. Replay goes straight to `seval/eval`.
;;     - No `:seon.eval` entry written per replay (there's no turn
;;       to attach to, and the schema doesn't make :seon.eval/turn
;;       optional).
;;     - detect-and-tee (MVP's incoming addition to eval-batch!)
;;       doesn't re-fire, so we don't write no-op upserts that would
;;       re-anchor tx-ids across boots.
;;   - Per-entity target ns:
;;     - :ns     → source IS the (ns foo …) form; eval from 'cljs.user
;;     - :fn     → ident is "<ns>/<name>" string; ns is the prefix
;;     - :schema → ident is the keyword; ns is its namespace segment
;;   - Failures land as `:seon.log` :warn entries (NOT new :seon.eval
;;     entries — no turn to attach them to). One try/catch per entity;
;;     a failure doesn't abort the rest of the replay.
;;   - The replay-level (with-tx-context {:seon.db/origin :replay
;;     :seon.db/replay? true}) tags only the log-write transactions —
;;     no eval entities are written.
;; ---------------------------------------------------------------------------

(declare substrate-ns-kws)

(defn- entry-ns-kw
  "The owning-namespace keyword for a program-graph entry. Used as the
   substrate-vs-agent replay discriminator (Step 4): an entry whose
   `entry-ns-kw` is in `substrate-ns-kws` is a substrate row (re-indexed
   by index-substrate! every boot) and is skipped on replay.

     :ns     → ident IS the ns keyword.
     :fn     → ident is \"<ns>/<name>\"; ns is the prefix.
     :test   → ident is \"<ns>/<name>\"; ns is the prefix.
     :schema → ident is a keyword; ns is its keyword namespace."
  [{:keys [kind ident]}]
  (case kind
    :ns     ident
    :fn     (keyword (-> ident (str/split #"/" 2) first))
    :test   (keyword (-> ident (str/split #"/" 2) first))
    :schema (keyword (namespace ident))))

(defn- target-ns-for-entry
  "Per-entity target ns symbol for `(seval/eval … {:ns _})`.

   For :ns entries the source itself is a (ns …) form which switches
   the ns on its own; we eval from 'cljs.user. For :fn / :test / :schema
   the source is a bare (defn …) / (deftest …) / (schema/register! …) —
   we must be IN the owning ns before eval so the def lands in the right
   slot."
  [{:keys [kind ident]}]
  (case kind
    :ns     'cljs.user
    :fn     (-> ident (str/split #"/" 2) first symbol)
    :test   (-> ident (str/split #"/" 2) first symbol)
    :schema (-> ident namespace symbol)))

(defn ^:async ^:private query-program-graph-entries
  "Returns a vector of {:kind <:ns|:fn|:test|:schema> :ident <id-value>
   :source <string> :tx <long>} sorted by tx-id ascending, EXCLUDING
   substrate rows (Step 4). Reads against the CURRENT db so only
   currently-asserted sources land in the replay set; retracted /
   superseded source values stay in history and are not replayed.

   Substrate rows (those whose owning ns is in `substrate-ns-kws`) are
   filtered out: the compiled substrate fns are re-indexed from var meta
   + file-read by `index-substrate!` on every boot, so re-evaling their
   `:source` would shadow the real compiled fn. Only agent-authored
   corpus (`:seon.fn` / `:seon.test` / `:seon.schema` / `:seon.ns` rows
   in `seon.agent.<id>` nses) replays.

   `:seon.test` rows replay their `:seon.test/source` (the agent's
   deftest form) alongside fns/schemas. Result rows (`last-passed-at`
   etc.) carry no `:seon.test/source` and so are never selected."
  [conn]
  (let [rows (d/q '[:find ?ident ?source ?tx ?kind
                    :where
                    (or-join [?e ?ident ?source ?tx ?kind]
                      (and [?e :seon.ns/name   ?ident ?tx]
                           [?e :seon.ns/source ?source]
                           [(ground :ns) ?kind])
                      (and [?e :seon.fn/sym    ?ident ?tx]
                           [?e :seon.fn/source ?source]
                           [(ground :fn) ?kind])
                      (and [?e :seon.test/sym    ?ident ?tx]
                           [?e :seon.test/source ?source]
                           [(ground :test) ?kind])
                      (and [?e :seon.schema/key    ?ident ?tx]
                           [?e :seon.schema/source ?source]
                           [(ground :schema) ?kind]))]
                  @conn)]
    (->> rows
         (map (fn [[ident source tx kind]]
                {:kind kind :ident ident :source source :tx tx}))
         (remove #(contains? substrate-ns-kws (entry-ns-kw %)))
         (sort-by :tx)
         vec)))

(defn ^:async ^:private replay-one!
  "Replay a single entry. Returns
     {:ok? true}
   or
     {:ok? false :error <message-string> :stack <stack-string>}.
   Never throws — converts any exception to data."
  [compile-state {:keys [source] :as entry}]
  (try
    (let [ns-sym (target-ns-for-entry entry)
          r      (await (seval/eval compile-state source
                                    {:ns            ns-sym
                                     :analyze-deps? false}))]
      (if (:ok r)
        {:ok? true}
        {:ok? false
         :error (or (some-> r :error :seon.error/message) "unknown")
         :stack (or (some-> r :error :seon.error/stack) "")}))
    (catch :default e
      {:ok? false
       :error (or (.-message e) (str e))
       :stack (or (.-stack e) "")})))

(defn ^:async ^:private log-replay-failure!
  [agent-id {:keys [kind ident]} {:keys [error stack]}]
  (await
    (log/warn! {:seon.log/source  ::log-replay-failure!
                :seon.log/agent   agent-id
                :seon.log/message (str "replay of " (name kind) " "
                                       (pr-str ident) " failed: " error)
                :seon.log/stack   (or stack "")})))

(defn ^:async replay-program-graph!
  "Re-eval every :seon.ns / :seon.fn / :seon.schema entity's persisted
   :source in tx-id order. Failures land as :seon.log :warn entries
   and do NOT abort replay — every entity gets its own try/catch.

   Returns a Promise of
     {:seon.client/replay-n-total <int>
      :seon.client/replay-n-ok    <int>
      :seon.client/replay-n-fail  <int>}.

   Substrate rows are NOT replayed (Step 4) — `query-program-graph-entries`
   excludes any entry whose owning ns is in `substrate-ns-kws`. The
   compiled substrate fns are rebuilt from var meta + file-read by
   `index-substrate!` on every boot, so only agent-authored corpus
   (fns / tests / schemas / nses under `seon.agent.<id>`) replays here.

   Call sites:
     - Boot path in start-agent!, before setup-agent-ns!.
     - REPL probe via the same-pod-session test pattern — see
       research/resume-findings-2026-05-23.md §'Same-pod-session test'."
  [{:keys [conn compile-state agent-id]}]
  (db/with-tx-context
    {:seon.db/origin   :replay
     :seon.db/replay?  true
     :seon.db/agent-id agent-id}
    (fn ^:async run-replay! []
      (let [entries (await (query-program-graph-entries conn))
            !n-ok   (volatile! 0)
            !n-fail (volatile! 0)]
        (doseq [entry entries]
          (let [r (await (replay-one! compile-state entry))]
            (if (:ok? r)
              (vswap! !n-ok inc)
              (do
                (vswap! !n-fail inc)
                ;; Best-effort log; swallow log-write failure (would
                ;; be a double-fault and we want the rest of replay
                ;; to continue).
                (try
                  (await (log-replay-failure! agent-id entry r))
                  (catch :default e
                    (log/error-console!
                      "seon.client/replay-program-graph!"
                      (str "log-replay-failure failed: " (.-message e)))))))))
        {:seon.client/replay-n-total (count entries)
         :seon.client/replay-n-ok    @!n-ok
         :seon.client/replay-n-fail  @!n-fail}))))

;; ---------------------------------------------------------------------------
;; Substrate boot seed (P2, 2026-05-27)
;;
;; Per docs/prds/agent-runtime/mvp-completion-plan-2026-05-27.md §Phase 2
;; + research/repl-session-context-template-2026-05-26.md §5: the substrate
;; transacts a deterministic sticky preamble (system-prompt + conventions)
;; plus an introspection-indexed set of core fns at boot, BEFORE any agent
;; turn. This gives the chronological renderer a stable cacheable prefix and
;; means replay-from-tx-0 starts on a fully-seeded substrate, not mid-air.
;;
;; Tx-ordering at boot (in start-agent!):
;;   1. Entity-schema decomposition (schema/all-entity-schemas-tx-data)
;;      — already shipped, Item 4 commit 35035d8.
;;   2. seed-substrate!    — sticky preamble (system-prompt + conventions)
;;   3. index-substrate!   — :seon.ns + :seon.fn rows from REAL runtime
;;                           introspection (var meta + source file-read)
;;
;; Each transact carries `:seon.db/origin :substrate-seed` in tx-meta so
;; audit queries can isolate seed datoms from agent-produced ones.
;; ---------------------------------------------------------------------------

(def ^:private default-conventions
  "Substrate conventions the agent must know. Lives as a `:seon.conventions`
   entity so it's queryable + rendered into every turn's context as a
   stable cached prefix. Compact deliberately — full conventions live in
   `docs/conventions.md` and the agent can ask for them."
  "Conventions for the substrate:

- Every public fn takes ONE map and returns ONE map. All keys are fully
  namespaced (`:seon.db/query`, never `:query`).
- Use `:malli/schema` metadata. Request + response are registered Malli
  schemas (`::foo-request` + `::foo-response`).
- Concrete types only. No `:any`. Use `{:optional true}` for absent
  fields — never store nil.
- Retraction is explicit: `[:db/retract eid :attr]`.
- One mechanism for storing program-graph entities: the analyzer plus
  a source string. Don't reparse with rewrite-clj; don't write parallel
  v1/v2 versions of fns.
- The DB is the source of truth. Don't cache atom-state that's
  derivable from datoms.")

(defn seed-substrate!
  "Tx-data for the sticky preamble: system-prompt + conventions.

   Both carry `:seon.sticky/position :prefix` so the chronological
   renderer pins them to the front regardless of tx-time ordering.
   Identity upsert on `:seon.system-prompt/id` and `:seon.conventions/id`
   — re-running is cheap.

   Pure fn. Caller transacts via `db/transact!` with
   `:seon.db/origin :substrate-seed`."
  []
  [{:seon.system-prompt/id      "default"
    :seon.system-prompt/content deepseek/default-system-prompt
    :seon.sticky/position       :prefix
    :seon.sticky/order          0}
   {:seon.conventions/id      "default"
    :seon.conventions/content default-conventions
    :seon.sticky/position     :prefix
    :seon.sticky/order        1}])

;; ---------------------------------------------------------------------------
;; index-substrate! — runtime introspection of compiled substrate fns
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
;; `substrate-vars` to widen the seeded set.
;; ---------------------------------------------------------------------------

(def ^:private substrate-vars
  "Compile-time `#'`-literal vars indexed into the corpus at boot. MUST be
   `#'`-literals (self-host `resolve` is a compile-time macro). Grow this list
   to widen the seeded substrate surface — the indexing mechanism is uniform."
  [#'db/transact!
   #'db/query
   #'db/pull
   #'db/entity
   #'db/current-agent-id
   #'schema/register!
   #'seon.test.runner/run!])

(def ^:private substrate-ns-kws
  "The set of namespace keywords owned by the COMPILED substrate, derived
   from `substrate-vars` — the SAME source of truth `index-substrate!` writes
   from, so the two can never drift. Used by `query-program-graph-entries`
   (Step 4) as the replay discriminator: any program-graph entry whose owning
   ns is in this set is a SUBSTRATE row (re-indexed from var meta + file-read
   on every boot by `index-substrate!`) and is SKIPPED on replay. Re-evaling a
   substrate row's source — e.g. `(defn ^:async transact! …)` — would shadow
   the real compiled fn, so substrate is never replayed; only agent-authored
   corpus (in `seon.agent.<id>` nses) replays.

   Robust by construction: it's NOT tx-meta (`:seon.db/origin :substrate-seed`
   can be absent on a re-asserted/older row, and history-dependent) and NOT a
   hand-typed ns list — it's the live var-meta `:ns` of the indexed vars."
  (into #{} (map #(keyword (str (:ns (meta %)))) substrate-vars)))

(defn- read-src-file
  "Read a substrate source file given a var-meta `:file` (project-relative,
   e.g. \"seon/db.cljs\"). The pod is Node; cwd is the repo root; sources live
   under <cwd>/src. Returns the file text, or nil if it can't be read."
  [file]
  (try
    (let [fs   (js/require "fs")
          path (str (.cwd js/process) "/src/" file)]
      (.readFileSync fs path "utf8"))
    (catch :default _ nil)))

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

(defn- arglists-from-source
  "Parse the pr-str-style arglists string (e.g. \"([{::keys [a b]}])\") from a
   `(defn …)` source text. Reader-free: an arg-vector is a `[..]` sitting
   directly inside the defn list — paren-depth 1, brace-depth 0. This skips
   `{:malli/schema [...]}` metadata maps (brace-depth > 0). Collects every
   arg-vector (single + multi-arity), wraps in parens. Tracks string, escape,
   `\\(` char-literal, and `;`-to-EOL comment state. Returns \"()\" if none
   found (caller treats that as no arglists)."
  [src]
  (let [n (count src)]
    (loop [i 0 pdepth 0 bdepth 0 in-str? false esc? false vecs []]
      (if (>= i n)
        (str "(" (str/join " " vecs) ")")
        (let [c (nth src i)]
          (cond
            esc?                   (recur (inc i) pdepth bdepth in-str? false vecs)
            (and in-str? (= c \\)) (recur (inc i) pdepth bdepth in-str? true vecs)
            in-str?                (recur (inc i) pdepth bdepth (not (= c \")) false vecs)
            (= c \")               (recur (inc i) pdepth bdepth true false vecs)
            (= c \\)               (recur (+ i 2) pdepth bdepth in-str? false vecs)
            (= c \;)               (let [eol (loop [j i]
                                               (if (or (>= j n) (= (nth src j) \newline))
                                                 j (recur (inc j))))]
                                     (recur eol pdepth bdepth in-str? false vecs))
            (= c \()               (recur (inc i) (inc pdepth) bdepth in-str? false vecs)
            (= c \))               (recur (inc i) (dec pdepth) bdepth in-str? false vecs)
            (= c \{)               (recur (inc i) pdepth (inc bdepth) in-str? false vecs)
            (= c \})               (recur (inc i) pdepth (dec bdepth) in-str? false vecs)
            (and (= c \[) (= pdepth 1) (zero? bdepth))
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
              (recur (inc vend) pdepth bdepth in-str? false (conj vecs (subs src i (inc vend)))))
            :else (recur (inc i) pdepth bdepth in-str? false vecs)))))))

(defn- var->fn-row
  "Build a `:seon.fn` row for a `#'`-literal substrate var from runtime
   introspection. Returns nil (and logs) when the source file can't be read or
   the form can't be extracted — NO `,,,` stub is ever persisted. `now` is the
   shared `:seon.fn/created-at` instant."
  [v now]
  (let [m       (meta v)
        sym     (str (:ns m) "/" (:name m))
        ns-kw   (keyword (str (:ns m)))
        spec    (some-> (:malli/schema m) m/schema m/form pr-str)
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
               :seon.fn/arglists   (arglists-from-source src)
               :seon.fn/doc        (or (:doc m) "")
               :seon.fn/private?   (boolean (:private m))
               :seon.fn/created-at now}
        ;; PRESENT ⇒ specced (exact contract in corpus); ABSENT ⇒ unspecced.
        (some? spec) (assoc :seon.fn/spec spec)))))

(defn index-substrate!
  "Tx-data for substrate `:seon.ns` + `:seon.fn` rows, built by REAL runtime
   introspection over `substrate-vars` (file-read at var-meta `:file`/`:line`
   + var meta for spec/doc). Replaces the old curated `seed-core-fns!`.

   Per owning ns, emits a `:seon.ns/name` + `:seon.ns/source` row so the
   `[:seon.ns/name <kw>]` lookup-ref on `:seon.fn/ns` resolves. The ns source
   is a MINIMAL `(ns x)` stub, NOT the file's real ns form: the replay path
   (replay-program-graph!) still re-evals `:seon.ns/source` today, and a bare
   `(ns seon.db)` is safe + cheap to replay whereas the real `(ns seon.db
   (:require …))` form would attempt requires in bootstrap CLJS. (Step 4 of the
   PRD makes substrate rows no-replay; when that lands this can carry real ns
   source.)

   Fns whose source can't be read are OMITTED, not stubbed — the corpus stays
   honest. Returns the tx-data vector; caller transacts under
   `:seon.db/origin :substrate-seed`."
  []
  (let [now     (js/Date.)
        fn-rows (keep #(var->fn-row % now) substrate-vars)
        ns-syms (into #{} (map #(first (str/split (:seon.fn/sym %) #"/" 2)) fn-rows))
        ns-rows (for [ns-sym ns-syms]
                  {:seon.ns/name   (keyword ns-sym)
                   :seon.ns/source (str "(ns " ns-sym ")")})]
    (vec (concat ns-rows fn-rows))))

(defn- stub-llm
  "A fake LLM that demonstrates the REPL-as-harness response shape: a
   `;; narration` line then a real `seon.db/transact!` form, then
   another narration + the state-flip-to-idle form. The agent reads
   its own eval log in subsequent turns and learns by mimicking what
   it sees here. Returns a Promise of {:text \"...\"}."
  [ctx]
  (let [text (str
               ";; stub LLM here — the real one needs DEEPSEEK_API_KEY\n"
               ";; reply to the user\n"
               "(seon.db/transact!\n"
               "  {:seon.db/tx-data\n"
               "   [{:seon.message/id      (seon.db/new-id!)\n"
               "     :seon.message/role    :assistant\n"
               "     :seon.message/content "
               (pr-str (str "hello from the stub LLM — saw "
                            (count ctx) " chars of ctx"))
               "\n"
               "     :seon.message/agent   [:seon.agent/id (seon.db/current-agent-id)]\n"
               "     :seon.message/at      (js/Date.)}]})\n\n"
               ";; halt the loop\n"
               "(seon.db/transact!\n"
               "  {:seon.db/tx-data\n"
               "   [{:seon.agent/id     (seon.db/current-agent-id)\n"
               "     :seon.agent/state  :idle}]})\n")]
    (.then (.resolve js/Promise nil) (fn [_] {:text text}))))

(defn ^:async start-agent!
  "Bring up the V0 agent: open conn, init bootstrap-CLJS, prime the
   agent's home namespace with the (result <eval-id>) accessor, then
   boot the turn loop.

     :llm-fn — fn of ctx-string returning a Promise of {:text \"...\"}.
               Optional; defaults to stub-llm for verification without
               an API key. Pass (seon.ai.deepseek/agent-adapter) for
               the real thing.

   Returns a Promise resolving to
     {:seon.agent/id _ :seon.agent/ns _}.
   Subsequent (seon.agent/chat ...) calls drive the loop via the
   kick listener."
  [& [{:keys [llm-fn] :or {llm-fn stub-llm}}]]
  (let [conn          (or @!agent-conn (await (open-agent-conn!)))
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
        ;; while the substrate code is stable.
        compile-state (await (repl/ensure-bootstrap!))
        ;; Mint the agent id locally (audit P1 — was a process-global
        ;; defonce in seon.agent). Every downstream call sees it via
        ;; the `(db/with-agent agent-id …)` scope wrapping the rest of
        ;; boot below — replay-program-graph!, setup-agent-ns!,
        ;; agent/boot!, install-user-trigger!, run-turn!. The
        ;; user-message-handler re-enters `with-agent` on each kick.
        agent-id      (db/new-id!)
        agent-ns-sym  (agent/home-ns agent-id)]
    (await
      (db/with-agent agent-id
        (fn ^:async boot-with-agent! []
          (let [;; v1.md §7.4. Re-eval every persisted AGENT-authored
                ;; :seon.ns / :seon.fn / :seon.test / :seon.schema entity's
                ;; :source in tx-id order. Substrate rows are excluded by
                ;; query-program-graph-entries (Step 4) — they're rebuilt by
                ;; index-substrate! later in this fn — so replay no longer
                ;; needs to be ordered ahead of substrate setup to avoid
                ;; shadowing the compiled substrate fns. Idempotent against an
                ;; empty conn (the :memory case until the SQLite flip lands) —
                ;; returns {…replay-n-total 0 …}.
                replay-stats  (await (replay-program-graph!
                                       {:conn          conn
                                        :compile-state compile-state
                                        :agent-id      agent-id}))
                _             (log/info-console!
                                "seon.client/start-agent!"
                                (str "replay: " (pr-str replay-stats)))
                ;; Prime the agent's home namespace with the atoms +
                ;; accessors. Per-agent: derived via
                ;; (seon.agent/home-ns agent-id).
                _             (await (seval/setup-agent-ns!
                                       compile-state
                                       agent-ns-sym
                                       agent-id))
                ;; Boot the turn loop (creates entity + installs kick).
                {:seon.agent/keys [id ns]}
                (await (agent/boot! {:seon.agent/id           agent-id
                                     :seon.agent/llm-fn       llm-fn
                                     :seon.agent/compile-state compile-state}))
                ;; Boot the pod's HTTP+SSE server (A-5). The browser hits
                ;; this for the dev iteration loop.
                {:seon.web/keys [port port-file]}
                (await (web.serve/start!))
                ;; Substrate handlers — register every substrate-wide
                ;; handler entity in the DB so the dispatcher (and the
                ;; inspector's handler count) sees them. Idempotent:
                ;; `handler/register!` upserts on the composite tuple
                ;; `[name agent]`; `wake/bootstrap-schema!` upserts on
                ;; `:db/ident`. Re-running on hot-reload is cheap.
                _ (await (h/bootstrap-schema!))
                _ (await (wake/bootstrap-schema!))
                _ (await (h/register!
                           {:seon.handler/name      :wake/on-message
                            :seon.handler/match     {:seon.handler.match/attr
                                                     :seon.message/to}
                            :seon.handler/fn        'seon.handlers.wake/wake-on-message
                            :seon.handler/on-origin #{:user :agent}}))
                ;; Schemas-as-queryable-data: decompose every registered
                ;; entity-shape :map schema into a :seon.schema DB entity
                ;; so the renderer's kind-lookup can query via datalog
                ;; (no in-memory atom walk on the hot path). Identity
                ;; upsert on :seon.schema/key — re-running is cheap.
                ;; P2 substrate seed — three transacts under one
                ;; `:seon.db/origin :substrate-seed` tx-meta scope so
                ;; audit queries can isolate seed datoms. Order is
                ;; load-bearing for replay-from-tx-0:
                ;;   1. entity-schema decomposition (Item 4) — must
                ;;      land first so subsequent entities reference
                ;;      registered shapes.
                ;;   2. sticky preamble (system-prompt + conventions)
                ;;      — pinned to context-front via
                ;;      `:seon.sticky/position :prefix`.
                ;;   3. substrate index — `:seon.ns` + `:seon.fn` rows
                ;;      built by `index-substrate!` from REAL runtime
                ;;      introspection (var meta + source file-read).
                ;; Each transact is its own tx so the substrate prefix
                ;; remains a stable cacheable sequence of tx-times.
                _ (await
                    (db/with-tx-context
                      {:seon.db/origin :substrate-seed}
                      (fn ^:async seed! []
                        (await (db/transact!
                                 {:seon.db/tx-data
                                  (schema/all-entity-schemas-tx-data)}))
                        (await (db/transact!
                                 {:seon.db/tx-data (seed-substrate!)}))
                        (await (db/transact!
                                 {:seon.db/tx-data
                                  (index-substrate!)})))))
                ;; Install the per-agent inspector tx-listener. Pushes
                ;; morphs for the agent-view inspector page (/agent/<id>).
                _ (seon.web.inspector/install!)]
            (log/info-console! "seon.client" "agent started"
                               {:agent id :ns (str ns) :port port :port-file port-file})
            {:seon.agent/id id :seon.agent/ns ns
             :seon.web/port port :seon.web/port-file port-file}))))))

(defn start-agent-with-stub!
  "Bring up the V0 agent with the canned stub LLM. Useful for verifying
   the full loop without a deepseek API key. Returns a channel."
  []
  (start-agent!))

(defn start-agent-with-deepseek!
  "Bring up the V0 agent against the real deepseek API. Requires
   DEEPSEEK_API_KEY in process.env. Returns a channel."
  []
  (start-agent! {:llm-fn (deepseek/agent-adapter)}))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

(defn- install-process-safety-net!
  "Belt-and-suspenders: Node 15+ defaults to terminating the process on
   an unhandled Promise rejection. Anything in the pod (substrate, agent
   eval, HTTP handlers) that throws inside an async chain and isn't
   caught upstream brings the whole pod down by default — a tiny
   substrate bug becomes a denial-of-service.

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
    (let [llm-fn (if (.. js/process -env -DEEPSEEK_API_KEY)
                   (do (log/info-console! "seon.client" "using DeepSeek LLM (DEEPSEEK_API_KEY set)")
                       (deepseek/agent-adapter))
                   (do (log/info-console! "seon.client" "using stub LLM (DEEPSEEK_API_KEY unset)")
                       nil))]
      (-> (start-agent! {:llm-fn (or llm-fn stub-llm)})
          (.then (fn [{:seon.agent/keys [id ns]
                       :seon.web/keys [port port-file]}]
                   (log/info-console! "seon.client" "auto-boot ready"
                                      {:agent id :ns (str ns)
                                       :url (str "http://127.0.0.1:" port)
                                       :port-file port-file})))
          (.catch (fn [err]
                    (log/error-console! "seon.client" "auto-boot failed" err))))))
  (log/info-console! "seon.client" "nREPL :7889 — (shadow.cljs.devtools.api/nrepl-select :client)")
  (start-heartbeat!))
