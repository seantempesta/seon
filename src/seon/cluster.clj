(ns seon.cluster
  "The entry: one JVM process hosting cluster instances, REPL-first.

  CONTRACT LAYER (orchestrator-authored, 2026-07-27 — the B0 rung). The
  schemas and function contracts are SEALED: the implementation lane
  fills the stub bodies until test/seon/cluster/boot_test.clj is green
  and may not loosen a schema or a test. Friction is reported, never
  resolved by weakening.

  The boot tower (plan README, rulings 2026-07-27): each layer reads
  only the one below it and publishes its own readiness —

  0. PROCESS. `start!` consumes one complete bootstrap configuration —
     the closed, deliberately tiny key set the process needs before any
     store exists. Everything else lives in the database (B2). The REPL
     (io-prepl) opens FIRST, before anything else, and the instance
     advertises its coordinate; the ten-second ruling is this layer's
     bound.
  1. STORE (B1, next rung — not in this namespace).
  2. FACTS and 3. FLOW (B2/N3): the flow graph definition is data
     derived from database facts at a basis; graph transforms are
     referenced as VARS so re-evaluating a defn updates a running proc
     with no restart, and topology changes rebuild the graph (measured
     ~0.3 ms) — nothing in a flow channel is durable
     (research/flow-dynamic-update-2026-07-27.md).

  Multi-instance from day 0: the process identity is
  (cluster-name, pid, start-instant); every path derives from
  (root, cluster-name) by convention; each instance advertises its own
  REPL coordinate under its cluster directory; a JVM may host several
  instances and NOTHING here is an ambient one-cluster singleton — no
  process-global connection, cache, or session keyed by \"the\"
  cluster.

  The root of the process owns exactly two shared executors — one
  bounded `:compute` (default parallelism = available processors, a
  computed hardware fact, never a literal) and one `:io` — created once
  per JVM and shared by every cluster's flow graph
  (research/flow-per-cluster-2026-07-27.md).

  Crash walk: `start!` performs no database writes and owns no durable
  state; a kill at any instant leaves at most an orphan advertisement
  file, and the advertisement carries (pid, start-instant) so a reader
  detects staleness against the live process table rather than trusting
  the file. `stop!` is idempotent; a killed process's next boot simply
  re-advertises."
  (:require [clojure.core.server]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test.check.generators :as gen]
            [seon.cluster.run]
            [seon.cluster.store]
            [seon.flow :as flow]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Bootstrap configuration — the CLOSED pre-store key set.
;;; A key that the database could own does not belong here; the closed
;;; map makes that a review-time refusal, not a convention.
;;; ---------------------------------------------------------------------------

;;; The running instance value returned by start!. Named predicates for
;;; the genuinely opaque platform objects; everything else is ordinary
;;; data.

(defn socket-server?
  "True for the java.net.ServerSocket an io-prepl listens on."
  [value]
  (instance? java.net.ServerSocket value))

(schema/register-core-predicate! 'seon.cluster/socket-server?
                                 socket-server?)

(defonce ^:private generator-server
  (delay (java.net.ServerSocket. 0)))

(def socket-server-generator
  (gen/fmap (fn [_] @generator-server) (gen/return nil)))

(schema.edn/load! {})
(schema/activate! (schema/snapshot))

;;; ---------------------------------------------------------------------------
;;; Pure resolution — defaults are THE defaults document for this layer
;;; ---------------------------------------------------------------------------

(defn- refused!
  [message offense]
  (throw (ex-info message
                  {:seon.error/kind :seon.boot/refused
                   :seon.boot/offense offense})))

(defn- require-candidate-value
  [schema-key value message]
  (if (schema/valid-candidate-value? schema-key value)
    value
    (refused! message
              {:seon.boot/schema schema-key
               :seon.boot/value value
               :seon.boot/explanation
               (schema/explain-candidate-value schema-key value)})))

(declare cluster-paths)

(defn resolve-bootstrap
  "Resolve overrides into one complete bootstrap configuration.
  Every key optional; absent = default. Defaults: cluster-name
  \"default\" (just a name, nothing special), root \"data/clusters\",
  prepl-host \"127.0.0.1\", prepl-port 0 (ephemeral — the advertisement
  carries the real port), log-dir derived as <root>/<name>/logs,
  store-dir derived as <root>/store — the PROCESS-root store every
  cluster branches from (ancestor-branch stays absent unless supplied).
  Refuses (throws ex-info {:seon.error/kind :seon.boot/refused ...}) on
  any unknown key or invalid value — the closed schema is the gate, not
  a convention."
  {:malli/schema [:=> [:cat :seon.boot/overrides] :seon.boot/config]}
  [overrides]
  (require-candidate-value
   :seon.boot/overrides
   overrides
   "The bootstrap overrides were refused.")
  (let [defaults {:seon.boot/cluster-name "default"
                  :seon.boot/root "data/clusters"
                  :seon.boot/prepl-host "127.0.0.1"
                  :seon.boot/prepl-port 0}
        base (merge defaults overrides)
        derived-store-dir
        (str (io/file (:seon.boot/root base) "store"))
        derived-log-dir
        (:seon.boot/log-dir
         (cluster-paths (:seon.boot/root base)
                        (:seon.boot/cluster-name base)))]
    (require-candidate-value
     :seon.boot/config
     (merge {:seon.boot/log-dir derived-log-dir
             :seon.boot/store-dir derived-store-dir}
            base)
     "The resolved bootstrap configuration was refused.")))

