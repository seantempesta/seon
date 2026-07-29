(ns seon.test.runner
  "Run the JVM gate and optionally commit per-test result facts."
  (:refer-clojure :exclude [run!])
  (:require [clojure.string :as str]
            [clojure.test :as test]
            [datahike.api :as d]
            [seon.cluster :as cluster]
            [seon.schema.edn :as schema.edn])
  (:gen-class))

(schema.edn/load! {})

(defn- var-symbol
  [test-var]
  (when test-var
    (let [{:keys [name ns]} (meta test-var)]
      (when (and name ns)
        (symbol (str (ns-name ns)) (str name))))))

(defn- event-symbol
  [event]
  (var-symbol (or (:var event) (first test/*testing-vars*))))

(defn- printable
  [value]
  (if (instance? Throwable value)
    (str (.getName (class value)) ": " (or (ex-message value) ""))
    (pr-str value)))

(defn- failure-message
  [event]
  (->> [(when (seq test/*testing-contexts*)
          (test/testing-contexts-str))
        (:message event)
        (when (contains? event :expected)
          (str "expected: " (printable (:expected event))))
        (when (contains? event :actual)
          (str "actual: " (printable (:actual event))))]
       (remove str/blank?)
       (str/join "\n")))

(defn- ensure-result
  [capture test-symbol]
  (if (contains? (::results capture) test-symbol)
    capture
    (-> capture
        (update ::order conj test-symbol)
        (assoc-in [::results test-symbol]
                  {:seon.test/sym (str test-symbol)
                   :seon.ns/name (symbol (namespace test-symbol))
                   :seon.test.result/outcome :pass
                   ::failure-messages []}))))

(defn- capture-event!
  [capture event]
  (when-let [test-symbol (event-symbol event)]
    (swap! capture
           (fn [current]
             (let [current (ensure-result current test-symbol)]
               (if (contains? #{:fail :error} (:type event))
                 (-> current
                     (assoc-in [::results test-symbol
                                :seon.test.result/outcome]
                               :fail)
                     (update-in [::results test-symbol ::failure-messages]
                                conj
                                (failure-message event)))
                 current))))))

(defn- captured-results
  [{::keys [order results]}]
  (mapv
   (fn [test-symbol]
     (let [result (get results test-symbol)
           messages (::failure-messages result)]
       (cond-> (dissoc result ::failure-messages)
         (seq messages)
         (assoc :seon.test.failure/message (str/join "\n\n" messages)))))
   order))

(defn run!
  "Run namespaces through `clojure.test` and return per-test values.

  The default reporter still receives every event and therefore keeps the
  gate's ordinary output and counters. Capture is invocation-local data."
  {:malli/schema [:=> [:cat :seon.test.runner/run-request]
                  :seon.test.runner/run-result]}
  [{namespaces :seon.test.runner/namespaces
    run-id :seon.test.run/id
    at :seon.test.run/at
    git-sha :seon.test.run/git-sha}]
  (let [capture (atom {::order [] ::results {}})
        default-report test/report
        raw-summary
        (binding [test/report
                  (fn [event]
                    (capture-event! capture event)
                    (default-report event))]
          (apply test/run-tests namespaces))
        summary
        {::test-count (:test raw-summary)
         ::pass-count (:pass raw-summary)
         ::fail-count (:fail raw-summary)
         ::error-count (:error raw-summary)}]
    {:seon.test.run/id run-id
     :seon.test.run/at at
     :seon.test.run/git-sha git-sha
     :seon.test.runner/summary summary
     :seon.test.runner/results (captured-results @capture)}))

(defn- namespace-tempid
  [namespace-name]
  (str "namespace:" namespace-name))

(defn- test-tempid
  [test-symbol]
  (str "test:" test-symbol))

(defn- result-id
  [run-id test-symbol]
  (str run-id ":" test-symbol))

(defn record-tx
  "Transaction data for one captured run and its exact test refs."
  {:malli/schema [:=> [:cat :seon.test.runner/run-result]
                  :seon.test.runner/record-tx]}
  [{run-id :seon.test.run/id
    at :seon.test.run/at
    git-sha :seon.test.run/git-sha
    results :seon.test.runner/results}]
  (let [namespace-names (distinct (map :seon.ns/name results))
        namespace-rows
        (mapv (fn [namespace-name]
                {:db/id (namespace-tempid namespace-name)
                 :seon.ns/name namespace-name})
              namespace-names)
        test-rows
        (mapv (fn [{test-symbol :seon.test/sym
                    namespace-name :seon.ns/name}]
                {:db/id (test-tempid test-symbol)
                 :seon.test/sym test-symbol
                 :seon.test/ns (namespace-tempid namespace-name)})
              results)
        run-tempid (str "run:" run-id)
        run-row {:db/id run-tempid
                 :seon.test.run/id run-id
                 :seon.test.run/at at
                 :seon.test.run/git-sha git-sha}
        failure-rows
        (into []
              (keep
               (fn [{test-symbol :seon.test/sym
                     message :seon.test.failure/message}]
                 (when message
                   {:db/id (str "failure:" (result-id run-id test-symbol))
                    :seon.test.failure/id
                    (str (result-id run-id test-symbol) ":failure")
                    :seon.test.failure/message message})))
              results)
        result-rows
        (mapv
         (fn [{test-symbol :seon.test/sym
               outcome :seon.test.result/outcome
               message :seon.test.failure/message}]
           (cond-> {:seon.test.result/id (result-id run-id test-symbol)
                    :seon.test.result/test (test-tempid test-symbol)
                    :seon.test.result/run run-tempid
                    :seon.test.result/outcome outcome}
             message
             (assoc :seon.test.result/failure
                    (str "failure:" (result-id run-id test-symbol)))))
         results)]
    (into namespace-rows
          (concat test-rows [run-row] failure-rows result-rows))))

(defn- start-cluster!
  [cluster-name root]
  (try
    (cluster/start! {:seon.boot/cluster-name cluster-name
                     :seon.boot/root root})
    (catch Throwable failure
      (when-let [instance (:seon.boot/instance (ex-data failure))]
        (cluster/stop! instance))
      (throw failure))))

(defn record!
  "Commit one run into an explicitly named, non-default cluster."
  {:malli/schema [:=> [:cat :seon.test.runner/record-request]
                  :seon.test.runner/recorded]}
  [{run-result :seon.test.runner/run-result
    cluster-name :seon.boot/cluster-name
    root :seon.boot/root}]
  (when (= "default" cluster-name)
    (throw
     (ex-info
      "Test results may not be written into the default cluster."
      {:seon.error/kind ::default-cluster-refused
       :seon.boot/cluster-name cluster-name})))
  (let [instance (start-cluster! cluster-name root)]
    (try
      (d/transact (:seon.boot/cluster-connection instance)
                  (record-tx run-result))
      {:seon.boot/cluster-name cluster-name
       :seon.test.run/id (:seon.test.run/id run-result)
       :seon.test.runner/recorded-count
       (count (:seon.test.runner/results run-result))}
      (finally
        (cluster/stop! instance)))))

(defn -main
  [cluster-name root git-sha & namespace-names]
  (let [namespaces (mapv symbol namespace-names)
        _ (doseq [test-namespace namespaces]
            (require test-namespace))
        run-result
        (run! {:seon.test.runner/namespaces namespaces
               :seon.test.run/id (str (random-uuid))
               :seon.test.run/at (java.util.Date.)
               :seon.test.run/git-sha git-sha})
        _ (record! {:seon.test.runner/run-result run-result
                    :seon.boot/cluster-name cluster-name
                    :seon.boot/root root})
        summary (:seon.test.runner/summary run-result)]
    (System/exit
     (if (zero? (+ (::fail-count summary) (::error-count summary))) 0 1))))
