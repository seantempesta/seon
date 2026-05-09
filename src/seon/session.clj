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
            [seon.db.relay :as relay]
            [seon.db.schema :as db-schema]
            [seon.flow.pool :as pool]
            [seon.orchestrator.session :as orch]
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

(schema/register! ::checkpoint-interval-ms
                  [:int {:min 0
                         :description "Auto-checkpoint poll interval in ms. 0 disables. Default 1000."}])

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
                   [::namespace ::namespace]
                   [::checkpoint-interval-ms {:optional true} ::checkpoint-interval-ms]])

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
;;; Auto-checkpoint scheduler
;;; ---------------------------------------------------------------------------
;;;
;;; One shared ScheduledExecutorService (daemon) runs all per-session
;;; checkpoint pollers. Each tick reads `@*ctx-version*` from the agent JVM;
;;; if it advanced past the last-checkpointed version, we call `checkpoint!`
;;; and update the marker. Failed ticks are logged but never propagate, so
;;; the scheduler thread stays alive and the session keeps polling.

(def ^:private default-checkpoint-interval-ms 1000)

(defonce ^:private checkpoint-scheduler
  (java.util.concurrent.Executors/newSingleThreadScheduledExecutor
   (reify java.util.concurrent.ThreadFactory
     (newThread [_ r]
       (doto (Thread. ^Runnable r "seon-session-checkpoint")
         (.setDaemon true))))))

(declare checkpoint!)

(defn- run-checkpoint-tick!
  "One scheduler tick for `session-id`. Reads `@*ctx-version*` from the
   agent JVM and calls `checkpoint!` if it has advanced. Swallows any
   exception (logged) so the scheduled task never aborts."
  [session-id]
  (try
    (when-let [entry (live-session session-id)]
      (let [{::keys [port last-checkpoint-version]} entry
            printed (pool/nrepl-eval! port "@*ctx-version*")
            version (Long/parseLong printed)]
        (when (> version (or last-checkpoint-version 0))
          (checkpoint! {::session-id session-id})
          (swap! live-sessions
                 (fn [m]
                   (if (contains? m session-id)
                     (assoc-in m [session-id ::last-checkpoint-version] version)
                     m))))))
    (catch Exception e
      (log/warn "Auto-checkpoint tick failed"
                {:session-id session-id :error (.getMessage e)}))))

(defn- schedule-checkpoint-task!
  "Schedule a periodic auto-checkpoint task for `session-id`. Returns the
   ScheduledFuture, which the caller stores in `live-sessions`."
  [session-id interval-ms]
  (when (and interval-ms (pos? interval-ms))
    (.scheduleAtFixedRate
     ^java.util.concurrent.ScheduledExecutorService checkpoint-scheduler
     ^Runnable (fn [] (run-checkpoint-tick! session-id))
     (long interval-ms)
     (long interval-ms)
     java.util.concurrent.TimeUnit/MILLISECONDS)))

(defn- cancel-checkpoint-task!
  "Cancel the scheduled checkpoint task on a live-sessions entry, if any."
  [entry]
  (when-let [^java.util.concurrent.ScheduledFuture fut (::checkpoint-future entry)]
    (try (.cancel fut false) (catch Exception _))))

;;; ---------------------------------------------------------------------------
;;; Bridge to the orchestrator's session registry (mcp__seon__eval lookup)
;;; ---------------------------------------------------------------------------
;;;
;;; mcp__seon__eval routes by session-id via
;;;   (seon.orchestrator.session/get-session-port {::id <id>})
;;; which reads from the `:seon.orchestrator` datahike DB. We persist the row
;;; via orch/register-external-session! / unregister-external-session! so MCP
;;; eval routing finds sessions launched here.

(defn- register-with-orchestrator!
  [session-id namespace port started-at]
  (orch/register-external-session!
   {:seon.orchestrator.session/id session-id
    :seon.orchestrator.session/namespace (str namespace)
    :seon.orchestrator.session/nrepl-port port
    :seon.orchestrator.session/started-at started-at
    :seon.orchestrator.session/db-name (str namespace)}))

