(ns seon.sci.kernel
  "The one guarded operation shared by evaluation and renderer calls.

  SCI calls the stable interrupt function at interpreted function-body and
  loop entrances. One process guard keeps per-thread arm state; an invocation
  either owns a new arm or inherits the identical context's active arm. Values
  are admitted before disarm. Host calls remain SCI's documented interruption
  ceiling and are never described as hard-stoppable."
  (:require [sci.core :as sci]
            [sci.impl.utils :as sci.utils]
            [sci.interrupt :as sci.interrupt]
            [seon.db :as db]
            [seon.error.refusal :as error.refusal]
            [seon.sci.admit :as admit])
  (:import [java.lang.management ManagementFactory]
           [java.util.concurrent Future ScheduledThreadPoolExecutor TimeUnit]
           [java.util.concurrent.atomic AtomicBoolean]))

(defn interrupted?
  "True when a throwable or one of its causes is SCI's interrupt."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [throwable]
  (loop [candidate throwable]
    (cond
      (nil? candidate) false
      (sci.utils/interrupt-ex? candidate) true
      :else (recur (ex-cause candidate)))))

(def ^:private thread-mx (ManagementFactory/getThreadMXBean))

(defn- allocated-bytes
  []
  (.getCurrentThreadAllocatedBytes
   ^com.sun.management.ThreadMXBean thread-mx))

(defonce ^:private ^ScheduledThreadPoolExecutor deadline-timer
  (doto (ScheduledThreadPoolExecutor.
         1
         (reify java.util.concurrent.ThreadFactory
           (newThread [_ runnable]
             (doto (Thread. runnable "seon-sci-time-limit")
               (.setDaemon true)))))
    (.setRemoveOnCancelPolicy true)))

(defn- new-guard
  []
  (let [thread-arm (ThreadLocal.)
        interrupt-fn
        (fn []
          (when-let [armed (.get ^ThreadLocal thread-arm)]
            (let [^longs entries (::entries armed)
                  ^longs sampled (::sampled armed)
                  ^AtomicBoolean reached (::reached armed)
                  outcome (::outcome armed)
                  ^long allocated-at-start (::allocated-at-start armed)
                  entrance-count (unchecked-inc (aget entries 0))]
              (aset entries 0 (long entrance-count))
              (when (.get reached)
                (vreset! outcome :time)
                (sci.interrupt/interrupt! "time-limit"))
              (when (and (::measurable armed)
                         (zero? (bit-and entrance-count 1023)))
                (aset sampled 0
                      (long (- (allocated-bytes)
                               allocated-at-start)))))))
        host-interop-observer
        (fn []
          (when-let [armed (.get ^ThreadLocal thread-arm)]
            (let [^longs observations (::host-interop-observations armed)]
              (aset observations 0
                    (long (unchecked-inc (aget observations 0)))))))
        built-in-call-observer
        (fn [qualified-symbol]
          (when-let [armed (.get ^ThreadLocal thread-arm)]
            (vswap! (::built-in-calls armed) conj qualified-symbol)))]
    {::thread-arm thread-arm
     ::interrupt-fn interrupt-fn
     ::host-interop-observer host-interop-observer
     ::built-in-call-observer built-in-call-observer}))

(defonce ^:private process-guard (delay (new-guard)))

(defn context-options
  "SCI initialization options backed by the one process guard."
  {:malli/schema [:=> [:cat] :map]}
  []
  (let [guard @process-guard]
    {::guard guard
     :interrupt-fn (::interrupt-fn guard)
     :host-interop-observer (::host-interop-observer guard)
     :built-in-call-observer (::built-in-call-observer guard)}))

(defn cache-program!
  "Publish immutable function and namespace rows used by lazy installation."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :map :map]
                  :nil]}
  [ctx functions namespaces]
  (reset! (::program-snapshot ctx)
          {:functions functions :namespaces namespaces})
  nil)

