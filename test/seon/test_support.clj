(ns seon.test-support
  "Shared test constructions which invoke production owners."
  (:require [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as async.impl]
            [clojure.java.io :as io]
            [clojure.test :as test]
            [datahike.api :as d]
            [malli.instrument :as mi]
            [seon.cluster :as cluster]
            [seon.cluster.export :as cluster.export]
            [seon.cluster.registry :as registry]
            [seon.cluster.source :as source]
            [seon.cluster.store :as store]
            [seon.config :as config]
            [seon.db :as db]
            [seon.env :as env]
            [seon.fs :as fs]
            [seon.fn :as seon.fn]
            [seon.instrument :as instrument]
            [seon.schema :as schema]
            [seon.sci.eval :as sci.eval]
            [seon.test.cache :as cache])
  (:import [java.util.concurrent CountDownLatch Future TimeUnit
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
   (config/defaults))
  ([manifest]
   (:seon.config/effective
    (config/compile-manifest {:seon.config/manifest manifest}))))

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

(defn- clone-directory!
  "Copy one immutable test base into a private mutable root."
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
        output (future (slurp (.getInputStream process)))
        exit (.waitFor process)]
    (when-not (zero? exit)
      (throw
       (ex-info "The shared published test base could not be cloned."
                {:seon.error/kind ::published-base-clone-failed
                 ::source (.getPath source)
                 ::target (.getPath target)
                 ::exit exit
                 ::output @output})))
    @output
    (.getPath target)))

(declare delete-recursively!)

(defn- replace-directory!
  [authority source target]
  (when (.exists (io/file target))
    (fs/delete-recursively! (str authority) (str target)))
  (clone-directory! source target))

(defn populate-published-root!
  "Populate `root` from the runner's immutable base, or publish standalone."
  [root]
  (if-let [base (System/getProperty "seon.test.published-base")]
    (let [root (clone-directory! base root)
          source-store (io/file base "data" "store")
          store (:seon.boot/store-dir
                 (cluster/resolve-bootstrap {:seon.boot/root root}))
          authority (or (System/getProperty "seon.operator.root") root)]
      (replace-directory! authority source-store store)
      (cluster.export/reidentify!
       store)
      root)
    (do
      (cluster/refresh-source! (str root))
      (str root))))

(defn populate-published-operator-root!
  "Populate an operator root from the runner's immutable current-src base."
  [root]
  (if-let [base (System/getProperty "seon.test.published-base")]
    (let [source-store (io/file base "data" "store")
          store (io/file root "data" "store")]
      (replace-directory! root source-store store)
      (cluster.export/reidentify! (str store))
      (str root))
    (do
      (cluster/refresh-source! (str (io/file root "data" "clusters")))
      (str root))))

(defn with-published-file-database
  "Run `body` on a private file-store branch of the published test base."
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
          (body connection)
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

(def source-manifest
  ;; The isolated runner publishes this exact manifest once per snapshot.
  ;; Direct iteration derives it once from its own source checkout.
  (delay
    (if-let [base (System/getProperty "seon.test.published-base")]
      (cache/manifest base)
      (seon.fn/build-manifest {:seon.fn/roots seon.fn/source-roots}))))

(def ^:private branch-leases
  ;; Datahike deletes a branch from the roster but retains its head until whole
  ;; store deletion. Reusing released names bounds retained heads by peak nested
  ;; fixture concurrency rather than total trial count.
  (atom {:seon.test-support/available []
         :seon.test-support/next 0}))

(defn- acquire-branch!
  []
  (let [[before _]
        (swap-vals!
         branch-leases
         (fn [{available :seon.test-support/available
               next-id :seon.test-support/next
               :as leases}]
           (if (seq available)
             (assoc leases :seon.test-support/available (pop available))
             (assoc leases :seon.test-support/next (inc next-id)))))
        available (:seon.test-support/available before)]
    (if (seq available)
      (peek available)
      (keyword "seon.test-support.fixture" (str (:seon.test-support/next before))))))

(defn- release-branch!
  [branch]
  (swap! branch-leases update :seon.test-support/available conj branch)
  nil)

(defn- populate-database!
  [connection]
  (cluster/populate-source!
   {:seon.db/connection connection
    :seon.fn/manifest @source-manifest})
  ;; `populate-source!` is the contents step used by production
  ;; `source/publish!`; production seals that completed population in the
  ;; following transaction. Keep this canonical fixture on the same side of
  ;; that provenance boundary so indexed core contracts are not misclassified
  ;; as agent-authored rows.
  (db/transact!
   connection
   {:tx-data [{:seon.source/digest (apply str (repeat 64 "0"))}]})
  nil)

(defn- close-base!
  [configuration connection]
  (d/release connection)
  (d/delete-database configuration)
  nil)

(defn- create-base
  []
  (let [configuration
        {:store {:backend :memory :id (random-uuid)}
         ;; Without commit records, reusing a bounded branch-name pool also
         ;; bounds the memory store's retained keys. Tests needing commit-graph
         ;; semantics own a production-shaped store instead of this fixture.
         :commit-graph? false
         :keep-history? true
         :schema-flexibility :write}
        base (System/getProperty "seon.test.published-base")
        configuration
        (if base
          (let [source (store/datahike-configuration (str (io/file base "data" "store")))
                backend (:store source)
                id (:id backend)]
            (-> source
                (dissoc :fuse-index-roots? :index-config)
                (assoc :branch source/current-branch
                       :store {:backend :tiered :id id
                               :frontend-config {:backend :memory :id id}
                               :backend-config backend
                               :write-policy :frontend-only
                               :read-policy :frontend-first})))
          configuration)
        _ (when-not base (d/create-database configuration))
        connection (d/connect configuration)]
    (try
      (when-not base (populate-database! connection))
      (.addShutdownHook
       (Runtime/getRuntime)
       (Thread. ^Runnable #(close-base! configuration connection)
                "seon-test-database-base-cleanup"))
      {:seon.test-support/configuration configuration
       :seon.test-support/connection connection
       :seon.sci.eval/ctx (sci.eval/cluster-ctx @connection)}
      (catch Throwable failure
        (close-base! configuration connection)
        (throw failure)))))

(def ^:private database-base
  ;; One new test JVM gets one newly populated base. Nothing survives process
  ;; exit, and bin/test never reuses this delay across invocations.
  (delay (create-base)))

(defn- seeded-cluster-name
  "The one cluster this fixture stood up, DERIVED, or nil when it seeded none."
  [database]
  (let [names (sort (db/q '[:find [?name ...]
                            :where [_ :seon.cluster/name ?name]]
                          database))]
    (when (= 1 (count names))
      (first names))))

