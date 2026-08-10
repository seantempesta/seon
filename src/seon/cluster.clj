(ns seon.cluster
  "Process entry for starting, inspecting, and stopping named clusters.

  `start!` resolves the closed bootstrap configuration, opens and
  advertises an io-prepl, then builds the remaining instance from the
  process-root store and executors, the published source commit, the
  cluster's database branch and configuration, recovered run facts,
  the root agent, agent and render flows, and the web server. The
  io-prepl and the partially built instance remain available when a
  later layer fails.

  A JVM may host several named cluster instances. They share the
  process-root store and executor pair; branch connections, flows,
  routing state, advertisements, and web servers remain per cluster.
  `readiness` derives its report from the instance and its database.
  `stop!` idempotently unwinds only the addressed instance and releases
  the shared store when its last holder stops."
  (:require [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as async.protocols]
            [clojure.core.async.flow :as flow.core]
            [clojure.core.server]
            [clojure.set :as set]
            [seon.blob :as blob]
            [seon.cluster.agent :as cluster.agent]
            [seon.cluster.instruction :as instruction]
            [seon.cluster.process :as cluster.process]
            [seon.cluster.wake :as wake]
            [seon.error :as error]
            [seon.cluster.run :as run]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test.check.generators :as gen]
            [datahike.api :as d]
            [datahike.gc-guard :as gc-guard]
            [seon.bootstrap :as bootstrap]
            [seon.cluster.source :as source]
            [seon.cluster.registry :as registry]
            [seon.cluster.store :as store]
            [clojure.string :as str]
            [seon.config :as config]
            [seon.db :as db]
            [seon.env :as env]
            [seon.flow :as flow]
            [seon.fn :as seon.fn]
            [seon.operator.runtime :as operator.runtime
             :refer [root-store-holder running-instances]]
            [seon.operator.state :as operator.state]
            [seon.oversight :as oversight]
            [seon.problems :as problems]
            [seon.render :as render]
            [seon.render.data :as render.data]
            [seon.print :as print]
            [seon.render.value :as render.value]
            [seon.schedule :as schedule]
            [seon.sci.admit :as admit]
            [seon.render.web :as web]
            [seon.sci.eval :as sci.eval]
            [taoensso.timbre :as log]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.schema.edn :as schema.edn]
            [seon.schema.form :as schema.form]
            [seon.search :as search])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file CopyOption Files StandardCopyOption]
           [java.util.concurrent Executor]))

;;; ---------------------------------------------------------------------------
;;; Bootstrap configuration — the CLOSED pre-store key set.
;;; A key that the database could own does not belong here; the closed
;;; map makes that a review-time refusal, not a convention.
;;; ---------------------------------------------------------------------------

;;; The running instance value returned by start!. Named predicates for
;;; the genuinely opaque platform objects; everything else is ordinary
;;; data.

(def ^:dynamic ^:private *source-progress!*
  (constantly nil))

(defn- report-source-progress!
  [phase]
  (*source-progress!* phase)
  nil)

(defn- report-analysis-warnings!
  [manifest]
  (doseq [finding (:seon.fn.manifest/findings manifest)]
    (report-source-progress!
     (str "WARNING "
          (name (:seon.fn.analyzer/type finding))
          " " (:seon.fn.analyzer/filename finding)
          (when-let [row (:seon.fn.analyzer/row finding)] (str ":" row))
          " — " (:seon.fn.analyzer/message finding))))
  nil)

(defn socket-server?
  "True for the java.net.ServerSocket an io-prepl listens on."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (instance? java.net.ServerSocket value))

(schema/register-core-predicate! 'seon.cluster/socket-server?
                                 socket-server?)

;;; Each generation makes a FRESH UNBOUND server socket. One shared
;;; delayed socket bound a real process port that no cluster owned and
;;; handed every later contract check the same object, so closing one
;;; sample invalidated the next. The no-argument constructor binds no
;;; port and opens no descriptor.
(def socket-server-generator
  (gen/fmap (fn [_] (java.net.ServerSocket.)) (gen/return nil)))

(defn- ref-identity
  [database ref attribute]
  (when (and database (:db/id ref))
    (get (db/pull database [attribute] (:db/id ref)) attribute)))

(defn render-ai
  "`:seon.render/ai` — one cluster and its load-bearing connections."
  {:malli/schema [:=> [:cat :seon.render/unit] [:maybe :string]]}
  [unit]
  (when-let [name (:seon.cluster/name unit)]
    (let [database (:seon.db/db unit)
          config-name (ref-identity database
                                    (:seon.cluster/config unit)
                                    :seon.config/cluster)
          plan-id (ref-identity database
                                (:seon.cluster/bootstrap-plan unit)
                                :seon.bootstrap.plan/id)
          instructions (count (:seon.cluster/instructions unit))
          toolkit (count (:seon.cluster/toolkit unit))]
      (str "Cluster " name ".\n"
           "Configuration " (or config-name "is connected")
           " and bootstrap plan " (or plan-id "is connected")
           "; " instructions " shared instruction"
           (when-not (= 1 instructions) "s")
           " and " toolkit " toolkit namespace"
           (when-not (= 1 toolkit) "s") "."))))

(defn render-html
  "`:seon.render/html` — one readable cluster card."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:maybe :seon.render/hiccup]]}
  [unit]
  (when-let [name (:seon.cluster/name unit)]
    (let [database (:seon.db/db unit)
          config-name (ref-identity database
                                    (:seon.cluster/config unit)
                                    :seon.config/cluster)
          plan-id (ref-identity database
                                (:seon.cluster/bootstrap-plan unit)
                                :seon.bootstrap.plan/id)]
      [:article {:class "seon-family-entry seon-cluster-entry"}
       [:h3 (str "Cluster " name)]
       [:dl
        [:div [:dt "Configuration"] [:dd (str (or config-name "Connected"))]]
        [:div [:dt "Bootstrap plan"] [:dd [:code (str (or plan-id "Connected"))]]]
        [:div [:dt "Shared instructions"]
         [:dd (str (count (:seon.cluster/instructions unit)))]]
        [:div [:dt "Toolkit namespaces"]
         [:dd (str (count (:seon.cluster/toolkit unit)))]]]])))

(declare readiness)

(def ^:dynamic ^:private *boot-progress!*
  (constantly nil))

(defn- boot-phase
  [instance]
  (cond
    (:seon.boot/ready-ms instance) :seon.boot.phase/ready
    (:seon.render.web/served instance) :seon.boot.phase/web
    (:seon.flow/graph instance) :seon.boot.phase/agents
    (:seon.flow/work-launcher instance) :seon.boot.phase/work-launcher
    (:seon.sci.eval/ctx instance) :seon.boot.phase/program
    (:seon.boot/config-result instance) :seon.boot.phase/config
    (contains? instance :seon.boot/recovered-runs) :seon.boot.phase/recovery
    (:seon.boot/cluster-connection instance) :seon.boot.phase/branch
    (:seon.store/store instance) :seon.boot.phase/store
    (:seon.boot/prepl-server instance) :seon.boot.phase/repl))

;;; ---------------------------------------------------------------------------
;;; MCP result projection — installed at the cluster io-prepl boundary.
;;; ---------------------------------------------------------------------------

(defonce ^:private mcp-projection
  (ThreadLocal.))

(defn project-next-prepl-value!
  "Mark this io-prepl connection's next returned value for MCP projection."
  {:malli/schema [:=> [:cat] :nil]}
  []
  (.set mcp-projection true)
  nil)

(defn- consume-mcp-projection!
  []
  (let [project? (true? (.get mcp-projection))]
    (.remove mcp-projection)
    project?))

(defn- mcp-instance
  [cluster-name]
  (get @running-instances cluster-name))

(defn- mcp-effective
  [cluster-name bootstrap-effective]
  (if-let [connection (:seon.boot/cluster-connection
                       (mcp-instance cluster-name))]
    (config/effective @connection cluster-name)
    bootstrap-effective))

(defn- evaluation-node
  ; A door evaluation is recognized by its result-edn parsing to a print
  ; node. The ORIGINAL string is parsed (the admitted projection may have
  ; truncated it into a marker); anything else falls to the generic window.
  [value]
  (when (and (map? value)
             (string? (:seon.cluster.eval/result-edn value)))
    (let [node (try (edn/read-string (:seon.cluster.eval/result-edn value))
                    (catch Exception _ nil))]
      (when (and (map? node) (:seon.print/face node))
        node))))

(defn- text-face
  ; Both evaluation modes render their compact inline value through the one
  ; printer used by the transcript. The complete admitted artifact is retained
  ; separately by `mcp-project`.
  [node effective details]
  (assoc details
         :seon.dev.mcp/text
         (print/emit-text
          node
          {:seon.print/length (:seon.print/length effective)
           :seon.print/level (:seon.print/level effective)})))

(defn- evaluation-face
  [value node effective]
  (text-face
   node effective
   (cond-> {:seon.cluster.eval/ns (:seon.cluster.eval/ns value)
             :seon.sci.eval/ending-ns (:seon.sci.eval/ending-ns value)
             :seon.sci.admit/capped?
             (boolean (:seon.sci.admit/capped? value))
             :seon.sci.admit/record (:seon.sci.admit/record value)}
      (contains? value :seon.cluster.eval/error)
      (assoc :seon.cluster.eval/error (:seon.cluster.eval/error value))
      (contains? value :seon.cluster.eval/output)
      (assoc :seon.cluster.eval/output (:seon.cluster.eval/output value)))))

(defn- prepl-exception-envelope?
  [value]
  (and (map? value)
       (vector? (:via value))
       (vector? (:trace value))
       (contains? value :cause)))

