(ns seon.primer.ctx
  "Ctx management - multi-session atom with auto XTDB persistence.

  The atom is permissive - store anything. Background sync to XTDB
  skips non-serializable values with warnings. Agent code should
  regenerate runtime-only data on load."
  (:refer-clojure :exclude [get get-in assoc! dissoc!])
  (:require [taoensso.timbre :as log]
            [seon.db.node :as db]
            [xtdb.api :as xt]))

;; All sessions in memory: {session-id -> ctx-map}
(defonce sessions (atom {}))

;; Reference to primer XTDB node (set on system start)
(defonce ^:private primer-node (atom nil))

(defn init!
  "Initialize ctx system with XTDB node. Called by Integrant."
  [node]
  (reset! primer-node node)
  (log/info "Ctx system initialized"))

;;; === Core API (atom-based, instant) ===

(defn get
  "Get ctx for session. Returns nil if not found."
  [session-id]
  (clojure.core/get @sessions session-id))

(defn get-in
  "Get nested value from session ctx."
  [session-id path]
  (clojure.core/get-in @sessions (cons session-id path)))

(defn update!
  "Update session ctx. Creates session if doesn't exist.
  Returns updated ctx."
  [session-id f & args]
  (let [result (apply swap! sessions update session-id f args)]
    (clojure.core/get result session-id)))

(defn update-in!
  "Update nested value in session ctx."
  [session-id path f & args]
  (apply swap! sessions update-in (cons session-id path) f args)
  (get session-id))

(defn assoc!
  "Set key in session ctx."
  [session-id k v]
  (update! session-id assoc k v))

(defn dissoc!
  "Remove key from session ctx."
  [session-id k]
  (update! session-id dissoc k))

;;; === Session Lifecycle ===

(defn create!
  "Create new session with initial data."
  [session-id initial-data]
  (swap! sessions assoc session-id
         (merge {:session/id session-id
                 :session/created-at (java.time.Instant/now)}
                initial-data))
  (get session-id))

(defn destroy!
  "Remove session from memory."
  [session-id]
  (swap! sessions dissoc session-id))

;;; === Persistence (background, best-effort) ===

(defn- serializable?
  "Check if value can be serialized to XTDB (EDN-compatible)."
  [v]
  (try
    (pr-str v)
    true
    (catch Exception _ false)))

(defn- filter-serializable
  "Filter ctx to only serializable keys. Warns on skipped keys."
  [ctx]
  (reduce-kv
   (fn [acc k v]
     (if (serializable? v)
       (assoc acc k v)
       (do
         (log/warn "Skipping non-serializable key in ctx persistence"
                   {:key k :type (type v)})
         acc)))
   {}
   ctx))

(defn checkpoint!
  "Save session to XTDB. Skips non-serializable values with warning."
  [session-id]
  (when-let [ctx (get session-id)]
    (let [persistable (filter-serializable ctx)]
      (db/execute-tx! @primer-node
                      [[:put-docs :primer-sessions
                        (assoc persistable
                               :xt/id session-id
                               :session/checkpointed-at (java.time.Instant/now))]])
      (log/debug "Checkpointed session" {:session-id session-id}))))

(defn checkpoint-all!
  "Checkpoint all active sessions."
  []
  (doseq [session-id (keys @sessions)]
    (checkpoint! session-id)))

;;; === Recovery (load from XTDB) ===

(defn load!
  "Load session from XTDB into atom. Returns ctx or nil."
  [session-id]
  (when-let [ctx (db/entity @primer-node :primer-sessions session-id)]
    (swap! sessions assoc session-id ctx)
    ctx))

(defn load-at!
  "Load historical session state into atom.
  Note: Temporal queries in XTDB v2 require specific query options."
  [session-id as-of-instant]
  (when-let [ctx (db/entity @primer-node :primer-sessions session-id
                            {:current-time as-of-instant})]
    (swap! sessions assoc session-id ctx)
    ctx))

;;; === Temporal Queries (read-only, doesn't affect atom) ===

(defn at
  "Get session ctx at point in time. Doesn't modify atom."
  [session-id as-of-instant]
  (db/entity @primer-node :primer-sessions session-id
             {:current-time as-of-instant}))

(defn history
  "Get checkpoint history for session."
  [session-id]
  (db/entity-history @primer-node :primer-sessions session-id))

;;; === Background Auto-Sync ===

(defonce ^:private sync-future (atom nil))
(defonce ^:private sync-running (atom false))

(defn start-auto-sync!
  "Start background thread that checkpoints all sessions periodically."
  [interval-ms]
  (reset! sync-running true)
  (reset! sync-future
          (future
            (while @sync-running
              (try
                (Thread/sleep interval-ms)
                (when @sync-running
                  (checkpoint-all!))
                (catch InterruptedException _
                  (log/debug "Auto-sync interrupted"))
                (catch Exception e
                  (log/error e "Error in ctx auto-sync")))))))

(defn stop-auto-sync!
  "Stop the background auto-sync thread."
  []
  (reset! sync-running false)
  (when-let [f @sync-future]
    (future-cancel f)
    (reset! sync-future nil)))

;;; === SSE Integration ===

(defonce ^:private _sessions-watch
  (add-watch sessions :sse-auto-refresh
             (fn [_ _ old-val new-val]
               (when (not= old-val new-val)
                 (require 'seon.web.sse)
                 ((resolve 'seon.web.sse/refresh-all!))))))
