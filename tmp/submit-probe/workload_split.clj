(ns submit-probe.workload-split
  (:require [clojure.core.async.impl.dispatch :as dispatch]
            [sci.core :as sci]
            [sci.interrupt :as sci.interrupt]
            [seon.config :as config]
            [seon.sci.eval :as eval]
            [seon.sci.reader :as reader])
  (:import [java.util.concurrent Callable Executor Executors FutureTask
            Semaphore TimeUnit]))

(def ^:dynamic *cpu-permits*)
(def ^:dynamic *io-executor*)
(def ^:dynamic *release-compute-permit?* false)
(def ^:dynamic *active-io*)
(def ^:dynamic *maximum-io*)
(def ^:dynamic *io-thread-kinds*)

(defn update-maximum!
  [maximum value]
  (swap! maximum max value))

(defn ^{:seon.workload :io} toy-io-sleep
  [milliseconds]
  (if *release-compute-permit?*
    (do
      (.release ^Semaphore *cpu-permits*)
      (try
        (let [active-io *active-io*
              maximum-io *maximum-io*
              io-thread-kinds *io-thread-kinds*
              task
              (FutureTask.
               ^Callable
               (fn []
                 (let [active (swap! active-io inc)]
                   (update-maximum! maximum-io active)
                   (swap! io-thread-kinds
                          conj
                          {:submit-probe/virtual?
                           (.isVirtual (Thread/currentThread))
                           :submit-probe/name (.getName (Thread/currentThread))})
                   (try
                     (Thread/sleep (long milliseconds))
                     milliseconds
                     (finally
                       (swap! active-io dec))))))]
          (.execute ^Executor *io-executor* task)
          (.get task))
        (finally
          (.acquire ^Semaphore *cpu-permits*))))
    (let [active (swap! *active-io* inc)]
      (update-maximum! *maximum-io* active)
      (swap! *io-thread-kinds*
             conj
             {:submit-probe/virtual? (.isVirtual (Thread/currentThread))
              :submit-probe/name (.getName (Thread/currentThread))})
      (try
        (Thread/sleep (long milliseconds))
        milliseconds
        (finally
          (swap! *active-io* dec))))))

(defn ^{:seon.workload :io} toy-io-value
  [value]
  value)

(defn ^{:seon.workload :io} toy-io-await
  [future]
  (deref future))

(defn untagged-wrapper
  [milliseconds]
  (toy-io-sleep milliseconds))

(defn untagged-blocker
  [milliseconds]
  (Thread/sleep (long milliseconds))
  milliseconds)

(defn resolved-var
  [symbol]
  (when (symbol? symbol)
    (try
      (if (namespace symbol)
        (find-var symbol)
        (ns-resolve *ns* symbol))
      (catch IllegalArgumentException _
        nil))))

(defn symbols-in
  [form]
  (filter symbol? (tree-seq coll? seq form)))

(defn direct-heads
  [form]
  (for [node (tree-seq coll? seq form)
        :when (seq? node)
        :let [head (first node)]
        :when (symbol? head)]
    (let [resolved (resolved-var head)]
      {:submit-probe/symbol head
       :submit-probe/resolved? (var? resolved)
       :submit-probe/workload (:seon.workload (meta resolved))})))

