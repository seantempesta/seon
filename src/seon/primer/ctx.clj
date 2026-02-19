(ns seon.primer.ctx
  "Ctx management - multi-session atoms with Datalevin persistence via seon.ctx.

  Each primer session is backed by a seon.ctx instance that handles
  debounced persistence and SSE push. The sessions atom provides a
  unified view for SSE refresh triggers."
  (:refer-clojure :exclude [get get-in assoc! dissoc!])
  (:require [taoensso.timbre :as log]
            [seon.ctx :as ctx]))

;; Datalevin connection (set on system start)
(defonce ^:private dl-conn (atom nil))

;; Track which session IDs have ctx instances
(defonce ^:private session-ids (atom #{}))

(defn- instance-id [session-id]
  (str "primer:" session-id))

(defn init!
  "Initialize ctx system with Datalevin connection. Called by Integrant."
  [conn]
  (reset! dl-conn conn)
  (log/info "Primer ctx system initialized with Datalevin"))

(declare create!)

;;; === Core API ===

(defn get
  "Get ctx for session. Returns nil if not found."
  [session-id]
  (ctx/get-value {::ctx/instance-id (instance-id session-id)}))

(defn get-in
  "Get nested value from session ctx."
  [session-id path]
  (clojure.core/get-in (get session-id) path))

(defn update!
  "Update session ctx. Creates session if doesn't exist.
  Returns updated ctx."
  [session-id f & args]
  (when-not (contains? @session-ids session-id)
    ;; Auto-create session on first update
    (create! session-id {}))
  (ctx/update! {::ctx/instance-id (instance-id session-id)
                ::ctx/f f
                ::ctx/args (vec args)}))

(defn update-in!
  "Update nested value in session ctx."
  [session-id path f & args]
  (apply update! session-id update-in path f args))

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
  (let [iid (instance-id session-id)
        data (merge {:session/id session-id
                     :session/created-at (java.time.Instant/now)}
                    initial-data)]
    ;; Destroy existing instance if any
    (ctx/destroy! {::ctx/instance-id iid})
    ;; Create new ctx instance with Datalevin persistence
    (ctx/create! {::ctx/conn @dl-conn
                  ::ctx/instance-id iid
                  ::ctx/initial-value data
                  ::ctx/persist? (some? @dl-conn)
                  ::ctx/sse-push? true})
    (swap! session-ids conj session-id)
    data))

(defn destroy!
  "Remove session from memory."
  [session-id]
  (ctx/destroy! {::ctx/instance-id (instance-id session-id)})
  (swap! session-ids disj session-id))

;;; === Persistence ===

(defn checkpoint!
  "Manually persist session to Datalevin."
  [session-id]
  (when @dl-conn
    (ctx/persist! {::ctx/conn @dl-conn
                   ::ctx/instance-id (instance-id session-id)})))

(defn checkpoint-all!
  "Checkpoint all active sessions."
  []
  (doseq [sid @session-ids]
    (checkpoint! sid)))

;;; === Recovery ===

(defn load!
  "Load session from Datalevin into memory. Returns ctx or nil."
  [session-id]
  (when-let [data (ctx/load! {::ctx/conn @dl-conn
                              ::ctx/instance-id (instance-id session-id)})]
    ;; Create a live ctx instance with the loaded data
    (create! session-id data)
    data))

;;; === Temporal Queries (simplified - no bitemporal in Datalevin) ===

(defn history
  "Get checkpoint history for session.
  Note: Datalevin doesn't support bitemporal queries.
  Returns empty vector (current state available via `get`)."
  [_session-id]
  [])

(defn list-sessions
  "List all active session IDs."
  []
  @session-ids)
