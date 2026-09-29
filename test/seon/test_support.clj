(ns seon.test-support
  "Shared test constructions which invoke production owners."
  (:require [seon.error.refusal]
            [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as async.impl]
            [clojure.java.io :as io]
            [clojure.test :as test]
            [datahike.api :as d]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.cluster.registry :as registry]
            [seon.cluster.source :as source]
            [seon.cluster.store :as store]
            [seon.config :as config]
            [seon.db :as db]
            [seon.env :as env]
            [seon.fs :as fs]
            [seon.fn :as seon.fn]
            [seon.instrument :as instrument]
            [seon.operator.runtime :as operator.runtime]
            [seon.profile :as profile]
            [seon.program :as program]
            [seon.schema :as schema]
            [seon.batch]
            [seon.sci.eval :as sci.eval]
            [seon.test]
            [seon.turn]
            [seon.turn.batch :as turn.batch])
  (:import [java.util.concurrent CountDownLatch ExecutionException Future TimeUnit
            TimeoutException]))

(set! *warn-on-reflection* true)

(def event-backstop-seconds
  "The loud clock around test events whose publishers are observable."
  20)

(defn effective-config
  "Compile shipped defaults plus an optional sparse manifest without writes."
  {:malli/schema
   [:function
    [:=> [:cat] :seon.config/effective]
    [:=> [:cat :seon.config/manifest] :seon.config/effective]]}
  ([]
   config/defaults)
  ([manifest]
   (:seon.config/effective
    (config/compile-manifest {:seon.boot/cluster-name "default" :seon.config/manifest manifest}))))

(defn render-context-channel
  "Create a channel supplying one profile to every context request."
  [profile]
  (async/chan
   1
   (map (fn [message]
          (update message
                  :seon.render.context/request
                  assoc
                  :seon.render/profile
                  profile)))))

(declare await-event!)

(declare delete-recursively!)

(defn- clone-directory!
  "Copy-on-write clone one directory tree (a file-store fixture or a checkout copy)."
  {:malli/schema [:=> [:cat :seon.schema/value :seon.schema/value] :string]}
  [source target]
  (let [source (.getCanonicalFile (io/file source))
        target (.getCanonicalFile (io/file target))
        _ (.mkdirs target)
        command
        (if (= "Mac OS X" (System/getProperty "os.name"))
          ["/bin/cp" "-cR" (str source java.io.File/separator ".")
           (.getPath target)]
          ["cp" "-a" "--reflink=auto"
           (str source java.io.File/separator ".") (.getPath target)])
        process (.start (doto (ProcessBuilder. ^java.util.List command)
                          (.redirectErrorStream true)))
        output (future (slurp (.getInputStream process)))]
    (try
      (await-event! (.onExit process) ::directory-cloned)
      (let [exit (.exitValue process)
            output (await-event! output ::directory-clone-output)]
        (when-not (zero? exit)
          (throw
           (ex-info "The directory could not be cloned."
                    {::source (.getPath source)
                     ::target (.getPath target)
                     ::exit exit
                     ::output output})))
        (.getPath target))
      (finally
        (when (.isAlive process)
          (.destroyForcibly process)
          (await-event! (.onExit process) ::directory-clone-stopped))
        (await-event! output ::directory-clone-output)))))


(defn populate-published-root!
  "Publish the checkout's program into `root`'s own store (a file-backed fixture)."
  {:seon.fn/destroys
   "the store directory of the root it is handed: publication creates it there, deleting an incomplete prior store"}
  ([root] (populate-published-root! root {}))
  ([root _options]
   (cluster/refresh-source! (str root))
   (str root)))

(defn populate-published-operator-root!
  "Publish the checkout's program into an operator root's cluster store."
  {:seon.fn/destroys
   "the store directory under the operator root it is handed: publication creates it there, deleting an incomplete prior store"}
  ([root] (populate-published-operator-root! root {}))
  ([root _options]
   (cluster/refresh-source! (str (io/file root "data" "clusters")))
   (str root)))