(defn- nil-deref?
  [cause]
  (= 'clojure.core$deref_future (first (:at cause))))

(defn- exception-summary
  [value]
  (let [cause-entry (last (:via value))
        nil-deref? (nil-deref? cause-entry)
        frame (or (:at cause-entry) (first (:trace value)))
        kind (or (get-in cause-entry [:data :seon.error/kind])
                 (if nil-deref?
                   :seon.dev.mcp/nil-deref
                   :seon.dev.mcp/jvm-exception))
        message (if nil-deref?
                  "The evaluated form dereferenced nil."
                  (str (or (:cause value) (:message cause-entry))))]
    (cond-> {:seon.error/kind kind
             :seon.error/message message
             :seon.dev.mcp/exception-class (str (:type cause-entry))}
      frame (assoc :seon.dev.mcp/frame frame))))

(defn- mcp-project
  [cluster-name bootstrap-effective value]
  (let [instance (mcp-instance cluster-name)
        connection (:seon.boot/cluster-connection instance)
        effective (mcp-effective cluster-name bootstrap-effective)
        evaluation-print-node (evaluation-node value)
        exception-envelope? (prepl-exception-envelope? value)
        exception-summary-value (when exception-envelope?
                                  (exception-summary value))
        admitted
        (if evaluation-print-node
          {:seon.sci.admit/print-node evaluation-print-node
           :seon.sci.admit/capped?
           (boolean (:seon.sci.admit/capped? value))}
          (admit/admit-value
           {:seon.sci.admit/value (or exception-summary-value value)
            :seon.sci.admit/interrupt-fn (fn [])
            :seon.sci.admit/caps (config/result-caps effective)
            :seon.config/on-core-error
            (:seon.config/on-core-error effective)}))
        artifact (render.value/artifact admitted)
        content (render.value/artifact-edn artifact)
        content-digest (blob/digest content)
        threshold (:seon.config.eval.result/blob-threshold effective)
        oversized? (> (count content) threshold)
        artifact-backed? oversized?
        print-node (:seon.sci.admit/print-node artifact)
        projected-node (if oversized?
                         (print/fit
                          print-node
                          (cond-> (render/agent-render-profile effective)
                            connection
                            (assoc :seon.print/requery-id
                                   [:seon.blob/digest content-digest])

                            (nil? connection)
                            (assoc :seon.print/requery-refusal
                                   "the cluster has no database connection")))
                         print-node)
        staged (when (and artifact-backed? connection)
                 (blob/stage! connection content))
        stored-digest
        (when staged
          (blob/with-publication!
           connection
           [staged]
           (fn []
             (let [result
                   (db/transact!
                    connection
                    [{:seon.dev.mcp.artifact/id content-digest
                      :seon.dev.mcp.artifact/digest content-digest}])]
               (when (:seon.error/kind result)
                 (throw
                  (ex-info
                   "The durable MCP artifact root did not commit."
                   {:seon.error/kind :core-bug
                    :seon.dev.mcp.artifact/digest content-digest
                    :seon.dev.mcp.artifact/transaction-result result})))
               content-digest))))]
    (cond-> {:seon.dev.mcp/value
             (if evaluation-print-node
               (evaluation-face value projected-node effective)
               (admit/semantic-value projected-node))
             :seon.sci.admit/capped?
             (:seon.sci.admit/capped? artifact)
             :seon.dev.mcp/windowed? artifact-backed?}
      artifact-backed?
      (assoc :seon.blob/digest content-digest
             :seon.blob/size (count content)
             :seon.dev.mcp/retrievable? (boolean stored-digest))

      (and artifact-backed? (nil? stored-digest))
      (assoc :seon.dev.mcp/remainder
             "The cluster has no database connection; the remainder is not retrievable."))))

(defn mcp-valf
  "Project marked MCP returns; preserve ordinary io-prepl returns unchanged."
  {:malli/schema [:=> [:cat :seon.boot/cluster-name
                       :seon.config/effective :any]
                  :string]}
  [cluster-name bootstrap-effective value]
  (admit/canonical-edn
   (if (consume-mcp-projection!)
     (mcp-project cluster-name bootstrap-effective value)
     value)))

(defn mcp-io-prepl
  "Serve one cluster io-prepl with the cluster-side MCP value projector."
  {:malli/schema [:=> [:cat :seon.boot/cluster-name
                       :seon.config/effective]
                  :nil]}
  [cluster-name bootstrap-effective]
  (clojure.core.server/io-prepl
   :valf (partial mcp-valf cluster-name bootstrap-effective)))

(defn mcp-get-value
  "Read and drill one stored MCP value artifact without mutating REPL state."
  {:malli/schema [:=> [:cat :seon.boot/cluster-name :seon.blob/digest
                       :seon.render.data/path :int]
                  :any]}
  [cluster-name content-digest path offset]
  (if-let [connection (:seon.boot/cluster-connection
                       (mcp-instance cluster-name))]
    (if-let [content
             (when
              (db/q
               '[:find ?artifact .
                 :in $ ?digest
                 :where
                 [?artifact :seon.dev.mcp.artifact/digest ?digest]]
               (db/db connection)
               content-digest)
               (blob/get connection content-digest))]
      (let [stored (render.value/read-artifact content)
            found (render.data/at
                   (render.value/artifact-value stored)
                   {:seon.render.data/path path
                    :seon.render.data/offset offset})]
        (if (contains? found :seon.render.data/value)
          (render.value/window
           (:seon.render.data/value found) offset
           (:seon.render.value/max-collection
            (config/effective @connection cluster-name)))
          found))
      {:seon.error/kind :seon.dev.mcp/value-not-found
       :seon.error/message "No stored MCP value has this digest."
       :seon.blob/digest content-digest})
    {:seon.error/kind :seon.dev.mcp/remainder-not-retrievable
     :seon.error/message
     "The cluster has no database connection; the remainder is not retrievable."
     :seon.blob/digest content-digest}))

(defn mcp-runtime-observation
  "Derive health and Flow observations for one root-discovered cluster."
  {:malli/schema [:=> [:cat :seon.boot/cluster-name] :map]}
  [cluster-name]
  (let [instance (mcp-instance cluster-name)
        connection (:seon.boot/cluster-connection instance)
        ready (when instance (readiness instance))
        problem-counts
        (into (sorted-map)
              (map (fn [[family rows]] [family (count rows)]))
              (:seon.problems/problems ready))
        readiness-face
        (when ready
          (dissoc ready :seon.problems/problems))]
    (cond-> {:seon.dev.mcp/cluster cluster-name
             :seon.dev.mcp/health
             (if connection :observed :unknown)
             :seon.dev.mcp/flow
             (if (and connection (:seon.flow/graph instance))
               (oversight/cluster-flow-status @connection instance)
               :unknown)
             :seon.dev.mcp/problem-counts problem-counts}
      readiness-face (assoc :seon.dev.mcp/readiness readiness-face))))

;;; ---------------------------------------------------------------------------
;;; Pure resolution — defaults are THE defaults document for this layer
;;; ---------------------------------------------------------------------------

(defn- refused!
  [message offense]
  (throw (ex-info message
                  {:seon.error/kind :seon.boot/refused
                   :seon.boot/offense offense})))

;; REQUIRES the population: `resolve-bootstrap` asks two questions and each
;; refusal arm asks a third, so the ambient arity made one bootstrap
;; resolution two or three complete classpath re-reads (2026-08-07).
(defn- require-candidate-value
  ([schema-key value message]
   (require-candidate-value (schema/declaration-population)
                            schema-key value message))
  ([forms schema-key value message]
   (if (schema/valid-candidate-value? forms schema-key value)
     value
     (refused! message
               {:seon.boot/schema schema-key
                :seon.boot/value value
                :seon.boot/explanation
                (schema/explain-candidate-value forms schema-key value)}))))

(declare cluster-paths)

(defn resolve-bootstrap
  "Resolve overrides into one complete bootstrap configuration.
  Every key optional; absent = default. Defaults: cluster-name
  \"default\" (just a name, nothing special), root \"data/clusters\",
  prepl-host \"127.0.0.1\", prepl-port 0 (ephemeral — the advertisement
  carries the real port), log-dir derived as <root>/<name>/logs,
  store-dir derived as <root>/store — the PROCESS-root store every
  cluster branches from.
  Refuses (throws ex-info {:seon.error/kind :seon.boot/refused ...}) when a
  declared key has an invalid value. Extra keys remain available for
  accretion."
  {:malli/schema [:=> [:cat :seon.boot/overrides] :seon.boot/config]}
  [overrides]
  ;; ONE declaration population for the whole resolution — it asks two
  ;; questions and each refusal arm asks a third.
  (let [forms (schema/declaration-population)]
    (require-candidate-value
     forms
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
       forms
       :seon.boot/config
       (merge {:seon.boot/log-dir derived-log-dir
               :seon.boot/store-dir derived-store-dir}
              base)
       "The resolved bootstrap configuration was refused."))))

(defn cluster-paths
  "Derive every per-cluster path from (root, cluster-name).
  Convention owns the layout: the cluster directory, its log directory,
  and its advertisement file. The STORE is per process root under
  branch-per-cluster (b2-plan section 0); its path is bootstrap config,
  never a per-cluster derivation. One derivation — no other code builds
  these paths."
  {:malli/schema [:=> [:cat :seon.boot/root :seon.boot/cluster-name]
                  [:map
                   [:seon.boot/cluster-dir :string]
                   [:seon.boot/advertisement-file :string]
                   [:seon.boot/log-dir :string]
                   [:seon.search/path :string]]]}
  [root cluster-name]
  (let [cluster-dir (io/file root cluster-name)]
    {:seon.boot/cluster-dir (str cluster-dir)
     :seon.boot/advertisement-file
     (str (io/file cluster-dir "prepl.edn"))
     :seon.boot/log-dir (str (io/file cluster-dir "logs"))
     :seon.search/path (str (io/file cluster-dir "derived" "lucene"))}))

;;; ---------------------------------------------------------------------------
;;; The instance lifecycle
;;; ---------------------------------------------------------------------------

(defn root-executors
  "The process root's two shared executors.

  Flow resolves this stable public entry point from its protected owner. The
  executor holder itself lives in `seon.operator.runtime`, outside every
  cluster program graph."
  {:malli/schema [:=> [:cat] :seon.boot/executors]}
  []
  (operator.runtime/root-executors))

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

(defn- create-directories!
  [config paths]
  (doseq [path [(:seon.boot/cluster-dir paths)
                (:seon.boot/log-dir config)]]
    (.mkdirs (io/file path))))

(defn- operator-root
  [cluster-root]
  (or (System/getProperty "seon.operator.root")
      (let [root-file (.getCanonicalFile (io/file cluster-root))
            parent (.getParentFile root-file)]
        (if (and (= "clusters" (.getName root-file))
                 parent (= "data" (.getName parent)))
          (.getCanonicalPath (.getParentFile parent))
          (.getCanonicalPath root-file)))))

(defn- warn-low-space!
  [managed-root effective]
  ;; statfs only — the boot path must never pay a recursive directory
  ;; walk (a checkout carrying frozen tmp/ evidence took ~94 s, which is
  ;; the P19 boot-readiness failure of 2026-08-05).
  (let [footprint (operator.state/filesystem-space managed-root)
        low? (or (< (:seon.operator.footprint/usable-bytes footprint)
                    (:seon.config.maintenance/min-usable-bytes effective))
                 (< (:seon.operator.footprint/usable-ratio footprint)
                    (:seon.config.maintenance/min-usable-ratio effective)))]
    (when low?
      (let [message
            (format
             (str "LOW DISK SPACE under %s: %.2f GiB usable (%.1f%%); "
                  (if (= :panic (:seon.config/on-core-error effective))
                    "the development core-error policy stops this boot."
                    "production logs the observation and continues."))
             managed-root
             (/ (double (:seon.operator.footprint/usable-bytes footprint))
                1073741824.0)
             (* 100.0 (:seon.operator.footprint/usable-ratio footprint)))]
        (log/warn message)
        (when (= :panic (:seon.config/on-core-error effective))
          (throw
           (ex-info message
                    {:seon.error/kind :seon.operator/low-disk-space
                     :seon.operator/footprint footprint
                     :seon.config.maintenance/min-usable-bytes
                     (:seon.config.maintenance/min-usable-bytes effective)
                     :seon.config.maintenance/min-usable-ratio
                     (:seon.config.maintenance/min-usable-ratio effective)})))))
    footprint))

(defn- write-advertisement!
  [paths advertisement]
  (spit (:seon.boot/advertisement-file paths)
        (str (pr-str advertisement) "\n")))

;;; ---------------------------------------------------------------------------
;;; The process-root store — opened once, shared by every instance
;;; ---------------------------------------------------------------------------

;;; The operator runtime owns the process-global holder. The count is the
;;; holder count, not a status flag — the last instance out releases the store,
;;; and the flock with it.

(defn- root-store-key
  "The canonical key for one physical process-root store."
  [store-dir]
  (.getCanonicalPath (io/file store-dir)))

(defn- acquire-root-store!
  "The ONE store at `store-dir`, opened on first use and shared after.

  A supplied history policy is creation-fixed for the whole operator root.
  A later cluster in the same JVM must request the held representation."
  ([store-dir]
   (acquire-root-store! store-dir ::unspecified-history-policy))
  ([store-dir keep-history?]
   (let [store-key (root-store-key store-dir)
         requested? (not= ::unspecified-history-policy keep-history?)]
     (locking root-store-holder
       (if-let [held (get @root-store-holder store-key)]
         (let [store (:seon.store/store held)
               main-connection (:seon.store/connection-object store)
               held-keep-history?
               (get-in @main-connection [:config :keep-history?])]
           (when (and requested?
                      (not= keep-history? held-keep-history?))
             (refused!
              "The requested history policy conflicts with the held operator-root store."
              {:seon.boot/rule ::keep-history-mismatch
               :seon.config.db/keep-history? keep-history?
               :seon.store/keep-history? held-keep-history?
               :seon.store/dir store-key}))
           (swap! root-store-holder update-in [store-key ::holders] inc)
           store)
         ; open OUTSIDE the map first: a failed open must leave no entry
         (let [request (cond-> {:seon.store/dir store-key}
                         requested?
                         (assoc :seon.config.db/keep-history? keep-history?))
               store (store/open-store! request)]
           (swap! root-store-holder assoc store-key
                  {:seon.store/store store ::holders 1})
           store))))))

(defn- release-root-store!
  "Drop one holder; the LAST one releases the store and its flock."
  [store-dir]
  (let [store-key (root-store-key store-dir)]
    (locking root-store-holder
      (when-let [held (get @root-store-holder store-key)]
        (let [remaining (dec (long (::holders held)))]
          (if (pos? remaining)
            (swap! root-store-holder assoc-in [store-key ::holders] remaining)
            (do
              ; release FIRST: a failure leaves this exact flock-held store
              ; addressable here for the stop retry
              (store/release-store! (:seon.store/store held))
              (swap! root-store-holder dissoc store-key)))))))
  nil)

;;; ---------------------------------------------------------------------------
;;; The default source population
;;; ---------------------------------------------------------------------------

(def boot-process-identity
  "The opaque provenance identity for the boot schema population."
  "seon.db.process/boot")

(declare require-committed!)

(defn- incompatible-declaration-message
  [cluster-name attribute]
  (let [target-name (or cluster-name "NAME")
        subject (if cluster-name
                  (str "Cluster `" cluster-name "`")
                  "This branch")]
    (str subject " predates the incompatible schema change for `" attribute
         "` and cannot be reopened in place. "
         "`bin/seon init " target-name " --force` destroys and reforks it from "
         "`current-src`; use export/import instead to preserve its data.")))

(defn- declaration-changes
  "Missing declarations, refusing non-accretive storage changes."
  [db forms cluster-name]
  (into
   []
   (keep
    (fn [{attribute :db/ident :as declaration}]
      (if-let [installed (get (:schema db) attribute)]
        (when-not
          (= (dissoc declaration :db/ident)
            (select-keys installed (keys (dissoc declaration :db/ident))))
          (refused!
           (incompatible-declaration-message cluster-name attribute)
           (cond->
            {:seon.boot/attribute attribute
             :seon.boot/installed installed
             :seon.boot/current declaration}
             cluster-name
             (assoc :seon.boot/cluster-name cluster-name))))
        declaration)))
   (schema.datahike/malli->datahike-schema-in
    {:seon.schema.projection/forms forms}
    (schema/canonical-database-attributes forms))))

(defn- missing-process-rows
  [db]
  (let [present
        (into
         #{}
         (db/q '[:find [?id ...]
                :where [_ :seon.db.process/id ?id]]
              db))]
    (into
     []
     (comp
      (remove present)
      (map (fn [process-id] {:seon.db.process/id process-id})))
     [boot-process-identity config/managing-process-identity])))

(defn- schema-row-changes
  [db forms]
  (into
   []
   (keep
    (fn [{schema-key :seon.schema/key :as desired}]
      (let [current
            (some-> (db/pull db (vec (keys desired))
                            [:seon.schema/key schema-key])
                    (dissoc :db/id))]
        (when-not (= desired (select-keys current (keys desired)))
          desired))))
   (schema/canonical-schema-rows forms)))