(defn- unregister-from-orchestrator! [session-id]
  (orch/unregister-external-session!
   {:seon.orchestrator.session/id session-id}))

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

   When `relay-port` is non-nil, also requires `seon.db.relay` and connects
   it back to the orchestrator, so agent-side `seon.db/transact!`,
   `seon.db/query`, `seon.db/pull-by-name`, `seon.db/pull-many-by-name`
   transparently route through the relay (Phase 3 step 9).

   We `intern` + `setDynamic` instead of `(def ^:dynamic *ctx* ...)` because
   `nrepl-eval!` treats stderr warnings (\"name suggests dynamic\") as errors."
  [ns-sym & {:keys [watch? relay-port]}]
  (str "(do "
       (when relay-port
         (str "(require '[seon.db.relay :as seon.db.relay]) "
              "(seon.db.relay/connect! "
              "  {:seon.db.relay/host \"127.0.0.1\" "
              "   :seon.db.relay/port " relay-port "}) "))
       "(create-ns '" ns-sym ") "
       "(in-ns '" ns-sym ") "
       "(clojure.core/refer-clojure) "
       "(let [a (atom {}) "
       "      v (atom 0)] "
       "  (intern '" ns-sym " '*ctx* a) "
       "  (intern 'user '*ctx* a) "
       "  (intern '" ns-sym " '*ctx-version* v) "
       "  (intern 'user '*ctx-version* v) "
       (when watch?
         (str "  (add-watch a :seon.session/version "
              "    (fn [_k# _r# _o# _n#] (swap! v inc))) "))
       "  nil) "
       "(.setDynamic (resolve (symbol \"" ns-sym "\" \"*ctx*\")) true) "
       "(.setDynamic (resolve (symbol \"user\" \"*ctx*\")) true) "
       "(.setDynamic (resolve (symbol \"" ns-sym "\" \"*ctx-version*\")) true) "
       "(.setDynamic (resolve (symbol \"user\" \"*ctx-version*\")) true) "
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
  [{::keys [namespace checkpoint-interval-ms]}]
  (let [session-id (::runtime/id (runtime/generate-id {}))
        started-at (java.util.Date.)
        port (find-free-port!)
        interval-ms (if (nil? checkpoint-interval-ms)
                      default-checkpoint-interval-ms
                      checkpoint-interval-ms)
        watch? (pos? interval-ms)
        scheduled-fut (atom nil)
        ;; One TCP relay server per agent. Created before the spawn so the
        ;; bootstrap code can hand the agent a valid port to connect back to.
        relay-handle (relay/start-server! {})
        relay-port (:seon.db.relay/port relay-handle)]
    ;; Persist :starting before the (slow) spawn so the row is visible.
    (db/transact! :seon.session
                  [{::agent session-id
                    ::namespace namespace
                    ::started-at started-at
                    ::status :starting}])
    (let [jvm (try (#'pool/spawn-agent-jvm! port)
                   (catch Exception spawn-err
                     (release-reserved-port! port)
                     (relay/stop-server! relay-handle)
                     (try (db/transact! :seon.session
                                        [{::agent session-id ::status :crashed}])
                          (catch Exception _))
                     (throw spawn-err)))
          ^Process proc (:seon.flow.pool/process jvm)
          pid (:seon.flow.pool/pid jvm)]
      ;; JVM is bound to the port; reservation no longer needed.
      (release-reserved-port! port)
      (try
        (pool/nrepl-eval! port (agent-bootstrap-code namespace
                                                     :watch? watch?
                                                     :relay-port relay-port))
        (bind-namespace! port namespace)
        (swap! live-sessions assoc session-id
               {::session-id session-id
                ::namespace namespace
                ::port port
                ::pid pid
                ::last-checkpoint-version 0
                ::relay-handle relay-handle
                :process proc})
        (register-with-orchestrator! session-id namespace port started-at)
        (db/transact! :seon.session
                      [{::agent session-id
                        ::port port
                        ::pid pid
                        ::status :running}])
        (when watch?
          (let [fut (schedule-checkpoint-task! session-id interval-ms)]
            (reset! scheduled-fut fut)
            (swap! live-sessions assoc-in
                   [session-id ::checkpoint-future] fut)))
        (log/info "Launched agent session"
                  {:session-id session-id :namespace namespace
                   :port port :pid pid
                   :checkpoint-interval-ms (when watch? interval-ms)})
        {::session-id session-id
         ::nrepl-port port
         ::pid pid}
        (catch Exception setup-err
          (log/error "Failed to set up agent session, killing JVM"
                     {:session-id session-id :namespace namespace
                      :error (.getMessage setup-err)})
          (when-let [^java.util.concurrent.ScheduledFuture fut @scheduled-fut]
            (try (.cancel fut false) (catch Exception _)))
          (try (.destroy proc) (catch Exception _))
          (try (relay/stop-server! relay-handle) (catch Exception _))
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
    ;; Cancel auto-checkpoint scheduler first so it can't race with shutdown.
    (cancel-checkpoint-task! entry)
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
    ;; Tear down the per-agent relay server now that the JVM is gone.
    (when-let [rh (::relay-handle entry)]
      (try (relay/stop-server! rh) (catch Exception _)))
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
