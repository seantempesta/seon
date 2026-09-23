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
            [seon.program :as program]
            [seon.schema :as schema]
            [seon.sci.eval :as sci.eval]
            [seon.test])
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
                state (sci.eval/projection-state database projection)]
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

(defn- offending-fixture-row
  "The authored row write admission refused, taken from its reported path.

   The refusal's own `:seon.db/entity-form` is the entity SCHEMA it was read
   against, not the row: the row is the one the fixture wrote at the path's
   leading index, so a seeding fixture reads back exactly what it authored."
  [tx-data report]
  (let [rows (if (map? tx-data) (:tx-data tx-data) tx-data)
        index (first (:seon.db/path report))]
    (when (and (int? index) (sequential? rows) (< -1 index (count rows)))
      [index (nth rows index)])))

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
  (let [report (db/transact! connection tx-data)]
    (when-not (and (map? report)
                   (some? (:db-after report)))
      (throw
       (ex-info
        (str "Fixture write was refused at the write: "
             (or (:seon.error/message report)
                 (str "no :db-after in " (pr-str report)))
             (when-let [[index row] (offending-fixture-row tx-data report)]
               (str " Offending row " index ": " (pr-str row) ".")))
        (cond-> (if (map? report) report {:seon.db/report report})
          true (assoc :seon.db/tx-data tx-data)))))
    report))

(defn- held-handle
  "The execution handle the agent entrance retains for `connection`, or nil.

  `seon.cluster.agent/acquire-context!` keeps every handle it acquires in the
  context state its environment carries; the connection's carried projection
  state is that environment. No registry beside it is consulted."
  {:malli/schema [:=> [:cat [:or :nil :seon.db/connection]]
                  [:or :nil :seon.agent/execution-handle]]}
  [connection]
  (when connection
    (let [environment (env/of {env/state-carrier
                               (:seon.sci.eval/projection-state (meta connection))})]
      (some (fn [[_ handle]]
              (when (identical? connection (:seon.db/connection handle)) handle))
            (some-> (:seon.agent/context-state environment) deref)))))

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

(defn- seeded-cluster-name
  "The one cluster this fixture stood up, DERIVED, or nil when it seeded none."
  [database]
  (let [names (sort (db/q '[:find [?name ...]
                            :where [_ :seon.cluster/name ?name]]
                          database))]
    (when (= 1 (count names))
      (first names))))

(defn fork-cluster-ctx
  "Fork the executing handle's context onto `connection` through the production fork.
  Custody, projection and environment repointing belong to sci.eval; a named
  cluster environment keeps the entrance's context state and held store."
  {:malli/schema [:function
                  [:=> [:cat :seon.db/connection] :seon.sci.eval/ctx]
                  [:=> [:cat :seon.db/connection [:maybe :seon.boot/cluster-name]]
                   :seon.sci.eval/ctx]]}
  ([connection]
   (fork-cluster-ctx connection (seeded-cluster-name (db/db connection))))
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
                          :seon.agent/context-state (:seon.agent/context-state handle)}
                   (:seon.store/store handle)
                   (assoc :seon.store/store (:seon.store/store handle)))))))))))


(defn agent-value
  "Evaluate `source` at the boundary an AGENT actually calls, and return the
  value the agent reads.

  An agent never invokes a Var directly: its reply is read into forms and
  each one crosses `seon.sci.eval/evaluate`, which is total by construction —
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
      (sci.eval/evaluate
       (cond-> {:seon.cluster.eval/source source
                :seon.sci.eval/ctx ctx
                :seon.sci.admit/caps (config/result-caps decisions)
                :seon.sci.eval/time-limit-ms
                (:seon.config.eval/time-limit-ms decisions)
                :seon.config/on-core-error
                (:seon.config/on-core-error decisions)}
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

  Everything structural is defaulted here from the SHIPPED decisions, once,
  so no suite carries constants of its own; the caller wins, so a fixture
  that drives a channel or pins a dial supplies its own and keeps it. The
  cluster's identity — connection, name, process, SCI ctx — is never
  defaulted: those are the caller's world."
  [handle]
  (let [decisions config/defaults]
    (merge {:seon.agent/context-state (atom {})
            :seon.cluster.wake/channel (async/chan (async/sliding-buffer 1))
            :seon.render/context-channel (async/chan (async/sliding-buffer 1))
            :seon.turn.loop/completion (async/promise-chan)
            :seon.sci.admit/caps (config/result-caps decisions)
            :seon.config.eval/time-limit-ms
            (:seon.config.eval/time-limit-ms decisions)
            :seon.config/on-core-error (:seon.config/on-core-error decisions)
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
       (test/is passed?
              (str message " "
                   (pr-str (without-duplicate-error check))))))))

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
       connection (sci.eval/projection-state @connection (db/carried-projection source)))
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

(defn namespace-row
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
   The agent joins the one cluster the fixture database holds; its namespace
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
     (or (seeded-cluster-name database)
         (throw (ex-info "A fixture agent needs exactly one cluster in its database."
                         {:seon.error/operation 'seon.test-support/agent-tx
                          :seon.error/expected :seon.cluster/name
                          :seon.error/offending
                          (db/q '[:find [?name ...] :where [_ :seon.cluster/name ?name]]
                                database)})))})))

(defn program-row
  "Analyze an explicit synthetic declaration through the production source path."
  [database identity source]
  (let [projection (db/carried-projection database)
        [attribute declaration-symbol] identity
        rows (seon.fn/source-rows
              database (program/shapes-in projection)
              (namespace-row database (symbol (namespace declaration-symbol)))
              source (set (keys (:seon.schema.projection/forms projection))))]
    (or (some #(when (= declaration-symbol (get % attribute)) %) rows)
        (throw (ex-info "The fixture source did not define the requested declaration."
                        {:seon.program/identity identity :seon.program/source source})))))

(defn program-fn-row
  "A complete indexed declaration, or an explicitly analyzed agent fixture.
   The one-argument form returns the canonical source artifact's actual row.
   Synthetic definitions require the database so the production analyzer owns
   their resolver context and producing digest."
  ([function-symbol]
   (or (some #(when (= function-symbol (:seon.fn/sym %)) %)
             (mapcat :seon.fn.file/rows (:seon.fn.manifest/artifacts @source-manifest)))
       (throw (ex-info "A synthetic fixture definition requires its database and source."
                       {:seon.fn/sym function-symbol}))))
  ([database function-symbol source]
   (program-row database [:seon.fn/sym function-symbol] source)))

(defn apply-config!
  "Set one cluster's config dials through the production path.

   A fixture that upserts `{:seon.config/cluster name :seon.config.x/dial v}`
   is writing a PARTIAL config entity. Write admission reads an identity-keyed
   map against the WHOLE config schema, so it refuses the row for
   `:seon.config/applied-manifest-digest` — a digest only `compile-manifest`
   can compute from the complete effective config. Such a seed has been
   landing nothing; the dial the test thought it set stayed at its default.

   `config/apply!` compiles the manifest and exact-reconciles the one desired
   row, exactly as `bin/seon config apply` does. It is EXACT: the manifest is
   the cluster's whole overlay, not an addition to an earlier one."
  [connection cluster-name manifest]
  (checked-fixture-result
   (config/apply! {:seon.db/connection connection
                   :seon.boot/cluster-name cluster-name
                   :seon.config/manifest manifest})))

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
    (cluster/ensure-cluster-entity!
     connection cluster-name cluster/boot-process-identity))
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