(defn with-published-file-database
  "Run `body` on a private file-store branch of a root the checkout is published into.

  The connection CARRIES the branch's own program projection, exactly as the
  canonical in-memory base and a live cluster's boot do. A branch connection
  opened straight off a store carries none, so every `db/db` on it fell back
  to the thread's projection -- in a worker, the PACKAGED projection, which
  declares schema forms and no function contracts at all. Every seam that asks
  the carried projection about a FUNCTION then answered from an empty map:
  `seon.effect/accepts-request?` refused every capability request alike with
  :seon.effect/invalid-request (measured on batch 85's published base: 0
  function contracts carried, 1118 derived)."
  [root branch body]
  (let [root (str root)]
    (populate-published-root! root)
    (let [store-dir
          (:seon.boot/store-dir
           (cluster/resolve-bootstrap {:seon.boot/root root}))
          opened (store/open-store! {:seon.store/dir store-dir})]
    (try
      (registry/branch! {:seon.store/store opened
                         :seon.cluster.registry/from source/current-branch
                         :seon.store/branch branch})
      (let [connection (store/open-branch! opened branch)]
        (try
          (let [database @connection
                projection (schema/projection-from-database database)
                ;; the private branch is this fixture's cluster: a published
                ;; program root holds no cluster row, so the world must name it
                ;; (seon.sci.eval/carried-cluster-name refuses to guess one)
                state (sci.eval/projection-state database projection (name branch))]
            (db/carry-connection-projection-state! connection state)
            (schema/call-with-projection-state state #(body connection)))
          (finally
            (d/release connection))))
      (finally
        (store/release-store! opened))))))

(def committed
  "Returned when a boundary expected to refuse instead commits."
  ::committed)

(def unknown-refusal
  "Returned when a thrown boundary carries no classifiable ex-data."
  ::unknown-refusal)

(defn- published-commit-ids
  "The `:current-src` heads this JVM's held stores currently name.

   One konserve head record per store, so this is an ordinary FACT read,
   not a cached mirror: nothing has to tell the fixture that the program moved."
  []
  (not-empty
   (into (sorted-set)
         (keep (fn [held]
                 (when-let [store (:seon.store/store held)]
                   (:seon.source/commit-id (source/current store)))))
         (vals @operator.runtime/root-store-holder))))

(defonce ^:private manifest-cache (atom {}))

(def source-manifest
  "The source manifest of the checkout this JVM runs under.

   It is re-derived when a held store's `:current-src` head moves, so an
   adopted accreted arity is present without any hand rebuild."
  (reify clojure.lang.IDeref
    (deref [_]
      (let [publication (published-commit-ids)]
        (or (get @manifest-cache publication)
            (let [manifest (seon.fn/build-manifest {:seon.fn/roots seon.fn/source-roots})]
              (reset! manifest-cache {publication manifest})
              manifest))))))

(defn- checked-fixture-result
  "Stop setup at its first flat refusal, naming it and retaining its data.

   \"Fixture setup was refused.\" alone names nothing: clojure.test prints the
   exception message and not its data, so every refusal in base construction
   read identically and the diagnosing agent had to reconstruct which one
   fired."
  {:malli/schema [:=> [:cat :seon.schema/value] :seon.schema/value]}
  [result]
  (when (or (:seon.db.write.attempt/request-id result)
            (:seon.db/invalid-read result) (:seon.schema/expected-value result)
            (:seon.config/error-key result) (:seon.test.run/unavailable result))
    (throw (ex-info (str "Fixture setup was refused: "
                         (:seon.error/message result))
                    result)))
  result)

(defn- offending-fixture-entity
  "The authored row write admission refused, taken from its reported path.

   The refusal's own `:seon.db/entity-form` is the entity SCHEMA it was read
   against, not the row: the row is the one the fixture wrote at the path's
   leading index, so a seeding fixture reads back exactly what it authored."
  [tx-data report]
  (let [entities (if (map? tx-data) (:tx-data tx-data) tx-data)
        index (first (:seon.db/path report))]
    (when (and (int? index) (sequential? entities) (< -1 index (count entities)))
      [index (nth entities index)])))

(defn transacted!
  "Transact fixture data through the writer and prove the report landed.

   A fixture that discards `seon.db/transact!`'s answer reads ABSENCE OF
   SIGNAL as health, the project's named recurring failure class. Write
   admission refuses a row the current schema no longer admits by RETURNING a
   flat `:seon.error` value with nothing in the log: the seed never lands, the
   test renders an empty world, and it fails several assertions away from its
   cause. This is the one fixture write path, so a refusal stops the test AT
   the write, naming the refusal's diagnostic and the offending row.

   Returns the transaction report, whose `:tx-data`, `:tempids` and `:db-after`
   the seeding fixture reads exactly as production callers do."
  {:malli/schema
   [:=> [:cat :seon.db/connection :seon.store/transaction]
    :seon.db/transaction-report]}
  [connection tx-data]
  ;; A fixture writes the connection it names as that world's own work, even
  ;; inside another fixture's custody (a nested `with-database`).
  (let [report (db/call-with-custody {:seon.db/connection connection}
                                     #(db/transact! connection tx-data))]
    (when-not (and (map? report)
                   (some? (:db-after report)))
      (throw
       (ex-info
        (str "Fixture write was refused at the write: "
             (or (:seon.error/message report)
                 (str "no :db-after in " (pr-str report)))
             (when-let [[index entity] (offending-fixture-entity tx-data report)]
               (str " Offending entity " index ": " (pr-str entity) ".")))
        (cond-> (if (map? report) report {:seon.db/report report})
          true (assoc :seon.db/tx-data tx-data)))))
    report))

(defn- held-handle
  "The execution handle the agent entrance retains for `connection`, or nil.

  `seon.cluster.agent/acquire-context!` keeps every handle it acquires in the
  roster its environment carries; the connection's carried projection
  state is that environment. No registry beside it is consulted."
  {:malli/schema [:=> [:cat [:or :nil :seon.db/connection]]
                  [:or :nil :seon.agent/execution-handle]]}
  [connection]
  (when connection
    (let [environment (env/of {env/state-carrier
                               (:seon.sci.eval/projection-state (meta connection))})]
      ;; Only the handle acquired for no agent: an agent armed on the fixture's
      ;; live branch borrows the same connection under `[branch agent-id]`, and
      ;; its context is that agent's private universe.
      (some (fn [[[_ agent-id] handle]]
              (when (and (nil? agent-id) (identical? connection (:seon.db/connection handle)))
                handle))
            (some-> (:seon.agent/routing environment) deref :seon.agent/contexts)))))

(defn execution-handle
  "The executing test's handle: the one held for `connection`, else for this
  body's custody, else the host member `seon.test/run` binds around this body
  (`seon.test/*member*`). Every member runs on a branch the entrance acquired."
  {:malli/schema [:=> [:cat [:or :nil :seon.db/connection]] :seon.agent/execution-handle]}
  [connection]
  (or (held-handle connection)
      (held-handle db/*conn*)
      seon.test/*member*
      (throw (ex-info (str "The canonical fixture needs an executing test handle: run the "
                           "test through seon.test/run, whose member branch the agent "
                           "entrance acquired.")
                      {:seon.error/operation 'seon.test-support/with-database
                       :seon.error/expected :seon.agent/execution-handle
                       :seon.error/offending (if db/*conn* :no-held-handle :no-custody)}))))

(defn attempt-routing
  "The routing the executing attempt that holds `connection` arms its graphs in;
  its release stops and joins every graph armed there before it closes."
  {:malli/schema [:=> [:cat [:or :nil :seon.db/connection]] :seon.agent/routing]}
  [connection]
  (:seon.agent/routing (execution-handle connection)))

(defn- cluster-names
  "Every cluster name `database` holds."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:set :seon.boot/cluster-name]]}
  [database]
  (set (db/q '[:find [?name ...] :where [_ :seon.cluster/name ?name]] database)))

(defn- seeded-cluster-name
  "The one cluster this fixture stood up, else the executing handle's own.
   A fixture branch inherits the rows of the database its handle acquired
   (`:seon.db/db`); the clusters `database` adds to those are the ones it seeded."
  {:malli/schema [:=> [:cat [:or :nil :seon.db/connection] :seon.db/database-value]
                  [:or :nil :seon.boot/cluster-name]]}
  [connection database]
  (let [handle (execution-handle connection)
        present (cluster-names database)
        seeded (remove (cluster-names (:seon.db/db handle)) present)]
    (cond
      (= 1 (count seeded)) (first seeded)
      (and (empty? seeded) (present (:seon.cluster/name handle))) (:seon.cluster/name handle))))

(defn fork-cluster-ctx
  "Fork the executing handle's context onto `connection` through the production fork.
  Custody, projection and environment repointing belong to sci.eval; a named
  cluster environment keeps the entrance's roster and held store."
  {:malli/schema [:function
                  [:=> [:cat :seon.db/connection] :seon.sci.eval/ctx]
                  [:=> [:cat :seon.db/connection [:maybe :seon.boot/cluster-name]]
                   :seon.sci.eval/ctx]]}
  ([connection]
   (fork-cluster-ctx connection (seeded-cluster-name connection (db/db connection))))
  ([connection cluster-name]
   (let [handle (execution-handle connection)
         database (db/db connection)
         projection-state (:seon.sci.eval/projection-state (meta database))]
     (sci.eval/fork-cluster-ctx
      (:seon.sci.eval/ctx handle) database connection projection-state
      (cond-> {}
        cluster-name
        (assoc :seon.env/environment
               (env/refuse-incomplete-environment!
                (env/environment
                 (cond-> {:seon.boot/cluster-name cluster-name
                          :seon.agent/routing (:seon.agent/routing handle)}
                   (:seon.store/store handle)
                   (assoc :seon.store/store (:seon.store/store handle)))))))))))


(defn code-request
  "An MCP-shaped SCI run over the real evaluator: `turn.batch/code-request`
  with the turn's evaluate stage (`seon.turn/evaluate-row`, so
  `seon.sci.eval/evaluate` runs each form) and its namespace cursor and frame
  request, drawn from the request's world. The request's own keys win."
  {:malli/schema [:=> [:cat :map] :seon.batch/request]}
  [{world :seon.batch/world start :seon.batch/start-ns :as request}]
  (let [start (or start 'user)
        {ctx :seon.sci.eval/ctx limit :seon.sci.eval/time-limit-ms connection :seon.db/connection} world]
    (turn.batch/code-request
     (merge {:seon.batch/start-ns start
             :seon.batch/evaluate #'seon.turn/evaluate-row
             :seon.batch/ns-name (fn [{current :seon.turn/namespace-cursor}] @current)
             :seon.turn/namespace-cursor (volatile! start)
             :seon.turn/frame-request
             (fn [_ordinal namespace-name]
               {:seon.cluster.eval/source ""
                :seon.cluster.eval/ns [:seon.ns/name namespace-name]
                :seon.sci.eval/ctx ctx
                :seon.db/connection connection
                :seon.sci.admit/caps (config/result-caps config/defaults)
                :seon.sci.eval/time-limit-ms limit})}
            request))))

(defn evaluate
  "`:seon.cluster.eval/source` evaluated through the one REPL path (the fold,
  with the turn's evaluate and show stages) in the request's context: the
  last form's evaluation map — value, shown text, error, record, ending
  namespace — with every form's printed output, and `:seon.batch/unread`
  when the text stops being readable. Nothing settles: the forms' program
  facts are not proposed.

  A context holding custody that has not acquired the request's database
  acquires it first (`seon.sci.eval/acquire!`), as an agent's next run forks
  onto its head. Without a context, a base context is built from
  `:seon.schema/projection`; without a database, the executing member's is
  read, since an agent's evaluation always has one.

  With `:seon.test/rows` true the derive stage also runs (still nothing
  settles) and the result carries `:seon.program/row`, the declaration the
  last form defined, for a test that transacts or installs it."
  {:malli/schema [:=> [:cat [:map [:seon.cluster.eval/source :string]
                             [:seon.sci.admit/caps :map]]]
                  :map]}
  [{ctx :seon.sci.eval/ctx source :seon.cluster.eval/source namespace-ref :seon.cluster.eval/ns
    limit :seon.sci.eval/time-limit-ms rows? :seon.test/rows :as request}]
  (let [ctx (or ctx (sci.eval/build-base-ctx (or (:seon.schema/projection request) (schema/handed-projection))))
        database (or (:seon.db/db request)
                     (some-> (get-in ctx [::sci.eval/custody :seon.db/connection]) db/db)
                     (get-in ctx [::sci.eval/custody :seon.db/db])
                     (some-> (:seon.sci.kernel/program-snapshot ctx) deref :seon.db/db)
                     ;; an agent's evaluation always has one: the executing member's
                     (db/db (:seon.db/connection (execution-handle nil))))
        connection (or (:seon.db/connection request) (get-in ctx [::sci.eval/custody :seon.db/connection]))
        _ (when (and (contains? ctx ::sci.eval/custody) (not (sci.eval/acquired-database? ctx database)))
            (sci.eval/acquire! {:seon.sci.eval/ctx (::sci.eval/base-ctx ctx ctx) :seon.db/db database}))
        request (assoc request :seon.sci.eval/ctx ctx)
        start (or (second namespace-ref) 'user)
        limit (or limit (:seon.config.eval/time-limit-ms config/defaults))
        world (cond-> {:seon.boot/cluster-name (:seon.boot/cluster-name request "default")
                       :seon.sci.eval/ctx ctx :seon.sci.eval/time-limit-ms limit
                       :seon.batch/sink (seon.batch/sink)}
                ;; the world an agent's evaluation has: its custody's connection
                ;; and its identity, from which omitted inputs are supplied
                connection (assoc :seon.db/connection connection
                                  ;; the context's own environment (its routing, store),
                                  ;; else one naming the cluster and connection
                                  :seon.env/environment (or (env/of ctx)
                                                            (env/environment {:seon.boot/cluster-name (:seon.boot/cluster-name request "default")
                                                                              :seon.db/connection connection})))
                (:seon.agent/id request) (assoc :seon.agent/id (:seon.agent/id request)))
        batch (seon.batch/evaluate-batch
               (-> (turn.batch/code-request
                    {:seon.batch/world world
                     :seon.batch/basis database
                     :seon.batch/text source
                     :seon.batch/start-ns start
                     :seon.batch/evaluate #'seon.turn/evaluate-row
                     :seon.batch/show #'seon.turn/show-row
                     :seon.batch/ns-name (fn [{current :seon.turn/namespace-cursor}] @current)
                     :seon.turn/namespace-cursor (volatile! start)
                     :seon.turn/frame-request (fn [_ordinal namespace-name]
                                                (assoc request :seon.cluster.eval/ns [:seon.ns/name namespace-name]))
                     :seon.sci.eval/time-limit-ms limit})
                   (as-> r (if rows?
                             (dissoc r :seon.batch/settle :seon.batch/observe)
                             (dissoc r :seon.batch/settle :seon.batch/derive :seon.batch/facts :seon.batch/apply
                                     :seon.batch/fold :seon.batch/observe)))))
        entries (:seon.batch/entries batch)
        output (apply str (keep #(get-in % [:seon.batch/evaluation :seon.cluster.eval/output]) entries))]
    (cond-> (merge (:seon.batch/evaluation (peek entries)) (:seon.batch/shown-map (peek entries)))
      (seq output) (assoc :seon.cluster.eval/output output)
      (:seon.batch/unread batch) (assoc :seon.batch/unread (:seon.batch/unread batch))
      ;; the declaration the last form defined, as the batch's derive stage
      ;; proposes it (what a settlement would commit)
      rows? (as-> e (if-let [row (first (:seon.batch/entities (:seon.batch/change (peek entries))))]
                      (assoc e :seon.program/row row)
                      e)))))

(defn agent-value
  "Evaluate `source` at the boundary an AGENT actually calls, and return the
  value the agent reads.

  An agent never invokes a Var directly: its reply is read into forms and
  each one crosses the one REPL path (`evaluate` here), total by construction —
  every failure, a violated contract included, comes back as a flat
  `:seon.error` value in `:seon.sci.admit/value`. A test that calls an
  agent-facing function directly with an argument its DECLARED CONTRACT
  forbids is therefore not testing the agent's own boundary at all: under the
  contracts every cluster arms, the contract refuses first and the direct
  call throws (AGENTS §2.4, and the issue this helper closes). Drive the
  form through here and the assertion is about the value an agent genuinely
  receives.

  `ctx` is a live SCI ctx (`fork-cluster-ctx`); the caps, the one time limit
  and the error dial come from the shipped decisions."
  ([ctx source] (agent-value ctx source nil))
  ([ctx source namespace-name]
   (let [decisions config/defaults]
     (:seon.sci.admit/value
      (evaluate
       (cond-> {:seon.cluster.eval/source source
                :seon.sci.eval/ctx ctx
                :seon.sci.admit/caps (config/result-caps decisions)
                :seon.sci.eval/time-limit-ms
                (:seon.config.eval/time-limit-ms decisions)}
         namespace-name (assoc :seon.cluster.eval/ns
                               [:seon.ns/name namespace-name])))))))

(defn cluster-handle
  "One agent's cluster handle, carrying every structural member production
  arms and leaving the cluster's own identity to the caller.

  `:seon.turn.loop/cluster` declares three channels, the admission caps
  and five dials; `seon.cluster/arm-agent-instance!` builds all of them from
  the cluster's compiled decisions. A fixture that hands `turn`, `settle!`,
  `evaluate-sources` or `terminal-data` a handful of the members it happens
  to read hands a shape the declared contract forbids, and the members it
  drops are exactly the ones the FAILURE paths need — the fault bound, the
  recurrence limit — so the omission only ever shows up when something has
  already gone wrong (AGENTS §5: supply every declared input).

  Everything structural is defaulted here from the world's own decisions,
  the effective config of the handle's cluster on its connection (the
  shipped decisions for a handle naming no cluster world), so no suite
  carries constants of its own; the caller wins, so a fixture that drives a
  channel or pins a dial supplies its own and keeps it. The cluster's
  identity — connection, name, process, SCI ctx — is never defaulted: those
  are the caller's world."
  [handle]
  (let [connection (:seon.db/connection handle)
        decisions (if (and connection (:seon.cluster/name handle))
                    (config/effective (db/db connection) (:seon.cluster/name handle))
                    config/defaults)
        _ (when (:seon.error/at decisions)
            (throw (ex-info (str (:seon.error/message decisions)) decisions)))]
    (merge {:seon.agent/routing (attempt-routing (:seon.db/connection handle))
            :seon.cluster.wake/channel (async/chan (async/sliding-buffer 1))
            :seon.render/context-channel (async/chan (async/sliding-buffer 1))
            :seon.turn.loop/completion (async/promise-chan)
            :seon.sci.admit/caps (config/result-caps decisions)
            :seon.config.eval/time-limit-ms
            (:seon.config.eval/time-limit-ms decisions)
            :seon.config.error/recurrence-limit
            (:seon.config.error/recurrence-limit decisions)
            :seon.config.error/max-evidence-bytes
            (:seon.config.error/max-evidence-bytes decisions)
            :seon.config.message/max-chain
            (:seon.config.message/max-chain decisions)}
           handle)))

(defn environment
  "One subset environment (store + facts, no graphs, no web) for a test.

  The bracket allocates nothing of its own here: it calls the same
  `seon.env` constructor boot calls, with only the layers the test
  actually stood up. A test that has a connection supplies it and gets
  the facts layer too; a test exercising pure Flow plumbing supplies
  only its cluster name."
  ([cluster-name]
   (environment cluster-name nil))
  ([cluster-name connection]
   (env/refuse-incomplete-environment!
    (env/environment
     (cond-> {:seon.boot/cluster-name cluster-name}
       connection
       (assoc :seon.db/connection connection
              :seon.schema/projection
              (let [database (db/db connection)]
                (or (db/carried-projection database)
                    (schema/projection-from-database database)))))))))

(defn await-event!
  "Await one channel, latch, future, or watched reference with a loud backstop.
  The fourth argument supplies the observation bound in milliseconds."
  ([event-source event]
   (await-event! event-source event (constantly true)))
  ([event-source event accept?]
   (await-event! event-source event accept?
                 (.toMillis TimeUnit/SECONDS event-backstop-seconds)))
  ([event-source event accept? timeout-ms]
   (cond
     (instance? clojure.lang.IRef event-source)
     (let [watch-key (Object.)
           accepted (async/promise-chan)
           publish! (fn [value]
                      (when (accept? value)
                        (async/offer! accepted [value])))]
       (add-watch event-source watch-key
                  (fn [_ _ _ value] (publish! value)))
       (try
         (publish! @event-source)
         (first (await-event! accepted event (constantly true) timeout-ms))
         (finally
           (remove-watch event-source watch-key)
           (async/close! accepted))))

     (instance? CountDownLatch event-source)
     (if (.await ^CountDownLatch event-source
                 timeout-ms
                 TimeUnit/MILLISECONDS)
       true
       (throw
        (ex-info
         "The test did not observe its required latch event."
         {::event event ::timeout-ms timeout-ms})))

     (instance? Future event-source)
     (try
       (.get ^Future event-source
             timeout-ms
             TimeUnit/MILLISECONDS)
       (catch ExecutionException failure
         (throw (.getCause failure)))
       (catch TimeoutException timeout
         (.cancel ^Future event-source true)
         (throw
          (ex-info
           "The test future did not publish its required completion."
           {::event event ::timeout-ms timeout-ms}
           timeout))))

     (satisfies? async.impl/ReadPort event-source)
     (let [backstop
           (async/timeout timeout-ms)]
       (loop []
         (let [[value selected] (async/alts!! [event-source backstop])]
           (cond
             (= selected backstop)
             (throw
              (ex-info
               "The test channel did not publish its required event."
               {::event event ::timeout-ms timeout-ms}))

             (nil? value)
             (throw
              (ex-info
               "The test channel closed before its required event."
               {::event event}))

             (accept? value)
             value

             :else
             (recur)))))

     :else
     (throw
      (ex-info
       "The test event source is not a channel, latch, future, or watched reference."
       {::event event
        ::event-source (class event-source)})))))

(defn delete-recursively!
  "Delete one project-local `tmp/` descendant or refuse the broad target.

  `seon.fs/delete-recursively!` owns the no-follow walk. This wrapper supplies
  the test fixture's explicit project-local `tmp/` authority and refuses the
  authority root itself."
  [path]
  (let [root-path (.normalize (.toAbsolutePath (.toPath (io/file "tmp"))))
        target-path (.normalize (.toAbsolutePath (.toPath (io/file path))))]
    (when (or (= root-path target-path)
              (not (.startsWith target-path root-path)))
      (throw
       (ex-info
        "Recursive test cleanup is restricted to descendants of tmp/."
        {::path (str target-path)})))
    (fs/delete-recursively! (str root-path) (str target-path))))

(defn file-store-probe-schema
  "Return the one synthetic string marker attribute for a file-store test."
  [marker-attribute]
  [{:db/ident marker-attribute
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}])

(defn file-store-markers
  "Read every value of a file-store test's synthetic marker attribute.

  A flat read refusal (an uninstalled marker attribute) is returned as that
  error value, never poured element-wise into the marker set."
  [connection marker-attribute]
  (let [result (db/q
                '[:find [?marker ...]
                  :in $ ?marker-attribute
                  :where
                  [_ ?marker-attribute ?marker]]
                (db/db connection)
                marker-attribute)]
    (if (map? result)
      result
      (set result))))

(defn read-operations
  "Run `body` with `seon.db/*read-evidence-sink*` bound and answer
  `[result operations]`: each `seon.db` read's `:seon.db/read-operation` in
  order (`:q`, `:pull`, `:pull-many`; `seon.db/entity` records `:pull`), or
  `:index` for an index read such as `datoms`, whose evidence carries a plan
  and no request. The sink is a binding, so only this thread and threads that
  convey its bindings are observed: a concurrent test's reads never count,
  and no Var is redefined."
  {:malli/schema [:=> [:cat [:fn clojure.core/ifn?]]
                  [:tuple [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary
                                 :seon.schema.admission/reason "The body's arbitrary result is returned unchanged."}]
                   [:vector :keyword]]]}
  [body]
  (let [sink (atom [])
        result (binding [db/*read-evidence-sink* sink] (body))]
    [result (mapv #(get-in % [:seon.db/read-request :seon.db/read-operation] :index) @sink)]))

(defn armed-counts
  "The calls each of `symbols` has counted so far, summed over its armed
  cells in seon.profile (`profile/cells`: the host Var's and any SCI
  context's). Diff two readings to count the calls between them; no Var is
  redefined. A symbol with no armed cell refuses, since a zero from an
  unarmed Var proves nothing. The counters are JVM-wide: a concurrent request
  calling the same Var counts too, so an exact count is honest only for a Var
  no concurrent member reaches."
  {:malli/schema [:=> [:cat [:set :qualified-symbol]] [:map-of :qualified-symbol :int]]}
  [symbols]
  (let [found (reduce (fn [found cell]
                        (let [sym (:seon.profile/sym cell)]
                          (if (contains? symbols sym)
                            (update found sym (fnil + 0) (:seon.profile/calls (profile/observation cell)))
                            found)))
                      {} (profile/cells))
        missing (remove #(contains? found %) symbols)]
    (when (seq missing)
      (throw (ex-info (str "No armed profile cell counts " (vec missing) ": arm the Var before counting its calls.")
                      {:seon.error/operation 'seon.test-support/armed-counts
                       :seon.error/expected :seon.profile/cell
                       :seon.error/offending (vec missing)})))
    found))

(defn armed-calls
  "Run `body` and answer `[result calls]`: `calls` maps each of `symbols` to
  the calls counted while `body` ran, other threads' included
  (`armed-counts`)."
  {:malli/schema [:=> [:cat [:set :qualified-symbol] [:fn clojure.core/ifn?]]
                  [:tuple [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary
                                 :seon.schema.admission/reason "The body's arbitrary result is returned unchanged."}]
                   [:map-of :qualified-symbol :int]]]}
  [symbols body]
  (let [before (armed-counts symbols)
        result (body)]
    [result (merge-with - (armed-counts symbols) before)]))

(defn hiccup-elements
  "Every element of a hiccup value, depth first: a test asserts on the node its
  behavior is about, never on the serialized page."
  {:malli/schema [:=> [:cat :seon.schema/value] [:sequential :seon.schema/value]]}
  [hiccup]
  (filter #(and (vector? %) (keyword? (first %))) (tree-seq sequential? seq hiccup)))

(defn hiccup-leaves
  "The leaves a hiccup value shows (strings, symbols, numbers), attributes skipped."
  {:malli/schema [:=> [:cat :seon.schema/value] [:sequential :seon.schema/value]]}
  [hiccup]
  (cond (and (vector? hiccup) (keyword? (first hiccup))) (mapcat hiccup-leaves (remove map? (rest hiccup)))
        (sequential? hiccup) (mapcat hiccup-leaves hiccup)
        (nil? hiccup) []
        :else [hiccup]))

(defn hiccup-classes
  "The class of every element of a hiccup value."
  {:malli/schema [:=> [:cat :seon.schema/value] [:set :seon.schema/value]]}
  [hiccup]
  (set (keep #(:class (second %)) (hiccup-elements hiccup))))

(defn reply
  "One `#:seon.repl{…}` response read back as the data it is. A tagged literal
  the reader cannot construct (an `#object`) stays data too, as its tag and
  form, so a test asserts on the reply's keys rather than on its bytes."
  {:malli/schema [:=> [:cat :string] :seon.schema/value]}
  [text]
  (binding [*read-eval* false
            *default-data-reader-fn* (fn [tag form] {::tag tag ::form form})]
    (read-string text)))

(defn refusal-data
  "Return a flat error value, deepest thrown ex-data, or `committed`."
  [thunk]
  (try
    (let [result (thunk)]
      (if (and (map? result)
               (inst? (:seon.error/at result))
               (qualified-keyword? (:seon.error/layer result))
               (qualified-symbol? (:seon.error/operation result)))
        result
        committed))
    (catch Throwable error
      (loop [throwable error
             found nil]
        (if throwable
          (recur (ex-cause throwable)
                 (or (not-empty (ex-data throwable)) found))
          (or found unknown-refusal))))))

(defn assert-check!
  "Assert a nonempty test.check result while retaining its complete shrink data."
  ([check]
   (assert-check! check "Generative check failed."))
  ([check message]
   (letfn [(without-duplicate-error [result]
             (let [error (get-in result
                                 [:result-data
                                  :clojure.test.check.properties/error])
                   result-data
                   (when-let [data (:result-data result)]
                     (if (identical? (:result result) error)
                       (not-empty
                        (dissoc data
                                :clojure.test.check.properties/error))
                       data))]
               (cond-> (if result-data
                         (assoc result :result-data result-data)
                         (dissoc result :result-data))
                 (and (map? (:shrunk result))
                      (instance? Throwable (:result result)))
                 (assoc :result false)

                 (map? (:shrunk result))
                 (update :shrunk without-duplicate-error))))]
     (let [passed? (and (pos-int? (:num-tests check))
                        (true? (:result check)))]
       ;; The failing check is the value the report prints, shrink data included.
       (test/testing message
         (test/is (nil? (when-not passed? (without-duplicate-error check)))))))))

(defn- run-database-body
  "Call `body` with `connection` as its custody, under the projection the
  connection carries: the fixture's writes are its own branch's work."
  {:malli/schema [:=> [:cat :seon.db/connection [:or :nil [:vector :map]] [:fn clojure.core/ifn?]]
                  [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary
                         :seon.schema.admission/reason "A fixture returns its body's arbitrary result unchanged."}]]}
  [connection extra-schema body]
  (db/call-with-custody
   {:seon.db/connection connection}
   #(schema/call-with-projection-state
     (:seon.sci.eval/projection-state (meta connection))
     (fn []
       (when (seq extra-schema)
         (checked-fixture-result (db/transact! connection {:tx-data extra-schema})))
       (body connection)))))

(defn- with-fresh-database
  "Run `body` on an independent, empty in-memory store carrying the executing
  program's installed attribute schema. Blob keys, roster and GC are
  store-global, so a subject over them needs a store, not a branch; its
  program facts are the test's own writes."
  {:malli/schema [:=> [:cat [:or :nil :uuid :string] [:or :nil [:vector :map]] [:fn clojure.core/ifn?]]
                  [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary
                         :seon.schema.admission/reason "A fixture returns its body's arbitrary result unchanged."}]]}
  [database-id extra-schema body]
  (let [handle (execution-handle db/*conn*)
        source (db/db (:seon.db/connection handle))
        source-config (:config source)
        configuration (cond-> {:store {:backend :memory :id (or database-id (random-uuid))}
                               :schema-flexibility (:schema-flexibility source-config)
                               :keep-history? (:keep-history? source-config)}
                        (contains? source-config :attribute-refs?)
                        (assoc :attribute-refs? (:attribute-refs? source-config)))
        attributes (into []
                         (keep (fn [[ident attribute]]
                                 (when (and (keyword? ident) (namespace ident)
                                            (not (#{"db" "db.install" "db.type" "db.cardinality"
                                                    "db.unique" "db.part"} (namespace ident))))
                                   (dissoc attribute :db/id))))
                         (d/schema source))
        _ (d/create-database configuration)
        connection (d/connect configuration)]
    (try
      (d/transact connection attributes)
      (db/carry-connection-projection-state!
       connection (sci.eval/projection-state @connection (db/carried-projection source)
                                             (:seon.cluster/name handle)))
      (run-database-body connection extra-schema body)
      (finally
        (d/release connection)
        (d/delete-database configuration)))))

(defn- with-branched-database
  "Run `body` on a fresh branch off the commit the executing handle was
  acquired at, acquired and released through the agent entrance: an isolated
  agent for one fixture. A nested fixture never sees its parent's writes."
  {:malli/schema [:=> [:cat [:or :nil [:vector :map]] [:fn clojure.core/ifn?]]
                  [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary
                         :seon.schema.admission/reason "A fixture returns its body's arbitrary result unchanged."}]]}
  [extra-schema body]
  (let [parent (execution-handle db/*conn*)
        handle (agent/acquire-context!
                parent nil {:seon.agent/isolate? true
                            :seon.cluster.registry/from (:seon.source/commit-id parent)})]
    (try
      (run-database-body (:seon.db/connection handle) extra-schema body)
      (finally (agent/release-context! handle)))))

(defn with-database
  "Run `body` on an isolated branch of the executing test's database.

   Every invocation acquires its own branch, connection, datoms, history and
   writer through `seon.cluster.agent/acquire-context!` off the commit the
   executing handle was acquired at, runs the body under that connection's
   custody, and releases it (unlinked) through `release-context!`. It
   never copies a store and never indexes source. It needs the handle that
   `seon.test/run` acquired for the member; without one it refuses by name.
   `:seon.test-support/extra-schema` installs synthetic declarations.

   `:seon.test-support/fresh-store?` (or `:seon.test-support/database-id`)
   supplies an independent empty in-memory store with the installed attribute
   schema, for store-global subjects such as blob keys."
  {:malli/schema [:function
                  [:=> [:cat [:fn clojure.core/ifn?]]
                   [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary
                         :seon.schema.admission/reason "A fixture returns its body's arbitrary result unchanged."}]]
                  [:=> [:cat :map [:fn clojure.core/ifn?]]
                   [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary
                         :seon.schema.admission/reason "A fixture returns its body's arbitrary result unchanged."}]]]}
  ([body] (with-database {} body))
  ([{:seon.test-support/keys [database-id extra-schema fresh-store?]} body]
    (if (or database-id fresh-store?)
      (with-fresh-database database-id extra-schema body)
      (with-branched-database extra-schema body))))


(defn turn-closed-at
  "The instant a turn closed, read THROUGH its closing transaction ref.

   `:seon.turn/closed-tx` IS the closing transaction (`seon.turn/open?` means
   no closed-tx), so pulling the attribute answers `{:db/id …}` and never an
   instant — the shape forty-five assertions were comparing to `inst?` since
   the transaction refs landed (`ae0e54841`). The time lives on the
   transaction, exactly as `seon.plan` reads `:my.plan.item/completed-tx`.
   Absent while the turn is still open."
  [database turn-id]
  (get-in (db/pull database
                   [{:seon.turn/closed-tx [:db/txInstant]}]
                   [:seon.turn/id turn-id])
          [:seon.turn/closed-tx :db/txInstant]))

(defn namespace-entity
  "A namespace declaration exactly as an agent's `(ns name)` writes it.
   `program/declaration-row` owns the row and derives its definition digest."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.ns/name] :seon.ns/ns]}
  [database namespace-name]
  (program/declaration-row
   (db/carried-projection database)
   {:seon.ns/name namespace-name
    :seon.ns/source (pr-str (list 'ns namespace-name))}
   :all :agent))

(defn agent-tx
  "Create one fixture agent through `seon.cluster.agent/creation-tx`.
   The agent joins the cluster the fixture seeded, else the executing
   handle's own cluster; its namespace
   defaults to `my.agents.<agent-id>`."
  {:malli/schema [:function
                  [:=> [:cat :seon.db/database-value :seon.agent/id]
                   :seon.store/transaction-data]
                  [:=> [:cat :seon.db/database-value :seon.agent/id :seon.ns/name]
                   :seon.store/transaction-data]]}
  ([database agent-id]
   (agent-tx database agent-id (symbol (str "my.agents." agent-id))))
  ([database agent-id namespace-name]
   (agent/creation-tx
    {:seon.agent/id agent-id
     :seon.ns/name namespace-name
     :seon.cluster/name
     (or (seeded-cluster-name nil database)
         (throw (ex-info "A fixture agent needs the one cluster its fixture seeded, or its handle's own."
                         {:seon.error/operation 'seon.test-support/agent-tx
                          :seon.error/expected :seon.cluster/name
                          :seon.error/offending
                          (db/q '[:find [?name ...] :where [_ :seon.cluster/name ?name]]
                                database)})))})))

(defn agents-tx
  "Create several fixture agents, each through `agent-tx`."
  {:malli/schema [:=> [:cat :seon.db/database-value [:vector :seon.agent/id]]
                  :seon.store/transaction-data]}
  [database agent-ids]
  (into [] (mapcat #(agent-tx database %)) agent-ids))

(defn- digest-namespace-entity
  "The namespace row the writer digests a declaration of `namespace-name` with:
   the stored row as the writer reads it (`seon.db/declaration-value`), else the
   canonical row a fixture writes beside the declaration (`namespace-row`)."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.ns/name] :map]}
  [database namespace-name]
  (if-let [stored (db/q '[:find ?e . :in $ ?n :where [?e :seon.ns/name ?n]] database namespace-name)]
    (@#'seon.db/declaration-value database (db/identity-attributes database) stored)
    (namespace-entity database namespace-name)))

(defn schema-entities
  "Canonical schema declaration rows for synthetic `forms`, `[key definition]`
   pairs in dependency order, each declared onto `database`'s projection through
   `seon.schema/projection-with-schema`. The writer derives its projection from
   stored schema rows (`seon.db/carried-projection`), so a synthetic schema a
   fixture writes through is one of these rows, never a carried projection."
  {:malli/schema [:=> [:cat :seon.db/database-value [:vector [:tuple :qualified-keyword :any]]]
                  [:vector :map]]}
  [database forms]
  (let [projection (reduce (fn [current [schema-key definition]]
                             (schema/projection-with-schema
                              current schema-key definition {:seon.schema.admission/source :core}))
                           (db/carried-projection database) forms)]
    (vec (schema/canonical-schema-entities projection (into {} forms)))))

(defn revised-entity
  "`row` with `overrides` merged, digested from that FINAL row through the one
   digest owner `seon.db/definition-digest-of` with the namespace row the write
   leaves (`digest-namespace-row`), the digest `seon.db/write-digest-error`
   reproduces. Never assoc onto a row."
  {:malli/schema [:function
                  [:=> [:cat :seon.db/database-value :map :map] :map]
                  [:=> [:cat :seon.db/database-value :map :map [:maybe :map]] :map]]}
  ([database entity overrides]
   (revised-entity database entity overrides
                (some->> (or (:seon.fn/ns entity) (:seon.test/ns entity)) second
                         (digest-namespace-entity database))))
  ([database entity overrides namespace-entity]
   ;; The writer digests with the namespace entity the SAME transaction stores, so a
   ;; fixture that writes its namespace beside the entity hands that entity in.
   (let [entity (merge entity overrides)]
     (assoc entity :seon.program/definition-digest
            (db/definition-digest-of (db/carried-projection database) entity namespace-entity)))))

(defn revision
  "The upsert changing the STORED declaration `identity` by `overrides`, with the
   digest of its final value: the stored row as the writer reads it
   (`seon.db/declaration-value`), `overrides` merged, sets unioned as an upsert
   accretes them. The partial edit leaves the row's components alone."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.program/identity :map] :map]}
  [database [attribute value :as identity] overrides]
  (let [entity (or (db/q '[:find ?e . :in $ ?a ?v :where [?e ?a ?v]] database attribute value)
                   (throw (ex-info "A revision needs its stored declaration." {:seon.program/identity identity})))
        stored (@#'seon.db/declaration-value database (db/identity-attributes database) entity)]
    (assoc overrides attribute value
           :seon.program/definition-digest
           (:seon.program/definition-digest
            (revised-entity database (merge-with #(if (set? %1) (into %1 %2) %2) stored overrides) {})))))

(defn program-entity
  "Analyze an explicit synthetic declaration through the production source path,
   then `revised-row` with `overrides`. The row is the desired declaration, not
   a write: an upsert onto a stored identity keeps the owned attributes it drops
   and the writer refuses its digest, so write a stored identity through
   `replacement-tx`."
  ([database identity source] (program-entity database identity source {}))
  ([database identity source overrides]
   (let [projection (db/carried-projection database)
         [attribute declaration-symbol] identity
         namespace-name (symbol (namespace declaration-symbol))
         ns-entity (digest-namespace-entity database namespace-name)
         entities (seon.fn/source-rows
               database (program/shapes-in projection) ns-entity
               source (set (keys (:seon.schema.projection/forms projection))))]
     ;; Finalized as an agent's definition is: its contract's parsed facts
     ;; (`seon.fn/contract-facts-row`), then the digest of the final entity.
     (revised-entity
      database
      (seon.fn/contract-facts-row
       projection ns-entity
       (or (some #(when (= declaration-symbol (get % attribute)) %) entities)
           (throw (ex-info "The fixture source did not define the requested declaration."
                           {:seon.program/identity identity :seon.program/source source}))))
      overrides ns-entity))))

(defn replacement-tx
  "Transaction data leaving `identity` exactly as analyzed from `source` with
   `overrides`: a new declaration is its row; a stored one is replaced through
   `seon.program/exact-replacement-tx`, which retracts the owned attributes and
   components the new row drops, so the writer's final value is this row."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.program/identity :string :map]
                  :seon.store/transaction-data]}
  [database identity source overrides]
  (let [entity (program-entity database identity source overrides)]
    (if-let [stored (db/pull database '[*] identity)]
      (program/exact-replacement-tx stored entity)
      [entity])))

(defn program-fn-entity
  "A complete indexed declaration, or an explicitly analyzed agent fixture.
   The one-argument form re-indexes the declaration's own file through
   publication's incremental path (`seon.fn/rows` with that one changed path,
   against the world the executing member acquired): the canonical file row
   with its file provenance and stored digest, in 25-300 ms, instead of the
   whole-checkout manifest (12.8-15 s cold, refused on the dev roots).
   Synthetic definitions require the database so the production analyzer owns
   their resolver context and producing digest; `overrides` are digested in."
  ([function-symbol]
   (let [world (:seon.db/db (execution-handle db/*conn*))
         path (get-in (db/pull world [{:seon.fn/file [:seon.fn.file/relative-path]}]
                               [:seon.fn/sym function-symbol])
                      [:seon.fn/file :seon.fn.file/relative-path])]
     (or (when path
           (some #(when (= function-symbol (:seon.fn/sym %)) %)
                 (seon.fn/program-entities {:seon.fn/roots seon.fn/source-roots
                                :seon.fn/changed-paths #{path}
                                :seon.source/previous-database world})))
         (throw (ex-info "The executing member's world indexes no file defining this declaration; a synthetic fixture definition requires its database and source."
                         {:seon.error/operation 'seon.test-support/program-fn-entity
                          :seon.error/expected :seon.fn/file
                          :seon.error/offending function-symbol})))))
  ([database function-symbol source]
   (program-entity database [:seon.fn/sym function-symbol] source {}))
  ([database function-symbol source overrides]
   (program-entity database [:seon.fn/sym function-symbol] source overrides)))

(defn apply-config!
  "Set one cluster's config dials through the production path.

   `config/apply!` compiles the manifest over the decisions the cluster's row
   already holds and exact-reconciles the one desired row, exactly as
   `bin/seon config apply` does. A later manifest changes only its own keys."
  [connection cluster-name manifest]
  (checked-fixture-result
   (db/call-with-custody {:seon.db/connection connection}
                         #(config/apply! {:seon.db/connection connection
                                          :seon.boot/cluster-name cluster-name
                                          :seon.config/manifest manifest}))))

(defn seed-cluster!
  "Seed one complete cluster/config path for tests that create agents.

   The manifest arity carries the cluster's config overlay through
   `apply-config!`, so a test that needs a dial never hand-writes a partial
   config row."
  ([connection cluster-name]
   (seed-cluster! connection cluster-name {}))
  ([connection cluster-name manifest]
   (apply-config! connection cluster-name manifest)
   (checked-fixture-result
    (db/call-with-custody {:seon.db/connection connection}
                          #(cluster/ensure-cluster-entity!
                            connection cluster-name cluster/boot-process-identity)))
   ;; The entity a fork records (registry/fork-entity): the seeded cluster descends
   ;; from the program the executing member's own cluster adopted, which a
   ;; test request's world branches from.
   (when-let [source-commit
              (:seon.source/commit-id
               (db/pull (db/db connection) [:seon.source/commit-id]
                        [:seon.cluster/name (:seon.cluster/name (execution-handle connection))]))]
     (transacted! connection [(registry/fork-entity cluster-name source-commit)]))
   nil))

(defn preserving-instrumentation-state
  "Scope a test's deliberate instrumentation changes to that test.
   Restore the entering instrumentation state even when the body throws.

   `seon.instrument` owns what restoring means, including the one case a
   captured root cannot answer for: a body that reloads the program's
   namespaces in this JVM (development adoption) replaces the protocols,
   types and classes those closures build from, so the Vars it named are
   left as the loader left them (`seon.instrument/replaced-definitions`)."
  [body]
  (let [state (instrument/state)]
    (try
      (body)
      (finally
        (instrument/restore! state)))))

(defn preserving-schema-registry
  "Scope a test's deliberate schema-registry changes to that test.

  A SEPARATE owner from `preserving-instrumentation-state`, deliberately:
  the two preserve different shared state for different call sets (a test
  that arms a contract rarely declares a schema, and vice versa), and folding
  the projection snapshot into the instrumentation helper would make every
  instrumentation test pay for a projection it never touches while naming the
  concern wrongly. Both restore in a `finally`, including after a throw.

  What it preserves is each RUNNING cluster's schema projection — the only
  shared schema registry a JVM has. The packaged declaration forms are
  derived from classpath resources on every call and hold no mutable state,
  and `seon.schema/register!` refuses outside an isolated candidate delta, so
  neither can be leaked into.

  `seon.test/run` applies the same restore around EVERY in-process run, so
  this helper is the scoped version for a test that changes the registry
  deliberately and wants the change gone before its own later assertions."
  [body]
  (let [before ((requiring-resolve 'seon.test.runner/live-cluster-schema-states))]
    (try
      (body)
      (finally
        ((requiring-resolve 'seon.test.runner/restore-live-cluster-schema!)
         before)))))

(defn closeable
  "Adapt an acquired fixture value to Clojure's with-open cleanup scope.
   Dereferencing returns the value; closing calls its supplied release function."
  [value release!]
  (reify java.io.Closeable
    (close [_] (release! value))
    clojure.lang.IDeref
    (deref [_] value)))
