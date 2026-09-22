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
            [seon.error.refusal :as refusal]
            [seon.turn :as turn]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test.check.generators :as gen]
            [datahike.api :as d]
            [datahike.gc-guard :as gc-guard]
            [seon.bootstrap :as bootstrap]
            [seon.cluster.source :as source]
            [seon.cluster.export :as export]
            [seon.cluster.registry :as registry]
            [seon.cluster.store :as store]
            [clojure.string :as str]
            [seon.config :as config]
            [seon.db :as db]
            [seon.env :as env]
            [seon.flow :as flow]
            [seon.fn :as seon.fn]
            [seon.fs :as fs]
            [seon.instrument :as instrument]
            [seon.id :as id]
            [seon.test.cache :as test.cache]
            [seon.operator.runtime :as operator.runtime
             :refer [root-store-holder running-instances]]
                        [seon.oversight :as oversight]
            [seon.problems :as problems]
            [seon.render :as render]
            [seon.render.data :as render.data]
            [seon.print :as print]
            [seon.program :as program]
            [seon.render.value :as render.value]
            [seon.schedule :as schedule]
            [seon.sci.admit :as admit]
            [seon.render.web :as web]
            [seon.sci.eval :as sci.eval]
            [taoensso.timbre :as log]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.schema.edn :as schema.edn]
            [malli.core :as m]
            [malli.registry :as mr]
            [seon.schema.internal :as internal])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file CopyOption Files InvalidPathException LinkOption Paths
            StandardCopyOption]
           [java.util Date]
           [java.util.concurrent Executor TimeUnit]
           [java.util.concurrent.locks ReentrantLock]))

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

(def ^:dynamic ^:private *source-refresh-holder-token*
  nil)

(declare source-refresh-holder)

(defn- report-source-progress!
  {:malli/schema [:=> [:cat :string] :nil]}
  [phase]
  (when *source-refresh-holder-token*
    (swap! source-refresh-holder
           (fn [holder]
             (if (identical? *source-refresh-holder-token* (::holder-token holder))
               (assoc holder
                      :seon.operator.lock/phase phase
                      :seon.operator.lock/progress-at (Date.))
               holder))))
  (*source-progress!* phase)
  ;; Clojure prepl writes through PrintWriter, which records an IOException
  ;; instead of throwing it. A departed observer must not leave queued
  ;; publications doing work after their lifecycle caller has timed out.
  (when (and (instance? java.io.PrintWriter *out*)
             (.checkError ^java.io.PrintWriter *out*))
    (let [message (str "Source publication observer closed in phase " phase ".")]
      (throw (ex-info message
                      {:seon.error/at (Date.)
                       :seon.error/layer :seon.cluster/publication
                       :seon.error/operation 'seon.cluster/report-source-progress!
                       :seon.error/message message
                       :seon.cluster/source-observer-closed phase
                       :seon.source/progress phase}))))
  nil)

(defn- report-analysis-warnings!
  "Report finding differences only for the files this publication replaced."
  {:malli/schema [:=> [:cat [:maybe [:vector :seon.lint/finding]]
                       [:vector :seon.lint/finding]] :nil]}
  [previous findings]
  (let [current (set findings)
        before (when previous (set previous))]
    (report-source-progress!
     (str "findings in analyzed files: " (count current)
          (if previous
            (str "; added=" (count (set/difference current before))
                 "; resolved=" (count (set/difference before current)))
            "; delta unavailable: no previous publication"))))
  nil)

(defn socket-server?
  "True for the java.net.ServerSocket an io-prepl listens on."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [value]
  (instance? java.net.ServerSocket value))

(schema/register-core-predicate! 'seon.cluster/socket-server?
                                 socket-server?)

