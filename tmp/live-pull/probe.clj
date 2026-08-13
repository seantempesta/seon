(ns probe
  "Decompose generated-opening derivation on a published program graph.

  Run from the repository root with:

    clojure -M:dev:test -i tmp/live-pull/probe.clj
      -m probe tmp/live-pull/root-NAME

  The target root must not already exist. The probe writes only beneath that
  root and prints one EDN record per measurement."
  (:require [clojure.java.io :as io]
            [datahike.pull-api :as pull-api]
            [seon.bootstrap :as bootstrap]
            [seon.cluster :as cluster]
            [seon.config :as config]
            [seon.db :as db]
            [seon.render :as render]
            [seon.render.walk :as walk]
            [seon.schema :as schema]
            [seon.sci.admit :as admit]
            [seon.sci.eval :as sci.eval]
            [seon.sci.kernel :as sci.kernel]
            [seon.test-support :as support])
  (:import [com.sun.management ThreadMXBean]
           [java.lang.management ManagementFactory]
           [java.nio.file Files LinkOption Path]
           [java.util Date]))

(def ^:dynamic *phase* :outside)

(defn- private-var
  [namespace-name symbol-name]
  (or (ns-resolve namespace-name symbol-name)
      (throw (ex-info "The profiling boundary does not exist."
                      {:live-pull.probe/namespace namespace-name
                       :live-pull.probe/symbol symbol-name}))))

(def ^:private allocation-bean
  (let [mx-bean (ManagementFactory/getThreadMXBean)]
    (when (instance? ThreadMXBean mx-bean)
      (let [mx-bean ^ThreadMXBean mx-bean]
        (when (and (.isThreadAllocatedMemorySupported mx-bean)
                   (not (.isThreadAllocatedMemoryEnabled mx-bean)))
          (.setThreadAllocatedMemoryEnabled mx-bean true))
        (when (.isThreadAllocatedMemoryEnabled mx-bean) mx-bean)))))

(defn- allocated-bytes
  []
  (when allocation-bean
    (.getThreadAllocatedBytes ^ThreadMXBean allocation-bean
                              (.threadId (Thread/currentThread)))))

(defn- observe!
  [observations phase label started-ns started-bytes]
  (let [elapsed (- (System/nanoTime) started-ns)
        finished-bytes (allocated-bytes)
        allocated (when (and started-bytes finished-bytes)
                    (- finished-bytes started-bytes))]
    (swap! observations
           (fn [current]
             (cond-> (-> current
                         (update-in [phase label :calls] (fnil inc 0))
                         (update-in [phase label :nanoseconds]
                                    (fnil + 0) elapsed))
               allocated
               (update-in [phase label :allocated-bytes]
                          (fnil + 0) allocated))))))

(defn- timed-wrapper
  [observations label function]
  (fn [& arguments]
    (let [phase *phase*
          started-ns (System/nanoTime)
          started-bytes (allocated-bytes)]
      (try
        (apply function arguments)
        (finally
          (observe! observations phase label started-ns started-bytes))))))

(defn- phase-wrapper
  [observations phase label function]
  (fn [& arguments]
    (let [parent *phase*
          started-ns (System/nanoTime)
          started-bytes (allocated-bytes)]
      (try
        (binding [*phase* phase]
          (apply function arguments))
        (finally
          (observe! observations parent label started-ns started-bytes))))))

(defn- printable-observations
  [observations]
  (into (sorted-map)
        (map (fn [[phase labels]]
               [phase
                (into (sorted-map)
                      (map (fn [[label sample]]
                             [label
                              (cond-> {:calls (:calls sample)
                                       :milliseconds
                                       (/ (double (:nanoseconds sample))
                                          1000000.0)}
                                (:allocated-bytes sample)
                                (assoc :allocated-bytes
                                       (:allocated-bytes sample)))]))
                      labels)]))
        observations))

(defn- summarize
  [value]
  (cond
    (and (map? value) (:seon.render.walk/order value))
    {:live-pull.probe/member-count
     (count (:seon.render.walk/order value))
     :live-pull.probe/selector-top-level-count
     (count (:seon.render.walk/selector value))}

    (and (map? value) (:seon.repl/candidates value))
    {:live-pull.probe/candidate-count
     (count (:seon.repl/candidates value))
     :live-pull.probe/root-key (:seon.repl/root-key value)}

    (and (map? value) (:seon.repl/form value))
    {:live-pull.probe/entry-form (:seon.repl/form value)
     :live-pull.probe/entry-key (:seon.repl/key value)}

    :else
    {:live-pull.probe/value-class (some-> value class str)
     :live-pull.probe/error-kind
     (when (map? value) (:seon.error/kind value))}))