(defn- instruction-row-changes
  [db rows]
  (into
   []
   (remove
    (fn [{instruction-id :seon.cluster.instruction/id}]
      (some? (db/q '[:find ?instruction .
                    :in $ ?instruction-id
                    :where
                    [?instruction :seon.cluster.instruction/id
                     ?instruction-id]]
                  db instruction-id))))
   rows))

(defn- activation-requirements
  [database]
  (let [projection (schema/projection-from-database database)
        forms (:seon.schema.projection/forms projection)
        shapes (into []
                     (filter :seon.schema/entity?)
                     (:seon.schema.projection/catalog projection))
        schema-keys (into #{} (map :seon.schema/key) shapes)
        required-attributes
        (into #{} (mapcat :seon.schema/required-attrs) shapes)
        config-dials
        (into #{}
              (map first)
              (schema.form/map-entries
               (get forms :seon.config/manifest)))
        config-defaults
        (into #{}
              (filter
               (fn [dial]
                 (let [properties
                       (schema.form/attr-form-properties (get forms dial))]
                   (or (contains? properties :seon.config/default)
                       (true? (:seon.config/optional properties))))))
              config-dials)
        executable-symbols
        (into #{}
              (db/q '[:find [?symbol ...]
                      :where
                      [?function :seon.fn/sym ?symbol]]
                    database))]
    {:seon.activation/projection projection
     :seon.activation/schema-keys schema-keys
     :seon.activation/required-attributes required-attributes
     :seon.activation/config-defaults config-defaults
     :seon.activation/config-required
     (set/difference config-dials config-defaults)
     :seon.activation/executable-symbols executable-symbols}))

(defn- lookup-refs-in
  [database value]
  (letfn [(lookup-ref? [candidate]
            (and (vector? candidate)
                 (= 2 (count candidate))
                 (qualified-keyword? (first candidate))
                 (:db/unique (get (:schema database) (first candidate)))))
          (walk [candidate]
            (cond
              (lookup-ref? candidate) [candidate]
              (map? candidate) (mapcat walk (vals candidate))
              (coll? candidate) (mapcat walk candidate)
              :else []))]
    (->> (walk value) distinct (sort-by pr-str) vec)))

(defn- activation-lookup-row
  [source-digest [attribute value]]
  {:seon.activation.lookup/id
   (schema/sha-256
    [(.getBytes (pr-str [source-digest attribute value])
                StandardCharsets/UTF_8)])
   :seon.activation.lookup/attribute attribute
   :seon.activation.lookup/value value})

(defn- transact-initialization!
  [connection rows]
  (loop [pending (vec rows)]
    (when (seq pending)
      (let [database @connection
            ready?
            (fn [row]
              (every?
               (fn [[attribute value]]
                 (:db/id (db/pull database [:db/id] [attribute value])))
               (lookup-refs-in database row)))
            ready (into [] (filter ready?) pending)
            waiting (into [] (remove ready?) pending)]
        (when (empty? ready)
          (refused!
           "Initialization lookup refs do not resolve."
           {:seon.activation/missing
            (into []
                  (comp
                   (mapcat #(lookup-refs-in database %))
                   ;; `(distinct)` — the transducer. Bare `distinct` is the
                   ;; one-argument COLLECTION arity, so composing it here
                   ;; handed `comp` a LazySeq where a reducing function
                   ;; belongs and the refusal threw a ClassCastException
                   ;; instead of naming the unresolved lookups (found
                   ;; 2026-08-08, the first time this branch ever ran).
                   (distinct)
                   (map (fn [[attribute value]]
                          {:seon.activation/lookup-attribute attribute
                           :seon.activation/lookup-value value})))
                  waiting)}))
        (require-committed!
         (db/transact! connection
                       {:tx-data ready
                        :tx-meta
                        {:seon.db/process
                         [:seon.db.process/id boot-process-identity]}})
         {:seon.boot/population :seon.config/initialization})
        (recur waiting))))
  nil)

(defn activation-missing
  "Missing facts for one stored or candidate activation closure at `database`."
  {:malli/schema
   [:=> [:cat :seon.db/database-value
         :seon.activation/closure
         :seon.activation/lookup-rows]
    :seon.activation/missing]}
  [database closure lookup-rows]
  (let [{expected-schemas :seon.activation/schema-keys
         expected-attributes :seon.activation/required-attributes
         expected-defaults :seon.activation/config-defaults
         expected-required :seon.activation/config-required
         expected-symbols :seon.activation/executable-symbols}
        (activation-requirements database)
        stored-schemas (set (:seon.activation/schema-keys closure))
        stored-attributes (set (:seon.activation/required-attributes closure))
        stored-defaults (set (:seon.activation/config-defaults closure))
        stored-required (set (:seon.activation/config-required closure))
        stored-symbols (set (:seon.activation/executable-symbols closure))
        database-schemas
        (into #{}
              (db/q '[:find [?key ...]
                      :where [?schema :seon.schema/key ?key]]
                    database))
        installed-attributes (set (keys (:schema database)))
        database-symbols expected-symbols
        missing-schemas
        (set/union (set/difference expected-schemas stored-schemas)
                   (set/difference stored-schemas database-schemas))
        missing-attributes
        (set/union (set/difference expected-attributes stored-attributes)
                   (set/difference stored-attributes installed-attributes))
        expected-dials (set/union expected-defaults expected-required)
        stored-dials (set/union stored-defaults stored-required)
        missing-dials
        (set/union (set/difference expected-dials stored-dials)
                   (set/intersection stored-defaults stored-required))
        missing-symbols
        (set/union (set/difference expected-symbols stored-symbols)
                   (set/difference stored-symbols database-symbols))
        missing-lookups
        (into []
              (keep
               (fn [{attribute :seon.activation.lookup/attribute
                     value :seon.activation.lookup/value}]
                 (let [resolved (db/pull database [:db/id] [attribute value])]
                   (when-not (:db/id resolved)
                     {:seon.activation/lookup-attribute attribute
                      :seon.activation/lookup-value value}))))
              lookup-rows)]
    (into []
          cat
          [(map (fn [schema-key]
                  {:seon.activation/schema-key schema-key})
                (sort missing-schemas))
           (map (fn [attribute]
                  {:seon.activation/required-attribute attribute})
                (sort missing-attributes))
           (map (fn [dial]
                  {:seon.activation/config-dial dial})
                (sort missing-dials))
           (sort-by pr-str missing-lookups)
           (map (fn [symbol]
                  {:seon.activation/executable-symbol symbol})
                (sort missing-symbols))])))

(defn derive-activation
  "Derive and preflight the complete activation closure on one source scratch."
  {:malli/schema [:=> [:cat :seon.activation/request]
                  :seon.activation/result]}
  [{connection :seon.db/connection
    source-digest :seon.source/digest
    requested-symbols :seon.activation/requested-symbols}]
  (let [database @connection
        requirements (activation-requirements database)
        initialization
        (schema/call-with-forms
         (:seon.schema.projection/forms
          (:seon.activation/projection requirements))
         config/default-population)
        lookup-rows
        (mapv (partial activation-lookup-row source-digest)
              (lookup-refs-in database initialization))
        closure
        {:seon.activation/source-digest source-digest
         :seon.activation/schema-keys
         (:seon.activation/schema-keys requirements)
         :seon.activation/required-attributes
         (:seon.activation/required-attributes requirements)
         :seon.activation/config-defaults
         (:seon.activation/config-defaults requirements)
         :seon.activation/config-required
         (:seon.activation/config-required requirements)
         :seon.activation/executable-symbols
         (:seon.activation/executable-symbols requirements)
         :seon.activation/lookup-refs
         (mapv (fn [{id :seon.activation.lookup/id}]
                 [:seon.activation.lookup/id id])
               lookup-rows)}
        requested-missing
        (mapv (fn [symbol]
                {:seon.activation/executable-symbol symbol})
              (sort
               (set/difference
                (into #{} (map str) requested-symbols)
                (:seon.activation/executable-symbols requirements))))]
    {:seon.activation/closure closure
     :seon.activation/lookup-rows lookup-rows
     :seon.activation/missing
     (into (activation-missing database closure lookup-rows)
           requested-missing)}))

(def ^:private activation-closure-set-attributes
  "The closure attributes `:seon.activation/closure` declares as sets.

  Datahike projects a cardinality-many attribute as a VECTOR and omits it
  entirely when the entity holds no datom for it, so a pulled closure carries
  neither the declared type nor the declared key. `derive-activation` builds
  the same shape from real sets; this vector is the one declaration both the
  pull pattern and the read boundary below are built from, so the two cannot
  drift apart."
  [:seon.activation/schema-keys
   :seon.activation/required-attributes
   :seon.activation/config-defaults
   :seon.activation/config-required
   :seon.activation/executable-symbols])

(def ^:private activation-closure-pattern
  (-> [:seon.activation/source-digest]
      (into activation-closure-set-attributes)
      (conj {:seon.activation/lookup-refs
             [:seon.activation.lookup/id
              :seon.activation.lookup/attribute
              :seon.activation.lookup/value]})))

(defn- pulled-closure
  "Restore the declared `:seon.activation/closure` value from a pull projection.

  Absent IS the empty set: a cardinality-many attribute with no members has no
  datoms, so `(set nil)` is the faithful reading, not a substituted default."
  [closure]
  (reduce (fn [closure attribute]
            (assoc closure attribute (set (get closure attribute))))
          closure
          activation-closure-set-attributes))

(defn- stored-activation
  [database]
  (when-let [source
             (db/q '[:find ?source .
                     :where
                     [?source :seon.source/digest _]
                     [?source :seon.source/activation-closure ?closure]]
                   database)]
    (let [source-row
          (db/pull database
                   [{:seon.source/activation-closure
                     activation-closure-pattern}]
                   source)
          closure (pulled-closure
                   (:seon.source/activation-closure source-row))
          lookup-rows (vec (:seon.activation/lookup-refs closure))]
      {:seon.activation/closure
       (update closure :seon.activation/lookup-refs
               (fn [rows]
                 (mapv (fn [{id :seon.activation.lookup/id}]
                         [:seon.activation.lookup/id id])
                       rows)))
       :seon.activation/lookup-rows lookup-rows})))

(defn- closure-fact-missing
  [database closure lookup-rows]
  (let [schema-keys (set (:seon.activation/schema-keys closure))
        required-attributes
        (set (:seon.activation/required-attributes closure))
        config-defaults (set (:seon.activation/config-defaults closure))
        config-required (set (:seon.activation/config-required closure))
        executable-symbols
        (set (:seon.activation/executable-symbols closure))
        database-schemas
        (into #{}
              (db/q '[:find [?key ...]
                      :where [?schema :seon.schema/key ?key]]
                    database))
        installed-attributes (set (keys (:schema database)))
        database-symbols
        (into #{}
              (db/q '[:find [?symbol ...]
                      :where [?function :seon.fn/sym ?symbol]]
                    database))]
    (into []
          cat
          [(map (fn [schema-key]
                  {:seon.activation/schema-key schema-key})
                (sort (set/difference schema-keys database-schemas)))
           (map (fn [attribute]
                  {:seon.activation/required-attribute attribute})
                (sort
                 (set/difference required-attributes installed-attributes)))
           (map (fn [dial]
                  {:seon.activation/config-dial dial})
                (sort (set/intersection config-defaults config-required)))
           (keep
            (fn [{attribute :seon.activation.lookup/attribute
                  value :seon.activation.lookup/value}]
              (when-not (:db/id (db/pull database [:db/id] [attribute value]))
                {:seon.activation/lookup-attribute attribute
                 :seon.activation/lookup-value value}))
            (sort-by pr-str lookup-rows))
           (map (fn [executable-symbol]
                  {:seon.activation/executable-symbol executable-symbol})
                (sort
                 (set/difference executable-symbols database-symbols)))])))

(defn require-activation!
  "Return the stored closure or refuse with every missing activation fact."
  {:malli/schema [:=> [:cat :seon.db/database-value]
                  :seon.activation/closure]}
  [database]
  (let [{closure :seon.activation/closure
         lookup-rows :seon.activation/lookup-rows}
        (or (stored-activation database)
            {:seon.activation/closure nil
             :seon.activation/lookup-rows []})
        missing
        (if closure
          (closure-fact-missing database closure lookup-rows)
          [{:seon.activation/schema-key :seon.activation/closure}])]
    (when (seq missing)
      (let [refusal (source/activation-refusal missing)]
        (refused! (:seon.error/message refusal) refusal)))
    closure))

(defn- accrete-schema-population!
  "Install the current additive schema population on one branch.

  Registration and database installation are separate in Datahike's
  `:write` schema mode. Every opened branch therefore passes through this
  choke point before any domain transaction. Missing declarations and
  canonical rows accrete; an incompatible declaration refuses loudly and
  names refork or export/import as the resolutions. A converged reopen issues
  no transaction."
  [connection cluster-name]
  (let [forms (schema.edn/packaged-forms)]
    (schema/call-with-forms
     forms
     (fn []
       (let [declarations
             (declaration-changes @connection forms cluster-name)]
         (when (seq declarations)
           (require-committed!
            (db/transact! connection {:tx-data declarations})
            {:seon.boot/population :seon.schema/declarations})))
       (let [process-rows (missing-process-rows @connection)]
         (when (seq process-rows)
           (require-committed!
            (db/transact! connection {:tx-data process-rows})
            {:seon.boot/population :seon.db/processes})))
       (let [schema-rows (schema-row-changes @connection forms)]
         (when (seq schema-rows)
           (require-committed!
            (db/transact! connection
                          {:tx-data schema-rows
                           :tx-meta
                           {:seon.db/process
                            [:seon.db.process/id boot-process-identity]}})
            {:seon.boot/population :seon.schema/rows}))))))
  nil)