(defn fork-cluster-ctx
  "Fork the process source base's acquired SCI ctx for `connection`.

  THE ENVIRONMENT RIDES THE CTX, exactly as boot puts it there
  (`seon.cluster`, `env/replace-environment!` into the projection state).
  Without it `seon.call-preparation/hook` finds no connection and returns its
  arguments untouched, so every supplied default is inert: a producer
  contracted `[value database]` — the shape an attribute-declared HTML
  producer takes — was then invoked with one argument and answered
  `ArityException`, which the walk recorded as a renderer failure. A fixture
  that omits a declared input is the defect, not the contract (§5.1).

  The cluster name is DERIVED from the database rather than remembered: a
  fixture that seeded exactly one cluster gets a production-shaped
  environment; one that seeded none carries no environment, as before."
  ([connection]
   (fork-cluster-ctx connection (seeded-cluster-name @connection)))
  ([connection cluster-name]
   (let [base-ctx (:seon.sci.eval/ctx @database-base)
         projection (:seon.schema/projection base-ctx)
         projection-state (sci.eval/projection-state @connection projection)]
     (when cluster-name
       (env/replace-environment!
        projection-state
        (env/refuse-incomplete-environment!
         (env/environment {:seon.boot/cluster-name cluster-name
                           :seon.db/connection connection
                           :seon.db/basis-t (db/basis-t @connection)
                           :seon.schema/projection projection}))))
     (sci.eval/fork-cluster-ctx base-ctx @connection connection
                                projection-state))))

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
   (let [decisions (config/defaults)]
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
  (let [decisions (config/defaults)]
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
              (schema/projection-from-database @connection)))))))