(defn cluster-paths
  "Derive every per-cluster path from (root, cluster-name).
  Convention owns the layout: the cluster directory, its log directory,
  and its advertisement file. The STORE is per process root under
  branch-per-cluster (b2-plan section 0); its path is bootstrap config,
  never a per-cluster derivation. One derivation — no other code builds
  these paths."
  {:malli/schema [:=> [:cat :seon.boot/root :seon.boot/cluster-name]
                  [:map {:closed true}
                   [:seon.boot/cluster-dir :string]
                   [:seon.boot/advertisement-file :string]
                   [:seon.boot/log-dir :string]]]}
  [root cluster-name]
  (let [cluster-dir (io/file root cluster-name)]
    {:seon.boot/cluster-dir (str cluster-dir)
     :seon.boot/advertisement-file
     (str (io/file cluster-dir "prepl.edn"))
     :seon.boot/log-dir (str (io/file cluster-dir "logs"))}))

;;; ---------------------------------------------------------------------------
;;; The shared root executors — created once per JVM, never per cluster
;;; ---------------------------------------------------------------------------

(defonce ^:private root-executor-pair
  (delay
    {:compute
     (flow/bounded-platform-executor
      (.availableProcessors (Runtime/getRuntime)))
     :io (java.util.concurrent.Executors/newCachedThreadPool)}))