(defn walk-case
  [label source]
  (let [event
        (first
         (reader/read
          {::reader/text source
           ::reader/ns 'submit-probe.workload-split
           ::reader/features #{:clj}}))
        form (::reader/form event)]
    {:submit-probe/case label
     :submit-probe/source source
     :submit-probe/direct-heads (vec (direct-heads form))
     :submit-probe/tagged-symbols-anywhere
     (into
      []
      (keep
       (fn [symbol]
         (when-let [workload
                    (:seon.workload (meta (resolved-var symbol)))]
           {:submit-probe/symbol symbol
            :submit-probe/workload workload})))
      (symbols-in form))}))

(def walk-cases
  [(walk-case
    :direct
    "(submit-probe.workload-split/toy-io-sleep 10)")
   (walk-case
    :local-call
    "(let [f submit-probe.workload-split/toy-io-sleep] (f 10))")
   (walk-case
    :higher-order
    "(map submit-probe.workload-split/toy-io-value [1 2])")
   (walk-case
    :dynamic-apply
    "(apply submit-probe.workload-split/toy-io-sleep [10])")
   (walk-case
    :untagged-transitive-wrapper
    "(submit-probe.workload-split/untagged-wrapper 10)")
   (walk-case
    :untagged-blocker
    "(submit-probe.workload-split/untagged-blocker 10)")
   (walk-case
    :alias-introduced-by-require
    "(do (require '[submit-probe.workload-split :as p]) (p/toy-io-sleep 10))")])

(def caps
  (config/result-caps (config/defaults)))

(def source
  (str "(do "
       "(reduce + (map inc (range 3000))) "
       "(submit-probe.workload-split/toy-io-sleep 100) "
       "(reduce + (map inc (range 3000))))"))

(defn evaluation-context
  []
  (let [probe-ns (sci/create-ns 'submit-probe.workload-split)]
    (sci/init
     {:namespaces
      {'clojure.core sci.interrupt/clojure-core
       'submit-probe.workload-split
       {'toy-io-sleep
        (sci/copy-var
         submit-probe.workload-split/toy-io-sleep
         probe-ns)}}})))

(defn run-scenario
  [{:keys [scenario tasks concurrency release-compute-permit?]}]
  (let [admission (Semaphore. concurrency)
        cpu-permits (Semaphore. concurrency)
        io-executor (dispatch/executor-for :io)
        virtual-executor (Executors/newVirtualThreadPerTaskExecutor)
        active-io (atom 0)
        maximum-io (atom 0)
        io-thread-kinds (atom #{})
        started-at (System/nanoTime)
        futures
        (mapv
         (fn [ordinal]
           (.submit
            virtual-executor
            ^Callable
            (fn []
              (let [gate (if release-compute-permit?
                           cpu-permits
                           admission)]
                (.acquire gate)
                (try
                  (binding [*cpu-permits* cpu-permits
                            *io-executor* io-executor
                            *release-compute-permit?*
                            release-compute-permit?
                            *active-io* active-io
                            *maximum-io* maximum-io
                            *io-thread-kinds* io-thread-kinds]
                    {:submit-probe/ordinal ordinal
                     :submit-probe/eval-thread-virtual?
                     (.isVirtual (Thread/currentThread))
                     :submit-probe/evaluation
                     (eval/evaluate
                      {:seon.cluster.run.form/source source
                       :seon.sci.eval/ctx (evaluation-context)
                       :seon.sci.admit/caps caps
                       :seon.sci.eval/time-limit-ms 5000
                       :seon.config/on-core-error :panic})})
                  (finally
                    (.release gate)))))))
         (range tasks))
        results (mapv #(.get % 10 TimeUnit/SECONDS) futures)
        elapsed (double (/ (- (System/nanoTime) started-at) 1000000.0))]
    (.shutdown virtual-executor)
    {:submit-probe/scenario scenario
     :submit-probe/tasks tasks
     :submit-probe/concurrency concurrency
     :submit-probe/elapsed-ms elapsed
     :submit-probe/max-overlapping-io @maximum-io
     :submit-probe/io-threads @io-thread-kinds
     :submit-probe/all-eval-threads-virtual?
     (every? :submit-probe/eval-thread-virtual? results)
     :submit-probe/values
     (mapv
      #(get-in % [:submit-probe/evaluation :seon.sci.admit/value])
      results)}))

(def boring-scenario
  {:scenario :boring-whole-eval-lifetime-gate
   :tasks 8
   :concurrency 2
   :release-compute-permit? false})

(def split-scenario
  {:scenario :split-release-permit-around-tagged-io
   :tasks 8
   :concurrency 2
   :release-compute-permit? true})

;; Warm SCI and both executor paths before recording comparable trials.
(run-scenario
 {:scenario :warmup
  :tasks 2
  :concurrency 2
  :release-compute-permit? true})

(prn
 {:submit-probe/tagged-vars
  (into {}
        (map
         (fn [v]
           [(symbol (str (ns-name (:ns (meta v)))) (str (:name (meta v))))
            (:seon.workload (meta v))]))
        [#'toy-io-sleep #'toy-io-value #'toy-io-await])
  :submit-probe/form-walk walk-cases
  :submit-probe/measurement-trials
  (vec
   (mapcat
    (fn [trial]
      [(assoc (run-scenario boring-scenario) :submit-probe/trial trial)
       (assoc (run-scenario split-scenario) :submit-probe/trial trial)])
    (range 1 4)))})

(shutdown-agents)
