(ns seon.help-trial-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [clojure.core.async :as async]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.config :as config]
            [seon.context-blocks-fixture :as fixture]
            [seon.db :as db]
            [seon.flow :as flow]
            [seon.turn :as turn]
            [seon.test-support :as support]))

; The committed trial is the executable subject, not a copied scoring model.
(load-file "docs/prds/context-generation/research/help_trial_2026_09_09.clj")

(deftest preflight-requires-the-generated-opening-and-unpolluted-fixture
  (support/with-database
   (fn [connection]
     (config/apply! {:seon.db/connection connection :seon.boot/cluster-name "trial"
                    :seon.config/manifest {:seon.config.ai/no-provider true}})
     (db/transact! connection [{:seon.agent/id "root"
                               :seon.agent/namespace {:seon.ns/name 'my.agents.root}}])
     (cluster/ensure-cluster-entity! connection "trial" cluster/boot-process-identity)
     (let [ctx (support/fork-cluster-ctx connection)
           environment (support/environment "trial" connection)
           routing (agent/routing)]
       (with-open [faults (support/closeable (async/chan (async/sliding-buffer 16)) async/close!)
                   launcher (support/closeable
                             (flow/start-work-launcher!
                              {:seon.env/environment environment
                               :seon.flow/configuration
                               (select-keys (support/effective-config) flow/flow-workload-attributes)})
                             flow/stop-work-launcher!)
                   handle-resource
                   (support/closeable
                    (support/cluster-handle
                     {:seon.env/environment environment :seon.db/connection connection
                      :seon.cluster/name "trial" :seon.sci.eval/ctx ctx
                      :seon.flow/work-launcher @launcher
                      :seon.flow/executor (cluster/projection-executor (:seon.sci.eval/projection-state ctx))
                      :seon.db.process/id cluster/boot-process-identity})
                    (fn [handle]
                      (agent/disarm! {:seon.agent/routing routing :seon.agent/id "juniper"})
                      (doseq [channel-key [:seon.cluster.wake/channel :seon.render/context-channel
                                   :seon.turn.loop/completion]]
                        (async/close! (get handle channel-key)))))]
         (let [handle @handle-resource
               check! #((resolve 'help-trial-2026-09-09/preflight) handle %)]
           (swap! routing assoc :seon.agent/fault-channel @faults)
           (cluster/ensure-entity! connection cluster/boot-process-identity
                                   {:seon.agent/id "juniper" :seon.cluster/name "trial"
                                    :seon.ns/name 'my.agents.juniper})
           (fixture/install! handle routing)
           (is (string? (:seon.turn/id (turn/system-turn
                                       {:seon.turn.loop/cluster handle :seon.agent/id "juniper"
                                        :seon.turn/write? true}))))
           (let [initial @connection
                 admitted (check! initial)]
             (is (seq (:seon.trial/expected-sources admitted)))
             (is (= 30 (:seon.trial/turns-left admitted)))
             ; A senderless inbox message must count; joining its sender would hide it.
             (let [report (db/transact! connection [{:seon.message/id "trial/pollution"
                                                     :seon.message/to [:seon.agent/id "juniper"]
                                                     :seon.message/content "Extra instruction"
                                                     :seon.message/inbox [:seon.agent/id "juniper"]}])]
               (is (nil? (:seon.error/kind report)) (pr-str report)))
             (is (thrown-with-msg? clojure.lang.ExceptionInfo #"initial fixture" (check! @connection)))
             (is (= admitted (check! initial)) "an advancing connection cannot change the checked value")
             (db/transact! connection [[:db.fn/retractEntity [:seon.message/id "trial/pollution"]]
                                      [:db/add [:example/order "a1"] :example/amount 61]])
             (is (thrown-with-msg? clojure.lang.ExceptionInfo #"initial fixture" (check! @connection)))
             (db/transact! connection [[:db/add [:example/order "a1"] :example/amount 60]
                                      [:db/add (:db/id (first (:seon.trial/evaluations admitted)))
                                       :seon.cluster.eval/source "(+ 1 2)"]])
             (is (thrown-with-msg? clojure.lang.ExceptionInfo #"initial fixture" (check! @connection))))))))))

(def answers
  (str ";; 1. Write thinking comments before each form.\n"
       ";; 2. A value or error arrives in the next turn.\n"
       ";; 3. Use the result/e... symbol as an argument or with get-in.\n"
       ";; 4. Ask for doc or dir when unsure.\n"
       ";; 5. Query the orders to read ids, customers, and amounts.\n"
       ";; 6. Each reply is a turn; my.agent/done ends the session; 30 turns remain.\n"
       ";; 7. No. Wait until the next turn and the result has been seen.\n"))

(deftest trial-scores-actual-forms-and-fails-on-absence
  (support/with-database
   (fn [connection]
     (let [ctx (support/fork-cluster-ctx connection)
           score #((resolve 'help-trial-2026-09-09/score) @connection ctx % 30)
           query "(seon.db/q '[:find ?order :where [?order :example/order]])"
           good (score (str answers ";; I should read the orders.\n" query))]
       (is (= 12 (:seon.trial/passed good)) (pr-str good))
       (is (empty? (:seon.trial/failed good)))
       (let [trial (edn/read-string
                    (slurp "docs/prds/context-generation/research/help_trial_run7_wave_2_2026_09_15.edn"))
             reply (get-in trial [:seon.trial/completion :seon.ai/text])]
         (is (= 12 (:seon.trial/passed (score reply)))
             "the actual trial's answer continues after each numbered question header"))
       (is (= 12 (:seon.trial/passed (score (str answers query "\n;; The prompt draws => itself.\n(defn increment {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 1))")))))
       (is (= 12 (:seon.trial/passed (score (str answers "(doc seon.db/q)")))))
       (doseq [[reply expected]
               [["" :right-function]
                [(str answers query "\n(my.plan/complete! \"juniper/read\")") :no-premature-complete]
                [(str answers query "\n(my.plan/complete! \"juniper/read\")") :argument-shapes]
                [(str answers query "\n(my.message/send {:to \"root\" :content \"hi\"})") :argument-shapes]
                [(str answers "my.agents.juniper=> " query) :no-prompt-marker]
                [(str answers query "\n(my.plan/invented!)") :syntax]
                [(str answers "```clojure\n" query "\n```") :syntax]
                [(str answers "#=(+ 1 1)") :syntax]]]
         (is (some #{expected} (:seon.trial/failed (score reply))) (pr-str (score reply))))))))
