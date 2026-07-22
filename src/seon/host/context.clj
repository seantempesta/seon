(ns seon.host.context
  "Own the JVM agent host's shared sci base and per-agent contexts.

   One base context is built once per host process: the portable pure slice
   of the `my.*` toolkit loaded from its real sources, plus every capability
   namespace provisioned through the ONE wrapper registry
   ([[register-wrappers!]]). The registry backs the base's sci `:load-fn`:
   registering a namespace makes it lazily require-able in EVERY live
   context (the load-fn closure is shared by all forks — probed in the seam
   study), first require injects the cached wrapper vars, and re-registering
   an implementation upgrades the shared vars in place so existing
   contexts' next calls use it. The `seon.db` wrappers are synchronous UDS
   round-trips to the cluster writer through the one existing
   `seon.db.transport.uds` client. Every agent context is a `sci/fork` of
   that base (persistent-structure sharing; forked defs stay private).

   Effectful capability calls carry `:seon.capability/op-id`. For
   `seon.db/transact!` the op-id IS the database protocol's durable
   idempotency receipt: it crosses the boundary as
   `:seon.db.protocol/request-id`, the writer stamps it on the committed
   transaction entity, and a repeated call with the same op-id returns the
   recorded outcome (`:seon.capability/replayed? true`) instead of
   re-executing — no second receipt entity exists.

   The durable agent is database facts. A context is a cache of those
   facts: park drops it, restore forks the base and replays the agent's
   home-ns corpus def sources ([[restore-context-defs!]] over
   [[agent-def-sources]] + [[replay-defs!]]).

   Recording (U4): [[start-eval-receipt!]] allocates a managed
   `:seon.eval/id` through the wire protocol's generated-candidates
   field and commits the `:running` receipt before a form may run;
   [[record-eval-terminal!]] terminalizes behind the receipt's CAS fence
   in ONE transaction with every program-graph row the form tees
   (`seon.host.record` builds the exact data the child tee writes).
   `seon.schema/register!` admits for real through the one bridge, and
   the registry diff around each form tees the canonical `:seon.schema`
   row."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [sci.core :as sci]
            [sci.ctx-store]
            [sci.interrupt :as interrupt]
            [seon.ai.provider :as ai.provider]
            [seon.ai.tokens :as tokens]
            [seon.content-hash :as content-hash]
            [seon.db.id :as db.id]
            [seon.db.protocol :as protocol]
            [seon.db.transport.uds :as uds]
            [seon.host.record :as record]
            [seon.repair.candidates :as candidates]
            [seon.schema :as schema]
            [seon.time :as time])
  (:import [java.util.concurrent Callable ExecutorService Executors]
           [java.util.concurrent.locks Condition ReentrantLock]))

(set! *warn-on-reflection* true)

(def ^:dynamic *agent-id*
  "The invocation's agent identity, bound around every host eval.

   Threads per-agent provenance through the shared wrapper closures:
   reads carry `:seon.db/user`/`:seon.db/process` so the writer's read
   spend attributes to the exact agent instead of the empty identity,
   and writes carry the same two references as transaction metadata —
   the one provenance vocabulary the pod stamps."
  nil)

(defn- provenance
  "The two durable provenance references for the bound agent, or nil."
  []
  (when *agent-id*
    {:seon.db/user [:seon.agent/id *agent-id*]
     :seon.db/process [:seon.db.process/id :seon.db.process/repl]}))

(defn- with-read-provenance
  "Attach the bound agent's read provenance to one protocol request."
  [request]
  (merge request (provenance)))