(defn root-executors
  "The process root's two shared executors.
  One bounded `:compute` platform-thread executor (parallelism =
  available processors — a computed hardware fact) and one `:io`
  executor for blocking transport. Idempotent per JVM: repeated calls return the SAME
  executor objects (the root owns them; cluster graphs share them).
  This is deliberately process-global state — the one sanctioned kind:
  a genuinely process-local artifact, like a compiler state or a
  connection."
  {:malli/schema [:=> [:cat]
                  [:map {:closed true}
                   [:compute [:fn 'seon.flow/executor?]]
                   [:io [:fn 'seon.flow/executor?]]]]}
  []
  @root-executor-pair)

;;; ---------------------------------------------------------------------------
;;; The instance lifecycle
;;; ---------------------------------------------------------------------------

(defonce ^:private running-instances (atom {}))

(def ^:private starting ::starting)

(defn- server-name
  [cluster-name]
  (str "seon.cluster/" cluster-name))

(defn- reserve-cluster!
  [cluster-name]
  (loop []
    (let [instances @running-instances]
      (if (contains? instances cluster-name)
        (refused! "The cluster already has an instance in this process."
                  {:seon.boot/cluster-name cluster-name})
        (when-not (compare-and-set! running-instances
                                    instances
                                    (assoc instances cluster-name starting))
          (recur))))))

(defn- release-reservation!
  [cluster-name]
  (swap! running-instances
         (fn [instances]
           (if (= starting (get instances cluster-name))
             (dissoc instances cluster-name)
             instances))))

(defn- current-process-identity
  []
  (let [handle (java.lang.ProcessHandle/current)
        optional (.startInstant (.info handle))]
    (when-not (.isPresent optional)
      (refused! "The process start instant is unavailable."
                {:seon.boot/pid (.pid handle)}))
    {:seon.boot/pid (.pid handle)
     :seon.boot/start-instant (java.util.Date/from (.get optional))}))

(defn- create-directories!
  [config paths]
  (doseq [path [(:seon.boot/cluster-dir paths)
                (:seon.boot/log-dir config)]]
    (.mkdirs (io/file path))))

(defn- write-advertisement!
  [paths advertisement]
  (spit (:seon.boot/advertisement-file paths)
        (str (pr-str advertisement) "\n")))

(defn start!
  "Start one cluster instance in this JVM, REPL FIRST.
  Order: resolve paths and create directories → open the io-prepl
  socket server (clojure.core.server, `:accept
  clojure.core.server/io-prepl`) → write the advertisement (real bound
  port, this process's pid and start-instant from
  java.lang.ProcessHandle) → return the instance value. No store, no
  database, no flow graph — those are later rungs stacked ON this
  value. Two instances in one JVM are fully independent except the
  shared root executors. Refuses a second start! for a cluster this
  JVM already has running (one instance per cluster per process)."
  {:malli/schema [:=> [:cat :seon.boot/overrides] :seon.boot/instance]}
  [overrides]
  (let [config (resolve-bootstrap overrides)
        cluster-name (:seon.boot/cluster-name config)
        paths (cluster-paths (:seon.boot/root config) cluster-name)
        name (server-name cluster-name)]
    (create-directories! config paths)
    (reserve-cluster! cluster-name)
    (let [server (volatile! nil)]
      (try
        (let [prepl-server
              (clojure.core.server/start-server
               {:accept 'clojure.core.server/io-prepl
                :port (:seon.boot/prepl-port config)
                :name name
                :address (:seon.boot/prepl-host config)})
              _ (vreset! server prepl-server)
              advertisement
              (merge
               {:seon.boot/cluster-name cluster-name
                :seon.boot/prepl-host (:seon.boot/prepl-host config)
                :seon.boot/prepl-port (.getLocalPort prepl-server)}
               (current-process-identity))
              instance
              {:seon.boot/config config
               :seon.boot/advertisement advertisement
               :seon.boot/prepl-server prepl-server
               :seon.boot/executors (root-executors)}
              instance
              (require-candidate-value
               :seon.boot/instance
               instance
               "The started cluster instance was refused.")]
          (write-advertisement! paths advertisement)
          (swap! running-instances assoc cluster-name instance)
          instance)
        (catch Throwable throwable
          (when @server
            (clojure.core.server/stop-server name))
          (release-reservation! cluster-name)
          (throw throwable))))))

(defn- active-instance?
  [registered instance]
  (and (map? registered)
       (identical? (:seon.boot/prepl-server registered)
                   (:seon.boot/prepl-server instance))))

(defn- claim-stop!
  [cluster-name instance marker]
  (loop []
    (let [instances @running-instances]
      (if-not (active-instance? (get instances cluster-name) instance)
        false
        (if (compare-and-set! running-instances
                              instances
                              (assoc instances cluster-name marker))
          true
          (recur))))))

(defn stop!
  "Stop exactly THIS instance, instance-addressed never name-addressed.
  Closes ITS prepl server socket and deletes ITS advertisement; a
  delayed stop! of an old instance value must not touch a replacement
  started under the same cluster name (the replacement's socket,
  advertisement, and registry entry all survive). Idempotent — stopping
  a stopped instance is a no-op returning nil. Never touches the shared
  root executors."
  {:malli/schema [:=> [:cat :seon.boot/instance] :nil]}
  [instance]
  (let [config (:seon.boot/config instance)
        cluster-name (:seon.boot/cluster-name config)
        marker (Object.)]
    (when (claim-stop! cluster-name instance marker)
      (try
        (.close ^java.net.ServerSocket (:seon.boot/prepl-server instance))
        (let [advertisement-file
              (io/file
               (:seon.boot/advertisement-file
                (cluster-paths (:seon.boot/root config) cluster-name)))]
          (when (= (:seon.boot/advertisement instance)
                   (try
                     (edn/read-string (slurp advertisement-file))
                     (catch Throwable _ nil)))
            (.delete advertisement-file)))
        (finally
          (swap! running-instances
                 (fn [instances]
                   (if (identical? marker (get instances cluster-name))
                     (dissoc instances cluster-name)
                     instances)))))))
  nil)

(defn- matching-live-process?
  [advertisement]
  (try
    (let [optional
          (java.lang.ProcessHandle/of
           (long (:seon.boot/pid advertisement)))]
      (when (.isPresent optional)
        (let [handle (.get optional)
              start (.startInstant (.info handle))]
          (and (.isAlive handle)
               (.isPresent start)
               (= (.getTime
                   ^java.util.Date (:seon.boot/start-instant advertisement))
                  (.toEpochMilli ^java.time.Instant (.get start)))))))
    (catch Throwable _
      false)))

(defn read-advertisement
  "Read and validate one cluster's advertisement, or nil.
  Returns the advertisement map only when the file exists, parses,
  validates against :seon.boot/advertisement, AND its (pid,
  start-instant) matches a live process — a stale file from a killed
  instance reads as nil, never as a coordinate. (ProcessHandle/of pid →
  startInstant comparison; tolerate the platform's millisecond
  truncation.)"
  {:malli/schema [:=> [:cat :seon.boot/root :seon.boot/cluster-name]
                  [:maybe :seon.boot/advertisement]]}
  [root cluster-name]
  (try
    (let [path (:seon.boot/advertisement-file
                (cluster-paths root cluster-name))
          advertisement (edn/read-string (slurp path))]
      (when (and
             (schema/valid-candidate-value?
              :seon.boot/advertisement advertisement)
             (= cluster-name (:seon.boot/cluster-name advertisement))
             (matching-live-process? advertisement))
        advertisement))
    (catch Throwable _
      nil)))
