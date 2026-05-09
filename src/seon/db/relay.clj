(ns seon.db.relay
  "Cross-JVM `seon.db` relay (Phase 3 step 9 of the datahike migration).

   Lets code running inside an agent JVM call `seon.db/transact!`,
   `seon.db/query`, `seon.db/pull-by-name`, or `seon.db/pull-many-by-name`
   against any flow-managed db (`:seon.session`, `:seon.orchestrator`, etc.)
   even though the agent JVM has no local datahike flow.

   Wire model: one relay server per agent JVM. The orchestrator opens a
   length-prefixed Nippy TCP socket via `seon.flow.harness.channel/start-server!`,
   passes the chosen port into the agent at bootstrap, and the agent connects
   back. Requests flow agent -> orchestrator; the orchestrator dispatches to
   the resident `seon.db/<op>` (which routes through the datahike flow), then
   replies. Per-agent state means no multiplexing, no shared accept loop, and
   tear-down is `close!`.

   Two sides:
   - `start-server!`     — orchestrator spawns one per agent during `launch!`.
   - `connect!`/`request!` — agent calls these from the bootstrap; sets
     `*relay-active?*` so the rebound `seon.db` ops route via this ns."
  (:require [clojure.core.async :as a]
            [seon.flow.harness.channel :as channel]
            [seon.schema :as schema]
            [taoensso.nippy :as nippy]
            [taoensso.timbre :as log]))

;;; ---------------------------------------------------------------------------
;;; Schemas
;;; ---------------------------------------------------------------------------

(schema/register! ::request-id :uuid)
(schema/register! ::op [:enum :transact! :query :pull-by-name :pull-many-by-name])
(schema/register! ::db-name :keyword)
(schema/register! ::args [:vector :any])
(schema/register! ::timeout-ms [:int {:min 1}])
(schema/register! ::value :any)
(schema/register! ::error-message :string)
(schema/register! ::error-type :keyword)
(schema/register! ::error-data [:map-of :any :any])
(schema/register! ::status [:enum :ok :error])
(schema/register! ::host :string)
(schema/register! ::port [:int {:min 0 :max 65535}])

(schema/register! ::request
                  [:map
                   [::request-id ::request-id]
                   [::op ::op]
                   [::db-name ::db-name]
                   [::args ::args]
                   [::timeout-ms {:optional true} ::timeout-ms]])

(schema/register! ::reply
                  [:map
                   [::request-id ::request-id]
                   [::status ::status]
                   [::value {:optional true} ::value]
                   [::error-message {:optional true} ::error-message]
                   [::error-type {:optional true} ::error-type]
                   [::error-data {:optional true} ::error-data]])

(schema/register! ::start-server-request :map)
(schema/register! ::start-server-response
                  [:map
                   [::port ::port]
                   [:close-fn fn?]])

(schema/register! ::stop-server-request
                  [:map
                   [:close-fn fn?]])
(schema/register! ::stop-server-response :nil)

(schema/register! ::connect-request
                  [:map
                   [::host {:optional true} ::host]
                   [::port ::port]])
(schema/register! ::connect-response :nil)

(schema/register! ::disconnect-request :map)
(schema/register! ::disconnect-response :nil)

(schema/register! ::request-request
                  [:map
                   [::op ::op]
                   [::db-name ::db-name]
                   [::args ::args]
                   [::timeout-ms {:optional true} ::timeout-ms]])
(schema/register! ::request-response :any)

;;; ---------------------------------------------------------------------------
;;; Server side (orchestrator)
;;; ---------------------------------------------------------------------------

(defn- safe-ex-data
  "Strip non-Nippy-serializable entries from an ex-data map so the reply
   round-trips. Malli explainers and similar can carry fns/classes."
  [data]
  (when (map? data)
    (into {}
          (filter (fn [[_ v]]
                    (try (nippy/fast-freeze v) true
                         (catch Throwable _ false))))
          data)))

