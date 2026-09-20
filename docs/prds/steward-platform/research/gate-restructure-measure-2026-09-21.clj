;; One foreground JVM in the tested checkout. Supply the same source-root,
;; git-sha and published-base JVM properties as bin/test-fast. Argument: output EDN.
;; Admission/reuse/recording use the existing owners; no forced test execution.
(require '[clojure.java.io :as io] '[datahike.api :as d]
         '[seon.config :as config] '[seon.db :as db]
         '[seon.error-test :as error-test] '[seon.fn :as functions]
         '[seon.schema :as schema] '[seon.sci.eval :as sci.eval]
         '[seon.test :as seon-test] '[seon.test.arm :as arm]
         '[seon.test.bounds :as bounds] '[seon.test.fast :as fast]
         '[seon.test.runner :as runner] '[seon.test-support :as support])
(import '[java.util.concurrent TimeUnit TimeoutException ExecutionException])

(defn- observed [state phase body]
  (let [stack (::stack state) parent (.get ^ThreadLocal stack)
        nested (volatile! 0) completed (volatile! false)
        previous @(::active state) started (System/nanoTime)]
    (.set ^ThreadLocal stack nested)
    (reset! (::active state) phase)
    (try (let [result (body)] (vreset! completed true) result)
         (finally
           (let [elapsed (- (System/nanoTime) started)]
             (when parent (vswap! parent + elapsed))
             (.set ^ThreadLocal stack parent)
             (reset! (::active state) previous)
             (swap! (::events state) conj
                    {::phase phase ::completed? @completed ::inclusive-ns elapsed
                     ::exclusive-ns (- elapsed @nested)}))))))

(defn- bounded [state phase seconds body]
  (let [work (future-call (#'runner/on-caller-loader #(observed state phase body)))]
    (try (.get ^java.util.concurrent.Future work seconds TimeUnit/SECONDS)
         (catch ExecutionException failure (throw (.getCause failure)))
         (catch TimeoutException _
           (future-cancel work)
           (throw (ex-info "Measured phase exceeded its declared bound."
                           {::phase phase ::active-phase @(::active state) ::seconds seconds}))))))

(defn- distribution [values]
  (when (seq values)
    (let [v (vec (sort values)) n (count v)]
      {::count n ::median-ms (/ (+ (v (quot (dec n) 2)) (v (quot n 2))) 2000000.0)
       ::p90-ms (/ (v (dec (long (Math/ceil (* 0.9 n))))) 1000000.0)
       ::max-ms (/ (peek v) 1000000.0)})))

(defn- fixture-sample [state ordinal]
  (let [before (count @(::events state))
        result (bounded state ::ordinary-fixture support/event-backstop-seconds
                        (fn []
                          (let [started (System/nanoTime) begin (volatile! nil) end (volatile! nil)]
                            (support/with-database
                             (fn [connection]
                               (vreset! begin (System/nanoTime))
                               (observed state ::ordinary-body #(db/basis-t (db/db connection)))
                               (vreset! end (System/nanoTime))))
                            (let [ended (System/nanoTime)]
                             {::ordinal ordinal ::total-ns (- ended started)
                             ::setup-ns (- @begin started) ::body-ns (- @end @begin)
                             ::cleanup-ns (- ended @end)}))))
        counts (frequencies (map ::phase (subvec (vec @(::events state)) before)))]
    (when-not (and (= 1 (get counts ::branch 0)) (= 1 (get counts ::delete-branch 0))
                   (every? #(zero? (get counts % 0)) [::population ::analysis ::sci-base]))
      (throw (ex-info "Warmed fixture exceeded its structural work budget." {::counts counts})))
    (assoc result ::counts counts)))

(defn- probe [state admission]
  (let [owners {#'support/populate-database! ::population #'functions/build-manifest ::analysis
                #'sci.eval/build-base-ctx ::sci-base #'d/branch! ::branch #'d/connect ::connect
                #'support/reconnect-with-projection ::connection-state
                #'config/apply! ::config #'support/seed-cluster! ::cluster-seed
                #'seon-test/resolve-test ::resolution #'d/release ::release
                #'d/delete-branch! ::delete-branch}
        wrappers (into {} (map (fn [[v phase]]
                                (let [original @v]
                                  [v (fn [& args] (observed state phase #(apply original args)))]))) owners)
        with-db @#'error-test/with-db
        wrappers (assoc wrappers #'error-test/with-db
                        (fn [body]
                          (observed state ::recurrence-fixture
                                    #(with-db (fn [connection]
                                                (observed state ::recurrence-body (fn [] (body connection))))))))]
    (with-redefs-fn wrappers
      (fn []
        (let [base (if admission
                     (deref (deref #'support/database-base))
                     (bounded state ::base-readiness (bounds/exchange-seconds 0 bounds/fixture-priming-ms)
                              #(deref (deref #'support/database-base))))]
          (when-not (::support/connection base)
            (throw (ex-info "Canonical base unavailable." {::base base})))
          (when-not admission
            (dotimes [ordinal 10]
              (swap! (::samples state) conj (fixture-sample state ordinal))))
          (if (seq (:seon.test.run/members admission))
            (bounded state ::recurrence-test (bounds/exchange-seconds 0 bounds/fixture-priming-ms)
                     #(#'runner/run-task!
                       {:seon.test.runner/task-symbols [(::target state)]}
                       {:seon.db/db (db/db (::support/connection base))
                        :seon.db/connection (::support/connection base)
                        :seon.sci.eval/ctx (:seon.sci.eval/ctx base)
                        :seon.schema/projection (:seon.schema/projection (:seon.sci.eval/ctx base))
                        :seon.test/class-loader (clojure.lang.RT/baseLoader)}))
            {:seon.test.runner/task-results []}))))))

(defn- run-probe [state]
  (let [arming (bounded state ::arming (bounds/exchange-seconds 0 bounds/fixture-priming-ms)
                       #(#'arm/initialize-contracts! "gate-measure" ['seon.error-test]))]
    (schema/call-with-projection
     (:seon.test.runner/projection arming)
     (fn []
       ;; Ordinary fixture probes are measurements, not test executions.
       ;; Keep their evidence even if the separate real-test admission refuses.
       (probe state nil)
       (let [request (-> (#'fast/snapshot-request ['seon.error-test])
                         (dissoc :seon.test/namespaces)
                         (assoc :seon.test/identities #{(::target state)})
                         (update :seon.test.run/members
                                 #(filterv (fn [m] (= (::target state) (:seon.test.member/symbol m))) %)))
             admission (bounded state ::admission (bounds/exchange-seconds 0 bounds/fixture-priming-ms)
                                #(runner/record-snapshot! (::root state) request))]
         (when-not (:seon.test.run/provenance admission)
           (throw (ex-info "Measurement admission refused." {::admission admission})))
         (let [provenance (:seon.test.run/provenance admission)
               outcome (try (probe state admission) (catch Throwable failure failure))
               recorded (bounded state ::recording (bounds/exchange-seconds 0 bounds/fixture-priming-ms)
                                 #(runner/record-snapshot!
                                   (::root state)
                                   {:seon.test.run/provenance provenance
                                    :seon.test/run-basis-t (:seon.test.run/basis-t provenance)
                                    :seon.test/run-at (:seon.test.run/at provenance)
                                    :seon.test.run/terminated? true
                                    :seon.test.runner/results (vec (:seon.test.runner/task-results outcome))}))]
           (when-not (vector? recorded)
             (throw (ex-info "Measurement completion was not recorded." {::recording recorded})))
           (when (instance? Throwable outcome) (throw outcome))
           (let [facts (bounded state ::recorded-tally (bounds/exchange-seconds 0 0)
                                #(runner/recorded-run! (::root state) (:seon.test.run/id provenance)))]
             (when-not (vector? facts)
               (throw (ex-info "Recorded measurement facts unavailable." {::facts facts})))
             {::provenance provenance ::task-output (:seon.test.runner/task-output outcome)
              ::results (:seon.test.runner/task-results outcome)
              ::tally (runner/print-recorded-tally! facts)})))))))

(let [state {::stack (ThreadLocal.) ::active (atom ::initialization) ::events (atom []) ::samples (atom [])
             ::root (System/getProperty "seon.test.source-root" ".")
             ::target 'seon.error-test/recurrence-counting-does-not-require-a-notification-threshold}
      output (or (first *command-line-args*) "tmp/gate-restructure/measure.edn")
      result (try (run-probe state)
                  (catch Throwable failure {::failure {::message (ex-message failure) ::data (ex-data failure)}}))
      samples @(::samples state)
      result (assoc result ::samples samples ::events @(::events state) ::first (first samples)
                    ::subsequent (into {} (map (fn [phase] [phase (distribution (map phase (rest samples)))]))
                                       [::total-ns ::setup-ns ::body-ns ::cleanup-ns])
                    ::phase-counts (frequencies (map ::phase @(::events state)))
                    ::phase-durations
                    (into {} (map (fn [[phase events]]
                                    [phase {::inclusive (distribution (map ::inclusive-ns events))
                                            ::exclusive (distribution (map ::exclusive-ns events))}]))
                          (group-by ::phase (filter ::completed? @(::events state)))))]
  (io/make-parents output)
  (spit output (str (pr-str result) "\n"))
  (prn (dissoc result ::events ::samples))
  (shutdown-agents)
  (when (or (::failure result)
            (pos? (+ (get-in result [::tally :seon.test.runner/fail-count] 0)
                     (get-in result [::tally :seon.test.runner/error-count] 0))))
    (throw (ex-info "Measurement or its admitted test failed." {::output output}))))
