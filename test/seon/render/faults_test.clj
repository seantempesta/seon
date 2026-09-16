(ns seon.render.faults-test
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.config :as config]
            [seon.db :as db]
            [seon.error :as error]
            [seon.eval :as evaluation]
            [seon.render :as render]
            [seon.test-support :as support]
            [seon.turn :as turn]))

(deftest pulled-fault-concern-uses-the-entity-pair
  (support/with-database
   (fn [connection]
     (let [effective (config/defaults)
           caps (config/result-caps effective)
           ctx (support/fork-cluster-ctx connection)
           _ (support/transacted! connection [{:seon.agent/id "fault-render-agent"}
                                              {:seon.turn/id "fault-render-turn"
                                               :seon.turn/agent [:seon.agent/id "fault-render-agent"]
                                               :seon.turn/opened-tx "datomic.tx"}])
           recording (error/recording
                      (db/db connection)
                      {:seon.error/source {:seon.error/kind :seon.instrument/contract-violated
                                           :seon.error/message "No credential configured."
                                           :seon.error/data {:seon.instrument/fn "seon.ai/complete"}}
                       :seon.error/id "fault-render-probe" :seon.error/at (java.util.Date. 0)
                       :seon.error/process "fault-render-probe" :seon.sci.admit/caps caps
                       :seon.config.error/max-evidence-bytes 16384
                       :seon.config.error/recurrence-limit 100
                       :seon.turn/id "fault-render-turn"})
           written (db/transact! connection (:seon.db/tx-data recording))
           pulled (db/pull (db/db connection)
                           '[* {:seon.error/fn [:db/id :seon.fn/sym]}
                             {:seon.error/occurrences [* {:seon.error.occurrence/turn [:seon.turn/id]}]}]
                           (:seon.error/ref recording))
           request {:seon.db/db (db/db connection) :seon.db/connection connection
                    :seon.sci.eval/ctx ctx :seon.sci.admit/caps caps
                    :seon.sci.eval/time-limit-ms (* 1000 support/event-backstop-seconds)
                    :seon.config/on-core-error :panic
                    :seon.render/profile (render/agent-render-profile effective)
                    :seon.render/value pulled
                    :seon.render.call/id [:fault-test/entity]}
           ai (render/render-call (assoc request :seon.render/output :seon.render/ai))
           html (render/render-call (assoc request :seon.render/output :seon.render/html))]
       (is (:db-after written))
       (is (int? (:db/id pulled)))
       (is (str/includes? (str ai) "Occurrences: 1"))
       (doseq [expected ["No credential configured." "seon.ai/complete" "1970"
                         "fault-render-turn" "Inspect evidence" "Occurrences: 1"]]
         (is (str/includes? (pr-str html) expected) expected))))))

(deftest the-opening-derives-repair-reads-through-function-refs
  (support/with-database
   (fn [connection]
     (let [cluster-name "fault-opening"
           _ (config/apply! {:seon.db/connection connection :seon.boot/cluster-name cluster-name})
           agents ["root" "repair" "happened"]
           setup (db/transact! connection
                              (into [[:db/add "cluster" :seon.cluster/name cluster-name]]
                                    (mapcat #(agent/creation-tx
                                              {:seon.agent/id % :seon.ns/name (symbol (str "my.agents." %))
                                               :seon.cluster/name cluster-name}) agents)))
           _ (is (:db-after setup) (pr-str (dissoc setup :db-before :db-after)))
           ctx (support/fork-cluster-ctx connection cluster-name)
           handle (support/cluster-handle
                   {:seon.db/connection connection :seon.cluster/name cluster-name
                    :seon.db.process/id cluster/boot-process-identity :seon.sci.eval/ctx ctx})]
       (try
         (doseq [id agents]
           (let [result (turn/system-turn {:seon.turn.loop/cluster handle
                                          :seon.agent/id id :seon.turn/write? true})]
             (is (not (:seon.error/kind result)) (pr-str result))
             (let [entry (first (filter #(str/includes? (or (:seon.cluster.eval/source %) "") ":seon.error/fn")
                                        (evaluation/of-agent (db/db connection) id)))]
               (is (some? entry) id)
               (is (= "[]" (:seon.eval/shown entry)))
               (is (seq (:seon.cluster.eval/read-evidence entry))))))
         (finally
           (doseq [channel [(:seon.cluster.wake/channel handle)
                           (:seon.render/context-channel handle)
                           (:seon.turn.loop/completion handle)]]
             (async/close! channel))))))))