(defn- measure
  [observations label operation]
  (reset! observations {})
  (let [started-ns (System/nanoTime)
        started-bytes (allocated-bytes)
        value (binding [*phase* label] (operation))
        elapsed-ns (- (System/nanoTime) started-ns)
        finished-bytes (allocated-bytes)]
    (println
     (pr-str
      (merge
       {:live-pull.probe/measurement label
        :live-pull.probe/wall-ms (/ (double elapsed-ns) 1000000.0)
        :live-pull.probe/allocated-bytes
        (when (and started-bytes finished-bytes)
          (- finished-bytes started-bytes))
        :live-pull.probe/phases (printable-observations @observations)}
       (summarize value))))
    value))

(defn- directory-bytes
  [root]
  (with-open [paths (Files/walk (.toPath (io/file root))
                                (make-array java.nio.file.FileVisitOption 0))]
    (reduce (fn [total ^Path path]
              (if (Files/isRegularFile path (make-array LinkOption 0))
                (+ total (Files/size path))
                total))
            0
            (iterator-seq (.iterator paths)))))

(defn- conditions
  [root database extra-agent-count]
  (let [operating-system (ManagementFactory/getOperatingSystemMXBean)]
    {:live-pull.probe/root (.getCanonicalPath (io/file root))
     :live-pull.probe/store-bytes (directory-bytes root)
     :live-pull.probe/eavt-datom-count (count (:eavt database))
     :live-pull.probe/extra-agent-count extra-agent-count
     :live-pull.probe/basis-transaction (db/basis-t database)
     :live-pull.probe/commit-id (:datahike/commit-id database)
     :live-pull.probe/java-version (System/getProperty "java.version")
     :live-pull.probe/os-name (System/getProperty "os.name")
     :live-pull.probe/os-version (System/getProperty "os.version")
     :live-pull.probe/os-arch (System/getProperty "os.arch")
     :live-pull.probe/available-processors (.getAvailableProcessors
                                            operating-system)
     :live-pull.probe/max-heap-bytes (.maxMemory (Runtime/getRuntime))}))

(defn- make-request
  [connection ctx agent-id]
  {:seon.db/db @connection
   :seon.db/connection connection
   :seon.sci.eval/ctx ctx
   :seon.cluster.agent/id agent-id
   :seon.render.walk/lookup [:seon.cluster.agent/id agent-id]
   :seon.sci.admit/caps (config/result-caps (config/defaults))
   :seon.sci.eval/time-limit-ms 5000
   :seon.config/on-core-error :record
   :seon.render/distance 3})

(defn- settle-help!
  [connection agent-id]
  (let [situation (bootstrap/situation @connection agent-id)
        print-node
        (:seon.sci.admit/print-node
         (admit/admit-value
          {:seon.sci.admit/value situation
           :seon.sci.admit/interrupt-fn (fn [])
           :seon.sci.admit/caps (config/result-caps (config/defaults))
           :seon.config/on-core-error :record}))]
    (db/transact!
     connection
     [{:seon.cluster.eval/id (pr-str [(bootstrap/run-id agent-id) 0])
       :seon.cluster.eval/run [:seon.cluster.run/id
                               (bootstrap/run-id agent-id)]
       :seon.cluster.eval/ordinal 0
       :seon.cluster.eval/at (Date.)
       :seon.cluster.eval/result-edn (pr-str print-node)}])))