(defn cache-function!
  "Publish one committed function row into the live program snapshot."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :qualified-symbol :map]
                  :qualified-symbol]}
  [ctx function-symbol row]
  (swap! (::program-snapshot ctx) assoc-in
         [:functions function-symbol] row)
  function-symbol)

(defn program-function
  "Return one cached function row from the acquired program snapshot."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :qualified-symbol]
                  [:or :nil :map]]}
  [ctx function-symbol]
  (get-in @(::program-snapshot ctx) [:functions function-symbol]))

(defn program-namespace
  "Return one cached namespace row from the acquired program snapshot."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.ns/name]
                  [:or :nil :map]]}
  [ctx namespace-name]
  (get-in @(::program-snapshot ctx) [:namespaces namespace-name]))

(defn public-functions-in
  "Return public contracted functions in `namespace-name` from the snapshot."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.ns/name]
  [:vector :qualified-symbol]]}
  [ctx namespace-name]
  (->> (:functions @(::program-snapshot ctx))
       (keep (fn [[function-symbol row]]
               (when (and (= namespace-name
                             (symbol (namespace function-symbol)))
                          (false? (:seon.sci.eval/function-private? row)))
                 function-symbol)))
       (sort-by str)
       vec))

(defn mark-installed!
  "Record that `function-symbol` has been installed from database source."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :qualified-symbol]
                  :qualified-symbol]}
  [ctx function-symbol]
  (swap! (::installed-functions ctx) conj function-symbol)
  function-symbol)

(defn ensure-function!
  "Install `function-symbol` from its database row once per live context."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx
                       :seon.db/database-value
                       :qualified-symbol]
                  :qualified-symbol]}
  [ctx database function-symbol]
  (when-not (contains? @(::installed-functions ctx) function-symbol)
    (if-let [install! (::install-function! ctx)]
      (install! ctx database function-symbol)
      (throw
       (ex-info "SCI context has no database-program installer."
                {:seon.error/kind ::missing-function-installer
                 :seon.fn/sym (str function-symbol)}))))
  function-symbol)

(defn context-projection
  "Return the latest immutable schema projection held by `ctx`."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx]
                  [:or :nil :seon.schema/projection]]}
  [ctx]
  (or (some-> (:seon.sci.eval/projection-state ctx)
              deref
              :seon.schema/projection)
      (:seon.schema/projection ctx)))

(defn- record
  [armed final-outcome]
  (let [^longs entries (::entries armed)
        ^longs sampled (::sampled armed)
        ^longs host-interop-observations (::host-interop-observations armed)
        started-at (::started-at armed)
        allocated-at-start (::allocated-at-start armed)]
    {:seon.eval/fn-entries (aget entries 0)
     :seon.eval/host-interop-count (aget host-interop-observations 0)
     :seon.eval/duration-ms
     (quot (- (System/nanoTime) started-at) 1000000)
     :seon.eval/allocated-bytes
     (if (::measurable armed)
       (max (aget sampled 0) (- (allocated-bytes) allocated-at-start))
       -1)
     :seon.eval/outcome (or @(::outcome armed) final-outcome)}))