(def ^:private op->var
  "Resolve `seon.db` ops once at load time so each request avoids
   `requiring-resolve` overhead and a missing op fails loudly here."
  (delay
    {:transact!         (requiring-resolve 'seon.db/transact!)
     :query             (requiring-resolve 'seon.db/query)
     :pull-by-name      (requiring-resolve 'seon.db/pull-by-name)
     :pull-many-by-name (requiring-resolve 'seon.db/pull-many-by-name)}))

(defn- dispatch-request
  "Resolve the relay op to its `seon.db` fn and call it. Returns the value or
   throws."
  [{::keys [op db-name args]}]
  (let [op-var (or (get @op->var op)
                   (throw (ex-info "Unknown relay op" {::op op})))]
    (apply op-var db-name args)))

(defn- build-error-reply [request-id ^Throwable t]
  (let [base {::request-id request-id
              ::status :error
              ::error-message (or (.getMessage t) (str (class t)))
              ::error-type :execution}
        data (safe-ex-data (ex-data t))]
    (if (seq data)
      (assoc base ::error-data data)
      base)))

(defn- handle-request
  "Run one request on a thread, write the reply to out-ch."
  [out-ch req]
  (a/thread
    (let [request-id (::request-id req)
          reply (try
                  {::request-id request-id
                   ::status :ok
                   ::value (dispatch-request req)}
                  (catch Throwable t
                    (log/warn "Relay request failed"
                              {:request-id request-id
                               :op (::op req)
                               :db-name (::db-name req)
                               :error (.getMessage t)})
                    (build-error-reply request-id t)))]
      (a/>!! out-ch reply))))

(defn start-server!
  "Open a TCP relay server on a random port. Returns a server handle.

   Spawns a server thread that reads requests from the socket, dispatches
   each on a thread (so they run in parallel), and writes the reply back.
   The server lives until `close-fn` is called.

   Request keys: none (pass `{}`).

   Response keys:
     ::port      — the bound port (advertise this to the agent JVM).
     :close-fn   — no-arg fn that tears the server + socket down."
  {:malli/schema [:=> [:cat ::start-server-request] ::start-server-response]}
  [{}]
  (let [{::channel/keys [port in-ch out-ch close!]}
        (channel/start-server! {::channel/port 0})]
    (a/go-loop []
      (when-let [req (a/<! in-ch)]
        (handle-request out-ch req)
        (recur)))
    (log/info "Started seon.db relay server" {:port port})
    {::port port
     :close-fn (fn []
                 (close!)
                 (log/info "Stopped seon.db relay server" {:port port}))}))

(defn stop-server!
  "Tear down a server handle returned by `start-server!`."
  {:malli/schema [:=> [:cat ::stop-server-request] ::stop-server-response]}
  [{:keys [close-fn]}]
  (close-fn)
  nil)

;;; ---------------------------------------------------------------------------
;;; Client side (agent JVM)
;;; ---------------------------------------------------------------------------

(defonce ^:private client-state
  ;; {::out-ch ::in-ch ::close! ::pending (atom {request-id -> promise})}
  ;; nil when no relay is connected.
  (atom nil))

(def ^:dynamic *relay-active?*
  "True inside the agent JVM after `connect!` succeeds. `seon.db` checks this
   to decide whether to route ops over the relay. Never set on the
   orchestrator JVM."
  false)

(defn- start-reply-loop!
  "Drain replies from the in-ch and deliver each to the matching pending
   promise. When the channel closes, fail every still-pending promise with
   :relay-disconnected so callers don't hang."
  [in-ch pending]
  (a/thread
    (loop []
      (if-let [reply (a/<!! in-ch)]
        (let [rid (::request-id reply)]
          (when-let [p (get @pending rid)]
            (swap! pending dissoc rid)
            (deliver p reply))
          (recur))
        ;; channel closed: fail outstanding promises
        (let [outstanding @pending]
          (reset! pending {})
          (doseq [[rid p] outstanding]
            (deliver p {::request-id rid
                        ::status :error
                        ::error-message "Relay connection closed"
                        ::error-type :relay-disconnected})))))))

(declare request!)

(defn- intern-agent-db-shim!
  "Inside an agent JVM the heavy `seon.db` ns can't load (it pulls integrant +
   datahike, which the lean `:agent` deps alias deliberately omits). To keep
   user code portable — `(seon.db/transact! :seon.orchestrator [...])` — this
   creates the `seon.db` namespace lazily and interns the four supported ops
   as thin wrappers over `request!`.

   No-op on the orchestrator: `seon.db` is already loaded with its real
   definitions, so we never reach here unless `connect!` is called."
  []
  (let [target-ns (create-ns 'seon.db)]
    (intern target-ns
            (with-meta 'transact! {:doc "Agent-side relay shim: routes to orchestrator."})
            (fn ([db-name tx-data]
                 (request! {::op :transact! ::db-name db-name ::args [tx-data]}))
                ([db-name tx-data _opts]
                 (request! {::op :transact! ::db-name db-name ::args [tx-data]}))))
    (intern target-ns
            (with-meta 'query {:doc "Agent-side relay shim: routes to orchestrator."})
            (fn [db-name datalog-query & inputs]
              (request! {::op :query ::db-name db-name
                         ::args (into [datalog-query] inputs)})))
    (intern target-ns
            (with-meta 'pull-by-name {:doc "Agent-side relay shim: routes to orchestrator."})
            (fn [db-name selector eid]
              (request! {::op :pull-by-name ::db-name db-name
                         ::args [selector eid]})))
    (intern target-ns
            (with-meta 'pull-many-by-name {:doc "Agent-side relay shim: routes to orchestrator."})
            (fn [db-name selector eids]
              (request! {::op :pull-many-by-name ::db-name db-name
                         ::args [selector eids]})))
    nil))

(defn connect!
  "Connect this JVM (the agent) to an orchestrator's relay server. After this
   returns, `*relay-active?*` is true, `request!` works, and the four
   supported `seon.db` ops are interned as agent-side shims so user code
   reads as `(seon.db/transact! ...)`.

   Request keys:
     ::host — defaults to `\"127.0.0.1\"`.
     ::port — port advertised by the orchestrator's `start-server!`."
  {:malli/schema [:=> [:cat ::connect-request] :nil]}
  [{::keys [host port]}]
  ;; Close any prior connection so we don't leak the previous socket/loop.
  (when-let [{::keys [close!]} @client-state]
    (try (close!) (catch Exception _))
    (reset! client-state nil))
  (let [host (or host "127.0.0.1")
        ch (channel/connect! {::channel/host host ::channel/port port})
        pending (atom {})]
    (start-reply-loop! (::channel/in-ch ch) pending)
    (reset! client-state {::out-ch (::channel/out-ch ch)
                          ::in-ch (::channel/in-ch ch)
                          ::close! (::channel/close! ch)
                          ::pending pending})
    (alter-var-root #'*relay-active?* (constantly true))
    (intern-agent-db-shim!)
    (log/info "seon.db relay connected" {:host host :port port})
    nil))

(defn disconnect!
  "Close the relay connection and clear `*relay-active?*`. No-op if not
   connected. Returns nil."
  {:malli/schema [:=> [:cat ::disconnect-request] ::disconnect-response]}
  [{}]
  (when-let [{::keys [close!]} @client-state]
    (close!))
  (reset! client-state nil)
  (alter-var-root #'*relay-active?* (constantly false))
  nil)

(defn request!
  "Send a relay request, block on the reply, and return the value. Throws
   ex-info on relay error or timeout.

   Request keys:
     ::op         — :transact! | :query | :pull-by-name | :pull-many-by-name
     ::db-name    — flow-managed db keyword (e.g. :seon.orchestrator)
     ::args       — vector of args after db-name (matches each fn's signature)
     ::timeout-ms — optional (default 30000)"
  {:malli/schema [:=> [:cat ::request-request] ::request-response]}
  [{::keys [op db-name args timeout-ms]}]
  (let [{::keys [out-ch pending]}
        (or @client-state
            (throw (ex-info "Relay not connected (no orchestrator socket)"
                            {::error-type :relay-not-connected})))
        timeout-ms (or timeout-ms 30000)
        rid (random-uuid)
        p (promise)
        envelope {::request-id rid
                  ::op op
                  ::db-name db-name
                  ::args (vec args)
                  ::timeout-ms timeout-ms}]
    (swap! pending assoc rid p)
    (try
      (when-not (a/>!! out-ch envelope)
        (throw (ex-info "Relay out channel closed before send"
                        {::error-type :relay-disconnected})))
      (let [reply (deref p timeout-ms ::timed-out)]
        (cond
          (= reply ::timed-out)
          (throw (ex-info "Relay request timed out"
                          {::error-type :timeout
                           ::request-id rid
                           ::timeout-ms timeout-ms}))

          (= :ok (::status reply))
          (::value reply)

          :else
          (throw (ex-info (or (::error-message reply) "Relay request failed")
                          {::error-type (or (::error-type reply) :execution)
                           ::request-id rid
                           ::error-data (::error-data reply)}))))
      (finally
        (swap! pending dissoc rid)))))
