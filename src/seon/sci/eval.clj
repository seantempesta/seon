(ns seon.sci.eval
  "Evaluate one source form on a bounded `:compute` platform thread.

  Also owns the SCI base: one process-shared interpreter context (SCI's
  own word, `ctx` — `reference-code/sci/src/sci/core.cljc`) forked per
  evaluation, whose callable surface is computed from committed
  program-function facts filtered by the agent's derived namespace
  policy — never a name-prefix rule or a hand list."
  (:require [sci.core :as sci]
            [sci.interrupt :as sci.interrupt]
            [seon.agent.lifecycle :as lifecycle]
            [seon.schema :as schema]
            [seon.sci.interrupt :as interrupt])
  (:import (java.util.concurrent Callable Executors Semaphore TimeUnit
                                 TimeoutException)))

;;; ---------------------------------------------------------------------------
;;; The SCI base and its computed callable surface
;;; ---------------------------------------------------------------------------

(def ^:private core-base
  "The process-shared SCI context without database-derived tools."
  (delay
    (let [lifecycle-ns (sci/create-ns 'seon.agent.lifecycle)]
      (sci/init
       {:namespaces
        {'clojure.core sci.interrupt/clojure-core
         'clojure.string sci.interrupt/clojure-string
         'seon.agent.lifecycle
         {'wait (sci/copy-var lifecycle/wait lifecycle-ns)
          'complete (sci/copy-var lifecycle/complete lifecycle-ns)
          'pause (sci/copy-var lifecycle/pause lifecycle-ns)
          'resume (sci/copy-var lifecycle/resume lifecycle-ns)
          'terminate (sci/copy-var lifecycle/terminate lifecycle-ns)}}
        ;; SCI already exposes Exception. Keep the additional JVM surface to
        ;; the two broad roots instead of enumerating exception subclasses.
        :classes
        {'Throwable Throwable
         'java.lang.Throwable Throwable
         'Error Error
         'java.lang.Error Error}}))))

(defn require-spec-namespaces
  "The namespace symbols one agent's resolved require specs expose.
  The specs come from `seon.agent.home/home-requires-for` — the
  agent's own datom or the config-seeded canonical default — so the
  callable surface is a database-derived policy, never a name-prefix
  rule or a hand list."
  {:malli/schema [:=> [:cat [:sequential :any]] [:set :symbol]]}
  [require-specs]
  (into #{}
        (keep (fn [spec]
                (when (and (sequential? spec) (symbol? (first spec)))
                  (first spec))))
        require-specs))

(defn- tool-binding
  "Wrap one exposed program function so its call establishes the
  invocation bindings — the effect request context and the platform
  leaves — on the calling (eval) thread. The bindings map is
  {Var value}."
  [bindings function-symbol]
  (when-let [host-var (requiring-resolve (symbol function-symbol))]
    (with-meta
      (fn [& args]
        (with-bindings bindings
          (apply @host-var args)))
      (meta host-var))))

(defn- tool-namespaces
  [program-functions exposed-namespaces bindings]
  (reduce
   (fn [namespaces function-symbol]
     (let [qualified (symbol function-symbol)
           namespace-symbol (some-> (namespace qualified) symbol)]
       (if (contains? exposed-namespaces namespace-symbol)
         (if-let [binding (tool-binding bindings function-symbol)]
           (assoc-in namespaces
                     [namespace-symbol (symbol (name qualified))]
                     binding)
           namespaces)
         namespaces)))
   {}
   program-functions))

(defn base
  "Build the SCI base from current program-graph function facts.
  `::program-functions` are the committed public function symbols;
  `::exposed-namespaces` is the agent's derived require-spec namespace
  set; `::bindings` is the {Var value} invocation context established
  on each tool call."
  [{::keys [program-functions exposed-namespaces bindings]}]
  (sci/merge-opts
   (sci/fork @core-base)
   {:namespaces (tool-namespaces program-functions
                                 (or exposed-namespaces #{})
                                 (or bindings {}))}))

(defn fork
  "Fork the shared base and install one evaluation's `:interrupt-fn`."
  [{::keys [base-ctx] :keys [interrupt-fn]}]
  (assoc (sci/fork (or base-ctx
                       (base {::program-functions []
                              ::exposed-namespaces #{}
                              ::bindings {}})))
         :interrupt-fn interrupt-fn))

;;; ---------------------------------------------------------------------------
;;; Bounded evaluation
;;; ---------------------------------------------------------------------------

(defn evaluation-value?
  "Whether a value is a possible result from SCI evaluation."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [value]
  (or (not (and (map? value)
                (contains? value :seon.error/message)))
      (and (string? (:seon.error/message value))
           (keyword? (:seon.error/kind value))
           (map? (:seon.error/data value)))))

(schema/register-core-predicate!
 'seon.sci.eval/evaluation-value?
 evaluation-value?)

(schema/register! ::source :string)
(schema/register! ::base-ctx 'some?)
(schema/register! ::concurrency 'pos-int?)
(schema/register! ::semaphore-wait-ms :int)
(schema/register!
 ::value
 [:fn
  {:error/message
   "must be a successful SCI value or a complete evaluation error"
   :gen/schema
   [:or
    :nil
    :boolean
    :int
    :double
    :string
    :keyword
    :symbol
    [:vector :int]
    [:map {:closed true}
     [:seon.error/message :string]
     [:seon.error/kind :keyword]
     [:seon.error/data :map]]]}
  'seon.sci.eval/evaluation-value?])
(schema/register!
 ::record
 [:map {:closed true}
  [:seon.eval/fn-entries :int]
  [:seon.eval/duration-ms :int]
  [:seon.eval/allocated-bytes :int]
  [:seon.eval/outcome [:enum :ok :time :error]]
  [::semaphore-wait-ms ::semaphore-wait-ms]])
(schema/register! ::evaluation
                  [:map {:closed true}
                   [::value ::value]
                   [::record ::record]])
(schema/register! ::evaluate-request
                  [:map {:closed true}
                   [::source ::source]
                   [::base-ctx {:optional true} ::base-ctx]
                   [:seon.sci.interrupt/time-limit-ms
                    :seon.sci.interrupt/time-limit-ms]])

(defonce ^:private compute-pool
  (Executors/newCachedThreadPool
   (reify java.util.concurrent.ThreadFactory
     (newThread [_ runnable]
       (doto (Thread. runnable "seon-sci-compute")
         (.setDaemon true))))))

(defonce ^:private permits
  (atom nil))

(defn open!
  "Set the number of concurrent SCI evaluations."
  {:malli/schema
   [:=> [:cat [:map {:closed true} [::concurrency ::concurrency]]]
    :nil]}
  [{::keys [concurrency]}]
  (reset! permits (Semaphore. (int concurrency)))
  nil)

(defn available
  "Available concurrent SCI evaluation permits."
  {:malli/schema [:=> [:cat] :int]}
  []
  (.availablePermits ^Semaphore @permits))

(defn- diagnosis
  [throwable {:seon.eval/keys [outcome duration-ms]}]
  (case outcome
    :time
    (format "Ran out of time after %dms." duration-ms)

    (or (.getMessage ^Throwable throwable)
        (.getName (class throwable)))))

(defn- error-value
  [throwable record]
  (let [exception-data (ex-data throwable)]
    {:seon.error/message (diagnosis throwable record)
     :seon.error/kind (:seon.eval/outcome record)
     :seon.error/data
     (cond->
      {:seon.sci.eval/throwable-class (.getName (class throwable))
       :seon.sci.eval/record record}
       (.getMessage ^Throwable throwable)
       (assoc :seon.sci.eval/raw-message
              (.getMessage ^Throwable throwable))
       (:sci.impl/symbol exception-data)
       (assoc :sci.impl/symbol (:sci.impl/symbol exception-data)))}))

(defn evaluate
  "Evaluate one source form and return a value plus diagnostics."
  {:malli/schema [:=> [:cat ::evaluate-request] ::evaluation]}
  [{::keys [source base-ctx]
    time-limit-ms :seon.sci.interrupt/time-limit-ms}]
  (let [semaphore ^Semaphore @permits
        waiting-at (System/nanoTime)]
    (when-not semaphore
      (throw
       (ex-info "seon.sci.eval/open! must be called before evaluate."
                {:seon.error/kind :configuration})))
    (.acquire semaphore)
    (let [semaphore-wait-ms
          (quot (- (System/nanoTime) waiting-at) 1000000)
          {:keys [interrupt-fn]
           stop! ::interrupt/stop!
           record ::interrupt/record}
          (interrupt/start
           {::interrupt/time-limit-ms time-limit-ms})
          evaluation-ctx
          (fork
           (cond-> {:interrupt-fn interrupt-fn}
             base-ctx (assoc ::base-ctx base-ctx)))
          task
          (.submit
           ^java.util.concurrent.ExecutorService compute-pool
           ^Callable
           (fn []
             (try
               ;; D7: parse inside the armed SCI context. `#=` is refused
               ;; by SCI's reader and never reaches host evaluation.
               (let [form (sci/parse-string evaluation-ctx source)
                     value (sci/eval-form evaluation-ctx form)]
                 {::value value
                  ::record (record :ok)})
               (catch Throwable throwable
                 (let [evaluation-record
                       (record
                        (if (interrupt/interrupted? throwable)
                          :time
                          :error))]
                   {::value (error-value throwable evaluation-record)
                    ::record evaluation-record}))
               (finally
                 (stop!)
                 ;; A blocked host call consumes exactly this one permit until
                 ;; the platform thread actually returns. Other permits remain
                 ;; usable, so one wedge cannot release imaginary capacity.
                 (.release semaphore)))))]
      (try
        (-> (.get task (long time-limit-ms) TimeUnit/MILLISECONDS)
            (assoc-in [::record ::semaphore-wait-ms] semaphore-wait-ms))
        (catch TimeoutException throwable
          (let [evaluation-record (record :time)]
            {::value (error-value throwable evaluation-record)
             ::record
             (assoc evaluation-record
                    ::semaphore-wait-ms semaphore-wait-ms)}))))))

(defn error?
  "Whether a value is Seon's flat error value."
  {:malli/schema [:=> [:catn [::candidate ::value]] :boolean]}
  [candidate]
  (and (map? candidate)
       (contains? candidate :seon.error/message)))