(defn- own-arm
  [ctx guard time-limit-ms]
  (let [^ThreadLocal thread-arm (::thread-arm guard)
        allocated-at-start (allocated-bytes)
        armed {::ctx ctx
               ::entries (long-array 1)
               ::sampled (long-array 1)
               ::host-interop-observations (long-array 1)
               ::built-in-calls (volatile! #{})
               ::reached (AtomicBoolean. false)
               ::outcome (volatile! nil)
               ::started-at (System/nanoTime)
               ::allocated-at-start allocated-at-start
               ::measurable (not (neg? allocated-at-start))}]
    (.set thread-arm armed)
    (try
      (let [task (.schedule deadline-timer
                            ^Runnable #(.set ^AtomicBoolean (::reached armed)
                                             true)
                            (long time-limit-ms)
                            TimeUnit/MILLISECONDS)]
        {:interrupt-fn (::interrupt-fn guard)
         ::built-in-calls (fn [] @(::built-in-calls armed))
         ::stop!
         (fn []
           (.cancel ^Future task false)
           (.set ^AtomicBoolean (::reached armed) false)
           (.remove thread-arm)
           nil)
         ::record #(record armed %)})
      (catch Throwable failure
        (.remove thread-arm)
        (throw failure)))))

(defn arm
  "Arm `ctx` on this thread and return its stop, record, and observers.

  ONE re-entrancy rule for every entrance, because a second rule is how the
  two boundaries diverged: work reached while the IDENTICAL context is
  already armed on this thread INHERITS that arm — the outer deadline keeps
  governing, the returned `::stop!` is inert, and no second timer exists, so
  nested work can never restart the clock and outlive the limit that admitted
  it. A DIFFERENT context on an armed thread is refused, because one thread
  cannot honestly serve two limits. `::built-in-calls` reports the governing
  arm's observations either way."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx
                       :seon.sci.eval/time-limit-ms]
                  :map]}
  [ctx time-limit-ms]
  (let [guard (::guard ctx)]
    (when-not guard
      (throw
       (ex-info "SCI context has no stable interrupt guard."
                {:seon.error/kind ::missing-interrupt-guard})))
    (if-let [armed (.get ^ThreadLocal (::thread-arm guard))]
      (do
        (when-not (identical? ctx (::ctx armed))
          (throw
           (ex-info "A different SCI context is already armed on this thread."
                    {:seon.error/kind ::already-armed})))
        {:interrupt-fn (::interrupt-fn guard)
         ::built-in-calls (fn [] @(::built-in-calls armed))
         ::stop! (constantly nil)
         ::record #(record armed %)})
      (own-arm ctx guard time-limit-ms))))

(defn failure-value
  "The ONE flat `:seon.error` value for a failure at the guarded boundary.

  Both entrances classify here so they cannot drift apart. A throwable that
  already carries a refusal — an instrument contract violation, a refused
  schema declaration, an unresolved invocation — keeps its own
  `:seon.error/kind` and gains this boundary's evidence; everything else
  becomes `::time-limit-kind` when the diagnostic record's outcome is `:time`
  and `::failure-kind` otherwise. `:seon.fn/sym` is the invoked function
  symbol when one exists: it prefixes the message and rides in the data. A
  form evaluation supplies no symbol, which is the ONLY difference between
  the two entrances — the classification itself is identical."
  {:malli/schema [:=> [:cat :seon.sci.kernel/failure-request
                       :any
                       :seon.sci.admit/record]
                  :seon.error/value]}
  [{subject :seon.fn/sym
    time-limit-kind ::time-limit-kind
    failure-kind ::failure-kind}
   throwable
   diagnostic-record]
  (let [timed-out? (= :time (:seon.eval/outcome diagnostic-record))
        evidence (cond-> {:seon.sci.eval/throwable (.getName (class throwable))
                          :seon.sci.admit/record diagnostic-record}
                   subject (assoc :seon.fn/sym subject))
        existing (error.refusal/refusal throwable)]
    (if (:seon.error/kind existing)
      (cond-> (update existing :seon.error/data merge evidence)
        ;; A refusal is raised as ex-info data that need not repeat the
        ;; message, but an error VALUE must always say what happened —
        ;; the run loop reads its presence as the failed state, and a
        ;; refusal preserved without one stored a nil there.
        (not (:seon.error/message existing))
        (assoc :seon.error/message
               (or (ex-message throwable) (.getName (class throwable)))))
      (cond-> {:seon.error/kind (if timed-out? time-limit-kind failure-kind)
               :seon.error/message
               (cond->> (if timed-out?
                          (str "Ran out of time after "
                               (:seon.eval/duration-ms diagnostic-record) "ms.")
                          (or (ex-message throwable)
                              (.getName (class throwable))))
                 subject (str "Invocation of " subject " failed: "))
               :seon.error/data evidence}
        (ex-data throwable)
        (assoc-in [:seon.error/data :seon.sci.eval/data]
                  (ex-data throwable))))))

(defn unarmed-record
  "The diagnostic record for a failure that never reached an arm."
  {:malli/schema [:=> [:cat :int] :seon.sci.admit/record]}
  [started-at]
  {:seon.eval/fn-entries 0
   :seon.eval/host-interop-count 0
   :seon.eval/duration-ms
   (quot (- (System/nanoTime) started-at) 1000000)
   :seon.eval/allocated-bytes -1
   :seon.eval/outcome :error})

(defn invoke
  "Resolve and invoke one live SCI Var, admitting its value before disarm."
  {:malli/schema [:=> [:cat :seon.sci.eval/invocation-request]
                  :seon.sci.eval/invocation-result]}
  [{ctx :seon.sci.eval/ctx
    database :seon.db/db
    function-symbol-string :seon.fn/sym
    arguments :seon.sci.eval/args
    time-limit-ms :seon.sci.eval/time-limit-ms
    caps :seon.sci.admit/caps
    read-evidence-sink :seon.db/read-evidence-sink
    on-core-error :seon.config/on-core-error}]
  (let [started-at (System/nanoTime)
        arm-state (volatile! nil)
        function-symbol (symbol function-symbol-string)]
    (try
      (let [{:keys [interrupt-fn] record-fn ::record :as armed}
            (arm ctx time-limit-ms)]
        (vreset! arm-state armed)
        (ensure-function! ctx database function-symbol)
        (binding [db/*conn*
                  (get-in ctx
                          [:seon.sci.eval/custody
                           :seon.db/connection])
                  db/*read-evidence-sink*
                  (or read-evidence-sink db/*read-evidence-sink*)]
          (let [sci-var (sci/resolve ctx function-symbol)]
            (when-not (sci.utils/var? sci-var)
              (throw
               (ex-info (str function-symbol " is not an installed SCI Var.")
                        {:seon.error/kind ::unresolved-invocation
                         :seon.fn/sym function-symbol-string})))
            (let [value (apply sci-var arguments)
                  invocation-record (record-fn :ok)]
              (admit/admit-value
               {:seon.sci.admit/value value
                :seon.sci.admit/interrupt-fn interrupt-fn
                :seon.sci.admit/caps caps
                :seon.config/on-core-error on-core-error
                :seon.sci.admit/record invocation-record})))))
      (catch Throwable throwable
        (let [record-value
              (if-let [record-fn (::record @arm-state)]
                (record-fn (if (interrupted? throwable) :time :error))
                (unarmed-record started-at))
              failure (failure-value
                       {:seon.fn/sym function-symbol-string
                        ::time-limit-kind ::time-limit
                        ::failure-kind ::invocation-failed}
                       throwable record-value)]
          (try
            (admit/admit-value
             {:seon.sci.admit/value failure
              :seon.sci.admit/interrupt-fn (constantly nil)
              :seon.sci.admit/caps caps
              :seon.config/on-core-error :record
              :seon.sci.admit/record record-value})
            (catch Throwable admission-failure
              {:seon.sci.admit/value
               {:seon.error/kind ::failure-admission-failed
                :seon.error/message
                (str (:seon.error/message failure)
                     " Failure admission also failed: "
                     (or (ex-message admission-failure)
                         (.getName (class admission-failure))))
                :seon.error/data
                {:seon.sci.eval/throwable
                 (.getName (class admission-failure))}}
               :seon.sci.admit/capped? false
               :seon.sci.admit/record record-value}))))
      (finally
        (when-let [stop! (::stop! @arm-state)]
          (try (stop!) (catch Throwable _ nil)))))))
