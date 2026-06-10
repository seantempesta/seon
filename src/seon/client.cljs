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
       {:seon.message/from    seon.agent/user-ref
        :seon.message/to      [[:seon.agent/id \"<agent-id>\"]]
        :seon.message/content \"hello\"})"
  (:require
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
    ;; Content search over allowed files — the exemplar npm-package
    ;; wrapper (@vscode/ripgrep). Required so the agent can call
    ;; (seon.search/grep ...) from bootstrap-CLJS eval and so the
    ;; substrate-vars seed below can index it.
    [seon.search]
    [seon.platform]
    ;; Phase B item 9 — shared read-side wrapper over the analyzer
    ;; state. Required here so the build includes it; item 10's
    ;; detect-and-tee in seon.eval/eval-batch! consumes it.
    [seon.analyzer-info])
  ;; Compile-time enumeration of the build's specced public fns —
  ;; `substrate-vars` below = curated unspecced base + this macro's
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
(def agent-bootstrap-attrs
  "The full set of registered seon attr keywords whose datahike schema the
   agent conn needs. Public so tests can build an isolated `:memory` conn with
   the same schema the pod boots against (see index-substrate-test)."
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
   ;; turns-since-inbound is DERIVED from the count of :seon.turn
   ;; entities with :seon.turn/at > the latest inbound message's :at.
   ;; See seon.agent/turns-since-inbound.
   :seon.session/id
   :seon.session/at
   :seon.session/turns

   ;; --- Turn (v1.md §2.1) ---
   :seon.turn/id
   :seon.turn/at
   :seon.turn/status
   ;; The full prompt is a logs/prompts/<agent>/<turn>.txt BLOB (three-
   ;; tier storage); the datoms are the char-count projection + the
   ;; file pointer. :seon.turn/prompt-text RETIRED 2026-06-09 (was
   ;; silently cap-edn-truncated at 16,406 chars — useless evidence).
   :seon.turn/prompt-chars
   :seon.turn/prompt-file
   :seon.turn/woken-by
   :seon.turn/messages
   :seon.turn/evals

   ;; --- Message (from/to refs since unit 1.5 — role/agent retired) ---
   :seon.message/id
   :seon.message/from
   :seon.message/to
   :seon.message/content
   :seon.message/at
   :seon.message/hops

   ;; --- User (ONE human entity, seeded at boot) ---
   :seon.user/id

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

;; ---------------------------------------------------------------------------
;; Persistent on-disk agent conn (Track A1, 2026-06-09).
;;
;; The agent conn moved off `:backend :memory` (wiped every restart —
;; only one run was ever reviewable) onto datahike's konserve :file
;; backend. Each pod-run gets its own gitignored "session directory"
;; under `data/seon-pod/<run-id>/`, PERSISTED (never auto-deleted) so
;; past runs stay reviewable.
;;
;; run-id is a human-readable UTC timestamp (NOT a bare uuid) so the
;; dirs sort chronologically and a human can tell runs apart at a
;; glance. The store `:id` is derived deterministically from the
;; run-id (stable per dir) so a reconnect to the same dir uses the same
;; store identity.
;;
;; Konserve's node-filestore quirks (verified live, 2026-06-09):
;;   - it does NOT mkdir -p the parent — `data/seon-pod/` must exist
;;     before create-database (ENOENT otherwise);
;;   - `create-database` THROWS if the store dir already exists, and
;;     `connect` THROWS if it doesn't — so we branch on
;;     `database-exists?` (create+connect when absent, connect-only
;;     when present). This makes the helper idempotent across restarts
;;     pointed at the same dir.
;; ---------------------------------------------------------------------------

(def ^:private node-fs (js/require "fs"))
(def ^:private node-path (js/require "path"))
(def ^:private node-crypto (js/require "crypto"))

(defn- path->uuid
  "Deterministic, well-formed UUID derived from a store path via md5.
   Datahike's :file backend REQUIRES a UUID-typed :id and normalizes a
   malformed one (e.g. `(uuid path)` wraps the raw string, which the
   built fork re-hashes to a DIFFERENT id at create time → connect
   then fails with :store-identity-mismatch). A proper RFC-shaped UUID
   is preserved as-is through create→connect, so the same dir always
   re-derives the same id and reconnect succeeds (verified live,
   2026-06-09)."
  [s]
  (let [hex (-> (.createHash node-crypto "md5") (.update s) (.digest "hex"))]
    (uuid (str (subs hex 0 8) "-" (subs hex 8 12) "-" (subs hex 12 16) "-"
               (subs hex 16 20) "-" (subs hex 20 32)))))

(def pod-store-base
  "Gitignored base holding one session directory per pod-run."
  "data/seon-pod")

(defn- run-id
  "Human-readable, filesystem-safe UTC timestamp run-id, e.g.
   \"2026-06-09T14-55-02-123Z\". Colons/dots in the ISO form are
   replaced with dashes so it's a valid directory name on every OS."
  []
  (-> (.toISOString (js/Date.))
      (str/replace #"[:.]" "-")))

(defn- disk-store-cfg
  "datahike config for a pod session directory under pod-store-base.
   `:keep-history? true` is REQUIRED (assert-preconditions! re-asserts
   it; tx-meta-as-history is a silent no-op without it).

   `:id` is a deterministic, well-formed UUID derived from the store
   path (see `path->uuid`). The store layer requires :id present (the
   config default isn't applied on the database-exists?/connect path),
   and it must be a properly-shaped UUID or the built datahike fork
   re-normalizes it and connect fails with :store-identity-mismatch."
  [rid]
  (let [path (.join node-path pod-store-base rid)]
    {:store              {:backend :file
                          :path    path
                          :id      (path->uuid path)}
     :schema-flexibility :write
     :keep-history?      true}))

(defn ^:async open-disk-conn!
  "Open a persistent file-backed datahike conn for the given run-id,
   creating the database the first time and connecting on subsequent
   opens of the same dir. Ensures the gitignored base dir exists first
   (konserve does not mkdir -p). Returns a Promise of the conn."
  [rid]
  (let [cfg (disk-store-cfg rid)]
    ;; konserve does not create parent dirs; the base must exist before
    ;; create-database (ENOENT otherwise). recursive = mkdir -p, no-op
    ;; if already present.
    (.mkdirSync node-fs pod-store-base #js {:recursive true})
    (let [exists? (await (d/database-exists? cfg))]
      (when-not exists?
        (await (d/create-database cfg)))
      (await (d/connect cfg)))))

(defn ^:async open-agent-conn! []
  (let [rid (run-id)
        conn (await (open-disk-conn! rid))]
    (log/info-console! "seon.client/open-agent-conn!"
                       (str "pod store: " (.join node-path pod-store-base rid)))
    (let [
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

(declare substrate-ns-set)

(defn- entry-ns-kw
  "The owning-namespace keyword for a program-graph entry. Used as the
   substrate-vs-agent replay discriminator (Step 4): an entry whose
   `entry-ns-kw` is in `(substrate-ns-set)` is a substrate row (re-indexed
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

   Substrate rows (those whose owning ns is in `(substrate-ns-set)`) are
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
         (remove #(contains? (substrate-ns-set) (entry-ns-kw %)))
         ;; Boot-indexed `:seon.schema` rows store the registered SHAPE
         ;; (`[:string {...}]`, `:keyword`) as :source — an index row, not
         ;; an eval-able registration call. Only a `(…)` form (an agent's
         ;; `(seon.schema/register! …)` tee) is replayable; shape literals
         ;; are rebuilt from the live registry by `index-schemas` each boot.
         (remove #(and (= :schema (:kind %))
                       (not (str/starts-with? (str/trim (str (:source %))) "("))))
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
   excludes any entry whose owning ns is in `(substrate-ns-set)`. The
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

- Every public fn fully specs + validates ALL its args and its return via
  `:malli/schema`. Two shapes: (1) map-in/map-out — one namespaced-keyword
  map in, one out (PREFERRED for API-like fns; request + response are
  registered Malli schemas `::foo-request` + `::foo-response`); or (2) named
  positional — each arg a fully-namespaced-spec'd slot via Malli `:catn`
  inside a `:=>`/`:function` schema (fine for ordinary data-processing fns and
  for mimicking a well-known API). Every arg must be named + specced; an
  unspecced/bare arg is the violation, not a positional one. All keys in any
  map are fully namespaced (`:seon.db/query`, never `:query`).
- Concrete types only. No `:any`. Use `{:optional true}` for absent
  fields — never store nil.
- Retraction is explicit: `[:db/retract eid :attr]`.
- One mechanism for storing program-graph entities: the analyzer plus
  a source string. Don't reparse with rewrite-clj; don't write parallel
  v1/v2 versions of fns.
- The DB is the source of truth. Don't cache atom-state that's
  derivable from datoms.")

(defn seed-substrate!
  "Tx-data for the sticky preamble (system-prompt + conventions) plus
   THE user entity.

   The preamble rows carry `:seon.sticky/position :prefix` so the
   chronological renderer pins them to the front regardless of tx-time
   ordering. Identity upsert on `:seon.system-prompt/id` and
   `:seon.conventions/id` — re-running is cheap.

   The user row is the ONE `:seon.user/id` entity every
   `:seon.message/from`/`to` user-ref resolves to (identity upsert,
   idempotent — same pattern as agent entities; one human for now).

   Pure fn. Caller transacts via `db/transact!` with
   `:seon.db/origin :substrate-seed`."
  []
  [{:seon.user/id "user"}
   {:seon.system-prompt/id      "default"
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

(def ^:private curated-substrate-vars
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
   ;; Read surface on the user's machine (allowlist-gated, see seon.fs).
   ;; Indexed so the functions catalog teaches the SEARCH→READ recipe.
   #'seon.fs/read-file
   #'seon.fs/list-dir
   #'seon.fs/stat
   #'seon.fs/walk-dir
   #'seon.fs/home-dir
   #'seon.search/grep
   #'seon.test.runner/run!])

(def ^:private substrate-vars
  "Every var indexed into the corpus at boot: the curated unspecced base
   PLUS the compile-time roster of every PUBLIC `:malli/schema`-carrying fn
   across the build's whole `seon.*` require closure
   (`seon.indexing/specced-fn-vars` — unit #23 fix b: 'all of the schemas,
   functions and tests in the cljs package should be present in the
   database'). Deduped by fully-qualified sym, curated entries first."
  (->> (into curated-substrate-vars (specced-fn-vars))
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

(defn- substrate-ns-set
  "The set of namespace keywords owned by the COMPILED substrate, derived
   from `substrate-vars` + the preload's deftest vars — the SAME sources of
   truth the boot indexers write from, so they can never drift. Used by
   `query-program-graph-entries` (Step 4) as the replay discriminator: any
   program-graph entry whose owning ns is in this set is a SUBSTRATE row
   (re-indexed from var meta + file-read on every boot) and is SKIPPED on
   replay. Re-evaling a substrate row's source — e.g. `(defn ^:async
   transact! …)` — would shadow the real compiled fn, so substrate is never
   replayed; only agent-authored corpus (in `seon.agent.<id>` / agent domain
   nses) replays.

   A fn (not a def) because `!indexed-test-vars` is populated by the
   preload AFTER this ns loads; robust by construction either way — it's
   NOT tx-meta and NOT a hand-typed ns list, it's the live var-meta `:ns`
   of the indexed vars."
  []
  (into #{}
        (map #(keyword (str (:ns (meta %)))))
        (concat substrate-vars @!indexed-test-vars)))

(defn- read-src-file
  "Read a substrate source file given a var-meta `:file` (classpath-relative,
   e.g. \"seon/db.cljs\" or \"seon/agent_context_test.cljs\"). The pod is
   Node; cwd is the repo root; sources live under the deps.edn `:cljs` source
   roots (src, test, guest-cljs/src — probed in that order). Returns the file
   text, or nil if it can't be read."
  [file]
  (let [fs (js/require "fs")]
    (some (fn [root]
            (try
              (.readFileSync fs (str (.cwd js/process) "/" root "/" file) "utf8")
              (catch :default _ nil)))
          ["src" "test" "guest-cljs/src"])))

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

   Always emits the FULL substrate row set — a PURE function of `substrate-vars`
   + the on-disk source, independent of any conn. Re-seeding the same rows on a
   later boot is idempotent at the DB layer: every row upserts on its identity
   attr (`:seon.ns/name` / `:seon.fn/sym`). The lookup-ref `[:seon.ns/name <kw>]`
   is the only ref shape ever emitted for `:seon.fn/ns` (a single
   `:seon.db/ref`); it is never a bare keyword.

   Boot-time DEDUP (the \"fresh agent, same conn\" guard) is applied by the
   caller via [[substrate-index-tx]], which drops rows already present on the
   conn so a second/Nth `start-agent!` on the shared `*conn*` re-seeds nothing.
   Keeping THIS fn conn-free preserves its role as a pure tx-data builder (the
   shape the index-substrate-test guards rely on).

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

   Pure tx-data builder; the boot dedup in [[substrate-index-tx]] drops
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
   builder; [[substrate-index-tx]] dedups against the conn."
  ([] (index-tests @!indexed-test-vars))
  ([vars]
   (let [now     (js/Date.)
         rows    (keep #(var->test-row % now) vars)
         ns-syms (into #{} (map #(first (str/split (:seon.test/sym %) #"/" 2)) rows))
         ns-rows (for [ns-sym ns-syms]
                   {:seon.ns/name   (keyword ns-sym)
                    :seon.ns/source (str "(ns " ns-sym ")")})]
     (vec (concat ns-rows rows)))))

(defn ^:async substrate-index-tx
  "Boot-time substrate index tx-data: [[index-substrate!]] + [[index-schemas]]
   + [[index-tests]] filtered to the rows not yet present on `conn`. This is
   the idempotency guard for the
   \"fresh agent, same conn\" path — on the FIRST boot of a conn it returns the
   full set; on the SECOND and Nth boot (a second `start-agent!` on the shared
   `*conn*`, or a reconnect to a persistent store that already holds the
   substrate index) it returns ONLY rows whose `:seon.fn/sym` / `:seon.ns/name`
   identity is absent — typically `[]`.

   Querying the conn's CURRENT identity set and emitting only the gap means a
   re-index never re-transacts a substrate row against the populated store —
   removing the re-seed interaction that the Run-3 findings traced to a
   malformed `:seon.fn/ns` value. Returns a Promise of the tx-data vector."
  [conn]
  (let [all       (concat (index-substrate!)
                          (index-schemas)
                          (index-tests))
        db        (await (d/db conn))
        have-fns  (into #{} (map first) (d/q '[:find ?sym :where [?f :seon.fn/sym ?sym]] db))
        have-nses (into #{} (map first) (d/q '[:find ?nm :where [?n :seon.ns/name ?nm]] db))
        have-schs (into #{} (map first) (d/q '[:find ?k :where [?s :seon.schema/key ?k]] db))
        have-tsts (into #{} (map first) (d/q '[:find ?t :where [?e :seon.test/sym ?t]] db))]
    (vec (remove (fn [row]
                   (or (contains? have-fns  (:seon.fn/sym row))
                       (contains? have-nses (:seon.ns/name row))
                       (contains? have-schs (:seon.schema/key row))
                       (contains? have-tsts (:seon.test/sym row))))
                 all))))

(defn- stub-llm
  "A fake LLM that demonstrates the REPL-as-harness response shape: a
   `;; narration` line then a real `seon.db/transact!` form, then
   another narration + the state-flip-to-idle form. The agent reads
   its own eval log in subsequent turns and learns by mimicking what
   it sees here. Returns a Promise of {:text \"...\"}."
  [ctx]
  (let [text (str
               ";; stub LLM here — the real one needs DEEPSEEK_API_KEY\n"
               ";; reply to whoever woke this turn\n"
               "(seon.agent/reply!\n"
               "  {:seon.message/content "
               (pr-str (str "hello from the stub LLM — saw "
                            (count ctx) " chars of ctx"))
               "})\n\n"
               ";; halt the loop\n"
               "(seon.db/transact!\n"
               "  {:seon.db/tx-data\n"
               "   [{:seon.agent/id     (seon.db/current-agent-id)\n"
               "     :seon.agent/state  :idle}]})\n")]
    (.then (.resolve js/Promise nil) (fn [_] {:text text}))))

(defn- current-llm-fn
  "The llm-fn for this pod process: the DeepSeek adapter when
   DEEPSEEK_API_KEY is set, else the stub. Single selection point —
   `-main` and the hot-reload re-arm both call it. Rebuilt FRESH at
   each call (not cached) so a hot reload of seon.ai.deepseek takes
   effect on re-armed listeners; a registry of boot-time llm-fn
   closures would pin agents to pre-reload adapter code, defeating
   the re-arm."
  []
  (if (.. js/process -env -DEEPSEEK_API_KEY)
    (deepseek/agent-adapter)
    stub-llm))

(defn- live-agent-ids
  "Agent ids whose `:seon.agent/state` is `:idle` or `:running` — the
   agents whose user-message triggers must exist. Derived from the DB
   at call time (reactive-context: no stored agent registry)."
  [db]
  (->> (db/query {:seon.db/query '[:find ?aid
                                   :where
                                   [?a :seon.agent/id ?aid]
                                   [?a :seon.agent/state ?state]
                                   [(contains? #{:idle :running} ?state)]]
                  :seon.db/db db})
       (mapv first)))

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
   Subsequent (seon.agent/message! …) calls (or POST /chat) drive the
   loop via the kick listener."
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
                ;;      introspection (var meta + source file-read), DEDUPED
                ;;      against the conn by `substrate-index-tx` so a second
                ;;      agent's boot on the shared conn re-seeds nothing
                ;;      (returns `[]`) — the "fresh agent, same conn" guard.
                ;; Each transact is its own tx so the substrate prefix
                ;; remains a stable cacheable sequence of tx-times.
                index-tx (await (substrate-index-tx conn))
                ;; ENVELOPE CONTRACT (A4): db/transact! never rejects —
                ;; failures resolve as {:seon.db/ok? false …}. Boot seed
                ;; MUST stay fail-loud, so each step checks the envelope
                ;; and throws (surface-errors-loudly): a silent partial
                ;; seed would be far worse than a crashed boot.
                _ (await
                    (db/with-tx-context
                      {:seon.db/origin :substrate-seed}
                      (fn ^:async seed! []
                        (let [check!
                              (fn [step {ok?   :seon.db/ok?
                                         error :seon.db/error}]
                                (when-not ok?
                                  (throw (ex-info
                                           (str "boot seed transact failed at "
                                                step ": "
                                                (:seon.error/message error))
                                           {:seon.client/seed-step step
                                            :seon.db/error error}))))]
                          (check! :entity-schemas
                                  (await (db/transact!
                                           {:seon.db/tx-data
                                            (schema/all-entity-schemas-tx-data)})))
                          (check! :substrate-seed
                                  (await (db/transact!
                                           {:seon.db/tx-data (seed-substrate!)})))
                          (check! :substrate-index
                                  (await (db/transact!
                                           {:seon.db/tx-data index-tx})))))))
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
    (let [llm-fn (current-llm-fn)]
      (log/info-console! "seon.client"
                         (if (.. js/process -env -DEEPSEEK_API_KEY)
                           "using DeepSeek LLM (DEEPSEEK_API_KEY set)"
                           "using stub LLM (DEEPSEEK_API_KEY unset)"))
      (-> (start-agent! {:llm-fn llm-fn})
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
