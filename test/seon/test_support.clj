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
            [seon.error :as error]
            [seon.fs :as fs]
            [seon.fn :as seon.fn]
            [seon.instrument :as instrument]
            [seon.schema :as schema]
            [seon.sci.eval :as sci.eval]
            [seon.test.cache :as cache])
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

(declare await-event!)

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
        output (future (slurp (.getInputStream process)))]
    (try
      (await-event! (.onExit process) ::published-base-cloned)
      (let [exit (.exitValue process)
            output (await-event! output ::published-base-clone-output)]
        (when-not (zero? exit)
          (throw
           (ex-info "The shared published test base could not be cloned."
                    {:seon.error/kind ::published-base-clone-failed
                     ::source (.getPath source)
                     ::target (.getPath target)
                     ::exit exit
                     ::output output})))
        (.getPath target))
      (finally
        (when (.isAlive process)
          (.destroyForcibly process)
          (await-event! (.onExit process) ::published-base-clone-stopped))
        (await-event! output ::published-base-clone-output)))))

(declare delete-recursively!)

(defn- replace-directory!
  [authority source target]
  (when (.exists (io/file target))
    (fs/delete-recursively! (str authority) (str target)))
  (clone-directory! source target))

(defn populate-published-root!
  "Populate `root` from the runner's immutable base, or publish standalone."
  ([root] (populate-published-root! root {}))
  ([root options]
  ((requiring-resolve 'seon.test.runner/fixture-observation!)
   'seon.test-support/populate-published-root! options)
  (if-let [base (System/getProperty "seon.test.published-base")]
    (let [root (clone-directory! base root)
          source-store (io/file base "data" "store")
          store (:seon.boot/store-dir
                 (cluster/resolve-bootstrap {:seon.boot/root root}))
          ;; the deletion authority is the run root this fixture genuinely
          ;; holds — never the JVM-wide operator root, which in an
          ;; undeclared worker resolves to the developer's checkout
          authority root]
      (replace-directory! authority source-store store)
      (cluster.export/reidentify!
       store)
      root)
    (do
      (cluster/refresh-source! (str root))
      (str root)))))

(defn populate-published-operator-root!
  "Populate an operator root from the runner's immutable current-src base."
  ([root] (populate-published-operator-root! root {}))
  ([root options]
  ((requiring-resolve 'seon.test.runner/fixture-observation!)
   'seon.test-support/populate-published-operator-root! options)
  (if-let [base (System/getProperty "seon.test.published-base")]
    (let [source-store (io/file base "data" "store")
          store (io/file root "data" "store")]
      (replace-directory! root source-store store)
      (cluster.export/reidentify! (str store))
      (str root))
    (do
      (cluster/refresh-source! (str (io/file root "data" "clusters")))
      (str root)))))

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
      ;; `pr-str` emits this keyword into every diagnostic that transports a
      ;; live connection roster, and `clojure.edn/read-string` refuses a
      ;; keyword whose name begins with a digit. The name must round-trip.
      (keyword "seon.test-support.fixture"
               (str "fixture-" (:seon.test-support/next before))))))

(defn- release-branch!
  [branch]
  (swap! branch-leases update :seon.test-support/available conj branch)
  nil)

(defn- checked-fixture-result
  "Stop setup at its first flat refusal, retaining the complete diagnostic."
  [result]
  (when (:seon.error/kind result)
    (throw (ex-info "Fixture setup was refused." result)))
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
                   (nil? (:seon.error/kind report))
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

(defn- populate-database!
  [connection]
  (checked-fixture-result
   (cluster/populate-source!
    {:seon.db/connection connection
     :seon.fn/manifest @source-manifest}))
  nil)

(defn- seal-population!
  "Seal the completed population, through the connection's carried projection.

  `populate-source!` is the contents step used by production
  `source/publish!`; production seals that completed population in the
  following transaction. Keep this canonical fixture on the same side of that
  provenance boundary so indexed core contracts are not misclassified as
  agent-authored rows. This write happens AFTER the base carries its
  projection state, because the writer validates against the projection its
  connection carries (law 2.1) rather than one bound around the call."
  [connection]
  (checked-fixture-result
   (db/transact!
    connection
    {:tx-data [{:seon.source/digest (apply str (repeat 64 "0"))}]}))
  nil)

(defn- close-base!
  [{::keys [configuration connection private-root]}]
  (try
    (try
      (d/release connection)
      (finally (d/delete-database configuration)))
    (finally
      (when private-root (delete-recursively! private-root))))
  nil)

(defn- create-base
  [base]
  (let [private-root (when base
                       (let [parent (doto (io/file "tmp" "fixture-bases") .mkdirs)]
                         (str (java.nio.file.Files/createTempDirectory
                               (.toPath parent) "base-"
                               (make-array java.nio.file.attribute.FileAttribute 0)))))]
    (try
      (let [configuration
            (if base
              (let [private-store (clone-directory! (io/file base "data" "store")
                                                    (io/file private-root "store"))
                    _ (cluster.export/reidentify! private-store)
                    source (store/datahike-configuration private-store)
                    backend (:store source)
                    id (:id backend)]
                ;; Connect-time migration can mutate even a frontend-only
                ;; backend. Every JVM therefore owns the copy it connects.
                (-> source
                    (dissoc :fuse-index-roots? :index-config)
                    (assoc :branch source/current-branch
                           :store {:backend :tiered :id id
                                   :frontend-config {:backend :memory :id id}
                                   :backend-config backend
                                   :write-policy :frontend-only
                                   :read-policy :frontend-first})))
              ;; Without commit records, the leased branch-name pool also
              ;; bounds retained keys. Commit-graph tests own file stores.
              {:store {:backend :memory :id (random-uuid)}
               :commit-graph? false
               :keep-history? true
               :schema-flexibility :write})
            _ (when-not base (d/create-database configuration))
            connection (d/connect configuration)]
        (try
          (when-not base (populate-database! connection))
          ;; The worker's bootstrap projection has schema forms, but no
          ;; populated function contracts. Carry this base's actual program
          ;; ONCE, before the sealing write and cold SCI acquisition: both the
          ;; writer and the ctx then take the projection the connection carries.
          (let [database @connection
                projection (schema/projection-from-database database)
                state (sci.eval/projection-state database projection)]
            (db/carry-connection-projection-state! connection state)
            (when-not base (seal-population! connection))
            (cond-> {:seon.test-support/configuration configuration
                     :seon.test-support/connection connection
                     :seon.sci.eval/ctx
                     (sci.eval/cluster-ctx (db/db connection) connection state)}
              private-root (assoc ::private-root private-root)))
          (catch Throwable failure
            (close-base! {::configuration configuration ::connection connection})
            (throw failure))))
      (catch Throwable failure
        (when private-root (delete-recursively! private-root))
        (throw failure)))))

(defn- retrying-base
  "Share one daemon construction; retain successful values, never failures."
  [construct]
  (let [attempt (atom nil)]
    (reify
      clojure.lang.IPending
      (isRealized [_]
        (boolean (when-let [completion @attempt] (realized? completion))))
      clojure.lang.IDeref
      (deref [_]
        (let [completion
              (locking attempt
                (or @attempt
                    (let [completion (promise)
                          projection (schema/handed-projection)
                          loader (ClassLoader/getSystemClassLoader)
                          work
                          (fn []
                            (let [result
                                  (try
                                    (with-bindings {clojure.lang.Compiler/LOADER loader}
                                      (if projection
                                        (schema/call-with-projection projection construct)
                                        (construct)))
                                    (catch Throwable failure
                                      (error/diagnostic
                                       {:seon.error/kind ::database-base-unavailable
                                        :seon.error/message
                                        (str "Canonical fixture base construction failed: "
                                             (ex-message failure))
                                        :seon.error/diagnostic-layer :test-fixture
                                        :seon.error/diagnostic-operation ::create-base
                                        :seon.error/diagnostic-member ::database-base
                                        :seon.error/diagnostic-expected :constructed-base
                                        :seon.error/diagnostic-offending
                                        (symbol (.getName (class failure)))
                                        :seon.error/diagnostic-cause
                                        (or (ex-message failure) :seon.error/unknown)
                                        :seon.error/diagnostic-evidence
                                        (or (ex-data failure) :seon.error/unknown)})))]
                              (when (:seon.error/kind result)
                                (compare-and-set! attempt completion nil))
                              (deliver completion result)))]
                      (reset! attempt completion)
                      (doto (Thread. ^Runnable work "seon-test-database-base")
                        (.setDaemon true)
                        (.setContextClassLoader loader)
                        (.start))
                      completion)))]
          @completion)))))

(defonce ^:private database-base
  ;; One successful base per JVM. A caller can stop waiting without interrupting
  ;; construction; failed attempts report a typed value and the next call retries.
  (retrying-base
   (fn []
     (let [base (create-base (System/getProperty "seon.test.published-base"))]
       (.addShutdownHook
        (Runtime/getRuntime)
        (Thread. ^Runnable #(close-base! base) "seon-test-database-base-cleanup"))
       base))))

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
   (fork-cluster-ctx connection (seeded-cluster-name (db/db connection))))
  ([connection cluster-name]
   (let [base-ctx (:seon.sci.eval/ctx (checked-fixture-result @database-base))
         database (db/db connection)
         projection (db/carried-projection database)
         projection-state (:seon.sci.eval/projection-state (meta database))]
     (when cluster-name
       (env/replace-environment!
        projection-state
        (env/refuse-incomplete-environment!
         (env/environment {:seon.boot/cluster-name cluster-name
                           :seon.db/connection connection
                           :seon.db/basis-t (db/basis-t (db/db connection))
                           :seon.schema/projection projection}))))
     (sci.eval/fork-cluster-ctx base-ctx (db/db connection) connection
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
              (schema/projection-from-database (db/db connection))))))))

