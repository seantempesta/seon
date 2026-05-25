(ns sidecar-poc.datahike
  "In-guest overlay that mirrors `datahike.api` for the 9 APIs the V0 audit
   identified: create-database / connect / transact! / q / pull / entity /
   listen! / unlisten! / @conn-deref + schema / reverse-schema / pull-many /
   filter as bonus capabilities.

   Agent code under wasm requires this namespace as `[sidecar-poc.datahike :as d]`
   and reads/writes through a familiar shape; every call routes through the
   WIT bridge in `sidecar-poc.wit` to the JVM writer.

   Semantic notes:

   - A `db` value here is a plain map `{:basis-t Long :conn conn}`. It is
     immutable. Queries against an older `db` value use the writer's `:basis-t`
     snapshot read path (`d/as-of` server-side).

   - A `conn` is a wrapper map `{:basis-t (atom Long) :listeners (atom {})}`.
     The basis-t atom is bumped on every observed tx event (own or otherwise).
     `listen!` registers a per-key callback that fires from the in-guest fan-out
     loop — exactly the shape `datahike.core/listen!` uses on the JVM, modulo
     handler input keys: instead of `{:db-before db-before :db-after db-after}`,
     handlers receive `{:basis-t Long :basis-t-before Long :tx-data [...] :tx-meta {...}}`.

   - Listener fan-out: the FIRST `listen!` call spins a background loop that
     polls `next-tx-event` and dispatches into the local listener map. On a
     guest that supports host-initiated callbacks (Phase 4 design option (a)),
     this loop is unnecessary; the fan-out shape is identical."
  (:require [sidecar-poc.wit :as wit]))

;; ---------- db values ----------

(defn db
  "Snapshot the connection's current basis-t and return an immutable db value.
   Subsequent queries against this value will read at that basis-t (via the
   writer's `:basis-t` field on `q`/`pull`)."
  [conn]
  {:basis-t @(:basis-t conn) :conn conn})

(defn- basis-t-of [db-or-conn]
  ;; Accepts a db value `{:basis-t N}` or a conn `{:basis-t (atom N)}`.
  (let [bt (:basis-t db-or-conn)]
    (cond
      (number? bt) bt
      (some? bt)   @bt
      :else        0)))

(defn- conn-of [db-or-conn]
  ;; Resolve a conn from either shape.
  (or (:conn db-or-conn) db-or-conn))

;; ---------- create-database / connect ----------

(defn create-database
  "On the sidecar, the writer subprocess creates the DB at startup; the guest's
   `create-database` is informational. We return the cfg so callers that thread
   it into `connect` still work."
  [cfg]
  cfg)

(defn connect
  "Return a logical connection wrapper. The cfg argument is ignored — the
   sidecar host already wired this guest to its writer.

   Optional opts (currently none honored on the sidecar; reserved for future
   use such as filtered subscriptions)."
  ([cfg] (connect cfg {}))
  ([_cfg _opts]
   (let [;; Probe the writer to populate initial basis-t.
         schema-result (try (wit/schema-call) (catch :default _ nil))
         ;; The schema response itself doesn't carry basis-t through the
         ;; current EDN-string return shape; the first transact/listen will
         ;; correct it. Start at 0; first tx event updates.
         conn {:basis-t   (atom 0)
               :listeners (atom {})
               :sub       (atom nil)
               :stopped?  (atom false)
               :schema    (atom schema-result)}]
     conn)))

(defn stop!
  "Signal the conn's listener loop to terminate. Agents call this before
   returning their final report so the wstd executor sees an empty task
   queue and `async_exported_function`'s block_on can complete."
  [conn]
  (reset! (:stopped? conn) true))

;; ---------- transact! ----------

(defn- normalize-tx
  "Accepts either (transact! conn tx-data) or (transact! conn arg-map)
   where arg-map is {:tx-data ... :tx-meta ...}.  Returns
   `[tx-data-edn tx-meta-edn]`."
  [arg]
  (if (and (map? arg) (contains? arg :tx-data))
    [(pr-str (:tx-data arg))
     (when-let [m (:tx-meta arg)] (pr-str m))]
    [(pr-str arg) nil]))

(defn transact!
  "Transact tx-data. Returns the parsed tx-report map; bumps the conn's
   basis-t atom so subsequent `(db conn)` snapshots see the new state."
  ([conn tx-data] (transact! conn tx-data nil))
  ([conn tx-data tx-meta]
   (let [[tx-edn _]    (normalize-tx tx-data)
         tx-meta-edn   (or (when tx-meta (pr-str tx-meta))
                           (second (normalize-tx tx-data)))
         request-id    (str (random-uuid))
         report        (wit/transact-call tx-edn (or tx-meta-edn "") request-id)
         new-bt        (:basis-t report)]
     (when new-bt (reset! (:basis-t conn) new-bt))
     (assoc report :request-id request-id))))

;; ---------- q ----------

(defn q
  "Run a Datalog query. `db-or-conn` can be a db value (queries against the
   snapshot) or a conn (queries against the current basis-t)."
  [query db-or-conn & args]
  (let [bt (basis-t-of db-or-conn)]
    (wit/q-call (pr-str query) (vec args) bt)))

;; ---------- pull / pull-many / entity ----------

(defn- eid->edn
  "Coerce an eid to the EDN-string the writer expects. Ints stay ints
   (their pr-str is just the int); lookup-refs (vectors) get pr-str'd;
   strings pass through."
  [eid]
  (cond
    (string? eid) eid
    :else         (pr-str eid)))

(defn pull
  ([db selector eid] (pull db selector eid {}))
  ([db selector eid _opts]
   (wit/pull-call (pr-str selector) (eid->edn eid) (basis-t-of db))))

