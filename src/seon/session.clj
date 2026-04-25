(ns seon.session
  "Owner of `:seon.session` — the canonical agent session registry.

   One row per running agent JVM: id, namespace, port, status, ctx checkpoint.

   Phase 3 step 2 adds the agent-launch demo target: `launch!`, `checkpoint!`,
   and `stop!` give the orchestrator a clean lifecycle for spawning an agent
   JVM, evaluating forms in it, persisting `*ctx*` to `:seon.session`, and
   shutting it down.

   The implementation deliberately bypasses `seon.flow.pool` because the pool
   itself is disabled (its health-checker SIGKILLs idle JVMs). We reuse the
   pool's spawn + nrepl-eval primitives but own the session lifecycle here."
  (:require [clojure.edn :as edn]
            [seon.db :as db]
            [seon.db.schema :as db-schema]
            [seon.flow.pool :as pool]
            [seon.runtime :as runtime]
            [seon.schema :as schema]
            [taoensso.timbre :as log]))

;;; ---------------------------------------------------------------------------
;;; Attribute Schemas
;;; ---------------------------------------------------------------------------

(schema/register! ::agent
                  [:string {:min 1
                            :seon.db/identity true
                            :description "Session id (e.g. \"a5ba3e\")"}])

(schema/register! ::namespace
                  [:symbol {:description "Agent namespace symbol (e.g. seon.apps.demo)"}])

(schema/register! ::port
                  [:int {:min 1 :max 65535
                         :description "nREPL port the agent JVM is listening on"}])

(schema/register! ::pid
                  [:int {:min 1 :description "Operating-system process id of the agent JVM"}])

(schema/register! ::started-at
                  [:inst {:description "Wall-clock time the session started"}])

(schema/register! ::stopped-at
                  [:inst {:description "Wall-clock time the session stopped"}])

(schema/register! ::checkpointed-at
                  [:inst {:description "Wall-clock time the ctx checkpoint was last written"}])

(schema/register! ::status
                  [:enum :starting :running :idle :stopping :stopped :crashed :merged])

(schema/register! ::ctx
                  [:string {:description "Serialized ctx checkpoint blob (Nippy/pr-str)"}])

;;; ---------------------------------------------------------------------------
;;; Entity Schema
;;; ---------------------------------------------------------------------------

(def agent-entity-schema
  "Malli :map schema for an agent session row, installed on the
   `:seon.session` datahike DB via `:seon.db/flow`'s `:namespace-schemas`."
  [:map
   [::agent ::agent]
   [::namespace ::namespace]
   [::port {:optional true} ::port]
   [::pid {:optional true} ::pid]
   [::started-at ::started-at]
   [::stopped-at {:optional true} ::stopped-at]
   [::status ::status]
   [::ctx {:optional true} ::ctx]])

(db-schema/register-entity-schema! "seon.session/agent" agent-entity-schema)

;;; ---------------------------------------------------------------------------
;;; Request / Response Schemas
;;; ---------------------------------------------------------------------------

(schema/register! ::session-id
                  [:string {:min 4 :max 6
                            :pattern "^[A-Za-z0-9]{4,6}$"
                            :description "6-char base62 session id"}])

(schema/register! ::launch-request
                  [:map
                   {:gen/fmap (fn [_]
                                (throw (ex-info "launch-request not generatable: spawns a JVM"
                                                {:type :malli.generator/no-generator})))}
                   [::namespace ::namespace]])

(schema/register! ::launch-response
                  [:map
                   [::session-id ::session-id]
                   [::nrepl-port ::port]
                   [::pid ::pid]])

(schema/register! ::checkpoint-request
                  [:map
                   [::session-id ::session-id]])

(schema/register! ::checkpoint-response
                  [:map
                   [::session-id ::session-id]
                   [::checkpointed-at ::checkpointed-at]])

(schema/register! ::stop-request
                  [:map
                   [::session-id ::session-id]])

(schema/register! ::stop-response
                  [:map
                   [::session-id ::session-id]
                   [::status [:enum :stopped]]])

;;; ---------------------------------------------------------------------------
;;; In-process registry
;;; ---------------------------------------------------------------------------
;;;
;;; The agent JVM `Process` and `:nrepl-port` live here. The DB row holds the
;;; canonical, durable view; this map carries the live OS handle that the DB
;;; can't store. Keyed by session-id (string).

(defonce ^:private live-sessions (atom {}))

(defn- live-session [session-id]
  (get @live-sessions session-id))

;;; ---------------------------------------------------------------------------
;;; Bridge to the orchestrator's session registry (mcp__seon__eval lookup)
;;; ---------------------------------------------------------------------------
;;;
;;; mcp__seon__eval routes by session-id via
;;;   (seon.orchestrator.session/get-session-port {::id <id>})
;;; which reads from `seon.orchestrator.session/session-registry`. Until that
;;; atom migrates to flow state (Phase 3 cleanup), we write into it directly
;;; so MCP can find sessions launched here. We avoid taking a hard require
;;; cycle by resolving the var lazily.