(defn await-event!
  "Await one channel, latch, future, or watched reference with a loud backstop."
  ([event-source event]
   (await-event! event-source event (constantly true)))
  ([event-source event accept?]
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
         (first (await-event! accepted event))
         (finally
           (remove-watch event-source watch-key)
           (async/close! accepted))))

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
       (catch ExecutionException failure
         (throw (.getCause failure)))
       (catch TimeoutException timeout
         (.cancel ^Future event-source true)
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
  [connection projection-state extra-schema body]
  (db/carry-connection-projection-state! connection projection-state)
  (schema/call-with-projection-state
   projection-state
   (fn []
     (when (seq extra-schema)
       (checked-fixture-result (db/transact! connection {:tx-data extra-schema})))
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

(defn- with-fresh-database [database-id extra-schema options body]
  ((requiring-resolve 'seon.test.runner/fixture-observation!)
    'seon.test-support/with-fresh-database
    options)
  (let [configuration {:store {:backend :memory, :id (or database-id (random-uuid))},
                       :keep-history? true,
                       :schema-flexibility :write}
        _ (d/create-database configuration)
        provisional-connection (d/connect configuration)]
    (try
      (populate-database! provisional-connection)
      (let [{connection :seon.test-support/connection,
             projection-state :seon.sci.eval/projection-state} (reconnect-with-projection
                                                                 configuration
                                                                 provisional-connection)]
        (try
          (run-database-body connection projection-state extra-schema body)
          (finally (d/release connection))))
      (finally (d/release provisional-connection) (d/delete-database configuration)))))

(defn- with-branched-database
  [extra-schema body]
  (let [{configuration :seon.test-support/configuration
         base-connection :seon.test-support/connection
         base-ctx :seon.sci.eval/ctx} (checked-fixture-result @database-base)
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
  "Run `body` on a fresh branch of one canonical in-memory base.\n\n   The production source population is installed once per new test JVM.\n   Every invocation gets a distinct active branch, connection, datoms, schema\n   evolution, transaction history, and writer. Optional\n   `:seon.test-support/extra-schema` rows are synthetic declarations whose\n   installation is itself part of a test.\n\n   `:seon.test-support/database-id` preserves the legacy physical-store\n   identity contract through an isolated slower path. Store-global blob tests\n   request `:seon.test-support/fresh-store?` because blob keys are outside\n   Datahike branch facts."
  ([body] (with-database {} body))
  ([{:seon.test-support/keys [database-id extra-schema fresh-store?], :as options} body]
    (if (or database-id fresh-store?)
      (with-fresh-database database-id extra-schema options body)
      (with-branched-database extra-schema body))))

(defn program-fn-row
  "One first-party program row for a fixture that needs only the identity.

   A bare `{:seon.fn/sym \"ns/name\"}` is refused: the fn schema requires
   `:seon.schema.admission/source` (where the definition was admitted from)
   and `:seon.fn/ns`, so a fixture writing the bare row has been seeding
   nothing. The namespace must already exist — in the canonical fixture every
   first-party namespace does."
  [function-symbol]
  {:seon.fn/sym (str function-symbol)
   :seon.fn/ns [:seon.ns/name (symbol (namespace (symbol (str function-symbol))))]
   :seon.schema.admission/source :core})

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