(defn cluster-name?
  "True when `value` is one valid relative path segment.

  This is the ruled 2026-08-13 input narrowing: cluster names contain no path
  separator and are neither `.` nor `..`, so cluster-path derivation cannot
  escape or alias the cluster root."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [value]
  (and (string? value)
       (not (empty? value))
       (not (#{"." ".."} value))
       (not (str/includes? value "/"))
       (not (str/includes? value "\\"))
       (try
         (let [path (Paths/get value (make-array String 0))]
           (and (not (.isAbsolute path))
                (= 1 (.getNameCount path))))
         (catch InvalidPathException _
           false))))

(def cluster-name-generator
  (gen/one-of
   [(gen/fmap (fn [characters] (apply str characters))
              (gen/vector
               (gen/elements
                (seq "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"))
               1 48))
    (gen/elements ["default" "alpha.beta"])]))

(schema/register-core-predicate! 'seon.cluster/cluster-name?
                                 cluster-name?)

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

(defn format-ai
  "Format the terminal description of one already-read cluster."
  {:malli/schema [:=> [:cat [:or :seon.render/unit :seon.error/value]]
                  [:or :nil :string :seon.error/value]]}
  [unit]
  (if (or (:seon.db/invalid-read unit) (:seon.schema/expected-value unit))
    unit
    (when-let [name (:seon.cluster/name unit)]
      (let [database (:seon.db/db unit)
          config-name (or (get-in unit [:seon.cluster/config
                                        :seon.config/cluster])
                          (ref-identity database
                                        (:seon.cluster/config unit)
                                        :seon.config/cluster))
          instructions (count (:seon.cluster/instructions unit))
          toolkit (count (:seon.cluster/toolkit unit))]
        (str "Cluster " name ".\n"
             "Configuration " (or config-name "is connected")
             "; " instructions " shared instruction"
             (when-not (= 1 instructions) "s")
             " and " toolkit " toolkit namespace"
             (when-not (= 1 toolkit) "s") ".")))))

(defn render-ai
  "`:seon.render/ai` — source reading one exact cluster before formatting it."
  {:malli/schema [:=> [:cat :seon.render/unit] [:maybe :seon.render/source]]}
  [unit]
  (when-let [name (:seon.cluster/name unit)]
    (pr-str
     (list `format-ai
           (list 'seon.db/pull
                 (list 'quote
                       [:seon.cluster/name
                        {:seon.cluster/config [:seon.config/cluster]}
                        :seon.cluster/instructions
                        :seon.cluster/toolkit])
                 [:seon.cluster/name name])))))

(defn render-html
  "`:seon.render/html` — one readable cluster card."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:maybe :seon.render/hiccup]]}
  [unit]
  (when-let [name (:seon.cluster/name unit)]
    (let [database (:seon.db/db unit)
          config-name (ref-identity database
                                    (:seon.cluster/config unit)
                                    :seon.config/cluster)]
      [:article {:class "seon-family-entry seon-cluster-entry"}
       [:h3 (str "Cluster " name)]
       [:dl
        [:div [:dt "Configuration"] [:dd (str (or config-name "Connected"))]]
        [:div [:dt "Shared instructions"]
         [:dd (str (count (:seon.cluster/instructions unit)))]]
        [:div [:dt "Toolkit namespaces"]
         [:dd (str (count (:seon.cluster/toolkit unit)))]]]])))



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
  "Mark the next PREPL return with explicit projection and read-only intent.
  Unspecified intent conservatively announces possible runtime changes."
  {:malli/schema [:function
                  [:=> [:cat] :nil]
                  [:=> [:cat [:or :boolean
                              [:map
                               [:seon.dev.mcp/evaluation? {:optional true} :boolean]
                               [:seon.dev.mcp/project? {:optional true} :boolean]
                               [:seon.dev.mcp/read-only? {:optional true} :boolean]]]] :nil]]}
  ([] (project-next-prepl-value! false))
  ([request]
   (.set mcp-projection
         (if (map? request)
           request
           {:seon.dev.mcp/evaluation? request}))
   nil))

(defn- consume-mcp-projection!
  []
  (let [project? (.get mcp-projection)]
    (.remove mcp-projection)
    project?))

(defn- mcp-instance
  [cluster-name]
  (get @running-instances cluster-name))

(defn- mcp-effective
  {:malli/schema [:=> [:cat :seon.boot/cluster-name [:or :nil :seon.config/effective]]
                  [:or :nil :seon.config/effective :seon.config/error
                   :seon.db/error-result :seon.schema/validation-refusal]]}
  [cluster-name bootstrap-effective]
  (let [instance (mcp-instance cluster-name)
        connection (:seon.boot/cluster-connection instance)
        projection-state
        (get-in instance
                [:seon.sci.eval/ctx :seon.sci.eval/projection-state])]
    (if connection
      (if projection-state
        (schema/call-with-projection-state
         projection-state
         #(config/effective (db/db connection) cluster-name))
        {:seon.error/at (Date.)
          :seon.error/layer :seon.dev.mcp/configuration
          :seon.error/operation 'seon.cluster/mcp-effective
          :seon.schema/expected-value :seon.schema/projection
          :seon.schema/refused-value :seon.error/unknown
          :seon.error/message "The MCP config read has no cluster projection state."
          :seon.error/expected :seon.sci.eval/projection-state
          :seon.error/data {:seon.boot/cluster-name cluster-name}})
      bootstrap-effective)))

(defn- nil-deref?
  {:malli/schema [:=> [:cat :map] :boolean]}
  [cause]
  (= 'clojure.core$deref_future (first (:at cause))))

(defn- first-seon-frame
  {:malli/schema [:=> [:cat [:sequential :seon.error/frame]]
                  [:maybe :seon.error/frame]]}
  [trace]
  (some (fn [frame]
          (when (str/starts-with? (str (first frame)) "seon.")
            frame))
        trace))

(defn- exception-summary
  {:malli/schema [:=> [:cat :map] :seon.dev.mcp/jvm-exception-error]}
  [value]
  (let [cause-entry (last (:via value))
        nil-deref? (nil-deref? cause-entry)
        frame (or (first-seon-frame (:trace value))
                  (:at cause-entry)
                  (first (:trace value)))
        message (if nil-deref?
                  "The evaluated form dereferenced nil."
                  (str (or (:cause value) (:message cause-entry))))]
    {:seon.error/at (java.util.Date.)
       :seon.error/layer :seon.dev.mcp/evaluation
       :seon.error/operation `exception-summary
       :seon.error/message message
       :seon.error/exception-class (:type cause-entry)
       :seon.error/frame frame
       :seon.error/expected :successful-prepl-evaluation
       :seon.error/offending (str (:type cause-entry))}))

(defn- mcp-projection-error
  {:malli/schema
   [:function
    [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary
                      :seon.schema.admission/reason "The projection fallback describes an arbitrary value that failed projection."
                      :gen/elements [nil false 0 "" :k [] {}]}]]
     :seon.dev.mcp/projection-failed-result]
    [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary
                      :seon.schema.admission/reason "The projection fallback describes an arbitrary value that failed projection."
                      :gen/elements [nil false 0 "" :k [] {}]}]
               :seon.error/throwable]
     :seon.dev.mcp/projection-failed-result]]}
  ([value]
   (mcp-projection-error value nil))
  ([value failure]
   {:seon.dev.mcp/value
    (cond-> {:seon.error/at (java.util.Date.)
      :seon.error/layer :seon.dev.mcp/projection
      :seon.error/operation `mcp-projection-error
      :seon.error/message "MCP projection refused the value: expected an admissible projected value; projection raised an exception. Fix: inspect the offending value class and its projection contract."
      :seon.dev.mcp/projection-offending-class (if (nil? value) "nil" (.getName (class value)))
      :seon.error/member :seon.dev.mcp/value
      :seon.error/expected :admissible-projected-value}
      failure (assoc :seon.dev.mcp/projection-failure-message
                     (or (ex-message failure) "Projection failed.")))
    :seon.dev.mcp/windowed? false}))

(defn- mcp-project
  {:malli/schema
   [:function
    [:=> [:cat :seon.boot/cluster-name [:or :nil :seon.config/effective] :seon.schema/value]
     [:map [:seon.dev.mcp/value :seon.schema/value] [:seon.dev.mcp/windowed? :boolean]]]
    [:=> [:cat :seon.boot/cluster-name [:or :nil :seon.config/effective]
          :seon.schema/value :boolean :boolean]
     [:map [:seon.dev.mcp/value :seon.schema/value] [:seon.dev.mcp/windowed? :boolean]]]]}
  ([cluster-name bootstrap-effective value]
   (mcp-project cluster-name bootstrap-effective value false false))
  ([cluster-name bootstrap-effective value evaluation? exception?]
   (try
     (let [instance (mcp-instance cluster-name)
           connection (:seon.boot/cluster-connection instance)
           effective (mcp-effective cluster-name bootstrap-effective)
           caps (when-not (or (:seon.config/error-key effective)
                 (:seon.db/invalid-read effective) (:seon.schema/expected-value effective))
                  (config/result-caps effective))]
       (cond
         (or (:seon.config/error-key effective)
                 (:seon.db/invalid-read effective) (:seon.schema/expected-value effective))
         {:seon.dev.mcp/value effective :seon.dev.mcp/windowed? false}

         (:seon.config/error-key caps)
         {:seon.dev.mcp/value caps :seon.dev.mcp/windowed? false}

         (and evaluation? (not exception?) (string? (:seon.eval/shown value)))
         {:seon.dev.mcp/value
          (assoc (select-keys value [:seon.cluster.eval/ns
                                    :seon.sci.eval/ending-ns
                                    :seon.sci.admit/record
                                    :seon.cluster.eval/error
                                    :seon.cluster.eval/output])
                 :seon.dev.mcp/text (:seon.eval/shown value))
          :seon.dev.mcp/windowed? false}

         :else
         (let [exception-summary-value (when exception?
                                         (exception-summary value))
               instance-projection
               (some-> (:seon.sci.eval/ctx instance)
                       env/of
                       :seon.schema/projection)
               admitted
                 (admit/admit-value
                  (cond-> {:seon.sci.admit/value
                           (if exception-summary-value
                             (select-keys exception-summary-value
                                          [:seon.error/message])
                             value)
                           :seon.sci.admit/interrupt-fn (fn [])
                           :seon.sci.admit/caps caps
                           :seon.config/on-core-error
                           (:seon.config/on-core-error effective)}
                    instance-projection
                    (assoc :seon.schema/projection instance-projection)))
               artifact (render.value/artifact admitted)
               content (render.value/artifact-edn artifact)
               content-digest (blob/digest content)
               threshold (:seon.config.eval.result/blob-threshold effective)
               oversized? (> (count content) threshold)
               artifact-backed? oversized?
               profile
               (cond-> (assoc (render/agent-render-profile effective)
                              :seon.render.profile/id :seon.render.profile/mcp)
                 (and artifact-backed? connection)
                 (assoc :seon.print/requery-id
                        (list 'seon.render.value/artifact-value
                              (list 'seon.render.value/read-artifact
                                    (list 'seon.blob/get
                                          (list 'seon.cluster.boot/connection cluster-name)
                                          content-digest))))

                 (not (and artifact-backed? connection))
                 (assoc :seon.print/requery-refusal
                        (if artifact-backed?
                          "the cluster has no database connection"
                          "the value has no durable MCP artifact")))
               projection
               (render.value/prepare
                {:seon.render/value (render.value/artifact-value artifact)
                 :seon.render/profile profile
                 :seon.render.value/root [:seon.blob/digest content-digest]
                 :seon.sci.admit/caps caps})
               projected-node (:seon.render.value/tree projection)
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
                      (when (or (:seon.db.write.attempt/request-id result)
                                (:seon.db/invalid-read result) (:seon.schema/expected-value result))
                        (throw
                         (ex-info
                          "The durable MCP artifact root did not commit."
                          {:seon.dev.mcp.artifact/root-not-committed content-digest
                           :seon.error/message
                           "The durable MCP artifact root did not commit."
                           :seon.dev.mcp.artifact/digest content-digest
                           :seon.dev.mcp.artifact/transaction-result result})))
                      content-digest))))]
           (cond-> {:seon.dev.mcp/value
                    (cond-> (admit/semantic-value projected-node)
                      exception-summary-value
                      (->> (merge (dissoc exception-summary-value
                                         :seon.error/message))))
                    :seon.dev.mcp/windowed? artifact-backed?}
             artifact-backed?
             (assoc :seon.blob/digest content-digest
                    :seon.blob/size (count content)
                    :seon.dev.mcp/retrievable? (boolean stored-digest))

             (and artifact-backed? (nil? stored-digest))
             (assoc :seon.dev.mcp/remainder
                    "The cluster has no database connection; the remainder is not retrievable.")))))
    (catch Throwable failure (mcp-projection-error value failure)))))

(defn mcp-valf
  "Project marked MCP returns with caller-supplied exception recognition."
  {:malli/schema
   [:function
    [:=> [:cat :seon.boot/cluster-name :seon.config/effective [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Clojure's prepl hands the projection arbitrary evaluation results, including live JVM objects and nil.", :gen/elements [nil false 0 "" :k [] {}]}]] :string]
    [:=> [:cat :seon.boot/cluster-name :seon.config/effective [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Clojure's prepl hands the projection arbitrary evaluation results, including live JVM objects and nil.", :gen/elements [nil false 0 "" :k [] {}]}] :boolean] :string]
    [:=> [:cat :seon.boot/cluster-name :seon.config/effective [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Clojure's prepl hands the projection arbitrary evaluation results, including live JVM objects and nil.", :gen/elements [nil false 0 "" :k [] {}]}] :boolean [:sequential :seon.flow/channel]] :string]]}
  ([cluster-name bootstrap-effective value]
   (mcp-valf cluster-name bootstrap-effective value false))
  ([cluster-name bootstrap-effective value exception?]
   (mcp-valf cluster-name bootstrap-effective value exception?
             (keep #(get-in % [:seon.render.web/view
                               :seon.render.web/runtime-eval-channel])
                   (vals @running-instances))))
  ([cluster-name bootstrap-effective value exception? runtime-eval-channels]
   (let [projection (consume-mcp-projection!)]
     (try
       ;; A host evaluation may replace Vars used by any cohosted cluster.
       (when-not (:seon.dev.mcp/read-only? projection)
         (doseq [channel runtime-eval-channels]
           (async/offer! channel
                         :seon.render.web/runtime-eval)))
       (admit/canonical-edn
        (if (and projection (get projection :seon.dev.mcp/project? true))
          (mcp-project cluster-name bootstrap-effective value
                       (true? (:seon.dev.mcp/evaluation? projection)) exception?)
          value))
       (catch Throwable _
         ;; Fixed semantic data never re-enters admission or a failed producer.
         (binding [*print-length* nil *print-level* nil]
           (pr-str (mcp-projection-error value))))))))

(defn mcp-io-prepl
  "Serve PREPL events with explicit exception status at MCP projection."
  {:malli/schema [:=> [:cat :seon.boot/cluster-name
                       :seon.config/effective]
                  :nil]}
  [cluster-name bootstrap-effective]
  (let [out *out*
        lock (Object.)]
    (clojure.core.server/prepl
     *in*
     (fn [event]
       (binding [*out* out *flush-on-newline* true *print-readably* true]
         (locking lock
           (prn
            (if (#{:ret :tap} (:tag event))
              (assoc event :val
                     (mcp-valf cluster-name bootstrap-effective
                               (:val event) (true? (:exception event))))
              event))))))))

(defn mcp-get-value
  "Read and drill one stored MCP value artifact without mutating REPL state."
  {:malli/schema [:=> [:cat :seon.boot/cluster-name :seon.blob/digest :seon.render.data/path :int] :seon.schema/value]}
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
                    :seon.render.data/offset offset})
            effective (mcp-effective cluster-name nil)]
        (if (or (:seon.config/error-key effective)
                 (:seon.db/invalid-read effective) (:seon.schema/expected-value effective))
          effective
          (if (contains? found :seon.render.data/value)
            (let [value (:seon.render.data/value found)
                  collection-size
                  (:seon.render.value/max-collection effective)]
              (if (string? value)
                (let [total (count value)
                      offset (max 0 offset)
                      available
                      (max collection-size
                           (:seon.print/width (print/default-options)))
                      start (if (pos? total)
                              (min offset (dec total))
                              0)
                      end (min total (+ start available))
                      window (subs value start end)]
                  {:seon.render.value/window window
                   :seon.render.value/steps []
                   :seon.render.value/offset offset
                   :seon.render.value/shown (count window)
                   :seon.render.value/total total
                   :seon.render.value/beyond-end?
                   (and (pos? total) (>= offset total))
                   :seon.render.value/more? (< end total)})
                (render.value/window value offset collection-size)))
            found)))
      {:seon.dev.mcp/value-not-found content-digest
       :seon.error/at (java.util.Date.)
       :seon.error/layer :seon.cluster/operation
       :seon.error/operation 'seon.cluster/mcp-get-value
       :seon.error/message "No stored MCP value has this digest."
       :seon.blob/digest content-digest})
    {:seon.dev.mcp/remainder-not-retrievable content-digest
     :seon.error/at (java.util.Date.)
     :seon.error/layer :seon.cluster/operation
     :seon.error/operation 'seon.cluster/mcp-get-value
     :seon.error/message
     "The cluster has no database connection; the remainder is not retrievable."
     :seon.blob/digest content-digest}))

(defn mcp-runtime-observation
  "Derive health and Flow observations for one root-discovered cluster."
  {:malli/schema [:=> [:cat :seon.boot/cluster-name] :map]}
  [cluster-name]
  (let [instance (mcp-instance cluster-name)
        connection (:seon.boot/cluster-connection instance)
        projection-state
        (get-in instance
                [:seon.sci.eval/ctx :seon.sci.eval/projection-state])
        ready (when instance ((requiring-resolve 'seon.cluster.boot/readiness) instance))
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
               (schema/call-with-projection-state
                projection-state
                #(oversight/flow-status (db/db connection) instance))
               :unknown)
             :seon.dev.mcp/problem-counts problem-counts}
      readiness-face (assoc :seon.dev.mcp/readiness readiness-face))))

;;; ---------------------------------------------------------------------------
;;; Pure resolution — defaults are THE defaults document for this layer
;;; ---------------------------------------------------------------------------

(defn- refused!
  {:malli/schema [:=> [:cat :string :seon.schema/value] :nil]}
  [message offense]
  (throw (ex-info message
                  (merge
                   {:seon.boot/refused true
                    :seon.error/message message
                    ;; A projection is execution input, not refusal evidence.
                    :seon.boot/offense
                    (if (map? offense)
                      (dissoc offense :seon.schema/projection :seon.cluster.source/phase)
                      offense)}
                   (when (map? offense)
                     (select-keys offense [:seon.cluster.source/phase]))))))

(defn- require-candidate-value
  [projection schema-key value message]
  (if (schema/valid-candidate-value? projection schema-key value)
    value
    (refused! message
              {:seon.boot/schema schema-key
               :seon.boot/value value
               :seon.boot/explanation
               (schema/explain-candidate-value projection schema-key value)})))

(declare cluster-paths operator-root)

(defn resolve-bootstrap
  "Resolve overrides into one complete bootstrap configuration.
  Every key optional; absent = default. Defaults: cluster-name
  \"default\" (just a name, nothing special), root \"data/clusters\",
  prepl-host \"127.0.0.1\", prepl-port 0 (ephemeral — the advertisement
  carries the real port), log-dir derived as <root>/<name>/logs,
  store-dir derived as <operator-root>/data/store — the process-root
  store every cluster branches from.
  Refuses (throws ex-info {:seon.boot/refused true ...}) when a
  declared key has an invalid value. Extra keys remain available for
  accretion."
  {:malli/schema [:=> [:cat :seon.boot/overrides] :seon.boot/config]}
  [overrides]
  ;; ONE declaration population for the whole resolution — it asks two
  ;; questions and each refusal arm asks a third.
  (let [projection (schema/declaration-projection (schema.edn/packaged-forms))]
    (require-candidate-value
     projection
     :seon.boot/overrides
     overrides
     "The bootstrap overrides were refused.")
    (let [defaults {:seon.boot/cluster-name "default"
                    :seon.boot/root "data/clusters"
                    :seon.boot/prepl-host "127.0.0.1"
                    :seon.boot/prepl-port 0}
          base (merge defaults overrides)
          derived-store-dir
          (str (io/file (operator-root (:seon.boot/root base))
                        "data" "store"))
          derived-log-dir
          (:seon.boot/log-dir
           (cluster-paths (:seon.boot/root base)
                          (:seon.boot/cluster-name base)))]
      (require-candidate-value
       projection
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
                   [:seon.boot/log-dir :string]]]}
  [root cluster-name]
  (let [cluster-dir (io/file root cluster-name)]
    {:seon.boot/cluster-dir (str cluster-dir)
     :seon.boot/advertisement-file
     (str (io/file cluster-dir "prepl.edn"))
     :seon.boot/log-dir (str (io/file cluster-dir "logs"))}))

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

(defn- require-cluster-target!
  [paths]
  (let [target (.toPath (io/file (:seon.boot/cluster-dir paths)))
        no-follow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])]
    (when (and (Files/exists target no-follow)
               (not (Files/isDirectory target no-follow)))
      (refused!
       "The cluster path exists and is not a cluster directory."
       {:seon.boot/rule ::non-cluster-target
        :seon.boot/cluster-dir (str target)})))
  paths)

(defn- operator-root
  "The process root whose `data/store` this cluster root branches from.

  DERIVED FROM THE ROOT THE CALLER GENUINELY HOLDS, never from the process
  working directory. `<root>/data/clusters` names the operator root above it;
  any other cluster root names itself. The declared
  `-Dseon.operator.root` — set on every JVM `bin/seon [--root PATH]`
  launches — answers only when the cluster root is relative and therefore
  resolves to the working directory, which is the developer's checkout.

  The removed fallback is the pair that wiped the development store: the
  default cluster root \"data/clusters\" is relative, so an undeclared JVM
  derived the checkout, while a declared JVM let its property outrank an
  explicit `tmp/` fixture root and pointed that fixture at the developer's
  live `data/store`. An undeclared JVM whose cluster root resolves to the
  working directory is now a typed refusal naming it."
  [cluster-root]
  (let [declared (System/getProperty "seon.operator.root")
        root-file (.getCanonicalFile (io/file cluster-root))
        parent (.getParentFile root-file)
        derived (if (and (= "clusters" (.getName root-file))
                         parent (= "data" (.getName parent)))
                  (.getCanonicalPath (.getParentFile parent))
                  (.getCanonicalPath root-file))
        working (.getCanonicalPath (io/file (System/getProperty "user.dir")))]
    (cond
      ; the caller handed a root of its own: it wins over any property
      (not= derived working) derived

      (not (str/blank? declared)) declared

      :else
      (refused!
       "The operator root is undeclared and the cluster root resolves to the working directory."
       {:seon.boot/rule ::undeclared-operator-root
        :seon.boot/root cluster-root
        :seon.boot/working-directory working}))))

(defn warn-low-space!
  "Observe volume capacity and apply the supplied low-space policy."
  {:malli/schema [:=> [:cat :string :seon.config/effective]
                  [:map [:seon.operator.footprint/root :string]
                   [:seon.operator.footprint/usable-bytes [:int {:min 0}]]
                   [:seon.operator.footprint/total-bytes [:int {:min 0}]]
                   [:seon.operator.footprint/usable-ratio :double]
                   [:seon.operator.footprint/observed-at :inst]]]}
  [managed-root effective]
  ;; statfs only — the boot path must never pay a recursive directory
  ;; walk (a checkout carrying frozen tmp/ evidence took ~94 s, which is
  ;; the P19 boot-readiness failure of 2026-08-05).
  (let [footprint (fs/filesystem-space managed-root)
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
                    {:seon.operator/low-disk-space managed-root
                     :seon.error/message message
                     :seon.operator/footprint footprint
                     :seon.config.maintenance/min-usable-bytes
                     (:seon.config.maintenance/min-usable-bytes effective)
                     :seon.config.maintenance/min-usable-ratio
                     (:seon.config.maintenance/min-usable-ratio effective)})))))
    footprint))

(defn write-advertisement!
  [paths advertisement]
  (fs/write-edn-atomically! (:seon.boot/advertisement-file paths) advertisement))

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

(defn acquire-root-store!
  "The ONE store at `store-dir`, opened on first use and shared after.

  A supplied history policy is creation-fixed for the whole operator root.
  A later cluster in the same JVM must request the held representation."
  ([store-dir]
   (acquire-root-store! store-dir ::unspecified-history-policy))
  ([store-dir keep-history?]
   (acquire-root-store! store-dir keep-history? false))
  ([store-dir keep-history? destroy?]
   (let [store-key (root-store-key store-dir)
         requested? (not= ::unspecified-history-policy keep-history?)]
     (locking root-store-holder
       (if-let [held (get @root-store-holder store-key)]
         (let [_ (when destroy? (refused! "Cannot destroy a held store." {:seon.store/dir store-key}))
               store (:seon.store/store held)
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
         (let [request (cond-> {:seon.store/dir store-key :seon.store/destroy? destroy?}
                         requested?
                         (assoc :seon.config.db/keep-history? keep-history?))
               store (store/open-store! request)]
           (swap! root-store-holder assoc store-key
                  {:seon.store/store store ::holders 1})
           store))))))

(defn release-root-store!
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
  [cluster-name attribute
   {:seon.boot/keys [property installed-value declared-value]}]
  (let [target-name (or cluster-name "NAME")
        subject (if cluster-name
                  (str "Cluster `" cluster-name "`")
                  "This branch")]
    (str subject " cannot reopen in place: `" attribute "` changed "
         property " from " (pr-str installed-value)
         " to " (pr-str declared-value)
         ", which Datahike does not apply to an installed attribute. "
         "`bin/seon init " target-name " --force` destroys and reforks it from "
         "`current-src`; use export/import instead to preserve its data.")))

(defn- accretive-property-change?
  "Does Datahike apply this one property change to an INSTALLED attribute?

  The rule is the dependency's own acceptance rule,
  `datahike.schema/find-invalid-schema-updates`
  (`reference-code/datahike/src/datahike/schema.cljc:257`), narrowed to the
  changes an upsert of the current declaration can actually EXPRESS:

  - `:db/index` — an index may be added monotonically to an existing
    attribute; the transactor atomically backfills AVET before publishing the
    resulting database value, and removal remains unsupported
    (`reference-code/datahike/src/datahike/schema.cljc:277`, enforced again per
    datom at `reference-code/datahike/src/datahike/db/transaction.cljc:105`).
  - `:db/doc`, `:db/noHistory`, `:db/isComponent` — always updatable
    (`reference-code/datahike/src/datahike/schema.cljc:285`).
  - `:db/cardinality` — one may widen to many unless the installed attribute
    carries a `:db/unique` constraint
    (`reference-code/datahike/src/datahike/schema.cljc:264`).

  A DROP is never accretive here, even where Datahike would accept the update:
  transacting the current declaration cannot retract a property the branch still
  carries, so reading a drop as compatible would leave the stale property
  installed (2026-09-16,
  `docs/seon/issues/adoption-misses-a-dropped-uniqueness-on-an-installed-attribute.md`).
  Every other property — `:db/valueType`, `:db/unique`, `:db/tupleType`… —
  answers false and the refusal names it."
  [property installed-value declared-value installed]
  (case property
    :db/index (and (nil? installed-value) (true? declared-value))
    (:db/doc :db/noHistory :db/isComponent) (some? declared-value)
    :db/cardinality (and (= :db.cardinality/one installed-value)
                         (= :db.cardinality/many declared-value)
                         (nil? (:db/unique installed)))
    false))

(defn- declaration-property-changes
  "Every storage property where the installed attribute and the declaration differ.

  The comparison is the UNION of both declarations' properties. Selecting only the
  keys the CURRENT declaration carries read a DROPPED property as compatible — the
  branch kept an installed `:db/unique` the bridge no longer derives, and the
  stale identity surfaced much later as a program-indexing conflict naming the
  wrong cause (2026-09-16,
  `docs/seon/issues/adoption-misses-a-dropped-uniqueness-on-an-installed-attribute.md`).
  Absence of the property IS the signal, so it is compared rather than skipped.
  Datahike's `:schema` entry holds exactly the declaration datoms transacted
  for that attribute plus its `:db/ident`
  (`reference-code/datahike/src/datahike/db/transaction.cljc:90`), so the two
  maps are comparable once `:db/ident` is dropped."
  [installed declaration]
  (let [installed (dissoc installed :db/ident)
        declaration (dissoc declaration :db/ident)]
    (into
     []
     (keep
      (fn [property]
        (let [installed-value (get installed property)
              declared-value (get declaration property)]
          (when-not (= installed-value declared-value)
            {:seon.boot/property property
             :seon.boot/installed-value installed-value
             :seon.boot/declared-value declared-value}))))
     (sort (into #{} (concat (keys installed) (keys declaration)))))))

(defn- declaration-changes
  "Missing declarations plus the accretive updates, refusing the rest.

  An attribute already installed on the branch is compared property by property
  rather than by whole-map equality: a change every differing property is
  accretive under `accretive-property-change?` is ADOPTED IN PLACE by
  transacting the declaration, which is how adding `:db/index` to a live
  attribute reaches Datahike's atomic AVET backfill instead of forcing a
  destructive refork of an existing cluster. A property Datahike would not apply
  refuses, naming that property and both of its values."
  [db projection cluster-name]
  (into
   []
   (keep
    (fn [{attribute :db/ident :as declaration}]
      (if-let [installed (get (:schema db) attribute)]
        (let [changes (declaration-property-changes installed declaration)]
          (when (seq changes)
            (if-let [refusal
                     (first
                      (remove
                       (fn [{:seon.boot/keys [property installed-value
                                              declared-value]}]
                         (accretive-property-change?
                          property installed-value declared-value installed))
                       changes))]
              (refused!
               (incompatible-declaration-message
                cluster-name attribute refusal)
               (cond->
                (merge
                 {:seon.boot/attribute attribute
                  :seon.boot/installed installed
                  :seon.boot/current declaration
                  :seon.boot/changes changes}
                 refusal)
                 cluster-name
                 (assoc :seon.boot/cluster-name cluster-name)))
              declaration)))
        declaration)))
   (schema.datahike/malli->datahike-schema-in
    projection
    (schema/canonical-database-attributes projection))))

(defn- missing-process-rows
  "Return required process rows absent from `db`, or its read refusal."
  {:malli/schema [:=> [:cat
                       [:or :seon.db/database-value :seon.error/value]]
                  [:or [:vector [:map
                                 [:seon.db.process/id
                                  :seon.db.process/id]]]
                   :seon.error/value]]}
  [db]
  (let [read-result
        (db/q '[:find [?id ...]
                :where [_ :seon.db.process/id ?id]]
              db)]
    (if (and (map? read-result)
             (contains? read-result :seon.error/at)
             (contains? read-result :seon.error/layer)
             (contains? read-result :seon.error/operation))
      read-result
      (let [present (set read-result)]
        (into
         []
         (comp
          (remove present)
          (map (fn [process-id] {:seon.db.process/id process-id})))
         [boot-process-identity config/managing-process-identity])))))

(defn- schema-lookup-ref?
  [value]
  (and (vector? value)
       (= 2 (count value))
       (= :seon.schema/key (first value))))

(defn- schema-reference-valued?
  [value]
  (or (schema-lookup-ref? value)
      (and (set? value) (seq value) (every? schema-lookup-ref? value))))

(defn- as-schema-lookup-refs
  "Normalize a pulled ref value into the canonical lookup-ref shape.

  Canonical rows reference other schema rows as `[:seon.schema/key k]`
  lookup refs; a pull returns those refs as entity maps (one map or a
  collection of them). Convergence compares in the canonical shape, so
  an already-installed reference graph reads as equal instead of
  re-transacting on every reopen."
  [value]
  (cond
    (map? value) [:seon.schema/key (:seon.schema/key value)]
    (coll? value) (into #{} (map as-schema-lookup-refs) value)
    :else value))

(defn- store-comparable-value
  "Normalize one attribute value into the store's own comparison semantics.

  Datahike holds a cardinality-many attribute as a SET and reads it back in
  an order it chooses, so a declaration's ordered value never equals its own
  stored reading. Such a value therefore compares as a set; every other value
  compares as it stands. The INSTALLED schema decides which — never the
  attribute's name."
  [installed-schema attribute value]
  (if (= :db.cardinality/many
         (get-in installed-schema [attribute :db/cardinality]))
    (if (coll? value) (set value) #{value})
    value))

(defn- schema-row-converged?
  "Does the stored row already carry every desired attribute value?

  Compares under the store's own semantics for each attribute, so a
  cardinality-many value read back in a different order is convergence, not a
  change to re-transact on every branch open."
  [installed-schema desired current]
  (letfn [(comparable [row]
            (into {}
                  (map (fn [[attribute value]]
                         [attribute
                          (store-comparable-value
                           installed-schema attribute value)]))
                  row))]
    (= (comparable desired)
       (comparable (select-keys current (keys desired))))))

(defn- schema-row-changes
  [db projection]
  (into
   []
   (keep
    (fn [{schema-key :seon.schema/key :as desired}]
      (let [selector (mapv (fn [[attribute value]]
                             (if (schema-reference-valued? value)
                               {attribute [:seon.schema/key]}
                               attribute))
                           desired)
            pulled (some-> (db/pull db selector
                                    [:seon.schema/key schema-key])
                           (dissoc :db/id))
            current
            (when pulled
              (into {}
                    (map (fn [[attribute value]]
                           (if (schema-reference-valued?
                                (get desired attribute))
                             [attribute (as-schema-lookup-refs value)]
                             [attribute value])))
                    pulled))]
        (when-not (schema-row-converged? (:schema db) desired current)
          desired))))
   (schema/canonical-schema-rows projection (:seon.schema.projection/forms projection))))

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

(defn- lookup-resolution
  "How one lookup ref answers at `database`: resolved, absent, or REFUSED.

   The probe has THREE states, never two. Reading a refused read as an absent
   entity is the absence-as-health defect this seam shipped: on 2026-09-18 a
   `seon.db/pull` refusal on an EXISTING provider descriptor
   (`:seon.db/unknown-pull-schema`) made every model row look unready, and the
   boot reported \"Initialization lookup refs do not resolve\" about rows whose
   targets were in the database all along. The read's own refusal is the
   evidence; this seam never re-decides it."
  {:malli/schema [:=> [:cat :seon.db/database-value [:sequential :seon.schema/value]]
                  [:or :nil [:map [::lookup [:sequential :seon.schema/value]]
                             [::refusal {:optional true} :seon.db/error-result]]]]}
  [database lookup]
  (let [result (db/pull database [:db/id] (vec lookup))]
    (cond
      (or (:seon.db/invalid-read result) (:seon.schema/expected-value result))
      {::lookup lookup ::refusal result}

      (:db/id result) nil

      :else {::lookup lookup})))

(defn- row-lookup-refusal
  [database row]
  (reduce (fn [_ lookup]
            (let [resolution (lookup-resolution database lookup)]
              (if (::refusal resolution) (reduced resolution) nil)))
          nil
          (lookup-refs-in database row)))

(defn- row-ready?
  [database row]
  (every? (fn [lookup] (nil? (lookup-resolution database lookup)))
          (lookup-refs-in database row)))

(defn- transact-initialization!
  [connection rows]
  (config/require-functions! (db/db connection) rows)
  (loop [pending (vec rows)]
    (when (seq pending)
      (let [database (db/db connection)
            refusal (some #(row-lookup-refusal database %) pending)]
        ;; A refused readiness read is surfaced verbatim BEFORE any judgement
        ;; about what resolves: the readiness question cannot be answered at
        ;; all while the read seam is refusing.
        (when refusal
          (let [[attribute value] (::lookup refusal)]
            (refused!
             (str "An initialization readiness read was refused: "
                  (:seon.error/message (::refusal refusal)))
             {:seon.activation/lookup-attribute attribute
              :seon.activation/lookup-value value
              :seon.boot/population :seon.config/initialization
              :seon.boot/result (::refusal refusal)})))
        (let [ready (into [] (filter #(row-ready? database %)) pending)
              waiting (into [] (remove #(row-ready? database %)) pending)]
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
          (recur waiting)))))
  nil)

(defn require-admissible-branch!
  "Refuse incompatible installed declarations before acquiring the branch projection.
   Config reconciliation checks its own declared function names against the
   program graph; no historical activation roster is required."
  [database cluster-name]
  (let [forms (schema.edn/packaged-forms)
        projection (or (schema/handed-projection) (schema/declaration-projection forms))]
    (schema/call-with-forms
     forms
     (fn []
       (schema/call-with-projection
        projection
        (fn []
          (declaration-changes database projection cluster-name))))))
  nil)

(defn accrete-schema-population!
  "Install the current additive schema population on one branch.

  Registration and database installation are separate in Datahike's
  `:write` schema mode. Every opened branch therefore passes through this
  choke point before any domain transaction. Missing declarations and
  canonical rows accrete; an incompatible declaration refuses loudly and
  names refork or export/import as the resolutions. A converged reopen issues
  no transaction.

  The population HANDS its transactions the projection its declarations come
  from — the same projection write admission validates against — so a caller
  that opened a branch without one (the canonical fixture base, the artifact
  initializer) populates through this seam instead of refusing
  `:seon.schema/missing-projection`. A projection the caller already handed
  (`refresh-source!`'s declaration projection, a cluster's advanceable
  projection state) wins, so this derives one only when nothing supplied it."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/connection [:or :nil :seon.boot/cluster-name]]
     [:or :nil :seon.db/transaction-report]]
    [:=> [:cat :seon.db/connection [:or :nil :seon.boot/cluster-name] :boolean]
     [:or :nil :seon.db/transaction-report]]]}
  ([connection cluster-name]
   (accrete-schema-population! connection cluster-name true))
  ([connection cluster-name publish-schema-rows?]
  (let [forms (schema.edn/packaged-forms)
        projection (or (schema/handed-projection) (schema/declaration-projection forms))]
    (schema/call-with-forms
     forms
     (fn []
       (schema/call-with-projection
        projection
        (fn []
          (let [declarations
                (declaration-changes (db/db connection) projection cluster-name)]
            (when (seq declarations)
              (require-committed!
               (db/transact! connection {:tx-data declarations})
               {:seon.boot/population :seon.schema/declarations})))
          (let [process-rows (missing-process-rows (db/db connection))]
            (when (and (map? process-rows)
                       (contains? process-rows :seon.error/at)
                       (contains? process-rows :seon.error/layer)
                       (contains? process-rows :seon.error/operation))
              (throw (ex-info (:seon.error/message process-rows)
                              process-rows)))
            (when (seq process-rows)
              (require-committed!
               (db/transact! connection {:tx-data process-rows})
               {:seon.boot/population :seon.db/processes})))
          (let [schema-rows (when publish-schema-rows?
                              (schema-row-changes (db/db connection) projection))]
            (when (seq schema-rows)
              (require-committed!
               (db/transact! connection
                             {:tx-data schema-rows
                              :tx-meta
                              {:seon.db/process
                               [:seon.db.process/id boot-process-identity]}})
               {:seon.boot/population :seon.schema/rows}))))))))))

(defn populate-source!
  "The default `current-src` content: this code's schema and program rows.
  Named by symbol in `source/publish!`'s request, so the producer is
  data and N5's program-graph indexer replaces it without touching the
  boot path. The convergent population transactions are DERIVED, never
  hand-written:
  the Datahike declarations of every registered database attribute, the
  core process entities the provenance refs resolve to (genesis data —
  bootstrap content lives in the source branch), and the canonical schema rows
  asserted with that process provenance.

  Every transaction here is HANDED the one projection this population's
  declarations come from — the same projection write admission validates
  against — so the population never depends on its caller having bound one.
  A caller that already handed a projection (`refresh-source!`) wins."
  {:malli/schema
   [:=> [:cat [:map [:seon.db/connection
                     :seon.db/connection]]]
    [:or :nil :seon.reconcile/result]]}
  [{connection :seon.db/connection
    analyzed :seon.program/rows
    manifest :seon.fn/manifest
    directory :seon.fn/root
    roots :seon.fn/roots
    previous :seon.fn/previous-manifest
    paths :seon.fn/changed-paths
    prior-database :seon.source/previous-database
    classes :seon.source/change-classes
    inputs :seon.source/relative-file-digests}]
  (let [forms (schema.edn/packaged-forms)]
    (schema/call-with-forms
     forms
     (fn []
       (schema/call-with-projection
        (or (schema/handed-projection)
            (schema/declaration-projection forms))
        (fn []
          ;; EACH KIND OF CHANGED INPUT HAS ONE OWNER. A complete publication
          ;; supplies no classes and runs every owner; an incremental one
          ;; names exactly the owners its changed inputs belong to, so a
          ;; schema resource never re-indexes the program and a config
          ;; document never re-accretes the schema.
          (let [schema-report
                (when (or (nil? classes) (classes :schema-resource))
          (report-source-progress! "schema population started")
          ;; Complete publication admits schema rows with their renderer
          ;; definitions in index!'s one final transaction.
          (let [report (accrete-schema-population! connection nil (some? classes))]
            (report-source-progress! "schema population complete")
            report))]
          (when (nil? classes)
          (report-source-progress! "instruction rows")
          (let [rows (instruction-row-changes
                      (db/db connection)
                      (instruction/seed-rows))]
            (when (seq rows)
              (require-committed!
               (db/transact! connection
                             {:tx-data rows
                              :tx-meta
                              {:seon.db/process
                               [:seon.db.process/id boot-process-identity]}})
               {:seon.boot/population :seon.cluster.instruction/rows}))))
          (let [result
                (when (or (nil? classes) (classes :program))
                  (report-source-progress! "program rows started")
                  (let [result (seon.fn/index!
             (cond-> {:seon.db/connection connection
                      :seon.schema/projection (schema/handed-projection)
                      :seon.db/process
                      [:seon.db.process/id boot-process-identity]}
               analyzed (assoc :seon.program/rows analyzed)
               directory (assoc :seon.fn/root directory)
               roots (assoc :seon.fn/roots roots)
               manifest (assoc :seon.fn/manifest manifest)
               inputs (assoc :seon.source/relative-file-digests inputs)
               (or classes prior-database)
               (assoc :seon.source/previous-database (or prior-database (db/db connection)))
               previous (assoc :seon.fn/previous-manifest previous)
               paths (assoc :seon.fn/changed-paths paths)
               (nil? manifest) (assoc :seon.fn/roots (or roots seon.fn/source-roots)))
             report-source-progress!)]
                    (report-source-progress! "program rows complete")
                    result))]
          ;; Initialization rows come LAST because they may name a program row
          ;; by lookup ref — the call-preparation suppliers do — and program
          ;; rows are asserted by `index!` immediately above. Nothing earlier in
          ;; this population reads a config fact (`seon.fn` and
          ;; `seon.cluster.instruction` name no config attribute), so the move
          ;; costs no dependency and removes an ordering hazard that would
          ;; otherwise force every declared row to predate the program graph.
          (when (or (nil? classes) (classes :config))
          (report-source-progress! "initialization rows")
          (let [rows (config/default-population)]
            (when (seq rows)
              (transact-initialization! connection rows))))
            (cond-> result
              (and result schema-report)
              (update :seon.reconcile/adopt-identities (fnil into #{})
                      (require-committed! (seon.fn/report-identities schema-report)
                                          {:seon.boot/population :seon.schema/rows})))))))))))

;;; ---------------------------------------------------------------------------
;;; Ordered boot above the REPL
;;; ---------------------------------------------------------------------------

;;; The roots the published source digest is computed over. Today the
;;; population above is derived from the Clojure program plus the
;;; classpath schema population. The indexer reads only Clojure files,
;;; while the source digest also covers the EDN declarations whose
;;; Datahike schema and canonical rows are installed into `current-src`.
(def source-roots
  "The complete file roots whose content identifies `current-src`."
  (into seon.fn/source-roots
        ["config/default.edn"]))

(defn- publication-roots
  "The root sets one publication analyzes, digests, and compares against.

  READ ONCE, AT THE TOP OF THE PUBLICATION, AND CARRIED AS A VALUE.
  Development adoption reloads the program's namespaces — `seon.fn` and
  `seon.cluster` among them — so every `def` above is re-evaluated while the
  publication that triggered the reload is still running. A seam that re-reads
  either root var after that point is acting on a value its own adoption
  re-decided, and the post-adoption digest compare then reports a source change
  that never happened."
  []
  {:seon.fn/root (fs/source-directory)
   :seon.source/roots source-roots
   :seon.fn/roots seon.fn/source-roots})

(defonce ^:private source-refresh-monitor
  ;; One JVM may receive overlapping editor events. Serialize analysis,
  ;; publication, and artifact replacement as one operation; the Datahike
  ;; expected-head guard remains the cross-plan correctness fence.
  (ReentrantLock.))

(defonce ^:private source-refresh-holder
  (atom nil))

(defn- source-refresh-acquisition-bound-ms
  []
  (:seon.config.operator/event-silence-backstop-ms config/defaults))

(defn- source-refresh-holder-view
  [holder]
  (some-> holder (dissoc ::holder-token)))

(defn- with-source-refresh-monitor!
  {:malli/schema [:=> [:cat [:=> [:cat] :seon.schema/value]] :seon.schema/value]}
  [transition]
  (if (.isHeldByCurrentThread ^ReentrantLock source-refresh-monitor)
    (transition)
    (let [bound-ms (source-refresh-acquisition-bound-ms)
          started-ms (System/currentTimeMillis)
          waiter (assoc (cluster.process/current-identity)
                        :seon.operator.lock/command "source publication"
                        :seon.operator.lock/waiting-since (Date. started-ms)
                        :seon.operator.lock/acquisition-timeout-ms bound-ms)]
      (if-not (.tryLock ^ReentrantLock source-refresh-monitor
                        bound-ms TimeUnit/MILLISECONDS)
        (let [waited-ms (- (System/currentTimeMillis) started-ms)
              holder (source-refresh-holder-view @source-refresh-holder)]
          (throw
           (ex-info
            (str "Timed out after " waited-ms
                 " ms waiting for source publication held in phase "
                 (pr-str (:seon.operator.lock/phase holder)) ".")
            {:seon.error/message
             (str "Source publication waited " waited-ms " ms, exceeding the "
                  bound-ms " ms acquisition bound while the holder was in phase "
                  (pr-str (:seon.operator.lock/phase holder)) ".")
             :seon.source/source-refresh-acquisition-timeout true
             :seon.operator.lock/waited-ms waited-ms
             :seon.operator.lock/acquisition-timeout-ms bound-ms
             :seon.operator.lock/holder holder
             :seon.operator.lock/waiter waiter})))
        (let [token (Object.)
              acquired-at (Date.)
              holder (assoc (cluster.process/current-identity)
                            ::holder-token token
                            :seon.operator.lock/command "source publication"
                            :seon.operator.lock/phase "request accepted"
                            :seon.operator.lock/acquired-at acquired-at
                            :seon.operator.lock/acquisition-timeout-ms bound-ms)]
          (reset! source-refresh-holder holder)
          (try
            (binding [*source-refresh-holder-token* token]
              (transition))
            (finally
              (.unlock ^ReentrantLock source-refresh-monitor)
              (swap! source-refresh-holder
                     (fn [current]
                       (when-not (identical? token (::holder-token current))
                         current))))))))))

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
     [:seon.store/store :seon.store/store]
     [:seon.store/branch :seon.store/branch]
     [:seon.db/db :seon.db/database-value]
     [:seon.schema/projection :seon.schema/projection]
     [:seon.sci.eval/ctx :seon.sci.eval/ctx]]]}
  [store]
  (let [{commit-id :seon.source/commit-id} (current-source! store)
        connection (store/open-branch! store source/current-branch)]
    (try
      (let [database (db/db connection)
            projection (schema/projection-from-database database)
            projection-state (sci.eval/projection-state database projection)
            [source-digest base-ctx]
            (schema/call-with-projection-state
             projection-state
             #(vector
                    (db/q '[:find ?digest .
                       :where [_ :seon.source/digest ?digest]]
                     database)
               (sci.eval/cluster-ctx
                database connection projection-state)))]
        {:seon.source/commit-id commit-id
         :seon.source/digest source-digest
         :seon.store/store store
         :seon.store/branch source/current-branch
         :seon.db/db database
         :seon.schema/projection projection
         :seon.sci.eval/ctx base-ctx})
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

(defn require-coherent-program!
  [connection cluster-name]
  (let [currentness (program-currentness (db/db connection))]
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
   :seon.source/test-input-digest (:seon.source/test-input-digest snapshot)
   :seon.source/test-input-digests (:seon.source/test-input-digests snapshot)
   :seon.source/relative-file-digests (:seon.source/relative-file-digests snapshot)
   :seon.fn/manifest manifest})

(defn- current-publication
  [store expected-digest]
  (when-let [{branch :seon.source/branch commit-id :seon.source/commit-id}
             (source/current store)]
    ;; Reading the seal needs no program projection. The changed publication
    ;; acquires its prior declaration world once, after this cheap comparison.
    (let [database (d/commit-as-db (:seon.store/connection-object store) commit-id)]
      (try
        (let [digest (db/q '[:find ?digest . :where [_ :seon.source/digest ?digest]] database)]
          (when (map? digest)
            (refused! "Published source digest could not be read." digest))
          (when (and digest (or (nil? expected-digest) (= expected-digest digest)))
            {:seon.source/branch branch :seon.source/commit-id commit-id
             :seon.source/digest digest :seon.source/built? false}))
        (finally (when database (d/release-materialized-db database)))))))

(defn- full-source-refresh!
  "Capture, compare, classify and analyze selected rows on the current lineage."
  [root store roots]
  (let [published (current-publication store nil)
        committed (when published
                    (d/commit-as-db (:seon.store/connection-object store)
                                    (:seon.source/commit-id published)))]
    (try
      (let [directory (:seon.fn/root roots)
            requested-paths (:seon.source/changed-paths roots)
            input-roots (test.cache/input-roots directory)
            requested (filterv #(or (test.cache/input-path? input-roots %)
                                    (and (test.cache/input-path? (set (:seon.source/roots roots)) %)
                                         (some (partial str/ends-with? %) [".clj" ".cljc" ".edn"])))
                               requested-paths)
            partial? (and committed (seq requested-paths))
            inventory (if partial? requested
                          (source/discover-paths directory (:seon.source/roots roots)))
            [observed captured] (source/capture-paths directory inventory)
            paths (if partial? requested
                      (vec (into (set inventory)
                                 (when committed
                                   (db/q '[:find [?path ...]
                                           :where [_ :seon.fn.file/relative-path ?path]] committed)))))
            prior (if committed (source/stored-path-digests committed paths) {})
            changed (into #{} (filter #(not= (get prior %) (get observed %))) paths)
            classification (cond
                             (nil? committed) :all
                             (empty? changed) :selected
                             :else (source/classify-paths changed (set (keys (test.cache/gitlink-digests directory)))))
            analysis-paths (if (= :all classification)
                             (into changed (if partial?
                                             (source/discover-paths directory (:seon.source/roots roots))
                                             inventory))
                             changed)
            [additional additional-sources]
            (source/capture-paths directory (vec (remove (set inventory) analysis-paths)))
            captured (merge captured additional-sources)]
    (if (and committed (empty? changed)
             (not-any? (requiring-resolve 'seon.issue/note-path?) requested-paths))
      published
      (let [stored (when partial?
                     (into {} (db/q '[:find ?path ?digest
                                      :where [?file :seon.fn.file/relative-path ?path]
                                             [?file :seon.fn.file/digest ?digest]] committed)))
            inputs (merge (if partial? (merge (apply dissoc stored requested) observed) observed) additional)
            snapshot {:seon.source/relative-file-digests inputs
                      :seon.source/digest (id/digest 64 [(into (sorted-map) inputs)])
                      :seon.source/test-input-digest
                      (test.cache/test-input-digest directory
                                                    (test.cache/input-digests directory))}
            digest (:seon.source/digest snapshot)]
      (let [database (when published (source/database store (:seon.source/commit-id published)))
            selected analysis-paths]
        (try
          (let [paths (when database selected)
                analyzed (seon.fn/analyze-rows
                          (cond-> {:seon.fn/root directory
                                   :seon.fn/roots (:seon.fn/roots roots)
                                   :seon.fn/changed-paths selected
                                   :seon.fn.analyzer/sources captured
                                   :seon.source/relative-file-digests inputs}
                            database (assoc :seon.source/previous-database database)))
                previous-findings (when database
                                    (seon.fn/file-rows database (vec paths) :seon.lint/file))
                _ (when (:seon.error/at previous-findings)
                    (refused! "Published findings could not be read." previous-findings))
                _ (report-analysis-warnings! previous-findings (filterv :seon.lint/id analyzed))
                classes (when paths
                          (cond-> #{:program}
                            (some #(str/starts-with? % "resources/seon/schemas/") changed) (conj :schema-resource)
                            (contains? changed config/default-manifest-path) (conj :config)))
                _ (report-source-progress! (str "branch publication started: "
                                                (if paths (count paths) (count (filter :seon.fn.file/relative-path analyzed)))
                                                " inputs"))
                result (source/publish!
                        {:seon.store/store store :seon.fn/root (:seon.fn/root roots)
                         :seon.source/digest digest :seon.source/populate `populate-source!
                         :seon.source/test-input-digest (:seon.source/test-input-digest snapshot)
                         :seon.source/changed-paths (vec (into changed requested-paths))
                         :seon.source/relative-file-digests inputs
                         :seon.source/progress! report-source-progress!
                         :seon.source/populate-request
                         (cond-> {:seon.program/rows analyzed :seon.fn/root directory
                                  :seon.fn/roots (:seon.fn/roots roots)
                                  :seon.source/relative-file-digests inputs}
                           database (assoc :seon.source/previous-database database)
                           paths (assoc :seon.fn/changed-paths paths
                                        :seon.source/change-classes classes))})]
            (report-source-progress! "branch publication complete")
            result)
          (finally (when database (d/release-materialized-db database))))))))
      (finally (when committed (d/release-materialized-db committed))))))

