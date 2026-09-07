(ns seon.cluster.evaluate-sources-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is]]
            [seon.blob :as blob]
            [seon.cluster.agent :as agent]
            [seon.cluster.loop :as loop]
            [seon.config :as config]
            [seon.db :as db]
            [seon.sci.eval :as sci.eval]
            [seon.test-support :as support]))

(deftest ordered-evaluation-retains-one-explicit-basis-without-publication
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "preview-batch")
     (db/transact! connection
                   (agent/creation-tx
                    {:seon.cluster.agent/id "preview-batch-agent"
                     :seon.ns/name 'my.agents.preview-batch
                     :seon.cluster/name "preview-batch"}))
     (let [database @connection
           base (support/fork-cluster-ctx connection)
           forked (sci.eval/fork-for-turn
                   {:seon.sci.eval/ctx base
                    :seon.db/db database
                    :seon.db/connection connection
                    :seon.cluster.agent/id "preview-batch-agent"})
           defaults (config/defaults)
           channel (async/chan 1)
           cluster (merge defaults
                          {:seon.db/connection connection
                           :seon.cluster/name "preview-batch"
                           :seon.cluster.run/process "preview-test"
                           :seon.sci.eval/ctx base
                           :seon.cluster.wake/channel channel
                           :seon.render/context-channel channel
                           :seon.cluster.loop/completion channel
                           :seon.cluster.loop/evaluate 'seon.sci.eval/evaluate
                           :seon.sci.admit/caps (config/result-caps defaults)
                           :seon.config.eval/time-limit-ms 2000
                           :seon.config/on-core-error :panic})
           sources (loop/planned-sources
                    (str "(seon.db/pull [:seon.cluster.agent/id] [:seon.cluster.agent/id \"later-agent\"])\n"
                         "(in-ns 'preview.batch-next)\n"
                         "(+ 1 2)\n"
                         "(inc result/e2)\n"
                         "(seon.db/pull [:seon.cluster.agent/id] [:seon.cluster.agent/id \"later-agent\"])\n"
                         "(apply str (repeat 50000 \"x\"))\n"
                         "(throw (ex-info \"preview failure\" {}))")
                    'my.agents.preview-batch
                    (:seon.config.eval.result/max-source (config/result-caps defaults)))
           original-evaluate sci.eval/evaluate
           original-transact db/transact!
           evaluations (atom 0)
           writes (atom 0)]
       (try
         (let [outcomes
               (with-redefs
                 [blob/stage! (fn [& _] (throw (ex-info "preview staged a blob" {})))
                  db/transact! (fn [& args]
                                 (swap! writes inc)
                                 (apply original-transact args))
                  sci.eval/evaluate
                  (fn [request]
                    (let [result (original-evaluate request)]
                      (when (= 1 (swap! evaluations inc))
                        (db/transact! connection [{:seon.cluster.agent/id "later-agent"}]))
                      result))]
                 (loop/evaluate-sources
                  {:seon.cluster.loop/cluster cluster
                   :seon.db/db database
                   :seon.sci.eval/ctx (:seon.sci.eval/ctx forked)
                   :seon.cluster.agent/id "preview-batch-agent"
                   :seon.cluster.run.form/ordinal 0
                   :seon.ns/name 'my.agents.preview-batch
                   :seon.cluster.reply/sources sources}))
               values (mapv #(get-in % [:seon.sci.eval/evaluation :seon.sci.admit/value]) outcomes)]
           (is (= 7 (count outcomes)))
           (is (nil? (first values)))
           (is (= [3 4 nil] (subvec values 2 5)))
           (is (= (apply str (repeat 50000 "x")) (nth values 5)))
           (is (string? (get-in (last outcomes) [:seon.sci.eval/evaluation :seon.cluster.eval/error])))
           (is (= (range 7) (map :seon.cluster.run.form/ordinal outcomes)))
           (is (= [:seon.ns/name 'preview.batch-next]
                  (get-in (nth outcomes 2) [:seon.cluster.loop/admitted-form :seon.cluster.run.form/ns])))
           (is (every? #(= (db/basis-t database)
                           (get-in % [:seon.sci.eval/evaluation :seon.cluster.eval/read-basis-transaction])) outcomes))
           (is (seq (get-in (first outcomes) [:seon.sci.eval/evaluation :seon.cluster.eval/read-evidence])))
           (is (= 1 @writes) "only the deliberate concurrent database change was written")
           (is (nil? db/*read-database*) "the failing evaluation restores read custody")
           (is (= {:seon.cluster.agent/id "later-agent"}
                  (binding [db/*conn* connection]
                    (db/pull [:seon.cluster.agent/id] [:seon.cluster.agent/id "later-agent"]))
                  (binding [db/*conn* connection db/*read-database* database]
                    (db/pull @connection [:seon.cluster.agent/id] [:seon.cluster.agent/id "later-agent"]))))
           (is (= (db/q '[:find (count ?run) . :where [?run :seon.cluster.run/id]] database)
                  (db/q '[:find (count ?run) . :where [?run :seon.cluster.run/id]] @connection))))
         (finally (async/close! channel)))))))