(defn populate-source!
  "The default `current-src` content: this code's schema and program rows.
  Named by symbol in `source/publish!`'s request, so the producer is
  data and N5's program-graph indexer replaces it without touching the
  boot path. The convergent population transactions are DERIVED, never
  hand-written:
  the Datahike declarations of every registered database attribute, the
  core process entities the provenance refs resolve to (genesis data —
  bootstrap content lives in the source branch), and the canonical schema rows
  asserted with that process provenance."
  {:malli/schema
   [:=> [:cat [:map [:seon.db/connection
                     :seon.db/connection]]]
    :nil]}
  [{connection :seon.db/connection
    manifest :seon.fn/manifest}]
  (let [forms (schema.edn/packaged-forms)]
    (schema/call-with-forms
     forms
     (fn []
       (report-source-progress! "schema population started")
       (accrete-schema-population! connection nil)
       (report-source-progress! "schema population complete")
       (report-source-progress! "bootstrap rows")
       (let [rows (bootstrap/population-tx @connection)]
         (when (seq rows)
           (require-committed!
            (db/transact! connection
                          {:tx-data rows
                           :tx-meta
                           {:seon.db/process
                            [:seon.db.process/id boot-process-identity]}})
            {:seon.boot/population :seon.bootstrap/rows})))
       (report-source-progress! "instruction rows")
       (let [rows (instruction-row-changes
                   @connection
                   (instruction/seed-rows))]
         (when (seq rows)
           (require-committed!
            (db/transact! connection
                          {:tx-data rows
                           :tx-meta
                           {:seon.db/process
                            [:seon.db.process/id boot-process-identity]}})
            {:seon.boot/population :seon.cluster.instruction/rows})))
       (report-source-progress! "program rows started")
       (seon.fn/index!
        (cond-> {:seon.db/connection connection
                 :seon.db/process
                 [:seon.db.process/id boot-process-identity]}
          manifest (assoc :seon.fn/manifest manifest)
          (nil? manifest) (assoc :seon.fn/roots seon.fn/source-roots))
        report-source-progress!)
       (report-source-progress! "program rows complete")
       ;; Initialization rows come LAST because they may name a program row
       ;; by lookup ref — the call-preparation suppliers do — and program
       ;; rows are asserted by `index!` immediately above. Nothing earlier in
       ;; this population reads a config fact (`seon.fn` and
       ;; `seon.cluster.instruction` name no config attribute), so the move
       ;; costs no dependency and removes an ordering hazard that would
       ;; otherwise force every declared row to predate the program graph.
       (report-source-progress! "initialization rows")
       (let [rows (config/default-population)]
         (when (seq rows)
           (transact-initialization! connection rows))))))
  nil)

;;; ---------------------------------------------------------------------------
;;; The tower above the REPL
;;; ---------------------------------------------------------------------------

;;; The roots the published source digest is computed over. Today the
;;; population above is derived from the Clojure program plus the
;;; classpath schema population. The indexer reads only Clojure files,
;;; while the source digest also covers the EDN declarations whose
;;; Datahike schema and canonical rows are installed into `current-src`.
(def source-roots
  "The complete file roots whose content identifies `current-src`."
  (into seon.fn/source-roots
        ["resources/seon/bootstrap.edn"
         "config/default.edn"]))

(defonce ^:private source-refresh-monitor
  ;; One JVM may receive overlapping editor events. Serialize analysis,
  ;; publication, and artifact replacement as one operation; the Datahike
  ;; expected-head guard remains the cross-plan correctness fence.
  (Object.))

(defonce ^:private source-analysis-cache
  ;; A source snapshot determines the complete static projection. Scratch
  ;; stores differ, but their program rows do not; repeated test/experiment
  ;; roots should not re-run clj-kondo for identical bytes.
  (atom nil))

(defn source-snapshot
  "Snapshot source plus the merged schema declaration set."
  {:malli/schema [:=> [:cat] :seon.source/snapshot]}
  []
  (let [tree-snapshot
        (source/snapshot {:seon.source/roots source-roots})
        schema-digest (schema.edn/declaration-digest)
        schema-path (.getCanonicalPath (io/file "resources/seon/schemas"))
        file-digests
        (assoc (:seon.source/file-digests tree-snapshot)
               schema-path schema-digest)
        digest
        (schema/sha-256
         [(.getBytes (str "source\u0000"
                          (:seon.source/digest tree-snapshot) "\n"
                          "schema\u0000" schema-digest "\n")
                    StandardCharsets/UTF_8)])]
    {:seon.source/digest digest
     :seon.source/file-digests (into (sorted-map) file-digests)}))

(defn- current-source-snapshot
  []
  (source-snapshot))

(defn- publish-current-source!
  [store source-digest manifest]
  (source/publish!
   {:seon.store/store store
    :seon.source/digest source-digest
    :seon.source/populate `populate-source!
    :seon.source/activation `derive-activation
    :seon.source/populate-request {:seon.fn/manifest manifest}}))

(defn- current-source!
  "The exact published source commit new clusters fork.
  Boot never indexes files: absent publication tells the operator to run
  `bin/seon init`."
  [store]
  (or (source/current store)
      (refused!
       "No `current-src` branch is published; run `bin/seon init` first."
       {:seon.source/branch source/current-branch})))

(defn source-base!
  "Acquire the immutable published program value shared by new cluster forks."
  {:malli/schema
   [:=> [:cat :seon.store/store]
    [:map
     [:seon.source/commit-id :seon.source/commit-id]
     [:seon.source/digest :seon.source/digest]
     [:seon.source/activation-closure :seon.activation/closure]
     [:seon.store/store :seon.store/store]
     [:seon.store/branch :seon.store/branch]
     [:seon.db/db :seon.db/database-value]
     [:seon.schema/projection :seon.schema/projection]
     [:seon.sci.eval/ctx :seon.sci.eval/ctx]]]}
  [store]
  (let [{commit-id :seon.source/commit-id} (current-source! store)
        connection (store/open-branch! store source/current-branch)]
    (try
      (let [database @connection
            activation-closure (require-activation! database)
            source-digest
            (db/q '[:find ?digest .
                    :where [_ :seon.source/digest ?digest]]
                  database)
            projection (schema/projection-from-database database)
            projection-state (sci.eval/projection-state database projection)]
        {:seon.source/commit-id commit-id
         :seon.source/digest source-digest
         :seon.source/activation-closure activation-closure
         :seon.store/store store
         :seon.store/branch source/current-branch
         :seon.db/db database
         :seon.schema/projection projection
         :seon.sci.eval/ctx
         (sci.eval/cluster-ctx database nil projection-state)})
      (finally
        (d/release connection)))))

(defn- count-installed
  [db attribute]
  (if (contains? (:schema db) attribute)
    (or
     (db/q '[:find (count ?entity) .
            :in $ ?attribute
            :where [?entity ?attribute]]
          db
          attribute)
     0)
    0))

(defn- program-currentness
  [db]
  (let [recorded-digests
        (if (contains? (:schema db) :seon.source/digest)
          (into
           #{}
           (db/q '[:find [?digest ...]
                  :where [_ :seon.source/digest ?digest]]
                db))
          #{})
        namespace-count (count-installed db :seon.ns/name)
        function-count (count-installed db :seon.fn/sym)
        namespace-populated? (pos? namespace-count)
        function-populated? (pos? function-count)
        partial? (not= namespace-populated? function-populated?)
        populated? (and namespace-populated? function-populated?)
        one-digest? (= 1 (count recorded-digests))]
    {:seon.source/coherent? (and one-digest? populated?)
     :seon.source/partial? partial?
     :seon.source/recorded-digests recorded-digests
     :seon.source/namespace-count namespace-count
     :seon.source/function-count function-count}))

(defn- require-coherent-program!
  [connection cluster-name]
  (let [currentness (program-currentness @connection)]
    (when-not (:seon.source/coherent? currentness)
      (let [condition
            (cond
              (:seon.source/partial? currentness)
              (str "partial ("
                   (:seon.source/namespace-count currentness)
                   " namespace rows and "
                   (:seon.source/function-count currentness)
                   " function rows)")

              (empty? (:seon.source/recorded-digests currentness))
              "unprimed (no recorded source digest)"

              (> (count (:seon.source/recorded-digests currentness)) 1)
              (str "incoherent (multiple recorded source digests "
                   (pr-str (:seon.source/recorded-digests currentness))
                   ")")

              :else
              "unprimed (the source-owned program rows are absent)")]
        (refused!
         (str
          "Cluster `" cluster-name "` was not started because its program "
          "graph is " condition ". "
          "`bin/seon init " cluster-name " --force` destroys that branch and "
          "reforks a complete cluster from `current-src`.")
         currentness)))
    currentness))

(defn source-artifact-file
  "The per-store artifact that describes the published source commit."
  {:malli/schema [:=> [:cat :seon.boot/root] :string]}
  [root]
  (str (io/file root "build" "current-src.edn")))

(defn- read-source-artifact
  [root]
  (try
    (let [value (edn/read-string (slurp (source-artifact-file root)))]
      (when (map? value) value))
    (catch Throwable _ nil)))

(defn- write-source-artifact!
  [root artifact]
  (let [target (.toPath (io/file (source-artifact-file root)))
        directory (.getParent target)]
    (Files/createDirectories directory
                             (make-array java.nio.file.attribute.FileAttribute 0))
    (let [temporary (Files/createTempFile
                     directory "current-src-" ".edn"
                     (make-array java.nio.file.attribute.FileAttribute 0))]
      (try
        (Files/writeString temporary (str (pr-str artifact) "\n")
                           StandardCharsets/UTF_8
                           (make-array java.nio.file.OpenOption 0))
        (Files/move temporary target
                    (into-array CopyOption
                                [StandardCopyOption/ATOMIC_MOVE
                                 StandardCopyOption/REPLACE_EXISTING]))
        (finally
          (Files/deleteIfExists temporary))))
    artifact))

(defn- source-artifact
  [published manifest snapshot]
  {:seon.source/digest (:seon.source/digest published)
   :seon.source/commit-id (:seon.source/commit-id published)
   :seon.source/file-digests (:seon.source/file-digests snapshot)
   :seon.fn/manifest manifest})

(defn- stable-manifest
  []
  (report-source-progress! "source snapshot")
  (let [snapshot-before (current-source-snapshot)
        cached @source-analysis-cache
        cached? (= snapshot-before (:seon.source/snapshot cached))
        _ (report-source-progress!
           (str "analysis started: "
                (count (:seon.source/file-digests snapshot-before))
                " source inputs"))
        manifest (if cached?
                   (:seon.fn/manifest cached)
                   (seon.fn/build-manifest
                    {:seon.fn/roots seon.fn/source-roots}))
        _ (report-source-progress! "analysis complete")
        _ (report-analysis-warnings! manifest)
        snapshot-after (current-source-snapshot)]
    (when-not (= snapshot-before snapshot-after)
      (refused! "Source changed while current-src was being analyzed; retry."
                {:seon.source/digest-before
                 (:seon.source/digest snapshot-before)
                 :seon.source/digest-after
                 (:seon.source/digest snapshot-after)}))
    (let [result {:seon.source/snapshot snapshot-after
                  :seon.source/digest (:seon.source/digest snapshot-after)
                  :seon.fn/manifest manifest}]
      (when-not cached?
        (clojure.core/reset! source-analysis-cache result))
      result)))

(defn- full-source-refresh!
  [root store]
  (let [{source-digest :seon.source/digest
         snapshot :seon.source/snapshot
         manifest :seon.fn/manifest} (stable-manifest)
        _ (report-source-progress! "branch publication started")
        published (publish-current-source! store source-digest manifest)
        _ (report-source-progress! "branch publication complete")]
    (write-source-artifact! root (source-artifact published manifest snapshot))
    published))

(defn- canonical-path
  [path]
  (.getCanonicalPath (io/file path)))

(defn- unreported-source-current?
  [published-file-digests current-file-digests reported-paths]
  (= (apply dissoc published-file-digests reported-paths)
     (apply dissoc current-file-digests reported-paths)))

