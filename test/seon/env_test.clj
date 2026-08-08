(ns ^{:seon.test/platform
       "Moving part: environment construction and carriage across every crossing."}
    seon.env-test
  "The environment-isolation class regressions.

  These graduate the three Phase 0 falsifiers of the seon.env PRD
  (2026-08-07) — fork carriage, flow carriage, and construction refusal
  — into the one place a runner sees them. Each proves a CLASS: that
  code which crossed a thread can name its own cluster, and that a
  crossing which names none is refused where it is built."
  (:require [clojure.test :refer [deftest is testing]]
            [sci.core :as sci]
            [seon.db :as db]
            [seon.effect :as effect]
            [seon.env :as sut]
            [seon.flow :as flow]
            [seon.sci.eval :as eval]
            [seon.sci.kernel :as kernel]
            [seon.test-support :as test-support])
  (:import [java.util.concurrent Executors]))

;;; ---------------------------------------------------------------------------
;;; Construction
;;; ---------------------------------------------------------------------------

(deftest construction-refuses-up-front-and-names-the-failed-layer
  (testing "an absent required member refuses as a flat value"
    (let [refusal (sut/environment {})]
      (is (not (sut/environment? refusal)))
      (is (= :seon.env/incomplete-environment (:seon.error/kind refusal)))
      (is (= :store (get-in refusal [:seon.error/data :seon.env/layer])))
      (is (= :seon.boot/cluster-name
             (get-in refusal [:seon.error/data :seon.env/member])))))

  (testing "boot refuses at the FIRST layer that did not stand"
    (let [refusal (sut/boot-environment {:seon.boot/cluster-name "layers"})]
      (is (= :branch (get-in refusal [:seon.error/data :seon.env/layer])))
      (is (= :seon.db/connection
             (get-in refusal [:seon.error/data :seon.env/member])))))

  (testing "subset construction is ordinary: fewer layers, same constructor"
    (let [environment (sut/environment {:seon.boot/cluster-name "subset"})]
      (is (sut/environment? environment))
      (is (= "subset" (:seon.boot/cluster-name environment)))
      (is (nil? (:seon.flow/work-launcher environment)))))

  (testing "maps are open: an undeclared member is carried, never refused"
    (is (sut/environment?
         (sut/environment {:seon.boot/cluster-name "open"
                           :seon.env-test/not-yet-declared 1}))))

  (testing "the declared members are read from the one schema"
    (let [members (sut/members)]
      (is (= :seon.boot/cluster-name
             (:seon.env/member (first members)))
          "dependency order starts at the store layer")
      (is (contains? (into #{} (map :seon.env/member) members)
                     :seon.db/read-evidence-sink)
          "the read-evidence sink is a declared optional member")))

  (testing "scoping may narrow only turn-layer members"
    (let [environment (sut/environment {:seon.boot/cluster-name "scope"})]
      (is (= "agent-a"
             (:seon.cluster.agent/id
              (sut/scope environment {:seon.cluster.agent/id "agent-a"}))))
      (is (= :seon.env/unscopable-member
             (:seon.error/kind
              (sut/scope environment {:seon.db/connection ::not-a-connection})))))))

;;; ---------------------------------------------------------------------------
;;; Fork carriage
;;; ---------------------------------------------------------------------------

(defn- environment-reading-fork
  "One sci fork carrying its own environment plus a leaf that reads it."
  [base cluster-name]
  (let [environment (sut/environment {:seon.boot/cluster-name cluster-name})
        ctx (sut/carry (sci/fork base) environment)]
    (sci/intern ctx (sci/create-ns 'my.env) 'cluster-name
                (fn [] (:seon.boot/cluster-name (sut/of ctx))))
    ctx))

(deftest a-fork-resolves-its-own-environment-across-a-thread-hop
  (let [base (sut/carry (sci/init {})
                        (sut/environment {:seon.boot/cluster-name "BASE"}))
        fork-count 16
        rounds 4
        forks (mapv #(environment-reading-fork base (str "cluster-" %))
                    (range fork-count))
        callables (mapv #(sci/eval-string* % "(fn [] (my.env/cluster-name))")
                        forks)
        executor (Executors/newVirtualThreadPerTaskExecutor)]
    (try
      (let [observations
            (vec
             (for [_ (range rounds)
                   result (.invokeAll
                           executor
                           (mapv (fn [ordinal]
                                   (let [callable (nth callables ordinal)]
                                     ^{:type Object}
                                     (reify java.util.concurrent.Callable
                                       (call [_]
                                         [ordinal (callable)]))))
                                 (range fork-count)))]
               (.get ^java.util.concurrent.Future result)))
            mismatches
            (remove (fn [[ordinal observed]]
                      (= (str "cluster-" ordinal) observed))
                    observations)]
        (is (= (* fork-count rounds) (count observations)))
        (is (= [] (vec mismatches))
            "every fork's code resolved ITS fork's environment off-thread")
        (is (= "BASE" (:seon.boot/cluster-name (sut/of base)))
            "no fork's assoc reached the shared base"))
      (finally
        (.shutdownNow executor)))))

;;; ---------------------------------------------------------------------------
;;; Flow carriage
;;; ---------------------------------------------------------------------------

(def ^:private launcher-configuration
  {:seon.config.flow.compute/queue-depth 8
   :seon.config.flow.compute/concurrency 2
   :seon.config.flow.io/queue-depth 8
   :seon.config.flow.io/concurrency 4})

(deftest a-submission-delivers-exactly-its-own-environment
  (let [environments (mapv #(test-support/environment (str "launcher-" %))
                           (range 2))
        launchers (mapv (fn [environment]
                          (flow/start-work-launcher!
                           {:seon.env/environment environment
                            :seon.flow/configuration launcher-configuration}))
                        environments)
        io-observed (atom [])
        compute-observed (atom [])
        complete-observed (atom [])
        per-launcher 8]
    (try
      ;; A decoy in the dynamic carriers: work that reads a binding frame
      ;; instead of its submission returns the decoy and fails loudly.
      (binding [db/*conn* ::decoy-connection
                effect/*request-context* {:seon.env/marker ::decoy-context}]
        (doseq [[ordinal launcher] (map-indexed vector launchers)
                n (range per-launcher)
                :let [environment (nth environments ordinal)]]
          (is (true?
               (flow/submit!
                launcher
                {:seon.env/environment environment
                 :seon.flow/submission-id (keyword (str "io-" ordinal "-" n))
                 :seon.flow/workload :io
                 :seon.flow/work-fn
                 (fn [call]
                   (swap! io-observed conj
                          [ordinal
                           (:seon.boot/cluster-name (sut/of call))
                           db/*conn*])
                   ::io-done)
                 :seon.flow/complete!
                 (fn [terminal]
                   (swap! complete-observed conj
                          [ordinal
                           (:seon.boot/cluster-name (sut/of terminal))]))})))
          (is (= :seon.flow/completed
                 (:seon.flow/outcome
                  (flow/submit!!
                   launcher
                   {:seon.env/environment environment
                    :seon.flow/submission-id
                    (keyword (str "compute-" ordinal "-" n))
                    :seon.flow/workload :compute
                    :seon.flow/time-limit-ms 15000
                    :seon.flow/work-fn
                    (fn [call]
                      (swap! compute-observed conj
                             [ordinal
                              (:seon.boot/cluster-name (sut/of call))])
                      ::compute-done)}))))))
      (test-support/await-event!
       (future
         (while (< (count @complete-observed) (* 2 per-launcher))
           (Thread/sleep 5))
         ::settled)
       ::io-terminals-settled)

      (let [expected (fn [ordinal] (str "launcher-" ordinal))
            wrong (fn [rows]
                    (vec (remove (fn [[ordinal observed & _]]
                                   (= (expected ordinal) observed))
                                 rows)))]
        (is (= (* 2 per-launcher) (count @io-observed)))
        (is (= [] (wrong @io-observed))
            "io work read exactly its own submission's environment")
        (is (= [] (wrong @compute-observed))
            "compute work read exactly its own submission's environment")
        (is (= [] (wrong @complete-observed))
            "each terminal callback read exactly its submission's environment")
        (is (every? nil? (map #(nth % 2) @io-observed))
            "io ran with the dynamic carrier at its root nil, as the audit found"))
      (finally
        (run! flow/stop-work-launcher! launchers)))))

(deftest a-submission-carries-the-submitting-threads-interrupt-arm
  (let [environment (test-support/environment "arm-carriage")
        launcher (flow/start-work-launcher!
                  {:seon.env/environment environment
                   :seon.flow/configuration launcher-configuration})
        ctx (eval/build-base-ctx)]
    (try
      (testing "an armed submitter's arm reaches the io thread"
        (let [observed (promise)
              armed (kernel/arm ctx 15000)
              submitting-arm (atom nil)]
          (try
            (reset! submitting-arm (kernel/current-arm))
            (flow/submit!
             launcher
             {:seon.env/environment environment
              :seon.flow/submission-id ::armed-io
              :seon.flow/workload :io
              :seon.flow/work-fn
              (fn [call]
                (deliver observed
                         {:carried (:seon.sci.kernel/arm (sut/of call))
                          :adopted (kernel/current-arm)})
                ::done)
              :seon.flow/complete! (fn [_])})
            (finally
              ((:seon.sci.kernel/stop! armed))))
          (let [{:keys [carried adopted]}
                (test-support/await-event! (future @observed) ::armed-io)]
            (is (identical? @submitting-arm carried)
                "the submission carried the submitting thread's arm as data")
            (is (identical? @submitting-arm adopted)
                "the io thread ran under that same arm, not unarmed"))))

      (testing "an unarmed submitter carries no arm, which is not a refusal"
        (let [observed (promise)]
          (is (true?
               (flow/submit!
                launcher
                {:seon.env/environment environment
                 :seon.flow/submission-id ::unarmed-io
                 :seon.flow/workload :io
                 :seon.flow/work-fn
                 (fn [call]
                   (deliver observed
                            {:carried (:seon.sci.kernel/arm (sut/of call))
                             :adopted (kernel/current-arm)})
                   ::done)
                 :seon.flow/complete! (fn [_])})))
          (let [{:keys [carried adopted]}
                (test-support/await-event! (future @observed) ::unarmed-io)]
            (is (nil? carried))
            (is (nil? adopted)))))
      (finally
        (flow/stop-work-launcher! launcher)))))

;;; ---------------------------------------------------------------------------
;;; Refusal at the crossings
;;; ---------------------------------------------------------------------------

(defn- inert-step
  ([] {:workload :io})
  ([args] args)
  ([state _transition] state)
  ([state _input _message] [state nil]))

(deftest a-crossing-that-names-no-environment-is-refused-where-it-is-built
  (testing "the proc construction door"
    (let [refusal (test-support/refusal-data
                   #(flow/var-process #'inert-step :io {}))]
      (is (= :seon.env/absent-environment (:seon.error/kind refusal)))
      (is (= :seon.flow/var-process
             (get-in refusal [:seon.error/data :seon.env/boundary])))))

  (testing "the work launcher"
    (is (= :seon.env/absent-environment
           (:seon.error/kind
            (test-support/refusal-data
             #(flow/start-work-launcher!
               {:seon.flow/configuration launcher-configuration}))))))

  (let [environment (test-support/environment "refusal")
        launcher (flow/start-work-launcher!
                  {:seon.env/environment environment
                   :seon.flow/configuration launcher-configuration})]
    (try
      (testing "the io submission"
        (let [refusal (test-support/refusal-data
                       #(flow/submit!
                         launcher
                         {:seon.flow/submission-id ::no-environment
                          :seon.flow/workload :io
                          :seon.flow/work-fn (fn [_] ::unreached)
                          :seon.flow/complete! (fn [_])}))]
          (is (= :seon.env/absent-environment (:seon.error/kind refusal)))
          (is (= :seon.flow/submit!
                 (get-in refusal [:seon.error/data :seon.env/boundary])))))

      (testing "the compute submission"
        (let [refusal (test-support/refusal-data
                       #(flow/submit!!
                         launcher
                         {:seon.flow/submission-id ::no-environment
                          :seon.flow/workload :compute
                          :seon.flow/time-limit-ms 1000
                          :seon.flow/work-fn (fn [_] ::unreached)}))]
          (is (= :seon.env/absent-environment (:seon.error/kind refusal)))
          (is (= :seon.flow/submit!!
                 (get-in refusal [:seon.error/data :seon.env/boundary])))))
      (finally
        (flow/stop-work-launcher! launcher)))))
