(ns seon.rereads-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [sci.core :as sci]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.config :as config]
            [seon.context-blocks-fixture :as fixture]
            [seon.db :as db]
            [seon.eval :as evaluation]
            [seon.flow :as flow]
            [seon.repl :as repl]
            [seon.test-support :as support]
            [seon.turn :as turn]))

(defn- with-rereads [f]
  (support/with-database
   (fn [connection]
     (config/apply! {:seon.db/connection connection :seon.boot/cluster-name "rereads"
                    :seon.config/manifest {:seon.config.ai/no-provider true}})
     (support/transacted! connection [{:seon.agent/id "root"
                                      :seon.agent/namespace {:seon.ns/name 'my.agents.root}}])
     (cluster/ensure-cluster-entity! connection "rereads" cluster/boot-process-identity)
     (let [ctx (support/fork-cluster-ctx connection)
           environment (support/environment "rereads" connection)
           routing (agent/routing)]
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
                      :seon.cluster/name "rereads" :seon.sci.eval/ctx ctx
                      :seon.flow/work-launcher @launcher
                      :seon.flow/executor (cluster/projection-executor (:seon.sci.eval/projection-state ctx))
                      :seon.db.process/id cluster/boot-process-identity})
                    (fn [handle]
                      (agent/disarm! {:seon.agent/routing routing :seon.agent/id "juniper"})
                      (doseq [key [:seon.cluster.wake/channel :seon.render/context-channel
                                   :seon.turn.loop/completion]]
                        (async/close! (get handle key)))))]
         (let [handle @resource
               request {:seon.turn.loop/cluster handle :seon.agent/id "juniper"
                        :seon.turn/write? true}]
           (swap! routing assoc :seon.agent/fault-channel @faults)
           (cluster/ensure-entity! connection cluster/boot-process-identity
                                   {:seon.agent/id "juniper" :seon.cluster/name "rereads"
                                    :seon.ns/name 'my.agents.juniper})
           (fixture/install! handle routing)
           (is (string? (:seon.turn/id (turn/system-turn request))))
           (agent/arm! (assoc request :seon.agent/routing routing))
           (f connection handle routing request)
           (is (nil? (async/poll! @faults)) "the real graph remains fault-free")))))))

(defn- entries [connection source]
  (filterv #(= source (:seon.cluster.eval/source %))
           (evaluation/of-agent @connection "juniper")))

(deftest stale-read-with-equal-value-refreshes-evidence-without-emitting
  (with-rereads
    (fn [connection handle routing request]
      (let [source "(seon.db/q '[:find (min ?amount) . :where [?e :example/amount ?amount]])"]
        (fixture/submit! handle routing source)
        (agent/disarm! {:seon.agent/routing routing :seon.agent/id "juniper"})
        (turn/system-turn request)
        (let [before (entries connection source)
              previous (last before)
              all-before (evaluation/of-agent @connection "juniper")]
          (is (= 1 (count before)))
          (is (= 40 (repl/shown-value (:seon.eval/shown previous))))
          (is (seq (:seon.cluster.eval/read-evidence previous)))
          (support/transacted! connection [[:db/add [:example/order "a1"] :example/amount 61]])
          (is (false? (db/read-evidence-current? @connection (:seon.cluster.eval/read-evidence previous))))
          (testing "preview emits nothing and changes no facts"
            (let [basis (db/basis-t @connection)
                  preview (turn/system-turn (assoc request :seon.turn/write? false))]
              (is (vector? (:seon.turn/forms preview)) (pr-str preview))
              (is (not-any? :seon.turn/text (:seon.turn/forms preview)))
              (is (= basis (db/basis-t @connection)))))
          (let [result (turn/system-turn request)
                refreshed (last (entries connection source))]
            (is (vector? (:seon.turn/forms result)) (pr-str result))
            (is (nil? (:seon.turn/id result)))
            (is (not-any? :seon.turn/text (:seon.turn/forms result)))
            (is (= (mapv :seon.cluster.eval/id all-before)
                   (mapv :seon.cluster.eval/id (evaluation/of-agent @connection "juniper"))))
            (is (= (:seon.eval/shown previous) (:seon.eval/shown refreshed)))
            (is (> (:seon.cluster.eval/read-basis-transaction refreshed)
                   (:seon.cluster.eval/read-basis-transaction previous)))
            (is (true? (db/read-evidence-current? @connection (:seon.cluster.eval/read-evidence refreshed))))
            (let [basis (db/basis-t @connection)]
              (is (nil? (:seon.turn/id (turn/system-turn request))))
              (is (= basis (db/basis-t @connection)) "fresh evidence avoids another evaluation or write")))
          (testing "a changed value emits exactly once with its actual result handle"
            (support/transacted! connection [[:db/add [:example/order "c1"] :example/amount 39]])
            (let [result (turn/system-turn request)
                  added (last (entries connection source))
                  emission (repl/entity-emission added)
                  ctx (agent/acquire-context! handle "juniper")]
              (is (string? (:seon.turn/id result)) (pr-str result))
              (is (= 1 (count (filter :seon.turn/text (:seon.turn/forms result)))))
              (is (= 2 (count (entries connection source))))
              (is (= {:seon.repl/changes {[] {:seon.db.diff/after 39}}}
                     (repl/shown-value (:seon.eval/shown added))))
              (is (= 39 @(sci/resolve ctx (:seon.repl/handle emission))))
              (is (nil? (get (:schema @connection) :seon.cluster.eval/refreshes))
                  "supersession has no installed edge")
              (is (= (:seon.cluster.eval/id added)
                     (:seon.cluster.eval/id
                      (get (#'turn/latest-evaluations @connection "juniper")
                           (#'turn/source-key (assoc added :seon.ns/name 'my.agents.juniper))))))
              (is (nil? (:seon.turn/id (turn/system-turn request)))))
            (support/transacted! connection [[:db/add [:example/order "c1"] :example/amount 38]])
            (let [result (turn/system-turn request)
                  latest (last (entries connection source))]
              (is (string? (:seon.turn/id result)) (pr-str result))
              (is (= 3 (count (entries connection source))))
              (is (= {:seon.repl/changes {[] {:seon.db.diff/after 38}}}
                     (repl/shown-value (:seon.eval/shown latest))))
              (is (= 38 (:seon.repl/shown-value
                         (get (#'turn/latest-evaluations @connection "juniper")
                              (#'turn/source-key (assoc latest :seon.ns/name 'my.agents.juniper))))))
              (let [basis (db/basis-t @connection)]
                (is (nil? (:seon.turn/id (turn/system-turn request))))
                (is (= basis (db/basis-t @connection))
                    "the latest read answers the next pass without an edge")))))))))

(deftest failed-evaluations-are-not-promoted-and-documentation-follows-its-evidence
  (with-rereads
    (fn [connection handle routing request]
      (let [read-source "(seon.db/q '[:find (min ?amount) . :where [?e :example/amount ?amount]])"
            failures ["(Simplest:)"
                      (str "(do " read-source " (/ 1 0))")
                      "(seon.db/pull 1 2)"
                      "(+ 1 #unknown/tag 2)"]
            documentation "(doc my.plan/current!)"]
        (doseq [source (conj failures documentation)]
          (fixture/submit! handle routing source))
        (agent/disarm! {:seon.agent/routing routing :seon.agent/id "juniper"})
        (let [failed (mapv #(last (entries connection %)) failures)
              doc-entry (last (entries connection documentation))]
          (doseq [entry failed]
            (is (some? entry))
            (is (string? (:seon.cluster.eval/error entry)) (pr-str entry))
            (is (not (#'turn/read-only-evaluation? @connection entry))))
          (is (seq (:seon.cluster.eval/read-evidence (second failed)))
              "the failed read genuinely observed database facts")
          (is (seq (:seon.cluster.eval/read-evidence doc-entry)))
          (is (#'turn/read-only-evaluation? @connection doc-entry))
          (is (not (#'turn/read-only-evaluation?
                     @connection (dissoc doc-entry :seon.cluster.eval/read-evidence))))
          (support/transacted! connection [[:db/add [:example/order "c1"] :example/amount 39]])
          (let [plan (#'turn/system-plan @connection []
                                        (#'turn/latest-evaluations @connection "juniper"))
                by-source (into {} (map (juxt :seon.cluster.eval/source identity)) plan)]
            (is (not-any? #(get by-source %) failures))
            (is (= :unchanged (:seon.turn/status (get by-source documentation))))
            (is (nil? (:seon.turn/id (turn/system-turn request))))
            (is (= 1 (count (entries connection documentation)))))
          (support/transacted! connection [[:db/add [:seon.fn/sym "my.plan/current!"]
                                           :seon.fn/doc "Select the current plan item. Reread proof."]])
          (let [plan (#'turn/system-plan @connection []
                                        (#'turn/latest-evaluations @connection "juniper"))]
            (is (= :changed (:seon.turn/status
                             (some #(when (= documentation (:seon.cluster.eval/source %)) %) plan))))
            (is (not-any? #(some #{(:seon.cluster.eval/source %)} failures) plan)))
          (let [result (turn/system-turn request)]
            (is (vector? (:seon.turn/forms result)) (pr-str result))
            (is (= 2 (count (entries connection documentation))))
            (is (= (mapv :seon.cluster.eval/id failed)
                   (mapv #(-> (entries connection %) last :seon.cluster.eval/id) failures)))))))))

(deftest generated-read-check-needs-read-evidence-not-an-evaluator-result
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           captured (atom [])
           source {:seon.cluster.eval/source
                   "(seon.db/q '[:find [?source ...] :where [_ :seon.cluster.eval/source ?source]])"}
           _ (binding [db/*read-evidence-sink* captured]
               (db/q '[:find [?source ...]
                       :where [_ :seon.cluster.eval/source ?source]] database))
           evidence (db/read-evidence @captured)]
       (is (seq evidence) "The real query must publish dependency evidence.")
       (let [fault (#'turn/generated-read-fault
                    database source {:seon.cluster.eval/read-evidence evidence})]
         (is (contains? (:seon.turn/generated-read-attributes fault)
                        :seon.cluster.eval/source)
             "Turn activity is refused using the captured dependencies alone."))))))