(defn reload-order
  "Namespaces ordered so each one's required namespaces reload first.

  `requires` maps a namespace name to the set of namespace names it requires.
  Only members of `namespaces` are ordered; edges leaving that set are
  ignored. Ties break by name, so the order is stable across boots. Clojure
  namespaces cannot require each other cyclically; inconsistent stored edges
  refuse before any namespace is reloaded."
  {:malli/schema
   [:=> [:cat [:set :seon.ns/name] [:map-of :seon.ns/name [:set :seon.ns/name]]]
    [:vector :seon.ns/name]]}
  [namespaces requires]
  (loop [remaining (into (sorted-set-by #(compare (str %1) (str %2))) namespaces)
         ordered []]
    (if (empty? remaining)
      ordered
      (let [ready (some (fn [namespace-name]
                          (when (empty? (set/intersection
                                         (get requires namespace-name #{})
                                         remaining))
                            namespace-name))
                        remaining)]
        (when-not ready
          (refused! "Development reload requires contain a cycle."
                    {:seon.ns/requires (set remaining)}))
        (recur (disj remaining ready) (conj ordered ready))))))

(defn- namespace-requires
  "The declared `:seon.ns/requires` edges among `namespaces`, by name."
  {:malli/schema [:=> [:cat :seon.db/database-value [:seqable :symbol]]
                  [:map-of :symbol [:set :symbol]]]}
  [database namespaces]
  (let [edges (db/q '[:find ?name ?required-name
                      :in $ [?name ...]
                      :where
                      [?namespace :seon.ns/name ?name]
                      [?namespace :seon.ns/requires ?required-name]]
                    database (vec namespaces))]
    (when (or (:seon.db/invalid-read edges) (:seon.schema/expected-value edges))
      (refused! "Development reload could not read namespace requires." edges))
    (reduce (fn [result [namespace-name required-name]]
              (update result namespace-name (fnil conj #{}) required-name))
            {}
            edges)))

(defn- reloadable-namespace?
  [namespace-name]
  (let [resource (.. (str namespace-name) (replace \- \_) (replace \. \/))]
    (boolean
     (and (find-ns namespace-name)
          (or (io/resource (str resource ".clj"))
              (io/resource (str resource ".cljc")))))))

(defn- load-development-definitions!
  "The one ordered JVM definition replacement used by development adoption."
  {:malli/schema [:=> [:cat [:set :symbol] [:map-of :symbol [:set :symbol]]] :nil]}
  [namespaces requires]
  (doseq [namespace-name (reload-order namespaces requires)
          :when (reloadable-namespace? namespace-name)]
    (report-source-progress! (str "development reload " namespace-name))
    (require namespace-name :reload))
  nil)

(declare commit-fault! process-identity)

(def ^:private adoption-identity-attribute?
  "Declaration identity attributes the adoption record names.

  A `:seon.fn.file/relative-path` row is a file digest and a `:seon.lint/id` row is an
  analyzer finding; neither is a declaration a test can reach, and the file is
  already recorded as an adoption input. Excluding them here keeps
  `:seon.test/adoption-identities` a set of real declaration refs."
  #{:seon.ns/name :seon.fn/sym :seon.schema/key :seon.test/sym})

(defn- adoption-identities
  "Declaration identities among `identities`, in admission order.

  An identity a row does not carry is absent, never a nil member: the
  adoption record is a set of refs, and `#{nil}` names nothing a later check
  can resolve."
  [identities]
  (into [] (filter (comp adoption-identity-attribute? first)) identities))

(defn development-namespaces
  "Changed declaration namespaces and their dependents in either program value."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/database-value :seon.fn.file/identities] [:set :seon.ns/name]]
    [:=> [:cat :seon.db/database-value :seon.db/database-value :seon.fn.file/identities]
     [:set :seon.ns/name]]]}
  ([database identities] (development-namespaces database database identities))
  ([previous database identities]
   (let [selected (into #{}
                        (keep (fn [[attribute value]]
                                (case attribute
                                  :seon.ns/name value
                                  (:seon.fn/sym :seon.test/sym) (symbol (namespace value))
                                  nil))) identities)
         databases (if (identical? previous database) [database] [previous database])]
     (loop [selected selected pending selected]
       (if (empty? pending)
         selected
         (let [callers (into #{}
                             (mapcat
                              (fn [value]
                                (let [result (db/q '[:find [?name ...] :in $ [?required ...]
                                                    :where [?ns :seon.ns/requires ?required]
                                                    [?ns :seon.ns/name ?name]]
                                                  value (vec pending))]
                                  (when (:seon.error/at result)
                                    (refused! "Development namespace dependents could not be read." result))
                                  result)))
                             databases)
               added (set/difference callers selected)]
           (recur (into selected added) added)))))))

(defn- verify-development-sources!
  "Refuse adoption when a reloaded namespace no longer has its published bytes."
  {:malli/schema [:=> [:cat :seon.db/database-value :string [:set :symbol]] :nil]}
  [database directory namespaces]
  (let [paths (db/q '[:find [?path ...] :in $ [?name ...]
                     :where [?ns :seon.ns/name ?name]
                            [?ns :seon.fn/file ?file]
                            [?file :seon.fn.file/relative-path ?path]]
                   database (vec namespaces))
        _ (when (:seon.error/at paths)
            (refused! "Reloaded namespace files could not be read." paths))
        expected (source/stored-path-digests database (vec paths))
        observed (source/path-digests directory (vec paths))
        changed (filterv #(or (nil? (get expected %))
                             (not= (get expected %) (get observed %))) paths)]
    (when (seq changed)
      (refused! "Source changed during development adoption."
                {:seon.cluster.source/phase :adoption
                 :seon.source/changed-paths changed})))
  nil)

