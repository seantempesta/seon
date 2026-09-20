(ns seon.test.fast
  "`bin/test-fast NS…`: one armed JVM and the shared result authority,
  with no base publication in the iteration path.
  It arms contracts exactly as a `bin/test` worker does (`seon.test.arm`)
  and runs the named namespaces with clojure.test. What it does NOT prove:
  isolation per worker, retained roots, the platform tier — those are
  `bin/test --paths … -- …` per commit and `--platform` per lane."
  (:require [clojure.test :as test]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [seon.cluster.source :as source]
            [seon.id :as id]
            [seon.schema :as schema]
            [seon.test.arm :as arm]
            [seon.test.cache :as cache]
            [seon.test.selection :as selection]
            [seon.test.runner :as runner]))

(defn- snapshot-request
  "Identify the tested files and retain the one resolved published base."
  {:malli/schema [:=> [:cat [:vector :symbol]] :seon.source/test-selection-request]}
  [namespaces]
  (let [source-root (System/getProperty "seon.test.source-root" ".")
        git-sha (System/getProperty "seon.test.git-sha")
        staged (io/file "tmp/published-base.edn")
        base (if (.isFile staged) (edn/read-string (slurp staged))
                 (cache/newest-base source-root git-sha))
        provenance (edn/read-string (slurp (io/file (:seon.test.cache/base base) "provenance.edn")))
        inputs (cache/input-digests ".")
        external (cache/test-input-digest "." inputs)
        program (:seon.source/digest
                 (source/snapshot {:seon.fn/root "."
                                   :seon.source/roots @(requiring-resolve 'seon.cluster/source-roots)}))]
    {:seon.test.run/provenance
     (merge (selection/overlay-provenance ".")
            {:seon.test.run/id (id/id) :seon.test.run/at (java.util.Date.)
             :seon.test.run/git-sha git-sha
             :seon.test.run/published-base-digest (:seon.test.cache/digest base)
             :seon.test.run/overlay-input-digest
             (id/digest 64 [(into (sorted-map) (cache/source-inputs inputs)) external])
             :seon.test.run/program-digest program
             :seon.test.run/basis-t (:seon.test.run/basis-t provenance)
             :seon.test.run/branch :current-src})
     :seon.test.run/input-digest external
     :seon.test.run/policy :named :seon.test/include-long? true
     :seon.test/namespaces (set namespaces)
     :seon.test.run/members
     (mapv (fn [v] {:seon.test.member/symbol (#'runner/var-symbol v)
                     :seon.test.member/reasons #{:named}})
           (#'runner/test-vars-in namespaces))}))

(defn -main
  "Arm contracts, run the named test namespaces, exit with the tally."
  {:malli/schema [:=> [:cat [:* {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Clojure's command-line entry point receives any number of string arguments; the command parser owns option combinations and their diagnostics.", :gen/elements [[]]} :string]] :nil]}
  [& args]
  (let [namespaces (mapv symbol args)
      started (java.time.Instant/now)
      progress (atom {:seon.test.runner/description "test-fast initialization"
                      :seon.test.runner/at-nanos (System/nanoTime)
                      :seon.test.runner/at started})
      backstop (#'runner/start-liveness-backstop!
                progress (#'runner/silence-seconds) started)
      exit-code
      (try
        (let [_ (when (empty? namespaces)
                  (throw (ex-info "At least one test namespace is required."
                                  {:seon.test/namespaces #{}})))
              arming (#'arm/initialize-contracts! "test-fast" namespaces)]
          (doseq [namespace-name namespaces]
            (when (empty? (#'runner/test-vars-in [namespace-name]))
              (throw (ex-info "Requested namespace has no tests."
                              {:seon.test.runner/namespace namespace-name}))))
          (schema/call-with-projection
           (:seon.test.runner/projection arming)
           (fn []
             (let [request (snapshot-request namespaces)
                   root (System/getProperty "seon.test.source-root" ".")
                   admission (runner/record-snapshot! root request)
                   _ (when (:seon.error/at admission)
                       (throw (ex-info "Snapshot admission refused." admission)))
                   _ (when-not (:seon.test.run/provenance admission)
                       (throw (ex-info "Snapshot admission returned no run." {:seon.test/admission admission})))
                   provenance (:seon.test.run/provenance admission)
                   selected (set (map :seon.test.member/symbol (:seon.test.run/members admission)))
                   vars (filterv #(selected (#'runner/var-symbol %)) (#'runner/test-vars-in namespaces))
                   report test/report
                   result
                   (binding [test/report
                             (fn [event]
                               (when (= :begin-test-ns (:type event))
                                 (#'runner/reassert-contracts! arming "test-fast"))
                               (report event))]
                     (#'runner/run-request!
                      (assoc provenance :seon.test.runner/namespaces namespaces)
                      progress [{:seon.test.runner/tier-name :named
                                 :seon.test.runner/vars vars}]))
                   recorded (runner/record-snapshot!
                             root {:seon.test.run/provenance provenance
                                   :seon.test/run-basis-t (:seon.test.run/basis-t provenance)
                                   :seon.test/run-at (:seon.test.run/at provenance)
                                   :seon.test.run/terminated? true
                                   :seon.test.runner/results (:seon.test.runner/results result)})
                   _ (when-not (vector? recorded)
                       (throw (ex-info "Snapshot results were not recorded." {:seon.test/recording recorded})))
                   facts (runner/recorded-run! root (:seon.test.run/id provenance))
                   _ (when-not (vector? facts)
                       (throw (ex-info "Recorded run coverage is unavailable." facts)))
                   summary (runner/print-recorded-tally! facts)]
               (println "bin/test-fast:" (:seon.test.runner/test-count summary) "executed,"
                        (:seon.test.runner/unchanged-count summary) "unchanged; run"
                        (:seon.test.run/id provenance))
               (if (zero? (+ (:seon.test.runner/fail-count summary)
                             (:seon.test.runner/error-count summary))) 0 1)))))
        (catch Throwable failure
          (binding [*out* *err*]
            (println "bin/test-fast: initialization or execution failed:"
                     (ex-message failure))
            (prn (if (:seon.error/at (ex-data failure))
                   (ex-data failure) (Throwable->map failure))))
          1)
        (finally (.shutdownNow backstop)))]
    (shutdown-agents)
    (System/exit exit-code)))
