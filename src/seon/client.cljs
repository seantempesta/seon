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
    ;; Iteration surface — owns the canonical !compile-state +
    ;; !conn defonces. start-agent! shares those atoms (no second
    ;; copy in this ns; see compile-state-lifecycle research note).
    [seon.repl :as repl]
    ;; Phase 2 — test capture as data. Required so the bundle
    ;; includes the runner; agent code reaches it from
    ;; bootstrap-CLJS eval via the analyzer's globalThis fallback
    ;; (seon.eval/truly-undeclared?).
    [seon.test.runner]
    ;; Pod HTTP+SSE server — A-5. Required here so the build includes
    ;; it; start-agent! calls (web.serve/start!) at boot.
    [seon.web.serve :as web.serve]
    ;; Broadcast tx-listener — A-6. Required here so the build includes
    ;; it; start-agent! calls (web.broadcast/install!) at boot.
    [seon.web.broadcast :as web.broadcast]
    ;; Local-machine capability surface — A-9. Required so the agent
    ;; can call (seon.fs/read-file ...) + (seon.platform/host) from
    ;; bootstrap-CLJS eval.
    [seon.fs]
    [seon.platform]))

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
(defonce !compile-state (atom nil))

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
   :seon.schema/key
   :seon.schema/ns
   :seon.schema/source

   ;; --- Log (A-6) ---
   :seon.log/at
   :seon.log/level
   :seon.log/source
   :seon.log/agent
   :seon.log/message
   :seon.log/stack
   :seon.log/dismissed-at

   ;; --- Test (Phase 2 — test capture as data) ---
   :seon.test/sym
   :seon.test/last-passed-at
   :seon.test/last-failed-at
   :seon.test/last-failure-summary
   :seon.test/last-run-id])

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

(defn- target-ns-for-entry
  "Per-entity target ns symbol for `(seval/eval … {:ns _})`.

   For :ns entries the source itself is a (ns …) form which switches
   the ns on its own; we eval from 'cljs.user. For :fn / :schema the
   source is a bare (defn …) / (schema/register! …) — we must be IN
   the owning ns before eval so the def lands in the right slot."
  [{:keys [kind ident]}]
  (case kind
    :ns     'cljs.user
    :fn     (-> ident (str/split #"/" 2) first symbol)
    :schema (-> ident namespace symbol)))

(defn ^:async ^:private query-program-graph-entries
  "Returns a vector of {:kind <:ns|:fn|:schema> :ident <id-value>
   :source <string> :tx <long>} sorted by tx-id ascending. Reads
   against the CURRENT db so only currently-asserted sources land
   in the replay set; retracted / superseded source values stay in
   history and are not replayed."
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
                      (and [?e :seon.schema/key    ?ident ?tx]
                           [?e :seon.schema/source ?source]
                           [(ground :schema) ?kind]))]
                  @conn)]
    (->> rows
         (map (fn [[ident source tx kind]]
                {:kind kind :ident ident :source source :tx tx}))
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
    (db/transact!
      {:seon.db/tx-data
       [{:seon.log/at      (js/Date.)
         :seon.log/level   :warn
         :seon.log/source  "seon.client/replay-program-graph!"
         :seon.log/agent   [:seon.agent/id agent-id]
         :seon.log/message (str "replay of " (name kind) " "
                                (pr-str ident) " failed: " error)
         :seon.log/stack   stack}]})))

(defn ^:async replay-program-graph!
  "Re-eval every :seon.ns / :seon.fn / :seon.schema entity's persisted
   :source in tx-id order. Failures land as :seon.log :warn entries
   and do NOT abort replay — every entity gets its own try/catch.

   Returns a Promise of
     {:seon.client/replay-n-total <int>
      :seon.client/replay-n-ok    <int>
      :seon.client/replay-n-fail  <int>}.

   Call sites:
     - Boot path in start-agent! between assert-preconditions! and
       setup-agent-ns! so substrate atoms (last to run) win the
       last-write race against any agent code that redefines them.
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
               "   [{:seon.message/id      (seon.agent/new-id!)\n"
               "     :seon.message/role    :assistant\n"
               "     :seon.message/content "
               (pr-str (str "hello from the stub LLM — saw "
                            (count ctx) " chars of ctx"))
               "\n"
               "     :seon.message/agent   [:seon.agent/id (session-id)]\n"
               "     :seon.message/at      (js/Date.)}]})\n\n"
               ";; halt the loop\n"
               "(seon.db/transact!\n"
               "  {:seon.db/tx-data\n"
               "   [{:seon.agent/id     (session-id)\n"
               "     :seon.agent/state  :idle}]})\n")]
    (.then (.resolve js/Promise nil) (fn [_] {:text text}))))

(defn ^:async start-agent!
  "Bring up the V0 agent: open conn, init bootstrap-CLJS, prime the
   agent's home namespace with !session-id / !results / !current-ns
   atoms, then boot the session loop.

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
        ;; v1.md §7.4. Re-eval every persisted :seon.ns / :seon.fn /
        ;; :seon.schema entity's :source in tx-id order. Idempotent
        ;; against an empty conn (the :memory case until the SQLite
        ;; flip lands) — returns {…replay-n-total 0 …}. Runs BEFORE
        ;; setup-agent-ns! so substrate atoms (last to run) win the
        ;; last-write race against any agent code that touches the
        ;; home-ns atoms.
        replay-stats  (await (replay-program-graph!
                               {:conn          conn
                                :compile-state compile-state
                                :agent-id      agent/default-id}))
        _             (log/info-console!
                        "seon.client/start-agent!"
                        (str "replay: " (pr-str replay-stats)))
        ;; Prime the agent's home namespace with the atoms + accessors.
        ;; agent-ns-sym is the V0 default (`seon.agent.seon`); when
        ;; multi-agent comes, derive per-id via (seon.agent/home-ns id).
        _             (await (seval/setup-agent-ns!
                               compile-state
                               agent/default-ns
                               agent/default-id))
        ;; Boot the turn loop (creates entity + installs kick).
        {:seon.agent/keys [id ns]}
        (await (agent/boot! {:seon.agent/llm-fn       llm-fn
                             :seon.agent/compile-state compile-state}))
        ;; Boot the pod's HTTP+SSE server (A-5). The browser hits
        ;; this for the dev iteration loop.
        {:seon.web/keys [port port-file]}
        (await (web.serve/start!))
        ;; Install the broadcast tx-listener (A-6) — every DB tx now
        ;; re-renders running agents + diffs against the per-agent
        ;; HTML cache + pushes datastar-patch-elements to open SSE
        ;; connections when the rendered string changes. Also wires the
        ;; render-on-connect watcher so a fresh /sse open gets the
        ;; current state immediately.
        _ (web.broadcast/install!)]
    (log/info-console! "seon.client" "agent started"
                       {:agent id :ns (str ns) :port port :port-file port-file})
    {:seon.agent/id id :seon.agent/ns ns
     :seon.web/port port :seon.web/port-file port-file}))

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