(defn- development-arming-identities
  "Reloaded Vars and functions whose contracts refer to changed schemas."
  {:malli/schema [:=> [:cat :seon.db/database-value [:set :symbol] :seon.fn.file/identities]
                  :seon.reconcile/adopt-identities]}
  [database namespaces identities]
  (let [schemas (into [] (keep (fn [[attribute value]]
                                (when (= :seon.schema/key attribute) value))) identities)
        referring (if (seq schemas)
                    (db/q '[:find [?sym ...]
                            :in $ % [?changed ...] [?attribute ...]
                            :where (affected ?key ?changed)
                                   [?arity ?attribute ?key]
                                   [?function :seon.fn/arities ?arity]
                                   [?function :seon.fn/sym ?sym]]
                          database
                          '[[(affected ?key ?changed)
                             [?schema :seon.schema/key ?changed]
                             [(identity ?changed) ?key]]
                            [(affected ?key ?changed)
                             [?schema :seon.schema/key ?key]
                             [?schema :seon.schema/references ?ref]
                             (affected ?ref ?changed)]]
                          schemas
                          [:seon.fn.arity/input-refs :seon.fn.arity/output-refs
                           :seon.fn.arity/guard-refs])
                    [])]
    (when (:seon.error/at referring)
      (refused! "Development contract referrers could not be read." referring))
    (into #{}
          (map #(vector :seon.fn/sym %))
          (into (set referring)
                (mapcat (fn [namespace-name]
                          (when-let [loaded (find-ns namespace-name)]
                            (map #(symbol (str namespace-name) (str %))
                                 (keys (ns-interns loaded))))))
                namespaces))))

