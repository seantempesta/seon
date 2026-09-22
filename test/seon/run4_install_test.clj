(ns seon.run4-install-test
  (:require [clojure.core.async :as async]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [sci.core :as sci]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.config :as config]
            [seon.context-blocks-fixture :as fixture]
            [seon.db :as db]
            [seon.eval :as evaluation]
            [seon.flow :as flow]
            [seon.sci.admit :as admit]
            [seon.test-support :as support]))

(deftest refused-run4-definition-never-enters-the-retained-context
  (support/with-database
   (fn [connection]
     (config/apply! {:seon.db/connection connection
                    :seon.boot/cluster-name "run4-install"
                    :seon.config/manifest {:seon.config.ai/no-provider true}})
     (cluster/ensure-cluster-entity! connection "run4-install" cluster/boot-process-identity)
     (let [ctx (support/fork-cluster-ctx connection)
           environment (support/environment "run4-install" connection)
           routing (agent/routing)
           source (some (fn [[id source]] (when (= "faef54087471" id) source))
                        (:run4/evaluations
                         (edn/read-string (slurp "test/seon/run4_replies.edn"))))]
       (is (string? source) "The exact captured definition must be present.")
       (with-open [faults (support/closeable (async/chan (async/sliding-buffer 16)) async/close!)
                   launcher (support/closeable
                             (flow/start-work-launcher!
                              {:seon.env/environment environment
                               :seon.flow/configuration
                               (select-keys (support/effective-config) flow/flow-workload-attributes)})
                             flow/stop-work-launcher!)
                   resource
                   (support/closeable
                    (support/cluster-handle
                     {:seon.env/environment environment :seon.db/connection connection
                      :seon.cluster/name "run4-install" :seon.sci.eval/ctx ctx
                      :seon.flow/work-launcher @launcher
                      :seon.flow/executor (cluster/projection-executor (:seon.sci.eval/projection-state ctx))
                      :seon.db.process/id cluster/boot-process-identity})
                    (fn [handle]
                      (agent/disarm! {:seon.agent/routing routing :seon.agent/id "juniper"})
                      (doseq [key [:seon.cluster.wake/channel :seon.render/context-channel
                                   :seon.turn.loop/completion]]
                        (async/close! (get handle key)))))]
         (let [handle @resource
               function-symbol 'my.agents.juniper/largest-customer
               entries (fn [turn-id]
                         (let [turn-eid (:db/id (db/pull @connection [:db/id] [:seon.turn/id turn-id]))]
                           (filter #(= turn-eid (get-in % [:seon.cluster.eval/run :db/id]))
                                   (evaluation/of-agent @connection "juniper"))))]
           (swap! routing assoc :seon.agent/fault-channel @faults)
           (cluster/ensure-entity! connection cluster/boot-process-identity
                                   {:seon.agent/id "juniper" :seon.cluster/name "run4-install"
                                    :seon.ns/name 'my.agents.juniper})
           (agent/arm! {:seon.turn.loop/cluster handle :seon.agent/routing routing
                        :seon.agent/id "juniper"})
           (let [turn-id (fixture/submit! handle routing (str source "\n(dir my.agents.juniper)"))
                 saved (vec (entries turn-id))
                 definition (first (filter #(= source (:seon.cluster.eval/source %)) saved))
                 retained (agent/acquire-context! handle "juniper")
                 result-var (when definition (sci/resolve retained (admit/result-handle (:seon.cluster.eval/id definition))))
                 result (when result-var @result-var)]
             (is (= 2 (count saved)) (pr-str saved))
             (is (= 1 (count (filter :seon.cluster.eval/error saved))))
             (is (qualified-symbol? (:seon.test.accretion/function-sym result)) (pr-str result))
             (is (= [[]] (:seon.test.accretion/arguments result)))
             (is (string? (:seon.test.accretion/expected result)))
             (is (contains? result :seon.test.accretion/actual))
             (is (= "Fix the contract or the function and re-evaluate the defn."
                    (:seon.error/message result)))
             (is (str/includes? (:seon.eval/shown definition) "Fix the contract"))
             (is (str/blank? (:seon.cluster.eval/output definition)))
             (is (nil? (db/q '[:find ?f . :in $ ?sym :where [?f :seon.fn/sym ?sym]]
                              @connection (str function-symbol))))
             (is (nil? (sci/resolve retained function-symbol)))
             (let [directory (first (filter #(= "(dir my.agents.juniper)" (:seon.cluster.eval/source %)) saved))
                   value (some-> (sci/resolve retained (admit/result-handle (:seon.cluster.eval/id directory))) deref)]
               (is (vector? (:functions value)) (pr-str value))
               (is (not-any? #(= function-symbol (:sym %)) (:functions value)))))
           ; Acceptance and rejected replacement exercise the same retained context.
           (fixture/submit! handle routing
                            "(defn largest-customer {:malli/schema [:=> [:cat :int] :int]} [x] x)")
           (let [retained (agent/acquire-context! handle "juniper")
                 accepted @(sci/resolve retained function-symbol)]
             (fixture/submit! handle routing source)
             (is (identical? accepted @(sci/resolve retained function-symbol)))
             (is (= "(defn largest-customer {:malli/schema [:=> [:cat :int] :int]} [x] x)"
                    (:seon.fn/source (db/pull @connection [:seon.fn/source]
                                             [:seon.fn/sym (str function-symbol)])))))
           (is (nil? (async/poll! @faults)))))))))