(defn- incremental-source-refresh!
  [root store changed-paths]
  (let [cached (read-source-artifact root)
        published (source/current store)
        manifest (:seon.fn/manifest cached)
        expected-commit (:seon.source/commit-id published)]
    (if-not (and manifest
                 expected-commit
                 (= expected-commit (:seon.source/commit-id cached))
                 (map? (:seon.source/file-digests cached)))
      (full-source-refresh! root store)
      (let [paths (->> changed-paths (map canonical-path) distinct sort vec)
            snapshot-before (current-source-snapshot)
            known-functions (seon.fn/manifest-function-symbols manifest)
            changes
            (mapv
             (fn [path]
               (let [file (io/file path)
                     current (seon.fn/artifact-by-path manifest path)
                     clojure-source? (and (.isFile file)
                                          (or (str/ends-with? path ".clj")
                                              (str/ends-with? path ".cljc")))
                     desired (when clojure-source?
                               (seon.fn/build-artifact
                                {:seon.fn.file/path path
                                 :seon.fn.file/first-party-functions
                                 known-functions}))]
                 (seon.fn/plan-file-change
                  {:seon.fn.change/status
                   (cond
                     (not (.exists file)) :deleted
                     (not clojure-source?) :schema-resource
                     current :modified
                     :else :added)
                   :seon.fn.change/current-artifact current
                   :seon.fn.change/desired-artifact desired})))
             paths)
            snapshot-after (current-source-snapshot)
            digest-after (:seon.source/digest snapshot-after)
            unreported-current?
            (unreported-source-current?
             (:seon.source/file-digests cached)
             (:seon.source/file-digests snapshot-after)
             paths)]
        (if (or (empty? paths)
                (not= snapshot-before snapshot-after)
                (not unreported-current?)
                (some #(= :full-rebuild (:seon.fn.change/action %)) changes))
          (full-source-refresh! root store)
          (let [desired-artifacts
                ;; Persist complete file analysis for the next edit. Only
                ;; `:seon.fn.change/rows` is the safe database delta.
                (mapv :seon.fn.change/artifact changes)
                next-manifest
                (seon.fn/replace-manifest-artifacts manifest desired-artifacts)
                _ (report-analysis-warnings! next-manifest)
                rows (into [] (mapcat :seon.fn.change/rows) changes)
                result
                (source/upsert!
                 {:seon.store/store store
                  :seon.source/expected-commit-id expected-commit
                  :seon.source/digest digest-after
                  :seon.program/rows rows
                  :seon.source/activation `derive-activation
                  :seon.db/process
                  [:seon.db.process/id boot-process-identity]})]
            (write-source-artifact! root
                                    (source-artifact result next-manifest
                                                     snapshot-after))
            result))))))

(defn refresh-source!
  "Publish the current source tree onto the one `current-src` branch.

  With changed paths, reuse the published manifest for safe same-identity
  upserts. Any deletion, new identity, schema resource, missing/stale artifact,
  or uncertain projection falls back to one complete scratch publication."
  {:malli/schema
   [:function
    [:=> [:cat :seon.boot/root] :seon.source/published]
    [:=> [:cat :seon.boot/root [:vector :string]] :seon.source/published]]}
  ([root]
   (refresh-source! root []))
  ([root changed-paths]
   (report-source-progress! "request accepted")
   (locking source-refresh-monitor
     (report-source-progress! "bootstrap configuration")
     (let [config (resolve-bootstrap {:seon.boot/root root})
           store-dir (:seon.boot/store-dir config)
           _ (report-source-progress! "store acquisition")
           held-store (acquire-root-store! store-dir)]
       (try
         (report-source-progress! "source build")
         (if (seq changed-paths)
           (incremental-source-refresh! root held-store changed-paths)
           (full-source-refresh! root held-store))
         (finally
           (release-root-store! store-dir)))))))

;;; ---------------------------------------------------------------------------
;;; Recovery — the pass that runs before anything resumes
;;; ---------------------------------------------------------------------------

(defn process-identity
  "This process's identity as a run holder: `<pid>-<start-millis>`.
  (pid, start-instant) is the process identity the whole system already
  uses; a bare pid is recyclable and a recycled pid claiming to hold a
  run is the one confusion recovery must not have. The run loop's
  handle should carry THIS value as `:seon.cluster.run/process`, so the
  holder a run names and the holder recovery judges are the same string."
  {:malli/schema [:=> [:cat :seon.boot/advertisement] :seon.cluster.run/process]}
  [advertisement]
  (str (:seon.boot/pid advertisement) "-"
       (inst-ms (:seon.boot/start-instant advertisement))))

(defn- recover-runs!
  "Settle every run held by a dead process, before anything resumes.
  BY FACT, NEVER BY CLOCK: a run whose holder is not in the live set is
  closed immediately, its custody and agent pointer are retracted, and
  BOTH the run and its dangling receipts — those carrying no terminal
  fact — get `interrupted-at` asserted (presence is the state; there is
  no status).

  The counts below are DIAGNOSTICS, never the answer. Recovery's own
  evidence is durable: `:seon.cluster.run/interrupted-at` on every run
  this pass cut, so a later process can still ask what the last one
  interrupted. Reporting only the counts is what made the 2026-08-08
  restart's honesty claim unfalsifiable — the interrupted run was
  indistinguishable by query from a normal close.
  No clock is consulted at all: this process just started, so on this
  branch every other holder is provably gone (one connection per
  branch, one process per store).

  Nothing here re-opens, re-plans, or re-executes. `recover-tx` is pure
  over the values it is handed and returns [] for a run needing
  nothing, so a clean boot commits nothing at all."
  [connection process]
  (let [db @connection
        now (java.util.Date.)
        open-runs (db/q '[:find [?run-id ...]
                         :where
                         [?run :seon.cluster.run/id ?run-id]
                         (not [?run :seon.cluster.run/closed-at _])]
                       db)
        ;; the decision moved INSIDE the transaction (custody revision,
        ;; Revision 4): `recover-call` reads each run's receipts at
        ;; transaction time, so this caller only names the open runs —
        ;; a stale-basis recovery stamping a settled receipt is
        ;; unrepresentable
        operations (into []
                         (mapcat
                          (fn [run-id]
                            (run/recover-tx
                             {:seon.cluster.run/id run-id
                              :seon.cluster.run/live-processes #{process}
                              :seon.cluster.run/now now})))
                         open-runs)]
    (when (seq operations)
      (require-committed!
       (db/transact! connection operations)
       {:seon.boot/population :seon.cluster.run/recovery}))
    {:seon.boot/recovered-runs (count open-runs)
     :seon.boot/recovery-operations (count operations)}))

;;; ---------------------------------------------------------------------------
;;; The armed layers — the fault consumer, the root agent, and the loop
;;; ---------------------------------------------------------------------------

;;; THE ROOT AGENT. One entity, ensured at boot through the same atomic
;;; id + namespace + cluster-ref transition every agent uses. It costs no
;;; process. It exists so escalation has somewhere honest to go — before it,
;;; the escalation dial had to ship absent because naming an agent that might
;;; not exist would have been a lie.
(def root-agent-id "root")

(defn- require-committed!
  [result offense]
  (when (:seon.error/kind result)
    (refused! "The cluster population transaction was refused."
              (assoc offense :seon.boot/result result)))
  result)

(defn ensure-cluster-entity!
  "Exactly converge the branch-local cluster entity's shared base set."
  {:malli/schema
   [:=> [:cat :seon.db/connection
         :seon.cluster/name
         :seon.db.process/id]
    :nil]}
  [connection cluster-name process]
  ;; Transaction metadata cannot resolve a lookup ref introduced by that same
  ;; transaction. Establish this process under the bootstrap provenance first;
  ;; subsequent cluster and agent transactions can then name it honestly.
  (when-not (db/q '[:find ?entity .
                   :in $ ?process
                   :where [?entity :seon.db.process/id ?process]]
                 @connection process)
    (require-committed!
     (db/transact!
      connection
      {:tx-data [{:seon.db.process/id process}]
       :tx-meta {:seon.db/process
                 [:seon.db.process/id boot-process-identity]}})
     {:seon.db.process/id process
      :seon.boot/population :seon.db.process/process}))
  (let [toolkit-namespaces (instruction/toolkit-namespaces @connection)
        desired {:seon.cluster/name cluster-name
                 :seon.cluster/config
                 [:seon.config/cluster cluster-name]
                 :seon.cluster/bootstrap-plan
                 [:seon.bootstrap.plan/id bootstrap/plan-id]
                 :seon.cluster/instructions
                 (mapv (fn [instruction-id]
                         [:seon.cluster.instruction/id instruction-id])
                       instruction/instruction-ids)
                 :seon.cluster/toolkit
                 (mapv (fn [namespace-name]
                         [:seon.ns/name namespace-name])
                       toolkit-namespaces)}
        expected-current
        {:seon.cluster/name cluster-name
         :seon.cluster/config {:seon.config/cluster cluster-name}
         :seon.cluster/bootstrap-plan
         {:seon.bootstrap.plan/id bootstrap/plan-id}
         :seon.cluster/instructions
         (into #{}
               (map (fn [instruction-id]
                      {:seon.cluster.instruction/id instruction-id}))
               instruction/instruction-ids)
         :seon.cluster/toolkit
         (into #{}
               (map (fn [namespace-name]
                      {:seon.ns/name namespace-name}))
               toolkit-namespaces)}
        current (some-> (db/pull @connection
                                '[:seon.cluster/name
                                  {:seon.cluster/config
                                   [:seon.config/cluster]}
                                  {:seon.cluster/bootstrap-plan
                                   [:seon.bootstrap.plan/id]}
                                  {:seon.cluster/instructions
                                   [:seon.cluster.instruction/id]}
                                  {:seon.cluster/toolkit
                                   [:seon.ns/name]}]
                                [:seon.cluster/name cluster-name])
                        (dissoc :db/id)
                        (update :seon.cluster/instructions set)
                        (update :seon.cluster/toolkit set))]
    (when-not (= expected-current current)
      (require-committed!
       (db/transact!
        connection
        {:tx-data
         (cond-> []
           current
           (conj [:db/retract
                  [:seon.cluster/name cluster-name]
                  :seon.cluster/instructions]
                 [:db/retract
                  [:seon.cluster/name cluster-name]
                  :seon.cluster/toolkit])
           true (conj desired))
         :tx-meta {:seon.db/process [:seon.db.process/id process]}})
       {:seon.cluster/name cluster-name
        :seon.boot/population :seon.cluster/cluster})))
  nil)

(defn ensure-entity-call
  "Create an absent agent inside the transaction; otherwise change nothing."
  {:malli/schema
   [:=> [:cat :seon.db/database-value
         :seon.cluster.run/process
         :inst
         :seon.cluster.agent/creation-request]
    :seon.store/transaction-data]}
  [db process now
   {agent-id :seon.cluster.agent/id
    namespace-name :seon.ns/name
    :as request}]
  (if (db/q '[:find ?agent .
             :in $ ?agent-id
             :where [?agent :seon.cluster.agent/id ?agent-id]]
           db agent-id)
    []
    (into (cluster.agent/creation-tx request)
          (bootstrap/seed-tx
           db
           {:seon.cluster.agent/id agent-id
            :seon.cluster/name (:seon.cluster/name request)
            :seon.ns/name namespace-name
            :seon.cluster.run/process process
            :seon.cluster.run/opened-at now}))))

(defn ensure-entity!
  "Create one absent agent atomically and return its durable useful identity.

  An existing agent resumes untouched, so the result is always derived from
  the committed database value rather than from the caller's request."
  {:malli/schema
   [:=> [:cat :seon.db/connection
         :seon.db.process/id
         :seon.cluster.agent/creation-request]
    [:or :seon.cluster.agent/creation-result :seon.error/value]]}
  [connection process request]
  (let [transaction-result
        (db/transact!
         connection
         {:tx-data [[:db.fn/call #'ensure-entity-call
                     process (java.util.Date.) request]]
          :tx-meta {:seon.db/process [:seon.db.process/id process]}})]
    (if (:seon.error/kind transaction-result)
      transaction-result
      ;; Read back from the connection because this operation needs only the
      ;; current committed value, not the rest of the transaction report.
      (let [database (db/db connection)
            agent-id (:seon.cluster.agent/id request)
            bootstrap-run-id (bootstrap/run-id agent-id)
            agent
            (db/pull database
                     '[:seon.cluster.agent/id
                       {:seon.cluster.agent/namespace [:seon.ns/name]}
                       {:seon.cluster.agent/cluster [:seon.cluster/name]}]
                     [:seon.cluster.agent/id agent-id])
            run-agent-id
            (db/q '[:find ?agent-id .
                    :in $ ?run-id
                    :where
                    [?run :seon.cluster.run/id ?run-id]
                    [?run :seon.cluster.run/agent ?agent]
                    [?agent :seon.cluster.agent/id ?agent-id]]
                  database bootstrap-run-id)
            namespace-name
            (get-in agent [:seon.cluster.agent/namespace :seon.ns/name])
            cluster-name
            (get-in agent [:seon.cluster.agent/cluster :seon.cluster/name])]
        (if (and namespace-name cluster-name (= agent-id run-agent-id))
          {:seon.cluster.agent/id agent-id
           :seon.ns/name namespace-name
           :seon.cluster/name cluster-name
           :seon.cluster.run/id bootstrap-run-id}
          {:seon.error/kind :seon.cluster.agent/creation-incomplete
           :seon.error/message
           (str "Agent " (pr-str agent-id)
                " committed without its namespace, cluster, or bootstrap run.")
           :seon.error/data {:seon.cluster.agent/id agent-id}})))))

(defn- seed-root-agent!
  "Ensure root and its agent-owned maintenance initialization exist."
  [connection cluster-name process]
  (require-committed!
   (ensure-entity!
    connection
    process
    {:seon.cluster.agent/id root-agent-id
     :seon.cluster/name cluster-name
     :seon.ns/name 'my.agents.root})
   {:seon.cluster.agent/id root-agent-id
    :seon.boot/population :seon.cluster.agent/agent})
  (require-committed!
   (db/transact!
    connection
    {:tx-data [[:db.fn/call #'schedule/root-maintenance-seed-call]]
     :tx-meta {:seon.db/process [:seon.db.process/id process]}})
   {:seon.cluster.agent/id root-agent-id
    :seon.boot/population :seon.schedule/root-maintenance}))

(defn- serve!
  "Bind the cluster's web view, or refuse LOUDLY.

  The last layer, deliberately: everything it renders must already
  stand, and a failure here must not be able to cost the run loop. It
  throws like any other tower layer, so the REPL survives and the
  degraded instance carries what stood — which is the honest behaviour
  for a port that is already taken, rather than a silent fallback to a
  no-UI-today state that nobody would notice until they opened a
  browser.

  THE PORT IS DERIVED FROM THE CLUSTER NAME, so a named cluster answers
  on the same port after every restart and a browser tab keeps working.
  A manifest dial still wins — an explicit port is somebody's decision
  and outranks a derivation. When the derived port is taken, the view
  binds an ephemeral one and says BOTH numbers, because a name collision
  must not look like a broken build and a moved bookmark must not fail
  silently."
  [connection cluster-name dials view]
  (let [wanted (or (:seon.config.web/port dials)
                   (web/derived-port cluster-name))
        served (web/start!
                ;; THE VIEW HALF comes from the armed layer, not from
                ;; here: the mult, the watched registration, and the
                ;; render wake channel all belong to the render proc's
                ;; pipeline, and the web service only taps and offers.
                ;; The coalesce floor is no longer passed — the PROC
                ;; reads it from the config facts per pass (F2 §1.2), so
                ;; a live dial change applies without restarting a tab.
                (merge {:seon.render.web/port wanted
                        :seon.store/connection-object connection
                        :seon.cluster.agent/id root-agent-id
                        :seon.sci.admit/caps (config/result-caps dials)}
                       (select-keys view
                                    [:seon.render.web/pages-mult
                                     :seon.render.web/registration
                                     :seon.render.web/latest-packages
                                     :seon.render.web/render-channel
                                     :seon.render/context-channel
                                     :seon.render.web/fault-channel
                                     :seon.cluster.run/process
                                     :seon.sci.eval/ctx
                                     :seon.config.eval/time-limit-ms
                                     :seon.config/on-core-error])))]
    (if-let [unavailable (:seon.render.web/wanted-port served)]
      (log/warn (str "seon " cluster-name " view: port " unavailable
                     " was taken, serving on "
                     (:seon.render.web/url served)
                     " instead — a bookmark on " unavailable
                     " will not reach this cluster"))
      (log/info (str "seon " cluster-name " view: "
                     (:seon.render.web/url served))))
    served))

(defn- tagged-run
  "The run the TAGGED agent points at and this process holds, or nil.
  Attribution is STRUCTURAL: an agent graph's fault arrives tagged with
  its agent (structural provenance from the error-channel join), so
  attribution is that agent's one held run — exact under concurrency,
  where the serial-era global query stopped being. That global query
  (`attributed-run`) is deleted at F2 §3.3."
  [db agent-id process]
  (db/q '[:find ?id .
         :in $ ?agent-id ?process
         :where
         [?agent :seon.cluster.agent/id ?agent-id]
         [?agent :seon.cluster.agent/run ?run]
         [?run :seon.cluster.run/process ?process]
         [?run :seon.cluster.run/id ?id]]
       db agent-id process))

(defn- bounded-fault-string
  [value limit]
  (if (and (string? value) (pos-int? limit) (< limit (count value)))
    (str (subs value 0 (dec limit)) "…")
    value))

(defn- over-fault-inline-ceiling?
  [value limit]
  (and (string? value) (pos-int? limit) (< limit (count value))))

(defn- bounded-fault-transaction
  [transaction-data inline-ceiling]
  (mapv
   (fn [operation]
     (let [message-capped?
           (over-fault-inline-ceiling?
            (:seon.error/message operation) inline-ceiling)]
       (cond-> operation
         message-capped?
         (update :seon.error/message bounded-fault-string inline-ceiling)

         message-capped?
         (assoc :seon.error/capped? true)

         (over-fault-inline-ceiling?
          (:seon.cluster.message/content operation) inline-ceiling)
         (update :seon.cluster.message/content
                 bounded-fault-string inline-ceiling))))
   transaction-data))

(defn- previously-reported-fault-signature?
  [database signature]
  (some?
   (db/q '[:find ?error .
           :in $ ?signature
           :where [?error :seon.error/signature ?signature]]
         database signature)))

(defn- commit-fault!
  "Commit one escaped Throwable as one durable fact per delivery.

  TOTAL, never throws. Returns `[fact outcome previously-reported?]`, deriving
  `fact` and its content signature before the transaction attempt. Every
  delivery reaches the writer; `previously-reported?` lets the Flow committer
  suppress only stderr/panic output for a signature already seen in facts.
  `outcome` is `:seon.flow/committed` or the transaction failure value.

  Everything it needs is read fresh: the dials from the config
  singleton, the attribution from the database value at the fault's
  own basis. A fault from an agent graph carries its agent as a
  structural tag (F1 §6) and attributes through `tagged-run`. An
  UNTAGGED fault — the cluster graph's own, from the armer or the
  render proc — attributes to NO run, and that is correct rather than
  missing: it is not a run's fault. The serial-era fallback query is
  gone (F2 §3.3). It goes through
  `db/transact!`, which never throws. The signature query and Flow's
  process-local signature set bound notification only; recurrence remains the
  query-derived count of committed facts."
  [connection cluster-name process caps fault]
  (try
    (let [db @connection
          dials (config/effective db cluster-name)
          source-fault fault
          agent-id (:seon.cluster.agent/id source-fault)
          run-id (when agent-id (tagged-run db agent-id process))
          dropped-count (::flow/dropped-fault-count source-fault)
          transaction-data
          (-> (error/commit-tx
               db
               (cond-> {:seon.error/source source-fault
                        :seon.error/id (str (random-uuid))
                        :seon.error/at (java.util.Date.)
                        :seon.error/process process
                        :seon.sci.admit/caps caps
                        :seon.error/basis-t (db/basis-t db)
                        :seon.config.error/recurrence-limit
                        (:seon.config.error/recurrence-limit dials)}
                 (:seon.config.error/escalate-to dials)
                 (assoc :seon.config.error/escalate-to
                        (:seon.config.error/escalate-to dials))
                 run-id (assoc :seon.cluster.run/id run-id)
                 agent-id (assoc :seon.cluster.agent/id agent-id)))
              (cond-> (pos-int? dropped-count)
                (update 0 assoc
                        :seon.error/dropped-fault-count dropped-count
                        :seon.error/dropped-fault-digest
                        (::flow/dropped-fault-digest source-fault)))
              (bounded-fault-transaction
               (:seon.config.eval.result/blob-threshold dials)))
          fact (first transaction-data)
          signature (:seon.error/signature fact)
          previously-reported?
          (previously-reported-fault-signature? db signature)]
      (try
        (let [result (db/transact! connection transaction-data)]
          [fact (if (db/database-value? (:db-after result))
                  ::flow/committed
                  result)
           previously-reported?])
        (catch Throwable failure
          [fact failure previously-reported?])))
    (catch Throwable failure
      ;; `error/commit-tx` is total. This last-resort shape is only for a
      ;; failure before its fact exists, so no content signature is available
      ;; for Flow to collapse honestly.
      [nil failure false])))

(defn- single-line-fault-text
  [value]
  (-> (str value)
      (str/replace "\r" " ")
      (str/replace "\n" " ")))

(defn- emit-core-fault!
  [configuration reported]
  (let [fact (::flow/fault-fact reported)
        outcome (::flow/commit-outcome reported)
        mode (::flow/core-error-mode reported)
        inline-ceiling
        (:seon.config.eval.result/blob-threshold configuration)
        message (bounded-fault-string
                 (or (:seon.error/message fact)
                     "A core fault could not be normalized.")
                 inline-ceiling)
        failure-message
        (when-not (= ::flow/committed outcome)
          (bounded-fault-string
           (or (when (map? outcome) (:seon.error/message outcome))
               (when (instance? Throwable outcome) (ex-message outcome))
               (str outcome))
           inline-ceiling))]
    (binding [*out* *err*]
      (println
       (str "SEON CORE FAULT"
            (when (= :panic mode) " (dev panic)")
            ": " (single-line-fault-text message)
            " [signature " (:seon.error/signature fact)
            (when failure-message
              (str "; durable record refused: "
                   (single-line-fault-text failure-message)))
            "]"))
      (flush)))
  nil)

(defn- loop-handle
  "The process resources and structural dials the loop proc carries.

  AI settings are deliberately absent: the `:call` branch resolves them
  from current cluster and agent facts once per turn, so config apply and
  per-agent overrides take effect on the next turn without rebuilding the
  graph."
  [connection cluster-name process ctx work-launcher
   wake-channel stream-channel context-channel completion]
  (let [dials (config/effective @connection cluster-name)]
    (cond-> {;; The one environment value this cluster's procs and
             ;; submissions carry. The handle's remaining entries are
             ;; process-local ports and structural dials, which are not
             ;; environment members.
             :seon.env/environment (env/of ctx)
             :seon.db/connection connection
              :seon.cluster/name cluster-name
              :seon.cluster.run/process process
              :seon.flow/work-launcher work-launcher
              :seon.sci.eval/ctx ctx
              :seon.cluster.wake/channel wake-channel
              ;; the cluster's ONE stream conn (F2 §2.1): sliding-1, so
              ;; the newest complete snapshot wins and the provider fold
              ;; is never parked by presentation
              :seon.cluster.loop/stream-channel stream-channel
              :seon.render/context-channel context-channel
              :seon.cluster.loop/completion completion
              :seon.cluster.loop/evaluate 'seon.sci.eval/evaluate
              :seon.sci.admit/caps (config/result-caps dials)
              :seon.config.eval/time-limit-ms
              (:seon.config.eval/time-limit-ms dials)
              :seon.config/on-core-error (:seon.config/on-core-error dials)
              :seon.config.error/recurrence-limit
              (:seon.config.error/recurrence-limit dials)
              :seon.config.eval.result/blob-threshold
              (:seon.config.eval.result/blob-threshold dials)
              ;; the conversation bound: every delivery a turn makes is
              ;; measured against it, so the loop must carry it the same
              ;; way it carries every other dial — derived from facts
              ;; once, here, never read at the call site
              :seon.config.message/max-chain
              (:seon.config.message/max-chain dials)}
      (:seon.config.error/escalate-to dials)
      (assoc :seon.config.error/escalate-to
             (:seon.config.error/escalate-to dials)))))

(defn projection-executor
  "Run IO work on the process executor with one cluster projection bound."
  {:malli/schema [:=> [:cat :seon.sci.eval/projection-state]
                  :seon.flow/executor]}
  [projection-state]
  (let [^Executor delegate (:io (root-executors))]
    (reify Executor
      (execute [_ command]
        (.execute
         delegate
         ^Runnable
         (reify Runnable
           (run [_]
             (schema/call-with-projection-state
              projection-state
              #(.run ^Runnable command)))))))))

(defn- cluster-graph-definition
  "The cluster's OWN small graph (F1 R7, F2 §1): armer, render, and the
  derived search-index proc — a schedule proc later. One graph per cluster,
  so the components that arm agents and derive pages have exactly the
  ping/error/pause uniformity every other proc has. The render proc's
  channels are external ports (created by `arm-agents!`, carried on the
  handle and the view), so the graph definition stays pure data."
  [handle routing view]
  (let [environment (env/of handle)]
    {:procs {:seon.cluster.agent/armer
             {:proc (flow/var-process
                     #'cluster.agent/armer-step :io
                     (env/carry {:seon.cluster.loop/cluster handle
                                 :seon.cluster.agent/routing routing}
                                environment))}
             :seon.render.web/render
             {:proc (flow/var-process
                     #'web/render-step :io
                     (env/carry (assoc view
                                       :seon.cluster.loop/cluster
                                       handle)
                                environment))}
             :seon.search/index
             {:proc (flow/var-process
                     #'search/index-step :io
                     (env/carry {:seon.search/index
                                 (:seon.search/index view)
                                 :seon.search/channel
                                 (:seon.search/channel view)
                                 :seon.search/completion
                                 (:seon.search/completion view)}
                                environment))}}
     :conns []
     :io-exec (:seon.flow/executor handle)}))

(defn- arm-agents!
  "Arm this cluster: the armer graph, fan-out, routing listener, prime.
  ARMED AND IDLE — the per-agent successor of the single run loop
  (F1). The armer's prime derives (agents in facts) − (armed set) and
  arms one graph per agent (R6: arm-all-at-boot); each arm ends with
  its own mailbox prime, whose first pass derives that agent's work
  from FACTS. A fresh cluster has no triggers, so boot makes zero
  model calls. A rebooted cluster never resumes interrupted work:
  recovery has already closed it. The first pass can start a new
  episode only for an unanswered durable message, including one that
  arrived before the crash.

  ORDER IS THE CONTRACT. The cluster graph starts first because the
  fan-out taps ITS channels; the routing listener comes after the
  fan-out because its fault channel is THE FAN-OUT'S; the armer prime
  comes LAST, after the listener, so an agent created between the
  prime's derivation and the listener's registration cannot exist —
  anything committed earlier is in the facts the prime's pass reads.
  This is the wiring whose absence meant every core fault in a live
  cluster was dropped by a sliding buffer nobody read."
  [instance connection cluster-name]
  (let [process (process-identity (:seon.boot/advertisement instance))
        armer-channel (async/chan (async/sliding-buffer 1))
        stream-channel (async/chan (async/sliding-buffer 1))
        context-channel (async/chan)
        completion (async/promise-chan)
        io-executor
        (projection-executor
         (:seon.sci.eval/projection-state (:seon.sci.eval/ctx instance)))
        handle
        (assoc
         (loop-handle connection cluster-name process
                      (:seon.sci.eval/ctx instance)
                      (:seon.flow/work-launcher instance)
                      armer-channel stream-channel context-channel completion)
         :seon.flow/executor io-executor)
        routing (cluster.agent/routing)
        ;; the render pipeline's external ports (F2 §1): the wake
        ;; channel route! delivers into, the pages channel the proc's
        ;; snapshots exit on (multed here, tapped per tab), the watched
        ;; registration the feed writes, and the proc's own orderly-stop
        ;; completion — all process-local, all free to lose
        render-channel (async/chan (async/sliding-buffer 1))
        search-channel (async/chan (async/sliding-buffer 1))
        pages-channel (async/chan (async/sliding-buffer 1))
        latest-packages (atom {})
        view {:seon.render.web/render-channel render-channel
              :seon.render/context-channel context-channel
              :seon.render.web/pages-channel pages-channel
              :seon.render.web/registration (atom {})
              :seon.render.web/latest-packages latest-packages
              :seon.render.web/completion (async/promise-chan)
              :seon.render.web/root-agent-id "root"
              :seon.search/index (:seon.search/index instance)
              :seon.search/channel search-channel
              :seon.search/completion (async/promise-chan)
              :seon.sci.eval/ctx (:seon.sci.eval/ctx handle)
              :seon.config.eval/time-limit-ms
              (:seon.config.eval/time-limit-ms handle)
              :seon.config/on-core-error
              (:seon.config/on-core-error handle)
              ;; THE ONE THING THE DATABASE CANNOT ANSWER, carried to
              ;; the page boundary rather than defaulted at it. On this
              ;; branch the live set is a singleton by construction —
              ;; one connection per branch, one process per store, the
              ;; same invariant `recover-runs!` reasons from — so the
              ;; holder a run names and the holder a rendered page
              ;; judges are the same string.
              :seon.cluster.run/process (:seon.cluster.run/process handle)}
        graph (flow.core/create-flow
               (cluster-graph-definition handle routing view))
        started (flow.core/start graph)
        _ (flow.core/resume graph)
        fanout (flow/start-error-fanout!
                {:seon.env/environment (env/of handle)
                 :seon.flow/graph graph
                 :seon.flow/started started
                 :seon.flow/fault-buffer-capacity 64
                 :seon.flow/monitor-buffer-capacity 64
                 :seon.flow/read-core-error-mode
                 (fn []
                   (or (:seon.config/on-core-error
                        (config/effective @connection cluster-name))
                       :record))
                 :seon.flow/commit-fault!
                 (fn [fault]
                   (commit-fault! connection cluster-name process
                                  (:seon.sci.admit/caps handle) fault))
                 :seon.flow/commit-drop!
                 (fn [dropped]
                   ;; The buffer converts overflow into a bounded synthetic
                   ;; fault. This callback runs on the committer proc, never
                   ;; on the thread that faulted.
                   (commit-fault! connection cluster-name process
                                  (:seon.sci.admit/caps handle) dropped))
                 :seon.flow/panic!
                 (fn [reported]
                   ;; FAIL LOUD IS NOT FALL DOWN (owner ruling): Flow calls
                   ;; this only for the first occurrence of a signature. The
                   ;; same callback reports a refused durable write once,
                   ;; including in record mode, without a second trace path.
                   (emit-core-fault! handle reported))})]
    ;; the fault channel joins the routing entry so every later arm
    ;; can tap its agent graph's errors into the ONE committer inbox
    (swap! routing assoc :seon.cluster.agent/fault-channel
           (:seon.flow/fault-channel fanout))
    ;; THE ROUTING DELIVERY (F1 §4): one listener per cluster, and its
    ;; own faults ride the same path as every other fault
    (wake/route! {:seon.cluster.wake/connection connection
                  :seon.cluster.wake/channels
                  (fn [] (cluster.agent/channels routing))
                  :seon.cluster.wake/fenced?
                  (fn [agent-eid channel]
                    (cluster.agent/fenced-route? routing agent-eid channel))
                  :seon.cluster.wake/armer-channel armer-channel
                  :seon.cluster.wake/render-channel render-channel
                  :seon.cluster.wake/search-channel search-channel
                  :seon.cluster.wake/fault-channel
                  (:seon.flow/fault-channel fanout)
                  :seon.cluster.wake/key :seon.cluster.agent/route})
    ;; ARM ALL AT BOOT (R6), synchronously, through the armer's ONE
    ;; derivation. The listener is already registered, so every arm's
    ;; mailbox prime and every commit concurrent with it are conserved.
    ;; Direct invocation publishes readiness: a returned instance is
    ;; armed, while the running proc owns every later wake.
    (cluster.agent/armer-step
     (cluster.agent/armer-step
      {:seon.cluster.loop/cluster handle
       :seon.cluster.agent/routing routing})
     ::cluster.agent/arm
     ::cluster.agent/boot)
    {:seon.cluster.loop/cluster handle
     :seon.flow/graph graph
     :seon.flow/error-fanout fanout
     :seon.cluster.agent/routing routing
     ;; the view half `serve!` hands to the web service: one mult over
     ;; the proc's pages out-port, the shared registration, and the
     ;; wake channel a freshly opened tab offers into
     :seon.render.web/view
     (assoc view
            :seon.render.web/pages-mult (async/mult pages-channel)
            :seon.render.web/fault-channel
            (:seon.flow/fault-channel fanout))
     :seon.search/completion (:seon.search/completion view)}))

(defn- disarm-agents!
  "Unwind the armed layers of ONE instance, newest first.
  The routing LISTENER goes first so nothing new is routed while the
  graphs unwind. An explicit armer quiescence event then proves every
  earlier arm wake has settled and closes that input, while the render
  proc remains available to active agent turns. Each agent graph is
  then joined at its own turn proc's completion; only after those turns
  finish does the cluster graph stop and the fan-out detach its taps.
  Each layer is released only if it stands — a degraded instance
  disarms the same way.

  ORDERLY STOP WAITS FOR THE ACTIVE PASS. `flow/stop` only queues
  `::flow/stop`; it does not join the proc (`flow/impl.clj:174-183`).
  Each proc therefore publishes its own completion from the stop
  transition, which Flow invokes only after the active transform
  returns. This wait honestly includes a seconds-long model call and
  any transaction it starts; only then may the branch connection be
  released. There is no sleep or deadline standing in for that event.

  This is orderly-stop behavior only. A process kill cannot await a
  completion and may lose an in-flight transaction by design; the crash
  model owns that row and the next boot settles its durable wreckage."
  [instance]
  ;; the VIEW goes first: it is the newest layer and the only one
  ;; holding sockets belonging to somebody outside this process
  (when-let [served (:seon.render.web/served instance)]
    (web/stop! served))
  (when-let [handle (:seon.cluster.loop/cluster instance)]
    (wake/unlisten! {:seon.cluster.wake/connection
                     (:seon.db/connection handle)
                     :seon.cluster.wake/key :seon.cluster.agent/route}))
  (when-let [handle (:seon.cluster.loop/cluster instance)]
    (let [armer-channel (:seon.cluster.wake/channel handle)
          quiesced (async/promise-chan)]
      (when-not (async.protocols/closed? armer-channel)
        (when-not (async/>!! armer-channel
                             {::cluster.agent/quiesce quiesced})
          (throw
           (ex-info "The cluster armer input closed before quiescence."
                    {:seon.error/kind
                     :seon.cluster.agent/armer-quiescence-undeliverable})))
        (when-not (= ::cluster.agent/quiesced (async/<!! quiesced))
          (throw
           (ex-info "The cluster armer did not publish quiescence."
                    {:seon.error/kind
                     :seon.cluster.agent/armer-quiescence-undeliverable})))
        ;; Closure is the observable completion fact a later stop derives
        ;; from. Publish it only after the armer acknowledged quiescence.
        (async/close! armer-channel))))
  (when-let [routing (:seon.cluster.agent/routing instance)]
    (doseq [agent-id (sort (keys (:seon.cluster.agent/armed @routing)))]
      (cluster.agent/disarm! {:seon.cluster.agent/id agent-id
                              :seon.cluster.agent/routing routing})))
  (when-let [graph (:seon.flow/graph instance)]
    (flow.core/stop graph)
    ;; BOTH cluster-graph procs are joined at their own completions —
    ;; `flow/stop` only queues `::flow/stop`, so a render pass holding
    ;; the branch connection would otherwise still be deriving when the
    ;; connection is released
    (async/<!! (:seon.cluster.loop/completion
                (:seon.cluster.loop/cluster instance)))
    (some-> (get-in instance [:seon.render.web/view
                              :seon.render.web/completion])
            async/<!!)
    (some-> (:seon.search/completion instance) async/<!!))
  ;; A degraded boot can open the index before the graph stands. Then no proc
  ;; owns its close transition yet, so this layer releases it directly.
  (when (and (:seon.search/index instance)
             (nil? (:seon.flow/graph instance)))
    (search/close! (:seon.search/index instance)))
  (when-let [fanout (:seon.flow/error-fanout instance)]
    (flow/stop-error-fanout! fanout))
  (when-let [handle (:seon.cluster.loop/cluster instance)]
    (some-> (:seon.cluster.loop/stream-channel handle) async/close!)
    (some-> (:seon.render/context-channel handle) async/close!))
  ;; the render pipeline's own ports, after the proc that reads them has
  ;; published its completion: a tab still looping on a tap sees its tap
  ;; close and falls out of the loop
  (when-let [view (:seon.render.web/view instance)]
    (async/close! (:seon.render.web/render-channel view))
    (async/close! (:seon.render.web/pages-channel view)))
  nil)

(defn- stand-cluster-runtime!
  [instance publish! compiled-config connection cluster-name config
   projection-state base-ctx]
  (schema/call-with-projection-state
   projection-state
   (fn []
     (let [recovery (recover-runs!
                     connection
                     (process-identity (:seon.boot/advertisement instance)))
           instance (publish! (merge instance recovery))
           instance (publish!
                     (assoc instance
                            :seon.boot/config-result
                            (config/apply-compiled! connection compiled-config)))
           process (process-identity (:seon.boot/advertisement instance))
           _ (ensure-cluster-entity! connection cluster-name process)
           _ (seed-root-agent! connection cluster-name process)
           search-path (:seon.search/path
                        (cluster-paths (:seon.boot/root config) cluster-name))
           instance (publish!
                     (assoc instance :seon.search/index
                            (search/open! connection search-path)))
           bare-ctx
           (if base-ctx
             (sci.eval/fork-cluster-ctx
              base-ctx @connection connection projection-state)
             (sci.eval/cluster-ctx
              @connection connection projection-state))
           boot-dials (config/effective @connection cluster-name)
           ;; The launcher's own graph belongs to this cluster too, but the
           ;; launcher cannot be a member of the environment its own procs
           ;; carry. Its graph therefore receives this cluster's environment
           ;; up to the facts layer; every LATER carrier gets the complete
           ;; one built immediately below.
           pre-graph-environment
           (env/refuse-incomplete-environment!
            (env/environment
             {:seon.boot/cluster-name cluster-name
              :seon.db/connection connection
              :seon.schema/projection
              (:seon.schema/projection @projection-state)
              :seon.sci.admit/caps (config/result-caps boot-dials)
              :seon.config/on-core-error
              (:seon.config/on-core-error boot-dials)}))
           work-launcher
           (flow/start-work-launcher!
            {:seon.env/environment pre-graph-environment
             ::flow/configuration
             (select-keys boot-dials flow/flow-workload-attributes)})
           ;; Boot's 0->1 constructor. A missing layer refuses HERE with a
           ;; flat error naming it, so no partial environment is ever handed
           ;; to a proc, a submission, or a fork.
           environment
           (env/refuse-incomplete-environment!
            (env/boot-environment
             (assoc pre-graph-environment
                    :seon.flow/work-launcher work-launcher)))
           ;; The environment rides the ctx by `assoc` — never through
           ;; `sci/init` options, which silently drop unknown keys. Every
           ;; per-turn `sci/fork` and every closure built inside it then
           ;; carries this cluster's environment across any thread by
           ;; construction.
           instance (publish!
                     (assoc instance :seon.sci.eval/ctx
                            (env/carry bare-ctx environment)))
           instance (publish!
                     (assoc instance :seon.flow/work-launcher work-launcher))
           instance (publish!
                     (merge instance
                            (arm-agents! instance connection cluster-name)))
           dials (config/effective @connection cluster-name)
           served (serve! connection cluster-name dials
                          (:seon.render.web/view instance))
           advertisement (assoc (:seon.boot/advertisement instance)
                                :seon.render.web/url
                                (:seon.render.web/url served)
                                :seon.render.web/port
                                (:seon.render.web/port served))]
       (write-advertisement!
        (cluster-paths (:seon.boot/root config) cluster-name)
        advertisement)
       (publish! (assoc instance
                        :seon.render.web/served served
                        :seon.boot/advertisement advertisement))))))

(defn- stack-tower!
  "Stack store → source commit → fork → connection → config onto `instance`.
  Each layer is assoc'd as it stands, and the whole value is republished
  to the registry at every step, so the instance a failure carries is
  exactly what stands: absence marks where boot stopped."
  [instance publish! compiled-config]
  (let [config (:seon.boot/config instance)
        cluster-name (:seon.boot/cluster-name config)
        keep-history?
        (get-in compiled-config
                [:seon.config/effective :seon.config.db/keep-history?])
        store (acquire-root-store! (:seon.boot/store-dir config) keep-history?)
        instance (publish! (assoc instance :seon.store/store store))
        store-id (get-in @(:seon.store/connection-object store)
                         [:config :store :id])
        cluster-branch (registry/cluster-branch cluster-name)
        existing-cluster? (contains? (registry/roster store) cluster-branch)
        source-base (when-not existing-cluster? (source-base! store))
        start-permit (gc-guard/try-reachability-permit! store-id :roster)
        _ (when-let [kind (:seon.error/kind start-permit)]
            (throw
             (ex-info
              (if (= :sweep-in-progress kind)
                "A reachability sweep is in progress; retry start later."
                "Reachability publication is currently unavailable; retry start later.")
              start-permit)))
        forked
        (try
          (if existing-cluster?
            {:seon.store/branch cluster-branch
             :seon.cluster/created? false}
            (registry/ensure-cluster!
             {:seon.store/store store
              :seon.boot/cluster-name cluster-name
              :seon.source/commit-id
              (:seon.source/commit-id source-base)
              :datahike.gc-guard/reachability-permit start-permit}))
          (finally
            (gc-guard/release-reachability-permit! start-permit)))
        provisional-connection
        (store/open-branch! store (:seon.store/branch forked))
        instance (publish!
                  (assoc instance
                         :seon.boot/cluster-connection provisional-connection))
        ;; Activation is a property of this cluster's own immutable database
        ;; value. A newly forked branch was preflighted before creation; an
        ;; older sovereign branch is checked here without consulting files or
        ;; another cluster's projection.
        _ (require-activation! @provisional-connection)
        ;; Boot never reads or indexes the file tree. This gate precedes schema
        ;; accretion, recovery, config, and arming:
        ;; an incoherent program graph gets no runtime semantics. A complete
        ;; older corpus remains a legitimate sovereign world.
        _ (require-coherent-program! provisional-connection cluster-name)
        ;; A branch may predate this process's additive schema population.
        ;; Install it before recovery or config can transact a newly added
        ;; attribute. This is the same population that creates `current-src`;
        ;; converged reopens issue zero transactions.
        _ (accrete-schema-population! provisional-connection cluster-name)
        projection (schema/projection-from-database @provisional-connection)
        projection-state
        (sci.eval/projection-state @provisional-connection projection)
        ;; Datahike's writer is a core.async state machine created with the
        ;; connection. Create the durable cluster connection while its one
        ;; advanceable projection state is bound, so in-writer transaction
        ;; functions decode against the same cluster basis as their caller.
        _ (store/release-branch! provisional-connection)
        connection
        (schema/call-with-projection-state
         projection-state
         #(store/open-branch! store (:seon.store/branch forked)))
        instance (publish!
                  (assoc instance :seon.boot/cluster-connection connection))]
    (stand-cluster-runtime!
     instance publish! compiled-config connection cluster-name config
     projection-state (:seon.sci.eval/ctx source-base))))

(defn start!
  "Start one cluster instance in this JVM, REPL FIRST, then the tower.
  Order: resolve paths and create directories → open the io-prepl
  socket server and write the advertisement (real bound port, pid,
  start-instant — the REPL is live from here NO MATTER WHAT) → open the
  process-root store (first instance; siblings reuse the held store) →
  snapshot the already-published `current-src` commit when the cluster branch
  is absent → registry/ensure-cluster! → store/open-branch! → require
  one recorded source
  digest and a coherent program graph (a complete older corpus is
  sovereign and allowed) → accrete the current schema
  population → config/apply-compiled! with the shipped defaults → return the complete
  instance. A later-layer failure THROWS
  with the DEGRADED INSTANCE in the ex-data under :seon.boot/instance
  (tower fields absent from the failure point) while the REPL and
  advertisement survive; the instance stays registered, and the caller
  stops it through that carried value like any other. Two instances in one JVM share the root store and executors,
  nothing else. Refuses a second start! for a cluster this JVM already
  has running."
  {:malli/schema [:=> [:cat :seon.boot/start-request] :seon.boot/instance]}
  [request]
  (let [began (System/nanoTime)
        config-request
        (select-keys request
                     [:seon.config/manifest :seon.config/environment])
        config
        (resolve-bootstrap
         (apply dissoc request (keys config-request)))
        cluster-name (:seon.boot/cluster-name config)
        compiled-config
        (config/compile-manifest
         (assoc config-request :seon.boot/cluster-name cluster-name))
        paths (cluster-paths (:seon.boot/root config) cluster-name)
        repository-root (or (System/getProperty "seon.repository.root")
                            (System/getProperty "user.dir"))
        managed-root (operator-root (:seon.boot/root config))
        claim-here? (not= "true" (System/getProperty "seon.operator.claimed"))
        _ (when claim-here?
            (operator.state/claim-root! repository-root managed-root nil
                                        cluster-name))
        _ (warn-low-space! managed-root (:seon.config/effective compiled-config))
        server-symbol (server-name cluster-name)]
    (create-directories! config paths)
    (when claim-here?
      (operator.state/mark-root-created! repository-root managed-root))
    (reserve-cluster! cluster-name)
    (let [server (volatile! nil)
          ;; LAYER 0 — the REPL. Its own failure unwinds completely
          ;; (socket closed, reservation released); once it succeeds,
          ;; nothing below may take it down.
          instance
          (try
            (let [prepl-server
                  (clojure.core.server/start-server
                   {:accept 'seon.cluster/mcp-io-prepl
                    :args [cluster-name
                           (:seon.config/effective compiled-config)]
                    :port (:seon.boot/prepl-port config)
                    :name server-symbol
                    :address (:seon.boot/prepl-host config)})
                  _ (vreset! server prepl-server)
                  advertisement
                  (merge
                   {:seon.boot/cluster-name cluster-name
                    :seon.boot/prepl-host (:seon.boot/prepl-host config)
                    :seon.boot/prepl-port (.getLocalPort prepl-server)}
                   (cluster.process/current-identity))
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
                (clojure.core.server/stop-server server-symbol))
              (release-reservation! cluster-name)
              (throw throwable)))
          ;; the registry always holds the instance AS IT STANDS, so a
          ;; stop! of the carried value and a stop! of the registered
          ;; one release the same resources
          published (volatile! instance)
          progressed (volatile! (boot-phase instance))
          _ (*boot-progress!* @progressed)
          publish! (fn [value]
                     (vreset! published value)
                     (swap! running-instances
                            (fn [instances]
                              (if (contains? instances cluster-name)
                                (assoc instances cluster-name value)
                                instances)))
                     (let [phase (boot-phase value)]
                       (when (not= phase @progressed)
                         (vreset! progressed phase)
                         (*boot-progress!* phase)))
                     value)]
      (try
        ;; the elapsed measure belongs to boot, not to whoever prints
        ;; the banner: a caller timing `start!` from outside measures
        ;; its own require time too
        (let [stood (stack-tower! instance publish! compiled-config)]
          (publish! (assoc stood :seon.boot/ready-ms
                           (quot (- (System/nanoTime) began) 1000000))))
        (catch Throwable failure
          ;; LOUD, and the REPL survives: the degraded instance rides the
          ;; refusal so the caller can diagnose over the live socket and
          ;; stop it like any other instance.
          (throw (ex-info
                  (str "The cluster instance failed above the REPL: "
                       (ex-message failure))
                  {:seon.error/kind :seon.boot/refused
                   :seon.boot/offense {:seon.boot/cluster-name cluster-name}
                   :seon.boot/instance @published}
                  failure)))))))

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

(defn readiness
  "The boot banner, DERIVED from a started instance. Never duplicated.

  Everything here is read back out of the instance and the database it
  points at, so the banner cannot say something the system does not.
  That is the whole discipline: a banner assembled from variables the
  boot path happened to have in hand drifts the first time a layer
  changes, and a banner nobody can regenerate is a log line rather than
  a readout.

  Returns ordinary data. A caller that wants one field takes one field."
  {:malli/schema [:=> [:cat :seon.boot/instance] :seon.boot/readiness]}
  [instance]
  (let [connection (:seon.boot/cluster-connection instance)
        db (some-> connection deref)
        served (:seon.render.web/served instance)
        advertisement (:seon.boot/advertisement instance)
        agents (if db
                 (or (db/q '[:find (count ?a) . :where
                            [?a :seon.cluster.agent/id _]] db)
                     0)
                 0)
        found (if db
                (problems/problems
                 db {:seon.cluster.run/live-processes
                     #{(process-identity advertisement)}})
                {})]
    (cond-> {:seon.boot/cluster-name (:seon.boot/cluster-name advertisement)
             :seon.boot/pid (:seon.boot/pid advertisement)
             :seon.boot/prepl-port (:seon.boot/prepl-port advertisement)
             :seon.cluster.agent/count agents
             ;; `{}` when healthy — the same value `problems` derives, so
             ;; the banner screams exactly when the facts do and nobody
             ;; maintains a second notion of "fine"
             :seon.problems/problems found}
      served (assoc :seon.render.web/url (:seon.render.web/url served))
      (:seon.render.web/wanted-port served)
      (assoc :seon.render.web/wanted-port
             (:seon.render.web/wanted-port served))
      (:seon.boot/recovered-runs instance)
      (assoc :seon.boot/recovered-runs
             (:seon.boot/recovered-runs instance))
      (:seon.boot/ready-ms instance)
      (assoc :seon.boot/ready-ms (:seon.boot/ready-ms instance)))))

(defn banner
  "`readiness` as the block a person reads at a terminal.

  THE URL LEADS, because it is the one thing somebody is about to use.
  A fallback port is called out on its own line rather than folded into
  the URL line — a bookmark that stopped working deserves a sentence,
  not a number somebody has to notice."
  {:malli/schema [:=> [:cat :seon.boot/readiness] :string]}
  [{:seon.render.web/keys [url wanted-port]
    :seon.problems/keys [problems]
    :as ready}]
  (str/join
   "\n"
   (cond-> [(str "seon " (:seon.boot/cluster-name ready) " ready")
            (str "  view         " (or url "(not serving)"))]
     wanted-port
     (conj (str "  port         " wanted-port
                " was taken — a bookmark on it will not reach this cluster"))
     true
     (into [(str "  repl         " (:seon.boot/prepl-port ready)
                 "  (pid " (:seon.boot/pid ready) ")")
            (str "  agents       " (:seon.cluster.agent/count ready))
            (str "  problems     " (if (empty? problems)
                                     "none"
                                     (str (count problems) " families — "
                                          (str/join ", " (sort (map name (keys problems)))))))])
     ;; only when there WAS wreckage: a zero here is noise on every
     ;; healthy boot, and noise is what makes a banner unread
     (pos? (or (:seon.boot/recovered-runs ready) 0))
     (conj (str "  recovered    " (:seon.boot/recovered-runs ready)
                " run(s) from a dead process"))
     (:seon.instrument/instrumented ready)
     (conj (str "  instrumented " (:seon.instrument/instrumented ready)
                " vars"))
     (:seon.boot/ready-ms ready)
     (conj (format "  boot         %.2fs"
                   (/ (double (:seon.boot/ready-ms ready)) 1000))))))

(defn stop!
  "Stop exactly THIS instance, instance-addressed never name-addressed.
  Unwinds the tower in reverse: releases ITS cluster branch connection,
  drops its hold on the process-root store — the LAST instance out
  releases the store and with it the lifetime flock, a sibling's hold
  keeps it open — then closes ITS prepl server socket and deletes ITS
  advertisement. The database resources go FIRST so a failure to release
  one restores this exact instance to the registry with its REPL up for
  diagnosis and a later stop retry. A DEGRADED instance stops the same
  way: absence marks what was never built, so each layer is released
  only if it stands. A delayed stop! of an old instance value
  must not touch a replacement started under the same cluster name (the
  replacement's socket, advertisement, and registry entry all survive).
  Idempotent — stopping a stopped instance is a no-op returning nil.
  Never touches the shared root executors."
  {:malli/schema [:=> [:cat :seon.boot/instance] :nil]}
  [instance]
  (let [config (:seon.boot/config instance)
        cluster-name (:seon.boot/cluster-name config)
        marker (Object.)]
    (when (claim-stop! cluster-name instance marker)
      (try
        ;; the armed layers first: nothing new may be derived while the
        ;; database resources are being released
        (disarm-agents! instance)
        (some-> (:seon.flow/work-launcher instance)
                flow/stop-work-launcher!)
        (when-let [connection (:seon.boot/cluster-connection instance)]
          (d/release connection))
        (when (:seon.store/store instance)
          (release-root-store! (:seon.boot/store-dir config)))
        (when-not
         (clojure.core.server/stop-server (server-name cluster-name))
          (when-not
           (.isClosed ^java.net.ServerSocket (:seon.boot/prepl-server instance))
            (refused!
             "The cluster's registered prepl server is unavailable."
             {:seon.boot/cluster-name cluster-name})))
        (let [advertisement-file
              (io/file
               (:seon.boot/advertisement-file
                (cluster-paths (:seon.boot/root config) cluster-name)))]
          (when (= (:seon.boot/advertisement instance)
                   (try
                     (edn/read-string (slurp advertisement-file))
                     (catch Throwable _ nil)))
            (.delete advertisement-file)))
        (swap! running-instances
               (fn [instances]
                 (if (identical? marker (get instances cluster-name))
                   (dissoc instances cluster-name)
                   instances)))
        (catch Throwable failure
          ;; A resource that failed to release remains the addressed
          ;; generation. Restoring the exact instance makes stop retryable
          ;; while its live REPL and registry fence exclude a replacement.
          (swap! running-instances
                 (fn [instances]
                   (if (identical? marker (get instances cluster-name))
                     (assoc instances cluster-name instance)
                     instances)))
          (throw failure)))))
  nil)

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
             (cluster.process/live?
              (select-keys advertisement
                           [:seon.boot/pid :seon.boot/start-instant])))
        advertisement))
    (catch Throwable _
      nil)))