(defn pull-many [db selector eids]
  (wit/pull-many-call (pr-str selector)
                      (mapv eid->edn eids)
                      (basis-t-of db)))

(defn entity
  "Eager entity replacement for datahike's lazy `d/entity`. Returns the
   realized map (with component refs expanded to depth 1) or nil if missing.
   This matches V0's call sites that read 1-2 scalar attrs or shallow
   component-ref vectors (see audit Reason B)."
  ([db ref] (entity db ref {}))
  ([db ref {:keys [selector depth]
            :or {selector "" depth 1}}]
   (let [sel (if (= selector "") "" (pr-str selector))]
     (wit/entity-pull-call (eid->edn ref) sel depth (basis-t-of db)))))

;; ---------- schema / reverse-schema ----------

(defn schema [_db] (wit/schema-call))
(defn reverse-schema [_db] (wit/reverse-schema-call))

;; ---------- filter ----------

(defn filter
  "Build a filtered-db. `pred-query` is an EDN Datalog query returning rows
   of `[?e]` — the eids to retain. **NOT** a `(fn [db datom] -> bool)` —
   see PROTOCOL.md for why. Returns a filtered-db value the caller can pass
   to `q` (which will route to `q-filtered` server-side via the handle).

   Caller is responsible for releasing the handle with `release-filter!`
   when done; on long-lived agents, the handle survives across tx commits
   but its filter set is fixed at creation time. If the underlying
   predicate's result would have changed, the caller must rebuild the
   filtered db."
  [db pred-query & args]
  (let [handle (wit/db-filter-call (pr-str pred-query) (vec args))]
    {:filtered? true
     :handle    handle
     :basis-t   (basis-t-of db)
     :conn      (conn-of db)}))

(defn release-filter! [filtered-db]
  (when-let [h (:handle filtered-db)]
    (wit/filter-release-call h)))

;; Extend q so it handles filtered-db values.
(defn q-on
  "Like `q` but routes through `q-filtered` when given a filtered-db. Most
   call sites should use `q`; this is the explicit form."
  [query filtered-db & args]
  (wit/q-filtered-call (:handle filtered-db) (pr-str query) (vec args)))

;; ---------- listen! / unlisten! ----------
;;
;; One upstream subscription per conn; many local callbacks. On the first
;; `listen!` call we spin a background loop that polls `next-tx-event` and
;; fans out into the local listener map. `unlisten!` is a local map removal.

(defn- ensure-listener-loop! [conn]
  (when (nil? @(:sub conn))
    (let [handle (wit/subscribe-tx-call (str "guest-" (random-uuid)))]
      (reset! (:sub conn) handle)
      ;; Spin a JS Promise-based loop (no core.async; we're guest-side).
      ;; The host's `next-tx-event` is sync from QuickJS's perspective and
      ;; uses bounded polling internally (~50ms). On timeout it throws a
      ;; protocol error tagged "no-event"; we swallow that and re-loop on
      ;; a microtask so the agent's main loop gets to run.
      (letfn [(sleep-tick []
                (js/Promise.
                  (fn [resolve _] (js/setTimeout #(resolve nil) 25))))
              (loop-step []
                (cond
                  @(:stopped? conn)
                  (js/Promise.resolve nil)

                  :else
                  (-> (js/Promise.resolve nil)
                      (.then (fn [_]
                               (try
                                 (let [ev (wit/next-tx-event-call handle)
                                       new-bt (:basis-t ev)]
                                   (when new-bt
                                     (reset! (:basis-t conn) new-bt))
                                   (doseq [[_k handler] @(:listeners conn)]
                                     (try (handler (assoc ev :conn conn))
                                          (catch :default e
                                            (js/console.warn
                                              "listener handler threw:" (str e)))))
                                   :event)
                                 (catch :default e
                                   (let [msg (or (.-message e) (str e))]
                                     (if (re-find #"no-event" msg)
                                       :no-event
                                       (do (js/console.warn "next-tx-event:" msg)
                                           :no-event)))))))
                      ;; If no event was available, yield via setTimeout to
                      ;; let setTimeout-based main loops run. If we did
                      ;; deliver an event, immediately spin to drain any
                      ;; more queued events.
                      (.then (fn [outcome]
                               (if (= outcome :no-event)
                                 (sleep-tick)
                                 (js/Promise.resolve nil))))
                      (.then loop-step))))]
        (loop-step)))))

(defn listen!
  "Register a handler for tx events. Handler receives a map shaped like:

       {:basis-t N
        :basis-t-before N-1
        :db-name \"default\"
        :datoms-added N
        :datoms-retracted N
        :tx-data [[e a v t op] ...]
        :tx-meta {db/txInstant ... db/commitId ...}
        :request-id <string-or-nil>
        :conn conn-ref}

   This differs from JVM `datahike.core/listen!`'s `{:db-before :db-after}`
   in the same way the audit's Reason C calls out: db handles become
   basis-t integers, queries take :basis-t for consistent reads."
  ([conn handler]
   (let [k (random-uuid)]
     (listen! conn k handler)
     k))
  ([conn key handler]
   (swap! (:listeners conn) assoc key handler)
   (ensure-listener-loop! conn)
   key))

(defn unlisten!
  "Drop a listener. Local map removal — no wire call needed because the
   fan-out happens entirely in the guest."
  [conn key]
  (swap! (:listeners conn) dissoc key)
  ;; If no listeners remain, optionally unsubscribe upstream. We leave the
  ;; subscription open to avoid thrash; the host registry handles cleanup
  ;; on instance unload.
  nil)