(defn- orch-registry-atom
  "Resolve the orchestrator's session-registry atom. Returns the atom or nil
   if the var is unbound / unresolvable."
  []
  (when-let [v (resolve 'seon.orchestrator.session/session-registry)]
    (let [a (var-get v)]
      (when (instance? clojure.lang.IAtom a) a))))

(defn- orch-registry []
  (some-> (orch-registry-atom) deref))

(defn- register-with-orchestrator! [session-id entry]
  ;; TODO Phase 3 cleanup: migrate seon.orchestrator.session/session-registry
  ;; to flow state and stop reaching across namespaces here.
  (when-let [a (orch-registry-atom)]
    (swap! a assoc session-id entry)))

(defn- unregister-from-orchestrator! [session-id]
  (when-let [a (orch-registry-atom)]
    (swap! a dissoc session-id)))

;;; ---------------------------------------------------------------------------
;;; Port allocation
;;; ---------------------------------------------------------------------------
;;;
;;; The pool reserves 7900–7999. We pick from 7980–7999, which is outside the
;;; default pool size (3 JVMs starting at 7900) but still in the agent range
;;; that other tooling (`bin/agent-eval`, MCP) recognises.

(def ^:private launch-port-min 7980)
(def ^:private launch-port-max 7999)

(def ^:private port-allocation-lock (Object.))
(defonce ^:private reserved-ports (atom #{}))

(defn- find-free-port!
  "Pick a port in the launch range that is neither TCP-bound, nor already
   tracked in `live-sessions`, nor reserved by a concurrent in-flight launch.
   Synchronised so two callers can't both pick the same port before either
   has spawned a JVM on it."
  []
  (locking port-allocation-lock
    (let [taken (into #{} (map ::port) (vals @live-sessions))
          reserved @reserved-ports
          port (or (first (filter #(and (not (contains? taken %))
                                        (not (contains? reserved %))
                                        (not (#'pool/port-bound? %)))
                                  (range launch-port-min (inc launch-port-max))))
                   (throw (ex-info "No free agent port in launch range"
                                   {:range [launch-port-min launch-port-max]})))]
      (swap! reserved-ports conj port)
      port)))

(defn- release-reserved-port! [port]
  (swap! reserved-ports disj port))

;;; ---------------------------------------------------------------------------
;;; Agent JVM bootstrap
;;; ---------------------------------------------------------------------------

(defn- agent-bootstrap-code
  "Code evaluated inside the agent JVM after spawn. Creates the requested
   namespace, interns a `*ctx*` atom there, and also interns it into `user`
   so `mcp__seon__eval` (which evaluates in `user` regardless of any
   `(in-ns ...)` form because nREPL's :ns header is per-message) can refer
   to `*ctx*` without qualification.

   We `intern` + `setDynamic` instead of `(def ^:dynamic *ctx* ...)` because
   `nrepl-eval!` treats stderr warnings (\"name suggests dynamic\") as errors."
  [ns-sym]
  (str "(do "
       "(create-ns '" ns-sym ") "
       "(in-ns '" ns-sym ") "
       "(clojure.core/refer-clojure) "
       "(let [a (atom {})] "
       "  (intern '" ns-sym " '*ctx* a) "
       "  (intern 'user '*ctx* a)) "
       "(.setDynamic (resolve (symbol \"" ns-sym "\" \"*ctx*\")) true) "
       "(.setDynamic (resolve (symbol \"user\" \"*ctx*\")) true) "
       ":ok)"))

(defn- bind-namespace!
  "Switch the agent JVM's nREPL evaluator to `ns-sym` for subsequent evals.
   Returns nil; throws on nREPL error."
  [port ns-sym]
  (pool/nrepl-eval! port (str "(in-ns '" ns-sym ")"))
  nil)

;;; ---------------------------------------------------------------------------
;;; Public API
;;; ---------------------------------------------------------------------------

(defn launch!
  "Spawn an agent JVM, register the session, and inject `*ctx*` into the
   requested namespace.

   Request keys:
     ::namespace - Symbol. Clojure namespace the agent JVM should evaluate in.

   Response keys:
     ::session-id - 6-char base62 id (also written to `:seon.session/agent`).
     ::nrepl-port - Port the agent JVM's nREPL is listening on.
     ::pid        - OS pid of the agent JVM."
  {:malli/schema [:=> [:cat ::launch-request] ::launch-response]}
  [{::keys [namespace]}]
  (let [session-id (::runtime/id (runtime/generate-id {}))
        started-at (java.util.Date.)
        port (find-free-port!)]
    ;; Persist :starting before the (slow) spawn so the row is visible.
    (db/transact! :seon.session
                  [{::agent session-id
                    ::namespace namespace
                    ::started-at started-at
                    ::status :starting}])
    (let [jvm (try (#'pool/spawn-agent-jvm! port)
                   (catch Exception spawn-err
                     (release-reserved-port! port)
                     (try (db/transact! :seon.session
                                        [{::agent session-id ::status :crashed}])
                          (catch Exception _))
                     (throw spawn-err)))
          ^Process proc (:seon.flow.pool/process jvm)
          pid (:seon.flow.pool/pid jvm)]
      ;; JVM is bound to the port; reservation no longer needed.
      (release-reserved-port! port)
      (try
        (pool/nrepl-eval! port (agent-bootstrap-code namespace))
        (bind-namespace! port namespace)
        (swap! live-sessions assoc session-id
               {::session-id session-id
                ::namespace namespace
                ::port port
                ::pid pid
                :process proc})
        (register-with-orchestrator! session-id
                                     {:seon.orchestrator.session/id session-id
                                      :seon.orchestrator.session/namespace namespace
                                      :seon.orchestrator.session/status :running
                                      :seon.orchestrator.session/nrepl-port port
                                      :seon.orchestrator.session/started-at started-at
                                      :seon.orchestrator.session/db-name (str namespace)
                                      :seon.orchestrator.session/last-activity-at started-at
                                      :seon.orchestrator.session/eval-count 0
                                      :seon.orchestrator.session/current-eval nil})
        (db/transact! :seon.session
                      [{::agent session-id
                        ::port port
                        ::pid pid
                        ::status :running}])
        (log/info "Launched agent session"
                  {:session-id session-id :namespace namespace
                   :port port :pid pid})
        {::session-id session-id
         ::nrepl-port port
         ::pid pid}
        (catch Exception setup-err
          (log/error "Failed to set up agent session, killing JVM"
                     {:session-id session-id :namespace namespace
                      :error (.getMessage setup-err)})
          (try (.destroy proc) (catch Exception _))
          (swap! live-sessions dissoc session-id)
          (unregister-from-orchestrator! session-id)
          (try (db/transact! :seon.session
                             [{::agent session-id ::status :crashed}])
               (catch Exception _))
          (throw setup-err))))))

(defn checkpoint!
  "Read `@*ctx*` from the agent JVM, serialize it (pr-str), and persist it
   to the session row's `:seon.session/ctx`.

   Request keys:
     ::session-id - The id returned by `launch!`.

   Response keys:
     ::session-id      - Echo of the input id.
     ::checkpointed-at - Wall-clock time of the write."
  {:malli/schema [:=> [:cat ::checkpoint-request] ::checkpoint-response]}
  [{::keys [session-id]}]
  (let [{::keys [port]} (or (live-session session-id)
                            (throw (ex-info "Unknown session id"
                                            {:session-id session-id})))
        ;; nrepl-eval! returns the *printed form* of the value. The agent
        ;; evaluates `(pr-str @*ctx*)` -> string S, then nREPL prints S,
        ;; producing `"\"…\""`. read-string once to peel that outer layer
        ;; so we store the pr-str blob itself, not its escaped print form.
        printed (pool/nrepl-eval! port "(pr-str @*ctx*)")
        blob (edn/read-string printed)
        checkpointed-at (java.util.Date.)]
    (db/transact! :seon.session
                  [{::agent session-id
                    ::ctx blob}])
    {::session-id session-id
     ::checkpointed-at checkpointed-at}))

(defn stop!
  "Auto-checkpoint, terminate the agent JVM, and mark the session row stopped.

   Request keys:
     ::session-id - The id returned by `launch!`.

   Response keys:
     ::session-id - Echo of the input id.
     ::status     - Always :stopped on success."
  {:malli/schema [:=> [:cat ::stop-request] ::stop-response]}
  [{::keys [session-id]}]
  (let [entry (or (live-session session-id)
                  (throw (ex-info "Unknown session id"
                                  {:session-id session-id})))
        ^Process proc (:process entry)]
    ;; Best-effort checkpoint before halting so postmortem inspection works.
    (try (checkpoint! {::session-id session-id})
         (catch Exception e
           (log/warn "Pre-stop checkpoint failed"
                     {:session-id session-id :error (.getMessage e)})))
    (try
      (.destroy proc)
      (.waitFor proc 5 java.util.concurrent.TimeUnit/SECONDS)
      (catch Exception e
        (log/warn "Agent JVM did not exit cleanly"
                  {:session-id session-id :error (.getMessage e)})))
    (swap! live-sessions dissoc session-id)
    (unregister-from-orchestrator! session-id)
    (db/transact! :seon.session
                  [{::agent session-id
                    ::status :stopped
                    ::stopped-at (java.util.Date.)}])
    (log/info "Stopped agent session" {:session-id session-id})
    {::session-id session-id
     ::status :stopped}))

(comment
  ;; Demo: launch -> eval -> checkpoint -> stop
  (def res (launch! {::namespace 'seon.apps.demo}))
  (checkpoint! {::session-id (::session-id res)})
  (stop! {::session-id (::session-id res)})
  (orch-registry)
  nil)
