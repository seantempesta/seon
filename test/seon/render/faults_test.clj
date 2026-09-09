(ns seon.render.faults-test
  (:require [clojure.edn :as edn]
            [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.config :as config]
            [seon.db :as db]
            [seon.error :as error]
            [seon.eval :as evaluation]
            [seon.render :as render]
            [seon.render.web :as web]
            [seon.test-support :as support]
            [seon.turn :as turn]))

(deftest pulled-fault-concern-uses-the-entity-pair
  (support/with-database
   (fn [connection]
     (let [effective (config/defaults)
           caps (config/result-caps effective)
           ctx (support/fork-cluster-ctx connection)
           fault (assoc
                  (error/normalize
                   {:seon.error/source {:seon.error/kind :seon.ai/no-credential
                                        :seon.error/message "No credential configured."}
                    :seon.error/id "fault-render-probe"
                    :seon.error/at (java.util.Date. 0)
                    :seon.error/process "fault-render-probe"
                    :seon.sci.admit/caps caps
                    :seon.config.error/max-evidence-bytes
                    (:seon.config.error/max-evidence-bytes effective)})
                  :seon.instrument/fn "seon.ai/complete"
                  :seon.error/run [:seon.turn/id "fault-render-turn"])
           written (db/transact!
                    connection
                    [[:db/add "turn" :seon.turn/id "fault-render-turn"]
                     fault])
           pulled (db/pull @connection '[* {:seon.error/run [:db/id :seon.turn/id]}]
                           [:seon.error/id "fault-render-probe"])
           request {:seon.db/db @connection
                    :seon.db/connection connection
                    :seon.sci.eval/ctx ctx
                    :seon.sci.admit/caps caps
                    :seon.sci.eval/time-limit-ms (* 1000 support/event-backstop-seconds)
                    :seon.config/on-core-error :panic
                    :seon.render/profile (render/agent-render-profile effective)
                    :seon.render/captured-calls (atom {})
                    :seon.render/captured-invocations (atom {})}
           experiment (fn [output]
                        (#'web/selected-unit-experiment
                         request :seon.error/_agent output [pulled]
                         [:seon.error/id "fault-render-probe"]
                         {:seon.render.data/path [] :seon.render.data/offset 0}))
           ai (render/render-call
               (assoc (dissoc request :seon.render/captured-invocations)
                      :seon.render/value [pulled]
                      :seon.render.walk/attribute :seon.error/agent
                      :seon.render/output :seon.render/ai
                      :seon.render.call/id [:fault-test/ai]))
           html (get-in (experiment :seon.render/html)
                        [:seon.render/previews 'seon.error/render-faults-html])]
       (is (not (:seon.error/kind written)))
       (is (int? (:db/id pulled)) "the subject is a real pulled entity")
       (is (string? ai)
           (pr-str (#'render/invoke-selected
                    (assoc request :seon.render/value [pulled]
                           :seon.render.walk/attribute :seon.error/agent
                           :seon.render/output :seon.render/ai)
                    'seon.error/render-faults-ai)))
       (is (= :seon.ai/no-credential (:seon.error/kind (edn/read-string ai))))
       (doseq [expected ["Faults (1)" "No credential configured."
                         "seon.ai/no-credential" "seon.ai/complete"
                         "1970" "fault-render-turn" "Inspect durable evidence"]]
         (is (str/includes? (pr-str html) expected) expected))
       (is (not (str/includes? (pr-str html) "items, depth")))
       (is (not (str/includes? ai "items, depth")))))))

(deftest the-opening-routes-repair-reads-to-root-and-stewards
  (support/with-database
   (fn [connection]
     (let [cluster-name "fault-opening"
           effective (config/defaults)
           _ (config/apply! {:seon.db/connection connection :seon.boot/cluster-name cluster-name})
           agents ["root" "repair" "happened"]
           setup (db/transact!
                  connection
                  (into [[:db/add "cluster" :seon.cluster/name cluster-name]]
                        (mapcat #(agent/creation-tx
                                  {:seon.agent/id % :seon.ns/name (symbol (str "my.agents." %))
                                   :seon.cluster/name cluster-name}) agents)))
           _ (is (:db-after setup) (pr-str setup))
           ctx (support/fork-cluster-ctx connection cluster-name)
           handle (support/cluster-handle
                   {:seon.db/connection connection :seon.cluster/name cluster-name
                    :seon.db.process/id cluster/boot-process-identity :seon.sci.eval/ctx ctx})
           sources (fn [id] (mapv :seon.cluster.eval/source (evaluation/of-agent @connection id)))
           repair-reads (fn [id] (filter #(str/includes? % ":seon.error/steward") (sources id)))
           open! (fn [id]
                   (let [result (turn/system-turn {:seon.turn.loop/cluster handle
                                                   :seon.agent/id id :seon.turn/write? true})]
                     (is (not (:seon.error/kind result)) (pr-str result))
                     result))]
       (try
         (doseq [id agents] (open! id))
         (is (= 1 (count (repair-reads "root"))) (pr-str (sources "root")))
         (is (empty? (repair-reads "repair")))
         (is (empty? (repair-reads "happened")))
         (let [root-read (first (filter #(str/includes? (:seon.cluster.eval/source %) ":seon.error/steward")
                                       (evaluation/of-agent @connection "root")))]
           (is (= "[]" (:seon.eval/value root-read)))
           (is (seq (:seon.cluster.eval/read-evidence root-read))))
         (let [fault (assoc
                      (error/normalize
                       {:seon.error/source {:seon.error/kind :seon.ai/no-credential
                                            :seon.error/message "Repair this configured provider."}
                        :seon.error/id "routed-opening" :seon.error/at (java.util.Date. 0)
                        :seon.error/process "fault-opening"
                        :seon.sci.admit/caps (config/result-caps effective)
                        :seon.config.error/max-evidence-bytes
                        (:seon.config.error/max-evidence-bytes effective)})
                      :seon.error/agent [:seon.agent/id "happened"]
                      :seon.error/steward [:seon.agent/id "repair"])
               written (db/transact! connection [fault])]
           (is (:db-after written) (pr-str written)))
         (open! "repair")
         (open! "happened")
         (is (= 1 (count (repair-reads "repair"))) (pr-str (sources "repair")))
         (is (empty? (repair-reads "happened")))
         (let [entry (last (filter #(str/includes? (:seon.cluster.eval/source %) ":seon.error/steward")
                                  (evaluation/of-agent @connection "repair")))]
           (is (str/includes? (:seon.eval/value entry) "Repair this configured provider.")))
         (finally
           (doseq [channel [(:seon.cluster.wake/channel handle)
                            (:seon.render/context-channel handle)
                            (:seon.turn.loop/completion handle)]]
             (async/close! channel))))))))