(defn- replacements
  [observations]
  (let [boundaries
        [[#'walk/root-selector :root-selector]
         [#'db/pull :seon-db-pull]
         [#'db/q :seon-db-query]
         [#'pull-api/pull-spec :datahike-pull-execution]
         [#'pull-api/pull-dependency-plan :datahike-dependency-plan]
         [(private-var 'seon.db 'decode-pull-result) :pull-result-decode]
         [(private-var 'seon.render.walk 'acquisition-members)
          :membership-index]
         [#'render/render-call :render-call]
         [(private-var 'seon.render 'producer) :render-selection]
         [(private-var 'seon.render 'candidates)
          :owning-namespace-candidates]
         [(private-var 'seon.render 'invoke-selected) :render-invocation]
         [#'sci.kernel/invoke :sci-kernel-invoke]
         [#'admit/admit-value :admission]
         [#'walk/neighborhood :neighborhood]
         [#'walk/ordered-episode :ordered-episode]]]
    (into
     {(private-var 'seon.bootstrap 'direct-candidates)
      (phase-wrapper observations :direct-candidates :direct-candidates
                     @(private-var 'seon.bootstrap 'direct-candidates))
      (private-var 'seon.bootstrap 'listing-candidates)
      (phase-wrapper observations :listing-candidates :listing-candidates
                     @(private-var 'seon.bootstrap 'listing-candidates))
      (private-var 'seon.bootstrap 'root-candidate)
      (phase-wrapper observations :root-candidate :root-candidate
                     @(private-var 'seon.bootstrap 'root-candidate))
      #'walk/root-acquisition
      (phase-wrapper observations :root-acquisition :root-acquisition
                     @#'walk/root-acquisition)
      #'walk/root-pull-plan
      (phase-wrapper observations :root-pull-plan :root-pull-plan
                     @#'walk/root-pull-plan)}
     (map (fn [[resolved-var label]]
            [resolved-var
             (timed-wrapper observations label @resolved-var)]))
     boundaries)))

(defn- run-probe!
  [root connection extra-agent-count]
  (support/seed-cluster! connection "live-pull-probe")
  (let [agent-id "live-pull-probe-agent"
        _ (cluster/ensure-entity!
           connection cluster/boot-process-identity
           {:seon.cluster.agent/id agent-id
            :seon.cluster/name "live-pull-probe"
            :seon.ns/name 'my.agents.live-pull-probe-agent})
        _ (doseq [index (range extra-agent-count)]
            (let [extra-agent-id (str "live-pull-extra-" index)]
              (cluster/ensure-entity!
               connection cluster/boot-process-identity
               {:seon.cluster.agent/id extra-agent-id
                :seon.cluster/name "live-pull-probe"
                :seon.ns/name (symbol "my.agents" extra-agent-id)})))
        ctx (sci.eval/cluster-ctx @connection connection)
        generator-request (make-request connection ctx agent-id)
        observations (atom {})]
    (println (pr-str (conditions root @connection extra-agent-count)))
    (schema/call-with-projection
     (sci.kernel/context-projection ctx)
     (fn []
       (with-redefs-fn
         (replacements observations)
         (fn []
           (let [pull-plan
                 (measure observations :cold-root-pull-plan
                          #(walk/root-pull-plan generator-request))
                 request-with-plan
                 (assoc generator-request
                        :seon.render.walk/root-pull-plan pull-plan)
                 _ (measure observations :cold-root-acquisition
                            #(walk/root-acquisition request-with-plan))
                 _ (measure observations :warm-root-acquisition
                            #(walk/root-acquisition request-with-plan))
                 _ (measure observations :cold-pull-result
                            #(bootstrap/pull-result generator-request))
                 _ (measure observations :warm-pull-result
                            #(bootstrap/pull-result generator-request))]
             (settle-help! connection agent-id)
             (let [settled-request (assoc generator-request
                                          :seon.db/db @connection)]
               (measure observations :next-entry-after-help
                        #(bootstrap/next-entry settled-request
                                               (bootstrap/run-id
                                                agent-id)))))))))))

(defn -main
  "Run the decomposition against one new repository-local root."
  [& [root extra-agent-count]]
  (when-not root
    (throw (ex-info "Pass a new repository-local probe root."
                    {:live-pull.probe/usage
                     "tmp/live-pull/root-NAME"})))
  (let [root-file (.getCanonicalFile (io/file root))
        extra-agent-count (if extra-agent-count
                            (parse-long extra-agent-count)
                            0)]
    (when-not (nat-int? extra-agent-count)
      (throw (ex-info "The extra-agent count must be a natural integer."
                      {:live-pull.probe/extra-agent-count
                       extra-agent-count})))
    (when (.exists root-file)
      (throw (ex-info "The probe root already exists."
                      {:live-pull.probe/root (.getPath root-file)})))
    (when-not (.startsWith (.toPath root-file)
                           (.toPath (.getCanonicalFile (io/file "tmp/live-pull"))))
      (throw (ex-info "The probe root must be beneath tmp/live-pull."
                      {:live-pull.probe/root (.getPath root-file)})))
    (support/with-published-file-database
      root-file :live-pull-probe
      (fn [connection]
        (run-probe! root-file connection extra-agent-count))))
  (shutdown-agents))