(defn- development-source-refresh!
  {:malli/schema
   [:=> [:cat :seon.store/store :seon.boot/instance :seon.source/published
          [:vector :string]
          [:map [:seon.fn/root :string] [:seon.source/roots :seon.source/roots]]]
    :nil]}
  [held-store instance published changed-paths _roots]
  (let [connection (:seon.boot/cluster-connection instance)
        cluster-name (get-in instance [:seon.boot/advertisement :seon.boot/cluster-name])
        cluster-ref [:seon.cluster/name cluster-name]
        ctx (:seon.sci.eval/ctx instance)
        prior-commit (:seon.source/commit-id
                      (db/pull (db/db connection) [:seon.source/commit-id] cluster-ref))]
    (if (and prior-commit (= prior-commit (:seon.source/commit-id published)))
      (do (report-source-progress! "development cluster converged") nil)
      (let [previous-database (db/db connection)
        published-database (source/database held-store (:seon.source/commit-id published))]
       (try
        (let [published-projection (db/carried-projection published-database)
        identities (if prior-commit
                     (source/changed-identities held-store published prior-commit)
                     (into #{} (keep program/row-identity)
                           (seon.fn/published-index-rows published-database)))
        program-identities (into #{} (filter (comp (set program/identity-attributes) first)) identities)
        issue-identities (into #{} (keep (fn [[attribute value]]
                                         (when (= :seon.issue/id attribute) value))) identities)
        forms (:seon.schema.projection/forms published-projection)
        _ (when (some #(= :seon.schema/key (first %)) identities)
            (report-source-progress! "development changed schema declarations")
            (schema/call-with-forms
             forms
             #(require-committed!
               (db/transact! connection
                             {:tx-data [[:db.fn/call
                                         (fn [database]
                                           (declaration-changes database published-projection cluster-name))]]})
               {:seon.boot/population :seon.schema/declarations})))
        _ (when (seq program-identities)
            (report-source-progress! "development changed program rows")
            (require-committed!
             (schema/call-with-projection
              published-projection
              #(seon.fn/index!
              {:seon.db/connection connection
               :seon.schema/projection published-projection
               :seon.source/database published-database
               :seon.source/previous-database previous-database
               :seon.reconcile/adopt-identities program-identities}
              *source-progress!*))
             {:seon.boot/population :seon.fn/population}))
        _ (when (seq issue-identities)
            (report-source-progress! "development changed issues")
            (require-committed!
             ((requiring-resolve 'seon.issue/adopt!) connection published-database issue-identities)
             {:seon.boot/population :seon.issue/rows}))
        database (db/db connection)
        projection (schema/projection-from-database database)
        changed-identities (adoption-identities program-identities)
        deleted-identities (filterv #(empty? (db/pull published-database '[*] %)) changed-identities)
        namespaces (development-namespaces previous-database database changed-identities)]
    (report-source-progress! "development loaded definitions")
    ;; Clojure reload leaves removed interns behind. Remove only definitions
    ;; whose identity is absent from the published database.
    (doseq [[attribute function-symbol] deleted-identities
            :when (#{:seon.fn/sym :seon.test/sym} attribute)]
      (let [qualified (symbol function-symbol)
            namespace-name (symbol (namespace qualified))
            local-name (symbol (name qualified))]
        (when (find-ns namespace-name)
          (ns-unmap namespace-name local-name))))
    ;; A changed caller reloaded before its changed callee fails on the
    ;; callee's new Var, so the order follows the declared requires facts.
    (load-development-definitions! namespaces (namespace-requires database namespaces))
    (verify-development-sources! published-database (fs/source-directory)
                                 (into #{} (filter reloadable-namespace?) namespaces))
    (env/advance-projection! (get ctx env/state-carrier)
                             (db/basis-t database) projection)
    (report-source-progress! "development JVM instrumentation")
    (schema/call-with-projection
     projection
     (fn []
       (let [effective (config/effective database cluster-name)
             _ (when (or (:seon.config/error-key effective)
                 (:seon.db/invalid-read effective) (:seon.schema/expected-value effective))
                 (refused! "Development instrumentation configuration is unavailable."
                           effective))
             result (instrument/apply!
                     {:seon.config/on-core-error
                      (:seon.config/on-core-error effective)
                      :seon.flow/commit-fault!
                      (:seon.flow/commit-fault! @(:seon.sci.kernel/program-snapshot ctx))
                      :seon.sci.admit/caps (config/result-caps effective)
                      :seon.config.error/max-evidence-bytes
                      (:seon.config.error/max-evidence-bytes effective)
                      :seon.schema/projection projection
                      :seon.instrument/changed-identities
                      (development-arming-identities database namespaces changed-identities)})]
         (when (or (:seon.instrument/registration-observation result)
                   (and (= :panic (:seon.config/on-core-error effective))
                        (not (pos? (or (:seon.instrument/instrumented result) 0)))))
           (refused! "Development JVM instrumentation did not restore contracts."
                     result)))))
    ;; This fact means indexing, reload and instrumentation succeeded. SCI
    ;; acquires this database on first use; it does no work during adoption.
    (report-source-progress! "development adoption record")
    (require-committed!
     (db/transact! connection
                   {:tx-data [{:db/id cluster-ref
                               :seon.source/commit-id
                               (:seon.source/commit-id published)}
                              {:db/id :db/current-tx
                               :seon.test/adoption-cluster cluster-ref
                               :seon.test/adoption-identities (set/difference (set changed-identities)
                                                                             (set deleted-identities))
                               :seon.test/adoption-inputs (set changed-paths)}]})
     {:seon.boot/population :seon.source/commit-id})
    (when-let [channel (get-in instance
                              [:seon.render.web/view
                               :seon.render.web/runtime-eval-channel])]
      (async/offer! channel :seon.render.web/runtime-eval))
    (report-source-progress! "development cluster converged")
    nil)
    (finally (d/release-materialized-db published-database)))))))

(defn refresh-source!
  "Publish the current source tree onto the one `current-src` branch.

  Content digests select changed inputs and declaration edges select affected
  files. The captured input bytes produce rows directly; the current database
  supplies prior identities. Analyzer configuration changes select every source.
  A post-reload source-change refusal leaves the adoption record unchanged;
  the next request reconciles from the last adopted source database."
  {:malli/schema
   [:function
    [:=> [:cat :seon.boot/root] :seon.source/published]
    [:=> [:cat :seon.boot/root [:vector :string]] :seon.source/published]
    [:=> [:cat :seon.boot/root [:vector :string] [:maybe :seon.boot/cluster-name]]
     :seon.source/published]
    [:=> [:cat :seon.boot/root [:vector :string] [:maybe :seon.boot/cluster-name] :string]
     :seon.source/published]]}
  ([root]
   (refresh-source! root []))
  ([root changed-paths]
   (refresh-source! root changed-paths nil))
  ([root changed-paths development-cluster]
   (refresh-source! root changed-paths development-cluster (fs/source-directory)))
  ([root changed-paths development-cluster directory]
   (report-source-progress! "request accepted")
   (with-source-refresh-monitor!
    (fn []
     (report-source-progress! "bootstrap configuration")
     (let [instance (when development-cluster
                      (get @running-instances development-cluster))
           _ (when (and development-cluster (not instance))
               (refused! "Development updates require the named cluster to be running."
                         {:seon.boot/cluster-name development-cluster}))
           config (resolve-bootstrap {:seon.boot/root root})
           store-dir (:seon.boot/store-dir config)
           _ (report-source-progress! "store acquisition")
           held-store (acquire-root-store! store-dir)
           ;; The publication's own roots, read before any adoption reload can
           ;; re-evaluate the vars that declare them.
           roots (assoc (publication-roots) :seon.fn/root directory
                        :seon.source/changed-paths
                        (mapv #(fs/relative-path directory %) changed-paths))]
       (try
         (report-source-progress! "source build")
         (schema/call-with-projection
          (schema/declaration-projection (schema.edn/packaged-forms))
          (fn []
            (let [published (full-source-refresh! root held-store roots)]
              (when instance
                (development-source-refresh! held-store instance published changed-paths roots))
              published)))
         (finally
           (release-root-store! store-dir))))))))

(defn publication-base!
  "Export the common publication and its exact manifest for isolated workers."
  {:malli/schema [:=> [:cat :seon.boot/root :string :string] :string]}
  [root directory destination]
  (with-source-refresh-monitor!
    (fn []
      (let [store-dir (:seon.boot/store-dir (resolve-bootstrap {:seon.boot/root root}))
            held (acquire-root-store! store-dir)]
        (try
          ;; An export names a complete checkout, not an empty edit request.
          ;; Include stored paths so files removed from that checkout retract.
          (let [paths (set (test.cache/input-paths directory))
                published (source/current held)
                database (when published (source/database held (:seon.source/commit-id published)))
                paths (try
                        (if database
                          (let [stored (db/q '[:find [?path ...]
                                               :where [_ :seon.fn.file/relative-path ?path]] database)]
                            (when (:seon.error/at stored)
                              (refused! "The exported publication's input paths could not be read." stored))
                            (into paths stored))
                          paths)
                        (finally (when database (d/release-materialized-db database))))]
            (refresh-source! root (vec (sort paths)) nil directory))
          (let [published (source/current held)
                database (source/database held (:seon.source/commit-id published))
                digest (db/q database '[:find ?digest . :where [_ :seon.source/digest ?digest]])
                input-digest (db/q database '[:find ?digest .
                                              :where [_ :seon.source/test-input-digest ?digest]])]
            (try
              (when (map? digest)
                (refused! "The exported publication digest could not be read." digest))
              (when (map? input-digest)
                (refused! "The exported publication input digest could not be read." input-digest))
              (when-not input-digest
                (refused! "The exported publication has no input digest." :seon.error/absent))
              (report-source-progress! "publication export")
              (export/export! {:seon.store/store held
                               :seon.export/parent-dir (str (io/file destination "data"))})
              (let [manifest (seon.fn/database-manifest database directory
                                                        (:seon.fn/roots (publication-roots)) nil)
                    input-digests (test.cache/external-input-digests
                                   directory (test.cache/input-digests directory))
                    inputs (into {} (db/q '[:find ?path ?digest
                                            :where [?file :seon.fn.file/relative-path ?path]
                                                   [?file :seon.fn.file/digest ?digest]] database))]
                (write-source-artifact! destination
                  (source-artifact (assoc published :seon.source/digest digest) manifest
                                   {:seon.source/test-input-digest input-digest
                                    :seon.source/test-input-digests input-digests
                                    :seon.source/relative-file-digests inputs}))
                (spit (io/file destination "manifest.edn") (pr-str manifest)))
              (spit (io/file destination "provenance.edn")
                    (pr-str {:seon.test.run/program-digest digest
                             :seon.test.run/basis-t (db/basis-t database)
                             :seon.test.run/branch source/current-branch}))
              destination
              (finally (d/release-materialized-db database))))
          (finally (release-root-store! store-dir)))))))

;;; ---------------------------------------------------------------------------
;;; Recovery — the pass that runs before anything resumes
;;; ---------------------------------------------------------------------------

(defn process-identity
  "Process provenance from its pid and start instant, carried on execution requests."
  ;; IT NEEDS (pid, start-instant) AND NOTHING ELSE, so that is what it
  ;; declares: `:seon.cluster.process/identity`, which every advertisement
  ;; satisfies (maps are open). Demanding the whole advertisement to compute
  ;; an identity was a lie about the dependency, and under armed contracts it
  ;; refused three fixtures that hand exactly the pair the identity IS.
  {:malli/schema [:=> [:cat :seon.cluster.process/identity]
                  :seon.db.process/id]}
  [advertisement]
  (str (:seon.boot/pid advertisement) "-"
       (inst-ms (:seon.boot/start-instant advertisement))))

(defn recover-runs!
  "Close all prior open turns before arming agents. The writer decides
  which evaluations and effects remain unfinished; nothing is replayed."
  {:malli/schema
   [:=> [:cat :seon.db/connection]
    [:or
     [:map
      [:seon.boot/recovered-runs :seon.boot/recovered-runs]
      [:seon.boot/recovery-operations :seon.boot/recovery-operations]]
     :seon.error/value]]}
  [connection]
  (let [db (db/db connection)
        now (java.util.Date.)
        open-runs (db/q '[:find [?run-id ...]
                         :where
                         [?run :seon.turn/id ?run-id]
                         (not [?run :seon.turn/closed-tx _])]
                       db)
        result
        (if (and (map? open-runs)
                 (contains? open-runs :seon.error/at)
                 (contains? open-runs :seon.error/layer)
                 (contains? open-runs :seon.error/operation))
          open-runs
          (let [;; the decision moved INSIDE the transaction (custody revision,
                ;; Revision 4): `recover-call` reads each run's receipts at
                ;; transaction time, so this caller only names the open runs —
                ;; a stale-basis recovery stamping a settled receipt is
                ;; unrepresentable
                operations
                (into (schedule/recover-tx db now)
                      (mapcat
                       (fn [run-id]
                         (turn/recover-tx
                          {:seon.turn/id run-id
                           :seon.turn/now now})))
                      open-runs)]
            (when (seq operations)
              (require-committed!
               (db/transact! connection operations)
               {:seon.boot/population :seon.turn/recovery}))
            {:seon.boot/recovered-runs (count open-runs)
             :seon.boot/recovery-operations (count operations)}))]
    result))

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
  (when (:seon.error/at result)
    (refused! (str "The cluster population transaction was refused: " (:seon.error/message result))
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
                 (db/db connection) process)
    (require-committed!
     (db/transact!
      connection
      {:tx-data [{:seon.db.process/id process}]
       :tx-meta {:seon.db/process
                 [:seon.db.process/id boot-process-identity]}})
     {:seon.db.process/id process
      :seon.boot/population :seon.db.process/process}))
  (let [toolkit-namespaces (instruction/toolkit-namespaces (db/db connection))
        desired {:seon.cluster/name cluster-name
                 :seon.cluster/config
                 [:seon.config/cluster cluster-name]
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
        current (some-> (db/pull (db/db connection)
                                '[:seon.cluster/name
                                  {:seon.cluster/config
                                   [:seon.config/cluster]}
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
         :seon.db.process/id
         :inst
         :seon.agent/creation-request]
    :seon.store/transaction-data]}
  [db process now
   {agent-id :seon.agent/id
    namespace-name :seon.ns/name
    :as request}]
  (if (db/q '[:find ?agent .
             :in $ ?agent-id
             :where [?agent :seon.agent/id ?agent-id]]
           db agent-id)
    []
    (into (cluster.agent/creation-tx request)
          (bootstrap/seed-tx
           db
           {:seon.agent/id agent-id :seon.cluster/name (:seon.cluster/name request) :seon.ns/name namespace-name :seon.db.process/id process :seon.turn/opened-tx "datomic.tx"}))))

(defn ensure-entity!
  "Create one absent agent atomically and return its durable useful identity.

  An existing agent resumes untouched, so the result is always derived from
  the committed database value rather than from the caller's request."
  {:malli/schema
   [:=> [:cat :seon.db/connection
         :seon.db.process/id
         :seon.agent/creation-request]
    [:or :seon.agent/creation-result :seon.error/value]]}
  [connection process request]
  (let [transaction-result
        (db/transact!
         connection
         {:tx-data [[:db.fn/call #'ensure-entity-call
                     process (java.util.Date.) request]]
          :tx-meta {:seon.db/process [:seon.db.process/id process]}})]
    (if (or (:seon.db.write.attempt/request-id transaction-result)
            (:seon.db/invalid-read transaction-result)
            (:seon.schema/expected-value transaction-result))
      transaction-result
      ;; Read back from the connection because this operation needs only the
      ;; current committed value, not the rest of the transaction report.
      (let [database (db/db connection)
            agent-id (:seon.agent/id request)
            bootstrap-run-id (bootstrap/run-id agent-id)
            agent
            (db/pull database
                     '[:seon.agent/id
                       {:seon.agent/namespace [:seon.ns/name]}]
                     [:seon.agent/id agent-id])
            run-agent-id
            (db/q '[:find ?agent-id .
                    :in $ ?run-id
                    :where
                    [?run :seon.turn/id ?run-id]
                    [?run :seon.turn/agent ?agent]
                    [?agent :seon.agent/id ?agent-id]]
                  database bootstrap-run-id)
            namespace-name
            (get-in agent [:seon.agent/namespace :seon.ns/name])
            cluster-name
            (db/q '[:find ?name . :where [_ :seon.cluster/name ?name]] database)]
        (if (and namespace-name cluster-name (= agent-id run-agent-id))
          {:seon.agent/id agent-id
           :seon.ns/name namespace-name
           :seon.cluster/name cluster-name
           :seon.turn/id bootstrap-run-id}
          {:seon.agent/creation-incomplete agent-id
           :seon.error/at (java.util.Date.)
           :seon.error/layer :seon.cluster/operation
           :seon.error/operation 'seon.cluster/ensure-entity!
           :seon.error/message
           (str "Agent " (pr-str agent-id)
                " committed without its namespace, cluster, or bootstrap run.")
           :seon.error/data {:seon.agent/id agent-id}})))))

(defn seed-root-agent!
  "Ensure root and its agent-owned maintenance initialization exist."
  [connection cluster-name process]
  (require-committed!
   (ensure-entity!
    connection
    process
    {:seon.agent/id root-agent-id
     :seon.cluster/name cluster-name
     :seon.ns/name 'my.agents.root})
   {:seon.agent/id root-agent-id
    :seon.boot/population :seon.agent/agent})
  (require-committed!
   (db/transact!
    connection
    {:tx-data [[:db.fn/call #'schedule/root-maintenance-seed-call]]
     :tx-meta {:seon.db/process [:seon.db.process/id process]}})
   {:seon.agent/id root-agent-id
    :seon.boot/population :seon.schedule/root-maintenance}))

(defn serve!
  "Bind the cluster's web view and publish its actual URL and port.

  Every bind rewrites the existing PREPL advertisement before returning the
  updated instance. A publication failure closes the new listener and throws.

  The last layer, deliberately: everything it renders must already
  stand, and a failure here must not be able to cost the run loop. It
  throws like any other boot layer, so the REPL survives and the
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
  [instance dials]
  (let [advertisement (:seon.boot/advertisement instance)
        cluster-name (:seon.boot/cluster-name advertisement)
        connection (:seon.boot/cluster-connection instance)
        view (assoc (:seon.render.web/view instance)
                    :seon.turn.loop/cluster (:seon.turn.loop/cluster instance))
        wanted (or (:seon.config.web/port dials)
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
                        :seon.agent/id root-agent-id
                        :seon.sci.admit/caps (config/result-caps dials)}
                       (select-keys view
                                    [:seon.turn.loop/cluster
                                     :seon.agent/routing
                                     :seon.render.web/pages-mult
                                     :seon.render.web/registration
                                     :seon.render.web/latest-packages
                                     :seon.render.web/render-channel
                                     :seon.render.web/fault-channel
                                     :seon.db.process/id
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
    (let [advertisement (merge advertisement
                               (select-keys served [:seon.render.web/url
                                                    :seon.render.web/port]))]
      (try
        (write-advertisement!
         (cluster-paths (get-in instance [:seon.boot/config :seon.boot/root])
                        cluster-name)
         advertisement)
        (assoc instance
               :seon.render.web/served served
               :seon.boot/advertisement advertisement)
        (catch Throwable cause
          (web/stop! served)
          (throw cause))))))

(defn- tagged-run
  "The tagged agent's open turn, or nil.
  Attribution is STRUCTURAL: an agent graph's fault arrives tagged with
  its agent (structural provenance from the error-channel join), so
  attribution is that agent's one open turn — exact under concurrency,
  where the serial-era global query stopped being. That global query
  (`attributed-run`) is deleted at F2 §3.3."
  [db agent-id]
  (db/q '[:find ?id .
         :in $ ?agent-id
         :where
         [?agent :seon.agent/id ?agent-id]
         [?run :seon.turn/agent ?agent]
         (not [?run :seon.turn/closed-tx])
         [?run :seon.turn/id ?id]]
       db agent-id))

(defn- previously-reported-fault-signature?
  [database signature]
  (some?
   (db/q '[:find ?error .
           :in $ ?signature
           :where [?error :seon.error/signature ?signature]]
         database signature)))

(defn commit-fault!
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
  [connection cluster-name process caps observation]
  (try
    (let [db (db/db connection)
          dials (config/effective db cluster-name)
          source-fault (:seon.error/source observation)
          agent-id (:seon.agent/id source-fault)
          run-id (when agent-id (tagged-run db agent-id))
          dropped-count (::flow/dropped-fault-count source-fault)
          threshold (:seon.config.eval.result/blob-threshold dials)
          request
          (cond-> {:seon.schema/projection (db/carried-projection db)
                   :seon.error/source source-fault
                   :seon.error/declared-schema (:seon.error/declared-schema observation)
                   :seon.error/id (str (random-uuid))
                   :seon.error/at (java.util.Date.)
                   :seon.error/process process
                   :seon.sci.admit/caps caps
                   :seon.error/basis-t (db/basis-t db)
                   :seon.config.error/recurrence-limit
                   (:seon.config.error/recurrence-limit dials)
                   ;; THE FAULT FAMILY'S OWN BOUND rides the request the
                   ;; committer builds, so `error/prepare` AND
                   ;; `error/commit-tx` — both of which declare it required —
                   ;; read the one dial this cluster's effective config
                   ;; carries. Handing it to only one of the two is how every
                   ;; core fault became unrecordable: `commit-tx` refused its
                   ;; contract and the operator printed the refusal about
                   ;; itself instead of the fault.
                   :seon.config.error/max-evidence-bytes
                   (:seon.config.error/max-evidence-bytes dials)}
            (:seon.config.error/escalate-to dials)
            (assoc :seon.config.error/escalate-to
                   (:seon.config.error/escalate-to dials))
            run-id (assoc :seon.turn/id run-id)
            agent-id (assoc :seon.agent/id agent-id))
          ;; The fault family's own bound decides how much evidence the
          ;; durable fact keeps; the blob threshold decides where the
          ;; complete evidence lives. Two decisions, two declared keys, ONE
          ;; request — `prepare` and `commit-tx` see the same value.
          prepared (error/prepare request)
          staged (when (or (let [size (:seon.error/data-size
                                       (:seon.error/fact prepared))]
                             ;; AN UNSERIALIZABLE EVIDENCE MEASURED NOTHING,
                             ;; so the fact carries no size; the content
                             ;; comparison below is what decides staging then.
                             (and (int? size) (> size threshold)))
                           (not= (:seon.error/data-content prepared)
                                 (:seon.error/data-edn (:seon.error/fact prepared))))
                   (blob/stage! connection (:seon.error/data-content prepared)))
          prepared-fact (cond-> (:seon.error/fact prepared)
                          staged (assoc :seon.error/data-blob (:seon.blob/digest staged))
                          (pos-int? dropped-count)
                          (assoc :seon.error/dropped-fault-count dropped-count
                                 :seon.error/dropped-fault-digest
                                 (::flow/dropped-fault-digest source-fault)))
          recording (error/recording db (assoc request :seon.error/fact prepared-fact))
          transaction-data (:seon.db/tx-data recording)
          fact (:seon.error/fact recording)
          signature (:seon.error/signature fact)
          previously-reported?
          (previously-reported-fault-signature? db signature)]
      (try
        (let [result (blob/with-publication!
                      connection (if staged [staged] [])
                      #(db/transact! connection transaction-data))]
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
      (let [fault (:seon.error/source observation)
            cause (if (instance? Throwable fault) fault (::flow.core/ex fault))
            message (or (:seon.error/message fault)
                        (when (instance? Throwable cause)
                          (str (.getName (class cause)) ": " (ex-message cause)))
                        (str fault))]
        [{:seon.error/message message} failure false]))))

(defn- single-line-fault-text
  [value]
  (-> (str value)
      (str/replace "\r" " ")
      (str/replace "\n" " ")))

(defn- emit-core-fault!
  [_configuration reported]
  (let [fact (::flow/fault-fact reported)
        outcome (::flow/commit-outcome reported)
        mode (::flow/core-error-mode reported)
        message (or (:seon.error/message fact)
                    "A core fault could not be normalized.")
        failure-message
        (when-not (= ::flow/committed outcome)
          (or (when (map? outcome) (:seon.error/message outcome))
              (when (instance? Throwable outcome) (ex-message outcome))
              (str outcome)))]
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
   wake-channel stream-channel completion]
  (let [dials (config/effective (db/db connection) cluster-name)]
    (cond-> {;; The one environment value this cluster's procs and
             ;; submissions carry. The handle's remaining entries are
             ;; process-local ports and structural dials, which are not
             ;; environment members.
             :seon.env/environment (env/of ctx)
             :seon.agent/context-state (atom {})
             :seon.sci.eval/projection-state
             (:seon.sci.eval/projection-state ctx)
             :seon.db/connection connection
              :seon.cluster/name cluster-name
              :seon.db.process/id process
              :seon.flow/work-launcher work-launcher
              :seon.sci.eval/ctx ctx
              :seon.cluster.wake/channel wake-channel
              ;; the cluster's ONE stream conn (F2 §2.1): sliding-1, so
              ;; the newest complete snapshot wins and the provider fold
              ;; is never parked by presentation
              :seon.turn.loop/stream-channel stream-channel
              :seon.turn.loop/completion completion
              :seon.sci.admit/caps (config/result-caps dials)
              :seon.config.eval/time-limit-ms
              (:seon.config.eval/time-limit-ms dials)
              :seon.config/on-core-error (:seon.config/on-core-error dials)
              :seon.config.error/recurrence-limit
              (:seon.config.error/recurrence-limit dials)
              ;; the fault family's own bound rides with the loop's other
              ;; dials, so every committer this cluster owns hands it to
              ;; `seon.error/commit-tx` instead of falling back to a
              ;; bootstrap number nobody chose
              :seon.config.error/max-evidence-bytes
              (:seon.config.error/max-evidence-bytes dials)
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
  render proc — a schedule proc later. One graph per cluster,
  so the components that arm agents and derive pages have exactly the
  ping/error/pause uniformity every other proc has. The render proc's
  channels are external ports (created by `arm-agents!`, carried on the
  handle and the view), so the graph definition stays pure data."
  [handle routing view]
  (let [environment (env/of handle)]
    {:procs {:seon.agent/armer
             {:proc (flow/var-process
                     #'cluster.agent/armer-step :io
                     (env/carry {:seon.turn.loop/cluster handle
                                 :seon.agent/routing routing}
                                environment))}
             :seon.render.web/render
             {:proc (flow/var-process
                     #'web/render-step :io
                     (env/carry (assoc view
                                       :seon.turn.loop/cluster handle
                                       :seon.agent/routing routing)
                                environment))}}
     :conns []
     :io-exec (:seon.flow/executor handle)}))

(defn arm-agents!
  "Arm the cluster's shared graph, fan-out, routing listener and prime.
  The armer acquires agent graphs only for current work or stored schedules.
  Idle agents remain facts until a wake or schedule needs their graph. A
  scheduled agent needs its existing timer proc even when it has no turn;
  its SCI program remains unacquired until evaluation. Recovery has already
  closed interrupted turns, and pending durable work is derived by the prime.

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
        completion (async/promise-chan)
        io-executor
        (projection-executor
         (:seon.sci.eval/projection-state (:seon.sci.eval/ctx instance)))
        handle
        (assoc
         (loop-handle connection cluster-name process
                      (:seon.sci.eval/ctx instance)
                      (:seon.flow/work-launcher instance)
                      armer-channel stream-channel completion)
         :seon.flow/executor io-executor)
        routing (cluster.agent/routing)
        ;; the render pipeline's external ports (F2 §1): the wake
        ;; channel route! delivers into, the pages channel the proc's
        ;; snapshots exit on (multed here, tapped per tab), the watched
        ;; registration the feed writes, and the proc's own orderly-stop
        ;; completion — all process-local, all free to lose
        render-channel (async/chan (async/sliding-buffer 1))
        runtime-eval-channel (async/chan (async/sliding-buffer 1))
        pages-channel (async/chan (async/sliding-buffer 1))
        latest-packages (atom {})
        render-interest (atom :all)
        view {:seon.render.web/render-channel render-channel
              :seon.render.web/runtime-eval-channel runtime-eval-channel
              :seon.render.web/pages-channel pages-channel
              :seon.render.web/registration (atom {})
              :seon.render.web/latest-packages latest-packages
              :seon.render.web/interest render-interest
              :seon.render.web/completion (async/promise-chan)
              :seon.render.web/root-agent-id "root"
              :seon.sci.eval/ctx (:seon.sci.eval/ctx handle)
              :seon.config.eval/time-limit-ms
              (:seon.config.eval/time-limit-ms handle)
              :seon.config/on-core-error
              (:seon.config/on-core-error handle)
              ;; Execution provenance for web effects.
              :seon.db.process/id (:seon.db.process/id handle)}
        {graph :seon.flow/graph
         joins :seon.flow/joins}
        (flow/start-graph!
         {:seon.flow/graph-definition
          (cluster-graph-definition handle routing view)
          :seon.flow/joins
          {::error-fanout
           (fn [{started :seon.flow/started
                 graph :seon.flow/graph}]
             (flow/start-error-fanout!
              {:seon.env/environment (env/of handle)
               :seon.flow/graph graph
               :seon.flow/started started
               :seon.flow/fault-buffer-capacity 64
               :seon.flow/monitor-buffer-capacity 64
               :seon.flow/read-core-error-mode
               (fn []
                 (or (:seon.config/on-core-error
                      (config/effective (db/db connection) cluster-name))
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
                 (emit-core-fault! handle reported))}))
           ::pages-mult
           (fn [_]
             (async/mult pages-channel))}})
        fanout (::error-fanout joins)
        pages-mult (::pages-mult joins)]
    ;; the fault channel joins the routing entry so every later arm
    ;; can tap its agent graph's errors into the ONE committer inbox
    (swap! routing assoc :seon.agent/fault-channel
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
                  :seon.render.web/interest render-interest
                  :seon.cluster.wake/fault-channel
                  (:seon.flow/fault-channel fanout)
                  :seon.cluster.wake/key :seon.agent/route})
    ;; BOOT IS ONE WAKE through the ordinary render path. The listener is
    ;; already registered and the cold interest is `:all`, so commits before
    ;; this offer are covered by the same newest-database derivation as every
    ;; later pass.
    (async/offer! render-channel ::wake/render)
    ;; Derive agents with work or schedules through the ordinary armer. The listener is already registered, so every arm's
    ;; mailbox prime and every commit concurrent with it are conserved.
    ;; Direct invocation publishes readiness: a returned instance is
    ;; armed, while the running proc owns every later wake.
    (cluster.agent/armer-step
     (cluster.agent/armer-step
      {:seon.turn.loop/cluster handle
       :seon.agent/routing routing})
     :seon.agent/arm
     :seon.agent/boot)
    {:seon.turn.loop/cluster handle
     :seon.flow/graph graph
     :seon.flow/error-fanout fanout
     :seon.agent/routing routing
     ;; the view half `serve!` hands to the web service: one mult over
     ;; the proc's pages out-port, the shared registration, and the
     ;; wake channel a freshly opened tab offers into
     :seon.render.web/view
     (assoc view
            :seon.agent/routing routing
            :seon.render.web/pages-mult pages-mult
            :seon.render.web/fault-channel
            (:seon.flow/fault-channel fanout))}))

(defn disarm-agents!
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
  {:malli/schema [:=> [:cat :map] :nil]}
  [instance]
  ;; the VIEW goes first: it is the newest layer and the only one
  ;; holding sockets belonging to somebody outside this process
  (when-let [served (:seon.render.web/served instance)]
    (web/stop! served))
  (when-let [handle (:seon.turn.loop/cluster instance)]
    (wake/unlisten! {:seon.cluster.wake/connection
                     (:seon.db/connection handle)
                     :seon.cluster.wake/key :seon.agent/route}))
  (when-let [handle (:seon.turn.loop/cluster instance)]
    (let [armer-channel (:seon.cluster.wake/channel handle)
          quiesced (async/promise-chan)]
      (when-not (async.protocols/closed? armer-channel)
        (when-not (async/>!! armer-channel
                             {:seon.agent/quiesce quiesced})
          (throw
           (ex-info "The cluster armer input closed before quiescence."
                    {:seon.agent/armer-quiescence-undeliverable true
                     :seon.error/message
                     "The cluster armer input closed before quiescence."})))
        (when-not (= :seon.agent/quiesced (async/<!! quiesced))
          (throw
           (ex-info "The cluster armer did not publish quiescence."
                    {:seon.agent/armer-quiescence-undeliverable true
                     :seon.error/message
                     "The cluster armer did not publish quiescence."})))
        ;; Closure is the observable completion fact a later stop derives
        ;; from. Publish it only after the armer acknowledged quiescence.
        (async/close! armer-channel))))
  (when-let [routing (:seon.agent/routing instance)]
    (doseq [agent-id (sort (keys (:seon.agent/armed @routing)))]
      (cluster.agent/disarm! {:seon.agent/id agent-id
                              :seon.agent/routing routing})))
  (when-let [graph (:seon.flow/graph instance)]
    (flow.core/stop graph)
    ;; BOTH cluster-graph procs are joined at their own completions —
    ;; `flow/stop` only queues `::flow/stop`, so a render pass holding
    ;; the branch connection would otherwise still be deriving when the
    ;; connection is released
    (async/<!! (:seon.turn.loop/completion
                (:seon.turn.loop/cluster instance)))
    (some-> (get-in instance [:seon.render.web/view
                              :seon.render.web/completion])
            async/<!!))
  (when-let [fanout (:seon.flow/error-fanout instance)]
    (flow/stop-error-fanout! fanout))
  (when-let [handle (:seon.turn.loop/cluster instance)]
    (some-> (:seon.turn.loop/stream-channel handle) async/close!))
  ;; the render pipeline's own ports, after the proc that reads them has
  ;; published its completion: a tab still looping on a tap sees its tap
  ;; close and falls out of the loop
  (when-let [view (:seon.render.web/view instance)]
    (async/close! (:seon.render.web/render-channel view))
    (async/close! (:seon.render.web/runtime-eval-channel view))
    (async/close! (:seon.render.web/pages-channel view)))
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
              (schema/declaration-projection (schema.edn/packaged-forms))
              :seon.boot/advertisement advertisement)
             (= cluster-name (:seon.boot/cluster-name advertisement))
             (cluster.process/live?
              (select-keys advertisement
                           [:seon.boot/pid :seon.boot/start-instant])))
        advertisement))
    (catch Throwable _
      nil)))
