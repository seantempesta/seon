(ns seon.test-support
  "Shared test constructions which invoke production owners."
  (:require [seon.error.refusal]
            [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as async.impl]
            [clojure.java.io :as io]
            [clojure.test :as test]
            [datahike.api :as d]
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
            [seon.operator.runtime :as operator.runtime]
            [seon.program :as program]
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

(defn- clone-directory!
  "Copy one immutable test base into a private mutable root."
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
      (await-event! (.onExit process) ::published-base-cloned)
      (let [exit (.exitValue process)
            output (await-event! output ::published-base-clone-output)]
        (when-not (zero? exit)
          (throw
           (ex-info "The shared published test base could not be cloned."
                    {::source (.getPath source)
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
  {:seon.fn/destroys
   "the store directory of the root it is handed, replaced wholesale by a clone of the runner's published base"}
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
      (cluster.export/reidentify-branches!
       store #{source/current-branch})
      root)
    (do
      (cluster/refresh-source! (str root))
      (str root)))))

(defn populate-published-operator-root!
  "Populate an operator root from the runner's immutable current-src base."
  {:seon.fn/destroys
   "the store directory under the operator root it is handed, replaced wholesale by a clone of the runner's published current-src base"}
  ([root] (populate-published-operator-root! root {}))
  ([root options]
  ((requiring-resolve 'seon.test.runner/fixture-observation!)
   'seon.test-support/populate-published-operator-root! options)
  (if-let [base (System/getProperty "seon.test.published-base")]
    (let [source-store (io/file base "data" "store")
          store (io/file root "data" "store")]
      (replace-directory! root source-store store)
      ;; An operator opens :db for store custody, then forks the published
      ;; :current-src head. Exact retained commits are never branch sources in
      ;; this fixture copy.
      (cluster.export/reidentify-branches!
       (str store) #{:db source/current-branch})
      (str root))
    (do
      (cluster/refresh-source! (str (io/file root "data" "clusters")))
      (str root)))))

(defn with-published-file-database
  "Run `body` on a private file-store branch of the published test base.

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

   One konserve head record per store (measured 1.05 ms in default PID 88182),
   so this is an ordinary FACT read, not a cached mirror: nothing has to tell
   the fixture that the program moved."
  []
  (not-empty
   (into (sorted-set)
         (keep (fn [held]
                 (when-let [store (:seon.store/store held)]
                   (try
                     (:seon.source/commit-id (source/current store))
                     (catch Throwable _ nil)))))
         (vals @operator.runtime/root-store-holder))))

(defn- publication-key
  "The publication a canonical fixture built RIGHT NOW would carry.

   An isolated worker's published snapshot is immutable for the JVM's life, so
   its path is the whole key. A development JVM populates from the working
   tree, and the fact that moves under it is the store's `:current-src` head:
   `bin/seon init --dev` advances that head only once adoption converges, so a
   converged adoption is a cache MISS by construction and no run ever branches
   from a base older than the publication it runs under.

   No reachable store means nothing observable moved: the key then carries no
   commit member and the existing base stands, exactly as before. A check that
   answers `fine` when its subject is absent is the project's named failure
   class, so the absent case is the key's own member, never a silent `nil`."
  ([] (publication-key (System/getProperty "seon.test.published-base")
                       (published-commit-ids)))
  ([published-base commits]
   (cond
     published-base {:seon.test-support/published-base published-base}
     commits {:seon.source/commit-id commits}
     :else {:seon.test-support/published-base :seon.test-support/no-store})))

(defn- build-source-manifest
  []
  (if-let [base (System/getProperty "seon.test.published-base")]
    (cache/manifest base)
    (seon.fn/build-manifest {:seon.fn/roots seon.fn/source-roots})))

(defonce ^:private manifest-cache (atom {}))

(def source-manifest
  "The source manifest of the publication this JVM currently runs under.

   The isolated runner publishes one immutable manifest per snapshot. A
   development JVM derives it from its own checkout, and re-derives it when
   `publication-key` moves: the base and this manifest therefore always
   describe the same publication, and an adopted accreted arity is present in
   both without any hand rebuild."
  (reify clojure.lang.IDeref
    (deref [_]
      (let [publication (publication-key)]
        (or (get @manifest-cache publication)
            (let [manifest (build-source-manifest)]
              (reset! manifest-cache {publication manifest})
              manifest))))))

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

(defn- close-base!
  "Release one canonical base exactly once.

   A base is closed either by JVM shutdown or by retirement, and a retired base
   must not be closed a second time from its own shutdown hook: the second
   `d/delete-database` throws inside a hook, where nothing reports it."
  [{::keys [configuration connection private-root closed cleanup-hook]}]
  (when (or (nil? closed) (compare-and-set! closed false true))
    (when cleanup-hook
      (try
        (.removeShutdownHook (Runtime/getRuntime) ^Thread cleanup-hook)
        ;; Shutdown is already running: the hook is about to close this base
        ;; itself, and the guard above makes that a no-op.
        (catch IllegalStateException _ nil)))
    (try
      (try
        (d/release connection)
        (finally (d/delete-database configuration)))
      (finally
        (when private-root (delete-recursively! private-root)))))
  nil)

(defn- create-base
  "Open a private copy of the published store; never analyze source here."
  [base]
  (let [base (or base (System/getProperty "seon.test.published-base")
                 (:seon.test.cache/base
                  (cache/newest-base (System/getProperty "seon.test.source-root" ".")
                                     (System/getProperty "seon.test.git-sha" "HEAD"))))
        parent (doto (io/file "tmp" "fixture-bases") .mkdirs)
        private-root (str (java.nio.file.Files/createTempDirectory
                           (.toPath parent) "base-"
                           (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (let [private-store (clone-directory! (io/file base "data" "store")
                                             (io/file private-root "store"))
            ;; Ordinary fixtures open and branch only from the published head.
            ;; Its retained commit ancestry is export work proportional to the
            ;; publication's whole history, not fixture acquisition work.
            _ (cluster.export/reidentify-branches!
               private-store #{source/current-branch})
            source-configuration (store/datahike-configuration private-store)
            ;; The worker owns this file-store copy. Datahike's tiered
            ;; ready-store copies every backend key into memory on connect;
            ;; ordinary file-store branches already share immutable roots.
            configuration (-> source-configuration
                              (dissoc :fuse-index-roots? :index-config)
                              (assoc :branch source/current-branch))
            connection (d/connect configuration)]
        (try
          (let [database @connection
                projection (or (db/carried-projection database)
                               (schema/projection-from-database
                                database (or (schema/handed-projection) {})))
                state (sci.eval/projection-state database projection)]
            (db/carry-connection-projection-state! connection state)
            {::configuration configuration
             ::connection connection
             ::closed (atom false)
             ::private-root private-root
             ::sci-context
             (delay (sci.eval/cluster-ctx (db/db connection) connection state))})
          (catch Throwable failure
            (close-base! {::configuration configuration ::connection connection})
            (throw failure))))
      (catch Throwable failure
        (delete-recursively! private-root)
        (throw failure)))))

(defprotocol Held
  "Take and return a hold on a shared canonical base.

   Datahike refuses to delete a branch under an active connection, so a base a
   run still branches from cannot be closed under it. A hold is the one fact
   that says so."
  (acquire-base! [this]
    "Return `{:seon.test-support/value base :seon.test-support/hold token}`.")
  (release-base! [this held]
    "Return a hold taken by `acquire-base!`, closing a retired base at zero."))

(defn- adjust-holders
  [state completion delta]
  (swap!
   state
   (fn [current]
     (-> current
         (cond-> (identical? completion (::completion current))
           (update ::holders (fnil + 0) delta))
         (update ::retired
                 (fn [retired]
                   (mapv (fn [entry]
                           (cond-> entry
                             (identical? completion (::completion entry))
                             (update ::holders (fnil + 0) delta)))
                         retired)))))))

(defn- retrying-base
  "Share one daemon construction PER PUBLICATION KEY; retain successful values,
   never failures.

   `key-fn` names the publication a base built now would carry. When it differs
   from the key the current base was built under, that base is RETIRED and a
   new construction starts, so a converged development adoption is a cache miss
   by construction and no lane ever rebuilds a base by hand. A retired base is
   closed only after its last holder releases it, on a daemon thread, so a run
   holding the old base finishes on it while a later run gets the new one and
   no branch is deleted under an active connection.

   `state` is DATA. Reloading this namespace must neither discard a realized
   base nor keep an older namespace's construction code, so the process-wide
   base keeps its state in a `defonce` atom and its behaviour in these Vars.

   The one- and two-argument arities own a private state atom; the
   one-argument arity keys nothing and behaves exactly as before."
  {:malli/schema
   [:function
    [:=> [:cat :seon.instrument/callable] :seon.schema/value]
    [:=> [:cat :seon.instrument/callable :seon.instrument/callable] :seon.schema/value]
    [:=> [:cat :seon.call-preparation/state :seon.instrument/callable :seon.instrument/callable] :seon.schema/value]]}
  ([construct] (retrying-base (atom {::retired []}) (constantly nil) construct))
  ([key-fn construct] (retrying-base (atom {::retired []}) key-fn construct))
  ([state key-fn construct]
   (let [;; At most ONE construction runs at a time. A publication that
         ;; advances while a construction is in flight supersedes it, and
         ;; without this bound two lanes adopting minutes apart would put two
         ;; full canonical populations on the machine at once. A superseded
         ;; construction still completes, so every caller already waiting on it
         ;; gets a base, and retirement closes it once it is realized.
         construction-lock (Object.)
         start!
         (fn [completion publication]
           (let [projection (schema/handed-projection)
                 loader (ClassLoader/getSystemClassLoader)
                 work
                 (fn []
                   (let [result
                         (try
                           (with-bindings {clojure.lang.Compiler/LOADER loader}
                             (locking construction-lock
                               (if projection
                                 (schema/call-with-projection projection construct)
                                 (construct))))
                           (catch Throwable failure
                             (.printStackTrace failure)
                             (seon.error.refusal/diagnostic
                              {:seon.test.run/unavailable true
                               :seon.test.run/provenance-failure (str "Canonical fixture base construction failed: " (ex-message failure))
                               :seon.error/at (java.util.Date.)
                               :seon.error/layer :seon.test/fixture
                               :seon.error/operation 'seon.test-support/create-base
                               :seon.error/message (str "Canonical fixture base construction failed: "
                                    (ex-message failure))
                               :seon.error/throwable failure
                               :seon.error/expected :constructed-base
                               :seon.error/data (or (ex-data failure) {})})))]
                     (if (:seon.test.run/unavailable result)
                       (do (swap! state
                                  (fn [current]
                                    (cond-> current
                                      (identical? completion (::completion current))
                                      (dissoc ::completion ::key))))
                           (deliver completion result))
                       ;; The base carries the publication it was built from,
                       ;; so a run can name its own coherence boundary.
                       (deliver completion
                                (assoc result :seon.test-support/publication-key
                                       publication)))))]
             (doto (Thread. ^Runnable work "seon-test-database-base")
               (.setDaemon true)
               (.setContextClassLoader loader)
               (.start))))
         sweep!
         (fn []
           (let [closable? (fn [entry]
                             (and (zero? (::holders entry 0))
                                  (realized? (::completion entry))))
                 [before _] (swap-vals! state update ::retired
                                        #(vec (remove closable? %)))
                 closing (into []
                               (comp (filter closable?)
                                     (map (comp deref ::completion))
                                     (remove :seon.test.run/unavailable))
                               (::retired before))]
             (when (seq closing)
               (doto (Thread. ^Runnable #(run! close-base! closing)
                              "seon-test-database-base-retire")
                 (.setDaemon true)
                 (.start)))))
         entry!
         (fn []
           (let [publication (key-fn)
                 completion
                 (locking state
                   (let [{current-key ::key completion ::completion} @state]
                     (if (and completion (= publication current-key))
                       completion
                       (let [fresh (promise)]
                         (swap! state
                                (fn [current]
                                  (cond-> (assoc current ::key publication
                                                 ::completion fresh
                                                 ::holders 0)
                                    completion
                                    (update ::retired conj
                                            {::completion completion
                                             ::holders (::holders current 0)}))))
                         (start! fresh publication)
                         fresh))))]
             (sweep!)
             completion))]
     (reify
       clojure.lang.IPending
       (isRealized [_]
         ;; A base built under a SUPERSEDED publication is not a realized base
         ;; for this run: an observer that guards its deref with `realized?`
         ;; (`seon.test.runner`'s drift snapshot does) must not be the caller
         ;; that pays for the next construction.
         (boolean (let [{publication ::key completion ::completion} @state]
                    (and completion
                         (= publication (key-fn))
                         (realized? completion)))))
       clojure.lang.IDeref
       (deref [_] @(entry!))
       Held
       (acquire-base! [_]
         (let [completion (entry!)]
           ;; Register the hold BEFORE waiting: a key change between the
           ;; comparison and the wait retires this completion, and the
           ;; registration then lands on the retired entry, which is exactly
           ;; what keeps it open under this run.
           (adjust-holders state completion 1)
           {:seon.test-support/hold completion
            :seon.test-support/value @completion}))
       (release-base! [_ held]
         (when-let [completion (:seon.test-support/hold held)]
           (adjust-holders state completion -1)
           (sweep!))
         nil)))))

(defonce ^:private base-state
  ;; The process-wide base's STATE, kept across reloads of this namespace.
  (atom {::retired []}))

(def ^:private database-base
  ;; One successful base per PUBLICATION per JVM. A caller can stop waiting
  ;; without interrupting construction; failed attempts report a typed value
  ;; and the next call retries. A development adoption that advances
  ;; `:current-src` is a miss, so an accreted arity is provable in process.
  (retrying-base
   base-state
   publication-key
   (fn []
     (let [base (create-base (System/getProperty "seon.test.published-base"))
           hook (Thread. ^Runnable #(close-base! base)
                         "seon-test-database-base-cleanup")]
       (.addShutdownHook (Runtime/getRuntime) hook)
       (assoc base ::cleanup-hook hook)))))

(def ^:private ^:dynamic *held-base*
  "The canonical base value the ENCLOSING fixture holds.

   `fork-cluster-ctx` must fork the ctx of the base this fixture's connection
   actually branched from. Reaching for the current base instead would, at the
   exact moment a development adoption advances the publication, fork a NEW
   base's ctx over an OLD base's branch."
  nil)

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
   (let [base-ctx @(::sci-context
                    (checked-fixture-result (or *held-base* @database-base)))
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
  [connection projection-state extra-schema body]
  (db/carry-connection-projection-state! connection projection-state)
  (schema/call-with-projection-state
   projection-state
   (fn []
     (when (seq extra-schema)
       (checked-fixture-result (db/transact! connection {:tx-data extra-schema})))
     (body connection))))

(defn- with-fresh-database [database-id extra-schema options body]
  ((requiring-resolve 'seon.test.runner/fixture-observation!)
   'seon.test-support/with-fresh-database options)
  ;; Store-global tests copy immutable published data into a distinct store.
  ;; They never reconstruct that data by indexing the program again.
  (let [base (create-base nil)]
    (try
      ;; fork-database reads :db, regardless of source-config :branch
      ;; (Datahike versioning.cljc:620). This private store has no :db writer.
      (d/force-branch! @(::connection base) :db #{:current-src})
      (let [configuration
            (d/fork-database (::configuration base)
                             {:store {:backend :memory
                                      :id (or database-id (random-uuid))}})
            connection (d/connect configuration)]
        (try
          (let [projection (db/carried-projection (db/db (::connection base)))
                state (sci.eval/projection-state @connection projection)]
            (binding [*held-base* base]
              (run-database-body connection state extra-schema body)))
          (finally
            (d/release connection)
            (d/delete-database configuration))))
      (finally (close-base! base)))))

(defn- with-branched-database
  [extra-schema body]
  ;; HOLD the base across the whole fixture. A development adoption that lands
  ;; mid-run retires this base rather than closing it, so the branch below is
  ;; never deleted under its own active connection, and the NEXT fixture gets
  ;; the base built from the new publication.
  (let [held (acquire-base! database-base)
        base (:seon.test-support/value held)
        {configuration :seon.test-support/configuration
         base-connection :seon.test-support/connection} (try
                                        (checked-fixture-result base)
                                        (catch Throwable failure
                                          (release-base! database-base held)
                                          (throw failure)))
        base-projection (db/carried-projection (db/db base-connection))
        branch (acquire-branch!)]
    (try
      ;; Fork the sealed base head in the worker-owned file store.
      (d/branch! base-connection (get configuration :branch :db) branch)
      (let [branch-configuration (assoc configuration :branch branch)
            connection (schema/call-with-projection
                        base-projection #(d/connect branch-configuration))]
        (try
          (let [state (sci.eval/projection-state @connection base-projection)]
            (binding [*held-base* base]
              (run-database-body connection state extra-schema body)))
          (finally (d/release connection))))
      (finally
        ;; Datahike refuses deletion while a child connection remains active.
        ;; Return the name only after successful retirement; a teardown failure
        ;; quarantines the lease rather than reusing live mutable state.
        (when (contains? (d/branches base-connection) branch)
          (d/delete-branch! base-connection branch))
        (release-branch! branch)
        (release-base! database-base held)))))

(defn with-database
  "Run `body` on an isolated branch of the published test database.

   The worker opens the published population without indexing source.
   Every invocation gets its own branch, connection, datoms, history and writer.
   `:seon.test-support/extra-schema` installs synthetic declarations.

   `:seon.test-support/database-id` preserves physical-store identity through
   the isolated store path. Store-global blob tests request
   `:seon.test-support/fresh-store?` because blob keys are outside branch facts."
  ([body] (with-database {} body))
  ([{:seon.test-support/keys [database-id extra-schema fresh-store?], :as options} body]
    (if (or database-id fresh-store?)
      (with-fresh-database database-id extra-schema options body)
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

(defn program-row
  "Analyze an explicit synthetic declaration through the production source path."
  [database identity source]
  (let [projection (db/carried-projection database)
        [attribute declaration-symbol] identity
        rows (seon.fn/source-rows
              database (program/shapes-in projection)
              {:seon.ns/name (symbol (namespace declaration-symbol))}
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