(defn await-event!
  "Await one channel, latch, or future event with a loud backstop."
  ([event-source event]
   (await-event! event-source event (constantly true)))
  ([event-source event accept?]
   (cond
     (instance? CountDownLatch event-source)
     (if (.await ^CountDownLatch event-source
                 event-backstop-seconds
                 TimeUnit/SECONDS)
       true
       (throw
        (ex-info
         "The test did not observe its required latch event."
         {::event event})))

     (instance? Future event-source)
     (try
       (.get ^Future event-source
             event-backstop-seconds
             TimeUnit/SECONDS)
       (catch TimeoutException timeout
         (throw
          (ex-info
           "The test future did not publish its required completion."
           {::event event}
           timeout))))

     (satisfies? async.impl/ReadPort event-source)
     (let [backstop
           (async/timeout
            (.toMillis TimeUnit/SECONDS event-backstop-seconds))]
       (loop []
         (let [[value selected] (async/alts!! [event-source backstop])]
           (cond
             (= selected backstop)
             (throw
              (ex-info
               "The test channel did not publish its required event."
               {::event event}))

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
       "The test event source is not a channel, latch, or future."
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
                @connection
                marker-attribute)]
    (if (map? result)
      result
      (set result))))

(defn refusal-data
  "Return a flat error value, deepest thrown ex-data, or `committed`."
  [thunk]
  (try
    (let [result (thunk)]
      (if (and (map? result) (keyword? (:seon.error/kind result)))
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
  "Assert one test.check result while retaining its complete shrink data."
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
     (let [passed? (true? (:result check))]
       (test/is passed?
              (str message " "
                   (pr-str (without-duplicate-error check))))))))

(defn- run-database-body
  [connection projection-state extra-schema body]
  (schema/call-with-projection-state
   projection-state
   (fn []
     (when (seq extra-schema)
       (db/transact! connection {:tx-data extra-schema}))
     (body connection))))

(defn- reconnect-with-projection
  ([configuration provisional-connection]
   (reconnect-with-projection configuration provisional-connection
                              (schema/projection-from-database
                               @provisional-connection)))
  ([configuration provisional-connection projection]
  (let [projection-state
        (sci.eval/projection-state @provisional-connection projection)]
    (d/release provisional-connection)
    {:seon.test-support/connection
     (schema/call-with-projection-state
      projection-state #(d/connect configuration))
     :seon.sci.eval/projection-state projection-state})))

(defn- with-fresh-database
  [database-id extra-schema body]
  (let [configuration
        {:store {:backend :memory :id (or database-id (random-uuid))}
         :keep-history? true
         :schema-flexibility :write}
        _ (d/create-database configuration)
        provisional-connection (d/connect configuration)]
    (try
      (populate-database! provisional-connection)
      (let [{connection :seon.test-support/connection
             projection-state :seon.sci.eval/projection-state}
            (reconnect-with-projection configuration provisional-connection)]
        (try
          (run-database-body connection projection-state extra-schema body)
          (finally
            (d/release connection))))
      (finally
        (d/release provisional-connection)
        (d/delete-database configuration)))))

(defn- with-branched-database
  [extra-schema body]
  (let [{configuration :seon.test-support/configuration
         base-connection :seon.test-support/connection
         base-ctx :seon.sci.eval/ctx} @database-base
        base-projection (:seon.schema/projection base-ctx)
        branch (acquire-branch!)]
    (try
      ;; Fork the sealed base head. With a published base, branch heads and
      ;; transactions live only in this JVM's Konserve memory frontend.
      (d/branch! base-connection (get configuration :branch :db) branch)
      (let [branch-configuration (assoc configuration :branch branch)
            provisional-connection (d/connect branch-configuration)]
        (try
          (let [{connection :seon.test-support/connection
                 projection-state :seon.sci.eval/projection-state}
            (reconnect-with-projection branch-configuration
                                           provisional-connection
                                           base-projection)]
            (try
              (run-database-body connection projection-state extra-schema body)
              (finally
                (d/release connection))))
          (finally
            (d/release provisional-connection))))
      (finally
        ;; Datahike refuses deletion while a child connection remains active.
        ;; Return the name only after successful retirement; a teardown failure
        ;; quarantines the lease rather than reusing live mutable state.
        (when (contains? (d/branches base-connection) branch)
          (d/delete-branch! base-connection branch))
        (release-branch! branch)))))

(defn with-database
  "Run `body` on a fresh branch of one canonical in-memory base.

   The production source population is installed once per new test JVM.
   Every invocation gets a distinct active branch, connection, datoms, schema
   evolution, transaction history, and writer. Optional
   `:seon.test-support/extra-schema` rows are synthetic declarations whose
   installation is itself part of a test.

   `:seon.test-support/database-id` preserves the legacy physical-store
   identity contract through an isolated slower path. Store-global blob tests
   request `:seon.test-support/fresh-store?` because blob keys are outside
   Datahike branch facts."
  ([body]
   (with-database {} body))
  ([{:seon.test-support/keys [database-id extra-schema fresh-store?]} body]
   (if (or database-id fresh-store?)
     (with-fresh-database database-id extra-schema body)
     (with-branched-database extra-schema body))))

(defn seed-cluster!
  "Seed one complete cluster/config path for tests that create agents."
  [connection cluster-name]
  (let [configured (config/apply! {:seon.db/connection connection
                                   :seon.boot/cluster-name cluster-name})]
    (when (:seon.error/kind configured)
      (throw (ex-info "The fixture cluster configuration was refused." configured))))
  (cluster/ensure-cluster-entity!
   connection cluster-name cluster/boot-process-identity)
  nil)

(defn preserving-instrumentation-state
  "Scope a test's deliberate instrumentation changes to that test.
   Restore the entering callable roots and Malli registry even when it throws."
  [body]
  (let [roots (into {} (map (juxt identity deref)) (instrument/instrumented))
        registry @(ns-resolve 'malli.core '-function-schemas*)
        schemas @registry]
    (try
      (body)
      (finally
        (try
          (doseq [instrumented-var (instrument/instrumented)]
            (alter-var-root instrumented-var mi/-f->original))
          (finally
            (reset! registry schemas)
            (doseq [[instrumented-var callable] roots]
              (alter-var-root instrumented-var (constantly callable)))))))))

(defn closeable
  "Adapt an acquired fixture value to Clojure's with-open cleanup scope.
   Dereferencing returns the value; closing calls its supplied release function."
  [value release!]
  (reify java.io.Closeable
    (close [_] (release! value))
    clojure.lang.IDeref
    (deref [_] value)))
