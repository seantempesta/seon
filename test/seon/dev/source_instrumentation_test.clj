(ns seon.dev.source-instrumentation-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.config :as config]
            [seon.db :as db]
            [seon.env :as env]
            [seon.fresh-operator]
            [seon.instrument :as instrument]
            [seon.operator.state :as operator.state]
            [seon.schema :as schema]
            [seon.test-support :as support]))

(deftest ^{:seon.test/long "Acquire the canonical database fixture before observing instrumentation restoration."
           :seon.test/long-ms 600000}
  source-publication-restores-contracts-after-early-reload-failure
  (support/with-database
   (fn [connection]
     (let [projection (schema/handed-projection)
           state (env/environment-state
                  (env/environment
                   {:seon.boot/cluster-name "instrumentation-test"
                    :seon.db/basis-t (db/basis-t @connection)
                    :seon.schema/projection projection}))
           instances-var (ns-resolve 'seon.cluster 'running-instances)
           instances
           (atom {"instrumentation-test"
                  {:seon.boot/cluster-connection connection
                   :seon.boot/advertisement
                   {:seon.boot/cluster-name "instrumentation-test"}
                   :seon.sci.eval/ctx
                   {:seon.sci.eval/projection-state state}}})
           source ((ns-resolve 'seon.fresh-operator 'init-form)
                   "tmp/source-instrumentation-test" nil false [] false
                   "instrumentation-test")]
       (doseq [failure-point [:reload :publication :none]]
         (let [events (atom [])
               failure (ex-info "intentional source lifecycle failure"
                                {:seon.test/failure-point failure-point})
               actual
               (with-redefs-fn
                 {instances-var instances
                  #'clojure.core/require
                  (fn [& _]
                    (swap! events conj :reload)
                    (when (= :reload failure-point) (throw failure)))
                  #'cluster/refresh-source!
                  (fn [& _]
                    (swap! events conj :publication)
                    (when (= :publication failure-point) (throw failure))
                    {:seon.source/branch :current-src})
                  #'config/effective
                  (fn [_ _]
                    (is (identical? projection (schema/handed-projection)))
                    {:seon.config/on-core-error :panic})
                  #'instrument/remove!
                  (fn [] (swap! events conj :unstrument))
                  #'instrument/apply!
                  (fn [request]
                    (is (= :panic (:seon.config/on-core-error request)))
                    (is (identical? projection (schema/handed-projection)))
                    (swap! events conj :restore)
                    {:seon.instrument/registered 1
                     :seon.instrument/instrumented 1})}
                 (fn []
                   (try (eval (read-string source))
                        (catch Throwable thrown thrown))))]
           (is (not-any? #{:unstrument} @events)
               "publication never strips all live contract wrappers")
           (is (= :restore (last @events))
               "the same restoration owns both reload and publication failures")
           (is (= 1 (count (filter #{:restore} @events))))
           (case failure-point
             :none (is (= :current-src (:seon.source/branch actual)))
             :reload (is (identical? failure actual))
             :publication (is (= (ex-data failure)
                                 (:seon.fresh-operator/exception-data actual))))))))))


(deftest ^{:seon.test/long "Start a cold Clojure JVM to compile operator forms before runtime namespaces load."
           :seon.test/long-ms 60000}
  generated-init-compiles-before-runtime-owners-are-loaded
  (let [init-form (ns-resolve 'seon.fresh-operator 'init-form)
        forms (mapv (fn [arguments]
                      (apply init-form "tmp/source-instrumentation-test" arguments))
                    [[nil false [] true nil]
                     ["scratch" false [] true nil]
                     ["scratch" true [] true nil]
                     [nil false ["src/seon/cluster.clj"] false "development"]
                     ["scratch" false [] false nil]])
        code
        (pr-str
         `(do
            (assert (nil? (find-ns 'seon.cluster))
                    "cold compilation must not inherit the worker's loaded cluster")
            (doseq [source# ~forms]
              (eval (list 'fn [] (read-string source#))))
            (assert (nil? (find-ns 'seon.cluster))
                    "compilation must not need the runtime require to have happened")
            (println "cold-init-compilation-passed")))
        outcome
        (operator.state/run-process!
         {:seon.operator.subprocess/argv
          [(str (io/file (System/getProperty "java.home") "bin" "java"))
           "-cp" (System/getProperty "java.class.path")
           "clojure.main" "-e" code]
          :seon.operator.subprocess/deadline-ms
          (* 1000 support/event-backstop-seconds)
          :seon.operator.subprocess/merge-error? true})]
    (is (= 0 (:seon.operator.subprocess/exit outcome)) (pr-str outcome))
    (is (= "cold-init-compilation-passed\n"
           (:seon.operator.subprocess/output outcome)))))