(schema/register! ::writer-socket-path [:string {:min 1}])
(schema/register! ::database-name ::protocol/database-name)
(schema/register! ::backend ::protocol/backend)
(schema/register! ::database-path ::protocol/database-path)
(schema/register! ::pool-state 'some?)
(schema/register! ::pool-lock 'some?)
(schema/register! ::pool-condition 'some?)
(schema/register! ::call-executor 'some?)
(schema/register! ::eval-generator 'some?)
(schema/register! ::projection-state 'some?)
(schema/register! ::session 'some?)
(schema/register! ::committed-basis :int)
(schema/register!
 ::writer
 [:map
  [::writer-socket-path ::writer-socket-path]
  [::database-name ::database-name]
  [::backend {:optional true} ::backend]
  [::database-path {:optional true} ::database-path]
  [::pool-state ::pool-state]
  [::pool-lock ::pool-lock]
  [::pool-condition ::pool-condition]
  [::call-executor ::call-executor]
  [::eval-generator ::eval-generator]])
(schema/register! ::ctx 'some?)
(schema/register! ::registry 'some?)
(schema/register! ::lib :symbol)
(schema/register! ::wrapper-fn 'fn?)
(schema/register! ::reconcile-ephemeral! 'fn?)
;; An SCI var may hold ordinary immutable data as well as a function. This is
;; the genuinely polymorphic interpreter-binding boundary; callers still use
;; the closed `::wrapper` union so a registration cannot supply both shapes.
(schema/register! ::wrapper-value :any)
(schema/register! ::arglists [:sequential [:vector :symbol]])
(schema/register! ::doc [:string {:min 1}])
(schema/register!
 ::wrapper
 [:or
  [:map {:closed true}
   [::wrapper-fn ::wrapper-fn]
   [::arglists {:optional true} ::arglists]
   [::doc {:optional true} ::doc]]
  [:map {:closed true}
   [::wrapper-value ::wrapper-value]
  [::arglists {:optional true} ::arglists]
   [::doc {:optional true} ::doc]]])
(schema/register! ::wrappers [:map-of :symbol ::wrapper])
(schema/register!
 ::register-request
 [:map {:closed true}
  [::registry ::registry]
  [::lib ::lib]
  [::wrappers ::wrappers]])
(schema/register!
 ::install-request
 [:map {:closed true}
  [::registry ::registry]
  [::ctx ::ctx]
  [::lib ::lib]])
(schema/register! :seon.capability/op-id [:string {:min 1}])
(schema/register! :seon.capability/replayed? :boolean)
(schema/register! ::files [:int {:min 0}])
(schema/register! ::pure-blocks [:int {:min 0}])
(schema/register! ::loaded [:int {:min 0}])
(schema/register! ::failed [:int {:min 0}])
(schema/register! ::excluded [:int {:min 0}])
(schema/register! ::source-path :string)
(schema/register! ::namespace :symbol)
(schema/register! ::status [:enum :loaded :failed :excluded])
(schema/register! ::reason [:string {:min 1}])
(schema/register!
 ::block
 [:map {:closed true}
  [::source-path ::source-path]
  [::namespace ::namespace]
  [::block-name :string]
  [::status ::status]
  [::reason {:optional true} ::reason]])
(schema/register! ::blocks [:vector ::block])
(schema/register!
 ::failures
 [:vector [:map {:closed true}
           [::source-path ::source-path]
           [::namespace ::namespace]
           [::block-name :string]
           [::failure :string]]])
(schema/register!
 ::report
 [:map {:closed true}
  [::files ::files]
  [::pure-blocks ::pure-blocks]
  [::loaded ::loaded]
  [::failed ::failed]
  [::excluded ::excluded]
  [::blocks ::blocks]
  [::failures ::failures]])
(schema/register!
 ::base
 [:map {:closed true}
  [::ctx ::ctx]
  [::report ::report]
  [::registry ::registry]])
(schema/register! ::def-sources [:vector :string])
(schema/register!
 ::replay-envelope
 [:map
  [:seon.eval/ok? :boolean]])
(schema/register! ::replay-envelopes [:vector ::replay-envelope])
(schema/register! ::materialized-function [:tuple ::ctx 'some?])
(schema/register! ::function-rows [:vector :map])

(def writer-pool-defaults
  "Hardware-derived host writer-pool defaults.

   W1's config-facts sweep will relocate these values into the
   aero-to-database configuration pipeline. The member count mirrors the
   writer executor's `cpu-workers` derivation because both processes share
   the machine."
  {::pool-size (max 1 (dec (.availableProcessors (Runtime/getRuntime))))
   ::pool-wait-timeout-ms 1000
   ::call-deadline-ms 120000
   ::request-conflict-backoff-ms 10})

(defn- initial-pool-state
  []
  {::closed? false
   ::members {}
   ::available []
   ::opening 0})

(defn writer-session
  "Build one host writer session over a lazy retained connection pool.

   Every member independently acquires the configured database and remains
   retained until eviction or session close. One leased member serves one
   round-trip at a time; other callers use other members up to the writer's
   useful read parallelism instead of serializing behind one channel."
  {:malli/schema [:=> [:cat [:map [::writer-socket-path ::writer-socket-path]
                             [::database-name ::database-name]
                             [::backend {:optional true} ::backend]
                             [::database-path {:optional true}
                              ::database-path]]]
                  ::writer]}
  [{::keys [writer-socket-path database-name backend database-path]}]
  (let [pool-size (::pool-size writer-pool-defaults)
        pool-lock (ReentrantLock.)]
    (cond-> {::writer-socket-path writer-socket-path
             ::database-name database-name
             ::pool-state (atom (initial-pool-state))
             ::pool-lock pool-lock
             ::pool-condition (.newCondition pool-lock)
             ::call-executor (Executors/newFixedThreadPool (int pool-size))
             ::eval-generator (atom nil)}
      backend (assoc ::backend backend)
      database-path (assoc ::database-path database-path))))

(defn- with-pool-lock
  [{::keys [pool-lock]} f]
  (.lock ^ReentrantLock pool-lock)
  (try
    (f)
    (finally
      (.unlock ^ReentrantLock pool-lock))))

(defn- pool-snapshot
  [{::keys [pool-state] :as writer}]
  (with-pool-lock
    writer
    (fn []
      (let [{::keys [closed? members available opening]} @pool-state]
        {::closed? closed?
         ::pool-size (::pool-size writer-pool-defaults)
         ::live-members (count members)
         ::available-members (count available)
         ::in-flight-members (- (count members) (count available))
         ::opening-members opening}))))

(defn- pool-error
  ([writer reason message]
   (pool-error writer reason message {}))
  ([writer reason message data]
   {:seon/error
    {:seon.error/message message
     :seon.error/kind :agent
     :seon.error/data (merge {::pool-reason reason
                              ::pool (pool-snapshot writer)}
                             data)}}))

(defn- close-member-session!
  [session]
  (when session
    (try (uds/close-session! session)
         (catch Throwable _)))
  nil)

(defn- member-present?
  [state {::keys [member-id session]}]
  (identical? session (get-in state [::members member-id ::session])))

(defn- release-member!
  [{::keys [pool-state pool-condition] :as writer} member]
  (let [retain?
        (with-pool-lock
          writer
          (fn []
            (let [state @pool-state]
              (if (and (not (::closed? state))
                       (member-present? state member))
                (do (swap! pool-state update ::available conj
                           (::member-id member))
                    (.signal ^Condition pool-condition)
                    true)
                false))))]
    (when-not retain?
      (close-member-session! (::session member)))
    nil))

(defn- evict-member!
  [{::keys [pool-state pool-condition] :as writer} member]
  (with-pool-lock
    writer
    (fn []
      (when (member-present? @pool-state member)
        (swap! pool-state
               (fn [state]
                 (-> state
                     (update ::members dissoc (::member-id member))
                     (update ::available
                             (fn [member-ids]
                               (filterv #(not= (::member-id member) %)
                                        member-ids))))))
        (.signalAll ^Condition pool-condition))))
  (close-member-session! (::session member))
  nil)

(declare protocol-error-value)

(defn- handshake-request!
  [session request]
  (uds/call! {::uds/session session ::uds/message request}))

(defn- open-member!
  [{::keys [writer-socket-path database-name backend database-path]
    :as writer}]
  ;; The host registers no listen interests today. If it does, the pool recipe
  ;; requires pinning them to one designated member and re-registering them
  ;; whenever that member is replaced.
  (let [session (atom nil)]
    (try
      (let [connected (uds/open-session! writer-socket-path)
            _ (reset! session connected)
            ensure-response
            (when backend
              (handshake-request!
               connected
               (protocol/ensure-database-request
                (cond-> {::protocol/request-id (str (random-uuid))
                         ::protocol/database-name database-name
                         ::protocol/backend backend}
                  database-path
                  (assoc ::protocol/database-path database-path)))))
            ensure-error (when (and ensure-response
                                    (not (::protocol/success? ensure-response)))
                           (protocol-error-value ensure-response))
            resolve-response
            (when-not ensure-error
              (handshake-request!
               connected
               (protocol/resolve-head-request
                {::protocol/request-id (str (random-uuid))
                 ::protocol/database-name database-name})))
            resolve-error (when (and resolve-response
                                     (not (::protocol/success? resolve-response)))
                            (protocol-error-value resolve-response))]
        (if-let [error (or ensure-error resolve-error)]
          (do (close-member-session! connected) error)
          {::member-id (random-uuid) ::session connected}))
      (catch Throwable throwable
        (close-member-session! @session)
        (if (= protocol/connection-capacity-error
               (::protocol/error-kind (ex-data throwable)))
          (pool-error writer :writer-capacity
                      "The database writer is at its connection capacity."
                      (select-keys
                       (ex-data throwable)
                       [::protocol/request-id ::protocol/error-kind
                        ::protocol/configuration-key
                        ::protocol/maximum-connections]))
          (pool-error writer :connect-failed
                      "The host could not open a database writer connection."
                      {::failure (str throwable)}))))))

(defn- finish-opening!
  [{::keys [pool-state pool-condition] :as writer} result]
  (let [installed?
        (with-pool-lock
          writer
          (fn []
            (swap! pool-state update ::opening dec)
            (let [closed? (::closed? @pool-state)]
              (when (and (not closed?) (not (:seon/error result)))
                (swap! pool-state assoc-in
                       [::members (::member-id result)] result))
              (.signalAll ^Condition pool-condition)
              (and (not closed?) (not (:seon/error result))))))]
    (cond
      (:seon/error result) result
      installed? result
      :else (do (close-member-session! (::session result))
                (pool-error writer :session-closed
                            "The database writer session is closed.")))))

(defn- acquire-member!
  [{::keys [pool-state pool-condition] :as writer} wait-timeout-ms]
  (let [deadline (+ (System/nanoTime)
                    (.toNanos java.util.concurrent.TimeUnit/MILLISECONDS
                              (long wait-timeout-ms)))
        decision
        (with-pool-lock
          writer
          (fn []
            (loop []
              (let [{::keys [closed? members available opening]} @pool-state
                    remaining (- deadline (System/nanoTime))]
                (cond
                  closed?
                  (pool-error writer :session-closed
                              "The database writer session is closed.")

                  (seq available)
                  (let [member-id (peek available)
                        member (get members member-id)]
                    (swap! pool-state update ::available pop)
                    member)

                  (< (+ (count members) opening)
                     (::pool-size writer-pool-defaults))
                  (do (swap! pool-state update ::opening inc)
                      ::open-member)

                  (not (pos? remaining))
                  (pool-error writer :pool-exhausted
                              "Every database writer connection is busy."
                              {::pool-wait-timeout-ms wait-timeout-ms})

                  :else
                  (let [interrupted?
                        (try
                          (.awaitNanos ^Condition pool-condition remaining)
                          false
                          (catch InterruptedException _ true))]
                    (if interrupted?
                      (do (.interrupt (Thread/currentThread))
                          (pool-error writer :interrupted
                                      "Waiting for a database writer connection was interrupted."))
                      (recur))))))))]
    (if (= ::open-member decision)
      (finish-opening! writer (open-member! writer))
      decision)))

(defn- replace-member!
  [writer]
  (let [member (acquire-member!
                writer (::pool-wait-timeout-ms writer-pool-defaults))]
    (when-not (:seon/error member)
      (release-member! writer member))
    member))

(defn close-session!
  "Close every retained connection in the writer session."
  {:malli/schema [:=> [:cat ::writer] :nil]}
  [{::keys [pool-state pool-condition call-executor] :as writer}]
  (let [members
        (with-pool-lock
          writer
          (fn []
            (let [members (vals (::members @pool-state))]
              (swap! pool-state assoc
                     ::closed? true ::members {} ::available [])
              (.signalAll ^Condition pool-condition)
              members)))]
    (run! #(close-member-session! (::session %)) members)
    (.shutdownNow ^ExecutorService call-executor))
  nil)

(defn- throwable-cause
  [throwable]
  (or (.getCause ^Throwable throwable) throwable))

(defn- invoke-member!
  [{::keys [call-executor] :as writer} member request deadline-ms]
  (let [task (.submit ^ExecutorService call-executor
                      ^Callable
                      (fn []
                        (uds/call! {::uds/session (::session member)
                                    ::uds/message request})))
        timeout ::deadline]
    (try
      (let [response (deref task (long deadline-ms) timeout)]
        (if (= timeout response)
          (do (evict-member! writer member)
              {::call-outcome :deadline})
          (do (release-member! writer member)
              {::call-outcome :response ::response response})))
      (catch Throwable throwable
        (let [failure (throwable-cause throwable)
              data (ex-data failure)]
          (if (= protocol/frame-too-large-error
                 (::protocol/error-kind data))
            (do
              (release-member! writer member)
              {::call-outcome :response ::response data})
            (do
              (evict-member! writer member)
              {::call-outcome :failure ::failure failure})))))))

(defn- call-attempt!
  [writer request budget-ms]
  (let [started (System/nanoTime)
        wait-ms (min (long budget-ms)
                     (long (::pool-wait-timeout-ms writer-pool-defaults)))
        member (acquire-member! writer wait-ms)]
    (if (:seon/error member)
      {::call-outcome :error ::response member}
      (let [spent-ms (.toMillis java.util.concurrent.TimeUnit/NANOSECONDS
                                (- (System/nanoTime) started))
            remaining (max 0 (- (long budget-ms) spent-ms))]
        (if (zero? remaining)
          (do (release-member! writer member)
              {::call-outcome :deadline})
          (invoke-member! writer member request remaining))))))

(defn- active-request-conflict?
  [response]
  (and (false? (::protocol/success? response))
       (= protocol/request-conflict-error (::protocol/error-kind response))
       (true? (::protocol/running? response))))

(defn- release-in-flight?
  [response]
  (= protocol/release-error
     (get-in response [:seon/error :seon.error/data ::protocol/error-kind])))

(defn- sleep-before-recovery-poll!
  [remaining]
  (let [backoff-ms
        (min remaining
             (long (::request-conflict-backoff-ms writer-pool-defaults)))]
    (try
      (Thread/sleep (long backoff-ms))
      true
      (catch InterruptedException _
        (.interrupt (Thread/currentThread))
        false))))

(defn- recovery-write!
  [writer request]
  (let [budget-ms (long (::call-deadline-ms writer-pool-defaults))
        deadline (+ (System/nanoTime)
                    (.toNanos java.util.concurrent.TimeUnit/MILLISECONDS
                              budget-ms))]
    (loop []
      (let [remaining (.toMillis java.util.concurrent.TimeUnit/NANOSECONDS
                                 (max 0 (- deadline (System/nanoTime))))]
        (if (zero? remaining)
          (pool-error writer :request-conflict-timeout
                      "The database writer is still settling the original write."
                      {::protocol/request-id (::protocol/request-id request)
                       ::protocol/error-kind protocol/request-conflict-error
                       ::protocol/running? true})
          (let [{::keys [call-outcome response failure]}
                (call-attempt! writer request remaining)]
            (case call-outcome
              :response
              (if (active-request-conflict? response)
                (if (sleep-before-recovery-poll! remaining)
                  (recur)
                  (pool-error writer :interrupted
                              "Database write recovery was interrupted."))
                response)

              :error
              (if (release-in-flight? response)
                (if (sleep-before-recovery-poll! remaining)
                  (recur)
                  (pool-error writer :interrupted
                              "Database write recovery was interrupted."))
                response)
              :deadline
              (pool-error writer :write-recovery-deadline
                          "The database write recovery reached its deadline."
                          {::protocol/request-id (::protocol/request-id request)})
              :failure
              (pool-error writer :write-recovery-failed
                          "The database write recovery connection failed."
                          {::protocol/request-id (::protocol/request-id request)
                           ::failure (str failure)}))))))))

(defn- writer-call!
  "Dispatch one bounded round-trip and reconnect a failed member once."
  [writer request]
  (let [write? (= protocol/transact-operation (::protocol/operation request))
        deadline-ms (::call-deadline-ms writer-pool-defaults)
        {::keys [call-outcome response failure]}
        (call-attempt! writer request deadline-ms)]
    (case call-outcome
      :response response
      :error response
      :deadline
      (if write?
        (recovery-write! writer request)
        (do (replace-member! writer)
            (pool-error writer :call-deadline
                        "The database writer call reached its deadline."
                        {::protocol/request-id (::protocol/request-id request)})))
      :failure
      (let [{retry-outcome ::call-outcome
             retry-response ::response
             retry-failure ::failure}
            (call-attempt! writer request deadline-ms)]
        (case retry-outcome
          :response
          (if (and write? (active-request-conflict? retry-response))
            (recovery-write! writer request)
            retry-response)
          :error
          (if (and write? (release-in-flight? retry-response))
            (recovery-write! writer request)
            retry-response)
          :deadline
          (if write?
            (recovery-write! writer request)
            (do (replace-member! writer)
                (pool-error writer :call-deadline
                            "The database writer call reached its deadline."
                            {::protocol/request-id
                             (::protocol/request-id request)})))
          :failure
          (pool-error writer :connection-failed
                      "The database writer connection failed after reconnecting."
                      {::protocol/request-id (::protocol/request-id request)
                       ::failure (str (or retry-failure failure))}))))))

(defn- protocol-error-value
  [response]
  (if (:seon/error response)
    response
    {:seon/error
     {:seon.error/message
      (str "The database writer rejected the call: "
           (or (::protocol/error response) (::protocol/error-kind response)))
      :seon.error/kind :agent
      :seon.error/data
      (select-keys response [::protocol/error-kind
                             ::protocol/configuration-key
                             ::protocol/maximum-frame-bytes])}}))

(defn- ensure-database!
  "Ensure the configured database on the writer; explicit config only.

   The writer releases a database when its last physical connection
   closes; ensuring re-opens it. The backend and path come ONLY from the
   host's explicit configuration (the same facts the child's
   database-selection carries) — never guessed from a name."
  [{::keys [database-name backend database-path] :as writer}]
  (writer-call!
   writer
   (protocol/ensure-database-request
    (cond-> {::protocol/request-id (str (random-uuid))
             ::protocol/database-name database-name
             ::protocol/backend backend}
      database-path (assoc ::protocol/database-path database-path)))))

(defn resolve-head!
  "Resolve the writer's current database value for the host's database.

   A not-found answer for a configured backend means the writer released
   the database; one explicit ensure re-opens it before the retry."
  {:malli/schema [:=> [:cat ::writer] :map]}
  [{::keys [database-name backend] :as writer}]
  (let [resolve-once
        (fn []
          (writer-call!
           writer
           (protocol/resolve-head-request
            {::protocol/request-id (str (random-uuid))
             ::protocol/database-name database-name})))
        response (resolve-once)
        response (if (and backend
                          (not (::protocol/success? response))
                          (= :seon.db.protocol.error/not-found
                             (::protocol/error-kind response)))
                   (do (ensure-database! writer) (resolve-once))
                   response)]
    (if (::protocol/success? response)
      (:seon.db/db response)
      (protocol-error-value response))))

(defn- db-query-with-evidence
  "Context `seon.db/query-with-evidence`: one read plus its own cost.

   Mirrors the pod surface of the same name: the returned map carries the
   result together with the writer's dependency, cache, and resource
   evidence, so an agent can see what its own query charged."
  [writer query-form & arguments]
  (let [head (resolve-head! writer)]
    (if (:seon/error head)
      head
      (let [response (writer-call!
                      writer
                      (protocol/query-request
                       (with-read-provenance
                         {::protocol/request-id (str (random-uuid))
                          :seon.db/db head
                          ::protocol/query-form query-form
                          ::protocol/arguments (vec arguments)})))]
        (if (::protocol/success? response)
          (select-keys response
                       [:datahike.query/result
                        :datahike.read/dependency-plan
                        :datahike.query/attribute-dependencies
                        :datahike.query/cache-evidence
                        :datahike.query/resource-evidence])
          (protocol-error-value response))))))

(defn- db-query
  "Context `seon.db/query`: one blocking Datalog read at the current head."
  [writer query-form & arguments]
  (let [response (apply db-query-with-evidence writer query-form arguments)]
    (if (:seon/error response)
      response
      (:datahike.query/result response))))

(defn- db-pull
  "Context `seon.db/pull`: one blocking pull read at the current head."
  [writer selector entity-id]
  (let [head (resolve-head! writer)]
    (if (:seon/error head)
      head
      (let [response (writer-call!
                      writer
                      (protocol/pull-request
                       (with-read-provenance
                         {::protocol/request-id (str (random-uuid))
                          :seon.db/db head
                          ::protocol/selector selector
                          ::protocol/entity-id entity-id})))]
        (if (::protocol/success? response)
          (::protocol/result response)
          (protocol-error-value response))))))

(defn- receipt-basis
  "Basis transaction of the committed receipt for `op-id`, or nil.

   The receipt is the writer's own durable idempotency fact: the
   `:seon.db.protocol/request-id` datom stamped on every committed
   transaction entity. Its presence answers \"did it happen?\" by query;
   its basis transaction is the completed-at fact — nothing extra is
   stored."
  [writer head op-id]
  (let [response (writer-call!
                  writer
                  (protocol/query-request
                   {::protocol/request-id (str (random-uuid))
                    :seon.db/db head
                    ::protocol/query-form
                    '[:find ?transaction .
                      :in $ ?op-id
                      :where [?transaction :seon.db.protocol/request-id
                              ?op-id]]
                    ::protocol/arguments [op-id]}))]
    (if (::protocol/success? response)
      {::receipt-transaction (:datahike.query/result response)}
      (protocol-error-value response))))

(defn- replayed-outcome
  "Recorded outcome for an op-id whose receipt already committed."
  [head op-id receipt-transaction]
  {:seon.db/ok? true
   :seon.capability/op-id op-id
   :seon.capability/replayed? true
   :db-after (cond-> (select-keys head [:db-name :t :datahike/commit-id])
               (< receipt-transaction (:t head))
               (assoc :as-of receipt-transaction))})

(defn- db-transact!
  "Context `seon.db/transact!`: one exactly-once write at the current head.

   Accepts the pod's shapes — raw transaction data, or a map carrying
   `:seon.db/tx-data` plus an optional `:seon.capability/op-id`. The
   wrapper generates the op-id when absent and sends it as the protocol's
   `::protocol/request-id`, so the writer's durable idempotency receipt
   makes the retained-connection resend in [[writer-call!]] safe: a
   connection killed between commit and acknowledgement recovers the
   recorded outcome instead of re-executing. A caller-supplied op-id is
   first checked against the receipt so an agent-level retry after any
   crash also returns the recorded outcome (`:seon.capability/replayed?
   true`) exactly once."
  [writer request]
  (let [{tx-data :seon.db/tx-data op-id :seon.capability/op-id}
        (if (and (map? request) (contains? request :seon.db/tx-data))
          request
          {:seon.db/tx-data (vec request)})
        supplied-op-id? (some? op-id)
        op-id (or op-id (str (random-uuid)))
        head (resolve-head! writer)]
    (if (:seon/error head)
      head
      (let [receipt (when supplied-op-id?
                      (receipt-basis writer head op-id))]
        (cond
          (:seon/error receipt)
          receipt

          (some? (::receipt-transaction receipt))
          (replayed-outcome head op-id (::receipt-transaction receipt))

          :else
          (let [response (writer-call!
                          writer
                          (protocol/transaction-request
                           (cond-> {::protocol/request-id op-id
                                    :seon.db/db head
                                    ::protocol/transaction-data (vec tx-data)}
                             (provenance)
                             (assoc ::protocol/transaction-meta
                                    (provenance)))))]
            (if (::protocol/success? response)
              (cond-> {:seon.db/ok? true
                       :seon.capability/op-id op-id
                       :db-after (select-keys (:db-after response)
                                              [:db-name :t
                                               :datahike/commit-id])
                       :tempids (:tempids response)}
                (::protocol/recovered? response)
                (assoc :seon.capability/replayed? true))
              (protocol-error-value response))))))))

;;; Wrapper registry — the ONE capability-provisioning mechanism.

(defn registry
  "Create one empty wrapper registry for one host base.

   Process-local derived state: a restart rebuilds it by re-registration
   from the host's configuration, never from persistence."
  {:malli/schema [:=> [:cat] ::registry]}
  []
  (atom {}))

(defn- shared-var-meta
  "Mark host-authored metadata as read-only in every agent fork."
  [host-authored? var-meta]
  (cond-> var-meta host-authored? (assoc :sci/built-in true)))

(defn- stamp-shared-base-vars!
  "Stamp every var SCI or the portable loader installed in the base.

   The base is still host-owned here. SCI's write guard therefore runs in
   its privileged context while this one walk marks every interned Var;
   agent forks never receive that privilege."
  [ctx]
  (sci.ctx-store/with-ctx (assoc ctx :unrestricted true)
    (doseq [shared-var
            (sci/eval-string*
             ctx "(vec (mapcat (comp vals ns-interns) (all-ns)))")
            :when (and (instance? sci.lang.Var shared-var)
                       (not (:sci/built-in (meta shared-var))))]
      (alter-meta! shared-var (partial shared-var-meta true))))
  nil)

(defn- register-wrapper-vars!
  [host-authored? {::keys [registry lib wrappers]}]
  (swap! registry
         (fn [entries]
           (let [entry (get entries lib)
                 sci-ns (or (::sci-ns entry) (sci/create-ns lib))
                 vars
                 (reduce-kv
                  (fn [acc fn-sym {::keys [wrapper-fn wrapper-value arglists doc]
                                  :as wrapper}]
                    (let [value (if (contains? wrapper ::wrapper-fn)
                                  wrapper-fn wrapper-value)]
                      (if-let [live (get acc fn-sym)]
                        (do (sci/alter-var-root live (constantly value))
                            acc)
                        (assoc acc fn-sym
                               (sci/new-var
                                fn-sym value
                                (shared-var-meta
                                 host-authored?
                                 (cond-> {:ns sci-ns :name fn-sym}
                                   arglists (assoc :arglists arglists)
                                   doc (assoc :doc doc))))))))
                  (or (::vars entry) {})
                  wrappers)]
             (assoc entries lib {::sci-ns sci-ns ::vars vars}))))
  nil)

(defn register-wrappers!
  "Register or upgrade agent-authored corpus function vars.

   Registering a namespace makes it lazily require-able in EVERY live
   context: the registry backs the shared `:load-fn` closure, so first
   require injects the cached wrapper vars with `:arglists`/`:doc` live
   on real sci vars. Corpus vars remain writable because eval-side `defn`
   is their deliberate recorded edit path. Re-registering alters the
   shared var's root through SCI's privileged host API, so every context
   that already required it sees the upgrade. A registry `(lib, symbol)`
   keeps its ownership class for its lifetime."
  {:malli/schema [:=> [:cat ::register-request] :nil]}
  [request]
  (register-wrapper-vars! false request))

(defn register-host-wrappers!
  "Register or upgrade host-authored read-only SCI built-in vars."
  {:malli/schema [:=> [:cat ::register-request] :nil]}
  [request]
  (register-wrapper-vars! true request))

(defn install-registered-wrappers!
  "Link one context to a namespace's exact shared registry vars."
  {:malli/schema [:=> [:cat ::install-request] :nil]}
  [{::keys [registry ctx lib]}]
  (when-let [vars (get-in @registry [lib ::vars])]
    (sci/add-namespace! ctx lib vars))
  nil)

(declare query-writer-at!)

(def ^:private corpus-namespace-source-query
  '[:find ?source .
    :in $ ?lib
    :where
    [?namespace :seon.ns/name ?lib]
    [?namespace :seon.ns/source ?source]])

(defn- corpus-namespace-source
  "Stored namespace source at one current immutable database value."
  [writer lib]
  (let [database (resolve-head! writer)]
    (if (:seon/error database)
      database
      (query-writer-at! writer database corpus-namespace-source-query [lib]))))

(defn- registry-load-fn
  "Shared sci `:load-fn`; registry first, then stored corpus source.

   Called by sci only on the FIRST require of an unknown lib. The body is
   registry-first so provisioned wrappers remain the cheap path under the
   JVM's process-global load lock. A missing registry namespace resolves
   `:seon.ns/source` at one current immutable database value; sci evaluates
   that source and recursively materializes its declared require closure."
  [registry writer]
  (fn [{:keys [libname ctx]}]
    (if (get-in @registry [libname ::vars])
      (do
        (install-registered-wrappers!
         {::registry registry ::ctx ctx ::lib libname})
        {})
      (let [source (corpus-namespace-source writer libname)]
        (when (:seon/error source)
          (throw
           (ex-info (get-in source [:seon/error :seon.error/message])
                    {:seon.error/kind :core-bug})))
        (when source {:source source})))))

(defn- register-host-capabilities!
  "Seed the registry with the host's capability families over `writer`.

   This is the one provisioning path: `seon.db` reads/writes close over
   the pure-data writer boundary, `seon.schema` and `seon.ai.tokens` wrap
   the compiled host functions. Restart re-registers from configuration;
   nothing here persists."
  [registry writer]
  (register-host-wrappers!
   {::registry registry
    ::lib 'seon.ai.provider
    ::wrappers
    {'provider-locality {::wrapper-value ai.provider/provider-locality}
     'frontier-provider? {::wrapper-fn ai.provider/frontier-provider?
                          ::arglists '([provider])}}})
  (register-host-wrappers!
   {::registry registry
    ::lib 'seon.db
    ::wrappers
    {'query {::wrapper-fn (partial db-query writer)
             ::arglists '([query-form & arguments])
             ::doc "Run one Datalog read at the writer's current head."}
     'query-with-evidence
     {::wrapper-fn (partial db-query-with-evidence writer)
      ::arglists '([query-form & arguments])
      ::doc "Run one Datalog read and return its result with its own cost evidence."}
     'pull {::wrapper-fn (partial db-pull writer)
            ::arglists '([selector entity-id])
            ::doc "Pull one entity's selection at the current head."}
     'transact! {::wrapper-fn (partial db-transact! writer)
                 ::arglists '([request])
                 ::doc "Commit transaction data exactly once; an op-id retry replays the receipt."}
     'head {::wrapper-fn (partial resolve-head! writer)
            ::arglists '([])
            ::doc "Resolve the writer's current database value."}}})
  (register-host-wrappers!
   {::registry registry
    ::lib 'seon.db.id
    ::wrappers
    {'candidate-manifest {::wrapper-fn db.id/candidate-manifest
                          ::arglists '([generator-policies allocations])
                          ::doc "Generate one validated identity-candidate manifest."}
     'generator-policy-query {::wrapper-value db.id/generator-policy-query
                              ::doc "Query for stored generated-identity policies."}}})
  (register-host-wrappers!
   {::registry registry
    ::lib 'seon.db.protocol
    ::wrappers
    {'query-operation {::wrapper-value protocol/query-operation}
     'pull-operation {::wrapper-value protocol/pull-operation}
     'success? {::wrapper-value ::protocol/success?}
     'result {::wrapper-value ::protocol/result}}})
  (register-host-wrappers!
   {::registry registry
    ::lib 'seon.schema
    ::wrappers
    {'validate {::wrapper-fn (fn [schema-key value]
                               (schema/valid-candidate-value? schema-key
                                                              value))
                ::arglists '([schema-key value])
                ::doc "True when the value satisfies the registered schema."}
     ;; Real admission through the one `seon.schema/register!` bridge:
     ;; the host registry validates and admits exactly as the child's,
     ;; a banned/invalid shape returns the guidance as an error VALUE,
     ;; and the surrounding form's registry diff tees the canonical
     ;; `:seon.schema` row into the same transaction as its eval row.
     'register! {::wrapper-fn
                 (fn [schema-key schema-form]
                   (try
                     (schema/register! schema-key schema-form)
                     schema-key
                     (catch Throwable throwable
                       {:seon/error
                        {:seon.error/message
                         (str (.getMessage throwable))
                         :seon.error/kind :user-input}})))
                 ::arglists '([schema-key schema])
                 ::doc "Register one schema; the eval tee persists the canonical row."}
     'schema-definition {::wrapper-fn schema/schema-definition
                         ::arglists '([schema-key])
                         ::doc "Return one registered schema's canonical definition."}}})
  (register-host-wrappers!
   {::registry registry
    ::lib 'seon.ai.tokens
    ::wrappers
    {'estimate {::wrapper-fn tokens/estimate
                ::arglists '([value])
                ::doc "Estimated token size of one value."}
     'estimate-chars {::wrapper-fn tokens/estimate-chars
                      ::arglists '([character-count])
                      ::doc "Estimated token size of a character count."}
     'clip-str {::wrapper-fn tokens/clip-str
                ::arglists '([value budget] [value budget marker])
                ::doc "Clip text to an estimated token budget."}}})
  (register-host-wrappers!
   {::registry registry
    ::lib 'seon.content-hash
    ::wrappers
    {'sha-256 {::wrapper-fn content-hash/sha-256
               ::arglists '([content])}}})
  (register-host-wrappers!
   {::registry registry
    ::lib 'seon.time
    ::wrappers
    {'iso-string {::wrapper-fn time/iso-string
                  ::arglists '([instant])}}})
  (register-host-wrappers!
   {::registry registry
    ::lib 'seon.repair.candidates
    ::wrappers
    {'rank-candidates {::wrapper-fn candidates/rank-candidates
                       ::arglists '([from names])}}})
  (register-host-wrappers!
   {::registry registry
    ::lib 'seon.repl.internal
    ::wrappers
    {'read-forms {::wrapper-fn
                  (fn
                    ([source]
                     (record/read-forms {::record/source (or source "")}))
                    ([source options]
                     (record/read-forms
                      {::record/source (or source "")
                       ::record/ns-sym (:seon.repl/current-ns options)
                       ::record/aliases (:seon.repl/aliases options)})))
                  ::arglists '([source])}}})
  (register-host-wrappers!
   {::registry registry
    ::lib 'seon.agent.ctx
    ::wrappers
    {'read-file-text
     {::wrapper-fn (fn [path]
                     (try
                       (let [file (io/file path)]
                         (when (and (.isFile file) (.canRead file))
                           (slurp file)))
                       (catch Throwable _ nil)))
      ::arglists '([path])
      ::doc "Read one repo-relative UTF-8 text file, or nil when unreadable."}
     'list-skill-files
     {::wrapper-fn
      (fn [dir]
        (try
          (let [root (io/file dir)]
            (if-not (.isDirectory root)
              []
              (into []
                    (mapcat
                     (fn [name]
                       (let [path (io/file root name)]
                         (cond
                           (.isDirectory path)
                           (let [skill (io/file path "SKILL.md")]
                             (when (.isFile skill) [(.getPath skill)]))

                           (and (.isFile path) (str/ends-with? name ".md"))
                           [(.getPath path)]))))
                    (or (seq (.list root)) []))))
          (catch Throwable _ [])))
      ::arglists '([dir])
      ::doc "List the readable skill markdown files under one corpus directory."}}})
  (register-host-wrappers!
   {::registry registry
    ::lib 'seon.render.canvas
    ::wrappers
    {'field-signal
     {::wrapper-fn
      (fn [field]
        (str "seon_"
             (.encodeToString
              (.withoutPadding (java.util.Base64/getUrlEncoder))
              (.getBytes ^String (str field)
                         java.nio.charset.StandardCharsets/UTF_8))))
      ::arglists '([field])
      ::doc "Encode a qualified field keyword as a Datastar-safe signal identifier."}}}))

;;; Portable `my.*` slice, loaded from the real sources.

(def ^:private toolkit-source-root (io/file "src/my"))

(defn- toolkit-source-files
  "Every toolkit Clojure source path in deterministic discovery order."
  []
  (->> (file-seq toolkit-source-root)
       (filter #(.isFile ^java.io.File %))
       (filter #(re-find #"\.clj[sc]$" (.getName ^java.io.File %)))
       (mapv #(.getPath ^java.io.File %))
       sort
       vec))

(defn- definition-form?
  [form]
  (and (seq? form) (contains? '#{def defn defn-} (first form))))

(defn- definition-blocks
  "Top-level definition blocks from the one tools.reader source read."
  [source ns-sym aliases]
  (into []
        (comp (filter definition-form?)
              (map (fn [form]
                     (let [source (or (:source (meta form)) (pr-str form))]
                       {::block-name (str (second form))
                        ::source source
                        ::host-source (some-> (record/read-host-form source)
                                              pr-str)}))))
        (record/read-forms {::record/source source
                            ::record/ns-sym ns-sym
                            ::record/aliases aliases})))

(defn- pure-block?
  "True when a defn block has no async, js-interop, or db-boundary marker."
  [block]
  (and (string? block)
       (not (re-find #"\^:async|\(await |js/|#js|\(\.\-|\(\. |\(\.[a-zA-Z]|db/transact!|db/query|db/pull|db/entity|db/db\b|blob/"
                     block))))

(defn- edge-aliases
  [edges]
  (into {}
        (keep (fn [{:seon.ns.require/keys [target alias]}]
                (when (and target alias) [alias target])))
        edges))

(defn- source-unit
  [path]
  (let [source (slurp (io/file path))
        ns-form (record/read-ns-form source)
        ns-sym (second ns-form)
        edges (if ns-form (record/ns-require-edges ns-form) #{})
        aliases (edge-aliases edges)]
    {::source-path path
     ::namespace ns-sym
     ::require-edges edges
     ::blocks (if ns-sym (definition-blocks source ns-sym aliases) [])}))

(defn dependency-order
  "Topologically order source units by their parsed namespace requires.

   Only edges between supplied units constrain the result. Input position is
   the deterministic tie-breaker; a cycle is returned as data."
  {:malli/schema [:=> [:cat [:vector :map]]
                  [:map [::ordered [:vector :map]]
                   [::cycle [:vector :symbol]]]]}
  [units]
  (let [names (mapv ::namespace units)
        candidates (set names)
        position (zipmap names (range))
        by-name (into {} (map (juxt ::namespace identity)) units)
        needs (into {}
                    (map (fn [{::keys [namespace require-edges]}]
                           [namespace
                            (into #{}
                                  (comp (map :seon.ns.require/target)
                                        (filter candidates))
                                  require-edges)]))
                    units)]
    (loop [remaining needs ordered []]
      (if (empty? remaining)
        {::ordered (mapv by-name ordered) ::cycle []}
        (let [ready (->> remaining
                         (keep (fn [[name required]]
                                 (when (empty? required) name)))
                         (sort-by position)
                         vec)]
          (if (empty? ready)
            {::ordered (mapv by-name ordered)
             ::cycle (->> (keys remaining) (sort-by position) vec)}
            (let [released (set ready)]
              (recur (into {}
                           (map (fn [[name required]]
                                  [name (apply disj required released)]))
                           (apply dissoc remaining ready))
                     (into ordered ready)))))))))

(defn- require-spec
  [{:seon.ns.require/keys [target alias refers refer-all? as-alias?]}]
  (cond-> [target]
    (and alias (not as-alias?)) (conj :as alias)
    (seq refers) (conj :refer (vec (sort refers)))
    refer-all? (conj :refer :all)))

(defn- synthetic-ns-form
  "The synthetic `(ns …)` source establishing one context namespace.

   Stands in for the production augment-ns-source aliases, pointed at
   the host capability namespaces the registry provisions."
  [ns-sym require-edges available-libs]
  (let [parsed (->> require-edges
                    (remove :seon.ns.require/as-alias?)
                    (filter #(contains? available-libs
                                        (:seon.ns.require/target %)))
                    (sort-by (comp str :seon.ns.require/target))
                    (mapv require-spec))
        defaults [['clojure.string :as 'str]
                  ['clojure.set :as 'set]
                  ['clojure.edn :as 'edn]
                  ['clojure.walk :as 'walk]
                  ['seon.db :as 'db]
                  ['seon.schema :as 'schema]
                  ['seon.ai.tokens :as 'tokens]]
        parsed-targets (set (map first parsed))
        specs (into parsed (remove #(contains? parsed-targets (first %))) defaults)]
    (pr-str (list 'ns ns-sym (cons :require specs)))))

(defn ensure-context-ns!
  "Ensure `ns-sym` exists in `ctx` with the standard capability aliases.

   Idempotent: an existing namespace is left untouched (re-running the
   ns form would be harmless but wasteful under the shared load lock)."
  {:malli/schema [:=> [:catn [::ctx ::ctx] [::ns-sym :symbol]] :nil]}
  [ctx ns-sym]
  (when-not (sci/eval-string* ctx (str "(find-ns '" ns-sym ")"))
    (sci/eval-string* ctx (synthetic-ns-form ns-sym #{}
                                             '#{clojure.string clojure.set
                                                clojure.edn clojure.walk
                                                seon.db seon.schema
                                                seon.ai.tokens})))
  nil)

(defn- block-row
  [unit block status reason unresolved-symbol]
  (cond-> {::source-path (::source-path unit)
           ::namespace (::namespace unit)
           ::block-name (::block-name block)
           ::status status}
    reason (assoc ::reason reason)
    unresolved-symbol (assoc ::unresolved-symbol unresolved-symbol)))

(defn load-portable-slice!
  "Eval every pure `my.*` defn block from its real source into `ctx`.

   Returns the honest ledger: block counts plus each failure's first error
   line. Failures are references to impure private helpers the pure slice
   does not carry, recorded — never silently skipped."
  [ctx registry]
  (let [units (mapv source-unit (toolkit-source-files))
        {::keys [ordered cycle]} (dependency-order units)
        candidate-libs (set (map ::namespace units))
        available-libs (into candidate-libs (keys @registry))
        loaded-rows
        (into []
              (mapcat
               (fn [{::keys [namespace require-edges blocks] :as unit}]
                 (let [portable (filterv (comp pure-block? ::host-source) blocks)
                       excluded-blocks (remove (comp pure-block? ::host-source) blocks)
                       excluded-rows
                       (mapv #(block-row unit % :excluded
                                         "The block is outside the portable C1 class (async, JS, database, or blob capability evidence)."
                                         nil)
                             excluded-blocks)
                       ns-error
                       (try
                         (sci/eval-string*
                          ctx (synthetic-ns-form namespace require-edges
                                                 available-libs))
                         nil
                         (catch Throwable throwable
                           (first (str/split-lines
                                   (str (.getMessage throwable))))))]
                   (into excluded-rows
                         (if ns-error
                           (map #(block-row unit % :failed ns-error nil) portable)
                           (map (fn [{::keys [host-source] :as block}]
                                  (try
                                    (sci/eval-string*
                                     ctx (str "(in-ns '" namespace ")\n" host-source))
                                    (block-row unit block :loaded nil nil)
                                    (catch Throwable throwable
                                      (block-row
                                       unit block :failed
                                       (first (str/split-lines
                                               (str (.getMessage throwable))))
                                       (:sci.impl/symbol (ex-data throwable))))))
                                portable))))))
              ordered)
        cycle-rows
        (into []
              (mapcat
               (fn [namespace]
                 (let [unit (first (filter #(= namespace (::namespace %)) units))]
                   (map #(block-row unit % :failed
                                    (str "Namespace require cycle: "
                                         (str/join ", " cycle))
                                    nil)
                        (filter (comp pure-block? ::host-source) (::blocks unit)))))
               cycle))
        initial-rows (into loaded-rows cycle-rows)
        excluded-names
        (reduce (fn [by-ns row]
                  (if (= :excluded (::status row))
                    (update by-ns (::namespace row) (fnil conj #{})
                            (::block-name row))
                    by-ns))
                {}
                initial-rows)
        rows
        (mapv
         (fn [row]
           (if-let [dependency (and (= :failed (::status row))
                                    (::unresolved-symbol row))]
             (if (contains? (get excluded-names (::namespace row) #{})
                            (name dependency))
               (-> row
                   (assoc ::status :excluded
                          ::reason (str "Depends on excluded non-portable helper `"
                                        dependency "`."))
                   (dissoc ::unresolved-symbol))
               (dissoc row ::unresolved-symbol))
             (dissoc row ::unresolved-symbol)))
         initial-rows)
        failures (into []
                       (comp (filter #(= :failed (::status %)))
                             (map (fn [row]
                                    {::source-path (::source-path row)
                                     ::namespace (::namespace row)
                                     ::block-name (::block-name row)
                                     ::failure (::reason row)})))
                       rows)]
    {::files (count units)
     ::pure-blocks (count (remove #(= :excluded (::status %)) rows))
     ::loaded (count (filter #(= :loaded (::status %)) rows))
     ::failed (count failures)
     ::excluded (count (filter #(= :excluded (::status %)) rows))
     ::blocks rows
     ::failures failures}))

(defn build-base!
  "Build the one shared base context for a host serving one cluster.

   Every capability namespace provisions through the one wrapper
   registry: [[register-host-capabilities!]] seeds it from the writer
   coordinates, and the registry-backed `:load-fn` serves first requires
   lazily in the base and every fork. The portable `my.*` pure slice
   loads from its real sources (its requires exercise that lazy path).
   The returned report is the honest real-vs-failed load ledger."
  {:malli/schema [:=> [:cat ::writer] ::base]}
  [writer]
  (let [wrapper-registry (registry)
        _ (register-host-capabilities! wrapper-registry writer)
        ctx (sci/init
             {:load-fn (registry-load-fn wrapper-registry writer)
              :namespaces {'clojure.core interrupt/clojure-core
                           'clojure.string interrupt/clojure-string}
              :interrupt-fn
              (fn []
                (when (.isInterrupted (Thread/currentThread))
                  (interrupt/interrupt! "eval deadline exceeded"
                                        {:seon.error/kind :timeout})))})
        report (load-portable-slice! ctx wrapper-registry)
        _ (stamp-shared-base-vars! ctx)]
    {::ctx ctx ::report report ::registry wrapper-registry}))

(defn fork-context
  "Fork one private agent context from the shared base."
  {:malli/schema [:=> [:cat ::base] ::ctx]}
  [{::keys [ctx]}]
  (sci/fork ctx))

(defn replay-defs!
  "Replay def sources into a context; restore = fork base + this replay.

   Each source string evaluates in order; every outcome is a value. The
   sources come from the one program corpus ([[agent-def-sources]]);
   replay is reconstruction, never a fresh agent eval, so nothing here
   re-tees."
  {:malli/schema [:=> [:cat ::ctx ::def-sources] ::replay-envelopes]}
  [ctx def-sources]
  (mapv (fn [source]
          (try
            {:seon.eval/ok? true
             :seon.eval/value (sci/eval-string* ctx source)}
            (catch Throwable throwable
              {:seon.eval/ok? false
               :seon/error {:seon.error/message
                            (str (first (str/split-lines
                                         (str (.getMessage throwable)))))
                            :seon.error/kind :agent}})))
        def-sources))

;;; Eval recording — host-tier turns are first-class corpus citizens (U4).

(def ^:private agent-def-sources-query
  '[:find ?source ?transaction
    :in $ ?ns-sym
    :where
    [?namespace :seon.ns/name ?ns-sym]
    [?fn :seon.fn/ns ?namespace]
    [?fn :seon.fn/source ?source ?transaction]])

(defn agent-def-sources
  "Ordered corpus def sources for one namespace, oldest tee first.

   The durable agent is database facts: restore forks the shared base
   and replays exactly these `:seon.fn/source` rows. Returns the error
   value on a failed read — the caller decides whether a restore without
   replay is acceptable."
  {:malli/schema [:=> [:catn [::writer ::writer] [::ns-sym :symbol]] :any]}
  [writer ns-sym]
  (let [rows (db-query writer agent-def-sources-query ns-sym)]
    (if (:seon/error rows)
      rows
      (into [] (map first) (sort-by second rows)))))

(defn restore-context-defs!
  "Replay one namespace's corpus defs into a freshly forked context.

   Establishes the namespace with the standard aliases, then replays
   each stored def source inside it. Returns the replay envelopes (an
   error value when the corpus read itself failed); individual replay
   failures are values inside the vector, never throws."
  {:malli/schema [:=> [:catn [::writer ::writer] [::ctx ::ctx]
                       [::ns-sym :symbol]] :any]}
  [writer ctx ns-sym]
  (let [sources (agent-def-sources writer ns-sym)]
    (if (:seon/error sources)
      sources
      (do (ensure-context-ns! ctx ns-sym)
          (replay-defs!
           ctx
           (mapv #(str "(in-ns '" ns-sym ")\n" %) sources))))))

(defn- record-transact!
  "One provenance-stamped transaction on the retained writer connection.

   An explicit `database` becomes the protocol request's `:seon.db/db`;
   omission preserves the current-head behavior used by receipt recording.
   `candidates` ride the protocol's `::generated-candidates` field, so
   the writer validates and commits managed identity allocation in the
   same transaction — the exact mechanism `seon.db.id/allocate!` uses."
  [writer {::keys [tx-data candidates database]}]
  (let [database (or database (resolve-head! writer))]
    (if (:seon/error database)
      database
      (let [response
            (writer-call!
             writer
             (protocol/transaction-request
              (cond-> {::protocol/request-id (str (random-uuid))
                       :seon.db/db database
                       ::protocol/transaction-data (vec tx-data)}
                (provenance)
                (assoc ::protocol/transaction-meta (provenance))
                (seq candidates)
                (assoc ::protocol/generated-candidates (vec candidates)))))]
        (if (::protocol/success? response)
          {:seon.db/ok? true
           :db-after (select-keys (:db-after response)
                                  [:db-name :t :datahike/commit-id])}
          (protocol-error-value response))))))

(defn query-writer!
  "Run one host-internal query through the retained writer session."
  {:malli/schema [:=> [:cat ::writer :any [:sequential :any]] :any]}
  [writer query-form arguments]
  (apply db-query writer query-form arguments))

(defn query-writer-at!
  "Run one host query at the caller's explicit immutable database value."
  {:malli/schema
   [:=> [:cat ::writer :seon.db/db :any [:sequential :any]] :any]}
  [writer database query-form arguments]
  (let [response
        (writer-call!
         writer
         (protocol/query-request
          (with-read-provenance
            {::protocol/request-id (str (random-uuid))
             :seon.db/db database
             ::protocol/query-form query-form
             ::protocol/arguments (vec arguments)})))]
    (if (::protocol/success? response)
      (:datahike.query/result response)
      (protocol-error-value response))))

(def ^:private pinned-namespace-sources-query
  '[:find ?sym ?source ?transaction
    :in $ ?ns-sym
    :where
    [?namespace :seon.ns/name ?ns-sym]
    [?function :seon.fn/ns ?namespace]
    [?function :seon.fn/sym ?sym]
    [?function :seon.fn/source ?source ?transaction]
    [?transaction :seon.db/process ?process]
    [?process :seon.db.process/id :seon.db.process/repl]])

(defn verify-pinned-function!
  "Verify one authored source identity at an explicit immutable database value."
  {:malli/schema
   [:=> [:cat ::writer :seon.db/db :qualified-symbol :string]
    [:vector [:tuple :string :string :int]]]}
  [writer database function-symbol source-fingerprint]
  (let [ns-sym (symbol (namespace function-symbol))
        rows (query-writer-at! writer database pinned-namespace-sources-query
                               [ns-sym])]
    (when (:seon/error rows)
      (throw (ex-info (get-in rows [:seon/error :seon.error/message])
                      {:seon.error/kind :core-bug})))
    (let [target-source
          (some (fn [[sym source _transaction]]
                  (when (= function-symbol (symbol sym)) source))
                rows)]
      (when-not target-source
        (throw (ex-info "The requested current agent function does not exist."
                        {:seon.execution/function-symbol function-symbol
                         :seon.error/kind :agent})))
      (when-not (= source-fingerprint
                   (content-hash/sha-256 target-source))
        (throw (ex-info "The requested function source is no longer current."
                        {:seon.execution/function-symbol function-symbol
                         :seon.error/kind :agent})))
      (vec rows))))

(defn- stamp-source-root!
  [sci-var source]
  (let [source-fingerprint (content-hash/sha-256 source)
        root @sci-var]
    (sci/alter-var-root
     sci-var
     (constantly
      (with-meta root
        (assoc (meta root)
               :seon.fn/source-fingerprint source-fingerprint)))))
  sci-var)

(defn materialize-pinned-function!
  "Materialize one pinned authored function in a detached disposable context."
  {:malli/schema
   [:=> [:catn [::writer ::writer]
               [::ctx ::ctx]
               [:seon.db/db :seon.db/db]
               [::function-symbol :qualified-symbol]
               [::source-fingerprint :string]
               [::reconcile-ephemeral! ::reconcile-ephemeral!]]
    ::materialized-function]}
  [writer retained-ctx database function-symbol source-fingerprint
   reconcile-ephemeral!]
  (let [ns-sym (symbol (namespace function-symbol))
        rows (verify-pinned-function! writer database function-symbol
                                      source-fingerprint)]
    (let [ctx (sci/fork retained-ctx)]
        ;; A plain fork retains identical SCI Vars. Remove then recreate the
        ;; authored namespace before replay so pinned definitions cannot bind
        ;; shared roots in the retained context or registry.
        (sci/eval-string* ctx (str "(remove-ns '" ns-sym ")"))
        (ensure-context-ns! ctx ns-sym)
        (let [ordered (sort-by #(nth % 2) rows)
              envelopes
              (replay-defs!
               ctx
               (mapv (fn [[_ source _]]
                       (str "(in-ns '" ns-sym ")\n" source))
                     ordered))]
          (when-let [failed (first (remove :seon.eval/ok? envelopes))]
            (throw (ex-info (get-in failed [:seon/error :seon.error/message])
                            {:seon.error/kind :agent})))
          (let [vars-by-symbol
                (into {}
                      (keep (fn [[sym source _]]
                              (when-let [sci-var (sci/resolve ctx (symbol sym))]
                                [(symbol sym)
                                 (stamp-source-root! sci-var source)])))
                      ordered)
                target-var (get vars-by-symbol function-symbol)]
            (when-not target-var
              (throw
               (ex-info "The requested current agent function did not load."
                        {:seon.execution/function-symbol function-symbol
                         :seon.error/kind :core-bug})))
            (let [reconciled (reconcile-ephemeral! vars-by-symbol)]
              (when (:seon/error reconciled)
                (throw
                 (ex-info
                  (get-in reconciled [:seon/error :seon.error/message])
                  {:seon.error/kind :core-bug}))))
            [ctx target-var])))))

(def ^:private committed-schema-query
  '[:find ?key ?form
    :where
    [?schema :seon.schema/key ?key]
    [?schema :seon.schema/form ?form]])

(def ^:private committed-function-contract-query
  '[:find ?sym ?form
    :where
    [?function :seon.fn/sym ?sym]
    [?function :seon.fn/spec ?form]])

(def ^:private committed-projection-row-limit 4096)

(defn acquire-committed-projection!
  "Acquire and compile the complete committed program at one database value."
  {:malli/schema [:=> [:catn [::writer ::writer]] :map]}
  [writer]
  (try
    (let [database (resolve-head! writer)]
      (if (:seon/error database)
        database
        (let [member (fn [query]
                       {::protocol/operation protocol/query-operation
                        :seon.db/db database
                        ::protocol/query-form query
                        ::protocol/arguments []
                        :datahike.resource/max-work 1000000
                        ;; One sentinel proves the claimed complete population
                        ;; did not stop at Datahike's early-stop row limit.
                        :datahike.resource/max-results
                        (inc committed-projection-row-limit)
                        :datahike.resource/max-result-weight (* 3 1024 1024)})
              response
              (writer-call!
                writer
                (protocol/execute-many-request
                  {::protocol/request-id (str (random-uuid))
                   ::protocol/members
                   [(member committed-schema-query)
                    (member committed-function-contract-query)]
                   :datahike.resource/max-result-weight (* 6 1024 1024)}))]
          (if-not (::protocol/success? response)
            (protocol-error-value response)
            (let [[schemas contracts] (::protocol/results response)]
              (if-not (every? ::protocol/success? [schemas contracts])
                {:seon/error
                 {:seon.error/message
                  "Committed schema projection acquisition failed."
                  :seon.error/kind :core-bug
                  :seon.error/data
                  {:seon.host.context/member-results [schemas contracts]}}}
                (let [schema-rows (:datahike.query/result schemas)
                      contract-rows (:datahike.query/result contracts)]
                  (if (or (> (count schema-rows)
                             committed-projection-row-limit)
                          (> (count contract-rows)
                             committed-projection-row-limit))
                    {:seon/error
                     {:seon.error/message
                      "Committed schema projection exceeds its complete-row bound."
                      :seon.error/kind :core-bug}}
                    {::database database
                     ::projection
                     (schema/projection-from-rows
                       {:seon.schema/schema-rows schema-rows
                        :seon.schema/function-contract-rows
                        contract-rows})}))))))))
    (catch Throwable throwable
      {:seon/error
       {:seon.error/message
        (str "Committed schema projection is invalid: "
             (.getMessage throwable))
        :seon.error/kind :core-bug
        :seon.error/data (ex-data throwable)}})))

(defn publish-committed-projection!
  "Publish `acquired` only when its basis transaction is newer."
  {:malli/schema [:=> [:catn [::projection-state ::projection-state]
                             [::acquired :map]]
                  :map]}
  [projection-state acquired]
  (swap! projection-state
         (fn [current]
           (let [floor (max (get-in current [::database :t] -1)
                            (get current ::committed-basis -1))
                 acquired-basis (get-in acquired [::database :t] -1)]
             (if (or (< floor acquired-basis)
                     (and (::fault current) (= floor acquired-basis)))
               acquired
               current)))))

(defn current-committed-projection
  "Return the complete retained projection or its newer-generation fault."
  {:malli/schema [:=> [:catn [::projection-state ::projection-state]] :map]}
  [projection-state]
  (let [current @projection-state]
    (if-let [fault (::fault current)]
      {:seon/error fault}
      {::database (::database current)
       ::projection (::projection current)})))

(defn refresh-committed-projection!
  "Rebuild and monotonically publish the writer's complete projection."
  {:malli/schema [:=> [:catn [::writer ::writer]
                             [::projection-state ::projection-state]
                             [::committed-basis ::committed-basis]]
                  :map]}
  [writer projection-state committed-basis]
  ;; The durable commit is already newer than the served projection. Publish
  ;; an unavailable floor before any reacquire/build work can block, so a
  ;; concurrent browser never observes the old generation as current truth.
  (swap! projection-state
         (fn [current]
           (let [floor (max (get-in current [::database :t] -1)
                            (get current ::committed-basis -1))]
             (if (< floor committed-basis)
               {::fault
                {:seon.error/message
                 "Committed schema projection refresh is pending."
                 :seon.error/kind :core-bug}
                ::pending? true
                ::committed-basis committed-basis}
               current))))
  (let [read-result (acquire-committed-projection! writer)
        acquired
        (if (and (not (:seon/error read-result))
                 (< (get-in read-result [::database :t] -1)
                    committed-basis))
          {:seon/error
           {:seon.error/message
            "Committed schema projection refresh returned a stale database value."
            :seon.error/kind :core-bug}}
          read-result)]
    (if (:seon/error acquired)
      (do
        (swap! projection-state
               (fn [current]
                 (if (< (max (get-in current [::database :t] -1)
                             (get current ::committed-basis -1))
                        committed-basis)
                   {::fault (:seon/error acquired)
                    ::committed-basis committed-basis}
                   (if (and (::pending? current)
                            (= committed-basis
                               (::committed-basis current)))
                     {::fault (:seon/error acquired)
                      ::committed-basis committed-basis}
                     current))))
        (let [current @projection-state]
          (if (>= (get-in current [::database :t] -1) committed-basis)
            current
            acquired)))
      (publish-committed-projection! projection-state acquired))))

(defn transact-writer!
  "Commit host-derived transaction data through the retained writer.

   The three-argument form sends the caller's immutable database value in the
   transaction request. The two-argument form resolves the current head."
  {:malli/schema
   [:function
    [:=> [:cat ::writer [:vector :any]] :map]
    [:=> [:cat ::writer :seon.db/db [:vector :any]] :map]]}
  ([writer tx-data]
   (record-transact! writer {::tx-data tx-data}))
  ([writer database tx-data]
   (record-transact! writer {::database database ::tx-data tx-data})))

(def ^:private eval-allocation-key ::eval-allocation)
(def ^:private max-allocation-attempts 16)

(defn- eval-id-generator
  "The stored generator policy for `:seon.eval/id`; cached per session.

   The policy is a database fact on the `:seon.schema` row; process-local
   caching is safe because a policy change would arrive with a new
   program, not mid-session."
  [writer]
  (or @(::eval-generator writer)
      (let [rows (db-query writer db.id/generator-policy-query
                           [:seon.eval/id])]
        (if (:seon/error rows)
          rows
          (if-let [generator (some (fn [[attr generator]]
                                     (when (= :seon.eval/id attr)
                                       generator))
                                   rows)]
            (reset! (::eval-generator writer) generator)
            {:seon/error
             {:seon.error/message
              "No stored generator policy for :seon.eval/id."
              :seon.error/kind :core-bug}})))))

(defn start-eval-receipt!
  "Allocate one managed eval id and commit its `:running` receipt.

   The durable execution boundary: the caller runs the form ONLY after
   this returns `{:seon.eval/id id}`. Candidate conflicts retry with a
   fresh candidate, bounded exactly like the allocator."
  {:malli/schema [:=> [:cat ::writer
                       [:map [:seon.agent.turn/id :string]
                        [:seon.eval/at :inst]
                        [:seon.eval/source :string]
                        [:seon.eval/narration :string]
                        [:seon.eval/ns :symbol]
                        [:seon.agent/id :string]]]
                  :map]}
  [writer {turn-id :seon.agent.turn/id
           at :seon.eval/at
           source :seon.eval/source
           narration :seon.eval/narration
           ns-sym :seon.eval/ns
           agent-id :seon.agent/id}]
  (let [generator (eval-id-generator writer)]
    (if (:seon/error generator)
      generator
      (loop [attempt 1]
        (let [manifest (db.id/candidate-manifest
                        {:seon.eval/id generator}
                        [{:seon.db.id/key eval-allocation-key
                          :seon.db.id/identity-attr :seon.eval/id}])
              eval-id (:seon.db.id/value (first manifest))
              outcome
              (record-transact!
               writer
               {::tx-data (record/start-tx-data
                           {:seon.agent.turn/id turn-id
                            :seon.eval/id eval-id
                            :seon.eval/at at
                            :seon.eval/source source
                            :seon.eval/narration narration
                            :seon.eval/ns ns-sym
                            :seon.eval/agent [:seon.agent/id agent-id]})
                ::candidates manifest})]
          (cond
            (:seon.db/ok? outcome) {:seon.eval/id eval-id}

            (and (= protocol/generated-candidate-conflict-error
                    (get-in outcome [:seon/error :seon.error/data
                                     ::protocol/error-kind]))
                 (< attempt max-allocation-attempts))
            (recur (inc attempt))

            :else outcome))))))

(defn record-eval-terminal!
  "Terminalize one receipt with its frozen outcome and program tee.

   One transaction carries the `:running` CAS fence, the complete eval
   row, and every program-graph row the form tees — the eval's committed
   transaction IS the transaction that wrote the corpus datom, exactly
   as the child records."
  {:malli/schema [:=> [:cat ::writer :map] :map]}
  [writer {eval-id :seon.eval/id
           ::keys [envelope at duration-ms source narration ns-sym
                   agent-id forms var-meta new-schema-keys output
                   database-edn-cap]}]
  (let [program-tx-data
        (when (:seon.eval/ok? envelope)
          (record/tee-tx-data
           {::record/forms (or forms [])
            ::record/source source
            ::record/ns-sym ns-sym
            ::record/var-meta (or var-meta {})
            ::record/new-schema-keys (or new-schema-keys #{})
            ::record/at at}))
        function-rows
        (into []
              (filter #(and (map? %)
                            (contains? % :seon.fn/sym)
                            (contains? % :seon.fn/source)))
              program-tx-data)
        tx-data
        (into (record/terminal-tx-data
               {:seon.eval/id eval-id
                ::record/envelope envelope
                ::record/at at
                ::record/duration-ms duration-ms
                ::record/source source
                ::record/narration narration
                ::record/ns-sym ns-sym
                ::record/agent-ref [:seon.agent/id agent-id]
                ::record/output output
                ::record/database-edn-cap database-edn-cap})
              program-tx-data)]
    (let [result (record-transact! writer {::tx-data tx-data})
          projection-change?
          (boolean
            (some (fn [operation]
                    (or (and (map? operation)
                             (or (contains? operation :seon.schema/form)
                                 (contains? operation :seon.fn/spec)))
                        (and (vector? operation)
                             (contains? #{:seon.schema/form :seon.fn/spec}
                                        (nth operation 2 nil)))))
                  tx-data))]
      (cond-> result
        (:seon.db/ok? result)
        (assoc ::projection-changed? projection-change?
               ::function-rows function-rows)))))
