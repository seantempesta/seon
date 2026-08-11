(ns seon.test-support
  "Shared test constructions which invoke production owners."
  (:require [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as async.impl]
            [clojure.java.io :as io]
            [clojure.test :as test]
            [datahike.api :as d]
            [seon.db :as db]
            [seon.cluster :as cluster]
            [seon.cluster.export :as cluster.export]
            [seon.env :as env]
            [seon.fs :as fs]
            [seon.fn :as seon.fn]
            [seon.schema :as schema]
            [seon.sci.eval :as sci.eval])
  (:import [java.util.concurrent CountDownLatch Future TimeUnit
            TimeoutException]))

(set! *warn-on-reflection* true)

(def event-backstop-seconds
  "The loud clock around test events whose publishers are observable."
  20)

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

(defn populate-published-root!
  "Populate `root` from the runner's immutable base, or publish standalone."
  [root]
  (if-let [base (System/getProperty "seon.test.published-base")]
    (let [root (clone-directory! base root)]
      (cluster.export/reidentify! (io/file root "store"))
      root)
    (do
      (cluster/refresh-source! (str root))
      (str root))))

(defn populate-published-operator-root!
  "Populate an operator root from the runner's immutable current-src base."
  [root]
  (if-let [base (System/getProperty "seon.test.published-base")]
    (let [store (io/file root "data" "clusters" "store")]
      (clone-directory! (io/file base "store") store)
      (cluster.export/reidentify! store)
      (str root))
    (do
      (cluster/refresh-source! (str (io/file root "data" "clusters")))
      (str root))))

(def committed
  "Returned when a boundary expected to refuse instead commits."
  ::committed)

(def unknown-refusal
  "Returned when a thrown boundary carries no classifiable ex-data."
  ::unknown-refusal)

(def source-manifest
  ;; The runner loads every selected test namespace before it invokes a test,
  ;; so the first fixture derives this immutable value from that invocation's
  ;; frozen source tree. Every database still installs the complete population
  ;; through its own transactions; only repeated static analysis is shared.
  (delay
    (seon.fn/build-manifest {:seon.fn/roots seon.fn/source-roots})))

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
        _ (d/create-database configuration)
        connection (d/connect configuration)]
    (try
      (populate-database! connection)
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

(defn prepare-base!
  "Acquire this worker's immutable database and SCI base once."
  [] @database-base nil)

(defn fork-cluster-ctx
  "Fork the process source base's acquired SCI ctx for `connection`."
  [connection]
  (let [base-ctx (:seon.sci.eval/ctx @database-base)
        projection (:seon.schema/projection base-ctx)
        projection-state (sci.eval/projection-state @connection projection)]
    (sci.eval/fork-cluster-ctx base-ctx @connection connection
                               projection-state)))

(defn start-cluster!
  "Start a current-source cluster from this worker's acquired SCI base."
  [request]
  (cluster/start!
   (assoc request :seon.sci.eval/ctx (:seon.sci.eval/ctx @database-base))))

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
  "Read every value of a file-store test's synthetic marker attribute."
  [connection marker-attribute]
  (set
   (db/q
    '[:find [?marker ...]
      :in $ ?marker-attribute
      :where
      [_ ?marker-attribute ?marker]]
    @connection
    marker-attribute)))

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
      ;; The private :db head is populated and sealed exactly once, then never
      ;; exposed or transacted. Branch creation overwrites this lease's stale
      ;; deleted head with that same immutable base value.
      (d/branch! base-connection :db branch)
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
  (db/transact! connection [{:seon.config/cluster cluster-name}])
  (cluster/ensure-cluster-entity!
   connection cluster-name cluster/boot-process-identity)
  nil)
